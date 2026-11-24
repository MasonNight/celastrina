#!/usr/bin/env bash
# Drive the household TV over ADB for hand verification of screens.
#
#   scripts/tv.sh connect               connect and print the ADB serial
#   scripts/tv.sh install [apk]         install (default: the debug APK), keeping app data
#   scripts/tv.sh launch                wake the display and start Celastrina Dev
#   scripts/tv.sh open-movie <tmdb-id>  start Celastrina Dev on that Movie's detail screen
#   scripts/tv.sh press <key>...        press remote keys, e.g. press DPAD_CENTER BACK
#   scripts/tv.sh screenshot [out.png]  wake the display and save a screenshot (default: tv.png)
#   scripts/tv.sh log                   Celastrina's crashes, log lines and player errors since the last
#                                       launch, plus any presses on the real remote
#
# The TV is shared with the household. launch, open-movie and press refuse while someone is using it
# (another app is playing, or the real remote was pressed in the last 2 minutes); --force overrides.
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

FORCE=false
args=()
for arg in "$@"; do
  if [ "$arg" = --force ]; then FORCE=true; else args+=("$arg"); fi
done
set -- "${args[@]+"${args[@]}"}"

# Says why someone seems to be using the TV, or nothing when it looks free.
busy_reason() {
  local serial="$1" now playing
  playing="$(adb -s "$serial" shell dumpsys media_session | tr -d '\r' | awk -v own="$PACKAGE" '
    /package=/ { sub(/.*package=/, ""); pkg = $1 }
    /state=PlaybackState \{state=3,/ && pkg != own { print pkg }' | sort -u | paste -sd, -)"
  if [ -n "$playing" ]; then echo "$playing is playing"; return; fi
  # Presses on the physical remote are logged by Fire OS's OZ-DCS service; keys injected over ADB aren't.
  now="$(adb -s "$serial" shell date +%s | tr -d '\r')"
  if adb -s "$serial" logcat -d -v epoch | tr -d '\r' | awk -v since="$((now - 120))" '
      /Remote button pressed/ && $1 + 0 >= since { found = 1 } END { exit !found }'; then
    echo "the real remote was pressed in the last 2 minutes"
  fi
}

# Connects, and stops unless the TV is free or --force was given.
connect_unless_busy() {
  local serial reason
  serial="$(connect)"
  if ! $FORCE; then
    reason="$(busy_reason "$serial")"
    if [ -n "$reason" ]; then
      echo "Someone seems to be using the TV: $reason. Try again later, or pass --force." >&2
      exit 3
    fi
  fi
  echo "$serial"
}

# Starts Celastrina Dev with a fresh log buffer, so `log` shows only this run.
start_app() {
  local serial="$1"; shift
  wake "$serial"
  adb -s "$serial" logcat -c
  adb -s "$serial" shell am start -W "$@" -n "$PACKAGE/$ACTIVITY"
}

case "${1:-}" in
  connect) connect ;;
  install)
    serial="$(connect)"
    adb -s "$serial" install -r "${2:-$REPO/app/build/outputs/apk/debug/app-debug.apk}"
    ;;
  launch)
    serial="$(connect_unless_busy)"
    start_app "$serial"
    ;;
  open-movie)
    [ -n "${2:-}" ] || { echo "Usage: scripts/tv.sh open-movie <tmdb-id>" >&2; exit 2; }
    serial="$(connect_unless_busy)"
    start_app "$serial" -S --ei dev.celastrina.OPEN_MOVIE "$2"
    ;;
  press)
    [ -n "${2:-}" ] || { echo "Usage: scripts/tv.sh press <key>... (e.g. DPAD_CENTER, BACK)" >&2; exit 2; }
    serial="$(connect_unless_busy)"
    shift
    for key in "$@"; do adb -s "$serial" shell input keyevent "KEYCODE_${key#KEYCODE_}"; done
    ;;
  log)
    serial="$(connect)"
    adb -s "$serial" logcat -d -v time | tr -d '\r' |
      grep -E 'AndroidRuntime|/Celastrina|ExoPlayerImpl|PlaybackException|Remote button pressed' || echo "Nothing logged since the last launch."
    ;;
  screenshot)
    serial="$(connect)"
    wake "$serial"
    out="${2:-tv.png}"
    # Fire OS prints "Init wrapper sys mutex successful…" before the PNG bytes; keep only the PNG.
    adb -s "$serial" exec-out screencap -p | python3 -c 'import sys; b = sys.stdin.buffer.read(); sys.stdout.buffer.write(b[b.find(b"\x89PNG"):])' >"$out"
    echo "$out"
    ;;
  *) awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"; exit 2 ;;
esac
