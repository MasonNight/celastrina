package dev.celastrina.core.port

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class KeptExtensionCodeTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun removing_an_extension_deletes_its_code_and_what_android_compiled_from_it() {
        val kept = "aaaa1111"
        val removed = "bbbb2222"
        listOf(
            "$kept.cs3", "oat/arm/$kept.odex", "oat/arm/$kept.vdex", "oat/$kept.cs3.cur.prof",
            "$removed.cs3", "oat/arm/$removed.odex", "oat/arm/$removed.vdex", "oat/$removed.cs3.cur.prof", "$removed.tmp",
        ).forEach { File(dir, it).apply { parentFile.mkdirs() }.writeText("code") }

        ExtensionLoader.deleteCodeExcept(dir, setOf(kept))

        val left = dir.walk().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()
        assertEquals(listOf("$kept.cs3", "oat/$kept.cs3.cur.prof", "oat/arm/$kept.odex", "oat/arm/$kept.vdex"), left)
    }

    @Test
    fun removing_every_extension_leaves_nothing_behind() {
        listOf("cccc3333.cs3", "oat/arm/cccc3333.odex", "oat/arm/cccc3333.vdex", "oat/cccc3333.cs3.cur.prof")
            .forEach { File(dir, it).apply { parentFile.mkdirs() }.writeText("code") }

        ExtensionLoader.deleteCodeExcept(dir, emptySet())

        assertEquals(emptyList(), dir.listFiles().orEmpty().map { it.name })
    }
}
