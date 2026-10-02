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
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/logtail"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

const (
	listenAddress                   = ":80"
	localAPITimeoutMs               = 5_000
	whoIsTimeoutMs                  = 5_000
	httpReadHeaderTimeout           = 10 * time.Second
	httpIdleTimeout                 = 60 * time.Second
	httpShutdownTimeoutMs           = 3_000
	httpHeaderBytesMax              = 16 << 10
	watchRetryDelayMs               = 1_000
	watchConsecutiveFailureCountMax = 60
	hostnameLengthMax               = 63
	stateDirectoryLengthMax         = 4096
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
	server      *tsnet.Server
	localClient *local.Client
	httpServer  *http.Server
	cancel      context.CancelFunc
	done        sync.WaitGroup
}

type callbacksHolder struct{ callbacks Callbacks }

var (
	// mutex guards current. It is held across tsnet startup, which calls the
	// interface getter, so the getter and error recording use their own state.
	mutex      sync.Mutex
	current    *node
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
// empty once the node is registered or for interactive login.
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

	mutex.Lock()
	defer mutex.Unlock()
	if current != nil {
		return errors.New("bootlegremote: already started")
	}
	currentCallbacks.Store(&callbacksHolder{callbacks: cb})
	clearError()
	// logpolicy.LogsDir (used by LocalBackend's sockstat logger) otherwise falls
	// through to os.MkdirTemp, which fails on Android and panics.
	for variable, directory := range map[string]string{"TS_LOGS_DIR": "logs", "TMPDIR": "tmp"} {
		path := filepath.Join(stateDir, directory)
		if err := os.MkdirAll(path, 0o700); err != nil {
			return setError(fmt.Errorf("create %s: %w", path, err))
		}
		if err := os.Setenv(variable, path); err != nil {
			return setError(fmt.Errorf("set %s: %w", variable, err))
		}
	}

	server := &tsnet.Server{
		Dir:       stateDir,
		Hostname:  hostname,
		AuthKey:   strings.TrimSpace(authKey),
		Ephemeral: false,
		UserLogf:  log.Printf,
	}
	if verboseLogging.Load() {
		server.Logf = log.Printf
	}
	if err := server.Start(); err != nil {
		server.Close()
		return setError(fmt.Errorf("tsnet start: %w", err))
	}
	localClient, err := server.LocalClient()
	if err != nil {
		server.Close()
		return setError(fmt.Errorf("tsnet local client: %w", err))
	}
	listener, err := server.Listen("tcp", listenAddress)
	if err != nil {
		server.Close()
		return setError(fmt.Errorf("tsnet listen %s: %w", listenAddress, err))
	}

	ctx, cancel := context.WithCancel(context.Background())
	started := &node{
		server:      server,
		localClient: localClient,
		cancel:      cancel,
		httpServer: &http.Server{
			Handler:           newHandler(localClient),
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
		watchStatus(ctx, localClient, cb)
	}()
	current = started
	return nil
}

// SetVerboseLogging sends tsnet's backend log to the Go log (logcat) on the
// next Start. Off by default; the backend log is otherwise discarded.
func SetVerboseLogging(enabled bool) {
	verboseLogging.Store(enabled)
}

// Stop shuts the node down. The node stays registered (state is kept).
func Stop() {
	mutex.Lock()
	stopping := current
	current = nil
	mutex.Unlock()
	if stopping == nil {
		return
	}
	stopping.cancel()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), httpShutdownTimeoutMs*time.Millisecond)
	defer cancel()
	if err := stopping.httpServer.Shutdown(shutdownCtx); err != nil {
		log.Printf("bootlegremote: http shutdown: %v", err)
	}
	if err := stopping.server.Close(); err != nil {
		log.Printf("bootlegremote: tsnet close: %v", err)
	}
	stopping.done.Wait()
}

// NetworkChanged forwards Android's default-network changes (from
// ConnectivityManager) to netmon so the node rebinds promptly instead of
// waiting for the next poll. interfaceName is "" when the network is lost.
func NetworkChanged(interfaceName, gatewayIP string) {
	updateDefaultRoute(interfaceName, gatewayIP)
	mutex.Lock()
	running := current
	mutex.Unlock()
	if running == nil {
		return
	}
	if monitor, ok := running.server.Sys().NetMon.GetOK(); ok {
		monitor.InjectEvent()
	}
}

type status struct {
	BackendState   string   `json:"backendState"`
	TailscaleIPs   []string `json:"tailscaleIPs"`
	LoginURL       string   `json:"loginURL,omitempty"`
	DNSName        string   `json:"dnsName,omitempty"`
	MagicDNSSuffix string   `json:"magicDNSSuffix,omitempty"`
	Health         []string `json:"health,omitempty"`
	Error          string   `json:"error,omitempty"`
}

// Status returns the node status as JSON: backend state, tailnet IPs, the
// login URL if interactive login is needed, and the last error.
func Status() string {
	mutex.Lock()
	running := current
	mutex.Unlock()
	if running == nil {
		return encodeStatus(status{BackendState: ipn.Stopped.String(), TailscaleIPs: []string{}, Error: currentError()})
	}
	return statusOf(context.Background(), running.localClient)
}

func statusOf(ctx context.Context, localClient *local.Client) string {
	ctx, cancel := context.WithTimeout(ctx, localAPITimeoutMs*time.Millisecond)
	defer cancel()
	errorText := currentError()
	tailnetStatus, err := localClient.StatusWithoutPeers(ctx)
	if err != nil {
		return encodeStatus(status{BackendState: "Unknown", TailscaleIPs: []string{}, Error: err.Error()})
	}
	result := status{
		BackendState: tailnetStatus.BackendState,
		TailscaleIPs: []string{},
		LoginURL:     tailnetStatus.AuthURL,
		Health:       tailnetStatus.Health,
		Error:        errorText,
	}
	for _, ip := range tailnetStatus.TailscaleIPs {
		result.TailscaleIPs = append(result.TailscaleIPs, ip.String())
	}
	if tailnetStatus.Self != nil {
		result.DNSName = tailnetStatus.Self.DNSName
	}
	if tailnetStatus.CurrentTailnet != nil {
		result.MagicDNSSuffix = tailnetStatus.CurrentTailnet.MagicDNSSuffix
	}
	return encodeStatus(result)
}

func encodeStatus(value status) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		panic(fmt.Sprintf("bootlegremote: status must always encode: %v", err))
	}
	return string(encoded)
}

// watchStatus pushes status JSON to the callback whenever the backend state,
// login URL or netmap changes, deduplicating identical snapshots.
func watchStatus(ctx context.Context, localClient *local.Client, cb Callbacks) {
	lastSent := ""
	send := func() {
		snapshot := statusOf(ctx, localClient)
		if snapshot != lastSent && ctx.Err() == nil {
			lastSent = snapshot
			cb.OnStatus(snapshot)
		}
	}
	for retry := 0; retry < watchConsecutiveFailureCountMax && ctx.Err() == nil; retry++ {
		watcher, err := localClient.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyNoPrivateKeys)
		if err != nil {
			log.Printf("bootlegremote: watch ipn bus: %v", err)
			sleepContext(ctx, watchRetryDelayMs*time.Millisecond)
			continue
		}
		retry = 0
		for {
			notification, err := watcher.Next()
			if err != nil {
				break
			}
			if notification.State != nil || notification.BrowseToURL != nil || notification.SelfChange != nil || notification.LoginFinished != nil || notification.Health != nil || notification.ErrMessage != nil {
				if notification.ErrMessage != nil {
					setError(errors.New(*notification.ErrMessage))
				}
				send()
			}
		}
		watcher.Close()
	}
}

func sleepContext(ctx context.Context, duration time.Duration) {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
	case <-timer.C:
	}
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

func newHandler(localClient *local.Client) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "text/plain; charset=utf-8")
		fmt.Fprintln(writer, "bootleg ok")
	})
	mux.HandleFunc("GET /whoami", func(writer http.ResponseWriter, request *http.Request) {
		ctx, cancel := context.WithTimeout(request.Context(), whoIsTimeoutMs*time.Millisecond)
		defer cancel()
		who, err := localClient.WhoIs(ctx, request.RemoteAddr)
		if err != nil {
			http.Error(writer, "whois failed: "+err.Error(), http.StatusForbidden)
			return
		}
		type whoAmI struct {
			RemoteAddress string   `json:"remoteAddress"`
			NodeName      string   `json:"nodeName"`
			StableNodeID  string   `json:"stableNodeID"`
			UserLogin     string   `json:"userLogin,omitempty"`
			Tags          []string `json:"tags,omitempty"`
		}
		response := whoAmI{RemoteAddress: request.RemoteAddr}
		if who.Node != nil {
			response.NodeName = who.Node.ComputedName
			if response.NodeName == "" {
				response.NodeName = who.Node.Name
			}
			response.StableNodeID = string(who.Node.StableID)
			response.Tags = who.Node.Tags
		}
		// Tagged nodes carry a placeholder user profile; report tags instead.
		if who.UserProfile != nil && len(response.Tags) == 0 {
			response.UserLogin = who.UserProfile.LoginName
		}
		writer.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(writer).Encode(response); err != nil {
			log.Printf("bootlegremote: whoami encode: %v", err)
		}
	})
	return mux
}
