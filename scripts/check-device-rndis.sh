#!/usr/bin/env bash
# L4 RNDIS check (~60s, Mac host): set Bootleg's prefix up (HID on, then off), confirm the phone links
# RNDIS, keyboard and mouse first and the Mac lists them as interfaces 0-1 (CDC + CDC Data; macOS has
# no RNDIS driver), 2 and 3 (HID); with no hidg fd open, re-plug through the HAL (another USB mode and
# back to the original) and confirm the prefix stays first; expose a read-only disk after it; turn
# RNDIS tethering on and off with the volume mounted and confirm the daemon's medium follows with no
# re-enumeration (same disk, no DiskArbitration removal). The data path needs an RNDIS host: see
# scripts/rndis-windows-checklist.md.
#
# Needs the debug app (`mise run deploy`); uses wireless adb (the prefix re-plugs USB). Takes the
# device lock unless BOOTLEG_DEVICE_LOCK_HELD=1. Sends no HID input.
set -euo pipefail

cd "$(dirname "$0")/.."
WIFI_SERIAL=${BOOTLEG_WIFI_SERIAL:-$(scripts/wifi-serial.sh)}
USB_SERIAL=${BOOTLEG_USB_SERIAL:-$(scripts/usb-serial.sh)}
LOCK=/private/tmp/claude-501/bootleg-device.lock
LOCK_WAIT_S_MAX=120
PREFIX_WAIT_S_MAX=30
WAIT_S_MAX=20
ADB_CALL_TIMEOUT_S=90
FIXTURE_DIR=build/device-fixtures
IMAGE=rndis-ro.img
PHONE_PATH=/sdcard/bootleg-l4/$IMAGE
KERNEL_PATH=/data/media/0/bootleg-l4/$IMAGE
VOLUME=BLRNDIS
PREFIX='["ffs.bootleg_rndis","hid.bootleg_kbd","hid.bootleg_mouse_wheel"]'
MAC_PREFIX='0:2 1:10 2:3 3:3' # interface:class, CDC control, CDC data, HID, HID
started_s=$SECONDS

fail() {
    echo "check-device-rndis: FAIL: $*" >&2
    exit 1
}
step() { echo "check-device-rndis: [$((SECONDS - started_s))s] $*"; }
wadb() { timeout --foreground --kill-after=5 "$ADB_CALL_TIMEOUT_S" adb -s "$WIFI_SERIAL" "$@"; }
root() { wadb shell "su -c '$1'" | tr -d '\r'; }
call() {
    local method=$1
    shift
    wadb shell content call --uri content://dev.jacobhayes.bootleg.debug --method "$method" "$@" |
        tr -d '\r' | sed -n 's/^Result: Bundle\[{json=\(.*\)}\]$/\1/p'
}
wait_until() {
    local seconds=$1 description=$2 deadline=$((SECONDS + $1))
    shift 2
    until "$@"; do
        [[ $SECONDS -lt $deadline ]] || fail "timed out after ${seconds}s waiting for $description"
        sleep 0.5
    done
}
expect_type() { [[ $(jq -r .type <<<"$1") == *"$2" ]] || fail "expected $2, got $1"; }

# A gadget state read that succeeded; anything else (adb down, an error) fails the caller.
state() {
    local json
    json=$(call STATE) && jq -e '.luns | type == "object"' <<<"$json" >/dev/null && echo "$json"
}
lun0_empty() {
    local json
    json=$(state) || return 1
    [[ $(jq -r '.luns["0"].file // ""' <<<"$json") == "" ]]
}
prefix_ready() {
    local json
    json=$(state) || return 1
    [[ $(jq -c '.configLinks | keys_unsorted[:3]' <<<"$json") == "$PREFIX" && $(jq -r .rndis <<<"$json") == READY &&
        $(jq -r .udc <<<"$json") != "" ]]
}
medium_is() { [[ $(root "cat /data/adb/bootleg/rndis/status.json" | jq -r .media) == "$1" ]]; }
no_hidg_holders() { [[ -z $(root "ls -l /proc/[0-9]*/fd 2>/dev/null | grep hidg") ]]; }
usb_functions() { wadb shell svc usb getFunctions 2>&1 | tr -d '\r' | sed 's/.*: *//'; }
usb_mode_has() { [[ $(usb_functions) == *"$1"* ]]; }
usb_adb_up() { [[ $(timeout --foreground 10 adb -s "$USB_SERIAL" get-state 2>/dev/null) == device ]]; }
volume_disk() { diskutil info "/Volumes/$VOLUME" 2>/dev/null | awk '/Part of Whole:/ {print $NF}'; }
mac_interfaces() {
    ioreg -r -c IOUSBHostInterface -l -w0 | awk -v vendor="$VENDOR_DECIMAL" '
        /^\+-o / { if (v == vendor && n != "") print n ":" c; v = ""; n = ""; c = "" }
        /"idVendor" = / { v = $NF }
        /"bInterfaceNumber" = / { n = $NF }
        /"bInterfaceClass" = / { c = $NF }
        END { if (v == vendor && n != "") print n ":" c }' | sort -t: -k1,1n | paste -sd' ' -
}
# The phone's USB session on the Mac: a new one on every enumeration.
usb_session() {
    ioreg -r -c IOUSBHostDevice -l -w0 | awk -v vendor="$VENDOR_DECIMAL" '
        /^\+-o / { if (v == vendor) print s; v = ""; s = "" }
        /"idVendor" = / { v = $NF }
        /"sessionID" = / { s = $NF }
        END { if (v == vendor) print s }' | head -1
}
mac_has_prefix() { [[ $(mac_interfaces) == "$MAC_PREFIX"* ]]; }

# Preflight before taking anything over: nothing here is undone by cleanup.
command -v jq >/dev/null || fail "jq is required"
[[ ! -d /Volumes/$VOLUME ]] || fail "/Volumes/$VOLUME is already mounted; eject it first"
if [[ ${BOOTLEG_DEVICE_LOCK_HELD:-0} == 1 ]]; then
    [[ -d $LOCK ]] || fail "BOOTLEG_DEVICE_LOCK_HELD=1 but $LOCK doesn't exist"
else
    deadline=$((SECONDS + LOCK_WAIT_S_MAX))
    until mkdir "$LOCK" 2>/dev/null; do
        [[ $SECONDS -lt $deadline ]] || fail "device lock $LOCK still held after ${LOCK_WAIT_S_MAX}s"
        sleep 1
    done
    lock_taken=1
fi

# Cleanup acts only on what this run changed, and never removes a disk unless a successful state
# read shows the Mac let go of it.
lock_taken=${lock_taken:-0}
exported=0
tether_on=0
functions_before=
hid_before=
mode_before=
cleanup() {
    local status=$?
    trap - EXIT
    trap '' INT TERM HUP
    [[ $tether_on == 0 ]] || call TETHER_OFF >/dev/null || echo "check-device-rndis: WARN: tethering off failed" >&2
    if [[ -n $functions_before && $(usb_functions) != "$functions_before" ]]; then
        if call HID_ENABLE --extra keyboard:b:false --extra mouse:b:false >/dev/null && no_hidg_holders; then
            wadb shell svc usb setFunctions "$functions_before" >/dev/null || echo "check-device-rndis: WARN: USB functions not put back" >&2
        else
            echo "check-device-rndis: WARN: a hidg fd is open; set the USB mode back to '$functions_before' by hand" >&2
        fi
    fi
    if [[ $exported == 1 ]]; then
        [[ -z ${disk_id:-} ]] || diskutil eject "$disk_id" >/dev/null 2>&1 || true
        if lun0_empty; then
            call APPLY --arg "'{\"slots\":[],\"hid\":{\"keyboard\":false,\"mouse\":false}}'" >/dev/null || echo "check-device-rndis: WARN: drives off failed" >&2
        else
            echo "check-device-rndis: WARN: the disk may still be in use; left it exposed (eject it, then turn drives off)" >&2
            [[ $status != 0 ]] || status=1
        fi
    fi
    if [[ -n $hid_before ]]; then
        call HID_ENABLE --extra "keyboard:b:$(jq -r .keyboard <<<"$hid_before")" --extra "mouse:b:$(jq -r .mouse <<<"$hid_before")" >/dev/null ||
            echo "check-device-rndis: WARN: HID not put back to $hid_before" >&2
    fi
    [[ -z $mode_before ]] || call TETHER_MODE --extra "mode:s:$mode_before" >/dev/null || true
    [[ $lock_taken == 0 ]] || rmdir "$LOCK"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

[[ $(wadb shell dumpsys window | tr -d '\r' | grep -o 'isKeyguardShowing=[a-z]*' | head -1) == isKeyguardShowing=false ]] ||
    fail "the phone is locked; unlock it first"
vendor_hex=$(root "cat /config/usb_gadget/g1/idVendor" | sed 's/^0[xX]//')
[[ $vendor_hex =~ ^[0-9a-fA-F]{1,4}$ ]] || fail "cannot read the gadget's idVendor: '$vendor_hex'"
VENDOR_DECIMAL=$((16#$vendor_hex))
json=$(state) || fail "cannot read the gadget state"
[[ $(jq '[.luns[] | select(.file != "")] | length' <<<"$json") == 0 ]] || fail "disks are exposed; eject them first"
[[ $(call TETHER_STATE | jq -r .tethered) == false ]] || fail "USB tethering is on; turn it off first"
# Never expose unformatted media: the fixture is a 16 MiB MBR + FAT image.
if ! [[ -f $FIXTURE_DIR/$IMAGE && $(wc -c <"$FIXTURE_DIR/$IMAGE" | tr -d " ") == 16777216 ]] ||
    ! file -b "$FIXTURE_DIR/$IMAGE" | grep -q 'DOS/MBR boot sector'; then
    mkdir -p "$FIXTURE_DIR/rndis-content"
    echo "hello from bootleg" >"$FIXTURE_DIR/rndis-content/hello.txt"
    rm -f "$FIXTURE_DIR/tmp-rndis.dmg" "$FIXTURE_DIR/tmp-rndis.cdr"
    hdiutil create -quiet -size 16m -fs MS-DOS -volname "$VOLUME" -layout MBRSPUD -srcfolder "$FIXTURE_DIR/rndis-content" "$FIXTURE_DIR/tmp-rndis.dmg"
    hdiutil convert -quiet "$FIXTURE_DIR/tmp-rndis.dmg" -format UDTO -o "$FIXTURE_DIR/tmp-rndis"
    mv "$FIXTURE_DIR/tmp-rndis.cdr" "$FIXTURE_DIR/$IMAGE"
fi
if [[ $(wadb shell "sha256sum $PHONE_PATH 2>/dev/null" | cut -d' ' -f1) != "$(shasum -a 256 "$FIXTURE_DIR/$IMAGE" | cut -d' ' -f1)" ]]; then
    wadb shell "mkdir -p ${PHONE_PATH%/*}"
    wadb push -q "$FIXTURE_DIR/$IMAGE" "$PHONE_PATH" >/dev/null
fi
hid_before=$(call HID_STATE | jq -c '{keyboard, mouse}')
mode_before=$(call TETHER_STATE | jq -r .mode)

step "set the prefix up (HID on), then HID off: the prefix stays, no hidg fd stays open"
expect_type "$(call HID_ENABLE --extra keyboard:b:true --extra mouse:b:true)" ApplyResult.Applied
wait_until "$PREFIX_WAIT_S_MAX" "the prefix first with the RNDIS daemon ready" prefix_ready
expect_type "$(call HID_ENABLE --extra keyboard:b:false --extra mouse:b:false)" ApplyResult.Applied
wait_until "$WAIT_S_MAX" "every hidg fd to close" no_hidg_holders
wait_until "$WAIT_S_MAX" "the Mac to list RNDIS as interfaces 0-1 and HID as 2-3" mac_has_prefix
medium_is false || fail "the RNDIS medium is connected with tethering off"

step "HAL round trip through another USB mode and back; the prefix stays first"
functions_before=$(usb_functions)
other=mtp
[[ $functions_before != *mtp* ]] || other=ptp
wadb shell svc usb setFunctions "$other" >/dev/null
wait_until "$WAIT_S_MAX" "the HAL to link $other" usb_mode_has "$other"
no_hidg_holders || fail "a hidg fd opened during the round trip"
wadb shell svc usb setFunctions "$functions_before" >/dev/null
functions_before=
wait_until "$PREFIX_WAIT_S_MAX" "the prefix to stay first" prefix_ready
wait_until "$WAIT_S_MAX" "the Mac to list the prefix again" mac_has_prefix

step "a read-only disk after the prefix"
exported=1
expect_type "$(call APPLY --arg "'{\"slots\":[{\"lun\":0,\"backingPath\":\"$KERNEL_PATH\",\"mode\":\"DISK_RO\"}],\"hid\":{\"keyboard\":false,\"mouse\":false}}'")" ApplyResult.Applied
wait_until "$WAIT_S_MAX" "/Volumes/$VOLUME to mount" test -d "/Volumes/$VOLUME"
disk_id=$(volume_disk)
[[ $(cat "/Volumes/$VOLUME/hello.txt") == "hello from bootleg" ]] || fail "/Volumes/$VOLUME/hello.txt is wrong"
prefix_ready && mac_has_prefix || fail "the disk moved the prefix: $(mac_interfaces)"

step "RNDIS tethering on and off with the volume mounted: no re-enumeration"
toggles_started=$(date '+%Y-%m-%d %H:%M:%S')
session=$(usb_session)
[[ -n $session ]] || fail "the Mac lists no USB device of the phone"
[[ $(call TETHER_MODE --extra mode:s:rndis | jq -r .mode) == rndis ]] || fail "could not set the mode to RNDIS"
tether_on=1
expect_type "$(call TETHER_ON)" TetherResult.Changed
medium_is true || fail "the RNDIS medium is off with tethering on"
expect_type "$(call TETHER_OFF)" TetherResult.Changed
tether_on=0
medium_is false || fail "the RNDIS medium is on after tethering off"
[[ $(call TETHER_STATE | jq -r .tethered) == false ]] || fail "still tethered: $(call TETHER_STATE)"
[[ $(volume_disk) == "$disk_id" ]] || fail "the volume moved from $disk_id to $(volume_disk): USB re-enumerated"
[[ $(usb_session) == "$session" ]] || fail "the phone's USB session changed: USB re-enumerated"
removals=$(/usr/bin/log show --start "$toggles_started" --style compact \
    --predicate "process == \"diskarbitrationd\" AND eventMessage BEGINSWITH \"removed disk, id = /dev/$disk_id\"") ||
    fail "cannot read DiskArbitration's log"
[[ $removals != *"removed disk"* ]] || fail "DiskArbitration saw $disk_id removed during the toggles"

step "eject on the Mac, drives off, HID back"
diskutil eject "$disk_id" >/dev/null
wait_until "$WAIT_S_MAX" "the phone to see the eject" lun0_empty
expect_type "$(call APPLY --arg "'{\"slots\":[],\"hid\":{\"keyboard\":false,\"mouse\":false}}'")" ApplyResult.Applied
exported=0
prefix_ready || fail "the prefix moved when the disk went"
wait_until "$WAIT_S_MAX" "USB adb to come back" usb_adb_up
step "PASS"
