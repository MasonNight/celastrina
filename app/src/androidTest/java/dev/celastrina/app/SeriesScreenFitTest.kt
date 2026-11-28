package dev.celastrina.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * On the Household's Fire TV, 1920×1080 at density 320 (960×540dp), a Series' episode cards stay fully on screen
 * however tall its header is: the focused card at its focus scale, its `N. Name` title and its runtime.
 */
@RunWith(AndroidJUnit4::class)
class SeriesScreenFitTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val remote = Remote(instrumentation)
    private var activity: Activity? = null
    private var restoreDisplay: List<String> = emptyList()

    @Before
    fun householdTvDisplay() {
        // The debug build opens a Series directly; the minified release build has no way in without TMDB's home rows.
        assumeTrue(BuildConfig.DEBUG)
        restoreDisplay = listOf(
            restoreCommand(remote.shell("wm size"), "wm size"),
            restoreCommand(remote.shell("wm density"), "wm density"),
        )
        remote.shell("wm size 1920x1080")
        remote.shell("wm density 320")
    }

    @After
    fun restore() {
        activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
        restoreDisplay.forEach { remote.shell(it) }
    }

    @Test
    fun episode_cards_fit_under_the_tallest_header() {
        val app = context.applicationContext as CelastrinaApplication
        SmokeCatalogue(app, instrumentation.context.assets).use { catalogue ->
            catalogue.seed()
            DebugArtwork.put(context, HEAVY_SERIES_LOGO_URL, logoPng())
            try {
                openSeries(HEAVY_SERIES_ID)
                remote.await("the title logo drawn as an image") { root ->
                    root.find { it.contentDescription?.toString() == HEAVY_SERIES && it.text == null }
                }
                remote.press(KeyEvent.KEYCODE_DPAD_DOWN)
                remote.await("an episode card focused, below the season tabs") { root ->
                    root.find { it.text?.toString() == "Season 1" }?.let { tab ->
                        val tabs = Rect().also(tab::getBoundsInScreen)
                        root.find { it.isFocused && Rect().also(it::getBoundsInScreen).top > tabs.bottom }
                    }
                }
                SystemClock.sleep(500) // Let the focus scale finish.
                val screen = Rect(0, 0, 1920, 1080)
                val root = assertNotNull(remote.root)
                assertFullyOnScreen("the focused card", assertNotNull(root.find { it.isFocused }), screen)
                val title = textNode(root, "1. $HEAVY_EPISODE")
                assertFullyOnScreen("the card's title", title, screen)
                // The card's image, drawn at its focus scale, ends above its title rather than over it.
                val scaledCard = Rect().also(assertNotNull(root.find { it.contentDescription?.toString() == HEAVY_EPISODE })::getBoundsInScreen)
                val titleBounds = Rect().also(title::getBoundsInScreen)
                assertTrue(scaledCard.bottom <= titleBounds.top, "The focused card at $scaledCard covers its title at $titleBounds")
                assertFullyOnScreen("the card's runtime", textNode(root, HEAVY_EPISODE_RUNTIME), screen)
                assertFullyOnScreen("the season tabs", textNode(root, "Season 1"), screen)
            } finally {
                DebugArtwork.remove(context, HEAVY_SERIES_LOGO_URL)
            }
        }
    }

    private fun textNode(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo =
        root.find { it.text?.toString() == text && it.isVisibleToUser } ?: fail("No visible \"$text\" on the Series screen")

    /** Within the screen, above the 24dp inset the screen keeps at its bottom, and at least one text line tall. */
    private fun assertFullyOnScreen(what: String, node: AccessibilityNodeInfo, screen: Rect) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val bottomInset = 24 * 2
        assertTrue(
            bounds.top >= screen.top && bounds.left >= screen.left && bounds.right <= screen.right &&
                bounds.bottom <= screen.bottom - bottomInset && bounds.height() >= 14 * 2,
            "Expected $what fully on the 1920×1080 screen above its bottom inset, but it was at $bounds",
        )
    }

    private fun openSeries(tmdbId: Int) {
        remote.press(KeyEvent.KEYCODE_WAKEUP)
        val intent = Intent(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .setClassName(context.packageName, "dev.celastrina.app.MainActivity")
            .putExtra("dev.celastrina.OPEN_SERIES", tmdbId)
        activity = instrumentation.startActivitySync(intent)
        val metrics = context.resources.displayMetrics
        assertEquals(2f, activity!!.resources.displayMetrics.density, "density 320 (${metrics.widthPixels}×${metrics.heightPixels})")
    }

    /** Puts back an override `wm` reported, such as `Override size: 1920x1080`, or resets to the physical value. */
    private fun restoreCommand(reported: String, command: String): String =
        Regex("Override \\w+: (\\S+)").find(reported)?.let { "$command ${it.groupValues[1]}" } ?: "$command reset"

    /** A 3:1 logo, 360×120dp at density 320, so it takes the full 120dp height the header allows a logo. */
    private fun logoPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(720, 240, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(230, 200, 60))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
