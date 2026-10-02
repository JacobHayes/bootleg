// Package bootlegremote runs an embedded Tailscale node (tsnet, userspace
// networking, no VPN slot) inside the Bootleg Android app and serves HTTP on
// its tailnet address. The exported surface is gomobile-friendly: only
// strings, errors and the Callbacks interface cross the language boundary.
package bootlegremote

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/logtail"
	"tailscale.com/net/netmon"
)

const (
	listenAddress           = ":80"
	whoIsTimeoutMs          = 5_000
	httpReadHeaderTimeout   = 10 * time.Second
	httpIdleTimeout         = 60 * time.Second
	httpShutdownTimeoutMs   = 3_000
	httpHeaderBytesMax      = 16 << 10
	hostnameLengthMax       = 63
	stateDirectoryLengthMax = 4096
)

// Callbacks is implemented on the Kotlin side.
type Callbacks interface {
	// OnStatus receives the same JSON as Status whenever it changes.
	OnStatus(statusJSON string)
	// Interfaces returns the device's network interfaces as JSON
	// (see interfaceJSON); Go cannot enumerate them itself on Android.
	Interfaces() string
}

type node struct {
	backend    backend
	httpServer *http.Server
	callbacks  *callbacksHolder
	cancel     context.CancelFunc
	done       sync.WaitGroup
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
		currentCallbacks.CompareAndSwap(holder, nil)
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
	listener, err := running.Listen(listenAddress)
	if err != nil {
		running.Close()
		return nil, fmt.Errorf("tsnet listen %s: %w", listenAddress, err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	started := &node{
		backend:   running,
		callbacks: holder,
		cancel:    cancel,
		httpServer: &http.Server{
			Handler:           newHandler(running),
			ReadHeaderTimeout: httpReadHeaderTimeout,
			IdleTimeout:       httpIdleTimeout,
			MaxHeaderBytes:    httpHeaderBytesMax,
		},
	}
	started.done.Add(2)
	go func() {
		defer started.done.Done()
		if err := started.httpServer.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("bootlegremote: http serve: %v", err)
		}
	}()
	go func() {
		defer started.done.Done()
		watchStatus(ctx, running, func(snapshot status) { holder.callbacks.OnStatus(encodeStatus(snapshot)) }, sleepContext)
	}()
	return started, nil
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

// Stop shuts the node down and returns once it has fully closed. The node
// stays registered (state is kept). Status reports Stopping meanwhile.
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
	// A newer holder (installed by a later registration) must survive.
	currentCallbacks.CompareAndSwap(stopped.callbacks, nil)
}

func stopNode(stopped *node) {
	stopped.cancel()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), httpShutdownTimeoutMs*time.Millisecond)
	defer cancel()
	if err := stopped.httpServer.Shutdown(shutdownCtx); err != nil {
		log.Printf("bootlegremote: http shutdown: %v", err)
	}
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

func newHandler(running backend) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "text/plain; charset=utf-8")
		fmt.Fprintln(writer, "bootleg ok")
	})
	mux.HandleFunc("GET /whoami", func(writer http.ResponseWriter, request *http.Request) {
		ctx, cancel := context.WithTimeout(request.Context(), whoIsTimeoutMs*time.Millisecond)
		defer cancel()
		who, err := running.WhoIs(ctx, request.RemoteAddr)
		if err != nil {
			http.Error(writer, "whois failed: "+err.Error(), http.StatusForbidden)
			return
		}
		writer.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(writer).Encode(who); err != nil {
			log.Printf("bootlegremote: whoami encode: %v", err)
		}
	})
	return mux
}
