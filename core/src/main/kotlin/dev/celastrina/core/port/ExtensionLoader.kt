package dev.celastrina.core.port

import com.lagradost.cloudstream3.plugins.BasePlugin

/**
 * How Celastrina's platform loads an Extension's code into its own process (ADR 0001): a DEX class loader on Android,
 * a JAR class loader in the Core's tests.
 */
interface ExtensionLoader {
    /**
     * A class loader for the `.cs3` file [code], whose parent is Celastrina's own class loader, so the Extension links
     * against the bundled CloudStream `library` and shims. [key] names this exact code (its SHA-256), so a loader may
     * keep it on disk under that name. A class the Extension needs that Celastrina lacks fails when first used, with
     * [NoClassDefFoundError].
     */
    fun classLoaderFor(key: String, code: ByteArray): ClassLoader

    /**
     * Deletes any code this loader kept on disk except [keys]': the Extensions removed or replaced by an update. Called
     * when Celastrina starts, before any Extension is loaded, since code already loaded can't be unloaded.
     */
    fun keepOnly(keys: Set<String>) {}

    /** Runs [plugin]'s load hook the way its platform expects, which registers its providers. */
    fun start(plugin: BasePlugin) = plugin.load()

    companion object {
        /** For a Celastrina that can't load Extensions; installing one fails. */
        val None = object : ExtensionLoader {
            override fun classLoaderFor(key: String, code: ByteArray): ClassLoader =
                throw UnsupportedOperationException("This Celastrina can't load Extensions")
        }
    }
}
