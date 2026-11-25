package dev.celastrina.core.source

/** A Source's ID made from its display name: lowercase ASCII letters and digits joined by '-', like `movie-night-jellyfin`. */
fun slugOf(name: String): String =
    name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString("-").ifEmpty { "source" }
