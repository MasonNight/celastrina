#!/usr/bin/env bash
# PROTOTYPE — measures artwork browsing smoothness, memory and APK size on a connected TV.
# Usage: ./measure.sh <tv-ip>   (needs a release build: ./gradlew assembleRelease)
set -euo pipefail
ADB="${ADB:-adb}"; TV="$1:5555"; PKG=dev.celastrina.prototype.browsing
APK=app/build/outputs/apk/release/app-release.apk
$ADB connect "$TV" >/dev/null; $ADB -s "$TV" wait-for-device
echo "APK size: $(stat -c %s $APK) bytes ($(du -h $APK | cut -f1))"
[ -n "${SKIP_INSTALL:-}" ] || $ADB -s "$TV" install -r "$APK" >/dev/null
k() { $ADB -s "$TV" shell input keyevent "$@"; }
for v in A B C; do
  $ADB -s "$TV" shell am force-stop $PKG
  $ADB -s "$TV" shell am start -W -n $PKG/.MainActivity -e variant $v | grep -E 'TotalTime' | sed "s/^/  $v cold start /"
  sleep 4
  $ADB -s "$TV" shell dumpsys gfxinfo $PKG reset >/dev/null
  # Browse: right x10 along a row, down a row, repeat; then back up. Mirrors flicking through artwork.
  for r in 1 2 3 4 5; do k 22 22 22 22 22 22 22 22 22 22; k 20; done
  for r in 1 2 3 4 5; do k 21 21 21 21 21; k 19; done
  sleep 2
  echo "== Variant $v"
  $ADB -s "$TV" shell dumpsys gfxinfo $PKG | grep -E 'Total frames|Janky frames|50th|90th|95th|99th' | sed 's/^/  /'
  $ADB -s "$TV" shell dumpsys meminfo $PKG | grep -E 'TOTAL( PSS)?:|TOTAL  ' | head -1 | sed 's/^/  mem /'
  $ADB -s "$TV" shell dumpsys meminfo $PKG | grep -E 'Java Heap|Native Heap|Graphics' | head -3 | sed 's/^/  /'
done
