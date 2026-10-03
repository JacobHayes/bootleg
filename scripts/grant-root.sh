#!/bin/sh
# Pre-grants Magisk root and MANAGE_EXTERNAL_STORAGE to the installed Bootleg app (testing only).
# Usage: scripts/grant-root.sh [adb serial]
set -eu

# Wireless adb can hang on a dead transport: every adb call here is bounded (timeout runs the adb binary).
adb() { timeout --foreground --kill-after=5 60 adb "$@"; }

PACKAGE=dev.jacobhayes.bootleg
# USB and wireless adb often both list the same phone; default to $ANDROID_SERIAL, then the first device.
SERIAL=${1:-${ANDROID_SERIAL:-$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')}}
[ -n "$SERIAL" ] || { echo "grant-root: no device" >&2; exit 1; }
ADB="adb -s $SERIAL"

uid=$($ADB shell pm list packages -U "$PACKAGE" | sed -n "s/^package:$PACKAGE uid://p" | tr -d '\r')
case "$uid" in
    '' | *[!0-9]*) echo "grant-root: $PACKAGE is not installed (uid '$uid')" >&2; exit 1 ;;
esac

# Magisk policies: (uid, policy, until, logging, notification); policy 2 = allow, until 0 = forever.
# Only select non-NULL columns: Magisk 31's --sqlite crashes its daemon on NULL values.
$ADB shell su -c "\"magisk --sqlite 'INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($uid,2,0,1,0)'\""
$ADB shell su -c "\"magisk --sqlite 'SELECT uid,policy,until FROM policies WHERE uid=$uid'\""

$ADB shell appops set --uid "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow
# Power-allowlisted apps may start their foreground service from the background (boot, tile, debug receiver).
$ADB shell cmd deviceidle whitelist "+$PACKAGE" >/dev/null
echo "grant-root: root + MANAGE_EXTERNAL_STORAGE granted to $PACKAGE (uid $uid)"
