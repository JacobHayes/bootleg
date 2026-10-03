# shellcheck shell=bash disable=SC2034 # sourced by scripts/e2e-ui.sh, which uses the variables
# Doze test: Remote reachability while the phone is "unplugged" and in deep Doze. Turns Remote on
# with the UI switch, approves this Mac (debug hook REMOTE_APPROVE, standing in for the in-app
# Approve button), simulates unplugged + deep idle (dumpsys battery unplug; deviceidle force-idle),
# then runs `bootleg status` from this Mac every BOOTLEG_DOZE_POLL_INTERVAL_S (15) for
# BOOTLEG_DOZE_DURATION_S (120) and reports reachability and latency. Restores battery/idle,
# revokes this Mac and turns Remote off when it ends.
#
# Limitation: the screen stays on (turning it off could lock the phone, and unlocking needs the
# user), so this is force-idle with the screen on, not real screen-off Doze. Sourced by
# scripts/e2e-ui.sh.

define_test doze.remote-reachable "Remote stays reachable (bootleg status) while unplugged and force-idle" --usb

DOZE_DURATION_S=${BOOTLEG_DOZE_DURATION_S:-120}
DOZE_POLL_INTERVAL_S=${BOOTLEG_DOZE_POLL_INTERVAL_S:-15}
DOZE_STATUS_TIMEOUT_S=20
DOZE_NODE_WAIT_S=45
DOZE_READY_WAIT_S=90
DOZE_CLI=build/bin/bootleg
DOZE_REACHABLE_RATIO_MIN_PERCENT=80

# approved_status_works HOST NODE: one `bootleg status` attempt (it shows up as pending), the
# approval, then a status that must succeed. A node that just reached Running can take a while to
# be reachable from this Mac's side of the tailnet, so callers retry this.
approved_status_works() {
    timeout "$DOZE_STATUS_TIMEOUT_S" "$DOZE_CLI" --host "$1" status >/dev/null 2>&1 || true
    call REMOTE_APPROVE --extra "node:s:$2" >/dev/null
    timeout "$DOZE_STATUS_TIMEOUT_S" "$DOZE_CLI" --host "$1" status >/dev/null
}

positive_integer() { [[ $1 =~ ^[1-9][0-9]*$ ]]; }

device_deep_idle() { adb shell dumpsys deviceidle get deep | grep -q IDLE; }

test_doze_remote_reachable() {
    local mac_node host ok=0 total=0 latencies=() deadline started latency sorted
    require "BOOTLEG_DOZE_DURATION_S is a positive integer ($DOZE_DURATION_S)" positive_integer "$DOZE_DURATION_S"
    require "BOOTLEG_DOZE_POLL_INTERVAL_S is a positive integer ($DOZE_POLL_INTERVAL_S)" positive_integer "$DOZE_POLL_INTERVAL_S"
    [[ -x $DOZE_CLI ]] || mise run remote:cli >/dev/null
    mac_node=$(tailscale status --json | jq -r '.Self.ID')
    require "this Mac is on a tailnet (tailscale status)" test -n "$mac_node" -a "$mac_node" != null

    on_cleanup "[[ \$(call REMOTE_STATE | jq -r .enabled) == false ]] || call REMOTE_OFF >/dev/null"
    on_cleanup "call REMOTE_REVOKE --extra node:s:$mac_node >/dev/null || true"
    on_cleanup "adb shell dumpsys deviceidle unforce >/dev/null; adb shell dumpsys battery reset"
    adb shell pm grant "$PACKAGE" android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || true

    step "Remote on (UI) and approve this Mac"
    tab Remote
    if ! ui checked "Remote control"; then ui toggle "Remote control"; fi
    require "node reaches Running" wait_until "$DOZE_NODE_WAIT_S" remote_running
    host=$(call REMOTE_STATE | jq -r '.dnsName')
    require "bootleg status works before idling" wait_until "$DOZE_READY_WAIT_S" approved_status_works "$host" "$mac_node"

    step "unplugged + forced deep idle (screen on)"
    adb shell dumpsys battery unplug
    adb shell dumpsys deviceidle force-idle | tr -d '\r' | sed 's/^/    /'
    check "device idle state is IDLE" device_deep_idle

    step "bootleg status every ${DOZE_POLL_INTERVAL_S}s for ${DOZE_DURATION_S}s"
    deadline=$((SECONDS + DOZE_DURATION_S))
    while ((SECONDS < deadline)); do
        total=$((total + 1))
        started=$(now_ms)
        if timeout "$DOZE_STATUS_TIMEOUT_S" "$DOZE_CLI" --host "$host" status >/dev/null 2>&1; then
            latency=$(($(now_ms) - started))
            ok=$((ok + 1))
            latencies+=("$latency")
            log "    #$total ok ${latency}ms (idle: $(adb shell dumpsys deviceidle get deep | tr -d '\r'))"
        else
            log "    #$total FAILED after $(($(now_ms) - started))ms"
        fi
        # The poll after this sleep would start past the deadline: stop now instead.
        ((SECONDS + DOZE_POLL_INTERVAL_S < deadline)) || break
        sleep "$DOZE_POLL_INTERVAL_S"
    done
    if ((ok > 0)); then
        sorted=$(printf '%s\n' "${latencies[@]}" | sort -n)
        log "reachable $ok/$total; latency min $(head -1 <<<"$sorted")ms median $(sed -n "$(((ok + 1) / 2))p" <<<"$sorted")ms max $(tail -1 <<<"$sorted")ms"
    fi
    require "at least one poll ran" test "$total" -gt 0
    check "reachable in ≥ ${DOZE_REACHABLE_RATIO_MIN_PERCENT}% of polls while idle" test "$((ok * 100))" -ge "$((total * DOZE_REACHABLE_RATIO_MIN_PERCENT))"
}
