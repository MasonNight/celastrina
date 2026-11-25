package dev.celastrina.core.catalogue

import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.Storage
import java.security.MessageDigest

/**
 * What the Catalogue has read from TMDB, kept in [storage] so browsing is fast on repeat visits and still works
 * offline. One cache serves every Catalogue the app makes, so a new TMDB key keeps what's cached.
 *
 * Nothing is kept longer than [MAX_AGE_MILLIS] (6 months): older answers are deleted when the cache is first used and
 * whenever it stores something. An index of what's stored, and when, is kept in [storage] too, since storage can't be
 * listed.
 */
class CatalogueCache(private val storage: Storage, private val clock: Clock) {

    private val lock = Any()

    /** Each stored key and when it was stored. Read from storage at first use. */
    private var index: MutableMap<String, Long>? = null

    /** TMDB's answer to [request], if it was stored less than [freshForMillis] ago. */
    internal fun fresh(request: String, freshForMillis: Long): String? = read(request, freshForMillis)

    /** TMDB's answer to [request], however old it is short of being purged, for when TMDB can't be reached. */
    internal fun kept(request: String): String? = read(request, MAX_AGE_MILLIS)

    internal fun store(request: String, body: String) = synchronized(lock) {
        val index = loadedIndex()
        val key = keyOf(request)
        val now = clock.nowMillis()
        storage.write(key, "$now\n$body".encodeToByteArray())
        index[key] = now
        purge(index)
        saveIndex(index)
    }

    private fun read(request: String, maxAgeMillis: Long): String? = synchronized(lock) {
        loadedIndex()
        val text = storage.read(keyOf(request))?.decodeToString() ?: return null
        val storedAt = text.substringBefore('\n').toLongOrNull() ?: return null
        text.substringAfter('\n').takeIf { clock.nowMillis() - storedAt < maxAgeMillis }
    }

    private fun loadedIndex(): MutableMap<String, Long> = index ?: run {
        val loaded = storage.read(INDEX_KEY)?.decodeToString().orEmpty().lineSequence()
            .mapNotNull { line -> line.split(' ').takeIf { it.size == 2 }?.let { (key, at) -> at.toLongOrNull()?.let { key to it } } }
            .toMap(mutableMapOf())
        if (purge(loaded)) saveIndex(loaded)
        loaded.also { index = it }
    }

    /** Deletes what's 6 months old from [index] and storage, and says whether anything was. */
    private fun purge(index: MutableMap<String, Long>): Boolean {
        val now = clock.nowMillis()
        val expired = index.filterValues { now - it >= MAX_AGE_MILLIS }.keys
        expired.forEach { key ->
            storage.delete(key)
            index.remove(key)
        }
        return expired.isNotEmpty()
    }

    private fun saveIndex(index: Map<String, Long>) =
        storage.write(INDEX_KEY, index.entries.joinToString("\n") { (key, at) -> "$key $at" }.encodeToByteArray())

    private fun keyOf(request: String): String =
        KEY_PREFIX + MessageDigest.getInstance("SHA-256").digest(request.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        /** 6 months at most: 182 days is less than any 6 calendar months. */
        const val MAX_AGE_MILLIS = 182 * 24 * 60 * 60 * 1000L

        private const val KEY_PREFIX = "catalogue-"
        private const val INDEX_KEY = "catalogue-index"
    }
}
