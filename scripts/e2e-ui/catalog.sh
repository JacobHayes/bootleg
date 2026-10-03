# shellcheck shell=bash disable=SC2034 # sourced by scripts/e2e-ui.sh, which uses the variables
# Catalog tests: Catalog -> Rescue -> download netboot.xyz (plain ISO) and Memtest86+ (zipped ISO,
# extracted) through the app; each lands in the Library SHA-256 verified, with the store's owner
# and label, and no .partial/.zip left behind. Images already in the Library are deleted through
# the UI first, so every run downloads. Sourced by scripts/e2e-ui.sh.

define_test catalog.open "open cold (no cache): list at once, cards fill in (one transient feed failure tolerated, live upstreams); reopen: saved results at once"
define_test catalog.netboot "download netboot.xyz.iso (plain ISO): verified, sidecar, owner/label, in Library"
define_test catalog.memtest "download Memtest86+ (zip, extracted): verified, sidecar, owner/label, no leftovers"
define_test catalog.gparted-pause-cancel "GParted Live: pause, resume appends, cancel removes the partial (~10 MB)" --opt-in

# sidecar_matches NAME: the .sha256 sidecar equals the file's actual hash.
sidecar_matches() {
    local expected actual
    expected=$(adb shell cat "/sdcard/bootleg/$1.sha256" | tr -d '\r' | awk '{ print $1 }')
    actual=$(adb shell sha256sum "/sdcard/bootleg/$1" | tr -d '\r' | awk '{ print $1 }')
    [[ -n $expected && $expected == "$actual" ]]
}

# owned_like_store NAME: same owner, group and SELinux label as the store directory.
catalog_owned_like_store() {
    local dir file
    dir=$(root "stat -c \"%u %g %C\" $STORE")
    file=$(root "stat -c \"%u %g %C\" $STORE/$1")
    [[ -n $dir && $dir == "$file" ]]
}

no_leftovers() { ! adb shell ls /sdcard/bootleg/ | grep -qE '\.(partial|zip)$'; }

# forget_download FILE: Downloads -> "Remove from list" on FILE's card, if listed.
forget_download() {
    tab Library
    ui tap Downloads
    ui wait Downloads 5
    # A finished record says "Remove from list", a failed one "Remove"; deleting the image in the
    # Library already drops a finished record.
    if has_text "$1"; then tap_after "$1" "Remove from list" || tap_after "$1" Remove || true; fi
    tab Library
}

# catalog_says_downloaded CARD FILE: the expanded catalog card shows FILE as Downloaded (no button).
catalog_says_downloaded() {
    local state
    open_rescue "$1"
    ui tap "$1"
    wait_until 3 has_text "$2" || ui scroll-to "$2" 2 || true
    state=$(ui_texts | awk -v file="$2" 'found { print; exit } $0 == file { found = 1 }')
    ui tap "$1" # collapse again
    [[ $state == Downloaded ]]
}
catalog_not_downloaded() { ! catalog_says_downloaded "$@"; }

# download_and_verify CARD FILE_REGEX: catalog_download plus the checks on the downloaded image.
download_and_verify() {
    catalog_download "$1" "$2"
    shot "$file-downloads"
    log "    status: $(record_status "$file")"
    check "$file: downloaded and verified" wait_until "$CATALOG_DOWNLOAD_WAIT_S" download_verified "$file"
    shot "$file-verified"
    check "$file: sidecar matches the file" sidecar_matches "$file"
    check "$file: owner and label match the store" catalog_owned_like_store "$file"
    tab Library
    check "$file: in Library" has_text "$file"
}

CATALOG_LIST_WAIT_S=3
# A feed's deadline is 30 s once it has a fetch slot (CatalogLimits.FEED_TIMEOUT_MS), plus slot waits.
CATALOG_LOAD_WAIT_S=45
# The feeds are live upstreams: one failing card is an upstream blip, more is Bootleg's problem.
CATALOG_FAILED_CARDS_MAX=1

catalog_cards_loaded() { ! ui_has "~Loading" && ! ui_has "Not loaded yet"; }
catalog_failed_cards() {
    local texts
    texts=$(ui_texts) || return 1
    grep -cE "Refresh failed|Couldn't load" <<<"$texts" || true
}
catalog_cards_ok() {
    local failed
    failed=$(catalog_failed_cards) || return 1
    ((failed == 0)) && return 0
    log "    WARN: $failed Desktop card(s) failed to load (live upstream); up to $CATALOG_FAILED_CARDS_MAX tolerated"
    ((failed <= CATALOG_FAILED_CARDS_MAX))
}

# The Catalog tab opened at $1 (ms); logs how long until the step's condition held.
catalog_elapsed() { log "    $1 after $(($(now_ms) - $2)) ms"; }

test_catalog_open() {
    local started
    step "cold open (no saved results)"
    adb shell am force-stop "$PACKAGE"
    adb shell run-as "$PACKAGE" rm -f files/catalog-cache.json
    launch_app
    started=$(now_ms)
    ui tap Catalog # not `tab`: it waits for a still screen, which would hide the timing
    check "cold: the Desktop list shows at once" wait_until "$CATALOG_LIST_WAIT_S" has_text "Arch Linux"
    catalog_elapsed "list" "$started"
    check "cold: every Desktop card loads" wait_until "$CATALOG_LOAD_WAIT_S" catalog_cards_loaded
    catalog_elapsed "cards loaded" "$started"
    check "cold: at most $CATALOG_FAILED_CARDS_MAX Desktop card failed" catalog_cards_ok
    shot cold

    step "reopen (saved results)"
    restart_app
    started=$(now_ms)
    ui tap Catalog # not `tab`: it waits for a still screen, which would hide the timing
    check "warm: saved results show at once" wait_until "$CATALOG_LIST_WAIT_S" has_text "~updated"
    catalog_elapsed "saved results" "$started"
    check "warm: at most $CATALOG_FAILED_CARDS_MAX Desktop card failed" catalog_cards_ok
    shot warm
}

test_catalog_netboot() {
    step "clean slate"
    if in_store "$ISO"; then
        delete_from_library "$ISO"
        # Finding O9: the Catalog and Downloads kept saying "Downloaded" for an image deleted in the
        # Library (they read the download records, not the file), so no Download button came back.
        check "Catalog no longer shows deleted $ISO as Downloaded" catalog_not_downloaded netboot.xyz "$ISO"
    fi
    forget_download "$ISO"

    step "download"
    download_and_verify netboot.xyz "netboot\\.xyz\\.iso"
}

test_catalog_memtest() {
    local old
    step "clean slate"
    for old in $(adb shell ls /sdcard/bootleg/ | tr -d '\r' | grep -E '^mt86plus_.*\.iso$' || true); do
        delete_from_library "$old"
        forget_download "$old"
    done

    step "download (zip, extracted)"
    download_and_verify "Memtest86+" "mt86plus_.*_x86_64\\.iso"
    check "no .partial or .zip left" no_leftovers
    tab Library
    check "Library shows SHA-256 verified" has_text "SHA-256 verified"
    shot library
}

partial_size() { adb shell stat -c %s "/sdcard/bootleg/$1.partial" 2>/dev/null | tr -d '\r'; }
partial_at_least() { local size; size=$(partial_size "$1"); [[ -n $size ]] && ((size > $2)); }

test_catalog_gparted_pause_cancel() {
    local gparted partial_paused partial_resumed
    on_cleanup "adb shell 'rm -f /sdcard/bootleg/gparted-live-*.partial'"
    open_rescue "GParted Live"
    ui tap "GParted Live"
    wait_until 3 ui_grep_text '^Download gparted-live-.*\.iso$' >/dev/null || true
    gparted=$(ui_grep_text '^Download gparted-live-.*\.iso$' | head -1 | sed 's/^Download //' || true)
    require "GParted: a download button" test -n "$gparted"
    on_cleanup "has_text Cancel && ui tap Cancel && ui tap 'Cancel download' || true"
    ui tap "Download $gparted"
    ui wait View 10 && ui tap View
    check "GParted: progress shown" wait_until 20 ui_has "~ of "
    # Let some data arrive before pausing, so the resume has something to append to.
    wait_until 10 partial_at_least "$gparted" 1000000 || true
    ui tap Pause
    check "GParted: paused" wait_until 10 ui_has "~Paused · "
    partial_paused=$(partial_size "$gparted")
    shot gparted-paused
    ui tap Resume
    wait_until 10 partial_at_least "$gparted" "${partial_paused:-0}" || true
    partial_resumed=$(partial_size "$gparted")
    check "GParted: resume appends (partial grew from $partial_paused to $partial_resumed)" test "${partial_resumed:-0}" -gt "${partial_paused:-0}"
    ui tap Cancel
    ui wait "Cancel download" 5
    shot gparted-cancel-dialog
    ui tap "Cancel download"
    check "GParted: partial removed" wait_until 10 bash -c "! adb shell ls /sdcard/bootleg/ | grep -q gparted"
}
