# Which Fire TV models can sideload apps over ADB

Research for issue #2 (map #1). Researched 2026-11-23. Feeds the **Install Runbook** and Celastrina's minimum Android API level.

Confidence tags: **[primary]** = stated on an Amazon developer page; **[secondary]** = press/community only; **[inference]** = my reasoning from primary facts, to verify on real hardware.

## Short answer

- Every Fire TV running **Fire OS** (5, 6, 7, 8, 14) accepts an APK over ADB from a computer on the same LAN. Devices running **Vega OS** do not: they run `.vpkg` React Native packages, have no Android/APK runtime, and their developer path needs an Amazon Developer Account plus Amazon's `vega` CLI. Vega OS is out of scope for Celastrina v1.
- As of Amazon's spec data (May 2026), only three devices run Vega OS, all of them **sticks**: Fire TV Stick 4K Select (2025, `AFTCA002`), Fire TV Stick 4K 3rd Gen (2026, `AFTCR001`) and Fire TV Stick HD (2026, `AFTCL001`). Every listed smart TV, Cube and soundbar runs Fire OS, including 2026 TVs.
- API levels in the field: **22** (Fire OS 5), **25** (Fire OS 6), **28** (Fire OS 7), **30** (Fire OS 8) and **34** (Fire OS 14, on 2025–2026 smart TVs). Fire OS 7 (API 28) is the largest group.
- An "AFT" model prefix does **not** prove a device can run Celastrina, because the Vega sticks also report `AFT…` model strings. To detect an unsupported device, reach it with `adb` and read its Android SDK level. A Vega device won't answer as an Android device at all.
- Nearly every Fire TV in Amazon's table reports a **32-bit ABI**. Ship `armeabi-v7a` code, or no native code at all.

## 1. Fire OS vs Vega OS, per device

Source: the device dataset embedded in Amazon's *Device Specifications: Fire TV Streaming Media Player* page (last modified 2026-05-14), which holds every Fire TV category (sticks, Cubes, smart TVs, soundbars). Each record gives `buildModel`, `androidVersion` and `osVersion`. [primary] <https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html>

Fire OS → Android mapping, from *Fire OS overview* (last modified 2026-10-16): Fire OS 5 = Android 5.1 / API 22; Fire OS 6 = Android 7.1 / API 25; Fire OS 7 = Android 9 / API 28; Fire OS 8 = Android 10/11 / API 29–30; Fire OS 14 = Android 12–14 / API 31–34; Fire OS 16 = Android 15–16 / API 35–36. [primary] <https://developer.amazon.com/docs/fire-tv/fire-os-overview.html>. No Fire TV in the spec dataset lists Fire OS 16 yet.

### Vega OS: cannot sideload APKs (unsupported)

| Device | Year | Build.MODEL | OS |
|---|---|---|---|
| Fire TV Stick 4K Select | 2025 | `AFTCA002` | Vega OS 2.0, Android "N/A" |
| Fire TV Stick 4K – 3rd Gen | 2026 | `AFTCR001` | Vega OS 2.0, Android "N/A" |
| Fire TV Stick HD | 2026 | `AFTCL001` | Vega OS 2.0, Android "N/A" |

### Fire OS streaming players and Cubes (supported)

| Device | Year | Build.MODEL | Fire OS / API |
|---|---|---|---|
| Fire TV Stick 4K Plus | 2025 | `AFTMA08C15` | FOS 8 / 30 |
| Fire TV Stick HD (Alexa Voice Remote) | 2024 | `AFTSS` | FOS 7 / 28 |
| Fire TV Stick 4K Max – 2nd Gen | 2023 | `AFTKRT` | FOS 8 / 30 |
| Fire TV Stick 4K – 2nd Gen | 2023 | `AFTKM` | FOS 8 / 30 |
| Fire TV Cube – 3rd Gen | 2022 | `AFTGAZL` | FOS 7 / 28 |
| Fire TV Stick 4K Max – 1st Gen | 2021 | `AFTKA` | FOS 7 / 28 |
| Fire TV Stick – 3rd Gen | 2020 | `AFTSSS` | FOS 7 / 28 |
| Fire TV Stick Lite – 1st Gen | 2020 | `AFTSS` | FOS 7 / 28 |
| Fire TV Cube – 2nd Gen | 2019 | `AFTR` | FOS 7 / 28 |
| Fire TV Cube – 1st Gen | 2018 | `AFTA` | FOS 6 / 25 |
| Fire TV Stick 4K – 1st Gen | 2018 | `AFTMM` | FOS 6 / 25 |
| Fire TV – 3rd Gen | 2017 | `AFTN` | FOS 6 / 25 |
| Fire TV Stick – Basic Edition | 2017 | `AFTT` | FOS 5 / 22 |
| Fire TV Stick – 2nd Gen | 2016–19 | `AFTT` | FOS 5 / 22 |
| Fire TV – 2nd Gen | 2015 | `AFTS` | FOS 5 / 22 |
| Fire TV Stick – 1st Gen | 2014 | `AFTM` | FOS 5 / 22 |
| Fire TV – 1st Gen | 2014 | `AFTB` | FOS 5 / 22 |

### Fire OS smart TVs (supported), grouped by OS

- **Fire OS 14 / API 34 (2025–2026):** Hisense U7 (2026) `AFTHA78451`; TCL QM64L (2026) `AFTCHE92D8`; Onida 4K (2026) `AFTAR04B9A`; MediaTek MT9603 reference (2025) `AFTFO8B359`, `AFTFO63DD9`; Amlogic T950D5 reference (2025) `AFTANFD5E0`, `AFTAN6CD5E`, `AFTAN69D6D`.
- **Fire OS 8 / API 30 (2024–2026):** Amazon Ember Artline (2026) `AFTBOE497A`, `AFTBOEF7DA`; Amazon Fire TV Omni QLED (2025) `AFTPOC9BD3`, `AFTPO9D4D7`; Amazon Fire TV 2-Series (2025) `AFTCU864A2`, `AFTCUD6595`; Amazon Fire TV 4-Series (2025) `AFTNA1322D`, `AFTNA67200`; Xiaomi 4K (2026) `AFTBR92D74`; TCL QLED panels (2025) `AFTMA475B1`; JVC / Panasonic LCD (2024–25) `AFTWYM01`; Panasonic OLED (2024–25) `AFTTOR001`; Regal / 4K QLED (2024–25) `AFTSH3F7EF`; 4K UHD (2024) `AFTPOF46A5`; TCL S4/S5/Q5/Q6 (2024) `AFTDEC012E`. One oddity: TCL 4K Smart Fire TV (2024) `AFTMA475B1` is listed as FOS 8 but "API level 28".
- **Fire OS 7 / API 28 (2019–2024):** includes the Fire TV 4-Series (2021) `AFTTI43`, Omni (2021/2022) `AFTTIFF43`, 2-Series (2023) `AFTKA002`/`AFTKAUK002`, Toshiba/Insignia/Hisense/TCL/JVC/Grundig/Xiaomi/Redmi/Funai/AmazonBasics 4K/Panasonic MX800 models (full list in the source dataset).
- **Fire OS 6 / API 25 (2018–2020):** Toshiba HD `AFTBAMR311`, Insignia HD `AFTEAMR311`, Toshiba 4K `AFTKMST12`, Insignia 4K `AFTJMST12`, Onida HD `AFTLE`, Onida HD/FHD `AFTTIFF55`, AmazonBasics HD/FHD `AFTBU001`; plus the soundbars `AFTMM`.
- **Fire OS 5 / API 22:** Element 4K (2017) `AFTRS`.

Beware of model-string collisions: `AFTSS` is both the 2020 Stick Lite and the 2024 Stick HD; `AFTMM` is both the Stick 4K 1st Gen and two soundbars; `AFTKA` (Stick 4K Max 1st Gen) is a prefix of `AFTKA002` (2-Series TV). Match exact strings, never prefixes.

### Where Vega OS is heading [secondary]

According to press reports (AFTVnews, Tom's Guide, Android Authority, Digital Trends; late 2025), Amazon said future Fire TV **Sticks** will run Vega OS. The spec data fits that: every stick released since the 2025 4K Select runs Vega, except the Fire OS 8 "4K Plus". None of the sources say Fire OS smart TVs are moving to Vega, and 2026 TVs still ship Fire OS 8 or 14. Index: <https://www.aftvnews.com/tag/sideload/>

## 2. Why Vega OS is unsupported

- Vega apps are React Native/JS packages (`.vpkg`) with the React Native runtime built into the OS. [primary] <https://developer.amazon.com/docs/vega/2.0/vega-rn-overview>
- Vega's Developer Mode is how you sideload, and it needs an **Amazon Developer Account**. You run `vega devmode login`, type a 6-digit code shown on the TV, and the device reboots. You install with `vega device install-app --packagePath <x>.vpkg`. [primary] <https://developer.amazon.com/docs/vega/2.0/developer-mode.html> (last updated 2026-10-13)
- The device tool is **VDA (Vega Device Adapter)**, not adb. It looks very much like adb: server on 5037, TCP on 5555, it honors `ANDROID_SERIAL`, and it has the commands `devices`, `connect`, `shell`, `push`. But it has no `install` or `getprop`, and its shell tools are `vpm`/`vlcm` rather than `pm`/`am`. [primary] <https://developer.amazon.com/docs/vega/0.22/vda-tools>. To turn on TCP/IP mode, go to Settings > My Fire TV > Developer Options > Connection Mode, or connect over USB first. [primary] <https://developer.amazon.com/docs/vega/0.23/vda-over-tcpip>
- Amazon's Vega docs never mention APKs. The claim that Android apps must be rebuilt to run on Vega comes from third parties (e.g. Dolby's summary) [secondary]. For a Household, the practical result is that `adb install celastrina.apk` has nothing to target.

## 3. Enabling ADB on Fire OS: settings paths

**Amazon's documented path** (*Connect to Fire TV Through ADB*, last modified 2022-06-28) [primary] <https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html>:

1. **Settings > Device (or My Fire TV) > Developer Options**
2. Turn on **ADB Debugging**.
3. Turn on **Apps from Unknown Sources**.
4. Find the IP at **Settings > Device (or My Fire TV) > About > Network**.
5. Run `adb connect <ip>:5555` (any port from 5555 to 5585 is documented). On the first connection the TV shows "Allow USB debugging?". The user must pick **Always allow from this computer** and then **OK**. Check with `adb devices`.
6. Amazon says "most Fire TV devices only allow network connections", so USB is not a dependable path.

Install commands: `adb install <apk>`, `adb install -r <apk>` to reinstall, `adb uninstall <pkg>`, `adb shell pm list packages`. [primary] <https://developer.amazon.com/docs/fire-tv/installing-and-running-your-app>

**Hidden Developer Options** [secondary, but consistent with Amazon's Vega doc, which uses the same gesture]: newer Fire OS updates (first seen around Fire OS 7.2.8.x in 2022) hide **Developer Options** by default. To show it:

- Go to **Settings > My Fire TV > About**. On Fire TV smart TVs this is **Settings > Device & Software > About**.
- Select the first item (the device name, e.g. "Fire TV Stick") about **7 times**, until "No need, you are already a developer" appears.
- Press Back once. **Developer Options** now shows in the menu.

Sources: AFTVnews (2022-06-18) <https://www.aftvnews.com/how-to-find-show-unhide-reveal-developer-options-on-an-amazon-fire-tv-stick-fire-tv-cube-or-fire-tv-smart-tv/>. The same 7-press gesture on About is Amazon's own Vega instruction [primary]: <https://developer.amazon.com/docs/vega/2.0/developer-mode.html>.

**Not verified:**
- On newer Fire OS builds the "Apps from Unknown Sources" toggle reportedly became a per-app **Install unknown apps** list. I found no Amazon page that documents it [unverified].
- I also expect that `adb install` doesn't need the unknown-sources setting at all, because Android only applies that check to on-device installer apps, not to `pm install` over ADB [inference]. The Install Runbook should treat **ADB Debugging** as the only required toggle and fall back to the unknown-sources toggle if an install fails. Check this on the driving dev's TV.

Menu labels vary by device type and Fire OS version: "Device", "My Fire TV" or "Device & Software" [primary + secondary]. The runbook should describe what to look for rather than give one fixed path.

## 4. Detecting an unsupported TV remotely

Amazon's documented signals [primary] <https://developer.amazon.com/docs/device-specs/identify-fire-tv-devices.html> (last modified 2026-05-20):

- Feature flag `amazon.hardware.fire_tv` identifies all Fire TV devices. `Build.MODEL` patterns work "for Fire TV devices running Fire OS". Amazon warns that pairing MODEL with MANUFACTURER may fail for third-party TVs.
- API checks: `Build.VERSION.SDK_INT >= 25` means Fire OS 6 or later; `<= 22` means Fire OS 5.

Suggested runbook decision tree [inference, verify on hardware]:

1. Ask the user for the TV's IP, or have them read it from About > Network.
2. `adb connect <ip>:5555`.
   - If the connection is refused or times out, the cause is one of three things: ADB Debugging is off, it's a Vega device, or it's the wrong IP. Have the user open **Settings > My Fire TV > About**. A Vega device lists a "Vega OS" version there and has no "ADB Debugging" toggle in Developer Options; treat it as unsupported.
   - If the connection is pending authorisation, have the user accept the prompt on the TV.
3. Once `adb devices` shows `device`:
   - `adb shell getprop ro.build.version.sdk` must be a number ≥ Celastrina's minSdk.
   - `adb shell getprop ro.product.model` should be an `AFT…` string. Log it for support.
   - `adb shell pm has-feature amazon.hardware.fire_tv` confirms the device is a Fire TV. Not needed for install; useful if Android/Google TV support is added later.
   - These are the standard AOSP properties behind `Build.VERSION.SDK_INT` and `Build.MODEL`.
4. Optionally keep a denylist of the known Vega models `AFTCA002`, `AFTCR001` and `AFTCL001` for messaging. Don't rely on it, because new Vega sticks will add strings.

## 5. Implications for the minimum API level and the APK

- **minSdk 22** covers every Fire OS device ever made.
- **minSdk 25** drops only the 2014–2017 Fire OS 5 hardware: Fire TV 1st/2nd Gen, Stick 1st/2nd Gen/Basic, Element 4K.
- **minSdk 28** would also drop the Fire OS 6 Fire TV 3rd Gen, Cube 1st Gen, Stick 4K 1st Gen, and several 2018–2020 Toshiba/Insignia/Onida TVs.

This choice is for a later decision. Check which Fire OS the driving dev's "older" TV runs first (`getprop ro.build.version.sdk`).

- Fire OS 8 devices refuse to install APKs signed **only with v1**, so sign with v1 and v2 (or v2 and later). [primary] <https://developer.amazon.com/docs/fire-tv/fire-os-8>
- ABI: the dataset lists "32-bit" for every device except the Panasonic MX800 (2023, 64-bit). If Celastrina bundles native libraries (e.g. a media engine), it must include `armeabi-v7a`. [primary, same spec dataset]

## Open questions

- Is the per-app "Install unknown apps" setting needed for `adb install` on Fire OS 7, 8 and 14? Test on hardware.
- Which exact Fire OS version and model is the driving dev's TV?
- Does plain `adb` get any response from a Vega device in Developer Mode, given that VDA copies adb's ports? This doesn't matter for Households, whose Vega devices won't be in Developer Mode.
