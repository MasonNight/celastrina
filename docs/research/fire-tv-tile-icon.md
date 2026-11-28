# Filling Celastrina's Fire TV tile

Research for [#58](https://github.com/MasonNight/celastrina/issues/58). Sources were read on 2026-11-28. Each
answer is marked **Tested** (seen on the household TV) or **Docs** (from documentation only).

The device is the household AFTTI43 running Fire OS 7.7.1.8 (PS7718/5826, API 28). Screenshots and mockups are on
the [`evidence/58-icon-mockups`](https://github.com/MasonNight/celastrina/tree/evidence/58-icon-mockups/evidence/58-icon)
branch.

## What Fire OS draws

**Tested.** In **Your Apps & Channels**, each tile is 263×148 px on the 1920×1080 screen (≈349×196 when focused).
For a sideloaded app, Fire OS scales the square `android:icon` to the full tile height (148×148), centres it, and
fills the two side bars with flat grey `#5A5A5A`. The grey stays the same when the tile is focused. The page behind
the tiles is `#1A1A1A`.

There are three sideloaded packages on the TV (`pm list packages -3 -i`, installer `null`): `dev.celastrina`,
`dev.celastrina.debug` and `tv.cartoonwatch.app`. All three are letterboxed the same way.

- Cartoon Watch sets `android:banner` on `<application>` (`aapt dump badging` shows
  `banner='res/drawable/tv_banner.xml'`).
- Celastrina sets it on both `<application>` and the activity.

So Fire OS 7 ignores the banner in both places. Apps installed from the Appstore fill their tiles edge to edge.

Celastrina isn't on the Home row: that row holds only the Amazon-pinned apps. So there's no Home screenshot to
mock up. A Home favourite tile would be 144×80 px, smaller than the Apps grid tile.

## Can a sideloaded app control what fills its tile?

**Fire OS 7, Docs and Tested:** only through the square icon itself. Amazon documents no manifest attribute,
`com.amazon…` meta-data or resource for the tile, and doesn't mention `android:banner` anywhere.

- Adaptive icons are explicitly unsupported: *"Some Android 9 features, such as split-Screen, notification dots, and
  adaptive icons, aren't supported in Fire OS 7."* ([Fire OS 7](https://developer.amazon.com/docs/fire-tv/fire-os-7.html))
- Amazon's sideloading page says only *"Sideloaded apps appear in both the Recent row and in the My Library row in the
  Apps section"*, and nothing about icons or banners.
  ([Installing and running your app](https://developer.amazon.com/docs/fire-tv/installing-and-running-your-app.html))
- Android allows a TV launcher to pick either image: *"Depending on the Android TV device, either the icon or banner is
  used as the app launch point."*
  ([Get started with TV apps](https://developer.android.com/training/tv/get-started/create))
  Fire OS 7 picks the icon.
- `android:roundIcon` is deprecated for TV
  ([TV app icon guidelines](https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines)), and
  no Amazon page mentions it.

**Fire OS 8, Docs only:** the docs say nothing. [Fire OS 8](https://developer.amazon.com/docs/fire-tv/fire-os-8.html)
mentions no icons, adaptive icons or banners, only that *"Some Android 11 features aren't yet supported in all Fire OS
8 devices."* There's no Fire OS 8 device here to test on.

**Untested lever: a non-square `android:icon`.** Fire OS fits the icon to the tile height. If it preserves the aspect
ratio and fits within the whole tile, a 16:9 PNG icon would fill it. If it always scales to the height, the icon
would be cropped or squashed instead. No source says which. Testing it means reinstalling the debug app to refresh
the cached tile (uninstall `-k`, reinstall, `pm trim-caches`), so it needs the maintainer's go-ahead. It would also
change how the icon looks in Settings and the app switcher.

## Where does the grey come from?

**Tested and Docs:** the launcher draws it. It isn't part of the icon, which is navy edge to edge and fills the
148×148 square. No source documents the grey, and the app has no documented way to change its colour:

- The background layer of an adaptive icon is the usual mechanism, and it's unsupported on Fire OS 7 (above).
- `android:colorPrimary` only tints the launch animation: *"To customize the color of this animation, set the
  `android:colorPrimary` attribute."* ([Get started with TV apps](https://developer.android.com/training/tv/get-started/create))

**The practical lever:** paint the square's own background `#5A5A5A`, so the bars and the icon read as one grey
tile. This is mocked up as option C. It's **untested** on the device. Risks:

- A faint seam might show where Fire scales the square's edge.
- A future launcher update could change the grey.

## Would the Amazon Appstore change anything?

**Docs only: yes.** An Appstore submission uploads a 16:9 tile image, and Amazon says that's what the Fire TV
interface shows:

- App Icon (Fire TV): *"1280 x 720px"*, *"PNG (no transparency)"*, and *"The App Icon appears within a row of other
  app icons in the Fire TV user interface."*
  ([Add store listing information](https://developer.amazon.com/docs/app-submission/add-store-listing-information.html))
- An archived Fire App Builder page says the APK's `ic_launcher` is *"the image thumbnail that appears in your list of
  apps on Fire TV… However, when you submit your app into the Amazon Appstore, you're required to submit an app icon
  that is 1280 x 720px (PNG). This is the actual icon that will be used in the Fire TV user interface."*
  ([archive.org copy](https://web.archive.org/web/20210118132859/https://developer.amazon.com/docs/fire-app-builder/customize-look-and-feel.html);
  the live page is gone)

That fits what the TV shows: every Appstore app fills its tile, and every sideloaded app is letterboxed. Option D
shows what an Appstore tile could look like.

## Leanback launcher conventions (AOSP and Android TV)

**Docs only.**

- `android:banner` is 320×180 px at xhdpi, *"Text must be included in the image"*. It goes on `<application>` as a
  default for every activity, or on an `<activity>`.
  ([Get started with TV apps](https://developer.android.com/training/tv/get-started/create))
- *"There is no default banner."*
  ([`<application>`](https://developer.android.com/guide/topics/manifest/application-element#banner))
- In the framework, an activity's banner falls back to the application's
  (`return banner != 0 ? banner : applicationInfo.banner;` in
  [`ComponentInfo.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/core/java/android/content/pm/ComponentInfo.java)).
  The framework never substitutes the icon. Falling back to the icon is the launcher's choice, and the Leanback
  launcher's source isn't public.

Celastrina's banner therefore still matters on Android TV and Google TV launchers, even though Fire OS 7 ignores it.

## Mockups

All mockups are composited at true size onto a real screenshot of Your Apps & Channels. The bottom-left Celastrina tile
in each image is left untouched, as the current tile for comparison. A 3× zoom of each tile is on the evidence branch
too, for inspection only.

| | Design | Within Fire OS 7 sideloading? |
|---|---|---|
| Current | Navy square, moon and butterfly | yes (shipped) |
| A | *Celastrina* in italic serif above a smaller moon, navy square | yes |
| B | CELASTRINA in spaced bold caps above the moon, navy square | yes |
| C | As A, on a `#5A5A5A` square that merges with Fire's grey bars | yes, **untested** on the device |
| D | Full 16:9 navy tile, italic serif name above the moon | only via the Appstore 1280×720 icon, or the untested wide-icon lever |

At true size, A's and C's name is readable from across the room. B's caps are the smallest, about 11 px high, and
the weakest of the three.

## Recommendation

Ship **C** (the name above the butterfly, on a square that blends into Fire's grey), because it's the only option
that reads as a filled tile for a sideloaded app. If the maintainer allows a debug reinstall, test a 16:9
`android:icon` first: if Fire OS fits it to the whole tile, ship **D** instead.
