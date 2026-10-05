#!/bin/sh
# Installs the debug APK unless the phone already has exactly this build (its base.apk hashes the
# same), then pre-grants root and storage access (scripts/grant-root.sh; idempotent).
# Usage: scripts/install-apk.sh [adb serial] [apk]
#   (default serial: $ANDROID_SERIAL, else the only adb device, else the one phone on USB adb)
set -eu
cd "$(dirname "$0")/.."

# Wireless adb can hang on a dead transport; an install of the ~100 MB debug APK takes ~15 s over it.
adb() { timeout --foreground --kill-after=5 180 adb "$@"; }

PACKAGE=dev.jacobhayes.bootleg
SERIAL=${1:-${ANDROID_SERIAL:-}}
if [ -z "$SERIAL" ]; then
    # The only adb device, else the one phone on USB (usb-serial.sh refuses to pick among several).
    SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" { count++; serial = $1 } END { if (count == 1) print serial }')
    [ -n "$SERIAL" ] || SERIAL=$(scripts/usb-serial.sh)
fi
APK=${2:-app/build/outputs/apk/debug/app-debug.apk}
[ -f "$APK" ] || { echo "install-apk: $APK is missing (./gradlew :app:assembleDebug)" >&2; exit 1; }

local_sha=$(shasum -a 256 "$APK" | awk '{ print $1 }')
installed_path=$(adb -s "$SERIAL" shell pm path "$PACKAGE" | tr -d '\r' | sed -n 's/^package://p' | grep '/base\.apk$' | head -1) || installed_path=
installed_sha=
[ -z "$installed_path" ] || installed_sha=$(adb -s "$SERIAL" shell sha256sum "$installed_path" | awk '{ print $1 }')

if [ "$local_sha" = "$installed_sha" ]; then
    echo "install-apk: $SERIAL already has $APK"
else
    echo "install-apk: installing $APK on $SERIAL"
    adb -s "$SERIAL" install -r -t "$APK" >/dev/null
fi
sh scripts/grant-root.sh "$SERIAL" >/dev/null
