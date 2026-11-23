# Extension formats Celastrina could host

Research for [#15](https://github.com/MasonNight/celastrina/issues/15). Question: what would it take for Celastrina (Kotlin, Compose for TV, Media3, `minSdk 25`, tested on Fire OS 7 / API 28 / 32-bit ARM, one universal APK with no native code) to host Extensions that a Household imports, and which formats are realistic?

Scope: this note covers only the host mechanism and the plugin APIs. It does not list, test or link any scraper Extension, Extension repository, or the sites they target.

Sources were read on 2026-11-23 at these commits:

| Repo | Commit | Licence |
|---|---|---|
| [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream) | `a28e6468f5` | GPL-3.0 |
| [recloudstream/gradle](https://github.com/recloudstream/gradle) (the plugin build tool) | `32895aedb6` | LGPL-3.0 |
| [recloudstream/TestPlugins](https://github.com/recloudstream/TestPlugins) (the plugin template) | `d87d65a16e` | none stated |
| [recloudstream/csdocs](https://github.com/recloudstream/csdocs) (CloudStream docs) | `092c74c9a5` | none stated |
| [aniyomiorg/aniyomi](https://github.com/aniyomiorg/aniyomi) | `97414446b8` | Apache-2.0 |
| [aniyomiorg/extensions-lib](https://github.com/aniyomiorg/extensions-lib) | `971ce96e76` | Apache-2.0 |
| [Spyou/Zangetsu](https://github.com/Spyou/Zangetsu) | `48741e66aa` | GPL-3.0 |
| [mozilla/rhino](https://github.com/mozilla/rhino) | `e0fd905ebf` | MPL-2.0 |

Link prefixes used below: `CS` = `https://github.com/recloudstream/cloudstream/blob/a28e6468f5/`, `ANI` = `https://github.com/aniyomiorg/aniyomi/blob/97414446b8/`, `EXT` = `https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/`, `ZAN` = `https://github.com/Spyou/Zangetsu/blob/48741e66aa/`.

## Summary

| | CloudStream `.cs3` | Aniyomi APK | Sandboxed JS (Rhino) |
|---|---|---|---|
| **Runs on our stack?** | Yes. Plain DEX, no native code; CloudStream itself has `minSdk 23` and pins libraries for Fire TV. | Probably, but the host app needs API 26, so API 25 is untested upstream. Pure DEX. | Only Rhino ≤ 1.8.x. Rhino 1.9+ needs API 26, interpreted mode only. |
| **Host API** | The real CloudStream `library` artifact (GPL-3.0) can be bundled as-is, plus shims for a few app-module classes. | Reimplement the stub API in `extensions-lib` (Apache-2.0): ~35 classes. | Ours to design. |
| **Isolation without changing plugins** | No. Plugins get an Android `Context`, use the library's global HTTP client, WebView, and shared key-value store. Crash isolation in a separate (non-isolated) process is possible. | No, for the same reasons (`Injekt`, `SharedPreferences`, `androidx.preference`). | Yes, by construction: the script can only reach host functions we expose. |
| **Licence** | GPL-3.0, same as Celastrina. | Apache-2.0, one-way compatible with GPL-3.0. | MPL-2.0, compatible through its "Secondary Licenses" clause. |
| **Maps onto the Source contract?** | Search by title, then `load`, then `loadLinks`. A few providers accept an IMDb ID directly. Typed `ExtractorLink` with headers, referer, quality and type. | Search by title, then episode list, then hosters, then videos. `Video` has resolution, bitrate, headers, subtitles. | Whatever we define. Zangetsu's JS contract is title-search based too. |

## 1. CloudStream extensions (`.cs3`)

### File format

A `.cs3` is a zip built by the CloudStream Gradle plugin. It contains `manifest.json`, a `classes.dex`, and optionally the contents of a compiled resource APK (minus `AndroidManifest.xml`) when the plugin sets `requiresResources` ([recloudstream/gradle `tasks/Tasks.kt` L150–167](https://github.com/recloudstream/gradle/blob/32895aedb6/src/main/kotlin/com/lagradost/cloudstream3/gradle/tasks/Tasks.kt#L150-L167)). The manifest has four fields: `name`, `pluginClassName`, `requiresResources`, `version` ([CS `library/.../plugins/BasePlugin.kt` L64–77](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/commonMain/kotlin/com/lagradost/cloudstream3/plugins/BasePlugin.kt#L64-L77)).

Plugins are compiled `compileOnly` against the **whole CloudStream app**: the Gradle plugin downloads `classes.jar` from a CloudStream GitHub release and adds it as a `compileOnly` dependency ([recloudstream/gradle `CloudstreamConfigurationProvider.kt` L26–38](https://github.com/recloudstream/gradle/blob/32895aedb6/src/main/kotlin/com/lagradost/cloudstream3/gradle/configuration/CloudstreamConfigurationProvider.kt#L26-L38), [`CloudstreamExtension.kt` L95–99](https://github.com/recloudstream/gradle/blob/32895aedb6/src/main/kotlin/com/lagradost/cloudstream3/gradle/CloudstreamExtension.kt#L95-L99)). The template calls this "Stubs for all cloudstream classes" and sets `minSdk = 21` and `jvmTarget = JVM_1_8` ([TestPlugins `build.gradle.kts` L48, L60, L75](https://github.com/recloudstream/TestPlugins/blob/d87d65a16e/build.gradle.kts#L48-L75)). D8 dexes the plugin at the project's `minSdk` ([gradle `CompileDexTask.kt`](https://github.com/recloudstream/gradle/blob/32895aedb6/src/main/kotlin/com/lagradost/cloudstream3/gradle/tasks/CompileDexTask.kt), [`LibraryExtensionCompat.kt` L31–34](https://github.com/recloudstream/gradle/blob/32895aedb6/src/main/kotlin/com/lagradost/cloudstream3/gradle/LibraryExtensionCompat.kt#L31-L34)).

The README says the plugin system is "heavily based on Aliucord" ([recloudstream/gradle README](https://github.com/recloudstream/gradle/blob/32895aedb6/README.md)).

### Repositories and plugin lists

Two levels of JSON ([csdocs `devs/create-your-own-json-repository.md`](https://github.com/recloudstream/csdocs/blob/092c74c9a5/devs/create-your-own-json-repository.md); [CS `RepositoryManager.kt` L34–77](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt#L34-L77)):

- **Repository** (`repo.json`): `name`, `description`, `iconUrl`, `manifestVersion` (unused), `pluginLists` (URLs).
- **Plugin list** (`plugins.json`): an array of `SitePlugin` with `url` (to the `.cs3`), `status` (0 down, 1 ok, 2 slow, 3 beta), `version` (any change triggers auto-update), `apiVersion` (always 1, unused), `name`, `internalName`, `authors`, `description`, `repositoryUrl`, `tvTypes`, `language`, `iconUrl`, `fileSize`, `fileHash` (`sha256-<hex>`).

Import: the Household gives a URL, a `cloudstreamrepo://` link, or a short code. The short code is resolved through third-party URL shorteners by following an HTTP redirect ([`RepositoryManager.kt` L132–158](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt#L132-L158)). Downloads are verified against `fileHash` when it is present ([L193–218](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt#L193-L218)). That hash comes from the same plugin list, so it detects corruption, not tampering. There is no signing.

### Host API a plugin compiles against

- **`BasePlugin` / `Plugin`**: the entry class. `load()` / `load(context: Context)` registers providers with `registerMainAPI(MainAPI)` and extractors with `registerExtractorAPI(ExtractorApi)`, which add them to global registries (`APIHolder.allProviders`, `extractorApis`) ([CS `BasePlugin.kt` L14–48](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/commonMain/kotlin/com/lagradost/cloudstream3/plugins/BasePlugin.kt#L14-L48); [CS `app/.../plugins/Plugin.kt`](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/app/src/main/java/com/lagradost/cloudstream3/plugins/Plugin.kt)). `Plugin` also exposes `resources` and an `openSettings: (Context) -> Unit` hook for the plugin's own settings UI.
- **`MainAPI`**: `search(query)`, `quickSearch`, `getMainPage`, `load(url): LoadResponse`, `loadLinks(data, isCasting, subtitleCallback, callback: (ExtractorLink) -> Unit): Boolean`, `getVideoInterceptor(link): okhttp3.Interceptor?`, and `getLoadUrl(SyncIdName, id)` for providers that can open a page from an external ID. `supportedSyncNames` declares which (`Anilist`, `MyAnimeList`, `Kitsu`, `Trakt`, `Imdb`, `Simkl`, `LocalList`). Per-call timeout hints: `loadLinksTimeoutMs`, `searchTimeoutMs`, `loadTimeoutMs`, and others ([CS `MainAPI.kt` L494–716](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt#L494-L716); [`syncproviders/SyncAPI.kt`](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/commonMain/kotlin/com/lagradost/cloudstream3/syncproviders/SyncAPI.kt)).
- **`LoadResponse`** carries `syncData`, a map where providers can record MAL, AniList, Kitsu, IMDb (`addImdbId`) and TMDB (`addTMDbId`) IDs ([`MainAPI.kt` L1812–2060](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt#L1812-L2060)). An `Episode` has `data`, `name`, `season`, `episode` and more ([L2550](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt#L2550)).
- **`ExtractorLink`** ([CS `utils/ExtractorApi.kt` L686–760](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt#L686-L760)): `source`, `name`, `url`, `referer`, `quality: Int`, `headers: Map<String,String>`, `extractorData`, `type`, `audioTracks`. `getAllHeaders()` merges `referer` into the headers. `ExtractorLinkType` is `VIDEO`, `M3U8`, `DASH`, `TORRENT` or `MAGNET` ([L416–443](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt#L416-L443)). `Qualities` maps to 144–2160 p, with 400 meaning "Unknown" ([L847](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt#L847)).
- **`ExtractorApi`**: `name`, `mainUrl`, `requiresReferer`, `getUrl(url, referer, subtitleCallback, callback)` ([L1420](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt#L1420)). The library ships 114 built-in extractor files that plugins call through `loadExtractor` (count of `library/**/extractors/*.kt` at this commit).
- **Helpers plugins rely on**: the global `app` HTTP client (NiceHttp over OkHttp), Jsoup, Jackson, Rhino 1.8.1, `WebViewResolver` (a real Android WebView, 60 s default), Ktor `http`, and kotlinx coroutines/serialization ([CS `library/build.gradle.kts` L55–69](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/build.gradle.kts#L55-L69); [`network/WebViewResolver.android.kt` L49](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/src/androidMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.android.kt#L49)).

### How CloudStream loads them

`PluginManager.loadPlugin` ([CS `PluginManager.kt` L593–688](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/app/src/main/java/com/lagradost/cloudstream3/plugins/PluginManager.kt#L593-L688)):

1. Marks the file read-only, for Android 14 (L598–609).
2. `PathClassLoader(filePath, context.classLoader)`. **The parent is the app's own class loader**, so plugin code links directly against every host class (L611).
3. Reads `manifest.json` as a class-loader resource and instantiates `pluginClassName` reflectively (L612–634).
4. If `requiresResources` is set, builds a `Resources` through the hidden `AssetManager.addAssetPath` (L645–658).
5. Calls `load(context)` in the app process (L671–675).

Lookups run under `withTimeout`: 120 s default, clamped to 5 s–8 min, with the provider's hint used when set ([CS `ui/APIRepository.kt` L28–33, L70, L209](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/app/src/main/java/com/lagradost/cloudstream3/ui/APIRepository.kt#L28-L33)). A "safe mode" file disables plugin loading after crashes ([`PluginManager.kt` L571–591](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/app/src/main/java/com/lagradost/cloudstream3/plugins/PluginManager.kt#L571-L591)).

### Reimplement or reuse under GPL-3.0?

**Reuse is the realistic path, and Zangetsu shows it works.** CloudStream publishes its Kotlin Multiplatform `library` module (namespace `com.lagradost.api`, Android + JVM targets) ([CS `library/build.gradle.kts` L20–45](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/build.gradle.kts#L20-L45)). Zangetsu depends on `com.github.recloudstream.cloudstream:library:v4.8.0` and loads `.cs3` files with `PathClassLoader` against it ([ZAN `android/app/build.gradle.kts` L216–230](https://github.com/Spyou/Zangetsu/blob/48741e66aa/android/app/build.gradle.kts#L216-L230); [ZAN `PluginHost.kt` L33–45, L190](https://github.com/Spyou/Zangetsu/blob/48741e66aa/android/app/src/main/kotlin/com/spyou/watch_app/cloudstream/PluginHost.kt#L33-L45)). Both projects are GPL-3.0, so bundling the library in Celastrina raises no licence conflict.

What reuse costs, as Zangetsu's build file records:

- **App-module classes.** Plugins compile against the whole app jar, so some reference classes that are not in `library`. Zangetsu adds "clean-room shims" in package `com.lagradost.cloudstream3` for `AcraApplication` (key/value storage and `context`) and `MainActivity` (lifecycle events) so that those plugins link ([ZAN `cloudstream/VendoredAppShims.kt`](https://github.com/Spyou/Zangetsu/blob/48741e66aa/android/app/src/main/kotlin/com/spyou/watch_app/cloudstream/VendoredAppShims.kt)). Zangetsu also vendors `DataStore`, `CloudflareKiller`, `WebkitCookieJar` and other classes ([ZAN `cloudstream/`](https://github.com/Spyou/Zangetsu/tree/48741e66aa/android/app/src/main/kotlin/com/spyou/watch_app/cloudstream)).
- **Dependency drift.** Plugins rebuilt against CloudStream's pre-release inline calls into newer library versions. Zangetsu had to force `kotlinx-coroutines` 1.11.0 and add `ktor-http` at runtime, because plugins otherwise failed with `NoClassDefFoundError` ([ZAN `build.gradle.kts` L200–214, L333–344](https://github.com/Spyou/Zangetsu/blob/48741e66aa/android/app/build.gradle.kts#L200-L214)). A host that bundles the library has to keep its transitive versions in step with what plugins were compiled against. The `apiVersion` field that might have signalled this is "currently unused" ([`RepositoryManager.kt` L56–58](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt#L56-L58)).
- **Activity-dependent plugins.** Zangetsu notes plugins that "hard-cast the load context to an Activity" for their settings UI ([ZAN `PluginHost.kt` L56–60, L286](https://github.com/Spyou/Zangetsu/blob/48741e66aa/android/app/src/main/kotlin/com/spyou/watch_app/cloudstream/PluginHost.kt#L56-L60)).

A clean reimplementation of the API is possible in principle. It would have to reproduce the `com.lagradost.cloudstream3.*` class and method signatures exactly, since plugin DEX links by name, and keep pace with upstream. That is a large, moving target (the library has 176 Kotlin files) for no licensing gain.

### API 25 and 32-bit ARM

- **No native code.** A `.cs3` has only `classes.dex`, `manifest.json` and optional resources (see File format above). DEX is architecture-independent, so 32-bit ARM is not a concern for the plugin itself.
- **CloudStream targets old Fire TVs.** It sets `minSdk = "23"` ([CS `gradle/libs.versions.toml` L67](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/gradle/libs.versions.toml#L67)). It pins Jackson at 2.13.1 because "Later versions don't support minSdk <26 (Crashes on Android TV's and FireSticks)" (L22), and Rhino at 1.8.1 because 1.9.0 "Requires minSdk 26" (L50). The library inherits the same `minSdk` ([CS `library/build.gradle.kts` L29–30](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/library/build.gradle.kts#L29-L30)).
- **Desugaring.** The CloudStream app enables core-library desugaring with the NIO flavour ([CS `app/build.gradle.kts` L184, L276](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/app/build.gradle.kts#L184)). If Celastrina bundles the library, it should do the same.
- **Unverified:** whether any given plugin calls an Android API above 25. The template dexes at `minSdk 21`, but nothing enforces API usage, so the answer is per plugin. This was not tested (scope limit).

## 2. Aniyomi extensions (APKs)

### Format and repositories

An Aniyomi extension is an ordinary Android APK. It declares `<uses-feature android:name="tachiyomi.animeextension"/>` and `meta-data` for `aniyomix.name`, `aniyomix.contentWarning`, `aniyomix.torrent`, `aniyomix.extensionLib` (lib version) and `tachiyomi.animeextension.class` (one or more `;`-separated classes) ([EXT `README.md`](https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/README.md)). The host accepts lib versions 14, 16 and 17 ([ANI `AnimeExtensionLoader.kt` L40–53](https://github.com/aniyomiorg/aniyomi/blob/97414446b8/app/src/main/java/eu/kanade/tachiyomi/extension/anime/util/AnimeExtensionLoader.kt#L40-L53)).

A repository serves `repo.json` and `index.min.json` ([ANI `ExtensionRepoService.kt`](https://github.com/aniyomiorg/aniyomi/blob/97414446b8/domain/src/main/java/mihon/domain/extensionrepo/service/ExtensionRepoService.kt)). The index schema is defined as protobuf, with an equivalent JSON form, optionally gzipped. It holds `name`, `badgeLabel`, **`signingKey`**, `contact`, and a list of extensions (`packageName`, `apkUrl`, `extensionLib`, `versionCode`, `contentWarning`, `isTorrent`, `sources`) ([EXT `index/index.proto`](https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/index/index.proto), [`index/README.md`](https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/index/README.md)).

### Host API

`extensions-lib` v17 is a **stub library**: every body is `throw Exception("Stub!")`, and extensions depend on it `compileOnly` because "The actual implementations are included in the host app" ([EXT `README.md`](https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/README.md)). It has about 35 Kotlin files ([EXT `library/src/main/java`](https://github.com/aniyomiorg/extensions-lib/tree/971ce96e76/library/src/main/java/eu/kanade/tachiyomi)):

- `AnimeSource` / `AnimeCatalogueSource`: `getPopularAnime`, `getLatestUpdates`, `getSearchAnime(page, query, filters)`, `getAnimeDetails`, `getEpisodeList`, `getSeasonList`, `getHosterList(episode)`, `getVideoList(hoster)`.
- `AnimeHttpSource`, a template-method base class with `*Request` / `*Parse` pairs, plus `resolveVideo`, `sortVideos`, `headersBuilder`, and `client`/`network` ([EXT `AnimeHttpSource.kt`](https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/library/src/main/java/eu/kanade/tachiyomi/animesource/online/AnimeHttpSource.kt)).
- Models: `SAnime`, `SEpisode` (`episode_number: Float`), `Hoster`, and **`Video`** (`videoUrl`, `videoTitle`, `resolution: Int?`, `bitrate: Int?`, `headers: okhttp3.Headers?`, `subtitleTracks`, `audioTracks`, `timestamps`, plus mpv/ffmpeg args) ([EXT `Video.kt`](https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/library/src/main/java/eu/kanade/tachiyomi/animesource/model/Video.kt)).
- `NetworkHelper` (OkHttp client), `JavaScriptEngine.evaluate(script)`, and `HttpServer`, a NanoHTTPD subclass that lets an extension start a local HTTP server ([EXT `network/`](https://github.com/aniyomiorg/extensions-lib/tree/971ce96e76/library/src/main/java/eu/kanade/tachiyomi/network), [`HttpServer.kt`](https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/library/src/main/java/eu/kanade/tachiyomi/animesource/model/HttpServer.kt)).
- `ConfigurableAnimeSource.setupPreferenceScreen(androidx.preference.PreferenceScreen)` for settings.

A host must also provide these runtime libraries at compatible versions: Kotlin 2.4.0, coroutines 1.10.2, kotlinx.serialization 1.9.0 (json, json-okio, protobuf), OkHttp 5.4.0 (+brotli, zstd), Jsoup 1.22.2, Injekt, and NanoHTTPD 2.3.1 ([EXT `README.md` "App Dependency Requirements"](https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/README.md)). In Aniyomi the `JavaScriptEngine` is backed by QuickJS, which is native code ([ANI `core/common/.../JavaScriptEngine.kt` L22](https://github.com/aniyomiorg/aniyomi/blob/97414446b8/core/common/src/main/java/eu/kanade/tachiyomi/network/JavaScriptEngine.kt#L22); [ANI `gradle/libs.versions.toml` L29](https://github.com/aniyomiorg/aniyomi/blob/97414446b8/gradle/libs.versions.toml#L29)). Its contract only returns primitives, so a reimplementation could back it with Rhino.

### How Aniyomi loads them

([ANI `AnimeExtensionLoader.kt`](https://github.com/aniyomiorg/aniyomi/blob/97414446b8/app/src/main/java/eu/kanade/tachiyomi/extension/anime/util/AnimeExtensionLoader.kt))

- **Two install modes.** *Shared*: installed system-wide as a package and found through `getInstalledPackages`. *Private*: the APK is copied to `filesDir/exts/*.ext`, set read-only, and parsed with `getPackageArchiveInfo`, with no system install (L61–160).
- **Trust gate.** The loader reads the signing-certificate SHA-256. An extension is trusted if that fingerprint equals a configured repository's `signingKey`, or if the user trusted that exact `package:versionCode:fingerprint` (L272–286; [ANI `TrustAnimeExtension.kt`](https://github.com/aniyomiorg/aniyomi/blob/97414446b8/app/src/main/java/eu/kanade/domain/extension/anime/interactor/TrustAnimeExtension.kt)). This is stronger than CloudStream's hash, because the key is pinned per repository.
- **Loading.** `ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)` resolves the system class loader first, then the extension, then the host. On `LinkageError` it falls back to a plain `PathClassLoader` (L298–345; [ANI `ChildFirstPathClassLoader.kt`](https://github.com/aniyomiorg/aniyomi/blob/97414446b8/app/src/main/java/eu/kanade/tachiyomi/util/system/ChildFirstPathClassLoader.kt)). Everything runs in the app process.

### API 25, 32-bit ARM, reuse

- **The Aniyomi host has `MIN_SDK = 26`** ([ANI `buildSrc/.../AndroidConfig.kt` L7–9](https://github.com/aniyomiorg/aniyomi/blob/97414446b8/buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt#L7-L9)), so upstream never exercises extensions on API 25. `extensions-lib` itself declares `minSdk = 24` and Java 17 bytecode ([EXT `library/build.gradle.kts` L21–33](https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/library/build.gradle.kts#L21-L33)). An extension APK's own `minSdk` is set by whoever builds it. **Unverified for API 25**, and probably fine on Fire OS 7 (API 28).
- Extensions are DEX inside an APK. Nothing in the lib requires native code, so 32-bit ARM is not a concern for them; the host's QuickJS would be, which is why Celastrina would swap it.
- **Reuse means reimplementation.** The stubs define the API, and the host supplies real classes in the `eu.kanade.tachiyomi.*` packages. Zangetsu does exactly this with vendored Aniyomi classes plus RxJava 1, Injekt, kotlinx-serialization-protobuf and `androidx.preference` ([ZAN `build.gradle.kts` L315–345](https://github.com/Spyou/Zangetsu/blob/48741e66aa/android/app/build.gradle.kts#L315-L345)). Zangetsu's build notes record an extension whose static initialiser threw inside an extension-launched coroutine and "killed the whole app" until a missing dependency was added (same lines). That is the in-process failure mode.
- **Licence.** Aniyomi and `extensions-lib` are Apache-2.0. The FSF lists Apache-2.0 as "compatible with version 3 of the GNU GPL" (not GPLv2) ([gnu.org license list, #apache2](https://www.gnu.org/licenses/license-list.html#apache2)). Celastrina may include or adapt that code under GPL-3.0 if it keeps the Apache notices.

### Prior art: a CloudStream ↔ Aniyomi bridge

CloudStream's own docs describe a compatibility plugin. It downloads an internal compat APK so that installed Aniyomi extensions appear as CloudStream providers, and it is "not guaranteed to work perfectly" ([csdocs `Integrations/Aniyomi.md`](https://github.com/recloudstream/csdocs/blob/092c74c9a5/Integrations/Aniyomi.md)). It shows that the two APIs are close enough to adapt one onto the other.

## 3. Isolation

### What Android gives

- **`android:isolatedProcess="true"`** on a `<service>`: "this service runs under a special process that is isolated from the rest of the system and has no permissions of its own. The only communication with it is through the Service API, with binding and starting" ([service element](https://developer.android.com/guide/topics/manifest/service-element#isolated)). Isolated processes get UIDs 99000–99999 ([AOSP `Process.java` L186–192, android-9.0.0_r1](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/core/java/android/os/Process.java)).
- **No network.** `INTERNET` is what maps an app to the `inet` group ([AOSP `platform.xml` L54–56, android-7.1.2_r1](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.1.2_r1/data/etc/platform.xml)), and an isolated process has no permissions. Current SELinux policy allows isolated apps only "network sockets received over IPC. New socket creation is not permitted" ([AOSP sepolicy `private/isolated_app.te`, main](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/isolated_app.te)).
- **No app files.** `neverallow isolated_app ... app_data_file:file open`: an isolated process may only read or write app data files passed to it as file descriptors. It may find only `activity_service`, `display_service` and `webviewupdate_service` ([sepolicy `private/isolated_app_all.te`, main](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/isolated_app_all.te); same rules in [android-7.1.2_r1 `isolated_app.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-7.1.2_r1/isolated_app.te) and [android-9.0.0_r1](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-9.0.0_r1/private/isolated_app.te)).
- **Loading DEX inside it.** `DexClassLoader`/`PathClassLoader` need a file path. Before API 26, DexClassLoader "requires an application-private, writable directory to cache optimized classes" ([DexClassLoader](https://developer.android.com/reference/dalvik/system/DexClassLoader)). `InMemoryDexClassLoader(ByteBuffer, parent)` was **added in API 26** ([InMemoryDexClassLoader](https://developer.android.com/reference/dalvik/system/InMemoryDexClassLoader)). So on Fire OS 7 (API 28) an isolated service could receive the plugin's bytes over Binder and load them in memory. On Fire OS 6 (API 25) there is no in-memory loader, and the isolated process can neither open app files nor, by inference, keep a writable private code cache. *Inference, not tested:* isolated DEX loading is likely impossible on API 25.
- **Android 14 rule.** Apps targeting API 34+ must mark every dynamically loaded file read-only, "Otherwise, the system throws an exception" ([Android 14 behaviour changes, "Safer dynamic code loading"](https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading)). Both CloudStream and Aniyomi already do this.
- A plain `android:process=":ext"` service runs under the **same UID**: separate process, same files and network ([service element `android:process`](https://developer.android.com/guide/topics/manifest/service-element#proc)). It gives crash isolation, not a security boundary.

### Can either format run isolated without changing the plugins?

**No, not fully.** The reasons, from the plugin APIs above:

- **Network.** CloudStream plugins mostly use the library's global `app` client, which Celastrina would own inside the isolated process and could route over Binder to a host-side proxy. But plugins also receive raw OkHttp (`getVideoInterceptor`, direct client construction) and use `WebViewResolver`, which needs a real WebView. Aniyomi extensions build their own `OkHttpClient` from `network.client` and can start a NanoHTTPD `HttpServer`. In an isolated process, any socket the plugin opens itself fails. *Inference:* a proxy would need to stand in for OkHttp's transport (for example with a custom `SocketFactory` backed by sockets the host passes over IPC, which SELinux allows). That is plausible but unproven, and a WebView cannot run there.
- **Context and storage.** CloudStream calls `Plugin.load(context)`; plugins call `getKey`/`setKey` and `AcraApplication.context`. Aniyomi extensions use `Injekt.get<Application>()` and `SharedPreferences`, and render an `androidx.preference.PreferenceScreen`. An isolated process has a `Context`, but no access to the app's data or `SharedPreferences` files, so all of these need Binder-backed fakes.
- **Resources.** CloudStream's `requiresResources` uses `AssetManager.addAssetPath(path)`, which needs a readable path.
- **API 25.** The in-memory loader does not exist there (above).

What is realistic: (a) a **same-UID `:ext` process** for crash and ANR containment plus a hard kill on timeout, with no security gain; or (b) a **partially isolated** host on API 26+ that serves the plugins that only use `app` and Jsoup, with the rest failing as `source_error`. Option (b) means writing and maintaining Binder shims for the plugin API surface. Neither format was designed for it, and neither upstream host does it.

### What an in-process plugin can reach

Everything Celastrina can: the plugin's classes are loaded with the app's class loader as parent ([CS `PluginManager.kt` L611](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/app/src/main/java/com/lagradost/cloudstream3/plugins/PluginManager.kt#L611); [ANI `AnimeExtensionLoader.kt` L299](https://github.com/aniyomiorg/aniyomi/blob/97414446b8/app/src/main/java/eu/kanade/tachiyomi/extension/anime/util/AnimeExtensionLoader.kt#L299)) and run in its process and UID. Concretely:

- **Other Sources' credentials:** the plugin can read the same `filesDir`, databases and `SharedPreferences`, including Jellyfin/Emby tokens and Stremio add-on URLs that carry secrets. Reflection can reach in-memory objects. The CloudStream API even hands plugins the shared key/value store (`getKey`/`setKey`).
- **Files:** all app-private storage, plus whatever storage permissions the app holds.
- **Network, including the LAN:** the app's `INTERNET` permission covers the home network, so a plugin can reach the Household's Jellyfin server, router admin pages and other LAN devices, and can open a listening socket (Aniyomi's `HttpServer` does this by design).
- **Other plugins and the player:** the global registries (`APIHolder.allProviders`, `extractorApis`) can be enumerated or altered.
- **Code execution:** a plugin can load further code at runtime (`PathClassLoader` on any file it writes). Neither format's host prevents this.

Encryption at rest does not help against code that runs in the same process with the same key access. Only a process boundary does, which leads back to the isolation limits above.

## 4. A sandboxed JavaScript runtime

### Pure-JVM engines on Android

- **Rhino (MPL-2.0).** Rhino's Android notes say "minSdk is 26", "runs only in interpreted mode", and that some features (`JavaAdapter`) may be missing ([rhino `it-android/README.md`](https://github.com/mozilla/rhino/blob/e0fd905ebf/it-android/README.md)). The README states Java 17+ to run ([rhino `README.md`](https://github.com/mozilla/rhino/blob/e0fd905ebf/README.md)). The API-26 floor arrived with 1.9.0 ([CS `libs.versions.toml` L50](https://github.com/recloudstream/cloudstream/blob/a28e6468f5/gradle/libs.versions.toml#L50)); the PR that introduced `VarHandle` slot maps says these "are not available on older versions of Android" ([rhino #1785](https://github.com/mozilla/rhino/pull/1785)). **For `minSdk 25`, Rhino 1.8.1 is the usable line**, and CloudStream ships it on `minSdk 23`. Releases in the 1.8.x/1.7.x lines continued alongside 1.9 (1.8.1 published 2025-12-03; [rhino releases](https://github.com/mozilla/rhino/releases)).
- **Nashorn (GPL-2.0 + Classpath exception).** Standalone Nashorn targets "Java 11 and later" on the JVM ([openjdk/nashorn README](https://github.com/openjdk/nashorn/blob/main/README.md)). Nashorn compiles JS to JVM bytecode at runtime, and ART cannot define classes from JVM bytecode: `ClassLoader.defineClass` throws `UnsupportedOperationException("can't load this type of class file")` ([AOSP libcore `ClassLoader.java` L453, android-9.0.0_r1](https://android.googlesource.com/platform/libcore/+/refs/tags/android-9.0.0_r1/ojluni/src/main/java/java/lang/ClassLoader.java)). The same applies to Rhino's compiled mode, which is why Rhino runs interpreted on Android. Not usable.
- **GraalJS (UPL-1.0)** needs the GraalVM/Truffle runtime ([oracle/graaljs](https://github.com/oracle/graaljs)). Not usable on ART.
- **QuickJS, V8 (J2V8), Duktape and JavaScriptCore** are all native, so they are ruled out by "no native code" ([Choose the app stack, #8](https://github.com/MasonNight/celastrina/issues/8)).

**Rhino 1.8.x in interpreted mode is the only pure-JVM option for API 25.** Expect it to be slower than QuickJS, and expect gaps in modern ECMAScript support. Rhino publishes a compatibility table ([rhino README](https://github.com/mozilla/rhino/blob/e0fd905ebf/README.md)).

### Sandboxing Rhino

Rhino scripts can reach Java through `LiveConnect` (`Packages`, `java.*`) unless the embedder prevents it. Rhino's API for preventing it is `Context.initSafeStandardObjects()`, plus a `ClassShutter` that denies every class ([rhino `Context.java` L1100](https://github.com/mozilla/rhino/blob/e0fd905ebf/rhino/src/main/java/org/mozilla/javascript/Context.java#L1100), [`ClassShutter.java`](https://github.com/mozilla/rhino/blob/e0fd905ebf/rhino/src/main/java/org/mozilla/javascript/ClassShutter.java)). Its effectiveness on Rhino 1.8.1 on a device was not tested here. With Java access shut off, the script can reach only the host functions Celastrina injects. The capability surface is then explicit: a `fetch` that the host performs (and can restrict, for example by refusing private and LAN addresses), an HTML parser, crypto helpers, a timer, and a log. `Context.setInstructionObserverThreshold` / `observeInstructionCount` let the host enforce a CPU budget ([rhino `Context.java` L2440–2481](https://github.com/mozilla/rhino/blob/e0fd905ebf/rhino/src/main/java/org/mozilla/javascript/Context.java#L2440-L2481)). A JS Extension could also run in an `isolatedProcess`, because Rhino is just library code in Celastrina's own APK and the script text can be passed over Binder. This is the one format where the isolation preference from #9 is straightforward.

### How Zangetsu runs its JS sources

- **Engine:** `flutter_js` ^0.8.0, a QuickJS bridge on Android and JavaScriptCore on Apple ([ZAN `pubspec.yaml` L82–83](https://github.com/Spyou/Zangetsu/blob/48741e66aa/pubspec.yaml#L82-L83)). That is native, so it is not reusable by Celastrina as-is.
- **Threading:** on Android the engine runs on its own Dart isolate, because pumping the JS event loop on the UI isolate froze the screen ([ZAN `lib/core/provider/js_engine.dart` L22–60](https://github.com/Spyou/Zangetsu/blob/48741e66aa/lib/core/provider/js_engine.dart#L22-L60)).
- **Host capabilities:** the script talks back on four channels, `fetch`, `console`, `crypto` and `timer` (L12). `fetch` is performed by the host's Dio client, with a per-fetch ceiling of 25 s and a per-call ceiling of 25 s ([ZAN `provider_manager.dart` L108–130, L478–530](https://github.com/Spyou/Zangetsu/blob/48741e66aa/lib/core/provider/provider_manager.dart#L108-L130)). No URL allowlist or LAN block was found in that path.
- **Provider contract:** a script defines `getInfo()`, `getHomePage(rowId, page)`, `search(query, page)`, `getDetail(url)`, `getEpisodes(seriesUrl)` and `getVideoSources(episodeUrl)`. The host offers `fetch`, `extractVideo(embedUrl, opts)`, `htmlText`, `absUrl` and `console` ([ZAN `providers/_template.js`](https://github.com/Spyou/Zangetsu/blob/48741e66aa/providers/_template.js)). Shared "extractor" scripts resolve embeds ([ZAN `extractors/_template.js`](https://github.com/Spyou/Zangetsu/blob/48741e66aa/extractors/_template.js)). Like the other two formats, the contract is site-catalogue and title-search based.
- **The same app also hosts CloudStream and Aniyomi** in-process, as above (no `android:process` or `isolatedProcess` in [ZAN `AndroidManifest.xml`](https://github.com/Spyou/Zangetsu/blob/48741e66aa/android/app/src/main/AndroidManifest.xml)).

The catch for a JS format is that **no existing Extensions use it**. Households would be importing Extensions written for Celastrina's own JS contract, or a port of Zangetsu's. The format is the safest of the three, but it gives the least reach.

## 5. Mapping onto the Source contract (#9)

The contract: a **Title reference** goes in (kind; TMDB, IMDb and Wikidata IDs; title and year; season and episode in Catalogue numbering). **Streams** (Source, stable Stream ID, URL, delivery type; optional label, resolution, codec, languages, bitrate, size, headers, subtitles), **Not available**, or a typed **Failure** (`unreachable`, `auth_required`, `rate_limited`, `source_error`) come out. Each lookup has its own timeout of about 8 s ("Extensions may need longer, to be decided in the extension-format ticket").

| Contract part | CloudStream adapter | Aniyomi adapter |
|---|---|---|
| **Match the Title** | If `supportedSyncNames` contains `Imdb`, call `getLoadUrl(Imdb, tt…)` then `load(url)`, with no ambiguity. Otherwise `search("<title>")`, pick a `SearchResponse` by name, year and type, then `load(url)`. `LoadResponse.syncData` can then confirm by IMDb or TMDB ID where the provider fills it. | `getSearchAnime(1, "<title>", emptyFilters)`, pick by title (`SAnime` has no year field), then `getAnimeDetails` and `getEpisodeList`. No external IDs in the model. |
| **Episode** | Pick the `Episode` whose `season`/`episode` match. Fall back to list position. Anime seasons are often flat lists, so Catalogue numbering may not match. | `SEpisode.episode_number: Float`, with optional `getSeasonList`. Same numbering risk. |
| **Streams** | `loadLinks` streams `ExtractorLink`s to a callback. URL becomes URL. `type` maps to the delivery type: `VIDEO` to progressive, `M3U8` to HLS, `DASH` to DASH, and `TORRENT`/`MAGNET` are dropped. `quality` gives resolution (400 means unknown). `getAllHeaders()` gives headers, Referer included. `name`/`source` give the label. `SubtitleFile` callbacks give sidecar subtitles. | `getHosterList` then `getVideoList`, or `resolveVideo`. `videoUrl` is the URL; `resolution`, `bitrate`, `headers`, `subtitleTracks` and `audioTracks` map directly. Delivery type must be inferred from the URL or Content-Type. `isTorrent` extensions are skipped. |
| **Stable Stream ID** | Neither format gives one. Derive it, for example from a hash of the plugin, the matched page URL, the extractor name and the quality, because tokenised URLs change. | Same. Hash the extension, the episode URL, the hoster name and `videoTitle`. |
| **Not available** | `search` returns nothing that matches, or `loadLinks` returns `false` or emits no links. | Empty search, episode or video lists. |
| **Failure** | The API defines none, only exceptions. Map timeouts and `IOException` to `unreachable`, HTTP 429 to `rate_limited`, and everything else (parse errors, `NotImplementedError`, `NoClassDefFoundError`) to `source_error`. `auth_required` cannot be detected generically. | Same. `HttpException(code)` exists in the lib ([EXT `network/HttpException.kt`](https://github.com/aniyomiorg/extensions-lib/blob/971ce96e76/library/src/main/java/eu/kanade/tachiyomi/network/HttpException.kt)) and maps 401/403 to `auth_required`, which is a guess, and 429 to `rate_limited`. |
| **Per-Stream extras** | `getVideoInterceptor(link)` returns an OkHttp `Interceptor` for playback. Honouring it means playing through Media3's `OkHttpDataSource` with that interceptor, in-process. `extractorVerifierJob` is a keep-alive poll during playback. | `Video.headers` only, plus extensions that serve through a local `HttpServer` (the URL then points at localhost). |

**Title-search ambiguity.** All three formats are built around the plugin's own site catalogue: search by free text, then open a page. Only CloudStream's `getLoadUrl` offers a by-ID path, and only for providers that implement it. The adapter needs a matcher (normalised title, year where available, Movie vs Series), and it should treat "several equally good hits" as Not available or offer each as separate Streams, rather than guess. The Catalogue's alternative titles (TMDB/AniList) can be tried in turn.

**Lookup latency.** One lookup is at least two or three sequential network round trips (search, load, links), and often more when extractors follow embed chains. CloudStream allows 120 s by default and up to 8 min for `loadLinks`. Zangetsu caps a JS call at 25 s. The contract's 8 s will cut off many real plugins. A separate Extension budget of roughly 20–30 s, with Streams shown as they arrive (the contract already streams per Source) and the matched page URL cached per Title, would follow prior art. That is a judgement for the grilling ticket, not a measured number.

## Open questions for "Choose the Extension format and trust model"

1. CloudStream in-process with a warning (matches the stated preference, needs `library` + shims + dependency pinning) vs a Rhino JS format that can be isolated (safe, but has no existing Extensions)? Or both, with CloudStream behind a stronger warning?
2. Is a same-UID `:ext` process worth it for crash and timeout containment, given that it is not a security boundary?
3. Would Fire OS 6 (API 25) be dropped from Extension support if isolation requires `InMemoryDexClassLoader` (API 26+)?
4. Pin the CloudStream `library` version, and how to report plugins that fail to link (`NoClassDefFoundError` → `source_error` with a "needs a newer Celastrina" hint)?
5. Integrity: CloudStream offers only an unsigned SHA-256 from the same list. Should Celastrina pin a hash or key per imported repository, like Aniyomi's `signingKey`?
6. Should Celastrina honour `getVideoInterceptor` (in-process playback hooks), or play only plain URL + headers?
7. Extension lookup budget and title-matching rules (above).
