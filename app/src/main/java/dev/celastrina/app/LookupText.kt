package dev.celastrina.app

import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.LookupStatus

/** The quiet line under Play and in Choose Stream, such as "3 Streams from 2 Sources · best 720p · still asking 1". */
fun Lookup?.statusLine(): String = when (val status = this?.status) {
    null -> "Asking your Sources…"
    LookupStatus.NoSourceCanLook -> "None of your Sources can look for this"
    is LookupStatus.Found -> listOfNotNull(
        "${counted(status.streams, "Stream")} from ${counted(status.fromSources, "Source")}",
        status.bestResolution?.let { "best ${qualityOf(it)}" },
        "still asking ${status.stillAsking}".takeIf { status.stillAsking > 0 },
    ).joinToString(" · ")
    is LookupStatus.Asking -> "Asking ${counted(status.sources, "Source")}…"
    LookupStatus.NotAvailable -> "Not available from your Sources"
    is LookupStatus.NoStreams -> listOfNotNull(
        "No Streams",
        "${status.notAvailable} Not available".takeIf { status.notAvailable > 0 },
        "${status.failed} failed",
    ).joinToString(" · ")
}

private fun counted(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/** A picture height as the Household knows it: "480p", "1080p", "4K". */
fun qualityOf(resolution: Int): String = if (resolution >= 2160) "4K" else "${resolution}p"
