#!/usr/bin/env bash
# E2E (UI) flow 9: Remote reachability while the phone is "unplugged" and in deep Doze. Turns Remote
# on with the UI switch, approves this Mac (debug hook REMOTE_APPROVE, standing in for the in-app
# Approve button), simulates unplugged + deep idle (dumpsys battery unplug; deviceidle force-idle),
# then runs `bootleg status` from this Mac every POLL_INTERVAL_S for DURATION_S and reports
# reachability and latency. Restores battery/idle, revokes this Mac and turns Remote off in an EXIT
# trap.
#
# Limitation: the screen stays on (turning it off could lock the phone, and unlocking needs the
# user), so this is force-idle with the screen on, not real screen-off Doze.
# Usage: scripts/e2e-ui-doze.sh [DURATION_S] [POLL_INTERVAL_S]   (defaults 120 and 15)
FLOW=doze
ADB_TRANSPORT=usb
source "$(dirname "$0")/e2e-ui-lib.sh"

DURATION_S=${1:-120}
POLL_INTERVAL_S=${2:-15}
STATUS_TIMEOUT_S=20
NODE_WAIT_S=45
CLI=build/bin/bootleg
REACHABLE_RATIO_MIN_PERCENT=80

remote_running() { [[ $(call REMOTE_STATE | jq -r '.running and .backendState == "Running"') == true ]]; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

[[ -x $CLI ]] || mise run remote:cli >/dev/null
MAC_NODE=$(tailscale status --json | jq -r '.Self.ID')
[[ -n $MAC_NODE && $MAC_NODE != null ]] || die "this Mac isn't on a tailnet (tailscale status)"

take_lock
on_cleanup "[[ \$(call REMOTE_STATE | jq -r .enabled) == false ]] || call REMOTE_OFF >/dev/null"
on_cleanup "call REMOTE_REVOKE --extra node:s:$MAC_NODE >/dev/null || true"
on_cleanup "adb shell dumpsys deviceidle unforce >/dev/null; adb shell dumpsys battery reset"
adb shell pm grant "$PACKAGE" android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || true
launch_app

step "Remote on (UI) and approve this Mac"
tab Remote
if ! ui checked "Remote control"; then ui toggle "Remote control"; fi
require "node reaches Running" wait_until "$NODE_WAIT_S" remote_running
host=$(call REMOTE_STATE | jq -r '.dnsName')
"$CLI" --host "$host" status >/dev/null 2>&1 || true # shows up as pending
call REMOTE_APPROVE --extra "node:s:$MAC_NODE" >/dev/null
require "bootleg status works before idling" timeout "$STATUS_TIMEOUT_S" "$CLI" --host "$host" status >/dev/null

step "unplugged + forced deep idle (screen on)"
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle | tr -d '\r' | sed 's/^/    /'
check "device idle state is IDLE" bash -c "adb shell dumpsys deviceidle get deep | grep -q IDLE"

step "bootleg status every ${POLL_INTERVAL_S}s for ${DURATION_S}s"
ok=0
total=0
latencies=()
deadline=$((SECONDS + DURATION_S))
while ((SECONDS < deadline)); do
    total=$((total + 1))
    started=$(now_ms)
    if timeout "$STATUS_TIMEOUT_S" "$CLI" --host "$host" status >/dev/null 2>&1; then
        latency=$(($(now_ms) - started))
        ok=$((ok + 1))
        latencies+=("$latency")
        log "    #$total ok ${latency}ms (idle: $(adb shell dumpsys deviceidle get deep | tr -d '\r'))"
    else
        log "    #$total FAILED after $(($(now_ms) - started))ms"
    fi
    sleep "$POLL_INTERVAL_S"
done
sorted=$(printf '%s\n' "${latencies[@]}" | sort -n)
log "reachable $ok/$total; latency min $(head -1 <<<"$sorted")ms median $(sed -n "$(((ok + 1) / 2))p" <<<"$sorted")ms max $(tail -1 <<<"$sorted")ms"
check "reachable in ≥ ${REACHABLE_RATIO_MIN_PERCENT}% of polls while idle" test "$((ok * 100))" -ge "$((total * REACHABLE_RATIO_MIN_PERCENT))"
