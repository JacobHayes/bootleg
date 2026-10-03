#!/usr/bin/env bash
# E2E (UI) flow 1: Catalog -> Rescue -> download netboot.xyz (plain ISO) and Memtest86+ (zipped ISO,
# extracted) through the app; each lands in the Library SHA-256 verified, with the store's owner and
# label, and no .partial/.zip left behind. With --pause-cancel it also starts GParted Live (~720 MB),
# pauses, resumes (append, not restart) and cancels it (partial removed), using ~10 MB of data.
#
# Images already in the Library are deleted through the UI first, so every run downloads.
# Usage: scripts/e2e-ui-catalog.sh [--pause-cancel]
FLOW=catalog
source "$(dirname "$0")/e2e-ui-lib.sh"

DOWNLOAD_WAIT_S=180
pause_cancel=false
[[ ${1:-} == --pause-cancel ]] && pause_cancel=true

# record_status FILE: the status line of FILE's card in the Downloads view.
record_status() {
    ui texts | awk -v file="$1" '
        found && !/^(Remove from list|Pause|Cancel|Resume|Retry|Remove)$/ { print; exit }
        $0 == file { found = 1 }'
}
download_verified() { [[ $(record_status "$1") == "Downloaded · SHA-256 verified" ]]; }

# sidecar_matches NAME: the .sha256 sidecar equals the file's actual hash.
sidecar_matches() {
    local expected actual
    expected=$(adb shell cat "/sdcard/bootleg/$1.sha256" | tr -d '\r' | awk '{ print $1 }')
    actual=$(adb shell sha256sum "/sdcard/bootleg/$1" | tr -d '\r' | awk '{ print $1 }')
    [[ -n $expected && $expected == "$actual" ]]
}

# owned_like_store NAME: same owner, group and SELinux label as the store directory.
owned_like_store() {
    local dir file
    dir=$(root "stat -c \"%u %g %C\" $STORE")
    file=$(root "stat -c \"%u %g %C\" $STORE/$1")
    [[ -n $dir && $dir == "$file" ]]
}

no_leftovers() { ! adb shell ls /sdcard/bootleg/ | grep -qE '\.(partial|zip)$'; }

# download RELEASE_CARD FILE_REGEX: Catalog -> Rescue -> expand card -> Download the first file
# matching FILE_REGEX -> Downloads view. Sets $file.
download() {
    local card=$1 pattern=$2
    tab Catalog
    ui tap Rescue
    sleep 0.8
    ui scroll-to "$card" 4
    ui tap "$card"
    sleep 1
    # Bring the expanded card's rows into view (it may sit at the bottom of the list).
    ui scroll-to "~Download " 2 || true
    ui swipe-up
    file=$(ui grep-text "^Download $pattern\$" | head -1 | sed 's/^Download //' || true)
    if [[ -z $file ]]; then
        # The card was already expanded (the tab kept its state) and the tap collapsed it.
        ui tap "$card"
        sleep 1
        file=$(ui grep-text "^Download $pattern\$" | head -1 | sed 's/^Download //' || true)
    fi
    require "$card: a download button matching $pattern" test -n "$file"
    ui tap "Download $file"
    ui wait View 10 && ui tap View
    shot "$file-downloads"
    log "    status: $(record_status "$file")"
    check "$file: downloaded and verified" wait_until "$DOWNLOAD_WAIT_S" download_verified "$file"
    shot "$file-verified"
    check "$file: sidecar matches the file" sidecar_matches "$file"
    check "$file: owner and label match the store" owned_like_store "$file"
    tab Library
    check "$file: in Library" has_text "$file"
}

take_lock
launch_app

# forget_download FILE: Downloads -> "Remove from list" on FILE's card, if listed.
forget_download() {
    tab Library
    ui tap Downloads
    sleep 0.8
    if has_text "$1"; then tap_after "$1" "Remove from list"; fi
    tab Library
}

# catalog_says_downloaded CARD FILE: the expanded catalog card shows FILE as Downloaded (no button).
catalog_says_downloaded() {
    tab Catalog
    ui tap Rescue
    sleep 0.8
    ui tap "$1"
    sleep 1
    ui scroll-to "$2" 2 || true
    local state
    state=$(ui texts | awk -v file="$2" 'found { print; exit } $0 == file { found = 1 }')
    ui tap "$1" # collapse again
    [[ $state == Downloaded ]]
}

catalog_not_downloaded() { ! catalog_says_downloaded "$@"; }

step "clean slate"
for old in netboot.xyz.iso $(adb shell ls /sdcard/bootleg/ | tr -d '\r' | grep -E '^mt86plus_.*\.iso$' || true); do
    if adb shell ls "/sdcard/bootleg/$old" >/dev/null 2>&1; then
        delete_from_library "$old"
        # Finding O9: the Catalog and Downloads keep saying "Downloaded" for an image deleted in the
        # Library (they read the download records, not the file), so no Download button comes back.
        if [[ $old == netboot.xyz.iso ]]; then
            check "Catalog no longer shows deleted $old as Downloaded" catalog_not_downloaded netboot.xyz "$old"
        fi
    fi
    forget_download "$old"
done

step "netboot.xyz ISO"
download netboot.xyz "netboot\\.xyz\\.iso"

step "Memtest86+ (zip, extracted)"
download "Memtest86+" "mt86plus_.*_x86_64\\.iso"
check "no .partial or .zip left" no_leftovers
tab Library
check "Library shows SHA-256 verified" has_text "SHA-256 verified"
shot library

if $pause_cancel; then
    step "GParted pause / resume / cancel"
    on_cleanup "adb shell 'rm -f /sdcard/bootleg/gparted-live-*.partial'"
    tab Catalog
    ui tap Rescue
    sleep 0.8
    ui tap "GParted Live"
    sleep 1
    gparted=$(ui grep-text '^Download gparted-live-.*\.iso$' | head -1 | sed 's/^Download //' || true)
    ui tap "Download $gparted"
    ui wait View 10 && ui tap View
    check "GParted: progress shown" wait_until 20 ui has "~ of "
    sleep 3
    ui tap Pause
    check "GParted: paused" wait_until 10 ui has "~Paused · "
    partial_paused=$(adb shell stat -c %s "/sdcard/bootleg/$gparted.partial" | tr -d '\r')
    shot gparted-paused
    ui tap Resume
    sleep 4
    partial_resumed=$(adb shell stat -c %s "/sdcard/bootleg/$gparted.partial" | tr -d '\r')
    check "GParted: resume appends (partial grew from $partial_paused to $partial_resumed)" test "$partial_resumed" -gt "$partial_paused"
    ui tap Cancel
    sleep 0.6
    shot gparted-cancel-dialog
    ui tap "Cancel download"
    check "GParted: partial removed" wait_until 10 bash -c "! adb shell ls /sdcard/bootleg/ | grep -q gparted"
fi
