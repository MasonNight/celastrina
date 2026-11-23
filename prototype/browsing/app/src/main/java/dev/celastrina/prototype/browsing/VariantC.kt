// PROTOTYPE — Variant C "Side rail + grid".
// Home: a left rail (Home, Watchlist, each Category) and a dense poster grid; no hero art,
// the focused Title's info sits in a strip at the bottom. Detail: not a new page but a side
// sheet over the dimmed grid; the Play button says which Source/quality it will use, and
// Series use a season stepper with a compact text episode list.
package dev.celastrina.prototype.browsing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

private val railC = listOf("⌂  Home", "★  Watchlist") + Fixture.categories.map { "▤  $it" }

@Composable
fun HomeC(nav: Nav) {
    var section by remember { mutableIntStateOf(0) }
    var railOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<Title?>(null) }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(if (railOpen) 220.dp else 64.dp).fillMaxHeight().background(Color(0xFF15171D)).padding(vertical = 40.dp, horizontal = 8.dp)
                .onFocusChanged { railOpen = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            railC.forEachIndexed { i, label ->
                ListItem(selected = section == i, onClick = { section = i }, modifier = Modifier.onFocusChanged { if (it.isFocused) section = i },
                    headlineContent = { Text(if (railOpen) label else label.take(1), maxLines = 1) })
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val groups: List<Pair<String, List<Title>>> = when (section) {
                0 -> listOf("Continue watching" to LocalState.continueWatching, "Watchlist" to LocalState.watchlistTitles, "Playable now" to Fixture.titles.filter { it.playable })
                1 -> listOf("Watchlist" to LocalState.watchlistTitles)
                else -> Fixture.categories[section - 2].let { c -> listOf(c to Fixture.inCategory(c)) }
            }
            LazyVerticalGrid(GridCells.Fixed(7), Modifier.weight(1f), contentPadding = PaddingValues(32.dp, 32.dp, 32.dp, 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                groups.forEach { (name, titles) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h-$name") { SectionTitle(name) }
                    items(titles, key = { "$name-${it.id}" }) { t ->
                        Column {
                            PosterCard(t, nav, { focused = t }, width = 1000.dp /* fills cell */, modifier = Modifier.fillMaxWidth())
                            LocalState.progress[t.id]?.let { if (name == "Continue watching") ProgressBar(it, Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(70.dp).background(Color(0xFF15171D)).padding(horizontal = 32.dp), contentAlignment = Alignment.CenterStart) {
                focused?.let { t -> Column { Text(t.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Meta(t) } }
            }
        }
    }
}

@Composable
fun DetailC(t: Title, nav: Nav) {
    val lookups = rememberLookups(t)
    var season by remember { mutableIntStateOf(1) }
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { play.requestFocus() }
    Box(Modifier.fillMaxSize()) {
        Art(t.backdrop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)))
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(520.dp).background(Color(0xF2181A20))) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Art(t.backdrop, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xF2181A20))))
                Text(t.name, Modifier.align(Alignment.BottomStart).padding(24.dp, 8.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            LazyColumn(contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 60.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Meta(t) }
                item {
                    val best = lookups.offers().firstOrNull()
                    val pending = lookups.values.any { it == Lookup.Pending }
                    Button(onClick = { nav.play(t) }, Modifier.focusRequester(play).fillMaxWidth()) {
                        Column {
                            Text(if (LocalState.progress[t.id] != null) "Resume" else "Play")
                            Text(when { best != null -> "${best.quality} from ${best.source}"; pending -> "finding a Stream…"; else -> "no Source has it" }, fontSize = 11.sp)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.chooseStream(t) }) { Text("Other Streams") }
                        OutlinedButton(onClick = { LocalState.toggleWatchlist(t.id) }) { Text(if (t.id in LocalState.watchlist) "✓ Watchlist" else "+ Watchlist") }
                    }
                }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { lookups.forEach { (n, l) -> SourceDot(n, l) } } }
                item { Text(t.synopsis, color = Color(0xFFD0D3D9), fontSize = 14.sp) }
                if (t.kind == Kind.Series) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { season = if (season == 1) t.seasons else season - 1 }) { Text("◀") }
                            Text("Season $season of ${t.seasons}", Modifier.padding(horizontal = 16.dp), color = Color.White, fontSize = 16.sp)
                            OutlinedButton(onClick = { season = season % t.seasons + 1 }) { Text("▶") }
                        }
                    }
                    items(t.episodes(season), key = { "${it.season}-${it.number}" }) { e ->
                        ListItem(selected = false, onClick = { nav.play(t, e) },
                            headlineContent = { Text("${e.number}. ${e.name}") }, trailingContent = { Text("${e.minutes}m") })
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceDot(name: String, l: Lookup) {
    val c = when (l) { Lookup.Pending -> Color(0xFF888888); is Lookup.Found -> Color(0xFF6BD68B); Lookup.NotAvailable -> Color(0xFF555555); is Lookup.Failure -> Color(0xFFE06C6C) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(c, RoundedCornerShape(4.dp)))
        Text(" " + name.substringBefore(" ("), color = Dim, fontSize = 11.sp)
    }
}
