package dev.celastrina.core.configuration

import dev.celastrina.core.source.LookupOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Install Runbook (INSTALL.md) is read by agents, so nothing compiles it. These checks keep the two facts it shares
 * with the code from drifting: the `protocol_version`s it supports, the signing certificate's fingerprint, and the
 * provider commands its jobs call (written `call <command> '<JSON arguments>'`).
 */
class InstallRunbookTest {

    private val repository = File(System.getProperty("celastrina.repository"))
    private val runbook = File(repository, "INSTALL.md").readText()

    /** Every provider call in the runbook (a line or code span starting `call `): the command, and the fields it passes. */
    private val calls: List<Pair<String, Set<String>>> =
        Regex("""(?m)(?:^\s*|`)call ([a-z][a-z-]*)(?: '(\{.*?\})')?""").findAll(runbook).map { match ->
            match.groupValues[1] to Regex(""""([a-z_]+)":""").findAll(match.groupValues[2]).map { it.groupValues[1] }.toSet()
        }.toList()

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

    @Test
    fun the_runbook_calls_only_commands_and_arguments_the_provider_takes() {
        assertTrue(calls.isNotEmpty(), "INSTALL.md has no `call <command>` lines")
        for ((command, fields) in calls) {
            val takes = ConfigurationCommands.fields[command]
            assertTrue(takes != null, "INSTALL.md calls $command, but the provider has no such command")
            assertTrue(takes.containsAll(fields), "INSTALL.md calls $command with ${fields - takes}, which it doesn't take ($takes)")
        }
    }

    @Test
    fun every_provider_command_has_a_job_in_the_runbook() {
        val called = calls.map { it.first }.toSet()

        assertEquals(emptySet(), ConfigurationCommands.fields.keys - called, "Commands no INSTALL.md job calls")
    }

    @Test
    fun the_runbook_explains_every_problem_and_reason_the_provider_replies() {
        // Quick Connect happens on the TV only, so the provider never replies quick_connect_unsupported.
        val problems = WriteOutcome.Problem.entries - WriteOutcome.Problem.QuickConnectUnsupported
        val wire = (problems + LookupOutcome.Failure.Reason.entries).map { it.name.replace(Regex("(?<=[a-z])(?=[A-Z])"), "_").lowercase() }

        assertEquals(emptyList(), wire.filter { "`$it`" !in runbook }, "Problems and reasons INSTALL.md doesn't explain")
    }
}
