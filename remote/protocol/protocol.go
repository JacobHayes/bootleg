// Package protocol holds the Bootleg remote protocol v1 as Go sees it: message
// types, error and close codes, limits, the binary HID frame codec and the
// browser key-code table. The Kotlin RemoteController is the authority on
// message semantics; Go (transport and CLI) only needs the shapes below.
// Keep every wire name in this file so it can be checked against
// remote/PROTOCOL.md and core's Protocol.kt in one place.
package protocol

import (
	"encoding/json"
	"errors"
	"fmt"
)

const Version = 1

// Limits shared by the transport, the web UI and the CLI.
const (
	MessageBytesMax           = 64 << 10
	HidFramesPerSecondMax     = 200
	RequestsPerSecondMax      = 50
	ConnectionCountMax        = 8
	PendingConnectionCountMax = 4
	OutboundQueueLengthMax    = 32
	PingIntervalMs            = 5_000
	DeadAfterMs               = 15_000
	TypeLengthMax             = 32
	RequestIDLengthMax        = 64
	PairCodeLength            = 6
	PairRequestBytesMax       = 1 << 10
	// TokenBytes is the raw token size (256 bits). On the wire it is
	// unpadded base64url, TokenEncodedLength characters.
	TokenBytes          = 32
	TokenEncodedLength  = 43
	CloseReasonBytesMax = 120
	// Bounds on what a connection's identity may carry to Kotlin (connJson).
	// Text lengths count UTF-16 code units, as Kotlin's String.length does.
	IdentityTagCountMax   = 32
	IdentityTextLengthMax = 256
	ConnJSONBytesMax      = 16 << 10
)

// Message types ("t"). Requests carry an "id"; pushes do not.
const (
	TypeHello         = "hello"
	TypeWelcome       = "welcome"
	TypeStateGet      = "state.get"
	TypeLeaseTake     = "lease.take"
	TypeLeaseTakeover = "lease.takeover"
	// TypeLeaseTakeoverReply is the holder's answer (a mutation: allow).
	TypeLeaseTakeoverReply = "lease.takeover_reply"
	TypeLeaseRelease       = "lease.release"
	TypeSlotsApply         = "slots.apply"
	TypeSlotEject          = "slot.eject"
	TypeSlotReinsert       = "slot.reinsert"
	TypeHidSet             = "hid.set"
	TypeTetherSet          = "tether.set"
	TypeBootkeyRun         = "bootkey.run"
	TypeBootkeyCancel      = "bootkey.cancel"
	TypePastePrepare       = "paste.prepare"
	TypePasteRun           = "paste.run"
	TypePasteCancel        = "paste.cancel"
	// Pushes from the phone.
	TypeState = "state"
	TypeLease = "lease"
	TypeAuth  = "auth"
	// To the holder: someone asks for the lease, or withdrew the request.
	TypeLeaseTakeoverRequest   = "lease.takeover_request"
	TypeLeaseTakeoverCancelled = "lease.takeover_cancelled"
	// Go → Kotlin only: a hotspot pairing attempt (never sent by clients).
	TypePair = "pair"
)

// Auth states in welcome and auth pushes.
const (
	AuthApproved = "approved"
	AuthPending  = "pending"
)

// Error codes in {id, ok:false, error:{code, msg}}. Go itself only produces
// the first four; the rest come from Kotlin and are listed for the CLI's exit
// code mapping.
const (
	ErrorNotAuthorized = "not_authorized"
	ErrorBadRequest    = "bad_request"
	ErrorRateLimited   = "rate_limited"
	ErrorUnavailable   = "unavailable"

	ErrorPending       = "pending"
	ErrorUnauthorized  = "unauthorized"
	ErrorRevoked       = "revoked"
	ErrorLeaseRequired = "lease_required"
	ErrorLeaseHeld     = "lease_held"
	ErrorStaleEpoch    = "stale_epoch"
	ErrorLeaseChanged  = "lease_changed"
	ErrorDeclined      = "takeover_declined"
	ErrorLocalPriority = "local_priority"
	ErrorRevConflict   = "rev_conflict"
	ErrorNotFound      = "not_found"
	ErrorDevice        = "device"
	ErrorDisksExposed  = "disks_exposed"
	ErrorTimeout       = "timeout"
)

// Slot modes on the wire (core LunMode).
const (
	ModeCdrom  = "cdrom"
	ModeDiskRO = "disk_ro"
	ModeDiskRW = "disk_rw"
)

// Boot-key actions in bootkey.run.
const (
	BootActionMenu  = "menu"
	BootActionSetup = "setup"
)

// WebSocket close codes (4000–4999 are application codes).
const (
	CloseNormal         = 1000
	CloseServer         = 4000 // Kotlin closed the session; the reason says why
	CloseVersion        = 4400 // hello.v mismatch
	CloseUnauthorized   = 4401 // revoked, expired or unknown credentials
	CloseMalformed      = 4402 // invalid JSON envelope or binary frame
	CloseSlowConsumer   = 4408
	CloseRateLimited    = 4429
	CloseServerStopping = 4503
)

// Close reasons Go reports to Kotlin through Closed(sessionId, reason).
const (
	ReasonClientClosed    = "client_closed"
	ReasonDead            = "dead"
	ReasonSlowConsumer    = "slow_consumer"
	ReasonRateLimited     = "rate_limited"
	ReasonMalformed       = "malformed"
	ReasonVersion         = "version"
	ReasonRevoked         = "revoked"
	ReasonUnauthorized    = "unauthorized"
	ReasonListenerStopped = "listener_stopped"
	ReasonWriteFailed     = "write_failed"
	ReasonPairDone        = "pair_done"
)

// CloseCodeFor maps a close reason (Go's or Kotlin's) to a WebSocket code.
func CloseCodeFor(reason string) int {
	switch reason {
	case ReasonClientClosed:
		return CloseNormal
	case ReasonVersion:
		return CloseVersion
	case ReasonRevoked, ReasonUnauthorized, "expired":
		return CloseUnauthorized
	case ReasonMalformed:
		return CloseMalformed
	case ReasonSlowConsumer:
		return CloseSlowConsumer
	case ReasonRateLimited:
		return CloseRateLimited
	case ReasonListenerStopped:
		return CloseServerStopping
	}
	return CloseServer
}

// Envelope is the part of every JSON message Go looks at.
type Envelope struct {
	ID json.RawMessage `json:"id,omitempty"`
	T  string          `json:"t"`
	V  *int            `json:"v,omitempty"`
}

// ParseEnvelope checks that data is one JSON object with a short lowercase
// "t" and, if present, a scalar "id" of bounded length.
func ParseEnvelope(data []byte) (Envelope, error) {
	if len(data) > MessageBytesMax {
		return Envelope{}, fmt.Errorf("message of %d bytes exceeds %d", len(data), MessageBytesMax)
	}
	var envelope Envelope
	if err := json.Unmarshal(data, &envelope); err != nil {
		return Envelope{}, fmt.Errorf("not a JSON object: %w", err)
	}
	if !validType(envelope.T) {
		return Envelope{}, fmt.Errorf("invalid message type %q", truncate(envelope.T, TypeLengthMax))
	}
	if len(envelope.ID) > 0 {
		if len(envelope.ID) > RequestIDLengthMax {
			return Envelope{}, errors.New("request id too long")
		}
		switch envelope.ID[0] {
		case '"', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9':
		default:
			return Envelope{}, errors.New("request id must be a string or number")
		}
	}
	return envelope, nil
}

func validType(value string) bool {
	if value == "" || len(value) > TypeLengthMax {
		return false
	}
	for _, character := range value {
		if (character < 'a' || character > 'z') && character != '.' && character != '_' {
			return false
		}
	}
	return true
}

func truncate(value string, lengthMax int) string {
	if len(value) <= lengthMax {
		return value
	}
	return value[:lengthMax]
}

// ErrorBody is the error part of a response.
type ErrorBody struct {
	Code    string `json:"code"`
	Message string `json:"msg,omitempty"`
}

// ErrorResponse encodes {id, ok:false, error:{code, msg}}.
func ErrorResponse(id json.RawMessage, code, message string) []byte {
	type response struct {
		ID    json.RawMessage `json:"id,omitempty"`
		OK    bool            `json:"ok"`
		Error ErrorBody       `json:"error"`
	}
	encoded, err := json.Marshal(response{ID: id, OK: false, Error: ErrorBody{Code: code, Message: message}})
	if err != nil {
		panic(fmt.Sprintf("protocol: error response must encode: %v", err))
	}
	return encoded
}

// Lease is the lease part of welcome, lease and state messages. Holder is the
// holding session's id, absent when the lease is free. Local is true while
// the phone's own Control tab has priority.
type Lease struct {
	Holder     *int64 `json:"holder"`
	HolderName string `json:"holderName,omitempty"`
	Epoch      uint16 `json:"epoch"`
	Local      bool   `json:"local,omitempty"`
}

// TakeoverRequest is the lease.takeover_request push to the holder;
// lease.takeover_cancelled carries only Requester.
type TakeoverRequest struct {
	Requester     int64  `json:"requester"`
	RequesterName string `json:"requesterName,omitempty"`
	Epoch         uint16 `json:"epoch"`
	TimeoutMs     int64  `json:"timeoutMs,omitempty"`
}

// Welcome answers hello.
type Welcome struct {
	T         string `json:"t"`
	V         int    `json:"v"`
	SessionID int64  `json:"sessionId"`
	Auth      string `json:"auth"`
	StateRev  int64  `json:"stateRev"`
	Lease     Lease  `json:"lease"`
}

// Response is a reply to a request. Result fields sit next to id/ok
// (for example paste.prepare's planId); Raw keeps the whole message.
type Response struct {
	ID    json.RawMessage `json:"id"`
	OK    bool            `json:"ok"`
	Error *ErrorBody      `json:"error,omitempty"`
	Raw   json.RawMessage `json:"-"`
}

// Image is one entry of state.images.
type Image struct {
	ImageID   string `json:"imageId"`
	Name      string `json:"name"`
	SizeBytes int64  `json:"sizeBytes,omitempty"`
	// DefaultMode is the mode slots.apply uses when a slot names none.
	DefaultMode string `json:"defaultMode,omitempty"`
}

// Slot is one entry of state.slots.
type Slot struct {
	Lun     int    `json:"lun"`
	ImageID string `json:"imageId,omitempty"`
	Name    string `json:"name,omitempty"`
	Mode    string `json:"mode,omitempty"`
	Status  string `json:"status,omitempty"`
}

// SlotRequest is one entry of slots.apply.
type SlotRequest struct {
	Lun     int    `json:"lun"`
	ImageID string `json:"imageId"`
	Mode    string `json:"mode"`
}

// HidState is state.hid.
type HidState struct {
	Keyboard bool `json:"keyboard"`
	Mouse    bool `json:"mouse"`
	// Wheel is true when the mouse takes scroll frames (OpScroll); phones
	// without scroll support omit it.
	Wheel  bool   `json:"wheel,omitempty"`
	Status string `json:"status,omitempty"`
	// Link is the phone's HID session while the keyboard or mouse is on:
	// stopped, connecting, open, reconnecting, failed or restart_required.
	Link string `json:"link,omitempty"`
}

// TetherState is state.tether: the phone's USB tethering as Android reports it.
type TetherState struct {
	On bool `json:"on"`
}

// TetherResult is the tether.set result. Warning is set when tethering
// changed but the phone's drives then need attention.
type TetherResult struct {
	Tether  TetherState `json:"tether"`
	Warning string      `json:"warning,omitempty"`
}

// State is the state push and the state.get result (under "state").
type State struct {
	Rev    int64    `json:"rev"`
	Slots  []Slot   `json:"slots"`
	Images []Image  `json:"images"`
	Hid    HidState `json:"hid"`
	// Tether is nil until the phone knows whether it is tethering.
	Tether *TetherState `json:"tether,omitempty"`
	Lease  Lease        `json:"lease"`
}

// PastePlan is the paste.prepare result. Unsupported lists the characters
// the keyboard layout can't type (kept raw: a count or a list both work).
type PastePlan struct {
	PlanID      string          `json:"planId"`
	Unsupported json.RawMessage `json:"unsupported,omitempty"`
	DurationMs  int64           `json:"durationMs"`
}
