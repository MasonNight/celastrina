package dev.celastrina.core.source

import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.source.internetarchive.InternetArchiveSource

/** The Sources a new Household starts with, enabled and needing no settings: just the Internet Archive. */
fun preinstalledSources(http: HttpClient): List<Source> = listOf(InternetArchiveSource(http))
