#!/usr/bin/env bash
# Prints the serial of the one phone connected over USB adb (wireless serials contain ':').
# Set BOOTLEG_USB_SERIAL to pick one when several are connected.
set -euo pipefail

if [ -n "${BOOTLEG_USB_SERIAL:-}" ]; then
  echo "$BOOTLEG_USB_SERIAL"
  exit 0
fi
serials=$(adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /:/ { print $1 }')
count=$(printf '%s\n' "$serials" | grep -c . || true)
if [ "$count" -ne 1 ]; then
  echo "usb-serial: expected exactly one phone on USB adb, found $count; set BOOTLEG_USB_SERIAL" >&2
  exit 1
fi
echo "$serials"
