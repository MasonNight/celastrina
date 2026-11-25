package dev.celastrina.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.celastrina.core.source.Lookup
import dev.celastrina.core.source.LookupOutcome
import dev.celastrina.core.source.SourceLookup
import dev.celastrina.core.source.Stream
import dev.celastrina.core.source.qualityOf
import java.util.Locale

/**
 * Every Stream found for [title], grouped by Source in the Household's order, with each Source's state: still
 * looking, Not available, or why it failed. Streams that arrive while it is open join the list.
 */
@Composable
fun ChooseStreamScreen(title: String, lookup: Lookup?, onPick: (Stream) -> Unit) {
    val first = remember { FocusRequester() }
    val firstStream = lookup?.streams?.firstOrNull()
    LaunchedEffect(firstStream != null) { if (firstStream != null) first.requestFocus() }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
        Text("Choose Stream", style = MaterialTheme.typography.headlineMedium)
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            lookup?.statusLine ?: "Asking your Sources…",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lookup?.sources.orEmpty().forEach { source ->
                item(key = "source:${source.source.id}") { SourceHeader(source) }
                val streams = (source.outcome as? LookupOutcome.Streams)?.streams.orEmpty()
                items(streams, key = { "stream:${it.sourceId}:${it.id}" }) { stream ->
                    StreamItem(stream, if (stream == firstStream) Modifier.focusRequester(first) else Modifier) { onPick(stream) }
                }
            }
        }
    }
}

@Composable
private fun SourceHeader(source: SourceLookup) {
    Row(Modifier.padding(top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(source.source.name, style = MaterialTheme.typography.titleMedium)
        Text(source.state(), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun StreamItem(stream: Stream, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        headlineContent = { Text(stream.label ?: stream.id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                stream.chips().forEach { Chip(it) }
            }
        },
    )
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun SourceLookup.state(): String = when (val outcome = outcome) {
    null -> "Looking…"
    LookupOutcome.NotAvailable -> "Not available"
    is LookupOutcome.Streams -> if (outcome.streams.size == 1) "1 Stream" else "${outcome.streams.size} Streams"
    is LookupOutcome.Failure -> outcome.reason.message()
}

/** What the Household reads for a Source's Failure. */
fun LookupOutcome.Failure.Reason.message(): String = when (this) {
    LookupOutcome.Failure.Reason.Unreachable -> "Can't reach it"
    LookupOutcome.Failure.Reason.AuthRequired -> "Needs logging in again"
    LookupOutcome.Failure.Reason.RateLimited -> "Too busy right now"
    LookupOutcome.Failure.Reason.SourceError -> "Source error"
}

/** The quality chips: picture, codec, container, audio languages, subtitles and size, whichever are known. */
private fun Stream.chips(): List<String> = listOfNotNull(
    resolution?.let(::qualityOf),
    videoCodec?.uppercase(Locale.ROOT),
    container?.uppercase(Locale.ROOT),
    audioLanguages.takeIf { it.isNotEmpty() }?.joinToString("/") { it.uppercase(Locale.ROOT) },
    subtitles.takeIf { it.isNotEmpty() }?.let { "Subtitles" },
    sizeBytes?.let(::sizeText),
)

private fun sizeText(bytes: Long): String =
    if (bytes >= 1_000_000_000) String.format(Locale.ROOT, "%.1f GB", bytes / 1e9) else "${bytes / 1_000_000} MB"
