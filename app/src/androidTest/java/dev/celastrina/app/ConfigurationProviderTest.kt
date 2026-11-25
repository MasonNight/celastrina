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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives the configuration ContentProvider the way the agent does: `content call` and `content query` from the
 * shell, base64-encoded JSON in, one JSON reply out. The rules behind it are covered by the Core's tests.
 *
 * Runs on a device (the AFTTI43) against the installed Celastrina Dev, whose configuration it leaves as it found it.
 */
@RunWith(AndroidJUnit4::class)
class ConfigurationProviderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val authority = "${BuildConfig.APPLICATION_ID}.configuration"

    @Test
    fun the_agent_reads_the_status_with_the_protocol_version() {
        val status = contentCall("status")
        val sources = contentCall("list")["sources"]!!.jsonArray.size

        assertEquals("ok", status.string("result"))
        assertEquals(1, status["protocol_version"]!!.jsonPrimitive.int)
        assertEquals(BuildConfig.VERSION_NAME, status.string("app_version"))
        assertEquals(sources, status["sources"]!!.jsonPrimitive.int)
        assertEquals(0, status["extensions"]!!.jsonPrimitive.int)
        val queried = shell("content query --uri content://$authority/status")
        assertTrue("protocol_version=1" in queried, queried)
    }

    @Test
    fun base64_json_arguments_reach_the_configuration_service() {
        val reply = contentCall("update", base64("""{"id": "no-such-source", "name": "Movie Night"}"""))

        assertEquals("invalid", reply.string("result"))
        assertEquals("unknown_source", reply.string("problem"))
        assertEquals("There's no Source with the ID no-such-source.", reply.string("message"))
    }

    @Test
    fun arguments_that_dont_decode_are_refused_with_a_reason() {
        val notBase64 = contentCall("update", "not-base64!")
        val notAnObject = contentCall("update", base64("""["internet-archive"]"""))
        val noName = contentCall("update", base64("""{"id": "internet-archive"}"""))
        val unexpected = contentCall("enable", base64("""{"id": "internet-archive", "url": "https://example.org"}"""))
        val unknownCommand = contentCall("rename")

        listOf(notBase64, notAnObject, noName, unexpected).forEach { reply ->
            assertEquals("invalid" to "bad_argument", reply.string("result") to reply.string("problem"), reply.toString())
        }
        assertEquals("unknown_command", unknownCommand.string("problem"))
    }

    @Test
    fun a_change_from_a_computer_is_announced_on_the_tv() = runBlocking {
        val source = contentCall("list")["sources"]!!.jsonArray.firstOrNull()?.jsonObject
        assumeTrue("Celastrina Dev has no Sources to toggle", source != null)
        val id = source!!.string("id")
        val name = source.string("name")
        val wasEnabled = source["enabled"]!!.jsonPrimitive.boolean
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

    private fun contentCall(method: String, arg: String? = null): JsonObject {
        val output = shell("content call --uri content://$authority --method $method" + (arg?.let { " --arg $it" } ?: ""))
        // `content call` prints: Result: Bundle[{reply={"result":"ok",…}}]
        val reply = output.substringAfter("reply=", missingDelimiterValue = "").substringBeforeLast("}]")
        check(reply.isNotEmpty()) { "No reply from content call: $output" }
        return Json.parseToJsonElement(reply).jsonObject
    }

    /** Runs [command] as the `shell` user, as `adb shell` does. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    private fun base64(json: String) = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)

    private fun JsonObject.string(name: String) = this[name]!!.jsonPrimitive.content

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}
