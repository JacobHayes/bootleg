// Command bootleg controls a Bootleg phone remotely: disks, HID and boot
// keys. It talks plain HTTP/WebSocket to the phone over this computer's own
// Tailscale (identified by WhoIs, approved once in the app), or over the
// phone's offline hotspot with a paired token (--host and --token-file).
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

const (
	defaultHost          = "bootleg"
	defaultTimeout       = 15 * time.Second
	bootkeyTimeout       = 3 * time.Minute
	pasteTimeoutMargin   = 15 * time.Second
	tokenFileBytesMax    = 1 << 10
	tokenFilePermissions = 0o600
	keyRepeatCountMax    = 100
	keyRepeatIntervalMin = 10 * time.Millisecond
	keyRepeatIntervalMax = 10 * time.Second
	defaultKeyInterval   = 100 * time.Millisecond
	cleanupTimeout       = 2 * time.Second
)

const usageText = `usage: bootleg [flags] <command> [args]

Commands:
  status                         show approval, lease, HID and disks
  images                         list images on the phone
  mount <image> [--lun N] [--mode cdrom|ro|rw]
                                 expose an image (by imageId or exact name) as
                                 the next LUN; --lun N replaces existing LUN N
  eject <lun> [--force]          eject a disk
  reinsert <lun>                 re-insert an ejected disk
  hid on|off [--keyboard] [--mouse]
                                 turn the USB keyboard/mouse on or off
  type <text> | type -           type text (from stdin with -), up to 64 KiB
  key <chord> [--repeat N] [--interval 100ms]
                                 tap a chord such as ctrl+alt+delete, cmd+space, f12
  bootkey <vendor> [menu|setup]  mash a firmware boot key while the target powers on
  lease take|takeover|release    take the controller lease and hold it until Ctrl-C
  pair <code>                    pair over the offline hotspot (needs --host)
  kbd                            interactive keyboard; Ctrl-] then ? for help

When another session asks for control (its --takeover), kbd and lease
take/takeover print "<name> asks for control. Allow? [y/N]" on stderr: in kbd
the next y or n keystroke answers (and is not typed); lease reads a line from
stdin. No answer within the phone's timeout (30 s), or end of stdin, declines.
All other commands decline takeover requests at once.

Flags:
  --host H        phone address (default $BOOTLEG_HOST or bootleg)
  --token-file F  hotspot token file, 0600 (default $BOOTLEG_TOKEN_FILE)
  --timeout D     overall deadline for the command
  --takeover      ask the current lease holder to hand over control
  --wait          wait for approval on the phone instead of failing
  --json          machine-readable output (status, images)

Exit codes: 0 ok, 2 usage, 3 auth or pending, 4 lease, 5 device, 6 timeout.
`

type options struct {
	host      string
	tokenFile string
	timeout   time.Duration
	takeover  bool
	wait      bool
	json      bool
	lun       int
	mode      string
	force     bool
	repeat    int
	interval  time.Duration
	keyboard  bool
	mouse     bool
}

type invocation struct {
	options
	command string
	args    []string
	// text is what `type` types (its argument or stdin), read before
	// connecting.
	text string
	// interrupt is cancelled by Ctrl-C/SIGTERM/SIGHUP only (no deadline).
	interrupt context.Context
	stdin     io.Reader
	stdout    io.Writer
	stderr    io.Writer
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM, syscall.SIGHUP)
	code := run(ctx, os.Args[1:], os.Stdin, os.Stdout, os.Stderr)
	stop()
	os.Exit(code)
}

func run(ctx context.Context, args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	parsed, err := parseArgs(args)
	if err != nil {
		if errors.Is(err, flag.ErrHelp) {
			fmt.Fprint(stdout, usageText)
			return exitOK
		}
		fmt.Fprintf(stderr, "bootleg: %v\n\n%s", err, usageText)
		return exitUsage
	}
	parsed.stdin, parsed.stdout, parsed.stderr = stdin, stdout, &syncWriter{writer: stderr}
	if err := dispatch(ctx, parsed); err != nil {
		fmt.Fprintf(stderr, "bootleg: %v\n", err)
		return exitCodeFor(err)
	}
	return exitOK
}

func newFlagSet(target *options) *flag.FlagSet {
	set := flag.NewFlagSet("bootleg", flag.ContinueOnError)
	set.SetOutput(io.Discard)
	set.StringVar(&target.host, "host", target.host, "")
	set.StringVar(&target.tokenFile, "token-file", target.tokenFile, "")
	set.DurationVar(&target.timeout, "timeout", 0, "")
	set.BoolVar(&target.takeover, "takeover", false, "")
	set.BoolVar(&target.wait, "wait", false, "")
	set.BoolVar(&target.json, "json", false, "")
	set.IntVar(&target.lun, "lun", -1, "")
	set.StringVar(&target.mode, "mode", "", "")
	set.BoolVar(&target.force, "force", false, "")
	set.IntVar(&target.repeat, "repeat", 1, "")
	set.DurationVar(&target.interval, "interval", defaultKeyInterval, "")
	set.BoolVar(&target.keyboard, "keyboard", false, "")
	set.BoolVar(&target.mouse, "mouse", false, "")
	return set
}

// parseArgs accepts flags anywhere (before or after the command); "--" ends
// flags, and a lone "-" is a positional (type -).
func parseArgs(args []string) (*invocation, error) {
	parsed := &invocation{options: options{host: os.Getenv("BOOTLEG_HOST"), tokenFile: os.Getenv("BOOTLEG_TOKEN_FILE")}}
	if parsed.host == "" {
		parsed.host = defaultHost
	}
	set := newFlagSet(&parsed.options)
	var flagArgs, positionals []string
	for index := 0; index < len(args); index++ {
		arg := args[index]
		if arg == "--" {
			positionals = append(positionals, args[index+1:]...)
			break
		}
		if arg == "-h" || arg == "--help" || arg == "-help" {
			return nil, flag.ErrHelp
		}
		if !strings.HasPrefix(arg, "-") || arg == "-" {
			positionals = append(positionals, arg)
			continue
		}
		flagArgs = append(flagArgs, arg)
		name := strings.TrimLeft(arg, "-")
		if strings.Contains(name, "=") {
			continue
		}
		defined := set.Lookup(name)
		if defined == nil {
			return nil, fmt.Errorf("unknown flag %s", arg)
		}
		if boolean, ok := defined.Value.(interface{ IsBoolFlag() bool }); ok && boolean.IsBoolFlag() {
			continue
		}
		if index+1 >= len(args) {
			return nil, fmt.Errorf("flag %s needs a value", arg)
		}
		index++
		flagArgs = append(flagArgs, args[index])
	}
	if err := set.Parse(flagArgs); err != nil {
		return nil, err
	}
	if len(positionals) == 0 {
		return nil, errors.New("missing command")
	}
	parsed.command, parsed.args = positionals[0], positionals[1:]
	return parsed, validate(parsed)
}

var commandArgCounts = map[string][2]int{ // min, max positional args
	"status": {0, 0}, "images": {0, 0}, "mount": {1, 1}, "eject": {1, 1}, "reinsert": {1, 1},
	"hid": {1, 1}, "type": {1, 1}, "key": {1, 1}, "bootkey": {1, 2}, "lease": {1, 1}, "pair": {1, 1}, "kbd": {0, 0},
}

func validate(parsed *invocation) error {
	counts, ok := commandArgCounts[parsed.command]
	if !ok {
		return fmt.Errorf("unknown command %q", parsed.command)
	}
	if len(parsed.args) < counts[0] || len(parsed.args) > counts[1] {
		return fmt.Errorf("%s takes %d–%d arguments, got %d", parsed.command, counts[0], counts[1], len(parsed.args))
	}
	if parsed.timeout < 0 {
		return errors.New("--timeout must be positive")
	}
	if parsed.repeat < 1 || parsed.repeat > keyRepeatCountMax {
		return fmt.Errorf("--repeat must be 1–%d", keyRepeatCountMax)
	}
	if parsed.interval < keyRepeatIntervalMin || parsed.interval > keyRepeatIntervalMax {
		return fmt.Errorf("--interval must be %v–%v", keyRepeatIntervalMin, keyRepeatIntervalMax)
	}
	if parsed.lun < -1 || parsed.lun > 7 {
		return errors.New("--lun must be 0–7")
	}
	if parsed.mode != "" {
		if _, err := wireMode(parsed.mode); err != nil {
			return err
		}
	}
	switch parsed.command {
	case "hid":
		if parsed.args[0] != "on" && parsed.args[0] != "off" {
			return errors.New("hid takes on or off")
		}
	case "lease":
		if parsed.args[0] != "take" && parsed.args[0] != "takeover" && parsed.args[0] != "release" {
			return errors.New("lease takes take, takeover or release")
		}
	case "key":
		if _, err := parseChord(parsed.args[0]); err != nil {
			return err
		}
	case "pair":
		if !validPairCode(parsed.args[0]) {
			return errors.New("the pairing code is 6 digits")
		}
		if parsed.host == defaultHost {
			return errors.New("pair needs --host with the hotspot address shown on the phone (for example 192.168.49.1:8080)")
		}
	case "bootkey":
		if len(parsed.args) == 2 && parsed.args[1] != "menu" && parsed.args[1] != "setup" {
			return errors.New("bootkey action is menu or setup")
		}
	}
	return nil
}

func validPairCode(code string) bool {
	if len(code) != 6 {
		return false
	}
	for _, character := range code {
		if character < '0' || character > '9' {
			return false
		}
	}
	return true
}

func wireMode(mode string) (string, error) {
	switch strings.ToLower(mode) {
	case "cdrom", "cd":
		return "cdrom", nil
	case "ro", "disk_ro":
		return "disk_ro", nil
	case "rw", "disk_rw":
		return "disk_rw", nil
	}
	return "", fmt.Errorf("--mode must be cdrom, ro or rw, not %q", mode)
}

// defaultTokenFile is where pair stores the token when --token-file isn't
// given.
func defaultTokenFile() (string, error) {
	directory, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(directory, "bootleg", "hotspot-token"), nil
}

// readToken loads a token file, refusing one other users could read.
func readToken(path string) (string, error) {
	info, err := os.Stat(path)
	if err != nil {
		return "", usageErrorf("token file: %v", err)
	}
	if info.Mode().Perm()&0o077 != 0 {
		return "", usageErrorf("token file %s is readable by others (mode %o); run chmod 600 %s", path, info.Mode().Perm(), path)
	}
	if info.Size() > tokenFileBytesMax {
		return "", usageErrorf("token file %s is too large", path)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", usageErrorf("token file: %v", err)
	}
	token := strings.TrimSpace(string(data))
	if token == "" {
		return "", usageErrorf("token file %s is empty", path)
	}
	return token, nil
}

// writeToken stores a token with 0600 permissions, replacing any old one
// atomically.
func writeToken(path, token string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".token-*")
	if err != nil {
		return err
	}
	defer os.Remove(temporary.Name())
	if err := temporary.Chmod(tokenFilePermissions); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.WriteString(token + "\n"); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporary.Name(), path)
}
