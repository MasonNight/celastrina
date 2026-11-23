// PROTOTYPE — Variant A "Hero + stacked rows".
// Home: the focused Title's backdrop and blurb fill the top; Categories are stacked
// horizontal rows you scroll down through. Detail: full-bleed backdrop, Play /
// Choose Stream / Watchlist buttons; Series get season tabs and an episode row underneath.
// Stream choice lives behind a button; the Source lookups show as a quiet status line.
package dev.celastrina.prototype.browsing

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeA(nav: Nav) {
    var focused by remember { mutableStateOf(LocalState.continueWatching.first()) }
    // Backdrop only follows focus once it rests (300 ms), so flicking through cards doesn't decode a backdrop per keypress.
    var heroId by remember { mutableStateOf(focused.id) }
    LaunchedEffect(focused.id) { kotlinx.coroutines.delay(300); heroId = focused.id }
    val rows = buildList {
        add("Continue watching" to LocalState.continueWatching)
        add("Watchlist" to LocalState.watchlistTitles)
        Fixture.categories.forEach { add(it to Fixture.inCategory(it)) }
    }
    Box(Modifier.fillMaxSize()) {
        Crossfade(heroId, Modifier.fillMaxWidth().fillMaxHeight(0.62f).align(androidx.compose.ui.Alignment.TopEnd), label = "hero") { id ->
            Box(Modifier.fillMaxSize()) {
                Art(Fixture.byId(id).backdrop, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.55f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Bg)))
            }
        }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(48.dp, 40.dp, 48.dp, 0.dp).fillMaxWidth(0.45f).height(190.dp)) {
                Text(focused.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Meta(focused)
                Spacer(Modifier.height(8.dp))
                Text(focused.synopsis, color = Color(0xFFD0D3D9), fontSize = 14.sp, maxLines = 3)
                if (!focused.playable) { Spacer(Modifier.height(6.dp)); NotPlayableBadge() }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(rows, key = { it.first }) { (name, titles) ->
                    Column {
                        SectionTitle(name, Modifier.padding(start = 48.dp, bottom = 8.dp))
                        LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(titles, key = { it.id }) { t ->
                                if (name == "Continue watching") LandscapeCard(t, nav, { focused = t }, progress = LocalState.progress[t.id])
                                else PosterCard(t, nav, { focused = t })
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DetailA(t: Title, nav: Nav) {
    val lookups = rememberLookups(t)
    var season by remember { mutableIntStateOf(1) }
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { play.requestFocus() }
    Box(Modifier.fillMaxSize()) {
        Art(t.backdrop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.7f to Color(0x66000000))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Bg)))
        LazyColumn(contentPadding = PaddingValues(48.dp, 56.dp, 48.dp, 80.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(Modifier.fillMaxWidth(0.5f)) {
                    Text(t.name, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Meta(t)
                    Spacer(Modifier.height(12.dp))
                    Text(t.synopsis, color = Color(0xFFD0D3D9), fontSize = 15.sp)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val resume = LocalState.progress[t.id]
                    Button(onClick = { nav.play(t) }, Modifier.focusRequester(play)) { Text(if (resume != null) "Resume" else "Play") }
                    OutlinedButton(onClick = { nav.chooseStream(t) }) { Text("Choose Stream") }
                    OutlinedButton(onClick = { LocalState.toggleWatchlist(t.id) }) { Text(if (t.id in LocalState.watchlist) "✓ Watchlist" else "+ Watchlist") }
                }
            }
            item {
                val offers = lookups.offers()
                val pending = lookups.values.count { it == Lookup.Pending }
                Text(when {
                    offers.isNotEmpty() -> "${offers.size} Streams from ${offers.map { it.source }.distinct().size} Sources · best ${offers.first().quality}" + if (pending > 0) " · still asking $pending" else ""
                    pending > 0 -> "Asking ${pending} Sources…"
                    else -> "No Source can play this right now"
                }, color = Dim, fontSize = 13.sp)
            }
            if (t.kind == Kind.Series) {
                item {
                    TabRow(selectedTabIndex = season - 1) {
                        (1..t.seasons).forEach { s -> Tab(selected = season == s, onFocus = { season = s }) { Text("Season $s", Modifier.padding(12.dp, 6.dp)) } }
                    }
                }
                item {
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(t.episodes(season), key = { "${it.season}-${it.number}" }) { e ->
                            Column(Modifier.width(240.dp)) {
                                FocusCard({ nav.play(t, e) }, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { Art(e.still, Modifier.fillMaxSize()) }
                                Text("${e.number}. ${e.name}", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                                Text("${e.minutes} min", color = Dim, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
