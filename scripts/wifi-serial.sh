#!/usr/bin/env bash
# Prints the phone's wireless adb serial (<address>:<port>) and makes sure adb is connected to it.
# The address changes with the network, so it is always discovered, never hardcoded:
#   1. over USB adb (when the phone is on USB): its current wlan0 address, port 5555;
#   2. otherwise a wireless serial adb is already connected to, if it is still the same phone;
#   3. otherwise the phone's adb service as advertised over mDNS (`adb mdns services`).
# Candidates from 2 and 3 must report the expected ro.serialno when one is known.
#
# Usage: scripts/wifi-serial.sh [usb-serial]   (env: BOOTLEG_USB_SERIAL)
set -euo pipefail
cd "$(dirname "$0")/.."

# Wireless adb can hang on a dead transport: every adb call here is bounded (timeout runs the adb binary).
adb() { timeout --foreground --kill-after=5 60 adb "$@"; }

port=5555
candidates_max=8

expected_serial=${1:-${BOOTLEG_USB_SERIAL:-}}
usb_serial=
if [ -n "$expected_serial" ]; then
  [ "$(adb -s "$expected_serial" get-state 2>/dev/null)" = device ] && usb_serial=$expected_serial
else
  usb_serial=$(scripts/usb-serial.sh 2>/dev/null) || usb_serial=
  expected_serial=$usb_serial
fi

# is_phone SERIAL: adb reaches SERIAL and it is the expected phone (any phone when none is expected).
is_phone() {
  [ "$(adb -s "$1" get-state 2>/dev/null)" = device ] || return 1
  [ -z "$expected_serial" ] && return 0
  [ "$(adb -s "$1" shell getprop ro.serialno 2>/dev/null | tr -d '\r')" = "$expected_serial" ]
}

if [ -n "$usb_serial" ]; then
  address=$(adb -s "$usb_serial" shell ip -4 addr show wlan0 | grep -oE 'inet [0-9.]+' | head -1 | cut -d' ' -f2) || address=
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
  is_phone "$serial" || {
    echo "wifi-serial: $serial isn't reachable over adb as $usb_serial" >&2
    exit 1
  }
  echo "$serial"
  exit 0
fi

# Wireless serials adb already has (address:port, in the device state), then mDNS services. A
# service name is adb-<ro.serialno>-<suffix>, so with an expected serial only its own are tried.
connected=$(adb devices | awk 'NR > 1 && $2 == "device" && $1 ~ /^[0-9.]+:[0-9]+$/ { print $1 }' | head -n "$candidates_max")
advertised=$(adb mdns services 2>/dev/null |
  awk -v want="$expected_serial" '$2 ~ /^_adb(-tls-connect)?\._tcp/ && $3 ~ /^[0-9.]+:[0-9]+$/ && (want == "" || index($1, "adb-" want "-") == 1) {
      print ($2 ~ /^_adb\._tcp/ ? 0 : 1), $3 }' |
  sort -n | cut -d' ' -f2 | head -n "$candidates_max") || advertised=

# With no expected phone, the candidates must all be one phone: picking one of several silently
# could point a device check at the wrong phone.
if [ -z "$expected_serial" ]; then
  phones=$(
    for serial in $connected; do adb -s "$serial" shell getprop ro.serialno 2>/dev/null | tr -d '\r'; done
    adb mdns services 2>/dev/null | awk '$1 ~ /^adb-.+-[^-]+$/ && $2 ~ /^_adb(-tls-connect)?\._tcp/ { name = substr($1, 5); sub(/-[^-]+$/, "", name); print name }' || true
  ) || phones=
  phones=$(printf '%s\n' "$phones" | grep . | sort -u) || phones=
  if [ "$(printf '%s\n' "$phones" | grep -c .)" -gt 1 ]; then
    echo "wifi-serial: several phones on wireless adb or mDNS ($(echo "$phones" | tr '\n' ' ')); set BOOTLEG_USB_SERIAL" >&2
    exit 1
  fi
  expected_serial=$phones
fi

for serial in $connected; do
  if is_phone "$serial"; then
    echo "$serial"
    exit 0
  fi
done
for serial in $advertised; do
  adb connect "$serial" >/dev/null 2>&1 || continue
  if is_phone "$serial"; then
    echo "$serial"
    exit 0
  fi
done

echo "wifi-serial: no phone on USB adb, and no ${expected_serial:-phone} found among wireless adb devices or mDNS services" >&2
exit 1
