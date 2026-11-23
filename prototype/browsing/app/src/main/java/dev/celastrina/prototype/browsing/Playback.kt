// PROTOTYPE — shared Player (real Media3, real Failover on a real 404) and Choose Stream list.
package dev.celastrina.prototype.browsing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import kotlinx.coroutines.delay

private fun describe(e: PlaybackException): String {
    val http = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return if (http != null) "HTTP ${http.responseCode}" else e.errorCodeName
}

@Composable
fun Player(t: Title, ep: Episode?, startAt: StreamOffer?) {
    val lookups = rememberLookups(t)
    val anyPending = lookups.values.any { it == Lookup.Pending }
    val ctx = LocalContext.current
    // Candidates in Source order; a picked Stream goes first.
    val candidates = remember(lookups.offers(), startAt) { listOfNotNull(startAt) + lookups.offers().filter { it != startAt } }
    var index by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var gaveUp by remember { mutableStateOf(false) }
    val player = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { player.release() } }

    val current = candidates.getOrNull(index)
    LaunchedEffect(current?.url, current?.source, index) {
        if (current != null) { player.setMediaItem(MediaItem.fromUri(current.url)); player.prepare() }
        else if (!anyPending && index > 0) gaveUp = true
    }
    DisposableEffect(candidates) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val failed = candidates.getOrNull(index) ?: return
                val next = candidates.getOrNull(index + 1)
                notice = if (next != null) "${failed.source} didn't work (${describe(error)}). Switched to ${next.source} · ${next.quality}."
                else "${failed.source} didn't work (${describe(error)}). No other Streams."
                index++
            }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    LaunchedEffect(notice) { if (notice != null) { delay(6000); notice = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ PlayerView(it).apply { this.player = player; useController = true } }, Modifier.fillMaxSize())
        if (current == null || gaveUp) {
            Column(Modifier.align(Alignment.Center).background(Color(0xDD000000), RoundedCornerShape(12.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (gaveUp || (!anyPending && candidates.isEmpty())) "Nothing can play ${t.name} right now" else "Finding a Stream for ${t.name}${ep?.let { " · S${it.season} E${it.number}" } ?: ""}…",
                    fontSize = 20.sp, color = Color.White)
                lookups.forEach { (n, l) -> LookupLine(n, l) }
            }
        }
        notice?.let {
            Text(it, Modifier.align(Alignment.TopEnd).padding(24.dp).widthIn(max = 420.dp).background(Color(0xE6202020), RoundedCornerShape(8.dp)).padding(14.dp),
                color = Color.White, fontSize = 14.sp)
        }
        current?.let {
            Text("Prototype: ${it.source} · ${it.label}", Modifier.align(Alignment.TopStart).padding(16.dp), color = Color(0x99FFFFFF), fontSize = 11.sp)
        }
    }
}

@Composable
fun ChooseStream(t: Title, ep: Episode?, onPick: (StreamOffer) -> Unit) {
    val lookups = rememberLookups(t)
    val first = remember { FocusRequester() }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.35f).fillMaxHeight()) {
            Art(t.backdrop, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.BottomStart).padding(32.dp)) {
                Text(t.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                ep?.let { Text("Season ${it.season} · Episode ${it.number}", color = Dim) }
            }
        }
        LazyColumn(Modifier.weight(0.65f).padding(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle("Choose Stream") }
            fakeSources.forEach { s ->
                val l = lookups[s.name] ?: Lookup.Pending
                item(s.name) { Spacer(Modifier.height(8.dp)); LookupLine(s.name, l) }
                if (l is Lookup.Found) items(l.streams, key = { s.name + it.label }) { o ->
                    val isFirst = o == lookups.offers().firstOrNull()
                    Surface(onClick = { onPick(o) }, modifier = Modifier.fillMaxWidth().then(if (isFirst) Modifier.focusRequester(first) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1C1F26), focusedContainerColor = Color(0xFF34405A))) {
                        Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(o.quality, Modifier.background(Accent, RoundedCornerShape(4.dp)).padding(6.dp, 2.dp), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp)); Text(o.label, color = Color.White)
                        }
                    }
                    if (isFirst) LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                }
            }
        }
    }
}
