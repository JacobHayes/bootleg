# shellcheck shell=bash disable=SC2034 # sourced by scripts/e2e-ui.sh, which uses the variables
# HID tests (Control tab): Keyboard & mouse on -> the Mac enumerates a keyboard and a mouse; F24
# from the F13–F24 page; the Shift chip latches for one key (Shift+F24); the boot-key sheet renders
# its presets (no key is run); the Type pane opens the IME with the field right above it; off ->
# both gone; Settings "Compatible mouse (no wheel)" (flipped with HID off) exposes the 3-byte boot
# mouse function and the Pointer pane's hint drops scrolling.
#
# Harmless input only: nothing but the enable switch, the pane selector, the Shift chip, the
# F13–F24 toggle, F24, the boot-key sheet (opened, scrolled by its description text, closed with
# Back) and the typing field's focus (the IME opens, nothing is typed; Back closes it) is touched.
# Never the touchpad, a key typed into the field, the click buttons or a boot-key action. Sourced by
# scripts/e2e-ui.sh.

define_test hid.enable-disable "Keyboard & mouse on: Mac enumerates keyboard + mouse; off: both gone, no HID function left"
define_test hid.f24 "F24 from the F13–F24 page reaches the host (reports written)"
define_test hid.shift-latch "Shift chip latches for one key: Shift+F24 sent, then released"
define_test hid.boot-key-sheet "boot-key sheet renders firmware presets and durations; one Back closes it"
define_test hid.compatible-mouse "Compatible mouse (no wheel): persisted, HID on links hid.bootleg_mouse (not the wheel mouse), hint drops scrolling; restored"
define_test hid.type-ime "Type pane: the IME opens with the field and pane selector directly above it and no bottom nav; Back closes it and clears focus; Keys hides it"

HID_ENUMERATE_WAIT_S=15

hid_written() { call HID_STATE | jq '.written'; }
host_has_hid() { [[ -n $(host_hid_rows "$HID_VENDOR_ID" 1 6) && -n $(host_hid_rows "$HID_VENDOR_ID" 1 2) ]]; }
host_has_no_hid() { [[ -z $(host_hid_rows "$HID_VENDOR_ID" 1 6) && -z $(host_hid_rows "$HID_VENDOR_ID" 1 2) ]]; }
hid_enabled() { ui_checked "Keyboard & mouse"; }
hid_disabled() { ! ui_checked "Keyboard & mouse"; }
no_hid_function() { [[ $(root 'ls /config/usb_gadget/g1/functions' | grep -c hid.bootleg) == 0 ]]; }
hid_reports_written_since() { (($(hid_written) >= $1 + 2)); }

# hid_test_start: Control tab with Keyboard & mouse off, and its cleanup (HID off).
hid_test_start() {
    HID_VENDOR_ID=$(gadget_vendor_id)
    # The next test starts by requiring that the Mac sees no Bootleg HID: wait for the drop too.
    on_cleanup "[[ \$(call HID_STATE | jq '.keyboard or .mouse') == false ]] || call HID_OFF >/dev/null; wait_until \$HID_ENUMERATE_WAIT_S host_has_no_hid"
    require "no Bootleg keyboard/mouse enumerated before the test" host_has_no_hid
    tab Control
    require "Control tab shows the switch" has_text "Keyboard & mouse"
}

hid_enable() {
    if ! hid_enabled; then ui toggle "Keyboard & mouse"; fi
    check "switch on" wait_until 10 hid_enabled
    check "Mac enumerates keyboard + mouse" wait_until "$HID_ENUMERATE_WAIT_S" host_has_hid
    check "status leaves Reconnecting USB…" wait_until 15 not_text "Reconnecting USB…"
}

# send_f24: selects the Keys pane and its F13–F24 page ("More function keys"; selecting either
# again is a no-op) and taps F24. The function keys never scroll; a missing toggle is reported.
send_f24() {
    ui tap Keys || return 1
    ui wait "More function keys" 5 || return 1
    ui tap "More function keys"
    ui wait F24 5
    shot overflow
    ui tap F24
}

test_hid_enable_disable() {
    hid_test_start
    hid_enable
    shot enabled
    step "disable"
    ui toggle "Keyboard & mouse" || true
    check "switch off" wait_until 15 hid_disabled
    check "Mac drops keyboard + mouse" wait_until "$HID_ENUMERATE_WAIT_S" host_has_no_hid
    check "no Bootleg HID function left" wait_until 10 no_hid_function
    shot disabled
}

test_hid_f24() {
    local before
    hid_test_start
    hid_enable
    ui tap Keys
    check "F13–F24 button reachable without scrolling the key row" wait_until 5 has_text "More function keys"
    if has_text "More function keys"; then
        before=$(hid_written)
        send_f24
        check "F24 sent (reports written from $before)" wait_until 5 hid_reports_written_since "$before"
    fi
}

test_hid_shift_latch() {
    local before
    hid_test_start
    hid_enable
    ui tap Keys
    ui tap Shift
    check "Shift chip shows latched (Shift·)" wait_until 5 has_text "Shift·"
    shot shift-latched
    if has_text "More function keys"; then
        before=$(hid_written)
        send_f24
        check "Shift+F24 sent (reports written from $before)" wait_until 5 hid_reports_written_since "$before"
        check "latched Shift released after one key" wait_until 5 not_text "Shift·"
    else
        # Latched -> locked (Shift down on the host) -> off (Shift up): a plain Shift tap.
        before=$(hid_written)
        ui tap Shift
        shot shift-locked
        ui tap Shift
        check "Shift lock + unlock sent a Shift tap (reports written from $before)" wait_until 5 hid_reports_written_since "$before"
        check "Shift chip back to off" wait_until 5 not_text "Shift·"
    fi
    check "no reports dropped" test "$(call HID_STATE | jq '.dropped')" == 0
}

HID_COMPATIBLE_MOUSE="Compatible mouse (no wheel)"

compatible_pref_is() { [[ $(adb shell run-as "$PACKAGE" cat shared_prefs/bootleg.xml 2>/dev/null | tr -d '\r' | grep -o 'name="compatible_mouse" value="[a-z]*"' | sed 's/.*value="\(.*\)"/\1/') == "$1" ]]; }
linked_mouse_is() { [[ $(root 'ls /config/usb_gadget/g1/configs/b.1' | grep -E '^hid\.bootleg_mouse' || true) == "$1" ]]; }

# set_compatible_mouse true|false: flips the Settings switch (HID must be off, so nothing is rebuilt).
set_compatible_mouse() {
    tab Settings
    has_text "$HID_COMPATIBLE_MOUSE" || ui scroll-to "$HID_COMPATIBLE_MOUSE" 3 || return 1
    if [[ $(if ui_checked "$HID_COMPATIBLE_MOUSE"; then echo true; else echo false; fi) != "$1" ]]; then ui toggle "$HID_COMPATIBLE_MOUSE"; fi
    wait_until 5 compatible_pref_is "$1"
}

test_hid_compatible_mouse() {
    local before
    hid_test_start
    before=$(call HID_STATE | jq -r '.compatibleMouse')
    # HID off first (cleanups run newest first): the setting must never rebuild a live mouse here.
    # The setting goes back only once HID reads as off, so it never swaps a live mouse function.
    on_cleanup "call HID_OFF >/dev/null; [[ \$(call HID_STATE | jq '.keyboard or .mouse') == false ]] && call MOUSE_LAYOUT --extra compatible:b:$before >/dev/null"
    step "compatible mouse on (HID off)"
    check "Compatible mouse persisted as on" set_compatible_mouse true
    shot settings-compatible
    tab Control
    hid_enable
    check "the boot mouse function is linked, not the wheel mouse" wait_until 10 linked_mouse_is hid.bootleg_mouse
    ui tap Pointer
    check "touchpad hint says there is no scrolling" has_text "~Compatible mouse: no scrolling"
    shot control-compatible
    step "HID off, compatible mouse off, HID on"
    ui toggle "Keyboard & mouse" || true
    check "switch off" wait_until 15 hid_disabled
    check "Mac drops keyboard + mouse" wait_until "$HID_ENUMERATE_WAIT_S" host_has_no_hid
    check "Compatible mouse persisted as off" set_compatible_mouse false
    tab Control
    hid_enable
    check "the wheel mouse function is linked" wait_until 10 linked_mouse_is hid.bootleg_mouse_wheel
    ui tap Pointer
    check "touchpad hint offers two-finger scrolling" has_text "~Two-finger drag to scroll"
    shot control-wheel
}

boot_sheet_closed() { not_text "Boot menu"; }
durations_listed() { ui_grep_text '^[0-9]+ s$' >/dev/null; }

test_hid_boot_key_sheet() {
    local bounds corners drag_x drag_y
    hid_test_start
    hid_enable
    ui tap "Boot keys"
    check "sheet opens" wait_until 5 has_text "Boot menu"
    # Scroll the sheet with a drag that starts on its description text (never on a key or action).
    if bounds=$(ui attr "~Start an action" bounds); then
        read -r -a corners <<<"${bounds//[\[\],]/ }"
        drag_x=$(((corners[0] + corners[2]) / 2)) drag_y=$(((corners[1] + corners[3]) / 2))
        adb shell input swipe "$drag_x" "$drag_y" "$drag_x" $((drag_y - 500)) 500
    fi
    check "firmware setup group" wait_until 3 has_text "Firmware setup"
    check "vendor presets listed (≥ 4 chips)" test "$(ui_texts | grep -cE '^(Generic|Dell|HP|Lenovo|ASUS|Acer|MSI|Gigabyte|Apple|Mac|Microsoft Surface|Intel NUC)')" -ge 4
    check "durations listed" durations_listed
    shot boot-keys
    # Back (a phone key, not a HID report) should dismiss the sheet. Seen: once the sheet was
    # dragged to full height, the first Back only returned it to half height.
    adb shell input keyevent KEYCODE_BACK
    check "one Back dismisses the (expanded) boot-key sheet" wait_until 3 boot_sheet_closed
    if has_text "Boot menu"; then adb shell input keyevent KEYCODE_BACK; wait_until 3 boot_sheet_closed || true; fi
    if has_text "Close sheet"; then UI_TAP_WAIT_S=0 ui tap "Close sheet" || true; wait_until 3 boot_sheet_closed || true; fi # the scrim, a separate window over the touchpad
    check "sheet closed" boot_sheet_closed
}

# The IME window's top edge in pixels: the touchable region inside the InputMethod window's block
# (other windows list their own regions first). Fails when there's none (IME hidden).
ime_top() {
    adb shell dumpsys window windows | awk '
        /^ *Window #[0-9]+ Window\{/ { ime = /InputMethod\}/ }
        ime && match($0, /touchable region=SkRegion\(\([0-9]+,[0-9]+/) {
            top = substr($0, RSTART, RLENGTH); sub(/.*,/, "", top); print top; found = 1; exit
        }
        END { exit !found }'
}
typing_field_node() { ui_dump | grep -m1 -oE '<node [^>]*class="android.widget.EditText"[^>]*>'; }
# Both need the field in a fresh dump: a failed dump or a missing field is neither focused nor unfocused.
typing_field_focused() {
    local node
    node=$(typing_field_node) || return 1
    [[ $node == *'focused="true"'* ]]
}
typing_field_unfocused() {
    local node
    node=$(typing_field_node) || return 1
    [[ $node == *'focused="false"'* ]]
}
bottom_of_bounds() { grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+' | grep -oE '[0-9]+$'; }

# selector_just_above_ime: the pane selector (the lowest row) ends within IME_GAP_MAX_PX above the
# IME, so nothing hides behind it and there's no dead space between them.
IME_GAP_MAX_PX=200
selector_just_above_ime() {
    local top bottom
    top=$(ime_top) && bottom=$(ui attr Pointer bounds | grep -oE '[0-9]+\]$' | tr -d ']') || return 1
    ((bottom <= top && top - bottom <= IME_GAP_MAX_PX))
}
field_above_ime() {
    local top bottom
    top=$(ime_top) && bottom=$(typing_field_node | bottom_of_bounds) || return 1
    ((bottom < top))
}

test_hid_type_ime() {
    hid_test_start
    hid_enable
    # Runs before the HID-off cleanup (newest first): an abort with the IME up mustn't leave it open.
    on_cleanup "hide_keyboard"
    # Focusing the field sends nothing; no key is typed into it.
    ui tap Type
    check "selecting Type opens the IME" wait_until 5 ime_shown
    check "the typing field has focus" wait_until 3 typing_field_focused
    check "the field sits above the IME" wait_until 3 field_above_ime
    check "the pane selector sits directly above the IME (no dead space)" wait_until 3 selector_just_above_ime
    check "the bottom navigation steps aside while the IME is up" not_text Drives
    shot type-ime-open
    adb shell input keyevent KEYCODE_BACK
    check "Back closes the IME" wait_until 5 ime_hidden
    check "Back clears the field's focus" wait_until 3 typing_field_unfocused
    check "the bottom navigation is back" wait_until 3 has_text Drives
    check "still on the Type pane" has_text "Type here to send keys"
    shot type-ime-closed
    ui tap Type
    check "selecting Type again reopens the IME" wait_until 5 ime_shown
    ui tap Keys
    check "selecting Keys hides the IME" wait_until 5 ime_hidden
    check "Keys pane shown" wait_until 3 has_text "More function keys"
}
