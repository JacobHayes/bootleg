package transport

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"testing/fstest"
	"time"

	"github.com/coder/websocket"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

// fakeKotlin is a Bridge that records every call. Hooks run synchronously
// inside the callback, so a hook that calls back into the Server would
// deadlock if Go held a lock across callbacks.
type fakeKotlin struct {
	mutex     sync.Mutex
	opens     map[int64]string
	commands  map[int64][]string
	frames    map[int64][][]byte
	closed    map[int64][]string
	hidResult int32
	changed   chan struct{}

	onOpen    func(id int64, connJSON string)
	onCommand func(id int64, message string)
	onClosed  func(id int64, reason string)
}

func newFakeKotlin() *fakeKotlin {
	return &fakeKotlin{
		opens:    map[int64]string{},
		commands: map[int64][]string{},
		frames:   map[int64][][]byte{},
		closed:   map[int64][]string{},
		changed:  make(chan struct{}, 1),
	}
}

func (k *fakeKotlin) notify() {
	select {
	case k.changed <- struct{}{}:
	default:
	}
}

func (k *fakeKotlin) Open(id int64, connJSON string) {
	k.mutex.Lock()
	k.opens[id] = connJSON
	hook := k.onOpen
	k.mutex.Unlock()
	if hook != nil {
		hook(id, connJSON)
	}
	k.notify()
}

func (k *fakeKotlin) Command(id int64, message string) {
	k.mutex.Lock()
	k.commands[id] = append(k.commands[id], message)
	hook := k.onCommand
	k.mutex.Unlock()
	if hook != nil {
		hook(id, message)
	}
	k.notify()
}

func (k *fakeKotlin) Hid(id int64, frame []byte) int32 {
	k.mutex.Lock()
	defer k.mutex.Unlock()
	k.frames[id] = append(k.frames[id], append([]byte(nil), frame...))
	k.notify()
	return k.hidResult
}

func (k *fakeKotlin) Closed(id int64, reason string) {
	k.mutex.Lock()
	k.closed[id] = append(k.closed[id], reason)
	hook := k.onClosed
	k.mutex.Unlock()
	if hook != nil {
		hook(id, reason)
	}
	k.notify()
}

func (k *fakeKotlin) snapshot(read func(*fakeKotlin)) {
	k.mutex.Lock()
	defer k.mutex.Unlock()
	read(k)
}

func (k *fakeKotlin) openIDs() []int64 {
	var ids []int64
	k.snapshot(func(k *fakeKotlin) {
		for id := range k.opens {
			ids = append(ids, id)
		}
	})
	return ids
}

func (k *fakeKotlin) commandsOf(id int64) []string {
	var result []string
	k.snapshot(func(k *fakeKotlin) { result = append(result, k.commands[id]...) })
	return result
}

func (k *fakeKotlin) framesOf(id int64) [][]byte {
	var result [][]byte
	k.snapshot(func(k *fakeKotlin) { result = append(result, k.frames[id]...) })
	return result
}

func (k *fakeKotlin) closedOf(id int64) []string {
	var result []string
	k.snapshot(func(k *fakeKotlin) { result = append(result, k.closed[id]...) })
	return result
}

// eventually polls condition for up to 3s.
func eventually(t *testing.T, what string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !condition() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(2 * time.Millisecond)
	}
}

type harness struct {
	t        *testing.T
	server   *Server
	listener *Listener
	kotlin   *fakeKotlin
	host     string
}

func testConfig() Config {
	config := DefaultConfig(fstest.MapFS{"index.html": {Data: []byte("<!doctype html><title>Bootleg</title>")}})
	config.PairTimeout = 200 * time.Millisecond
	return config
}

const testNodeID = "nTESTNODE"

func newHarness(t *testing.T, transport string, configure func(*Config, *Endpoint)) *harness {
	t.Helper()
	inner, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	host := inner.Addr().String()
	config := testConfig()
	endpoint := Endpoint{
		Transport:    transport,
		AllowedHosts: func() []string { return []string{host} },
	}
	if transport == TransportTailnet {
		endpoint.Identify = func(context.Context, string) (Identity, error) {
			return Identity{Kind: IdentityNode, NodeID: testNodeID, Name: "laptop", User: "user@example.com"}, nil
		}
	}
	if configure != nil {
		configure(&config, &endpoint)
	}
	kotlin := newFakeKotlin()
	server := New(kotlin, config)
	listener := server.Serve(inner, endpoint)
	t.Cleanup(listener.Stop)
	return &harness{t: t, server: server, listener: listener, kotlin: kotlin, host: host}
}

func (h *harness) url(path string) string { return "http://" + h.host + path }

type dialOptions struct {
	origin string
	cookie string
	bearer string
	host   string
}

func (h *harness) dial(options dialOptions) (*websocket.Conn, *http.Response, error) {
	header := http.Header{}
	if options.origin != "" {
		header.Set("Origin", options.origin)
	}
	if options.cookie != "" {
		header.Set("Cookie", tokenCookieName+"="+options.cookie)
	}
	if options.bearer != "" {
		header.Set("Authorization", "Bearer "+options.bearer)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, response, err := websocket.Dial(ctx, "ws://"+h.host+"/ws", &websocket.DialOptions{HTTPHeader: header, Host: options.host})
	if conn != nil {
		conn.SetReadLimit(protocol.MessageBytesMax * 2)
		h.t.Cleanup(func() { conn.CloseNow() })
	}
	return conn, response, err
}

// mustDial opens a WebSocket and returns it with the session id Kotlin saw.
func (h *harness) mustDial(options dialOptions) (*websocket.Conn, int64) {
	h.t.Helper()
	before := len(h.kotlin.openIDs())
	conn, response, err := h.dial(options)
	if err != nil {
		status := 0
		if response != nil {
			status = response.StatusCode
		}
		h.t.Fatalf("dial failed (HTTP %d): %v", status, err)
	}
	var id int64
	eventually(h.t, "Open", func() bool {
		ids := h.kotlin.openIDs()
		if len(ids) <= before {
			return false
		}
		for _, candidate := range ids {
			id = max(id, candidate)
		}
		return true
	})
	return conn, id
}

func writeJSON(t *testing.T, conn *websocket.Conn, message string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := conn.Write(ctx, websocket.MessageText, []byte(message)); err != nil {
		t.Fatalf("write %s: %v", message, err)
	}
}

func writeBinary(t *testing.T, conn *websocket.Conn, frame []byte) error {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	return conn.Write(ctx, websocket.MessageBinary, frame)
}

func readJSON(t *testing.T, conn *websocket.Conn) map[string]any {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("decode %s: %v", data, err)
	}
	return decoded
}

// readUntilClosed reads until the server closes and returns the close code.
func readUntilClosed(t *testing.T, conn *websocket.Conn) websocket.StatusCode {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for {
		_, _, err := conn.Read(ctx)
		if err != nil {
			return websocket.CloseStatus(err)
		}
	}
}

func hello() string { return `{"id":1,"t":"hello","v":1}` }

func errorCode(message map[string]any) string {
	body, _ := message["error"].(map[string]any)
	code, _ := body["code"].(string)
	return code
}

func containsString(values []string, want string) bool {
	for _, value := range values {
		if strings.Contains(value, want) {
			return true
		}
	}
	return false
}
