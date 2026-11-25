package dev.celastrina.core.testing

import dev.celastrina.core.port.ExtensionLoader
import java.io.File
import java.net.URLClassLoader

/**
 * The Extension loader fake: loads a `.cs3`'s JVM classes (the test Extension carries them beside its `classes.dex`)
 * with the test's own class loader, which has CloudStream's `library`, as parent.
 *
 * [missingClasses] (class-name prefixes) stand in for an older Celastrina that lacks code an Extension links against:
 * the Extension can't load them, and fails with [NoClassDefFoundError] where it first uses one.
 */
class JvmExtensionLoader(private val dir: File, private val missingClasses: Set<String> = emptySet()) : ExtensionLoader {

    /** The keys of the code loaded so far, oldest first. */
    val loaded = mutableListOf<String>()

    override fun keepOnly(keys: Set<String>) {
        ExtensionLoader.deleteCodeExcept(dir, keys)
    }

    override fun classLoaderFor(key: String, code: ByteArray): ClassLoader {
        val file = File(dir, "$key.cs3")
        dir.mkdirs()
        file.writeBytes(code)
        synchronized(loaded) { loaded += key }
        val own = javaClass.classLoader
        val parent = if (missingClasses.isEmpty()) own else object : ClassLoader(own) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (missingClasses.any { name.startsWith(it) }) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        return URLClassLoader(arrayOf(file.toURI().toURL()), parent)
    }
}
