package transport

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

func postPair(t *testing.T, h *harness, body, origin string) (*http.Response, pairResponse) {
	t.Helper()
	request, err := http.NewRequest(http.MethodPost, h.url("/pair"), strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	if origin != "" {
		request.Header.Set("Origin", origin)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var decoded pairResponse
	json.NewDecoder(response.Body).Decode(&decoded)
	return response, decoded
}

// kotlinAcceptsCode makes the fake answer pair commands: right code → ok.
func kotlinAcceptsCode(h *harness, code string) {
	h.kotlin.onCommand = func(id int64, message string) {
		var command struct {
			T         string `json:"t"`
			Code      string `json:"code"`
			TokenHash string `json:"tokenHash"`
		}
		json.Unmarshal([]byte(message), &command)
		if command.T != protocol.TypePair || len(command.TokenHash) != 64 {
			return
		}
		if command.Code == code {
			h.server.Send(id, `{"ok":true}`)
		} else {
			h.server.Send(id, `{"ok":false,"error":{"code":"unauthorized","msg":"wrong code"}}`)
		}
	}
}

func TestPairCLIThenBearer(t *testing.T) {
	h := newHarness(t, TransportHotspot, nil)
	kotlinAcceptsCode(h, "123456")
	response, reply := postPair(t, h, `{"code":"123456"}`, "")
	if response.StatusCode != http.StatusOK || !reply.OK || !validToken(reply.Token) {
		t.Fatalf("pair: %d %+v", response.StatusCode, reply)
	}
	if len(response.Cookies()) != 0 {
		t.Fatal("the CLI path must not set a cookie")
	}
	// The pair session was opened and closed, and Kotlin only saw the hash.
	var pairID int64
	h.kotlin.snapshot(func(k *fakeKotlin) {
		for id, connJSON := range k.opens {
			pairID = id
			if strings.Contains(connJSON, reply.Token) || !strings.Contains(connJSON, tokenHash(reply.Token)) {
				t.Errorf("pair conn JSON %s", connJSON)
			}
		}
		for _, command := range k.commands[pairID] {
			if strings.Contains(command, reply.Token) {
				t.Error("token leaked to Kotlin")
			}
		}
	})
	if reasons := h.kotlin.closedOf(pairID); len(reasons) != 1 || reasons[0] != protocol.ReasonPairDone {
		t.Fatalf("pair session Closed %v", reasons)
	}
	_, id := h.mustDial(dialOptions{bearer: reply.Token})
	h.kotlin.snapshot(func(k *fakeKotlin) {
		if !strings.Contains(k.opens[id], tokenHash(reply.Token)) {
			t.Errorf("ws identity %s", k.opens[id])
		}
	})
}

func TestPairBrowserGetsHttpOnlyCookie(t *testing.T) {
	h := newHarness(t, TransportHotspot, nil)
	kotlinAcceptsCode(h, "654321")
	response, reply := postPair(t, h, `{"code":"654321"}`, "http://"+h.host)
	if response.StatusCode != http.StatusOK || !reply.OK || reply.Token != "" {
		t.Fatalf("pair: %d %+v", response.StatusCode, reply)
	}
	cookies := response.Cookies()
	if len(cookies) != 1 {
		t.Fatalf("cookies %v", cookies)
	}
	cookie := cookies[0]
	if cookie.Name != tokenCookieName || !cookie.HttpOnly || cookie.SameSite != http.SameSiteStrictMode || cookie.Domain != "" || cookie.Path != "/" || !validToken(cookie.Value) {
		t.Fatalf("cookie %+v", cookie)
	}
	h.mustDial(dialOptions{origin: "http://" + h.host, cookie: cookie.Value})
}

func TestPairRejections(t *testing.T) {
	h := newHarness(t, TransportHotspot, func(config *Config, _ *Endpoint) {})
	kotlinAcceptsCode(h, "123456")
	cases := []struct {
		name, body, origin string
		status             int
	}{
		{"wrong code", `{"code":"000000"}`, "", http.StatusForbidden},
		{"short code", `{"code":"12345"}`, "", http.StatusBadRequest},
		{"letters", `{"code":"12345a"}`, "", http.StatusBadRequest},
		{"not json", `code=123456`, "", http.StatusBadRequest},
		{"trailing object", `{"code":"123456"}{"code":"123456"}`, "", http.StatusBadRequest},
		{"trailing garbage", `{"code":"123456"} x`, "", http.StatusBadRequest},
		{"huge", `{"code":"123456","pad":"` + strings.Repeat("a", 2<<10) + `"}`, "", http.StatusBadRequest},
		{"cross origin", `{"code":"123456"}`, "http://evil.example", http.StatusForbidden},
		{"null origin", `{"code":"123456"}`, "null", http.StatusForbidden},
	}
	for _, testCase := range cases {
		response, reply := postPair(t, h, testCase.body, testCase.origin)
		if response.StatusCode != testCase.status || reply.OK || reply.Token != "" {
			t.Errorf("%s: %d %+v, want %d", testCase.name, response.StatusCode, reply, testCase.status)
		}
	}
	// Only the well-formed same-origin attempt reached Kotlin.
	if opens := len(h.kotlin.openIDs()); opens != 1 {
		t.Errorf("%d pair sessions reached Kotlin, want 1", opens)
	}
}

func TestPairTimesOutWhenKotlinIsSilent(t *testing.T) {
	h := newHarness(t, TransportHotspot, nil)
	response, reply := postPair(t, h, `{"code":"123456"}`, "")
	if response.StatusCode != http.StatusGatewayTimeout || reply.OK {
		t.Fatalf("got %d %+v", response.StatusCode, reply)
	}
	if h.server.SessionCount() != 0 {
		t.Fatal("pair session leaked")
	}
}

func TestPairIsHotspotOnly(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	response, _ := postPair(t, h, `{"code":"123456"}`, "")
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("tailnet /pair got %d", response.StatusCode)
	}
}

func TestTransportInfo(t *testing.T) {
	h := newHarness(t, TransportHotspot, nil)
	response, err := http.Get(h.url("/api/transport"))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var info map[string]any
	json.NewDecoder(response.Body).Decode(&info)
	if info["transport"] != TransportHotspot || info["hasToken"] != false {
		t.Fatalf("info %v", info)
	}
}
