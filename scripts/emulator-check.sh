#!/usr/bin/env bash
# Run all instrumented tests in disposable app installations on the selected Android TV device.
set -euo pipefail

serial="${CELASTRINA_ADB_SERIAL:?Set CELASTRINA_ADB_SERIAL to the device serial.}"
cd "$(dirname "$0")/.."
device=(adb -s "$serial")
[ "$("${device[@]}" get-state)" = device ]

work="$(mktemp -d "${TMPDIR:-/tmp}/celastrina-check.XXXXXX")"
installed=()
install_count=0
device_lock=
cleanup() {
  local status=$? index package packages
  for ((index=install_count - 1; index >= 0; index--)); do
    package="${installed[index]}"
    if packages="$(list_packages "$package")"; then
      if grep -Fxq "package:$package" <<<"$packages"; then
        "${device[@]}" uninstall "$package" || status=1
      fi
    else
      status=1
    fi
  done
  if [ -n "$device_lock" ]; then rmdir -- "$device_lock" || status=1; fi
  rm -rf -- "$work"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# Use a disposable signing key for the minified build, as the release gates do.
export CELASTRINA_KEYSTORE="$work/device-check.p12"
case "$OSTYPE" in
  msys*|cygwin*) CELASTRINA_KEYSTORE="$(cygpath -m "$CELASTRINA_KEYSTORE")" ;;
esac
export CELASTRINA_KEYSTORE_PASSWORD=device-check CELASTRINA_KEY_ALIAS=device-check CELASTRINA_KEY_PASSWORD=device-check
keytool -genkeypair -keystore "$CELASTRINA_KEYSTORE" -storetype PKCS12 -alias "$CELASTRINA_KEY_ALIAS" \
  -storepass "$CELASTRINA_KEYSTORE_PASSWORD" -keypass "$CELASTRINA_KEY_PASSWORD" \
  -keyalg RSA -keysize 2048 -validity 1 -dname CN=CelastrinaDeviceCheck

build_apks() {
  local build_type="$1" build="$2"
  ./gradlew -PisolatedDeviceCheck=true "-PtestBuildType=$build_type" \
    ":app:assemble$build" ":app:assemble${build}AndroidTest" --console=plain
  cp "app/build/outputs/apk/$build_type/app-$build_type.apk" "$work/$build_type.apk"
  cp "app/build/outputs/apk/androidTest/$build_type/app-$build_type-androidTest.apk" "$work/$build_type-test.apk"
}
build_apks debug Debug
build_apks release Release

# Use the same per-device lock as tv.sh.
lock_key="${serial//%/%25}"
lock_key="${lock_key//:/%3A}"
lock_key="${lock_key//\//%2F}"
case "$OSTYPE" in
  msys*|cygwin*) lock_root="$(cygpath -u "$LOCALAPPDATA")/Celastrina/locks" ;;
  *) lock_root="$HOME/.cache/celastrina/locks" ;;
esac
lock="${CELASTRINA_TV_LOCK_DIR:-$lock_root/$lock_key.lock}"
mkdir -p "$(dirname "$lock")"
if ! mkdir "$lock"; then
  echo "Could not lock $serial at $lock. Finish the other device session before rerunning." >&2
  exit 1
fi
device_lock="$lock"

list_packages() {
  "${device[@]}" shell pm list packages "$1" | tr -d '\r'
}

for package in dev.celastrina.debug.devicecheck dev.celastrina.devicecheck; do
  for name in "$package" "$package.test"; do
    packages="$(list_packages "$name")"
    if grep -Fxq "package:$name" <<<"$packages"; then
      echo "$name is already installed on $serial. Leaving it and its data in place." >&2
      exit 1
    fi
  done
done

run_tests() {
  local build_type="$1" package="$2" results="$work/$1-results.txt"
  installed[install_count++]="$package"
  "${device[@]}" install -t "$work/$build_type.apk" || return 1
  installed[install_count++]="$package.test"
  "${device[@]}" install -t "$work/$build_type-test.apk" || return 1
  "${device[@]}" shell am instrument -w -r "$package.test/androidx.test.runner.AndroidJUnitRunner" \
    | tr -d '\r' | tee "$results" || return 1
  # Android's shell command can exit zero even on a test failure or if the target crashes.
  if ! grep -Eq '^OK \([1-9][0-9]* tests?\)$' "$results" || ! grep -Fxq 'INSTRUMENTATION_CODE: -1' "$results"; then
    echo "Instrumented tests failed or did not finish on $build_type." >&2
    return 1
  fi
}

# Run both variants so a debug failure does not hide a release-only failure.
failed=0
run_tests debug dev.celastrina.debug.devicecheck || failed=1
run_tests release dev.celastrina.devicecheck || failed=1
exit "$failed"
