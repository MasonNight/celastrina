package dev.celastrina.core.testing

import dev.celastrina.core.port.Logger

/** The logger fake: keeps every message so a test can check what was logged. */
class RecordingLogger : Logger {
    private val logged = mutableListOf<String>()
    val messages: List<String> get() = synchronized(logged) { logged.toList() }

    override fun info(message: String) {
        synchronized(logged) { logged += message }
    }
}
