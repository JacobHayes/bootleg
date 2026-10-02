package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/JacobHayes/bootleg/remote/protocol"
	"github.com/JacobHayes/bootleg/remote/transport"
)

func TestParseArgs(t *testing.T) {
	t.Setenv("BOOTLEG_HOST", "")
	t.Setenv("BOOTLEG_TOKEN_FILE", "")
	parsed, err := parseArgs([]string{"mount", "debian.iso", "--lun", "2", "--mode=cd", "--takeover"})
	if err != nil {
		t.Fatal(err)
	}
	if parsed.command != "mount" || parsed.args[0] != "debian.iso" || parsed.lun != 2 || parsed.mode != "cd" || !parsed.takeover || parsed.host != defaultHost {
		t.Fatalf("parsed %+v", parsed)
	}
	parsed, err = parseArgs([]string{"--host", "192.168.49.1:8080", "--token-file", "/x", "type", "-"})
	if err != nil || parsed.args[0] != "-" || parsed.host != "192.168.49.1:8080" || parsed.tokenFile != "/x" {
		t.Fatalf("parsed %+v, %v", parsed, err)
	}
	parsed, err = parseArgs([]string{"type", "--", "--not-a-flag"})
	if err != nil || parsed.args[0] != "--not-a-flag" {
		t.Fatalf("-- handling: %+v, %v", parsed, err)
	}
	parsed, err = parseArgs([]string{"key", "ctrl+alt+delete", "--repeat", "3", "--interval", "50ms"})
	if err != nil || parsed.repeat != 3 || parsed.interval != 50*time.Millisecond {
		t.Fatalf("key flags: %+v, %v", parsed, err)
	}
	t.Setenv("BOOTLEG_HOST", "phone.example")
	if parsed, _ := parseArgs([]string{"status"}); parsed.host != "phone.example" {
		t.Fatalf("BOOTLEG_HOST ignored: %q", parsed.host)
	}
	t.Setenv("BOOTLEG_HOST", "")

	invalid := [][]string{
		{},
		{"frobnicate"},
		{"status", "extra"},
		{"mount"},
		{"hid", "maybe"},
		{"lease", "steal"},
		{"key", "ctrl+nope"},
		{"key", "a", "--repeat", "0"},
		{"key", "a", "--repeat", "101"},
		{"key", "a", "--interval", "1ms"},
		{"mount", "x", "--lun", "8"},
		{"mount", "x", "--mode", "floppy"},
		{"pair", "12345", "--host", "192.168.49.1:8080"},
		{"pair", "123456"}, // no --host
		{"bootkey", "dell", "bios"},
		{"status", "--bogus"},
		{"status", "--host"},
		{"status", "--timeout", "-1s"},
	}
	for _, args := range invalid {
		if _, err := parseArgs(args); err == nil {
			t.Errorf("%q accepted", args)
		}
	}
	var stdout, stderr bytes.Buffer
	if code := run(context.Background(), []string{"frobnicate"}, nil, &stdout, &stderr); code != exitUsage {
		t.Errorf("unknown command exit %d", code)
	}
	if code := run(context.Background(), []string{"--help"}, nil, &stdout, &stderr); code != exitOK || !strings.Contains(stdout.String(), "usage: bootleg") {
		t.Errorf("--help exit %d", code)
	}
}

func TestParseChord(t *testing.T) {
	cases := map[string]chord{
		"ctrl+alt+delete": {modifiers: []byte{0xE0, 0xE2}, key: 0x4C},
		"cmd+space":       {modifiers: []byte{0xE3}, key: 0x2C},
		"CMD+Tab":         {modifiers: []byte{0xE3}, key: 0x2B},
		"f12":             {key: 0x45},
		"F24":             {key: 0x73},
		"shift+a":         {modifiers: []byte{0xE1}, key: 0x04},
		"esc":             {key: 0x29},
		"KeyZ":            {key: 0x1D},
		"numpadenter":     {key: 0x58},
		"cmd":             {key: 0xE3},
		"ctrl+shift":      {modifiers: []byte{0xE0}, key: 0xE1},
		"alt+/":           {modifiers: []byte{0xE2}, key: 0x38},
		"9":               {key: 0x26},
	}
	for text, want := range cases {
		got, err := parseChord(text)
		if err != nil {
			t.Errorf("%s: %v", text, err)
			continue
		}
		if !bytes.Equal(got.modifiers, want.modifiers) || got.key != want.key {
			t.Errorf("%s = %+v, want %+v", text, got, want)
		}
	}
	for _, text := range []string{"", "ctrl+", "+a", "a+ctrl", "ctrl+ctrl+a", "f25", "hyper+a", "a+b", strings.Repeat("ctrl+", 9) + "a"} {
		if _, err := parseChord(text); err == nil {
			t.Errorf("%q accepted", text)
		}
	}
	frames := cases["ctrl+alt+delete"].frames(7)
	var ops []string
	for _, frame := range frames {
		if frame.Epoch != 7 {
			t.Fatal("epoch not carried")
		}
		ops = append(ops, string(rune('0'+frame.Op))+":"+string(rune(frame.Usage)))
	}
	want := []protocol.Frame{
		{Op: protocol.OpKeyDown, Usage: 0xE0}, {Op: protocol.OpKeyDown, Usage: 0xE2},
		{Op: protocol.OpKeyDown, Usage: 0x4C}, {Op: protocol.OpKeyUp, Usage: 0x4C},
		{Op: protocol.OpKeyUp, Usage: 0xE2}, {Op: protocol.OpKeyUp, Usage: 0xE0},
	}
	for index := range want {
		if frames[index].Op != want[index].Op || frames[index].Usage != want[index].Usage {
			t.Fatalf("frames %v", ops)
		}
	}
}

func TestCharacterAndEscapeChords(t *testing.T) {
	cases := map[rune]chord{
		'a':  {key: 0x04},
		'A':  {modifiers: []byte{0xE1}, key: 0x04},
		'!':  {modifiers: []byte{0xE1}, key: 0x1E},
		'\r': {key: 0x28},
		0x7f: {key: 0x2A},
		0x03: {modifiers: []byte{0xE0}, key: 0x06}, // Ctrl-C
		'?':  {modifiers: []byte{0xE1}, key: 0x38},
		' ':  {key: 0x2C},
	}
	for character, want := range cases {
		got, ok := characterChord(character)
		if !ok || !bytes.Equal(got.modifiers, want.modifiers) || got.key != want.key {
			t.Errorf("%q = %+v (%v), want %+v", character, got, ok, want)
		}
	}
	if _, ok := characterChord('é'); ok {
		t.Error("non-US character mapped")
	}
	escapes := map[string]chord{
		"":     {key: 0x29},
		"[A":   {key: 0x52},
		"[3~":  {key: 0x4C},
		"OP":   {key: 0x3A},
		"[24~": {key: 0x45},
		"[Z":   {modifiers: []byte{0xE1}, key: 0x2B},
		"x":    {modifiers: []byte{0xE2}, key: 0x1B},
	}
	for sequence, want := range escapes {
		got, ok := escapeChord(sequence)
		if !ok || !bytes.Equal(got.modifiers, want.modifiers) || got.key != want.key {
			t.Errorf("ESC %q = %+v (%v), want %+v", sequence, got, ok, want)
		}
	}
	if _, ok := escapeChord("[99~"); ok {
		t.Error("unknown sequence mapped")
	}
}

func TestExitCodes(t *testing.T) {
	cases := map[error]int{
		nil:                           exitOK,
		usageErrorf("x"):              exitUsage,
		errPending:                    exitAuth,
		context.DeadlineExceeded:      exitTimeout,
		&httpStatusError{status: 401}: exitAuth,
		&httpStatusError{status: 503}: exitDevice,
		&protocolError{protocol.ErrorBody{Code: protocol.ErrorLeaseRequired}}: exitLease,
		&protocolError{protocol.ErrorBody{Code: protocol.ErrorStaleEpoch}}:    exitLease,
		&protocolError{protocol.ErrorBody{Code: protocol.ErrorRevoked}}:       exitAuth,
		&protocolError{protocol.ErrorBody{Code: protocol.ErrorDevice}}:        exitDevice,
		&protocolError{protocol.ErrorBody{Code: protocol.ErrorTimeout}}:       exitTimeout,
		&closeError{code: protocol.CloseUnauthorized}:                         exitAuth,
		&closeError{code: protocol.CloseServer}:                               exitDevice,
		&deviceError{errors.New("x")}:                                         exitDevice,
		errors.New("other"):                                                   exitFailure,
	}
	for err, want := range cases {
		if got := exitCodeFor(err); got != want {
			t.Errorf("%v → %d, want %d", err, got, want)
		}
	}
}

func TestTokenFilePermissions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "token")
	if err := writeToken(path, "secret-token"); err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat(path)
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("mode %o", info.Mode().Perm())
	}
	if token, err := readToken(path); err != nil || token != "secret-token" {
		t.Fatalf("read %q %v", token, err)
	}
	os.Chmod(path, 0o644)
	if _, err := readToken(path); exitCodeFor(err) != exitUsage {
		t.Fatalf("world-readable token file accepted: %v", err)
	}
}

// phoneServer runs the real transport with a fake phone behind it.
type phoneServer struct {
	phone     *fakePhone
	host      string
	tokenFile string
}

func startPhone(t *testing.T, transportKind string) *phoneServer {
	t.Helper()
	inner, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	host := inner.Addr().String()
	phone := newFakePhone()
	config := transport.DefaultConfig(nil)
	phone.server = transport.New(phone, config)
	endpoint := transport.Endpoint{Transport: transportKind, AllowedHosts: func() []string { return []string{host} }}
	if transportKind == transport.TransportTailnet {
		endpoint.Identify = func(context.Context, string) (transport.Identity, error) {
			return transport.Identity{Kind: transport.IdentityNode, NodeID: "nCLI"}, nil
		}
	}
	listener := phone.server.Serve(inner, endpoint)
	t.Cleanup(listener.Stop)
	served := &phoneServer{phone: phone, host: host}
	if transportKind == transport.TransportHotspot {
		served.tokenFile = filepath.Join(t.TempDir(), "token")
		token := strings.Repeat("A", protocol.TokenEncodedLength)
		if err := writeToken(served.tokenFile, token); err != nil {
			t.Fatal(err)
		}
	}
	return served
}

func (s *phoneServer) run(t *testing.T, stdin string, args ...string) (int, string, string) {
	t.Helper()
	full := []string{"--host", s.host}
	if s.tokenFile != "" {
		full = append(full, "--token-file", s.tokenFile)
	}
	full = append(full, args...)
	var stdout, stderr bytes.Buffer
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	code := run(ctx, full, strings.NewReader(stdin), &stdout, &stderr)
	return code, stdout.String(), stderr.String()
}

func (s *phoneServer) frames() [][]byte {
	var frames [][]byte
	s.phone.read(func(p *fakePhone) { frames = append(frames, p.frames...) })
	return frames
}

func TestCLIStatusImagesMountOverHotspot(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	code, stdout, stderr := phone.run(t, "", "status")
	if code != exitOK || !strings.Contains(stdout, "approved") || !strings.Contains(stdout, "Lease: free") {
		t.Fatalf("status %d\n%s\n%s", code, stdout, stderr)
	}
	code, stdout, _ = phone.run(t, "", "images")
	if code != exitOK || !strings.Contains(stdout, "img-debian") || !strings.Contains(stdout, "700.0 MiB") {
		t.Fatalf("images %d %s", code, stdout)
	}
	code, stdout, stderr = phone.run(t, "", "mount", "debian-13.iso")
	if code != exitOK || !strings.Contains(stdout, "LUN 0: debian-13.iso [cdrom]") {
		t.Fatalf("mount %d\n%s\n%s", code, stdout, stderr)
	}
	code, stdout, stderr = phone.run(t, "", "mount", "img-tools", "--mode", "rw")
	if code != exitOK || !strings.Contains(stdout, "LUN 1: tools.img [disk_rw]") {
		t.Fatalf("second mount %d\n%s\n%s", code, stdout, stderr)
	}
	phone.phone.read(func(p *fakePhone) {
		if len(p.state.Slots) != 2 || p.state.Slots[0].ImageID != "img-debian" || p.state.Slots[1].Mode != protocol.ModeDiskRW {
			t.Errorf("slots %+v", p.state.Slots)
		}
		if p.holder != nil {
			t.Error("one-shot commands must release the lease")
		}
	})
	if code, _, _ := phone.run(t, "", "mount", "nope.iso"); code != exitDevice {
		t.Fatalf("unknown image exit %d", code)
	}
	if code, _, _ := phone.run(t, "", "eject", "1", "--force"); code != exitOK {
		t.Fatalf("eject exit %d", code)
	}
	code, stdout, _ = phone.run(t, "", "status", "--json")
	if code != exitOK || !strings.Contains(stdout, `"imageId": "img-debian"`) {
		t.Fatalf("status --json %d %s", code, stdout)
	}
}

func TestCLIKeyTypeAndHid(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	if code, _, stderr := phone.run(t, "", "hid", "on", "--keyboard"); code != exitOK {
		t.Fatalf("hid on: %d %s", code, stderr)
	}
	phone.phone.read(func(p *fakePhone) {
		if !p.state.Hid.Keyboard || p.state.Hid.Mouse {
			t.Errorf("hid %+v", p.state.Hid)
		}
	})
	if code, _, stderr := phone.run(t, "", "key", "ctrl+alt+delete"); code != exitOK {
		t.Fatalf("key: %d %s", code, stderr)
	}
	frames := phone.frames()
	if len(frames) != 7 {
		t.Fatalf("got %d frames: %x", len(frames), frames)
	}
	epoch := frames[0][:2]
	for _, frame := range frames {
		if !bytes.Equal(frame[:2], epoch) {
			t.Fatalf("frames carry different epochs: %x", frames)
		}
	}
	if !bytes.Equal(frames[2][2:], []byte{byte(protocol.OpKeyDown), 0x4C}) || frames[6][2] != byte(protocol.OpReleaseAll) {
		t.Fatalf("frames %x", frames)
	}
	if code, _, stderr := phone.run(t, "hello\nworld", "type", "-"); code != exitOK {
		t.Fatalf("type: %d %s", code, stderr)
	}
	phone.phone.read(func(p *fakePhone) {
		if len(p.pasted) != 1 || p.pasted[0] != "hello\nworld" {
			t.Errorf("pasted %q", p.pasted)
		}
		ran := false
		for _, request := range p.requests {
			if request["t"] == protocol.TypePasteRun && request["planId"] == "plan-1" {
				ran = true
			}
		}
		if !ran {
			t.Error("paste.run with the plan id never sent")
		}
	})
	if code, _, _ := phone.run(t, "", "bootkey", "dell", "setup"); code != exitOK {
		t.Fatalf("bootkey exit %d", code)
	}
}

func TestCLIKbdRelaysTapsAndReleases(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	code, _, stderr := phone.run(t, "aB\x1b[A\x1dq", "kbd")
	if code != exitOK {
		t.Fatalf("kbd: %d %s", code, stderr)
	}
	var keys []string
	for _, frame := range phone.frames() {
		keys = append(keys, strings.TrimSpace(strings.Join([]string{string("?DUMBR"[frame[2]])}, "")))
		if len(frame) == 4 {
			keys[len(keys)-1] += string("0123456789ABCDEF"[frame[3]>>4]) + string("0123456789ABCDEF"[frame[3]&15])
		}
	}
	// a tap, Shift+b tap, Up tap, then release-all on exit.
	want := "D04 U04 DE1 D05 U05 UE1 D52 U52 R"
	if got := strings.Join(keys, " "); got != want {
		t.Fatalf("kbd frames %q, want %q", got, want)
	}
	phone.phone.read(func(p *fakePhone) {
		if p.holder != nil {
			t.Error("kbd must release the lease on exit")
		}
	})
}

func TestCLILeaseConflictAndTakeover(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	// Another client takes the lease and keeps its session open.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	token, _ := readToken(phone.tokenFile)
	other, err := connect(ctx, connectOptions{host: phone.host, token: token})
	if err != nil {
		t.Fatal(err)
	}
	defer other.close()
	if err := acquireLease(ctx, &invocation{}, other); err != nil {
		t.Fatal(err)
	}
	code, _, stderr := phone.run(t, "", "eject", "0")
	if code != exitLease || !strings.Contains(stderr, "--takeover") {
		t.Fatalf("eject with lease held: %d %s", code, stderr)
	}
	// A session that isn't prompting declines at once: no 30 s wait.
	started := time.Now()
	if code, _, stderr := phone.run(t, "", "eject", "0", "--takeover"); code != exitLease || !strings.Contains(stderr, protocol.ErrorDeclined) {
		t.Fatalf("refused takeover exit %d %s", code, stderr)
	}
	if elapsed := time.Since(started); elapsed > 3*time.Second {
		t.Fatalf("declining took %v", elapsed)
	}
	// Now the holder answers yes.
	events := other.takeoverEvents()
	answered := make(chan protocol.TakeoverRequest, 1)
	go func() {
		event := <-events
		answered <- event.request
		other.replyTakeover(!event.cancelled)
	}()
	if code, _, stderr := phone.run(t, "", "eject", "0", "--takeover"); code != exitOK {
		t.Fatalf("takeover: %d %s", code, stderr)
	}
	if request := <-answered; request.RequesterName != "requester-laptop" || request.TimeoutMs != 30_000 || request.Requester == 0 {
		t.Fatalf("takeover request %+v", request)
	}
	// The old holder learned it lost the lease.
	if err := other.waitFor(ctx, func() bool { return !other.holdsLease() }); err != nil {
		t.Fatal(err)
	}
}

func TestCLIPendingAndWait(t *testing.T) {
	phone := startPhone(t, transport.TransportTailnet)
	previous := tailnetPrefixes
	tailnetPrefixes = append([]netip.Prefix{netip.MustParsePrefix("127.0.0.0/8")}, previous...)
	t.Cleanup(func() { tailnetPrefixes = previous })

	phone.phone.mutex.Lock()
	phone.phone.approve = false
	phone.phone.mutex.Unlock()
	code, _, stderr := phone.run(t, "", "status")
	if code != exitAuth || !strings.Contains(stderr, "approve") {
		t.Fatalf("pending status: %d %s", code, stderr)
	}
	phone.phone.mutex.Lock()
	phone.phone.approve = true
	phone.phone.mutex.Unlock()
	if code, _, stderr := phone.run(t, "", "status"); code != exitOK {
		t.Fatalf("approved status over the tailnet: %d %s", code, stderr)
	}
}

func TestCLIRefusesNonTailnetHostWithoutToken(t *testing.T) {
	phone := startPhone(t, transport.TransportTailnet)
	code, _, stderr := phone.run(t, "", "status")
	if code != exitDevice || !strings.Contains(stderr, "not a tailnet address") {
		t.Fatalf("got %d %s", code, stderr)
	}
}

func TestCLIPairSavesTokenThenWorks(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	phone.phone.pairCode = "424242"
	path := filepath.Join(t.TempDir(), "paired-token")
	var stdout, stderr bytes.Buffer
	code := run(context.Background(), []string{"pair", "000000", "--host", phone.host, "--token-file", path}, nil, &stdout, &stderr)
	if code != exitAuth {
		t.Fatalf("wrong code exit %d: %s", code, stderr.String())
	}
	code = run(context.Background(), []string{"pair", "424242", "--host", phone.host, "--token-file", path}, nil, &stdout, &stderr)
	if code != exitOK {
		t.Fatalf("pair exit %d: %s", code, stderr.String())
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("token file %v %v", info, err)
	}
	phone.tokenFile = path
	if code, _, stderr := phone.run(t, "", "status"); code != exitOK {
		t.Fatalf("status with paired token: %d %s", code, stderr)
	}
}

func TestCLIRevokedSessionExitsWithAuth(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	phone.phone.mutex.Lock()
	phone.phone.approve = true
	phone.phone.mutex.Unlock()
	done := make(chan int, 1)
	go func() {
		code, _, _ := phone.run(t, "", "lease", "take")
		done <- code
	}()
	deadline := time.Now().Add(3 * time.Second)
	for {
		var holder *int64
		phone.phone.read(func(p *fakePhone) { holder = p.holder })
		if holder != nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("lease never taken")
		}
		time.Sleep(5 * time.Millisecond)
	}
	token, _ := readToken(phone.tokenFile)
	if count := phone.phone.server.Revoke(transport.RevokeToken, tokenHashForTest(token)); count != 1 {
		t.Fatalf("revoked %d sessions", count)
	}
	select {
	case code := <-done:
		if code != exitAuth {
			t.Fatalf("revoked lease holder exit %d, want %d", code, exitAuth)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("lease take did not exit after revoke")
	}
}

func tokenHashForTest(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// syncBuffer is a bytes.Buffer safe to read while a command writes it.
type syncBuffer struct {
	mutex  sync.Mutex
	buffer bytes.Buffer
}

func (b *syncBuffer) Write(data []byte) (int, error) {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	return b.buffer.Write(data)
}

func (b *syncBuffer) String() string {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	return b.buffer.String()
}

// start runs a command in the background with the given stdin.
func (s *phoneServer) start(t *testing.T, stdin io.Reader, args ...string) (<-chan int, *syncBuffer) {
	t.Helper()
	full := []string{"--host", s.host, "--token-file", s.tokenFile}
	full = append(full, args...)
	stderr := &syncBuffer{}
	done := make(chan int, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	go func() {
		defer cancel()
		done <- run(ctx, full, stdin, io.Discard, stderr)
	}()
	return done, stderr
}

func (s *phoneServer) connect(t *testing.T) *client {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	token, _ := readToken(s.tokenFile)
	connected, err := connect(ctx, connectOptions{host: s.host, token: token})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(connected.close)
	return connected
}

func (s *phoneServer) holder() *int64 {
	var holder *int64
	s.phone.read(func(p *fakePhone) { holder = p.holder })
	return holder
}

func waitUntil(t *testing.T, what string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !condition() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func waitExit(t *testing.T, done <-chan int) int {
	t.Helper()
	select {
	case code := <-done:
		return code
	case <-time.After(5 * time.Second):
		t.Fatal("command did not exit")
		return -1
	}
}

// askTakeover has requester ask the holder for the lease in the background.
func askTakeover(requester *client) <-chan error {
	result := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		result <- acquireLease(ctx, &invocation{options: options{takeover: true}, stderr: io.Discard}, requester)
	}()
	return result
}

func TestCLILeaseHoldAnswersTakeoverFromStdin(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	stdin, input := io.Pipe()
	defer input.Close()
	done, stderr := phone.start(t, stdin, "lease", "take")
	waitUntil(t, "the lease", func() bool { return phone.holder() != nil })
	requester := phone.connect(t)

	result := askTakeover(requester)
	waitUntil(t, "the first prompt", func() bool {
		return strings.Count(stderr.String(), "requester-laptop asks for control. Allow? [y/N]") == 1
	})
	io.WriteString(input, "n\n")
	if err := <-result; exitCodeFor(err) != exitLease || !strings.Contains(err.Error(), protocol.ErrorDeclined) {
		t.Fatalf("declined takeover: %v", err)
	}

	result = askTakeover(requester)
	waitUntil(t, "the second prompt", func() bool { return strings.Count(stderr.String(), "asks for control") == 2 })
	io.WriteString(input, "yes\n")
	if err := <-result; err != nil {
		t.Fatalf("allowed takeover: %v", err)
	}
	if !requester.holdsLease() {
		t.Fatal("requester does not hold the lease")
	}
	if code := waitExit(t, done); code != exitOK || !strings.Contains(stderr.String(), "Control handed over") {
		t.Fatalf("lease take exit %d\n%s", code, stderr.String())
	}
}

func TestCLILeaseHoldDeclinesWithoutAnswer(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	phone.phone.mutex.Lock()
	phone.phone.takeoverAnnounceMs = 200 // the fake itself waits 30 s
	phone.phone.mutex.Unlock()
	stdin, input := io.Pipe()
	defer input.Close()
	done, stderr := phone.start(t, stdin, "lease", "take")
	waitUntil(t, "the lease", func() bool { return phone.holder() != nil })
	requester := phone.connect(t)
	started := time.Now()
	if err := <-askTakeover(requester); exitCodeFor(err) != exitLease {
		t.Fatalf("unanswered takeover: %v", err)
	}
	if elapsed := time.Since(started); elapsed > 3*time.Second {
		t.Fatalf("declining an unanswered prompt took %v", elapsed)
	}
	if !strings.Contains(stderr.String(), "No answer; declined.") {
		t.Fatalf("stderr %s", stderr.String())
	}
	// End of stdin declines at once from then on.
	input.Close()
	if err := <-askTakeover(requester); exitCodeFor(err) != exitLease {
		t.Fatalf("takeover after stdin closed: %v", err)
	}
	if phone.holder() == nil || requester.holdsLease() {
		t.Fatal("the lease moved")
	}
	select {
	case code := <-done:
		t.Fatalf("lease take exited early: %d", code)
	default:
	}
}

func TestCLIKbdAnswersTakeoverWithNextKeystroke(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	stdin, input := io.Pipe()
	defer input.Close()
	done, stderr := phone.start(t, stdin, "kbd")
	waitUntil(t, "the lease", func() bool { return phone.holder() != nil })
	io.WriteString(input, "x")
	waitUntil(t, "the x tap", func() bool { return len(phone.frames()) == 2 })
	requester := phone.connect(t)
	result := askTakeover(requester)
	waitUntil(t, "the prompt", func() bool { return strings.Contains(stderr.String(), "asks for control") })
	io.WriteString(input, "y")
	if err := <-result; err != nil {
		t.Fatalf("takeover: %v", err)
	}
	if code := waitExit(t, done); code != exitOK {
		t.Fatalf("kbd exit %d\n%s", code, stderr.String())
	}
	for _, frame := range phone.frames() {
		if len(frame) == 4 && frame[3] == 0x1C { // y
			t.Fatalf("the answer was typed: %x", phone.frames())
		}
	}
}

func TestCLIMountKeepsLunsDense(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	for range 2 {
		if code, _, stderr := phone.run(t, "", "mount", "img-debian"); code != exitOK {
			t.Fatalf("mount: %d %s", code, stderr)
		}
	}
	code, stdout, stderr := phone.run(t, "", "mount", "img-tools", "--lun", "0", "--mode", "rw")
	if code != exitOK || !strings.Contains(stdout, "LUN 0: tools.img [disk_rw]") {
		t.Fatalf("replace LUN 0: %d %s %s", code, stdout, stderr)
	}
	phone.phone.read(func(p *fakePhone) {
		if len(p.state.Slots) != 2 || p.state.Slots[0].ImageID != "img-tools" || p.state.Slots[1].ImageID != "img-debian" || p.state.Slots[1].Mode != protocol.ModeCdrom {
			t.Errorf("slots %+v", p.state.Slots)
		}
	})
	if code, _, stderr := phone.run(t, "", "mount", "img-tools", "--lun", "2"); code != exitUsage || !strings.Contains(stderr, "omit it to add LUN 2") {
		t.Fatalf("--lun past the end: %d %s", code, stderr)
	}
	for range 6 {
		if code, _, stderr := phone.run(t, "", "mount", "img-tools"); code != exitOK {
			t.Fatalf("mount: %d %s", code, stderr)
		}
	}
	if code, _, stderr := phone.run(t, "", "mount", "img-tools"); code != exitUsage || !strings.Contains(stderr, "all 8 LUNs") {
		t.Fatalf("ninth mount: %d %s", code, stderr)
	}
	phone.phone.read(func(p *fakePhone) {
		for index, slot := range p.state.Slots {
			if slot.Lun != index || len(p.state.Slots) != 8 {
				t.Errorf("slots %+v", p.state.Slots)
			}
		}
	})
}

func TestCLIMountUsesDefaultMode(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	cases := []struct{ image, want string }{
		{"hybrid.iso", "LUN 0: hybrid.iso [disk_ro]"}, // defaultMode beats the .iso rule
		{"tools.img", "LUN 1: tools.img [disk_ro]"},   // no defaultMode: name rule
		{"debian-13.iso", "LUN 2: debian-13.iso [cdrom]"},
	}
	for _, testCase := range cases {
		code, stdout, stderr := phone.run(t, "", "mount", testCase.image)
		if code != exitOK || !strings.Contains(stdout, testCase.want) {
			t.Fatalf("mount %s: %d %q %s, want %q", testCase.image, code, stdout, stderr, testCase.want)
		}
	}
}

func TestCLIExplicitTimeoutBoundsJobs(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	phone.phone.mutex.Lock()
	phone.phone.holdJobs = true
	phone.phone.mutex.Unlock()
	for _, args := range [][]string{{"type", "hello"}, {"bootkey", "dell"}} {
		started := time.Now()
		code, _, stderr := phone.run(t, "", append(args, "--timeout", "500ms")...)
		if code != exitTimeout {
			t.Fatalf("%v: exit %d %s", args, code, stderr)
		}
		if elapsed := time.Since(started); elapsed > 3*time.Second {
			t.Fatalf("%v with --timeout 500ms took %v", args, elapsed)
		}
	}
	phone.phone.read(func(p *fakePhone) {
		sent := map[string]bool{}
		for _, request := range p.requests {
			messageType, _ := request["t"].(string)
			sent[messageType] = true
		}
		if !sent[protocol.TypePasteCancel] || !sent[protocol.TypeBootkeyCancel] {
			t.Errorf("jobs not cancelled after the deadline: %v", sent)
		}
	})
}

func TestCLITypeStdinHonoursDeadline(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	stdin, input := io.Pipe()
	defer input.Close()
	started := time.Now()
	done, stderr := phone.start(t, stdin, "type", "-", "--timeout", "300ms")
	if code := waitExit(t, done); code != exitTimeout {
		t.Fatalf("exit %d %s", code, stderr.String())
	}
	if elapsed := time.Since(started); elapsed > 3*time.Second {
		t.Fatalf("blocked stdin took %v", elapsed)
	}
	phone.phone.read(func(p *fakePhone) {
		if len(p.requests) != 0 {
			t.Errorf("connected before stdin was read: %v", p.requests)
		}
	})
}

func TestJobContextDeadlines(t *testing.T) {
	interrupt, stop := context.WithCancel(context.Background())
	defer stop()
	short, cancelShort := context.WithTimeout(context.Background(), time.Second)
	defer cancelShort()
	// Default: the job outlives the command's default deadline.
	job, cancel := jobContext(short, &invocation{interrupt: interrupt}, time.Minute)
	deadline, _ := job.Deadline()
	cancel()
	if time.Until(deadline) < 50*time.Second {
		t.Fatalf("default job deadline %v from now", time.Until(deadline))
	}
	// --timeout: the earlier deadline wins.
	job, cancel = jobContext(short, &invocation{options: options{timeout: time.Second}, interrupt: interrupt}, time.Minute)
	deadline, _ = job.Deadline()
	cancel()
	if time.Until(deadline) > 2*time.Second {
		t.Fatalf("explicit job deadline %v from now", time.Until(deadline))
	}
	// Ctrl-C stops it either way.
	job, cancel = jobContext(short, &invocation{interrupt: interrupt}, time.Minute)
	defer cancel()
	stop()
	select {
	case <-job.Done():
	case <-time.After(time.Second):
		t.Fatal("interrupt did not stop the job")
	}
}
