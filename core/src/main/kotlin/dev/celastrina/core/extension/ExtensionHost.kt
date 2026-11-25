package dev.celastrina.core.extension

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import dev.celastrina.core.catalogue.Title
import dev.celastrina.core.port.ExtensionLoader
import dev.celastrina.core.port.Logger
import dev.celastrina.core.source.LookupOutcome.Failure.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.time.Duration.Companion.seconds

/**
 * Runs Extensions in Celastrina's own process, unisolated (ADR 0001). It loads each Extension's code once, and guards
 * every call into it: a call that doesn't finish within [CALL_TIMEOUT] is abandoned, and anything it throws becomes a
 * typed [ExtensionFailure] instead of reaching the rest of the app.
 */
internal class ExtensionHost(private val loader: ExtensionLoader, private val logger: Logger) {
    private val lock = Any()
    private val loaded = mutableMapOf<String, List<MainAPI>>()

    /** Where Extension calls run, so a call that hangs, even one blocking its thread, holds up nothing but itself. */
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The providers the Extension [code] (a `.cs3`, whose SHA-256 is [sha256]) registers when loaded, loading it
     * the first time it is asked for.
     */
    internal suspend fun providers(name: String, sha256: String, code: () -> ByteArray): List<MainAPI> {
        synchronized(lock) { loaded[sha256] }?.let { return it }
        val providers = call(name, "load") { load(sha256, code()) }
        return synchronized(lock) { loaded.getOrPut(sha256) { providers } }
    }

    /** The Sources the Extension [code] provides, one per provider it registers, loading it the first time it is asked for. */
    internal suspend fun sourcesOf(name: String, sha256: String, code: () -> ByteArray): List<ProvidedSource> =
        providers(name, sha256, code).map(ExtensionSource::provided)

    private fun load(sha256: String, code: ByteArray): List<MainAPI> {
        val className = pluginClassName(code)
        val classLoader = loader.classLoaderFor(sha256, code)
        val plugin = Class.forName(className, true, classLoader).getDeclaredConstructor().newInstance() as BasePlugin
        // Providers register themselves globally; this name picks out the ones this load registered.
        plugin.filename = "celastrina-extension-$sha256-${UUID.randomUUID()}"
        loader.start(plugin)
        return synchronized(APIHolder.allProviders) { APIHolder.allProviders.filter { it.sourcePlugin == plugin.filename } }
    }

    /**
     * Runs [block], a call into the Extension [name], under the call timeout. Throws [ExtensionFailure] when it
     * times out (`unreachable`) or throws anything at all (`source_error`), even a network error: what an
     * Extension throws says too little to tell its site being down from its own bugs.
     */
    internal suspend fun <T> call(name: String, what: String, block: suspend () -> T): T {
        val call = calls.async { block() }
        try {
            // Timed on the real clock, where the Extension's code runs, rather than the caller's.
            return withContext(Dispatchers.IO) { withTimeout(CALL_TIMEOUT) { call.await() } }
        } catch (e: CancellationException) {
            // The lookup itself was cancelled: let it end.
            if (!currentCoroutineContext().isActive) throw e
            if (e is TimeoutCancellationException) {
                logger.info("Extension $name didn't finish $what within $CALL_TIMEOUT")
                throw ExtensionFailure(Reason.Unreachable, needsNewerCelastrina = false)
            }
            logger.info("Extension $name failed in $what: $e")
            throw ExtensionFailure(Reason.SourceError, needsNewerCelastrina = false)
        } catch (e: Throwable) {
            val needsNewer = e is NoClassDefFoundError || e is NoSuchMethodError
            logger.info("Extension $name failed in $what: $e")
            throw ExtensionFailure(Reason.SourceError, needsNewer)
        } finally {
            call.cancel()
        }
    }

    internal companion object {
        val CALL_TIMEOUT = 30.seconds

        /** The plugin class named in the `.cs3`'s `manifest.json`. Throws [IllegalArgumentException] when there's none. */
        fun pluginClassName(code: ByteArray): String {
            val manifest = ZipInputStream(ByteArrayInputStream(code)).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name == "manifest.json" }?.let { zip.readBytes() }
            } ?: throw IllegalArgumentException("The .cs3 has no manifest.json")
            return manifestField(manifest, "pluginClassName") ?: throw IllegalArgumentException("manifest.json names no pluginClassName")
        }

        private fun manifestField(manifest: ByteArray, field: String): String? = try {
            Json.parseToJsonElement(manifest.decodeToString()).jsonObject[field]?.jsonPrimitive?.content
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** One Source an Extension provides: the name it gives the Source, which Title kinds it plays, and whether it matches by IMDb ID. */
internal data class ProvidedSource(val name: String, val titleKinds: Set<Title.Kind>, val imdb: Boolean)

/** An Extension call that failed for [reason]. [needsNewerCelastrina] when the Extension links against code Celastrina lacks. */
internal class ExtensionFailure(val reason: Reason, val needsNewerCelastrina: Boolean) : Exception()
