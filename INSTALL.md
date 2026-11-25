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

Start with [Connect](#connect), then read [status](#reading-status): Celastrina must be installed and its
`protocol_version` supported. The commands are written `call <command> '<JSON>'`; see
[Calling Celastrina](#calling-celastrina).

### 1. Read the Sources (Do)

```sh
call list
```

`sources` lists every Source in the order Celastrina asks them, each with `id`, `name`, `kind` (`internet-archive`,
`jellyfin`, `emby`, `stremio`, or `extension` for a Source an Extension provides), `enabled`, `position` (1 is asked
first), `test_state` (`passing`, `failing` or `untested`), `has_login`, and sometimes `problem` and `update_available`.
Tell the person what they have in plain words, by name and in order ("You have two Sources: Home Jellyfin first, then
the Internet Archive, which has a few public-domain Movies."). Speak of Sources by name; the IDs are yours.

### 2. Find out what to change (Ask)

If the person hasn't said, ask what they'd like to do: add a Source, change one (its name, address or login), turn one
off or on, change the order, or remove one. Then state your plan in one sentence and do it. Do several changes in one
run if they ask for several.

### 3. Add a Source (Ask, then Do)

Celastrina can use these Source Kinds:

- **Jellyfin** (`jellyfin`) or **Emby** (`emby`): the person's own media server.
- **Stremio-compatible add-on** (`stremio`): an add-on the person already uses, by its manifest URL (it ends
  `/manifest.json`).
- Sources from Extensions come from [Import an Extension Repository](#import-an-extension-repository), not from here.

Ask for what you need, in one message:

- **A name** for the Source, shown on the TV ("Home Jellyfin"). Offer one made from the server's or add-on's name if
  they don't mind.
- **For Jellyfin or Emby:** the address they open the server at in a browser, such as `http://192.168.1.20:8096`, and
  the username.
- **For an add-on:** the manifest URL. It often holds the person's own settings or a secret token, so never repeat it
  back in full, write it anywhere else, or show it in a summary: call it "the add-on's URL".

Before adding, **Do** check the address:

- If its host is `localhost`, `127.0.0.1`, `::1` or `0.0.0.0`, it names the TV itself, not the server. Ask for the
  server's address on the home network (on the server's computer, the address other devices use; Jellyfin's Dashboard
  shows it).
- An address without `http://` or `https://`: Jellyfin and Emby usually answer at `http://<address>:8096`. Ask rather
  than guess when you can't tell.

**Jellyfin and Emby log in.** For Jellyfin, **Ask** how they'd like to log in, unless they already said:

> I can log in to Jellyfin for you with your password; Celastrina keeps only the access token, never the password. Or
> you can log in on the TV with Quick Connect, approving a code from your phone, so the password never passes through
> me. Which would you like?

The password through you is the default; Emby has no Quick Connect, so for Emby just ask for the password. An empty
password is allowed if the account has none.

**With the password**, add it:

```sh
call add '{"kind":"jellyfin","name":"<name>","url":"<server address>","username":"<username>","password":"<password>"}'
call add '{"kind":"emby","name":"<name>","url":"<server address>","username":"<username>","password":"<password>"}'
call add '{"kind":"stremio","name":"<name>","url":"<manifest URL>"}'
```

Forget the password once the call returns: don't repeat it, store it, or put it in a file that stays.

**With Quick Connect** (Jellyfin only), tell the person what to do on the TV:

> On the TV, open Celastrina, press the Menu button (☰) on the remote, and choose **Settings → Sources → Add Source →
> Jellyfin**. Fill in the name and the server address, then choose **Use Quick Connect instead**. The TV shows a code:
> on your phone or computer, sign in to Jellyfin, open your profile's **Quick Connect** page, and enter it. Tell me
> when the TV says it's added.

Then **Do** `call list` every 5 seconds, for up to 5 minutes, until a `jellyfin` Source with that name appears. If the
TV says Quick Connect is turned off, the server's administrator can turn it on in the Jellyfin dashboard
(**Dashboard → General → Quick Connect**), or the person can log in with the password instead.

**Verify.** `add` replies `ok` with the new Source's `id`. `call list` shows it last in the order, `enabled`,
`test_state` `passing` and, for Jellyfin or Emby, `has_login` `true`. Tell the person ("Home Jellyfin is added, after
the Internet Archive, and its connection test passed."). If the reply is `failure`, the Source is saved but failing:
go to [A failed connection test](#a-failed-connection-test). If it's `invalid`, nothing was saved: see
[Replies](#replies).

Adding exactly the same Source again is harmless and replies its `id`. Add each Jellyfin or Emby server only once:
a second Source logging in to the same server signs the first one out (it then fails with `auth_required`). To use
another account, change the existing Source's login instead.

### 4. Change a Source (Do)

Change only the settings the person named; the others stay as they are, and the Source keeps its `id` and its place
in the order:

```sh
call update '{"id":"<id>","name":"<new name>"}'
call update '{"id":"<id>","url":"<new address or manifest URL>"}'
call update '{"id":"<id>","password":"<new password>"}'
call update '{"id":"<id>","username":"<new username>","password":"<its password>"}'
```

A new username needs its password in the same call. Only Jellyfin and Emby take a username or password, only they and
add-ons take a URL, and the Internet Archive takes only a name. **Verify** like an add: `ok`, then `call list`.
Changing a URL or login runs the connection test again; on `failure` see [A failed connection
test](#a-failed-connection-test).

### 5. Turn a Source off or on (Do)

```sh
call disable '{"id":"<id>"}'
call enable '{"id":"<id>"}'
```

A disabled Source keeps its settings and place, but Celastrina doesn't ask it for Streams. Enabling runs its
connection test again. **Verify** `enabled` with `call list`.

### 6. Change the order (Ask if unclear, then Do)

Celastrina asks Sources in order and prefers Streams from earlier ones. Take the new order in the person's own words
("the Archive last", "Home Jellyfin first, then the add-on"), map each name to its `id`, and put them first:

```sh
call set-order '{"ids":["<first id>","<second id>"]}'
```

The Sources you name go first, in that order; the rest follow in the order they were in. So "put X last" means naming
every other Source, in their current order. If the wording could mean two orders, ask once. **Verify** with `call list`
and read the whole order back ("Now: Home Jellyfin, then the add-on, then the Internet Archive.").

### 7. Remove a Source (Ask, then Do)

**Ask** first, naming what's lost:

> Remove Home Jellyfin? Celastrina forgets its address and login; adding it back later needs the password (or Quick
> Connect) again. Your Watchlist and Continue watching stay.

Only on a yes:

```sh
call remove '{"id":"<id>"}'
```

**Verify** it's gone from `call list`. Removing a Source that's already gone replies `ok`. Offer to turn a Source off
instead when the person might want it back. A Source from an Extension comes back if the Extension is installed again;
to get rid of it for good, remove its Extension (see [Import an Extension
Repository](#import-an-extension-repository), step 7).

### 8. Use your own TMDB key (optional: Ask)

Only when the person asks, or when the TMDB key is failing: `status` says `"tmdb_key":"none"`, or the TV says "No TMDB
key" or "TMDB rejected the key". Celastrina's home screen and search come from TMDB, with a key built into the app.

1. **Ask** for their own key:

   > Celastrina can use your own TMDB key instead of the built-in one. TMDB gives them free: sign in at themoviedb.org,
   > open **Settings → API**, and copy either the **API Key** or the **API Read Access Token**. Paste it here.

2. **Verify** it from this computer before setting it, without printing it. A Read Access Token starts `eyJ`:

   ```sh
   curl -s -o /dev/null -w '%{http_code}\n' "https://api.themoviedb.org/3/configuration?api_key=<key>"        # API Key
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <token>" https://api.themoviedb.org/3/configuration  # token
   ```

   `200` is good. `401` means TMDB turned it down: ask them to copy it again, once. Any other answer: TMDB can't be
   checked from here; set it anyway and say so.
3. **Do** set it, then **Verify** `status` says `"tmdb_key":"override"`:

   ```sh
   call set-tmdb-key '{"key":"<key>"}'
   ```

   The TV starts using it at once. Never repeat the key back; `status` never shows it either.

To go back to the built-in key, when the person asks: `call clear-tmdb-key`, then `status` says `"tmdb_key":"project"`
(or `none` in a build without one).

### 9. End the job (Do)

Read `call list` once more and tell the person the Sources and their order in one sentence, then the
[reminder](#ending-every-job).

### A failed connection test

A `failure` reply means the change was saved, but Celastrina couldn't use the Source:
`{"result":"failure","id":"home-jellyfin","reason":"unreachable","message":"Can't reach Home Jellyfin at http://…"}`.
The Source shows "Failing its test" on the TV until it's fixed. Offer **one** round of fixing:

1. **Do** check the address, if the reason is `unreachable`:
   - Its host isn't `localhost`, `127.0.0.1`, `::1` or `0.0.0.0` (see step 3).
   - The TV can reach it. With the host and port from the address (port 80 for `http://`, 443 for `https://`, unless
     it names one):

     ```sh
     $ADB -s $TV shell "toybox nc -w 5 <host> <port> </dev/null && echo reachable"
     $ADB -s $TV shell ping -c 1 -W 3 <host>     # a name that won't resolve prints "unknown host"
     ```

2. **Do** explain it in plain words, using the `reason`, the checks, and the `message` (which never shows an add-on's
   URL):

   | `reason` | Say |
   |---|---|
   | `unreachable` | The TV can't reach the server at that address. It may be off or asleep, the address or port may be wrong, `http` and `https` may be swapped, or it's only reachable from the computer it runs on. If the check above said `reachable`, the server answered too slowly: try again in a minute. |
   | `auth_required` | The server turned down the username or password (or the saved login, if it's gone stale). For an add-on, it turned the URL down: its settings may need making again on the add-on's own page. |
   | `rate_limited` | The server is refusing requests for now. Nothing to fix: try again later (enable it again, or update with the same settings). |
   | `source_error` | Something answered, but not a Jellyfin or Emby server (often a wrong path: use the address the server's own web page is at, without `/web/…`), or not an add-on manifest. An add-on that offers no Streams, such as a catalogue-only add-on, can't be used at all. |

3. **Ask** for the one thing that would fix it (a corrected address, the password again), then `call update` with just
   that and check the reply. A Jellyfin or Emby Source whose first login failed has no login saved, so a corrected
   address needs the password in the same call (`{"id":"<id>","url":"<address>","password":"<password>"}`); if
   you've already forgotten it, ask for it again. If it still fails, stop fixing: tell the person the Source is saved but failing, and that
   it will work once the server's reachable (or the login's right) without being added again. Offer to remove it if
   they'd rather not keep it.

### Replies

Every reply is one JSON object with `result`:

- `ok`: done. Repeating a command that's already done replies `ok` and changes nothing.
- `failure`: saved, but the Source failed its connection test (`id`, `reason`, `message`). See above.
- `invalid`: refused, nothing changed (`problem`, `message`). The `message` says what's wrong in words you can pass on.

| `problem` | What to do |
|---|---|
| `bad_argument` | Your call is malformed (not base64, not a JSON object, a missing field, a field this command doesn't take, or the wrong type). Fix the call; don't involve the person. |
| `unknown_command` | This runbook and the app disagree. Check `protocol_version`; tell the person to pull a newer copy of the repository. |
| `unknown_source` | No Source has that `id`. Read `call list` again and map the name to its `id` afresh. |
| `blank_name` | Ask for a name. |
| `unknown_kind` | Use `jellyfin`, `emby` or `stremio`. |
| `bad_url` | The address must start `http://` or `https://` and name a host (a manifest URL ends `/manifest.json`). Ask for it again. |
| `missing_login` | Jellyfin and Emby need a username and a password (it may be empty), and a new username needs its password too. |
| `name_taken` | Another Source has that name. Ask for another, or offer to change that Source instead. |
| `repeated_source` | Your `set-order` named a Source twice. Fix the list. |
| `unneeded_setting` | That Source Kind takes no URL or login (the Internet Archive takes neither; an add-on takes no login). Drop the field. |
| `blank_tmdb_key` | The key was empty. Ask for it again. |

## Import an Extension Repository

Extensions are third-party code that add Sources to Celastrina. They come from an Extension Repository: a list at a
URL, run by someone other than Celastrina. Celastrina doesn't ship, recommend or link any, and neither do you.

**Rules for this job:**

- **The URL comes only from the person.** Never suggest, search for, look up or guess one, and never reuse one from
  another conversation. If they ask you for one, say you can't recommend any and that the choice is theirs.
- **The warning is shown word for word** and needs an explicit yes ("yes", "go ahead", "import it"). Anything else
  (silence, a question, "I guess", a changed subject) is a no.
- **The person picks the Extensions.** List them without recommending, ranking or describing any beyond what the list
  says.

Start with [Connect](#connect), then read [status](#reading-status). The commands are written
`call <command> '<JSON>'`; see [Calling Celastrina](#calling-celastrina).

### 1. Get the URL (Ask)

If the person hasn't given it:

> What's the address of the Extension Repository you'd like to import? It's a link that usually ends `.json`.

If the host is `localhost` or `127.0.0.1`, it names the TV itself: ask for an address the TV can reach.

### 2. Show the warning (Do, then Ask)

**Do** ask Celastrina for its warning, by importing without acknowledging it:

```sh
call import-repository '{"url":"<url>"}'
```

It replies `invalid` with problem `warning_not_acknowledged`, the `warning` text and its `warning_version`. **Ask**,
quoting the `warning` exactly as it came, with nothing added inside the quote:

> Before I import <url>, Celastrina asks you to read this:
>
> > <warning, word for word>
>
> Import it?

On anything but an explicit yes, stop: nothing was imported. Show the warning on every import, even of a Repository
imported before.

### 3. Import (Do)

```sh
call import-repository '{"url":"<url>","warning_acknowledged":<warning_version>}'
```

`warning_acknowledged` is the number from the `warning_version` you just showed. The reply's `repository` has its
`name`, `url` and `extensions`, each with `name` (what you install it by), `display_name`, `version`, `description`,
`authors`, `languages`, `installed` and `update_available`. On `failure`, the Repository couldn't be read (`reason`,
`message`): `unreachable` means the TV can't reach the URL (check it as in [A failed connection
test](#a-failed-connection-test)); `source_error` means it answered with something that isn't an Extension Repository.
Tell the person and stop; ask whether the URL is right, once.

Importing the same Repository again is harmless: it refreshes the list.

### 4. Let the person pick (Ask)

List every Extension in the Repository's order, each by `display_name`, with its `description`, languages and authors
when it has them, and mark the ones already installed. Don't recommend, rank or comment on any. Then ask:

> Which would you like to install? Any, all or none.

None is a fine answer: the Repository stays imported, and Extensions can be installed from it later.

### 5. Install the chosen Extensions (Do)

For each one picked, by its `name`:

```sh
call install-extension '{"repository":"<repository url>","name":"<name>"}'
```

`ok` replies `ids`: the Sources the Extension became, added enabled at the end of the order. `failure` (with `reason`
and `message`) means it isn't usable: it couldn't be downloaded (`unreachable`), or didn't load or needs a newer
Celastrina (`source_error`). Pass the `message` on in plain words. Installing one that's already installed is harmless.

**Verify** with `call list` (its Sources are last, `kind` `extension`) and `status` (`extensions` went up). Tell the
person which Sources were added, by name. If they want them elsewhere in the order, do [Add or change
Sources](#add-or-change-sources) step 6.

### 6. Update Extensions (Do, then Ask)

Nothing updates by itself. When the person asks about updates (or the TV says "N updates available"):

```sh
call check-updates
```

It reads every imported Repository again and replies `updates_available` and `repositories`, where an Extension with
`update_available` `true` has a newer `version` than its `installed_version`. **Ask** which to update, listing them by
`display_name` with both versions, then for each:

```sh
call update-extension '{"name":"<name>"}'
```

or, when they want every update, `call update-extensions`: its `extensions` holds each one's reply (`ok` with `ids`, or
`failure`). An update keeps the Extension's Sources where they are in the order. `call extensions` shows the same as
`check-updates` without reading the Repositories again.

### 7. Remove Extensions or a Repository (Ask, then Do)

Read `call extensions` and confirm by name:

> Remove <display name>? Its Sources (<their names>) go from Celastrina straight away.

Only on a yes:

```sh
call remove-extension '{"name":"<name>"}'
```

To remove a whole Repository, confirm the same way, naming the Extensions installed from it, which go too:

```sh
call remove-repository '{"url":"<repository url>"}'
```

**Verify** with `call list` and `call extensions`. Removing something already gone replies `ok`. The Extension's code
is deleted from the TV the next time Celastrina starts.

### 8. End the job (Do)

Tell the person what was imported, installed, updated or removed, by name, then the [reminder](#ending-every-job).

### Extension replies

Besides those in [Replies](#replies):

| `problem` | What to do |
|---|---|
| `warning_not_acknowledged` | Expected in step 2. Anywhere else: the warning changed since you showed it. Show the new one and ask again. |
| `bad_url` | A Repository's address starts `http://` or `https://`. Ask for it again. |
| `unknown_repository` | That Repository isn't imported. Import it first (with the warning). |
| `unknown_extension` | The Repository lists no Extension by that `name`, or none by that name is installed. Use `name`, not `display_name`, from the latest reply. |
| `pinned_to_other_repository` | An Extension of that name is already installed from another Repository. Tell the person; it has to be removed before installing this one. |
| `missing_hash` | The Repository gives no checksum for that Extension, so Celastrina won't install it. Tell the person; nothing to fix on this side. |
| `hash_mismatch` | The download doesn't match the Repository's checksum, so it wasn't installed. Try once more later; if it persists, tell the person the Repository is broken or tampered with. |

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

## Calling Celastrina

Sources, Extensions and the TMDB key are configured by calling Celastrina on the TV over ADB. The jobs write each call
as `call <command> '<JSON arguments>'`, for example `call enable '{"id":"home-jellyfin"}'`. The arguments go
base64-encoded, so quoting works the same on every computer. Run it as:

```sh
$ADB -s $TV shell content call --uri content://dev.celastrina.configuration --method enable \
  --arg "$(printf '%s' '{"id":"home-jellyfin"}' | base64 | tr -d '\n')"
```

```powershell
$arg = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"id":"home-jellyfin"}'))
& $ADB -s $TV shell content call --uri content://dev.celastrina.configuration --method enable --arg $arg
```

A call with no arguments (`call list`) drops `--arg`. If your shell keeps functions between commands, define `call`
once:

```sh
call() {
  if [ $# -gt 1 ]; then
    $ADB -s $TV shell content call --uri content://dev.celastrina.configuration --method "$1" \
      --arg "$(printf '%s' "$2" | base64 | tr -d '\n')"
  else
    $ADB -s $TV shell content call --uri content://dev.celastrina.configuration --method "$1"
  fi
}
```

- **Values.** Put the person's values into the JSON as JSON strings: escape `"` as `\"` and `\` as `\\`. In the
  single-quoted shell form, a `'` in a value is written `'\''` (in PowerShell, `''`).
- **Secrets.** Passwords, add-on URLs and TMDB keys go only into the call. Don't echo them, save them to a file that
  stays, or repeat them to the person.
- **Replies.** The call prints `Result: Bundle[{reply={…}}]`; the reply is one JSON object whose `result` is `ok`,
  `invalid` or `failure` (see [Replies](#replies)). Calls that test a connection take a few seconds.

## Reading status

```sh
call status
$ADB -s $TV shell content call --uri content://dev.celastrina.configuration --method status   # the same, in full
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
| A `call` prints `SecurityException` | The call didn't come from `adb shell`. Run it through `$ADB -s $TV shell`, never from an app on the TV. |
| A `call` prints `Could not find provider` | Celastrina isn't installed (or this is a Celastrina Dev-only TV). Check Connect step 6. |
| A `call` replies `bad_argument` saying `--arg isn't base64` | The base64 was wrapped across lines or mangled by quoting. Encode with `base64 \| tr -d '\n'` (or `[Convert]::ToBase64String` in PowerShell). |
| Quick Connect: no new Source after 5 minutes | Ask what the TV shows. A code that expired needs **Add Source** again for a new one. If the person gave up, offer to log in with the password instead. |
