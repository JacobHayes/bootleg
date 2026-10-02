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
