package dev.celastrina.core.source

/** Where a Lookup of one Title has got to: each Source asked, in the Household's order, with its answer once it has one. */
data class Lookup(val sources: List<SourceLookup>) {

    val isFinished: Boolean get() = sources.none { it.isLooking }

    /** Every Stream found so far, in the Household's Source order and then each Source's own order. */
    val streams: List<Stream> get() = sources.flatMap { (it.outcome as? LookupOutcome.Streams)?.streams.orEmpty() }

    /** One quiet line for the detail screen, such as "3 Streams from 2 Sources · best 720p · still asking 1". */
    val statusLine: String
        get() {
            if (sources.isEmpty()) return "None of your Sources can look for this"
            val looking = sources.count { it.isLooking }
            val streams = streams
            if (streams.isNotEmpty()) {
                val from = sources.count { it.outcome is LookupOutcome.Streams }
                val best = streams.mapNotNull { it.resolution }.maxOrNull()?.let { "best ${qualityOf(it)}" }
                return listOfNotNull(
                    "${counted(streams.size, "Stream")} from ${counted(from, "Source")}",
                    best,
                    "still asking $looking".takeIf { looking > 0 },
                ).joinToString(" · ")
            }
            if (looking > 0) return "Asking ${counted(looking, "Source")}…"
            val notAvailable = sources.count { it.outcome == LookupOutcome.NotAvailable }
            val failed = sources.size - notAvailable
            if (failed == 0) return "Not available from your Sources"
            return listOfNotNull("No Streams", "$notAvailable Not available".takeIf { notAvailable > 0 }, "$failed failed").joinToString(" · ")
        }

    private fun counted(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}

/** One Source in a [Lookup]: its answer, or null while it is still being asked. */
data class SourceLookup(val source: Source, val outcome: LookupOutcome?) {
    val isLooking: Boolean get() = outcome == null
}

/** A picture height as the Household knows it: "480p", "1080p", "4K". */
fun qualityOf(resolution: Int): String = if (resolution >= 2160) "4K" else "${resolution}p"
