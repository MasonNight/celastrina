package dev.celastrina.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationChange
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * A short notice in the top-right corner for each change made from a computer while Celastrina is on screen.
 * It blocks nothing and takes no focus; a newer change replaces the one showing.
 */
@Composable
fun ChangedFromComputerNotices(changes: Flow<ConfigurationChange>) {
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(changes) {
        changes.collectLatest { change ->
            if (change.from != ChangedFrom.Computer) return@collectLatest
            notice = change.notice()
            delay(NOTICE_MILLIS)
            notice = null
        }
    }
    notice?.let { CornerNotice(it) }
}

/** A notice in the top-right corner over whatever is on screen. It blocks nothing and takes no focus. */
@Composable
fun CornerNotice(text: String) {
    Box(Modifier.fillMaxSize().padding(top = 32.dp, end = 48.dp), contentAlignment = Alignment.TopEnd) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .background(Color(0xE6202020), RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

/** How long a notice shows: about 6 s. */
const val NOTICE_MILLIS = 6_000L

/** "Source removed from a computer: Internet Archive". */
fun ConfigurationChange.notice(): String = when (action) {
    ConfigurationChange.Action.Added -> "Source added from a computer: $sourceName"
    ConfigurationChange.Action.Changed -> "Source changed from a computer: $sourceName"
    ConfigurationChange.Action.Reordered -> "Source order changed from a computer: $sourceName first"
    ConfigurationChange.Action.Renamed -> "Source renamed from a computer: $previousName → $sourceName"
    ConfigurationChange.Action.Enabled -> "Source enabled from a computer: $sourceName"
    ConfigurationChange.Action.Disabled -> "Source disabled from a computer: $sourceName"
    ConfigurationChange.Action.Removed -> "Source removed from a computer: $sourceName"
    ConfigurationChange.Action.TmdbKeySet -> "TMDB key set from a computer"
    ConfigurationChange.Action.TmdbKeyCleared -> "TMDB key cleared from a computer: back to the project key"
    ConfigurationChange.Action.RepositoryImported -> "Extension Repository imported from a computer: $sourceName"
}
