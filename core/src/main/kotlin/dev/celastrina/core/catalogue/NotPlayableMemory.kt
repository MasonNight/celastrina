package dev.celastrina.core.catalogue

import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Storage
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.LookupOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days

/**
 * The Titles this TV remembers as Not playable: those whose latest Lookup, in the past 7 days, found every Source Not
 * available. It survives restarts, and is forgotten whenever the Household's Sources change. One instance per app.
 */
class NotPlayableMemory(private val storage: Storage, private val clock: Clock) {

    private var remembered: List<Remembered>? = null
    private val revisions = MutableStateFlow(0)

    /** Goes up each time a Title is remembered or forgotten, so screens know to look at their badges again. */
    val revision: StateFlow<Int> = revisions.asStateFlow()

    /** Whether [title]'s latest Lookup found every Source Not available. */
    @Synchronized
    fun isNotPlayable(title: Title): Boolean = current().any { it.isOf(title.kind, title.tmdbId) }

    /**
     * Remembers what the finished [lookup] of [title] says: Not playable when every Source answered Not available, and
     * otherwise not, since it is the latest Lookup that counts. An episode's Lookup says nothing about its Series.
     */
    @Synchronized
    internal fun record(title: TitleReference, lookup: Lookup) {
        if (!lookup.isFinished || title.episode != null) return
        val everySourceNotAvailable = lookup.sources.isNotEmpty() && lookup.sources.all { it.outcome == LookupOutcome.NotAvailable }
        val others = current().filterNot { it.isOf(title.kind, title.tmdbId) }
        when {
            everySourceNotAvailable -> save(others + Remembered(title.kind, title.tmdbId, clock.nowMillis()))
            others.size != current().size -> save(others)
        }
    }

    /** Forgets every Not playable Title: the Household's Sources have changed. */
    @Synchronized
    internal fun forgetAll() {
        if (current().isNotEmpty()) save(emptyList())
    }

    /** What is remembered from the last 7 days. */
    private fun current(): List<Remembered> {
        val now = clock.nowMillis()
        return (remembered ?: load().also { remembered = it }).filter { now - it.atMillis < REMEMBERED_FOR }
    }

    private fun save(kept: List<Remembered>) {
        remembered = kept
        storage.write(KEY, json.encodeToString(Stored.serializer(), Stored(kept)).encodeToByteArray())
        revisions.value++
    }

    private fun load(): List<Remembered> =
        storage.read(KEY)?.let { json.decodeFromString(Stored.serializer(), it.decodeToString()).titles }.orEmpty()

    @Serializable
    private data class Stored(val titles: List<Remembered>)

    @Serializable
    private data class Remembered(val kind: Title.Kind, val tmdbId: Int, val atMillis: Long) {
        fun isOf(kind: Title.Kind, tmdbId: Int) = this.kind == kind && this.tmdbId == tmdbId
    }

    private companion object {
        const val KEY = "not-playable.json"
        val REMEMBERED_FOR = 7.days.inWholeMilliseconds
        val json = Json { ignoreUnknownKeys = true }
    }
}
