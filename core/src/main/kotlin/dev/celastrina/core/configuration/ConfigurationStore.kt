package dev.celastrina.core.configuration

import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.extension.ListedExtension
import dev.celastrina.core.port.Storage
import dev.celastrina.core.source.Source
import dev.celastrina.core.source.internetarchive.InternetArchiveSource
import dev.celastrina.core.source.jellyfin.MediaServerLogin
import dev.celastrina.core.source.SourceId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where the Household's configuration is kept, behind one lock that every change holds while it reads and saves. */
internal class ConfigurationStore(val storage: Storage) {
    val lock = Any()

    fun loadExtensions(): StoredExtensions =
        storage.read(EXTENSIONS)?.let { json.decodeFromString<StoredExtensions>(it.decodeToString()) } ?: StoredExtensions()

    fun saveExtensions(extensions: StoredExtensions) {
        storage.write(EXTENSIONS, json.encodeToString(StoredExtensions.serializer(), extensions).encodeToByteArray())
    }

    /** The first of [name], `name 2`, `name 3`… that no Source is called. */
    fun uniqueName(name: String, sources: List<StoredSource>): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name $it" }.first { n -> sources.none { it.name.equals(n, ignoreCase = true) } }

    fun login(id: SourceId): MediaServerLogin? =
        storage.read(loginKey(id))?.let { json.decodeFromString(MediaServerLogin.serializer(), it.decodeToString()) }

    /** The first of [id], `id-2`, `id-3`… that no Source has. */
    fun uniqueId(id: SourceId, sources: List<StoredSource>): SourceId =
        generateSequence(1) { it + 1 }.map { if (it == 1) id else SourceId("$id-$it") }.first { candidate -> sources.none { it.id == candidate } }

    /**
     * [sources] with [added] joining the order: above the Internet Archive Sources at the bottom, so the Archive, a test
     * Source, stays last. Once the Household has moved the Archive up, or when [added] is itself an Archive, they join
     * the end.
     */
    fun withAdded(sources: List<StoredSource>, added: List<StoredSource>): List<StoredSource> {
        if (added.any { it.isArchive }) return sources + added
        val archivesAtBottom = sources.takeLastWhile { it.isArchive }
        return sources.dropLast(archivesAtBottom.size) + added + archivesAtBottom
    }

    private val StoredSource.isArchive get() = kind == InternetArchiveSource.Kind.id

    fun save(sources: List<StoredSource>) {
        storage.write(SOURCES, json.encodeToString(StoredSources.serializer(), StoredSources(sources)).encodeToByteArray())
    }

    fun load(): List<StoredSource> =
        storage.read(SOURCES)?.let { json.decodeFromString<StoredSources>(it.decodeToString()).sources } ?: preinstalled()

    fun preinstalled() = listOf(
        StoredSource(id = SourceId.of(INTERNET_ARCHIVE), name = INTERNET_ARCHIVE, kind = InternetArchiveSource.Kind.id),
    )
}

/** Reaches the [ConfigurationStore] as if its reads and writes were the class's own. */
internal abstract class StoreAccess(protected val store: ConfigurationStore) {
    protected val lock get() = store.lock
    protected val storage get() = store.storage
    protected fun load() = store.load()
    protected fun save(sources: List<StoredSource>) = store.save(sources)
    protected fun loadExtensions() = store.loadExtensions()
    protected fun saveExtensions(extensions: StoredExtensions) = store.saveExtensions(extensions)
    protected fun uniqueId(id: SourceId, sources: List<StoredSource>) = store.uniqueId(id, sources)
    protected fun uniqueName(name: String, sources: List<StoredSource>) = store.uniqueName(name, sources)
    protected fun withAdded(sources: List<StoredSource>, added: List<StoredSource>) = store.withAdded(sources, added)
    protected fun login(id: SourceId) = store.login(id)
}

@Serializable
internal data class StoredSources(val sources: List<StoredSource>)

@Serializable
internal data class StoredSource(
    val id: SourceId,
    val name: String,
    val kind: String,
    val enabled: Boolean = true,
    val testState: TestState = TestState.Untested,
    /** A Jellyfin or Emby server's address, without a trailing '/', or an add-on's manifest URL. */
    val url: String? = null,
    val username: String? = null,
    /** For a Source from an Extension: the Extension's name, the name it gives this Source, and what the Source plays. */
    val extension: String? = null,
    // Stored as "provider", its name before the glossary's word was used.
    @SerialName("provider") val nameInExtension: String? = null,
    val titleKinds: Set<Title.Kind> = emptySet(),
    val imdb: Boolean = false,
    val addedFromComputerAt: Long? = null,
    val changedFromComputerAt: Long? = null,
)

@Serializable
internal data class StoredExtensions(
    val repositories: List<StoredRepository> = emptyList(),
    val installed: List<InstalledExtension> = emptyList(),
) {
    /** What [installed]'s Extension Repository lists for it now. */
    fun listedFor(installed: InstalledExtension): ListedExtension? =
        repositories.find { it.url == installed.repositoryUrl }?.extensions?.find { it.name == installed.name }

    /** The installed Extensions whose Extension Repository lists a newer version. */
    fun updatable(): List<InstalledExtension> = installed.filter { (listedFor(it)?.version ?: 0) > it.version }
}

@Serializable
internal data class StoredRepository(val url: String, val name: String, val extensions: List<ListedExtension>)

/** An installed Extension, pinned to the Extension Repository it came from. Its code is kept under its SHA-256. */
@Serializable
internal data class InstalledExtension(
    val name: String,
    val displayName: String,
    val repositoryUrl: String,
    val version: Int,
    val sha256: String,
    /** The Celastrina version this Extension's code couldn't load or run on, or null when it can. */
    val needsNewerThan: String? = null,
)
