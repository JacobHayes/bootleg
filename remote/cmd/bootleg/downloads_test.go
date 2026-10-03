package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/JacobHayes/bootleg/remote/protocol"
	"github.com/JacobHayes/bootleg/remote/transport"
)

func TestParseDownloadArgs(t *testing.T) {
	t.Setenv("BOOTLEG_HOST", "")
	parsed, err := parseArgs([]string{"catalog", "--arch", "aarch64", "--category", "Rescue", "--json"})
	if err != nil || parsed.arch != protocol.ArchArm64 || parsed.category != protocol.CategoryRescue || !parsed.json {
		t.Fatalf("catalog flags: %+v, %v", parsed, err)
	}
	parsed, err = parseArgs([]string{"download", "netboot.xyz.iso", "--follow"})
	if err != nil || !parsed.follow || parsed.arch != protocol.ArchX86_64 {
		t.Fatalf("download flags: %+v, %v", parsed, err)
	}
	for _, args := range [][]string{
		{"catalog", "--arch", "sparc"},
		{"catalog", "--category", "games"},
		{"catalog", "extra"},
		{"download"},
		{"download", "x", "--follow", "--cancel"},
		{"status", "--follow"},
		{"downloads", "x"},
	} {
		if _, err := parseArgs(args); err == nil {
			t.Errorf("%q accepted", args)
		}
	}
}

func TestCLICatalogListsEntriesWithoutTheLease(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	code, stdout, stderr := phone.run(t, "", "catalog")
	if code != exitOK || !strings.Contains(stdout, "aaaaaaaaaaaaaaaa\tavailable\t2.0 MiB\tnetboot.xyz 2.0\tnetboot.xyz.iso") ||
		strings.Contains(stdout, "arm64.iso") || !strings.Contains(stdout, "debian.iso") {
		t.Fatalf("catalog %d\n%s\n%s", code, stdout, stderr)
	}
	code, stdout, _ = phone.run(t, "", "catalog", "--arch", "arm64", "--category", "rescue", "--json")
	var catalog protocol.Catalog
	if code != exitOK || json.Unmarshal([]byte(stdout), &catalog) != nil || catalogEntryCount(catalog) != 1 || strings.Contains(stdout, `"id"`) {
		t.Fatalf("catalog --json %d %s", code, stdout)
	}
	phone.phone.read(func(p *fakePhone) {
		for _, request := range p.requests {
			if request["t"] == protocol.TypeLeaseTake {
				t.Error("listing the catalog took the lease")
			}
		}
	})
}

func TestCLIDownloadStartsByNameOrIdAndReleasesTheLease(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	code, stdout, stderr := phone.run(t, "", "download", "NETBOOT.XYZ.ISO")
	if code != exitOK || !strings.Contains(stdout, "downloading netboot.xyz.iso") {
		t.Fatalf("download %d\n%s\n%s", code, stdout, stderr)
	}
	code, stdout, _ = phone.run(t, "", "download", "aaaaaaaaaaaaaaaa")
	if code != exitOK || !strings.Contains(stdout, "already downloading") {
		t.Fatalf("repeat %d %s", code, stdout)
	}
	code, stdout, _ = phone.run(t, "", "download", "debian.iso")
	if code != exitOK || !strings.Contains(stdout, "already in the library") {
		t.Fatalf("downloaded %d %s", code, stdout)
	}
	if code, _, _ := phone.run(t, "", "download", "nope.iso"); code != exitDevice {
		t.Fatalf("unknown entry exit %d", code)
	}
	code, stdout, _ = phone.run(t, "", "downloads")
	if code != exitOK || !strings.Contains(stdout, "aaaaaaaaaaaaaaaa\tqueued") {
		t.Fatalf("downloads %d %s", code, stdout)
	}
	code, stdout, _ = phone.run(t, "", "downloads", "--json")
	var listed struct {
		Downloads []protocol.Download `json:"downloads"`
		Truncated *bool               `json:"truncated"`
	}
	if code != exitOK || json.Unmarshal([]byte(stdout), &listed) != nil || len(listed.Downloads) != 1 || listed.Truncated == nil || *listed.Truncated {
		t.Fatalf("downloads --json %d %s", code, stdout)
	}
	phone.phone.read(func(p *fakePhone) {
		if p.holder != nil {
			t.Error("download must release the lease it took")
		}
		for _, request := range p.requests {
			if request["t"] == protocol.TypeDownloadStart {
				if _, ok := request["epoch"]; !ok {
					t.Error("download.start without an epoch")
				}
				if _, ok := request["url"]; ok {
					t.Error("the client sent a URL")
				}
			}
		}
	})
}

func TestCLIDownloadFollowEndsOnTheOutcome(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	cases := []struct {
		entryID  string
		finish   func(*protocol.Download)
		remove   bool
		wantCode int
		wantOut  string
	}{
		{"aaaaaaaaaaaaaaaa", func(d *protocol.Download) {
			d.Status, d.Verified, d.DownloadedBytes = protocol.DownloadCompleted, true, d.TotalBytes
		}, false, exitOK, ""},
		{"cccccccccccccccc", func(d *protocol.Download) { d.Status, d.Error = protocol.DownloadFailed, "Checksum mismatch" }, false, exitDevice, "Checksum mismatch"},
		{"bbbbbbbbbbbbbbbb", nil, true, exitDevice, "cancelled"},
	}
	for _, test := range cases {
		done, output := phone.start(t, strings.NewReader(""), "download", test.entryID, "--follow")
		waitUntil(t, "the download starts", func() bool {
			found := false
			phone.phone.read(func(p *fakePhone) {
				for _, download := range p.downloads {
					found = found || download.EntryID == test.entryID
				}
			})
			return found
		})
		time.Sleep(50 * time.Millisecond)
		phone.phone.setDownload(test.entryID, func(d *protocol.Download) { d.Status, d.DownloadedBytes = protocol.DownloadRunning, 1<<20 }, false)
		phone.phone.setDownload(test.entryID, test.finish, test.remove)
		if code := waitExit(t, done); code != test.wantCode || !strings.Contains(output.String(), test.wantOut) {
			t.Errorf("%s: exit %d, want %d with %q:\n%s", test.entryID, code, test.wantCode, test.wantOut, output.String())
		}
	}
}

func TestCLIDownloadFollowGivesUpOnADownloadThatNeverShows(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	saved := followUnseenTimeout
	followUnseenTimeout = 300 * time.Millisecond
	t.Cleanup(func() { followUnseenTimeout = saved })
	phone.phone.read(func(p *fakePhone) { p.forgetStarts = true })
	done, output := phone.start(t, strings.NewReader(""), "download", "netboot.xyz.iso", "--follow")
	if code := waitExit(t, done); code != exitDevice || !strings.Contains(output.String(), "never showed up") {
		t.Fatalf("exit %d\n%s", code, output.String())
	}
}

func TestCLIDownloadCancel(t *testing.T) {
	phone := startPhone(t, transport.TransportHotspot)
	if code, _, _ := phone.run(t, "", "download", "gparted.iso"); code != exitOK {
		t.Fatal("start failed")
	}
	code, stdout, _ := phone.run(t, "", "download", "--cancel", "gparted.iso")
	if code != exitOK || !strings.Contains(stdout, "cancelled") {
		t.Fatalf("cancel %d %s", code, stdout)
	}
	code, stdout, _ = phone.run(t, "", "download", "--cancel", "cccccccccccccccc")
	if code != exitOK || !strings.Contains(stdout, "nothing to cancel") {
		t.Fatalf("second cancel %d %s", code, stdout)
	}
	phone.phone.read(func(p *fakePhone) {
		if len(p.downloads) != 0 || p.holder != nil {
			t.Errorf("downloads %+v holder %v", p.downloads, p.holder)
		}
	})
}
