#!/bin/sh
# L4 HID check (< 45s): enable Bootleg's keyboard + mouse, confirm the Mac enumerates them, send
# only harmless input (F24 tap, Shift tap, a mouse move that cancels out, and one scroll detent up
# and back with the Mac's pointer parked on the menu bar), check the Mac parsed the wheel mouse,
# then turn HID off and confirm the keyboard and mouse stay attached and idle at the front of the
# configuration (Bootleg's fixed prefix) while ADB and foreign links are untouched.
#
# Needs the debug app installed (`mise run deploy`) and the Mac as the USB host. Uses wireless adb
# because enabling HID re-enumerates USB. Takes the device lock unless BOOTLEG_DEVICE_LOCK_HELD=1
# (set by a caller such as check-device.sh that already holds it).
#
# Host-side capture: scripts/hidcapture.swift (built into build/hidcapture) records what the Mac
# receives from the phone, and the check asserts that exactly the F24 tap, the Shift tap and the
# +10,+10 / -10,-10 mouse move arrived. That needs macOS Input Monitoring for the terminal; the
# helper only checks the permission (it never prompts), and without it the check prints SKIP and
# falls back to the enumeration and written-report checks.
#
# Scroll is only sent while the helper (--park-pointer) holds the pointer over the middle of the
# menu bar, where nothing reacts to it; the helper puts the pointer back at its recorded absolute
# position when it stops. If parking or the capture fails, the check fails without scrolling;
# without Input Monitoring the scroll is skipped.
#
# Once HID has been enabled, an EXIT/INT/TERM trap turns it off again whatever happens, so a
# failure can't leave a keyboard and mouse attached to the Mac.
#
# Usage: scripts/check-device-hid.sh   (env: BOOTLEG_WIFI_SERIAL, BOOTLEG_USB_SERIAL)
set -eu

WIRELESS=${BOOTLEG_WIFI_SERIAL:-$(scripts/wifi-serial.sh)}
USB=$(scripts/usb-serial.sh)
LOCK=/private/tmp/claude-501/bootleg-device.lock
LOCK_WAIT_S_MAX=120
ENUMERATE_WAIT_S_MAX=10
ADB_RETURN_WAIT_S_MAX=20
CAPTURE_SECONDS_MAX=20
# The parked capture must outlive HID_SEND's worst case (10s link wait + 2 x 2s drain), so the
# pointer can't be restored while the scroll is still on its way; it is stopped once confirmed.
PARKED_CAPTURE_SECONDS=60
CAPTURE_READY_WAIT_S_MAX=10
# Wireless adb can hang on a dead transport: every call is bounded.
ADB="timeout --foreground --kill-after=5 120 adb -s $WIRELESS"
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CAPTURE_SOURCE=$ROOT/scripts/hidcapture.swift
CAPTURE_BINARY=$ROOT/build/hidcapture
CAPTURE_OUTPUT=$(mktemp "${TMPDIR:-/tmp}/bootleg-hidcapture.XXXXXX")

fail() {
    echo "check-device-hid: FAIL: $*" >&2
    exit 1
}
step() { echo "check-device-hid: $*"; }

hid_enabled=0
lock_taken=0
capture_pid=

# Runs once on any exit: HID off if we turned it on, stop the capture, release the lock.
cleanup() {
    status=$?
    trap - EXIT
    # Cleanup is bounded (every adb call is); a second signal must not stop it before the lock is released.
    trap '' INT TERM HUP
    if [ -n "$capture_pid" ]; then
        kill "$capture_pid" 2>/dev/null || true
        wait "$capture_pid" 2>/dev/null || true
        # A parked pointer must have been put back; say so loudly if not.
        if grep -q '"parked":{"at":{' "$CAPTURE_OUTPUT" && ! grep -q '"restored":{"x"' "$CAPTURE_OUTPUT"; then
            echo "check-device-hid: WARN: the capture helper did not report restoring the Mac's pointer: $(cat "$CAPTURE_OUTPUT")" >&2
            [ "$status" != 0 ] || status=1
        fi
    fi
    # Debug calls are synchronous and the app serializes gadget changes, so
    # this HID_OFF runs only after any change an interrupted call left running on the phone.
    if [ "$hid_enabled" = 1 ]; then
        echo "check-device-hid: cleanup: turning HID off" >&2
        case "$(call HID_OFF 2>&1)" in
        *ApplyResult.Applied*) hid_enabled=0 ;;
        *) echo "check-device-hid: WARN: cleanup HID_OFF failed; turn keyboard + mouse off in the app" >&2 ;;
        esac
    fi
    rm -f "$CAPTURE_OUTPUT"
    if [ "$lock_taken" = 1 ]; then rmdir "$LOCK"; fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

if [ "${BOOTLEG_DEVICE_LOCK_HELD:-0}" != 1 ]; then
    waited=0
    until mkdir "$LOCK" 2>/dev/null; do
        [ "$waited" -lt "$LOCK_WAIT_S_MAX" ] || fail "device lock $LOCK still held after ${LOCK_WAIT_S_MAX}s"
        sleep 1
        waited=$((waited + 1))
    done
    lock_taken=1
fi

# Debug provider call; prints the JSON payload.
call() {
    method=$1
    shift
    $ADB shell content call --uri content://dev.jacobhayes.bootleg.debug --method "$method" "$@" |
        tr -d '\r' | sed -n 's/^Result: Bundle\[{json=\(.*\)}\]$/\1/p'
}

# The first three links of configs/b.1 in link order (STATE lists them in that order), comma-separated.
leading_links() {
    printf '%s\n' "$1" | grep -o '"configLinks":{[^}]*}' | grep -o '"[^"]*":"[^"]*"' |
        sed 's/^"\([^"]*\)":.*$/\1/' | head -3 | paste -sd, -
}
PREFIX_LINKS=ffs.bootleg_rndis,hid.bootleg_kbd,hid.bootleg_mouse_wheel

# Links in configs/b.1 that aren't ours, as sorted "target" lines (the HAL renumbers functionN).
foreign_links() {
    printf '%s\n' "$1" | grep -o '"configLinks":{[^}]*}' | grep -o '"[^"]*":"[^"]*"' |
        sed 's/^"[^"]*":"\([^"]*\)"$/\1/' | grep -v bootleg | sort
}

# Rows of `hidutil list` for the phone's HID interfaces with the given usage page and usage.
host_hid_rows() {
    hidutil list 2>/dev/null | awk -v vid="$VENDOR_ID" -v page="$1" -v usage="$2" \
        'tolower($1) == vid && $4 == page && $5 == usage && $7 == "USB"'
}

wait_for_host() {
    want=$1 # present | absent
    waited=0
    while :; do
        keyboard=$(host_hid_rows 1 6 | wc -l | tr -d ' ')
        mouse=$(host_hid_rows 1 2 | wc -l | tr -d ' ')
        if [ "$want" = present ] && [ "$keyboard" -ge 1 ] && [ "$mouse" -ge 1 ]; then return 0; fi
        if [ "$want" = absent ] && [ "$keyboard" -eq 0 ] && [ "$mouse" -eq 0 ]; then return 0; fi
        [ "$waited" -lt "$ENUMERATE_WAIT_S_MAX" ] || return 1
        sleep 1
        waited=$((waited + 1))
    done
}

# Builds the capture helper if needed. Returns 1 when it can't be built.
build_capture() {
    if [ ! -x "$CAPTURE_BINARY" ] || [ "$CAPTURE_SOURCE" -nt "$CAPTURE_BINARY" ]; then
        mkdir -p "$(dirname "$CAPTURE_BINARY")"
        xcrun swiftc -O -o "$CAPTURE_BINARY" "$CAPTURE_SOURCE" >&2 || return 1
    fi
}

# Starts the capture helper in the background (extra arguments are passed on, e.g.
# --park-pointer). Returns 1 (after printing SKIP) when host capture isn't possible, so the check
# falls back to the other assertions.
start_capture() {
    if ! build_capture; then
        step "SKIP host capture: could not build $CAPTURE_SOURCE (Xcode command line tools?)"
        return 1
    fi
    "$CAPTURE_BINARY" --vendor "$VENDOR_ID" --product "$PRODUCT_ID" --seconds "$CAPTURE_SECONDS_MAX" "$@" >"$CAPTURE_OUTPUT" &
    capture_pid=$!
    polls=0
    while :; do
        if grep -q '"status":"ready"' "$CAPTURE_OUTPUT"; then return 0; fi
        if ! kill -0 "$capture_pid" 2>/dev/null; then
            code=0
            wait "$capture_pid" || code=$?
            capture_pid=
            if [ "$code" -eq 3 ]; then
                step "SKIP host capture: $(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "$CAPTURE_OUTPUT")"
                return 1
            fi
            fail "host capture helper exited with $code: $(cat "$CAPTURE_OUTPUT")"
        fi
        [ "$polls" -lt "$((CAPTURE_READY_WAIT_S_MAX * 10))" ] || fail "host capture helper not ready within ${CAPTURE_READY_WAIT_S_MAX}s"
        sleep 0.1
        polls=$((polls + 1))
    done
}

# What the Mac must receive, as "page usage value" lines in report order: F24 (0x73) down/up and
# Left Shift (0xE1) down/up from the keyboard, then the mouse X/Y +10 and back -10. Keyboard-array
# bookkeeping values (usages below 4 or above 16 bits) and unchanged mouse buttons (page 9,
# value 0) are not input and are dropped.
EXPECTED_KEYBOARD_INPUT='7 115 1
7 115 0
7 225 1
7 225 0'
EXPECTED_MOUSE_INPUT='1 48 10
1 49 10
1 48 -10
1 49 -10'

EXPECTED_SCROLL_INPUT='1 56 1
1 56 -1'

# Captured values as "page usage value", ordered by report time and then usage (IOKit delivers
# the X and Y of one report in either order). macOS reports every element of each report, so a
# zero X (1/48), Y (1/49), Wheel (1/56) or AC Pan (12/568) is an unused field of a move or scroll
# report, not input.
captured_input() {
    sed -n 's/^{"page":\([0-9]*\),"ts":\([0-9]*\),"usage":\([0-9]*\),"value":\(-*[0-9]*\)}$/\2 \1 \3 \4/p' "$CAPTURE_OUTPUT" |
        sort -n -k1,1 -k3,3 | awk '{ print $2, $3, $4 }' |
        awk '!($1 == 7 && ($2 < 4 || $2 > 65535)) && !($1 == 9 && $3 == 0)' |
        awk '!($1 == 1 && ($2 == 48 || $2 == 49 || $2 == 56) && $3 == 0) && !($1 == 12 && $2 == 568 && $3 == 0)'
}

# Sends one harmless HID_SEND sequence and checks it was queued and written in full.
send_sequence() {
    if [ -n "${2:-}" ]; then sent=$(call HID_SEND --extra "sequence:s:$1" --extra "$2"); else sent=$(call HID_SEND --extra "sequence:s:$1"); fi
    queued=$(printf '%s' "$sent" | sed -n 's/.*"queued":\([0-9]*\).*/\1/p')
    written=$(printf '%s' "$sent" | sed -n 's/.*"written":\([0-9]*\).*/\1/p')
    dropped=$(printf '%s' "$sent" | sed -n 's/.*"dropped":\([0-9]*\).*/\1/p')
    [ "${queued:-0}" -eq 2 ] && [ "${written:-0}" -ge 2 ] && [ "${dropped:-1}" -eq 0 ] || fail "HID_SEND $1: $sent"
    step "sent $1: $queued reports queued, $written written, none dropped"
}

# The mouse as the Mac parsed it (enumeration only, no input): "size wheel" where wheel is 1 when
# the descriptor has Wheel (09 38) and AC Pan (0a 38 02), else 0. Empty while no mouse is listed.
host_mouse_layout() {
    "$CAPTURE_BINARY" --describe --vendor "$VENDOR_ID" --product "$PRODUCT_ID" 2>/dev/null |
        sed -n 's/^{"kind":"mouse","maxInputReportSize":\([0-9]*\),"reportDescriptor":"\([0-9a-f]*\)"}$/\1 \2/p' |
        awk '{ wheel = (index($2, "0938") > 0 && index($2, "0a3802") > 0) ? 1 : 0; print $1, wheel }'
}

# Waits until the Mac lists exactly one Bootleg mouse with the expected "size wheel".
expect_host_mouse() {
    want=$1
    waited=0
    while :; do
        seen=$(host_mouse_layout)
        [ "$seen" = "$want" ] && return 0
        [ "$waited" -lt "$ENUMERATE_WAIT_S_MAX" ] || fail "the Mac's mouse is [$seen] (MaxInputReportSize, wheel), expected [$want]"
        sleep 1
        waited=$((waited + 1))
    done
}

hid_on() {
    hid_enabled=1
    enabled=$(call HID_ENABLE --extra keyboard:b:true --extra mouse:b:true)
    case "$enabled" in *ApplyResult.Applied*) ;; *) fail "HID_ENABLE: $enabled" ;; esac
    wait_for_host present || fail "Mac did not list a usage 1/6 keyboard and 1/2 mouse from $VENDOR_ID within ${ENUMERATE_WAIT_S_MAX}s"
}

hid_off() {
    off=$(call HID_OFF)
    case "$off" in *ApplyResult.Applied*) hid_enabled=0 ;; *) fail "HID_OFF: $off" ;; esac
    # A hidg fd still open on a removed function is a use-after-free in this kernel (f_hid).
    stale=$($ADB shell su -c "'ls -l /proc/*/fd 2>/dev/null'" | tr -d '\r' | grep 'hidg.*(deleted)' || true)
    [ -z "$stale" ] || fail "a /dev/hidg fd is still open on a removed function after HID_OFF: $stale"
}

# One detent up and one down, only with the pointer parked on the menu bar and the capture running.
scroll_check() {
    if ! start_capture --park-pointer --seconds "$PARKED_CAPTURE_SECONDS"; then
        step "SKIP scroll: no host capture, so no scroll was sent"
        return 0
    fi
    grep -q '"parked":{"at":{' "$CAPTURE_OUTPUT" || fail "pointer not parked; no scroll was sent: $(cat "$CAPTURE_OUTPUT")"
    case "$(sed -n 's/.*"opened":\[\([^]]*\)\].*/\1/p' "$CAPTURE_OUTPUT")" in
    *'"mouse"'*) ;;
    *)
        kill "$capture_pid" 2>/dev/null || true
        wait "$capture_pid" 2>/dev/null || true
        capture_pid=
        step "SKIP scroll: another app has seized the mouse, so its input can't be confirmed; no scroll was sent"
        return 0
        ;;
    esac
    step "pointer parked at $(sed -n 's/.*"at":\({[^}]*}\).*/\1/p' "$CAPTURE_OUTPUT")"
    # The helper restores the pointer when it stops, so it must still be running (and parked).
    kill -0 "$capture_pid" 2>/dev/null && ! grep -q '"status":"done"' "$CAPTURE_OUTPUT" ||
        fail "the capture helper stopped before the scroll; no scroll was sent: $(cat "$CAPTURE_OUTPUT")"
    send_sequence scroll pointerParked:b:true
    sleep 0.5
    kill "$capture_pid" 2>/dev/null || true
    wait "$capture_pid" 2>/dev/null || true
    capture_pid=
    grep -q '"restored":{"x"' "$CAPTURE_OUTPUT" || fail "the pointer was not put back: $(cat "$CAPTURE_OUTPUT")"
    seen=$(captured_input)
    [ "$seen" = "$EXPECTED_SCROLL_INPUT" ] || fail "the Mac received different scroll input than was sent.
expected:
$EXPECTED_SCROLL_INPUT
seen (filtered):
$seen
raw:
$(cat "$CAPTURE_OUTPUT")"
    step "host capture: the Mac received exactly one wheel detent up and one down; pointer restored to $(sed -n 's/.*"restored":\({[^}]*}\).*/\1/p' "$CAPTURE_OUTPUT")"
}

# Which device kinds the helper could open (another app may have seized one, e.g. Karabiner-Elements
# seizes keyboards), and the expected input for those.
expected_input() {
    opened=$(sed -n 's/.*"opened":\[\([^]]*\)\].*/\1/p' "$CAPTURE_OUTPUT")
    case "$opened" in *'"keyboard"'*) printf '%s\n' "$EXPECTED_KEYBOARD_INPUT" ;; esac
    case "$opened" in *'"mouse"'*) printf '%s\n' "$EXPECTED_MOUSE_INPUT" ;; esac
}

VENDOR_ID=$($ADB shell su -c "cat /config/usb_gadget/g1/idVendor" | tr -d '\r' | tr 'A-F' 'a-f' | sed 's/^0x0*/0x/')
[ -n "$VENDOR_ID" ] || fail "cannot read the gadget's idVendor"
PRODUCT_ID=$($ADB shell su -c "cat /config/usb_gadget/g1/idProduct" | tr -d '\r' | tr 'A-F' 'a-f' | sed 's/^0x0*/0x/')
[ -n "$PRODUCT_ID" ] || fail "cannot read the gadget's idProduct"

build_capture || fail "could not build $CAPTURE_SOURCE (Xcode command line tools?), needed to read the Mac's mouse descriptor"

before=$(call STATE)
case "$before" in *'"udc":"'[!\"]*) ;; *) fail "gadget not bound before the test: $before" ;; esac
foreign_before=$(foreign_links "$before")
# Once Bootleg's prefix is set up, its idle keyboard and mouse stay enumerated; before that, any is another app's.
if [ "$(leading_links "$before")" != "$PREFIX_LINKS" ]; then
    wait_for_host absent || fail "a $VENDOR_ID keyboard/mouse is already enumerated; is another HID app active?"
fi

step "enabling keyboard + mouse, waiting for the Mac to enumerate them (vendor $VENDOR_ID)"
hid_on
host_hid_rows 1 6 | sed 's/^/  keyboard: /'
host_hid_rows 1 2 | sed 's/^/  mouse:    /'

capturing=0
captured_kinds=
if start_capture; then capturing=1; fi

for sequence in f24 shift mouse; do
    send_sequence "$sequence"
done

if [ "$capturing" = 1 ]; then
    # The provider returns once the reports are written; give the host a moment to deliver them.
    sleep 0.5
    kill "$capture_pid" 2>/dev/null || true
    wait "$capture_pid" 2>/dev/null || true
    capture_pid=
    expected=$(expected_input)
    seen=$(captured_input)
    [ "$seen" = "$expected" ] || fail "the Mac received different input than was sent.
expected:
$expected
seen (filtered):
$seen
raw:
$(cat "$CAPTURE_OUTPUT")"
    case "$expected" in
    *'7 115 1'*) step "host capture: the Mac received exactly F24 down/up and Shift down/up" && captured_kinds="keyboard" ;;
    *) step "SKIP keyboard capture: another app has seized the keyboard ($(sed -n 's/.*"refused":\({[^}]*}\).*/\1/p' "$CAPTURE_OUTPUT"); Karabiner-Elements does); its reports were checked as written" ;;
    esac
    case "$expected" in
    *'1 48 10'*) step "host capture: the Mac received exactly the mouse move +10,+10 and back -10,-10" && captured_kinds="${captured_kinds:+$captured_kinds + }mouse" ;;
    *) step "SKIP mouse capture: another app has seized the mouse; its reports were checked as written" ;;
    esac
fi

expect_host_mouse "5 1"
step "the Mac parsed the wheel mouse: 5-byte reports with Wheel and AC Pan"
if [ "$capturing" = 1 ]; then
    scroll_check
else
    step "SKIP scroll: no host capture, so no scroll was sent"
fi

step "turning HID off"
hid_off
after=$(call STATE)
[ "$(leading_links "$after")" = "$PREFIX_LINKS" ] || fail "the prefix isn't linked first after HID_OFF: $after"
[ "$(foreign_links "$after")" = "$foreign_before" ] || fail "foreign links changed: before [$foreign_before] after [$(foreign_links "$after")]"
wait_for_host present || fail "the Mac dropped the idle keyboard/mouse after HID_OFF (off must not re-enumerate)"

waited=0
until [ "$(timeout --foreground --kill-after=5 10 adb -s "$USB" get-state 2>/dev/null)" = device ]; do
    [ "$waited" -lt "$ADB_RETURN_WAIT_S_MAX" ] || fail "USB adb ($USB) did not come back within ${ADB_RETURN_WAIT_S_MAX}s"
    sleep 1
    waited=$((waited + 1))
done
if [ "$capturing" = 1 ]; then
    step "PASS: enumerated, harmless input written and received by the Mac ($captured_kinds captured), HID idle in the prefix, USB adb and foreign links intact"
else
    step "PASS (host capture skipped): enumerated, harmless input written, HID idle in the prefix, USB adb and foreign links intact"
fi
