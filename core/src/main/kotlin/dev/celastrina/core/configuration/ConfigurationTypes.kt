package dev.celastrina.core.configuration

import dev.celastrina.core.extension.ExtensionRepository
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.SourceId

data class Status(val appVersion: String, val tmdbKey: TmdbKeyInUse, val sources: Int, val extensions: Int, val protocolVersion: Int)

/** Which TMDB key the Catalogue uses. Says nothing of the key itself. */
enum class TmdbKeyInUse {
    /** The key built into the app. */
    Project,

    /** The Household's own key. */
    Override,

    /** No key at all: a build without a project key, and the Household hasn't set one. */
    None,
}

/**
 * A Source to add. [kind] is a Source Kind's ID; Jellyfin and Emby need [url], [username] and [password], and a
 * Stremio-compatible add-on needs its manifest URL as [url].
 */
data class NewSource(
    val kind: String,
    val name: String,
    val url: String? = null,
    val username: String? = null,
    /** Used to log in, then forgotten. */
    val password: String? = null,
)

/** Changes to a Source; null leaves that setting as it is. A [password] logs in again. */
data class SourceChanges(
    val name: String? = null,
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
)

/** What the Household and the agent see of a Source. */
data class SourceSummary(
    /** Made from the name when the Source is added; unchanged by renames. */
    val id: SourceId,
    val name: String,
    /** The Source Kind's ID, such as `internet-archive`. */
    val kind: String,
    val enabled: Boolean,
    /** 1 for the Source asked about first. */
    val position: Int,
    val testState: TestState,
    val hasLogin: Boolean,
    /** When the agent added it from a computer, in epoch milliseconds; null when it was added on the TV or preinstalled. */
    val addedFromComputerAt: Long?,
    /** When the agent last changed it from a computer, in epoch milliseconds; null when it never has. */
    val changedFromComputerAt: Long?,
    /** Why Lookups don't ask this Source though it is enabled, such as "Needs a newer Celastrina"; null when they do. */
    val problem: String? = null,
    /** For a Source from an Extension: whether its Extension Repository lists a newer version of the Extension. */
    val updateAvailable: Boolean = false,
)

/** How the Source's latest connection test went. */
enum class TestState { Untested, Passing, Failing }

/** What a write answers: saved, refused with a [Problem], or saved but failing its connection test. */
sealed interface WriteOutcome {
    data object Ok : WriteOutcome

    /** A Source was added (or was already there), passing its connection test. */
    data class Added(val sourceId: SourceId) : WriteOutcome

    data class Invalid(val problem: Problem, val message: String) : WriteOutcome

    /** The write was saved, but the Source [sourceId] failed its connection test for [reason]. */
    data class Failed(val sourceId: SourceId, val reason: Reason, val message: String) : WriteOutcome

    enum class Problem {
        UnknownSource, BlankName, UnknownKind, BadUrl, MissingLogin, NameTaken, RepeatedSource, UnneededSetting, BlankTmdbKey, QuickConnectUnsupported,
        UnknownRepository, UnknownExtension, PinnedToOtherRepository, MissingHash, HashMismatch,
    }
}

/** How adding a Jellyfin server with Quick Connect is going. */
sealed interface QuickConnect {
    /** Show [code] until the Household approves it on the server, asking again every few seconds. */
    class Waiting internal constructor(
        val code: String,
        internal val name: String,
        internal val url: String,
        internal val secret: String,
        internal val deviceId: String,
    ) : QuickConnect

    /** Done: the server was [added][WriteOutcome.Added], or the add was [refused][WriteOutcome.Invalid]. */
    data class Finished(val outcome: WriteOutcome) : QuickConnect

    /** Couldn't log in, for [reason]; nothing was saved. */
    data class Failed(val reason: Reason, val message: String) : QuickConnect
}

/** Where a change was made: on the TV, or by the agent over ADB. */
enum class ChangedFrom { Tv, Computer }

/** A saved change to the configuration, as the on-screen notice tells it. */
data class ConfigurationChange(
    val action: Action,
    /**
     * The Source's name after the change; for a new order, the Source now first; for an imported Extension Repository,
     * its name; null for any other change that isn't to a Source.
     */
    val sourceName: String?,
    val from: ChangedFrom,
    /** For a rename, the name it had before. */
    val previousName: String? = null,
) {
    enum class Action { Added, Renamed, Changed, Enabled, Disabled, Removed, Reordered, TmdbKeySet, TmdbKeyCleared, RepositoryImported, RepositoryRemoved, ExtensionUpdated, ExtensionRemoved }
}

/** What importing an Extension Repository answers. */
sealed interface ImportOutcome {
    /** Imported: the Repository and the Extensions it lists, ready to choose from. */
    data class Imported(val repository: ExtensionRepository) : ImportOutcome

    /** Refused until the Household has seen [warning] and agreed: import again acknowledging [warningVersion]. */
    data class WarningNotAcknowledged(val warning: String, val warningVersion: Int) : ImportOutcome

    data class Invalid(val problem: WriteOutcome.Problem, val message: String) : ImportOutcome

    /** The Repository couldn't be read, for [reason]. */
    data class Failed(val reason: Reason, val message: String) : ImportOutcome
}

/** What installing an Extension answers. */
sealed interface InstallOutcome {
    /** Installed (or already was): the IDs of the Sources it provides. */
    data class Installed(val sourceIds: List<SourceId>) : InstallOutcome

    data class Invalid(val problem: WriteOutcome.Problem, val message: String) : InstallOutcome

    /** Not usable, for [reason]: it couldn't be downloaded, didn't load, or needs a newer Celastrina. */
    data class Failed(val reason: Reason, val message: String) : InstallOutcome
}
