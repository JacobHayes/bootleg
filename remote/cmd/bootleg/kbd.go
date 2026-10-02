package main

import (
	"context"
	"fmt"
	"io"
	"os"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/term"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

const (
	escapeByte          = 0x1b
	commandByte         = 0x1d // Ctrl-]
	escapeSequenceWait  = 50 * time.Millisecond
	escapeSequenceBytes = 8
	inputBufferBytes    = 256
)

const kbdHelp = `Ctrl-] then:
  q  quit (releases everything)    r  release all keys
  d  Ctrl+Alt+Delete               s  Cmd/Win + the next key
  S  tap Cmd/Win alone             ]  send a literal Ctrl-]
  ?  this help
When another session asks for control, the next y or n keystroke
answers (and is not typed); no answer in time declines.`

// byteSource turns a blocking reader into bytes with timeouts, so a lone Esc
// can be told apart from the start of an escape sequence.
type byteSource struct {
	bytes chan byte
	done  chan struct{}
}

func newByteSource(reader io.Reader) *byteSource {
	source := &byteSource{bytes: make(chan byte, inputBufferBytes), done: make(chan struct{})}
	go func() {
		defer close(source.done)
		buffer := make([]byte, inputBufferBytes)
		for {
			count, err := reader.Read(buffer)
			for _, value := range buffer[:count] {
				source.bytes <- value
			}
			if err != nil {
				return
			}
		}
	}()
	return source
}

// next returns the next byte; ok is false at end of input, timeout or ctx end.
func (s *byteSource) next(ctx context.Context, timeout time.Duration) (byte, bool) {
	var expired <-chan time.Time
	if timeout > 0 {
		timer := time.NewTimer(timeout)
		defer timer.Stop()
		expired = timer.C
	}
	select {
	case value := <-s.bytes:
		return value, true
	default:
	}
	select {
	case value := <-s.bytes:
		return value, true
	case <-s.done:
		select {
		case value := <-s.bytes:
			return value, true
		default:
			return 0, false
		}
	case <-expired:
		return 0, false
	case <-ctx.Done():
		return 0, false
	}
}

// kbdCommand relays terminal input as key taps. Terminals report characters,
// not key-up, so every key is a tap: decoded text plus escape sequences.
func kbdCommand(ctx context.Context, call *invocation, c *client) error {
	prompt := newTakeoverPrompt(c, call.stderr, "\r\n")
	if err := acquireLease(ctx, call, c); err != nil {
		return err
	}
	defer releaseLease(c)
	defer releaseAll(c)

	if file, ok := call.stdin.(*os.File); ok && term.IsTerminal(int(file.Fd())) {
		previous, err := term.MakeRaw(int(file.Fd()))
		if err != nil {
			return fmt.Errorf("raw terminal: %w", err)
		}
		defer term.Restore(int(file.Fd()), previous)
	}
	status := func(format string, args ...any) {
		fmt.Fprintf(call.stderr, "\r\n[bootleg] "+format+"\r\n", args...)
	}
	status("relaying keystrokes to %s. Ctrl-] q quits, Ctrl-] ? shows help.", call.host)

	// The session ending (lease lost, revoked, phone gone) stops the loop.
	sessionCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() {
		c.waitFor(sessionCtx, func() bool { return !c.holdsLease() })
		cancel()
	}()
	go prompt.watch(sessionCtx)

	source := newByteSource(call.stdin)
	pace := newPacer()
	tap := func(keys chord) error { return sendChord(sessionCtx, c, pace, keys) }
	for {
		value, ok := source.next(sessionCtx, 0)
		if !ok {
			if !c.holdsLease() && ctx.Err() == nil {
				if prompt.handedOver() {
					status("control handed over")
					return nil
				}
				return leaseError("lost the controller lease")
			}
			return nil
		}
		// While another session asks for control, the next y or n answers
		// instead of being typed.
		if prompt.isPending() && strings.ContainsRune("yYnN", rune(value)) {
			prompt.answer(value == 'y' || value == 'Y')
			continue
		}
		var err error
		switch {
		case value == commandByte:
			var quit bool
			quit, err = kbdEscapeCommand(sessionCtx, source, c, tap, status)
			if quit {
				return nil
			}
		case value == escapeByte:
			sequence := readEscapeSequence(sessionCtx, source)
			if keys, ok := escapeChord(sequence); ok {
				err = tap(keys)
			} else {
				bell(call.stderr)
			}
		default:
			character := decodeCharacter(sessionCtx, source, value)
			if keys, ok := characterChord(character); ok {
				err = tap(keys)
			} else {
				bell(call.stderr)
			}
		}
		if err != nil {
			return err
		}
	}
}

func bell(output io.Writer) { fmt.Fprint(output, "\a") }

func readEscapeSequence(ctx context.Context, source *byteSource) string {
	var sequence strings.Builder
	for sequence.Len() < escapeSequenceBytes {
		value, ok := source.next(ctx, escapeSequenceWait)
		if !ok {
			break
		}
		sequence.WriteByte(value)
		first := sequence.String()[0]
		if first != '[' && first != 'O' {
			break // Alt+character
		}
		if sequence.Len() > 1 && (value == '~' || (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')) {
			break
		}
	}
	return sequence.String()
}

// decodeCharacter completes a UTF-8 sequence that starts with first.
func decodeCharacter(ctx context.Context, source *byteSource, first byte) rune {
	if first < utf8.RuneSelf {
		return rune(first)
	}
	encoded := []byte{first}
	for len(encoded) < utf8.UTFMax && !utf8.FullRune(encoded) {
		value, ok := source.next(ctx, escapeSequenceWait)
		if !ok {
			break
		}
		encoded = append(encoded, value)
	}
	character, _ := utf8.DecodeRune(encoded)
	return character
}

func kbdEscapeCommand(ctx context.Context, source *byteSource, c *client, tap func(chord) error, status func(string, ...any)) (bool, error) {
	value, ok := source.next(ctx, 0)
	if !ok {
		return true, nil
	}
	switch value {
	case 'q':
		return true, nil
	case 'r':
		releaseAll(c)
		status("released all keys")
	case 'd':
		keys, _ := parseChord("ctrl+alt+delete")
		status("sent Ctrl+Alt+Delete")
		return false, tap(keys)
	case 'S':
		keys, _ := parseChord("cmd")
		return false, tap(keys)
	case 's':
		next, ok := source.next(ctx, 0)
		if !ok {
			return true, nil
		}
		var inner chord
		if next == escapeByte {
			inner, ok = escapeChord(readEscapeSequence(ctx, source))
		} else {
			inner, ok = characterChord(decodeCharacter(ctx, source, next))
		}
		if ok {
			return false, tap(chord{modifiers: append([]byte{usageLeftGUI}, inner.modifiers...), key: inner.key})
		}
	case commandByte, ']':
		return false, tap(chord{modifiers: []byte{usageLeftControl}, key: protocol.KeyCodes()["BracketRight"]})
	case '?', 'h':
		status("%s", strings.ReplaceAll(kbdHelp, "\n", "\r\n"))
	}
	return false, nil
}
