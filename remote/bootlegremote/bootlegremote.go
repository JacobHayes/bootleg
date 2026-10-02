// Package bootlegremote is Bootleg's remote-control transport for Android,
// bound with gomobile: an embedded Tailscale node (tsnet, userspace
// networking, no VPN slot) serving the web UI and WebSocket on its tailnet
// address, and optionally the same server on the offline hotspot. Only
// strings, numbers, byte slices, errors and the Callbacks interface cross the
// language boundary. All authorization decisions are Kotlin's.
package bootlegremote

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"

	"tailscale.com/envknob"
	"tailscale.com/logtail"
	"tailscale.com/net/netmon"

	"github.com/JacobHayes/bootleg/remote/transport"
)

const (
	tailnetListenAddress    = ":80"
	hostnameLengthMax       = 63
	stateDirectoryLengthMax = 4096
)

// Callbacks is implemented on the Kotlin side. Every method is called on a Go
// thread and must only enqueue work and return (within 20ms); Go never holds
// a lock across these calls.
type Callbacks interface {
	// OnStatus receives the same JSON as Status whenever it changes.
	OnStatus(statusJSON string)
	// Interfaces returns the device's network interfaces as JSON
	// (see interfaceJSON); Go cannot enumerate them itself on Android.
	Interfaces() string

	// Open announces a session. connJSON is {transport, remoteAddr,
	// identity:{kind, nodeId, user, tags, name | tokenHash}, browser,
	// userAgent}; the identity is bound to the accepted connection.
	Open(sessionID int64, connJSON string)
	// Command delivers one JSON message. Before Authorized only the first
	// hello (or, for a hotspot pairing session, one pair command) arrives.
	Command(sessionID int64, messageJSON string)
	// Hid delivers one validated binary HID frame; return 0 when queued and
	// 1 when the queue is full.
	Hid(sessionID int64, frame []byte) int32
	// Closed ends a session; it is called exactly once per Open, after the
	// session's last Command/Hid.
	Closed(sessionID int64, reason string)
}

type node struct {
	backend      backend
	listener     *transport.Listener
	callbacks    *callbacksHolder
	allowedHosts atomic.Pointer[[]string]
	cancel       context.CancelFunc
	done         sync.WaitGroup
}

type callbacksHolder struct{ callbacks Callbacks }

var (
	// lifecycleMutex serializes Start and Stop end to end, so a Start never
	// overlaps a node that is still closing. It is held across tsnet startup
	// and shutdown, which call the interface getter, so nothing on that path
	// may take it.
	lifecycleMutex sync.Mutex

	// stateMutex guards current and stopping. It is only held briefly, so
	// Status and NetworkChanged never wait for a slow Start or Stop.
	stateMutex sync.Mutex
	current    *node
	stopping   bool

	onceGlobal sync.Once

	verboseLogging atomic.Bool

	currentCallbacks atomic.Pointer[callbacksHolder]

	errorMutex sync.Mutex
	lastError  string
)

func initGlobals() {
	// Keep logs local: no upload to log.tailscale.com. With logtail off the
	// backend log is dropped unless SetVerboseLogging routes it to logcat.
	envknob.SetNoLogsNoSupport()
	logtail.Disable()
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		holder := currentCallbacks.Load()
		if holder == nil {
			return nil, errors.New("bootlegremote: no interface callback registered")
		}
		return parseInterfacesJSON(holder.callbacks.Interfaces())
	})
}

// releaseCallbacks drops the holder a listener installed when it stops,
// unless a newer registration already replaced it. If the other listener is
// still up, its holder (survivor) takes over.
func releaseCallbacks(mine, survivor *callbacksHolder) {
	currentCallbacks.CompareAndSwap(mine, survivor)
}

// Start brings the node up and starts serving. It returns once the backend is
// started; it does not wait for the node to reach Running (an interactive
// login may be needed, reported as loginURL in the status). authKey may be
// empty once the node is registered or for interactive login. A Start that
// races a Stop waits for the old node to finish closing.
func Start(stateDir, hostname, authKey string, cb Callbacks) error {
	if stateDir == "" || len(stateDir) > stateDirectoryLengthMax {
		return fmt.Errorf("bootlegremote: invalid state directory length %d", len(stateDir))
	}
	if hostname == "" || len(hostname) > hostnameLengthMax {
		return fmt.Errorf("bootlegremote: invalid hostname length %d", len(hostname))
	}
	if cb == nil {
		return errors.New("bootlegremote: nil callbacks")
	}
	onceGlobal.Do(initGlobals)

	lifecycleMutex.Lock()
	defer lifecycleMutex.Unlock()
	stateMutex.Lock()
	running := current
	stateMutex.Unlock()
	if running != nil {
		return errors.New("bootlegremote: already started")
	}
	holder := &callbacksHolder{callbacks: cb}
	currentCallbacks.Store(holder)
	started, err := startNode(stateDir, hostname, authKey, holder)
	if err != nil {
		releaseCallbacks(holder, hotspotCallbacks())
		return setError(err)
	}
	stateMutex.Lock()
	current = started
	stateMutex.Unlock()
	return nil
}

func startNode(stateDir, hostname, authKey string, holder *callbacksHolder) (*node, error) {
	clearError()
	if err := prepareEnvironment(stateDir); err != nil {
		return nil, err
	}
	running, err := startBackend(backendConfig{stateDir: stateDir, hostname: hostname, authKey: authKey, verbose: verboseLogging.Load()})
	if err != nil {
		return nil, err
	}
	listener, err := running.Listen(tailnetListenAddress)
	if err != nil {
		running.Close()
		return nil, fmt.Errorf("tsnet listen %s: %w", tailnetListenAddress, err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	started := &node{backend: running, callbacks: holder, cancel: cancel}
	started.setAllowedHosts(hostname, status{})
	started.listener = transportServer().Serve(listener, transport.Endpoint{
		Transport:    transport.TransportTailnet,
		AllowedHosts: func() []string { return *started.allowedHosts.Load() },
		Identify: func(ctx context.Context, remoteAddress string) (transport.Identity, error) {
			return identify(ctx, running, remoteAddress)
		},
	})
	started.done.Add(1)
	go func() {
		defer started.done.Done()
		watchStatus(ctx, running, func(snapshot status) {
			started.setAllowedHosts(hostname, snapshot)
			holder.callbacks.OnStatus(encodeStatus(snapshot))
		}, sleepContext)
	}()
	return started, nil
}

func identify(ctx context.Context, running backend, remoteAddress string) (transport.Identity, error) {
	peer, err := running.WhoIs(ctx, remoteAddress)
	if err != nil {
		return transport.Identity{}, err
	}
	return transport.Identity{Kind: transport.IdentityNode, NodeID: peer.NodeID, Name: peer.Name, User: peer.User, Tags: peer.Tags}, nil
}

// setAllowedHosts lists every name a tailnet client may put in Host: the
// short MagicDNS name, the FQDN and the node's tailnet IPs.
func (n *node) setAllowedHosts(hostname string, snapshot status) {
	hosts := []string{hostname}
	if snapshot.DNSName != "" {
		fqdn := strings.TrimSuffix(snapshot.DNSName, ".")
		hosts = append(hosts, fqdn)
		if short, _, found := strings.Cut(fqdn, "."); found && short != hostname {
			hosts = append(hosts, short)
		}
	}
	for _, ip := range snapshot.TailscaleIPs {
		if strings.Contains(ip, ":") {
			ip = "[" + ip + "]"
		}
		hosts = append(hosts, ip)
	}
	n.allowedHosts.Store(&hosts)
}

// prepareEnvironment points TS_LOGS_DIR and TMPDIR into the state directory:
// logpolicy.LogsDir (used by LocalBackend's sockstat logger) otherwise falls
// through to os.MkdirTemp, which fails on Android and panics. Tests replace it
// so the test binary's TMPDIR survives.
var prepareEnvironment = func(stateDir string) error {
	for variable, directory := range map[string]string{"TS_LOGS_DIR": "logs", "TMPDIR": "tmp"} {
		path := filepath.Join(stateDir, directory)
		if err := os.MkdirAll(path, 0o700); err != nil {
			return fmt.Errorf("create %s: %w", path, err)
		}
		if err := os.Setenv(variable, path); err != nil {
			return fmt.Errorf("set %s: %w", variable, err)
		}
	}
	return nil
}

// SetVerboseLogging sends tsnet's backend log to the Go log (logcat) on the
// next Start. Off by default; the backend log is otherwise discarded.
func SetVerboseLogging(enabled bool) {
	verboseLogging.Store(enabled)
}

// Stop ends every tailnet session (each gets Closed with listener_stopped),
// shuts the node down and returns once it has fully closed. The node stays
// registered (state is kept). Status reports Stopping meanwhile.
func Stop() {
	lifecycleMutex.Lock()
	defer lifecycleMutex.Unlock()
	stateMutex.Lock()
	stopped := current
	if stopped != nil {
		stopping = true
	}
	stateMutex.Unlock()
	if stopped == nil {
		return
	}
	stopNode(stopped)
	stateMutex.Lock()
	current = nil
	stopping = false
	stateMutex.Unlock()
	releaseCallbacks(stopped.callbacks, hotspotCallbacks())
}

func stopNode(stopped *node) {
	stopped.cancel()
	stopped.listener.Stop()
	if err := stopped.backend.Close(); err != nil {
		log.Printf("bootlegremote: tsnet close: %v", err)
	}
	stopped.done.Wait()
}

// runningNode returns the node if it is up and not stopping.
func runningNode() (*node, bool) {
	stateMutex.Lock()
	defer stateMutex.Unlock()
	return current, current != nil && !stopping
}

// nodeCallbacks is the tailnet node's holder, also while it is stopping (its
// sessions still get Closed then).
func nodeCallbacks() *callbacksHolder {
	stateMutex.Lock()
	defer stateMutex.Unlock()
	if current == nil {
		return nil
	}
	return current.callbacks
}

// NetworkChanged forwards Android's default-network changes (from
// ConnectivityManager) to netmon so the node rebinds promptly instead of
// waiting for the next poll. interfaceName is "" when the network is lost.
func NetworkChanged(interfaceName, gatewayIP string) {
	updateDefaultRoute(interfaceName, gatewayIP)
	if running, ok := runningNode(); ok {
		running.backend.InjectNetworkEvent()
	}
}

// Status returns the node status as JSON: backend state, tailnet IPs, the
// login URL if interactive login is needed, and the last error.
func Status() string {
	stateMutex.Lock()
	running, isStopping := current, stopping
	stateMutex.Unlock()
	switch {
	case running == nil:
		return encodeStatus(status{BackendState: backendStateStopped, Error: currentError()})
	case isStopping:
		return encodeStatus(status{BackendState: backendStateStopping, Error: currentError()})
	}
	return encodeStatus(statusOf(context.Background(), running.backend))
}

// setError records err for Status and returns it.
func setError(err error) error {
	errorMutex.Lock()
	defer errorMutex.Unlock()
	lastError = err.Error()
	return err
}

func clearError() {
	errorMutex.Lock()
	defer errorMutex.Unlock()
	lastError = ""
}

func currentError() string {
	errorMutex.Lock()
	defer errorMutex.Unlock()
	return lastError
}
