package dev.celastrina.testextension

import com.lagradost.cloudstream3.plugins.BasePlugin

/**
 * Celastrina's test Extension: a CloudStream plugin whose one provider plays films from the Internet Archive. It exists
 * only so automated tests can load a real `.cs3`; it is never released, recommended or imported by the runbook.
 *
 * A `.cs3` that carries a `providers.txt` registers one such provider per line, named by it, instead: the tests' newer
 * versions use it to add and drop providers.
 */
class InternetArchivePlugin : BasePlugin() {
    override fun load() {
        val names = javaClass.getResourceAsStream("/providers.txt")?.bufferedReader()?.use { it.readLines() }?.filter { it.isNotBlank() }
        (names ?: listOf(InternetArchiveProvider().name)).forEach { name -> registerMainAPI(InternetArchiveProvider().apply { this.name = name }) }
    }
}
