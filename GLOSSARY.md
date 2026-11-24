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

**Category**:
A label that groups Titles for browsing, such as Anime or Cartoons; it does not change what kind of Title something is.
_Avoid_: Type, kind, section

### Where it plays from

**Source**:
A provider that, given a Title (or episode), can offer Streams for it; Sources can be added by the household after installation. Each Source is one configured instance of a Source Kind, such as a particular Jellyfin server.
_Avoid_: Provider, addon, plugin, scraper

**Source Kind**:
A type of Source that Celastrina knows how to talk to, such as Internet Archive, Jellyfin or Stremio-compatible add-on; a Household can have several Sources of the same Kind.
_Avoid_: Source type, adapter, integration

**Extension**:
An unverified package of third-party code, such as a web scraper, that a Household imports into Celastrina after installation and that provides one or more Sources; Celastrina itself ships none.
_Avoid_: Plugin, addon, provider

**Extension Repository**:
A list of Extensions, published by a third party at a URL, that a Household imports in order to choose which of its Extensions to install and to receive their updates.
_Avoid_: Repo, store, marketplace, feed

**Stream**:
One offering of a Title from a specific Source. Celastrina plays only unencrypted http(s), HLS and DASH Streams; it drops the rest.
_Avoid_: Link, URL, file

**Title reference**:
What Celastrina tells a Source about the Title (or episode) it wants Streams for: the Title's IDs, names and year, as the Catalogue knows them.
_Avoid_: Query, request

**Lookup**:
Asking a Household's Sources for Streams of one Title, which happens only when its detail screen opens or an episode is picked.
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

### Getting it onto a TV

**Install Runbook**:
The instructions, written for an AI agent, that let it install, update, configure, or remove Celastrina on a household's TV, asking the user only for what it cannot do itself.
_Avoid_: Install guide, setup docs, README

**Household**:
The people who use Celastrina on a given TV and decide which Sources it has.
_Avoid_: User, account, customer
