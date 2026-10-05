#!/usr/bin/env bash
# L4 web check (~40s warm): the phone's real web UI in a real browser on this Mac, over the
# tailnet. Remote on (debug provider) → page pending → approve via the hook → the page turns
# approved without a reload → take control → the image picker lists the library by name → expose
# a 64 MB FAT32 blank (CREATE_BLANK) read-only, the Mac mounts it read-only, the Mac ejects, the
# page ejects the slot → HID on from the page, F24 from "More keys", reports written, a tab switch
# releases all, HID off → paste prepare shows the plan, cancelled (never run) → a second browser
# session asks to take over: decline keeps the lease, allow moves it → revoke via the hook: both
# pages show revoked and stay closed. Every step's screenshot lands in build/web-check/shots.
#
# The browser is the installed Google Chrome, driven by playwright-core (installed locally into
# build/web-check from scripts/web-check/package.json; nothing global). Takes the device lock; an
# EXIT trap ejects the volume, revokes this Mac, turns HID, Remote and the drives off and deletes
# the test image whatever happens.
#
# Usage: scripts/check-device-web.sh   (env: BOOTLEG_WIFI_SERIAL, BOOTLEG_USB_SERIAL,
#        BOOTLEG_WEB_HEADFUL=1 to watch it in a visible window, BOOTLEG_WEB_SHOTS=<dir> for the
#        screenshots, BOOTLEG_DEVICE_LOCK_HELD=1 when the caller already holds the device lock)
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/device-lock.sh
source scripts/device-lock.sh

WIFI_SERIAL=${BOOTLEG_WIFI_SERIAL:-$(scripts/wifi-serial.sh)}
PROVIDER=content://dev.jacobhayes.bootleg.debug
GADGET=/config/usb_gadget/g1
WORK_DIR=build/web-check
SHOTS_DIR=${BOOTLEG_WEB_SHOTS:-$WORK_DIR/shots}
IMAGE_NAME=zz-bootleg-l4-web.img
PHONE_IMAGE=/sdcard/bootleg/$IMAGE_NAME
IMAGE_SIZE_BYTES=$((64 * 1024 * 1024))

LOCK_WAIT_S=300
NODE_WAIT_S=20
ENUMERATE_WAIT_S=10
POLL_S=0.25

now_ms() { local ns; ns=$(date +%s%N); echo $((ns / 1000000)); }
run_started_ms=$(now_ms)
have_lock=false
remote_on=false
volume=

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# Wireless adb can hang on a dead transport: no call outlives the longest phone-side operation.
wadb() { timeout --foreground --kill-after=5 120 adb -s "$WIFI_SERIAL" "$@"; }
root() { wadb shell "su -c '$1'"; }

# call METHOD [ARGS...]: the debug provider's JSON; fails on an error reply.
call() {
    local method=$1 out json
    shift
    out=$(wadb shell content call --uri "$PROVIDER" --method "$method" "$@") || fail "$method: adb failed"
    json=${out#*json=}
    json=${json%\}]}
    jq -e 'type != "object" or .error == null' <<<"$json" >/dev/null || fail "$method: $(jq -r .error <<<"$json")"
    echo "$json"
}

is_mounted() { [[ -n $volume ]] && mount | grep -q " on /Volumes/$volume ("; }
hid_rows() { hidutil list 2>/dev/null | awk -v vid="$VENDOR_ID" 'tolower($1) == vid && $4 == 1 && ($5 == 6 || $5 == 2) && $7 == "USB"' | wc -l | tr -d ' '; }

cleanup() {
    local status=$?
    trap - EXIT
    # Cleanup is bounded (every adb call is); a second signal must not stop it before the lock is released.
    trap '' INT TERM HUP
    if $have_lock; then
        if is_mounted; then diskutil eject "/Volumes/$volume" >/dev/null 2>&1 || true; fi
        if $remote_on; then
            wadb shell content call --uri "$PROVIDER" --method REMOTE_REVOKE --extra "node:s:$MAC_NODE" >/dev/null 2>&1 || true
        fi
        # HID first: its own path closes the session before the functions go.
        if wadb shell "su -c 'ls $GADGET/functions'" 2>/dev/null | grep -q 'hid\.bootleg'; then
            case "$(wadb shell content call --uri "$PROVIDER" --method HID_OFF 2>&1)" in
            *ApplyResult.Applied*) ;;
            *) echo "WARN: cleanup HID_OFF failed; turn keyboard + mouse off in the app" >&2 ;;
            esac
        fi
        if $remote_on; then
            wadb shell content call --uri "$PROVIDER" --method REMOTE_OFF >/dev/null 2>&1 || true
        fi
        if wadb shell "su -c 'ls $GADGET/functions'" 2>/dev/null | grep -q bootleg; then
            wadb shell content call --uri "$PROVIDER" --method OFF >/dev/null 2>&1 || true
        fi
        wadb shell rm -f "$PHONE_IMAGE" >/dev/null 2>&1 || true
        device_lock_release
    fi
    local total_ms=$(($(now_ms) - run_started_ms))
    printf '%s  %d.%ds total (with setup and cleanup)\n' "$([[ $status == 0 ]] && echo PASS || echo FAIL)" $((total_ms / 1000)) $((total_ms % 1000 / 100))
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
trap 'exit 129' HUP

# playwright-core, pinned by scripts/web-check/package.json, into build/web-check (no browser
# download: it drives the installed Chrome).
install_deps() {
    command -v node >/dev/null && command -v npm >/dev/null || fail "needs node and npm from mise.toml: run it via mise run check:device:web"
    mkdir -p "$WORK_DIR"
    if ! cmp -s scripts/web-check/package.json "$WORK_DIR/package.json" || [[ ! -d $WORK_DIR/node_modules/playwright-core ]]; then
        cp scripts/web-check/package.json "$WORK_DIR/package.json"
        (cd "$WORK_DIR" && PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install --silent --no-audit --no-fund >/dev/null)
    fi
    [[ -d "/Applications/Google Chrome.app" ]] || fail "needs Google Chrome in /Applications"
}

take_lock() {
    device_lock_take check-device-web "$LOCK_WAIT_S" || fail "no device lock"
    have_lock=true
}

preflight() {
    MAC_NODE=$(tailscale status --json | jq -r '.Self.ID')
    [[ -n $MAC_NODE && $MAC_NODE != null ]] || fail "this Mac isn't on a tailnet (tailscale status)"
    local state
    state=$(call STATE)
    jq -e '.functions | map(select(contains("bootleg"))) | length == 0' <<<"$state" >/dev/null ||
        fail "Bootleg is already exporting drives or HID; turn it off before running this check"
    VENDOR_ID=$(root "cat $GADGET/idVendor" | tr -d '\r' | tr 'A-F' 'a-f' | sed 's/^0x0*/0x/')
    (($(hid_rows) == 0)) || fail "a $VENDOR_ID keyboard/mouse is already enumerated"
}

# A formatted FAT32 blank made by the app itself (never unformatted media: macOS would show a
# dialog). Its volume label comes from the boot sector, so the check knows what the Mac mounts.
blank_image() {
    wadb shell rm -f "$PHONE_IMAGE"
    call CREATE_BLANK --extra "name:s:$IMAGE_NAME" --extra "size:l:$IMAGE_SIZE_BYTES" --extra format:s:FAT32 >/dev/null
    # The blank is an MBR disk with one FAT32 partition; its boot sector holds the label.
    local mbr start_sector boot
    mbr=$(wadb exec-out dd if="$PHONE_IMAGE" bs=512 count=1 2>/dev/null | file -b -)
    start_sector=$(sed -nE 's/.*partition 1 : ID=0xc,.* startsector ([0-9]+),.*/\1/p' <<<"$mbr")
    [[ -n $start_sector ]] || fail "$PHONE_IMAGE isn't an MBR disk with a FAT32 partition: $mbr"
    boot=$(wadb exec-out dd if="$PHONE_IMAGE" bs=512 skip="$start_sector" count=1 2>/dev/null | file -b -)
    [[ $boot == *"FAT (32 bit)"* ]] || fail "$PHONE_IMAGE partition 1 isn't a FAT32 volume: $boot"
    volume=$(sed -nE 's/.*label: "([^"]*)".*/\1/p' <<<"$boot" | sed 's/ *$//')
    [[ -n $volume && ! -e /Volumes/$volume ]] || fail "unusable volume label '$volume' (or /Volumes/$volume exists)"
    call IMAGES | jq -e --arg name "/$IMAGE_NAME" 'any(endswith($name))' >/dev/null || fail "the library doesn't list $PHONE_IMAGE"
}

remote_up() {
    remote_on=true
    call REMOTE_ON >/dev/null
    local deadline_ms=$(($(now_ms) + NODE_WAIT_S * 1000)) state
    until [[ $(call REMOTE_STATE | jq -r '.running and .backendState == "Running"') == true ]]; do
        (($(now_ms) < deadline_ms)) || fail "the tailnet node didn't reach Running in ${NODE_WAIT_S}s"
        sleep "$POLL_S"
    done
    state=$(call REMOTE_STATE)
    HOST=$(jq -r '.dnsName | rtrimstr(".")' <<<"$state")
    [[ -n $HOST ]] || fail "the node has no MagicDNS name: $state"
    # Start from "never approved" so the pending path runs every time.
    if jq -e --arg node "$MAC_NODE" '.approvals | any(.nodeId == $node)' <<<"$state" >/dev/null; then
        call REMOTE_REVOKE --extra "node:s:$MAC_NODE" >/dev/null
    fi
    # The app left open on its Control tab holds local priority and refuses remote control; HOME
    # (a key on the phone, not the Mac) backgrounds it, which ends local capture.
    if [[ $(jq -r .lease <<<"$state") == local ]]; then
        wadb shell input keyevent KEYCODE_HOME
        deadline_ms=$(($(now_ms) + NODE_WAIT_S * 1000))
        until [[ $(call REMOTE_STATE | jq -r .lease) != local ]]; do
            (($(now_ms) < deadline_ms)) || fail "the phone's Control tab still has local priority"
            sleep "$POLL_S"
        done
    fi
    # Running comes a moment before the page answers over the tailnet.
    deadline_ms=$(($(now_ms) + NODE_WAIT_S * 1000))
    until curl -fsS --max-time 2 -o /dev/null "http://$HOST/api/transport" 2>/dev/null; do
        (($(now_ms) < deadline_ms)) || fail "http://$HOST/ didn't answer within ${NODE_WAIT_S}s of Running"
        sleep "$POLL_S"
    done
}

install_deps
take_lock
preflight
blank_image
remote_up
rm -rf "$SHOTS_DIR"
BOOTLEG_WIFI_SERIAL=$WIFI_SERIAL BOOTLEG_MAC_NODE=$MAC_NODE BOOTLEG_HOST=$HOST BOOTLEG_IMAGE_NAME=$IMAGE_NAME \
    BOOTLEG_VOLUME=$volume BOOTLEG_VENDOR_ID=$VENDOR_ID BOOTLEG_SHOTS_DIR=$SHOTS_DIR \
    BOOTLEG_WEB_MODULES=$PWD/$WORK_DIR node scripts/web-check/check.mjs
