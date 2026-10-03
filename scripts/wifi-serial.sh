#!/usr/bin/env bash
# Prints the phone's wireless adb serial (<wlan0 address>:5555), found over USB adb, and makes sure
# adb is connected to it. The address changes with the network, so scripts ask instead of hardcoding.
#
# Usage: scripts/wifi-serial.sh [usb-serial]
set -euo pipefail

# Wireless adb can hang on a dead transport: every adb call here is bounded (timeout runs the adb binary).
adb() { timeout --foreground --kill-after=5 60 adb "$@"; }

usb_serial=${1:-$(scripts/usb-serial.sh)}
port=5555

address=$(adb -s "$usb_serial" shell ip -4 addr show wlan0 | grep -oE 'inet [0-9.]+' | head -1 | cut -d' ' -f2)
if [ -z "$address" ]; then
  echo "wifi-serial: phone has no wlan0 address (not on Wi-Fi?)" >&2
  exit 1
fi

serial="$address:$port"
if ! adb devices | grep -q "^${serial}[[:space:]]*device"; then
  if [ "$(adb -s "$usb_serial" shell getprop service.adb.tcp.port | tr -d '\r')" != "$port" ]; then
    adb -s "$usb_serial" tcpip "$port" >/dev/null
    sleep 2
  fi
  adb connect "$serial" >/dev/null
fi
echo "$serial"
