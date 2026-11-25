package dev.celastrina.app

import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.celastrina.core.configuration.ChangedFrom
import dev.celastrina.core.configuration.ConfigurationChange
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the configuration ContentProvider the way the agent does: `content call` and `content query` from the
 * shell, base64-encoded JSON in, one JSON reply out. The rules behind it are covered by the Core's tests.
 *
 * Runs on a device (the AFTTI43) against the installed Celastrina Dev, whose configuration it leaves as it found it.
 * It reads the replies with Android's own org.json: against the minified release APK (-PtestBuildType=release) the test
 * links against the app's copy of each library, and R8 renames those it doesn't keep, kotlinx.serialization among them.
 */
@RunWith(AndroidJUnit4::class)
class ConfigurationProviderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val authority = "${BuildConfig.APPLICATION_ID}.configuration"

    @Test
    fun importing_an_extension_repository_without_acknowledging_the_warning_answers_the_warning() {
        val reply = contentCall("import-repository", base64("""{"url":"https://example.org/repo.json"}"""))

        assertEquals("invalid", reply.string("result"))
        assertEquals("warning_not_acknowledged", reply.string("problem"))
        assertTrue("home network" in reply.string("warning"), reply.toString())
        assertEquals(1, reply.getInt("warning_version"))
    }

    @Test
    fun the_agent_lists_updates_and_removes_extensions() {
        val extensions = contentCall("extensions")
        val removeUnknown = contentCall("remove-extension", base64("""{"name": "NoSuchExtension"}"""))
        val removeUnknownRepository = contentCall("remove-repository", base64("""{"url": "https://example.org/no-such-repo.json"}"""))
        val updateUnknown = contentCall("update-extension", base64("""{"name": "NoSuchExtension"}"""))
        val noName = contentCall("remove-extension")

        assertEquals("ok", extensions.string("result"))
        assertTrue(extensions.getInt("updates_available") >= 0, extensions.toString())
        assertTrue(extensions.get("repositories") is JSONArray, extensions.toString())
        assertEquals("ok" to "ok", removeUnknown.string("result") to removeUnknownRepository.string("result"))
        assertEquals("unknown_extension", updateUnknown.string("problem"))
        assertEquals("bad_argument", noName.string("problem"))
    }

    @Test
    fun the_agent_reads_the_status_with_the_protocol_version() {
        val status = contentCall("status")
        val sources = contentCall("list").getJSONArray("sources").length()

        assertEquals("ok", status.string("result"))
        assertEquals(1, status.getInt("protocol_version"))
        assertEquals(BuildConfig.VERSION_NAME, status.string("app_version"))
        assertEquals(sources, status.getInt("sources"))
        assertTrue(status.getInt("extensions") >= 0, status.toString())
        assertTrue(status.string("tmdb_key") in setOf("project", "override", "none"), status.toString())
        val queried = shell("content query --uri content://$authority/status")
        assertTrue("protocol_version=1" in queried, queried)
        assertTrue("tmdb_key=${status.string("tmdb_key")}" in queried, queried)
    }

    @Test
    fun the_agent_sets_and_clears_the_households_tmdb_key_without_it_being_revealed() {
        val before = contentCall("status").string("tmdb_key")
        // The Household's own key can't be read back, so a TV that has one is left alone.
        assumeTrue("Celastrina Dev already has the Household's own TMDB key", before != "override")
        val key = "not-a-real-tmdb-key-${System.nanoTime()}"

        val set = contentCall("set-tmdb-key", base64("""{"key": "$key"}"""))
        val whileSet = contentCall("status")
        val queriedWhileSet = shell("content query --uri content://$authority/status")
        val cleared = contentCall("clear-tmdb-key")
        val clearedAgain = contentCall("clear-tmdb-key")

        assertEquals("ok", set.string("result"))
        assertEquals("override", whileSet.string("tmdb_key"))
        listOf(whileSet.toString(), queriedWhileSet, contentCall("list").toString()).forEach { assertFalse(key in it, it) }
        assertEquals("ok" to "ok", cleared.string("result") to clearedAgain.string("result"))
        assertEquals(before, contentCall("status").string("tmdb_key"))
        assertEquals("blank_tmdb_key", contentCall("set-tmdb-key", base64("""{"key": " "}""")).string("problem"))
        assertEquals("bad_argument", contentCall("clear-tmdb-key", base64("""{"key": "x"}""")).string("problem"))
    }

    @Test
    fun base64_json_arguments_reach_the_configuration_service() {
        val reply = contentCall("update", base64("""{"id": "no-such-source", "name": "Movie Night"}"""))

        assertEquals("invalid", reply.string("result"))
        assertEquals("unknown_source", reply.string("problem"))
        assertEquals("There's no Source with the ID no-such-source.", reply.string("message"))
    }

    @Test
    fun adding_a_server_that_cant_be_reached_replies_failure_with_its_id_and_keeps_it_failing() {
        val add = """{"kind": "jellyfin", "name": "Provider Test Jellyfin", "url": "http://127.0.0.1:9", "username": "demo", "password": "secret"}"""
        try {
            val reply = contentCall("add", base64(add))

            assertEquals(listOf("failure", "provider-test-jellyfin", "unreachable"), listOf(reply.string("result"), reply.string("id"), reply.string("reason")))
            val listed = sources().single { it.string("id") == "provider-test-jellyfin" }
            assertEquals("failing" to false, listed.string("test_state") to listed.getBoolean("has_login"))
            assertTrue("secret" !in shell("run-as ${BuildConfig.APPLICATION_ID} cat files/core/sources.json"))
        } finally {
            contentCall("remove", base64("""{"id": "provider-test-jellyfin"}"""))
        }
    }

    @Test
    fun the_agent_sets_the_source_order() {
        val add = """{"kind": "jellyfin", "name": "Provider Test Order", "url": "http://127.0.0.1:9", "username": "demo", "password": ""}"""
        val before = sources().map { it.string("id") }
        try {
            contentCall("add", base64(add))

            val reply = contentCall("set-order", base64("""{"ids": ["provider-test-order"]}"""))

            assertEquals("ok", reply.string("result"))
            assertEquals(listOf("provider-test-order") + before, sources().map { it.string("id") })
            assertEquals("bad_argument", contentCall("set-order", base64("""{"ids": "provider-test-order"}""")).string("problem"))
        } finally {
            contentCall("remove", base64("""{"id": "provider-test-order"}"""))
        }
    }

    @Test
    fun arguments_that_dont_decode_are_refused_with_a_reason() {
        val notBase64 = contentCall("update", "not-base64!")
        val notAnObject = contentCall("update", base64("""["internet-archive"]"""))
        val nameNotAString = contentCall("update", base64("""{"id": "internet-archive", "name": 7}"""))
        val noId = contentCall("update", base64("""{"name": "Movie Night"}"""))
        val unexpected = contentCall("enable", base64("""{"id": "internet-archive", "url": "https://example.org"}"""))
        val unknownCommand = contentCall("rename")

        listOf(notBase64, notAnObject, nameNotAString, noId, unexpected).forEach { reply ->
            assertEquals("invalid" to "bad_argument", reply.string("result") to reply.string("problem"), reply.toString())
        }
        assertEquals("unknown_command", unknownCommand.string("problem"))
    }

    @Test
    fun a_change_from_a_computer_is_announced_on_the_tv() = runBlocking {
        val source = contentCall("list").getJSONArray("sources").optJSONObject(0)
        assumeTrue("Celastrina Dev has no Sources to toggle", source != null)
        val id = source!!.string("id")
        val name = source.string("name")
        val wasEnabled = source.getBoolean("enabled")
        val configuration = (instrumentation.targetContext.applicationContext as CelastrinaApplication).configuration
        val announced = mutableListOf<ConfigurationChange>()
        val listening = launch(start = CoroutineStart.UNDISPATCHED) { configuration.changes.toList(announced) }

        val toggles = if (wasEnabled) listOf("disable", "enable") else listOf("enable", "disable")
        toggles.forEach { assertEquals("ok", contentCall(it, base64("""{"id": "$id"}""")).string("result")) }
        withTimeout(5_000) { while (announced.size < 2) delay(50) }
        listening.cancel()

        val actions = toggles.map { if (it == "enable") ConfigurationChange.Action.Enabled else ConfigurationChange.Action.Disabled }
        assertEquals(actions.map { ConfigurationChange(it, name, ChangedFrom.Computer) }, announced)
    }

    @Test
    fun celastrina_itself_may_call_but_another_app_is_refused() {
        val client = checkNotNull(instrumentation.targetContext.contentResolver.acquireContentProviderClient(authority))
        val sources = Uri.parse("content://$authority/sources")
        val anotherApp = Process.myUid() / PER_USER_RANGE * PER_USER_RANGE + 19_999
        try {
            assertTrue(client.call("status", null, null)!!.getString("reply")!!.contains("protocol_version"))

            // Calls in Celastrina's own process don't cross Binder, so pretend the call came from another app's uid.
            val ownIdentity = Binder.clearCallingIdentity()
            try {
                Binder.restoreCallingIdentity((anotherApp.toLong() shl 32) or Process.myPid().toLong())
                assertFailsWith<SecurityException> { client.call("status", null, null) }
                assertFailsWith<SecurityException> { client.query(sources, null, null, null, null) }
            } finally {
                Binder.restoreCallingIdentity(ownIdentity)
            }
        } finally {
            client.close()
        }
    }

    private fun sources() = contentCall("list").getJSONArray("sources").let { list -> List(list.length()) { list.getJSONObject(it) } }

    private fun contentCall(method: String, arg: String? = null): JSONObject {
        val output = shell("content call --uri content://$authority --method $method" + (arg?.let { " --arg $it" } ?: ""))
        // `content call` prints: Result: Bundle[{reply={"result":"ok",…}}]
        val reply = output.substringAfter("reply=", missingDelimiterValue = "").substringBeforeLast("}]")
        check(reply.isNotEmpty()) { "No reply from content call: $output" }
        return JSONObject(reply)
    }

    /** Runs [command] as the `shell` user, as `adb shell` does. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    private fun base64(json: String) = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)

    private fun JSONObject.string(name: String): String = getString(name)

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}
