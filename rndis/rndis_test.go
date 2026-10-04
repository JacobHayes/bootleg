package rndis

import (
	"errors"
	"testing"
)

var testHostMAC = [6]byte{0x02, 0xb0, 0x07, 0x1e, 0x60, 0x01}

func message(messageType uint32, fields ...uint32) []byte {
	b := le.AppendUint32(nil, messageType)
	b = le.AppendUint32(b, uint32(8+4*len(fields)))
	for _, field := range fields {
		b = le.AppendUint32(b, field)
	}
	return b
}

func initialize(requestID uint32) []byte { return message(MsgInitialize, requestID, 1, 0, 16384) }

func query(requestID, oid uint32) []byte { return message(MsgQuery, requestID, oid, 0, 0, 0) }

// setFilter places the 4-byte value right after the 28-byte header (offset 20 from RequestID).
func setFilter(requestID, filter uint32) []byte {
	return message(MsgSet, requestID, OidGenCurrentPacketFilter, 4, 20, 0, filter)
}

// handle sends command and returns the completion the host would fetch next (nil for none).
func handle(t *testing.T, d *Device, command []byte) []byte {
	t.Helper()
	queued, err := d.Handle(command)
	if err != nil {
		t.Fatalf("Handle(%x): %v", command, err)
	}
	if !queued {
		return nil
	}
	response := d.NextResponse()
	if len(response) < 16 || int(le.Uint32(response[4:])) != len(response) || le.Uint32(response) != le.Uint32(command)|completionBit {
		t.Fatalf("bad completion %x to %x", response, command)
	}
	return response
}

// One host session from INITIALIZE to HALT, and what each session end must forget.
func TestSessionLifecycle(t *testing.T) {
	d := NewDevice(testHostMAC)
	response := handle(t, d, initialize(5))
	if len(response) != 52 || le.Uint32(response[8:]) != 5 || le.Uint32(response[12:]) != StatusSuccess ||
		le.Uint32(response[32:]) != PacketsPerTransferMax || le.Uint32(response[36:]) != MaxTransferBytes || !d.Initialized() {
		t.Fatalf("INITIALIZE completion %x", response)
	}
	// Every OID the device lists answers, with a consistent buffer; the list has f_rndis's 28.
	list := handle(t, d, query(6, OidGenSupportedList))[24:]
	if len(list) != 4*28 {
		t.Fatalf("supported list of %d bytes", len(list))
	}
	for i := 0; i < len(list); i += 4 {
		oid := le.Uint32(list[i:])
		response := handle(t, d, query(7, oid))
		length := le.Uint32(response[16:])
		if le.Uint32(response[12:]) != StatusSuccess || int(length) != len(response)-24 || (length > 0) != (le.Uint32(response[20:]) == 16) {
			t.Errorf("oid %#x: completion %x", oid, response)
		}
	}
	if got := handle(t, d, query(8, Oid8023PermanentAddress))[24:]; [6]byte(got) != testHostMAC {
		t.Fatalf("permanent address %x", got)
	}
	if status := le.Uint32(handle(t, d, query(9, 0xFD010100))[12:]); status != StatusNotSupported {
		t.Fatalf("unknown OID: %#x", status)
	}

	// Frames pass only with a filter and media, and the media change reaches the host.
	handle(t, d, setFilter(10, FilterDirected|FilterBroadcast))
	if d.Passing() || d.SetMedia(false) {
		t.Fatal("passing, or an indication, without media")
	}
	if !d.SetMedia(true) || !d.Passing() || le.Uint32(d.NextResponse()[8:]) != StatusMediaConnect {
		t.Fatal("media on: no indication or not passing")
	}
	broadcast, multicast, unicast := testFrame(60, 0), testFrame(60, 0), testFrame(60, 0)
	copy(broadcast, []byte{0xff, 0xff, 0xff, 0xff, 0xff, 0xff})
	multicast[0], unicast[0] = 0x01, 0x02
	if !d.Delivers(broadcast) || d.Delivers(multicast) || !d.Delivers(unicast) {
		t.Fatal("packet filter Directed|Broadcast not honoured")
	}

	// RESET keeps the session but drops the filter.
	if response := handle(t, d, message(MsgReset, 0)); le.Uint32(response[12:]) != 1 || !d.Initialized() || d.Passing() {
		t.Fatalf("RESET completion %x", response)
	}

	// HALT, a new INITIALIZE and USB events end the session: nothing queued for it is answered.
	for name, end := range map[string]func(){
		"HALT":       func() { handle(t, d, message(MsgHalt, 11)) },
		"INITIALIZE": func() { _, _ = d.Handle(initialize(12)) },
		"USB event":  d.EndSession,
	} {
		handle(t, d, initialize(1))
		handle(t, d, setFilter(2, FilterDirected))
		_, _ = d.Handle(query(3, OidGenLinkSpeed)) // a completion the host hasn't fetched
		session := d.Session
		end()
		if d.Session == session {
			t.Errorf("%s: same session", name)
		}
		if d.Passing() {
			t.Errorf("%s: still passing", name)
		}
		next := d.NextResponse()
		if name == "INITIALIZE" {
			if le.Uint32(next) != MsgInitialize|completionBit || le.Uint32(next[8:]) != 12 {
				t.Errorf("%s: answered %x before its own completion", name, next)
			}
			next = d.NextResponse()
		}
		if len(next) != 1 || next[0] != 0 {
			t.Errorf("%s: the old session's completion %x survived", name, next)
		}
	}
	if !d.Media() {
		t.Fatal("media is the phone's and outlives sessions")
	}
}

func TestMACs(t *testing.T) {
	for _, seed := range []string{"1A191FDF6007Z8", "x", "unknown"} {
		host, device := MACs(seed)
		NewDevice(host) // panics unless locally administered unicast
		again, _ := MACs(seed)
		if host == device || device[0] != host[0] || host != again {
			t.Fatalf("%q: host %x device %x", seed, host, device)
		}
	}
}

func TestRefusals(t *testing.T) {
	d := NewDevice(testHostMAC)
	for _, command := range [][]byte{message(MsgInitialize, 1, 2, 0, 16384), message(MsgInitialize, 1, 1, 0, FrameBytesMax)} {
		if response := handle(t, d, command); le.Uint32(response[12:]) != StatusFailure || d.Initialized() {
			t.Fatalf("INITIALIZE %x accepted", command)
		}
	}
	handle(t, d, initialize(1))
	inHeader := setFilter(2, FilterDirected)
	le.PutUint32(inHeader[20:], 4)
	outside := setFilter(3, FilterDirected)
	le.PutUint32(outside[20:], 1000)
	for command, want := range map[string]uint32{
		string(setFilter(4, FilterDirected|0x10)): StatusNotSupported,
		string(inHeader): StatusInvalidData,
		string(outside):  StatusInvalidData,
		string(message(MsgSet, 5, OidGenCurrentPacketFilter, 2, 20, 0, 0)): StatusInvalidData,
	} {
		if status := le.Uint32(handle(t, d, []byte(command))[12:]); status != want || d.Passing() {
			t.Errorf("SET %x: status %#x", command, status)
		}
	}
	for name, command := range map[string][]byte{
		"short":        {2, 0, 0, 0},
		"long length":  le.AppendUint32(le.AppendUint32(nil, MsgInitialize), 100),
		"short query":  message(MsgQuery, 1, OidGenLinkSpeed),
		"unknown type": message(0x42, 1),
		"packet":       message(MsgPacket, 36, 0),
		"oversized":    make([]byte, CommandBytesMax+1),
	} {
		if queued, err := d.Handle(command); !errors.Is(err, ErrMalformed) || queued {
			t.Errorf("%s: queued %v, error %v", name, queued, err)
		}
	}
}

// Whatever the host sends, Handle never panics, queues only well-formed completions of the
// request's type, and keeps the queue bounded.
func FuzzHandle(f *testing.F) {
	f.Add(initialize(1))
	f.Add(query(2, OidGenSupportedList))
	f.Add(setFilter(3, 0x0B))
	f.Add(message(MsgKeepalive, 4))
	f.Add(message(MsgReset, 0))
	f.Fuzz(func(t *testing.T, command []byte) {
		d := NewDevice(testHostMAC)
		for range ResponseQueueLengthMax + 1 {
			if queued, err := d.Handle(command); err != nil || !queued {
				return
			}
		}
		if len(d.responses) > ResponseQueueLengthMax {
			t.Fatalf("%d queued responses", len(d.responses))
		}
		response := d.NextResponse()
		if len(response) < 16 || int(le.Uint32(response[4:])) != len(response) || le.Uint32(response) != le.Uint32(command)|completionBit {
			t.Fatalf("bad completion %x", response)
		}
	})
}
