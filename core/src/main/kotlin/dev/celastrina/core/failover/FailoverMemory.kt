package dev.celastrina.core.failover

import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Storage
import dev.celastrina.core.source.Stream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.hours

/**
 * This TV's Failover memory: the Streams that failed here in the last 24 hours, by Source and Stream ID, which go to
 * the end of the candidate order. It survives restarts. One instance per app, shared by every playback session.
 */
class FailoverMemory(private val storage: Storage, private val clock: Clock) {

    private var demotions: List<Demotion>? = null

    /** Remembers that [stream] failed just now, which keeps it at the end of the order for the next 24 hours. */
    @Synchronized
    fun demote(stream: Stream) {
        val now = clock.nowMillis()
        val kept = current(now).filterNot { it.isOf(stream) } + Demotion(stream.sourceId, stream.id, now)
        demotions = kept
        storage.write(KEY, json.encodeToString(StoredDemotions.serializer(), StoredDemotions(kept)).encodeToByteArray())
    }

    /** Whether [stream] failed on this TV in the last 24 hours. */
    @Synchronized
    fun isDemoted(stream: Stream): Boolean = current(clock.nowMillis()).any { it.isOf(stream) }

    private fun current(now: Long): List<Demotion> {
        val all = demotions ?: load().also { demotions = it }
        return all.filter { now - it.atMillis < DEMOTED_FOR }
    }

    private fun load(): List<Demotion> =
        storage.read(KEY)?.let { json.decodeFromString(StoredDemotions.serializer(), it.decodeToString()).demotions }.orEmpty()

    @Serializable
    private data class StoredDemotions(val demotions: List<Demotion>)

    @Serializable
    private data class Demotion(val sourceId: String, val streamId: String, val atMillis: Long) {
        fun isOf(stream: Stream) = sourceId == stream.sourceId && streamId == stream.id
    }

    private companion object {
        const val KEY = "failover-memory.json"
        val DEMOTED_FOR = 24.hours.inWholeMilliseconds
        val json = Json { ignoreUnknownKeys = true }
    }
}
