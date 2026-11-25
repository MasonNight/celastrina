package dev.celastrina.core.source

import kotlinx.serialization.Serializable

/**
 * Names one Source for its whole life, whatever it is called later: lowercase ASCII letters and digits joined by '-',
 * like `movie-night-jellyfin`, made from its name when it is added. Stored and sent to the agent as the plain text.
 */
@JvmInline
@Serializable
value class SourceId(val value: String) {
    override fun toString() = value

    companion object {
        /** The ID a Source called [name] is given, unless another Source has it already. */
        fun of(name: String) = SourceId(slugOf(name))
    }
}

/** [name] as lowercase ASCII letters and digits joined by '-', like `movie-night-jellyfin`. */
internal fun slugOf(name: String): String =
    name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString("-").ifEmpty { "source" }
