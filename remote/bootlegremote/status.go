package bootlegremote

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"time"
)

const (
	localAPITimeoutMs = 5_000
	// The status watcher backs off from watchRetryDelayMinMs, doubling up to
	// watchRetryDelayMaxMs, and gives up after watchFailureCountMax consecutive
	// failures. A failed subscribe and a stream that ends both count; only a
	// stream that delivered a notification resets the count.
	watchRetryDelayMinMs = 250
	watchRetryDelayMaxMs = 10_000
	watchFailureCountMax = 60
)

type status struct {
	BackendState   string   `json:"backendState"`
	TailscaleIPs   []string `json:"tailscaleIPs"`
	LoginURL       string   `json:"loginURL,omitempty"`
	DNSName        string   `json:"dnsName,omitempty"`
	MagicDNSSuffix string   `json:"magicDNSSuffix,omitempty"`
	Health         []string `json:"health,omitempty"`
	Error          string   `json:"error,omitempty"`
}

const (
	backendStateStopped  = "Stopped"
	backendStateStopping = "Stopping"
	backendStateUnknown  = "Unknown"
)

func encodeStatus(value status) string {
	if value.TailscaleIPs == nil {
		value.TailscaleIPs = []string{}
	}
	encoded, err := json.Marshal(value)
	if err != nil {
		panic(fmt.Sprintf("bootlegremote: status must always encode: %v", err))
	}
	return string(encoded)
}

func statusOf(ctx context.Context, running backend) status {
	ctx, cancel := context.WithTimeout(ctx, localAPITimeoutMs*time.Millisecond)
	defer cancel()
	result, err := running.Status(ctx)
	if err != nil {
		return status{BackendState: backendStateUnknown, Error: err.Error()}
	}
	result.Error = currentError()
	return result
}

// watchRetryDelay is the wait after the failureCount-th consecutive failure.
func watchRetryDelay(failureCount int) time.Duration {
	if failureCount < 1 {
		panic(fmt.Sprintf("bootlegremote: failure count must be positive, got %d", failureCount))
	}
	delay := time.Duration(watchRetryDelayMinMs) * time.Millisecond
	for range failureCount - 1 {
		delay *= 2
		if delay >= watchRetryDelayMaxMs*time.Millisecond {
			return watchRetryDelayMaxMs * time.Millisecond
		}
	}
	return delay
}

// watchStatus calls publish with a fresh snapshot whenever the IPN bus reports
// a change that can affect it, deduplicating identical snapshots. It returns
// when ctx ends or after watchFailureCountMax consecutive failures.
func watchStatus(ctx context.Context, running backend, publish func(status), sleep func(context.Context, time.Duration)) {
	lastSent := ""
	send := func() {
		snapshot := statusOf(ctx, running)
		encoded := encodeStatus(snapshot)
		if encoded != lastSent && ctx.Err() == nil {
			lastSent = encoded
			publish(snapshot)
		}
	}
	failureCount := 0
	for ctx.Err() == nil {
		delivered := watchOnce(ctx, running, send)
		if ctx.Err() != nil {
			return
		}
		if delivered {
			failureCount = 0
		}
		failureCount++
		if failureCount >= watchFailureCountMax {
			setError(fmt.Errorf("status watch failed %d times in a row; giving up", failureCount))
			send()
			return
		}
		sleep(ctx, watchRetryDelay(failureCount))
	}
}

// watchOnce subscribes and forwards notifications until the stream ends. It
// reports whether at least one notification arrived.
func watchOnce(ctx context.Context, running backend, send func()) bool {
	watcher, err := running.WatchBus(ctx)
	if err != nil {
		if ctx.Err() == nil {
			log.Printf("bootlegremote: watch ipn bus: %v", err)
		}
		return false
	}
	defer watcher.Close()
	delivered := false
	for {
		changed, errorMessage, err := watcher.Next()
		if err != nil {
			return delivered
		}
		delivered = true
		if errorMessage != "" {
			setError(errors.New(errorMessage))
		}
		if changed {
			send()
		}
	}
}

func sleepContext(ctx context.Context, duration time.Duration) {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
	case <-timer.C:
	}
}
