package dev.celastrina.core.catalogue

import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.port.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NoTmdbKeyTest {

    /** A Catalogue without a key has nothing to ask TMDB with, so it mustn't try. */
    private val unasked = object : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = error("TMDB was asked without a key: $url")
        override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse = error("TMDB was asked without a key: $url")
    }

    @Test
    fun `a build without a TMDB key, and no key set by the Household, has a Catalogue unavailable for want of a key`() = runTest {
        val catalogue = Catalogue(unasked, TmdbSettings(apiKey = ""))

        assertEquals(CatalogueUnavailable.Reason.NoKey, assertFailsWith<CatalogueUnavailable> { catalogue.moviesRow() }.reason)
        assertEquals(CatalogueUnavailable.Reason.NoKey, assertFailsWith<CatalogueUnavailable> { catalogue.movieDetails(10331) }.reason)
        assertEquals(CatalogueUnavailable.Reason.NoKey, assertFailsWith<CatalogueUnavailable> { catalogue.seriesDetails(19885) }.reason)
    }
}
