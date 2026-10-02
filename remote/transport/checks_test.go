package transport

import (
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestHostAllowed(t *testing.T) {
	allowed := []string{"bootleg-pixel6", "bootleg-pixel6.tail.ts.net", "100.64.0.7", "[fd7a:115c:a1e0::7]", "192.168.49.1:8080"}
	cases := map[string]bool{
		"bootleg-pixel6":              true,
		"BOOTLEG-PIXEL6":              true,
		"bootleg-pixel6:80":           true,
		"bootleg-pixel6.tail.ts.net":  true,
		"100.64.0.7":                  true,
		"100.64.0.7:80":               true,
		"[fd7a:115c:a1e0::7]":         true,
		"192.168.49.1:8080":           true,
		"192.168.49.1":                false, // wrong port (80)
		"bootleg-pixel6:8080":         false,
		"evil.example":                false,
		"bootleg-pixel6.evil.example": false,
		"":                            false,
		"100.64.0.8":                  false,
		"bootleg-pixel6.tail.ts.net.": false,
	}
	for host, want := range cases {
		if got := hostAllowed(host, allowed); got != want {
			t.Errorf("hostAllowed(%q) = %v, want %v", host, got, want)
		}
	}
}

func TestCheckOriginMatrix(t *testing.T) {
	type result struct {
		browser bool
		ok      bool
	}
	host := "bootleg-pixel6"
	cases := []struct {
		origins []string
		host    string
		want    result
	}{
		{nil, host, result{false, true}},                                 // CLI: no Origin
		{[]string{"http://bootleg-pixel6"}, host, result{true, true}},    // exact
		{[]string{"http://BOOTLEG-pixel6"}, host, result{true, true}},    // host case
		{[]string{"http://bootleg-pixel6:80"}, host, result{true, true}}, // default port spelled out
		{[]string{"http://bootleg-pixel6"}, "bootleg-pixel6:80", result{true, true}},
		{[]string{"null"}, host, result{true, false}},
		{[]string{"https://bootleg-pixel6"}, host, result{true, false}},     // scheme
		{[]string{"http://bootleg-pixel6:8080"}, host, result{true, false}}, // port
		{[]string{"http://evil.example"}, host, result{true, false}},
		{[]string{"http://bootleg-pixel6.evil.example"}, host, result{true, false}},
		{[]string{"http://user@bootleg-pixel6"}, host, result{true, false}},
		{[]string{"http://bootleg-pixel6/path"}, host, result{true, false}},
		{[]string{"http://bootleg-pixel6", "http://bootleg-pixel6"}, host, result{true, false}},
		{[]string{""}, host, result{true, false}},
		{[]string{"http://192.168.49.1:8080"}, "192.168.49.1:8080", result{true, true}},
		{[]string{"http://192.168.49.1"}, "192.168.49.1:8080", result{true, false}},
	}
	for _, testCase := range cases {
		header := http.Header{}
		for _, origin := range testCase.origins {
			header.Add("Origin", origin)
		}
		browser, err := checkOrigin(header, testCase.host)
		got := result{browser, err == nil}
		if got != testCase.want {
			t.Errorf("origins %q host %q: got %+v (err %v), want %+v", testCase.origins, testCase.host, got, err, testCase.want)
		}
	}
}

func TestHotspotIngress(t *testing.T) {
	hotspot := netip.MustParsePrefix("192.168.49.1/24")
	tcp := func(address string) net.Addr { return net.TCPAddrFromAddrPort(netip.MustParseAddrPort(address)) }
	cases := []struct {
		local, remote string
		ok            bool
	}{
		{"192.168.49.1:8080", "192.168.49.23:51000", true},
		{"[::ffff:192.168.49.1]:8080", "[::ffff:192.168.49.23]:51000", true}, // v4-mapped
		{"192.168.49.1:8081", "192.168.49.23:51000", false},                  // wrong port
		{"192.168.1.50:8080", "192.168.49.23:51000", false},                // arrived on another interface
		{"192.168.49.1:8080", "192.168.50.23:51000", false},                  // outside the subnet
		{"192.168.49.1:8080", "100.64.0.9:51000", false},                     // tailnet peer on the hotspot port
		{"192.168.49.1:8080", "192.168.49.1:51000", false},                   // the phone itself (another app)
		{"192.168.49.1:8080", "127.0.0.1:51000", false},
	}
	for _, testCase := range cases {
		err := HotspotIngress(hotspot, 8080, tcp(testCase.local), tcp(testCase.remote))
		if (err == nil) != testCase.ok {
			t.Errorf("local %s remote %s: err %v, want ok=%v", testCase.local, testCase.remote, err, testCase.ok)
		}
	}
	if err := HotspotIngress(netip.Prefix{}, 8080, tcp("192.168.49.1:8080"), tcp("192.168.49.2:1")); err == nil {
		t.Error("invalid prefix must be rejected")
	}
}

// A listener whose ingress check fails drops the connection before any HTTP.
func TestIngressRejectsConnectionsEndToEnd(t *testing.T) {
	h := newHarness(t, TransportHotspot, func(_ *Config, endpoint *Endpoint) {
		endpoint.Ingress = func(local, remote net.Addr) error {
			return HotspotIngress(netip.MustParsePrefix("10.99.0.1/24"), 8080, local, remote)
		}
	})
	client := &http.Client{Timeout: 2 * time.Second}
	response, err := client.Get(h.url("/"))
	if err == nil {
		response.Body.Close()
		t.Fatalf("expected the connection to be dropped, got HTTP %d", response.StatusCode)
	}
}

func TestHTTPHostCheckAndSecurityHeaders(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	response, err := http.Get(h.url("/"))
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(response.Body)
	response.Body.Close()
	if response.StatusCode != http.StatusOK || !strings.Contains(string(body), "Bootleg") {
		t.Fatalf("GET / = %d %q", response.StatusCode, body)
	}
	csp := response.Header.Get("Content-Security-Policy")
	if !strings.Contains(csp, "default-src 'self'") || !strings.Contains(csp, "frame-ancestors 'none'") {
		t.Errorf("CSP %q", csp)
	}
	if response.Header.Get("X-Frame-Options") != "DENY" {
		t.Errorf("X-Frame-Options %q", response.Header.Get("X-Frame-Options"))
	}
	if response.Header.Get("Access-Control-Allow-Origin") != "" {
		t.Error("no CORS headers allowed")
	}

	request, _ := http.NewRequest(http.MethodGet, h.url("/"), nil)
	request.Host = "evil.example"
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusMisdirectedRequest {
		t.Errorf("foreign Host got %d, want 421", response.StatusCode)
	}

	request, _ = http.NewRequest(http.MethodOptions, h.url("/ws"), nil)
	request.Header.Set("Origin", "http://evil.example")
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusMethodNotAllowed || response.Header.Get("Access-Control-Allow-Origin") != "" {
		t.Errorf("preflight got %d with ACAO %q", response.StatusCode, response.Header.Get("Access-Control-Allow-Origin"))
	}

	response, err = http.Get(h.url("/keycodes.json"))
	if err != nil {
		t.Fatal(err)
	}
	body, _ = io.ReadAll(response.Body)
	response.Body.Close()
	if !strings.Contains(string(body), `"KeyA": 4`) {
		t.Errorf("keycodes.json not served: %.80s", body)
	}
}

func TestWebSocketOriginMatrixTailnet(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	cases := []struct {
		name   string
		dial   dialOptions
		status int // 0 = upgrade succeeds
	}{
		{"cli without origin", dialOptions{}, 0},
		{"same origin", dialOptions{origin: "http://" + h.host}, 0},
		{"null origin", dialOptions{origin: "null"}, http.StatusForbidden},
		{"cross origin", dialOptions{origin: "http://evil.example"}, http.StatusForbidden},
		{"other port", dialOptions{origin: "http://127.0.0.1:1"}, http.StatusForbidden},
		{"foreign host", dialOptions{host: "evil.example"}, http.StatusMisdirectedRequest},
	}
	for _, testCase := range cases {
		conn, response, err := h.dial(testCase.dial)
		if testCase.status == 0 {
			if err != nil {
				t.Errorf("%s: dial failed: %v", testCase.name, err)
				continue
			}
			conn.CloseNow()
			continue
		}
		if err == nil {
			t.Errorf("%s: upgrade succeeded, want HTTP %d", testCase.name, testCase.status)
			conn.CloseNow()
			continue
		}
		if response == nil || response.StatusCode != testCase.status {
			got := 0
			if response != nil {
				got = response.StatusCode
			}
			t.Errorf("%s: HTTP %d, want %d", testCase.name, got, testCase.status)
		}
	}
}

func TestWebSocketCredentialsHotspot(t *testing.T) {
	h := newHarness(t, TransportHotspot, nil)
	token, hash := newToken()
	origin := "http://" + h.host
	cases := []struct {
		name   string
		dial   dialOptions
		status int
	}{
		{"browser with cookie", dialOptions{origin: origin, cookie: token}, 0},
		{"cli with bearer", dialOptions{bearer: token}, 0},
		{"browser with bearer only", dialOptions{origin: origin, bearer: token}, http.StatusUnauthorized},
		{"cli with cookie only (cookie ignored)", dialOptions{cookie: token}, http.StatusUnauthorized},
		{"nothing", dialOptions{origin: origin}, http.StatusUnauthorized},
		{"malformed token", dialOptions{bearer: "short"}, http.StatusUnauthorized},
		{"cross-origin cookie", dialOptions{origin: "http://evil.example", cookie: token}, http.StatusForbidden},
	}
	for _, testCase := range cases {
		conn, response, err := h.dial(testCase.dial)
		if testCase.status == 0 {
			if err != nil {
				t.Errorf("%s: %v", testCase.name, err)
				continue
			}
			conn.CloseNow()
			continue
		}
		if err == nil {
			conn.CloseNow()
			t.Errorf("%s: upgrade succeeded, want %d", testCase.name, testCase.status)
			continue
		}
		if response == nil || response.StatusCode != testCase.status {
			t.Errorf("%s: response %v, want %d", testCase.name, response, testCase.status)
		}
	}
	// Only the hash crossed to Kotlin, never the token.
	h.kotlin.snapshot(func(k *fakeKotlin) {
		if len(k.opens) != 2 {
			t.Errorf("got %d opens, want 2", len(k.opens))
		}
		for _, connJSON := range k.opens {
			if strings.Contains(connJSON, token) || !strings.Contains(connJSON, hash) {
				t.Errorf("conn JSON must carry the token hash only: %s", connJSON)
			}
		}
	})
}
