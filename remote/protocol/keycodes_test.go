package protocol

import (
	"bytes"
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

var updateKeyCodes = flag.Bool("update", false, "regenerate keycodes.json from core's KeyUsage.kt")

const keyUsageSource = "../../core/src/main/kotlin/dev/jacobhayes/bootleg/core/hid/KeyUsage.kt"

var (
	enumEntryPattern = regexp.MustCompile(`^\s+([A-Z0-9_]+)\(0x([0-9A-Fa-f]{2})\),?\s*$`)
	putPattern       = regexp.MustCompile(`put\("([A-Za-z0-9]+)", KeyUsage\.([A-Z0-9_]+)\)`)
	// for (letter in 'A'..'Z') put("Key$letter", KeyUsage.valueOf(letter.toString()))
	// for (number in 1..24) put("F$number", KeyUsage.valueOf("F$number"))
	loopPattern = regexp.MustCompile(`for \((\w+) in ('?)(\w)'?\.\.'?(\w+)'?\) put\("(\w*)\$(\w+)", KeyUsage\.valueOf\((?:"(\w*)\$(\w+)"|(\w+)\.toString\(\))\)\)`)
)

// browserKeyCodesFromKotlin parses KeyUsage.kt: the enum's usage ids and the
// BrowserKeyCodes map (plain puts and the range loops). Every put in the
// BrowserKeyCodes block must be understood, so a new construct fails loudly
// instead of silently dropping keys.
func browserKeyCodesFromKotlin(t *testing.T) map[string]int {
	t.Helper()
	source, err := os.ReadFile(keyUsageSource)
	if err != nil {
		t.Fatalf("read %s: %v", keyUsageSource, err)
	}
	usageByName := map[string]int{}
	codes := map[string]int{}
	inBrowserCodes := false
	for lineNumber, line := range strings.Split(string(source), "\n") {
		if strings.HasPrefix(line, "object BrowserKeyCodes") {
			inBrowserCodes = true
			continue
		}
		if !inBrowserCodes {
			if match := enumEntryPattern.FindStringSubmatch(line); match != nil {
				value, _ := strconv.ParseInt(match[2], 16, 32)
				usageByName[match[1]] = int(value)
			}
			continue
		}
		if !strings.Contains(line, "put(") {
			continue
		}
		lookup := func(name string) int {
			usage, ok := usageByName[name]
			if !ok {
				t.Fatalf("KeyUsage.kt:%d: unknown usage %s", lineNumber+1, name)
			}
			return usage
		}
		if match := loopPattern.FindStringSubmatch(line); match != nil {
			isCharacter := match[2] == "'"
			first, last := match[3], match[4]
			codePrefix, usagePrefix := match[5], match[7]
			var values []string
			if isCharacter {
				for character := first[0]; character <= last[0]; character++ {
					values = append(values, string(character))
				}
			} else {
				from, _ := strconv.Atoi(first)
				to, _ := strconv.Atoi(last)
				for number := from; number <= to; number++ {
					values = append(values, strconv.Itoa(number))
				}
			}
			for _, value := range values {
				codes[codePrefix+value] = lookup(usagePrefix + value)
			}
			continue
		}
		if match := putPattern.FindStringSubmatch(line); match != nil {
			codes[match[1]] = lookup(match[2])
			continue
		}
		t.Fatalf("KeyUsage.kt:%d: unrecognised BrowserKeyCodes entry: %s", lineNumber+1, strings.TrimSpace(line))
	}
	if len(usageByName) < 100 || len(codes) < 100 {
		t.Fatalf("parsed only %d usages and %d codes from KeyUsage.kt", len(usageByName), len(codes))
	}
	return codes
}

func TestKeyCodesMatchCoreBrowserKeyCodes(t *testing.T) {
	expected := browserKeyCodesFromKotlin(t)
	encoded, err := json.MarshalIndent(expected, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	encoded = append(encoded, '\n')
	if *updateKeyCodes {
		if err := os.WriteFile(filepath.Join(".", "keycodes.json"), encoded, 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	if !bytes.Equal(encoded, KeyCodesJSON) {
		t.Fatal("keycodes.json is out of date with core's BrowserKeyCodes; run `mise run remote:keycodes`")
	}
	table := KeyCodes()
	if len(table) != len(expected) {
		t.Fatalf("KeyCodes has %d entries, want %d", len(table), len(expected))
	}
	for code, usage := range expected {
		if int(table[code]) != usage {
			t.Errorf("%s = 0x%02x, want 0x%02x", code, table[code], usage)
		}
	}
	for code, usage := range map[string]byte{"KeyA": 0x04, "Digit0": 0x27, "F24": 0x73, "MetaLeft": 0xE3, "Escape": 0x29} {
		if table[code] != usage {
			t.Errorf("spot check %s = 0x%02x, want 0x%02x", code, table[code], usage)
		}
	}
}
