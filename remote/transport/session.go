package transport

import (
	"bytes"
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

type session struct {
	server   *Server
	listener *Listener
	id       int64
	identity Identity
	// pair is set for a hotspot /pair exchange, which has no socket: the
	// first Send for the session is the pairing verdict.
	pair chan []byte

	conn       atomic.Pointer[websocket.Conn]
	authorized atomic.Bool
	closing    atomic.Bool
	closeOnce  sync.Once
	// reason and graceful are written inside closeOnce and read only after
	// closeRequested is closed or after a later closeOnce.Do returned.
	reason         string
	graceful       bool
	closeRequested chan struct{}
	outbound       chan []byte

	lastPongUnixNano atomic.Int64
	pingInFlight     atomic.Bool

	// Reader-goroutine state.
	helloForwarded bool
	lastReleaseAll []byte
	hidBucket      *tokenBucket
	requestBucket  *tokenBucket
}

func newSession(server *Server, listener *Listener, id int64, identity Identity, isPair bool) *session {
	now := server.config.Now()
	created := &session{
		server:         server,
		listener:       listener,
		id:             id,
		identity:       identity,
		closeRequested: make(chan struct{}),
		outbound:       make(chan []byte, protocol.OutboundQueueLengthMax),
		hidBucket:      newTokenBucket(protocol.HidFramesPerSecondMax, protocol.HidFramesPerSecondMax, now),
		requestBucket:  newTokenBucket(protocol.RequestsPerSecondMax, protocol.RequestsPerSecondMax, now),
	}
	if isPair {
		created.pair = make(chan []byte, 1)
	}
	created.lastPongUnixNano.Store(now.UnixNano())
	return created
}

// close starts ending the session; only the first call counts. A graceful
// close flushes queued messages and sends a close frame; otherwise the socket
// is dropped at once (dead peers, slow consumers, stopping listeners).
// Nothing more is forwarded to Kotlin after this.
func (s *session) close(reason string, graceful bool) {
	s.closeOnce.Do(func() {
		s.reason = reason
		s.graceful = graceful
		s.closing.Store(true)
		close(s.closeRequested)
		if conn := s.conn.Load(); conn != nil && !graceful {
			conn.CloseNow()
		}
	})
}

// finalReason must only be called after close has run.
func (s *session) finalReason() string {
	s.close(protocol.ReasonClientClosed, false)
	return s.reason
}

func (s *session) send(message []byte) bool {
	if s.closing.Load() {
		return false
	}
	if s.pair != nil {
		select {
		case s.pair <- message:
			return true
		default:
			return false
		}
	}
	select {
	case s.outbound <- message:
		return true
	default:
		s.close(protocol.ReasonSlowConsumer, false)
		return false
	}
}

// run serves an accepted WebSocket until it ends, then reports Closed. It
// runs on the HTTP handler goroutine.
func (s *session) run(conn *websocket.Conn, connJSON string) {
	server := s.server
	defer s.listener.sessions.Done()
	s.conn.Store(conn)
	if s.closing.Load() {
		// Closed (listener stopping) while the upgrade was in flight.
		conn.CloseNow()
	}
	server.bridge.Open(s.id, connJSON)

	ctx, cancel := context.WithCancel(context.Background())
	var workers sync.WaitGroup
	workers.Add(2)
	go func() {
		defer workers.Done()
		s.writeLoop(conn)
	}()
	go func() {
		defer workers.Done()
		s.livenessLoop(ctx, conn, &workers)
	}()
	s.readLoop(ctx, conn)
	reason := s.finalReason()
	cancel()
	workers.Wait()
	conn.CloseNow()
	server.unregister(s)
	server.bridge.Closed(s.id, reason)
}

func (s *session) readLoop(ctx context.Context, conn *websocket.Conn) {
	for {
		messageType, data, err := conn.Read(ctx)
		if err != nil {
			if errors.Is(err, websocket.ErrMessageTooBig) {
				s.close(protocol.ReasonMalformed, false)
			}
			return
		}
		if s.closing.Load() {
			continue
		}
		switch messageType {
		case websocket.MessageText:
			s.handleText(data)
		case websocket.MessageBinary:
			s.handleBinary(data)
		}
	}
}

func (s *session) handleText(data []byte) {
	now := s.server.config.Now()
	envelope, err := protocol.ParseEnvelope(data)
	if err != nil {
		s.close(protocol.ReasonMalformed, true)
		return
	}
	if !s.requestBucket.allow(now) {
		s.close(protocol.ReasonRateLimited, true)
		return
	}
	if envelope.T == protocol.TypePair {
		// Pairing is Go → Kotlin only; a client must never be able to forge it.
		s.send(protocol.ErrorResponse(envelope.ID, protocol.ErrorBadRequest, "pair is only accepted at POST /pair"))
		return
	}
	if envelope.T == protocol.TypeHello && (envelope.V == nil || *envelope.V != protocol.Version) {
		s.close(protocol.ReasonVersion, true)
		return
	}
	if !s.authorized.Load() {
		// Fail closed: until Kotlin calls Authorized only the first hello goes
		// through.
		if envelope.T != protocol.TypeHello || s.helloForwarded {
			s.send(protocol.ErrorResponse(envelope.ID, protocol.ErrorNotAuthorized, "this device is not approved yet"))
			return
		}
		s.helloForwarded = true
	}
	s.server.bridge.Command(s.id, string(data))
}

func (s *session) handleBinary(data []byte) {
	frame, err := protocol.DecodeFrame(data)
	if err != nil {
		s.close(protocol.ReasonMalformed, true)
		return
	}
	if !s.authorized.Load() {
		return
	}
	if frame.Op == protocol.OpReleaseAll {
		// Exempt from the rate limit; repeats with the same epoch coalesce.
		if bytes.Equal(data, s.lastReleaseAll) {
			return
		}
		s.lastReleaseAll = data
	} else {
		if !s.hidBucket.allow(s.server.config.Now()) {
			s.close(protocol.ReasonRateLimited, true)
			return
		}
		s.lastReleaseAll = nil
	}
	// queue_full is Kotlin's to handle (its overflow path releases all keys);
	// the transport keeps reading so it never stalls the socket.
	s.server.bridge.Hid(s.id, data)
}

func (s *session) writeLoop(conn *websocket.Conn) {
	for {
		select {
		case message := <-s.outbound:
			if err := s.write(conn, message); err != nil {
				s.close(protocol.ReasonWriteFailed, false)
				return
			}
		case <-s.closeRequested:
			if !s.graceful {
				return
			}
			for flushed := 0; flushed < protocol.OutboundQueueLengthMax; flushed++ {
				select {
				case message := <-s.outbound:
					if err := s.write(conn, message); err != nil {
						conn.CloseNow()
						return
					}
					continue
				default:
				}
				break
			}
			conn.Close(websocket.StatusCode(protocol.CloseCodeFor(s.reason)), s.reason)
			return
		}
	}
}

func (s *session) write(conn *websocket.Conn, message []byte) error {
	ctx, cancel := context.WithTimeout(context.Background(), s.server.config.WriteTimeout)
	defer cancel()
	return conn.Write(ctx, websocket.MessageText, message)
}

func (s *session) pong() {
	s.lastPongUnixNano.Store(s.server.config.Now().UnixNano())
}

// livenessLoop pings every PingInterval and declares the peer dead once no
// pong arrived for DeadAfter.
func (s *session) livenessLoop(ctx context.Context, conn *websocket.Conn, workers *sync.WaitGroup) {
	config := s.server.config
	ticker := time.NewTicker(config.LivenessCheckInterval)
	defer ticker.Stop()
	var nextPing time.Time
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		now := config.Now()
		if now.Sub(time.Unix(0, s.lastPongUnixNano.Load())) >= config.DeadAfter {
			s.close(protocol.ReasonDead, false)
			return
		}
		if now.Before(nextPing) || !s.pingInFlight.CompareAndSwap(false, true) {
			continue
		}
		nextPing = now.Add(config.PingInterval)
		workers.Add(1)
		go func() {
			defer workers.Done()
			defer s.pingInFlight.Store(false)
			pingCtx, cancel := context.WithTimeout(ctx, config.DeadAfter)
			defer cancel()
			_ = conn.Ping(pingCtx)
		}()
	}
}
