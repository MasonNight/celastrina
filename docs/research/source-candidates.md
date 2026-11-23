# Source candidates: where Celastrina can get Streams

Researched 2026-11-23. Scope: only Sources whose content is legally licensed, public domain, or the Household's own media. Scrapers and unlicensed sites are out of scope and not covered here.

Live checks against archive.org, commons.wikimedia.org, wikidata.org and loc.gov were made on 2026-11-23 with a handful of anonymous read-only requests. Lines marked "(live check)" report what those requests returned. They are not documented guarantees.

## Answer

- **Internet Archive works as the v1 Source.** Search uses the Advanced Search API or the cursor-based Scrape API with Lucene-style queries such as `collection:feature_films AND mediatype:movies`. Resolution uses `https://archive.org/metadata/{identifier}`. Playback uses `https://archive.org/download/{identifier}/{file}`, which 302-redirects to a datanode and supports HTTP byte ranges (live check). No API key is needed for reads.
- **Pick the `h.264` MP4 derivative first.** These are `.mp4` files with `"source": "derivative"`, and Media3 plus every Fire TV can play them. Next try original MP4/MKV files, which play if the codecs inside are H.264, HEVC, VP9 or MPEG‑4 ASP. Skip `Ogg Video` (`.ogv`, Theora): Media3's Ogg support covers audio only, and Fire TV lists no Theora decoder.
- **Matching IA items to Catalogue Titles is the weak point.** Few IA items carry a machine-readable IMDb ID; titles are free text and sometimes wrong. The best bridge is **Wikidata**. Property P724 holds an Internet Archive ID, alongside P345 (IMDb), P4947 (TMDB) and P6216 (copyright status). About 5,100 Wikidata films with P724 also have a TMDB ID (live check). Celastrina can therefore go from TMDB/IMDb to Wikidata to a list of IA identifiers.
- **Failover is possible on IA.** A popular public-domain Movie often has several IA items (Night of the Living Dead has at least 4 per Wikidata), and each item usually has 2–4 video files. Each item and file can be a Stream.
- **Rights are the uploader's claim. IA does not guarantee them.** Restrict v1 to curated collections and items whose `licenseurl` is a Public Domain Mark or CC licence, and/or where Wikidata P6216 says public domain. Large collections such as `anime` (41k items) and `classic_tv` include items with no rights statement.
- **Jellyfin, Emby and Plex are the viable Household-added Sources.** The Household brings its own server and credentials. All three expose TMDB/IMDb/TVDB provider IDs, several versions per Title, and direct-file or HLS Streams without DRM. Plex now charges for remote playback.
- **Two more public-domain Sources are viable later:** Wikimedia Commons (VP9 WebM, MPEG‑4 and DASH via the MediaWiki API; linked from Wikidata P10) and the Library of Congress (`?fo=json` API returning an MP4 plus an HLS `.m3u8` per item).
- **Commercial free and ad-supported services (Tubi, Pluto TV, Kanopy, Hoopla, Freevee, Crackle, YouTube) offer no third-party Stream API.** YouTube's policies forbid extracting Streams. Pluto's terms forbid unauthorised automated access. Hoopla's API is a licensed partner API for library discovery, not a Stream source. Freevee no longer exists.

---

## 1. Internet Archive (v1 Source)

### 1.1 Finding Movies and Series (discovery)

**Endpoints**

| API | Endpoint | Notes |
|---|---|---|
| Advanced Search | `GET https://archive.org/advancedsearch.php?q=…&fl[]=…&sort[]=…&rows=…&page=…&output=json` | Pages by number. "We limit the number of sorted paged results returnable to 10,000." ([aboutsearch](https://archive.org/help/aboutsearch.htm)) |
| Scrape API | `GET/POST https://archive.org/services/search/v1/scrape?q=…&fields=…&sorts=…&count=…&cursor=…` | Cursor-based, so it has no 10k ceiling. `count` minimum is 100. `total_only=true` returns only the count. The total covers only results from the cursor's position onward, and completeness is not guaranteed while items change ([aboutsearch](https://archive.org/help/aboutsearch.htm)). Responses contain `items`, `count`, `cursor` and `total` (live check). |

The official `internetarchive` Python library sends all non-paged, non-full-text searches to the Scrape endpoint. It loops while the response contains a `cursor` and uses `total_only=true` for counts ([search.py](https://github.com/jjjake/internetarchive/blob/master/internetarchive/search.py)).

**Query syntax.** Lucene-like `field:value`, `AND`/`OR`, quoted phrases and `*` wildcards (e.g. `licenseurl:*publicdomain*`, `external-identifier:*`) ([aboutsearch](https://archive.org/help/aboutsearch.htm); wildcards confirmed by live check). The `format` field is searchable, e.g. `format:h.264` or `format:"512Kb MPEG4"` (live check).

**Collection identifiers** (live check, `numFound` on 2026-11-23):

| Identifier | Title | Items | Relevance |
|---|---|---|---|
| `feature_films` | Feature Films | 29,694 | Main Movie pool |
| `moviesandfilms` | (parent movies collection) | 113,654 | Broader, noisier |
| `silent_films` | Silent films | 3,525 | Movies |
| `Comedy_Films`, `SciFi_Horror` | genre sub-collections | 1,584 / 949 | Category hints |
| `classic_tv` | Classic TV | 11,452 | Series episodes; also contains commercials and educational TV |
| `classic_tv_1950s` (and other decade sub-collections such as `classic_tv_1960s`) | | 3,299 | Series |
| `animationandcartoons` | Animation & Cartoons | 16,480 | Cartoons Category |
| `classic_cartoons` | | 81 | Small |
| `anime` | "The Anime Cascade" | 41,435 | Anime Category; only 1,828 have a `licenseurl`, so the rest carry high rights risk |
| `opensource_movies`, `television` | community uploads / TV | 2.7M / 818k | Too noisy for a Catalogue Source |

Collections that do **not** exist as identifiers: `animation`, `film_noir`, `animation_shorts`. Each returned 0 items (live check).

**Useful search fields:** `identifier`, `title`, `year`, `date`, `mediatype`, `collection`, `subject`, `description`, `licenseurl`, `external-identifier`, `imdb`, `downloads`, `format` (live check).

Example:

```
https://archive.org/advancedsearch.php?q=collection:feature_films AND mediatype:movies
  &fl[]=identifier&fl[]=title&fl[]=year&fl[]=licenseurl&fl[]=external-identifier
  &sort[]=downloads desc&rows=50&output=json
```

### 1.2 Resolving an item to playable files

- `GET https://archive.org/metadata/{identifier}` returns the full record. Top-level keys include `created`, `d1`, `d2`, `dir`, `files`, `files_count`, `item_last_updated`, `item_size`, `metadata`, `server`, `uniq` and `workable_servers` ([md-read](https://archive.org/developers/md-read.html)). The live check also returned `alternate_locations` and `solo`.
- Sub-paths read parts of a record, e.g. `/metadata/{id}/files`, `/metadata/{id}/metadata/title` and `/files?start=&count=` for slicing ([md-read](https://archive.org/developers/md-read.html); `/metadata/{id}/metadata/title` confirmed by live check).
- A nonexistent identifier returns `{}`. `extended_err=1` adds error codes such as `102` (unavailable) and `104` (deleted) ([md-read](https://archive.org/developers/md-read.html)).
- **File entries** carry `name`, `source` (`original` / `derivative` / `metadata`), `format`, `original` (for a derivative, the file it came from), `size`, `md5`, `mtime`, and for video `length` (seconds), `height` and `width` ([md-read](https://archive.org/developers/md-read.html); `original`, `length`, `height` and `width` from live check).

Example `files` list for `sex_madness` (live check, trimmed):

| name | format | source | height | length (s) |
|---|---|---|---|---|
| `sex_madness.mpeg` | MPEG2 | original | 720 | 3117 |
| `sex_madness.mp4` | h.264 | derivative | 480 | 3117 |
| `sex_madness_512kb.mp4` | 512Kb MPEG4 | derivative | 240 | 3117 |
| `sex_madness.ogv` | Ogg Video | derivative | 304 | 3117 |
| `sex_madness.asr.srt` | SubRip | original | – | – |

**Download URL.** `https://archive.org/download/{identifier}/{url-encoded file name}`. The live check returned `302 Found` with `Location: https://dn800304.us.archive.org/0/items/sex_madness/sex_madness.mp4`, `Content-Type: video/mp4` and `Accept-Ranges: bytes`. A repeated request was redirected to a **different** datanode (`dn720001.ca.archive.org`). A `Range: bytes=0-1023` request to it returned `206 Partial Content` with `Content-Range: bytes 0-1023/324928028`. So seeking works, and the redirect target is not stable, which means the app should not cache it. Media3's default HTTP data source follows the redirect.

**Subtitles.** Many items carry auto-generated `ASR` captions as `.asr.srt` (SubRip), which Media3 can side-load ([Media3 formats](https://developer.android.com/media/media3/exoplayer/supported-formats)).

### 1.3 Which IA formats play on Media3 / Fire TV

Media3 support depends on two layers: the container, which Media3 parses, and the codec inside, which uses the device's `MediaCodec` decoders unless an extension is bundled ([Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats)). Fire TV decoders are from the [Fire TV Streaming Media Player specs](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html). Those specs list H.264, H.265/HEVC, VP9 (and AV1 on newer models), MPEG‑2 hardware decoding up to 1080p60, and MPEG‑4 (Simple/Advanced Simple Profile) up to 1080p. Vorbis is listed only in Ogg/Matroska as an audio codec, and no Theora decoder is listed.

| IA `format` | Typical file | Codec (typical) | Plays on Fire TV with Media3? |
|---|---|---|---|
| `h.264` (derivative) | `{name}.mp4` | H.264 + AAC, 480p since 2011 ([IA blog 2011](https://blog.archive.org/2011/06/04/improved-h-264-derivatives/)) | **Yes – first choice** |
| `h.264 IA`, `h.264 HD`, `h.264 720P` | `.ia.mp4` etc. | H.264 ([IA file definitions](https://help.archive.org/help/files-formats-and-derivatives-file-definitions-2/)) | Yes |
| `512Kb MPEG4` (derivative) | `_512kb.mp4` | Low-res 240p MP4 ([file definitions](https://help.archive.org/help/files-formats-and-derivatives-file-definitions-2/)); codec not stated (unverified) | Yes (MP4 container; H.264 or MPEG‑4 ASP, both decodable) – low-quality backup Stream |
| `MPEG4` (original) | `.mp4`, `.m4v`, `.mpeg4` | Whatever was uploaded | Usually; codec not known until probed |
| `Matroska` (original) | `.mkv` | Uploader's choice | Container yes; codec depends on contents. Items observed with an MKV original also had an `h.264` derivative (live check) |
| `MPEG2` (original) | `.mpeg`, `.mpg` | MPEG‑2 video + MP2 audio (typical) | Container (MPEG‑PS) yes; video decodable. MP2 audio is not in the Fire TV audio list, so it may need the Media3 FFmpeg extension, which decodes MP1/MP2/MP3 (unverified on device). Prefer the derivative |
| `Cinepack` / DivX / Xvid (original) | `.avi` | Cinepak / MPEG‑4 ASP | Media3 ships an `AviExtractor` ([source](https://github.com/androidx/media/blob/release/libraries/extractor/src/main/java/androidx/media3/extractor/avi/AviExtractor.java)) but the supported-formats page does not list AVI. ASP decodes, Cinepak does not. Prefer the derivative |
| `Ogg Video` (derivative) | `.ogv` | Theora + Vorbis | **No.** Media3's Ogg covers Vorbis/Opus/FLAC audio only, and Fire TV lists no Theora |
| `QuickTime`, `Windows Media` | `.mov`, `.wmv` | varies | `.mov` usually works via the MP4 extractor (unverified). `.wmv` is not supported |

Not every item has an `h.264` derivative. In `feature_films`, 17,182 of 29,694 items match `format:h.264`, and the item `bm86fgh443` has only an `MPEG4` original plus `.ogv` (live check). Stream selection should rank files by format, not assume one exists.

**Suggested Stream ranking within an item:** `h.264 HD`/`720P` > `h.264` > `MPEG4` original (`.mp4`) > `Matroska` original > `512Kb MPEG4` > other originals. Never use `.ogv`.

### 1.4 Reliability, limits and terms

- **Rate limits.** IA publishes no numeric limits. Its [Bots, LLMs and Automated Access](https://archive.org/developers/bots.html) page asks clients to add delays for bulk work, honour `429 Too Many Requests` and `Retry-After`, prefer batch operations, and cache responses.
- **User-Agent.** "All automated requests to archive.org must include a descriptive User-Agent header" naming the tool and version ([bots.html](https://archive.org/developers/bots.html)). Celastrina should send e.g. `Celastrina/<version> (+<repo URL>)`.
- **Terms of Use.** IA's [Rights](https://help.archive.org/help/rights/) page and Terms say collections are used "at their own risk" and that users must keep their use non-infringing. IA's terms were last revised in Dec 2014, when an explicit non-commercial clause was removed ([IA blog](https://blog.archive.org/2014/12/30/update-to-terms-of-use/)). archive.org/about/terms.php could not be fetched directly in this session, so the current wording is unverified.
- **2024 outage.** IA's own [services update of 2024‑10‑21](https://blog.archive.org/2024/10/21/internet-archive-services-update-2024-10-21/) describes "cyberattacks on October 9": a DDoS, plus a data exposure in which archive.org email addresses and encrypted passwords were published, and patrons were emailed via a third-party helpdesk system. The Wayback Machine returned on Oct 13 and archive.org came back read-only on Oct 21, with uploads, borrowing and reviews still unavailable. The post does not mention the APIs. Lesson: IA has no SLA and can be offline for **days**. A second Source is the only real mitigation, since Failover across IA items does not help when IA itself is down.
- **Per-item availability.** Metadata exposes `workable_servers`, `alternate_locations` and `solo` (live check). Download redirects pick a datanode per request, as observed above. `errcode 102` signals an unavailable item ([md-read](https://archive.org/developers/md-read.html)).

### 1.5 Matching an IA item to a Catalogue Title

Identifiers present in IA metadata (live check counts within `feature_films`, 29,694 items):

| Field | Example | Coverage | Reliability |
|---|---|---|---|
| `external-identifier` | `urn:imdb:tt0005812` | 2,114 items (≈7%); `classic_tv`: 0 | Good when present (structured URN) |
| `imdb` | `https://www.imdb.com/title/tt0041182/?ref_=fn_tt_tt_1` | 873 items | Usable after extracting `tt\d+`; free-text URL |
| IMDb link inside `description` | `sex_madness` links tt0147467 | ~30 items match `description:imdb.com` | Needs HTML parsing; unreliable |
| `title` | `"CUKESIM's New Job! ( Charles Chaplin-1915)"` | all | Free text, sometimes vandalised or decorated (the most-downloaded `feature_films` item has this title) |
| `year` / `date` | `1938` | `year` on 18,966 items (≈64%) | Often the original release year but not guaranteed |
| `director`, `publisher`, `credits`, `runtime`, `length` (file) | | varies | Helpful for disambiguation; file `length` can be compared with TMDB runtime |

**Wikidata as the bridge** (live check):
- Film items carry P345 (IMDb ID), P4947 (TMDB movie ID), P724 (Internet Archive ID), P10 (Commons video file) and P6216 (copyright status).
- Example: Night of the Living Dead (1968) is Q623051. It has P345 `tt0063350`, P4947 `10331`, P724 = `night_of_the_living_dead`, `Night.Of.The.Living.Dead_1080p`, `night_of_the_living_dead_dvd` and `NightOfTheLivingDead1968-Restored`, P10 `Night of the Living Dead (1968).webm`, and P6216 = Q19652 (public domain).
- SPARQL counts on `query.wikidata.org` (direct `instance of film` Q11424 only, so these are lower bounds): 5,173 films with P724, 5,080 of them also with a TMDB ID, and 2,541 films with a P10 Commons video.
- Lookup by external ID: `https://www.wikidata.org/w/api.php?action=query&list=search&srsearch=haswbstatement:P345=tt0063350`.
- Caveat: P724 values are community-maintained, and some may point at trailers or partial copies. Verify `length` against the Catalogue runtime (unverified how often this happens).

**Series.** IA has no Series/season/episode model. Uploads come in two shapes: one item per episode (e.g. `dick_van_dyke_show_31_never_name_a_duck`, title `"The Dick Van Dyke Show #31: Never Name a Duck"`), or one item per season holding many files named `… - S05E01.mkv` (live check). Episode matching therefore means parsing titles and file names (`S\d+E\d+`, `#\d+`). Treat Series on IA as best-effort.

**Multiple Streams per Title (Failover).**
- *Across items:* well-known public-domain Movies often have several IA items. Wikidata lists 4 for Night of the Living Dead, and a title search returns 254 `mediatype:movies` hits, most of them unrelated: trailers, parodies, VHS openings (live check). So Failover candidates should come from structured IDs (Wikidata P724, `external-identifier`), not title search.
- *Within an item:* usually 2–4 video files per item (`h.264`, `512Kb MPEG4`, `Ogg Video`, original). The playable ones can serve as a lower-quality Failover Stream when, say, a high-bitrate original stalls.

### 1.6 Licensing

- IA "does not make guarantees as to the copyright status of items" and rights information comes from uploaders ([Rights](https://help.archive.org/help/rights/)).
- `licenseurl` values seen: `http://creativecommons.org/publicdomain/mark/1.0/` and `http://creativecommons.org/licenses/publicdomain/` (live check). In `feature_films`, 7,669 items have a `licenseurl` containing `publicdomain`, and 9,141 have any `licenseurl` (live check).
- `possible-copyright-status` exists on 6,025 `mediatype:movies` items but on **0** `feature_films` items (live check), so it is not a usable filter here.
- A missing `licenseurl` is common (e.g. a Season 5 MKV upload of *The Dick Van Dyke Show* in `classic_tv` has none). Celastrina cannot verify rights itself, so a conservative v1 rule is to offer a Stream only when the IA item has a public-domain/CC `licenseurl` **or** the Wikidata Title has P6216 = public domain. Anything looser is a product and legal decision.

---

## 2. Other candidate Sources (comparison)

| Candidate | Auth model | Search / resolve | Stream protocol | DRM | Multiple Streams per Title | Catalogue Title mapping | Viable as Household-added Source? |
|---|---|---|---|---|---|---|---|
| **Jellyfin** | Household's server URL + user token (`/Users/AuthenticateByName` or Quick Connect) or admin API key | `GET /Items?searchTerm=&includeItemTypes=&hasTmdbId=…`; `POST /Items/{id}/PlaybackInfo` | Direct file (`/Videos/{id}/stream?static=true&mediaSourceId=`) or server transcode to HLS (`master.m3u8`) | None | Yes – `MediaSources[]` (versions) | `ProviderIds` (`Tmdb`, `Imdb`, `Tvdb`, …) | **Yes** – best fit |
| **Emby** | Server URL + `X-Emby-Token` (user `AccessToken` or API key) | Same lineage as Jellyfin: `/Items`, `/Items/{Id}/PlaybackInfo` | Direct / HLS transcode | None | Yes – `MediaSources` | `ProviderIds` | **Yes** (closed-source server; API close to Jellyfin) |
| **Plex Media Server** | plex.tv account: PIN flow → JWT/`authToken`; server `accessToken` from `clients.plex.tv/api/v2/resources`; `X-Plex-Token` + `X-Plex-Client-Identifier` | `/library/sections`, `/library/all`, `/library/matches` (guid hint), metadata `Media[]/Part[]` | Direct part file (`/library/parts/{id}/…/file.ext`) or universal transcode (HLS / HTTP) | None (personal media) | Yes – multiple `Media` per item ("Multi-Version Movies") | `Guid[]` = `imdb://`, `tmdb://`, `tvdb://` | **Yes**, but remote playback needs Plex Pass / Remote Watch Pass |
| **Wikimedia Commons** | None (User-Agent policy) | MediaWiki API `prop=videoinfo&viprop=url\|mime\|derivatives`; Wikidata P10 | Original WebM + VP9 WebM 240p–1080p, MPEG‑4 `.mov`, DASH `.mpd` | None | Yes – several transcodes; rarely >1 file per Title | Wikidata P10 ↔ P345/P4947 | **Yes** (public domain/free licences; small catalogue) |
| **Library of Congress** | None; 20 req/min on JSON API | `https://www.loc.gov/collections/{coll}/?fo=json` | MP4 (`video`) + HLS (`video_stream` `.m3u8`) | None | One file + HLS per item | Title/date only (no external IDs observed) | Possible – niche (shorts, newsreels) |
| **Tubi** | – | No public API found | – | – | – | – | **No** (no third-party playback API found) |
| **Pluto TV** | – | No public API; ToS forbids unauthorised/automated access | – | – | – | – | **No** |
| **Kanopy** | – | No API ("Kanopy does not have APIs", per a library vendor forum) | – | – | – | – | **No** |
| **Hoopla** | Licensed partner key + signed terms | Partner API for libraries/vendors (discovery/entitlements) | Not a Stream API | – | – | – | **No** |
| **Freevee** | – | Discontinued, folded into Prime Video (announced Nov 2024) | – | – | – | – | **No** |
| **Crackle** | – | No public developer API found (unverified; not researched in depth) | – | – | – | – | **No** |
| **YouTube** | API key / OAuth | Data API for metadata only | Must use the YouTube player; no Stream extraction | n/a | – | – | **No** as a Stream Source |

## 3. Per-candidate notes

### Jellyfin
- OpenAPI spec 12.4.0 at [api.jellyfin.org](https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json). Security scheme `CustomAuthentication`: API key in the `Authorization` header.
- **Token parsing** ([AuthorizationContext.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Server.Implementations/Security/AuthorizationContext.cs)): `Authorization: MediaBrowser Client=…, Device=…, DeviceId=…, Version=…, Token=…` is the primary form. The `ApiKey` query parameter is also accepted. `X-Emby-Token`, `X-MediaBrowser-Token` and `api_key` are accepted only when `EnableLegacyAuthorization` is on.
- Login endpoints: `/Users/AuthenticateByName`, plus `/QuickConnect/Initiate`, `/QuickConnect/Connect` and `/Users/AuthenticateWithQuickConnect`. Quick Connect lets the Household approve the TV with a code, so nobody types a password on a TV remote. Admin API keys live under `/Auth/Keys`.
- **PlaybackInfo:** `POST /Items/{itemId}/PlaybackInfo` takes `maxStreamingBitrate`, `mediaSourceId`, `enableDirectPlay`, `enableDirectStream`, `enableTranscoding` and similar parameters, plus a device profile. It returns `MediaSources[]`, each with `Id`, `Container`, `MediaStreams`, `SupportsDirectPlay`, `SupportsDirectStream`, `SupportsTranscoding`, `TranscodingUrl`, `Bitrate`, `RequiredHttpHeaders` and more (spec, `MediaSourceInfo`).
- **Stream URLs:** direct `GET /Videos/{itemId}/stream?static=true&mediaSourceId=…` (spec), or HLS `GET /Videos/{itemId}/master.m3u8` ([DynamicHlsController.cs](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/DynamicHlsController.cs)). The stable spec file did not list the HLS routes, though the source does. `/Videos/{itemId}/AlternateSources` also exists.
- **Title mapping:** `BaseItemDto.ProviderIds` is keyed by `MetadataProvider` names `Imdb`, `Tmdb`, `Tvdb`, `TvMaze`, … ([MetadataProvider.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Entities/MetadataProvider.cs)). `/Items` filters by `hasImdbId`, `hasTmdbId`, `hasTvdbId`, `isMovie`, `isSeries` and `searchTerm`. The stable spec shows no direct "provider ID equals" filter, so lookup is search-then-verify (unverified whether `AnyProviderIdEquals` still exists). Series: `/Shows/{seriesId}/Episodes?season=`.
- **Multiple Streams:** `MediaSources[]` (multiple versions of one item) provides intra-Source Failover. Fallback from direct play to transcode is a second axis.

### Emby
- API key auth: static token in the `X-Emby-Token` header or URL, created in Dashboard → Advanced → Security ([Emby API-Key Authentication](https://betadev.emby.media/doc/restapi/API-Key-Authentication.html)).
- User auth: `/Users/AuthenticateByName` returns `AccessToken`, which is then sent as `X-Emby-Token` ([User Authentication](https://dev.emby.media/doc/restapi/User-Authentication.html)).
- `GET/POST /Items/{Id}/PlaybackInfo` "requires authentication as user" ([reference](https://dev.emby.media/reference/RestAPI/MediaInfoService/postItemsByIdPlaybackinfo.html)).
- Jellyfin forked from Emby, so the shapes (`MediaSources`, `ProviderIds`, `/Videos/{id}/stream`) are near-identical. Endpoint-level differences were not verified. A single client adapter with small per-server differences is plausible (unverified).

### Plex Media Server
- Docs: [developer.plex.tv/pms](https://developer.plex.tv/pms/) (Apache-2.0). `X-Plex-Token` and `X-Plex-Client-Identifier` go in headers or the query string.
- Auth: the recommended JWT PIN flow POSTs a JWK to `https://clients.plex.tv/api/v2/pins`, the user approves, and the client polls `/api/v2/pins/{id}?deviceJWT=…` to get `authToken`. Tokens are refreshed via `/api/v2/auth/nonce` and `/api/v2/auth/token` and are valid 7 days. Server addresses and `accessToken` come from `clients.plex.tv/api/v2/resources`, preferring local connections.
- Metadata items have `Media[]`, each with `Part[]` whose `key` looks like `/library/parts/827/file.mkv` (direct file). Transcode protocols include `hls` and `http`. `Guid[]` entries look like `imdb://tt0088763` and `tmdb://105`, and `imdb`, `tmdb` and `tvdb` are the built-in providers ([developer.plex.tv](https://developer.plex.tv/pms/)).
- Multiple versions: files with the same title and year are collapsed into one item with several versions, which Plex calls "Multi-Version Movies" ([support](https://support.plex.tv/articles/200381043-multi-version-movies/)). That gives Failover inside one Source.
- **Cost caveat:** remote playback of personal media now requires a Plex Pass on the server admin's or viewer's account, or a Remote Watch Pass ([Plex blog](https://www.plex.tv/blog/important-2025-plex-updates/)). Per a Plex forum announcement this extends to "any third party clients using the API" in 2026 ([forum](https://forums.plex.tv/t/changes-coming-to-remote-streaming-on-roku/933671)); enforcement for third-party clients is unverified. Local network playback stays free.

### Wikimedia Commons (public domain / free licences)
- `https://commons.wikimedia.org/w/api.php?action=query&titles=File:…&prop=videoinfo&viprop=url|mime|size|derivatives&format=json`. For `File:Night_of_the_Living_Dead_(1968).webm` the live check returned the original WebM plus derivatives: `application/dash+xml` (`…vp9.mpd`), `video/webm; codecs="vp9, opus"` at 240p/480p/1080p, and `video/quicktime; codecs="mp4v"` 360p.
- VP9 is hardware-decoded on current Fire TV models, and older/lower-end models may lack it (check per device on [Fire TV specs](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html)). Media3 plays WebM, Matroska and DASH ([Media3](https://developer.android.com/media/media3/exoplayer/supported-formats)).
- Mapping: Wikidata P10 → Commons file. About 2,541 films have one (lower bound, live check). Clients must follow Wikimedia's [User-Agent policy](https://meta.wikimedia.org/wiki/User-Agent_policy).

### Library of Congress
- JSON API: append `fo=json` to collection URLs. The National Screening Room (`/collections/national-screening-room/`) returned 1,295 results (live check).
- Each `resources[]` entry has `video` (MP4 on `tile.loc.gov/storage-services/…`), `video_stream` (HLS `…/default.m3u8`), `duration`, `height` and `width` (live check).
- Rate limits: JSON API 20 req/min, Streaming Services 60 req/min, `/storage-services/` media 150 req/min. Exceeding any of them blocks access for 1 hour, and the API may return 429 or a CAPTCHA under load ([working within limits](https://www.loc.gov/apis/json-and-yaml/working-within-limits/)).
- No IMDb/TMDB IDs were observed, so matching relies on title and date. Collection rights statement not fetched (403), unverified. Content is mostly shorts, newsreels and early films. A narrow, high-quality public-domain Source.
- National Film Preservation Foundation: no public API found (not researched in depth, unverified).

### Free ad-supported and library services (no third-party Stream API)
- **YouTube.** The Data API is metadata-only. The Developer Policies forbid clients to "download, import, backup, cache, or store copies of YouTube audiovisual content without YouTube's prior written approval" (III.E.1.a) and to "separate, isolate, or modify the audio or video components" (III.I.7). They also forbid modifying or blocking the YouTube player (III.I.6) and playing from a background player (III.I.9) ([Developer Policies](https://developers.google.com/youtube/terms/developer-policies)). The YouTube ToS (effective 2023‑12‑15) forbids downloading except as expressly authorised and forbids access "using any automated means" ([ToS](https://www.youtube.com/t/terms)). At most, Celastrina could show an embedded YouTube player, which is not a Stream Media3 can play or fail over.
- **Pluto TV.** The terms forbid use that "gains or enables unauthorized access" and use of "automated software, devices, scripts, robots…" to access the Services (§6, last modified 2022‑11‑14, UK terms) ([Pluto ToU](https://legal.paramount.com/uk/en/plutotv/terms-of-use?r=pluto.tv)). No developer API is published.
- **Tubi.** No developer or third-party playback API found. Terms at tubitv.com/static/terms could not be read in this session (unverified).
- **Kanopy.** A library-vendor feature request records that "Kanopy does not have APIs" ([iii ideas](https://ideas.iii.com/forums/951766-vega-discover/suggestions/46663693-api-content-ingest-for-kanopy); undated, secondary).
- **Hoopla.** A Partner API exists, but it requires a licence, a unique key and signed terms, is aimed at libraries and vendors for discovery and entitlements, and is not a playback API ([developer.hoopladigital.com](https://developer.hoopladigital.com/getting-started)).
- **Freevee.** Amazon announced the shutdown in Nov 2024 and moved its content into Prime Video ([9to5Google](https://9to5google.com/2024/11/13/amazon-freevee-shutting-down/), secondary).
- **Crackle.** Not researched in depth. No public API known (unverified).

## 4. Open questions

1. **Rights policy:** what exactly qualifies an IA item for Celastrina? Options include `licenseurl` set, a Wikidata P6216 public-domain statement, curated collections only, or a Household opt-in for "unverified rights" items. This needs a product and legal decision, possibly an ADR.
2. **Catalogue choice:** if the Catalogue is TMDB-based, how much of IA is reachable through Wikidata P724 (about 5k films) compared with fuzzy title/year matching against the 29k `feature_films`? A quick offline job could measure match rates.
3. **Series on IA:** is best-effort episode parsing acceptable, or should v1 cover Movies only, with Series from IA marked experimental?
4. **Codec probing:** for originals (`MPEG4`/`Matroska`), should Celastrina try playback and fail over, or check codecs first? IA metadata gives no codec field (observed fields: `format`, `height`, `width`, `length`).
5. **MP2 audio in MPEG‑2 originals:** does Fire TV's platform decoder handle it, or is the Media3 FFmpeg extension needed? This needs a device test.
6. **Plex third-party remote-playback enforcement:** has it actually reached third-party clients, and does it apply to a Household's own server outside its LAN?
7. **Jellyfin provider-ID lookup:** is there a supported query to find an item by TMDB/IMDb ID directly in 10.11+/12.x, rather than search-then-filter?
8. **IA terms text:** fetch and archive the current archive.org/about/terms.php wording; it was not reachable in this session.

## 5. Sources

Internet Archive
- Advanced search / Scrape API help: https://archive.org/help/aboutsearch.htm
- Metadata Read API: https://archive.org/developers/md-read.html
- API index: https://archive.org/developers/index-apis.html
- Bots, LLMs and automated access: https://archive.org/developers/bots.html
- `internetarchive` Python library search.py: https://github.com/jjjake/internetarchive/blob/master/internetarchive/search.py
- File formats and derivatives: https://help.archive.org/help/files-formats-and-derivatives-file-definitions-2/
- Improved h.264 derivatives (2011): https://blog.archive.org/2011/06/04/improved-h-264-derivatives/
- Rights: https://help.archive.org/help/rights/
- Terms of Use update (2014): https://blog.archive.org/2014/12/30/update-to-terms-of-use/
- Services update 2024‑10‑21: https://blog.archive.org/2024/10/21/internet-archive-services-update-2024-10-21/
- Live API calls (2026‑11‑23): `advancedsearch.php`, `services/search/v1/scrape`, `metadata/{sex_madness, NightOfTheLivingDead1968-Restored, the-dick-van-dyke-show-season-5-of-5-mkv, silent-a-night-in-the-show, bm86fgh443}`, `download/sex_madness/sex_madness.mp4`

Playback
- Media3 ExoPlayer supported formats: https://developer.android.com/media/media3/exoplayer/supported-formats
- Media3 AviExtractor: https://github.com/androidx/media/blob/release/libraries/extractor/src/main/java/androidx/media3/extractor/avi/AviExtractor.java
- Fire TV Streaming Media Player specs: https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html

Wikidata / Wikimedia / LoC
- Wikidata API and SPARQL (live): https://www.wikidata.org/w/api.php, https://query.wikidata.org/sparql (Q623051)
- Commons API (live): https://commons.wikimedia.org/w/api.php
- Wikimedia User-Agent policy: https://meta.wikimedia.org/wiki/User-Agent_policy
- LoC API limits: https://www.loc.gov/apis/json-and-yaml/working-within-limits/
- LoC National Screening Room JSON (live): https://www.loc.gov/collections/national-screening-room/?fo=json

Media servers
- Jellyfin OpenAPI: https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json
- Jellyfin AuthorizationContext.cs: https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Server.Implementations/Security/AuthorizationContext.cs
- Jellyfin DynamicHlsController.cs: https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/DynamicHlsController.cs
- Jellyfin MetadataProvider.cs: https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Model/Entities/MetadataProvider.cs
- Emby API key auth: https://betadev.emby.media/doc/restapi/API-Key-Authentication.html
- Emby user auth: https://dev.emby.media/doc/restapi/User-Authentication.html
- Emby PlaybackInfo: https://dev.emby.media/reference/RestAPI/MediaInfoService/postItemsByIdPlaybackinfo.html
- Plex PMS API: https://developer.plex.tv/pms/
- Plex Multi-Version Movies: https://support.plex.tv/articles/200381043-multi-version-movies/
- Plex 2025 updates (remote playback): https://www.plex.tv/blog/important-2025-plex-updates/
- Plex forum, remote streaming changes: https://forums.plex.tv/t/changes-coming-to-remote-streaming-on-roku/933671

Commercial services
- YouTube API Developer Policies: https://developers.google.com/youtube/terms/developer-policies
- YouTube Terms of Service: https://www.youtube.com/t/terms
- Pluto TV Terms of Use (UK): https://legal.paramount.com/uk/en/plutotv/terms-of-use?r=pluto.tv
- Hoopla developer portal: https://developer.hoopladigital.com/getting-started
- Kanopy API (vendor forum, secondary): https://ideas.iii.com/forums/951766-vega-discover/suggestions/46663693-api-content-ingest-for-kanopy
- Freevee shutdown (secondary): https://9to5google.com/2024/11/13/amazon-freevee-shutting-down/
