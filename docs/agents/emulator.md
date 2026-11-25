# On-device checks: the `celastrina-tv` emulator

The emulator is an Android TV AVD on the maintainer's Windows PC (Mason-FractalNorth): API 28 x86 (the same Android version as the household AFTTI43's Fire OS 7), 1920×1080, density 320, WHPX acceleration.

- **Start**, idempotent, waits for boot (~41 s cold): `powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\mason\.config\celastrina\start-tv-emulator.ps1` (add `-NoWindow` for headless).
- **Stop**: `adb -s emulator-5554 emu kill`.
- **Drive it** from Git Bash: `CELASTRINA_ADB_SERIAL=emulator-5554 scripts/tv.sh install|launch|open-movie <id>|press <key>…|screenshot <png>|log`. Point `XDG_CONFIG_HOME` at a scratch folder so the TV's remembered address isn't touched.
- **Provider calls**: `adb -s emulator-5554 shell content call --uri content://dev.celastrina.debug.configuration …` (debug) or `content://dev.celastrina.configuration` (release); see INSTALL.md "Calling Celastrina".
- **Instrumented tests**: `adb -s emulator-5554 shell am instrument -w -r <test package>/androidx.test.runner.AndroidJUnitRunner`. Use `connected*AndroidTest` only when `adb devices` lists the emulator alone, because it runs on every connected device.

One agent drives the emulator at a time.

**What only the household TV can show**: Fire OS behaviour, the Omni's hardware decoders (HEVC, Dolby Vision), real frame times (#41), and the physical remote. Ask the maintainer before using the TV for any of these.
