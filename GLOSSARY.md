# Celastrina

An open-source streaming app for Fire TV that lets a household browse movies and series with rich artwork and play them from whichever Source can currently serve them.

## Language

### What can be watched

**Catalogue**:
The metadata about what exists to watch (titles, artwork, descriptions, seasons and episodes), independent of whether anything can play it.
_Avoid_: Library, database, index

**Title**:
A single entry in the Catalogue: either a Movie or a Series.
_Avoid_: Media, item, content, show

**Movie**:
A Title that is watched as one standalone piece.
_Avoid_: Film

**Series**:
A Title made of seasons and episodes.
_Avoid_: Show, TV show

**Title kind**:
Whether a Title is a Movie or a Series.
_Avoid_: Type, format, media type

**Season**:
One numbered part of a Series, holding its episodes in order. Season 0, where a Series has one, holds its specials.
_Avoid_: Series (for one part), volume, part

**Episode**:
One piece of a Series, known by its Season and its number within it.
_Avoid_: Chapter, part

**Category**:
A label that groups Titles for browsing, such as Anime or Cartoons; it does not change what kind of Title something is.
Anime is animation made in Japanese; Cartoons is any other animation.
_Avoid_: Type, kind, section

**Search**:
Finding Titles in the Catalogue by name. Search asks only the Catalogue, never the Sources.
_Avoid_: Lookup, find, query

### Browsing on home

**Row**:
One named line of Titles on home, such as Movies, Series, Anime, Cartoons, Continue watching or the Watchlist.
_Avoid_: Shelf, rail, carousel, list

**Hero**:
The large area at the top of home that shows the focused Title's backdrop, title logo and details.
_Avoid_: Banner, billboard, spotlight

**Continue watching**:
The Row of Titles and episodes the Household started but didn't finish, each resuming where playback got to, on the
Stream that last worked.
_Avoid_: Recently watched, history, resume list

**Watchlist**:
The Row of Titles the Household saved to watch later.
_Avoid_: Favourites, my list, bookmarks

### Where it plays from

**Source**:
A provider that, given a Title (or episode), can offer Streams for it; Sources can be added by the household after installation. Each Source is one configured instance of a Source Kind, such as a particular Jellyfin server.
_Avoid_: Provider, addon, plugin, scraper

**Source Kind**:
A type of Source that Celastrina knows how to talk to, such as Internet Archive, Jellyfin, Emby or Stremio-compatible add-on; a Household can have several Sources of the same Kind.
_Avoid_: Source type, adapter, integration

**Media server**:
A Jellyfin or Emby server, run by the Household or someone it knows; Jellyfin and Emby are two Source Kinds that work alike.
_Avoid_: Library server, Plex (a different product)

**Quick Connect**:
Logging a Jellyfin Source in by approving, from another device already signed in to the server, a code the TV shows.
_Avoid_: Pairing, device code

**Extension**:
An unverified package of third-party code, such as a web scraper, that a Household imports into Celastrina after installation and that provides one or more Sources; Celastrina itself ships none.
_Avoid_: Plugin, addon, provider

**Extension Repository**:
A list of Extensions, published by a third party at a URL, that a Household imports in order to choose which of its Extensions to install and to receive their updates.
_Avoid_: Repo, store, marketplace, feed

**Stream**:
One offering of a Title from a specific Source. Celastrina plays only unencrypted http(s), HLS and DASH Streams; it drops the rest.
_Avoid_: Link, URL, file

**Stream details**:
What a Stream says about itself: its picture height, codecs, container, audio languages, subtitles and size. A
Stremio-compatible add-on can give them in Celastrina's own extension to the add-on protocol.
_Avoid_: Metadata (that's the Catalogue's), quality info

**Choose Stream**:
The screen that lists every Stream a Lookup found, grouped by Source, for the Household to pick one by hand.
_Avoid_: Stream picker, source list

**Title reference**:
What Celastrina tells a Source about the Title (or episode) it wants Streams for: the Title's IDs, names and year, as the Catalogue knows them.
_Avoid_: Query, request

**Lookup**:
Asking a Household's Sources for Streams of one Title or episode: when its detail screen opens, an episode is picked or
a Continue watching card resumes it, and again when Failover finds its Stream list may have expired.
_Avoid_: Search, scrape, resolve

**Not available**:
A Source's answer that it has no Streams for a Title: a normal outcome, not a Failure.
_Avoid_: Not found, no results

**Failure**:
A Source's answer that it couldn't complete a Lookup, with a reason: unreachable, login needed, rate limited, or a Source error. A Stream that stops playing is not a Failure; Failover handles it.
_Avoid_: Error

**Not playable**:
Said of a Title whose latest Lookup, within the past week, found every Source Not available.
_Avoid_: Unavailable, dead

**Failover**:
Automatically moving playback to another Stream for the same Title when the current one doesn't work.
_Avoid_: Auto-switch, fallback

**Candidate**:
A Stream Failover may move to: one this TV can decode, in the Household's Source order.
_Avoid_: Option, alternative, backup

**Stall**:
Playback stopping to wait for data after its first picture, other than after a pause or a seek.
_Avoid_: Freeze, hang, lag

**Failover memory**:
What the TV remembers of the Streams that failed on it in the past 24 hours, which go last among the candidates.
_Avoid_: Blacklist, ban list, history

**Give up**:
Failover stopping for good: when the candidates run out, after five failures in a row, or after a minute without
playback. The Household then sees what happened at each Source.
_Avoid_: Abort, crash, error screen

### Getting it onto a TV

**TMDB key**:
The credential the Catalogue is read from TMDB with: the project's, built into Celastrina, unless the Household has set
its own, which overrides it.
_Avoid_: API key, token, licence

**Install Runbook**:
The instructions, written for an AI agent, that let it install, update, configure, or remove Celastrina on a household's TV, asking the user only for what it cannot do itself.
_Avoid_: Install guide, setup docs, README

**Household**:
The people who use Celastrina on a given TV and decide which Sources it has.
_Avoid_: User, account, customer
