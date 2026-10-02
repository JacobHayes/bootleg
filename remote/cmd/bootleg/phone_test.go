package main

import (
	"encoding/json"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/JacobHayes/bootleg/remote/protocol"
	"github.com/JacobHayes/bootleg/remote/transport"
)

// fakePhone stands in for Kotlin's RemoteController: just enough of the
// protocol (lease with epochs, approvals, state, slots, paste, boot keys,
// pairing) for the CLI to be exercised end to end through the real
// transport.
type fakePhone struct {
	server *transport.Server

	mutex    sync.Mutex
	approve  bool
	pairCode string
	state    protocol.State
	holder   *int64
	epoch    uint16
	sessions map[int64]bool
	frames   [][]byte
	requests []map[string]any
	pasted   []string
	// takeover is the pending lease.takeover, if any.
	takeover *pendingTakeover
	// takeoverAnnounceMs is the timeoutMs sent to the holder;
	// takeoverTimeout is when the fake itself gives up.
	takeoverAnnounceMs int64
	takeoverTimeout    time.Duration
	// holdJobs keeps paste.run and bootkey.run unanswered (until cancelled).
	holdJobs bool
}

type pendingTakeover struct {
	requester int64
	requestID any
	timer     *time.Timer
}

func newFakePhone() *fakePhone {
	return &fakePhone{
		approve:            true,
		takeoverAnnounceMs: 30_000,
		takeoverTimeout:    30 * time.Second,
		sessions:           map[int64]bool{},
		state: protocol.State{
			Rev: 1,
			Images: []protocol.Image{
				{ImageID: "img-debian", Name: "debian-13.iso", SizeBytes: 700 << 20, DefaultMode: protocol.ModeCdrom},
				// defaultMode wins over the .iso name rule.
				{ImageID: "img-hybrid", Name: "hybrid.iso", SizeBytes: 1 << 20, DefaultMode: protocol.ModeDiskRO},
				{ImageID: "img-tools", Name: "tools.img", SizeBytes: 64 << 20}, // no defaultMode: name rule
			},
			Slots: []protocol.Slot{},
		},
	}
}

func (p *fakePhone) lease() protocol.Lease {
	return protocol.Lease{Holder: p.holder, Epoch: p.epoch}
}

func (p *fakePhone) send(id int64, message any) {
	encoded, err := json.Marshal(message)
	if err != nil {
		panic(err)
	}
	p.server.Send(id, string(encoded))
}

func (p *fakePhone) Open(id int64, _ string) {
	p.mutex.Lock()
	p.sessions[id] = true
	approve := p.approve
	p.mutex.Unlock()
	if approve {
		p.server.Authorized(id)
	}
}

func (p *fakePhone) broadcastLease() {
	for id := range p.sessions {
		p.send(id, map[string]any{"t": protocol.TypeLease, "lease": p.lease()})
	}
}

func (p *fakePhone) Command(id int64, raw string) {
	var message map[string]any
	json.Unmarshal([]byte(raw), &message)
	p.mutex.Lock()
	defer p.mutex.Unlock()
	p.requests = append(p.requests, message)
	requestID := message["id"]
	reply := func(fields map[string]any) {
		response := map[string]any{"id": requestID, "ok": true}
		for key, value := range fields {
			response[key] = value
		}
		p.send(id, response)
	}
	fail := func(code string) {
		p.send(id, map[string]any{"id": requestID, "ok": false, "error": map[string]string{"code": code, "msg": "fake phone says no"}})
	}
	messageType, _ := message["t"].(string)
	switch messageType {
	case protocol.TypePair:
		if message["code"] == p.pairCode {
			p.send(id, map[string]any{"ok": true})
		} else {
			p.send(id, map[string]any{"ok": false, "error": map[string]string{"code": protocol.ErrorUnauthorized, "msg": "wrong code"}})
		}
		return
	case protocol.TypeHello:
		auth := protocol.AuthPending
		if p.approve {
			auth = protocol.AuthApproved
		}
		p.send(id, protocol.Welcome{T: protocol.TypeWelcome, V: protocol.Version, SessionID: id, Auth: auth, StateRev: p.state.Rev, Lease: p.lease()})
		return
	case protocol.TypeStateGet:
		state := p.state
		state.Lease = p.lease()
		reply(map[string]any{"state": state})
		return
	}
	epoch, _ := message["epoch"].(float64)
	if uint16(epoch) != p.epoch {
		fail(protocol.ErrorStaleEpoch)
		return
	}
	switch messageType {
	case protocol.TypeLeaseTake:
		if p.holder != nil {
			fail(protocol.ErrorLeaseHeld)
			return
		}
		p.holder, p.epoch = &id, p.epoch+1
		reply(nil)
		p.broadcastLease()
		return
	case protocol.TypeLeaseTakeover:
		holderSession, _ := message["holderSession"].(float64)
		if p.holder == nil || *p.holder != int64(holderSession) {
			fail(protocol.ErrorLeaseChanged)
			return
		}
		if p.takeover != nil {
			fail("busy")
			return
		}
		pending := &pendingTakeover{requester: id, requestID: requestID}
		pending.timer = time.AfterFunc(p.takeoverTimeout, func() {
			p.mutex.Lock()
			defer p.mutex.Unlock()
			if p.takeover == pending {
				p.endTakeover(false)
			}
		})
		p.takeover = pending
		p.send(*p.holder, map[string]any{"t": protocol.TypeLeaseTakeoverRequest, "requester": id, "requesterName": "requester-laptop", "epoch": p.epoch, "timeoutMs": p.takeoverAnnounceMs})
		return
	}
	if p.holder == nil || *p.holder != id {
		fail(protocol.ErrorLeaseRequired)
		return
	}
	switch messageType {
	case protocol.TypeLeaseRelease:
		if p.takeover != nil {
			reply(nil)
			p.endTakeover(true)
			return
		}
		p.holder, p.epoch = nil, p.epoch+1
		reply(nil)
		p.broadcastLease()
	case protocol.TypeLeaseTakeoverReply:
		if p.takeover == nil {
			fail(protocol.ErrorNotFound)
			return
		}
		allow, _ := message["allow"].(bool)
		reply(nil)
		p.endTakeover(allow)
	case protocol.TypeSlotsApply:
		if ifRev, _ := message["ifRev"].(float64); int64(ifRev) != p.state.Rev {
			fail(protocol.ErrorRevConflict)
			return
		}
		encoded, _ := json.Marshal(message["slots"])
		var slots []protocol.Slot
		json.Unmarshal(encoded, &slots)
		// LUNs must be exactly 0..n-1.
		luns := make([]int, len(slots))
		for index, slot := range slots {
			luns[index] = slot.Lun
		}
		sort.Ints(luns)
		for index, lun := range luns {
			if lun != index || len(luns) > 8 {
				fail(protocol.ErrorBadRequest)
				return
			}
		}
		p.state.Slots = slots
		p.state.Rev++
		reply(nil)
	case protocol.TypeHidSet:
		p.state.Hid.Keyboard, _ = message["keyboard"].(bool)
		p.state.Hid.Mouse, _ = message["mouse"].(bool)
		reply(nil)
	case protocol.TypePastePrepare:
		text, _ := message["text"].(string)
		p.pasted = append(p.pasted, text)
		reply(map[string]any{"planId": fmt.Sprintf("plan-%d", len(p.pasted)), "unsupported": []string{}, "durationMs": 5})
	case protocol.TypePasteRun, protocol.TypeBootkeyRun:
		if !p.holdJobs {
			reply(nil)
		}
	default:
		reply(nil)
	}
}

func (p *fakePhone) Hid(id int64, frame []byte) int32 {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	if p.holder != nil && *p.holder == id {
		p.frames = append(p.frames, append([]byte(nil), frame...))
	}
	return transport.HidQueued
}

// endTakeover settles the pending takeover: allow moves the lease to the
// requester; otherwise the requester gets takeover_declined and the holder
// a lease.takeover_cancelled.
func (p *fakePhone) endTakeover(allow bool) {
	pending := p.takeover
	p.takeover = nil
	pending.timer.Stop()
	if allow {
		requester := pending.requester
		p.holder, p.epoch = &requester, p.epoch+1
		p.send(requester, map[string]any{"id": pending.requestID, "ok": true})
		p.broadcastLease()
		return
	}
	if p.holder != nil {
		p.send(*p.holder, map[string]any{"t": protocol.TypeLeaseTakeoverCancelled, "requester": pending.requester})
	}
	p.send(pending.requester, map[string]any{"id": pending.requestID, "ok": false, "error": map[string]string{"code": protocol.ErrorDeclined, "msg": "the holder declined"}})
}

func (p *fakePhone) Closed(id int64, _ string) {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	delete(p.sessions, id)
	if p.takeover != nil && p.takeover.requester == id {
		p.takeover.timer.Stop()
		if p.holder != nil {
			p.send(*p.holder, map[string]any{"t": protocol.TypeLeaseTakeoverCancelled, "requester": id})
		}
		p.takeover = nil
	}
	if p.takeover != nil && p.holder != nil && *p.holder == id {
		p.endTakeover(true)
		return
	}
	if p.holder != nil && *p.holder == id {
		p.holder, p.epoch = nil, p.epoch+1
		p.broadcastLease()
	}
}

func (p *fakePhone) read(reader func(*fakePhone)) {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	reader(p)
}
