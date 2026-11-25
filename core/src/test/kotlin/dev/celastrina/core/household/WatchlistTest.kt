package dev.celastrina.core.household

import dev.celastrina.core.catalogue.Category
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.port.FileStorage
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchlistTest {

    @TempDir
    lateinit var dir: File

    /** The Watchlist kept where the app keeps it, so a new one on [dir] is the TV after a restart. */
    private fun watchlist() = Watchlist(FileStorage(dir))

    private val theMatrix = Title(603, Title.Kind.Movie, "The Matrix", 1999, "https://image.tmdb.org/t/p/w342/matrix.jpg", category = null)
    private val spiritedAway = Title(129, Title.Kind.Movie, "Spirited Away", 2001, null, Category.Anime)

    /** A Series with the same TMDB ID as [theMatrix]: TMDB numbers Movies and Series separately. */
    private val sameIdSeries = Title(603, Title.Kind.Series, "Some Series", 2010, null, category = null)

    @Test
    fun `a Title added to the Watchlist is in it after a restart, the most recently added first`() {
        val watchlist = watchlist()
        watchlist.add(theMatrix)
        watchlist.add(spiritedAway)

        val afterRestart = watchlist()

        assertEquals(listOf(spiritedAway, theMatrix), afterRestart.titles.value)
        assertTrue(theMatrix in afterRestart)
        assertFalse(sameIdSeries in afterRestart)
    }

    @Test
    fun `a Title removed from the Watchlist leaves it for good`() {
        val watchlist = watchlist()
        watchlist.add(theMatrix)
        watchlist.add(spiritedAway)

        watchlist.remove(theMatrix)

        assertEquals(listOf(spiritedAway), watchlist().titles.value)
        assertFalse(theMatrix in watchlist())
    }

    @Test
    fun `adding a Title again keeps it once, and the Title is known by its kind and TMDB ID`() {
        val watchlist = watchlist()
        watchlist.add(theMatrix)
        watchlist.add(spiritedAway)

        watchlist.add(theMatrix.copy(posterUrl = "https://image.tmdb.org/t/p/w342/matrix-new.jpg"))
        watchlist.remove(spiritedAway.copy(name = "千と千尋の神隠し"))

        assertEquals(listOf(theMatrix), watchlist().titles.value)
    }
}
