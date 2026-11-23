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
An unverified Source, such as a web scraper written by a third party, that a Household imports into Celastrina after installation; Celastrina itself ships none.
_Avoid_: Plugin, addon, provider

**Stream**:
One playable offering of a Title from a specific Source.
_Avoid_: Link, URL, file

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
