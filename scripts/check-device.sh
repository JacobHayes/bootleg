#!/usr/bin/env bash
# L4: end-to-end check on the real phone, with this Mac as the USB host (SPEC.md, "Verification ladder").
#
# Exposes three tiny fixtures at once (ISO9660 as CD-ROM, FAT as read-only disk, FAT as read-write
# disk), checks them from macOS, writes on the Mac and verifies the write on the phone, checks that a
# host eject is never undone, survives a USB HAL mode change, then turns Bootleg off and checks that
# the gadget is back to its usual shape (bound, ADB linked, nothing of ours left).
#
# Rules it keeps: only formatted media is exposed (macOS shows a modal dialog for unreadable disks),
# no keystrokes are ever sent, volumes are ejected on the Mac before the phone drops them, and the
# device lock is always released. Everything that re-enumerates USB goes over wireless adb.
#
# Usage: scripts/check-device.sh [--skip-build]   (--skip-build installs the APK `check` already built)
#        (env: BOOTLEG_DEVICE_LOCK_HELD=1 when the caller already holds the device lock, which is then kept)
set -euo pipefail
cd "$(dirname "$0")/.."

USB_SERIAL=$(scripts/usb-serial.sh)
WIFI_SERIAL=${BOOTLEG_WIFI_SERIAL:-$(scripts/wifi-serial.sh)}
LOCK_DIR=/private/tmp/claude-501/bootleg-device.lock
FIXTURE_DIR=build/device-fixtures
PHONE_DIR=/sdcard/bootleg-l4        # where adb pushes (FUSE view)
KERNEL_DIR=/data/media/0/bootleg-l4 # the same files as root and the kernel see them
MOUNT_DIR=/data/local/tmp/bootleg-l4-mnt
GADGET=/config/usb_gadget/g1
PROVIDER=content://dev.jacobhayes.bootleg.debug
APK=app/build/outputs/apk/debug/app-debug.apk

LOCK_WAIT_S=300
VOLUME_WAIT_S=30
HAL_WAIT_S=15
ADB_WAIT_S=20
POLL_S=0.25
FAT_FIXTURE_SIZE_BYTES=16777216
VOLUMES=(BLCD BLRO BLRW)

skip_build=false
[[ ${1:-} == --skip-build ]] && skip_build=true

# ---------------------------------------------------------------------------------------------
# Plumbing: timing, failure, adb

now_ms() { local ns; ns=$(date +%s%N); echo $((ns / 1000000)); }
run_started_ms=$(now_ms)
run_started_at=$(date '+%Y-%m-%d %H:%M:%S')
summary=()
current_step=setup
have_lock=false

fail() {
    echo "FAIL [$current_step]: $*" >&2
    exit 1
}

# step NAME COMMAND...: runs one stage, timing it for the summary.
step() {
    local name=$1 started_ms elapsed_ms
    shift
    current_step=$name
    started_ms=$(now_ms)
    "$@"
    elapsed_ms=$(($(now_ms) - started_ms))
    summary+=("$(printf '  ok   %5d.%ds  %s' $((elapsed_ms / 1000)) $((elapsed_ms % 1000 / 100)) "$name")")
    echo "${summary[${#summary[@]} - 1]}"
}

# wait_until SECONDS DESCRIPTION COMMAND...: polls COMMAND until it succeeds or time runs out.
wait_until() {
    local timeout_s=$1 what=$2 deadline_ms
    shift 2
    deadline_ms=$(($(now_ms) + timeout_s * 1000))
    until "$@"; do
        (($(now_ms) < deadline_ms)) || fail "timed out after ${timeout_s}s waiting for $what"
        sleep "$POLL_S"
    done
}

uadb() { timeout --foreground --kill-after=5 120 adb -s "$USB_SERIAL" "$@"; }
# Wireless adb can hang on a dead transport: no call outlives the longest phone-side operation.
wadb() { timeout --foreground --kill-after=5 120 adb -s "$WIFI_SERIAL" "$@"; }
usb_adb_up() { [[ $(uadb get-state 2>/dev/null) == device ]]; }
wifi_adb_up() { [[ $(wadb get-state 2>/dev/null) == device ]]; }

# root SCRIPT: runs SCRIPT as root over wireless adb. SCRIPT must not contain single quotes.
root() { wadb shell "su -c '$1'"; }

# call METHOD [ARGS]: calls the debug control provider and prints its JSON; fails on an error reply.
call() {
    local out json
    out=$(wadb shell "content call --uri $PROVIDER --method $1 ${2:-}") || fail "$1: adb failed"
    json=${out#*json=}
    json=${json%\}]}
    jq -e 'has("error") | not' <<<"$json" >/dev/null || fail "$1: $(jq -r .error <<<"$json")"
    echo "$json"
}

# expect_applied METHOD [ARGS]: the call must return ApplyResult.Applied.
expect_applied() {
    local json
    json=$(call "$@")
    jq -e '.type | endswith(".Applied")' <<<"$json" >/dev/null || fail "$1 did not apply: $json"
}

sha256_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
phone_sha256() { uadb shell "sha256sum $1 2>/dev/null" | cut -d' ' -f1; }
is_mounted() { mount | grep -q " on /Volumes/$1 ("; }
is_unmounted() { ! is_mounted "$1"; }
disk_key() { diskutil info -plist "/Volumes/$1" | plutil -extract "$2" raw -o - -; }
desired_lun_paths() { jq -r '[.luns[].file] | join(" ")' <<<"$1"; }

# ---------------------------------------------------------------------------------------------
# Cleanup: always leaves the Mac without our volumes, Bootleg off and the lock released.

cleanup() {
    local status=$?
    trap - EXIT
    # Cleanup is bounded (every adb call is); a second signal must not stop it before the lock is released.
    trap '' INT TERM HUP
    if $have_lock; then
        for volume in "${VOLUMES[@]}"; do
            if is_mounted "$volume"; then diskutil eject "/Volumes/$volume" >/dev/null 2>&1 || true; fi
        done
        if wadb shell "su -c 'ls $GADGET/functions'" 2>/dev/null | grep -q bootleg; then
            wadb shell "content call --uri $PROVIDER --method OFF" >/dev/null 2>&1 || true
        fi
        wadb shell "su -c 'umount $MOUNT_DIR 2>/dev/null; rmdir $MOUNT_DIR 2>/dev/null; true'" >/dev/null 2>&1 || true
        [[ ${BOOTLEG_DEVICE_LOCK_HELD:-0} == 1 ]] || rmdir "$LOCK_DIR"
    fi
    local total_ms=$(($(now_ms) - run_started_ms))
    echo
    echo "L4 summary"
    printf '%s\n' ${summary[@]+"${summary[@]}"}
    if ((status == 0)); then
        printf 'PASS  %d.%ds total\n' $((total_ms / 1000)) $((total_ms % 1000 / 100))
    else
        printf 'FAIL  at "%s" after %d.%ds\n' "$current_step" $((total_ms / 1000)) $((total_ms % 1000 / 100))
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
trap 'exit 129' HUP
# set -e failures outside fail() still say what broke.
set -E
trap 'echo "FAIL [$current_step]: \"$BASH_COMMAND\" exited $?" >&2' ERR

# ---------------------------------------------------------------------------------------------
# Stages

take_lock() {
    if [[ ${BOOTLEG_DEVICE_LOCK_HELD:-0} == 1 ]]; then
        [[ -d $LOCK_DIR ]] || fail "BOOTLEG_DEVICE_LOCK_HELD=1 but $LOCK_DIR doesn't exist"
        have_lock=true
        return 0
    fi
    local deadline_ms=$(($(now_ms) + LOCK_WAIT_S * 1000))
    until mkdir "$LOCK_DIR" 2>/dev/null; do
        (($(now_ms) < deadline_ms)) || fail "device lock $LOCK_DIR still held after ${LOCK_WAIT_S}s"
        sleep 1
    done
    have_lock=true
}

install_app() {
    [[ $(uadb get-state 2>/dev/null) == device ]] || fail "phone $USB_SERIAL is not connected over USB"
    if $skip_build; then
        [[ -f $APK ]] || fail "$APK is missing; run without --skip-build"
        uadb install -r -t "$APK" >/dev/null
    else
        ANDROID_SERIAL=$USB_SERIAL ./gradlew --console=plain -q :app:installDebug >/dev/null
    fi
    sh scripts/grant-root.sh "$USB_SERIAL" >/dev/null
}

# Deterministic content, built once and cached; only images that are missing get rebuilt.
make_fat_image() {
    local label=$1 output=$2 temp="$FIXTURE_DIR/tmp-$1"
    rm -f "$temp.dmg" "$temp.cdr"
    hdiutil create -quiet -size 16m -fs MS-DOS -volname "$label" -layout MBRSPUD -srcfolder "$FIXTURE_DIR/content" "$temp.dmg"
    hdiutil convert -quiet "$temp.dmg" -format UDTO -o "$temp"
    mv "$temp.cdr" "$output"
    rm -f "$temp.dmg"
}

# A cached image may come from an older script or another check: it must be the 16 MiB image that
# holds random.bin (its FAT short-name directory entry), or it is rebuilt.
fat_fixture_ok() {
    [[ -f $1 ]] && [[ $(stat -f %z "$1") == "$FAT_FIXTURE_SIZE_BYTES" ]] && LC_ALL=C grep -q -a 'RANDOM  BIN' "$1"
}

build_fixtures() {
    mkdir -p "$FIXTURE_DIR/content"
    if [[ ! -f $FIXTURE_DIR/content/random.bin ]]; then
        echo "hello from bootleg" >"$FIXTURE_DIR/content/hello.txt"
        # 1 MiB of AES-CTR keystream: incompressible and identical on every Mac.
        head -c 1048576 /dev/zero |
            openssl enc -aes-128-ctr -nosalt -K 000102030405060708090a0b0c0d0e0f -iv 00000000000000000000000000000000 >"$FIXTURE_DIR/content/random.bin.tmp"
        mv "$FIXTURE_DIR/content/random.bin.tmp" "$FIXTURE_DIR/content/random.bin"
    fi
    [[ -f $FIXTURE_DIR/cd.iso ]] ||
        hdiutil makehybrid -quiet -iso -joliet -default-volume-name BLCD -o "$FIXTURE_DIR/cd.iso" "$FIXTURE_DIR/content"
    fat_fixture_ok "$FIXTURE_DIR/ro.img" || make_fat_image BLRO "$FIXTURE_DIR/ro.img"
    fat_fixture_ok "$FIXTURE_DIR/rw.img" || make_fat_image BLRW "$FIXTURE_DIR/rw.img"
    local name
    for name in ro.img rw.img; do
        fat_fixture_ok "$FIXTURE_DIR/$name" || fail "the freshly built $name is not a 16 MiB image holding random.bin"
    done
}

# rw.img changes on every run (the Mac writes to it), so it is pushed fresh each time.
push_fixtures() {
    uadb shell "mkdir -p $PHONE_DIR"
    local name
    for name in cd.iso ro.img rw.img; do
        if [[ $(phone_sha256 "$PHONE_DIR/$name") != "$(sha256_of "$FIXTURE_DIR/$name")" ]]; then
            uadb push -q "$FIXTURE_DIR/$name" "$PHONE_DIR/$name" >/dev/null
            [[ $(phone_sha256 "$PHONE_DIR/$name") == "$(sha256_of "$FIXTURE_DIR/$name")" ]] || fail "push of $name is corrupt"
        fi
    done
}

preflight() {
    wifi_adb_up || timeout --foreground --kill-after=5 30 adb connect "$WIFI_SERIAL" >/dev/null
    wait_until 10 "wireless adb at $WIFI_SERIAL" wifi_adb_up
    local volume state paths
    for volume in "${VOLUMES[@]}"; do
        is_unmounted "$volume" || fail "/Volumes/$volume is already mounted; eject it first"
    done
    state=$(call STATE)
    paths=$(desired_lun_paths "$state")
    if [[ -n $paths && $paths != *"$KERNEL_DIR/"* ]]; then
        fail "Bootleg is exporting someone's media ($paths); turn it off before running L4"
    fi
    # Leftovers from an interrupted run: media in a LUN, or Bootleg's mass storage function at all
    # (it only exists while a slot is exposed).
    if [[ -n ${paths// /} ]] || jq -e '.functions | index("mass_storage.bootleg")' <<<"$state" >/dev/null; then
        expect_applied OFF
    fi
}

apply_three_luns() {
    local desired
    desired=$(jq -cn --arg dir "$KERNEL_DIR" '{slots: [
        {lun: 0, backingPath: "\($dir)/cd.iso", mode: "CDROM"},
        {lun: 1, backingPath: "\($dir)/ro.img", mode: "DISK_RO"},
        {lun: 2, backingPath: "\($dir)/rw.img", mode: "DISK_RW"}]}')
    expect_applied APPLY "--arg '$desired'"
    local volume
    for volume in "${VOLUMES[@]}"; do
        wait_until "$VOLUME_WAIT_S" "/Volumes/$volume to mount" is_mounted "$volume"
    done
}

# expect_disk VOLUME FILESYSTEM WRITABLE: the volume's media matches the LUN mode.
expect_disk() {
    local volume=$1 filesystem=$2 writable=$3
    [[ $(disk_key "$volume" FilesystemType) == "$filesystem" ]] || fail "$volume: filesystem is not $filesystem"
    [[ $(disk_key "$volume" RemovableMedia) == true ]] || fail "$volume: media is not removable"
    [[ $(disk_key "$volume" WritableMedia) == "$writable" ]] || fail "$volume: WritableMedia is not $writable"
    [[ $(disk_key "$volume" WritableVolume) == "$writable" ]] || fail "$volume: WritableVolume is not $writable"
}

expect_content() {
    local volume=$1 name
    for name in hello.txt random.bin; do
        [[ $(sha256_of "/Volumes/$volume/$name") == "$(sha256_of "$FIXTURE_DIR/content/$name")" ]] ||
            fail "/Volumes/$volume/$name does not match the fixture"
    done
}

host_reads() {
    expect_disk BLCD cd9660 false
    expect_disk BLRO msdos false
    expect_disk BLRW msdos true
    local volume
    for volume in "${VOLUMES[@]}"; do expect_content "$volume"; done
}

written_sha256=
host_write_and_eject() {
    echo "written by L4 at $(date -u +%Y-%m-%dT%H:%M:%SZ)" >/Volumes/BLRW/written.txt
    written_sha256=$(sha256_of /Volumes/BLRW/written.txt)
    diskutil eject /Volumes/BLRW >/dev/null
    wait_until 5 "BLRW to disappear" is_unmounted BLRW
    # The host eject empties LUN 2; reconcile must record it and must not put the media back.
    expect_applied RECONCILE
    jq -e '.slotIntents["2"] == "HOST_EJECTED" and .slotIntents["0"] == "EXPORTED"' <<<"$(call JOURNAL)" >/dev/null ||
        fail "slot 2 is not HOST_EJECTED after a host eject"
    jq -e '.luns["2"].file == "" and .luns["1"].file != ""' <<<"$(call STATE)" >/dev/null ||
        fail "LUN 2 was re-inserted after reconcile"
}

# Mounts the RW image's partition read-only on the phone and compares the file the Mac wrote.
phone_sees_write() {
    local start_sector offset_bytes phone_hash
    start_sector=$(od -An -t u4 -j 454 -N 4 "$FIXTURE_DIR/rw.img" | tr -d ' ')
    offset_bytes=$((start_sector * 512))
    ((offset_bytes > 0)) || fail "rw.img has no MBR partition"
    # Unmounting usually auto-clears the loop device, so a failing `losetup -d` is fine.
    phone_hash=$(root "mkdir -p $MOUNT_DIR
        dev=\$(losetup -f -s -r -o $offset_bytes $KERNEL_DIR/rw.img) || exit 1
        mount -t vfat -o ro \$dev $MOUNT_DIR && sha256sum $MOUNT_DIR/written.txt
        umount $MOUNT_DIR 2>/dev/null; losetup -d \$dev 2>/dev/null; rmdir $MOUNT_DIR" | cut -d' ' -f1)
    [[ $phone_hash == "$written_sha256" ]] || fail "phone reads written.txt as '$phone_hash', the Mac wrote $written_sha256"
}

udc_bound() { [[ -n $(root "cat $GADGET/UDC") ]]; }
config_links() { root "ls -l $GADGET/configs/b.1" | awk '/ -> / { print $NF }'; }
mtp_linked() { config_links | grep -q 'ffs.mtp$'; }
mtp_unlinked() { ! mtp_linked; }

# The Pixel HAL rewrites configs/b.1 on a mode change; our links and the host eject must survive.
hal_mode_change() {
    # Eject on the Mac first so the re-enumeration never pulls a mounted volume.
    diskutil eject /Volumes/BLCD >/dev/null
    diskutil eject /Volumes/BLRO >/dev/null
    expect_applied RECONCILE
    wadb shell svc usb setFunctions mtp >/dev/null
    wait_until "$HAL_WAIT_S" "the HAL to link MTP" mtp_linked
    wadb shell svc usb setFunctions >/dev/null
    wait_until "$HAL_WAIT_S" "the HAL to drop MTP" mtp_unlinked
    local state
    state=$(call STATE)
    jq -e '.udc != "" and .configLinks["mass_storage.bootleg"] == "mass_storage.bootleg" and (.luns | length) == 3' \
        <<<"$state" >/dev/null || fail "Bootleg's link or LUNs did not survive the mode change: $state"
    jq -e '[.slotIntents[]] == ["HOST_EJECTED", "HOST_EJECTED", "HOST_EJECTED"]' <<<"$(call JOURNAL)" >/dev/null ||
        fail "a host-ejected slot changed state across the mode change"
}

# An explicit re-insert brings media back without re-enumerating.
reinsert_after_hal() {
    expect_applied REINSERT "--extra lun:i:0"
    expect_applied REINSERT "--extra lun:i:1"
    wait_until "$VOLUME_WAIT_S" "/Volumes/BLCD to mount again" is_mounted BLCD
    wait_until "$VOLUME_WAIT_S" "/Volumes/BLRO to mount again" is_mounted BLRO
    expect_content BLCD
    expect_content BLRO
    diskutil eject /Volumes/BLCD >/dev/null
    diskutil eject /Volumes/BLRO >/dev/null
}

bootleg_off() {
    expect_applied OFF
}

gadget_restored() {
    # The HAL bounces the UDC once more right after our final bind (adbd reopening FunctionFS).
    wait_until "$ADB_WAIT_S" "the UDC to be bound" udc_bound
    root "readlink $GADGET/configs/b.1/function0" | grep -q '/ffs.adb$' || fail "function0 no longer links ffs.adb"
    if root "ls $GADGET/functions $GADGET/configs/b.1" | grep -q bootleg; then fail "Bootleg functions or links left in configfs"; fi
    local name
    for name in cd.iso ro.img rw.img; do
        [[ -z $(root "losetup -j $KERNEL_DIR/$name") ]] || fail "a loop device still holds $name"
    done
    [[ -z $(root "ls -d $MOUNT_DIR 2>/dev/null") ]] || fail "$MOUNT_DIR was left behind"
    wait_until "$ADB_WAIT_S" "USB adb to come back" usb_adb_up
}

# A volume that vanished while mounted makes macOS show "Disk Not Ejected Properly". DiskArbitration
# logs every mount, unmount and removal, so any whole disk removed with a partition still mounted is one.
# The placeholder LUN (HID on, no disk) is an empty drive: never mounted, so its removal never counts.
no_surprise_removals() {
    local pulled
    pulled=$(/usr/bin/log show --start "$run_started_at" --style compact --predicate 'process == "diskarbitrationd" AND
            (eventMessage BEGINSWITH "mounted disk" OR eventMessage BEGINSWITH "unmounted disk" OR eventMessage BEGINSWITH "removed disk")' |
        awk '
            function disk_of(line) { sub(/.* id = \/dev\//, "", line); sub(/(s[0-9]+)?[,.].*/, "", line); return line }
            / mounted disk, .*success/ { mounted[disk_of($0)]++ }
            / unmounted disk, .*success/ { mounted[disk_of($0)]-- }
            / removed disk, id = \/dev\/disk[0-9]+\./ { disk = disk_of($0); if (mounted[disk] > 0) print disk; mounted[disk] = 0 }')
    [[ -z $pulled ]] || fail "macOS lost mounted volumes without an eject (on $(echo "$pulled" | tr '\n' ' '))"
}

# ---------------------------------------------------------------------------------------------

step "lock device" take_lock
step "build + install + grant root" install_app
step "fixtures (cached) + push" build_fixtures
step "push changed fixtures" push_fixtures
step "preflight: wireless adb, clean slate" preflight
step "apply CD-ROM + RO + RW, Mac mounts all" apply_three_luns
step "Mac reads: hashes, RO/RW, removable" host_reads
step "Mac writes + ejects RW, no re-insert" host_write_and_eject
step "phone sees the Mac's write" phone_sees_write
step "HAL mode change keeps links" hal_mode_change
step "re-insert after mode change" reinsert_after_hal
step "Bootleg off" bootleg_off
step "gadget restored, ADB back, no loops" gadget_restored
step "macOS never lost a mounted volume" no_surprise_removals
