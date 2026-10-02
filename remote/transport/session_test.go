package transport

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

func frame(epoch uint16, op protocol.FrameOp, args ...byte) []byte {
	return append([]byte{byte(epoch >> 8), byte(epoch), byte(op)}, args...)
}

func TestFailsClosedBeforeAuthorized(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	conn, id := h.mustDial(dialOptions{})

	writeJSON(t, conn, `{"id":7,"t":"state.get"}`)
	reply := readJSON(t, conn)
	if errorCode(reply) != protocol.ErrorNotAuthorized || reply["id"] != float64(7) {
		t.Fatalf("pre-auth request got %v", reply)
	}
	if err := writeBinary(t, conn, frame(0, protocol.OpKeyDown, 0x04)); err != nil {
		t.Fatal(err)
	}
	writeJSON(t, conn, hello())
	eventually(t, "hello forwarded", func() bool { return len(h.kotlin.commandsOf(id)) == 1 })
	writeJSON(t, conn, `{"id":2,"t":"hello","v":1}`)
	if reply := readJSON(t, conn); errorCode(reply) != protocol.ErrorNotAuthorized {
		t.Fatalf("second pre-auth hello got %v", reply)
	}
	if commands := h.kotlin.commandsOf(id); len(commands) != 1 || !strings.Contains(commands[0], `"hello"`) {
		t.Fatalf("only the first hello may reach Kotlin, got %q", commands)
	}
	if frames := h.kotlin.framesOf(id); len(frames) != 0 {
		t.Fatalf("HID frames reached Kotlin before Authorized: %v", frames)
	}

	if !h.server.Authorized(id) {
		t.Fatal("Authorized returned false for a live session")
	}
	writeJSON(t, conn, `{"id":3,"t":"state.get"}`)
	if err := writeBinary(t, conn, frame(1, protocol.OpKeyDown, 0x04)); err != nil {
		t.Fatal(err)
	}
	eventually(t, "forwarding after Authorized", func() bool {
		return len(h.kotlin.commandsOf(id)) == 2 && len(h.kotlin.framesOf(id)) == 1
	})
	if h.server.Authorized(9999) || h.server.Send(9999, `{}`) || h.server.Close(9999, "x") {
		t.Fatal("unknown sessions must report false")
	}
}

func TestClientCannotForgePairCommand(t *testing.T) {
	h := newHarness(t, TransportHotspot, nil)
	token, _ := newToken()
	conn, id := h.mustDial(dialOptions{bearer: token})
	h.server.Authorized(id)
	writeJSON(t, conn, `{"id":1,"t":"pair","code":"123456","tokenHash":"x"}`)
	if reply := readJSON(t, conn); errorCode(reply) != protocol.ErrorBadRequest {
		t.Fatalf("got %v", reply)
	}
	if commands := h.kotlin.commandsOf(id); len(commands) != 0 {
		t.Fatalf("pair reached Kotlin: %q", commands)
	}
}

func TestOpenCarriesBoundIdentity(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	_, id := h.mustDial(dialOptions{origin: "http://" + h.host})
	var info connInfo
	h.kotlin.snapshot(func(k *fakeKotlin) {
		if err := json.Unmarshal([]byte(k.opens[id]), &info); err != nil {
			t.Fatal(err)
		}
	})
	if info.Transport != TransportTailnet || info.Identity.NodeID != testNodeID || info.Identity.Kind != IdentityNode || !info.Browser {
		t.Fatalf("conn info %+v", info)
	}
}

func TestMalformedInputClosesSession(t *testing.T) {
	cases := []struct {
		name   string
		send   func(*testing.T, *websocket.Conn)
		code   websocket.StatusCode
		reason string
	}{
		{"short frame", func(t *testing.T, conn *websocket.Conn) { writeBinary(t, conn, []byte{0, 1}) }, protocol.CloseMalformed, protocol.ReasonMalformed},
		{"long key frame", func(t *testing.T, conn *websocket.Conn) { writeBinary(t, conn, frame(1, protocol.OpKeyDown, 4, 0)) }, protocol.CloseMalformed, protocol.ReasonMalformed},
		{"unknown op", func(t *testing.T, conn *websocket.Conn) { writeBinary(t, conn, frame(1, 0x09)) }, protocol.CloseMalformed, protocol.ReasonMalformed},
		{"not json", func(t *testing.T, conn *websocket.Conn) { writeJSON(t, conn, `nope`) }, protocol.CloseMalformed, protocol.ReasonMalformed},
		{"bad type", func(t *testing.T, conn *websocket.Conn) { writeJSON(t, conn, `{"t":"State.Get"}`) }, protocol.CloseMalformed, protocol.ReasonMalformed},
		{"version", func(t *testing.T, conn *websocket.Conn) { writeJSON(t, conn, `{"id":1,"t":"hello","v":2}`) }, protocol.CloseVersion, protocol.ReasonVersion},
		{"oversized", func(t *testing.T, conn *websocket.Conn) {
			writeJSON(t, conn, `{"t":"paste.prepare","text":"`+strings.Repeat("a", protocol.MessageBytesMax)+`"}`)
		}, websocket.StatusMessageTooBig, protocol.ReasonMalformed},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			h := newHarness(t, TransportTailnet, nil)
			conn, id := h.mustDial(dialOptions{})
			h.server.Authorized(id)
			testCase.send(t, conn)
			if code := readUntilClosed(t, conn); code != testCase.code {
				t.Errorf("close code %d, want %d", code, testCase.code)
			}
			eventually(t, "Closed", func() bool { return len(h.kotlin.closedOf(id)) == 1 })
			if reasons := h.kotlin.closedOf(id); reasons[0] != testCase.reason {
				t.Errorf("Closed reason %q, want %q", reasons[0], testCase.reason)
			}
			if frames := h.kotlin.framesOf(id); len(frames) != 0 {
				t.Errorf("malformed frame forwarded: %v", frames)
			}
		})
	}
}

func TestHidRateLimitClosesSessionButReleaseAllIsExempt(t *testing.T) {
	now := time.Unix(1_000, 0)
	clock := func() time.Time { return now } // frozen: no refill during the test
	h := newHarness(t, TransportTailnet, func(config *Config, _ *Endpoint) { config.Now = clock })
	conn, id := h.mustDial(dialOptions{})
	h.server.Authorized(id)

	for range 500 {
		if err := writeBinary(t, conn, frame(3, protocol.OpReleaseAll)); err != nil {
			t.Fatal(err)
		}
	}
	if err := writeBinary(t, conn, frame(4, protocol.OpReleaseAll)); err != nil {
		t.Fatal(err)
	}
	for index := range protocol.HidFramesPerSecondMax {
		if err := writeBinary(t, conn, frame(4, protocol.OpMouseMove, byte(index), 0)); err != nil {
			t.Fatal(err)
		}
	}
	eventually(t, "frames forwarded", func() bool { return len(h.kotlin.framesOf(id)) == 2+protocol.HidFramesPerSecondMax })
	frames := h.kotlin.framesOf(id)
	if !bytes.Equal(frames[0], frame(3, protocol.OpReleaseAll)) || !bytes.Equal(frames[1], frame(4, protocol.OpReleaseAll)) {
		t.Fatalf("release-all with the same epoch must coalesce, new epoch must pass: %v", frames[:2])
	}
	if len(h.kotlin.closedOf(id)) != 0 {
		t.Fatal("closed before exceeding the limit")
	}
	writeBinary(t, conn, frame(4, protocol.OpMouseMove, 1, 1))
	if code := readUntilClosed(t, conn); code != protocol.CloseRateLimited {
		t.Fatalf("close code %d, want %d", code, protocol.CloseRateLimited)
	}
	eventually(t, "Closed", func() bool { return len(h.kotlin.closedOf(id)) == 1 })
	if reason := h.kotlin.closedOf(id)[0]; reason != protocol.ReasonRateLimited {
		t.Fatalf("reason %q", reason)
	}
	if got := len(h.kotlin.framesOf(id)); got != 2+protocol.HidFramesPerSecondMax {
		t.Fatalf("over-limit frame forwarded: %d frames", got)
	}
}

func TestTokenBucketRefills(t *testing.T) {
	start := time.Unix(0, 0)
	bucket := newTokenBucket(200, 200, start)
	for index := range 200 {
		if !bucket.allow(start) {
			t.Fatalf("frame %d refused within the burst", index)
		}
	}
	if bucket.allow(start) {
		t.Fatal("frame 201 in the same instant allowed")
	}
	if !bucket.allow(start.Add(5*time.Millisecond)) || bucket.allow(start.Add(5*time.Millisecond)) {
		t.Fatal("5ms at 200/s refills exactly one frame")
	}
	if !bucket.allow(start.Add(10 * time.Second)) {
		t.Fatal("bucket did not refill")
	}
	count := 1
	for bucket.allow(start.Add(10 * time.Second)) {
		count++
	}
	if count != 200 {
		t.Fatalf("refill capped at %d, want 200", count)
	}
}

func TestConnectionCaps(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	var pending []int64
	for range protocol.PendingConnectionCountMax {
		_, id := h.mustDial(dialOptions{})
		pending = append(pending, id)
	}
	if _, response, err := h.dial(dialOptions{}); err == nil || response.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("fifth pending connection: err %v", err)
	}
	for _, id := range pending {
		h.server.Authorized(id)
	}
	for range protocol.ConnectionCountMax - protocol.PendingConnectionCountMax {
		_, id := h.mustDial(dialOptions{})
		h.server.Authorized(id)
	}
	if _, response, err := h.dial(dialOptions{}); err == nil || response.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("ninth connection: err %v", err)
	}
	if count := h.server.SessionCount(); count != protocol.ConnectionCountMax {
		t.Fatalf("session count %d", count)
	}
}

func TestDeadPeerIsDetected(t *testing.T) {
	h := newHarness(t, TransportTailnet, func(config *Config, _ *Endpoint) {
		config.PingInterval = 20 * time.Millisecond
		config.DeadAfter = 120 * time.Millisecond
		config.LivenessCheckInterval = 10 * time.Millisecond
	})
	// A client that keeps reading answers pings and stays alive.
	alive, aliveID := h.mustDial(dialOptions{})
	go func() {
		for {
			if _, _, err := alive.Read(context.Background()); err != nil {
				return
			}
		}
	}()
	// A client that never reads never answers a ping.
	_, deadID := h.mustDial(dialOptions{})
	eventually(t, "dead peer closed", func() bool { return len(h.kotlin.closedOf(deadID)) == 1 })
	if reason := h.kotlin.closedOf(deadID)[0]; reason != protocol.ReasonDead {
		t.Fatalf("reason %q, want dead", reason)
	}
	time.Sleep(200 * time.Millisecond)
	if closed := h.kotlin.closedOf(aliveID); len(closed) != 0 {
		t.Fatalf("live peer closed: %v", closed)
	}
}

func TestSlowConsumerIsDisconnected(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	_, id := h.mustDial(dialOptions{}) // never reads
	big := `{"t":"state","pad":"` + strings.Repeat("x", 60<<10) + `"}`
	sent := 0
	for ; sent < 4_000; sent++ {
		if !h.server.Send(id, big) {
			break
		}
	}
	if sent == 4_000 {
		t.Fatal("outbound queue never filled")
	}
	eventually(t, "Closed", func() bool { return len(h.kotlin.closedOf(id)) == 1 })
	if reason := h.kotlin.closedOf(id)[0]; reason != protocol.ReasonSlowConsumer {
		t.Fatalf("reason %q", reason)
	}
	if h.server.Send(id, `{"t":"state"}`) {
		t.Fatal("Send after close must fail")
	}
}

func TestSendRejectsInvalidJSON(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	_, id := h.mustDial(dialOptions{})
	if h.server.Send(id, `{"t":`) {
		t.Fatal("invalid JSON accepted")
	}
	if h.server.Send(id, `"`+strings.Repeat("a", protocol.MessageBytesMax)+`"`) {
		t.Fatal("oversized message accepted")
	}
}

func TestKotlinCloseFlushesThenClosesOnce(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	conn, id := h.mustDial(dialOptions{})
	h.server.Send(id, `{"t":"welcome","v":1}`)
	h.server.Send(id, `{"t":"state","rev":1}`)
	h.server.Close(id, protocol.ReasonRevoked)
	h.server.Close(id, "again")
	if first := readJSON(t, conn); first["t"] != "welcome" {
		t.Fatalf("first %v", first)
	}
	if second := readJSON(t, conn); second["t"] != "state" {
		t.Fatalf("second %v", second)
	}
	if code := readUntilClosed(t, conn); code != protocol.CloseUnauthorized {
		t.Fatalf("close code %d, want %d", code, protocol.CloseUnauthorized)
	}
	eventually(t, "Closed", func() bool { return len(h.kotlin.closedOf(id)) == 1 })
	time.Sleep(50 * time.Millisecond)
	if reasons := h.kotlin.closedOf(id); len(reasons) != 1 || reasons[0] != protocol.ReasonRevoked {
		t.Fatalf("Closed calls %v, want exactly [revoked]", reasons)
	}
}

func TestRevokeClosesEveryMatchingSession(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	first, firstID := h.mustDial(dialOptions{})
	second, secondID := h.mustDial(dialOptions{})
	h.server.Authorized(firstID)
	if count := h.server.Revoke(RevokeToken, testNodeID); count != 0 {
		t.Fatalf("token revoke matched node sessions: %d", count)
	}
	if count := h.server.Revoke(RevokeNode, testNodeID); count != 2 {
		t.Fatalf("revoked %d sessions, want 2", count)
	}
	// Nothing is forwarded once revoked, even before the socket closes.
	writeJSON(t, first, `{"id":5,"t":"state.get"}`)
	for _, conn := range []*websocket.Conn{first, second} {
		if code := readUntilClosed(t, conn); code != protocol.CloseUnauthorized {
			t.Fatalf("close code %d", code)
		}
	}
	eventually(t, "both Closed", func() bool {
		return len(h.kotlin.closedOf(firstID)) == 1 && len(h.kotlin.closedOf(secondID)) == 1
	})
	if commands := h.kotlin.commandsOf(firstID); len(commands) != 0 {
		t.Fatalf("forwarded after revoke: %q", commands)
	}
}

// The fake calls back into the server from inside every callback; with any
// lock held across a callback this would deadlock.
func TestCallbacksMayReenterTheServer(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	h.kotlin.onOpen = func(id int64, _ string) {
		h.server.Authorized(id)
		h.server.Send(id, fmt.Sprintf(`{"t":"welcome","v":1,"sessionId":%d}`, id))
		h.server.SessionCount()
	}
	h.kotlin.onCommand = func(id int64, message string) {
		h.server.Send(id, `{"id":1,"ok":true}`)
		if strings.Contains(message, "lease.release") {
			h.server.Close(id, "bye")
		}
	}
	h.kotlin.onClosed = func(id int64, _ string) {
		if h.server.Send(id, `{}`) {
			t.Error("Send to a closed session succeeded")
		}
		h.server.Revoke(RevokeNode, "nobody")
		h.server.SessionCount()
	}
	conn, id := h.mustDial(dialOptions{})
	if welcome := readJSON(t, conn); welcome["sessionId"] != float64(id) {
		t.Fatalf("welcome %v", welcome)
	}
	writeJSON(t, conn, `{"id":1,"t":"lease.release"}`)
	if reply := readJSON(t, conn); reply["ok"] != true {
		t.Fatalf("reply %v", reply)
	}
	if code := readUntilClosed(t, conn); code != protocol.CloseServer {
		t.Fatalf("close code %d", code)
	}
	eventually(t, "Closed", func() bool { return len(h.kotlin.closedOf(id)) == 1 })
}

func TestClientDisconnectReportsClosedOnceAfterCommands(t *testing.T) {
	h := newHarness(t, TransportTailnet, nil)
	conn, id := h.mustDial(dialOptions{})
	h.server.Authorized(id)
	for index := range 20 {
		writeJSON(t, conn, fmt.Sprintf(`{"id":%d,"t":"state.get"}`, index))
	}
	conn.Close(websocket.StatusNormalClosure, "")
	eventually(t, "Closed", func() bool { return len(h.kotlin.closedOf(id)) == 1 })
	if commands := h.kotlin.commandsOf(id); len(commands) != 20 {
		t.Fatalf("Closed arrived before all commands: %d", len(commands))
	}
	h.server.Close(id, "late")
	time.Sleep(20 * time.Millisecond)
	if reasons := h.kotlin.closedOf(id); len(reasons) != 1 || reasons[0] != protocol.ReasonClientClosed {
		t.Fatalf("Closed calls %v", reasons)
	}
}

func TestListenerStopClosesSessionsAndWaits(t *testing.T) {
	h := newHarness(t, TransportHotspot, nil)
	token, _ := newToken()
	_, firstID := h.mustDial(dialOptions{bearer: token})
	_, secondID := h.mustDial(dialOptions{bearer: token})
	h.listener.Stop()
	for _, id := range []int64{firstID, secondID} {
		if reasons := h.kotlin.closedOf(id); len(reasons) != 1 || reasons[0] != protocol.ReasonListenerStopped {
			t.Fatalf("session %d Closed %v when Stop returned", id, reasons)
		}
	}
	if h.server.SessionCount() != 0 {
		t.Fatal("sessions left after Stop")
	}
	if _, _, err := h.dial(dialOptions{bearer: token}); err == nil {
		t.Fatal("dial after Stop succeeded")
	}
}
