// Package rndis is the device side of Microsoft's Remote NDIS over USB: the control messages the
// host sends as CDC encapsulated commands, the responses it fetches, and the PACKET_MSG framing of
// Ethernet frames on the bulk endpoints. It does no IO; cmd/bootleg-rndis wires it to FunctionFS
// and a TAP interface.
//
// Every value is little-endian. A message starts with MessageType and MessageLength (bytes,
// including the header); requests carry a RequestID that the completion echoes.
package rndis

import (
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"slices"
)

var le = binary.LittleEndian

// Message types; a completion is its request's type with the high bit set. Status codes.
const (
	MsgPacket             uint32 = 1
	MsgInitialize         uint32 = 2
	MsgHalt               uint32 = 3
	MsgQuery              uint32 = 4
	MsgSet                uint32 = 5
	MsgReset              uint32 = 6
	MsgIndicateStatus     uint32 = 7
	MsgKeepalive          uint32 = 8
	completionBit         uint32 = 0x80000000
	StatusSuccess         uint32 = 0x00000000
	StatusFailure         uint32 = 0xC0000001
	StatusInvalidData     uint32 = 0xC0010015
	StatusNotSupported    uint32 = 0xC00000BB
	StatusMediaConnect    uint32 = 0x4001000B
	StatusMediaDisconnect uint32 = 0x4001000C
)

// Limits. A frame is the standard Ethernet MTU plus its 14-byte header (no VLAN tag, no FCS), which
// is what Windows' RNDIS miniport and Linux's rndis_host send. One frame per transfer each way;
// MaxTransferBytes is what INITIALIZE tells the host it may send (one PACKET_MSG plus slack, as
// Linux's f_rndis advertises).
const (
	MTUBytes                 = 1500
	EthernetHeaderBytes      = 14
	FrameBytesMax            = MTUBytes + EthernetHeaderBytes
	PacketHeaderBytes        = 44
	PacketsPerTransferMax    = 1
	MaxTransferBytes         = FrameBytesMax + PacketHeaderBytes + 22
	CommandBytesMax          = 4096
	ResponseQueueLengthMax   = 16
	MulticastAddressCountMax = 32
)

// NDIS packet filter bits (NDIS_PACKET_TYPE_*): directed, multicast, all multicast, broadcast, promiscuous.
const (
	FilterDirected     uint32 = 0x01
	FilterMulticast    uint32 = 0x02
	FilterAllMulticast uint32 = 0x04
	FilterBroadcast    uint32 = 0x08
	FilterPromiscuous  uint32 = 0x20
	filterSupported           = FilterDirected | FilterMulticast | FilterAllMulticast | FilterBroadcast | FilterPromiscuous
)

// OIDs the tests and SET name; query answers the rest of Linux f_rndis's set, which Windows' rndismp
// and Linux's rndis_host are known to work with.
const (
	OidGenSupportedList       uint32 = 0x00010101
	OidGenLinkSpeed           uint32 = 0x00010107
	OidGenCurrentPacketFilter uint32 = 0x0001010E
	OidGenCurrentLookahead    uint32 = 0x0001010F
	Oid8023PermanentAddress   uint32 = 0x01010101
	Oid8023MulticastList      uint32 = 0x01010103
)

// OIDs that read 0: hardware status (ready), media supported and in use (802.3), physical medium
// (unspecified), transmit/receive OK, errors and no-buffer counts, MAC options and the Ethernet
// error counters. The statistics are optional to report, so they aren't kept.
var zeroOids = []uint32{
	0x00010102, 0x00010103, 0x00010104, 0x00010202, 0x00020101, 0x00020102, 0x00020103, 0x00020104,
	0x00020105, 0x01010105, 0x01020101, 0x01020102, 0x01020103,
}

// ErrMalformed marks a message the device can't parse; it gets no completion.
var ErrMalformed = errors.New("rndis: malformed message")

// Device is the control state of one host session (initialized, packet filter, the completions and
// indications the host hasn't fetched) plus the phone's media state, which outlives sessions.
// Session counts sessions: a notification sent for an older one is stale. Not safe for concurrent
// use; the caller serializes it.
type Device struct {
	hostMAC     [6]byte
	initialized bool
	filter      uint32
	media       bool
	responses   [][]byte
	Session     uint64
}

// MACs derives the host adapter's and the phone's TAP addresses from seed (the phone's serial), so a
// computer keeps one adapter identity and DHCP lease: locally administered unicast, and distinct.
func MACs(seed string) (host, device [6]byte) {
	sum := sha256.Sum256([]byte("bootleg-rndis:" + seed))
	copy(host[:], sum[:6])
	host[0] = host[0]&0xFC | 0x02
	device = host
	device[5] ^= 0x01
	return host, device
}

// NewDevice returns a disconnected device that reports hostMAC, a locally administered unicast
// address, as the host adapter's address.
func NewDevice(hostMAC [6]byte) *Device {
	if hostMAC[0]&0x01 != 0 || hostMAC[0]&0x02 == 0 {
		panic(fmt.Sprintf("rndis: host MAC %x must be locally administered unicast", hostMAC))
	}
	return &Device{hostMAC: hostMAC}
}

func (d *Device) Initialized() bool { return d.initialized }

func (d *Device) Media() bool { return d.media }

// Passing reports whether frames flow at all: an initialized host that set a filter, with media.
func (d *Device) Passing() bool { return d.initialized && d.filter != 0 && d.media }

// EndSession forgets the host's session (USB bind, enable, disable or unbind; HALT; a new
// INITIALIZE): the next host starts from INITIALIZE, and nothing queued for the old one is sent.
func (d *Device) EndSession() {
	d.initialized, d.filter, d.responses = false, 0, nil
	d.Session++
}

// NextResponse pops the oldest queued response, or the single zero byte the RNDIS USB spec answers
// GET_ENCAPSULATED_RESPONSE with when nothing is queued.
func (d *Device) NextResponse() []byte {
	if len(d.responses) == 0 {
		return []byte{0}
	}
	response := d.responses[0]
	d.responses = d.responses[1:]
	return response
}

func (d *Device) queue(fields ...any) {
	var b []byte
	for _, field := range fields {
		if v, ok := field.(uint32); ok {
			b = le.AppendUint32(b, v)
		} else {
			b = append(b, field.([]byte)...)
		}
	}
	le.PutUint32(b[4:], uint32(len(b)))
	if len(d.responses) >= ResponseQueueLengthMax {
		d.responses = d.responses[1:]
	}
	d.responses = append(d.responses, b)
}

// SetMedia changes the link the device offers and reports whether an INDICATE_STATUS was queued
// for the host (only to an initialized session, only on a change).
func (d *Device) SetMedia(connected bool) bool {
	if d.media == connected {
		return false
	}
	d.media = connected
	if !d.initialized {
		return false
	}
	code := StatusMediaDisconnect
	if connected {
		code = StatusMediaConnect
	}
	d.queue(MsgIndicateStatus, uint32(0), code, uint32(0), uint32(0))
	return true
}

// Delivers reports whether frame, about to go to the host, passes its packet filter. Unicast
// passes whenever a filter is set, as with Linux's u_ether: the TAP carries frames for the host.
func (d *Device) Delivers(frame []byte) bool {
	switch {
	case !d.Passing() || len(frame) < EthernetHeaderBytes:
		return false
	case d.filter&FilterPromiscuous != 0 || frame[0]&0x01 == 0:
		return true
	case [6]byte(frame[:6]) == [6]byte{0xff, 0xff, 0xff, 0xff, 0xff, 0xff}:
		return d.filter&FilterBroadcast != 0
	default:
		return d.filter&(FilterMulticast|FilterAllMulticast) != 0
	}
}

// Handle processes one encapsulated command and reports whether it queued a completion (HALT has
// none). A malformed command gets none and returns ErrMalformed.
func (d *Device) Handle(command []byte) (bool, error) {
	if len(command) < 12 || len(command) > CommandBytesMax {
		return false, fmt.Errorf("%w: %d bytes", ErrMalformed, len(command))
	}
	messageType, messageLength := le.Uint32(command[0:]), le.Uint32(command[4:])
	headerBytes := map[uint32]int{MsgInitialize: 24, MsgQuery: 28, MsgSet: 28}[messageType]
	if messageLength < 12 || int(messageLength) > len(command) || int(messageLength) < headerBytes {
		return false, fmt.Errorf("%w: length field %d for %d bytes", ErrMalformed, messageLength, len(command))
	}
	command = command[:messageLength]
	requestID, completion := le.Uint32(command[8:]), messageType|completionBit
	switch messageType {
	case MsgInitialize:
		d.EndSession()
		// Version 1.0 is the only one there is; a host that can't take one PACKET_MSG can't be served.
		d.initialized = le.Uint32(command[12:]) == 1 && le.Uint32(command[20:]) >= FrameBytesMax+PacketHeaderBytes
		code := StatusFailure
		if d.initialized {
			code = StatusSuccess
		}
		// Version 1.0, connectionless, 802.3, the transfer limits, no padding, no AF list.
		d.queue(completion, uint32(0), requestID, code, uint32(1), uint32(0), uint32(1), uint32(0),
			uint32(PacketsPerTransferMax), uint32(MaxTransferBytes), uint32(0), uint32(0), uint32(0))
	case MsgHalt:
		d.EndSession()
		return false, nil
	case MsgQuery:
		value, code := d.query(command)
		offset := uint32(0)
		if len(value) > 0 {
			offset = 16 // counts from RequestID
		}
		d.queue(completion, uint32(0), requestID, code, uint32(len(value)), offset, value)
	case MsgSet:
		d.queue(completion, uint32(0), requestID, d.set(command))
	case MsgReset:
		// The session stays initialized but drops its filter until the host sets it again
		// (AddressingReset=1 asks it to).
		d.filter = 0
		d.queue(completion, uint32(0), StatusSuccess, uint32(1))
	case MsgKeepalive:
		d.queue(completion, uint32(0), requestID, StatusSuccess)
	default:
		return false, fmt.Errorf("%w: unknown type %#x", ErrMalformed, messageType)
	}
	return true, nil
}

// informationBuffer returns the buffer a QUERY or SET carries, or ok=false when its offset and
// length don't describe bytes after the 28-byte fixed header and inside the message.
func informationBuffer(command []byte) ([]byte, bool) {
	length := uint64(le.Uint32(command[16:]))
	offset := uint64(le.Uint32(command[20:])) + 8 // counts from RequestID (byte 8)
	if length == 0 {
		return nil, true
	}
	if offset < 28 || offset+length > uint64(len(command)) {
		return nil, false
	}
	return command[offset : offset+length], true
}

func u32(v uint32) []byte { return le.AppendUint32(nil, v) }

func (d *Device) query(command []byte) ([]byte, uint32) {
	if _, ok := informationBuffer(command); !ok {
		return nil, StatusInvalidData
	}
	media := uint32(1) // NdisMediaStateDisconnected
	if d.media {
		media = 0
	}
	values := map[uint32][]byte{
		0x00010106:                u32(MTUBytes),      // maximum frame size
		OidGenLinkSpeed:           u32(4_800_000),     // 480 Mb/s in 100 b/s, the phone's high-speed link
		0x0001010A:                u32(FrameBytesMax), // transmit block size
		0x0001010B:                u32(FrameBytesMax), // receive block size
		0x0001010C:                u32(0x00FFFFFF),    // vendor ID: no IEEE OUI
		0x0001010D:                []byte("Bootleg RNDIS\x00"),
		OidGenCurrentPacketFilter: u32(d.filter),
		0x00010111:                u32(FrameBytesMax), // maximum total size
		0x00010114:                u32(media),
		0x00010116:                u32(0x0100), // vendor driver version
		Oid8023PermanentAddress:   d.hostMAC[:],
		0x01010102:                d.hostMAC[:], // current address
		Oid8023MulticastList:      {},           // a list is accepted, not kept
		0x01010104:                u32(MulticastAddressCountMax),
	}
	for _, oid := range zeroOids {
		values[oid] = u32(0)
	}
	oid := le.Uint32(command[12:])
	if oid == OidGenSupportedList {
		var b []byte
		for _, supported := range supportedOids(values) {
			b = le.AppendUint32(b, supported)
		}
		return b, StatusSuccess
	}
	if value, ok := values[oid]; ok {
		return value, StatusSuccess
	}
	return nil, StatusNotSupported
}

func supportedOids(values map[uint32][]byte) []uint32 {
	oids := []uint32{OidGenSupportedList}
	for oid := range values {
		oids = append(oids, oid)
	}
	slices.Sort(oids)
	return oids
}

func (d *Device) set(command []byte) uint32 {
	value, ok := informationBuffer(command)
	switch oid := le.Uint32(command[12:]); {
	case !ok:
		return StatusInvalidData
	case oid == OidGenCurrentPacketFilter && len(value) == 4 && le.Uint32(value)&^filterSupported != 0:
		return StatusNotSupported
	case oid == OidGenCurrentPacketFilter && len(value) == 4:
		d.filter = le.Uint32(value)
	case oid == OidGenCurrentLookahead && len(value) == 4:
	case oid == Oid8023MulticastList && len(value)%6 == 0 && len(value)/6 <= MulticastAddressCountMax:
	case oid == OidGenCurrentPacketFilter || oid == OidGenCurrentLookahead || oid == Oid8023MulticastList:
		return StatusInvalidData
	default:
		return StatusNotSupported
	}
	return StatusSuccess
}
