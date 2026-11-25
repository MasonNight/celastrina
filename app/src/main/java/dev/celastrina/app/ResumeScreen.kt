package dev.celastrina.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.failover.ResumeChoice
import dev.celastrina.core.failover.resumeChoice
import dev.celastrina.core.household.Watched
import dev.celastrina.core.household.Watching
import kotlinx.coroutines.delay

/**
 * What a Continue watching card opens: it asks the Sources for the Title or episode and starts playback where it got to,
 * on the Stream that last worked as soon as that arrives, or Play's choice if it hasn't within 3 s (see [resumeChoice]). When no Source has anything to play,
 * [onNothingToPlay] opens the detail screen instead, where the Household can see why.
 */
@Composable
fun ResumeScreen(
    watched: Watched,
    lookUp: (TitleReference) -> TitleLookup,
    failoverMemory: FailoverMemory,
    onPlay: (PlayRequest) -> Unit,
    onNothingToPlay: () -> Unit,
) {
    val watching = watched.watching
    val titleLookup = remember(watched) { lookUp(watching.reference) }
    val lookup by titleLookup.lookups.collectAsState()
    // Resuming waits only briefly for the Stream that last worked, so the time is checked again as it passes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(titleLookup) {
        while (true) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val choice = lookup?.resumeChoice(MediaCodecDecoders, failoverMemory, watched.lastWorked, now) ?: ResumeChoice.Waiting
    LaunchedEffect(choice) {
        when (choice) {
            is ResumeChoice.Play -> onPlay(
                PlayRequest(choice.stream, handPicked = false, titleLookup, watching.playerTitle(), watched.resumeAtMillis, watching),
            )
            ResumeChoice.NothingToPlay -> onNothingToPlay()
            ResumeChoice.Waiting -> Unit
        }
    }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(watching.imageUrl).size(960, 540).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
        Column(Modifier.align(Alignment.Center).padding(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Resuming ${watching.playerTitle()}…", style = MaterialTheme.typography.headlineSmall)
            Text(lookup.statusLine(), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

/** The name the player and its notices use: the Title's, and the episode's for a Series. */
private fun Watching.playerTitle() = episodeLabel?.let { "${title.name} · $it" } ?: title.name
