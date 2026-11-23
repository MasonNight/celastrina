// PROTOTYPE — Variant B "Category tabs + spotlight".
// Home: Categories are tabs across the top; the focused Title's backdrop fills the whole
// screen and there is ONE row of landscape cards along the bottom. You change Category by
// going up to the tabs, not by scrolling down. Detail: a two-column panel; Sources and
// their Streams are listed inline (no Choose Stream screen), and Series use a
// seasons column + episode list (master/detail).
package dev.celastrina.prototype.browsing

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

private val tabsB = listOf("Home", "Watchlist") + Fixture.categories

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeB(nav: Nav) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = when (tabsB[tab]) {
        "Home" -> LocalState.continueWatching + Fixture.titles.filter { it.playable }.take(12)
        "Watchlist" -> LocalState.watchlistTitles
        else -> Fixture.inCategory(tabsB[tab])
    }
    var focused by remember(tab) { mutableStateOf(titles.firstOrNull()) }
    var heroId by remember { mutableStateOf(focused?.id) }
    LaunchedEffect(focused?.id) { kotlinx.coroutines.delay(300); heroId = focused?.id }
    Box(Modifier.fillMaxSize()) {
        Crossfade(heroId, label = "spot") { id ->
            Box(Modifier.fillMaxSize()) {
                if (id != null) Art(Fixture.byId(id).backdrop, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xAA000000), 0.25f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color(0xF0000000))))
            }
        }
        TabRow(selectedTabIndex = tab, Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) {
            tabsB.forEachIndexed { i, n -> Tab(selected = tab == i, onFocus = { tab = i }) { Text(n, Modifier.padding(14.dp, 6.dp), fontSize = 15.sp) } }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(bottom = 56.dp)) {
            focused?.let { t ->
                Column(Modifier.padding(start = 56.dp, bottom = 20.dp).fillMaxWidth(0.5f)) {
                    Text(t.name, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Meta(t)
                    Text(t.synopsis, color = Color(0xFFD0D3D9), fontSize = 14.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                }
            }
            LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(titles, key = { it.id }) { t -> LandscapeCard(t, nav, { focused = t }, width = 240.dp, progress = if (tab == 0) LocalState.progress[t.id] else null) }
            }
            if (titles.isEmpty()) Text("Nothing here yet", Modifier.padding(start = 56.dp), color = Dim)
        }
    }
}

@Composable
fun DetailB(t: Title, nav: Nav) {
    val lookups = rememberLookups(t)
    var season by remember { mutableIntStateOf(1) }
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { play.requestFocus() }
    Box(Modifier.fillMaxSize()) {
        Art(t.backdrop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0xD90E0F13)))
        Row(Modifier.fillMaxSize().padding(48.dp, 40.dp, 48.dp, 60.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Column(Modifier.width(260.dp)) {
                Art(t.poster, Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.height(16.dp))
                Text(t.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Meta(t)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { nav.play(t) }, Modifier.focusRequester(play).fillMaxWidth()) { Text(if (LocalState.progress[t.id] != null) "Resume" else "Play best Stream") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { LocalState.toggleWatchlist(t.id) }, Modifier.fillMaxWidth()) { Text(if (t.id in LocalState.watchlist) "✓ In watchlist" else "+ Watchlist") }
            }
            if (t.kind == Kind.Series) {
                LazyColumn(Modifier.width(170.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { SectionTitle("Seasons") }
                    items((1..t.seasons).toList()) { s ->
                        ListItem(selected = season == s, onClick = { season = s }, headlineContent = { Text("Season $s") })
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { SectionTitle("Season $season") }
                    items(t.episodes(season), key = { "${it.season}-${it.number}" }) { e ->
                        ListItem(selected = false, onClick = { nav.play(t, e) },
                            leadingContent = { Art(e.still, Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp))) },
                            headlineContent = { Text("${e.number}. ${e.name}") },
                            supportingContent = { Text("${e.minutes} min") },
                            trailingContent = { Text("⋯", Modifier.padding(4.dp)) })
                    }
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(t.synopsis, color = Color(0xFFD0D3D9), fontSize = 15.sp)
                    Spacer(Modifier.height(24.dp))
                    SourcesInline(t, lookups, nav)
                }
            }
        }
    }
}

/** Every Source and its Streams, inline on the detail screen. */
@Composable
private fun SourcesInline(t: Title, lookups: Map<String, Lookup>, nav: Nav) {
    SectionTitle("Where it plays from")
    Spacer(Modifier.height(8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fakeSources.forEach { s ->
            val l = lookups[s.name] ?: Lookup.Pending
            item(s.name) { Box(Modifier.padding(top = 8.dp)) { LookupLine(s.name, l) } }
            if (l is Lookup.Found) items(l.streams, key = { s.name + it.label }) { o ->
                ListItem(selected = false, onClick = { nav.play(t, null, o) },
                    headlineContent = { Text(o.label) },
                    leadingContent = { Text(o.quality, Modifier.background(Accent, RoundedCornerShape(4.dp)).padding(6.dp, 2.dp), color = Color.Black, fontSize = 12.sp) })
            }
        }
    }
}
