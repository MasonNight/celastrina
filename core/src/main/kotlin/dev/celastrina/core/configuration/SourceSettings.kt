package dev.celastrina.core.configuration

import dev.celastrina.core.failover.FailoverMemory
import dev.celastrina.core.port.Clock
import dev.celastrina.core.port.HttpClient
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceKind
import dev.celastrina.core.source.internetarchive.InternetArchiveSource
import dev.celastrina.core.source.jellyfin.MediaServer
import dev.celastrina.core.source.jellyfin.MediaServerKinds
import dev.celastrina.core.source.jellyfin.MediaServerLogin
import dev.celastrina.core.source.jellyfin.QuickConnectStep
import dev.celastrina.core.source.SourceId
import dev.celastrina.core.source.stremio.StremioAddon
import dev.celastrina.core.source.stremio.StremioAddonKind
import java.util.UUID

/** The Household's Sources: adding, changing, ordering and removing them, and the Sources Lookups ask. */
internal class SourceSettings(
    store: ConfigurationStore,
    private val http: HttpClient,
    private val appVersion: String,
    private val clock: Clock,
    private val failoverMemory: FailoverMemory?,
    private val connection: ConnectionTests,
    private val extensions: ExtensionSettings,
    private val announce: (ConfigurationChange) -> Unit,
) : StoreAccess(store) {

    /** Every Source in the Household's order. Never includes a secret, only whether there is a login. */
    fun list(): List<SourceSummary> = synchronized(lock) {
        val extensions = loadExtensions()
        val needingNewer = extensions.installed.filter { this@SourceSettings.extensions.needsNewer(it) }.map { it.name }
        val updatable = extensions.updatable().map { it.name }
        load().mapIndexed { index, it ->
            SourceSummary(
                id = it.id,
                name = it.name,
                kind = it.kind,
                enabled = it.enabled,
                position = index + 1,
                testState = it.testState,
                hasLogin = login(it.id) != null,
                problem = ConfigurationService.NEEDS_NEWER_CELASTRINA.takeIf { _ -> it.extension in needingNewer },
                updateAvailable = it.extension in updatable,
                addedFromComputerAt = it.addedFromComputerAt,
                changedFromComputerAt = it.changedFromComputerAt,
            )
        }
    }

    /** The enabled Sources, in the order a Lookup asks them. */
    fun sources(): List<Source> = synchronized(lock) {
        load().filter { it.enabled }.mapNotNull { stored ->
            if (stored.extension != null) return@mapNotNull extensions.source(stored)
            when (val kind = kindOf(stored.kind)) {
                InternetArchiveSource.Kind -> InternetArchiveSource(http, id = stored.id, name = stored.name)
                in MediaServerKinds.all -> connection.server(stored).source(stored.id, stored.name, kind!!, login(stored.id))
                StremioAddonKind -> connection.addon(stored).source(stored.id, stored.name, storage.read(manifestKey(stored.id))?.decodeToString())
                else -> error("Unknown Source Kind ${stored.kind} for Source ${stored.id}")
            }
        }
    }

    /**
     * Adds a Source at the end of the order and answers its ID in [WriteOutcome.Added]. A Jellyfin or Emby server is
     * logged in to straight away, and only the access token is kept. Adding the same Source again is harmless.
     */
    suspend fun add(new: NewSource, from: ChangedFrom): WriteOutcome {
        val kind = kindOf(new.kind) ?: return invalid(
            WriteOutcome.Problem.UnknownKind,
            "There's no Source Kind ${new.kind}. Kinds: ${KINDS.joinToString { it.id }}.",
        )
        val name = new.name.trim()
        if (name.isEmpty()) return blankName()
        val url = if (kind == InternetArchiveSource.Kind) {
            if (new.url != null || new.username != null || new.password != null) return unneededSetting(kind)
            null
        } else if (kind == StremioAddonKind) {
            if (new.username != null || new.password != null) return unneededSetting(kind)
            StremioAddon.manifestUrlOf(new.url) ?: return badUrl(kind)
        } else {
            val url = serverAddress(new.url) ?: return badUrl(kind)
            if (new.username.isNullOrBlank() || new.password == null) {
                return invalid(WriteOutcome.Problem.MissingLogin, "A ${kind.name} Source needs a username and a password (which may be empty).")
            }
            url
        }
        val id = when (val saved = saveNew(name, kind, url, new.username?.trim(), from)) {
            is Saved.As -> saved.id
            is Saved.Refused -> return saved.outcome
        }
        return when (val tested = connection.test(id, logInWith = new.password)) {
            WriteOutcome.Ok -> WriteOutcome.Added(id)
            else -> tested
        }
    }

    /**
     * Starts adding a Jellyfin server with Quick Connect: the answer's code is for the Household to approve in the
     * server's Quick Connect page, signed in on another device. [new] needs the kind, name and server address. Nothing
     * is saved until the code is approved.
     */
    suspend fun startQuickConnect(new: NewSource): QuickConnect {
        if (kindOf(new.kind) != MediaServerKinds.Jellyfin) {
            return QuickConnect.Finished(invalid(WriteOutcome.Problem.QuickConnectUnsupported, "Quick Connect is only for Jellyfin servers. Log in with a username and password instead."))
        }
        val name = new.name.trim()
        if (name.isEmpty()) return QuickConnect.Finished(blankName())
        val url = serverAddress(new.url) ?: return QuickConnect.Finished(badUrl(MediaServerKinds.Jellyfin))
        synchronized(lock) { nameTaken(name, load())?.let { return QuickConnect.Finished(it) } }
        // The server is new, so this TV is a new device to it; the device ID is kept with the Source once it's added.
        val deviceId = UUID.randomUUID().toString()
        return when (val step = MediaServer(http, url, deviceId, appVersion).startQuickConnect()) {
            is QuickConnectStep.Started -> QuickConnect.Waiting(step.code, name, url, step.secret, deviceId)
            is QuickConnectStep.Failed -> QuickConnect.Failed(step.reason, connection.quickConnectFailure(name, url, step.reason, starting = true))
            else -> QuickConnect.Failed(Reason.SourceError, connection.quickConnectFailure(name, url, Reason.SourceError, starting = true))
        }
    }

    /**
     * Asks whether the Household has approved [waiting]'s code yet, answering [QuickConnect.Waiting] until it has.
     * Once approved, the server is added at the end of the order with the approving user's login, and answers
     * [WriteOutcome.Added].
     */
    suspend fun continueQuickConnect(waiting: QuickConnect.Waiting, from: ChangedFrom): QuickConnect {
        val (name, url) = waiting.name to waiting.url
        return when (val step = MediaServer(http, url, waiting.deviceId, appVersion).continueQuickConnect(waiting.secret)) {
            QuickConnectStep.Waiting -> waiting
            QuickConnectStep.Expired -> QuickConnect.Failed(Reason.AuthRequired, "The Quick Connect code for $name has expired. Start again for a new code.")
            is QuickConnectStep.Failed -> QuickConnect.Failed(step.reason, connection.quickConnectFailure(name, url, step.reason, starting = false))
            is QuickConnectStep.Started -> QuickConnect.Failed(Reason.SourceError, connection.quickConnectFailure(name, url, Reason.SourceError, starting = false))
            is QuickConnectStep.Connected -> when (val saved = saveNew(name, MediaServerKinds.Jellyfin, url, step.username, from)) {
                is Saved.Refused -> QuickConnect.Finished(saved.outcome)
                is Saved.As -> {
                    synchronized(lock) {
                        val sources = load()
                        storage.write(deviceIdKey(saved.id), waiting.deviceId.encodeToByteArray())
                        storage.write(loginKey(saved.id), json.encodeToString(MediaServerLogin.serializer(), step.login).encodeToByteArray())
                        save(sources.map { if (it.id == saved.id) it.copy(testState = TestState.Passing) else it })
                    }
                    QuickConnect.Finished(WriteOutcome.Added(saved.id))
                }
            }
        }
    }

    private sealed interface Saved {
        data class As(val id: SourceId) : Saved
        data class Refused(val outcome: WriteOutcome.Invalid) : Saved
    }

    /**
     * Saves a new Source at the end of the order, untested, and announces it. The same Source again is harmless and
     * answers its ID; another Source with the same name is refused.
     */
    private fun saveNew(name: String, kind: SourceKind, url: String?, username: String?, from: ChangedFrom): Saved {
        val saved = synchronized(lock) {
            val sources = load()
            val sameName = sources.find { it.name.equals(name, ignoreCase = true) }
            when {
                sameName == null -> {
                    val id = uniqueId(SourceId.of(name), sources)
                    val addedAt = clock.nowMillis().takeIf { from == ChangedFrom.Computer }
                    save(sources + StoredSource(id = id, name = name, kind = kind.id, url = url, username = username, addedFromComputerAt = addedAt))
                    Saved.As(id)
                }
                sameName.kind == kind.id && sameName.url == url && sameName.username == username -> return Saved.As(sameName.id)
                else -> return Saved.Refused(nameTaken(name, sources)!!)
            }
        }
        announce(ConfigurationChange(ConfigurationChange.Action.Added, name, from))
        return saved
    }

    private fun nameTaken(name: String, sources: List<StoredSource>): WriteOutcome.Invalid? =
        sources.find { it.name.equals(name, ignoreCase = true) }?.let {
            invalid(WriteOutcome.Problem.NameTaken, "There's already a Source called ${it.name}. Choose another name.")
        }

    /**
     * Changes a Source's name, server address or login; anything left null stays as it is. The ID never changes.
     * A new username needs the password too.
     */
    suspend fun update(id: SourceId, changes: SourceChanges, from: ChangedFrom): WriteOutcome {
        val newName = changes.name?.trim()
        if (newName != null && newName.isEmpty()) return blankName()
        val kind = synchronized(lock) { load().find { it.id == id }?.kind }?.let(::kindOf) ?: return unknownSource(id)
        val takesUrl = kind in MediaServerKinds.all || kind == StremioAddonKind
        val takesLogin = kind in MediaServerKinds.all
        if ((changes.url != null && !takesUrl) || ((changes.username != null || changes.password != null) && !takesLogin)) return unneededSetting(kind)
        val newUrl = changes.url?.let { (if (kind == StremioAddonKind) StremioAddon.manifestUrlOf(it) else serverAddress(it)) ?: return badUrl(kind) }
        val newUsername = changes.username?.trim()
        if (newUsername != null && (newUsername.isEmpty() || changes.password == null)) {
            return invalid(WriteOutcome.Problem.MissingLogin, "A new username needs its password too.")
        }
        val outcome = change(id, from) { source ->
            val changed = source.copy(name = newName ?: source.name, url = newUrl ?: source.url, username = newUsername ?: source.username)
            val announcement = when {
                changed.name != source.name -> ConfigurationChange(ConfigurationChange.Action.Renamed, changed.name, from, previousName = source.name)
                changed != source || changes.password != null -> ConfigurationChange(ConfigurationChange.Action.Changed, changed.name, from)
                else -> null
            }
            Edit(changed, announcement)
        }
        return if (outcome == WriteOutcome.Ok) connection.test(id, logInWith = changes.password) else outcome
    }

    /** Lets Lookups ask the Source again, and tests its connection. */
    suspend fun enable(id: SourceId, from: ChangedFrom): WriteOutcome {
        val outcome = setEnabled(id, true, from)
        return if (outcome == WriteOutcome.Ok) connection.test(id, logInWith = null) else outcome
    }

    /** Keeps the Source, but stops Lookups asking it. */
    fun disable(id: SourceId, from: ChangedFrom): WriteOutcome = setEnabled(id, false, from)

    private fun setEnabled(id: SourceId, enabled: Boolean, from: ChangedFrom) = change(id, from) { source ->
        val action = if (enabled) ConfigurationChange.Action.Enabled else ConfigurationChange.Action.Disabled
        Edit(source.copy(enabled = enabled), ConfigurationChange(action, source.name, from).takeIf { source.enabled != enabled })
    }

    /**
     * Puts the Sources [ids] first, in that order; the others follow in the order they were in. Lookups prefer
     * Streams from Sources earlier in the order.
     */
    fun setOrder(ids: List<SourceId>, from: ChangedFrom): WriteOutcome {
        ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            return invalid(WriteOutcome.Problem.RepeatedSource, "The order names ${it.key} more than once.")
        }
        val announcement = synchronized(lock) {
            val sources = load()
            ids.firstOrNull { id -> sources.none { it.id == id } }?.let { return unknownSource(it) }
            val ordered = ids.map { id -> sources.first { it.id == id } } + sources.filter { it.id !in ids }
            if (ordered == sources) return WriteOutcome.Ok
            save(ordered)
            ConfigurationChange(ConfigurationChange.Action.Reordered, ordered.first().name, from)
        }
        announce(announcement)
        return WriteOutcome.Ok
    }

    /** Removes the Source, its login and its Failover memory for good. Removing one that is already gone is harmless. */
    fun remove(id: SourceId, from: ChangedFrom): WriteOutcome = change(id, from, ifMissing = WriteOutcome.Ok) { source ->
        storage.delete(loginKey(id))
        storage.delete(deviceIdKey(id))
        storage.delete(manifestKey(id))
        failoverMemory?.forget(listOf(id))
        Edit(null, ConfigurationChange(ConfigurationChange.Action.Removed, source.name, from))
    }

    /** What an edit of one Source does: the changed Source (null to remove it) and its announcement, if anything changed. */
    private class Edit(val changed: StoredSource?, val announcement: ConfigurationChange?)

    /**
     * Applies [edit] to the Source [id] and saves it, marking it changed from a computer when it was. A missing Source
     * answers [ifMissing].
     */
    private fun change(id: SourceId, from: ChangedFrom, ifMissing: WriteOutcome = unknownSource(id), edit: (StoredSource) -> Edit): WriteOutcome {
        val announcement = synchronized(lock) {
            val sources = load()
            val source = sources.find { it.id == id } ?: return ifMissing
            val result = edit(source)
            val changed = if (result.announcement != null && from == ChangedFrom.Computer) {
                result.changed?.copy(changedFromComputerAt = clock.nowMillis())
            } else {
                result.changed
            }
            if (changed != source) save(sources.mapNotNull { if (it.id == id) changed else it })
            result.announcement
        }
        announcement?.let { announce(it) }
        return WriteOutcome.Ok
    }
}
