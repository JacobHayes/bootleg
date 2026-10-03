# shellcheck shell=bash disable=SC2034 # sourced by scripts/e2e-ui.sh, which uses the variables
# Settings test: flip "Wi-Fi only" and "Restore drives after reboot", check the UI and the
# SharedPreferences file, force-stop and relaunch the app, check both survived, then put them back
# as they were. The phone is never rebooted (that would need the user to unlock it); boot restore
# itself is covered by BootManifestTest (BOOT_COMPLETED arrives only after the unlock). Sourced by
# scripts/e2e-ui.sh.

define_test settings.persist "Wi-Fi only + Restore drives after reboot: flipped, persisted, survive a force-stop; restored"

SETTINGS_WIFI="Wi-Fi only"
SETTINGS_RESTORE="Restore drives after reboot"

pref() { adb shell run-as "$PACKAGE" cat shared_prefs/bootleg.xml 2>/dev/null | tr -d '\r' | grep -o "name=\"$1\" value=\"[a-z]*\"" | sed 's/.*value="\(.*\)"/\1/'; }
pref_is() { [[ $(pref "$1") == "$2" ]]; }
state_of() { if ui_checked "$1"; then echo true; else echo false; fi; }
switch_is() { [[ $(state_of "$1") == "$2" ]]; }
both_switches_shown() { has_text "$SETTINGS_WIFI" && has_text "$SETTINGS_RESTORE"; }

# set_switch LABEL true|false: toggles LABEL's switch if needed and waits until it shows the value.
set_switch() {
    switch_is "$1" "$2" && return 0
    ui toggle "$1"
    wait_until 5 switch_is "$1" "$2"
}

test_settings_persist() {
    local wifi_before restore_before wifi_want restore_want
    tab Settings
    require "Settings shows both switches" both_switches_shown
    wifi_before=$(state_of "$SETTINGS_WIFI")
    restore_before=$(state_of "$SETTINGS_RESTORE")
    log "before: wifi-only=$wifi_before restore=$restore_before"
    on_cleanup "launch_app; tab Settings; set_switch '$SETTINGS_WIFI' $wifi_before; set_switch '$SETTINGS_RESTORE' $restore_before"
    wifi_want=$([[ $wifi_before == true ]] && echo false || echo true)
    restore_want=$([[ $restore_before == true ]] && echo false || echo true)

    step "flip both"
    ui toggle "$SETTINGS_WIFI"
    ui toggle "$SETTINGS_RESTORE"
    shot flipped
    check "Wi-Fi only switch is $wifi_want" wait_until 5 switch_is "$SETTINGS_WIFI" "$wifi_want"
    check "restore switch is $restore_want" wait_until 5 switch_is "$SETTINGS_RESTORE" "$restore_want"
    check "wifi_only_downloads persisted as $wifi_want" wait_until 5 pref_is wifi_only_downloads "$wifi_want"
    check "restore_after_reboot persisted as $restore_want" wait_until 5 pref_is restore_after_reboot "$restore_want"

    step "survives force-stop + relaunch"
    restart_app
    tab Settings
    check "Wi-Fi only still $wifi_want" switch_is "$SETTINGS_WIFI" "$wifi_want"
    check "restore still $restore_want" switch_is "$SETTINGS_RESTORE" "$restore_want"
    shot after-restart

    if [[ $wifi_want == true ]]; then
        tab Library
        ui tap Downloads 2>/dev/null || true
        ui wait Downloads 5 || true
        shot downloads-wifi-only
    fi
}
