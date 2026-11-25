#!/usr/bin/env bash
# Measure home's smoothness, cold start, memory and APK size on the household TV, the way the browsing prototype's
# measure.sh did (prototype/browsing-screens), so the numbers compare with its Variant A.
#
#   scripts/measure.sh [package] [apk]   default package dev.celastrina (a release build); the apk is only sized
#
# It scrolls home with D-pad bursts (5 × ten Rights then Down, then 5 × five Lefts then Up) and prints
# `dumpsys gfxinfo` frame times and `dumpsys meminfo` totals. Measure a release build: debug Compose is far slower.
# The TV is shared: run it under the TV lock, after `scripts/tv.sh launch` has checked nobody is using the TV.
set -euo pipefail

PKG="${1:-dev.celastrina}"
APK="${2:-}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
serial="$("$REPO/scripts/tv.sh" connect)"
a() { adb -s "$serial" "$@"; }
k() { a shell input keyevent "$@"; }

[ -z "$APK" ] || echo "APK size: $(stat -c %s "$APK") bytes ($(du -h "$APK" | cut -f1))"
a shell input keyevent KEYCODE_WAKEUP
a shell am force-stop "$PKG"
a shell am start -W -n "$PKG/dev.celastrina.app.MainActivity" | grep -E 'TotalTime' | sed 's/^/cold start /'
sleep 6
a shell dumpsys gfxinfo "$PKG" reset >/dev/null
for _ in 1 2 3 4 5; do k 22 22 22 22 22 22 22 22 22 22; k 20; done
for _ in 1 2 3 4 5; do k 21 21 21 21 21; k 19; done
sleep 2
a shell dumpsys gfxinfo "$PKG" | grep -E 'Total frames|Janky frames|50th|90th|95th|99th' | sed 's/^/  /'
a shell dumpsys meminfo "$PKG" | grep -E 'TOTAL( PSS)?:|TOTAL  ' | head -1 | sed 's/^/  mem /'
