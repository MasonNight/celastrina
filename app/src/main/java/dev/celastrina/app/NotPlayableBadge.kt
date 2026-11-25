package dev.celastrina.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.celastrina.core.catalogue.NotPlayableMemory
import dev.celastrina.core.catalogue.Title

/**
 * The Titles remembered as Not playable, for [NotPlayableBadge] wherever a Title appears. Provided once around the
 * whole app, so a card or screen shows the badge without having the memory passed down to it.
 */
val LocalNotPlayable = staticCompositionLocalOf<NotPlayableMemory?> { null }

/** "Not playable", when [title]'s latest Lookup in the past week found every Source Not available; otherwise nothing. */
@Composable
fun NotPlayableBadge(title: Title, modifier: Modifier = Modifier) {
    val memory = LocalNotPlayable.current ?: return
    val revision by memory.revision.collectAsState()
    val notPlayable = remember(memory, revision, title.kind, title.tmdbId) { memory.isNotPlayable(title) }
    if (!notPlayable) return
    Text(
        "Not playable",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
