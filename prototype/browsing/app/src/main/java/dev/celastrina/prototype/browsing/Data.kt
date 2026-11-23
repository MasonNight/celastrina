// PROTOTYPE — throwaway fixture data. Artwork is random photography from picsum.photos
// (real JPEG loads over the network, so artwork performance is measured honestly),
// not real posters. Nothing here is a Catalogue design.
package dev.celastrina.prototype.browsing

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

enum class Kind { Movie, Series }

data class Episode(val season: Int, val number: Int, val name: String, val minutes: Int) {
    val still get() = "https://picsum.photos/seed/ep-$season-$number-$name/400/225"
}

data class Title(
    val id: String,
    val name: String,
    val year: Int,
    val kind: Kind,
    val categories: List<String>,
    val synopsis: String,
    /** false = in the Catalogue but no Source can currently play it. */
    val playable: Boolean,
    val seasons: Int = 0,
) {
    val poster get() = "https://picsum.photos/seed/p-$id/300/450"
    val backdrop get() = "https://picsum.photos/seed/b-$id/960/540"
    val landscape get() = "https://picsum.photos/seed/b-$id/400/225"
    fun episodes(season: Int) = (1..(8 + season % 3)).map {
        Episode(season, it, "Episode $it", 22 + (it * 7) % 30)
    }
}

private val realNames = listOf(
    "His Girl Friday" to 1940, "Night of the Living Dead" to 1968, "Nosferatu" to 1922,
    "The General" to 1926, "Charade" to 1963, "Detour" to 1945, "The Kid" to 1921,
    "Sherlock Jr." to 1924, "Metropolis" to 1927, "House on Haunted Hill" to 1959,
    "The Little Shop of Horrors" to 1960, "Penny Serenade" to 1941, "Carnival of Souls" to 1962,
    "Royal Wedding" to 1951, "The Stranger" to 1946, "Kansas City Confidential" to 1952,
)
private val series = listOf(
    "Flash Gordon", "The Lone Ranger", "Dick Tracy", "Bonanza", "One Step Beyond",
    "The Cisco Kid", "Space Patrol", "Captain Video",
)
private val anime = listOf("Starfield Courier", "Moonlit Blade", "Paper Kites", "Iron Lantern", "Blue Tide Academy")
private val cartoons = listOf("Superman (Fleischer)", "Popeye Shorts", "Betty Boop", "Felix the Cat", "Gulliver's Travels")

private const val LOREM = "A placeholder synopsis long enough to see how two or three lines of description sit over the artwork. " +
    "Real copy comes from the Catalogue."

object Fixture {
    val titles: List<Title> = buildList {
        realNames.forEachIndexed { i, (n, y) ->
            add(Title("m$i", n, y, Kind.Movie, listOf(if (i % 4 == 0) "Comedy" else "Drama"), LOREM, playable = i % 3 != 2))
        }
        // Pad out to a realistic number of rows/cards for the performance measurement.
        for (i in 0 until 40) add(Title("mx$i", "Movie ${i + 1}", 1930 + i, Kind.Movie, listOf("Drama"), LOREM, playable = i % 2 == 0))
        series.forEachIndexed { i, n -> add(Title("s$i", n, 1950 + i, Kind.Series, listOf("Adventure"), LOREM, playable = i % 3 != 1, seasons = 1 + i % 4)) }
        for (i in 0 until 20) add(Title("sx$i", "Series ${i + 1}", 1960 + i, Kind.Series, listOf("Drama"), LOREM, playable = i % 2 == 1, seasons = 1 + i % 3))
        anime.forEachIndexed { i, n -> add(Title("a$i", n, 1990 + i, Kind.Series, listOf("Anime"), LOREM, playable = i % 2 == 0, seasons = 2)) }
        cartoons.forEachIndexed { i, n -> add(Title("c$i", n, 1935 + i, if (i == 4) Kind.Movie else Kind.Series, listOf("Cartoons"), LOREM, playable = true, seasons = 3)) }
    }
    fun byId(id: String) = titles.first { it.id == id }

    /** Categories shown as rows / tabs / grid sections. */
    val categories = listOf("Movies", "Series", "Anime", "Cartoons", "Comedy", "Drama")
    fun inCategory(c: String) = when (c) {
        "Movies" -> titles.filter { it.kind == Kind.Movie }
        "Series" -> titles.filter { it.kind == Kind.Series && "Anime" !in it.categories && "Cartoons" !in it.categories }
        else -> titles.filter { c in it.categories }
    }
}

/** In-memory "local storage": continue watching + watchlist. No persistence (prototype rule). */
object LocalState {
    val progress = mutableStateMapOf("m0" to 0.42f, "s0" to 0.7f, "m3" to 0.15f, "c1" to 0.9f, "a0" to 0.33f)
    val watchlist = mutableStateListOf("m1", "s2", "m5", "a1", "m9")
    val continueWatching get() = progress.keys.map(Fixture::byId)
    val watchlistTitles get() = watchlist.map(Fixture::byId)
    fun toggleWatchlist(id: String) { if (id in watchlist) watchlist.remove(id) else watchlist.add(0, id) }
}

// ---- Sources and Streams (shapes loosely follow "Design the Source contract") ----

sealed interface Lookup {
    data object Pending : Lookup
    data class Found(val streams: List<StreamOffer>) : Lookup
    data object NotAvailable : Lookup
    data class Failure(val reason: String) : Lookup
}

data class StreamOffer(val source: String, val label: String, val quality: String, val url: String)

/** Archive h.264 MP4 that really plays; the "Jellyfin" URL 404s so Failover fires on screen. */
const val REAL_MP4 = "https://archive.org/download/his_girl_friday/his_girl_friday.mp4"
const val BROKEN_URL = "https://archive.org/download/his_girl_friday/does-not-exist-1080p.mp4"

data class FakeSource(val name: String, val delayMs: Long, val result: (Title) -> Lookup)

val fakeSources = listOf(
    FakeSource("Jellyfin (Living room)", 600) { t ->
        if (t.playable) Lookup.Found(listOf(StreamOffer("Jellyfin (Living room)", "1080p · H.264 · 5.1", "1080p", BROKEN_URL)))
        else Lookup.NotAvailable
    },
    FakeSource("Internet Archive", 1500) { t ->
        if (t.playable) Lookup.Found(listOf(
            StreamOffer("Internet Archive", "720p · H.264 MP4", "720p", REAL_MP4),
            StreamOffer("Internet Archive", "480p · 512Kb MP4", "480p", REAL_MP4),
        )) else Lookup.NotAvailable
    },
    FakeSource("Imported Extension", 8000) { _ -> Lookup.Failure("Timed out after 8 s") },
)
