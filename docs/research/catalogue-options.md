# Catalogue options for Movies, Series and Anime

Research for [#3](https://github.com/MasonNight/celastrina/issues/3) on the map ([#1](https://github.com/MasonNight/celastrina/issues/1)). Researched 2026-11-23.

**Question:** Which metadata services can power the Catalogue? For each, this note covers coverage of Movies, Series and the Anime Category; artwork; key and terms requirements; rate limits; and whether an open-source app may ship a key or each Household must supply one. It also covers how IDs map between services and what metadata Internet Archive items carry.

Terms follow `GLOSSARY.md`. "Anime" is a Category. An anime Title is still a Movie or a Series.

## Summary

| Service | Movies | Series | Anime | Artwork | Key | Ship a key in an OSS app? | Rate limit |
|---|---|---|---|---|---|---|---|
| **TMDB** | Yes | Yes | Yes, but anime is modelled as Western-style Series (one Series, many seasons) | Posters, backdrops, **logos**, stills, with per-language tags and up to `original` size | Free key for non-commercial use | Terms are silent on key distribution. Jellyfin ships one in source. | About 40 req/s per the docs ("could change at any time") |
| **AniList** | Anime films | Anime only | Best coverage. One entry per season/cour. | Cover (3 sizes) + one banner. **No logos.** | **None** for public reads | Nothing to ship | 90/min nominal; **30/min now (degraded)** |
| **Kitsu** | Anime films | Anime only | Good. One entry per season. | Poster (to 550x780 + original), cover/banner (to 3360x800 + original). No logos. | None for public GETs | Nothing to ship | Not published |
| **TheTVDB** | Yes | Yes (its strength) | Yes, as long-running Series | Rich (posters, fanart, clearlogos) | Key required. Paid licence **or** each end user pays $12/yr and enters a PIN. | Only under the user-supported model: each Household supplies its own PIN | n/a |
| **Wikidata** | Yes | Yes | Partial | None usable as a poster wall (Commons images are mostly not posters) | None (send a descriptive User-Agent) | Nothing to ship | WDQS: 60 s query time per 60 s, 5 parallel |
| **fanart.tv** | Yes (by TMDB/IMDb id) | Yes (by TVDB id) | Via TVDB/TMDB ids | **HD clear logos**, backgrounds, posters | Project key + optional per-user "client key" | Project key is designed to be shipped. A Household key is optional. | n/a |

**Bottom line for a later decision:**

- **TMDB is the only single service that covers Movies, Series and anime with TV-grade artwork, including title logos.** It is free for non-commercial use with attribution. A GPL app with no revenue fits that, but TMDB alone decides what counts as commercial.
- **AniList and Kitsu need no key at all.** They are the natural enrichment for the Anime Category, but their unit is a season, not a Series. AniList is currently throttled to 30 req/min.
- **TVDB is a poor default.** It requires either a negotiated paid licence or a paid subscription PIN from every Household.
- **ID mapping between anime databases and TMDB/TVDB is a community-maintained problem**, not something the APIs solve. The usable bridge is the Fribb/Anime-Lists mapping files. They carry season and episode offsets because one TMDB Series spans several AniList entries.
- **Internet Archive items carry thin, free-form metadata.** Only about 7% of `feature_films` carry an IMDb id, so most IA Titles have to be matched to the Catalogue by title and year.

## TMDB (The Movie Database)

- **Coverage:** Movies and TV (Series), including anime. Discover endpoints offer `with_genres`, `with_keywords`, `with_origin_country` and `with_original_language`, which can build an Anime Category (for example Animation + origin JP). None of these is an "anime" flag. [Discover TV](https://developer.themoviedb.org/reference/discover-tv)
- **Artwork:** `/movie/{id}/images` (and the TV equivalent) returns `backdrops`, `logos` and `posters`. Each image carries `width`, `height`, `aspect_ratio`, `iso_639_1`, `file_path` and `vote_average`. [Movie images](https://developer.themoviedb.org/reference/movie-images) Sizes come from `/configuration` ([Configuration](https://developer.themoviedb.org/reference/configuration-details)):
  - poster: `w92 w154 w185 w342 w500 w780 original`
  - backdrop: `w300 w780 w1280 original`
  - logo: `w45 w92 w154 w185 w300 w500 original`
  - still (episode): `w92 w185 w300 original`
  - base URL `https://image.tmdb.org/t/p/`
- **Language-aware art:** `include_image_language=en,null` returns localized art plus untagged (usually textless) art. For backdrops, the API picks "the highest rated backdrop with no language" by default. [Image languages](https://developer.themoviedb.org/docs/image-languages)
- **Key and cost:** You register for a key from your account settings and must accept the terms. [Getting started](https://developer.themoviedb.org/docs/getting-started) "Our API is free to use for non-commercial purposes" as long as you attribute TMDB. [FAQ](https://developer.themoviedb.org/docs/faq)
- **Terms** ([API Terms of Use](https://www.themoviedb.org/api-terms-of-use), last updated 2023-10-20):
  - No commercial use without a written agreement. "TMDB reserves the right to, in its sole discretion, determine whether Your use is commercial." The examples given are a paid app or ad revenue.
  - You must use an approved TMDB logo, less prominent than your own branding. You must also show the notice "This product uses the TMDB API but is not endorsed or certified by TMDB." [FAQ](https://developer.themoviedb.org/docs/faq)
  - You may not cache any TMDB information "for longer than 6 months". This matters for any on-device Catalogue cache.
  - The licence is "non-transferable, non-sublicensable". The terms do not mention embedding or distributing a key.
- **Shipping a key in OSS:** The terms neither permit nor forbid it. In practice, Jellyfin (GPL, open source) ships a TMDB key in source: `public const string ApiKey = "4219e2..."` in [TmdbUtils.cs](https://github.com/jellyfin/jellyfin/blob/master/MediaBrowser.Providers/Plugins/Tmdb/TmdbUtils.cs). This is precedent, not permission. A shipped key is shared by every install, so one abusive install can get it revoked for all.
- **Rate limit:** The legacy 40 req / 10 s limit was disabled in December 2019. The current upper limit sits "somewhere in the 40 requests per second range" and "could change at any time". Clients should honour 429 responses. [Rate limiting](https://developer.themoviedb.org/docs/rate-limiting)
- **ID mapping:** `/find/{external_id}` accepts `imdb_id`, `tvdb_id`, `wikidata_id` and social ids. It does **not** accept AniList, Kitsu, MAL or AniDB ids. [Find by ID](https://developer.themoviedb.org/reference/find-by-id) TV `external_ids` returns `imdb_id`, `tvdb_id` and `wikidata_id`, among others. [TV external IDs](https://developer.themoviedb.org/reference/tv-series-external-ids)

## AniList

- **Coverage:** Anime and manga only. Formats include TV, MOVIE, OVA, ONA and others. Each season or cour is its own entry. For example, *Frieren* S1 is AniList `154587` (28 episodes) and S2 is `182255`.
- **Artwork:** `coverImage{extraLarge, large, medium, color}` plus a single `bannerImage`. There are no logos and no per-language art. (Verified by a live unauthenticated query on 2026-11-23.)
- **Key:** None for public reads. A live POST to `https://graphql.anilist.co` with no token returned data. OAuth is only needed for user data.
- **Terms** ([Terms of Use](https://docs.anilist.co/guide/terms-of-use)):
  - "Free for non-commercial usage". Commercial use is free under $150/month revenue.
  - "Using the AniList API as a backup or data storage service is strictly prohibited." "Hoarding or mass collection of data … is strictly prohibited." Celastrina should query on demand and cache lightly, not mirror the catalogue.
  - Competing list or tracker services are prohibited. Celastrina is not one.
- **Rate limit** ([Rate limiting](https://docs.anilist.co/guide/rate-limiting)):
  - Nominally 90 req/min, plus a burst limiter.
  - **"The API is currently in a degraded state and is limited to 30 requests per minute."** Live response headers on 2026-11-23 showed `x-ratelimit-limit: 30`.
  - Raises are "not currently accepting requests".
  - The limit is per IP, so each Household's TV gets its own budget.
- **Other:** The API may be suspended during outages, which returns 403. Adult entries exist, and "Ecchi" is not flagged as adult. [Considerations](https://docs.anilist.co/guide/considerations)
- **ID mapping:** Exposes `idMal` (MyAnimeList) and `externalLinks` (streaming and official sites). It exposes **no TMDB, TVDB or IMDb id**.

## Kitsu

- **Coverage:** Anime and manga, with one entry per season. *Frieren* S1 is Kitsu `46474`.
- **Artwork:** `posterImage` comes in tiny/small/medium/large (up to 550x780) plus original. `coverImage` (a banner) comes in tiny/small/large (up to 3360x800) plus original. There are no logos. (Verified by a live query on 2026-11-23.)
- **Key:** "Authentication is not required for most public-facing `GET` endpoints." NSFW media is hidden from unauthenticated requests. [Kitsu API docs (Apiary, legacy)](https://kitsu.docs.apiary.io/) The docs are mid-migration to OpenAPI. [hummingbird-me/api-docs](https://github.com/hummingbird-me/api-docs)
- **Terms and rate limit:** No official terms of API use or rate limit could be found in Kitsu's own docs. Treat both as unpublished.
- **ID mapping:** The `/mappings` resource gives `externalSite` and `externalId` pairs, for example `myanimelist/anime` and `anilist/anime`. For *Frieren*, only the MAL and AniList mappings exist. There was no TVDB or TMDB mapping.

## TheTVDB

- **Coverage:** Series (its core), Movies and anime as long-running Series.
- **Key models** ([v4-api README](https://github.com/thetvdb/v4-api), [FAQ: key types](https://support.thetvdb.com/kb/faq.php?id=81)):
  - **Negotiated licence:** For a commercial or project licence, "contact us in advance to negotiate a contract (generally requiring attribution)". The key stays inactive until sales approves it.
  - **User-supported:** The key is auto-approved, but "each of your users has a $12/year TheTVDB subscription". Each user enters their own PIN, and "You are prohibited from sharing a PIN for multiple users."
  - Open-source projects get "significant discounts", but no free tier is published. [API information](https://thetvdb.com/api-information)
- **Attribution:** "attribution with a direct link to TheTVDB.com must be displayed to end users". [API information](https://thetvdb.com/api-information)
- **Fit for Celastrina:** Without a negotiated licence, every Household would need a paid TVDB subscription PIN. That adds a cost and a setup step to the Install Runbook.

## Wikidata

- **Coverage:** Broad for Movies and Series, partial for anime. It is useful as an **ID-mapping hub**, not as a Catalogue. It has no poster or backdrop artwork.
- **Licence:** Structured data is CC0. [Wikidata:Licensing](https://www.wikidata.org/wiki/Wikidata:Licensing)
- **Relevant properties:** P4947 TMDB movie ID, P4983 TMDB TV series ID, P4835 TheTVDB series ID, P12196 TheTVDB movie ID, P8729 AniList anime ID, P11495 Kitsu anime ID, P4086 MyAnimeList anime ID, P5646 AniDB anime ID, P345 IMDb ID, and P724 Internet Archive ID. (Labels checked via `wbgetentities` on 2026-11-23.)
- **Practical coverage (SPARQL, 2026-11-23):**
  - 7,258 items carry an AniList id, and 5,414 of those also carry a TMDB id.
  - *Frieren* (AniList 154587) resolves to a season-level item with MAL and Kitsu ids but **no** TMDB id. Wikidata splits anime by season, like AniList.
- **Limits:** WDQS allows 60 s of query time per 60 s per client and 5 parallel queries per IP. Clients without a policy-compliant User-Agent "may be blocked completely". The v1 docs are maintained until June 2027 because v2 now exists. [WDQS user manual](https://www.mediawiki.org/wiki/Wikidata_Query_Service/User_Manual)

## fanart.tv (artwork supplement)

- **What it adds:** `hdmovielogo` / `hdtvlogo` clear logos, backgrounds and posters, keyed by TMDB/IMDb ids for movies and TVDB ids for TV. [fanart.tv API docs](https://fanarttv.docs.apiary.io/)
- **Keys:**
  - A project `api_key` is required.
  - A "client key", "also known as a personal API key", is optional and is "used in addition to the project key" by end users who sign up.
  - The website ([get-an-api-key](https://fanart.tv/get-an-api-key/)) was behind a bot check and could not be read. The delay or benefit given to personal keys, and the commercial terms, are **unverified**.
- **Relevance:** Only needed if TMDB's own `logos` are judged insufficient.

## Mapping IDs between services (anime on TMDB vs AniList)

The core mismatch is the unit of a Title:

- **AniList and Kitsu** (and Wikidata, mostly) have one entry per season or cour.
- **TMDB and TVDB** have one Series with many seasons.

No official API bridges the two. AniList exposes only MAL ids, Kitsu mappings for new titles often stop at MAL/AniList, and TMDB `/find` does not accept anime-database ids.

Community mapping datasets fill the gap:

- **[Anime-Lists/anime-lists](https://github.com/Anime-Lists/anime-lists)** maps AniDB to TheTVDB Series and TMDB/IMDb movies. It is used by Plex HAMA, Jellyfin Ani-Sync, Kometa and Overseerr/Jellyseerr. It is actively updated (pushed 2026-11-23). There is no licence file.
- **[manami-project/anime-offline-database](https://github.com/manami-project/anime-offline-database)** cross-references MAL, AniList, Kitsu, AniDB and others. It does not cover TMDB/TVDB. It has 41,537 entries (week 27, 2026) under the **ODbL-1.0** licence. Data files moved to GitHub Releases.
- **[Fribb/anime-lists](https://github.com/Fribb/anime-lists)** merges the two lists above into `anime-list-full.json`. That file maps AniList, Kitsu, MAL and AniDB ids to `themoviedb_id`, `tvdb_id` and `imdb_id`, **with season and episode offsets**. It was pushed 2026-11-17 and has no licence file.
  - The file has 39,779 entries. 20,928 carry an AniList id, and 8,396 of those also map to TMDB or TVDB. Many unmapped entries are specials, ONAs and music videos.
  - Example: *Frieren* S2 (AniList 182255) maps to TMDB TV `209867` with `season.tmdb: 1, episode_offset.tmdb: 28`. TMDB files S2 as episodes 29+ of season 1, while TVDB calls it season 2.

**Implication:** Anchor the Catalogue on TMDB ids. Treat AniList/Kitsu as optional enrichment for the Anime Category, joined through the Fribb mapping. Expect the mapping to carry season and episode offsets, so it is never a 1:1 id swap. The licence status of Fribb and Anime-Lists (no licence file) should be checked before bundling them in the APK. Downloading them at runtime avoids redistributing them.

## Internet Archive item metadata

- **Read API:** `https://archive.org/metadata/{identifier}` returns `metadata`, `files` (name, format, size, md5, …) and server info. No auth is needed for public data. [Metadata Read API](https://archive.org/developers/md-read.html)
- **Schema:** `mediatype` (required; `movies` for video) and `collection` (effectively required). `title`, `description`, `date`, `creator`, `subject`, `runtime`, `language` and `licenseurl` are optional and mostly free-form. `external-identifier` is "usually in the form of urn:namespace:identifier". [Metadata schema](https://archive.org/developers/metadata-schema/index.html)
- **Reality check (advancedsearch, 2026-11-23):**
  - `collection:feature_films` holds 29,694 items, but only **2,113 (about 7%)** carry an IMDb `external-identifier` (for example `urn:imdb:tt0014532`).
  - The most-downloaded item, `HisNewJobCharlesChaplin-1915`, has an uploader-mangled title, no date, no description and no external id.
  - Its only artwork is an auto-generated `__ia_thumb.jpg` ("Item Tile").
- **Implication:** IA is a Source, not a Catalogue. To show an IA Stream on a Catalogue Title, match by `urn:imdb` when present (TMDB `/find` accepts `imdb_id`). Otherwise fall back to fuzzy title+year matching, and expect misses. Wikidata P724 (Internet Archive ID) is another occasional bridge.

## Open points (not verified)

- TMDB has not answered in writing whether a key embedded in a public GPL APK is acceptable. Emailing TMDB would settle it.
- fanart.tv's personal-key benefits and terms could not be read (bot wall).
- Kitsu's rate limit and API terms are unpublished.
- The licences of the Fribb and Anime-Lists mapping files are unstated.
