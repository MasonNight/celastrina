// PROTOTYPE — shared bits: cards, badges, Source lookups, the variant switcher pill.
package dev.celastrina.prototype.browsing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface Nav {
    fun detail(t: Title)
    fun play(t: Title, ep: Episode? = null, startAt: StreamOffer? = null)
    fun chooseStream(t: Title, ep: Episode? = null)
    fun back()
}

val Bg = Color(0xFF0E0F13)
val Dim = Color(0xFF9AA0AA)
val Accent = Color(0xFF7FB8FF)

@Composable
fun Art(url: String, modifier: Modifier = Modifier) =
    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.background(Color(0xFF22252C)))

@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) = Surface(
    onClick = onClick,
    modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White))),
    colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF22252C), focusedContainerColor = Color(0xFF22252C)),
    content = content,
)

@Composable
fun PosterCard(t: Title, nav: Nav, onFocus: () -> Unit = {}, width: Dp = 130.dp, modifier: Modifier = Modifier) =
    FocusCard({ nav.detail(t) }, modifier.width(width).aspectRatio(2f / 3f), onFocus) {
        Art(t.poster, Modifier.fillMaxSize())
        if (!t.playable) NotPlayableBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
    }

@Composable
fun LandscapeCard(t: Title, nav: Nav, onFocus: () -> Unit = {}, width: Dp = 220.dp, progress: Float? = null, modifier: Modifier = Modifier) =
    FocusCard({ if (progress != null) nav.play(t) else nav.detail(t) }, modifier.width(width).aspectRatio(16f / 9f), onFocus) {
        Art(t.landscape, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
        Text(t.name, Modifier.align(Alignment.BottomStart).padding(8.dp, 8.dp, 8.dp, if (progress != null) 12.dp else 8.dp),
            fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp, 0.dp, 8.dp, 5.dp))
        if (!t.playable) NotPlayableBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
    }

@Composable
fun ProgressBar(p: Float, modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
    Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Accent))
}

@Composable
fun NotPlayableBadge(modifier: Modifier = Modifier) =
    Text("Not playable", modifier.background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(5.dp, 2.dp), fontSize = 10.sp, color = Color(0xFFFFC27A))

@Composable
fun Meta(t: Title) = Text(
    buildString {
        append(t.year); append("  ·  "); append(if (t.kind == Kind.Movie) "Movie" else "Series · ${t.seasons} season${if (t.seasons > 1) "s" else ""}")
        append("  ·  "); append(t.categories.joinToString())
    }, color = Dim, fontSize = 14.sp,
)

@Composable
fun SectionTitle(s: String, modifier: Modifier = Modifier) = Text(s, modifier, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

/** Runs every Source lookup in parallel, as the detail screen would. */
@Composable
fun rememberLookups(t: Title): Map<String, Lookup> {
    val state = remember(t.id) { mutableStateMapOf<String, Lookup>().apply { fakeSources.forEach { put(it.name, Lookup.Pending) } } }
    LaunchedEffect(t.id) {
        fakeSources.forEach { s -> launch { delay(s.delayMs); state[s.name] = s.result(t) } }
    }
    return state
}

fun Map<String, Lookup>.offers() = fakeSources.flatMap { (this[it.name] as? Lookup.Found)?.streams ?: emptyList() }

@Composable
fun LookupLine(name: String, l: Lookup) = Row(verticalAlignment = Alignment.CenterVertically) {
    val (dot, text) = when (l) {
        Lookup.Pending -> Color(0xFF888888) to "Looking…"
        is Lookup.Found -> Color(0xFF6BD68B) to "${l.streams.size} Stream${if (l.streams.size > 1) "s" else ""} · best ${l.streams.first().quality}"
        Lookup.NotAvailable -> Color(0xFF555555) to "Not available"
        is Lookup.Failure -> Color(0xFFE06C6C) to l.reason
    }
    Box(Modifier.size(8.dp).background(dot, RoundedCornerShape(4.dp)))
    Spacer(Modifier.width(8.dp))
    Text(name, fontSize = 13.sp, color = Color.White)
    Spacer(Modifier.width(8.dp))
    Text(text, fontSize = 13.sp, color = Dim)
}

/** Floating, deliberately-not-the-design pill. ≡ (Menu) on the remote cycles variants. */
@Composable
fun PrototypeSwitcher(label: String, modifier: Modifier = Modifier) = Text(
    "◀  $label  ▶     ≡ Menu = next variant",
    modifier.background(Color(0xFFFFE04A), RoundedCornerShape(50)).padding(14.dp, 6.dp),
    color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
)
