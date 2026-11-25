package dev.celastrina.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.TmdbKeyInUse
import dev.celastrina.core.configuration.WriteOutcome

private val Dim = Color(0xFF9AA0AA)
private val Warning = Color(0xFFFFB4A8)

/**
 * Sets the Household's own TMDB key, or clears it to go back to Celastrina's (story 63). The key is typed as a secret
 * and never shown, here or anywhere else. [onDone] goes back once the key is saved or cleared.
 */
@Composable
fun TmdbKeyScreen(tmdbKey: TmdbKeyInUse, configuration: ConfigurationService, onDone: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun finish(outcome: WriteOutcome) {
        when (outcome) {
            is WriteOutcome.Invalid -> message = outcome.message
            is WriteOutcome.Failed -> message = outcome.message
            WriteOutcome.Ok, is WriteOutcome.Added -> onDone()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("TMDB key", style = MaterialTheme.typography.headlineMedium)
        Text(tmdbKey.description(), style = MaterialTheme.typography.bodyLarge, color = Dim, modifier = Modifier.widthIn(max = 800.dp))
        Text(
            "Celastrina reads its Catalogue from TMDB. If Celastrina's own key stops working, use a key of your own from " +
                "themoviedb.org: an API Read Access Token or an API key.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            modifier = Modifier.widthIn(max = 800.dp),
        )
        TvTextField("Your TMDB key", key, { key = it }, secret = true, focusFirst = true)
        message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Warning, modifier = Modifier.widthIn(max = 800.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { finish(configuration.setTmdbKey(key, ChangedFrom.Tv)) }) { Text("Use this key") }
            if (tmdbKey == TmdbKeyInUse.Override) {
                OutlinedButton(onClick = { finish(configuration.clearTmdbKey(ChangedFrom.Tv)) }) { Text("Go back to Celastrina's key") }
            }
        }
    }
}

/** Which TMDB key is in use, as Settings and the TMDB key screen say it. */
fun TmdbKeyInUse.description() = when (this) {
    TmdbKeyInUse.Project -> "Celastrina's own key."
    TmdbKeyInUse.Override -> "Your Household's own key."
    TmdbKeyInUse.None -> "No TMDB key. Set one here, or ask your agent to."
}
