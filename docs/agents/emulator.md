# On-device checks

Before reporting an implementation ticket done, pass the disposable runner below or
`scripts/emulator-check.sh` against an existing device with `CELASTRINA_ADB_SERIAL` set.

## Disposable emulator on Mason-FractalNorth

For automated ticket verification, use the Windows runner from Git Bash after loading the build environment.
It needs Python, Git for Windows, hardware acceleration, and the installed SDK package
`system-images;android-28;android-tv;x86`. Use a separate checkout per concurrent run so Gradle outputs stay isolated.

```bash
python -B -m unittest discover -s scripts -p test_disposable_emulator.py
python -B scripts/disposable-emulator.py
```

The runner builds and validates the four APKs before starting a fresh Android TV emulator. It reserves one
port pair on the host, runs both instrumented suites, and releases the slot when it finishes or fails. The
host defaults to one disposable emulator at a time. Set `CELASTRINA_EMULATOR_SLOTS` consistently on the host
to allow more concurrent runs. Keep performance runs alone on the host.

Find the emulator and test output under `app/build/reports/disposable-emulator/`. A Windows job stops the
emulator process tree if the runner exits abruptly. The next run reclaims the abandoned AVD files after
it reserves that slot and confirms its ports are free. The shared emulator on port 5554 is outside this pool.

Per-run APKs can be prepared with `scripts/emulator-check.sh --prepare-apks DIRECTORY` and reused with
`python -B scripts/disposable-emulator.py --apks DIRECTORY`. Use an existing empty directory when preparing
the bundle and record the source commit if you move it to another machine. Pass `--bash` if Git Bash is
not the default `bash` on your Windows PATH.

For a quick check of real emulator descendant cleanup (normal exit, abrupt termination, and slot reuse),
run `CELASTRINA_TEST_REAL_EMULATOR=1 python -B -m unittest discover -s scripts -p test_disposable_emulator.py`
from Git Bash. This needs the same installed SDK image but does not build or install APKs. Run it while
the disposable pool is idle. The SDK-free lifecycle test also checks ownership of a native Windows child;
a Python-only child can hide Microsoft Store Python's different native-child job inheritance.

## Shared emulator and household TV

For interactive work, open the `celastrina-tv2` Android TV emulator (API 28), serial `emulator-5554`, in the
T3 Device panel. If another T3 session owns it, use a disposable emulator for automated checks or wait
for the owner to release the shared one. This T3 session reservation is separate from the shell lock below.

In Git Bash, put Android SDK platform-tools (`adb`) and `python3` on `PATH`.

- Start with `powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\mason\.config\celastrina\start-tv-emulator.ps1`. Add `-NoWindow` for headless use.
- In Git Bash, export `CELASTRINA_ADB_SERIAL=emulator-5554` and install the debug APK with `scripts/tv.sh install`.
- Check playback with `scripts/tv.sh smoke <folder>`. Set `CELASTRINA_NOTORRENT_MANIFEST` to the notorrent manifest URL. Smoke clears debug app data, adds the Source again and saves screenshots in the folder.
- For configuration calls, use the [Install Runbook](../../INSTALL.md#calling-celastrina) with `content://dev.celastrina.debug.configuration`.
- Run instrumented tests on just this emulator with `adb -s emulator-5554 shell am instrument -w -r <test package>/androidx.test.runner.AndroidJUnitRunner`.

For an existing device, run `CELASTRINA_ADB_SERIAL=emulator-5554 scripts/emulator-check.sh`.
The script builds both debug and minified release, runs all instrumented tests for each, and removes both
disposable app installations and their test packages. The disposable installations use their own application IDs.
If the maintainer asks for a household TV check instead, pass that TV's serial from `scripts/tv.sh connect`.
To reuse a minified release built on another machine, pass `--release-apks DIRECTORY`. Put
`app-release.apk` and `app-release-androidTest.apk` there from the same build of the source under test,
using `-PisolatedDeviceCheck=true -PtestBuildType=release`. Record the source commit with the test result.
The script still builds debug and checks package IDs and instrumentation targets before installing anything.

## Device lock

`scripts/tv.sh` locks device commands with an exclusive directory per serial. Before driving the device directly, create the same directory atomically, wait if held, and remove it in a trap or `finally`.

- Windows: `$env:LOCALAPPDATA\Celastrina\locks\emulator-5554.lock`. Use `New-Item -ItemType Directory -ErrorAction Stop` for acquisition without `-Force`.
- Linux and macOS: `$HOME/.cache/celastrina/locks/emulator-5554.lock`. Use `mkdir`.
- For other serials, replace the basename with the serial plus `.lock`, escaping `%`, `:` and `/` as `%25`, `%3A` and `%2F`. Set `CELASTRINA_TV_LOCK_DIR` to use a different directory and share that path with other callers.

Ask the maintainer before using the household TV for Fire OS behavior, hardware decoders, frame times, or the physical remote.

## Fire OS launcher tiles

For an approved household TV icon check, use `scripts/tv.sh apps` to open **Your Apps & Channels**
without D-pad navigation. The TV display must already be awake. The launcher’s Apps button opens
`amzn://apps/android?a_lib`, resolved on the household TV to Amazon’s built-in Apps library
(`com.amazon.venezia/.deeplink.UriMatchActivity`); the similarly advertised `ALL_APPS` proxy does nothing
on Fire OS 7. An already open grid keeps its current viewport. `scripts/tv.sh tile-shot <out.png>` opens that grid, waits for it to draw,
saves the tiles, and returns to Home through the launcher's activity. Both commands take the device lock
and refuse while the TV appears busy. They fail if the verified Fire OS activity cannot start;
they do not navigate with remote keys. Android TV emulators do not have this Fire OS grid.
