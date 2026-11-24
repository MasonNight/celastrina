#!/usr/bin/env bash
# Drive the household TV over ADB for hand verification of screens.
#
#   scripts/tv.sh connect               connect and print the ADB serial
#   scripts/tv.sh install [apk]         install (default: the debug APK), keeping app data
#   scripts/tv.sh launch                wake the display and start Celastrina Dev
#   scripts/tv.sh open-movie <tmdb-id>  start Celastrina Dev on that Movie's detail screen
#   scripts/tv.sh screenshot [out.png]  wake the display and save a screenshot (default: tv.png)
#
# The TV's address is remembered in ~/.config/celastrina/tv. If it doesn't answer there, the script
# scans the local /24 for ADB on port 5555, keeps the host whose model matches CELASTRINA_TV_MODEL
# (default AFTTI43), and remembers the new address.
set -euo pipefail

MODEL="${CELASTRINA_TV_MODEL:-AFTTI43}"
ADDRESS_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/celastrina/tv"
PACKAGE=dev.celastrina.debug
ACTIVITY=dev.celastrina.app.MainActivity
REPO="$(cd "$(dirname "$0")/.." && pwd)"

model_at() { timeout 10 adb -s "$1:5555" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true; }

try_address() {
  local ip="$1"
  timeout 10 adb connect "$ip:5555" >/dev/null 2>&1 || return 1
  [ "$(model_at "$ip")" = "$MODEL" ]
}

scan() {
  local subnet
  subnet="$(ip -4 route get 1.1.1.1 | sed -n 's/.* src \([0-9.]*\)\.[0-9]* .*/\1/p')"
  echo "TV not at its remembered address; scanning $subnet.0/24 for ADB…" >&2
  for i in $(seq 1 254); do
    (timeout 1 bash -c "</dev/tcp/$subnet.$i/5555" 2>/dev/null && echo "$subnet.$i") &
  done 2>/dev/null | sort -u
  wait
}

connect() {
  local ip
  ip="$(cat "$ADDRESS_FILE" 2>/dev/null || true)"
  if [ -n "$ip" ] && try_address "$ip"; then echo "$ip:5555"; return; fi
  for ip in $(scan); do
    if try_address "$ip"; then
      mkdir -p "$(dirname "$ADDRESS_FILE")"
      echo "$ip" >"$ADDRESS_FILE"
      echo "Found the $MODEL at $ip; remembered in $ADDRESS_FILE" >&2
      echo "$ip:5555"
      return
    fi
  done
  echo "No $MODEL found. Is the TV on, with ADB Debugging enabled, and has this computer been allowed?" >&2
  exit 1
}

wake() { adb -s "$1" shell input keyevent KEYCODE_WAKEUP; sleep 2; }

case "${1:-}" in
  connect) connect ;;
  install)
    serial="$(connect)"
    adb -s "$serial" install -r "${2:-$REPO/app/build/outputs/apk/debug/app-debug.apk}"
    ;;
  launch)
    serial="$(connect)"
    wake "$serial"
    adb -s "$serial" shell am start -W -n "$PACKAGE/$ACTIVITY"
    ;;
  open-movie)
    [ -n "${2:-}" ] || { echo "Usage: scripts/tv.sh open-movie <tmdb-id>" >&2; exit 2; }
    serial="$(connect)"
    wake "$serial"
    adb -s "$serial" shell am start -W -S -n "$PACKAGE/$ACTIVITY" --ei dev.celastrina.OPEN_MOVIE "$2"
    ;;
  screenshot)
    serial="$(connect)"
    wake "$serial"
    out="${2:-tv.png}"
    # Fire OS prints "Init wrapper sys mutex successful…" before the PNG bytes; keep only the PNG.
    adb -s "$serial" exec-out screencap -p | python3 -c 'import sys; b = sys.stdin.buffer.read(); sys.stdout.buffer.write(b[b.find(b"\x89PNG"):])' >"$out"
    echo "$out"
    ;;
  *) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
