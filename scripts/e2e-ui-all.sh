#!/usr/bin/env bash
# Runs every UI end-to-end flow in order (each takes and releases the device lock itself) and
# prints a summary. Usage: scripts/e2e-ui-all.sh [FLOW...]   (default: all, in the order below)
set -uo pipefail
cd "$(dirname "$0")/.." || exit

flows=("$@")
((${#flows[@]})) || flows=(catalog drives blank hid settings tile remote doze)
results=()
status=0
for flow in "${flows[@]}"; do
    if bash "scripts/e2e-ui-$flow.sh"; then
        results+=("PASS $flow")
    else
        results+=("FAIL $flow")
        status=1
    fi
done
echo
printf '  %s\n' "${results[@]}"
exit "$status"
