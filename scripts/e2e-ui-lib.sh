#!/usr/bin/env bash
# Shared plumbing for the scripts/e2e-ui-*.sh flows: they drive the real app through its UI
# (scripts/ui.sh: uiautomator + input tap), then check the outcome on the phone (root, the debug
# provider's read-only STATE/JOURNAL/HID_STATE/REMOTE_STATE) and on this Mac (diskutil, hidutil).
#
# Each flow takes the device lock for its whole run and releases it in an EXIT trap, together with
# the flow's own cleanup (registered with `on_cleanup`). Rules kept: only harmless input reaches the
# Mac (F20-F24 and Shift taps; the touchpad and the Control tab's typing field are never touched),
# only formatted media is exposed, the Mac ejects a volume before the phone drops it, the phone is
# never locked or rebooted. Source it; don't run it.
#
# Environment:
#   BOOTLEG_SHOTS_DIR      where screenshots go (default build/e2e-shots/<flow>)
#   BOOTLEG_WIFI_SERIAL / BOOTLEG_USB_SERIAL   override the detected adb serials
#   BOOTLEG_DEVICE_LOCK_HELD=1                 the caller already holds the lock

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

FLOW=${FLOW:?set FLOW before sourcing e2e-ui-lib.sh}
LOCK_DIR=/private/tmp/claude-501/bootleg-device.lock
LOCK_WAIT_S=600
PACKAGE=dev.jacobhayes.bootleg
ACTIVITY=$PACKAGE/.MainActivity
PROVIDER=content://dev.jacobhayes.bootleg.debug
STORE=/data/media/0/bootleg
POLL_S=0.5

export BOOTLEG_SHOTS_DIR=${BOOTLEG_SHOTS_DIR:-build/e2e-shots/$FLOW}
USB_SERIAL_WAIT_S=30

# USB adb is briefly gone while another run re-enumerates the gadget: retry for a while.
find_usb_serial() {
    local waited=0 serial
    until serial=$(scripts/usb-serial.sh 2>/dev/null); do
        ((waited < USB_SERIAL_WAIT_S)) || { scripts/usb-serial.sh; return 1; }
        sleep 1
        waited=$((waited + 1))
    done
    echo "$serial"
}
USB_SERIAL=$(find_usb_serial)
WIFI_SERIAL=${BOOTLEG_WIFI_SERIAL:-$(scripts/wifi-serial.sh "$USB_SERIAL")}
# UI and checks go over wireless adb by default: USB adb drops while the gadget re-enumerates.
# Flows that never re-enumerate but may disturb Wi-Fi (the hotspot) set ADB_TRANSPORT=usb.
if [[ ${ADB_TRANSPORT:-wifi} == usb ]]; then
    export ANDROID_SERIAL=${ANDROID_SERIAL:-$USB_SERIAL}
else
    export ANDROID_SERIAL=${ANDROID_SERIAL:-$WIFI_SERIAL}
fi

# With pipefail, `producer | grep -q` fails whenever grep exits at the first match and the producer
# then dies of SIGPIPE. This grep reads all of its input and discards the output instead of -q.
grep() {
    local quiet=false arguments=() argument
    for argument in "$@"; do
        case $argument in
            -q) quiet=true ;;
            -q?*) quiet=true arguments+=("-${argument#-q}") ;;
            *) arguments+=("$argument") ;;
        esac
    done
    if $quiet; then command grep "${arguments[@]}" >/dev/null; else command grep "${arguments[@]}"; fi
}

passes=()
failures=()
cleanups=()
last_check_ok=true
have_lock=false
current_step=setup

log() { echo "[$FLOW] $*"; }

# check NAME COMMAND...: records a pass or a failure and carries on (the flow keeps going so one
# run reports every finding).
check() {
    local name=$1
    shift
    if "$@"; then
        last_check_ok=true
        passes+=("$name")
        log "  ok    $name"
    else
        last_check_ok=false
        failures+=("$name")
        log "  FAIL  $name"
        shot "fail-$(tr -c 'A-Za-z0-9' '-' <<<"$name" | cut -c1-40)" >/dev/null 2>&1 || true
    fi
}

# require NAME COMMAND...: like check, but stops the flow (cleanup still runs) when it fails.
require() {
    check "$@"
    $last_check_ok || die "required step failed: $1"
}

die() {
    log "ABORT [$current_step]: $*" >&2
    exit 1
}

step() {
    current_step=$1
    log "== $1"
}

# on_cleanup COMMAND: run at exit, last registered first.
on_cleanup() { cleanups=("$1" "${cleanups[@]}"); }

finish() {
    local status=$? cleanup
    trap - EXIT INT TERM
    for cleanup in "${cleanups[@]}"; do
        log "cleanup: $cleanup"
        eval "$cleanup" || log "WARN cleanup failed: $cleanup"
    done
    if $have_lock; then rmdir "$LOCK_DIR" && log "device lock released"; fi
    echo
    log "${#passes[@]} passed, ${#failures[@]} failed (screenshots in $BOOTLEG_SHOTS_DIR)"
    for failure in "${failures[@]}"; do log "  FAILED: $failure"; done
    if ((status == 0 && ${#failures[@]} > 0)); then status=1; fi
    log "$([[ $status == 0 ]] && echo PASS || echo FAIL)"
    exit "$status"
}
set -E
# Name the command that stopped the flow (only at top level: subshells and $(...) fail on purpose).
trap '((BASH_SUBSHELL == 0)) && log "ERROR: \`$BASH_COMMAND\` failed (line $LINENO, step: $current_step)" >&2' ERR
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

take_lock() {
    [[ ${BOOTLEG_DEVICE_LOCK_HELD:-0} == 1 ]] && return 0
    local waited=0
    until mkdir "$LOCK_DIR" 2>/dev/null; do
        ((waited < LOCK_WAIT_S)) || die "device lock $LOCK_DIR still held after ${LOCK_WAIT_S}s"
        ((waited % 30 == 0)) && log "waiting for the device lock..."
        sleep 1
        waited=$((waited + 1))
    done
    have_lock=true
    log "device lock taken"
}

# --- phone -----------------------------------------------------------------------------------------

ui() { scripts/ui.sh "$@"; }
shot() { ui shot "$1"; }
root() { adb shell "su -c '$1'" | tr -d '\r'; }

# call METHOD [ARGS...]: the debug provider (read-only methods in checks; OFF/HID_OFF/REMOTE_OFF in cleanup).
call() {
    adb shell content call --uri "$PROVIDER" --method "$@" | tr -d '\r' | sed -n 's/^Result: Bundle\[{json=\(.*\)}\]$/\1/p'
}

launch_app() {
    adb shell am start -W -n "$ACTIVITY" >/dev/null
    # A sheet or dialog left open by an earlier run hides the tabs from uiautomator.
    if ui has "Close sheet"; then ui tap "Close sheet"; sleep 0.8; fi
    ui wait Drives 15
}

restart_app() {
    adb shell am force-stop "$PACKAGE"
    launch_app
}

tab() {
    ui tap "$1"
    sleep 0.8
}

# wait_until TIMEOUT_S COMMAND...: polls COMMAND until it succeeds; false on timeout.
wait_until() {
    local timeout_s=$1 deadline
    shift
    deadline=$((SECONDS + timeout_s))
    until "$@"; do
        ((SECONDS < deadline)) || return 1
        sleep "$POLL_S"
    done
}

has_text() { ui has "$1"; }
not_text() { ! ui has "$1"; }

# more_after TEXT: taps the "More" (overflow) button of the card that shows TEXT (Library images,
# Drives slots: the card's More comes after its title).
more_after() { tap_after "$1" More; }

# tap_after TEXT LABEL: taps the first LABEL node that comes after TEXT in screen order (exact
# matches only; uiautomator lists nodes top to bottom, so this finds TEXT's own button).
tap_after() {
    local index
    index=$(ui texts | awk -v name="$1" -v label="$2" '$0 == label { count++ } $0 == name { print count + 1; exit }')
    [[ -n $index ]] || return 1
    ui tap "$2" "$index"
    sleep 0.6
}

# delete_from_library NAME: Library -> More -> Delete -> confirm, if the image is listed.
delete_from_library() {
    local name=$1
    tab Library
    has_text "$name" || ui scroll-to "$name" 3 || return 0
    more_after "$name"
    ui tap Delete
    sleep 0.6
    ui tap Delete # the confirm dialog's button
    wait_until 10 not_text "$name"
}

# create_blank NAME SIZE FORMAT [preallocate]: Library -> Create blank image. SIZE is a preset
# label ("1 GB") or a custom size in GB ("0.064"); FORMAT is Raw, FAT32, exFAT or ext4. Only types
# into the dialog's own text fields (never the Control tab).
create_blank() {
    local name=$1 size=$2 format=$3 preallocate=${4:-}
    tab Library
    ui tap "Create blank image"
    ui wait "File name" 5
    ui tap blank.img
    sleep 0.4
    adb shell input keyevent KEYCODE_MOVE_END
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do adb shell input keyevent KEYCODE_DEL; done
    adb shell input text "$name"
    adb shell input keyevent KEYCODE_BACK # hide the keyboard
    sleep 0.4
    if [[ $size == *GB ]]; then
        ui tap "$size"
    else
        ui tap Custom
        sleep 0.4
        ui tap "Size in GB"
        sleep 0.3
        adb shell input text "$size"
        adb shell input keyevent KEYCODE_BACK
        sleep 0.4
    fi
    ui tap "$format"
    if [[ $preallocate == preallocate ]] && ! ui checked Preallocate; then ui toggle Preallocate; fi
    sleep 0.4
    shot "create-$name"
    ui tap Create
    ui wait "$name" 60
}

# add_slot IMAGE [MODE_LABEL]: Drives -> Add slot -> IMAGE, then the mode menu (CD-ROM,
# "Disk (read-only)", "Disk (read/write)"), confirming the writable warning.
add_slot() {
    local image=$1 mode=${2:-} chip
    tab Drives
    if has_text "Add slot"; then ui tap "Add slot" "$(ui texts | grep -c '^Add slot$')"; fi
    ui wait "Choose an image" 5
    ui tap "$image"
    sleep 0.8
    [[ -n $mode ]] || return 0
    # The newest slot's mode chip is the last "Auto · ..." chip.
    chip=$(ui texts | grep -E '^Auto · ' | tail -1)
    ui tap "$chip" "$(ui texts | grep -cxF "$chip")"
    sleep 0.6
    ui tap "$mode"
    sleep 0.6
    if has_text "Make writable"; then ui tap "Make writable"; fi
}

apply_drives() {
    ui tap Apply
    wait_until 30 not_text "Not applied"
}

gadget_clean() {
    local links
    links=$(root 'ls /config/usb_gadget/g1/configs/b.1/ /config/usb_gadget/g1/functions/')
    ! grep -q bootleg <<<"$links" && [[ -n $(root 'cat /config/usb_gadget/g1/UDC') ]]
}

exported_luns() { call STATE | jq '[.luns[] | select(.file != "")] | length'; }

# notification_field FIELD: the given extra (title, text) of each of this app's posted notifications.
notification_field() {
    adb shell dumpsys notification --noredact | tr -d '\r' |
        awk -v pkg="pkg=$PACKAGE " -v key="android.$1=String (" '
            /NotificationRecord\(/ { ours = index($0, pkg) > 0 }
            ours && index($0, key) { line = substr($0, index($0, key) + length(key)); sub(/\)$/, "", line); print line }'
}
notification_titles() { notification_field title; }

# shade_action_center LABEL: "x y" of a SystemUI (shade) node labelled LABEL; fails when the shade
# isn't the focused window (the app's own "Eject all" button must not count).
shade_action_center() {
    local bounds corners
    bounds=$(ui dump | xmllint --xpath "string((//node[@package=\"com.android.systemui\"][@text=\"$1\" or @content-desc=\"$1\"])[1]/@bounds)" - 2>/dev/null)
    [[ -n $bounds ]] || return 1
    read -r -a corners <<<"$(tr '[],' '   ' <<<"$bounds")"
    echo "$(((corners[0] + corners[2]) / 2)) $(((corners[1] + corners[3]) / 2))"
}

# shade_eject_all TITLE: opens the shade, expands the notification titled TITLE (the Expand chevron
# that follows it) and taps its "Eject all" action. False if the action never showed.
shade_eject_all() {
    local title=$1 point attempt
    # Finished-download notifications group with the drives one and hide its actions: clear every
    # dismissible notification first (the ongoing drives notification stays). The shade only
    # becomes the uiautomator window over the launcher, not over the app: go home first (callers
    # that need the app afterwards call launch_app).
    adb shell input keyevent KEYCODE_HOME
    sleep 1
    adb shell cmd statusbar expand-notifications
    sleep 1.5
    if point=$(shade_action_center "Clear all"); then
        # shellcheck disable=SC2086
        adb shell input tap $point
        sleep 1.5
    fi
    for attempt in 1 2 3; do
        adb shell cmd statusbar expand-notifications
        sleep 1.5
        point=$(shade_action_center "Eject all") && break
        tap_after "$title" Expand || true
        sleep 1
        point=$(shade_action_center "Eject all") && break
        log "    shade attempt $attempt: no Eject all action yet"
    done
    shot notification-expanded
    [[ -n $point ]] || return 1
    # shellcheck disable=SC2086
    adb shell input tap $point
    sleep 1.2
}

# exports_channel_ok: the drives notification channel is low importance and silent.
exports_channel_ok() {
    local channel
    channel=$(adb shell dumpsys notification --noredact | tr -d '\r' | grep -oE "NotificationChannel\{mId='exports'[^}]*" | head -1)
    [[ $channel == *"mImportance=2,"* && $channel == *"mSound=null"* && $channel == *"mVibrationEnabled=false"* ]]
}

# drives_off: Mac ejects anything of ours first, then the app's emergency teardown (debug hook).
drives_off() {
    mac_eject_all
    [[ $(call JOURNAL | jq '.desired.slots | length') == 0 ]] && gadget_clean && return 0
    call OFF >/dev/null
}

# --- Mac -------------------------------------------------------------------------------------------

mac_volume_present() { [[ -d /Volumes/$1 ]]; }
mac_volume_absent() { [[ ! -d /Volumes/$1 ]]; }

# mac_disk_of VOLUME: prints diskN for a mounted volume.
mac_disk_of() { diskutil info "/Volumes/$1" | awk '/Part of Whole:/ { print $4 }'; }

# Ejects every external physical disk that the phone's gadget presents (File-Stor Gadget).
mac_eject_all() {
    local disk
    for disk in $(diskutil list external physical | awk '/^\/dev\/disk/ { sub("/dev/", "", $1); print $1 }'); do
        if diskutil info "$disk" | grep -q 'Media Name:.*Gadget'; then
            diskutil eject "$disk" >/dev/null 2>&1 || log "WARN could not eject $disk (Mac screen locked?)"
        fi
    done
}

mac_screen_unlocked() { ioreg -n Root -d1 | grep -q '"IOConsoleLocked" = No'; }

host_hid_rows() {
    hidutil list 2>/dev/null | awk -v vid="$1" -v page="$2" -v usage="$3" 'tolower($1) == vid && $4 == page && $5 == usage && $7 == "USB"'
}

gadget_vendor_id() { root 'cat /config/usb_gadget/g1/idVendor' | tr 'A-F' 'a-f' | sed 's/^0x0*/0x/'; }
