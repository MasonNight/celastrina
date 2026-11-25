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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationService
import dev.celastrina.core.configuration.ImportOutcome
import dev.celastrina.core.configuration.InstallOutcome
import dev.celastrina.core.extension.AvailableExtension
import dev.celastrina.core.extension.ExtensionRepository
import dev.celastrina.core.extension.ExtensionWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Dim = Color(0xFF9AA0AA)
private val Warning = Color(0xFFFFB4A8)

/** What the Extensions screen shows: the list, the import steps, or one Extension or Repository's actions. */
private sealed interface ExtensionsView {
    data object List : ExtensionsView
    data object EnterUrl : ExtensionsView

    /** The warning, every time, before [url] is imported. */
    data class Warn(val url: String) : ExtensionsView
    data class OneExtension(val repositoryUrl: String, val name: String) : ExtensionsView
    data class OneRepository(val url: String) : ExtensionsView
}

/**
 * The imported Extension Repositories and the Extensions each lists, with "N updates available" and Update all when
 * the last update check found any. Importing asks for the Repository's address, then shows the warning with Cancel
 * focused. Picking an Extension installs, updates or removes it; picking a Repository removes it with its Extensions.
 * Nothing updates by itself.
 */
@Composable
fun ExtensionsScreen(configuration: ConfigurationService) {
    var repositories by remember { mutableStateOf(configuration.repositories()) }
    var updates by remember { mutableStateOf(configuration.updatesAvailable()) }
    var view by remember { mutableStateOf<ExtensionsView>(ExtensionsView.List) }
    /** What the last action answered, when it wasn't simply done. */
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        repositories = configuration.repositories()
        updates = configuration.updatesAvailable()
    }
    LaunchedEffect(configuration) { configuration.changes.collect { refresh() } }

    /** Runs [action] off the main thread, saying [doing] meanwhile, then shows its problem, if any, on [then]. */
    fun run(doing: String, then: ExtensionsView? = null, action: suspend () -> String?) {
        if (busy != null) return
        busy = doing
        message = null
        scope.launch {
            val problem = withContext(Dispatchers.IO) { action() }
            refresh()
            busy = null
            message = problem
            if (then != null) view = then
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Extensions", style = MaterialTheme.typography.headlineMedium)
        busy?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Dim) }
        message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Warning, modifier = Modifier.widthIn(max = 800.dp)) }
        when (val v = view) {
            ExtensionsView.List -> ExtensionList(
                repositories,
                updates,
                onImport = {
                    message = null
                    view = ExtensionsView.EnterUrl
                },
                onUpdateAll = { run("Updating…") { configuration.updateAllExtensions(ChangedFrom.Tv).values.mapNotNull { it.problem() }.joinToString(" ").ifEmpty { null } } },
                onPickRepository = {
                    message = null
                    view = ExtensionsView.OneRepository(it.url)
                },
                onPickExtension = { repository, extension ->
                    message = null
                    view = ExtensionsView.OneExtension(repository.url, extension.name)
                },
            )
            ExtensionsView.EnterUrl -> {
                BackHandler { view = ExtensionsView.List }
                var url by remember { mutableStateOf("") }
                TvTextField("Extension Repository address", url, { url = it }, keyboardType = KeyboardType.Uri, placeholder = "https://…/repo.json", focusFirst = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (url.isNotBlank()) view = ExtensionsView.Warn(url) }) { Text("Next") }
                    OutlinedButton(onClick = { view = ExtensionsView.List }) { Text("Cancel") }
                }
            }
            is ExtensionsView.Warn -> {
                BackHandler { view = ExtensionsView.List }
                ImportWarning(
                    onCancel = { view = ExtensionsView.List },
                    onImport = {
                        run("Importing…", then = ExtensionsView.List) {
                            when (val outcome = configuration.importRepository(v.url, ExtensionWarning.VERSION, ChangedFrom.Tv)) {
                                is ImportOutcome.Imported -> null
                                is ImportOutcome.WarningNotAcknowledged -> outcome.warning
                                is ImportOutcome.Invalid -> outcome.message
                                is ImportOutcome.Failed -> outcome.message
                            }
                        }
                    },
                )
            }
            is ExtensionsView.OneExtension -> {
                BackHandler { view = ExtensionsView.List }
                val repository = repositories.find { it.url == v.repositoryUrl }
                val extension = repository?.extensions?.find { it.name == v.name }
                if (repository == null || extension == null) {
                    LaunchedEffect(Unit) { view = ExtensionsView.List }
                } else {
                    ExtensionActions(
                        repository,
                        extension,
                        onInstall = { run("Installing…") { configuration.installExtension(repository.url, extension.name, ChangedFrom.Tv).problem() } },
                        onUpdate = { run("Updating…") { configuration.updateExtension(extension.name, ChangedFrom.Tv).problem() } },
                        onRemove = { run("Removing…") { configuration.removeExtension(extension.name, ChangedFrom.Tv).problemMessage() } },
                    )
                }
            }
            is ExtensionsView.OneRepository -> {
                BackHandler { view = ExtensionsView.List }
                val repository = repositories.find { it.url == v.url }
                if (repository == null) {
                    LaunchedEffect(Unit) { view = ExtensionsView.List }
                } else {
                    RepositoryActions(
                        repository,
                        onKeep = { view = ExtensionsView.List },
                        onRemove = { run("Removing…", then = ExtensionsView.List) { configuration.removeRepository(repository.url, ChangedFrom.Tv).problemMessage() } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionList(
    repositories: List<ExtensionRepository>,
    updates: Int,
    onImport: () -> Unit,
    onUpdateAll: () -> Unit,
    onPickRepository: (ExtensionRepository) -> Unit,
    onPickExtension: (ExtensionRepository, AvailableExtension) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (updates > 0) {
            item(key = "updates") {
                ListItem(
                    selected = false,
                    onClick = onUpdateAll,
                    headlineContent = { Text("Update all") },
                    supportingContent = { Text(updatesText(updates)) },
                    modifier = Modifier.widthIn(max = 800.dp).focusRequester(first),
                )
            }
        }
        item(key = "import") {
            ListItem(
                selected = false,
                onClick = onImport,
                headlineContent = { Text("Import an Extension Repository") },
                supportingContent = { Text("By its address. Extensions are unverified code from third parties.") },
                modifier = Modifier.widthIn(max = 800.dp).then(if (updates > 0) Modifier else Modifier.focusRequester(first)),
            )
        }
        if (repositories.isEmpty()) {
            item(key = "none") { Text("No Extension Repositories imported.", style = MaterialTheme.typography.bodyLarge, color = Dim) }
        }
        repositories.forEach { repository ->
            item(key = "repository:${repository.url}") {
                ListItem(
                    selected = false,
                    onClick = { onPickRepository(repository) },
                    headlineContent = { Text(repository.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(repository.url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dim) },
                    modifier = Modifier.widthIn(max = 800.dp).padding(top = 12.dp),
                )
            }
            items(repository.extensions, key = { "extension:${repository.url}:${it.name}" }) { extension ->
                ListItem(
                    selected = false,
                    onClick = { onPickExtension(repository, extension) },
                    headlineContent = { Text(extension.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(extension.state()) },
                    modifier = Modifier.widthIn(max = 800.dp).padding(start = 32.dp),
                )
            }
        }
    }
}

/** The warning, word for word, with Cancel focused: importing takes a deliberate move and press. */
@Composable
private fun ImportWarning(onCancel: () -> Unit, onImport: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestFocus() }
    Text(ExtensionWarning.TEXT, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 800.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCancel, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
        OutlinedButton(onClick = onImport) { Text("Import") }
    }
}

@Composable
private fun ExtensionActions(
    repository: ExtensionRepository,
    extension: AvailableExtension,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirming by remember(extension.name) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.widthIn(max = 800.dp)) {
        Text(extension.displayName, style = MaterialTheme.typography.titleLarge)
        Text(extension.state())
        Text("From ${repository.name}", color = Dim)
        extension.description?.let { Text(it, color = Dim) }
        if (extension.authors.isNotEmpty()) Text("By ${extension.authors.joinToString()}", color = Dim)
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(extension.installed, extension.updateAvailable, confirming) { first.requestFocus() }
    when {
        !extension.installed -> Button(onClick = onInstall, modifier = Modifier.focusRequester(first)) { Text("Install") }
        confirming -> {
            BackHandler { confirming = false }
            Text("Remove ${extension.displayName}? Its Sources leave the order now.", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirming = false }, modifier = Modifier.focusRequester(first)) { Text("Keep") }
                Button(onClick = {
                    confirming = false
                    onRemove()
                }) { Text("Remove") }
            }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (extension.updateAvailable) Button(onClick = onUpdate, modifier = Modifier.focusRequester(first)) { Text("Update to version ${extension.version}") }
            OutlinedButton(
                onClick = { confirming = true },
                modifier = if (extension.updateAvailable) Modifier else Modifier.focusRequester(first),
            ) { Text("Remove") }
        }
    }
}

@Composable
private fun RepositoryActions(repository: ExtensionRepository, onKeep: () -> Unit, onRemove: () -> Unit) {
    val keep = remember { FocusRequester() }
    LaunchedEffect(Unit) { keep.requestFocus() }
    Text(repository.name, style = MaterialTheme.typography.titleLarge)
    Text(repository.url, color = Dim)
    val installed = repository.extensions.filter { it.installed }
    Text(
        if (installed.isEmpty()) {
            "Remove this Extension Repository?"
        } else {
            "Remove this Extension Repository and its Extensions (${installed.joinToString { it.displayName }})? Their Sources leave the order now."
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.widthIn(max = 800.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onKeep, modifier = Modifier.focusRequester(keep)) { Text("Keep") }
        Button(onClick = onRemove) { Text("Remove") }
    }
}

/** "Installed · version 1 · update available: version 2", or "Not installed · version 2". */
private fun AvailableExtension.state(): String = when {
    !installed -> "Not installed · version $version"
    updateAvailable -> "Installed · version $installedVersion · update available: version $version"
    else -> "Installed · version $installedVersion"
}

/** "1 update available", "3 updates available". */
fun updatesText(count: Int) = if (count == 1) "1 update available" else "$count updates available"

/** What the Household reads when an install or update wasn't simply done; null when it was. */
private fun InstallOutcome.problem(): String? = when (this) {
    is InstallOutcome.Installed -> null
    is InstallOutcome.Invalid -> message
    is InstallOutcome.Failed -> message
}
