#!/usr/bin/env bash
# E2E (UI) flow 5: Settings. Flip "Wi-Fi only" and "Restore drives after reboot", check the UI and
# the SharedPreferences file, force-stop and relaunch the app, check both survived, then put them
# back as they were. The phone is never rebooted (that would need the user to unlock it); boot
# restore itself is covered by BootManifestTest (BOOT_COMPLETED arrives only after the unlock).
# Usage: scripts/e2e-ui-settings.sh
FLOW=settings
source "$(dirname "$0")/e2e-ui-lib.sh"

WIFI="Wi-Fi only"
RESTORE="Restore drives after reboot"

pref() { adb shell run-as "$PACKAGE" cat shared_prefs/bootleg.xml 2>/dev/null | tr -d '\r' | grep -o "name=\"$1\" value=\"[a-z]*\"" | sed 's/.*value="\(.*\)"/\1/'; }
pref_is() { [[ $(pref "$1") == "$2" ]]; }
is_on() { ui checked "$1"; }
state_of() { if is_on "$1"; then echo true; else echo false; fi; }

take_lock
launch_app
tab Settings
require "Settings shows both switches" bash -c "scripts/ui.sh has '$WIFI' && scripts/ui.sh has '$RESTORE'"
wifi_before=$(state_of "$WIFI")
restore_before=$(state_of "$RESTORE")
log "before: wifi-only=$wifi_before restore=$restore_before"
on_cleanup "tab Settings; [[ \$(state_of '$WIFI') == $wifi_before ]] || ui toggle '$WIFI'; [[ \$(state_of '$RESTORE') == $restore_before ]] || ui toggle '$RESTORE'"
wifi_want=$([[ $wifi_before == true ]] && echo false || echo true)
restore_want=$([[ $restore_before == true ]] && echo false || echo true)

step "flip both"
ui toggle "$WIFI"
ui toggle "$RESTORE"
sleep 0.5
shot flipped
check "Wi-Fi only switch is $wifi_want" test "$(state_of "$WIFI")" == "$wifi_want"
check "restore switch is $restore_want" test "$(state_of "$RESTORE")" == "$restore_want"
check "wifi_only_downloads persisted as $wifi_want" wait_until 5 pref_is wifi_only_downloads "$wifi_want"
check "restore_after_reboot persisted as $restore_want" wait_until 5 pref_is restore_after_reboot "$restore_want"

step "survives force-stop + relaunch"
restart_app
tab Settings
check "Wi-Fi only still $wifi_want" test "$(state_of "$WIFI")" == "$wifi_want"
check "restore still $restore_want" test "$(state_of "$RESTORE")" == "$restore_want"
shot after-restart

if [[ $wifi_want == true ]]; then
    tab Library
    ui tap Downloads 2>/dev/null || true
    sleep 0.8
    shot downloads-wifi-only
    tab Settings
fi
