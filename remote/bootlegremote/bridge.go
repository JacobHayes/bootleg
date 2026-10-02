package bootlegremote

import (
	"sync"

	"github.com/JacobHayes/bootleg/remote/transport"
	"github.com/JacobHayes/bootleg/remote/web"
)

// Session bridge, Kotlin → Go. These are safe from any thread and return
// immediately; the transport never calls back into Kotlin while handling
// them.

// Authorized lets a session's requests and HID frames through. Until then
// only its first hello reaches Kotlin. Returns false if the session is gone.
func Authorized(sessionID int64) bool {
	return transportServer().Authorized(sessionID)
}

// Send queues a JSON message for the session (at most 32 queued; a full
// queue disconnects the slow consumer). Returns false if the session is gone
// or closing, or messageJSON is not valid JSON of at most 64 KiB.
func Send(sessionID int64, messageJSON string) bool {
	return transportServer().Send(sessionID, messageJSON)
}

// Close flushes queued messages, then closes the session with reason (sent
// in the close frame). Closed follows. Returns false if the session is gone.
func Close(sessionID int64, reason string) bool {
	return transportServer().Close(sessionID, reason)
}

// Revoke closes every live session of a credential: kind "node" with a
// tailnet stable node ID, or kind "token" with a hotspot token's SHA-256
// (hex). Returns how many sessions it closed.
func Revoke(kind, id string) int32 {
	return int32(transportServer().Revoke(kind, id))
}

var (
	transportOnce     sync.Once
	transportInstance *transport.Server
)

// transportServer is shared by the tailnet and hotspot listeners, so session
// ids are unique across both and Kotlin calls don't need to say which.
func transportServer() *transport.Server {
	transportOnce.Do(func() {
		transportInstance = transport.New(holderBridge{}, transport.DefaultConfig(web.FS))
	})
	return transportInstance
}

// holderBridge forwards to whichever Kotlin callbacks are registered. With
// none (a race with Stop), commands are dropped and HID reports queue_full;
// the listener is closing anyway.
type holderBridge struct{}

func (holderBridge) Open(sessionID int64, connJSON string) {
	if holder := currentCallbacks.Load(); holder != nil {
		holder.callbacks.Open(sessionID, connJSON)
	}
}

func (holderBridge) Command(sessionID int64, messageJSON string) {
	if holder := currentCallbacks.Load(); holder != nil {
		holder.callbacks.Command(sessionID, messageJSON)
	}
}

func (holderBridge) Hid(sessionID int64, frame []byte) int32 {
	if holder := currentCallbacks.Load(); holder != nil {
		return holder.callbacks.Hid(sessionID, frame)
	}
	return transport.HidQueueFull
}

func (holderBridge) Closed(sessionID int64, reason string) {
	if holder := currentCallbacks.Load(); holder != nil {
		holder.callbacks.Closed(sessionID, reason)
	}
}
