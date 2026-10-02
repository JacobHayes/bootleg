#!/bin/sh
# L4 HID check (< 30s): enable Bootleg's keyboard + mouse, confirm the Mac enumerates them, send
# only harmless input (F24 tap, Shift tap, a mouse move that cancels out), then turn HID off and
# confirm our functions are gone while ADB and foreign links are untouched.
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
# Once HID has been enabled, an EXIT/INT/TERM trap turns it off again whatever happens, so a
# failure can't leave a keyboard and mouse attached to the Mac.
#
# Usage: scripts/check-device-hid.sh   (env: BOOTLEG_WIFI_SERIAL, BOOTLEG_USB_SERIAL)
set -eu

WIRELESS=${BOOTLEG_WIFI_SERIAL:-192.168.1.50:5555}
USB=${BOOTLEG_USB_SERIAL:-}
LOCK=/private/tmp/claude-501/bootleg-device.lock
LOCK_WAIT_S_MAX=120
ENUMERATE_WAIT_S_MAX=10
ADB_RETURN_WAIT_S_MAX=20
CAPTURE_SECONDS_MAX=20
CAPTURE_READY_WAIT_S_MAX=10
ADB="adb -s $WIRELESS"
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
    trap - EXIT INT TERM
    if [ -n "$capture_pid" ]; then kill "$capture_pid" 2>/dev/null || true; fi
    if [ "$hid_enabled" = 1 ]; then
        echo "check-device-hid: cleanup: turning HID off" >&2
        case "$(call HID_OFF 2>&1)" in
        *ApplyResult.Applied*) ;;
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

# Builds the capture helper if needed and starts it in the background. Returns 1 (after printing
# SKIP) when host capture isn't possible, so the check falls back to the other assertions.
start_capture() {
    if [ ! -x "$CAPTURE_BINARY" ] || [ "$CAPTURE_SOURCE" -nt "$CAPTURE_BINARY" ]; then
        mkdir -p "$(dirname "$CAPTURE_BINARY")"
        if ! xcrun swiftc -O -o "$CAPTURE_BINARY" "$CAPTURE_SOURCE" >&2; then
            step "SKIP host capture: could not build $CAPTURE_SOURCE (Xcode command line tools?)"
            return 1
        fi
    fi
    "$CAPTURE_BINARY" --vendor "$VENDOR_ID" --product "$PRODUCT_ID" --seconds "$CAPTURE_SECONDS_MAX" >"$CAPTURE_OUTPUT" &
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

# Captured values as "page usage value", ordered by report time and then usage (IOKit delivers
# the X and Y of one report in either order).
captured_input() {
    sed -n 's/^{"page":\([0-9]*\),"ts":\([0-9]*\),"usage":\([0-9]*\),"value":\(-*[0-9]*\)}$/\2 \1 \3 \4/p' "$CAPTURE_OUTPUT" |
        sort -n -k1,1 -k3,3 | awk '{ print $2, $3, $4 }' |
        awk '!($1 == 7 && ($2 < 4 || $2 > 65535)) && !($1 == 9 && $3 == 0)'
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

before=$(call STATE)
case "$before" in *'"udc":"'[!\"]*) ;; *) fail "gadget not bound before the test: $before" ;; esac
foreign_before=$(foreign_links "$before")
wait_for_host absent || fail "a $VENDOR_ID keyboard/mouse is already enumerated; is another HID app active?"

step "enabling keyboard + mouse"
hid_enabled=1
enabled=$(call HID_ENABLE --extra keyboard:b:true --extra mouse:b:true)
case "$enabled" in *ApplyResult.Applied*) ;; *) fail "HID_ENABLE: $enabled" ;; esac

step "waiting for the Mac to enumerate them (vendor $VENDOR_ID)"
wait_for_host present || fail "Mac did not list a usage 1/6 keyboard and 1/2 mouse from $VENDOR_ID within ${ENUMERATE_WAIT_S_MAX}s"
host_hid_rows 1 6 | sed 's/^/  keyboard: /'
host_hid_rows 1 2 | sed 's/^/  mouse:    /'

capturing=0
captured_kinds=
if start_capture; then capturing=1; fi

for sequence in f24 shift mouse; do
    sent=$(call HID_SEND --extra "sequence:s:$sequence")
    queued=$(printf '%s' "$sent" | sed -n 's/.*"queued":\([0-9]*\).*/\1/p')
    written=$(printf '%s' "$sent" | sed -n 's/.*"written":\([0-9]*\).*/\1/p')
    dropped=$(printf '%s' "$sent" | sed -n 's/.*"dropped":\([0-9]*\).*/\1/p')
    [ "${queued:-0}" -eq 2 ] && [ "${written:-0}" -ge 2 ] && [ "${dropped:-1}" -eq 0 ] || fail "HID_SEND $sequence: $sent"
    step "sent $sequence: $queued reports queued, $written written, none dropped"
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

step "turning HID off"
off=$(call HID_OFF)
case "$off" in *ApplyResult.Applied*) hid_enabled=0 ;; *) fail "HID_OFF: $off" ;; esac
after=$(call STATE)
case "$after" in *hid.bootleg*) fail "Bootleg HID functions still present: $after" ;; esac
[ "$(foreign_links "$after")" = "$foreign_before" ] || fail "foreign links changed: before [$foreign_before] after [$(foreign_links "$after")]"
wait_for_host absent || fail "Mac still lists the Bootleg keyboard/mouse after HID_OFF"

waited=0
until [ "$(adb -s "$USB" get-state 2>/dev/null)" = device ]; do
    [ "$waited" -lt "$ADB_RETURN_WAIT_S_MAX" ] || fail "USB adb ($USB) did not come back within ${ADB_RETURN_WAIT_S_MAX}s"
    sleep 1
    waited=$((waited + 1))
done
if [ "$capturing" = 1 ]; then
    step "PASS: enumerated, harmless input written and received by the Mac ($captured_kinds captured), HID removed, USB adb and foreign links intact"
else
    step "PASS (host capture skipped): enumerated, harmless input written, HID removed, USB adb and foreign links intact"
fi
