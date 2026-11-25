package dev.celastrina.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.NewSource
import dev.celastrina.core.configuration.QuickConnect
import dev.celastrina.core.configuration.WriteOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Dim = Color(0xFF9AA0AA)
private val Warning = Color(0xFFFFB4A8)

/** The Source Kinds the Household can add on the TV, by their IDs. */
private val ADDABLE_KINDS = listOf("jellyfin", "emby", "stremio")

/**
 * Add Source: pick a Source Kind, fill in what it needs, and Celastrina saves and tests it through the configuration
 * service, with the same rules and messages the agent gets. A Jellyfin server can log in with Quick Connect instead of
 * a password. [onDone] goes back to the Sources, once a Source is saved.
 */
@Composable
fun AddSourceScreen(configuration: ConfigurationService, onExtensions: () -> Unit, onDone: () -> Unit) {
    var kind by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Add Source", style = MaterialTheme.typography.headlineMedium)
        when (val chosen = kind) {
            null -> KindChoice(onExtensions) { kind = it }
            else -> {
                BackHandler { kind = null }
                SourceForm(chosen, configuration, onDone)
            }
        }
    }
}

@Composable
private fun KindChoice(onExtensions: () -> Unit, onChoose: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Text("What kind of Source?", style = MaterialTheme.typography.titleMedium)
    ADDABLE_KINDS.forEachIndexed { index, kind ->
        ListItem(
            selected = false,
            onClick = { onChoose(kind) },
            headlineContent = { Text(kindName(kind)) },
            supportingContent = { Text(kindHint(kind)) },
            modifier = Modifier.widthIn(max = 800.dp).then(if (index == 0) Modifier.focusRequester(first) else Modifier),
        )
    }
    // Extensions' Sources come from installing them, so this leads to the Extensions screen.
    ListItem(
        selected = false,
        onClick = onExtensions,
        headlineContent = { Text(kindName("extension")) },
        supportingContent = { Text("From an Extension Repository you import. Extensions are unverified code from third parties.") },
        modifier = Modifier.widthIn(max = 800.dp),
    )
}

private fun kindHint(kind: String) = when (kind) {
    "jellyfin" -> "Your own Jellyfin server. Log in with Quick Connect or a username and password."
    "emby" -> "Your own Emby server. Log in with a username and password."
    else -> "An add-on, by its manifest URL ending /manifest.json."
}

/** How the form is going: being filled in, saving and testing, or waiting for a Quick Connect code to be approved. */
private sealed interface FormState {
    data object Editing : FormState
    data object Saving : FormState
    data class Approving(val waiting: QuickConnect.Waiting) : FormState

    /** Saved, but failing its test: it stays saved, so the Household can fix it from Sources. */
    data class SavedFailing(val message: String) : FormState
}

@Composable
private fun SourceForm(kind: String, configuration: ConfigurationService, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<FormState>(FormState.Editing) }
    val scope = rememberCoroutineScope()
    val isServer = kind == "jellyfin" || kind == "emby"

    fun finish(outcome: WriteOutcome) {
        when (outcome) {
            WriteOutcome.Ok, is WriteOutcome.Added -> onDone()
            is WriteOutcome.Invalid -> {
                message = outcome.message
                state = FormState.Editing
            }
            is WriteOutcome.Failed -> state = FormState.SavedFailing(outcome.message)
        }
    }

    fun add() {
        if (state != FormState.Editing) return
        state = FormState.Saving
        message = null
        scope.launch {
            val new = NewSource(
                kind = kind,
                name = name,
                url = url.ifBlank { null },
                username = if (isServer) username else null,
                password = if (isServer) password else null,
            )
            finish(withContext(Dispatchers.IO) { configuration.add(new, ChangedFrom.Tv) })
        }
    }

    fun quickConnect() {
        if (state != FormState.Editing) return
        state = FormState.Saving
        message = null
        scope.launch {
            when (val step = withContext(Dispatchers.IO) { configuration.startQuickConnect(NewSource(kind = kind, name = name, url = url)) }) {
                is QuickConnect.Waiting -> state = FormState.Approving(step)
                is QuickConnect.Finished -> finish(step.outcome)
                is QuickConnect.Failed -> {
                    message = step.message
                    state = FormState.Editing
                }
            }
        }
    }

    Text(kindName(kind), style = MaterialTheme.typography.titleMedium)
    when (val s = state) {
        FormState.Editing, FormState.Saving -> {
            TvTextField("Name", name, { name = it }, placeholder = if (isServer) "Movie Night ${kindName(kind)}" else "My add-on", focusFirst = true)
            if (isServer) {
                TvTextField("Server address", url, { url = it }, keyboardType = KeyboardType.Uri, placeholder = "http://192.168.1.20:8096")
            } else {
                // The manifest URL may hold the add-on's settings, even a key, so it shows as dots like a password.
                TvTextField("Manifest URL", url, { url = it }, secret = true, keyboardType = KeyboardType.Uri, placeholder = "https://…/manifest.json")
            }
            if (isServer) {
                TvTextField("Username", username, { username = it })
                TvTextField("Password (may be empty)", password, { password = it }, secret = true)
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Warning, modifier = Modifier.widthIn(max = 800.dp)) }
            if (s == FormState.Saving) Text("Testing the connection…", style = MaterialTheme.typography.bodyLarge, color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::add) { Text(if (isServer) "Log in and add" else "Add") }
                if (kind == "jellyfin") OutlinedButton(onClick = ::quickConnect) { Text("Use Quick Connect instead") }
            }
        }
        is FormState.Approving -> {
            BackHandler { state = FormState.Editing }
            QuickConnectCode(s.waiting.code, name)
            LaunchedEffect(s.waiting) {
                while (true) {
                    delay(QUICK_CONNECT_POLL_MILLIS)
                    when (val step = withContext(Dispatchers.IO) { configuration.continueQuickConnect(s.waiting, ChangedFrom.Tv) }) {
                        is QuickConnect.Waiting -> continue
                        is QuickConnect.Finished -> finish(step.outcome)
                        is QuickConnect.Failed -> {
                            message = step.message
                            state = FormState.Editing
                        }
                    }
                    break
                }
            }
        }
        is FormState.SavedFailing -> {
            val back = remember { FocusRequester() }
            LaunchedEffect(Unit) { back.requestFocus() }
            Text(
                "${name.trim()} is saved, but it failed its connection test: ${s.message}",
                style = MaterialTheme.typography.bodyLarge,
                color = Warning,
                modifier = Modifier.widthIn(max = 800.dp),
            )
            Text("It stays in your Sources, marked failing, so you can fix it there or ask your agent.", color = Dim)
            Button(onClick = onDone, modifier = Modifier.focusRequester(back)) { Text("Back to Sources") }
        }
    }
}

@Composable
private fun QuickConnectCode(code: String, name: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 800.dp)) {
        Text(
            "On a phone or computer, sign in to ${name.trim()}, open your profile's Quick Connect page, and enter this code:",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(code, style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 12.sp), modifier = Modifier.padding(vertical = 8.dp))
        Text("Waiting for you to approve it… Press Back to stop.", color = Dim)
    }
}

private const val QUICK_CONNECT_POLL_MILLIS = 3_000L
