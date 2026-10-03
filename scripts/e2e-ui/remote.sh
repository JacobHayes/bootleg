# shellcheck shell=bash disable=SC2034 # sourced by scripts/e2e-ui.sh, which uses the variables
# Remote screen tests. Remote on with its switch -> Tailscale shows Running with the node's name (an
# already enrolled node; if it asks to sign in, the test stops there and says so, it never completes
# a browser login). Offline mode: Start offline hotspot -> the network name, QR code and URL render
# and Go's listener is bound on the hotspot address (ss on the phone); Show pairing code -> a
# 6-digit code; Stop offline hotspot -> the listener is gone. Remote off at the end. This Mac never
# joins the hotspot.
#
# Over USB adb: the hotspot may take the phone's Wi-Fi radio, and nothing here re-enumerates USB.
# NEARBY_WIFI_DEVICES and ACCESS_LOCAL_NETWORK are pre-granted with pm grant (their system dialogs
# are not what these tests check). Sourced by scripts/e2e-ui.sh.

define_test remote.on-off "Remote switch: node Running with its name and IP, no problem card; off: node stops" --usb
define_test remote.hotspot "offline hotspot: URL on a private IPv4, Go listener on the hotspot address only; stop: listener gone" --usb
define_test remote.pairing-code "offline hotspot pairing code: 6 digits with an expiry countdown" --usb

REMOTE_NODE_WAIT_S=45
REMOTE_HOTSPOT_WAIT_S=25
REMOTE_PORT=8080
REMOTE_SCROLL_TOP_SWIPES_MAX=4

remote_running() { [[ $(call REMOTE_STATE | jq -r '.running and .backendState == "Running"') == true ]]; }
remote_stopped() { [[ $(call REMOTE_STATE | jq -r '.running') == false ]]; }
running_or_sign_in() { remote_running || has_text "Sign in to Tailscale"; }
remote_switch_on() { ui_checked "Remote control"; }
remote_switch_off() { ! ui_checked "Remote control"; }
listening_on() { root "ss -ltn" | grep -qE "[[:space:]]${1}:${REMOTE_PORT}[[:space:]]"; }
not_listening_on() { ! listening_on "$1"; }
not_listening_on_any() { ! root "ss -ltn" | grep -q "0.0.0.0:$REMOTE_PORT"; }
shows_tailscale_ip() { ui_grep_text '100\.[0-9]+\.[0-9]+\.[0-9]+' >/dev/null; }
shows_network_and_password() { has_text Network && has_text Password; }
shows_pairing_code() { ui_grep_text '^[0-9]{3} ?[0-9]{3}$' >/dev/null; }
phone_wifi_connected() { adb shell dumpsys wifi | grep -q 'mWifiInfo SSID'; }

# remote_on: Remote tab, switch on, node Running (an enrolled node; never a browser login).
remote_on() {
    local permission
    on_cleanup "[[ \$(call REMOTE_STATE | jq -r .enabled) == false ]] || call REMOTE_OFF >/dev/null"
    for permission in NEARBY_WIFI_DEVICES ACCESS_LOCAL_NETWORK; do
        adb shell pm grant "$PACKAGE" "android.permission.$permission" 2>/dev/null || log "note: pm grant $permission failed"
    done
    tab Remote
    require "Remote screen shows its switch" has_text "Remote control"
    if ! remote_switch_on; then ui toggle "Remote control"; fi
    wait_until 5 remote_switch_on || true
    wait_until "$REMOTE_NODE_WAIT_S" running_or_sign_in || true
    shot remote-on
    if has_text "Sign in to Tailscale"; then
        log "the node asks to sign in (not enrolled); not completing a browser login"
        require "node already enrolled (no sign-in asked)" false
    fi
    require "node reaches Running" wait_until "$REMOTE_NODE_WAIT_S" remote_running
}

# hotspot_off: Stop offline hotspot unless it is already stopped (the running hotspot's card is at
# the top of the screen, the Start button only shows once it stopped).
hotspot_off() {
    has_text "Start offline hotspot" && return 0
    ui scroll-to "Stop offline hotspot" 4 || return 0
    ui tap "Stop offline hotspot"
    wait_until 15 has_text "Start offline hotspot"
}

# hotspot_on: Start offline hotspot; sets $hotspot_address from the URL it shows.
hotspot_on() {
    local url
    on_cleanup hotspot_off
    ui scroll-to "Start offline hotspot" 6
    ui tap "Start offline hotspot"
    require "hotspot starts (Join this Wi-Fi shown)" wait_until "$REMOTE_HOTSPOT_WAIT_S" has_text "1. Join this Wi-Fi"
    shot hotspot-starting
    ui scroll-to "~2. Open http://" 3 || true
    url=$(ui_grep_text '^2\. Open http://' | sed 's/^2\. Open //' || true)
    hotspot_url=$url
    hotspot_address=$(sed -E 's#http://([0-9.]+):[0-9]+#\1#' <<<"$url")
    log "    hotspot URL: $url"
}

url_is_private() { [[ $hotspot_url =~ ^http://(10|172|192)\.[0-9.]+:$REMOTE_PORT$ ]]; }

test_remote_on_off() {
    local dns_name swipe
    remote_on
    dns_name=$(call REMOTE_STATE | jq -r '.dnsName')
    check "UI shows the node name ($dns_name)" wait_until 10 has_text "$dns_name"
    check "UI shows a tailscale IP" shows_tailscale_ip
    check "no Remote problem card" not_text "Remote problem"
    shot remote-running

    step "Remote off"
    for ((swipe = 0; swipe < REMOTE_SCROLL_TOP_SWIPES_MAX; swipe++)); do
        has_text "Remote control" && break
        adb shell input swipe 540 700 540 1900 300
    done
    ui toggle "Remote control"
    check "node stops" wait_until 20 remote_stopped
    check "switch off" wait_until 5 remote_switch_off
    shot remote-off
}

test_remote_hotspot() {
    remote_on
    hotspot_on
    check "URL is http://<private IPv4>:$REMOTE_PORT" url_is_private
    check "network name and password shown" shows_network_and_password
    check "Go listener bound on $hotspot_address:$REMOTE_PORT" wait_until 10 listening_on "$hotspot_address"
    check "listener not bound on the Wi-Fi address" not_listening_on_any
    shot hotspot-on

    step "stop hotspot"
    ui scroll-to "Stop offline hotspot" 4
    ui tap "Stop offline hotspot"
    check "hotspot card back to Start" wait_until 15 has_text "Start offline hotspot"
    check "listener gone" wait_until 10 not_listening_on "$hotspot_address"
    check "phone Wi-Fi still connected" phone_wifi_connected
}

test_remote_pairing_code() {
    remote_on
    hotspot_on
    ui scroll-to "Show pairing code" 4
    ui tap "Show pairing code"
    check "6-digit code shown" wait_until 5 shows_pairing_code
    check "expiry countdown shown" has_text "~Single use, expires in"
    shot pairing-code
    ui tap "Stop pairing"
    check "pairing stopped (Show pairing code back)" wait_until 5 has_text "Show pairing code"
}
