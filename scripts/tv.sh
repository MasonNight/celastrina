#!/usr/bin/env bash
# Verify Celastrina on an Android TV device over ADB.
#
#   scripts/tv.sh connect               connect and print the ADB serial
#   scripts/tv.sh install [apk]         install (default: the debug APK), keeping app data
#   scripts/tv.sh launch                wake the display and start Celastrina Dev
#   scripts/tv.sh open-movie <tmdb-id>  start Celastrina Dev on that Movie's detail screen
#   scripts/tv.sh open-series <tmdb-id> start Celastrina Dev on that Series' detail screen
#   scripts/tv.sh apps                 open Fire OS Your Apps & Channels without remote keys
#   scripts/tv.sh tile-shot <out.png>   capture Your Apps & Channels, then return Home
#   scripts/tv.sh press <key>...        press remote keys, e.g. press DPAD_CENTER BACK
#   scripts/tv.sh screenshot [out.png]  wake the display and save a screenshot (default: tv.png)
#   scripts/tv.sh smoke <folder>       reset the emulator app, play three Titles and save their screenshots
#   scripts/tv.sh log                   Celastrina's crashes, log lines and player errors since the last
#                                       launch, plus any presses on the real remote
#
# The TV is shared with the household. launch, open-movie, open-series and press refuse while someone is using it
# (another app is playing, or the real remote was pressed in the last 2 minutes); --force overrides.
#
# CELASTRINA_ADB_SERIAL=<serial> (e.g. emulator-5554) drives that device instead of finding the TV, so agents can
# test on an Android TV emulator and leave the household TV alone.
#
# Smoke requires CELASTRINA_NOTORRENT_MANIFEST and an emulator serial.
# CELASTRINA_TV_LOCK_DIR overrides the per-serial lock directory (see docs/agents/emulator.md).
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
  command -v adb >/dev/null || {
    echo 'adb is missing from PATH. Add Android SDK platform-tools (usually $ANDROID_HOME/platform-tools or %LOCALAPPDATA%/Android/Sdk/platform-tools on Windows).' >&2
    return 1
  }
  if [ -n "${CELASTRINA_ADB_SERIAL:-}" ]; then echo "$CELASTRINA_ADB_SERIAL"; return; fi
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

wake() { adb -s "$1" shell input keyevent KEYCODE_WAKEUP || return; sleep 2; }

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

lock_device() {
  local serial="$1" root lock_key reported=false
  lock_key="${serial//%/%25}"
  lock_key="${lock_key//:/%3A}"
  lock_key="${lock_key//\//%2F}"
  case "$OSTYPE" in
    msys*|cygwin*) root="$(cygpath -u "$LOCALAPPDATA")/Celastrina/locks" ;;
    *) root="$HOME/.cache/celastrina/locks" ;;
  esac
  DEVICE_LOCK="${CELASTRINA_TV_LOCK_DIR:-$root/$lock_key.lock}"
  mkdir -p "$(dirname "$DEVICE_LOCK")"
  local interrupted=0
  trap 'interrupted=130' INT
  trap 'interrupted=143' TERM
  trap 'interrupted=129' HUP
  local error
  while ! error="$(mkdir "$DEVICE_LOCK" 2>&1)"; do
    if (( interrupted )); then exit "$interrupted"; fi
    if [ ! -d "$DEVICE_LOCK" ]; then
      # The owner can release the directory between our mkdir and the existence check.
      if error="$(mkdir "$DEVICE_LOCK" 2>&1)"; then break; fi
      if [ ! -d "$DEVICE_LOCK" ]; then printf '%s\n' "$error" >&2; exit 1; fi
    fi
    if ! $reported; then
      echo "Waiting for device $serial lock at $DEVICE_LOCK" >&2
      reported=true
    fi
    sleep 1
    if (( interrupted )); then exit "$interrupted"; fi
  done
  trap 'rmdir -- "$DEVICE_LOCK"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 129' HUP
  if (( interrupted )); then exit "$interrupted"; fi
}

require_idle() {
  local serial="$1" reason
  if ! $FORCE; then
    reason="$(busy_reason "$serial")"
    if [ -n "$reason" ]; then
      echo "Someone seems to be using the TV: $reason. Try again later, or pass --force." >&2
      exit 3
    fi
  fi
}

# Starts Celastrina Dev with a fresh log buffer, so `log` shows only this run.
start_app() {
  local serial="$1"; shift
  wake "$serial" || return 1
  adb -s "$serial" logcat -c || return 1
  adb -s "$serial" shell am start -W "$@" -n "$PACKAGE/$ACTIVITY"
}

require_python() {
  command -v python3 >/dev/null || {
    echo 'python3 is missing from PATH. Add the Python 3 executable directory (usually /usr/bin on Linux or the Python installation directory in Git Bash).' >&2
    return 1
  }
}

save_screenshot() {
  local serial="$1" out="$2"
  # Fire OS can print a warning before the PNG signature.
  adb -s "$serial" exec-out screencap -p | python3 -c '
import sys
data = sys.stdin.buffer.read()
start = data.find(b"\x89PNG\r\n\x1a\n")
if start == -1:
    sys.exit("ADB did not return a PNG")
sys.stdout.buffer.write(data[start:])
' >"$out"
}

# Verified from the household AFTTI43's package resolver, not a guessed launcher component.
open_apps() {
  local serial="$1" response
  # This is a trampoline, not a drawable activity; -W can wait indefinitely for its window.
  if ! response="$(adb -s "$serial" shell am start -a android.intent.action.ALL_APPS \
      -n com.amazon.tv.launcher/.util.QuickSettingsProxyActivity 2>&1)" ||
      [[ "$response" == *"Error:"* || "$response" == *"Exception"* ]]; then
    printf 'Could not open Your Apps & Channels on %s: %s\n' "$serial" "$response" >&2
    return 1
  fi
}

launcher_home() {
  local response
  if ! response="$(adb -s "$1" shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME \
      -n com.amazon.tv.launcher/.ui.HomeActivity_vNext 2>&1)" ||
      [[ "$response" == *"Error:"* || "$response" == *"Exception"* ]]; then
    printf 'Could not return to Home on %s: %s\n' "$1" "$response" >&2
    return 1
  fi
}

tile_shot() (
  local serial="$1" out="$2"
  trap 'launcher_home "$serial" >&2' EXIT
  open_apps "$serial" || return 1
  sleep 2
  save_screenshot "$serial" "$out" || return 1
  launcher_home "$serial" || return 1
  trap - EXIT
  echo "$out"
)

smoke_configuration_call() {
  local serial="$1" method="$2" arg="$3" response
  response="$(adb -s "$serial" shell content call --uri "content://$PACKAGE.configuration" --method "$method" --arg "$arg")" &&
    [[ "$response" == *'"result":"ok"'* ]]
}

configure_smoke() {
  local serial="$1" arg
  arg="$(python3 -c '
import base64, json, os
source = dict(kind="stremio", name="notorrent", url=os.environ["CELASTRINA_NOTORRENT_MANIFEST"])
print(base64.b64encode(json.dumps(source).encode()).decode())
')"
  if ! smoke_configuration_call "$serial" add "$arg"; then
    echo "Could not configure the notorrent Source." >&2
    return 1
  fi
  arg="$(printf '%s' '{"id":"internet-archive"}' | base64 | tr -d '\n')"
  if ! smoke_configuration_call "$serial" disable "$arg"; then
    echo "Could not disable the bundled Internet Archive Source." >&2
    return 1
  fi
}

wait_for_first_frame() {
  local serial="$1" deadline=$((SECONDS + 120)) pid logs
  pid="$(adb -s "$serial" shell pidof -s "$PACKAGE" | tr -d '\r')" || return 1
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  while (( SECONDS < deadline )); do
    logs="$(adb -s "$serial" logcat -d -s --pid="$pid" -v brief Celastrina:I AndroidRuntime:E)" || return 1
    case "$logs" in
      *'Failover: first frame rendered'*) return 0 ;;
      *'Failover gave up'*|*'FATAL EXCEPTION'*) return 1 ;;
    esac
    sleep 2
  done
  return 1
}

smoke_title() {
  local serial="$1" out="$2" title="$3" kind="$4" id="$5"
  rm -f -- "$out" || return 1
  echo "Playing $title"
  if ! start_app "$serial" -S --ei "dev.celastrina.OPEN_$kind" "$id" --ez dev.celastrina.AUTO_PLAY true ||
     ! wait_for_first_frame "$serial"; then
    echo "$title did not reach a first frame." >&2
    return 1
  fi
  echo "$title reached a first frame."
  if ! save_screenshot "$serial" "$out"; then
    echo "Could not save the screenshot for $title." >&2
    return 1
  fi
  echo "$out"
}

smoke() {
  local serial="$1" folder="$2" result=0 clear_result clear_status=0
  mkdir -p "$folder"
  clear_result="$(adb -s "$serial" shell pm clear "$PACKAGE" 2>&1)" || clear_status=$?
  clear_result="${clear_result//$'\r'/}"
  if (( clear_status )) || [[ $'\n'"$clear_result"$'\n' != *$'\nSuccess\n'* ]]; then
    echo "Could not clear $PACKAGE on $serial." >&2
    if [ -n "$clear_result" ]; then printf '%s\n' "$clear_result" >&2; fi
    case "$clear_result" in
      *"Unknown package: $PACKAGE"*|*[Pp]"ackage $PACKAGE not found"*|*[Pp]"ackage $PACKAGE is not installed"*)
        echo 'Install the debug APK first.' >&2
        ;;
    esac
    return 1
  fi
  configure_smoke "$serial"
  smoke_title "$serial" "$folder/the-witcher-s01e01.png" "The Witcher S1E1" SERIES 71912 || result=1
  smoke_title "$serial" "$folder/naruto-s01e01.png" "Naruto S1E1" SERIES 46260 || result=1
  smoke_title "$serial" "$folder/the-hunger-games.png" "The Hunger Games" MOVIE 70160 || result=1
  return "$result"
}

case "${1:-}" in
  connect) connect ;;
  apps|tile-shot)
    command="$1"
    if [ "$command" = tile-shot ]; then
      [ "$#" -eq 2 ] && [ -n "$2" ] || { echo "Usage: scripts/tv.sh tile-shot <out.png>" >&2; exit 2; }
      require_python
    fi
    serial="$(connect)"
    lock_device "$serial"
    require_idle "$serial"
    if [ "$command" = apps ]; then open_apps "$serial"; else tile_shot "$serial" "$2"; fi
    ;;
  install)
    serial="$(connect)"
    lock_device "$serial"
    adb -s "$serial" install -r "${2:-$REPO/app/build/outputs/apk/debug/app-debug.apk}"
    ;;
  launch)
    serial="$(connect)"
    lock_device "$serial"
    require_idle "$serial"
    start_app "$serial"
    ;;
  open-movie)
    [ -n "${2:-}" ] || { echo "Usage: scripts/tv.sh open-movie <tmdb-id>" >&2; exit 2; }
    serial="$(connect)"
    lock_device "$serial"
    require_idle "$serial"
    start_app "$serial" -S --ei dev.celastrina.OPEN_MOVIE "$2"
    ;;
  open-series)
    [ -n "${2:-}" ] || { echo "Usage: scripts/tv.sh open-series <tmdb-id>" >&2; exit 2; }
    serial="$(connect)"
    lock_device "$serial"
    require_idle "$serial"
    start_app "$serial" -S --ei dev.celastrina.OPEN_SERIES "$2"
    ;;
  press)
    [ -n "${2:-}" ] || { echo "Usage: scripts/tv.sh press <key>... (e.g. DPAD_CENTER, BACK)" >&2; exit 2; }
    serial="$(connect)"
    lock_device "$serial"
    require_idle "$serial"
    shift
    for key in "$@"; do adb -s "$serial" shell input keyevent "KEYCODE_${key#KEYCODE_}"; done
    ;;
  log)
    serial="$(connect)"
    adb -s "$serial" logcat -d -v time | tr -d '\r' |
      grep -E 'AndroidRuntime|/Celastrina|ExoPlayerImpl|PlaybackException|Remote button pressed' || echo "Nothing logged since the last launch."
    ;;
  screenshot)
    require_python
    serial="$(connect)"
    lock_device "$serial"
    wake "$serial"
    out="${2:-tv.png}"
    save_screenshot "$serial" "$out"
    echo "$out"
    ;;
  smoke)
    [ "$#" -eq 2 ] && [ -n "$2" ] || { echo "Usage: scripts/tv.sh smoke <folder>" >&2; exit 2; }
    [[ "${CELASTRINA_ADB_SERIAL:-}" =~ ^emulator-[0-9]+$ ]] ||
      { echo "Smoke requires an emulator serial in CELASTRINA_ADB_SERIAL." >&2; exit 2; }
    : "${CELASTRINA_NOTORRENT_MANIFEST:?Set the notorrent manifest URL before running smoke.}"
    require_python
    serial="$(connect)"
    lock_device "$serial"
    smoke "$serial" "$2"
    ;;
  *) awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"; exit 2 ;;
esac
