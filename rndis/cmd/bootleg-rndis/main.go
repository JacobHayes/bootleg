//go:build linux

// Command bootleg-rndis serves the RNDIS USB function from a mounted FunctionFS instance and bridges
// its frames to a TAP interface that Android tethers (DHCP, DNS, NAT and the upstream are
// Android's). The root service runs it as its own process (setsid, stdio on files) and talks to it
// through the state directory:
//   - daemon.lock: flock'ed for the daemon's life (two ep0 holders can wedge the kernel's unbind);
//   - command.json: {"release": bool, "media": bool}, written by root before each SIGUSR1;
//   - status.json: written atomically on every change: pid, released, the last USB event, whether
//     a host initialized the function, media, and whether the last media-off drained.
//
// It starts held (ep0 open, no descriptors), so a config with the function linked can't bind until
// a command releases it, and disconnected. SIGTERM stops it.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"io"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"github.com/JacobHayes/bootleg/rndis"
)

const (
	logLinesMax         = 200
	commandBytesMax     = 4096
	ep0EventBytes       = 12
	ep0EventsPerReadMax = 4
	ep0PollTimeout      = 50 * time.Millisecond
	// A nonblocking ep0 call that finds the function's mutex taken retries this often, this many times.
	ep0RetryInterval = time.Millisecond
	ep0RetryCountMax = 200
	// A data stage the host hasn't completed by then is interrupted (see dataStage).
	ep0DataStageTimeout     = 500 * time.Millisecond
	ep0InterruptInterval    = 20 * time.Millisecond
	ep0InterruptCountMax    = 50
	errorBackoff            = 10 * time.Millisecond
	drainPollInterval       = 5 * time.Millisecond
	drainPollCountMax       = 100
	eventSetup              = 4
	requestTypeDeviceToHost = 0x80
)

// FunctionFS event types (struct usb_functionfs_event.type), except SETUP.
var eventNames = map[byte]string{0: "bind", 1: "unbind", 2: "enable", 3: "disable", 5: "suspend", 6: "resume"}

type command struct {
	Release bool `json:"release"`
	Media   bool `json:"media"`
}

type status struct {
	Pid      int    `json:"pid"`
	Released bool   `json:"released"`
	USB      string `json:"usb"`
	Host     bool   `json:"host"`
	Media    bool   `json:"media"`
	Drained  bool   `json:"drained"`
}

// The main goroutine (on a locked OS thread) owns ep0, the files and status; the data path
// goroutines share only device, under mu, and the counters.
type daemon struct {
	ffsDir, stateDir string
	ep0, tid         int
	notify, dataIn   *os.File
	dataOut, tap     *os.File
	status           status
	lastStatus       []byte
	logLines         atomic.Int64
	// inFlight counts frame transfers admitted through the device's gates and not yet finished.
	inFlight      atomic.Int32
	notifications chan uint64
	mu            sync.Mutex
	device        *rndis.Device
}

func (d *daemon) logf(format string, arguments ...any) {
	if d.logLines.Add(1) <= logLinesMax {
		log.Printf(format, arguments...)
	}
}

func main() {
	ffsDir := flag.String("ffs", "", "mounted FunctionFS directory of the function")
	tapName := flag.String("tap", "", "TAP interface to create")
	stateDir := flag.String("state", "", "directory for the lock, command and status files")
	macSeed := flag.String("mac-seed", "", "stable seed of the adapters' MAC addresses (the phone's serial)")
	flag.Parse()
	log.SetFlags(log.Lmicroseconds)
	log.SetPrefix("bootleg-rndis: ")
	if *ffsDir == "" || *tapName == "" || len(*tapName) >= 16 || *stateDir == "" || *macSeed == "" {
		log.Fatalf("usage: bootleg-rndis -ffs DIR -tap NAME -state DIR -mac-seed SEED")
	}
	lock, err := os.OpenFile(filepath.Join(*stateDir, "daemon.lock"), os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil || syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB) != nil {
		log.Fatalf("another daemon holds the lock in %s (%v)", *stateDir, err)
	}
	// Before readiness is published: a release sent the moment root sees our status must not be lost.
	signals := make(chan os.Signal, 8)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT, syscall.SIGUSR1)
	signal.Ignore(syscall.SIGHUP, syscall.SIGPIPE)
	// Every ep0 call happens on this thread, so dataStage can interrupt it.
	runtime.LockOSThread()
	hostMAC, deviceMAC := rndis.MACs(*macSeed)
	d := &daemon{
		ffsDir: *ffsDir, stateDir: *stateDir, tid: syscall.Gettid(),
		device: rndis.NewDevice(hostMAC), status: status{Pid: os.Getpid(), USB: "none", Drained: true},
		notifications: make(chan uint64, rndis.ResponseQueueLengthMax),
	}
	// A blocking ep0 read sleeps holding the function's mutex, which functionfs_unbind takes after
	// queueing UNBIND: a reader that consumes UNBIND and reads again first deadlocks the UDC write
	// (and everything reading UDC). Nonblocking calls only try-lock it, and ppoll doesn't hold it.
	if d.ep0, err = syscall.Open(filepath.Join(*ffsDir, "ep0"), syscall.O_RDWR|syscall.O_NONBLOCK|syscall.O_CLOEXEC, 0); err != nil {
		log.Fatalf("open ep0: %v", err)
	}
	if d.tap, err = openTap(*tapName, deviceMAC); err != nil {
		log.Fatalf("tap %s: %v", *tapName, err)
	}
	for {
		d.writeStatus()
		var s os.Signal
		if d.status.Released {
			select {
			case s = <-signals:
			default:
				d.serveEp0()
				continue
			}
		} else {
			s = <-signals // held: nothing on ep0 to serve
		}
		if s != syscall.SIGUSR1 {
			d.logf("stopping on %v", s)
			os.Exit(0)
		}
		d.applyCommand()
	}
}

// applyCommand reads command.json and applies the media state, then a release. Commands are
// levels, not edges, so coalesced signals lose nothing. Media off is reported drained only once no
// admitted frame is still being transferred (bounded: a host that stopped reading keeps a bulk-IN
// write pending until the next USB disable).
func (d *daemon) applyCommand() {
	f, err := os.Open(filepath.Join(d.stateDir, "command.json"))
	var data []byte
	if err == nil {
		data, err = io.ReadAll(io.LimitReader(f, commandBytesMax))
		f.Close()
	}
	var c command
	if err == nil {
		err = json.Unmarshal(data, &c)
	}
	if err != nil {
		d.logf("command: %v", err)
		return
	}
	d.mu.Lock()
	if d.device.SetMedia(c.Media) {
		d.notifyLocked()
	}
	d.mu.Unlock()
	d.status.Media, d.status.Drained = c.Media, c.Media || d.inFlight.Load() == 0
	for i := 0; !d.status.Drained && i < drainPollCountMax; i++ {
		time.Sleep(drainPollInterval)
		d.status.Drained = d.inFlight.Load() == 0
	}
	if c.Release && !d.status.Released {
		d.release()
	}
}

// release writes the descriptors, opens the endpoints and starts the data path.
func (d *daemon) release() {
	if _, err := d.ep0Call(func() (int, error) { return syscall.Write(d.ep0, rndis.FunctionFSDescriptors()) }, false); err != nil {
		log.Fatalf("write descriptors: %v", err)
	}
	if _, err := d.ep0Call(func() (int, error) { return syscall.Write(d.ep0, rndis.FunctionFSStrings()) }, false); err != nil {
		log.Fatalf("write strings: %v", err)
	}
	// Blocking fds outside the runtime poller: transfers wait in the kernel, and fail with
	// ESHUTDOWN while the host has the function disabled.
	open := func(name string, flag int) *os.File {
		fd, err := syscall.Open(filepath.Join(d.ffsDir, name), flag|syscall.O_CLOEXEC, 0)
		if err != nil {
			log.Fatalf("open %s: %v", name, err)
		}
		return os.NewFile(uintptr(fd), name)
	}
	d.notify, d.dataIn, d.dataOut = open("ep1", os.O_WRONLY), open("ep2", os.O_WRONLY), open("ep3", os.O_RDONLY)
	go d.notifyLoop()
	go d.hostToDevice()
	go d.deviceToHost()
	d.status.Released = true
}

// ep0Call runs one nonblocking ep0 read or write, retrying while the function's mutex is taken
// (EAGAIN). A data stage interrupted by dataStage's watchdog (EINTR) is not retried.
func (d *daemon) ep0Call(call func() (int, error), dataStage bool) (int, error) {
	for attempt := 0; ; attempt++ {
		n, err := call()
		switch {
		case err == nil:
			return n, nil
		case errors.Is(err, syscall.EINTR) && dataStage:
			return 0, err
		case !errors.Is(err, syscall.EAGAIN) && !errors.Is(err, syscall.EINTR):
			// The function is no longer active (EBADFD), or ep0 is gone: exit, so root sees the
			// function dead and restarts it.
			if errors.Is(err, syscall.EBADFD) || errors.Is(err, syscall.EBADF) || errors.Is(err, syscall.ENODEV) {
				log.Fatalf("ep0: %v", err)
			}
			return 0, err
		case attempt >= ep0RetryCountMax:
			return 0, errors.New("ep0 busy")
		}
		time.Sleep(ep0RetryInterval)
	}
}

// dataStage runs the data stage of a control request. f_fs waits for the host to complete it
// while holding the function's mutex, whatever O_NONBLOCK says, so a host that stops mid-transfer
// would also wedge a UDC unbind. A watchdog interrupts the wait with SIGURG to this thread (the Go
// runtime's own preemption signal, harmless to it): f_fs then dequeues the request and returns EINTR.
func (d *daemon) dataStage(call func() (int, error)) (int, error) {
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-done:
			return
		case <-time.After(ep0DataStageTimeout):
		}
		for range ep0InterruptCountMax {
			_ = syscall.Tgkill(os.Getpid(), d.tid, syscall.SIGURG)
			select {
			case <-done:
				return
			case <-time.After(ep0InterruptInterval):
			}
		}
	}()
	return d.ep0Call(call, true)
}

// serveEp0 waits (bounded) for ep0 events and handles them.
func (d *daemon) serveEp0() {
	fds := [1]struct {
		fd             int32
		events, result int16
	}{{fd: int32(d.ep0), events: 1}} // POLLIN
	timeout := syscall.NsecToTimespec(ep0PollTimeout.Nanoseconds())
	ready, _, errno := syscall.Syscall6(syscall.SYS_PPOLL, uintptr(unsafe.Pointer(&fds[0])), 1, uintptr(unsafe.Pointer(&timeout)), 0, 0, 0)
	if errno != 0 && errno != syscall.EINTR {
		log.Fatalf("ep0 poll: %v", errno)
	}
	if ready == 0 || fds[0].result&1 == 0 {
		return
	}
	buffer := make([]byte, ep0EventBytes*ep0EventsPerReadMax)
	n, err := d.ep0Call(func() (int, error) { return syscall.Read(d.ep0, buffer) }, false)
	if err != nil {
		d.logf("ep0 read: %v", err)
		return
	}
	for offset := 0; offset+ep0EventBytes <= n; offset += ep0EventBytes {
		event := buffer[offset : offset+ep0EventBytes]
		if event[8] == eventSetup {
			d.setup(event[:8])
			continue
		}
		name, known := eventNames[event[8]]
		if !known {
			d.logf("unknown FunctionFS event %d", event[8])
			continue
		}
		// FunctionFS coalesces events (an unread DISABLE can turn into ENABLE), so every one of them
		// ends the host's session: frames pass again only after the host initializes anew.
		d.status.USB = name
		if name == "bind" || name == "unbind" || name == "enable" || name == "disable" {
			d.mu.Lock()
			d.device.EndSession()
			d.mu.Unlock()
		}
	}
}

// setup answers a control request to our control interface: CDC SEND_ENCAPSULATED_COMMAND and
// GET_ENCAPSULATED_RESPONSE. Anything else is stalled.
func (d *daemon) setup(request []byte) {
	requestType, requestCode := request[0], request[1]
	value, index, length := le16(request[2:]), le16(request[4:]), int(le16(request[6:]))
	switch {
	case requestType == 0x21 && requestCode == 0x00 && value == 0 && index == 0 && length > 0 && length <= rndis.CommandBytesMax:
		message := make([]byte, length)
		n, err := d.dataStage(func() (int, error) { return syscall.Read(d.ep0, message) })
		if err != nil {
			d.logf("encapsulated command: %v", err)
			return
		}
		d.mu.Lock()
		queued, err := d.device.Handle(message[:n])
		if queued {
			d.notifyLocked()
		}
		d.mu.Unlock()
		if err != nil {
			d.logf("%v", err)
		}
	case requestType == 0xA1 && requestCode == 0x01 && value == 0 && index == 0:
		d.mu.Lock()
		response := d.device.NextResponse()
		d.mu.Unlock()
		if len(response) > length {
			d.logf("response of %d bytes cut to the host's %d", len(response), length)
			response = response[:length]
		}
		if _, err := d.dataStage(func() (int, error) { return syscall.Write(d.ep0, response) }); err != nil {
			d.logf("encapsulated response: %v", err)
		}
	default:
		d.logf("stalling request type %#x code %#x value %#x index %d length %d", requestType, requestCode, value, index, length)
		// FunctionFS stalls on a read for a device-to-host request and on a write for the other way.
		if requestType&requestTypeDeviceToHost != 0 {
			_, _ = d.ep0Call(func() (int, error) { return syscall.Read(d.ep0, nil) }, false)
		} else {
			_, _ = d.ep0Call(func() (int, error) { return syscall.Write(d.ep0, nil) }, false)
		}
	}
}

func le16(b []byte) uint16 { return uint16(b[0]) | uint16(b[1])<<8 }

// notifyLocked asks for a RESPONSE_AVAILABLE notification in the current session. Caller holds mu.
func (d *daemon) notifyLocked() {
	select {
	case d.notifications <- d.device.Session:
	default:
		d.logf("notification queue full")
	}
}

// notifyLoop sends RESPONSE_AVAILABLE on the interrupt endpoint, one per queued response of the
// current session. A write waits while the host isn't polling, so it runs apart from ep0.
func (d *daemon) notifyLoop() {
	notification := []byte{1, 0, 0, 0, 0, 0, 0, 0}
	for session := range d.notifications {
		d.mu.Lock()
		current := session == d.device.Session
		d.mu.Unlock()
		if !current {
			continue
		}
		if _, err := d.notify.Write(notification); err != nil && !errors.Is(err, syscall.ESHUTDOWN) {
			d.logf("notify: %v", err)
		}
	}
}

// admit counts a frame transfer in flight if the gate allows it, atomically with the check, so a
// media-off that waits for the count to drain is a barrier for every frame it let through.
func (d *daemon) admit(gate func() bool) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if !gate() {
		return false
	}
	d.inFlight.Add(1)
	return true
}

func (d *daemon) hostToDevice() {
	transfer := make([]byte, rndis.UnwrapBytesMax)
	for {
		n, err := d.dataOut.Read(transfer)
		if err != nil || n == 0 {
			// ESHUTDOWN while disabled; the pause keeps a host sending only zero-length packets
			// from spinning us.
			if err != nil && !errors.Is(err, syscall.ESHUTDOWN) {
				d.logf("bulk out: %v", err)
			}
			time.Sleep(errorBackoff)
			continue
		}
		if !d.admit(d.device.Passing) {
			continue // no session, no filter, or tethering off: the host's frames go nowhere
		}
		err = rndis.Unwrap(transfer[:n], func(frame []byte) { _, _ = d.tap.Write(frame) })
		d.inFlight.Add(-1)
		if err != nil {
			d.logf("bulk out: %v", err)
		}
	}
}

func (d *daemon) deviceToHost() {
	frame := make([]byte, rndis.FrameBytesMax+1)
	message := make([]byte, 0, rndis.PacketHeaderBytes+rndis.FrameBytesMax+1)
	for {
		n, err := d.tap.Read(frame)
		if err != nil {
			log.Fatalf("tap read: %v", err)
		}
		if n < rndis.EthernetHeaderBytes || n > rndis.FrameBytesMax || !d.admit(func() bool { return d.device.Delivers(frame[:n]) }) {
			continue
		}
		// The speed isn't known here; every bulk packet size is a multiple of the full-speed 64, so
		// terminating on that covers them all at the cost of a rare extra byte.
		message = rndis.Wrap(message[:0], frame[:n], rndis.FullSpeedBulkBytes)
		_, err = d.dataIn.Write(message)
		d.inFlight.Add(-1)
		if err != nil {
			if !errors.Is(err, syscall.ESHUTDOWN) {
				d.logf("bulk in: %v", err)
			}
			time.Sleep(errorBackoff)
		}
	}
}

// writeStatus rewrites status.json atomically when anything in it changed.
func (d *daemon) writeStatus() {
	d.mu.Lock()
	d.status.Host = d.device.Initialized()
	d.mu.Unlock()
	data, err := json.Marshal(d.status)
	if err != nil || string(data) == string(d.lastStatus) {
		return
	}
	temporary := filepath.Join(d.stateDir, ".status.json.tmp")
	if err = os.WriteFile(temporary, data, 0o600); err == nil {
		err = os.Rename(temporary, filepath.Join(d.stateDir, "status.json"))
	}
	if err != nil {
		d.logf("status: %v", err)
		return
	}
	d.lastStatus = data
}
