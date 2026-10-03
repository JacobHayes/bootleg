package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

const (
	// framesPerSecondCLI stays under the server's 200/s so a burst never
	// trips the limit and ends the session.
	framesPerSecondCLI = 150
	leaseConfirmWait   = 2 * time.Second
	// lunCountMax is the most disks the gadget exposes (LUNs 0–7).
	lunCountMax = 8
)

func dispatch(ctx context.Context, call *invocation) error {
	call.interrupt = ctx
	timeout := call.timeout
	if timeout == 0 {
		switch call.command {
		case "bootkey":
			timeout = bootkeyTimeout
		case "key":
			timeout = defaultTimeout + time.Duration(call.repeat)*call.interval
		case "type":
			timeout = defaultTimeout // extended once the plan's duration is known
		default:
			timeout = defaultTimeout
		}
	}
	if call.command == "pair" {
		ctx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		return pairCommand(ctx, call)
	}
	if call.command == "kbd" || (call.command == "lease" && call.args[0] != "release") {
		// Interactive: connect within the deadline, then run until stopped.
		return withSession(ctx, call, timeout, func(ctx context.Context, c *client) error {
			if call.command == "kbd" {
				return kbdCommand(ctx, call, c)
			}
			return leaseHoldCommand(ctx, call, c)
		})
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if call.command == "type" {
		text, err := typeText(ctx, call)
		if err != nil {
			return err
		}
		call.text = text
	}
	return withSession(ctx, call, timeout, func(ctx context.Context, c *client) error {
		switch call.command {
		case "status":
			return statusCommand(ctx, call, c)
		case "images":
			return imagesCommand(ctx, call, c)
		case "mount":
			return withLease(ctx, call, c, func() error { return mountCommand(ctx, call, c) })
		case "eject", "reinsert":
			return withLease(ctx, call, c, func() error { return slotCommand(ctx, call, c) })
		case "hid":
			return withLease(ctx, call, c, func() error { return hidCommand(ctx, call, c) })
		case "type":
			return withLease(ctx, call, c, func() error { return typeCommand(ctx, call, c) })
		case "key":
			return withLease(ctx, call, c, func() error { return keyCommand(ctx, call, c) })
		case "bootkey":
			return withLease(ctx, call, c, func() error { return bootkeyCommand(ctx, call, c) })
		case "lease":
			_, err := c.mutate(ctx, protocol.TypeLeaseRelease, nil)
			return err
		}
		return usageErrorf("unknown command %q", call.command)
	})
}

// withSession connects (within connectTimeout), waits for approval if
// --wait, runs body and closes.
func withSession(ctx context.Context, call *invocation, connectTimeout time.Duration, body func(context.Context, *client) error) error {
	connectOptions := connectOptions{host: call.host}
	if call.tokenFile != "" {
		token, err := readToken(call.tokenFile)
		if err != nil {
			return err
		}
		connectOptions.token = token
	}
	connectCtx, cancel := context.WithTimeout(ctx, connectTimeout)
	defer cancel()
	c, err := connect(connectCtx, connectOptions)
	if err != nil {
		return err
	}
	defer c.close()
	if c.currentAuth() != protocol.AuthApproved {
		if !call.wait {
			return errPending
		}
		fmt.Fprintln(call.stderr, "Waiting for approval: open Bootleg on the phone and approve this computer.")
		if err := c.waitFor(connectCtx, func() bool { return c.currentAuth() == protocol.AuthApproved }); err != nil {
			return err
		}
	}
	return body(ctx, c)
}

// acquireLease takes the lease if it's free, or asks the holder to hand it
// over with --takeover. The holder has to agree; nothing transfers on a
// timer.
func acquireLease(ctx context.Context, call *invocation, c *client) error {
	if c.holdsLease() {
		return nil
	}
	lease := c.currentLease()
	if lease.Local {
		return &protocolError{protocol.ErrorBody{Code: protocol.ErrorLocalPriority, Message: "the phone's Control tab is in use"}}
	}
	var err error
	switch {
	case lease.Holder == nil:
		_, err = c.mutate(ctx, protocol.TypeLeaseTake, nil)
	case !call.takeover:
		holder := strconv.FormatInt(*lease.Holder, 10)
		if lease.HolderName != "" {
			holder += " (" + lease.HolderName + ")"
		}
		return leaseError("another session holds the controller lease: %s; rerun with --takeover to ask for it", holder)
	default:
		fmt.Fprintln(call.stderr, "Asking the current controller to hand over…")
		_, err = c.mutate(ctx, protocol.TypeLeaseTakeover, map[string]any{"holderSession": *lease.Holder})
	}
	if err != nil {
		return err
	}
	confirmCtx, cancel := context.WithTimeout(ctx, leaseConfirmWait)
	defer cancel()
	if err := c.waitFor(confirmCtx, c.holdsLease); err != nil && !c.holdsLease() {
		return &deviceError{errors.New("the phone accepted the lease request but never confirmed it")}
	}
	return nil
}

func releaseLease(c *client) {
	if !c.holdsLease() {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), cleanupTimeout)
	defer cancel()
	c.mutate(ctx, protocol.TypeLeaseRelease, nil)
}

func withLease(ctx context.Context, call *invocation, c *client, body func() error) error {
	alreadyHeld := c.holdsLease()
	if err := acquireLease(ctx, call, c); err != nil {
		return err
	}
	if !alreadyHeld {
		defer releaseLease(c)
	}
	return body()
}

func statusCommand(ctx context.Context, call *invocation, c *client) error {
	state, raw, err := c.fetchState(ctx)
	if err != nil {
		return err
	}
	if call.json {
		var pretty bytes.Buffer
		json.Indent(&pretty, raw, "", "  ")
		fmt.Fprintln(call.stdout, pretty.String())
		return nil
	}
	fmt.Fprintf(call.stdout, "Session %d, %s, over %s\n", c.sessionID(), c.currentAuth(), call.host)
	fmt.Fprintf(call.stdout, "Lease: %s\n", describeLease(state.Lease, c.sessionID()))
	fmt.Fprintf(call.stdout, "HID: keyboard %s, mouse %s%s\n", onOff(state.Hid.Keyboard), onOff(state.Hid.Mouse), suffix(hidStatus(state.Hid)))
	if len(state.Slots) == 0 {
		fmt.Fprintln(call.stdout, "Disks: none")
	}
	for _, slot := range state.Slots {
		name := slot.Name
		if name == "" {
			name = slot.ImageID
		}
		fmt.Fprintf(call.stdout, "LUN %d: %s [%s]%s\n", slot.Lun, name, slot.Mode, suffix(slot.Status))
	}
	return nil
}

func describeLease(lease protocol.Lease, self int64) string {
	switch {
	case lease.Local:
		return fmt.Sprintf("the phone's Control tab (epoch %d)", lease.Epoch)
	case lease.Holder == nil:
		return fmt.Sprintf("free (epoch %d)", lease.Epoch)
	case *lease.Holder == self:
		return fmt.Sprintf("held by this session (epoch %d)", lease.Epoch)
	}
	name := ""
	if lease.HolderName != "" {
		name = " (" + lease.HolderName + ")"
	}
	return fmt.Sprintf("held by session %d%s (epoch %d)", *lease.Holder, name, lease.Epoch)
}

func onOff(value bool) string {
	if value {
		return "on"
	}
	return "off"
}

// hidStatus is the owed-release status, else the link when it isn't plainly open.
func hidStatus(hid protocol.HidState) string {
	if hid.Status != "" || hid.Link == "open" {
		return hid.Status
	}
	return hid.Link
}

func suffix(status string) string {
	if status == "" {
		return ""
	}
	return " — " + status
}

func imagesCommand(ctx context.Context, call *invocation, c *client) error {
	state, _, err := c.fetchState(ctx)
	if err != nil {
		return err
	}
	if call.json {
		encoded, _ := json.MarshalIndent(state.Images, "", "  ")
		fmt.Fprintln(call.stdout, string(encoded))
		return nil
	}
	for _, image := range state.Images {
		fmt.Fprintf(call.stdout, "%s\t%s\t%s\n", image.ImageID, humanBytes(image.SizeBytes), image.Name)
	}
	return nil
}

func humanBytes(size int64) string {
	const unit = 1024
	if size < unit {
		return fmt.Sprintf("%d B", size)
	}
	value, exponent := float64(size), 0
	for value >= unit && exponent < 4 {
		value /= unit
		exponent++
	}
	return fmt.Sprintf("%.1f %ciB", value, "KMGT"[exponent-1])
}

func findImage(images []protocol.Image, query string) (protocol.Image, error) {
	for _, image := range images {
		if image.ImageID == query {
			return image, nil
		}
	}
	var matches []protocol.Image
	for _, image := range images {
		if strings.EqualFold(image.Name, query) {
			matches = append(matches, image)
		}
	}
	switch len(matches) {
	case 1:
		return matches[0], nil
	case 0:
		return protocol.Image{}, &protocolError{protocol.ErrorBody{Code: protocol.ErrorNotFound, Message: fmt.Sprintf("no image %q (see `bootleg images`)", query)}}
	}
	return protocol.Image{}, usageErrorf("%d images are named %q; use the imageId", len(matches), query)
}

func mountCommand(ctx context.Context, call *invocation, c *client) error {
	state, _, err := c.fetchState(ctx)
	if err != nil {
		return err
	}
	image, err := findImage(state.Images, call.args[0])
	if err != nil {
		return err
	}
	// The phone's defaultMode; the file-name rule only if it sent none.
	mode := image.DefaultMode
	if mode == "" {
		mode = protocol.ModeDiskRO
		if strings.HasSuffix(strings.ToLower(image.Name), ".iso") {
			mode = protocol.ModeCdrom
		}
	}
	if call.mode != "" {
		mode, _ = wireMode(call.mode)
	}
	// slots.apply takes the full list with LUNs exactly 0..n-1: keep every
	// existing slot, and either append at LUN n or replace one with --lun.
	slots := make([]protocol.SlotRequest, 0, len(state.Slots)+1)
	for _, slot := range state.Slots {
		slots = append(slots, protocol.SlotRequest{Lun: slot.Lun, ImageID: slot.ImageID, Mode: slot.Mode})
	}
	sort.Slice(slots, func(i, j int) bool { return slots[i].Lun < slots[j].Lun })
	count := len(slots)
	lun := call.lun
	switch {
	case lun < 0 && count >= lunCountMax:
		return usageErrorf("all %d LUNs are in use; replace one with --lun 0–%d", lunCountMax, lunCountMax-1)
	case lun < 0:
		lun = count
		slots = append(slots, protocol.SlotRequest{Lun: lun, ImageID: image.ImageID, Mode: mode})
	case lun >= count:
		if count == 0 {
			return usageErrorf("--lun %d: no disks are exposed yet; omit --lun to add LUN 0", lun)
		}
		return usageErrorf("--lun %d: only LUNs 0–%d exist; --lun replaces one, omit it to add LUN %d", lun, count-1, count)
	default:
		index := sort.Search(count, func(index int) bool { return slots[index].Lun >= lun })
		if index == count || slots[index].Lun != lun {
			return usageErrorf("--lun %d is not an exposed LUN", lun)
		}
		slots[index] = protocol.SlotRequest{Lun: lun, ImageID: image.ImageID, Mode: mode}
	}
	if _, err := c.mutate(ctx, protocol.TypeSlotsApply, map[string]any{"slots": slots, "ifRev": state.Rev}); err != nil {
		return err
	}
	fmt.Fprintf(call.stdout, "LUN %d: %s [%s]\n", lun, image.Name, mode)
	return nil
}

func slotCommand(ctx context.Context, call *invocation, c *client) error {
	lun, err := strconv.Atoi(call.args[0])
	if err != nil || lun < 0 || lun > 7 {
		return usageErrorf("LUN must be 0–7, not %q", call.args[0])
	}
	if call.command == "eject" {
		_, err = c.mutate(ctx, protocol.TypeSlotEject, map[string]any{"lun": lun, "force": call.force})
	} else {
		_, err = c.mutate(ctx, protocol.TypeSlotReinsert, map[string]any{"lun": lun})
	}
	return err
}

func hidCommand(ctx context.Context, call *invocation, c *client) error {
	state, _, err := c.fetchState(ctx)
	if err != nil {
		return err
	}
	on := call.args[0] == "on"
	keyboard, mouse := state.Hid.Keyboard, state.Hid.Mouse
	both := !call.keyboard && !call.mouse
	if both || call.keyboard {
		keyboard = on
	}
	if both || call.mouse {
		mouse = on
	}
	_, err = c.mutate(ctx, protocol.TypeHidSet, map[string]any{"keyboard": keyboard, "mouse": mouse})
	return err
}

// typeText is the text for `type`: the argument, or stdin for "-". Stdin is
// read before connecting and bounded by ctx, so a stdin that never ends
// can't hold the lease or outlive the deadline.
func typeText(ctx context.Context, call *invocation) (string, error) {
	text := call.args[0]
	if text == "-" {
		type result struct {
			data []byte
			err  error
		}
		done := make(chan result, 1)
		go func() {
			data, err := io.ReadAll(io.LimitReader(call.stdin, protocol.MessageBytesMax+1))
			done <- result{data, err}
		}()
		select {
		case read := <-done:
			if read.err != nil {
				return "", read.err
			}
			text = string(read.data)
		case <-ctx.Done():
			return "", ctx.Err()
		}
	}
	if len(text) > protocol.MessageBytesMax-1024 {
		return "", usageErrorf("text is %d bytes; the limit is about 63 KiB", len(text))
	}
	if text == "" {
		return "", usageErrorf("nothing to type")
	}
	return text, nil
}

// jobContext bounds a job that answers when it finishes (paste.run,
// bootkey.run). Without --timeout the job gets duration from now, even past
// the command's default deadline; with --timeout the earlier deadline wins.
// Ctrl-C always stops it.
func jobContext(ctx context.Context, call *invocation, duration time.Duration) (context.Context, context.CancelFunc) {
	parent := ctx
	if call.timeout == 0 {
		parent = context.WithoutCancel(ctx)
	}
	runCtx, cancel := context.WithTimeout(parent, duration)
	stopOnInterrupt := context.AfterFunc(call.interrupt, cancel)
	return runCtx, func() {
		stopOnInterrupt()
		cancel()
	}
}

func typeCommand(ctx context.Context, call *invocation, c *client) error {
	text := call.text
	response, err := c.mutate(ctx, protocol.TypePastePrepare, map[string]any{"text": text})
	if err != nil {
		return err
	}
	var plan protocol.PastePlan
	json.Unmarshal(response.Raw, &plan)
	if plan.PlanID == "" {
		return &deviceError{errors.New("the phone returned no paste plan")}
	}
	if unsupported := strings.TrimSpace(string(plan.Unsupported)); unsupported != "" && unsupported != "0" && unsupported != "[]" && unsupported != `""` {
		fmt.Fprintf(call.stderr, "Skipping characters the keyboard can't type: %s\n", unsupported)
	}
	runCtx, cancel := jobContext(ctx, call, time.Duration(plan.DurationMs)*time.Millisecond+pasteTimeoutMargin)
	defer cancel()
	_, err = c.mutate(runCtx, protocol.TypePasteRun, map[string]any{"planId": plan.PlanID})
	if err != nil && runCtx.Err() != nil {
		cleanup, done := context.WithTimeout(context.Background(), cleanupTimeout)
		defer done()
		c.mutate(cleanup, protocol.TypePasteCancel, nil)
		if call.interrupt.Err() != nil {
			return call.interrupt.Err()
		}
	}
	return err
}

// pacer spaces HID frames to framesPerSecondCLI.
type pacer struct {
	interval time.Duration
	next     time.Time
}

func newPacer() *pacer { return &pacer{interval: time.Second / framesPerSecondCLI} }

func (p *pacer) wait(ctx context.Context) error {
	now := time.Now()
	if p.next.After(now) {
		timer := time.NewTimer(p.next.Sub(now))
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
		now = p.next
	}
	p.next = now.Add(p.interval)
	return nil
}

func sendChord(ctx context.Context, c *client, pace *pacer, keys chord) error {
	for _, frame := range keys.frames(c.currentLease().Epoch) {
		if err := pace.wait(ctx); err != nil {
			return err
		}
		if err := c.frame(ctx, frame); err != nil {
			return err
		}
	}
	return nil
}

func releaseAll(c *client) {
	ctx, cancel := context.WithTimeout(context.Background(), cleanupTimeout)
	defer cancel()
	c.frame(ctx, protocol.Frame{Epoch: c.currentLease().Epoch, Op: protocol.OpReleaseAll})
}

func keyCommand(ctx context.Context, call *invocation, c *client) error {
	keys, err := parseChord(call.args[0])
	if err != nil {
		return usageErrorf("%v", err)
	}
	defer releaseAll(c)
	pace := newPacer()
	for index := range call.repeat {
		if index > 0 {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(call.interval):
			}
		}
		if err := sendChord(ctx, c, pace, keys); err != nil {
			return err
		}
	}
	return nil
}

func bootkeyCommand(ctx context.Context, call *invocation, c *client) error {
	action := protocol.BootActionMenu
	if len(call.args) == 2 {
		action = call.args[1]
	}
	runCtx, cancel := jobContext(ctx, call, bootkeyTimeout)
	defer cancel()
	fmt.Fprintf(call.stderr, "Sending %s %s keys; power the target on now (Ctrl-C stops).\n", call.args[0], action)
	_, err := c.mutate(runCtx, protocol.TypeBootkeyRun, map[string]any{"vendor": strings.ToLower(call.args[0]), "action": action})
	if err != nil && runCtx.Err() != nil {
		cleanup, done := context.WithTimeout(context.Background(), cleanupTimeout)
		defer done()
		c.mutate(cleanup, protocol.TypeBootkeyCancel, nil)
		if call.interrupt.Err() != nil {
			return call.interrupt.Err()
		}
	}
	return err
}

// leaseHoldCommand takes the lease and keeps the session open until
// interrupted or the lease is lost, then releases it. Takeover requests
// are answered with a line from stdin (y allows; anything else, no answer
// in time, or end of input declines).
func leaseHoldCommand(ctx context.Context, call *invocation, c *client) error {
	if call.args[0] == "takeover" {
		call.takeover = true
	}
	holdCtx, stop := context.WithCancel(ctx)
	defer stop()
	prompt := newTakeoverPrompt(c, call.stderr, "\n")
	go prompt.watch(holdCtx)
	acquireCtx, cancel := context.WithTimeout(ctx, defaultTimeout)
	err := acquireLease(acquireCtx, call, c)
	cancel()
	if err != nil {
		return err
	}
	defer releaseLease(c)
	fmt.Fprintf(call.stdout, "Holding the controller lease (epoch %d). Ctrl-C releases it.\n", c.currentLease().Epoch)
	lost := make(chan error, 1)
	go func() { lost <- c.waitFor(holdCtx, func() bool { return !c.holdsLease() }) }()
	lines := readLines(holdCtx, call.stdin)
	for {
		select {
		case <-ctx.Done():
			return nil
		case err := <-lost:
			if ctx.Err() != nil {
				return nil
			}
			if err != nil {
				return err
			}
			if prompt.handedOver() {
				fmt.Fprintln(call.stderr, "Control handed over.")
				return nil
			}
			return leaseError("the lease was taken over")
		case line, ok := <-lines:
			if !ok {
				lines = nil
				prompt.closeInput()
				continue
			}
			prompt.answer(isYes(line))
		}
	}
}

func pairCommand(ctx context.Context, call *invocation) error {
	path := call.tokenFile
	if path == "" {
		var err error
		if path, err = defaultTokenFile(); err != nil {
			return usageErrorf("no --token-file and no config directory: %v", err)
		}
	}
	token, err := pair(ctx, call.host, call.args[0])
	if err != nil {
		return err
	}
	if err := writeToken(path, token); err != nil {
		return fmt.Errorf("save token: %w", err)
	}
	fmt.Fprintf(call.stdout, "Paired. Token saved to %s (mode 600). Use: bootleg --host %s --token-file %s status\n", path, call.host, path)
	return nil
}
