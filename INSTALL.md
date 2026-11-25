# Install Runbook

This file is for an AI agent that installs, updates, configures or removes Celastrina on a Household's Fire TV over ADB,
from macOS, Linux or Windows. If you're a person, point your agent at this file ("install Celastrina on my TV using
INSTALL.md").

## Before any job

- **One TV per run.** Every job starts with [Connect](#connect).
- **Tags.** Every step is tagged **Ask** (ask the person, then wait for their answer), **Do** (do it yourself, without
  asking) or **Verify** (check a result; on a mismatch, go to [Troubleshooting](#troubleshooting)).
- **Posture.** After Connect, read the TV's state, then state your plan once in one sentence ("I'll install
  Celastrina 1.2.0 on the AFTTI43 at 192.168.1.29"). Don't ask again except before a destructive step: uninstalling, or
  anything that loses the Household's data.
- **Never ask for Apps from Unknown Sources.** `adb install` doesn't need it.
- **Packages.** The release is `dev.celastrina` ("Celastrina"). `dev.celastrina.debug` ("Celastrina Dev") is a
  developer build that lives alongside it with its own data. These jobs never install, clear or uninstall
  `dev.celastrina.debug`.
- **Wording.** Speak to the person in plain words. Don't show them commands unless they ask.

Commands below are written for a POSIX shell (macOS, Linux). On Windows use PowerShell; where a command differs, the
PowerShell form follows it. `$ADB` is the `adb` you settle on in Connect, and `$TV` is the TV's serial
(`<ip>:5555`).

### What this runbook supports

<!-- supported-protocol-versions -->
Supported `protocol_version`s: `1`

`status` reports the app's `protocol_version`. If it's higher than every version listed here, this copy of the runbook
is too old to drive that app: stop, and tell the person to pull a newer copy of the Celastrina repository (or to point
you at `INSTALL.md` on GitHub's `main` branch), then start the job again.

### The signing certificate

Every Release is signed with one key. Its certificate's SHA-256 fingerprint is:

<!-- release-certificate-sha256 -->
`PLACEHOLDER: the maintainer records the fingerprint here before the first release (docs/release-keystore.md)`

Android itself refuses an update signed with a different key, so the fingerprint matters most on a first install. If
`apksigner` is on PATH (it comes with the Android SDK build-tools), check a download with
`apksigner verify --print-certs celastrina-<version>.apk`: its `certificate SHA-256 digest` is this fingerprint without
the colons, in lower case. If it isn't, stop and tell the person: the APK isn't a Celastrina Release. Without
`apksigner`, the `SHA256SUMS` check below is what you rely on; don't install the SDK just for this check.

## Connect

### 1. Settle on adb (Do)

Use `adb` if it's on PATH (`adb version`). Otherwise download Google's platform-tools into a per-user folder and call
it by its absolute path. No admin rights, no package manager, no PATH changes.

| OS | Download | Folder | `$ADB` |
|---|---|---|---|
| Linux | `https://dl.google.com/android/repository/platform-tools-latest-linux.zip` | `~/.local/opt/platform-tools` | `~/.local/opt/platform-tools/adb` |
| macOS | `https://dl.google.com/android/repository/platform-tools-latest-darwin.zip` | `~/.local/opt/platform-tools` | `~/.local/opt/platform-tools/adb` |
| Windows | `https://dl.google.com/android/repository/platform-tools-latest-windows.zip` | `%LOCALAPPDATA%\celastrina\platform-tools` | `%LOCALAPPDATA%\celastrina\platform-tools\adb.exe` |

The zip holds a `platform-tools/` folder, so unzip it into the folder's parent:

```sh
mkdir -p ~/.local/opt && cd ~/.local/opt
curl -fLo platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-linux.zip
unzip -q -o platform-tools.zip && rm platform-tools.zip
```

```powershell
$dir = "$env:LOCALAPPDATA\celastrina"; New-Item -ItemType Directory -Force $dir | Out-Null
Invoke-WebRequest https://dl.google.com/android/repository/platform-tools-latest-windows.zip -OutFile "$dir\pt.zip"
Expand-Archive -Force "$dir\pt.zip" $dir; Remove-Item "$dir\pt.zip"
```

Then start the server once: `$ADB start-server`.

### 2. ADB Debugging (Ask)

Ask the person to turn on ADB Debugging, naming their TV if you know it:

> On the TV, open **Settings → My Fire TV → About** (on a Fire TV smart TV it's **Settings → Device & Software →
> About**). Select the first item, the TV's name, 7 times, until it says you're a developer. Press Back, open
> **Developer Options**, and turn on **ADB Debugging**. Tell me when it's on.

If they already did this for an earlier job, skip it. Don't mention Apps from Unknown Sources.

### 3. Find the TV (Do, then Ask only if needed)

1. **Already connected.** `$ADB devices`. If exactly one line ends in `device` and its model
   (`$ADB -s <serial> shell getprop ro.product.model`) is a TV, use it. If there are several, list them by model and
   let the person pick.
2. **Probe the network.** Tell the person you're looking for the TV on their network (don't ask permission). Find
   this computer's IPv4 address on the home network and probe TCP port 5555 on every address of its /24, with about a
   1 s timeout each, in parallel:

   ```sh
   # Linux: base=$(ip -4 route get 1.1.1.1 | sed -n 's/.* src \([0-9.]*\)\.[0-9]* .*/\1/p')
   # macOS: base=$(ipconfig getifaddr "$(route -n get default | awk '/interface:/ {print $2}')" | cut -d. -f1-3)
   for i in $(seq 1 254); do
     (nc -z -w 1 "$base.$i" 5555 2>/dev/null && echo "$base.$i") &   # macOS: nc -z -G 1 -w 1
   done; wait
   # Without nc, bash can probe by itself: (timeout 1 bash -c "</dev/tcp/$base.$i/5555") instead of the nc command.
   ```

   ```powershell
   $base = ((Get-NetIPConfiguration | Where-Object IPv4DefaultGateway).IPv4Address.IPAddress -split '\.')[0..2] -join '.'
   1..254 | ForEach-Object { $c = New-Object Net.Sockets.TcpClient
     [pscustomobject]@{ Ip = "$base.$_"; Task = $c.ConnectAsync("$base.$_", 5555); Client = $c } } |
     ForEach-Object { try { if ($_.Task.Wait(1000)) { $_.Ip } } catch {} ; $_.Client.Dispose() }
   ```

   For each address that answers, `$ADB connect <ip>:5555` and read `ro.product.model` (after
   [authorizing](#4-authorize-do-then-ask), if it asks). **One TV:** confirm it with the person ("I found an AFTTI43
   at 192.168.1.29. Is that your TV?"). **Several:** list each address with its model and let them pick.
3. **Nothing found.** Ask for the address:

   > On the TV, open **Settings → My Fire TV → About → Network** (or **Device & Software → About → Network**) and tell
   > me the IP address shown.

   Then `$ADB connect <ip>:5555`. If nothing answers there either, see "Nothing answers on port 5555" in
   [Troubleshooting](#troubleshooting). If the person says their TV is a **Fire TV Stick 4K Select, Fire TV Stick 4K
   (3rd Gen) or Fire TV Stick HD (2026)** (models `AFTCA002`, `AFTCR001`, `AFTCL001`), it runs Vega OS: stop, and
   explain that Vega OS can't run Android apps, so Celastrina can't be installed on it.

Never store the TV's address for a later run: the router can hand it a new one.

### 4. Authorize (Do, then Ask)

A computer the TV hasn't seen before shows `unauthorized` until the person accepts a prompt on the TV. A computer it
already trusts also shows `unauthorized` (and `adb connect` may print `failed to authenticate`) for a few seconds
after connecting. So:

1. **Do** wait silently: check `$ADB -s $TV get-state` once a second for 10 s. If it prints `device`, go on.
2. **Ask** only if it's still `unauthorized`:

   > Your TV is showing "Allow USB debugging?". Tick **Always allow from this computer**, then choose **OK**.

   Keep checking `get-state` for up to 2 minutes.
3. If it's still not `device`: `$ADB disconnect $TV`, `$ADB connect $TV`, ask once more, and wait up to 2 minutes
   again. After that second failure, go to Troubleshooting ("Stays `unauthorized`").

### 5. Check the TV is supported (Do, Ask if needed)

```sh
$ADB -s $TV shell getprop ro.build.version.sdk       # Android SDK level
$ADB -s $TV shell getprop ro.product.manufacturer    # Amazon on a Fire TV
$ADB -s $TV shell getprop ro.product.model
```

| SDK level | Manufacturer | What to do |
|---|---|---|
| Below 25 (Fire OS 5 or older) | any | **Stop.** Tell the person Celastrina needs Fire OS 6 or later, and this TV can't run it. |
| 25–27 (Fire OS 6) | any | **Ask:** "Your TV runs Fire OS 6. Celastrina works on it as best it can, but it's tested on Fire OS 7. Go ahead?" Continue only on a yes. |
| 28 or higher | not `Amazon` | **Ask:** "This looks like an Android or Google TV, not a Fire TV. Celastrina should run but hasn't been tested on one. Go ahead?" Continue only on a yes. |
| 28 or higher | `Amazon` | Continue. |

On Fire OS 6 and a non-Amazon TV, ask both questions in one message.

### 6. Read Celastrina's state (Do)

```sh
$ADB -s $TV shell pm list packages dev.celastrina
```

`pm list packages` matches by prefix, so look for the exact line `package:dev.celastrina`; a `package:dev.celastrina.debug`
line is Celastrina Dev, which doesn't count. If Celastrina is installed, read its [status](#reading-status).

## Install

Start with [Connect](#connect). If Celastrina is already installed, do [Update](#update) instead.

1. **Do** find the latest Release. Ask GitHub's API, without logging in:

   ```sh
   curl -fsSL https://api.github.com/repos/MasonNight/celastrina/releases/latest
   ```

   `tag_name` is `v<version>`, and the assets are `celastrina-<version>.apk` and `SHA256SUMS`. If that answers 404,
   the repository may still be private: use the person's `gh` login instead (`gh release view --repo
   MasonNight/celastrina --json tagName`). If `gh` isn't installed or logged in, or GitHub can't be reached at all, see
   [Build from source](#build-from-source).
2. **Do** state the plan in one sentence ("I'll install Celastrina 1.2.0 on the AFTTI43 at 192.168.1.29").
3. **Do** download both assets into a new, empty folder:

   ```sh
   curl -fLO https://github.com/MasonNight/celastrina/releases/download/v<version>/celastrina-<version>.apk
   curl -fLO https://github.com/MasonNight/celastrina/releases/download/v<version>/SHA256SUMS
   # or, while the repository is private:
   gh release download v<version> --repo MasonNight/celastrina --pattern 'celastrina-*.apk' --pattern SHA256SUMS
   ```

4. **Verify** the download against `SHA256SUMS`, in that folder:

   ```sh
   sha256sum -c SHA256SUMS          # Linux
   shasum -a 256 -c SHA256SUMS      # macOS
   ```

   ```powershell
   $expected = (Get-Content SHA256SUMS).Split(' ')[0]
   (Get-FileHash -Algorithm SHA256 celastrina-<version>.apk).Hash.ToLower() -eq $expected   # must print True
   ```

   It must print `celastrina-<version>.apk: OK` (or `True`). If it doesn't, download again once; if it still fails,
   stop and tell the person. Never install an APK that fails this check. If `apksigner` is on PATH, also check the
   [signing certificate](#the-signing-certificate).
5. **Do** install, then compile it ahead of time so it runs smoothly (a sideloaded app is otherwise compiled slowly in
   the background):

   ```sh
   $ADB -s $TV install celastrina-<version>.apk                     # prints Success
   $ADB -s $TV shell cmd package compile -m speed -f dev.celastrina # prints Success
   ```

6. **Verify** with [status](#reading-status): `result` is `ok`, `app_version` is `<version>`, and `protocol_version` is
   supported.
7. **Ask** whether they'd like to add their Sources now (their Jellyfin or Emby server, or Stremio add-ons). Celastrina
   comes with the Internet Archive test Source, so it can play a few public-domain Movies already. On a yes, do
   [Add or change Sources](#add-or-change-sources).
8. **Do** end with the [reminder](#ending-every-job).

## Update

Start with [Connect](#connect).

1. **Do** read the installed version: `app_version` from [status](#reading-status). If Celastrina isn't installed, do
   [Install](#install) instead.
2. **Do** find the latest Release, as in [Install](#install) step 1, and compare the versions (`MAJOR.MINOR.PATCH`,
   numerically, field by field).
   - **The same:** tell the person Celastrina is up to date, and stop (with the [reminder](#ending-every-job)).
   - **Installed is newer:** tell them and stop. Never downgrade unless the person asks for a specific older version
     (see [Going back to an older version](#going-back-to-an-older-version)).
   - **Release is newer:** go on.
3. **Do** state the plan ("I'll update Celastrina from 1.1.0 to 1.2.0 on the AFTTI43; Sources, Watchlist and
   everything else stay").
4. **Do** download and **Verify** as in [Install](#install) steps 3 and 4.
5. **Do** install over the old version, keeping its data, and compile it:

   ```sh
   $ADB -s $TV install -r celastrina-<version>.apk                  # prints Success
   $ADB -s $TV shell cmd package compile -m speed -f dev.celastrina # prints Success
   ```

   Never add `-d`. If it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed copy was signed with a
   different key (usually a local build): see [Troubleshooting](#troubleshooting).
6. **Verify** with [status](#reading-status): `app_version` is the new version. If `protocol_version` is higher than
   this runbook [supports](#what-this-runbook-supports), tell the person the update worked, but that this copy of the
   runbook is too old to configure the new version, and they should pull a newer copy of the repository before the
   next job.
7. **Do** end with the [reminder](#ending-every-job).

### Going back to an older version

Only when the person asks for it. Android won't install an older version of a release build over a newer one, even
with `adb install -r -d`, so going back means [uninstalling](#uninstall) first, which loses all of the Household's
data. Spell that out and get an explicit yes, then do Uninstall and then Install with the older Release's assets
(`releases/tags/v<version>` instead of `releases/latest`).

## Uninstall

Start with [Connect](#connect).

1. **Verify** Celastrina is installed (Connect step 6). If it isn't, say so and stop.
2. **Ask**, spelling out what's lost:

   > Uninstalling Celastrina deletes everything it keeps on this TV: your Sources and their logins, any Extensions and
   > Extension Repositories you imported, your Watchlist, Continue watching, your own TMDB key if you set one, and its
   > memory of failing Streams and unplayable Titles. Reinstalling later starts from scratch. Uninstall it?

   Continue only on a clear yes.
3. **Do** `$ADB -s $TV uninstall dev.celastrina` (prints `Success`). Never uninstall `dev.celastrina.debug`.
4. **Verify** `$ADB -s $TV shell pm list packages dev.celastrina` has no line `package:dev.celastrina`.
5. **Do** end with the [reminder](#ending-every-job).

## Add or change Sources

Not written yet. Until it is, the commands are in `docs/adr/0002-config-over-adb-content-provider.md`; ask the person
before any write.

## Import an Extension Repository

Not written yet. Don't import an Extension Repository from this runbook.

## Build from source

Only when the person asks for it, or when GitHub can't be reached for the Release. Never as a fallback for other
errors.

1. **Ask**, warning about the signature switch:

   > I'll build Celastrina on this computer and sign it with a key made here. Android treats that as a different app
   > publisher from the official Releases: switching between a local build and a Release later means uninstalling,
   > which deletes your Sources, Watchlist and everything else Celastrina keeps. Build it locally?

   If Celastrina is already installed from a Release, the install in step 6 will fail until it's uninstalled; say so
   now, and treat that uninstall as in [Uninstall](#uninstall).
2. **Do** get the source at the latest Release's tag (`git clone https://github.com/MasonNight/celastrina.git &&
   git -C celastrina checkout v<version>`), or use the clone you're reading this from if GitHub can't be reached.
3. **Do** install a JDK 17 and the Android command-line tools into per-user folders (`~/.local/opt/jdk-17` and
   `~/.local/opt/android-sdk` on macOS and Linux, `%LOCALAPPDATA%\celastrina\…` on Windows), with no admin rights:
   - JDK 17: the Eclipse Temurin archive for this OS and CPU from `https://adoptium.net/temurin/releases/?version=17`.
   - Android command-line tools: the "Command line tools only" zip from
     `https://developer.android.com/studio#command-line-tools-only`, unzipped so that `sdkmanager` is at
     `<sdk>/cmdline-tools/latest/bin/sdkmanager`. Then
     `sdkmanager --sdk_root=<sdk> --licenses` and `sdkmanager --sdk_root=<sdk> "platforms;android-35" "build-tools;35.0.0"`.

   Set `JAVA_HOME` and `ANDROID_HOME` for these commands only.
4. **Do** make the local signing key, once, at `~/.android/celastrina-local.jks`, with a random password you keep in
   `~/.android/celastrina-local.password` (readable only by the person's user). Reuse both on later builds, so local
   updates keep working:

   ```sh
   keytool -genkeypair -keystore ~/.android/celastrina-local.jks -storetype PKCS12 -alias celastrina-local \
     -keyalg RSA -keysize 4096 -validity 10958 -dname "CN=Celastrina local build" \
     -storepass "$(cat ~/.android/celastrina-local.password)"
   ```

5. **Do** build. A build carries a TMDB key; the person's own key works (TMDB gives them free). Without one, the app
   shows "No TMDB key" until one is set with `set-tmdb-key`, but the build itself refuses to run without a key, so ask
   the person for theirs:

   ```sh
   export CELASTRINA_KEYSTORE=~/.android/celastrina-local.jks CELASTRINA_KEY_ALIAS=celastrina-local
   export CELASTRINA_KEYSTORE_PASSWORD="$(cat ~/.android/celastrina-local.password)"
   export CELASTRINA_KEY_PASSWORD="$CELASTRINA_KEYSTORE_PASSWORD" TMDB_API_KEY=<their key>
   ./gradlew :app:assembleRelease -PreleaseTag=v<version>
   ```

   The APK is `app/build/outputs/apk/release/app-release.apk`. Make your own `SHA256SUMS` isn't needed; skip the
   download and verify steps.
6. **Do** continue with [Install](#install) step 5, or [Update](#update) step 5, using that APK.

## Reading status

```sh
$ADB -s $TV shell content call --uri content://dev.celastrina.configuration --method status
```

It prints `Result: Bundle[{reply={…}}]`, where the reply is one JSON object, for example
`{"result":"ok","app_version":"1.2.0","tmdb_key":"project","sources":1,"extensions":0,"protocol_version":1}`.

The call starts Celastrina in the background if it isn't running, even if it's never been opened. If it prints
nothing useful (no `reply=`, or `Could not find provider`), open the app once and try again, once:

```sh
$ADB -s $TV shell am start -W -n dev.celastrina/dev.celastrina.app.MainActivity
```

If `status` still fails, go to [Troubleshooting](#troubleshooting).

## Ending every job

End every job, whether it finished or stopped, with one line:

> You can turn ADB Debugging off now (Settings → My Fire TV → Developer Options). Turn it back on before you next ask me
> to install, update or change Celastrina.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `curl`/`unzip`/`Expand-Archive` fails downloading platform-tools | Retry once. If `dl.google.com` can't be reached, ask the person to install Android platform-tools themselves and tell you where `adb` is. |
| Nothing answers on port 5555, and the person's address doesn't connect | ADB Debugging is off, the TV is asleep or on another network, or it's a Vega OS stick. Ask the person to check Developer Options → ADB Debugging is on, wake the TV, and confirm the address in About → Network. If there's no ADB Debugging option at all, check the About screen for "Vega OS": that TV can't run Celastrina. |
| Several TVs answer | List each address with `ro.product.model` and let the person pick. |
| Stays `unauthorized` after two prompts | The prompt was dismissed or missed, or the TV forgot this computer. Ask the person to open Developer Options, choose **Revoke USB debugging authorizations** if it's there (or turn ADB Debugging off and on), then do Authorize again. |
| `offline` in `adb devices` | `$ADB disconnect $TV`, then `$ADB connect $TV`. If it persists, `$ADB kill-server` and connect again. |
| `failed to connect … Connection refused` after it worked | The TV's address changed or ADB Debugging was turned off. Do Find the TV again. |
| `gh release view` says `release not found` | There's no Release yet. Tell the person; offer [Build from source](#build-from-source) only if they want it. |
| `releases/latest` answers 404 | The repository is private: use `gh` with the person's login. If `gh` isn't set up, ask the person to sign in with `gh auth login`, or offer [Build from source](#build-from-source). |
| The `SHA256SUMS` check fails | Download both files again into an empty folder. If it fails twice, stop: don't install it. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy was signed with another key: a local build over a Release, or the reverse. Installing needs an [uninstall](#uninstall) first, which loses all data. Ask, as in Uninstall step 2. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The installed copy is newer. Don't add `-d`; see [Going back to an older version](#going-back-to-an-older-version). |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Ask the person to free some space (Settings → Applications → Manage Installed Applications) and retry. |
| `INSTALL_FAILED_NO_MATCHING_ABIS` or `INSTALL_FAILED_OLDER_SDK` | This TV can't run Celastrina; recheck the SDK level in Connect step 5. |
| `cmd package compile` fails | Not fatal: Celastrina runs, but starts more slowly until Fire OS compiles it. Carry on and mention it. |
| `status` prints no `reply=` after opening the app once | Ask the person whether Celastrina opened on the TV. If it crashed, report it as a Celastrina bug with `$ADB -s $TV logcat -d \| grep -E 'AndroidRuntime\|Celastrina'`. |
| `status` reports a `protocol_version` this runbook doesn't list | Stop and tell the person to pull a newer copy of the Celastrina repository. |
| `adb uninstall` prints `Failure [DELETE_FAILED_INTERNAL_ERROR]` | Celastrina isn't installed (check for the exact line `package:dev.celastrina`). |
