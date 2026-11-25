package dev.celastrina.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.celastrina.core.configuration.TmdbKeyInUse

private val Dim = Color(0xFF9AA0AA)

/** Settings: which TMDB key is in use, and the ways to the Sources and About. */
@Composable
fun SettingsScreen(tmdbKey: TmdbKeyInUse, onSources: () -> Unit, onAbout: () -> Unit) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstItem.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("TMDB key", style = MaterialTheme.typography.titleMedium)
            Text(tmdbKey.description(), style = MaterialTheme.typography.bodyMedium, color = Dim)
        }
        ListItem(
            selected = false,
            onClick = onSources,
            headlineContent = { Text("Sources") },
            supportingContent = { Text("Add, order, enable, rename or remove where Celastrina plays from") },
            modifier = Modifier.widthIn(max = 640.dp).focusRequester(firstItem),
        )
        ListItem(
            selected = false,
            onClick = onAbout,
            headlineContent = { Text("About") },
            supportingContent = { Text("Version and TMDB attribution") },
            modifier = Modifier.widthIn(max = 640.dp),
        )
    }
}

/** About: Celastrina's version and TMDB's attribution, as TMDB's terms ask. */
@Composable
fun AboutScreen(appVersion: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("About", style = MaterialTheme.typography.headlineMedium)
        Text("Celastrina $appVersion", style = MaterialTheme.typography.titleMedium)
        Image(painterResource(R.drawable.tmdb_logo), contentDescription = "TMDB", modifier = Modifier.width(240.dp))
        Text(TMDB_DISCLAIMER, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 640.dp))
    }
}

/** TMDB's required wording, word for word. The README carries it too. */
const val TMDB_DISCLAIMER = "This product uses the TMDB API but is not endorsed or certified by TMDB."

private fun TmdbKeyInUse.description() = when (this) {
    TmdbKeyInUse.Project -> "Celastrina's own key."
    TmdbKeyInUse.Override -> "Your Household's own key, set from a computer."
    TmdbKeyInUse.None -> "No TMDB key. Ask your agent to set one."
}
