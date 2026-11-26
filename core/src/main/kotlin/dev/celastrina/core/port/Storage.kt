package dev.celastrina.core.port

/** The Core's persistent storage: named blobs that survive app restarts. */
interface Storage {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray)
    fun delete(key: String)
}
