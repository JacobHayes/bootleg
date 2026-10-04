#!/usr/bin/env bash
# L4 USB tethering check (~50s): with HID on and its session open, turn Android's USB tethering on
# through Bootleg (root closes every hidg fd before the HAL's re-plug and reopens nothing until it
# has settled), confirm no hidg fd crossed the re-plug and HID still types (F24), confirm the Mac gets the phone's NCM interface with a DHCP address and reaches the internet
# through it (curl bound to that interface), expose a read-only disk next to it (the Mac mounts it
# while the network and HID stay up), confirm Bootleg refuses to switch tethering while that disk is
# exposed, eject it on the Mac, turn tethering off, and put the phone back as it was.
#
# Needs the debug app installed (`mise run deploy`) and the Mac as the USB host. Uses wireless adb
# because every step re-plugs USB. Takes the device lock unless BOOTLEG_DEVICE_LOCK_HELD=1.
#
# Mac side: read-only commands plus `curl --interface`. macOS adds a "Pixel 6" network service the
# first time it sees the interface and may route through it while it exists; nothing here changes
# the service order or routes, and both return to normal once tethering is off.
#
# Usage: scripts/check-device-tether.sh   (env: BOOTLEG_WIFI_SERIAL, BOOTLEG_USB_SERIAL)
set -euo pipefail

cd "$(dirname "$0")/.."
WIFI_SERIAL=${BOOTLEG_WIFI_SERIAL:-$(scripts/wifi-serial.sh)}
USB_SERIAL=${BOOTLEG_USB_SERIAL:-$(scripts/usb-serial.sh)}
LOCK=/private/tmp/claude-501/bootleg-device.lock
LOCK_WAIT_S_MAX=120
INTERFACE_WAIT_S_MAX=20
VOLUME_WAIT_S_MAX=20
ADB_RETURN_WAIT_S_MAX=20
# One root setUsbTethering call is bounded at ~60s (ARCHITECTURE.md, USB tethering); a call this
# script started and then lost (killed adb) keeps running on the phone that long at most.
TETHER_SETTLE_WAIT_S_MAX=70
ADB_CALL_TIMEOUT_S=90
TETHER_MARKER=/data/adb/bootleg/tether-transition.json
CURL_TIMEOUT_S=10
CONNECTIVITY_URL=https://connectivitycheck.gstatic.com/generate_204
FIXTURE_DIR=build/device-fixtures
PHONE_DIR=/sdcard/bootleg-l4
KERNEL_DIR=/data/media/0/bootleg-l4
# Its own name: check:device caches a different ro.img (with random.bin) in the same directories.
IMAGE=tether-ro.img
VOLUME=BLRO
started_s=$SECONDS

fail() {
    echo "check-device-tether: FAIL: $*" >&2
    exit 1
}
step() { echo "check-device-tether: [$((SECONDS - started_s))s] $*"; }
# Wireless adb can hang; no single call may outlive the longest phone-side operation.
wadb() { timeout --foreground --kill-after=5 "$ADB_CALL_TIMEOUT_S" adb -s "$WIFI_SERIAL" "$@"; }

# Debug provider call; prints the JSON payload.
call() {
    local method=$1
    shift
    wadb shell content call --uri content://dev.jacobhayes.bootleg.debug --method "$method" "$@" |
        tr -d '\r' | sed -n 's/^Result: Bundle\[{json=\(.*\)}\]$/\1/p'
}

# wait_until SECONDS DESCRIPTION COMMAND...: polls COMMAND until it succeeds or time runs out.
wait_until() {
    local seconds=$1 description=$2
    shift 2
    local deadline=$((SECONDS + seconds))
    until "$@"; do
        [[ $SECONDS -lt $deadline ]] || fail "timed out after ${seconds}s waiting for $description"
        sleep 0.5
    done
}

is_mounted() { mount | grep -q " on /Volumes/$1 ("; }
lun0_empty() { [[ $(call STATE | jq -r '.luns["0"].file // ""') == "" ]]; }
sha256_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
phone_sha256() { wadb shell "sha256sum $1 2>/dev/null" | cut -d' ' -f1; }
tethered() { [[ $(call TETHER_STATE | jq -r .tethered) == true ]]; }
usb_adb_up() { [[ $(timeout --foreground --kill-after=5 10 adb -s "$USB_SERIAL" get-state 2>/dev/null) == device ]]; }
# Root's transition marker exists while a tethering change may still re-plug USB.
tether_settled() { [[ $(wadb shell "su -c 'test -e $TETHER_MARKER && echo pending || echo settled'" | tr -d '\r') == settled ]]; }
# A hidg fd open across a rebind panics the kernel once closed; root never leaves one, so none may show as deleted.
no_stale_hidg_fds() {
    local stale
    stale=$(wadb shell "su -c 'ls -l /proc/*/fd 2>/dev/null'" | tr -d '\r' | grep 'hidg.*(deleted)' || true)
    [[ -z $stale ]] || fail "a /dev/hidg fd crossed the re-plug: $stale"
}
hid_types() {
    local sent
    sent=$(call HID_SEND --extra sequence:s:f24)
    [[ $(jq -r '.queued == 2 and .written >= 2 and .dropped == 0' <<<"$sent") == true ]] || fail "HID_SEND f24 after the re-plug: $sent"
}
links_have_hid() { jq -e 'index("hid.bootleg_kbd") != null and index("hid.bootleg_mouse_wheel") != null' <<<"$1" >/dev/null; }

# The phone's interface on the Mac: one that wasn't there before tethering, with an IPv4 address.
ncm_interface=
find_ncm_interface() {
    local name
    for name in $(ifconfig -l); do
        [[ " $interfaces_before " == *" $name "* ]] && continue
        if ifconfig "$name" 2>/dev/null | grep -q 'inet [0-9]'; then
            ncm_interface=$name
            return 0
        fi
    done
    return 1
}
interface_gone() { ! ifconfig "$ncm_interface" >/dev/null 2>&1 || ! ifconfig "$ncm_interface" | grep -q 'inet [0-9]'; }
internet_through_ncm() {
    [[ $(curl -sS --interface "$ncm_interface" -m "$CURL_TIMEOUT_S" -o /dev/null -w '%{http_code}' "$CONNECTIVITY_URL") == 204 ]]
}

desired_json() { # $1: slots JSON array
    jq -cn --argjson slots "$1" --argjson hid "$hid_spec" '{slots: $slots, hid: $hid}'
}
expect_type() { # $1: JSON, $2: expected result type suffix
    [[ $(jq -r .type <<<"$1") == *"$2" ]] || fail "expected $2, got $1"
}

lock_taken=0
tether_on=0
volume_seen=0
disk_exposed=0
hid_before=
hid_spec=

# Puts HID back as it was before the check (HidController closes its session before removing
# functions) and reads it back. Returns 1 if it differs afterwards.
restore_hid() {
    [[ -n $hid_before ]] || return 0
    if [[ $(call HID_STATE | jq -c '{keyboard, mouse}') != "$hid_before" ]]; then
        if [[ $(jq -r '.keyboard or .mouse' <<<"$hid_before") == true ]]; then
            call HID_ENABLE --extra "keyboard:b:$(jq -r .keyboard <<<"$hid_before")" --extra "mouse:b:$(jq -r .mouse <<<"$hid_before")" >/dev/null
        else
            call HID_OFF >/dev/null
        fi
    fi
    [[ $(call HID_STATE | jq -c '{keyboard, mouse}') == "$hid_before" ]] || return 1
    hid_before=
}

cleanup() {
    local status=$?
    trap - EXIT
    # Cleanup is bounded (every adb call is); a second signal must not stop it before the lock is released.
    trap '' INT TERM HUP
    # Nothing here may change the gadget while a tethering change this script started is still
    # re-plugging USB (root serializes it anyway; this keeps the script's own order honest).
    if [[ $tether_on == 1 ]] && ! wait_quietly "$TETHER_SETTLE_WAIT_S_MAX" tether_settled; then
        echo "check-device-tether: WARN: a tethering change is still in flight after ${TETHER_SETTLE_WAIT_S_MAX}s; left drives, tethering and HID as they are" >&2
        if [[ $lock_taken == 1 ]]; then rmdir "$LOCK"; fi
        exit 1
    fi
    # Removing the disk is safe only once the Mac has ejected it (the phone sees LUN 0 emptied:
    # macOS holds the medium even after an unmount), or if the Mac never mounted it.
    local volume_released=1
    if is_mounted "$VOLUME"; then
        volume_seen=1
        diskutil eject "/Volumes/$VOLUME" >/dev/null 2>&1 || true
    fi
    if [[ $volume_seen == 1 ]]; then
        local deadline=$((SECONDS + VOLUME_WAIT_S_MAX))
        until lun0_empty || [[ $SECONDS -ge $deadline ]]; do sleep 0.5; done
        lun0_empty || volume_released=0
    fi
    if [[ $disk_exposed == 1 ]]; then
        if [[ $volume_released == 1 ]]; then
            # Keeps HID exactly as it is: dropping a HID function here could leave its node open.
            call APPLY --arg "'$(desired_json '[]')'" >/dev/null || echo "check-device-tether: WARN: drives off failed" >&2
        else
            echo "check-device-tether: WARN: /Volumes/$VOLUME is still mounted; left the disk exposed (eject it, then turn drives off)" >&2
        fi
    fi
    # Tethering off and a HID change re-plug USB too, which would pull the still-mounted volume.
    if [[ $volume_released == 0 ]]; then
        echo "check-device-tether: WARN: left tethering and HID as they are while the disk is exposed" >&2
        if [[ $lock_taken == 1 ]]; then rmdir "$LOCK"; fi
        exit 1
    fi
    if [[ $tether_on == 1 ]] && tethered; then
        echo "check-device-tether: cleanup: USB tethering off" >&2
        call TETHER_OFF >/dev/null || echo "check-device-tether: WARN: tethering off failed; turn it off in the app" >&2
    fi
    if ! restore_hid; then
        echo "check-device-tether: WARN: HID is not back to $hid_before; fix it in the Control tab" >&2
        [[ $status != 0 ]] || status=1
    fi
    if [[ $lock_taken == 1 ]]; then rmdir "$LOCK"; fi
    exit "$status"
}
# wait_quietly SECONDS COMMAND...: like wait_until, but returns 1 on timeout instead of failing (for cleanup).
wait_quietly() {
    local deadline=$((SECONDS + $1))
    shift
    until "$@"; do
        [[ $SECONDS -lt $deadline ]] || return 1
        sleep 0.5
    done
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

if [[ ${BOOTLEG_DEVICE_LOCK_HELD:-0} != 1 ]]; then
    waited=0
    until mkdir "$LOCK" 2>/dev/null; do
        [[ $waited -lt $LOCK_WAIT_S_MAX ]] || fail "device lock $LOCK still held after ${LOCK_WAIT_S_MAX}s"
        sleep 1
        waited=$((waited + 1))
    done
    lock_taken=1
fi

[[ $(wadb shell dumpsys window | tr -d '\r' | grep -o 'isKeyguardShowing=[a-z]*' | head -1) == isKeyguardShowing=false ]] ||
    fail "the phone is locked; unlock it first"
command -v jq >/dev/null || fail "jq is required"

# Preflight: tethering off, no media exposed, a formatted RO fixture on the phone.
tethered && fail "USB tethering is already on; turn it off first"
state=$(call STATE)
[[ $(jq '[.luns[] | select(.file != "")] | length' <<<"$state") == 0 ]] || fail "disks are exposed; eject them first: $state"
is_mounted "$VOLUME" && fail "/Volumes/$VOLUME is already mounted; eject it first"
# Never expose unformatted media: a cached fixture must still be the 16 MiB MBR + FAT image.
if [[ -f $FIXTURE_DIR/$IMAGE ]] && ! { [[ $(stat -f %z "$FIXTURE_DIR/$IMAGE") == 16777216 ]] &&
    file -b "$FIXTURE_DIR/$IMAGE" | grep -q 'DOS/MBR boot sector'; }; then
    rm -f "$FIXTURE_DIR/$IMAGE"
fi
if [[ ! -f $FIXTURE_DIR/$IMAGE ]]; then
    mkdir -p "$FIXTURE_DIR/tether-content"
    echo "hello from bootleg" >"$FIXTURE_DIR/tether-content/hello.txt"
    rm -f "$FIXTURE_DIR/tmp-tether.dmg" "$FIXTURE_DIR/tmp-tether.cdr"
    hdiutil create -quiet -size 16m -fs MS-DOS -volname "$VOLUME" -layout MBRSPUD -srcfolder "$FIXTURE_DIR/tether-content" "$FIXTURE_DIR/tmp-tether.dmg"
    hdiutil convert -quiet "$FIXTURE_DIR/tmp-tether.dmg" -format UDTO -o "$FIXTURE_DIR/tmp-tether"
    mv "$FIXTURE_DIR/tmp-tether.cdr" "$FIXTURE_DIR/$IMAGE"
    rm -f "$FIXTURE_DIR/tmp-tether.dmg"
fi
if [[ $(phone_sha256 "$PHONE_DIR/$IMAGE") != "$(sha256_of "$FIXTURE_DIR/$IMAGE")" ]]; then
    wadb shell "mkdir -p $PHONE_DIR"
    wadb push -q "$FIXTURE_DIR/$IMAGE" "$PHONE_DIR/$IMAGE" >/dev/null
fi
hid_before=$(call HID_STATE | jq -c '{keyboard, mouse}')
interfaces_before=$(ifconfig -l)

step "HID on (keyboard + mouse)"
[[ $(call HID_ENABLE --extra keyboard:b:true --extra mouse:b:true) == *ApplyResult.Applied* ]] || fail "HID_ENABLE failed"
hid_spec='{"keyboard":true,"mouse":true}'

step "HID session open before the re-plug"
hid_types
step "USB tethering on"
tether_on=1
result=$(call TETHER_ON)
expect_type "$result" TetherResult.Changed
[[ $(jq -r .active <<<"$result") == true ]] || fail "tethering not active after TETHER_ON: $result"
[[ $(jq -r .reconcile.type <<<"$result") == *ApplyResult.Applied ]] || fail "reconcile after tethering on: $result"
tether_settled || fail "TETHER_ON returned with the tethering change still pending"
links=$(call STATE | jq -c '.configLinks | [.[]] | sort')
jq -e 'index("ffs.adb") != null' <<<"$links" >/dev/null || fail "ffs.adb not linked next to tethering: $links"
links_have_hid "$links" || fail "HID not linked next to tethering: $links"
jq -e '[.[] | select(startswith("ncm."))] | length == 1' <<<"$links" >/dev/null || fail "no NCM function linked: $links"
no_stale_hidg_fds
hid_types
step "HID survived the re-plug: no hidg fd crossed it, and F24 reached the host"

wait_until "$INTERFACE_WAIT_S_MAX" "the Mac's NCM interface to get an address" find_ncm_interface
step "Mac: $ncm_interface $(ifconfig "$ncm_interface" | grep -o 'inet [0-9.]*')"
wait_until "$INTERFACE_WAIT_S_MAX" "internet through $ncm_interface" internet_through_ncm
step "Mac reaches the internet through $ncm_interface"

step "expose a read-only disk next to tethering and HID"
disk_exposed=1
result=$(call APPLY --arg "'$(desired_json "[{\"lun\":0,\"backingPath\":\"$KERNEL_DIR/$IMAGE\",\"mode\":\"DISK_RO\"}]")'")
expect_type "$result" ApplyResult.Applied
wait_until "$VOLUME_WAIT_S_MAX" "/Volumes/$VOLUME to mount" is_mounted "$VOLUME"
volume_seen=1
[[ $(cat "/Volumes/$VOLUME/hello.txt") == "hello from bootleg" ]] || fail "/Volumes/$VOLUME/hello.txt is wrong"
links=$(call STATE | jq -c '.configLinks | [.[]] | sort')
for function in ffs.adb mass_storage.bootleg; do
    jq -e --arg f "$function" 'index($f) != null' <<<"$links" >/dev/null || fail "$function not linked: $links"
done
links_have_hid "$links" || fail "HID not linked next to the disk: $links"
jq -e '[.[] | select(startswith("ncm."))] | length == 1' <<<"$links" >/dev/null || fail "NCM link lost by the disk change: $links"
wait_until "$INTERFACE_WAIT_S_MAX" "internet through $ncm_interface after the disk re-plug" internet_through_ncm
step "disk, network and HID all on the Mac at once"

step "switching tethering is refused while the disk is exposed"
result=$(call TETHER_OFF)
expect_type "$result" TetherResult.Refused
[[ $(jq -c .exposedLuns <<<"$result") == "[0]" ]] || fail "refusal names the wrong LUNs: $result"
tethered || fail "tethering changed although it was refused"

step "eject on the Mac, then tethering off"
diskutil eject "/Volumes/$VOLUME" >/dev/null
wait_until "$VOLUME_WAIT_S_MAX" "the phone to see the Mac's eject" lun0_empty
result=$(call TETHER_OFF)
expect_type "$result" TetherResult.Changed
[[ $(jq -r .active <<<"$result") == false ]] || fail "tethering still active after TETHER_OFF: $result"
tether_on=0
wait_until "$INTERFACE_WAIT_S_MAX" "$ncm_interface to go away" interface_gone
links=$(call STATE | jq -c '.configLinks | [.[]] | sort')
jq -e '[.[] | select(startswith("ncm."))] | length == 0' <<<"$links" >/dev/null || fail "NCM still linked: $links"
no_stale_hidg_fds
hid_types

step "drives off; HID back as it was"
expect_type "$(call APPLY --arg "'$(desired_json '[]')'")" ApplyResult.Applied
disk_exposed=0
restore_hid || fail "HID did not go back to $hid_before"
wait_until "$ADB_RETURN_WAIT_S_MAX" "USB adb to come back" usb_adb_up
step "PASS"
