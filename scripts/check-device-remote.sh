#!/usr/bin/env bash
# L4 remote check (< 30s warm): the real `bootleg` CLI on this Mac drives the phone over the
# tailnet. Remote on (debug provider) → node Running → `status` is pending (exit 3) → approve via
# the debug hook → `status` ok → `mount` a read-only FAT fixture, the Mac mounts it read-only,
# `diskutil eject` → `hid on`, harmless keys only (F24, Shift), `hid off` → revoke via the debug
# hook: a live session is closed and the next command exits 3 → Remote off → gadget clean, ADB up.
# Catalog downloads in between: `catalog` lists the rescue entries; netboot.xyz (or, when it is
# already in the library as the e2e fixture, another small netboot.xyz image) downloads with the
# app in the background and lands in the library SHA-256 verified, and a large entry is started
# then cancelled with no partial left. Whatever this run downloaded is deleted again.
#
# The approve/revoke hooks stand in for the in-app buttons (the screen may be locked). The phone
# must already be enrolled in the tailnet (auth key or sign-in once); this Mac must be on it.
# Everything that re-enumerates USB goes over wireless adb. Takes the device lock; an EXIT trap
# ejects the volume, revokes the approval, turns HID off (and waits for the Mac to drop it), then
# turns Remote and Bootleg's drives off.
#
# Usage: scripts/check-device-remote.sh   (env: BOOTLEG_WIFI_SERIAL, BOOTLEG_USB_SERIAL;
#        BOOTLEG_DEVICE_LOCK_HELD=1 when the caller already holds the device lock, which is then kept)
set -euo pipefail
cd "$(dirname "$0")/.."

WIFI_SERIAL=${BOOTLEG_WIFI_SERIAL:-$(scripts/wifi-serial.sh)}
USB_SERIAL=$(scripts/usb-serial.sh)
LOCK_DIR=/private/tmp/claude-501/bootleg-device.lock
PROVIDER=content://dev.jacobhayes.bootleg.debug
GADGET=/config/usb_gadget/g1
CLI=build/bin/bootleg
FIXTURE_DIR=build/device-fixtures
FIXTURE=$FIXTURE_DIR/remote-ro.img
VOLUME=BLREMOTE
FIXTURE_TEXT="hello from bootleg remote"
PHONE_IMAGE=/sdcard/bootleg/zz-bootleg-l4-remote.img # in the library, so the remote can name it
IMAGE_NAME=zz-bootleg-l4-remote.img

LOCK_WAIT_S=300
NODE_WAIT_S=20
VOLUME_WAIT_S=20
ENUMERATE_WAIT_S=10
ADB_WAIT_S=20
SESSION_CLOSE_WAIT_S=5
POLL_S=0.25
STORE=/sdcard/bootleg
SMALL_DOWNLOAD_SIZE_BYTES_MAX=$((64 * 1024 * 1024))
# A few MiB each; the catalog lists no size for them, so they are picked by name, in this order.
SMALL_DOWNLOAD_FILE_NAMES=(netboot.xyz.iso netboot.xyz.img netboot.xyz-sb.iso netboot.xyz-sb.img)
LARGE_DOWNLOAD_SIZE_BYTES_MIN=$((200 * 1024 * 1024))
DOWNLOAD_WAIT_S=180
CANCEL_PROGRESS_WAIT_S=30

now_ms() { local ns; ns=$(date +%s%N); echo $((ns / 1000000)); }
run_started_ms=$(now_ms)
current_step=setup
have_lock=false
remote_on=false
approved_node=
summary=()
holder_pid=
downloaded_image= # a library image this run added (deleted in cleanup)
started_large=     # a large download this run started (cancelled in cleanup)

fail() {
    echo "FAIL [$current_step]: $*" >&2
    exit 1
}

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

wait_until() {
    local timeout_s=$1 what=$2 deadline_ms
    shift 2
    deadline_ms=$(($(now_ms) + timeout_s * 1000))
    until "$@"; do
        (($(now_ms) < deadline_ms)) || fail "timed out after ${timeout_s}s waiting for $what"
        sleep "$POLL_S"
    done
}

wadb() { adb -s "$WIFI_SERIAL" "$@"; }
wifi_adb_up() { [[ $(wadb get-state 2>/dev/null) == device ]]; }
usb_adb_up() { [[ $(adb -s "$USB_SERIAL" get-state 2>/dev/null) == device ]]; }
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

# cli EXPECTED_EXIT ARGS...: runs the real CLI against the phone's tailnet name; prints its output.
cli() {
    local expected=$1 status=0 output
    shift
    output=$("$CLI" --host "$HOST" "$@" 2>&1) || status=$?
    [[ $status == "$expected" ]] || fail "bootleg $* exited $status (expected $expected): $output"
    echo "$output"
}

is_mounted() { mount | grep -q " on /Volumes/$VOLUME ("; }
is_unmounted() { ! is_mounted; }
hid_rows() { hidutil list 2>/dev/null | awk -v vid="$VENDOR_ID" 'tolower($1) == vid && $4 == 1 && ($5 == 6 || $5 == 2) && $7 == "USB"' | wc -l | tr -d ' '; }
host_hid_present() { (($(hid_rows) >= 2)); }
host_hid_absent() { (($(hid_rows) == 0)); }

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    [[ -n $holder_pid ]] && kill "$holder_pid" 2>/dev/null
    if $have_lock; then
        if is_mounted; then diskutil eject "/Volumes/$VOLUME" >/dev/null 2>&1 || true; fi
        if [[ -n $started_large ]]; then
            "$CLI" --host "$HOST" download --cancel "$started_large" >/dev/null 2>&1 || true
            wadb shell rm -f "$STORE/$started_large.partial" >/dev/null 2>&1 || true
        fi
        if [[ -n $downloaded_image ]]; then
            # A download that failed or timed out may still be running on the phone.
            "$CLI" --host "$HOST" download --cancel "$downloaded_image" >/dev/null 2>&1 || true
            wadb shell rm -f "$STORE/$downloaded_image.partial" >/dev/null 2>&1 || true
            wadb shell content call --uri "$PROVIDER" --method DELETE_IMAGE --extra "path:s:$STORE/$downloaded_image" >/dev/null 2>&1 || true
        fi
        if $remote_on; then
            [[ -n $approved_node ]] && wadb shell content call --uri "$PROVIDER" --method REMOTE_REVOKE --extra "node:s:$approved_node" >/dev/null 2>&1
        fi
        # HID goes first (its own path closes the session before the functions go), and must be
        # gone from the Mac before the drives' OFF re-enumerates the gadget.
        if wadb shell "su -c 'ls $GADGET/functions'" 2>/dev/null | grep -q 'hid\.bootleg'; then
            case "$(wadb shell content call --uri "$PROVIDER" --method HID_OFF 2>&1)" in
            *ApplyResult.Applied*) ;;
            *) echo "WARN: cleanup HID_OFF failed; turn keyboard + mouse off in the app" >&2 ;;
            esac
        fi
        if [[ -n ${VENDOR_ID:-} ]]; then
            local deadline_ms=$(($(now_ms) + ENUMERATE_WAIT_S * 1000))
            until host_hid_absent; do
                if (($(now_ms) >= deadline_ms)); then
                    echo "WARN: the Mac still lists a $VENDOR_ID keyboard/mouse after cleanup HID_OFF" >&2
                    break
                fi
                sleep "$POLL_S"
            done
        fi
        if $remote_on; then
            wadb shell content call --uri "$PROVIDER" --method REMOTE_OFF >/dev/null 2>&1 || true
        fi
        if wadb shell "su -c 'ls $GADGET/functions'" 2>/dev/null | grep -q bootleg; then
            wadb shell content call --uri "$PROVIDER" --method OFF >/dev/null 2>&1 || true
        fi
        # The fixture has to live in the library while the check runs; don't leave it there.
        wadb shell rm -f "$PHONE_IMAGE" >/dev/null 2>&1 || true
        [[ ${BOOTLEG_DEVICE_LOCK_HELD:-0} == 1 ]] || rmdir "$LOCK_DIR"
    fi
    local total_ms=$(($(now_ms) - run_started_ms))
    echo
    echo "L4 remote summary"
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

# ---------------------------------------------------------------------------------------------

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

build_cli() {
    mkdir -p "$(dirname "$CLI")"
    (cd remote && go build -o "../$CLI" ./cmd/bootleg)
    MAC_NODE=$(tailscale status --json | jq -r '.Self.ID')
    [[ -n $MAC_NODE && $MAC_NODE != null ]] || fail "this Mac isn't on a tailnet (tailscale status)"
}

sha256_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
byte_at() { od -An -tu1 -j "$2" -N1 "$1" | tr -d ' '; }

# The cached image must be exactly what fixture() built: unchanged since (sidecar hash), an MBR
# whose first partition holds a FAT volume labelled $VOLUME, with hello.txt's content. Anything
# else is rebuilt; unvalidated media is never exported (macOS would show a dialog for junk).
fixture_valid() {
    [[ -f $FIXTURE && -f $FIXTURE.sha256 ]] || return 1
    [[ $(sha256_of "$FIXTURE") == "$(cat "$FIXTURE.sha256")" ]] || return 1
    [[ $(file -b "$FIXTURE") == "DOS/MBR boot sector; partition 1 : "* ]] || return 1
    [[ $(byte_at "$FIXTURE" 510) == 85 && $(byte_at "$FIXTURE" 511) == 170 ]] || return 1
    local start_sector boot_sector
    start_sector=$(od -An -tu4 -j 454 -N4 "$FIXTURE" | tr -d ' ')
    ((start_sector > 0 && start_sector < 64)) || return 1
    boot_sector=$(dd if="$FIXTURE" bs=512 skip="$start_sector" count=1 2>/dev/null | file -b -)
    [[ $boot_sector == *"label: \"$VOLUME "* && $boot_sector == *"FAT ("* ]] || return 1
    LC_ALL=C grep -qa "$FIXTURE_TEXT" "$FIXTURE"
}

# A tiny formatted FAT image (never unformatted media: macOS would show a dialog), cached.
fixture() {
    if ! fixture_valid; then
        rm -rf "$FIXTURE_DIR/remote-content" "$FIXTURE" "$FIXTURE.sha256"
        mkdir -p "$FIXTURE_DIR/remote-content"
        echo "$FIXTURE_TEXT" >"$FIXTURE_DIR/remote-content/hello.txt"
        rm -f "$FIXTURE_DIR/tmp-remote.dmg" "$FIXTURE_DIR/tmp-remote.cdr"
        hdiutil create -quiet -size 8m -fs MS-DOS -volname "$VOLUME" -layout MBRSPUD -srcfolder "$FIXTURE_DIR/remote-content" "$FIXTURE_DIR/tmp-remote.dmg"
        hdiutil convert -quiet "$FIXTURE_DIR/tmp-remote.dmg" -format UDTO -o "$FIXTURE_DIR/tmp-remote"
        sha256_of "$FIXTURE_DIR/tmp-remote.cdr" >"$FIXTURE.sha256"
        mv "$FIXTURE_DIR/tmp-remote.cdr" "$FIXTURE"
        rm -f "$FIXTURE_DIR/tmp-remote.dmg"
        fixture_valid || fail "the freshly built $FIXTURE doesn't validate as a FAT image labelled $VOLUME"
    fi
    wifi_adb_up || adb connect "$WIFI_SERIAL" >/dev/null
    wait_until 10 "wireless adb at $WIFI_SERIAL" wifi_adb_up
    local want
    want=$(sha256_of "$FIXTURE")
    phone_sha256() { wadb shell "sha256sum $PHONE_IMAGE 2>/dev/null" | cut -d' ' -f1; }
    if [[ $(phone_sha256) != "$want" ]]; then
        wadb push -q "$FIXTURE" "$PHONE_IMAGE" >/dev/null
        [[ $(phone_sha256) == "$want" ]] || fail "the push of $FIXTURE to $PHONE_IMAGE is corrupt"
    fi
    is_unmounted || fail "/Volumes/$VOLUME is already mounted; eject it first"
    local state
    state=$(call STATE)
    jq -e '.functions | map(select(contains("bootleg"))) | length == 0' <<<"$state" >/dev/null ||
        fail "Bootleg is already exporting drives or HID; turn it off before running this check"
    call IMAGES >/dev/null
    VENDOR_ID=$(root "cat $GADGET/idVendor" | tr -d '\r' | tr 'A-F' 'a-f' | sed 's/^0x0*/0x/')
}

remote_up() {
    remote_on=true
    call REMOTE_ON >/dev/null
    node_running() { [[ $(call REMOTE_STATE | jq -r '.running and .backendState == "Running"') == true ]]; }
    wait_until "$NODE_WAIT_S" "the tailnet node to reach Running" node_running
    local state
    state=$(call REMOTE_STATE)
    HOST=$(jq -r '.dnsName' <<<"$state")
    [[ -n $HOST ]] || fail "the node has no MagicDNS name: $state"
    # Start from "never approved" so the pending path is exercised every run.
    if jq -e --arg node "$MAC_NODE" '.approvals | any(.nodeId == $node)' <<<"$state" >/dev/null; then
        call REMOTE_REVOKE --extra "node:s:$MAC_NODE" >/dev/null
    fi
    echo "    node $HOST ($(jq -r '.tailscaleIPs | join(" ")' <<<"$state")); $(tailscale ping -c 1 "$HOST" 2>&1 | tail -1)"
}

pending_then_approve() {
    cli 3 status >/dev/null
    jq -e --arg node "$MAC_NODE" '.pending | any(.nodeId == $node)' <<<"$(call REMOTE_STATE)" >/dev/null ||
        fail "this Mac ($MAC_NODE) is not listed as pending"
    approved_node=$MAC_NODE
    call REMOTE_APPROVE --extra "node:s:$MAC_NODE" >/dev/null
    cli 0 status | grep -q 'approved' || fail "status after approval doesn't say approved"
}

mount_read_only() {
    cli 0 mount "$IMAGE_NAME" --mode ro >/dev/null
    wait_until "$VOLUME_WAIT_S" "/Volumes/$VOLUME to mount" is_mounted
    [[ $(diskutil info -plist "/Volumes/$VOLUME" | plutil -extract WritableMedia raw -o - -) == false ]] ||
        fail "/Volumes/$VOLUME is writable"
    [[ $(cat "/Volumes/$VOLUME/hello.txt") == "$FIXTURE_TEXT" ]] || fail "hello.txt doesn't match"
    cli 0 status | grep -q "LUN 0: $IMAGE_NAME \[disk_ro\]" || fail "status doesn't list LUN 0 as disk_ro"
    diskutil eject "/Volumes/$VOLUME" >/dev/null
    wait_until 5 "/Volumes/$VOLUME to disappear" is_unmounted
}

harmless_hid() {
    host_hid_absent || fail "a $VENDOR_ID keyboard/mouse is already enumerated; is another HID app active?"
    cli 0 hid on >/dev/null
    wait_until "$ENUMERATE_WAIT_S" "the Mac to enumerate the keyboard and mouse" host_hid_present
    local before after
    before=$(call HID_STATE | jq '.written')
    # Only F24 and Shift taps: nothing the Mac acts on.
    cli 0 key f24 >/dev/null
    cli 0 key shift >/dev/null
    written() { after=$(call HID_STATE | jq '.written'); ((after - before >= 4)); }
    wait_until 5 "the phone to write the key reports" written
    echo "    $((after - before)) HID reports written"
    cli 0 hid off >/dev/null
    wait_until "$ENUMERATE_WAIT_S" "the Mac to drop the keyboard and mouse" host_hid_absent
}

revoke_closes_session() {
    "$CLI" --host "$HOST" lease take </dev/null >/dev/null 2>&1 &
    holder_pid=$!
    holding() { [[ $(call REMOTE_STATE | jq -r '.lease') == session:* ]]; }
    wait_until 5 "the background CLI to hold the lease" holding
    call REMOTE_REVOKE --extra "node:s:$MAC_NODE" >/dev/null
    approved_node=
    local status=0 deadline_ms=$(($(now_ms) + SESSION_CLOSE_WAIT_S * 1000))
    while kill -0 "$holder_pid" 2>/dev/null; do
        (($(now_ms) < deadline_ms)) || fail "the live CLI session was not closed within ${SESSION_CLOSE_WAIT_S}s of the revoke"
        sleep "$POLL_S"
    done
    wait "$holder_pid" || status=$?
    holder_pid=
    [[ $status == 3 ]] || fail "the revoked live session exited $status, expected 3"
    cli 3 status >/dev/null
    [[ $(call REMOTE_STATE | jq '.sessions | map(select(.auth == "approved")) | length') == 0 ]] ||
        fail "an approved session is still open after the revoke"
}

# in_store NAME: whether the library holds NAME. An adb failure fails the check rather than reading
# as "absent", which could pick (and cleanup later delete) the e2e runner's shared fixture.
in_store() {
    local answer
    answer=$(wadb shell "if [ -e $STORE/$1 ]; then echo present; else echo absent; fi" 2>/dev/null | tr -d '\r') || answer=
    case $answer in
    present) return 0 ;;
    absent) return 1 ;;
    *) fail "couldn't tell whether $STORE/$1 is in the library (adb said '$answer')" ;;
    esac
}
no_partial() { ! in_store "$1.partial"; }

# rescue_entries: the x86_64 rescue entries as JSON lines {entryId, fileName, sizeBytes, state}.
rescue_entries() {
    cli 0 catalog --category rescue --json | jq -c '.distros[].entries[] | {entryId, fileName, sizeBytes: (.sizeBytes // 0), state}'
}

catalog_downloads() {
    local entries small='' name large output download_status
    entries=$(rescue_entries)
    [[ -n $entries ]] || fail "the catalog lists no rescue entries"
    jq -e 'select(.fileName == "netboot.xyz.iso")' <<<"$entries" >/dev/null || fail "the catalog has no netboot.xyz.iso"
    if in_store netboot.xyz.iso; then
        # The e2e runner's shared fixture: never deleted here. A start is a no-op.
        cli 0 download netboot.xyz.iso | grep -q 'already in the library' || fail "netboot.xyz.iso in the library but download didn't say so"
    fi
    for name in "${SMALL_DOWNLOAD_FILE_NAMES[@]}"; do
        if jq -e --arg name "$name" 'select(.fileName == $name and .state == "available")' <<<"$entries" >/dev/null &&
            ! in_store "$name"; then
            small=$name
            break
        fi
    done
    if [[ -z $small ]]; then
        small=$(jq -r --argjson max "$SMALL_DOWNLOAD_SIZE_BYTES_MAX" \
            'select(.state == "available" and .sizeBytes > 0 and .sizeBytes <= $max) | [.sizeBytes, .fileName] | @tsv' <<<"$entries" |
            sort -n | head -1 | cut -f2)
    fi
    [[ -n $small ]] || fail "no small rescue entry is available to download (${SMALL_DOWNLOAD_FILE_NAMES[*]} are all in the library or missing)"
    [[ $small == netboot.xyz.iso ]] || echo "    netboot.xyz.iso is in the library or unavailable (kept); downloading $small instead"
    in_store "$small" && fail "$small is already in the library"
    # The app in the background, so Android refuses the job and root's broadcast has to schedule it.
    wadb shell input keyevent KEYCODE_HOME
    local since
    since=$(wadb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
    downloaded_image=$small
    output=$(cli 0 download "$small" --follow --timeout "${DOWNLOAD_WAIT_S}s")
    grep -q "SHA-256 verified" <<<"$output" || fail "$small finished without a verified SHA-256: $output"
    if wadb logcat -d -T "$since" -s BootlegDownloads:I | grep -q "through root's background broadcast"; then
        echo "    scheduled through root's background broadcast"
    else
        echo "    scheduled directly (the app could still schedule user-initiated jobs)"
    fi
    in_store "$small" || fail "$small is not in the library"
    cli 0 images --json | jq -e --arg name "$small" 'any(.[]; .name == $name and .verified == true)' >/dev/null ||
        fail "the library doesn't list $small as verified"
    no_partial "$small" || fail "$small left a partial file"

    large=$(jq -r --argjson min "$LARGE_DOWNLOAD_SIZE_BYTES_MIN" \
        'select(.state == "available" and .sizeBytes >= $min) | .fileName' <<<"$entries" | head -1)
    [[ -n $large ]] || fail "no large rescue entry is available to cancel"
    started_large=$large
    cli 0 download "$large" >/dev/null
    progressing() {
        download_status=$(cli 0 downloads --json | jq -r --arg name "$large" '.downloads[] | select(.fileName == $name) | "\(.status) \(.downloadedBytes)"')
        [[ $download_status == running\ * && ${download_status#running } != 0 ]]
    }
    wait_until "$CANCEL_PROGRESS_WAIT_S" "$large to start transferring" progressing
    cli 0 download --cancel "$large" | grep -q '^cancelled$' || fail "cancelling $large didn't say cancelled"
    gone() { [[ -z $(cli 0 downloads --json | jq -r --arg name "$large" '.downloads[] | select(.fileName == $name) | .status') ]]; }
    wait_until 10 "$large to leave the download list" gone
    wait_until 10 "$large.partial to be deleted" no_partial "$large"
    started_large=
    echo "    $large: cancelled at ${download_status#running } bytes, no partial left"

    call DELETE_IMAGE --extra "path:s:$STORE/$small" >/dev/null
    downloaded_image=
    in_store "$small" && fail "$small is still in the library after the delete"
    return 0
}

remote_down() {
    local state
    state=$(call REMOTE_OFF)
    remote_on=false
    jq -e '.running == false and .keepAwake == false' <<<"$state" >/dev/null || fail "Remote didn't stop: $state"
}

gadget_clean() {
    local state
    state=$(call STATE)
    if jq -e '.functions | map(select(contains("bootleg"))) | length > 0' <<<"$state" >/dev/null; then
        call OFF >/dev/null
        state=$(call STATE)
    fi
    jq -e '.udc != "" and (.functions | map(select(contains("bootleg"))) | length == 0)' <<<"$state" >/dev/null ||
        fail "gadget not clean: $state"
    root "readlink $GADGET/configs/b.1/function0" | grep -q '/ffs.adb$' || fail "function0 no longer links ffs.adb"
}

usb_adb_back() {
    wait_until "$ADB_WAIT_S" "USB adb to come back" usb_adb_up
}

step "lock device" take_lock
step "build CLI, find this Mac's node" build_cli
step "fixture (cached) + preflight" fixture
step "Remote on, node Running" remote_up
step "status pending (3) → approve → ok" pending_then_approve
step "catalog download (verified) and cancel" catalog_downloads
step "mount RO over tailnet, Mac reads, eject" mount_read_only
step "hid on, F24/Shift taps, hid off" harmless_hid
step "revoke closes live session, next exits 3" revoke_closes_session
step "drives off, gadget clean, ADB linked" gadget_clean
step "Remote off" remote_down
step "USB adb back" usb_adb_back
