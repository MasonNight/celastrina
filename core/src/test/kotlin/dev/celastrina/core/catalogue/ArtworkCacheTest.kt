package dev.celastrina.core.catalogue

import dev.celastrina.core.testing.VirtualClock
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

class ArtworkCacheTest {

    @TempDir
    lateinit var dir: File

    private val clock = VirtualClock()

    @Test
    fun `artwork is purged from its disk cache once it is 6 months old`() {
        val poster = artwork("poster.1", savedDaysAgo = 183)
        val backdrop = artwork("backdrop.1", savedDaysAgo = 170)
        val journal = artwork("journal", savedDaysAgo = 400)

        ArtworkCache.purge(dir, clock)

        assertEquals(listOf(backdrop), dir.listFiles()!!.filter { it.isFile && it != journal })
        assertEquals(false, poster.exists())
        assertEquals(true, journal.exists(), "The disk cache's own journal is kept, or the cache would lose track of the rest")
    }

    private fun artwork(name: String, savedDaysAgo: Int): File =
        File(dir, name).apply {
            writeText(name)
            setLastModified(clock.nowMillis() - savedDaysAgo.days.inWholeMilliseconds)
        }
}
