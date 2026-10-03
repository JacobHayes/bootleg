#!/usr/bin/env bash
# E2E (UI) flow 8: Remote screen. Turn Remote on with its switch -> Tailscale shows Running with
# the node's name (an already enrolled node; if it asks to sign in, the flow stops there and says
# so, it never completes a browser login). Offline mode: Start offline hotspot -> the network
# name, QR code and URL render and Go's listener is bound on the hotspot address (ss on the
# phone); Show pairing code -> a 6-digit code; Stop offline hotspot -> the listener is gone.
# Remote off at the end. This Mac never joins the hotspot.
#
# Runs over USB adb: the hotspot may take the phone's Wi-Fi radio, and nothing here re-enumerates USB.
# NEARBY_WIFI_DEVICES and ACCESS_LOCAL_NETWORK are pre-granted with pm grant (their system dialogs
# are not what this flow tests).
# Usage: scripts/e2e-ui-remote.sh
FLOW=remote
ADB_TRANSPORT=usb
source "$(dirname "$0")/e2e-ui-lib.sh"

NODE_WAIT_S=45
HOTSPOT_WAIT_S=25
PORT=8080

remote_running() { [[ $(call REMOTE_STATE | jq -r '.running and .backendState == "Running"') == true ]]; }
remote_stopped() { [[ $(call REMOTE_STATE | jq -r '.running') == false ]]; }
switch_on() { ui checked "Remote control"; }
listening_on() { root "ss -ltn" | grep -qE "[[:space:]]${1}:${PORT}[[:space:]]"; }
not_listening_on() { ! listening_on "$1"; }

take_lock
on_cleanup "[[ \$(call REMOTE_STATE | jq -r .enabled) == false ]] || call REMOTE_OFF >/dev/null"
on_cleanup "scripts/ui.sh has 'Stop offline hotspot' && scripts/ui.sh tap 'Stop offline hotspot' || true"
for permission in NEARBY_WIFI_DEVICES ACCESS_LOCAL_NETWORK; do
    adb shell pm grant "$PACKAGE" "android.permission.$permission" 2>/dev/null || log "note: pm grant $permission failed"
done
launch_app

step "Remote on"
tab Remote
require "Remote screen shows its switch" has_text "Remote control"
if ! switch_on; then ui toggle "Remote control"; fi
sleep 2
shot remote-on
if has_text "Sign in to Tailscale"; then
    log "the node asks to sign in (not enrolled); not completing a browser login"
    check "node already enrolled (no sign-in asked)" false
    exit 1
fi
check "node reaches Running" wait_until "$NODE_WAIT_S" remote_running
dns_name=$(call REMOTE_STATE | jq -r '.dnsName')
check "UI shows the node name ($dns_name)" wait_until 10 has_text "$dns_name"
check "UI shows a tailscale IP" bash -c "scripts/ui.sh grep-text '100\\.[0-9]+\\.[0-9]+\\.[0-9]+' >/dev/null"
check "no Remote problem card" not_text "Remote problem"
shot remote-running

step "offline hotspot"
ui scroll-to "Start offline hotspot" 6
ui tap "Start offline hotspot"
check "hotspot starts (Join this Wi-Fi shown)" wait_until "$HOTSPOT_WAIT_S" has_text "1. Join this Wi-Fi"
shot hotspot-starting
ui scroll-to "~2. Open http://" 3 || true
url=$(ui grep-text '^2\. Open http://' | sed 's/^2\. Open //' || true)
address=$(sed -E 's#http://([0-9.]+):[0-9]+#\1#' <<<"$url")
log "    hotspot URL: $url"
check "URL is http://<private IPv4>:$PORT" bash -c "[[ '$url' =~ ^http://(10|172|192)\\.[0-9.]+:$PORT\$ ]]"
check "network name and password shown" bash -c "scripts/ui.sh has Network && scripts/ui.sh has Password"
check "Go listener bound on $address:$PORT" wait_until 10 listening_on "$address"
check "listener not bound on the Wi-Fi address" bash -c "! adb shell \"su -c 'ss -ltn'\" | grep -q '0.0.0.0:$PORT'"
shot hotspot-on

step "pairing code"
ui scroll-to "Show pairing code" 4
ui tap "Show pairing code"
check "6-digit code shown" wait_until 5 bash -c "scripts/ui.sh grep-text '^[0-9]{3} ?[0-9]{3}\$' >/dev/null"
check "expiry countdown shown" has_text "~Single use, expires in"
shot pairing-code
ui tap "Stop pairing"
sleep 0.5

step "stop hotspot"
ui scroll-to "Stop offline hotspot" 4
ui tap "Stop offline hotspot"
check "hotspot card back to Start" wait_until 15 has_text "Start offline hotspot"
check "listener gone" wait_until 10 not_listening_on "$address"
check "phone Wi-Fi still connected" bash -c "adb shell dumpsys wifi | grep -q 'mWifiInfo SSID'"

step "Remote off"
for _ in 1 2 3 4; do has_text "Remote control" && break; adb shell input swipe 540 700 540 1900 300; sleep 0.5; done
ui toggle "Remote control"
check "node stops" wait_until 20 remote_stopped
check "switch off" bash -c "! scripts/ui.sh checked 'Remote control'"
shot remote-off
