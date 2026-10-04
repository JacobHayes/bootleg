package rndis

import "testing"

// Walks the FunctionFS blob the way f_fs (6.1) validates it: header, per-speed counts, each
// descriptor's length and type (no class-specific ones), and the Microsoft OS header rules.
func TestFunctionFSDescriptorsParse(t *testing.T) {
	blob := FunctionFSDescriptors()
	if le.Uint32(blob[0:]) != ffsDescriptorsMagicV2 || int(le.Uint32(blob[4:])) != len(blob) || le.Uint32(blob[24:]) != 1 {
		t.Fatalf("header %x", blob[:28])
	}
	rest := blob[28:]
	for speed, bulkBytes := range []int{FullSpeedBulkBytes, HighSpeedBulkBytes, SuperSpeedBulkBytes} {
		var types, endpoints []byte
		for range le.Uint32(blob[12+4*speed:]) {
			if len(rest) < 2 || rest[0] < 2 || int(rest[0]) > len(rest) {
				t.Fatalf("speed %d: bad descriptor length", speed)
			}
			descriptor := rest[:rest[0]]
			types = append(types, descriptor[1])
			if descriptor[1] == 0x05 {
				endpoints = append(endpoints, descriptor[2])
				if descriptor[3] == 0x02 && int(le.Uint16(descriptor[4:])) != bulkBytes {
					t.Fatalf("speed %d: bulk size %d", speed, le.Uint16(descriptor[4:]))
				}
			}
			rest = rest[len(descriptor):]
		}
		want := "\x0b\x04\x05\x04\x05\x05"
		if speed == 2 {
			want = "\x0b\x04\x05\x30\x04\x05\x30\x05\x30"
		}
		if string(types) != want || string(endpoints) != "\x81\x82\x03" {
			t.Fatalf("speed %d: types %x endpoints %x", speed, types, endpoints)
		}
	}
	if string(blob[28:36]) != "\x08\x0b\x00\x02\xef\x04\x01\x00" {
		t.Fatalf("IAD %x", blob[28:36])
	}
	if len(rest) != 35 || le.Uint32(rest[1:]) != 35 || le.Uint16(rest[5:]) != 1 || le.Uint16(rest[7:]) != 4 || rest[9] != 1 ||
		string(rest[11:29]) != "\x00\x01RNDIS\x00\x00\x005162001\x00" {
		t.Fatalf("Microsoft OS descriptor %x", rest)
	}
}
