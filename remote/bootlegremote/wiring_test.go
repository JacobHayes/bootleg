package bootlegremote

import (
	"context"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func dialSession(t *testing.T, address, host string, header http.Header) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, "ws://"+address+"/ws", &websocket.DialOptions{Host: host, HTTPHeader: header})
	if err != nil {
		t.Fatalf("dial %s (Host %s): %v", address, host, err)
	}
	t.Cleanup(func() { conn.CloseNow() })
	return conn
}

// The tailnet listener identifies callers with WhoIs, Kotlin sees the bound
// identity, and Stop ends the session with Closed before returning.
func TestTailnetSessionReachesCallbacks(t *testing.T) {
	fake := newFakeBackend()
	useFakeBackends(t, fake)
	callbacks := &recordingCallbacks{}
	if err := Start(t.TempDir(), "bootleg-test", "", callbacks); err != nil {
		t.Fatal(err)
	}
	address := *fake.address.Load()
	dialSession(t, address, "bootleg-test", nil)
	waitFor(t, func() bool {
		found := false
		callbacks.read(func(c *recordingCallbacks) { found = len(c.opens) == 1 })
		return found
	})
	callbacks.read(func(c *recordingCallbacks) {
		for _, connJSON := range c.opens {
			if !strings.Contains(connJSON, `"nodeId":"nFAKE"`) || !strings.Contains(connJSON, `"transport":"tailnet"`) {
				t.Errorf("conn JSON %s", connJSON)
			}
		}
	})
	if Send(1<<40, `{}`) || Authorized(1<<40) || Close(1<<40, "x") || Revoke("node", "nobody") != 0 {
		t.Error("unknown sessions must report false/0")
	}
	Stop()
	callbacks.read(func(c *recordingCallbacks) {
		if len(c.closed) != 1 {
			t.Errorf("Closed calls after Stop: %v", c.closed)
		}
		for _, reason := range c.closed {
			if reason != "listener_stopped" {
				t.Errorf("reason %q", reason)
			}
		}
	})
}

func TestHotspotValidatesAddress(t *testing.T) {
	for _, cidr := range []string{"", "192.168.49.1", "192.168.49.0/24", "fd00::1/64", "192.168.49.1/32", "10.0.0.1/4"} {
		if err := StartHotspot(cidr, &recordingCallbacks{}); err == nil {
			StopHotspot()
			t.Errorf("StartHotspot(%q) accepted", cidr)
		}
	}
	if err := StartHotspot("192.168.49.1/24", nil); err == nil {
		t.Error("nil callbacks accepted")
	}
}

// Hotspot and tailnet share one callbacks slot: stopping either hands it to
// the one still running, and stopping both clears it.
func TestHotspotAndTailnetShareCallbacks(t *testing.T) {
	useFakeBackends(t, newFakeBackend())
	previousListen := listenTCP
	listenTCP = func(string) (net.Listener, error) { return net.Listen("tcp", "127.0.0.1:0") }
	t.Cleanup(func() {
		StopHotspot()
		listenTCP = previousListen
	})
	nodeCallbacksValue, hotspotCallbacksValue := &recordingCallbacks{}, &recordingCallbacks{}
	if err := Start(t.TempDir(), "bootleg-test", "", nodeCallbacksValue); err != nil {
		t.Fatal(err)
	}
	if err := StartHotspot("192.168.49.1/24", hotspotCallbacksValue); err != nil {
		t.Fatal(err)
	}
	if err := StartHotspot("192.168.49.1/24", hotspotCallbacksValue); err == nil {
		t.Fatal("second StartHotspot accepted")
	}
	if currentCallbacks.Load().callbacks != hotspotCallbacksValue {
		t.Fatal("latest registration should be current")
	}
	StopHotspot()
	if holder := currentCallbacks.Load(); holder == nil || holder.callbacks != nodeCallbacksValue {
		t.Fatal("StopHotspot must hand callbacks back to the running node")
	}
	if err := StartHotspot("192.168.49.1/24", hotspotCallbacksValue); err != nil {
		t.Fatal(err)
	}
	Stop()
	if holder := currentCallbacks.Load(); holder == nil || holder.callbacks != hotspotCallbacksValue {
		t.Fatal("Stop must keep the hotspot's callbacks")
	}
	StopHotspot()
	if currentCallbacks.Load() != nil {
		t.Fatal("callbacks must be cleared when nothing runs")
	}
}
