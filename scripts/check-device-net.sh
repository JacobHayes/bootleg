#!/usr/bin/env bash
# L4 network check (~2 min, Mac host), both of Bootleg's network functions:
# - NCM: turn tethering on in NCM mode (sets the prefix up: NCM, keyboard, mouse first on the phone,
#   interfaces 0-1 CDC NCM and 2-3 HID on the Mac), the Mac's interface (found by the function's
#   host_addr) gets an address, resolves through the phone and reaches the internet; a HAL round trip
#   (another USB mode and back, HID off, no hidg fd) keeps the prefix first and tethering on; with a
#   read-only disk mounted, tethering off and on three times never re-enumerates (same USB session,
#   no DiskArbitration removal), the interface goes inactive and comes back; switching to RNDIS is
#   refused while the disk is exposed.
# - RNDIS: after the eject, tethering on in RNDIS mode is a mode change (the prefix set up again with
#   the RNDIS function); with the disk mounted again, RNDIS off and on never re-enumerates and the
#   daemon's medium follows. The RNDIS data path needs an RNDIS host: scripts/rndis-windows-checklist.md.
# - Finally the prefix goes back to the mode the phone had (one more re-plug, nothing exposed).
#
# Needs the debug app (`mise run deploy`); uses wireless adb (the prefix re-plugs USB). Takes the
# device lock unless BOOTLEG_DEVICE_LOCK_HELD=1. Keeps the screen on while it runs (the phone must be
# unlocked at the start; scripts/stay-awake.sh). Sends no HID input. Mac side: read-only commands,
# `curl --interface` and `dig`; macOS adds a "Pixel 6" network service the first time and may route
# through it while it is active.
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=scripts/device-lock.sh
source scripts/device-lock.sh
# shellcheck source=scripts/stay-awake.sh
source scripts/stay-awake.sh
WIFI_SERIAL=${BOOTLEG_WIFI_SERIAL:-$(scripts/wifi-serial.sh)}
USB_SERIAL=${BOOTLEG_USB_SERIAL:-$(scripts/usb-serial.sh)}
LOCK_WAIT_S_MAX=120
PREFIX_WAIT_S_MAX=30
WAIT_S_MAX=20
ADB_CALL_TIMEOUT_S=90
CURL_TIMEOUT_S=10
TOGGLE_COUNT=3
CONNECTIVITY_URL=https://connectivitycheck.gstatic.com/generate_204
FIXTURE_DIR=build/device-fixtures
IMAGE=rndis-ro.img
PHONE_PATH=/sdcard/bootleg-l4/$IMAGE
KERNEL_PATH=/data/media/0/bootleg-l4/$IMAGE
VOLUME=BLRNDIS
NCM_HOST_ADDR=/config/usb_gadget/g1/functions/ncm.bootleg/host_addr
MAC_PREFIX='0:2 1:10 2:3 3:3' # interface:class: CDC control, CDC data, HID, HID (NCM and RNDIS alike)
started_s=$SECONDS

fail() {
    echo "check-device-net: FAIL: $*" >&2
    exit 1
}
step() { echo "check-device-net: [$((SECONDS - started_s))s] $*"; }
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
prefix_json() { jq -cn --arg network "$1" '[$network, "hid.bootleg_kbd", "hid.bootleg_mouse_wheel"]'; }

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
# $1: the network function the prefix starts with; RNDIS also needs its daemon ready.
prefix_ready() {
    local json
    json=$(state) || return 1
    [[ $(jq -c '.configLinks | keys_unsorted[:3]' <<<"$json") == "$(prefix_json "$1")" && $(jq -r .udc <<<"$json") != "" ]] || return 1
    [[ $1 != ffs.bootleg_rndis || $(jq -r .rndis <<<"$json") == READY ]]
}
tethered() { [[ $(call TETHER_STATE | jq -r .tethered) == "$1" ]]; }
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

# The Mac's NCM interface: the one whose address is the function's host_addr.
ncm_interface=
find_ncm_interface() {
    local name address
    # The kernel prints it with a NUL before the newline.
    address=$(root "cat $NCM_HOST_ADDR" | tr -d '\0\n ')
    [[ $address =~ ^([0-9a-f]{2}:){5}[0-9a-f]{2}$ ]] || return 1
    for name in $(ifconfig -l); do
        if ifconfig "$name" 2>/dev/null | grep -q "ether $address"; then
            ncm_interface=$name
            return 0
        fi
    done
    return 1
}
ncm_active() { ifconfig "$ncm_interface" 2>/dev/null | grep -q 'status: active' && ifconfig "$ncm_interface" | grep -q 'inet [0-9]'; }
ncm_inactive() { ! ifconfig "$ncm_interface" 2>/dev/null | grep -q 'status: active'; }
internet_through_ncm() {
    [[ $(curl -sS --interface "$ncm_interface" -m "$CURL_TIMEOUT_S" -o /dev/null -w '%{http_code}' "$CONNECTIVITY_URL") == 204 ]]
}
# Android's DNS on the tether answers a lookup sent to the interface's router.
dns_through_ncm() {
    local router
    router=$(ipconfig getoption "$ncm_interface" router 2>/dev/null) || return 1
    [[ -n $(dig +short +time=3 +tries=2 "@$router" connectivitycheck.gstatic.com A) ]]
}
disk_slot_json() { echo "'{\"slots\":[{\"lun\":0,\"backingPath\":\"$KERNEL_PATH\",\"mode\":\"DISK_RO\"}],\"hid\":{\"keyboard\":false,\"mouse\":false}}'"; }
no_slots_json() { echo "'{\"slots\":[],\"hid\":{\"keyboard\":false,\"mouse\":false}}'"; }
expose_disk() {
    exported=1
    expect_type "$(call APPLY --arg "$(disk_slot_json)")" ApplyResult.Applied
    wait_until "$WAIT_S_MAX" "/Volumes/$VOLUME to mount" test -d "/Volumes/$VOLUME"
    disk_id=$(volume_disk)
    [[ $(cat "/Volumes/$VOLUME/hello.txt") == "hello from bootleg" ]] || fail "/Volumes/$VOLUME/hello.txt is wrong"
}
# Ejects on the Mac, then drops the slot (a host-ejected slot applied again would stay ejected).
eject_disk() {
    diskutil eject "$disk_id" >/dev/null
    wait_until "$WAIT_S_MAX" "the phone to see the eject" lun0_empty
    disk_id=
    expect_type "$(call APPLY --arg "$(no_slots_json)")" ApplyResult.Applied
    exported=0
}
# Fails on any re-enumeration since $1 (a log start time) and $2 (the USB session then).
assert_no_reenumeration() {
    [[ $(volume_disk) == "$disk_id" ]] || fail "the volume moved from $disk_id to $(volume_disk): USB re-enumerated"
    [[ $(usb_session) == "$2" ]] || fail "the phone's USB session changed: USB re-enumerated"
    local removals
    removals=$(/usr/bin/log show --start "$1" --style compact \
        --predicate "process == \"diskarbitrationd\" AND eventMessage BEGINSWITH \"removed disk, id = /dev/$disk_id\"") ||
        fail "cannot read DiskArbitration's log"
    [[ $removals != *"removed disk"* ]] || fail "DiskArbitration saw $disk_id removed"
}

# Preflight before taking anything over: nothing here is undone by cleanup.
command -v jq >/dev/null || fail "jq is required"
[[ ! -d /Volumes/$VOLUME ]] || fail "/Volumes/$VOLUME is already mounted; eject it first"
device_lock_take check-device-net "$LOCK_WAIT_S_MAX" || fail "no device lock"

# Cleanup acts only on what this run changed, and never removes a disk unless a successful state
# read shows the Mac let go of it.
exported=0
tether_on=0
disk_id=
functions_before=
hid_before=
mode_before=
cleanup() {
    local status=$?
    trap - EXIT
    trap '' INT TERM HUP
    [[ $tether_on == 0 ]] || call TETHER_OFF >/dev/null || echo "check-device-net: WARN: tethering off failed" >&2
    if [[ -n $functions_before && $(usb_functions) != "$functions_before" ]]; then
        if call HID_ENABLE --extra keyboard:b:false --extra mouse:b:false >/dev/null && no_hidg_holders; then
            wadb shell svc usb setFunctions "$functions_before" >/dev/null || echo "check-device-net: WARN: USB functions not put back" >&2
        else
            echo "check-device-net: WARN: a hidg fd is open; set the USB mode back to '$functions_before' by hand" >&2
        fi
    fi
    if [[ $exported == 1 ]]; then
        [[ -z $disk_id ]] || diskutil eject "$disk_id" >/dev/null 2>&1 || true
        if lun0_empty; then
            call APPLY --arg "$(no_slots_json)" >/dev/null || echo "check-device-net: WARN: drives off failed" >&2
        else
            echo "check-device-net: WARN: the disk may still be in use; left it exposed (eject it, then turn drives off)" >&2
            [[ $status != 0 ]] || status=1
        fi
    fi
    if [[ -n $hid_before ]]; then
        call HID_ENABLE --extra "keyboard:b:$(jq -r .keyboard <<<"$hid_before")" --extra "mouse:b:$(jq -r .mouse <<<"$hid_before")" >/dev/null ||
            echo "check-device-net: WARN: HID not put back to $hid_before" >&2
    fi
    [[ -z $mode_before ]] || call TETHER_MODE --extra "mode:s:$mode_before" >/dev/null || true
    stay_awake_restore "$WIFI_SERIAL"
    device_lock_release
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

[[ $(wadb shell dumpsys window | tr -d '\r' | grep -o 'isKeyguardShowing=[a-z]*' | head -1) == isKeyguardShowing=false ]] ||
    fail "the phone is locked; unlock it first"
stay_awake_on "$WIFI_SERIAL"
vendor_hex=$(root "cat /config/usb_gadget/g1/idVendor" | sed 's/^0[xX]//')
[[ $vendor_hex =~ ^[0-9a-fA-F]{1,4}$ ]] || fail "cannot read the gadget's idVendor: '$vendor_hex'"
VENDOR_DECIMAL=$((16#$vendor_hex))
json=$(state) || fail "cannot read the gadget state"
[[ $(jq '[.luns[] | select(.file != "")] | length' <<<"$json") == 0 ]] || fail "disks are exposed; eject them first"
tethered false || fail "USB tethering is on; turn it off first"
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
expect_type "$(call HID_ENABLE --extra keyboard:b:false --extra mouse:b:false)" ApplyResult.Applied
wait_until "$WAIT_S_MAX" "every hidg fd to close" no_hidg_holders

step "NCM on: the prefix with the NCM function first, the Mac's interface reaches the internet"
[[ $(call TETHER_MODE --extra mode:s:ncm | jq -r .mode) == ncm ]] || fail "could not set the mode to NCM"
tether_on=1
expect_type "$(call TETHER_ON)" TetherResult.Changed
wait_until "$PREFIX_WAIT_S_MAX" "the NCM prefix first" prefix_ready ncm.bootleg
wait_until "$WAIT_S_MAX" "the Mac to list NCM as interfaces 0-1 and HID as 2-3" mac_has_prefix
wait_until "$WAIT_S_MAX" "the Mac's NCM interface" find_ncm_interface
wait_until "$WAIT_S_MAX" "$ncm_interface to get an address" ncm_active
wait_until "$WAIT_S_MAX" "the internet through $ncm_interface" internet_through_ncm
dns_through_ncm || fail "the phone's DNS didn't answer through $ncm_interface"
step "Mac: $ncm_interface $(ifconfig "$ncm_interface" | grep -o 'inet [0-9.]*')"

step "HAL round trip through another USB mode and back: the prefix stays first, tethering stays on"
functions_before=$(usb_functions)
other=mtp
[[ $functions_before != *mtp* ]] || other=ptp
wadb shell svc usb setFunctions "$other" >/dev/null
wait_until "$WAIT_S_MAX" "the HAL to link $other" usb_mode_has "$other"
no_hidg_holders || fail "a hidg fd opened during the round trip"
wadb shell svc usb setFunctions "$functions_before" >/dev/null
functions_before=
wait_until "$PREFIX_WAIT_S_MAX" "the prefix to stay first" prefix_ready ncm.bootleg
tethered true || fail "the HAL's re-plug ended tethering"
wait_until "$WAIT_S_MAX" "the internet through $ncm_interface again" internet_through_ncm

step "NCM off and on $TOGGLE_COUNT times with a read-only disk mounted: no re-enumeration"
expose_disk
prefix_ready ncm.bootleg && mac_has_prefix || fail "the disk moved the prefix: $(mac_interfaces)"
toggles_started=$(date '+%Y-%m-%d %H:%M:%S')
session=$(usb_session)
[[ -n $session ]] || fail "the Mac lists no USB device of the phone"
for round in $(seq "$TOGGLE_COUNT"); do
    expect_type "$(call TETHER_OFF)" TetherResult.Changed
    wait_until "$WAIT_S_MAX" "$ncm_interface to go inactive (round $round)" ncm_inactive
    expect_type "$(call TETHER_ON)" TetherResult.Changed
    wait_until "$WAIT_S_MAX" "$ncm_interface to come back (round $round)" ncm_active
    wait_until "$WAIT_S_MAX" "the internet through $ncm_interface (round $round)" internet_through_ncm
done
assert_no_reenumeration "$toggles_started" "$session"

step "switching to RNDIS is refused while the disk is exposed; NCM stays on"
[[ $(call TETHER_MODE --extra mode:s:rndis | jq -r .mode) == rndis ]] || fail "could not set the mode to RNDIS"
expect_type "$(call TETHER_ON)" TetherResult.Refused
tethered true || fail "a refused mode change ended tethering"
prefix_ready ncm.bootleg || fail "a refused mode change moved the prefix"

step "eject, then RNDIS on: a mode change (the prefix set up again with the RNDIS function)"
eject_disk
expect_type "$(call TETHER_ON)" TetherResult.Changed
wait_until "$PREFIX_WAIT_S_MAX" "the RNDIS prefix first" prefix_ready ffs.bootleg_rndis
wait_until "$WAIT_S_MAX" "the Mac to list RNDIS as interfaces 0-1 and HID as 2-3" mac_has_prefix
medium_is true || fail "the RNDIS medium is off with tethering on"

step "RNDIS off and on with the volume mounted: no re-enumeration"
expose_disk
toggles_started=$(date '+%Y-%m-%d %H:%M:%S')
session=$(usb_session)
expect_type "$(call TETHER_OFF)" TetherResult.Changed
medium_is false || fail "the RNDIS medium is on after tethering off"
tethered false || fail "still tethered: $(call TETHER_STATE)"
expect_type "$(call TETHER_ON)" TetherResult.Changed
medium_is true || fail "the RNDIS medium is off with tethering on"
expect_type "$(call TETHER_OFF)" TetherResult.Changed
tether_on=0
assert_no_reenumeration "$toggles_started" "$session"

step "eject, drives off, the prefix back in the phone's mode ($mode_before)"
eject_disk
if [[ $mode_before != rndis ]]; then
    call TETHER_MODE --extra "mode:s:$mode_before" >/dev/null
    tether_on=1
    expect_type "$(call TETHER_ON)" TetherResult.Changed
    expect_type "$(call TETHER_OFF)" TetherResult.Changed
    tether_on=0
    wait_until "$PREFIX_WAIT_S_MAX" "the $mode_before prefix first" prefix_ready ncm.bootleg
fi
wait_until "$WAIT_S_MAX" "USB adb to come back" usb_adb_up
step "PASS"
