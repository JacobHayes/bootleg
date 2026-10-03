#!/usr/bin/env bash
# Drives the app's UI over adb for manual end-to-end runs: find a node by its text or content
# description, tap its center, wait for it, scroll to it, take a downscaled screenshot.
#
# Usage: scripts/ui.sh COMMAND [ARGS]
#   texts                       list the visible texts / content descriptions
#   has TEXT                    exit 0 if a node matches
#   attr TEXT NAME [INDEX]      print an attribute (checked, enabled, bounds...) of the matching node
#   grep-text REGEX             print the visible texts matching an extended regex
#   tap TEXT [INDEX]            tap the center of the INDEX-th (1-based) matching node
#   toggle TEXT                 tap the switch/checkbox on TEXT's row
#   checked TEXT                exit 0 if the switch/checkbox on TEXT's row is checked
#   wait TEXT [TIMEOUT_S]       wait until a node matches (default 15s)
#   gone TEXT [TIMEOUT_S]       wait until no node matches (default 15s)
#   scroll-to TEXT [SWIPES]     swipe up until a node matches (default 8 swipes)
#   swipe-up                    scroll the list a little (one short upward swipe)
#   shot NAME                  save a screenshot (≤ 900px) to $BOOTLEG_SHOTS_DIR/NAME.png
#   dump                        print the raw uiautomator XML
#
# TEXT matches @text or @content-desc exactly; a leading '~' matches a substring instead.
# Uses $ANDROID_SERIAL (prefer the wireless serial: USB adb drops while the gadget re-enumerates).
# Never type, swipe or scroll on the Control tab: keystrokes go to the USB host and the touchpad
# moves (or clicks) its pointer. There, only tap nodes found by text (chips, keys, switches).
set -euo pipefail

SHOTS_DIR=${BOOTLEG_SHOTS_DIR:-build/e2e-shots}
POLL_S=0.5
DUMP_TRIES_MAX=3
ROW_DISTANCE_MAX_PX=200

dump() {
    local try
    for ((try = 1; try <= DUMP_TRIES_MAX; try++)); do
        # uiautomator refuses while the UI never idles (spinners); retry a few times.
        if adb shell uiautomator dump /data/local/tmp/bootleg-ui.xml >/dev/null 2>&1; then
            adb exec-out cat /data/local/tmp/bootleg-ui.xml
            return 0
        fi
        sleep "$POLL_S"
    done
    echo "ui: uiautomator dump failed $DUMP_TRIES_MAX times" >&2
    return 1
}

xpath_for() {
    local text=$1
    if [[ $text == ~* ]]; then
        text=${text#\~}
        echo "//node[contains(@text,\"$text\") or contains(@content-desc,\"$text\")]"
    else
        echo "//node[@text=\"$text\" or @content-desc=\"$text\"]"
    fi
}

# bounds TEXT [INDEX]: prints "x y" of the matching node's center.
center() {
    local text=$1 index=${2:-1} xml bounds
    xml=$(dump)
    bounds=$(xmllint --xpath "string(($(xpath_for "$text"))[$index]/@bounds)" - <<<"$xml" 2>/dev/null || true)
    [[ -n $bounds ]] || return 1
    # [x1,y1][x2,y2]
    local numbers
    read -r -a numbers <<<"$(tr '[],' '   ' <<<"$bounds")"
    echo "$(((numbers[0] + numbers[2]) / 2)) $(((numbers[1] + numbers[3]) / 2))"
}

has() { center "$1" >/dev/null; }

# attr TEXT NAME [INDEX]: prints attribute NAME (checked, enabled, bounds...) of the matching node.
attr() {
    local value
    value=$(dump | xmllint --xpath "string(($(xpath_for "$1"))[${3:-1}]/@$2)" - 2>/dev/null || true)
    [[ -n $value ]] || return 1
    echo "$value"
}

# grep-text REGEX: prints the visible texts / content descriptions matching an extended regex.
grep_text() { texts | grep -E "$1"; }

wait_for() {
    local text=$1 timeout_s=${2:-15} deadline=$((SECONDS + ${2:-15}))
    until has "$text"; do
        ((SECONDS < deadline)) || { echo "ui: timed out after ${timeout_s}s waiting for '$text'" >&2; return 1; }
        sleep "$POLL_S"
    done
}

wait_gone() {
    local text=$1 timeout_s=${2:-15} deadline=$((SECONDS + ${2:-15}))
    while has "$text"; do
        ((SECONDS < deadline)) || { echo "ui: timed out after ${timeout_s}s waiting for '$text' to go" >&2; return 1; }
        sleep "$POLL_S"
    done
}

tap() {
    local point
    point=$(center "$1" "${2:-1}") || { echo "ui: no node matches '$1'" >&2; return 1; }
    # shellcheck disable=SC2086
    adb shell input tap $point
}

# row_switch TEXT: prints the checkable node (switch, checkbox) nearest to TEXT vertically, within
# ROW_DISTANCE_MAX_PX of its center: the switch on TEXT's row (Compose switches carry no text).
row_switch() {
    local text=$1 xml point label_y
    xml=$(dump)
    point=$(center "$text") || { echo "ui: no node matches '$text'" >&2; return 1; }
    label_y=${point#* }
    grep -oE '<node [^>]*checkable="true"[^>]*>' <<<"$xml" | awk -v y="$label_y" -v max="$ROW_DISTANCE_MAX_PX" '
        {
            match($0, /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/)
            split(substr($0, RSTART + 8, RLENGTH - 9), n, /[^0-9]+/)
            distance = (n[3] + n[5]) / 2 - y
            if (distance < 0) distance = -distance
            if (distance <= max && (best == "" || distance < best)) { best = distance; node = $0 }
        }
        END { if (node == "") exit 1; print node }'
}

# toggle TEXT: taps the switch on TEXT's row.
toggle() {
    local node bounds numbers
    node=$(row_switch "$1") || { echo "ui: no switch on the row of '$1'" >&2; return 1; }
    bounds=$(grep -oE 'bounds="[^"]+"' <<<"$node" | cut -d'"' -f2)
    read -r -a numbers <<<"$(tr '[],' '   ' <<<"$bounds")"
    adb shell input tap $(((numbers[0] + numbers[2]) / 2)) $(((numbers[1] + numbers[3]) / 2))
}

# checked TEXT: exit 0 if the switch on TEXT's row is checked.
checked() {
    local node
    node=$(row_switch "$1" 2>/dev/null) || return 1
    [[ $node == *'checked="true"'* ]]
}

swipe_up() {
    local size width height
    size=$(adb shell wm size | awk -F'[ x]' '/Physical/ { print $3 "x" $4 }')
    width=${size%x*} height=${size#*x}
    adb shell input swipe $((width / 2)) $((height * 6 / 10)) $((width / 2)) $((height * 4 / 10)) 300
    sleep "$POLL_S"
}

scroll_to() {
    local text=$1 swipes_max=${2:-8} swipe size width height
    size=$(adb shell wm size | awk -F'[ x]' '/Physical/ { print $3 "x" $4 }')
    width=${size%x*} height=${size#*x}
    for ((swipe = 0; swipe <= swipes_max; swipe++)); do
        has "$text" && return 0
        adb shell input swipe $((width / 2)) $((height * 7 / 10)) $((width / 2)) $((height * 3 / 10)) 300
        sleep "$POLL_S"
    done
    echo "ui: '$text' not found after $swipes_max swipes" >&2
    return 1
}

texts() {
    dump | xmllint --xpath '//node[@text!="" or @content-desc!=""]' - 2>/dev/null |
        grep -oE '(text|content-desc)="[^"]+"' | sed -E 's/^(text|content-desc)="(.*)"$/\2/'
}

shot() {
    mkdir -p "$SHOTS_DIR"
    local file=$SHOTS_DIR/$1.png
    adb exec-out screencap -p >"$file"
    sips -Z 900 "$file" >/dev/null
    echo "$file"
}

command=${1:?usage: scripts/ui.sh texts|has|tap|wait|gone|scroll-to|shot|dump ...}
shift
case $command in
    texts) texts ;;
    has) has "$1" ;;
    attr) attr "$@" ;;
    grep-text) grep_text "$1" ;;
    tap) tap "$@" ;;
    toggle) toggle "$1" ;;
    checked) checked "$1" ;;
    wait) wait_for "$@" ;;
    gone) wait_gone "$@" ;;
    scroll-to) scroll_to "$@" ;;
    swipe-up) swipe_up ;;
    shot) shot "$1" ;;
    dump) dump ;;
    *) echo "ui: unknown command '$command'" >&2; exit 2 ;;
esac
