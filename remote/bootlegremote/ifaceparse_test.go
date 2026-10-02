package bootlegremote

import (
	"net"
	"strings"
	"testing"
)

func TestParseInterfacesJSON(t *testing.T) {
	payload := `[
		{"name":"wlan0","index":30,"mtu":1500,"up":true,"broadcast":true,"loopback":false,"pointToPoint":false,"multicast":true,
		 "addrs":[{"ip":"192.168.1.50","prefixLen":24},{"ip":"fe80::1%wlan0","prefixLen":64},{"ip":"bogus","prefixLen":8},{"ip":"10.0.0.1","prefixLen":99}]},
		{"name":"","index":1,"mtu":1500,"up":true,"addrs":[]},
		{"name":"lo","index":1,"mtu":65536,"up":true,"loopback":true,"addrs":[{"ip":"127.0.0.1","prefixLen":8}]}
	]`
	interfaces, err := parseInterfacesJSON(payload)
	if err != nil {
		t.Fatal(err)
	}
	if len(interfaces) != 2 {
		t.Fatalf("got %d interfaces, want 2 (nameless entry skipped)", len(interfaces))
	}
	wlan := interfaces[0]
	if wlan.Name != "wlan0" || !wlan.IsUp() || wlan.Flags&net.FlagLoopback != 0 {
		t.Errorf("wlan0 parsed wrong: %+v", wlan.Interface)
	}
	addresses, err := wlan.Addrs()
	if err != nil {
		t.Fatal(err)
	}
	var rendered []string
	for _, address := range addresses {
		rendered = append(rendered, address.String())
	}
	// The bogus address is dropped; the out-of-range prefix keeps the IP without a mask.
	want := "192.168.1.50/24 fe80::1%wlan0 10.0.0.1"
	if got := strings.Join(rendered, " "); got != want {
		t.Errorf("wlan0 addresses = %q, want %q", got, want)
	}
	if !interfaces[1].IsLoopback() {
		t.Errorf("lo should be loopback")
	}
}

func TestParseInterfacesJSONRejectsGarbage(t *testing.T) {
	if _, err := parseInterfacesJSON("not json"); err == nil {
		t.Fatal("want error for non-JSON payload")
	}
	if interfaces, err := parseInterfacesJSON("  "); err != nil || interfaces != nil {
		t.Fatalf("empty payload: got %v, %v; want nil, nil", interfaces, err)
	}
	oversized := "[" + strings.Repeat(`{"name":"x"},`, interfaceCountMax) + `{"name":"x"}]`
	if _, err := parseInterfacesJSON(oversized); err == nil {
		t.Fatal("want error above interfaceCountMax")
	}
}
