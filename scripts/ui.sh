#!/usr/bin/env bash
# Drives the app's UI over adb for end-to-end runs: find a node by its text or content description,
# tap its center, wait for it, scroll to it, take a downscaled screenshot.
#
# Usage: scripts/ui.sh COMMAND [ARGS]   (or source it: the same commands as ui_* functions)
#   texts                       list the visible texts / content descriptions
#   has TEXT                    exit 0 if a node matches
#   attr TEXT NAME [INDEX]      print an attribute (checked, enabled, bounds...) of the matching node
#   grep-text REGEX             print the visible texts matching an extended regex
#   tap TEXT [INDEX]            tap the center of the INDEX-th (1-based) matching node, once it holds still
#   toggle TEXT                 tap the switch/checkbox on TEXT's row
#   checked TEXT                exit 0 if the switch/checkbox on TEXT's row is checked
#   wait TEXT [TIMEOUT_S]       wait until a node matches (default 15s)
#   gone TEXT [TIMEOUT_S]       wait until no node matches (default 15s)
#   scroll-to TEXT [SWIPES]     swipe up until a node matches (default 8 swipes)
#   swipe-up                    scroll the list a little (one short upward swipe)
#   shot NAME                   save a screenshot (≤ 900px) to $BOOTLEG_SHOTS_DIR/NAME.png
#   dump                        print the raw uiautomator XML
#
# TEXT matches @text or @content-desc exactly; a leading '~' matches a substring instead.
# Uses $ANDROID_SERIAL (prefer the wireless serial: USB adb drops while the gadget re-enumerates).
#
# Dumps: with BOOTLEG_UI_SERVER=1 they come from the resident helper (scripts/e2e-ui/
# UiDumpServer.java, ~0.2 s) instead of `uiautomator dump` (~2.4 s); ui_server_start starts it.
# Only one UiAutomation connection can exist, so while it runs every dump must go through it.
#
# Never type, swipe or scroll on the Control tab: keystrokes go to the USB host and the touchpad
# moves (or clicks) its pointer. There, only tap nodes found by text (chips, keys, switches).

UI_POLL_S=0.2
# Wireless adb can hang on a dead transport, and a helper request can stall with it: both are bounded.
# The longest single call is an install or a debug-provider call that changes the gadget.
UI_ADB_CALL_TIMEOUT_S=${UI_ADB_CALL_TIMEOUT_S:-120}
UI_SERVER_REQUEST_TIMEOUT_S=${UI_SERVER_REQUEST_TIMEOUT_S:-90}
UI_DUMP_TRIES_MAX=3
UI_ROW_DISTANCE_MAX_PX=200
# A node is tapped once two dumps in a row put it at the same place (sheets and lists animate).
UI_TAP_SETTLE_TRIES_MAX=40
# How long a tap waits for its node to show up (dialogs, menus and sheets take a moment).
UI_TAP_WAIT_S=${UI_TAP_WAIT_S:-5}
UI_SERVER_SOCKET=bootleg-ui-dump
UI_SERVER_JAR_DEVICE=/data/local/tmp/bootleg-ui-dump.jar
UI_SERVER_LOG_DEVICE=/data/local/tmp/bootleg-ui-dump.log
UI_SERVER_IDLE_MS=200
UI_SERVER_IDLE_TIMEOUT_MS=2000
UI_SERVER_EXIT_AFTER_UNUSED_MS=300000
UI_SERVER_START_WAIT_S=10
UI_SCREEN_SIZE=${UI_SCREEN_SIZE:-}
# The latest dump, reused as the first sample when checking that a node holds still before a tap.
UI_LAST_DUMP_FILE=${UI_LAST_DUMP_FILE:-${TMPDIR:-/tmp}/bootleg-ui-last-dump-$$.xml}
UI_LAST_DUMP_REUSE_S=2

# --- adb --------------------------------------------------------------------------------------------

command -v timeout >/dev/null || { echo "ui: needs GNU timeout (brew install coreutils)" >&2; exit 1; }
UI_ADB_BIN=${UI_ADB_BIN:-$(type -P adb)} || { echo "ui: adb not found" >&2; exit 1; }
# --foreground keeps adb in this shell's process group (so it never stops on terminal input) and
# signals only adb; exported so `bash -c` checks get the bounded adb too.
adb() { timeout --foreground --kill-after=5 "$UI_ADB_CALL_TIMEOUT_S" "$UI_ADB_BIN" "$@"; }
export UI_ADB_BIN UI_ADB_CALL_TIMEOUT_S
export -f adb

# --- helper server ---------------------------------------------------------------------------------

# The [b] keeps pgrep/pkill from matching the shell that runs them (its command line holds the pattern).
UI_SERVER_PATTERN="UiDumpServer [${UI_SERVER_SOCKET:0:1}]${UI_SERVER_SOCKET:1}"

ui_server_running() { [[ -n $(adb shell "pgrep -f '$UI_SERVER_PATTERN' || true" | tr -d '\r') ]]; }

# ui_server_start JAR: pushes JAR and starts the server (if it isn't already running), detached from
# the adb session so it outlives transport drops.
ui_server_start() {
    local jar=$1 waited
    [[ -f $jar ]] || { echo "ui: helper jar $jar missing" >&2; return 1; }
    if ui_server_running; then return 0; fi
    adb push "$jar" "$UI_SERVER_JAR_DEVICE" >/dev/null 2>&1 || { echo "ui: could not push $jar" >&2; return 1; }
    adb shell "rm -f $UI_SERVER_LOG_DEVICE; \
        CLASSPATH=/system/framework/uiautomator.jar:/system/framework/content.jar:$UI_SERVER_JAR_DEVICE \
        setsid nohup app_process /system/bin dev.jacobhayes.bootleg.e2e.UiDumpServer $UI_SERVER_SOCKET \
        $UI_SERVER_IDLE_MS $UI_SERVER_IDLE_TIMEOUT_MS $UI_SERVER_EXIT_AFTER_UNUSED_MS >$UI_SERVER_LOG_DEVICE 2>&1 </dev/null &"
    for ((waited = 0; waited < UI_SERVER_START_WAIT_S * 5; waited++)); do
        adb shell "grep -q listening $UI_SERVER_LOG_DEVICE" 2>/dev/null && return 0
        sleep 0.2
    done
    echo "ui: helper did not start: $(adb shell cat "$UI_SERVER_LOG_DEVICE" 2>&1)" >&2
    return 1
}

ui_server_stop() {
    adb shell "pkill -f '$UI_SERVER_PATTERN'" >/dev/null 2>&1 || true
    if [[ -n ${UI_SERVER_PORT:-} ]]; then adb forward --remove "tcp:$UI_SERVER_PORT" >/dev/null 2>&1 || true; fi
}

# ui_server_forward: (re)creates the forward for $ANDROID_SERIAL and sets UI_SERVER_PORT. A gadget
# re-enumeration drops USB adb and with it that transport's forwards.
ui_server_forward() {
    local port
    if [[ -n ${UI_SERVER_PORT:-} ]]; then
        adb forward "tcp:$UI_SERVER_PORT" "localabstract:$UI_SERVER_SOCKET" >/dev/null 2>&1 && return 0
    fi
    port=$(adb forward tcp:0 "localabstract:$UI_SERVER_SOCKET") || return 1
    UI_SERVER_PORT=$port
    export UI_SERVER_PORT
}

# ui_server_request WORD...: sends one request (words joined by tabs) and prints the answer.
ui_server_request() {
    local fd request
    [[ -n ${UI_SERVER_PORT:-} ]] || return 1
    { exec {fd}<>"/dev/tcp/127.0.0.1/$UI_SERVER_PORT"; } 2>/dev/null || return 1
    request=$(IFS=$'\t'; echo "$*")
    printf '%s\n' "$request" >&"$fd"
    timeout --foreground "$UI_SERVER_REQUEST_TIMEOUT_S" cat <&"$fd"
    local status=$?
    exec {fd}<&-
    return "$status"
}

# ui_server_recover: the forward or the server went away (re-enumeration, unused timeout).
ui_server_recover() {
    ui_server_forward || true
    if [[ -n ${BOOTLEG_UI_SERVER_JAR:-} ]] && ! ui_server_running; then ui_server_start "$BOOTLEG_UI_SERVER_JAR" || true; fi
}

# ui_content ARGS...: the `content` command (through the helper when it runs: no JVM start per call).
ui_content() {
    local output
    [[ ${BOOTLEG_UI_SERVER:-0} == 1 ]] || { adb shell content "$@"; return; }
    if ! output=$(ui_server_request content "$@") || [[ -z $output ]]; then
        ui_server_recover
        output=$(ui_server_request content "$@") || return 1
    fi
    printf '%s\n' "$output"
}

# --- queries -----------------------------------------------------------------------------------------

ui_dump() {
    local try xml
    for ((try = 1; try <= UI_DUMP_TRIES_MAX; try++)); do
        if [[ ${BOOTLEG_UI_SERVER:-0} == 1 ]]; then
            xml=$(ui_server_request dump) || xml=""
            if [[ $xml == *'</hierarchy>'* ]]; then
                ui_remember_dump "$xml"
                printf '%s\n' "$xml"
                return 0
            fi
            ui_server_recover
        # uiautomator refuses while the UI never idles (spinners); retry a few times.
        elif adb shell uiautomator dump /data/local/tmp/bootleg-ui.xml >/dev/null 2>&1; then
            xml=$(adb exec-out cat /data/local/tmp/bootleg-ui.xml)
            ui_remember_dump "$xml"
            printf '%s\n' "$xml"
            return 0
        fi
        sleep "$UI_POLL_S"
    done
    echo "ui: dump failed $UI_DUMP_TRIES_MAX times" >&2
    return 1
}

ui_xpath_for() {
    local text=$1
    if [[ $text == ~* ]]; then
        text=${text#\~}
        echo "//node[contains(@text,\"$text\") or contains(@content-desc,\"$text\")]"
    else
        echo "//node[@text=\"$text\" or @content-desc=\"$text\"]"
    fi
}

# ui_center_in XML TEXT [INDEX]: prints "x y" of the matching node's center.
ui_center_in() {
    local xml=$1 text=$2 index=${3:-1} bounds numbers
    bounds=$(xmllint --xpath "string(($(ui_xpath_for "$text"))[$index]/@bounds)" - <<<"$xml" 2>/dev/null || true)
    [[ -n $bounds ]] || return 1
    # [x1,y1][x2,y2]
    read -r -a numbers <<<"${bounds//[\[\],]/ }"
    echo "$(((numbers[0] + numbers[2]) / 2)) $(((numbers[1] + numbers[3]) / 2))"
}

ui_center() {
    local xml
    xml=$(ui_dump) || return 1
    ui_center_in "$xml" "$@"
}

ui_has() { ui_center "$1" >/dev/null; }

ui_attr() {
    local value
    value=$(ui_dump | xmllint --xpath "string(($(ui_xpath_for "$1"))[${3:-1}]/@$2)" - 2>/dev/null || true)
    [[ -n $value ]] || return 1
    echo "$value"
}

ui_texts_in() {
    xmllint --xpath '//node[@text!="" or @content-desc!=""]' - <<<"$1" 2>/dev/null |
        grep -oE '(text|content-desc)="[^"]+"' | sed -E 's/^(text|content-desc)="(.*)"$/\2/'
}

ui_texts() {
    local xml
    xml=$(ui_dump) || return 1
    ui_texts_in "$xml"
}

ui_grep_text() { ui_texts | grep -E "$1"; }

ui_wait() {
    local text=$1 timeout_s=${2:-15} deadline=$((SECONDS + ${2:-15}))
    until ui_has "$text"; do
        ((SECONDS < deadline)) || { echo "ui: timed out after ${timeout_s}s waiting for '$text'" >&2; return 1; }
        sleep "$UI_POLL_S"
    done
}

ui_gone() {
    local text=$1 timeout_s=${2:-15} deadline=$((SECONDS + ${2:-15}))
    while ui_has "$text"; do
        ((SECONDS < deadline)) || { echo "ui: timed out after ${timeout_s}s waiting for '$text' to go" >&2; return 1; }
        sleep "$UI_POLL_S"
    done
}

ui_remember_dump() {
    printf '%s\n' "$1" >"$UI_LAST_DUMP_FILE"
    echo "$EPOCHSECONDS" >"$UI_LAST_DUMP_FILE.at"
}

# ui_last_dump_fresh: the latest dump is recent enough to count as the first of two samples.
ui_last_dump_fresh() {
    [[ -f $UI_LAST_DUMP_FILE && -f $UI_LAST_DUMP_FILE.at ]] && ((EPOCHSECONDS - $(<"$UI_LAST_DUMP_FILE.at") <= UI_LAST_DUMP_REUSE_S))
}

# ui_forget_dump: input changes the screen, so the latest dump no longer vouches for positions.
ui_forget_dump() { rm -f "$UI_LAST_DUMP_FILE" "$UI_LAST_DUMP_FILE.at"; }

# ui_settled_center TEXT [INDEX]: the node's center once two consecutive dumps agree on it (the
# latest dump counts as the first one when it is recent).
# ui_settled_center TEXT INDEX DEADLINE: waits (until SECONDS reaches DEADLINE, at most
# UI_TAP_SETTLE_TRIES_MAX dumps) for the node to show and hold still.
ui_settled_center() {
    local text=$1 index=$2 deadline=$3 previous="" point try
    if ui_last_dump_fresh; then previous=$(ui_center_in "$(<"$UI_LAST_DUMP_FILE")" "$text" "$index") || previous=""; fi
    for ((try = 1; try <= UI_TAP_SETTLE_TRIES_MAX; try++)); do
        if point=$(ui_center "$text" "$index"); then
            [[ $point == "$previous" ]] && { echo "$point"; return 0; }
        fi
        previous=$point
        ((SECONDS < deadline)) || break
        [[ -n $point ]] || sleep "$UI_POLL_S"
    done
    if [[ -z $point ]]; then
        echo "ui: no node matches '$text' (waited ${UI_TAP_WAIT_S}s)" >&2
    else
        echo "ui: '$text' did not hold still (last at $point)" >&2
    fi
    return 1
}

ui_tap() {
    local point
    point=$(ui_settled_center "$1" "${2:-1}" $((SECONDS + UI_TAP_WAIT_S))) || return 1
    ui_forget_dump
    # shellcheck disable=SC2086
    adb shell input tap $point
}

# ui_row_switch TEXT: prints the checkable node (switch, checkbox) nearest to TEXT vertically, within
# UI_ROW_DISTANCE_MAX_PX of its center: the switch on TEXT's row (Compose switches carry no text).
ui_row_switch() {
    local text=$1 xml point label_y
    xml=$(ui_dump) || return 1
    point=$(ui_center_in "$xml" "$text") || { echo "ui: no node matches '$text'" >&2; return 1; }
    label_y=${point#* }
    grep -oE '<node [^>]*checkable="true"[^>]*>' <<<"$xml" | awk -v y="$label_y" -v max="$UI_ROW_DISTANCE_MAX_PX" '
        {
            match($0, /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/)
            split(substr($0, RSTART + 8, RLENGTH - 9), n, /[^0-9]+/)
            distance = (n[3] + n[5]) / 2 - y
            if (distance < 0) distance = -distance
            if (distance <= max && (best == "" || distance < best)) { best = distance; node = $0 }
        }
        END { if (node == "") exit 1; print node }'
}

ui_toggle() {
    local node bounds numbers previous="" try settled=false
    for ((try = 1; try <= UI_TAP_SETTLE_TRIES_MAX; try++)); do
        node=$(ui_row_switch "$1") || { echo "ui: no switch on the row of '$1'" >&2; return 1; }
        bounds=$(grep -oE 'bounds="[^"]+"' <<<"$node" | cut -d'"' -f2)
        [[ $bounds == "$previous" ]] && { settled=true; break; }
        previous=$bounds
    done
    $settled || { echo "ui: the switch on the row of '$1' did not hold still" >&2; return 1; }
    read -r -a numbers <<<"${bounds//[\[\],]/ }"
    ui_forget_dump
    adb shell input tap $(((numbers[0] + numbers[2]) / 2)) $(((numbers[1] + numbers[3]) / 2))
}

ui_checked() {
    local node
    node=$(ui_row_switch "$1" 2>/dev/null) || return 1
    [[ $node == *'checked="true"'* ]]
}

ui_screen_size() {
    [[ -n $UI_SCREEN_SIZE ]] || UI_SCREEN_SIZE=$(adb shell wm size | awk -F'[ x]' '/Physical/ { print $3 "x" $4 }')
    echo "$UI_SCREEN_SIZE"
}

ui_swipe_up() {
    local size width height
    size=$(ui_screen_size)
    width=${size%x*} height=${size#*x}
    ui_forget_dump
    adb shell input swipe $((width / 2)) $((height * 6 / 10)) $((width / 2)) $((height * 4 / 10)) 300
}

ui_scroll_to() {
    local text=$1 swipes_max=${2:-8} swipe size width height
    size=$(ui_screen_size)
    width=${size%x*} height=${size#*x}
    for ((swipe = 0; swipe <= swipes_max; swipe++)); do
        ui_has "$text" && return 0
        ui_forget_dump
        adb shell input swipe $((width / 2)) $((height * 7 / 10)) $((width / 2)) $((height * 3 / 10)) 300
    done
    echo "ui: '$text' not found after $swipes_max swipes" >&2
    return 1
}

ui_shot() {
    local dir=${BOOTLEG_SHOTS_DIR:-build/e2e-shots} file
    mkdir -p "$dir"
    file=$dir/$1.png
    adb exec-out screencap -p >"$file"
    sips -Z 900 "$file" >/dev/null
    echo "$file"
}

ui_main() {
    local command=${1:?usage: scripts/ui.sh texts|has|attr|grep-text|tap|toggle|checked|wait|gone|scroll-to|swipe-up|shot|dump ...}
    shift
    case $command in
        texts) ui_texts ;;
        has) ui_has "$1" ;;
        attr) ui_attr "$@" ;;
        grep-text) ui_grep_text "$1" ;;
        tap) ui_tap "$@" ;;
        toggle) ui_toggle "$1" ;;
        checked) ui_checked "$1" ;;
        wait) ui_wait "$@" ;;
        gone) ui_gone "$@" ;;
        scroll-to) ui_scroll_to "$@" ;;
        swipe-up) ui_swipe_up ;;
        shot) ui_shot "$1" ;;
        dump) ui_dump ;;
        *) echo "ui: unknown command '$command'" >&2; return 2 ;;
    esac
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    set -euo pipefail
    ui_main "$@"
fi
