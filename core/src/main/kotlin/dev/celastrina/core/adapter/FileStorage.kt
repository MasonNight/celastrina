package dev.celastrina.core.adapter

import dev.celastrina.core.port.Storage
import java.io.File

/**
 * [Storage] as one file per key in [dir]. Writes go to a temporary file and are renamed into
 * place, so a crash mid-write leaves the old value rather than half a new one.
 */
class FileStorage(private val dir: File) : Storage {

    override fun read(key: String): ByteArray? = fileFor(key).takeIf { it.isFile }?.readBytes()

    override fun write(key: String, value: ByteArray) {
        dir.mkdirs()
        val target = fileFor(key)
        val temp = File(dir, "$key.tmp")
        temp.writeBytes(value)
        check(temp.renameTo(target)) { "Couldn't save $key" }
    }

    override fun delete(key: String) {
        fileFor(key).delete()
    }

    private fun fileFor(key: String): File {
        require(KEY.matches(key)) { "Storage keys are letters, digits, '-', '_' and '.', not starting with '.': $key" }
        return File(dir, key)
    }

    private companion object {
        val KEY = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")
    }
}
