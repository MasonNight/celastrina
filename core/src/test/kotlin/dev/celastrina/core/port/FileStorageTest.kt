package dev.celastrina.core.port

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FileStorageTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `what is written is still there after the app restarts`() {
        FileStorage(dir).write("sources", "jellyfin".toByteArray())

        val afterRestart = FileStorage(dir)

        assertContentEquals("jellyfin".toByteArray(), afterRestart.read("sources"))
    }

    @Test
    fun `nothing is read back for a key never written or since deleted`() {
        val storage = FileStorage(dir)
        storage.write("watchlist", byteArrayOf(1))

        storage.delete("watchlist")

        assertNull(storage.read("watchlist"))
        assertNull(storage.read("never-written"))
    }

    @Test
    fun `keys can't reach outside the storage directory`() {
        val storage = FileStorage(File(dir, "inner"))

        assertFailsWith<IllegalArgumentException> { storage.write("../escape", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { storage.read("a/b") }
    }
}
