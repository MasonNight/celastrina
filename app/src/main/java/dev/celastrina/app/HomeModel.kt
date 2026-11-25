package dev.celastrina.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.celastrina.core.catalogue.Catalogue
import dev.celastrina.core.catalogue.CatalogueUnavailable
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
    data class Failed(val reason: CatalogueUnavailable.Reason) : RowState
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

    /** The hero on screen. It changes only once focus has rested on a card (see HomeScreen). */
    var hero by mutableStateOf<Pair<Title, Hero>?>(null)
        private set

    /** The card to focus when home shows: the last Title opened, or the first card. */
    var focusTarget by mutableStateOf<Pair<HomeRow, Title>?>(null)

    /** Whether home shows with focus on the Search button instead, as it does coming back from search. */
    var focusSearch by mutableStateOf(false)

    private val loadingMore = mutableSetOf<HomeRow>()
    private val heroes = object : LinkedHashMap<Title, Hero>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Title, Hero>) = size > HEROES_KEPT
    }

    init {
        HomeRow.entries.forEach { homeRow ->
            rows[homeRow] = RowState.Loading
            scope.launch {
                rows[homeRow] = try {
                    RowState.Loaded(offMain { catalogue.row(homeRow) })
                } catch (e: CatalogueUnavailable) {
                    RowState.Failed(e.reason)
                }
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
            } catch (_: CatalogueUnavailable) {
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
        } catch (_: CatalogueUnavailable) {
            return
        }
        this.hero = title to hero
    }

    private suspend fun <T> offMain(read: suspend () -> T): T = withContext(Dispatchers.Default) { read() }

    private companion object {
        const val HEROES_KEPT = 60
    }
}
