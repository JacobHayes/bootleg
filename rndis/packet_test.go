package rndis

import (
	"bytes"
	"errors"
	"math/rand/v2"
	"testing"
)

func testFrame(length int, seed byte) []byte {
	frame := make([]byte, length)
	for i := range frame {
		frame[i] = seed + byte(i)
	}
	return frame
}

// Property: frames wrapped back to back (as a batching host sends them) come back unchanged and in
// order, and a wrapped frame never ends on a full USB packet (the host would wait for more).
func TestWrapUnwrapRoundTrip(t *testing.T) {
	random := rand.New(rand.NewPCG(1, 2))
	for iteration := range 2000 {
		maxPacketBytes := []int{FullSpeedBulkBytes, HighSpeedBulkBytes, SuperSpeedBulkBytes}[iteration%3]
		var transfer []byte
		var want [][]byte
		for range 1 + random.IntN(4) {
			frame := testFrame(EthernetHeaderBytes+random.IntN(FrameBytesMax-EthernetHeaderBytes+1), byte(iteration))
			message := Wrap(nil, frame, maxPacketBytes)
			if len(message)%maxPacketBytes == 0 {
				t.Fatalf("a %d-byte frame ends on a full %d-byte packet", len(frame), maxPacketBytes)
			}
			if err := Unwrap(message, func([]byte) {}); err != nil {
				t.Fatalf("a %d-byte frame with its terminator: %v", len(frame), err)
			}
			transfer = append(transfer, message[:PacketHeaderBytes+len(frame)]...)
			want = append(want, frame)
		}
		var got [][]byte
		if err := Unwrap(transfer, func(frame []byte) { got = append(got, append([]byte(nil), frame...)) }); err != nil {
			t.Fatal(err)
		}
		if len(got) != len(want) {
			t.Fatalf("%d frames, want %d", len(got), len(want))
		}
		for i := range want {
			if !bytes.Equal(got[i], want[i]) {
				t.Fatalf("frame %d differs", i)
			}
		}
	}
}

func TestUnwrapRejectsMalformedTransfers(t *testing.T) {
	good := Wrap(nil, testFrame(100, 0), HighSpeedBulkBytes)
	mutate := func(offset int, value uint32) []byte {
		b := append([]byte(nil), good...)
		le.PutUint32(b[offset:], value)
		return b
	}
	cases := map[string][]byte{
		"truncated header":   good[:20],
		"truncated data":     good[:len(good)-1],
		"wrong type":         mutate(0, MsgQuery),
		"length too small":   mutate(4, 8),
		"data past message":  mutate(12, 200),
		"data in header":     mutate(8, 4),
		"offset overflow":    mutate(8, 0xFFFFFFF8),
		"runt frame":         mutate(12, EthernetHeaderBytes-1),
		"two trailing bytes": append(append([]byte(nil), good...), 0, 0),
		"oversized":          make([]byte, UnwrapBytesMax+1),
	}
	for name, transfer := range cases {
		if err := Unwrap(transfer, func([]byte) {}); !errors.Is(err, ErrMalformed) {
			t.Errorf("%s: %v", name, err)
		}
	}
}

// Whatever arrives on the bulk endpoint, Unwrap never panics and every frame it hands out is a
// plausible Ethernet frame.
func FuzzUnwrap(f *testing.F) {
	f.Add(Wrap(nil, testFrame(60, 0), HighSpeedBulkBytes))
	f.Add([]byte{0})
	f.Fuzz(func(t *testing.T, transfer []byte) {
		_ = Unwrap(transfer, func(frame []byte) {
			if len(frame) < EthernetHeaderBytes || len(frame) > FrameBytesMax {
				t.Fatalf("frame of %d bytes", len(frame))
			}
		})
	})
}
