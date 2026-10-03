package protocol

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestCatalogDownloadMessagesParse(t *testing.T) {
	for _, message := range []string{
		`{"id":1,"t":"catalog.list","arch":"arm64","category":"rescue"}`,
		`{"id":2,"t":"download.start","epoch":3,"entryId":"0123456789abcdef"}`,
		`{"id":"c","t":"download.cancel","epoch":3,"entryId":"0123456789abcdef"}`,
		`{"t":"downloads","downloads":[],"truncated":false}`,
		`{"t":"catalog","catalogRev":4,"refreshing":true}`,
	} {
		if _, err := ParseEnvelope([]byte(message)); err != nil {
			t.Errorf("%s: %v", message, err)
		}
	}
	// The shapes Kotlin sends (core RemoteController) decode into the CLI's types.
	reply := `{"id":1,"ok":true,"catalogRev":2,"refreshing":false,"truncated":true,"distros":[{"distroId":"netboot","name":"netboot.xyz",` +
		`"category":"rescue","fetchedAtMs":5,"entries":[{"entryId":"0123456789abcdef","version":"2.0","arch":"x86_64",` +
		`"fileName":"netboot.xyz.iso","sizeBytes":9,"state":"verifying"}]}]}`
	var catalog Catalog
	if err := json.Unmarshal([]byte(reply), &catalog); err != nil || !catalog.Truncated ||
		catalog.Distros[0].Entries[0].State != DownloadVerifying || len(catalog.Distros[0].Entries[0].EntryID) != EntryIDLength {
		t.Fatalf("catalog %+v, %v", catalog, err)
	}
	push := `{"t":"downloads","downloads":[{"entryId":"0123456789abcdef","fileName":"a.iso","title":"A","status":"completed",` +
		`"downloadedBytes":9,"totalBytes":9,"verified":true}],"truncated":false}`
	var downloads struct {
		Downloads []Download `json:"downloads"`
	}
	if err := json.Unmarshal([]byte(push), &downloads); err != nil || !downloads.Downloads[0].Verified ||
		downloads.Downloads[0].Status != DownloadCompleted {
		t.Fatalf("downloads %+v, %v", downloads, err)
	}
}

// Kotlin bounds catalog replies and downloads pushes to 48 KiB; Go's
// per-message limit must leave room for them.
func TestCatalogRepliesFitOneMessage(t *testing.T) {
	const kotlinReplyBytesMax = 48 << 10
	if kotlinReplyBytesMax >= MessageBytesMax {
		t.Fatalf("Kotlin's %d-byte replies don't fit Go's %d-byte messages", kotlinReplyBytesMax, MessageBytesMax)
	}
	big := `{"id":1,"ok":true,"distros":[{"name":"` + strings.Repeat("x", kotlinReplyBytesMax-64) + `"}]}`
	if _, err := ParseEnvelope([]byte(big)); err != nil && strings.Contains(err.Error(), "exceeds") {
		t.Fatalf("a 48 KiB reply is over the message limit: %v", err)
	}
}
