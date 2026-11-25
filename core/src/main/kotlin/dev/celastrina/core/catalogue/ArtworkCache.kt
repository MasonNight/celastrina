package dev.celastrina.core.catalogue

import dev.celastrina.core.port.Clock
import java.io.File

/**
 * Artwork (posters, backdrops, logos, stills) lives in the platform's LRU disk cache, which evicts by size alone. This
 * adds the Catalogue cache's 6-month cap on age.
 */
object ArtworkCache {

    /**
     * Deletes the artwork in the disk cache at [dir] saved 6 months ago or more. The cache's journal stays, and the
     * cache drops an entry whose files are gone when next asked for it.
     */
    fun purge(dir: File, clock: Clock) {
        val now = clock.nowMillis()
        dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith(JOURNAL) && now - it.lastModified() >= CatalogueCache.MAX_AGE_MILLIS }
            .forEach { it.delete() }
    }

    private const val JOURNAL = "journal"
}
