// Package transport is the Go half of Bootleg's remote control: HTTP and
// WebSocket serving, Host/Origin checks, credentials bound to the accepted
// connection, size/rate/connection limits, frame validation and liveness.
// It holds no authorization or lease state. Every decision goes to Kotlin
// through Bridge and comes back through Authorized/Send/Close/Revoke.
package transport

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

// Bridge is the Kotlin side. Every method must only enqueue and return
// quickly; Go calls them from connection goroutines and never holds a lock
// across a call. For one session the calls arrive in order: Open, then
// Command/Hid, then exactly one Closed.
type Bridge interface {
	Open(sessionID int64, connJSON string)
	Command(sessionID int64, messageJSON string)
	Hid(sessionID int64, frame []byte) int32
	Closed(sessionID int64, reason string)
}

// Hid results.
const (
	HidQueued    int32 = 0
	HidQueueFull int32 = 1
)

const (
	TransportTailnet = "tailnet"
	TransportHotspot = "hotspot"
)

// Identity is who a connection is, as bound by the listener: a tailnet node
// (from WhoIs on the accepted connection) or a hotspot token (only its
// SHA-256 crosses to Kotlin). Names are untrusted display text.
type Identity struct {
	Kind      string   `json:"kind"`
	NodeID    string   `json:"nodeId,omitempty"`
	User      string   `json:"user,omitempty"`
	Tags      []string `json:"tags,omitempty"`
	Name      string   `json:"name,omitempty"`
	TokenHash string   `json:"tokenHash,omitempty"`
}

const (
	IdentityNode  = "node"
	IdentityToken = "token"
	IdentityPair  = "pair"
)

// RevokeKind values for Revoke.
const (
	RevokeNode  = "node"
	RevokeToken = "token"
)

// Endpoint describes one listener.
type Endpoint struct {
	Transport string
	// AllowedHosts returns the exact Host header values clients may use
	// (host, or host:port). Checked on every request.
	AllowedHosts func() []string
	// Identify resolves a tailnet peer from the accepted connection's remote
	// address. Required for tailnet, unused on the hotspot.
	Identify func(ctx context.Context, remoteAddress string) (Identity, error)
	// Ingress, if set, vets each accepted connection's addresses before any
	// byte is read (the hotspot check).
	Ingress func(local, remote net.Addr) error
}

type Config struct {
	PingInterval time.Duration
	DeadAfter    time.Duration
	// LivenessCheckInterval is how often sessions are checked for a missed
	// pong; it bounds how late after DeadAfter a dead peer is noticed.
	LivenessCheckInterval time.Duration
	PairTimeout           time.Duration
	WriteTimeout          time.Duration
	IdentifyTimeout       time.Duration
	// PairBodyTimeout bounds reading a /pair request body, so a client
	// trickling one can't hold a connection slot.
	PairBodyTimeout time.Duration
	// Now is the clock for rate limits and liveness.
	Now func() time.Time
	// Static is served at / (the web UI).
	Static fs.FS
}

const (
	pairTimeoutMs            = 5_000
	pairBodyTimeoutMs        = 5_000
	writeTimeoutMs           = 5_000
	identifyTimeoutMs        = 5_000
	livenessCheckIntervalMs  = 1_000
	shutdownTimeoutMs        = 3_000
	tcpConnectionCountMax    = 32
	httpReadHeaderTimeoutMs  = 10_000
	httpIdleTimeoutMs        = 60_000
	httpHeaderBytesMax       = 16 << 10
	userAgentLengthMax       = 200
	tokenCookieName          = "bootleg_token"
	tokenCookieMaxAgeSeconds = 90 * 24 * 60 * 60
)

func DefaultConfig(static fs.FS) Config {
	return Config{
		PingInterval:          protocol.PingIntervalMs * time.Millisecond,
		DeadAfter:             protocol.DeadAfterMs * time.Millisecond,
		LivenessCheckInterval: livenessCheckIntervalMs * time.Millisecond,
		PairTimeout:           pairTimeoutMs * time.Millisecond,
		PairBodyTimeout:       pairBodyTimeoutMs * time.Millisecond,
		WriteTimeout:          writeTimeoutMs * time.Millisecond,
		IdentifyTimeout:       identifyTimeoutMs * time.Millisecond,
		Now:                   time.Now,
		Static:                static,
	}
}

// Server owns the sessions of every listener.
type Server struct {
	config  Config
	bridge  Bridge
	handler http.Handler

	mutex    sync.Mutex
	sessions map[int64]*session
	nextID   int64
}

func New(bridge Bridge, config Config) *Server {
	if bridge == nil {
		panic("transport: nil bridge")
	}
	if config.PingInterval <= 0 || config.DeadAfter <= config.PingInterval || config.LivenessCheckInterval <= 0 ||
		config.PairTimeout <= 0 || config.PairBodyTimeout <= 0 || config.WriteTimeout <= 0 || config.IdentifyTimeout <= 0 || config.Now == nil {
		panic(fmt.Sprintf("transport: invalid config %+v", config))
	}
	server := &Server{config: config, bridge: bridge, sessions: map[int64]*session{}}
	server.handler = server.routes()
	return server
}

// Listener is one served address.
type Listener struct {
	server   *Server
	endpoint Endpoint
	inner    *trackingListener
	http     *http.Server
	served   chan struct{}
	sessions sync.WaitGroup
	stopOnce sync.Once
	stopping bool // guarded by Server.mutex
}

type listenerKey struct{}

// Serve starts serving endpoint on listener in the background.
func (s *Server) Serve(listener net.Listener, endpoint Endpoint) *Listener {
	if endpoint.Transport != TransportTailnet && endpoint.Transport != TransportHotspot {
		panic(fmt.Sprintf("transport: unknown transport %q", endpoint.Transport))
	}
	if endpoint.AllowedHosts == nil || (endpoint.Transport == TransportTailnet && endpoint.Identify == nil) {
		panic("transport: endpoint needs AllowedHosts, and Identify on the tailnet")
	}
	served := &Listener{server: s, endpoint: endpoint, served: make(chan struct{})}
	served.inner = newTrackingListener(listener, tcpConnectionCountMax, endpoint.Ingress, func(err error) {
		log.Printf("bootleg transport: %s: rejected connection: %v", endpoint.Transport, err)
	})
	served.http = &http.Server{
		Handler:           s.handler,
		ReadHeaderTimeout: httpReadHeaderTimeoutMs * time.Millisecond,
		IdleTimeout:       httpIdleTimeoutMs * time.Millisecond,
		MaxHeaderBytes:    httpHeaderBytesMax,
		ConnContext: func(ctx context.Context, _ net.Conn) context.Context {
			return context.WithValue(ctx, listenerKey{}, served)
		},
		ErrorLog: log.New(discardWriter{}, "", 0),
	}
	go func() {
		defer close(served.served)
		if err := served.http.Serve(served.inner); err != nil && !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			log.Printf("bootleg transport: %s serve: %v", endpoint.Transport, err)
		}
	}()
	return served
}

type discardWriter struct{}

func (discardWriter) Write(data []byte) (int, error) { return len(data), nil }

// Addr is the bound address.
func (l *Listener) Addr() net.Addr { return l.inner.Addr() }

// Stop closes the listener, ends its sessions (each gets Closed with
// listener_stopped) and returns once every Closed callback has run.
func (l *Listener) Stop() {
	l.stopOnce.Do(func() {
		l.server.mutex.Lock()
		l.stopping = true
		l.server.mutex.Unlock()
		for _, active := range l.server.sessionsOf(l) {
			active.close(protocol.ReasonListenerStopped, false)
		}
		l.inner.Listener.Close()
		l.inner.closeAll()
		ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeoutMs*time.Millisecond)
		defer cancel()
		if err := l.http.Shutdown(ctx); err != nil {
			log.Printf("bootleg transport: %s shutdown: %v", l.endpoint.Transport, err)
		}
		<-l.served
		l.sessions.Wait()
	})
}

func (s *Server) sessionsOf(listener *Listener) []*session {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	var result []*session
	for _, active := range s.sessions {
		if active.listener == listener {
			result = append(result, active)
		}
	}
	return result
}

var (
	errCapacity = errors.New("too many connections")
	errStopping = errors.New("listener stopping")
)

// register reserves a session slot, enforcing the total and pending caps.
func (s *Server) register(listener *Listener, identity Identity, isPair bool) (*session, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if listener.stopping {
		return nil, errStopping
	}
	pendingCount := 0
	for _, active := range s.sessions {
		if !active.authorized.Load() {
			pendingCount++
		}
	}
	if len(s.sessions) >= protocol.ConnectionCountMax || pendingCount >= protocol.PendingConnectionCountMax {
		return nil, errCapacity
	}
	s.nextID++
	created := newSession(s, listener, s.nextID, identity, isPair)
	s.sessions[created.id] = created
	listener.sessions.Add(1)
	return created, nil
}

func (s *Server) unregister(ended *session) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if s.sessions[ended.id] == ended {
		delete(s.sessions, ended.id)
	}
}

func (s *Server) lookup(sessionID int64) *session {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return s.sessions[sessionID]
}

// Authorized lets the session's requests and HID frames through to Kotlin.
// It returns false if the session is gone.
func (s *Server) Authorized(sessionID int64) bool {
	target := s.lookup(sessionID)
	if target == nil || target.pair != nil || target.closing.Load() {
		return false
	}
	target.authorized.Store(true)
	return true
}

// Send queues one JSON message for the session. It returns false if the
// session is gone or closing, or if the message is invalid. A full outbound
// queue (OutboundQueueLengthMax) means a slow consumer: the session is
// closed.
func (s *Server) Send(sessionID int64, messageJSON string) bool {
	if len(messageJSON) > protocol.MessageBytesMax || !json.Valid([]byte(messageJSON)) {
		log.Printf("bootleg transport: dropping invalid outbound message for session %d (%d bytes)", sessionID, len(messageJSON))
		return false
	}
	target := s.lookup(sessionID)
	if target == nil {
		return false
	}
	return target.send([]byte(messageJSON))
}

// Close ends the session with reason (sent to the client in the close
// frame), after flushing messages already queued.
func (s *Server) Close(sessionID int64, reason string) bool {
	target := s.lookup(sessionID)
	if target == nil {
		return false
	}
	target.close(sanitizeReason(reason), true)
	return true
}

// Revoke closes every session of a tailnet node (kind "node", id = stable
// node ID) or hotspot token (kind "token", id = token SHA-256 hex). It
// returns how many sessions it closed.
func (s *Server) Revoke(kind, id string) int {
	if id == "" || (kind != RevokeNode && kind != RevokeToken) {
		return 0
	}
	s.mutex.Lock()
	var matched []*session
	for _, active := range s.sessions {
		identity := active.identity
		if (kind == RevokeNode && identity.Kind == IdentityNode && identity.NodeID == id) ||
			(kind == RevokeToken && identity.Kind == IdentityToken && identity.TokenHash == id) {
			matched = append(matched, active)
		}
	}
	s.mutex.Unlock()
	for _, active := range matched {
		active.close(protocol.ReasonRevoked, true)
	}
	return len(matched)
}

// SessionCount reports live sessions (for tests and diagnostics).
func (s *Server) SessionCount() int {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return len(s.sessions)
}

func sanitizeReason(reason string) string {
	if !utf8.ValidString(reason) {
		return "closed"
	}
	if len(reason) > protocol.CloseReasonBytesMax {
		reason = reason[:protocol.CloseReasonBytesMax]
		for !utf8.ValidString(reason) {
			reason = reason[:len(reason)-1]
		}
	}
	if reason == "" {
		return "closed"
	}
	return reason
}
