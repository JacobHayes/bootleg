# shellcheck shell=bash disable=SC2034 # sourced by scripts/e2e-ui.sh, which uses the variables
# HID tests (Control tab): Keyboard & mouse on -> the Mac enumerates a keyboard and a mouse; F24
# from the F13–F24 page; the Shift chip latches for one key (Shift+F24); the boot-key sheet renders
# its presets (no key is run); off -> both gone; Settings "Compatible mouse (no wheel)" (flipped
# with HID off) exposes the 3-byte boot mouse function and the Control hint drops scrolling.
#
# Harmless input only: nothing but the enable switch, the Shift chip, the F13–F24 toggle, F24 and
# the boot-key sheet (opened, scrolled by its description text, closed with Back) is touched. Never
# the touchpad, the typing field, the click buttons or a boot-key action. Sourced by scripts/e2e-ui.sh.

define_test hid.enable-disable "Keyboard & mouse on: Mac enumerates keyboard + mouse; off: both gone, no HID function left"
define_test hid.f24 "F24 from the F13–F24 page reaches the host (reports written)"
define_test hid.shift-latch "Shift chip latches for one key: Shift+F24 sent, then released"
define_test hid.boot-key-sheet "boot-key sheet renders firmware presets and durations; one Back closes it"
define_test hid.compatible-mouse "Compatible mouse (no wheel): persisted, HID on links hid.bootleg_mouse (not the wheel mouse), hint drops scrolling; restored"

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

# send_f24: selects the F13–F24 page of the function-key grid ("More function keys"; selecting it
# again is a no-op) and taps F24. Nothing in the key panel scrolls; a missing toggle is reported.
send_f24() {
    has_text "More function keys" || return 1
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
    check "F13–F24 button reachable without scrolling the key row" has_text "More function keys"
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
    on_cleanup "call HID_OFF >/dev/null; call MOUSE_LAYOUT --extra compatible:b:$before >/dev/null"
    step "compatible mouse on (HID off)"
    check "Compatible mouse persisted as on" set_compatible_mouse true
    shot settings-compatible
    tab Control
    hid_enable
    check "the boot mouse function is linked, not the wheel mouse" wait_until 10 linked_mouse_is hid.bootleg_mouse
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
