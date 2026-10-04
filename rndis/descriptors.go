package rndis

// FunctionFS blobs for the RNDIS function, written to ep0 (descriptors, then strings) when the
// daemon is released. Layout per include/uapi/linux/usb/functionfs.h.

const (
	ffsDescriptorsMagicV2 = 3
	ffsStringsMagic       = 2
	ffsHasFullSpeed       = 1
	ffsHasHighSpeed       = 2
	ffsHasSuperSpeed      = 4
	ffsHasMicrosoftOS     = 8

	// Endpoint addresses in descriptor order; FunctionFS names the files ep1, ep2, ep3 in that order.
	EndpointNotify  = 0x81 // interrupt IN: RESPONSE_AVAILABLE notifications
	EndpointDataIn  = 0x82 // bulk IN: device to host
	EndpointDataOut = 0x03 // bulk OUT: host to device

	NotifyPacketBytes   = 8
	FullSpeedBulkBytes  = 64
	HighSpeedBulkBytes  = 512
	SuperSpeedBulkBytes = 1024

	// The IAD's bFirstInterface. f_fs (6.1) renumbers interface descriptors but not this field, so it
	// must be the configuration-wide number: the function is always first in Bootleg's prefix.
	firstInterface = 0
)

// Class codes. The IAD's Miscellaneous / RNDIS / Ethernet (EF/04/01) is what Windows' inbox
// rndiscmp.inf (Vista and later) matches; the interfaces are the classic RNDIS pair, CDC
// Communications / ACM / vendor (02/02/FF) and CDC Data, which Linux's rndis_host matches too. The
// Microsoft OS compatible ID "RNDIS"/"5162001" is a second way for Windows to find it.
const (
	FunctionClass    = 0xEF
	FunctionSubclass = 0x04
	FunctionProtocol = 0x01
)

func interfaceDescriptors(bulkBytes int, notifyInterval byte, superSpeed bool) (b []byte, count uint32) {
	add := func(descriptor ...byte) {
		b = append(b, descriptor...)
		count++
	}
	companion := func(bytesPerInterval byte) {
		if superSpeed {
			add(6, 0x30, 0, 0, bytesPerInterval, 0)
		}
	}
	add(8, 0x0B, firstInterface, 2, FunctionClass, FunctionSubclass, FunctionProtocol, 0)
	add(9, 0x04, 0, 0, 1, 0x02, 0x02, 0xFF, 0)
	add(7, 0x05, EndpointNotify, 0x03, NotifyPacketBytes, 0, notifyInterval)
	companion(NotifyPacketBytes)
	add(9, 0x04, 1, 0, 2, 0x0A, 0, 0, 0)
	for _, address := range []byte{EndpointDataIn, EndpointDataOut} {
		add(7, 0x05, address, 0x02, byte(bulkBytes), byte(bulkBytes>>8), 0)
		companion(0)
	}
	return b, count
}

// FunctionFSDescriptors is the descriptors blob: full, high and super speed, plus the Microsoft
// OS extended compatible ID for interface 0.
func FunctionFSDescriptors() []byte {
	full, fullCount := interfaceDescriptors(FullSpeedBulkBytes, 32, false)
	// High and super speed intervals are 2^(n-1) microframes: 9 is 32ms.
	high, highCount := interfaceDescriptors(HighSpeedBulkBytes, 9, false)
	super, superCount := interfaceDescriptors(SuperSpeedBulkBytes, 9, true)

	b := le.AppendUint32(nil, ffsDescriptorsMagicV2)
	b = le.AppendUint32(b, 0) // length, filled in below
	b = le.AppendUint32(b, ffsHasFullSpeed|ffsHasHighSpeed|ffsHasSuperSpeed|ffsHasMicrosoftOS)
	for _, count := range []uint32{fullCount, highCount, superCount, 1} {
		b = le.AppendUint32(b, count)
	}
	b = append(append(append(b, full...), high...), super...)
	// Microsoft OS header (11 bytes): interface, dwLength, bcdVersion (6.1 accepts only 1), wIndex 4
	// (extended compat ID), bCount, reserved. Then one function: first interface 0, reserved 1, the
	// compatible and sub-compatible IDs, 6 reserved bytes.
	b = append(b, 0)
	b = le.AppendUint32(b, 11+24)
	b = le.AppendUint16(b, 1)
	b = le.AppendUint16(b, 4)
	b = append(b, 1, 0, 0, 1)
	b = append(b, "RNDIS\x00\x00\x005162001\x00"...)
	b = append(b, make([]byte, 6)...)
	le.PutUint32(b[4:], uint32(len(b)))
	return b
}

// FunctionFSStrings is the strings blob: none (every descriptor uses string index 0).
func FunctionFSStrings() []byte {
	return le.AppendUint32(le.AppendUint32(le.AppendUint32(le.AppendUint32(nil, ffsStringsMagic), 16), 0), 0)
}
