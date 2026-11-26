package dev.celastrina.core.testing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Extension loader fake must behave like Android's: code it loaded doesn't stop its file being deleted. */
class JvmExtensionLoaderTest {

    @TempDir
    lateinit var dir: File

    private val code by lazy {
        File(checkNotNull(System.getProperty("celastrina.testRepository")) { "Run through Gradle: no test repository" }, "InternetArchive.cs3").readBytes()
    }

    @Test
    fun `loaded code can be deleted, as on Android, and stays loaded`() {
        val codeDir = File(dir, "code")
        val loader = JvmExtensionLoader(codeDir)
        val classLoader = loader.classLoaderFor("abc", withResource(code, "providers.txt", "One\nTwo\n"))
        val plugin = Class.forName("dev.celastrina.testextension.InternetArchivePlugin", true, classLoader)

        loader.keepOnly(emptySet())

        assertEquals(0, codeDir.listFiles().orEmpty().size)
        // Classes and resources not yet touched still load from the deleted file's code.
        Class.forName("dev.celastrina.testextension.InternetArchiveProvider", true, classLoader)
        assertEquals("One\nTwo\n", plugin.getResourceAsStream("/providers.txt")!!.use { it.readBytes().decodeToString() })
        assertTrue(classLoader.getResource("providers.txt") != null)
    }

    private fun withResource(code: ByteArray, name: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            java.util.zip.ZipInputStream(code.inputStream()).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.encodeToByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
