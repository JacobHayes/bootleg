package protocol

import (
	"encoding/binary"
	"fmt"
)

// Binary HID frames: epoch (u16 big-endian), op, then op-specific bytes with
// an exact length per op.
type FrameOp byte

const (
	OpKeyDown      FrameOp = 0x01 // usage
	OpKeyUp        FrameOp = 0x02 // usage
	OpMouseMove    FrameOp = 0x03 // dx int8, dy int8
	OpMouseButtons FrameOp = 0x04 // buttons (low 3 bits)
	OpReleaseAll   FrameOp = 0x05
	OpScroll       FrameOp = 0x06 // wheel int8 (+ up), pan int8 (+ right)
)

const (
	frameHeaderBytes = 3
	MouseButtonsMask = 0x07
	// Keyboard page usages a frame may carry: 0x04 (A) through 0xE7 (right
	// GUI). 0x00–0x03 are error codes and 0xE8–0xFF are reserved.
	KeyUsageMin = 0x04
	KeyUsageMax = 0xE7
)

// FrameLength is the exact byte length of a frame with op, or 0 if op is
// unknown.
func FrameLength(op FrameOp) int {
	switch op {
	case OpKeyDown, OpKeyUp, OpMouseButtons:
		return frameHeaderBytes + 1
	case OpMouseMove, OpScroll:
		return frameHeaderBytes + 2
	case OpReleaseAll:
		return frameHeaderBytes
	}
	return 0
}

type Frame struct {
	Epoch   uint16
	Op      FrameOp
	Usage   byte // key down/up
	DeltaX  int8 // mouse move
	DeltaY  int8
	Buttons byte // mouse buttons
	Wheel   int8 // scroll
	Pan     int8
}

// MouseDeltaMin is the smallest mouse delta (motion and scroll): -128 is
// clamped to -127 on decode and encode, as Kotlin does, so it is symmetric.
const MouseDeltaMin = -127

func clampDelta(delta int8) int8 {
	if delta < MouseDeltaMin {
		return MouseDeltaMin
	}
	return delta
}

// DecodeFrame validates and decodes one binary frame.
func DecodeFrame(data []byte) (Frame, error) {
	if len(data) < frameHeaderBytes {
		return Frame{}, fmt.Errorf("frame of %d bytes is shorter than its %d-byte header", len(data), frameHeaderBytes)
	}
	frame := Frame{Epoch: binary.BigEndian.Uint16(data[0:2]), Op: FrameOp(data[2])}
	want := FrameLength(frame.Op)
	if want == 0 {
		return Frame{}, fmt.Errorf("unknown frame op 0x%02x", data[2])
	}
	if len(data) != want {
		return Frame{}, fmt.Errorf("frame op 0x%02x is %d bytes, want exactly %d", data[2], len(data), want)
	}
	switch frame.Op {
	case OpKeyDown, OpKeyUp:
		frame.Usage = data[3]
		if frame.Usage < KeyUsageMin || frame.Usage > KeyUsageMax {
			return Frame{}, fmt.Errorf("key usage 0x%02x outside 0x%02x–0x%02x", frame.Usage, KeyUsageMin, KeyUsageMax)
		}
	case OpMouseMove:
		frame.DeltaX, frame.DeltaY = clampDelta(int8(data[3])), clampDelta(int8(data[4]))
	case OpScroll:
		frame.Wheel, frame.Pan = clampDelta(int8(data[3])), clampDelta(int8(data[4]))
	case OpMouseButtons:
		frame.Buttons = data[3]
		if frame.Buttons&^MouseButtonsMask != 0 {
			return Frame{}, fmt.Errorf("mouse buttons 0x%02x use bits outside 0x%02x", frame.Buttons, MouseButtonsMask)
		}
	}
	return frame, nil
}

// Encode returns the wire bytes. It panics on a frame DecodeFrame would
// reject, so callers can't send what the server would drop.
func (f Frame) Encode() []byte {
	length := FrameLength(f.Op)
	if length == 0 {
		panic(fmt.Sprintf("protocol: unknown frame op 0x%02x", byte(f.Op)))
	}
	data := make([]byte, length)
	binary.BigEndian.PutUint16(data[0:2], f.Epoch)
	data[2] = byte(f.Op)
	switch f.Op {
	case OpKeyDown, OpKeyUp:
		data[3] = f.Usage
	case OpMouseMove:
		data[3], data[4] = byte(clampDelta(f.DeltaX)), byte(clampDelta(f.DeltaY))
	case OpScroll:
		data[3], data[4] = byte(clampDelta(f.Wheel)), byte(clampDelta(f.Pan))
	case OpMouseButtons:
		data[3] = f.Buttons
	}
	if _, err := DecodeFrame(data); err != nil {
		panic(fmt.Sprintf("protocol: refusing to encode invalid frame: %v", err))
	}
	return data
}
