package web

import (
	"io/fs"
	"regexp"
	"strings"
	"testing"
)

// The page is served under default-src 'self': anything inline would be
// blocked by the browser, so it must not exist.
func TestNoInlineCodeAndAssetsExist(t *testing.T) {
	index, err := fs.ReadFile(FS, "index.html")
	if err != nil {
		t.Fatal(err)
	}
	page := string(index)
	for _, forbidden := range []*regexp.Regexp{
		regexp.MustCompile(`<script(?:\s[^>]*)?>\s*[^<\s]`), // inline script body
		regexp.MustCompile(`<style`),
		regexp.MustCompile(`\sstyle=`),
		regexp.MustCompile(`\son[a-z]+=`),
		regexp.MustCompile(`(?:src|href)="(?:https?:)?//`),
	} {
		if match := forbidden.FindString(page); match != "" {
			t.Errorf("index.html contains %q (blocked by the CSP or loads off-origin)", match)
		}
	}
	for _, match := range regexp.MustCompile(`<script[^>]*>`).FindAllString(page, -1) {
		if !strings.Contains(match, `src="`) {
			t.Errorf("script tag without src: %s", match)
		}
	}
	for _, match := range regexp.MustCompile(`(?:src|href)="([^"]+)"`).FindAllStringSubmatch(page, -1) {
		if _, err := fs.Stat(FS, match[1]); err != nil {
			t.Errorf("index.html references missing %s", match[1])
		}
	}
	script, err := fs.ReadFile(FS, "app.js")
	if err != nil {
		t.Fatal(err)
	}
	for _, forbidden := range []string{"eval(", "new Function", "innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "localStorage"} {
		if strings.Contains(string(script), forbidden) {
			t.Errorf("app.js uses %s", forbidden)
		}
	}
}

// Every element id the script looks up exists in the page.
func TestScriptIDsExist(t *testing.T) {
	index, _ := fs.ReadFile(FS, "index.html")
	script, _ := fs.ReadFile(FS, "app.js")
	for _, match := range regexp.MustCompile(`\$\("([a-z-]+)"\)`).FindAllStringSubmatch(string(script), -1) {
		if !strings.Contains(string(index), `id="`+match[1]+`"`) {
			t.Errorf("app.js uses #%s, which index.html lacks", match[1])
		}
	}
}

// The lease holder must be able to answer a takeover request: the page has
// the prompt and the script handles the request, its withdrawal and the
// reply.
func TestTakeoverPromptWired(t *testing.T) {
	index, _ := fs.ReadFile(FS, "index.html")
	script, _ := fs.ReadFile(FS, "app.js")
	for _, id := range []string{"takeover", "takeover-text", "takeover-allow", "takeover-decline"} {
		if !strings.Contains(string(index), `id="`+id+`"`) {
			t.Errorf("index.html lacks #%s", id)
		}
	}
	for _, needle := range []string{`"lease.takeover_request"`, `"lease.takeover_cancelled"`, `"lease.takeover_reply"`, `$("takeover-text").textContent`} {
		if !strings.Contains(string(script), needle) {
			t.Errorf("app.js lacks %s", needle)
		}
	}
}

// The expose form's mode follows the phone's image.defaultMode.
func TestExposeUsesDefaultMode(t *testing.T) {
	script, _ := fs.ReadFile(FS, "app.js")
	if !strings.Contains(string(script), "image.defaultMode ||") {
		t.Error("app.js does not use image.defaultMode for the expose mode")
	}
}

// Message sizes are checked in UTF-8 bytes, not UTF-16 code units.
func TestMessageSizeCountsUTF8(t *testing.T) {
	script, _ := fs.ReadFile(FS, "app.js")
	if !strings.Contains(string(script), "new TextEncoder()") || !strings.Contains(string(script), "MESSAGE_BYTES_MAX = 64 * 1024") {
		t.Error("app.js does not measure messages in UTF-8 bytes against the 64 KiB limit")
	}
}

// F13–F24 (harmless keys the L4 web check presses) sit in the "More keys"
// overflow, and its buttons are wired like the F1–F12 row.
func TestMoreKeysOverflow(t *testing.T) {
	index, _ := fs.ReadFile(FS, "index.html")
	script, _ := fs.ReadFile(FS, "app.js")
	for _, needle := range []string{`<details id="more-keys"`, `id="fkeys-extra"`} {
		if !strings.Contains(string(index), needle) {
			t.Errorf("index.html lacks %s", needle)
		}
	}
	for _, needle := range []string{"FUNCTION_KEY_COUNT_MAX = 24", `$("fkeys-extra")]`} {
		if !strings.Contains(string(script), needle) {
			t.Errorf("app.js lacks %s", needle)
		}
	}
}

// paste.prepare's `unsupported` is [{index, codePoint}]: the plan text must
// decode code points (joining the objects printed "[object Object]").
func TestPastePlanDecodesUnsupported(t *testing.T) {
	script, _ := fs.ReadFile(FS, "app.js")
	if strings.Contains(string(script), `plan.unsupported.join(`) {
		t.Error("app.js joins paste.prepare's unsupported objects as strings")
	}
	for _, needle := range []string{"entry.codePoint", "plan.unsupportedCount"} {
		if !strings.Contains(string(script), needle) {
			t.Errorf("app.js lacks %s", needle)
		}
	}
}

// After a revoke or deny the page must not reconnect: each reconnect would
// show up on the phone as a new approval request.
func TestNoReconnectAfterRevoke(t *testing.T) {
	script := string(must(fs.ReadFile(FS, "app.js")))
	if !strings.Contains(script, `CLOSE_REASONS_FINAL = new Set(["revoked", "expired", "denied", "unauthorized"])`) {
		t.Error("app.js lacks the final close reasons")
	}
	start := strings.Index(script, "function onClosed(")
	if start < 0 {
		t.Fatal("app.js lacks onClosed")
	}
	closed := script[start:]
	closed = closed[:strings.Index(closed, "\n}\n")]
	final := strings.Index(closed, "CLOSE_REASONS_FINAL.has(event.reason)")
	if final < 0 {
		t.Fatal("onClosed doesn't check the final close reasons")
	}
	rest := closed[final:]
	if returnAt, reconnectAt := strings.Index(rest, "return;"), strings.Index(rest, "scheduleReconnect()"); returnAt < 0 || returnAt > reconnectAt {
		t.Error("onClosed reconnects after a final close reason")
	}
}

// A plain eject the computer refuses (device_attention: macOS holds the
// medium even after an unmount) must leave the user a forced eject, and a
// slot only offers the action that applies to its status.
func TestEjectOffersForceAndMatchesStatus(t *testing.T) {
	script := string(must(fs.ReadFile(FS, "app.js")))
	for _, needle := range []string{`error.message.startsWith("device_attention")`, `button("Force eject"`, `request("slot.eject", { lun, force })`, `slot.status !== "exported"`} {
		if !strings.Contains(script, needle) {
			t.Errorf("app.js lacks %s", needle)
		}
	}
}

func must[T any](value T, err error) T {
	if err != nil {
		panic(err)
	}
	return value
}
