#!/usr/bin/env bash
# E2E (UI) flow 4: Control tab. Turn on Keyboard & mouse -> the Mac enumerates a keyboard and a
# mouse; open the F13–F24 overflow and tap F24; tap the Shift chip (latched) then F24 (Shift+F24);
# open the boot-key sheet and check its presets render (no key is run); turn it off -> both gone.
#
# Harmless input only: the script taps nothing but the enable switch, the Shift chip, the F13–F24
# button, F24 and boot-key vendor chips (selection only). It never touches the touchpad, the typing
# field, the click buttons or a boot-key action.
# Usage: scripts/e2e-ui-hid.sh
FLOW=hid
source "$(dirname "$0")/e2e-ui-lib.sh"

ENUMERATE_WAIT_S=15

hid_written() { call HID_STATE | jq '.written'; }
host_has_hid() { [[ -n $(host_hid_rows "$VENDOR_ID" 1 6) && -n $(host_hid_rows "$VENDOR_ID" 1 2) ]]; }
host_has_no_hid() { [[ -z $(host_hid_rows "$VENDOR_ID" 1 6) && -z $(host_hid_rows "$VENDOR_ID" 1 2) ]]; }
hid_enabled() { ui checked "Keyboard & mouse"; }

take_lock
on_cleanup "[[ \$(call HID_STATE | jq '.keyboard or .mouse') == false ]] || call HID_OFF >/dev/null"
launch_app
VENDOR_ID=$(gadget_vendor_id)
require "no Bootleg keyboard/mouse enumerated before the test" host_has_no_hid

step "enable"
tab Control
require "Control tab shows the switch" has_text "Keyboard & mouse"
if ! hid_enabled; then ui toggle "Keyboard & mouse"; fi
check "switch on" wait_until 10 hid_enabled
check "Mac enumerates keyboard + mouse" wait_until "$ENUMERATE_WAIT_S" host_has_hid
check "status leaves Reconnecting USB…" wait_until 15 not_text "Reconnecting USB…"
shot enabled

# send_f24: taps F13–F24 -> F24 if the overflow button is on screen. It sits at the end of a
# horizontally scrolled key row; scrolling that row means swiping across keys (Ctrl+Alt+Del,
# F1...), which this flow never does, so an off-screen button is reported, not reached.
send_f24() {
    has_text "More function keys" || return 1
    ui tap "More function keys"
    sleep 0.6
    shot overflow
    ui tap F24
    sleep 1
}

step "F24 from the F13–F24 overflow"
check "F13–F24 button reachable without scrolling the key row" has_text "More function keys"
if has_text "More function keys"; then
    before=$(hid_written)
    send_f24
    after=$(hid_written)
    check "F24 sent (reports written $before -> $after)" test "$after" -ge "$((before + 2))"
fi

step "Shift chip"
ui tap Shift
sleep 0.5
shot shift-latched
check "Shift chip shows latched (Shift·)" has_text "Shift·"
if has_text "More function keys"; then
    before=$(hid_written)
    send_f24
    after=$(hid_written)
    check "Shift+F24 sent (reports written $before -> $after)" test "$after" -ge "$((before + 2))"
    check "latched Shift released after one key" not_text "Shift·"
else
    # Latched -> locked (Shift down on the host) -> off (Shift up): a plain Shift tap.
    before=$(hid_written)
    ui tap Shift
    sleep 0.5
    shot shift-locked
    ui tap Shift
    sleep 0.8
    after=$(hid_written)
    check "Shift lock + unlock sent a Shift tap (reports written $before -> $after)" test "$after" -ge "$((before + 2))"
    check "Shift chip back to off" bash -c "! scripts/ui.sh has 'Shift·'"
fi
check "no reports dropped" test "$(call HID_STATE | jq '.dropped')" == 0

step "boot-key sheet (render only)"
ui tap "Boot keys"
check "sheet opens" wait_until 5 has_text "Boot menu"
# Scroll the sheet with a drag that starts on its description text (never on a key or action).
if bounds=$(ui attr "~Start an action" bounds); then
    read -r -a corners <<<"$(tr '[],' '   ' <<<"$bounds")"
    drag_x=$(((corners[0] + corners[2]) / 2)) drag_y=$(((corners[1] + corners[3]) / 2))
    adb shell input swipe "$drag_x" "$drag_y" "$drag_x" $((drag_y - 500)) 500
    sleep 0.8
fi
check "firmware setup group" has_text "Firmware setup"
check "vendor presets listed (≥ 4 chips)" test "$(ui texts | grep -cE '^(Generic|Dell|HP|Lenovo|ASUS|Acer|MSI|Gigabyte|Apple|Mac|Microsoft Surface|Intel NUC)')" -ge 4
check "durations listed" bash -c "scripts/ui.sh grep-text '^[0-9]+ s$' >/dev/null"
shot boot-keys
# Back (a phone key, not a HID report) should dismiss the sheet. Seen: once the sheet was
# dragged to full height, the first Back only returns it to half height.
adb shell input keyevent KEYCODE_BACK
sleep 1
check "one Back dismisses the (expanded) boot-key sheet" not_text "Boot menu"
if has_text "Boot menu"; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi
if has_text "Close sheet"; then ui tap "Close sheet"; sleep 1; fi # the scrim, a separate window over the touchpad
check "sheet closed" not_text "Boot menu"

step "disable"
ui toggle "Keyboard & mouse" || true
check "switch off" wait_until 15 bash -c "! scripts/ui.sh checked 'Keyboard & mouse'"
check "Mac drops keyboard + mouse" wait_until "$ENUMERATE_WAIT_S" host_has_no_hid
check "no Bootleg HID function left" wait_until 10 bash -c "[[ \$(adb shell \"su -c 'ls /config/usb_gadget/g1/functions'\" | grep -c hid.bootleg) == 0 ]]"
shot disabled
