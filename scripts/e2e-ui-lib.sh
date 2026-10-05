#!/usr/bin/env bash
# Shared plumbing for the UI end-to-end tests (scripts/e2e-ui.sh runs them; the tests live in
# scripts/e2e-ui/*.sh). They drive the real app through its UI (scripts/ui.sh: uiautomator +
# input tap), then check the outcome on the phone (root, the debug provider's read-only STATE/
# JOURNAL/HID_STATE/REMOTE_STATE) and on this Mac (diskutil, hidutil).
#
# Each test runs in its own subshell: `require` stops only that test, and the cleanups it
# registered with `on_cleanup` run when it ends, so it leaves the phone as it found it. Rules kept:
# only harmless input reaches the Mac (F20-F24 and Shift taps; the touchpad and the Control tab's
# typing field are never touched), only formatted media is exposed, the Mac ejects a volume before
# the phone drops it, the phone is never locked or rebooted. Source it; don't run it.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=scripts/ui.sh
source scripts/ui.sh

LOCK_DIR=/private/tmp/claude-501/bootleg-device.lock
LOCK_WAIT_S=600
PACKAGE=dev.jacobhayes.bootleg
ACTIVITY=$PACKAGE/.MainActivity
PROVIDER=content://dev.jacobhayes.bootleg.debug
STORE=/data/media/0/bootleg
POLL_S=0.25
USB_SERIAL_WAIT_S=30
APP_START_WAIT_S=15
LAUNCH_BACKS_MAX=3
TAB_SELECT_WAIT_S=5
# Negative checks ("the refused eject dropped nothing") give the app this long to do the wrong thing.
REFUSAL_SETTLE_S=1.5
SHADE_WAIT_S=5
DEVICE_SHOTS_DIR=/data/local/tmp/bootleg-e2e-shots

current_test=setup
current_step=setup
test_started_ms=0
passes=()
failures=()
cleanups=()
last_check_ok=true

now_ms() {
    local micros=${EPOCHREALTIME//[!0-9]/}
    echo $((10#$micros / 1000))
}

log() {
    local micros=${EPOCHREALTIME//[!0-9]/} elapsed_ms
    elapsed_ms=$((10#$micros / 1000 - test_started_ms))
    printf '[%s +%d.%ds] %s\n' "$current_test" $((elapsed_ms / 1000)) $((elapsed_ms % 1000 / 100)) "$*"
}

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

# check NAME COMMAND...: records a pass or a failure and carries on (the test keeps going so one
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

# require NAME COMMAND...: like check, but stops the test (its cleanup still runs) when it fails.
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

# on_cleanup COMMAND: run when the test ends, last registered first.
on_cleanup() { cleanups=("$1" "${cleanups[@]}"); }

# --- device lock and serials -----------------------------------------------------------------------

have_lock=false
take_lock() {
    [[ ${BOOTLEG_DEVICE_LOCK_HELD:-0} == 1 ]] && return 0
    local waited=0
    until mkdir "$LOCK_DIR" 2>/dev/null; do
        # The runner's INT/TERM trap only sets the flag, so the wait has to look at it.
        ! ${interrupted:-false} || die "interrupted while waiting for the device lock"
        ((waited < LOCK_WAIT_S)) || die "device lock $LOCK_DIR still held after ${LOCK_WAIT_S}s"
        ((waited % 30 == 0)) && log "waiting for the device lock..."
        sleep 1
        waited=$((waited + 1))
    done
    have_lock=true
    log "device lock taken"
}

release_lock() {
    if $have_lock; then rmdir "$LOCK_DIR" && log "device lock released"; fi
    have_lock=false
}

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

# use_transport wifi|usb: points adb (and the dump server's forward) at that transport. UI and
# checks go over wireless adb by default, as USB adb drops while the gadget re-enumerates; tests
# that never re-enumerate but may disturb Wi-Fi (the hotspot) use USB.
use_transport() {
    case $1 in
        wifi) export ANDROID_SERIAL=$WIFI_SERIAL UI_SERVER_PORT=${UI_PORT_WIFI:-} ;;
        usb) export ANDROID_SERIAL=$USB_SERIAL UI_SERVER_PORT=${UI_PORT_USB:-} ;;
        *) die "unknown transport $1" ;;
    esac
}

wifi_adb_ready() { [[ $(adb -s "$WIFI_SERIAL" get-state 2>/dev/null) == device ]]; }

# wifi_adb_ok: wireless adb answers, reconnecting once when the transport went offline (Wi-Fi blips).
wifi_adb_ok() {
    wifi_adb_ready && return 0
    log "wireless adb $WIFI_SERIAL is not ready; reconnecting"
    # A stale offline transport survives `adb disconnect`; `reconnect offline` drops it.
    adb reconnect offline >/dev/null 2>&1 || true
    adb connect "$WIFI_SERIAL" >/dev/null 2>&1 || true
    wait_until 10 wifi_adb_ready
}

# --- phone -----------------------------------------------------------------------------------------

ui() { ui_main "$@"; }
root() { adb shell "su -c '$1'" | tr -d '\r'; }

# shot NAME: a screenshot, captured on the phone now and pulled when the test ends (pulling a
# full-size PNG over Wi-Fi adb right away costs more than the capture).
shot() {
    # In the background: the capture starts right away, and the test doesn't wait for the PNG.
    adb shell "mkdir -p $DEVICE_SHOTS_DIR && screencap -p $DEVICE_SHOTS_DIR/$1.png" >/dev/null 2>&1 &
}

pull_shots() {
    local file
    wait # for the captures shot started
    mkdir -p "$BOOTLEG_SHOTS_DIR"
    adb pull "$DEVICE_SHOTS_DIR/." "$BOOTLEG_SHOTS_DIR/" >/dev/null 2>&1 || true
    adb shell "rm -rf $DEVICE_SHOTS_DIR" >/dev/null 2>&1 || true
    for file in "$BOOTLEG_SHOTS_DIR"/*.png; do
        [[ -f $file ]] && sips -Z 900 "$file" >/dev/null 2>&1
    done
    return 0
}

# call METHOD [ARGS...]: the debug provider (read-only methods in checks; OFF/HID_OFF/REMOTE_OFF in cleanup).
call() {
    ui_content call --uri "$PROVIDER" --method "$@" | tr -d '\r' | sed -n 's/^Result: Bundle\[{json=\(.*\)}\]$/\1/p'
}

phone_awake_and_unlocked() {
    if ! adb shell dumpsys power | grep -q 'mWakefulness=Awake'; then
        adb shell input keyevent KEYCODE_WAKEUP
        sleep 0.5
    fi
    adb shell dumpsys window | grep -q 'isKeyguardShowing=false'
}

launch_app() {
    local deadline=$((SECONDS + APP_START_WAIT_S)) xml backs=0
    adb shell am start -W -n "$ACTIVITY" >/dev/null
    while ((SECONDS < deadline)); do
        xml=$(ui_dump) || continue
        if ui_center_in "$xml" Drives >/dev/null && ui_center_in "$xml" Library >/dev/null; then
            return 0
        elif ui_center_in "$xml" "Close sheet" >/dev/null; then
            # A sheet left open by an earlier test hides the tabs from uiautomator.
            UI_TAP_WAIT_S=0 ui_tap "Close sheet" || true
        elif ((backs < LAUNCH_BACKS_MAX)) && [[ $xml == *"package=\"$PACKAGE\""* && $xml == *'text="'[A-Za-z]* ]]; then
            # One of our dialogs (a test stopped halfway through it): Back dismisses it. Only
            # with text on screen, so a cold start's blank window never gets a Back.
            adb shell input keyevent KEYCODE_BACK
            backs=$((backs + 1))
        fi
        sleep "$POLL_S"
    done
    log "app did not show its tabs within ${APP_START_WAIT_S}s"
    return 1
}

restart_app() {
    adb shell am force-stop "$PACKAGE"
    launch_app
}

# The navigation item that holds the tab's label is the selected node (the label itself never is).
tab_selected() { [[ -n $(ui_dump | xmllint --xpath "//node[@selected=\"true\"][.//node[@text=\"$1\"]]/@bounds" - 2>/dev/null) ]]; }

# tab NAME: taps the navigation tab (also when selected: that leaves a sub-view like Downloads) and
# waits until it is the selected one.
tab() {
    ui tap "$1"
    wait_until "$TAB_SELECT_WAIT_S" tab_selected "$1" || { log "tab $1 not selected after ${TAB_SELECT_WAIT_S}s"; return 1; }
    # Already selected, the tab stays selected while its sub-view leaves: wait for a still screen.
    wait_until "$TAB_SELECT_WAIT_S" screen_still || true
}

# screen_still: two dumps in a row show the same texts.
screen_still() {
    local before
    before=$(ui_texts)
    [[ $(ui_texts) == "$before" ]]
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

has_text() { ui_has "$1"; }
not_text() { ! ui_has "$1"; }

# more_after TEXT: taps the "More" (overflow) button of the card that shows TEXT (Library images,
# Drives slots: the card's More comes after its title).
more_after() { tap_after "$1" More; }

# tap_after TEXT LABEL: taps the first LABEL node that comes after TEXT in screen order (exact
# matches only; uiautomator lists nodes top to bottom, so this finds TEXT's own button).
tap_after() {
    local index
    index=$(ui_texts | awk -v name="$1" -v label="$2" '$0 == label { count++ } $0 == name { print count + 1; exit }')
    [[ -n $index ]] || return 1
    ui_tap "$2" "$index"
}

in_store() { [[ -n $(root "ls $STORE/$1 2>/dev/null") ]]; }

# delete_from_library NAME: Library -> More -> Delete -> confirm, if the image is in the store.
delete_from_library() {
    local name=$1
    in_store "$name" || return 0
    tab Library
    has_text "$name" || ui scroll-to "$name" 3 || return 0
    more_after "$name"
    ui tap Delete
    ui wait "Delete $name?" 5
    ui tap Delete # the confirm dialog's button (the menu is gone once the dialog shows)
    wait_until 10 not_text "$name"
}

ime_shown() { adb shell dumpsys input_method | grep -q 'mInputShown=true'; }
ime_hidden() { ! ime_shown; }
focused() { [[ $(ui_attr "$1" focused 2>/dev/null) == true ]]; }

# hide_keyboard: Back, only while the keyboard shows (otherwise Back would close the dialog).
hide_keyboard() {
    wait_until 2 ime_shown || return 0
    adb shell input keyevent KEYCODE_BACK
    wait_until 3 ime_hidden
}

BLANK_NAME_DELETES=12 # "blank.img" plus margin

# create_blank NAME SIZE FORMAT [preallocate]: Library -> Create blank image. SIZE is a preset
# label ("1 GB") or a custom size in GB ("0.064"); FORMAT is Raw, FAT32, exFAT or ext4. Only types
# into the dialog's own text fields (never the Control tab).
create_blank() {
    local name=$1 size=$2 format=$3 preallocate=${4:-} deletes=() count
    tab Library
    ui tap "Create blank image"
    ui wait "File name" 5
    ui tap blank.img
    wait_until 3 ime_shown
    for ((count = 0; count < BLANK_NAME_DELETES; count++)); do deletes+=(KEYCODE_DEL); done
    adb shell input keyevent KEYCODE_MOVE_END "${deletes[@]}"
    adb shell input text "$name"
    wait_until 3 has_text "$name"
    hide_keyboard
    if [[ $size == *GB ]]; then
        ui tap "$size"
    else
        ui tap Custom
        ui tap "Size in GB"
        wait_until 3 ime_shown
        adb shell input text "$size"
        wait_until 3 has_text "$size"
        hide_keyboard
    fi
    ui tap "$format"
    if [[ $preallocate == preallocate ]] && ! ui checked Preallocate; then
        ui toggle Preallocate
        wait_until 3 ui_checked Preallocate
    fi
    shot "create-$name"
    ui tap Create
    ui gone "File name" 10
    ui wait "$name" 60
}

# add_slot IMAGE [MODE_LABEL]: Drives -> Add slot -> IMAGE, then the mode menu (CD-ROM,
# "Disk (read-only)", "Disk (read/write)"), confirming the writable warning.
add_slot() {
    local image=$1 mode=${2:-} chip
    tab Drives
    if has_text "Add slot"; then ui tap "Add slot" "$(ui_texts | grep -c '^Add slot$')"; fi
    ui wait "Choose an image" 5
    ui tap "$image"
    ui gone "Choose an image" 5
    [[ -n $mode ]] || return 0
    # The newest slot's mode chip is the last "Auto · ..." chip.
    chip=$(ui_texts | grep -E '^Auto · ' | tail -1)
    ui tap "$chip" "$(ui_texts | grep -cxF "$chip")"
    ui tap "$mode"
    if [[ $mode == "Disk (read/write)" ]] && wait_until 3 has_text "Make writable"; then
        ui tap "Make writable"
        ui gone "Make writable" 5
    fi
}

apply_drives() {
    ui tap Apply
    wait_until 30 not_text "Not applied"
}

# remove_slot IMAGE: the slot's More -> Remove slot (a draft change; apply_drives applies it).
remove_slot() {
    more_after "$1"
    ui tap "Remove slot"
    ui gone "Remove slot" 5
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
    bounds=$(ui_dump | xmllint --xpath "string((//node[@package=\"com.android.systemui\"][@text=\"$1\" or @content-desc=\"$1\"])[1]/@bounds)" - 2>/dev/null)
    [[ -n $bounds ]] || return 1
    read -r -a corners <<<"${bounds//[\[\],]/ }"
    echo "$(((corners[0] + corners[2]) / 2)) $(((corners[1] + corners[3]) / 2))"
}

shade_shows_any() {
    local label
    for label in "$@"; do shade_action_center "$label" >/dev/null && return 0; done
    return 1
}
shade_is_window() { ui_dump | grep -q 'package="com.android.systemui"'; }
shade_gone() { ! shade_action_center "Clear all" >/dev/null; }

# shade_expand TITLE: taps the Expand chevron on the row of the notification titled TITLE (the
# chevron spans the notification's header and text, so its bounds hold the title's center). Tree
# order doesn't tell: depending on the layout the chevron comes before or after the title.
shade_expand() {
    local xml title_point title_y point
    xml=$(ui_dump) || return 1
    title_point=$(ui_center_in "$xml" "$1") || return 1
    title_y=${title_point#* }
    point=$(grep -oE '<node [^>]*content-desc="Expand"[^>]*package="com.android.systemui"[^>]*>|<node [^>]*package="com.android.systemui"[^>]*content-desc="Expand"[^>]*>' <<<"$xml" |
        awk -v y="$title_y" '{
            match($0, /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/)
            split(substr($0, RSTART + 8, RLENGTH - 9), n, /[^0-9]+/)
            if (n[3] <= y && y <= n[5]) { print int((n[2] + n[4]) / 2), int((n[3] + n[5]) / 2); exit }
        }')
    [[ -n $point ]] || return 1
    ui_forget_dump
    # shellcheck disable=SC2086
    adb shell input tap $point
}

# shade_eject_all TITLE: opens the shade, expands the notification titled TITLE and taps its
# "Eject all" action. False if the action never showed.
shade_eject_all() {
    local title=$1 point="" attempt
    # Finished-download notifications group with the drives one and hide its actions: clear every
    # dismissible notification first (the ongoing drives notification stays). The shade only
    # becomes the uiautomator window over the launcher, not over the app: go home first (callers
    # that need the app afterwards call launch_app).
    adb shell input keyevent KEYCODE_HOME
    adb shell cmd statusbar expand-notifications
    wait_until "$SHADE_WAIT_S" shade_is_window || true
    if point=$(shade_action_center "Clear all"); then
        # shellcheck disable=SC2086
        adb shell input tap $point
        wait_until "$SHADE_WAIT_S" shade_gone || true
    fi
    point=""
    for attempt in 1 2 3; do
        adb shell cmd statusbar expand-notifications
        # Collapsed, the notification shows its title but not the action: expand it right away.
        wait_until "$SHADE_WAIT_S" shade_shows_any "Eject all" "$title" || true
        point=$(shade_action_center "Eject all") && break
        shade_expand "$title" || true
        point=$(wait_until "$SHADE_WAIT_S" shade_action_center "Eject all") && break
        log "    shade attempt $attempt: no Eject all action yet"
    done
    shot notification-expanded
    [[ -n $point ]] || return 1
    # shellcheck disable=SC2086
    adb shell input tap $point
    sleep "$REFUSAL_SETTLE_S"
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

# The mount point shows up a moment before diskutil can resolve it; checks right after need both.
mac_volume_present() { [[ -d /Volumes/$1 ]] && diskutil info "/Volumes/$1" >/dev/null 2>&1; }
mac_volume_absent() { [[ ! -d /Volumes/$1 ]]; }

# mac_disk_of VOLUME: prints diskN for a mounted volume.
mac_disk_of() { diskutil info "/Volumes/$1" | awk '/Part of Whole:/ { print $4 }'; }

# Ejects every external physical disk that the phone's gadget presents (File-Stor Gadget). The empty
# placeholder drive (HID on, no disk) has no media to eject, so it is skipped.
mac_eject_all() {
    local disk info
    for disk in $(diskutil list external physical | awk '/^\/dev\/disk/ { sub("/dev/", "", $1); print $1 }'); do
        info=$(diskutil info "$disk") || continue
        if grep -q 'Media Name:.*Gadget' <<<"$info" && ! grep -qE 'Disk Size: +0 B' <<<"$info"; then
            diskutil eject "$disk" >/dev/null 2>&1 || log "WARN could not eject $disk (Mac screen locked?)"
        fi
    done
}

mac_screen_unlocked() { ioreg -n Root -d1 | grep -q '"IOConsoleLocked" = No'; }
require_mac_unlocked() { require "Mac screen unlocked (a locked Mac refuses ejects)" mac_screen_unlocked; }

host_hid_rows() {
    hidutil list 2>/dev/null | awk -v vid="$1" -v page="$2" -v usage="$3" 'tolower($1) == vid && $4 == page && $5 == usage && $7 == "USB"'
}

gadget_vendor_id() { root 'cat /config/usb_gadget/g1/idVendor' | tr 'A-F' 'a-f' | sed 's/^0x0*/0x/'; }

# --- fixtures shared by several tests (made on first use, kept on the phone for later runs) ---------

# shellcheck disable=SC2034 # used by the tests
ISO=netboot.xyz.iso ISO_VOLUME=iPXE FAT_FIXTURE=e2e-drives-fat.img FAT_VOLUME=BOOTLEG

# need_iso: netboot.xyz.iso in the Library (downloaded through the Catalog when missing; it stays).
need_iso() {
    in_store "$ISO" && return 0
    log "fixture: downloading $ISO through the Catalog"
    catalog_download netboot.xyz 'netboot\.xyz\.iso'
    require "fixture $ISO downloaded" wait_until "$CATALOG_DOWNLOAD_WAIT_S" download_verified "$ISO"
}

FIXTURE_HASH_DIR=/data/local/tmp/bootleg-e2e-fixtures

fixture_sha() { root "sha256sum $STORE/$1" | awk '{ print $1 }'; }
fixture_recorded_sha() { adb shell "cat $FIXTURE_HASH_DIR/$1.sha256 2>/dev/null" | tr -d '\r'; }

# need_fat_fixture: a 64 MB FAT32 blank in the Library, created through the UI and kept for later
# runs. Tests only expose it read-only; its hash, recorded on the phone when it is created, must
# still match, or it is deleted and created again.
need_fat_fixture() {
    local sha
    if in_store "$FAT_FIXTURE"; then
        sha=$(fixture_sha "$FAT_FIXTURE")
        [[ -n $sha && $sha == "$(fixture_recorded_sha "$FAT_FIXTURE")" ]] && return 0
        log "fixture: $FAT_FIXTURE changed or has no recorded hash; creating it again"
        require "stale fixture $FAT_FIXTURE deleted" delete_from_library "$FAT_FIXTURE"
    fi
    log "fixture: creating $FAT_FIXTURE"
    create_blank "$FAT_FIXTURE" 0.064 FAT32
    sha=$(fixture_sha "$FAT_FIXTURE")
    require "fixture $FAT_FIXTURE hashed" test -n "$sha"
    adb shell "mkdir -p $FIXTURE_HASH_DIR && echo $sha >$FIXTURE_HASH_DIR/$FAT_FIXTURE.sha256"
}

# --- catalog helpers (the catalog tests and need_iso) ------------------------------------------------

CATALOG_DOWNLOAD_WAIT_S=180

# record_status FILE: the status line of FILE's card in the Downloads view.
record_status() {
    ui_texts | awk -v file="$1" '
        found && !/^(Remove from list|Pause|Cancel|Resume|Retry|Remove)$/ { print; exit }
        $0 == file { found = 1 }'
}
download_verified() { [[ $(record_status "$1") == "Downloaded · SHA-256 verified" ]]; }

# find_download_row PATTERN: the file name of the first "Download <file>" button matching PATTERN,
# swiping up a few times to bring it into view; empty if none shows.
find_download_row() {
    local attempt file=""
    for attempt in 1 2 3; do
        file=$(ui_grep_text "^Download $1\$" | head -1 | sed 's/^Download //' || true)
        [[ -n $file ]] && break
        ui swipe-up >/dev/null
    done
    echo "$file"
}

open_rescue() {
    tab Catalog
    ui tap Rescue
    ui wait "$1" 5 || ui scroll-to "$1" 4
}

# catalog_download RELEASE_CARD FILE_REGEX: Catalog -> Rescue -> expand card -> Download the first
# file matching FILE_REGEX -> Downloads view. Sets $file.
catalog_download() {
    local card=$1 pattern=$2
    open_rescue "$card"
    ui scroll-to "$card" 4
    ui tap "$card"
    # The Catalog keeps expanded cards across tab switches, so other cards' rows may be open too:
    # swipe until this card's row shows, and if the tap collapsed an already expanded card, reopen it.
    wait_until 3 ui_grep_text "^Download $pattern\$" >/dev/null || true
    file=$(find_download_row "$pattern")
    if [[ -z $file ]]; then
        ui scroll-to "$card" 4
        ui tap "$card"
        wait_until 3 ui_grep_text "^Download $pattern\$" >/dev/null || true
        file=$(find_download_row "$pattern")
    fi
    require "$card: a download button matching $pattern" test -n "$file"
    ui tap "Download $file"
    ui wait View 10 && ui tap View
    ui wait "$file" 5
}
