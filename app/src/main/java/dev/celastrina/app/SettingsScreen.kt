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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.celastrina.core.configuration.TmdbKeyInUse

/**
 * Settings: the ways to the Sources, the Extensions ("N updates available"), the TMDB key (saying which is in use) and
 * About.
 */
@Composable
fun SettingsScreen(
    tmdbKey: TmdbKeyInUse,
    updatesAvailable: Int,
    onSources: () -> Unit,
    onExtensions: () -> Unit,
    onTmdbKey: () -> Unit,
    onAbout: () -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstItem.requestFocusOnceShown() }
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        ListItem(
            selected = false,
            onClick = onSources,
            headlineContent = { Text("Sources") },
            supportingContent = { Text("Add, order, enable, rename or remove where Celastrina plays from") },
            modifier = Modifier.widthIn(max = 640.dp).focusRequester(firstItem),
        )
        ListItem(
            selected = false,
            onClick = onExtensions,
            headlineContent = { Text("Extensions") },
            supportingContent = { Text(if (updatesAvailable > 0) updatesText(updatesAvailable) else "Import Extension Repositories, and update or remove Extensions") },
            modifier = Modifier.widthIn(max = 640.dp),
        )
        ListItem(
            selected = false,
            onClick = onTmdbKey,
            headlineContent = { Text("TMDB key") },
            supportingContent = { Text(tmdbKey.description()) },
            modifier = Modifier.widthIn(max = 640.dp),
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
fun AboutScreen(appVersion: String, onBack: () -> Unit) {
    val back = remember { FocusRequester() }
    LaunchedEffect(Unit) { back.requestFocusOnceShown() }
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("About", style = MaterialTheme.typography.headlineMedium)
        Text("Celastrina $appVersion", style = MaterialTheme.typography.titleMedium)
        Image(painterResource(R.drawable.tmdb_logo), contentDescription = "TMDB", modifier = Modifier.width(240.dp))
        Text(TMDB_DISCLAIMER, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 640.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.focusRequester(back)) { Text("Back to Settings") }
    }
}

/** TMDB's required wording, word for word. The README carries it too. */
const val TMDB_DISCLAIMER = "This product uses the TMDB API but is not endorsed or certified by TMDB."
