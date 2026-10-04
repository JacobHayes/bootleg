package rndis

import "fmt"

// UnwrapBytesMax bounds one host-to-device transfer. We advertise one PACKET_MSG per transfer, but
// a host that batches anyway (up to its own 16 KiB) is still served.
const UnwrapBytesMax = 16 << 10

// Unwrap calls frame for each Ethernet frame in a host-to-device bulk transfer, in order. A
// transfer holds one or more PACKET_MSGs back to back; a single trailing zero byte (the host's
// short-packet terminator) is allowed. It stops at the first malformed message and returns an
// error; frames before it were already delivered. Frames alias transfer.
func Unwrap(transfer []byte, frame func([]byte)) error {
	if len(transfer) > UnwrapBytesMax {
		return fmt.Errorf("%w: transfer of %d bytes", ErrMalformed, len(transfer))
	}
	for len(transfer) > 0 {
		if len(transfer) == 1 && transfer[0] == 0 {
			return nil
		}
		if len(transfer) < PacketHeaderBytes {
			return fmt.Errorf("%w: %d trailing bytes", ErrMalformed, len(transfer))
		}
		messageType := le.Uint32(transfer[0:])
		messageLength := uint64(le.Uint32(transfer[4:]))
		if messageType != MsgPacket {
			return fmt.Errorf("%w: type %#x on the data endpoint", ErrMalformed, messageType)
		}
		if messageLength < PacketHeaderBytes || messageLength > uint64(len(transfer)) {
			return fmt.Errorf("%w: packet length %d with %d bytes left", ErrMalformed, messageLength, len(transfer))
		}
		// DataOffset counts from the DataOffset field itself (byte 8).
		dataOffset := uint64(le.Uint32(transfer[8:])) + 8
		dataLength := uint64(le.Uint32(transfer[12:]))
		if dataOffset < PacketHeaderBytes || dataOffset+dataLength > messageLength {
			return fmt.Errorf("%w: data %d+%d outside a %d-byte packet", ErrMalformed, dataOffset, dataLength, messageLength)
		}
		if dataLength < EthernetHeaderBytes || dataLength > FrameBytesMax {
			return fmt.Errorf("%w: frame of %d bytes", ErrMalformed, dataLength)
		}
		frame(transfer[dataOffset : dataOffset+dataLength])
		transfer = transfer[messageLength:]
	}
	return nil
}

// Wrap appends a PACKET_MSG holding frame to dst and returns it. When the message is a whole
// number of maxPacketBytes-sized USB packets, one zero byte is appended outside the message so the
// transfer ends with a short packet (FunctionFS sends no zero-length packet, and the host would
// otherwise wait for more).
func Wrap(dst, frame []byte, maxPacketBytes int) []byte {
	if len(frame) < EthernetHeaderBytes || len(frame) > FrameBytesMax {
		panic(fmt.Sprintf("rndis: frame of %d bytes", len(frame)))
	}
	if maxPacketBytes <= 0 {
		panic(fmt.Sprintf("rndis: max packet size %d", maxPacketBytes))
	}
	length := PacketHeaderBytes + len(frame)
	dst = le.AppendUint32(dst, MsgPacket)
	dst = le.AppendUint32(dst, uint32(length))
	dst = le.AppendUint32(dst, PacketHeaderBytes-8) // DataOffset
	dst = le.AppendUint32(dst, uint32(len(frame)))  // DataLength
	dst = append(dst, make([]byte, PacketHeaderBytes-16)...)
	dst = append(dst, frame...)
	if length%maxPacketBytes == 0 {
		dst = append(dst, 0)
	}
	return dst
}
