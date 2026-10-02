package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

const (
	dialTimeoutMs        = 5_000
	pairResponseBytesMax = 4 << 10
	userAgent            = "bootleg-cli/1"
	// helloRequestID is hello's id; later requests count up from 1.
	helloRequestID = 0
)

// tailnetPrefixes are Tailscale's address ranges. Without a token file the
// CLI only talks to a host that resolves into them, so a hijacked name on
// the LAN can't receive commands. Tests widen it.
var tailnetPrefixes = []netip.Prefix{
	netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("fd7a:115c:a1e0::/48"),
}

type connectOptions struct {
	host  string // host or host:port
	token string // bearer token for the hotspot; empty on the tailnet
}

// pinnedHTTPClient resolves host once and always dials that address. It
// never uses a proxy and never follows redirects.
func pinnedHTTPClient(ctx context.Context, options connectOptions) (*http.Client, error) {
	hostname, port, err := splitHostPort(options.host)
	if err != nil {
		return nil, usageErrorf("invalid --host %q: %v", options.host, err)
	}
	addresses, err := net.DefaultResolver.LookupNetIP(ctx, "ip", hostname)
	if err != nil {
		return nil, &deviceError{fmt.Errorf("resolve %s: %w (is Tailscale up on this computer?)", hostname, err)}
	}
	var pinned netip.Addr
	for _, address := range addresses {
		address = address.Unmap()
		if options.token != "" || inTailnet(address) {
			pinned = address
			break
		}
	}
	if !pinned.IsValid() {
		return nil, &deviceError{fmt.Errorf("%s resolves to %v, which is not a tailnet address; use --token-file for the offline hotspot", hostname, addresses)}
	}
	target := netip.AddrPortFrom(pinned, port).String()
	dialer := &net.Dialer{Timeout: dialTimeoutMs * time.Millisecond}
	transport := &http.Transport{
		Proxy: nil,
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return dialer.DialContext(ctx, network, target)
		},
		DisableCompression: true,
	}
	return &http.Client{
		Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return errors.New("refusing to follow a redirect")
		},
	}, nil
}

func inTailnet(address netip.Addr) bool {
	for _, prefix := range tailnetPrefixes {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}

func splitHostPort(host string) (string, uint16, error) {
	if host == "" {
		return "", 0, errors.New("empty host")
	}
	name, portText, err := net.SplitHostPort(host)
	if err != nil {
		// No port: the default HTTP port. Bracketed IPv6 without a port too.
		return strings.TrimSuffix(strings.TrimPrefix(host, "["), "]"), 80, nil
	}
	port, err := strconv.ParseUint(portText, 10, 16)
	if err != nil || port == 0 {
		return "", 0, fmt.Errorf("invalid port %q", portText)
	}
	return name, uint16(port), nil
}

// client is one WebSocket session.
type client struct {
	conn    *websocket.Conn
	welcome protocol.Welcome

	mutex    sync.Mutex
	nextID   int64
	waiting  map[int64]chan protocol.Response
	lease    protocol.Lease
	auth     string
	state    json.RawMessage
	changed  chan struct{} // pulsed on every message
	closed   chan struct{}
	closeErr error
	helloErr error

	onPush func(messageType string, raw []byte)
	// takeovers receives takeover requests and withdrawals once a
	// long-running command asks for them (takeoverEvents); until then every
	// request is declined at once so the requester isn't left waiting.
	takeovers chan takeoverEvent
}

// takeoverEvent is a lease.takeover_request (cancelled false) or a
// lease.takeover_cancelled push.
type takeoverEvent struct {
	request   protocol.TakeoverRequest
	cancelled bool
}

const (
	takeoverEventsBufferLength = 8
	takeoverReplyTimeout       = 5 * time.Second
)

func connect(ctx context.Context, options connectOptions) (*client, error) {
	httpClient, err := pinnedHTTPClient(ctx, options)
	if err != nil {
		return nil, err
	}
	header := http.Header{"User-Agent": {userAgent}}
	if options.token != "" {
		header.Set("Authorization", "Bearer "+options.token)
	}
	conn, response, err := websocket.Dial(ctx, "ws://"+options.host+"/ws", &websocket.DialOptions{HTTPClient: httpClient, HTTPHeader: header})
	if err != nil {
		if response != nil {
			return nil, &httpStatusError{status: response.StatusCode}
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, &deviceError{fmt.Errorf("connect to %s: %w", options.host, err)}
	}
	conn.SetReadLimit(protocol.MessageBytesMax)
	created := &client{
		conn:    conn,
		waiting: map[int64]chan protocol.Response{},
		changed: make(chan struct{}, 1),
		closed:  make(chan struct{}),
	}
	go created.readLoop()
	if err := created.hello(ctx); err != nil {
		created.close()
		return nil, err
	}
	return created, nil
}

func (c *client) hello(ctx context.Context) error {
	if err := c.writeJSON(ctx, map[string]any{"id": helloRequestID, "t": protocol.TypeHello, "v": protocol.Version}); err != nil {
		return err
	}
	for {
		c.mutex.Lock()
		welcomed, helloErr := c.welcome.T == protocol.TypeWelcome, c.helloErr
		c.mutex.Unlock()
		if helloErr != nil {
			return helloErr
		}
		if welcomed {
			return nil
		}
		if err := c.waitChange(ctx); err != nil {
			return err
		}
	}
}

func (c *client) readLoop() {
	defer close(c.closed)
	for {
		messageType, data, err := c.conn.Read(context.Background())
		if err != nil {
			c.mutex.Lock()
			c.closeErr = &closeError{code: int(websocket.CloseStatus(err)), reason: closeReason(err), err: err}
			for _, waiter := range c.waiting {
				close(waiter)
			}
			c.waiting = map[int64]chan protocol.Response{}
			c.mutex.Unlock()
			return
		}
		if messageType == websocket.MessageText {
			c.handle(data)
		}
	}
}

func closeReason(err error) string {
	var closeErr websocket.CloseError
	if errors.As(err, &closeErr) {
		return closeErr.Reason
	}
	return ""
}

func (c *client) handle(data []byte) {
	var head struct {
		ID    *int64          `json:"id"`
		T     string          `json:"t"`
		OK    *bool           `json:"ok"`
		Auth  string          `json:"auth"`
		Lease *protocol.Lease `json:"lease"`
		State json.RawMessage `json:"state"`
	}
	if err := json.Unmarshal(data, &head); err != nil {
		return
	}
	c.mutex.Lock()
	if head.Lease != nil {
		c.lease = *head.Lease
	}
	switch head.T {
	case protocol.TypeWelcome:
		json.Unmarshal(data, &c.welcome)
		c.auth = c.welcome.Auth
	case protocol.TypeAuth:
		c.auth = head.Auth
	case protocol.TypeState:
		c.state = append(json.RawMessage(nil), data...)
	}
	if head.State != nil {
		c.state = append(json.RawMessage(nil), head.State...)
	}
	var waiter chan protocol.Response
	if head.OK != nil && !*head.OK && head.ID != nil && *head.ID == helloRequestID && head.T == "" {
		var response protocol.Response
		json.Unmarshal(data, &response)
		c.helloErr = &protocolError{protocol.ErrorBody{Code: "unknown"}}
		if response.Error != nil {
			c.helloErr = &protocolError{*response.Error}
		}
	}
	if head.OK != nil && head.ID != nil && head.T == "" {
		waiter = c.waiting[*head.ID]
		delete(c.waiting, *head.ID)
	}
	onPush := c.onPush
	takeovers := c.takeovers
	c.mutex.Unlock()

	// Never block the read loop: replies to the phone go out on their own
	// goroutine, and events are handed over without waiting.
	if head.T == protocol.TypeLeaseTakeoverRequest || head.T == protocol.TypeLeaseTakeoverCancelled {
		event := takeoverEvent{cancelled: head.T == protocol.TypeLeaseTakeoverCancelled}
		json.Unmarshal(data, &event.request)
		delivered := false
		if takeovers != nil {
			select {
			case takeovers <- event:
				delivered = true
			default:
			}
		}
		if !delivered && !event.cancelled {
			go c.replyTakeover(false)
		}
	}

	if waiter != nil {
		var response protocol.Response
		json.Unmarshal(data, &response)
		response.Raw = append(json.RawMessage(nil), data...)
		waiter <- response
	} else if head.T != "" && onPush != nil {
		onPush(head.T, data)
	}
	select {
	case c.changed <- struct{}{}:
	default:
	}
}

// waitChange blocks until a message arrives, the session ends or ctx ends.
func (c *client) waitChange(ctx context.Context) error {
	select {
	case <-c.changed:
		return nil
	case <-c.closed:
		return c.err()
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (c *client) err() error {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	if c.closeErr != nil {
		return c.closeErr
	}
	return &deviceError{errors.New("connection closed")}
}

func (c *client) writeJSON(ctx context.Context, message any) error {
	encoded, err := json.Marshal(message)
	if err != nil {
		return err
	}
	if err := c.conn.Write(ctx, websocket.MessageText, encoded); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return c.err()
	}
	return nil
}

// request sends {id, t, ...fields} and waits for the matching reply. A reply
// with ok:false comes back as a *protocolError.
func (c *client) request(ctx context.Context, messageType string, fields map[string]any) (protocol.Response, error) {
	c.mutex.Lock()
	c.nextID++
	id := c.nextID
	waiter := make(chan protocol.Response, 1)
	c.waiting[id] = waiter
	c.mutex.Unlock()
	message := map[string]any{}
	for key, value := range fields {
		message[key] = value
	}
	message["id"] = id
	message["t"] = messageType
	if err := c.writeJSON(ctx, message); err != nil {
		c.forget(id)
		return protocol.Response{}, err
	}
	select {
	case response, ok := <-waiter:
		if !ok {
			return protocol.Response{}, c.err()
		}
		if !response.OK {
			failure := protocol.ErrorBody{Code: "unknown"}
			if response.Error != nil {
				failure = *response.Error
			}
			return response, &protocolError{failure}
		}
		return response, nil
	case <-ctx.Done():
		c.forget(id)
		return protocol.Response{}, ctx.Err()
	}
}

// mutate is request with the current lease epoch attached.
func (c *client) mutate(ctx context.Context, messageType string, fields map[string]any) (protocol.Response, error) {
	if fields == nil {
		fields = map[string]any{}
	}
	fields["epoch"] = c.currentLease().Epoch
	return c.request(ctx, messageType, fields)
}

// takeoverEvents switches the session from declining takeover requests at
// once to delivering them (and their withdrawals) on the returned channel.
func (c *client) takeoverEvents() <-chan takeoverEvent {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	if c.takeovers == nil {
		c.takeovers = make(chan takeoverEvent, takeoverEventsBufferLength)
	}
	return c.takeovers
}

// replyTakeover answers a takeover request with the current epoch. It blocks
// until the phone replies, so never call it on the read loop.
func (c *client) replyTakeover(allow bool) error {
	ctx, cancel := context.WithTimeout(context.Background(), takeoverReplyTimeout)
	defer cancel()
	_, err := c.mutate(ctx, protocol.TypeLeaseTakeoverReply, map[string]any{"allow": allow})
	return err
}

func (c *client) forget(id int64) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	delete(c.waiting, id)
}

func (c *client) frame(ctx context.Context, frame protocol.Frame) error {
	if err := c.conn.Write(ctx, websocket.MessageBinary, frame.Encode()); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return c.err()
	}
	return nil
}

func (c *client) currentLease() protocol.Lease {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	return c.lease
}

func (c *client) sessionID() int64 {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	return c.welcome.SessionID
}

func (c *client) currentAuth() string {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	return c.auth
}

func (c *client) holdsLease() bool {
	lease := c.currentLease()
	return lease.Holder != nil && *lease.Holder == c.sessionID()
}

// waitFor blocks until condition holds (checked after every message).
func (c *client) waitFor(ctx context.Context, condition func() bool) error {
	for !condition() {
		if err := c.waitChange(ctx); err != nil {
			return err
		}
	}
	return nil
}

// fetchState asks for a snapshot and decodes it.
func (c *client) fetchState(ctx context.Context) (protocol.State, json.RawMessage, error) {
	response, err := c.request(ctx, protocol.TypeStateGet, nil)
	if err != nil {
		return protocol.State{}, nil, err
	}
	var wrapper struct {
		State json.RawMessage `json:"state"`
	}
	json.Unmarshal(response.Raw, &wrapper)
	raw := wrapper.State
	if raw == nil {
		raw = response.Raw
	}
	var state protocol.State
	if err := json.Unmarshal(raw, &state); err != nil {
		return protocol.State{}, nil, &deviceError{fmt.Errorf("unexpected state: %w", err)}
	}
	if state.Lease.Epoch != 0 || state.Lease.Holder != nil {
		c.mutex.Lock()
		c.lease = state.Lease
		c.mutex.Unlock()
	}
	return state, raw, nil
}

func (c *client) close() {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	done := make(chan struct{})
	go func() {
		c.conn.Close(websocket.StatusNormalClosure, "")
		close(done)
	}()
	select {
	case <-done:
	case <-ctx.Done():
		c.conn.CloseNow()
	}
}

// pair exchanges a code for a token on the hotspot. No Origin is sent: this
// is the CLI bootstrap path.
func pair(ctx context.Context, host, code string) (string, error) {
	httpClient, err := pinnedHTTPClient(ctx, connectOptions{host: host, token: "pairing"})
	if err != nil {
		return "", err
	}
	body, _ := json.Marshal(map[string]string{"code": code})
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, "http://"+host+"/pair", bytes.NewReader(body))
	if err != nil {
		return "", usageErrorf("invalid host: %v", err)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("User-Agent", userAgent)
	response, err := httpClient.Do(request)
	if err != nil {
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		return "", &deviceError{err}
	}
	defer response.Body.Close()
	var reply struct {
		OK    bool                `json:"ok"`
		Token string              `json:"token"`
		Error *protocol.ErrorBody `json:"error"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, pairResponseBytesMax)).Decode(&reply); err != nil {
		return "", &httpStatusError{status: response.StatusCode}
	}
	if !reply.OK {
		if reply.Error != nil {
			return "", &protocolError{*reply.Error}
		}
		return "", &httpStatusError{status: response.StatusCode}
	}
	if len(reply.Token) != protocol.TokenEncodedLength {
		return "", &deviceError{errors.New("the phone returned a malformed token")}
	}
	return reply.Token, nil
}
