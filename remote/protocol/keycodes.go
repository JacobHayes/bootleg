package protocol

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"sync"
)

// KeyCodesJSON maps browser KeyboardEvent.code values to keyboard page
// usages. It is generated from core's BrowserKeyCodes (KeyUsage.kt) by
// `mise run remote:keycodes`; a test fails when the two drift. The web UI
// fetches it as /keycodes.json and the CLI resolves key names through it.
//
//go:embed keycodes.json
var KeyCodesJSON []byte

var (
	keyCodesOnce sync.Once
	keyCodes     map[string]byte
)

// KeyCodes returns the code → usage table.
func KeyCodes() map[string]byte {
	keyCodesOnce.Do(func() {
		var decoded map[string]int
		if err := json.Unmarshal(KeyCodesJSON, &decoded); err != nil {
			panic(fmt.Sprintf("protocol: keycodes.json is invalid: %v", err))
		}
		keyCodes = make(map[string]byte, len(decoded))
		for code, usage := range decoded {
			if usage < KeyUsageMin || usage > KeyUsageMax {
				panic(fmt.Sprintf("protocol: keycodes.json maps %q to out-of-range usage 0x%x", code, usage))
			}
			keyCodes[code] = byte(usage)
		}
	})
	return keyCodes
}
