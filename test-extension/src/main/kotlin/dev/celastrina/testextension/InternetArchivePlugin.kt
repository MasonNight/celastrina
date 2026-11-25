package dev.celastrina.testextension

import com.lagradost.cloudstream3.plugins.BasePlugin

/**
 * Celastrina's test Extension: a CloudStream plugin whose one provider plays films from the Internet Archive. It exists
 * only so automated tests can load a real `.cs3`; it is never released, recommended or imported by the runbook.
 */
class InternetArchivePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(InternetArchiveProvider())
    }
}
