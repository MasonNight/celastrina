package dev.celastrina.core.source

/**
 * Where a Lookup of one Title has got to: each Source asked, in the Household's order, with its answer once it has one.
 * [askedAtMillis] is when the Sources were last asked, so Failover can tell a Stream list that may have expired.
 */
data class Lookup(val sources: List<SourceLookup>, val askedAtMillis: Long = 0) {

    val isFinished: Boolean get() = sources.none { it.isLooking }

    /** Every Stream found so far, in the Household's Source order and then each Source's own order. */
    val streams: List<Stream> get() = sources.flatMap { (it.outcome as? LookupOutcome.Streams)?.streams.orEmpty() }

    /** Where the Lookup has got to, in a word, for the quiet line on the detail screen. */
    val status: LookupStatus
        get() {
            if (sources.isEmpty()) return LookupStatus.NoSourceCanLook
            val looking = sources.count { it.isLooking }
            val streams = streams
            if (streams.isNotEmpty()) {
                return LookupStatus.Found(
                    streams = streams.size,
                    fromSources = sources.count { it.outcome is LookupOutcome.Streams },
                    bestResolution = streams.mapNotNull { it.resolution }.maxOrNull(),
                    stillAsking = looking,
                )
            }
            if (looking > 0) return LookupStatus.Asking(looking)
            val notAvailable = sources.count { it.outcome == LookupOutcome.NotAvailable }
            val failed = sources.size - notAvailable
            return if (failed == 0) LookupStatus.NotAvailable else LookupStatus.NoStreams(notAvailable, failed)
        }
}

/** Where a [Lookup] has got to. */
sealed interface LookupStatus {
    /** No Source the Household has can match this Title. */
    data object NoSourceCanLook : LookupStatus

    /** [streams] Streams so far, from [fromSources] Sources, the tallest [bestResolution] pixels high where known. */
    data class Found(val streams: Int, val fromSources: Int, val bestResolution: Int?, val stillAsking: Int) : LookupStatus

    /** Nothing found yet, with [sources] still being asked. */
    data class Asking(val sources: Int) : LookupStatus

    /** Every Source answered Not available. */
    data object NotAvailable : LookupStatus

    /** Every Source has answered, without Streams: [notAvailable] Not available and [failed] with a Failure. */
    data class NoStreams(val notAvailable: Int, val failed: Int) : LookupStatus
}

/**
 * One Source in a [Lookup]: its answer, or null while it is still being asked. While it is [askingAgain], [outcome] is
 * its answer from last time, so the Streams it found stay listed until it answers again.
 */
data class SourceLookup(val source: Source, val outcome: LookupOutcome?, val askingAgain: Boolean = false) {
    val isLooking: Boolean get() = outcome == null || askingAgain
}
