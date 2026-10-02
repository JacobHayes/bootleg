package bootlegremote

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestWatchRetryDelayBacksOffAndCaps(t *testing.T) {
	want := []time.Duration{250 * time.Millisecond, 500 * time.Millisecond, time.Second, 2 * time.Second, 4 * time.Second, 8 * time.Second, 10 * time.Second, 10 * time.Second}
	for index, expected := range want {
		if got := watchRetryDelay(index + 1); got != expected {
			t.Errorf("delay after %d failures = %v, want %v", index+1, got, expected)
		}
	}
	if got := watchRetryDelay(1000); got != watchRetryDelayMaxMs*time.Millisecond {
		t.Errorf("delay after 1000 failures = %v", got)
	}
}

// scriptedBackend returns watchers from a script: each entry is the number of
// notifications the stream delivers before it ends, or -1 for a failed
// subscribe.
type scriptedBackend struct {
	fakeBackend
	script []int
	calls  int
}

func (b *scriptedBackend) WatchBus(ctx context.Context) (busWatcher, error) {
	index := b.calls
	b.calls++
	if index >= len(b.script) {
		return nil, errors.New("script exhausted")
	}
	if b.script[index] < 0 {
		return nil, errors.New("subscribe failed")
	}
	return &countingWatcher{remaining: b.script[index]}, nil
}

type countingWatcher struct{ remaining int }

func (w *countingWatcher) Next() (bool, string, error) {
	if w.remaining == 0 {
		return false, "", errors.New("stream ended")
	}
	w.remaining--
	return true, "", nil
}

func (w *countingWatcher) Close() error { return nil }

func TestWatchStatusCountsStreamFailuresAndResetsAfterDelivery(t *testing.T) {
	t.Cleanup(clearError)
	// Two failed subscribes, one empty stream, then a healthy stream (resets),
	// then failures until it gives up.
	script := []int{-1, -1, 0, 3}
	for range watchFailureCountMax {
		script = append(script, 0)
	}
	running := &scriptedBackend{script: script}
	var delays []time.Duration
	published := 0
	watchStatus(context.Background(), running, func(status) { published++ },
		func(_ context.Context, delay time.Duration) { delays = append(delays, delay) })

	if len(delays) < 4 {
		t.Fatalf("got %d delays: %v", len(delays), delays)
	}
	wantStart := []time.Duration{250 * time.Millisecond, 500 * time.Millisecond, time.Second, 250 * time.Millisecond}
	for index, expected := range wantStart {
		if delays[index] != expected {
			t.Fatalf("delay %d = %v, want %v (all: %v)", index, delays[index], expected, delays)
		}
	}
	// 3 failures before the healthy stream, then the healthy stream's end and
	// watchFailureCountMax-1 more before giving up (no sleep after the last).
	if want := 3 + watchFailureCountMax - 1; len(delays) != want {
		t.Fatalf("got %d delays, want %d", len(delays), want)
	}
	if !strings.Contains(currentError(), "giving up") {
		t.Fatalf("error %q should say the watcher gave up", currentError())
	}
	if published < 1 {
		t.Fatal("expected at least one status publish")
	}
}

// enteredBackend hands out watchers that signal when Next starts blocking.
type enteredBackend struct {
	*fakeBackend
	entered chan struct{}
}

func (b *enteredBackend) WatchBus(ctx context.Context) (busWatcher, error) {
	return signallingWatcher{ctx: ctx, entered: b.entered}, nil
}

type signallingWatcher struct {
	ctx     context.Context
	entered chan struct{}
}

func (w signallingWatcher) Next() (bool, string, error) {
	select {
	case w.entered <- struct{}{}:
	default:
	}
	<-w.ctx.Done()
	return false, "", w.ctx.Err()
}

func (w signallingWatcher) Close() error { return nil }

func TestWatchStatusStopsOnCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	running := &enteredBackend{fakeBackend: newFakeBackend(), entered: make(chan struct{}, 1)}
	done := make(chan struct{})
	go func() {
		watchStatus(ctx, running, func(status) {}, sleepContext)
		close(done)
	}()
	// Cancel only once the watcher is blocked in Next, so the test covers a
	// cancel that arrives mid-stream rather than before the first subscribe.
	select {
	case <-running.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("watchStatus never called Next")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("watchStatus did not return after cancel")
	}
}
