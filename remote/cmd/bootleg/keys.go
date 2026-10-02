package main

import (
	"fmt"
	"strings"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

// Modifier usages (keyboard page 0xE0–0xE7).
const (
	usageLeftControl  byte = 0xE0
	usageLeftShift    byte = 0xE1
	usageLeftAlt      byte = 0xE2
	usageLeftGUI      byte = 0xE3
	usageRightControl byte = 0xE4
	usageRightShift   byte = 0xE5
	usageRightAlt     byte = 0xE6
	usageRightGUI     byte = 0xE7
)

const chordPartCountMax = 8

var modifierNames = map[string]byte{
	"ctrl": usageLeftControl, "control": usageLeftControl, "lctrl": usageLeftControl,
	"shift": usageLeftShift, "lshift": usageLeftShift,
	"alt": usageLeftAlt, "option": usageLeftAlt, "opt": usageLeftAlt, "lalt": usageLeftAlt,
	"cmd": usageLeftGUI, "command": usageLeftGUI, "meta": usageLeftGUI, "super": usageLeftGUI, "win": usageLeftGUI, "gui": usageLeftGUI,
	"rctrl": usageRightControl, "rshift": usageRightShift, "ralt": usageRightAlt, "altgr": usageRightAlt, "rcmd": usageRightGUI,
}

// keyAliases maps friendly key names to browser codes (resolved through the
// shared keycodes table, so the CLI and the web UI agree on usages).
var keyAliases = map[string]string{
	"enter": "Enter", "return": "Enter", "esc": "Escape", "escape": "Escape",
	"backspace": "Backspace", "tab": "Tab", "space": "Space",
	"delete": "Delete", "del": "Delete", "insert": "Insert", "ins": "Insert",
	"home": "Home", "end": "End", "pageup": "PageUp", "pgup": "PageUp", "pagedown": "PageDown", "pgdn": "PageDown",
	"up": "ArrowUp", "down": "ArrowDown", "left": "ArrowLeft", "right": "ArrowRight",
	"capslock": "CapsLock", "printscreen": "PrintScreen", "prtsc": "PrintScreen", "scrolllock": "ScrollLock",
	"pause": "Pause", "menu": "ContextMenu", "numlock": "NumLock",
	"minus": "Minus", "equal": "Equal", "comma": "Comma", "period": "Period", "slash": "Slash",
	"backslash": "Backslash", "semicolon": "Semicolon", "quote": "Quote", "grave": "Backquote", "backquote": "Backquote",
	"bracketleft": "BracketLeft", "bracketright": "BracketRight",
	"mute": "AudioVolumeMute", "volumeup": "AudioVolumeUp", "volumedown": "AudioVolumeDown",
	"-": "Minus", "=": "Equal", ",": "Comma", ".": "Period", "/": "Slash", "\\": "Backslash",
	";": "Semicolon", "'": "Quote", "`": "Backquote", "[": "BracketLeft", "]": "BracketRight",
}

// chord is a key combination: modifiers held around one key (or a bare
// modifier tap when key is 0).
type chord struct {
	modifiers []byte
	key       byte
}

// parseChord parses "ctrl+alt+delete", "cmd+space", "f12", "shift+a",
// "KeyA" or a bare modifier like "cmd".
func parseChord(text string) (chord, error) {
	if strings.TrimSpace(text) == "" {
		return chord{}, fmt.Errorf("empty key chord")
	}
	parts := strings.Split(text, "+")
	if len(parts) > chordPartCountMax {
		return chord{}, fmt.Errorf("chord %q has more than %d parts", text, chordPartCountMax)
	}
	var result chord
	seen := map[byte]bool{}
	for index, part := range parts {
		name := strings.TrimSpace(part)
		if name == "" {
			return chord{}, fmt.Errorf("chord %q has an empty part", text)
		}
		if modifier, ok := modifierNames[strings.ToLower(name)]; ok && (index < len(parts)-1 || len(parts) == 1) {
			if seen[modifier] {
				return chord{}, fmt.Errorf("chord %q repeats %s", text, name)
			}
			seen[modifier] = true
			if len(parts) == 1 {
				result.key = modifier
			} else {
				result.modifiers = append(result.modifiers, modifier)
			}
			continue
		}
		if index != len(parts)-1 {
			return chord{}, fmt.Errorf("chord %q: %q is not a modifier (modifiers come first)", text, name)
		}
		usage, ok := keyUsage(name)
		if !ok {
			return chord{}, fmt.Errorf("unknown key %q", name)
		}
		result.key = usage
	}
	return result, nil
}

// keyUsage resolves a key name: a single letter or digit, an alias, fN, or a
// browser KeyboardEvent.code (case-insensitive).
func keyUsage(name string) (byte, bool) {
	codes := protocol.KeyCodes()
	lower := strings.ToLower(name)
	if len(name) == 1 {
		character := lower[0]
		switch {
		case character >= 'a' && character <= 'z':
			return codes["Key"+strings.ToUpper(lower)], true
		case character >= '0' && character <= '9':
			return codes["Digit"+lower], true
		}
	}
	if code, ok := keyAliases[lower]; ok {
		usage, found := codes[code]
		return usage, found
	}
	if modifier, ok := modifierNames[lower]; ok {
		return modifier, true
	}
	for code, usage := range codes {
		if strings.EqualFold(code, name) {
			return usage, true
		}
	}
	return 0, false
}

// frames returns the down/up sequence for one tap of the chord.
func (c chord) frames(epoch uint16) []protocol.Frame {
	var result []protocol.Frame
	for _, modifier := range c.modifiers {
		result = append(result, protocol.Frame{Epoch: epoch, Op: protocol.OpKeyDown, Usage: modifier})
	}
	result = append(result,
		protocol.Frame{Epoch: epoch, Op: protocol.OpKeyDown, Usage: c.key},
		protocol.Frame{Epoch: epoch, Op: protocol.OpKeyUp, Usage: c.key})
	for index := len(c.modifiers) - 1; index >= 0; index-- {
		result = append(result, protocol.Frame{Epoch: epoch, Op: protocol.OpKeyUp, Usage: c.modifiers[index]})
	}
	return result
}

// US-layout text for kbd mode (the terminal hands us characters, not keys).
var (
	unshiftedCharacters = map[rune]string{
		'\r': "Enter", '\n': "Enter", '\t': "Tab", ' ': "Space", 0x7f: "Backspace", 0x1b: "Escape",
		'-': "Minus", '=': "Equal", '[': "BracketLeft", ']': "BracketRight", '\\': "Backslash",
		';': "Semicolon", '\'': "Quote", '`': "Backquote", ',': "Comma", '.': "Period", '/': "Slash",
	}
	shiftedCharacters = map[rune]rune{
		'!': '1', '@': '2', '#': '3', '$': '4', '%': '5', '^': '6', '&': '7', '*': '8', '(': '9', ')': '0',
		'_': '-', '+': '=', '{': '[', '}': ']', '|': '\\', ':': ';', '"': '\'', '~': '`', '<': ',', '>': '.', '?': '/',
	}
	// Terminal escape sequences (after ESC) for keys without a character.
	escapeSequences = map[string]string{
		"[A": "ArrowUp", "[B": "ArrowDown", "[C": "ArrowRight", "[D": "ArrowLeft",
		"[H": "Home", "[F": "End", "[1~": "Home", "[4~": "End", "OH": "Home", "OF": "End",
		"[2~": "Insert", "[3~": "Delete", "[5~": "PageUp", "[6~": "PageDown",
		"OP": "F1", "OQ": "F2", "OR": "F3", "OS": "F4",
		"[15~": "F5", "[17~": "F6", "[18~": "F7", "[19~": "F8",
		"[20~": "F9", "[21~": "F10", "[23~": "F11", "[24~": "F12",
		"[Z": "shift+Tab",
	}
)

// characterChord maps one typed character to the chord that produces it on
// a US layout. Ctrl-A..Ctrl-Z arrive as 0x01–0x1a.
func characterChord(character rune) (chord, bool) {
	codes := protocol.KeyCodes()
	switch {
	case character >= 'a' && character <= 'z':
		return chord{key: codes["Key"+string(character-'a'+'A')]}, true
	case character >= 'A' && character <= 'Z':
		return chord{modifiers: []byte{usageLeftShift}, key: codes["Key"+string(character)]}, true
	case character >= '0' && character <= '9':
		return chord{key: codes["Digit"+string(character)]}, true
	}
	if code, ok := unshiftedCharacters[character]; ok {
		return chord{key: codes[code]}, true
	}
	if base, ok := shiftedCharacters[character]; ok {
		inner, _ := characterChord(base)
		return chord{modifiers: []byte{usageLeftShift}, key: inner.key}, true
	}
	if character >= 0x01 && character <= 0x1a {
		return chord{modifiers: []byte{usageLeftControl}, key: codes["Key"+string(character-1+'A')]}, true
	}
	return chord{}, false
}

// escapeChord resolves the bytes after ESC: a known sequence, Alt+character
// (ESC then the character), or nil for a lone Esc.
func escapeChord(sequence string) (chord, bool) {
	if sequence == "" {
		return chord{key: protocol.KeyCodes()["Escape"]}, true
	}
	if name, ok := escapeSequences[sequence]; ok {
		parsed, err := parseChord(name)
		return parsed, err == nil
	}
	runes := []rune(sequence)
	if len(runes) == 1 {
		if inner, ok := characterChord(runes[0]); ok {
			return chord{modifiers: append([]byte{usageLeftAlt}, inner.modifiers...), key: inner.key}, true
		}
	}
	return chord{}, false
}
