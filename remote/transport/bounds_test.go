package transport

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

func TestWhoIsIdentityIsBoundedBeforeOpen(t *testing.T) {
	text := func(count int, unit string) string { return strings.Repeat(unit, count) }
	tags := func(count int, tag string) []string {
		result := make([]string, count)
		for index := range result {
			result[index] = tag
		}
		return result
	}
	cases := []struct {
		name     string
		identity Identity
		accepted bool
	}{
		{"at the limits", Identity{Name: text(256, "n"), User: text(256, "u"), Tags: tags(32, "tag:"+text(252, "t"))}, true},
		{"astral characters count twice, as in Kotlin", Identity{Name: text(128, "\U0001F600")}, true},
		{"too many tags", Identity{Tags: tags(33, "tag:x")}, false},
		{"name too long", Identity{Name: text(257, "n")}, false},
		{"tag too long", Identity{Tags: []string{text(257, "t")}}, false},
		{"astral name too long", Identity{Name: text(129, "\U0001F600")}, false},
		{"invalid UTF-8", Identity{User: "user\xff@example.com"}, false},
		// Each control character escapes to six bytes: within every per-field bound, but over 16 KiB of JSON.
		{"connJson too large", Identity{Name: text(256, "\x01"), User: text(256, "\x01"), Tags: tags(32, text(256, "\x01"))}, false},
	}
	for _, testCase := range cases {
		identity := testCase.identity
		identity.Kind = IdentityNode
		identity.NodeID = testNodeID
		h := newHarness(t, TransportTailnet, func(_ *Config, endpoint *Endpoint) {
			endpoint.Identify = func(context.Context, string) (Identity, error) { return identity, nil }
		})
		conn, response, err := h.dial(dialOptions{})
		if testCase.accepted {
			if err != nil {
				t.Errorf("%s: dial failed: %v", testCase.name, err)
				continue
			}
			eventually(t, testCase.name+": Open", func() bool { return len(h.kotlin.openIDs()) == 1 })
			var opened string
			h.kotlin.snapshot(func(k *fakeKotlin) {
				for _, connJSON := range k.opens {
					opened = connJSON
				}
			})
			if len(opened) > protocol.ConnJSONBytesMax {
				t.Errorf("%s: connJson of %d bytes", testCase.name, len(opened))
			}
			conn.CloseNow()
			continue
		}
		if err == nil {
			conn.CloseNow()
			t.Errorf("%s: upgrade succeeded", testCase.name)
			continue
		}
		if response == nil || response.StatusCode != http.StatusForbidden {
			t.Errorf("%s: want HTTP 403, got %v", testCase.name, response)
		}
		if opens := len(h.kotlin.openIDs()); opens != 0 || h.server.SessionCount() != 0 {
			t.Errorf("%s: %d opens, %d sessions; nothing may reach Kotlin", testCase.name, opens, h.server.SessionCount())
		}
	}
}

func TestPairBodyTricklingIsCutOff(t *testing.T) {
	const bodyTimeout = 200 * time.Millisecond
	h := newHarness(t, TransportHotspot, func(config *Config, _ *Endpoint) { config.PairBodyTimeout = bodyTimeout })
	conn, err := net.Dial("tcp", h.host)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	body := `{"code":"123456"}`
	fmt.Fprintf(conn, "POST /pair HTTP/1.1\r\nHost: %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n", h.host, len(body))
	start := time.Now()
	// One byte every 100ms: the whole body would take 1.7s.
	go func() {
		for index := range len(body) {
			if _, err := conn.Write([]byte{body[index]}); err != nil {
				return
			}
			time.Sleep(100 * time.Millisecond)
		}
	}()
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	reader := bufio.NewReader(conn)
	response, err := http.ReadResponse(reader, nil)
	if err != nil {
		t.Fatalf("no response to a trickled body: %v", err)
	}
	io.Copy(io.Discard, response.Body)
	response.Body.Close()
	if response.StatusCode != http.StatusBadRequest {
		t.Errorf("trickled body got HTTP %d, want 400", response.StatusCode)
	}
	if elapsed := time.Since(start); elapsed > bodyTimeout+500*time.Millisecond {
		t.Errorf("answered after %v; the body deadline is %v", elapsed, bodyTimeout)
	}
	// The connection (and its slot) is released, not kept for another request.
	if _, err := reader.ReadByte(); err == nil {
		t.Error("connection still open after a timed-out body")
	}
	if opens := len(h.kotlin.openIDs()); opens != 0 {
		t.Errorf("%d pair sessions reached Kotlin", opens)
	}
}

func TestPairBodyDeadlineDoesNotCutKotlinsAnswerShort(t *testing.T) {
	h := newHarness(t, TransportHotspot, func(config *Config, _ *Endpoint) {
		config.PairBodyTimeout = 50 * time.Millisecond
		config.PairTimeout = 2 * time.Second
	})
	// Kotlin takes much longer than the body deadline to decide.
	h.kotlin.onCommand = func(id int64, _ string) {
		go func() {
			time.Sleep(300 * time.Millisecond)
			h.server.Send(id, `{"ok":true}`)
		}()
	}
	response, reply := postPair(t, h, `{"code":"123456"}`, "")
	if response.StatusCode != http.StatusOK || !reply.OK || !validToken(reply.Token) {
		t.Fatalf("pair: %d %+v", response.StatusCode, reply)
	}
}

func TestWebSocketHasNoReadDeadline(t *testing.T) {
	h := newHarness(t, TransportTailnet, func(config *Config, _ *Endpoint) { config.PairBodyTimeout = 50 * time.Millisecond })
	conn, id := h.mustDial(dialOptions{})
	time.Sleep(200 * time.Millisecond)
	writeJSON(t, conn, hello())
	eventually(t, "hello reaches Kotlin", func() bool { return len(h.kotlin.commandsOf(id)) == 1 })
}
