# Stremio add-on protocol: research for the Source contract

Researched 2026-11-23 against `Stremio/stremio-addon-sdk@master` (docs/ and src/), `Stremio/stremio-core@development` (the Rust client engine), and Kodi and Jellyfin primary sources. Links point at branch heads, so line anchors may drift. This note covers protocol design only. It does not catalogue or recommend any add-ons.

## Answer

- **It is a read-only REST/JSON protocol.** An add-on is an HTTPS server, or plain static JSON files, that serves `/manifest.json` and answers `GET /{resource}/{type}/{id}[/{extra}].json`. CORS must allow all origins. The client never runs add-on code. ([protocol.md][protocol], [SDK README][sdk-readme])
- **Resources:** `catalog`, `meta`, `stream`, `subtitles` and `addon_catalog` return data. `player` and `library` are newer *event* resources: the client pushes the user's watch and library activity to the add-on and ignores the response. ([protocol.md][protocol], [api/README.md][api-readme])
- **IDs are the join key.** Movies use IMDb `tt…` IDs. Series episodes use `tt…:season:episode`. Other namespaces use custom prefixes (e.g. `yt_id:`). The client routes a request only to add-ons whose `types` and `idPrefixes` match it. Cinemeta (IMDb-based, run by Stremio) is the default Catalogue, so a Stream-only add-on can just declare `idPrefixes: ["tt"]`. ([advanced.md#understanding-cinemeta][adv], [defineStreamHandler.md][streamh], [core request.rs][core-request])
- **Catalogue and Source are cleanly separable.** `catalog`/`meta` describe what exists. `stream` says how to play it. One escape hatch couples the two: a meta `video.streams` array that *suppresses* every other add-on's streams. ([meta.md][meta])
- **Errors and quality are weakly specified.** The protocol has no error schema. The SDK returns HTTP 500 `{"err":"handler error"}` or 404 `{"err":"not found"}`. Quality is free text in `name` ("usually used for stream quality"), and add-ons are asked to order streams best-first. Ranking across add-ons is not specified. ([SDK getRouter.js][router], [stream.md][stream], [defineStreamHandler.md][streamh])
- **Licensing is permissive.** The SDK and stremio-core are MIT ([SDK LICENSE][sdk-license], [core LICENSE][core-license]), so the protocol can be implemented from GPL-3.0 Celastrina.
- **Trust risks:** add-ons are remote servers, so there is no code-execution risk. The risks are privacy and legality. Every add-on whose filter matches learns the Household's IP and which Title they opened. Configurable add-ons carry secrets in the URL path. `infoHash`/NZB/archive Stream variants would turn the player into a P2P or Usenet client. Celastrina can refuse those variants without breaking the HTTP `url` path. ([stream.md][stream], [advanced.md#using-user-data-in-addons][adv-user], [manifest.md][manifest])

## 1. Manifest

Source: [manifest.md][manifest] unless noted.

- **Required fields:** `id` (dot-separated), `name`, `description`, `version` (semver), `resources`, `types`, `catalogs` (may be `[]`).
- **`resources`** takes strings or objects of the form `{ name, types, idPrefixes? }`. The object form overrides the top-level `types`/`idPrefixes` for that resource only. `idPrefixes` is ignored for `catalog`, because catalogs are always requested when declared.
- **`types`:** `movie`, `series`, `channel`, `tv` ([content.types.md][types]).
- **`idPrefixes`** (optional): the add-on is called only for IDs that start with one of these prefixes. The client enforces this in `Manifest::is_resource_supported` ([core manifest.rs][core-manifest]).
- **`catalogs[]`:** `{ type, id, name, extra? }`. Each `extra` entry is `{ name, isRequired, options, optionsLimit }`.
  - Known extras are `search`, `genre`, `skip` (pages of 100; fewer than 100 results means end of catalog) and `date` (Native EPG) ([defineCatalogHandler.md][cath]).
  - An extra with `isRequired: true` makes the catalog search-only, for example.
  - The client sends Board requests only to catalogs with no required extras, and Search requests only to catalogs that declare `search` ([api/README.md][api-readme]).
- **`addonCatalogs[]`** lets one add-on list other add-ons. Each response item is `{ transportName: "http", transportUrl, manifest }` ([addon_catalog.md][addoncat]).
- **`config[]`:** `{ key, type: text|number|password|checkbox|select, default, title, options, required }`. Values reach handlers as strings ([manifest.md#user-data][manifest]).
- **`behaviorHints`:**
  - `adult`
  - `p2p`: "may reveal the user's IP to other streaming parties"
  - `configurable`, `configurationRequired`
  - `epgProvider`
- Other fields: `background`, `logo`, `contactEmail` (used for the Report button).

## 2. Resources and response shapes

| Resource | URL | Response |
|---|---|---|
| catalog | `/catalog/{type}/{catalogId}[/{extra}].json` | `{ metas: MetaPreview[] }` (EPG: `{ metasDetailed }`) |
| meta | `/meta/{type}/{id}.json` | `{ meta: Meta }` |
| stream | `/stream/{type}/{videoId}.json` | `{ streams: Stream[] }` |
| subtitles | `/subtitles/{type}/{videoId}/{extra}.json` | `{ subtitles: Subtitle[] }` |
| addon_catalog | `/addon_catalog/{type}/{id}.json` | `{ addons: [...] }` |
| player / library | `/player/{type}/{videoId}/action=start&currentTime=….json`, `/library/{type}/{id}/action=libraryAdd.json` | ignored (events) |

Sources: [protocol.md][protocol], [api/README.md][api-readme], and the core `ResourceResponse` enum, which also accepts `metasDetailed` and `addons` ([core response.rs][core-response]).

- **Meta:**
  - Required: `id`, `type`, `name`.
  - Optional: `poster`/`posterShape`, `background`, `logo`, `description`, `releaseInfo`, `released`, `runtime`, `genres`, `cast`/`director` (deprecated in favour of `links`), `imdbRating`, `trailers`.
  - `videos[]` holds episodes: `{ id, title, released, season, episode, thumbnail, overview, streams?, available? }`.
  - With no `videos`, the meta has one video whose ID equals the meta ID ([meta.md][meta]).
  - `behaviorHints.defaultVideoId` opens a Title directly at a given video ([meta.md][meta]).
- **Meta Preview** (catalog item) is a cut-down Meta that requires `poster` ([meta.md#meta-preview-object][meta]).
- **Subtitles:**
  - Fields: `{ id, url, lang (ISO 639-2), label? }`.
  - Subtitle requests carry `videoHash` (OpenSubtitles hash), `videoSize` and `filename` as extras. These come from the Stream's `behaviorHints` ([subtitles.md][subs], [defineSubtitlesHandler.md][subh]).
- **Client flow:**
  1. Board/Discover loads every matching catalog from every add-on.
  2. Opening a Title fetches `meta` from every matching add-on.
  3. Picking a video, or opening any single-video Title, fetches `stream` from every matching add-on ([api/README.md][api-readme]).

## 3. ID scheme

- **Movie:** the Meta ID is also the Video ID, e.g. `tt0111161`.
- **Series:** the Video ID is `tt3107288:1:1` ([advanced.md#understanding-cinemeta][adv], [defineStreamHandler.md][streamh]).
- **Live TV:** the stream `id` is the channel ID, not the programme ID ([defineStreamHandler.md][streamh]).
- **Custom namespaces** use a prefix (e.g. `yt_id:UC…`). An add-on that uses a non-`tt` prefix must also serve `meta` for those IDs. With `tt`, Cinemeta already serves the meta ([protocol.md][protocol]).
- **Cinemeta** is hard-coded in the client as `https://v3-cinemeta.strem.io/manifest.json` ([core constants.rs][core-constants]).
- **Gap:** the protocol has no cross-namespace mapping (IMDb, TMDB, Kitsu). The client does name `tt`, `tmdb` and `kitsu` as "supported" prefixes for its own likes feature ([core constants.rs][core-constants]). Equivalence between IDs is the add-on author's problem.

## 4. Transport

- **URL shape:** the client takes the transport URL, which must end in `/manifest.json`, and replaces that suffix with `/{resource}/{type}/{id}.json`. When extras exist it uses `/{resource}/{type}/{id}/{k=v&k2=v2}.json` instead. Each component is percent-encoded ([core http_transport.rs][core-http]). Because only the suffix is replaced, any path prefix, such as a config segment, is preserved on every request.
- **CORS:** "each route, including `/manifest.json`, must serve CORS headers that allow all origins" ([protocol.md][protocol]). The SDK applies `cors()` globally ([router][router]).
- **HTTPS:** required for add-on URLs, except `127.0.0.1` ([SDK README][sdk-readme]).
- **Caching:** a handler may return `cacheMaxAge`, `staleRevalidate` and `staleError` (seconds). The SDK maps these to `Cache-Control: max-age, stale-while-revalidate, stale-if-error, public`. `serveHTTP({cacheMaxAge})` sets a global default. Errors strip the cache header, and `player`/`library` responses get `no-store` ([defineStreamHandler.md][streamh], [router][router], [serveHTTP.js][serve]). These are ordinary HTTP cache semantics, not protocol-level fields on the wire.
- **Hosting:**
  - A folder of static JSON works, e.g. on GitHub Pages ([protocol.md][protocol]).
  - Otherwise any Node host. BeamUp is Stremio's recommended host ([deploying/README.md][deploy], [SDK README][sdk-readme]).
  - Add-ons can be listed in the public collection via `publishToCentral` ([SDK README][sdk-readme]).
- **Legacy and other transports:**
  - `…/stremio/v1` URLs select the legacy v1/v2 transport. It is JSON-RPC 2.0 (`meta.get`, `stream.find`, `subtitles.find`) sent as base64 in `/q.json?b=…` ([protocol.md][protocol], [core legacy/mod.rs][core-legacy], [core constants.rs][core-constants]).
  - The old `Stremio/stremio-addons` repo is archived and marked "OBSOLETE" ([stremio-addons][legacy-repo]).
  - `ipfs://`/`ipns://` transports are described in protocol.md. I did not confirm that current stremio-core implements them; core has an `unsupported_transport.rs` ([core tree][core-tree]).
- **User config:** the SDK serves the routes `/:config?/manifest.json` and `/:config?/{resource}/…`. `config` is a URL-encoded JSON object in the first path segment ([router][router]). Hand-rolled add-ons put tokens in the same place, e.g. `https://host/<apiToken>/manifest.json` ([advanced.md#using-user-data-in-addons][adv-user]). Installation is via a `stremio://host/<data>/manifest.json` deep link ([advanced.md#creating-addon-configuration-pages][adv-user]).

## 5. Stream object

Sources: [stream.md][stream] and the core types `Stream`, `StreamSource` and `StreamBehaviorHints` ([core stream.rs][core-stream]).

**Exactly one source variant** (core deserialises an untagged enum):

- `url`: http(s)/ftp(s)/rtmp.
- `ytId`: YouTube ID, played in the built-in YouTube player.
- `infoHash` + `fileIdx?` + `sources`/`announce` (trackers, `dht:`): torrent. Without `fileIdx`, the largest file is chosen.
- `nzbUrl` + `servers` (`nntps://user:pass@host:port/conns`): Usenet.
- `rarUrls`/`zipUrls`/`7zipUrls`/`tgzUrls`/`tarUrls` + `fileIdx`/`fileMustInclude`: archives.
- `externalUrl`: opens in a browser or deep link.
- **Undocumented variants in core only:** `playerFrameUrl`, and per-platform `androidTvUrl`/`tizenUrl`/`webosUrl` inside the external variant ([core stream.rs][core-stream]).

**Descriptive fields:**

- `name`: "usually used for stream quality".
- `description`: core accepts `title` as an alias; `title` is being deprecated.
- `thumbnail`: core only.
- `subtitles[]`.

**`behaviorHints`:**

- `notWebReady`: set when the URL is not https or not MP4. Required when `proxyHeaders` is set.
- `bingeGroup`: auto-selects the next episode's Stream that has the same group string.
- `proxyHeaders {request, response}`.
- `countryWhitelist`: lowercase ISO 3166-1 alpha-3 codes.
- `videoHash`, `videoSize`, `filename`: fed to subtitle add-ons.
- Core keeps any unknown hints in a flattened `other` map ([core stream.rs][core-stream]).

## 6. Errors, quality and ordering

- **No error object exists in the protocol.** The SDK returns HTTP 404 `{err:"not found"}` when no handler matches and HTTP 500 `{err:"handler error"}` when a handler rejects. Neither is cached ([router][router]). Not-found and failure cannot be told apart beyond the status code, and there is no "retry-after", "geo-blocked" or "auth expired" code.
- **The client collapses failures into three buckets:** `EmptyContent` (an empty array is treated as an error state), `UnexpectedResponse(serde msg)` and `Env(fetch error)` ([core resource_loadable.rs][core-loadable]).
- **Quality has no structured fields:** no resolution, bitrate, codec, HDR, audio language or size, apart from `videoSize`, which exists for subtitle matching ([stream.md][stream]). Add-ons are told to return streams "ordered from highest to lowest quality" ([defineStreamHandler.md][streamh]).
- **Ordering across add-ons:** core plans one request per matching add-on in installed-add-on order. It stores results per add-on (`Vec<ResourceLoadable<Vec<Stream>>>`) and does not merge them into one ranked list ([core request.rs][core-request], [core meta_details.rs][core-meta-details]). I did not confirm how stremio-web renders the groups. The only cross-episode continuity signal is `bingeGroup`, plus core's "last used stream" lookup ([core meta_details.rs][core-meta-details]).
- **Failover inside a Stream:** none. A Stream is one locator, and nothing in the protocol carries health, liveness or fallback.

## 7. Gains and losses for Celastrina

**Gains**

- **Ecosystem compatibility.** Any existing HTTP add-on, the static-JSON pattern, and SDKs in Node, Python, Go, Rust, PHP, C# and Java ([protocol.md][protocol], [SDK README][sdk-readme]). A Household could self-host a Source as a folder of JSON.
- **Catalogue/Source split matches our vocabulary.** `catalog`/`meta` act as Catalogue providers and `stream` acts as a Source, joined by Title/episode ID. `idPrefixes` gives cheap routing, so a Source is only asked about Titles it can serve ([api/README.md][api-readme]).
- **Small attack surface.** The client makes JSON GETs and never executes add-on code ([SDK README][sdk-readme]). This contrasts sharply with Kodi (below).
- **MIT licensing.** The SDK and stremio-core are MIT, so a compatible client, or reuse of core's types, is compatible with GPL-3.0 ([SDK LICENSE][sdk-license], [core LICENSE][core-license]).
- **HTTP-native caching** (`stale-if-error` helps with flaky Sources) and `bingeGroup` for next-episode continuity.

**Losses / risks**

- **Weak Failover inputs.** No error schema, no structured quality, no health, no reason codes (§6). Celastrina would have to derive quality by parsing free-text `name`/`description`, and could only learn of a failure by trying playback.
- **Default Catalogue dependence.** Cinemeta is IMDb-keyed and hosted by Stremio ([core constants.rs][core-constants]). Relying on it binds the Catalogue to a third-party service and to IMDb IDs. Internet Archive items have no IMDb ID by default, so they would need a custom prefix plus our own `meta`.
- **Privacy leaks to each matching add-on:**
  - Client IP and the Title or episode ID on every Title open. Every matching add-on is queried in parallel ([api/README.md][api-readme]).
  - With `player`/`library`, the full watch history ("Users must trust such addons with their watch activity", [protocol.md][protocol]).
  - `proxyHeaders` and `url` let an add-on direct the player to arbitrary hosts.
- **Secrets in URLs.** Config sits in the transport URL path ([router][router], [adv-user]). Celastrina would have to store each transport URL as a secret, redact it in logs and crash reports, and never sync it in plaintext.
- **Legal exposure from non-HTTP variants.** `infoHash` (BitTorrent, where the `p2p` hint warns of IP exposure to peers, [manifest.md][manifest]) and `nzbUrl` + credentials would make a "neutral player" an active P2P or Usenet participant. Analysis, not sourced: accepting only `url` (and maybe `externalUrl`) and rejecting the rest at parse time keeps Celastrina an HTTP player.
- **`video.streams` exclusivity.** A meta add-on can silence all Sources for a video ([meta.md][meta]). A Catalogue provider must not be able to override Sources.
- **Spec drift.** Docs and core disagree in places (e.g. `playerFrameUrl` and per-platform URLs exist only in core). Several docs say "will soon be deprecated" with no versioning. The manifest has no protocol-version field ([manifest.md][manifest]).

## 8. Contrast: Kodi and Jellyfin

**Kodi.** A Kodi add-on is a package with an `addon.xml` manifest that imports `xbmc.python` and declares an extension point such as `xbmc.python.pluginsource` with a `library="addon.py"` entry. The add-on is Python code that Kodi runs itself ([sample addon.xml in xbmc/repo-plugins][kodi-addon]). Add-ons are distributed through repositories. The official ones take submissions as GitHub pull requests ([repo-plugins CONTRIBUTING][kodi-contrib]). Installing from elsewhere requires the "Unknown sources" setting, which is off by default ([settings.xml][kodi-settings]). Its warning reads: "Add-ons will be given access to personal data stored on this device… you are solely responsible…" ([strings.po][kodi-strings]). This is the opposite trust model to Stremio's: arbitrary local code execution in exchange for maximum flexibility. kodi.wiki returned HTTP 403 to automated fetches, so these claims rest on xbmc GitHub sources.

**Jellyfin.** Jellyfin plugins are optional server extensions covering authentication, channels, metadata, Live TV and notifications. They are installed from a catalog of official and third-party repository manifest URLs, or dropped into the plugin directory and loaded on server restart ([Jellyfin plugins docs][jf-plugins]). They are compiled .NET assemblies, built against `Jellyfin.Controller`/`Jellyfin.Model` and targeting net9.0 in the template ([plugin template README][jf-template], [template csproj][jf-csproj]). The plugins page has no security guidance on third-party repositories ([Jellyfin plugins docs][jf-plugins]). Like Kodi, this means running third-party code, but on a household server rather than the TV, and it does not apply to a standalone Fire TV client.

## 9. Open questions for the Source contract decision

1. **Wire compatibility or concepts only?** Accepting Stremio manifests verbatim buys the ecosystem. Defining a Celastrina contract with an adapter keeps that option open while adding structured quality and errors.
2. **Which Stream variants are allowed?** The proposed allowlist is `url` only, possibly plus `externalUrl`. Decide whether rejected variants are dropped silently or surfaced to the Household as "unsupported Stream".
3. **Structured quality and errors:** do we require them (e.g. `resolution`, `bitrateKbps`, `codec`, `container`, `audioLangs`, and typed errors like `not_found`/`geo_blocked`/`auth_required`/`rate_limited`) as a Celastrina extension under `behaviorHints`? Core preserves unknown hints ([core stream.rs][core-stream]).
4. **Catalogue authority and ID space:** do we use IMDb `tt` as canonical (Cinemeta-compatible), our own IDs with an ID map, or both? How does an Internet Archive Title get a stable ID?
5. **Should a Catalogue provider be allowed to return Streams?** That is, do we honour or ignore `video.streams`?
6. **Event resources:** do we ever send `player`/`library` events to Household Sources? The proposed default is never.
7. **Secret handling:** where are configured transport URLs stored, are they synced between devices, and how are they redacted?
8. **Privacy fan-out:** do we query every matching Source in parallel, as Stremio does, or only Sources the Household has enabled for that Category or Title?
9. **Failover ordering:** what is the rank function across Sources (Household priority, then parsed quality, then past success rate), and what counts as "failed" (HTTP error, playback error, startup timeout)?
10. **Unverified items:** IPFS transport support in current core; stremio-web's rendering and order of per-add-on stream groups; whether core enforces `countryWhitelist`.

## Sources

[protocol]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
[manifest]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/manifest.md
[meta]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/meta.md
[stream]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/stream.md
[subs]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/subtitles.md
[addoncat]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/addon_catalog.md
[types]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/content.types.md
[api-readme]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/README.md
[cath]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineCatalogHandler.md
[streamh]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineStreamHandler.md
[subh]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineSubtitlesHandler.md
[adv]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/advanced.md#understanding-cinemeta
[adv-user]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/advanced.md#using-user-data-in-addons
[deploy]: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/deploying/README.md
[sdk-readme]: https://github.com/Stremio/stremio-addon-sdk/blob/master/README.md
[sdk-license]: https://github.com/Stremio/stremio-addon-sdk/blob/master/LICENSE.md
[router]: https://github.com/Stremio/stremio-addon-sdk/blob/master/src/getRouter.js
[serve]: https://github.com/Stremio/stremio-addon-sdk/blob/master/src/serveHTTP.js
[legacy-repo]: https://github.com/Stremio/stremio-addons
[core-license]: https://github.com/Stremio/stremio-core/blob/development/LICENSE.md
[core-tree]: https://github.com/Stremio/stremio-core/tree/development/src/addon_transport
[core-http]: https://github.com/Stremio/stremio-core/blob/development/src/addon_transport/http_transport/http_transport.rs#L38-L101
[core-legacy]: https://github.com/Stremio/stremio-core/blob/development/src/addon_transport/http_transport/legacy/mod.rs
[core-constants]: https://github.com/Stremio/stremio-core/blob/development/src/constants.rs
[core-stream]: https://github.com/Stremio/stremio-core/blob/development/src/types/resource/stream.rs#L744-L990
[core-manifest]: https://github.com/Stremio/stremio-core/blob/development/src/types/addon/manifest.rs#L108
[core-request]: https://github.com/Stremio/stremio-core/blob/development/src/types/addon/request.rs#L359-L369
[core-response]: https://github.com/Stremio/stremio-core/blob/development/src/types/addon/response.rs#L86-L123
[core-loadable]: https://github.com/Stremio/stremio-core/blob/development/src/models/common/resource_loadable.rs#L14-L18
[core-meta-details]: https://github.com/Stremio/stremio-core/blob/development/src/models/meta_details.rs#L895-L920
[kodi-addon]: https://github.com/xbmc/repo-plugins/blob/omega/plugin.video.arteplussept/addon.xml
[kodi-contrib]: https://github.com/xbmc/repo-plugins/blob/master/CONTRIBUTING.md
[kodi-settings]: https://github.com/xbmc/xbmc/blob/master/system/settings/settings.xml
[kodi-strings]: https://github.com/xbmc/xbmc/blob/master/addons/resource.language.en_gb/resources/strings.po
[jf-plugins]: https://jellyfin.org/docs/general/server/plugins/
[jf-template]: https://github.com/jellyfin/jellyfin-plugin-template/blob/master/README.md
[jf-csproj]: https://github.com/jellyfin/jellyfin-plugin-template/blob/master/Jellyfin.Plugin.Template/Jellyfin.Plugin.Template.csproj

- Stremio add-on protocol: <https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md>
- Manifest / Meta / Stream / Subtitles / Addon catalog / Content types: <https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api/responses>
- Handler docs (catalog, stream, subtitles): <https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api/requests>
- Advanced usage (Cinemeta, user data, config pages): <https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/advanced.md>
- SDK README, LICENSE (MIT), `src/getRouter.js`, `src/serveHTTP.js`: <https://github.com/Stremio/stremio-addon-sdk>
- Archived legacy SDK: <https://github.com/Stremio/stremio-addons>
- stremio-core (MIT): HTTP and legacy transports, constants, Stream types, manifest filtering, request planning, ResourceError: <https://github.com/Stremio/stremio-core/tree/development/src>
- Kodi: sample `addon.xml`, repo-plugins CONTRIBUTING, `settings.xml`, `strings.po`: <https://github.com/xbmc/repo-plugins>, <https://github.com/xbmc/xbmc>
- Jellyfin: plugins docs <https://jellyfin.org/docs/general/server/plugins/>, plugin template <https://github.com/jellyfin/jellyfin-plugin-template>
