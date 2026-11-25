package dev.celastrina.core.configuration

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Install Runbook (INSTALL.md) is read by agents, so nothing compiles it. These checks keep the two facts it shares
 * with the code from drifting: the `protocol_version`s it supports, and the signing certificate's fingerprint.
 */
class InstallRunbookTest {

    private val repository = File(System.getProperty("celastrina.repository"))

    /** The line after an HTML-comment marker, as INSTALL.md and README.md record machine-checked facts. */
    private fun File.lineAfter(marker: String): String {
        val lines = readLines()
        val at = lines.indexOfFirst { it.trim() == "<!-- $marker -->" }
        assertTrue(at >= 0, "$name has no <!-- $marker --> line")
        return lines[at + 1].trim()
    }

    @Test
    fun an_agent_following_the_runbook_accepts_the_protocol_version_the_app_reports() {
        val supported = File(repository, "INSTALL.md").lineAfter("supported-protocol-versions")
            .removePrefix("Supported `protocol_version`s:").split(',').map { it.trim(' ', '`', '.').toInt() }

        assertTrue(
            ConfigurationService.PROTOCOL_VERSION in supported,
            "INSTALL.md supports protocol_version $supported, but the app reports ${ConfigurationService.PROTOCOL_VERSION}. " +
                "Update the runbook for the new protocol, then add the version to its supported list.",
        )
    }

    @Test
    fun the_runbook_publishes_the_same_signing_fingerprint_as_the_readme() {
        val marker = "release-certificate-sha256"

        assertEquals(File(repository, "README.md").lineAfter(marker), File(repository, "INSTALL.md").lineAfter(marker))
    }
}
