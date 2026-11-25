package dev.celastrina.core.source

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.catalogue.TitleReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One configured instance of a [SourceKind] that, given a Title, can offer Streams for it. */
interface Source {
    /** Stable for the life of the Source, whatever it is called. */
    val id: String
    val name: String
    val kind: SourceKind

    /**
     * Asks this Source for Streams of [title]. Only called when [kind] can match the Title.
     * Answers [LookupOutcome.Failure] rather than throwing, but a Source that throws is treated as `source_error`.
     */
    suspend fun lookUp(title: TitleReference): LookupOutcome
}

/** A type of Source Celastrina knows how to talk to, and which Titles its Sources can match. */
data class SourceKind(
    val name: String,
    /** The IDs a Source of this Kind can match a Title on; a Title needs at least one of them. */
    val idSchemes: Set<IdScheme>,
    val titleKinds: Set<Title.Kind>,
    /** Stable, for the agent and for storage, such as `internet-archive`. */
    val id: String = slugOf(name),
    /** How long a Lookup waits for a Source of this Kind before counting it unreachable. */
    val lookupTimeout: Duration = 8.seconds,
) {
    fun canMatch(title: TitleReference): Boolean =
        title.kind in titleKinds && idSchemes.any { it.isKnownFor(title) }
}

enum class IdScheme {
    Tmdb,
    Imdb,
    Wikidata,
    ;

    fun isKnownFor(title: TitleReference): Boolean = when (this) {
        Tmdb -> true
        Imdb -> title.imdbId != null
        Wikidata -> title.wikidataId != null
    }
}

/** What a Source answers: exactly one of Streams, Not available, or a typed Failure. */
sealed interface LookupOutcome {

    data class Streams(val streams: List<Stream>) : LookupOutcome {
        init {
            require(streams.isNotEmpty()) { "A Source with no Streams answers Not available" }
        }
    }

    /** The Source works, but has nothing for this Title. A normal answer, not a Failure. */
    data object NotAvailable : LookupOutcome

    data class Failure(val reason: Reason, val retryAfterMillis: Long? = null) : LookupOutcome {
        enum class Reason {
            /** The Source couldn't be reached, or didn't answer in time. */
            Unreachable,

            /** The Household must log in to the Source again. */
            AuthRequired,

            /** The Source is refusing requests for now; [retryAfterMillis] says for how long, when it said. */
            RateLimited,

            /** The Source answered, but not with something usable. */
            SourceError,
        }
    }
}
