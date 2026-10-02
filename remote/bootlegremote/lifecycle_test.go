package bootlegremote

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fakeBackend struct {
	closeGate  chan struct{} // Close blocks until this is closed, if set
	closeCalls atomic.Int32
	closed     chan struct{}
}

func newFakeBackend() *fakeBackend { return &fakeBackend{closed: make(chan struct{})} }

func (b *fakeBackend) Listen(string) (net.Listener, error) { return net.Listen("tcp", "127.0.0.1:0") }

func (b *fakeBackend) Status(context.Context) (status, error) {
	return status{BackendState: "Running", TailscaleIPs: []string{"100.64.0.1"}, DNSName: "bootleg-test.example.ts.net."}, nil
}

func (b *fakeBackend) WatchBus(ctx context.Context) (busWatcher, error) {
	return blockingWatcher{ctx: ctx}, nil
}

func (b *fakeBackend) WhoIs(context.Context, string) (peerIdentity, error) {
	return peerIdentity{}, errors.New("no whois in fake")
}

func (b *fakeBackend) InjectNetworkEvent() {}

func (b *fakeBackend) Close() error {
	if b.closeGate != nil {
		<-b.closeGate
	}
	if b.closeCalls.Add(1) == 1 {
		close(b.closed)
	}
	return nil
}

type blockingWatcher struct{ ctx context.Context }

func (w blockingWatcher) Next() (bool, string, error) {
	<-w.ctx.Done()
	return false, "", w.ctx.Err()
}

func (w blockingWatcher) Close() error { return nil }

type recordingCallbacks struct {
	mutex    sync.Mutex
	statuses []string
}

func (c *recordingCallbacks) OnStatus(statusJSON string) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.statuses = append(c.statuses, statusJSON)
}

func (c *recordingCallbacks) Interfaces() string { return "[]" }

// useFakeBackends makes Start hand out the given backends in order.
func useFakeBackends(t *testing.T, backends ...*fakeBackend) {
	t.Helper()
	var next atomic.Int32
	previous, previousPrepare := startBackend, prepareEnvironment
	prepareEnvironment = func(string) error { return nil }
	startBackend = func(backendConfig) (backend, error) {
		index := int(next.Add(1)) - 1
		if index >= len(backends) {
			return nil, errors.New("fake: no more backends")
		}
		return backends[index], nil
	}
	t.Cleanup(func() {
		Stop()
		startBackend, prepareEnvironment = previous, previousPrepare
	})
}

func backendStateOf(t *testing.T) string {
	t.Helper()
	var decoded status
	if err := json.Unmarshal([]byte(Status()), &decoded); err != nil {
		t.Fatal(err)
	}
	return decoded.BackendState
}

func TestStartStopRoundTrip(t *testing.T) {
	first, second := newFakeBackend(), newFakeBackend()
	useFakeBackends(t, first, second)
	callbacks := &recordingCallbacks{}
	if err := Start(t.TempDir(), "bootleg-test", "", callbacks); err != nil {
		t.Fatal(err)
	}
	if err := Start(t.TempDir(), "bootleg-test", "", callbacks); err == nil {
		t.Fatal("second Start while running must fail")
	}
	if state := backendStateOf(t); state != "Running" {
		t.Fatalf("state %q, want Running", state)
	}
	Stop()
	if first.closeCalls.Load() != 1 {
		t.Fatalf("backend closed %d times, want 1", first.closeCalls.Load())
	}
	if state := backendStateOf(t); state != backendStateStopped {
		t.Fatalf("state %q, want Stopped", state)
	}
	if currentCallbacks.Load() != nil {
		t.Fatal("Stop must clear the callbacks it installed")
	}
	Stop() // idempotent
	if err := Start(t.TempDir(), "bootleg-test", "", callbacks); err != nil {
		t.Fatalf("restart: %v", err)
	}
}

// A Start that races a Stop must wait until the old tsnet server has closed,
// and Status must say Stopping meanwhile instead of Stopped.
func TestConcurrentStartWaitsForStop(t *testing.T) {
	first, second := newFakeBackend(), newFakeBackend()
	first.closeGate = make(chan struct{})
	useFakeBackends(t, first, second)
	if err := Start(t.TempDir(), "bootleg-test", "", &recordingCallbacks{}); err != nil {
		t.Fatal(err)
	}

	stopDone := make(chan struct{})
	go func() {
		Stop()
		close(stopDone)
	}()
	waitFor(t, func() bool { return backendStateOf(t) == backendStateStopping })

	startDone := make(chan error, 1)
	newCallbacks := &recordingCallbacks{}
	go func() { startDone <- Start(t.TempDir(), "bootleg-test", "", newCallbacks) }()
	select {
	case err := <-startDone:
		t.Fatalf("Start returned (%v) while the old node was still closing", err)
	case <-time.After(50 * time.Millisecond):
	}
	if state := backendStateOf(t); state != backendStateStopping {
		t.Fatalf("state %q during stop, want Stopping", state)
	}

	close(first.closeGate)
	<-stopDone
	if err := <-startDone; err != nil {
		t.Fatalf("Start after Stop: %v", err)
	}
	if holder := currentCallbacks.Load(); holder == nil || holder.callbacks != newCallbacks {
		t.Fatal("the old Stop must not clear the new node's callbacks")
	}
	if state := backendStateOf(t); state != "Running" {
		t.Fatalf("state %q, want Running", state)
	}
}

func TestManyConcurrentStartStopStayConsistent(t *testing.T) {
	const roundCount = 20
	backends := make([]*fakeBackend, roundCount*2)
	for index := range backends {
		backends[index] = newFakeBackend()
	}
	useFakeBackends(t, backends...)
	var group sync.WaitGroup
	for range roundCount {
		group.Add(2)
		go func() {
			defer group.Done()
			_ = Start(t.TempDir(), "bootleg-test", "", &recordingCallbacks{})
		}()
		go func() {
			defer group.Done()
			Stop()
		}()
	}
	group.Wait()
	Stop()
	for index, fake := range backends {
		if calls := fake.closeCalls.Load(); calls > 1 {
			t.Fatalf("backend %d closed %d times", index, calls)
		}
	}
	if state := backendStateOf(t); state != backendStateStopped {
		t.Fatalf("state %q, want Stopped", state)
	}
}

// A Stop must only clear the holder it installed: if a newer registration
// replaced it, the newer one stays.
func TestStopKeepsNewerCallbacksHolder(t *testing.T) {
	useFakeBackends(t, newFakeBackend())
	if err := Start(t.TempDir(), "bootleg-test", "", &recordingCallbacks{}); err != nil {
		t.Fatal(err)
	}
	newer := &callbacksHolder{callbacks: &recordingCallbacks{}}
	currentCallbacks.Store(newer)
	Stop()
	if currentCallbacks.Load() != newer {
		t.Fatal("Stop cleared a newer callbacks holder")
	}
	currentCallbacks.Store(nil)
}

func waitFor(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for !condition() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met within 2s")
		}
		time.Sleep(time.Millisecond)
	}
}
