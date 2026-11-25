package dev.celastrina.core.catalogue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The search screen's state: the [query] typed so far on its keyboard, and the [results] for it. Search looks only in
 * the [catalogue], never asks the Sources, and keeps nothing once it's [close]d, so it leaves no history.
 *
 * Results follow the query live: once it has 2 or more characters, it's searched for about 400 ms after the last key
 * press. A newer query discards an older one still waiting for TMDB, so the results never go back to an older query's.
 * Work runs in [scope] until [close].
 */
class Search(private val catalogue: Catalogue, scope: CoroutineScope) {

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val editing = MutableStateFlow("")
    private val shown = MutableStateFlow<SearchResults>(SearchResults.Loading)
    private var trending: List<Title>? = null

    val query: StateFlow<String> = editing.asStateFlow()
    val results: StateFlow<SearchResults> = shown.asStateFlow()

    init {
        CoroutineScope(scope.coroutineContext + job).launch {
            editing.map { it.trim() }.distinctUntilChanged().collectLatest { query ->
                if (query.length < MIN_LENGTH) {
                    shown.value = trending()
                } else {
                    delay(DEBOUNCE_MILLIS)
                    shown.value = find(query)
                }
            }
        }
    }

    /** Adds [character], a letter, digit or space from the keyboard, to the end of the query. */
    fun type(character: Char) = editing.update { it + character }

    /** Deletes the query's last character. */
    fun delete() = editing.update { it.dropLast(1) }

    /** Empties the query, which brings back trending. */
    fun clear() = editing.update { "" }

    /** Stops searching; a closed Search changes no more. */
    fun close() = job.cancel()

    private suspend fun trending(): SearchResults = try {
        SearchResults.Trending(trending ?: catalogue.trending().also { trending = it })
    } catch (e: CatalogueUnavailable) {
        SearchResults.Unavailable(e.reason)
    }

    private suspend fun find(query: String): SearchResults = try {
        val titles = catalogue.search(query)
        if (titles.isEmpty()) SearchResults.NoMatches(query) else SearchResults.Found(query, titles)
    } catch (e: CatalogueUnavailable) {
        SearchResults.Unavailable(e.reason)
    }

    private companion object {
        const val MIN_LENGTH = 2
        const val DEBOUNCE_MILLIS = 400L
    }
}

/** What the search screen shows beside its keyboard. */
sealed interface SearchResults {
    /** Nothing to show yet: trending is on its way. */
    data object Loading : SearchResults

    /** Fewer than 2 characters are typed, so it shows this week's trending Movies and Series. */
    data class Trending(val titles: List<Title>) : SearchResults

    /** The Movies and Series found for [query], in TMDB's relevance order. */
    data class Found(val query: String, val titles: List<Title>) : SearchResults

    /** Nothing matches [query]. */
    data class NoMatches(val query: String) : SearchResults

    /** The Catalogue couldn't be read, for [reason]. */
    data class Unavailable(val reason: CatalogueUnavailable.Reason) : SearchResults
}
