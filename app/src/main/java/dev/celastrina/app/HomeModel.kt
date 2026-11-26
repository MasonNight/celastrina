package dev.celastrina.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.catalogue.CatalogueUnreadable
import dev.celastrina.core.catalogue.Hero
import dev.celastrina.core.catalogue.HomeRow
import dev.celastrina.core.catalogue.Row
import dev.celastrina.core.catalogue.Title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RowState {
    data object Loading : RowState
    data class Loaded(val row: Row) : RowState
    data class Failed(val reason: CatalogueUnreadable.Reason) : RowState
}

/**
 * Home's state, kept for as long as the app runs so that coming back from a detail screen finds the rows, the hero
 * and every scroll position as they were. Loading runs in [scope]; TMDB's answers are read off the main thread, as a
 * hero's (with every translation) is big enough to drop frames on the AFTTI43.
 */
class HomeModel(private val catalogue: Catalogue, private val scope: CoroutineScope) {

    val rows = mutableStateMapOf<HomeRow, RowState>()
    val columnState = LazyListState()
    val rowStates = HomeRow.entries.associateWith { LazyListState() }
    val householdRowStates = HouseholdRow.entries.associateWith { LazyListState() }

    /** The hero on screen. It changes only once focus has rested on a card (see HomeScreen). */
    var hero by mutableStateOf<Pair<Title, Hero>?>(null)
        private set

    /** The card to focus when home shows: the last Title opened, or the first card, by its [HomeRow] or [HouseholdRow]. */
    var focusTarget by mutableStateOf<Pair<Any, Title>?>(null)

    /** Whether home shows with focus on the Search button instead, as it does coming back from search. */
    var focusSearch by mutableStateOf(false)

    private val loadingMore = mutableSetOf<HomeRow>()
    private val refreshing = mutableSetOf<HomeRow>()
    private val heroes = object : LinkedHashMap<Title, Hero>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Title, Hero>) = size > HEROES_KEPT
    }

    init {
        HomeRow.entries.forEach(::load)
    }

    /**
     * Loads again each row that couldn't load, and each shown from what the cache kept while offline, as when the TV
     * comes back online. A row from the cache stays on screen while it loads; if TMDB still can't be reached, it stays
     * as it was.
     */
    fun retryFailed() {
        rows.filterValues { it is RowState.Failed }.keys.forEach(::load)
        rows.filterValues { it is RowState.Loaded && it.row.fromOfflineCache }.keys.forEach(::refresh)
    }

    private fun refresh(homeRow: HomeRow) {
        if (!refreshing.add(homeRow)) return
        scope.launch {
            try {
                val row = offMain { catalogue.row(homeRow) }
                if (!row.fromOfflineCache) rows[homeRow] = RowState.Loaded(row)
            } catch (_: CatalogueUnreadable) {
                // Still offline: the row keeps what it shows.
            } finally {
                refreshing.remove(homeRow)
            }
        }
    }

    private fun load(homeRow: HomeRow) {
        rows[homeRow] = RowState.Loading
        scope.launch {
            rows[homeRow] = try {
                RowState.Loaded(offMain { catalogue.row(homeRow) })
            } catch (e: CatalogueUnreadable) {
                RowState.Failed(e.reason)
            }
        }
    }

    /** Loads [homeRow]'s next Titles, unless they're loading already or the row is complete. */
    fun loadMore(homeRow: HomeRow) {
        val row = (rows[homeRow] as? RowState.Loaded)?.row ?: return
        if (!row.canLoadMore || !loadingMore.add(homeRow)) return
        scope.launch {
            try {
                rows[homeRow] = RowState.Loaded(offMain { catalogue.more(row) })
            } catch (_: CatalogueUnreadable) {
                // The row keeps what it has; scrolling to its end again tries again.
            } finally {
                loadingMore.remove(homeRow)
            }
        }
    }

    /** Shows [title] in the hero once its details arrive. A failure leaves the hero as it was. */
    suspend fun showHero(title: Title) {
        val hero = heroes[title] ?: try {
            offMain { catalogue.hero(title) }.also { heroes[title] = it }
        } catch (_: CatalogueUnreadable) {
            return
        }
        this.hero = title to hero
    }

    private suspend fun <T> offMain(read: suspend () -> T): T = withContext(Dispatchers.Default) { read() }

    private companion object {
        const val HEROES_KEPT = 60
    }
}

/**
 * The card home focuses when it first shows: the first card of the first row that has any, top to bottom, once every
 * row above it has loaded or failed. Each row is its [HomeRow] or [HouseholdRow] and its Titles, null while it loads.
 */
fun firstCard(rows: List<Pair<Any, List<Title>?>>): Pair<Any, Title>? {
    for ((row, titles) in rows) {
        if (titles == null) return null
        titles.firstOrNull()?.let { return row to it }
    }
    return null
}
