package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

// defaultTakeoverTimeout is used when a request names no timeoutMs; the
// phone gives up after the same 30 s.
const defaultTakeoverTimeout = 30 * time.Second

// takeoverPrompt asks the user whether another session may take the lease
// this one holds. Requests arrive on the client's read loop; answers go out
// on their own goroutines so nothing here blocks it.
type takeoverPrompt struct {
	c       *client
	output  io.Writer
	newline string // "\r\n" in a raw terminal
	events  <-chan takeoverEvent

	mutex      sync.Mutex
	pending    *protocol.TakeoverRequest
	timer      *time.Timer
	allowed    bool
	declineAll bool // no way to answer any more (stdin closed)
}

func newTakeoverPrompt(c *client, output io.Writer, newline string) *takeoverPrompt {
	// Subscribe now, so a request that arrives before watch runs is queued
	// rather than declined.
	return &takeoverPrompt{c: c, output: output, newline: newline, events: c.takeoverEvents()}
}

// watch handles takeover requests and withdrawals until ctx ends.
func (p *takeoverPrompt) watch(ctx context.Context) {
	defer p.stop()
	for {
		select {
		case <-ctx.Done():
			return
		case event := <-p.events:
			if event.cancelled {
				p.withdraw(event.request.Requester)
			} else {
				p.ask(event.request)
			}
		}
	}
}

func requesterName(request protocol.TakeoverRequest) string {
	if request.RequesterName != "" {
		return request.RequesterName
	}
	return fmt.Sprintf("session %d", request.Requester)
}

func (p *takeoverPrompt) print(format string, args ...any) {
	fmt.Fprintf(p.output, p.newline+format+p.newline, args...)
}

func (p *takeoverPrompt) ask(request protocol.TakeoverRequest) {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	name := requesterName(request)
	if p.declineAll {
		p.print("%s asked for control; declined (no input to answer with).", name)
		go p.c.replyTakeover(false)
		return
	}
	if p.timer != nil {
		p.timer.Stop()
	}
	timeout := time.Duration(request.TimeoutMs) * time.Millisecond
	if timeout <= 0 || timeout > defaultTakeoverTimeout {
		timeout = defaultTakeoverTimeout
	}
	pending := &request
	p.pending = pending
	p.timer = time.AfterFunc(timeout, func() { p.expire(pending) })
	p.print("%s asks for control. Allow? [y/N]", name)
}

func (p *takeoverPrompt) withdraw(requester int64) {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	if p.pending == nil || p.pending.Requester != requester {
		return
	}
	p.print("%s withdrew the request.", requesterName(*p.pending))
	p.clearLocked()
}

func (p *takeoverPrompt) expire(request *protocol.TakeoverRequest) {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	if p.pending != request {
		return
	}
	p.clearLocked()
	p.print("No answer; declined.")
	go p.c.replyTakeover(false)
}

func (p *takeoverPrompt) clearLocked() {
	if p.timer != nil {
		p.timer.Stop()
		p.timer = nil
	}
	p.pending = nil
}

func (p *takeoverPrompt) stop() {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	p.clearLocked()
}

// isPending reports whether a request waits for an answer.
func (p *takeoverPrompt) isPending() bool {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	return p.pending != nil
}

// answer replies to the pending request, if any, and reports whether there
// was one.
func (p *takeoverPrompt) answer(allow bool) bool {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	if p.pending == nil {
		return false
	}
	name := requesterName(*p.pending)
	p.clearLocked()
	if allow {
		p.allowed = true
		p.print("Handing control to %s.", name)
	} else {
		p.print("Declined.")
	}
	go func() {
		if err := p.c.replyTakeover(allow); err != nil {
			p.mutex.Lock()
			defer p.mutex.Unlock()
			p.print("Could not answer the takeover request: %v", err)
		}
	}()
	return true
}

// closeInput declines the pending request and every later one: there is
// no way left to answer.
func (p *takeoverPrompt) closeInput() {
	p.mutex.Lock()
	p.declineAll = true
	pending := p.pending != nil
	p.mutex.Unlock()
	if pending {
		p.answer(false)
	}
}

// handedOver reports whether the user allowed a takeover.
func (p *takeoverPrompt) handedOver() bool {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	return p.allowed
}

// isYes is the [y/N] answer: only y or yes allows.
func isYes(answer string) bool {
	answer = strings.ToLower(strings.TrimSpace(answer))
	return answer == "y" || answer == "yes"
}

// readLines delivers stdin line by line; the channel closes at end of input.
// The reader goroutine may stay blocked in Read after ctx ends if stdin
// never does; it stops at the next line.
func readLines(ctx context.Context, reader io.Reader) <-chan string {
	lines := make(chan string)
	go func() {
		defer close(lines)
		scanner := bufio.NewScanner(reader)
		for scanner.Scan() {
			select {
			case lines <- scanner.Text():
			case <-ctx.Done():
				return
			}
		}
	}()
	return lines
}

// syncWriter serializes writes from the prompt goroutines and the command.
type syncWriter struct {
	mutex  sync.Mutex
	writer io.Writer
}

func (w *syncWriter) Write(data []byte) (int, error) {
	w.mutex.Lock()
	defer w.mutex.Unlock()
	return w.writer.Write(data)
}
