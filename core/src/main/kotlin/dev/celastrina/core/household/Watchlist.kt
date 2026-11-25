package dev.celastrina.core.household

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.port.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * This TV's Watchlist: Titles the Household saved for later, known by their Title kind and TMDB ID. It survives
 * restarts, and removing a Source leaves it as it is. One instance per app.
 */
class Watchlist(private val storage: Storage) {

    private val state = MutableStateFlow(load())

    /** The Watchlist row: the most recently added first. */
    val titles: StateFlow<List<Title>> = state.asStateFlow()

    operator fun contains(title: Title): Boolean = state.value.any { it.isSameAs(title) }

    /** Adds [title] at the front. A Title already in the Watchlist stays where it is. */
    @Synchronized
    fun add(title: Title) {
        if (title in this) return
        save(listOf(title) + state.value)
    }

    @Synchronized
    fun remove(title: Title) {
        if (title !in this) return
        save(state.value.filterNot { it.isSameAs(title) })
    }

    private fun save(titles: List<Title>) {
        storage.write(KEY, json.encodeToString(StoredWatchlist.serializer(), StoredWatchlist(titles.map(::stored))).encodeToByteArray())
        state.value = titles
    }

    private fun load(): List<Title> =
        storage.read(KEY)?.let { json.decodeFromString(StoredWatchlist.serializer(), it.decodeToString()).titles.map(::restored) }.orEmpty()

    @Serializable
    private data class StoredWatchlist(val titles: List<StoredTitle>)

    private companion object {
        const val KEY = "watchlist.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}

private fun Title.isSameAs(other: Title) = kind == other.kind && tmdbId == other.tmdbId
