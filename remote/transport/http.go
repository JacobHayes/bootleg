package transport

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/coder/websocket"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

func (s *Server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /ws", s.handleWebSocket)
	mux.HandleFunc("POST /pair", s.handlePair)
	mux.HandleFunc("GET /api/transport", s.handleTransport)
	mux.HandleFunc("GET /keycodes.json", func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		writer.Write(protocol.KeyCodesJSON)
	})
	if s.config.Static != nil {
		mux.Handle("GET /", http.FileServerFS(s.config.Static))
	}
	return s.guard(mux)
}

// guard applies to every request: the Host must be one this listener
// answers to (no DNS rebinding), and every response carries the security
// headers. There is deliberately no CORS.
func (s *Server) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		listener := listenerFrom(request)
		if listener == nil || !hostAllowed(request.Host, listener.endpoint.AllowedHosts()) {
			http.Error(writer, "unknown host", http.StatusMisdirectedRequest)
			return
		}
		header := writer.Header()
		// connect-src names the ws:// origin explicitly because older Safari
		// doesn't let 'self' cover WebSocket schemes. Host was just checked.
		header.Set("Content-Security-Policy", "default-src 'self'; connect-src 'self' ws://"+request.Host+"; frame-ancestors 'none'")
		header.Set("X-Frame-Options", "DENY")
		header.Set("X-Content-Type-Options", "nosniff")
		header.Set("Referrer-Policy", "no-referrer")
		header.Set("Cross-Origin-Resource-Policy", "same-origin")
		header.Set("Cache-Control", "no-store")
		next.ServeHTTP(writer, request)
	})
}

func listenerFrom(request *http.Request) *Listener {
	listener, _ := request.Context().Value(listenerKey{}).(*Listener)
	return listener
}

// canonicalHost lowercases a host[:port] and drops the default HTTP port.
func canonicalHost(host string) string {
	return strings.TrimSuffix(strings.ToLower(host), ":80")
}

func hostAllowed(host string, allowed []string) bool {
	if host == "" {
		return false
	}
	canonical := canonicalHost(host)
	for _, candidate := range allowed {
		if candidate != "" && canonicalHost(candidate) == canonical {
			return true
		}
	}
	return false
}

// checkOrigin enforces the exact scheme/host/port match. It reports whether
// the request comes from a browser (has an Origin). A missing Origin is
// allowed here; callers then accept only non-browser credentials.
func checkOrigin(header http.Header, host string) (browser bool, err error) {
	values := header.Values("Origin")
	if len(values) == 0 {
		return false, nil
	}
	if len(values) > 1 {
		return true, errors.New("multiple Origin headers")
	}
	origin := values[0]
	if origin == "null" {
		return true, errors.New("opaque origin")
	}
	parsed, err := url.Parse(origin)
	if err != nil {
		return true, fmt.Errorf("unparseable origin: %w", err)
	}
	if parsed.Scheme != "http" || parsed.User != nil || parsed.Opaque != "" || parsed.Path != "" || parsed.RawQuery != "" || parsed.Fragment != "" {
		return true, errors.New("origin must be a bare http://host[:port]")
	}
	if parsed.Host == "" || canonicalHost(parsed.Host) != canonicalHost(host) {
		return true, errors.New("cross-origin request")
	}
	return true, nil
}

func validToken(token string) bool {
	if len(token) != protocol.TokenEncodedLength {
		return false
	}
	_, err := base64.RawURLEncoding.DecodeString(token)
	return err == nil
}

func tokenHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func newToken() (token, hash string) {
	raw := make([]byte, protocol.TokenBytes)
	if _, err := rand.Read(raw); err != nil {
		panic(fmt.Sprintf("transport: no randomness for a token: %v", err))
	}
	token = base64.RawURLEncoding.EncodeToString(raw)
	return token, tokenHash(token)
}

// hotspotToken reads the credential for the request's client type: browsers
// (Origin present) only by cookie, other clients only by bearer header, so a
// cookie can never ride along on a request without an Origin check.
func hotspotToken(request *http.Request, browser bool) (string, bool) {
	var token string
	if browser {
		cookie, err := request.Cookie(tokenCookieName)
		if err != nil {
			return "", false
		}
		token = cookie.Value
	} else {
		value, found := strings.CutPrefix(request.Header.Get("Authorization"), "Bearer ")
		if !found {
			return "", false
		}
		token = value
	}
	return token, validToken(token)
}

type connInfo struct {
	Transport     string   `json:"transport"`
	RemoteAddress string   `json:"remoteAddr"`
	Identity      Identity `json:"identity"`
	Browser       bool     `json:"browser"`
	UserAgent     string   `json:"userAgent,omitempty"`
}

// connJSON describes the connection for Kotlin's Open. It fails if the
// result would exceed protocol.ConnJSONBytesMax.
func connJSON(listener *Listener, request *http.Request, identity Identity, browser bool) (string, error) {
	userAgent := request.UserAgent()
	if len(userAgent) > userAgentLengthMax {
		userAgent = userAgent[:userAgentLengthMax]
	}
	encoded, err := json.Marshal(connInfo{
		Transport:     listener.endpoint.Transport,
		RemoteAddress: request.RemoteAddr,
		Identity:      identity,
		Browser:       browser,
		UserAgent:     strings.ToValidUTF8(userAgent, ""),
	})
	if err != nil {
		panic(fmt.Sprintf("transport: conn info must encode: %v", err))
	}
	if len(encoded) > protocol.ConnJSONBytesMax {
		return "", fmt.Errorf("connection info of %d bytes exceeds %d", len(encoded), protocol.ConnJSONBytesMax)
	}
	return string(encoded), nil
}

// checkIdentityBounds enforces the protocol's limits on what WhoIs returned
// before any of it crosses the bridge: at most IdentityTagCountMax tags, and
// every string valid UTF-8 of at most IdentityTextLengthMax UTF-16 units.
func checkIdentityBounds(identity Identity) error {
	if len(identity.Tags) > protocol.IdentityTagCountMax {
		return fmt.Errorf("%d tags exceed %d", len(identity.Tags), protocol.IdentityTagCountMax)
	}
	fields := append([]string{identity.NodeID, identity.User, identity.Name, identity.TokenHash}, identity.Tags...)
	for _, field := range fields {
		if !utf8.ValidString(field) {
			return errors.New("identity text is not valid UTF-8")
		}
		// Cheap byte bound first: no string longer than this in bytes can be short enough.
		if len(field) > protocol.IdentityTextLengthMax*utf8.UTFMax || len(utf16.Encode([]rune(field))) > protocol.IdentityTextLengthMax {
			return fmt.Errorf("identity text longer than %d characters", protocol.IdentityTextLengthMax)
		}
	}
	return nil
}

func (s *Server) identify(request *http.Request, listener *Listener, browser bool) (Identity, int, error) {
	switch listener.endpoint.Transport {
	case TransportTailnet:
		ctx, cancel := context.WithTimeout(request.Context(), s.config.IdentifyTimeout)
		defer cancel()
		identity, err := listener.endpoint.Identify(ctx, request.RemoteAddr)
		if err != nil {
			return Identity{}, http.StatusForbidden, fmt.Errorf("whois: %w", err)
		}
		if identity.Kind != IdentityNode || identity.NodeID == "" {
			return Identity{}, http.StatusForbidden, errors.New("whois returned no node identity")
		}
		if err := checkIdentityBounds(identity); err != nil {
			return Identity{}, http.StatusForbidden, fmt.Errorf("whois identity out of bounds: %w", err)
		}
		return identity, 0, nil
	case TransportHotspot:
		token, ok := hotspotToken(request, browser)
		if !ok {
			return Identity{}, http.StatusUnauthorized, errors.New("missing or malformed token")
		}
		return Identity{Kind: IdentityToken, TokenHash: tokenHash(token)}, 0, nil
	}
	return Identity{}, http.StatusInternalServerError, errors.New("unknown transport")
}

func (s *Server) handleWebSocket(writer http.ResponseWriter, request *http.Request) {
	listener := listenerFrom(request)
	browser, err := checkOrigin(request.Header, request.Host)
	if err != nil {
		http.Error(writer, "forbidden origin", http.StatusForbidden)
		return
	}
	identity, status, err := s.identify(request, listener, browser)
	if err != nil {
		http.Error(writer, http.StatusText(status), status)
		return
	}
	info, err := connJSON(listener, request, identity, browser)
	if err != nil {
		http.Error(writer, http.StatusText(http.StatusForbidden), http.StatusForbidden)
		return
	}
	created, err := s.register(listener, identity, false)
	if err != nil {
		http.Error(writer, err.Error(), http.StatusServiceUnavailable)
		return
	}
	conn, err := websocket.Accept(writer, request, &websocket.AcceptOptions{
		// checkOrigin above is stricter than the library's (exact port, no
		// "null"), and the library's would also reject missing-Origin CLIs.
		InsecureSkipVerify: true,
		OnPongReceived:     func(context.Context, []byte) { created.pong() },
	})
	if err != nil {
		s.unregister(created)
		listener.sessions.Done()
		return
	}
	conn.SetReadLimit(protocol.MessageBytesMax)
	created.run(conn, info)
}

type pairRequest struct {
	Code string `json:"code"`
}

type pairResponse struct {
	OK    bool                `json:"ok"`
	Token string              `json:"token,omitempty"`
	Error *protocol.ErrorBody `json:"error,omitempty"`
}

func validPairCode(code string) bool {
	if len(code) != protocol.PairCodeLength {
		return false
	}
	for _, character := range code {
		if character < '0' || character > '9' {
			return false
		}
	}
	return true
}

// handlePair is the hotspot bootstrap: a 6-digit code shown on the phone
// buys a 256-bit token. Go mints the token and only its hash reaches Kotlin,
// which checks the code, lockout and pairing state and answers through Send.
// decodeSingleJSON decodes exactly one JSON value; anything after it but
// whitespace is an error.
func decodeSingleJSON(reader io.Reader, target any) error {
	decoder := json.NewDecoder(reader)
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if _, err := decoder.Token(); err != io.EOF {
		return errors.New("trailing data after the JSON object")
	}
	return nil
}

func (s *Server) handlePair(writer http.ResponseWriter, request *http.Request) {
	listener := listenerFrom(request)
	if listener.endpoint.Transport != TransportHotspot {
		http.NotFound(writer, request)
		return
	}
	// The body must arrive within PairBodyTimeout. The deadline is this
	// request's alone: net/http resets it for the next request on the
	// connection, and WebSocket upgrades never set one.
	controller := http.NewResponseController(writer)
	if err := controller.SetReadDeadline(time.Now().Add(s.config.PairBodyTimeout)); err != nil {
		http.Error(writer, "internal error", http.StatusInternalServerError)
		return
	}
	browser, err := checkOrigin(request.Header, request.Host)
	if err != nil {
		http.Error(writer, "forbidden origin", http.StatusForbidden)
		return
	}
	request.Body = http.MaxBytesReader(writer, request.Body, protocol.PairRequestBytesMax)
	var body pairRequest
	if err := decodeSingleJSON(request.Body, &body); err != nil || !validPairCode(body.Code) {
		writePairResponse(writer, http.StatusBadRequest, pairResponse{Error: &protocol.ErrorBody{Code: protocol.ErrorBadRequest, Message: "code must be 6 digits"}})
		return
	}
	// The body is in. Clear the deadline: the server's background read
	// (watching for a hang-up while Kotlin decides) would otherwise hit it
	// and cancel the request.
	if err := controller.SetReadDeadline(time.Time{}); err != nil {
		http.Error(writer, "internal error", http.StatusInternalServerError)
		return
	}
	token, hash := newToken()
	identity := Identity{Kind: IdentityPair, TokenHash: hash}
	info, err := connJSON(listener, request, identity, browser)
	if err != nil {
		writePairResponse(writer, http.StatusBadRequest, pairResponse{Error: &protocol.ErrorBody{Code: protocol.ErrorBadRequest, Message: "connection info too large"}})
		return
	}
	created, err := s.register(listener, identity, true)
	if err != nil {
		writePairResponse(writer, http.StatusServiceUnavailable, pairResponse{Error: &protocol.ErrorBody{Code: protocol.ErrorUnavailable, Message: err.Error()}})
		return
	}
	defer func() {
		created.close(protocol.ReasonPairDone, false)
		s.unregister(created)
		s.bridge.Closed(created.id, protocol.ReasonPairDone)
		listener.sessions.Done()
	}()
	s.bridge.Open(created.id, info)
	command, err := json.Marshal(map[string]string{"t": protocol.TypePair, "code": body.Code, "tokenHash": hash})
	if err != nil {
		panic(fmt.Sprintf("transport: pair command must encode: %v", err))
	}
	s.bridge.Command(created.id, string(command))

	timer := time.NewTimer(s.config.PairTimeout)
	defer timer.Stop()
	var reply []byte
	select {
	case reply = <-created.pair:
	case <-timer.C:
		writePairResponse(writer, http.StatusGatewayTimeout, pairResponse{Error: &protocol.ErrorBody{Code: protocol.ErrorTimeout, Message: "the phone did not answer"}})
		return
	case <-created.closeRequested:
		writePairResponse(writer, http.StatusServiceUnavailable, pairResponse{Error: &protocol.ErrorBody{Code: protocol.ErrorUnavailable, Message: "pairing was interrupted"}})
		return
	case <-request.Context().Done():
		return
	}
	var verdict protocol.Response
	if err := json.Unmarshal(reply, &verdict); err != nil || !verdict.OK {
		failure := &protocol.ErrorBody{Code: protocol.ErrorUnauthorized, Message: "pairing refused"}
		if err == nil && verdict.Error != nil {
			failure = verdict.Error
		}
		writePairResponse(writer, http.StatusForbidden, pairResponse{Error: failure})
		return
	}
	if browser {
		http.SetCookie(writer, &http.Cookie{
			Name:     tokenCookieName,
			Value:    token,
			Path:     "/",
			MaxAge:   tokenCookieMaxAgeSeconds,
			HttpOnly: true,
			SameSite: http.SameSiteStrictMode,
		})
		writePairResponse(writer, http.StatusOK, pairResponse{OK: true})
		return
	}
	writePairResponse(writer, http.StatusOK, pairResponse{OK: true, Token: token})
}

func writePairResponse(writer http.ResponseWriter, status int, response pairResponse) {
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)
	if err := json.NewEncoder(writer).Encode(response); err != nil {
		log.Printf("bootleg transport: pair response: %v", err)
	}
}

// handleTransport tells the web UI which transport it is on and whether a
// pairing cookie is present, so it can show the pairing form. It reveals
// nothing about the token itself.
func (s *Server) handleTransport(writer http.ResponseWriter, request *http.Request) {
	listener := listenerFrom(request)
	type transportInfo struct {
		Transport string `json:"transport"`
		HasToken  bool   `json:"hasToken"`
	}
	info := transportInfo{Transport: listener.endpoint.Transport}
	if listener.endpoint.Transport == TransportHotspot {
		_, info.HasToken = hotspotToken(request, true)
	}
	writer.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(writer).Encode(info); err != nil {
		log.Printf("bootleg transport: transport info: %v", err)
	}
}
