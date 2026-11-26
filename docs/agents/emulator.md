# On-device checks

Use the `celastrina-tv2` Android TV emulator (API 28), serial `emulator-5554`. Drive it with `scripts/tv.sh` or `adb -s emulator-5554`. Use the T3 Device panel to view it.

In Git Bash, put Android SDK platform-tools (`adb`) and `python3` on `PATH`.

- Start with `powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\mason\.config\celastrina\start-tv-emulator.ps1`. Add `-NoWindow` for headless use.
- In Git Bash, export `CELASTRINA_ADB_SERIAL=emulator-5554` and install the debug APK with `scripts/tv.sh install`.
- Check playback with `scripts/tv.sh smoke <folder>`. Set `CELASTRINA_NOTORRENT_MANIFEST` to the notorrent manifest URL. Smoke clears debug app data, adds the Source again and saves screenshots in the folder.
- For configuration calls, use the [Install Runbook](../../INSTALL.md#calling-celastrina) with `content://dev.celastrina.debug.configuration`.
- Run instrumented tests on just this emulator with `adb -s emulator-5554 shell am instrument -w -r <test package>/androidx.test.runner.AndroidJUnitRunner`.

## Device lock

`scripts/tv.sh` locks device commands with an exclusive directory per serial. Before driving the device directly, create the same directory atomically, wait if held, and remove it in a trap or `finally`.

- Windows: `$env:LOCALAPPDATA\Celastrina\locks\emulator-5554.lock`. Use `New-Item -ItemType Directory -ErrorAction Stop` for acquisition without `-Force`.
- Linux and macOS: `$HOME/.cache/celastrina/locks/emulator-5554.lock`. Use `mkdir`.
- For other serials, replace the basename with the serial plus `.lock`, escaping `%`, `:` and `/` as `%25`, `%3A` and `%2F`. Set `CELASTRINA_TV_LOCK_DIR` to use a different directory and share that path with other callers.

Ask the maintainer before using the household TV for Fire OS behavior, hardware decoders, frame times, or the physical remote.
