package dev.celastrina.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.SourceChanges
import dev.celastrina.core.configuration.SourceSummary
import dev.celastrina.core.configuration.TestState
import dev.celastrina.core.configuration.WriteOutcome
import dev.celastrina.core.source.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Dim = Color(0xFF9AA0AA)
private val Warning = Color(0xFFFFB4A8)

/**
 * The Household's Sources in the order Lookups ask them, with the way to Add Source. Picking a Source opens what
 * can be done to it: move it up or down, enable or disable it, rename it or remove it (after a confirmation). Changes
 * made from a computer while it is open show at once.
 */
@Composable
fun SourcesScreen(configuration: ConfigurationService, onAddSource: () -> Unit) {
    var sources by remember { mutableStateOf(configuration.list()) }
    var pickedId by remember { mutableStateOf<SourceId?>(null) }
    /** What the last change answered, when it wasn't simply done. */
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(configuration) { configuration.changes.collect { sources = configuration.list() } }

    fun run(change: suspend () -> WriteOutcome, after: () -> Unit = {}) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { change() }
            sources = configuration.list()
            busy = false
            message = outcome.problemMessage()
            after()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Sources", style = MaterialTheme.typography.headlineMedium)
        val picked = sources.find { it.id == pickedId }
        if (picked == null) {
            SourceList(sources, message, onAddSource) {
                message = null
                pickedId = it.id
            }
        } else {
            BackHandler {
                message = null
                pickedId = null
            }
            SourceActions(
                source = picked,
                count = sources.size,
                busy = busy,
                message = message,
                onMove = { by ->
                    val ids = sources.map { it.id }.toMutableList()
                    val from = ids.indexOf(picked.id)
                    ids.add(from + by, ids.removeAt(from))
                    run({ configuration.setOrder(ids, ChangedFrom.Tv) })
                },
                onToggle = {
                    run({ if (picked.enabled) configuration.disable(picked.id, ChangedFrom.Tv) else configuration.enable(picked.id, ChangedFrom.Tv) })
                },
                onRename = { name -> run({ configuration.update(picked.id, SourceChanges(name = name), ChangedFrom.Tv) }) },
                onRemove = { run({ configuration.remove(picked.id, ChangedFrom.Tv) }) { pickedId = null } },
            )
        }
    }
}

@Composable
private fun SourceList(sources: List<SourceSummary>, message: String?, onAddSource: () -> Unit, onPick: (SourceSummary) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusOnceShown() }
    message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Warning) }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "add") {
            ListItem(
                selected = false,
                onClick = onAddSource,
                headlineContent = { Text("Add Source") },
                supportingContent = { Text("A Jellyfin or Emby server, or a Stremio-compatible add-on") },
                modifier = Modifier.widthIn(max = 800.dp).focusRequester(first),
            )
        }
        if (sources.isEmpty()) {
            item(key = "none") { Text("No Sources. Add one to play something.", style = MaterialTheme.typography.bodyLarge, color = Dim) }
        }
        items(sources, key = { it.id }) { source ->
            ListItem(
                selected = false,
                onClick = { onPick(source) },
                headlineContent = { Text("${source.position}. ${source.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { SourceDetails(source) },
                modifier = Modifier.widthIn(max = 800.dp),
            )
        }
    }
}

@Composable
private fun SourceDetails(source: SourceSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            listOfNotNull(
                kindName(source.kind),
                if (source.enabled) "Enabled" else "Disabled",
                source.testState.label(),
                source.problem,
                "update available".takeIf { source.updateAvailable },
            ).joinToString(" · "),
        )
        source.computerMarks().forEach { Text(it, color = Dim) }
    }
}

@Composable
private fun SourceActions(
    source: SourceSummary,
    count: Int,
    busy: Boolean,
    message: String?,
    onMove: (by: Int) -> Unit,
    onToggle: () -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var mode by remember(source.id) { mutableStateOf(ActionsMode.Menu) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(source.name, style = MaterialTheme.typography.titleLarge)
        SourceDetails(source)
        Text("${source.position} of $count in the order", color = Dim)
    }
    if (busy) Text("Saving…", style = MaterialTheme.typography.bodyLarge, color = Dim)
    message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Warning, modifier = Modifier.widthIn(max = 800.dp)) }
    when (mode) {
        ActionsMode.Menu -> {
            val first = remember { FocusRequester() }
            LaunchedEffect(source.id) { first.requestFocusOnceShown() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggle, modifier = Modifier.focusRequester(first)) { Text(if (source.enabled) "Disable" else "Enable") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (source.position > 1) onMove(-1) }) { Text("Move up") }
                    OutlinedButton(onClick = { if (source.position < count) onMove(1) }) { Text("Move down") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { mode = ActionsMode.Rename }) { Text("Rename") }
                    OutlinedButton(onClick = { mode = ActionsMode.ConfirmRemove }) { Text("Remove") }
                }
            }
        }
        ActionsMode.Rename -> {
            BackHandler { mode = ActionsMode.Menu }
            var name by remember(source.id) { mutableStateOf(source.name) }
            TvTextField(label = "New name", value = name, onValueChange = { name = it }, focusFirst = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onRename(name)
                    mode = ActionsMode.Menu
                }) { Text("Save") }
                OutlinedButton(onClick = { mode = ActionsMode.Menu }) { Text("Cancel") }
            }
        }
        ActionsMode.ConfirmRemove -> {
            BackHandler { mode = ActionsMode.Menu }
            val keep = remember { FocusRequester() }
            LaunchedEffect(Unit) { keep.requestFocusOnceShown() }
            Text(
                "Remove ${source.name} for good? Its login goes too. You'd have to add it again to use it.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 800.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { mode = ActionsMode.Menu }, modifier = Modifier.focusRequester(keep)) { Text("Keep") }
                Button(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

private enum class ActionsMode { Menu, Rename, ConfirmRemove }

/** The Source Kind's name the Household knows it by. */
fun kindName(kind: String): String = when (kind) {
    "internet-archive" -> "Internet Archive"
    "jellyfin" -> "Jellyfin"
    "emby" -> "Emby"
    "stremio" -> "Stremio-compatible add-on"
    "extension" -> "Extension"
    else -> kind
}

private fun TestState.label() = when (this) {
    TestState.Untested -> "Not tested"
    TestState.Passing -> "Connected"
    TestState.Failing -> "Failing its test"
}

/** "Added from a computer on 24 Nov 2026", and when it was last changed from one, if it was. */
fun SourceSummary.computerMarks(): List<String> {
    val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
    return listOfNotNull(
        addedFromComputerAt?.let { "Added from a computer on ${format.format(Date(it))}" },
        changedFromComputerAt?.let { "Changed from a computer on ${format.format(Date(it))}" },
    )
}

/** What the Household reads when a change wasn't simply done; null when it was. */
fun WriteOutcome.problemMessage(): String? = when (this) {
    WriteOutcome.Ok, is WriteOutcome.Added -> null
    is WriteOutcome.Invalid -> message
    is WriteOutcome.Failed -> "Saved, but it failed its connection test: $message"
}
