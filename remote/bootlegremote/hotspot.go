package bootlegremote

import (
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"

	"github.com/JacobHayes/bootleg/remote/transport"
)

const (
	hotspotPort          = 8080
	hotspotPrefixBitsMin = 8
	hotspotPrefixBitsMax = 30
)

type hotspot struct {
	listener  *transport.Listener
	callbacks *callbacksHolder
}

var (
	// hotspotMutex serializes StartHotspot/StopHotspot end to end.
	hotspotMutex sync.Mutex
	// hotspotStateMutex guards currentHotspot for quick reads.
	hotspotStateMutex sync.Mutex
	currentHotspot    *hotspot
)

// listenTCP is net.Listen, replaceable in tests.
var listenTCP = func(address string) (net.Listener, error) { return net.Listen("tcp", address) }

// StartHotspot serves the web UI, /ws and /pair on the offline hotspot at
// port 8080. interfaceCIDR is the phone's hotspot interface address with its
// prefix, for example "192.168.49.1/24": the listener binds that address
// only, and each connection must arrive on it from inside that subnet.
// Hotspot clients authenticate with a paired token; the tailnet node is not
// needed.
func StartHotspot(interfaceCIDR string, cb Callbacks) error {
	if cb == nil {
		return errors.New("bootlegremote: nil callbacks")
	}
	prefix, err := netip.ParsePrefix(interfaceCIDR)
	if err != nil {
		return fmt.Errorf("bootlegremote: hotspot address: %w", err)
	}
	if !prefix.Addr().Is4() || prefix.Bits() < hotspotPrefixBitsMin || prefix.Bits() > hotspotPrefixBitsMax || prefix.Addr() == prefix.Masked().Addr() {
		return fmt.Errorf("bootlegremote: hotspot address %s must be an IPv4 host address with a /%d–/%d prefix", interfaceCIDR, hotspotPrefixBitsMin, hotspotPrefixBitsMax)
	}
	onceGlobal.Do(initGlobals)

	hotspotMutex.Lock()
	defer hotspotMutex.Unlock()
	if hotspotCallbacks() != nil {
		return errors.New("bootlegremote: hotspot already started")
	}
	address := netip.AddrPortFrom(prefix.Addr(), hotspotPort)
	inner, err := listenTCP(address.String())
	if err != nil {
		return fmt.Errorf("bootlegremote: listen %s: %w", address, err)
	}
	holder := &callbacksHolder{callbacks: cb}
	currentCallbacks.Store(holder)
	started := &hotspot{callbacks: holder}
	started.listener = transportServer().Serve(inner, transport.Endpoint{
		Transport:    transport.TransportHotspot,
		AllowedHosts: func() []string { return []string{address.String()} },
		Ingress: func(local, remote net.Addr) error {
			return transport.HotspotIngress(prefix, hotspotPort, local, remote)
		},
	})
	hotspotStateMutex.Lock()
	currentHotspot = started
	hotspotStateMutex.Unlock()
	return nil
}

// StopHotspot closes the hotspot listener and its sessions (each gets
// Closed with listener_stopped) and returns once they are gone.
func StopHotspot() {
	hotspotMutex.Lock()
	defer hotspotMutex.Unlock()
	hotspotStateMutex.Lock()
	stopped := currentHotspot
	hotspotStateMutex.Unlock()
	if stopped == nil {
		return
	}
	stopped.listener.Stop()
	hotspotStateMutex.Lock()
	currentHotspot = nil
	hotspotStateMutex.Unlock()
	releaseCallbacks(stopped.callbacks, nodeCallbacks())
}

func hotspotCallbacks() *callbacksHolder {
	hotspotStateMutex.Lock()
	defer hotspotStateMutex.Unlock()
	if currentHotspot == nil {
		return nil
	}
	return currentHotspot.callbacks
}
