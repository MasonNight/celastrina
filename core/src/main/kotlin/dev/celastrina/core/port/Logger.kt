package dev.celastrina.core.port

/** Where the Core writes what a developer reading the device log would want to know. */
fun interface Logger {
    fun info(message: String)

    companion object {
        val StandardOut = Logger { println(it) }
    }
}
