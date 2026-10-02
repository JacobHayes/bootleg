package protocol

import (
	"bytes"
	"strings"
	"testing"
)

func TestDecodeFrameExactLengths(t *testing.T) {
	valid := map[string][]byte{
		"key down":      {0x01, 0x02, 0x01, 0x04},
		"key up":        {0x00, 0x00, 0x02, 0xE7},
		"mouse move":    {0xFF, 0xFF, 0x03, 0x81, 0x7F},
		"mouse buttons": {0x00, 0x01, 0x04, 0x07},
		"release all":   {0x00, 0x01, 0x05},
	}
	for name, data := range valid {
		frame, err := DecodeFrame(data)
		if err != nil {
			t.Errorf("%s: %v", name, err)
			continue
		}
		if encoded := frame.Encode(); !bytes.Equal(encoded, data) {
			t.Errorf("%s: round trip %x → %x", name, data, encoded)
		}
	}
	frame, _ := DecodeFrame([]byte{0x01, 0x02, 0x03, 0x80, 0x7F})
	if frame.Epoch != 0x0102 || frame.DeltaX != -127 || frame.DeltaY != 127 {
		t.Errorf("decoded %+v; -128 must clamp to -127", frame)
	}
	if encoded := (Frame{Op: OpMouseMove, DeltaX: -128, DeltaY: -128}).Encode(); !bytes.Equal(encoded[3:], []byte{0x81, 0x81}) {
		t.Errorf("encoded -128 as %x, want clamped to 81 81", encoded)
	}

	invalid := map[string][]byte{
		"empty":              {},
		"header only short":  {0x00, 0x01},
		"key down short":     {0x00, 0x01, 0x01},
		"key down long":      {0x00, 0x01, 0x01, 0x04, 0x00},
		"key up short":       {0x00, 0x01, 0x02},
		"move short":         {0x00, 0x01, 0x03, 0x01},
		"move long":          {0x00, 0x01, 0x03, 0x01, 0x01, 0x01},
		"buttons long":       {0x00, 0x01, 0x04, 0x01, 0x00},
		"release all long":   {0x00, 0x01, 0x05, 0x00},
		"unknown op":         {0x00, 0x01, 0x06},
		"op zero":            {0x00, 0x01, 0x00, 0x04},
		"usage error code":   {0x00, 0x01, 0x01, 0x01},
		"usage reserved":     {0x00, 0x01, 0x01, 0xE8},
		"usage none":         {0x00, 0x01, 0x02, 0x00},
		"buttons extra bits": {0x00, 0x01, 0x04, 0x08},
	}
	for name, data := range invalid {
		if _, err := DecodeFrame(data); err == nil {
			t.Errorf("%s: %x accepted", name, data)
		}
	}
}

func TestEncodeRefusesInvalidFrames(t *testing.T) {
	for _, frame := range []Frame{{Op: 0x09}, {Op: OpKeyDown, Usage: 0}, {Op: OpMouseButtons, Buttons: 0x10}} {
		func() {
			defer func() {
				if recover() == nil {
					t.Errorf("Encode(%+v) did not panic", frame)
				}
			}()
			frame.Encode()
		}()
	}
}

func TestParseEnvelope(t *testing.T) {
	valid := []string{
		`{"t":"hello","v":1}`,
		`{"id":1,"t":"state.get"}`,
		`{"id":"abc","t":"lease.take","epoch":3}`,
		`{"id":-1,"t":"bootkey.run","vendor":"dell"}`,
	}
	for _, message := range valid {
		if _, err := ParseEnvelope([]byte(message)); err != nil {
			t.Errorf("%s: %v", message, err)
		}
	}
	invalid := []string{
		``,
		`[]`,
		`"hello"`,
		`{"v":1}`,
		`{"t":""}`,
		`{"t":"Hello"}`,
		`{"t":"state get"}`,
		`{"t":"` + strings.Repeat("a", TypeLengthMax+1) + `"}`,
		`{"t":1}`,
		`{"id":{},"t":"hello"}`,
		`{"id":[1],"t":"hello"}`,
		`{"id":true,"t":"hello"}`,
		`{"id":"` + strings.Repeat("a", RequestIDLengthMax) + `","t":"hello"}`,
		`{"t":"hello"} {"t":"hello"}`,
	}
	for _, message := range invalid {
		if _, err := ParseEnvelope([]byte(message)); err == nil {
			t.Errorf("%q accepted", message)
		}
	}
	if _, err := ParseEnvelope([]byte(`{"t":"x","pad":"` + strings.Repeat("a", MessageBytesMax) + `"}`)); err == nil {
		t.Error("oversized message accepted")
	}
}

func TestCloseCodes(t *testing.T) {
	cases := map[string]int{
		ReasonVersion:         CloseVersion,
		ReasonRevoked:         CloseUnauthorized,
		ReasonUnauthorized:    CloseUnauthorized,
		ReasonMalformed:       CloseMalformed,
		ReasonSlowConsumer:    CloseSlowConsumer,
		ReasonRateLimited:     CloseRateLimited,
		ReasonListenerStopped: CloseServerStopping,
		"takeover":            CloseServer,
	}
	for reason, code := range cases {
		if got := CloseCodeFor(reason); got != code {
			t.Errorf("%s → %d, want %d", reason, got, code)
		}
	}
}
