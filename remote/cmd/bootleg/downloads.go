package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/JacobHayes/bootleg/remote/protocol"
)

const (
	// followProgressInterval is how often --follow prints progress.
	followProgressInterval = time.Second
	// downloadFollowTimeout bounds `download --follow` without --timeout:
	// a few GB on a slow link.
	downloadFollowTimeout = 2 * time.Hour
	// catalogArches is every architecture `download` looks an entry up in.
	catalogArchCount = 2
)

// followUnseenTimeout: a started download that never shows up in the
// downloads pushes for this long was cancelled (or never queued). A
// variable so tests can shorten it.
var followUnseenTimeout = 10 * time.Second

var catalogArches = [catalogArchCount]string{protocol.ArchX86_64, protocol.ArchArm64}

// wireArch accepts the usual spellings of the catalog's architectures.
func wireArch(arch string) (string, error) {
	switch strings.ToLower(arch) {
	case "x86_64", "amd64", "x64":
		return protocol.ArchX86_64, nil
	case "arm64", "aarch64":
		return protocol.ArchArm64, nil
	}
	return "", fmt.Errorf("--arch must be x86_64 or arm64, not %q", arch)
}

func wireCategory(category string) (string, error) {
	switch strings.ToLower(category) {
	case protocol.CategoryDesktop, protocol.CategoryServer, protocol.CategoryRescue:
		return strings.ToLower(category), nil
	}
	return "", fmt.Errorf("--category must be desktop, server or rescue, not %q", category)
}

// listCatalog asks for the catalog; while the phone is refreshing an empty
// one it waits for the refresh to end (catalog push) and asks again.
func listCatalog(ctx context.Context, c *client, arch, category string) (protocol.Catalog, json.RawMessage, error) {
	fields := map[string]any{"arch": arch}
	if category != "" {
		fields["category"] = category
	}
	for {
		response, err := c.request(ctx, protocol.TypeCatalogList, fields)
		if err != nil {
			return protocol.Catalog{}, nil, err
		}
		var catalog protocol.Catalog
		if err := json.Unmarshal(response.Raw, &catalog); err != nil {
			return protocol.Catalog{}, nil, &deviceError{fmt.Errorf("unexpected catalog: %w", err)}
		}
		if !catalog.Refreshing || catalogEntryCount(catalog) > 0 {
			return catalog, response.Raw, nil
		}
		if err := c.waitFor(ctx, func() bool {
			rev, refreshing := c.catalogState()
			return rev != catalog.CatalogRev && !refreshing
		}); err != nil {
			return protocol.Catalog{}, nil, err
		}
	}
}

func catalogEntryCount(catalog protocol.Catalog) int {
	count := 0
	for _, distro := range catalog.Distros {
		count += len(distro.Entries)
	}
	return count
}

func catalogCommand(ctx context.Context, call *invocation, c *client) error {
	catalog, raw, err := listCatalog(ctx, c, call.arch, call.category)
	if err != nil {
		return err
	}
	if call.json {
		var reply map[string]json.RawMessage
		json.Unmarshal(raw, &reply)
		delete(reply, "id")
		delete(reply, "ok")
		encoded, _ := json.MarshalIndent(reply, "", "  ")
		fmt.Fprintln(call.stdout, string(encoded))
		return nil
	}
	for _, distro := range catalog.Distros {
		if distro.Error != "" {
			fmt.Fprintf(call.stderr, "%s: %s (showing the last good list)\n", distro.Name, distro.Error)
		}
		for _, entry := range distro.Entries {
			size := "?"
			if entry.SizeBytes > 0 {
				size = humanBytes(entry.SizeBytes)
			}
			title := strings.TrimSpace(strings.Join([]string{distro.Name, entry.Version, entry.Variant}, " "))
			fmt.Fprintf(call.stdout, "%s\t%s\t%s\t%s\t%s\n", entry.EntryID, entry.State, size, title, entry.FileName)
		}
	}
	if catalog.Truncated {
		fmt.Fprintln(call.stderr, "(more entries than fit one reply; narrow it with --category)")
	}
	return nil
}

// findEntry resolves an entryId or an exact file name in the catalog
// (every architecture).
func findEntry(ctx context.Context, c *client, query string) (protocol.CatalogEntry, error) {
	var matches []protocol.CatalogEntry
	for _, arch := range catalogArches {
		catalog, _, err := listCatalog(ctx, c, arch, "")
		if err != nil {
			return protocol.CatalogEntry{}, err
		}
		for _, distro := range catalog.Distros {
			for _, entry := range distro.Entries {
				if entry.EntryID == query || strings.EqualFold(entry.FileName, query) {
					matches = append(matches, entry)
				}
			}
		}
	}
	switch len(matches) {
	case 1:
		return matches[0], nil
	case 0:
		return protocol.CatalogEntry{}, &protocolError{protocol.ErrorBody{Code: protocol.ErrorNotFound, Message: fmt.Sprintf("no catalog entry %q (see `bootleg catalog`)", query)}}
	}
	return protocol.CatalogEntry{}, usageErrorf("%d catalog entries match %q; use the entryId", len(matches), query)
}

// awaitDownloads waits for the downloads push the phone sends after
// welcome.
func awaitDownloads(ctx context.Context, c *client) ([]protocol.Download, error) {
	if err := c.waitFor(ctx, func() bool { _, ok := c.currentDownloads(); return ok }); err != nil {
		return nil, err
	}
	downloads, _ := c.currentDownloads()
	return downloads, nil
}

func downloadsCommand(ctx context.Context, call *invocation, c *client) error {
	downloads, err := awaitDownloads(ctx, c)
	if err != nil {
		return err
	}
	if call.json {
		encoded, _ := json.MarshalIndent(map[string]any{"downloads": downloads, "truncated": c.currentDownloadsTruncated()}, "", "  ")
		fmt.Fprintln(call.stdout, string(encoded))
		return nil
	}
	for _, download := range downloads {
		fmt.Fprintf(call.stdout, "%s\t%s\t%s\t%s\n", download.EntryID, describeDownload(download), download.Title, download.FileName)
	}
	if c.currentDownloadsTruncated() {
		fmt.Fprintln(call.stderr, "(more downloads than fit one message: some finished or paused ones are not listed)")
	}
	return nil
}

func describeDownload(download protocol.Download) string {
	switch download.Status {
	case protocol.DownloadRunning:
		if download.TotalBytes > 0 {
			return fmt.Sprintf("running %d%% (%s of %s)", download.DownloadedBytes*100/download.TotalBytes, humanBytes(download.DownloadedBytes), humanBytes(download.TotalBytes))
		}
		return fmt.Sprintf("running (%s)", humanBytes(download.DownloadedBytes))
	case protocol.DownloadCompleted:
		if download.Verified {
			return "completed, SHA-256 verified"
		}
		return "completed (no published checksum)"
	case protocol.DownloadFailed, protocol.DownloadPaused, protocol.DownloadQueued:
		if download.Error != "" {
			return download.Status + ": " + download.Error
		}
	}
	return download.Status
}

// downloadCommand starts (or with --cancel, cancels) a catalog download.
// Starting needs the lease only for the request; --follow then watches
// without it.
func downloadCommand(ctx context.Context, call *invocation, c *client) error {
	if call.cancel {
		return cancelDownloadCommand(ctx, call, c)
	}
	entry, err := findEntry(ctx, c, call.args[0])
	if err != nil {
		return err
	}
	var started protocol.DownloadStart
	if err := withLease(ctx, call, c, func() error {
		response, err := c.mutate(ctx, protocol.TypeDownloadStart, map[string]any{"entryId": entry.EntryID})
		if err != nil {
			return err
		}
		return json.Unmarshal(response.Raw, &started)
	}); err != nil {
		return err
	}
	switch started.Result {
	case protocol.StartDownloaded:
		fmt.Fprintf(call.stdout, "%s is already in the library\n", started.FileName)
		return nil
	case protocol.StartActive:
		fmt.Fprintf(call.stdout, "%s is already downloading\n", started.FileName)
	default:
		fmt.Fprintf(call.stdout, "downloading %s\n", started.FileName)
	}
	if !call.follow {
		return nil
	}
	return followDownload(ctx, call, c, entry.EntryID)
}

// followDownload prints progress until the download finishes: completed
// is success, anything that stops it (failed, paused, cancelled) is an
// error.
func followDownload(ctx context.Context, call *invocation, c *client, entryID string) error {
	var last string
	var lastPrintedAt time.Time
	seen := false
	unseenDeadline := time.Now().Add(followUnseenTimeout)
	for {
		downloads, err := awaitDownloads(ctx, c)
		if err != nil {
			return err
		}
		var current *protocol.Download
		for index := range downloads {
			if downloads[index].EntryID == entryID {
				current = &downloads[index]
			}
		}
		switch {
		case current == nil && seen:
			return &deviceError{errors.New("the download was cancelled")}
		case current != nil:
			seen = true
			description := describeDownload(*current)
			if description != last && time.Since(lastPrintedAt) >= followProgressInterval {
				fmt.Fprintln(call.stderr, description)
				last, lastPrintedAt = description, time.Now()
			}
			switch current.Status {
			case protocol.DownloadCompleted:
				fmt.Fprintf(call.stdout, "%s: %s\n", current.FileName, describeDownload(*current))
				return nil
			case protocol.DownloadFailed:
				return &deviceError{fmt.Errorf("the download failed: %s", current.Error)}
			case protocol.DownloadPaused:
				return &deviceError{errors.New("the download was paused on the phone")}
			}
		}
		if seen {
			if err := c.waitChange(ctx); err != nil {
				return err
			}
			continue
		}
		waitCtx, cancel := context.WithDeadline(ctx, unseenDeadline)
		err = c.waitChange(waitCtx)
		cancel()
		if err != nil && ctx.Err() == nil && errors.Is(err, context.DeadlineExceeded) {
			return &deviceError{errors.New("the download never showed up on the phone (cancelled?)")}
		}
		if err != nil {
			return err
		}
	}
}

func cancelDownloadCommand(ctx context.Context, call *invocation, c *client) error {
	query := call.args[0]
	entryID := ""
	downloads, err := awaitDownloads(ctx, c)
	if err != nil {
		return err
	}
	for _, download := range downloads {
		if download.EntryID == query || strings.EqualFold(download.FileName, query) {
			entryID = download.EntryID
		}
	}
	if entryID == "" {
		entry, err := findEntry(ctx, c, query)
		if err != nil {
			return err
		}
		entryID = entry.EntryID
	}
	var result struct {
		Cancelled bool `json:"cancelled"`
	}
	if err := withLease(ctx, call, c, func() error {
		response, err := c.mutate(ctx, protocol.TypeDownloadCancel, map[string]any{"entryId": entryID})
		if err != nil {
			return err
		}
		return json.Unmarshal(response.Raw, &result)
	}); err != nil {
		return err
	}
	if result.Cancelled {
		fmt.Fprintln(call.stdout, "cancelled")
	} else {
		fmt.Fprintln(call.stdout, "nothing to cancel")
	}
	return nil
}
