package dev.celastrina.app

import android.content.Context
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import dalvik.system.PathClassLoader
import dev.celastrina.core.port.ExtensionLoader
import java.io.File

/**
 * Loads Extensions' `classes.dex` into Celastrina's own process (ADR 0001), linked against the CloudStream `library`
 * and shims bundled in the app. Each `.cs3` is kept read-only under its SHA-256, as Android 14 requires of loaded code.
 */
class DexExtensionLoader(private val context: Context) : ExtensionLoader {

    private val dir get() = File(context.codeCacheDir, "extensions")

    override fun keepOnly(keys: Set<String>) {
        ExtensionLoader.deleteCodeExcept(dir, keys)
    }

    override fun classLoaderFor(key: String, code: ByteArray): ClassLoader {
        val dir = dir.apply { mkdirs() }
        val file = File(dir, "$key.cs3")
        if (!file.isFile) {
            val temp = File(dir, "$key.tmp")
            temp.delete()
            temp.writeBytes(code)
            check(temp.setReadOnly() && temp.renameTo(file)) { "Couldn't keep Extension code $key" }
        }
        return PathClassLoader(file.path, context.classLoader)
    }

    override fun start(plugin: BasePlugin) {
        if (plugin is Plugin) plugin.load(context) else plugin.load()
    }
}
