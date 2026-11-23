# TV app stack for Celastrina on Fire OS

- **Date:** 2026-11-23
- **Question:** Which stacks can realistically build a Fire OS TV app in late 2026? The candidates are native Kotlin with Jetpack Compose for TV + Media3/ExoPlayer, Leanback, React Native for TV, Flutter, and others. Each is compared on D-pad focus, rich artwork browsing on low-end sticks, playback (codecs, HLS/DASH, error detail good enough to drive **Failover**), APK size, headless build-from-source tooling, and Android/Google TV reach. The comparison includes an **older Fire OS device** (not Vega OS).
- **Method:** Only primary sources were used: Amazon developer docs, developer.android.com, AndroidX/Media3 release notes and source, the framework repositories, and the Google Maven artifacts themselves. Where a page's "last modified" date or a release date was visible, it is noted. Anything that couldn't be confirmed from a primary source is listed under [Open questions](#open-questions--unverified-items).

## Short answer

- **The "older Fire OS device" decides almost everything.** Amazon's device table [2] shows three generations still in scope:
  - **Fire OS 5 (API 22):** Fire TV Stick 2nd gen (2016–2019) and Basic Edition, 1 GB RAM, H.264/HEVC 1080p30 only, no VP9.
  - **Fire OS 6 (API 25):** Fire TV Stick 4K 1st gen (2018), 1.5 GB RAM.
  - **Fire OS 7 (API 28):** Fire TV Stick Lite and Stick 3rd gen (2020), and Stick HD (2024). All have 1 GB RAM.
- **Current AndroidX has dropped API 21/22, so Fire OS 5 is excluded from every current stack.** The cut-offs:
  - Compose ≥ 1.10, tv-material 1.1.0, tv-foundation 1.0.0 and Media3 ≥ 1.9 all need **API 23** [11][12][16].
  - **Compose 1.13.0 (2026-11-18) ships with `minSdkVersion 24`** in its AARs [12].
  - React Native (≥ 0.76) and Flutter (3.50) both need **API 24** [22][23][26].
  - Fire OS 5 is only reachable by pinning to Compose 1.9.x / tv-material 1.0.1 / Media3 1.8.x / Leanback 1.2.0 (all `minSdk 21`) [12]. No current React Native or Flutter release reaches it.
- **Fire OS 6 and 7 are reachable by every candidate**, because API 25 and API 28 are both ≥ 24.
- **Native Kotlin + Compose for TV + Media3 is Google's recommended path** [13][15]. Leanback is now marked **deprecated** in favour of Compose for TV [10]. The tv-foundation TV lazy lists are deprecated in favour of the standard Compose `LazyRow`/`LazyColumn`, which handle D-pad focus scrolling themselves [14].
- **For Failover, Media3 used directly gives by far the most detail.** It reports:
  - typed `PlaybackException` error codes covering network, HTTP status, parsing, decoder/format, DRM and behind-live-window [17];
  - the raw HTTP status via `InvalidResponseCodeException.responseCode` [18];
  - a stall detector added in 1.9.0 (`StuckPlayerException` with a stuck type, surfaced as `ERROR_CODE_TIMEOUT`) [16][21].

  The framework wrappers lose detail:
  - React Native's `react-native-video` v6 passes the numeric code through as a string [25].
  - Flutter's official `video_player` turns every error into `"VideoError"` plus a `toString()` [28].
- **Amazon officially lists three Fire TV routes:** Android, HTML5 web apps and React Native [4]. Flutter is not mentioned. Flutter's own Android TV umbrella issue (opened 2026-01-05) lists open gaps in D-pad, focus and text input [27].
- **Vega OS is a separate world and out of scope.** It is Linux-based and React-Native-first, and "your Fire OS APK does not run there" [7]. The 2025–2026 entry sticks run Vega OS: Stick 4K Select, Stick HD (2026) and Stick 4K 3rd gen (2026) [2]. So a newly bought low-end stick may not be able to run Celastrina at all.
- **Evidence leans towards** native Kotlin + Compose for TV + Media3, with the choice of minimum Fire OS (5 vs 6/7) made first. This is a reading of the evidence, not a decision.

## Comparison table

| | Native Kotlin + Compose for TV + Media3 | Native Kotlin + Leanback + Media3 | React Native for TV (react-native-tvos) + react-native-video | Flutter + video_player | Web app / WebView (HTML5) |
|---|---|---|---|---|---|
| **Vendor status** | Google's recommended TV UI path [13][15]. tv-material 1.1.0 and tv-foundation 1.0.0 stable (2026-05-06) [9] | **Deprecated** ("Use Compose for TV instead") [10]. Still receiving alphas: 1.3.0-alpha02, 2026-06-17 [10] | Community fork, tracks core RN (0.88.1-0, 2026-11-03) [22]. Amazon documents it for Fire TV [5] | No official TV support. Umbrella issue #180542 is open [27]. Not in Amazon's Fire TV route list [4] | Amazon lists HTML5 web apps as a Fire TV route [4] |
| **D-pad focus** | Built into Compose focus system. Standard lazy layouts bring the focused item into view, with `BringIntoViewSpec` for pivots [14]. TV Material components have focus indications [13] | Mature RecyclerView-based `BrowseSupportFragment` and grid views (leanback-grid) [10] | Native focus events, `TVFocusGuideView`, `nextFocus*`, and focus-aware `VirtualizedList`/`FlatList` [22] | Generic Flutter focus. Open P2/P3 D-pad issues (TextField D-pad #147772, Slider #54984, focus loss #49783) [27] | Hand-rolled spatial navigation (not researched further) |
| **Artwork browsing on 1 GB sticks** | No primary benchmark found on Fire TV. Amazon's perf blog uses Compose + Coil and stresses down-sampled images [8] | No primary benchmark found. Leanback disables outline clipping on low-RAM devices [10] | No primary benchmark found | No primary benchmark found. Impeller is off below API 29, so Fire OS 7 uses the legacy OpenGL renderer [29] | No primary benchmark found |
| **Playback engine** | Media3 ExoPlayer: HLS, DASH, SmoothStreaming, RTSP, progressive [20] | Same (Media3) | Media3 underneath on Android (v6.19.3 pins Media3 1.8.0) [25] | Media3 underneath on Android (`video_player_android`) [28] | Browser `<video>` + MSE (e.g. hls.js / shaka; not researched) |
| **Failover error detail** | Full: typed codes + HTTP status + stall types [16][17][18][21] | Full (same player) | Partial: `errorCode` = `"2"+PlaybackException.errorCode` as a string, plus name and stack trace [25] | Poor: single `"VideoError"` code, message = `toString()`. Behind-live-window is auto-handled [28] | Coarse: `MediaError` has 4 codes [34] |
| **minSdk (current release)** | 24 (Compose 1.13.0) / 23 (tv-material 1.1.0, Media3 1.11.1) [12] | 21 (1.2.0 stable) / 23 (1.3.0-alpha) [12] | 24 [22][23] | 24 [26] | Depends on the WebView version on the device (not researched) |
| **Reaches Fire OS 5 (API 22)?** | Only by pinning Compose 1.9.x / tv-material 1.0.1 / Media3 1.8.x [12] | Yes with Leanback 1.2.0 + Media3 1.8.x [12] | No | No | Unverified |
| **APK size** | Unverified (see Open questions) | Unverified | Unverified | Unverified (Flutter's docs give no Android figure) [30] | Small shell, unverified |
| **Headless build** | JDK 17 + Gradle + SDK (sdkmanager or new Android CLI) [31][32][33] | Same | Same + Node ≥ 22.11 [24] (+ NDK for new arch) [22] | Same Android SDK + Flutter SDK (3.50) [26] | Same Android SDK for the APK wrapper |
| **Android/Google TV reach** | Same APK with `LEANBACK_LAUNCHER`, leanback feature `required=false`, touchscreen not required [15] | Same | Same (RN TV targets Android TV) [22] | Same manifest rules [15] | Same manifest rules [15] |

## Per-stack notes

### 1. Native Kotlin + Jetpack Compose for TV + Media3/ExoPlayer

- **Status:** Google calls Compose "the recommended way to build apps for Android TV OS" [15]. Its Compose-for-TV page tells AI assistants to use "Jetpack Compose for TV … instead of Leanback" [13].
  - `androidx.tv:tv-material` 1.1.0 and `tv-foundation` 1.0.0 went stable on 2026-05-06 [9].
  - The `TvLazyRow`/`TvLazyColumn`/`TvLazy*Grid` layouts were deprecated (alpha11, 2024-07-10) and then removed (alpha12) [9].
  - The replacement is standard Compose Foundation `LazyRow`/`LazyColumn` (≥ 1.7.0), which "automatically handle D-pad navigation and bring the focused item into view". Custom pivots use `BringIntoViewSpec` via `LocalBringIntoViewSpec` [14].
- **Focus:** tv-material 1.1.0-alpha01 reworked focus APIs (`FocusProperties.onEnter`/`onExit`) and fixed Carousel focus management [9]. tv-material 1.0.1 (2025-07-16) fixed a bug where ExoPlayer did not render inside `Surface` composables on lower API levels [9]. That bug matters on Fire OS 6/7.
- **Low-end performance:**
  - Amazon's Fire TV performance blog (2024-12-05) uses Compose, Coil and ExoPlayer in its examples. It warns that "loading full-size images for thumbnail displays wastes memory", and recommends sized requests and `ReportDrawn*` for TTID/TTFD [8].
  - No primary-source benchmark of Compose on 1 GB Fire TV sticks was found.
  - tv-foundation/tv-material ship baseline profiles (alpha10) [9].
- **Playback:** see [Failover-relevant error reporting](#failover-relevant-error-reporting). Media3 1.11.1 (2026-09-10) is the latest stable [16].
  - `media3-ui-compose` and `media3-ui-compose-material3` now provide Compose player UI pieces, including `ErrorText`, `ProgressSlider` and `PlaybackSpeedControl` [16].
- **minSdk:** see [minSdk vs Fire OS](#minsdk-vs-fire-os-versions). Current Compose needs API 24. Fire OS 5 requires pinning to 2025-era versions.
- **Surprising:** Google's Compose-for-TV page still says it "works on Android TVs with Android 5.0 (API level 21) or higher" and shows `tv-material:1.0.0` with BOM `2026.09.00` [13]. That contradicts the `minSdk 23/24` in the artifacts the same BOM resolves to [12]. The page text is stale. Trust the AAR manifests.

### 2. Native Kotlin + Leanback

- The AndroidX Leanback page now says: "**This artifact and its classes are deprecated.** Use Compose for TV instead." [10]
- It is still being touched. Leanback 1.3.0-alpha01/alpha02 (2026-05-19 / 2026-06-17) and leanback-grid 1.1.0-alpha01/02 added `SpanSizeLookup` and fixed a view leak. They also raised `minSdk` from 21 to 23 [10].
- Leanback 1.2.0 (stable, 2025-04-23) is still `minSdk 21` [10][12]. That makes it the only maintained-ish TV UI toolkit that reaches Fire OS 5 without pinning an old Compose.
- Amazon's own 2025 Amplify tutorial chose Leanback over the Compose template, citing Fire TV's support for Leanback UI components. This is a blog post, not a policy statement (seen in search results, not fetched in full).
- Low-RAM note: Leanback 1.1.0-alpha01 "disabled outline clipping by default on low-ram device" [10].

### 3. React Native for TV (react-native-tvos)

- **Status:**
  - Amazon's Fire TV React Native guide (last modified 2025-07-22) points to the `react-native-tvos` fork and recommends Expo with the `with-tv` template and `@react-native-tvos/config-tv` [5].
  - The fork's latest stable is `0.88.1-0` (2026-11-03). `0.89.0-0rc2` came out 2026-11-22 [22].
  - Hermes and the New Architecture are enabled by default [22].
- **Focus:**
  - `onFocus`/`onBlur` are native core events.
  - `TVFocusGuideView` provides `destinations`, `autoFocus`, `trapFocus*`, `onFocusEnter`/`onFocusLeave`.
  - `nextFocus*` props are supported.
  - `VirtualizedList`/`FlatList` are wrapped in focus guides that trap focus along the list's axis [22].
- **Build:** RN 0.88 needs Node ≥ 22.11.0 and JDK 17 (higher JDKs "may encounter problems"), plus Android SDK Platform 35 and Build-Tools 36.0.0 [24]. The fork's Gradle catalog pins `minSdk 24`, `targetSdk 36`, `compileSdk 37`, AGP 9.2.1 and NDK 27.1 [22].
- **Video:** `react-native-video`:
  - v6.19.3 (2026-09-18) is the latest stable. v7 is in beta (v7.0.0-beta.14, 2026-11-20) [25].
  - v6.19.3 pins **Media3 1.8.0** and `minSdk 24` [25]. That means it doesn't get Media3 1.9's `StuckPlayerException` unless overridden, and Media3 1.8 would have allowed API 21.
- **Vega relationship:** Amazon positions React Native as the bridge to Vega (React Native for Vega) [6][7]. But Vega uses Amazon's own RN packages (`@amazon-devices/*`) and does not run APKs [7]. A Fire OS RN app is not automatically a Vega app.

### 4. Flutter

- **Status:**
  - Flutter 3.50 supports Android API 24–37. API ≤ 23 is "unsupported" (page updated 2026-11-11) [26].
  - Neither the supported-platforms page nor Amazon's Fire TV getting-started page mentions TV or Fire OS [4][26].
  - Umbrella issue **flutter/flutter#180542 "Support Android TV ☂️"** (opened 2026-01-05, P3) says Flutter apps "can be run as generic Android TV apps, but there's gaps in tooling, I/O, navigation, focus, core design language, documentation, samples, and tests" [27].
- **Focus:** Open issues tracked under that umbrella [27]:
  - D-pad on `TextField` is broken (#147772, P2, open since 2024-05)
  - Slider D-pad (#54984)
  - focus lost on Android TV (#49783)
- **Playback:**
  - The official `video_player_android` 2.13.0 uses Media3 [28].
  - Its `onPlayerError` auto-recovers `ERROR_CODE_BEHIND_LIVE_WINDOW`. It reports everything else as `events.onError("VideoError", "Video player had error $error", null)` [28], so error codes would need parsing out of a string.
  - Fire TV-specific bug: #155358, a video scaling regression on the Fire TV Stick 4K Max (2nd gen), is still open [27].
- **Rendering:** Impeller is enabled by default only on Android API 29+. Below that (all Fire OS 7 and older) Flutter "falls back to the legacy OpenGL renderer" [29].
  - Flutter issue #189767 (2026-07-21) reports ghosting on a TCL Android TV with a PowerVR GPU under Impeller [27].
  - The Fire TV Stick Lite/3rd gen use an IMG GE8300 GPU, also PowerVR [2]. Different model; relevance unverified.
- **APK size:** Flutter's app-size page gives no Android figure, only an iOS example (5.4 MB download for the 1.17 demo app) [30].

### 5. Web app / WebView, and others

- **Web/HTML5:** Amazon lists HTML5 web apps as a supported Fire TV route [4]. Its Web App FAQ was last modified 2023-08-30 [4], so this route looks lightly maintained. Not researched further.
  - For Failover, the standard `HTMLMediaElement.error` has only four `MediaError` codes (ABORTED, NETWORK, DECODE, SRC_NOT_SUPPORTED) plus a free-text message [34]. Richer detail would come from a JS player library (hls.js, shaka; not verified here).
- **Kotlin Multiplatform / Compose Multiplatform:** On Android, the UI layer is the same Jetpack Compose artifacts. No TV-specific advantage was found, and they weren't evaluated further. They only matter if non-Android targets become a goal.
- **Vega OS (out of scope):** see the next section.

## Vega OS direction (out of scope, but relevant)

- Amazon announced Vega OS on 2025-09-30. It is built on Linux components, and apps are built with React Native for Vega or web technologies (Vega WebView). Amazon's FAQ answers "No" to whether Fire OS apps are going away: "We continue to launch new features and devices on Fire OS" [6].
- On 2026-10-30 Amazon wrote: "Newer Fire TV sticks run Vega OS, a Linux-based platform where React Native is the primary app framework. **Your Fire OS APK does not run there.**" It offers ADBT, an AI-agent porting workflow from Fire OS code to React Native for Vega [7].
- Amazon's device table lists these as Vega OS ("OS 2.0", 1 GB RAM) [2]:
  - **Fire TV Stick 4K Select (2025)**
  - **Fire TV Stick HD (2026)**
  - **Fire TV Stick 4K 3rd Gen (2026)**

  The **Fire TV Stick 4K Plus (2025)** is still Fire OS 8 (API 30) [2].
- **Implication for Celastrina:** the Install Runbook should detect Vega devices and refuse cleanly, because ADB-sideloading an APK is impossible there [7]. The newest entry-level Fire TV sticks a Household might buy are Vega devices.

## Failover-relevant error reporting

This is mostly about Media3, since every Android option above (Compose, Leanback, RN, Flutter) ends up on Media3. What differs is how much of Media3's detail each stack passes through.

### What Media3 reports

`Player.Listener.onPlayerError(PlaybackException)` reports fatal errors with an `errorCode` and `getErrorCodeName()` [17]. These are the error codes most useful for Failover [17]:

| Failover signal | Media3 code(s) | Likely Failover action |
|---|---|---|
| Network down / host unreachable / connection reset | `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` (2001) | Check device connectivity first (Media3's doc suggests `getActiveNetwork`). If online, try the next Stream |
| Server slow | `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` (2002) | Next Stream |
| HTTP 4xx/5xx | `ERROR_CODE_IO_BAD_HTTP_STATUS` (2004). Cause chain contains `HttpDataSource.InvalidResponseCodeException` with `responseCode`, `responseMessage`, `headerFields`, `responseBody` [18] | Next Stream. Could distinguish 403/404/410 (dead) from 5xx/429 (transient) |
| Paywall/HTML instead of media | `ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE` (2003) (docs give the "paywall HTML page" example) | Next Stream, mark Source suspect |
| `http://` blocked | `ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED` (2007) | Config bug, not a Stream fault |
| Bad container / manifest | `ERROR_CODE_PARSING_CONTAINER_MALFORMED` (3001), `…MANIFEST_MALFORMED` (3002), `…CONTAINER_UNSUPPORTED` (3003), `…MANIFEST_UNSUPPORTED` (3004) | Next Stream |
| Device can't decode | `ERROR_CODE_DECODER_INIT_FAILED` (4001), `…DECODING_FAILED` (4003), `…DECODING_FORMAT_EXCEEDS_CAPABILITIES` (4004), `…DECODING_FORMAT_UNSUPPORTED` (4005), `…DECODING_RESOURCES_RECLAIMED` (4006) | Next Stream with a different codec or resolution. Remember per device |
| DRM | `ERROR_CODE_DRM_*` (6000–6008) | Next Stream (or treat as unsupported) |
| Live edge lost | `ERROR_CODE_BEHIND_LIVE_WINDOW` (1002) | Recover in place: seek to default position and re-prepare (as Flutter's plugin does [28]), not Failover |
| Stall / no progress | `ERROR_CODE_TIMEOUT` (1003) with cause `StuckPlayerException` [16][21] | Next Stream |

### Retries, track fallback and stall detection

- **Non-fatal retries and track fallback happen before a fatal error is raised.**
  - `DefaultLoadErrorHandlingPolicy` retries at least 3 times (6 for progressive live), with delay `min((errorCount-1)*1000, 5000)` ms. It doesn't retry parser, file-not-found or cleartext errors.
  - It can exclude a failing track for 60 s or a location for 300 s [19].
  - So a dead Stream can take several seconds to surface as fatal. A custom `LoadErrorHandlingPolicy` (pluggable per `MediaSource.Factory` [16]) is the lever for faster Failover. The tuning is a design decision, not researched here.
- **Stall detection is new in Media3 1.9.0 (2025-12-17)** [16]. ExoPlayer raises a `StuckPlayerException` when:
  - it is `STATE_BUFFERING` for **10 min** with no buffering progress;
  - it is `STATE_READY` for **10 s** with no playback progress;
  - it is 1 min past the declared duration;
  - playback has been suppressed for 10 min.

  The timeouts are configurable on `ExoPlayer.Builder` [16].
- **How a stall reaches the app:**
  - `ExoPlayerImpl` wraps detector-raised stalls as `ExoPlaybackException.createForUnexpected(exception, ERROR_CODE_TIMEOUT)` [21].
  - The cause carries `stuckType`: `STUCK_BUFFERING_NOT_LOADING`, `STUCK_BUFFERING_NO_PROGRESS`, `STUCK_PLAYING_NO_PROGRESS`, `STUCK_PLAYING_NOT_ENDING` or `STUCK_SUPPRESSED`, plus `timeoutMs` [21].
  - The internal "buffering but not loading" check (4 s) throws `StuckPlayerException` inside `ExoPlayerImplInternal`. The generic `RuntimeException` handler there maps `IllegalStateException` to `ERROR_CODE_FAILED_RUNTIME_CHECK` [21]. So the code reported for that path may be 1004 rather than 1003. Read the cause, not just the code. This comes from reading the source at the `release` branch and was not runtime-tested.
  - A 10-minute default for buffering stalls is far too long for user-facing Failover. Celastrina would likely set its own, shorter timeouts. That is a design decision.

### What each wrapper passes through

- **React Native (`react-native-video` v6.19.3):**
  - Emits `errorString` = `"ExoPlaybackException: " + getErrorCodeName(code)`, `errorCode` = `"2" + code` (string, e.g. `"22004"`), `errorException` = `toString()`, and `errorStackTrace` [25].
  - Retries some DRM errors once by itself [25].
  - The HTTP status is only in the exception text.
  - v7 (beta) maps playback failures to `player/playback-failed` with the code name embedded in the message [25].
- **Flutter (`video_player_android`):** one `"VideoError"` code for everything except behind-live-window [28]. Distinguishing HTTP 404 from decoder-unsupported would need string parsing, a forked plugin, or a custom platform channel.
- **Web:** four `MediaError` codes [34].

### Codecs on the in-scope devices

Media3 uses the platform decoders, so supported sample formats "depend on the underlying platform" [20]. Per Amazon's table [2]:

| Device (Fire OS / API) | RAM | H.264 | HEVC | VP9 | AV1 |
|---|---|---|---|---|---|
| Stick 2nd gen 2016–19 / Basic Ed. (FOS5 / 22) | 1 GB | 1080p30 (720p60) | 1080p30 Main, 8-bit | — | — |
| Stick 4K 1st gen 2018 (FOS6 / 25) | 1.5 GB | 4K60 | 4K60 Main10 | 4K60 Profile 2 | — |
| Stick Lite / Stick 3rd gen 2020, Stick HD 2024 (FOS7 / 28) | 1 GB | 1080p60 | 1080p60 Main10 (HDR10/10+/HLG) | 1080p60 Profile 2 | — |
| Stick 4K Max 1st gen 2021 (FOS7 / 28) | 2 GB | 4K60 | 4K60 | 4K60 | 4K60 |
| Stick 4K / 4K Max 2nd gen 2023, 4K Plus 2025 (FOS8 / 30) | 2 GB | 4K60 | 4K60 | 4K60 | 4K60 |

- All listed Fire TV devices report a **32-bit** application ABI [2]. Native libraries (RN Hermes, Flutter engine, Media3 FFmpeg extension) must include `armeabi-v7a`.
- Widevine L1/L3 and PlayReady are present on all of them [2].
- Media3's own adaptive support covers [20]:
  - DASH (FMP4/WebM/Matroska, demuxed only; no MPEG-TS)
  - HLS (MPEG-TS, FMP4/CMAF, AES-128; no SAMPLE-AES)
  - SmoothStreaming and RTSP
- Software extension decoders (AV1, VP9, FFmpeg audio, etc.) can fill gaps but must be built manually [20].

## minSdk vs Fire OS versions

From Amazon's Fire OS overview (last modified 2026-10-16) [1] and Fire OS 16 page (2026-06-30) [3]. The table confirms and extends the API levels in the question:

| Fire OS | Android base | API level |
|---|---|---|
| Fire OS 5 | Android 5.1 | 22 |
| Fire OS 6 | Android 7.1 | 25 |
| Fire OS 7 | Android 9 | 28 |
| Fire OS 8 | Android 10 / 11 | 29 / 30 (devices in the table report 30 [2]) |
| Fire OS 14 | Android 12–14 | 31–34 |
| Fire OS 16 | Android 15 / 16 | 35 / 36 |

The question's assumed mapping (5 = 22, 6 = 25, 7 = 28, 8 = 30) **is confirmed** [1][2]. Fire OS 14 and Fire OS 16 also exist now [1][3].

minSdk of current releases, read from the published AAR manifests on Google Maven [12] and the release notes:

| Library / framework | Version (date) | minSdk | Reaches FOS5 (22)? | FOS6 (25)? | FOS7 (28)? |
|---|---|---|---|---|---|
| Compose foundation/ui/runtime | 1.13.0 (2026-11-18) [11] | **24** [12] | No | Yes | Yes |
| Compose foundation/ui/runtime | 1.10.0–1.12.0 | 23 [11][12] | No | Yes | Yes |
| Compose foundation | 1.9.5 (last 1.9.x) | 21 [12] | Yes | Yes | Yes |
| androidx.tv:tv-material | 1.1.0 (2026-05-06) | 23 (depends on Compose 1.10.3) [12] | No | Yes | Yes |
| androidx.tv:tv-material | 1.0.1 (2025-07-16) | 21 [12] | Yes | Yes | Yes |
| androidx.tv:tv-foundation | 1.0.0 (2026-05-06) | 23 [12] | No | Yes | Yes |
| Media3 | 1.11.1 (2026-09-10) / 1.9.0+ | 23 ("Update `minSdk` to `23`", 1.9.0, 2025-12-17) [16][12] | No | Yes | Yes |
| Media3 | 1.8.0 (2025-07-30) | 21 [12][16] | Yes | Yes | Yes |
| Leanback | 1.2.0 (2025-04-23) | 21 [12] | Yes | Yes | Yes |
| Leanback | 1.3.0-alpha02 (2026-06-17) | 23 [10][12] | No | Yes | Yes |
| React Native / react-native-tvos | ≥ 0.76 / 0.88.1-0 | 24 [22][23] | No | Yes | Yes |
| Flutter | 3.50 | 24 [26] | No | Yes | Yes |

Notes:
- The Compose 1.13 bump to API 24 is visible in the AAR manifests [12]. The Compose foundation release notes searched only mention the 21 → 23 move in 1.10.0-alpha01 [11]. The 24 bump looks undocumented, or documented somewhere not checked.
- Amazon's Fire OS 16 page asks for `targetSdkVersion 36` across Fire OS 5–14 apps, and warns that apps targeting < 24 may be blocked from installing on Fire OS 16 [3]. That is about **target**SDK, not minSdk, and it applies to Appstore-distributed apps. Its effect on ADB sideloads is unverified.
- If Fire OS 5 support is required, the app is frozen on 2025-era AndroidX: Compose 1.9.x, tv-material 1.0.1, Media3 1.8.x. It would miss Media3's stall detector and any later security or bug fixes. A dual-flavor build (legacy flavor for API 22) is possible but doubles the test matrix (not researched).

## Build-from-source tooling (headless, cross-OS)

- **Android baseline (all native-APK stacks):** JDK 17, Gradle, and the Android SDK.
  - AGP 9.5 needs Gradle ≥ 9.6.0, JDK 17 and Build-Tools ≥ 36.0.0, and supports up to API 37 [31].
  - The classic route is cmdline-tools + `sdkmanager "platform-tools" "platforms;android-36"` + `sdkmanager --licenses` (interactive; usually piped `yes |` in CI) [32].
- **Surprising:**
  - `sdkmanager` is now marked **deprecated** in favour of the new **Android CLI** (`android sdk install|list|update|remove`) [32].
  - Android CLI is pitched explicitly at coding agents, with official plugins for Claude Code, Codex and Antigravity [33]. It is installable via `apt-get`, `winget` and `homebrew` (May 2026) [33].
  - Its license files are compatible with older tools and AGP (1.0.15498356, May 2026) [33].
  - It offers `android create/build/run/install`, a headless emulator, and non-zero exit codes on failure (Sept–Nov 2026 releases) [33].
  - Whether `android sdk install` accepts licenses non-interactively wasn't confirmed from the reference page fetched [33]. See Open questions.
- **React Native:** add Node ≥ 22.11.0, and JDK 17 (not newer) [24]. The NDK is pinned by the fork [22]. Expo's `with-tv` template is Amazon's suggested start [5].
- **Flutter:** add the Flutter SDK (3.50). It needs the same Android SDK and licenses (`flutter doctor --android-licenses`; not re-verified on a Flutter page here).
- All of these run on macOS, Linux and Windows per their own docs [24][26][33]. Native Kotlin has the fewest moving parts: no Node, Dart or NDK unless native code is added.

## Android/Google TV reach

- The same APK can be made visible on Android TV / Google TV. It needs:
  - a `LEANBACK_LAUNCHER` intent filter;
  - `android.software.leanback` with `required="false"`;
  - `android.hardware.touchscreen` with `required="false"`;
  - a 320×180 banner [15].

  Fire OS also uses the leanback launcher category (Amazon's RN guide says to check the manifest for leanback features [5]). So for any of the Android stacks, Android TV reach costs roughly nothing at the build level.
- Codec and DRM capabilities on non-Amazon TVs vary by device. Media3 picks decoders by capability [20]. Not researched further.

## Open questions / unverified items

1. **APK sizes.** No primary source gives release APK sizes for a minimal Compose-for-TV + Media3 app, a Leanback app, a react-native-tvos app or a Flutter app on Android. Flutter's docs give only an iOS figure [30]. These should be measured in a spike rather than guessed:
   - `flutter build apk --analyze-size --target-platform android-arm` [30]
   - `android analyze <apk>` from Android CLI [33]

   For reference only, unminified AAR sizes from Google Maven: compose foundation 1.13.0 ≈ 4.9 MB, ui 1.13.0 ≈ 4.2 MB, media3-exoplayer 1.11.1 ≈ 1.7 MB, leanback 1.2.0 ≈ 1.3 MB [12]. R8 shrinks these considerably. They are **not** APK contributions.
2. **Artwork-browsing performance on 1 GB sticks** (Lite / 3rd gen / Stick HD 2024 on Fire OS 7; 2nd gen on Fire OS 5). No primary benchmark exists for any stack. It needs an on-device spike: a grid of rows of posters with Coil/Glide down-sampling, measured with the FireOS performance tool Amazon references [8].
3. **Which exact older device the Household owns.** It decides whether Fire OS 5 (API 22) must be supported, and that decides library pinning [2][12].
4. **Compose 1.13 minSdk 24.** Seen in the AARs [12] but not found in the release notes searched [11]. The next Compose release may raise it further. Watch for a bump above 25, which would drop Fire OS 6.
5. **`StuckPlayerException` reporting path.** The detector path maps to `ERROR_CODE_TIMEOUT`, but the internal 4 s "buffering not loading" path may map to `ERROR_CODE_FAILED_RUNTIME_CHECK` [21]. This comes from source reading and needs a runtime test.
6. **Fire OS WebView/Chromium versions** per Fire OS release, and the MSE/EME behaviour of the web-app route. Not researched.
7. **Whether sideloaded APKs targeting older SDKs are blocked on Fire OS 16 devices** [3]. Unclear for ADB installs versus Appstore.
8. **Android CLI non-interactive license acceptance** (`android sdk install` flags) [33]. Not confirmed. `yes | sdkmanager --licenses` remains the documented fallback [32].
9. **Amazon's stance on Flutter for Fire OS.** Not found in Amazon's docs [4]. Absence is not a prohibition. Flutter APKs are ordinary APKs.
10. **react-native-video Media3 override.** Whether v6 can be safely bumped to Media3 ≥ 1.9 (to gain stall detection) wasn't checked [25].

## Sources

1. Amazon, "Fire OS Overview" (last modified 2026-10-16). https://developer.amazon.com/docs/fire-tv/fire-os-overview.html
2. Amazon, "Comparison Table for Fire TV Devices" (last modified 2026-04-23). The device data (RAM, SoC, ABI, Fire OS/API, codecs, DRM) was read from the page's embedded data. https://developer.amazon.com/docs/device-specs/device-specifications-comparison-table.html
3. Amazon, "Developing for Amazon Fire TV Devices Running Fire OS 16" (last modified 2026-06-30). https://developer.amazon.com/docs/fire-tv/fire-os-16.html
4. Amazon, "Get Started with Fire TV" (last modified 2026-05-20). https://developer.amazon.com/docs/fire-tv/get-started-with-fire-tv.html. Also the Web App FAQ (last modified 2023-08-30): https://developer.amazon.com/docs/fire-tv/web-app-faq.html
5. Amazon, "Get Started with React Native (Fire TV)" (last modified 2025-07-22). https://developer.amazon.com/docs/fire-tv/get-started-with-react-native.html
6. Amazon Appstore blog, "Get started with Vega Developer Tools" (2025-09-30). https://developer.amazon.com/apps-and-games/blogs/2025/09/announcing-vega-os
7. Amazon Appstore blog, "Porting Your TV App to React Native for Vega with ADBT" (2026-10-30). https://developer.amazon.com/apps-and-games/blogs/2026/10/port-your-tv-app-to-react-native-with-adbt
8. Amazon Appstore blog, "Optimize the performance of your Fire TV app" (2024-12-05). https://developer.amazon.com/apps-and-games/blogs/2024/12/optimize-fire-tv-app-performance
9. AndroidX, "Compose for TV" release notes (library last updated 2026-05-06). https://developer.android.com/jetpack/androidx/releases/tv
10. AndroidX, "Leanback" release notes (latest update 2026-06-17). https://developer.android.com/jetpack/androidx/releases/leanback
11. AndroidX, "Compose Foundation" release notes (1.13.0 on 2026-11-18; 1.10.0 on 2025-12-03; minSdk 21 → 23 in 1.10.0-alpha01). https://developer.android.com/jetpack/androidx/releases/compose-foundation
12. Google Maven, AAR `AndroidManifest.xml` `minSdkVersion` and POM dependencies, checked 2026-11-23. Under https://dl.google.com/android/maven2/:
    - `androidx/compose/foundation/foundation-android/{1.9.0,1.9.5,1.10.0,1.11.0,1.12.0,1.13.0}/`
    - `androidx/compose/ui/ui-android/{1.10.0…1.13.0}/`
    - `androidx/compose/runtime/runtime-android/{1.10.0…1.13.0}/`
    - `androidx/tv/tv-material/{1.0.1,1.1.0}/`
    - `androidx/tv/tv-foundation/1.0.0/`
    - `androidx/media3/media3-exoplayer/{1.8.0,1.9.0,1.11.1}/`
    - `androidx/leanback/leanback/{1.2.0,1.3.0-alpha02}/`
13. Android Developers, "Use Jetpack Compose on Android TV". https://developer.android.com/training/tv/playback/compose
14. Android Developers, "Create scrollable layouts" (Compose for TV lists). https://developer.android.com/training/tv/playback/compose/lists
15. Android Developers, "Get started with TV apps". https://developer.android.com/training/tv/get-started/create
16. androidx/media, `RELEASENOTES.md` (1.11.1 on 2026-09-10; 1.9.0 on 2025-12-17; 1.8.0 on 2025-07-30). https://github.com/androidx/media/blob/release/RELEASENOTES.md
17. Android Developers reference, `androidx.media3.common.PlaybackException`. https://developer.android.com/reference/androidx/media3/common/PlaybackException
18. Android Developers reference, `HttpDataSource.InvalidResponseCodeException`. https://developer.android.com/reference/androidx/media3/datasource/HttpDataSource.InvalidResponseCodeException
19. Android Developers reference, `DefaultLoadErrorHandlingPolicy`. https://developer.android.com/reference/androidx/media3/exoplayer/upstream/DefaultLoadErrorHandlingPolicy
20. Android Developers, "ExoPlayer supported formats". https://developer.android.com/media/media3/exoplayer/supported-formats
21. androidx/media source (`release` branch, read 2026-11-23):
    - https://github.com/androidx/media/blob/release/libraries/common/src/main/java/androidx/media3/common/util/StuckPlayerException.java
    - https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImpl.java (`onStuckPlayerDetected` → `ERROR_CODE_TIMEOUT`)
    - https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImplInternal.java
22. react-native-tvos:
    - README: https://github.com/react-native-tvos/react-native-tvos
    - Releases (0.88.1-0 on 2026-11-03; 0.89.0-0rc2 on 2026-11-22): https://github.com/react-native-tvos/react-native-tvos/releases
    - `packages/react-native/gradle/libs.versions.toml` at tag `v0.88.1-0`
23. React Native blog, "React Native 0.76 – New Architecture by default…" (2024-10-23). Android minSdk 23 → 24. https://reactnative.dev/blog/2024/10/23/release-0.76-new-architecture
24. React Native docs (0.88), "Set Up Your Environment" (Android). https://reactnative.dev/docs/set-up-your-environment?platform=android
25. TheWidlarzGroup/react-native-video:
    - Releases (v6.19.3 on 2026-09-18; v7.0.0-beta.14 on 2026-11-20): https://github.com/TheWidlarzGroup/react-native-video/releases
    - Files at tag `v6.19.3`: `android/src/main/java/com/brentvatne/exoplayer/ReactExoplayerView.java` (`onPlayerError`), `android/src/main/java/com/brentvatne/common/react/VideoEventEmitter.kt`, `android/gradle.properties` (Media3 1.8.0, minSdk 24)
    - v7 file on main: `packages/react-native-video/android/src/main/java/com/twg/video/core/VideoError.kt`
26. Flutter docs, "Supported deployment platforms" (Flutter 3.50, updated 2026-11-11). https://docs.flutter.dev/reference/supported-platforms
27. flutter/flutter issues:
    - #180542 "Support Android TV ☂️": https://github.com/flutter/flutter/issues/180542
    - #147772: https://github.com/flutter/flutter/issues/147772
    - #54984: https://github.com/flutter/flutter/issues/54984
    - #49783: https://github.com/flutter/flutter/issues/49783
    - #155358 (Fire TV Stick 4K Max regression): https://github.com/flutter/flutter/issues/155358
    - #189767 (PowerVR TV ghosting under Impeller): https://github.com/flutter/flutter/issues/189767
28. flutter/packages, `video_player_android` 2.13.0:
    - https://github.com/flutter/packages/blob/main/packages/video_player/video_player_android/android/src/main/kotlin/io/flutter/plugins/videoplayer/ExoPlayerEventListener.kt
    - `android/build.gradle.kts` (minSdk 24, Media3)
29. Flutter docs, "Impeller rendering engine". https://docs.flutter.dev/perf/impeller
30. Flutter docs, "Measuring your app's size". https://docs.flutter.dev/perf/app-size
31. Android Developers, "Android Gradle plugin release notes" (AGP 9.5 compatibility table). https://developer.android.com/build/releases/gradle-plugin
32. Android Developers, "sdkmanager" (marked deprecated in favour of Android CLI). https://developer.android.com/tools/sdkmanager
33. Android Developers, Android CLI:
    - Overview: https://developer.android.com/tools/agents
    - `android sdk` reference: https://developer.android.com/tools/agents/android-cli/commands/sdk
    - Release notes (0.7 in April 2026 through 1.1.16919734 in November 2026): https://developer.android.com/tools/agents/android-cli/release-notes
34. WHATWG HTML Living Standard, media element error codes (`MediaError`). https://html.spec.whatwg.org/multipage/media.html#error-codes
