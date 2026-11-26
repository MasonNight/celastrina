package dev.celastrina.app

import dev.celastrina.core.catalogue.HomeRow
import dev.celastrina.core.catalogue.Title
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FirstCardTest {

    private val detour = title(1, "Detour")
    private val charade = title(2, "Charade")
    private val pioneerOne = title(3, "Pioneer One", Title.Kind.Series)

    @Test
    fun home_waits_for_the_movies_row_even_when_the_series_row_loads_first() {
        val loading = listOf(HomeRow.Movies to null, HomeRow.Series to listOf(pioneerOne))
        val loaded = listOf(HomeRow.Movies to listOf(detour, charade), HomeRow.Series to listOf(pioneerOne))

        assertNull(firstCard(loading))
        assertEquals(HomeRow.Movies to detour, firstCard(loaded))
    }

    @Test
    fun continue_watching_comes_before_the_catalogue_rows_however_they_stand() {
        val rows = listOf(HouseholdRow.ContinueWatching to listOf(charade), HomeRow.Movies to null)

        assertEquals(HouseholdRow.ContinueWatching to charade, firstCard(rows))
    }

    @Test
    fun rows_that_are_empty_or_failed_are_passed_over() {
        val rows = listOf(HouseholdRow.ContinueWatching to emptyList(), HomeRow.Movies to emptyList(), HomeRow.Series to listOf(pioneerOne))

        assertEquals(HomeRow.Series to pioneerOne, firstCard(rows))
    }

    private fun title(id: Int, name: String, kind: Title.Kind = Title.Kind.Movie) = Title(id, kind, name, year = null, posterUrl = null, category = null)
}
