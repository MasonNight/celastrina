package dev.celastrina.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.celastrina.core.failover.Failover
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.failover.FailoverStep
import dev.celastrina.core.failover.GiveUpReason
import dev.celastrina.core.failover.PlayerFault
import dev.celastrina.core.failover.SourceSummary
import dev.celastrina.core.failover.FailoverReason
import dev.celastrina.core.source.Stream

/**
 * The error screen when Failover gives up on [title]: why, what happened at each Source, and Retry and Choose Stream.
 * Retry plays Failover's first candidate again; both start a new playback session through [play].
 */
@Composable
fun GiveUpScreen(title: String, gaveUp: FailoverStep.GaveUp, lookup: TitleLookup, failoverMemory: FailoverMemory, play: (Stream, handPicked: Boolean) -> Unit) {
    val current by lookup.lookups.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    if (choosing) {
        BackHandler { choosing = false }
        ChooseStreamScreen(title, current) { play(it, true) }
        return
    }

    val retry = remember { FocusRequester() }
    val choose = remember { FocusRequester() }
    val retryWith = current?.firstChoice(failoverMemory)
    LaunchedEffect(retryWith != null) { (if (retryWith != null) retry else choose).requestFocusOnceShown() }
    CouldNotPlay(title, gaveUp.because.message(), gaveUp.sources) {
        Button(onClick = { retryWith?.let { play(it, false) } }, enabled = retryWith != null, modifier = Modifier.focusRequester(retry)) { Text("Retry") }
        Button(onClick = { choosing = true }, modifier = Modifier.focusRequester(choose)) { Text("Choose Stream") }
    }
}

/**
 * The screen when one click finds nothing this TV can play for [title]: what happened at each Source, Back
 * ([onBack]) and Choose Stream, which lists whatever Streams were found and [play]s the one picked.
 */
@Composable
fun NothingToPlayScreen(title: String, sources: List<SourceSummary>, lookup: TitleLookup, onBack: () -> Unit, play: (Stream) -> Unit) {
    val current by lookup.lookups.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    if (choosing) {
        BackHandler { choosing = false }
        ChooseStreamScreen(title, current, play)
        return
    }

    val back = remember { FocusRequester() }
    LaunchedEffect(Unit) { back.requestFocusOnceShown() }
    CouldNotPlay(title, "None of your Sources has a Stream this TV can play.", sources) {
        Button(onClick = onBack, modifier = Modifier.focusRequester(back)) { Text("Back") }
        Button(onClick = { choosing = true }) { Text("Choose Stream") }
    }
}

/** "Couldn't play [title]", [why], what happened at each of the [sources], and [buttons]. */
@Composable
private fun CouldNotPlay(title: String, why: String, sources: List<SourceSummary>, buttons: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Couldn't play $title", style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(why, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { source ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(source.sourceName, style = MaterialTheme.typography.titleSmall)
                    Text(source.state.message(), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { buttons() }
    }
}

private fun GiveUpReason.message(): String = when (this) {
    GiveUpReason.NothingLeftToTry -> "None of the Streams found would play."
    GiveUpReason.NoPlaybackTimeLimit -> "Nothing played for ${Failover.NO_PLAYBACK_LIMIT.inWholeSeconds} seconds, so Celastrina stopped trying."
}

/** What happened at one Source, such as "2 Streams didn't play: an HTTP error, no picture within 15 s · 1 not tried". */
private fun SourceSummary.State.message(): String = when (this) {
    is SourceSummary.State.StreamsFailed -> buildString {
        append(if (failures.size == 1) "Its Stream didn't play: " else "${failures.size} Streams didn't play: ")
        append(failures.map { it.message() }.distinct().joinToString(", "))
        if (notTried > 0) append(" · $notTried not tried")
    }
    is SourceSummary.State.NotTried -> if (streams == 1) "1 Stream not tried" else "$streams Streams not tried"
    SourceSummary.State.NothingThisTvCanDecode -> "Only Streams this TV can't decode"
    SourceSummary.State.NotAvailable -> "Not available"
    is SourceSummary.State.LookupFailed -> reason.message()
    SourceSummary.State.StillLooking -> "Still looking…"
}

private fun FailoverReason.message(): String = when (this) {
    FailoverReason.AddressCheckTimeout -> "address check timed out"
    FailoverReason.NoFirstFrame -> "no picture within 15 s"
    FailoverReason.LongStall -> "stalled for over 20 s"
    FailoverReason.RepeatedStalls -> "stalled 3 times in 2 minutes"
    is FailoverReason.Fault -> when (fault) {
        is PlayerFault.Http -> "an HTTP error"
        PlayerFault.Network -> "a network error"
        PlayerFault.UnsupportedFormat -> "a format this TV can't play"
        PlayerFault.Decoder -> "a decoder error"
        PlayerFault.Other -> "a player error"
    }
}
