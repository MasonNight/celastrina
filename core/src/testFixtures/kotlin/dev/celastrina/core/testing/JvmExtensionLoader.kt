package dev.celastrina.core.testing

import dev.celastrina.core.port.ExtensionLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipInputStream

/**
 * The Extension loader fake: loads a `.cs3`'s JVM classes (the test Extension carries them beside its `classes.dex`)
 * with the test's own class loader, which has CloudStream's `library`, as parent.
 *
 * It keeps each `.cs3` on disk under its key, as the real loader does, but loads it from memory: loaded code doesn't hold
 * its file open, so [keepOnly] and the test's temporary directory can delete it on any OS, as on Android, while the
 * code stays loaded. (A `URLClassLoader` would keep the file open until closed, which Windows can't delete.)
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
        return InMemoryJarClassLoader(key, code, parent)
    }
}

/** Loads classes and resources from a JAR's [bytes], held in memory; [key] names it in its resources' URLs. */
private class InMemoryJarClassLoader(private val key: String, bytes: ByteArray, parent: ClassLoader) : ClassLoader(parent) {
    private val entries: Map<String, ByteArray> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.associate { it.name to zip.readBytes() }
    }

    override fun findClass(name: String): Class<*> {
        val bytes = entries[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
        val packageName = name.substringBeforeLast('.', "")
        if (packageName.isNotEmpty() && getDefinedPackage(packageName) == null) {
            definePackage(packageName, null, null, null, null, null, null, null)
        }
        return defineClass(name, bytes, 0, bytes.size)
    }

    override fun findResource(name: String): URL? {
        val bytes = entries[name] ?: return null
        return URL("celastrina-extension", key, -1, "/$name", object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : URLConnection(url) {
                override fun connect() {}
                override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
            }
        })
    }

    override fun findResources(name: String): Enumeration<URL> =
        Collections.enumeration(listOfNotNull(findResource(name)))
}
