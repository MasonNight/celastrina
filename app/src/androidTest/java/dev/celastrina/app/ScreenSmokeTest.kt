package dev.celastrina.app

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Opens the actual activity, driving its remote keys and checking Android's input focus on each screen. */
@RunWith(AndroidJUnit4::class)
class ScreenSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val remote = Remote(instrumentation)
    private var activity: Activity? = null

    @After
    fun closeActivity() {
        activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
    }

    @Test
    fun HomeScreen_SettingsScreen_SourcesScreen_AddSourceScreen_ExtensionsScreen_TmdbKeyScreen_AboutScreen_open_with_focus() {
        openActivity()
        await("Home ready for remote input") { it.hasLabel("Search") || it.hasLabel("No TMDB key") }
        press(KeyEvent.KEYCODE_MENU)
        screen("Settings")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Check a populated Sources list. The default Internet Archive Source exercises the LazyColumn's SourceId key.
        screen("Sources", "Internet Archive")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        screen("Add Source", "What kind of Source?")
        press(KeyEvent.KEYCODE_BACK)
        screen("Sources", "Internet Archive")
        press(KeyEvent.KEYCODE_BACK)
        screen("Settings")

        openSetting("Extensions", 1)
        screen("Extensions", "No Extension Repositories imported.")
        press(KeyEvent.KEYCODE_BACK)
        screen("Settings")

        openSetting("TMDB key", 2)
        screen("TMDB key", "Your TMDB key")
        press(KeyEvent.KEYCODE_BACK)
        screen("Settings")

        openSetting("About", 3)
        screen("About", "Back to Settings")
    }

    @Test
    fun HomeScreen_SearchScreen_SeriesDetailScreen_DetailScreen_ChooseStreamScreen_PlayerScreen_GiveUpScreen_NothingToPlayScreen_open_with_focus() {
        SmokeCatalogue(context.applicationContext as CelastrinaApplication, instrumentation.context.assets).use { catalogue ->
            catalogue.seed()
            try {
                openActivity()
                await("home with the Movie card focused") { it.focusedLabelContains(SMOKE_MOVIE) }
                press(KeyEvent.KEYCODE_DPAD_UP)
                await("home Search button focused") { it.focusedLabelContains("Search") }
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                screen("Search Movies and Series")
                press(KeyEvent.KEYCODE_BACK)
                await("home with Search focused") { it.focusedLabelContains("Search") && it.hasLabel("Movies") }

                if (BuildConfig.DEBUG) {
                    openActivity("dev.celastrina.OPEN_SERIES" to SMOKE_SERIES_ID)
                } else {
                    press(KeyEvent.KEYCODE_DPAD_DOWN)
                    await("Movie card focused") { it.focusedLabelContains(SMOKE_MOVIE) }
                    press(KeyEvent.KEYCODE_DPAD_DOWN)
                    await("Series card focused") { it.focusedLabelContains(SMOKE_SERIES) }
                    press(KeyEvent.KEYCODE_DPAD_CENTER)
                }
                screen(SMOKE_SERIES, SMOKE_SEASON)
                playEpisodeInOneClick()
                findNothingToPlay()
                leaveWhileFindingAStream(catalogue)

                if (BuildConfig.DEBUG) {
                    openActivity("dev.celastrina.OPEN_MOVIE" to SMOKE_MOVIE_ID)
                } else {
                    press(KeyEvent.KEYCODE_BACK)
                    await("home with the Series card focused") { it.focusedLabelContains(SMOKE_SERIES) }
                    press(KeyEvent.KEYCODE_DPAD_UP)
                    await("Movie card focused") { it.focusedLabelContains(SMOKE_MOVIE) }
                    press(KeyEvent.KEYCODE_DPAD_CENTER)
                }
                screen(SMOKE_MOVIE, "Choose Stream")
                openChooseStream()
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                await("player with its controls visible and focused") { it.hasLabel("Rewind") && it.hasInputFocus }

                press(KeyEvent.KEYCODE_BACK)
                screen(SMOKE_MOVIE, "Choose Stream")
                catalogue.stopServingVideo()
                openChooseStream()
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                screen("Couldn't play $SMOKE_MOVIE", "Retry")
            } finally {
                closeActivity()
            }
        }
    }

    /** From the Series screen with focus on its season tabs: Choose Stream by Menu and by holding OK, then one click plays. */
    private fun playEpisodeInOneClick() {
        assertTrue(!lastScreen.hasLabel("Pick an episode"), "The Series screen still asks to pick an episode")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        await("episode card focused") { it.focusedLabelContains(SMOKE_EPISODE) }
        press(KeyEvent.KEYCODE_MENU)
        screen("Choose Stream", "Smoke video")
        press(KeyEvent.KEYCODE_BACK)
        await("Series screen with the episode card focused") { it.hasLabel(SMOKE_SEASON) && it.focusedLabelContains(SMOKE_EPISODE) }
        press(KeyEvent.KEYCODE_DPAD_CENTER, longPress = true)
        screen("Choose Stream", "Smoke video")
        press(KeyEvent.KEYCODE_BACK)
        await("Series screen with the episode card focused") { it.hasLabel(SMOKE_SEASON) && it.focusedLabelContains(SMOKE_EPISODE) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("player with its controls visible and focused") { it.hasLabel("Rewind") && it.hasInputFocus }
        press(KeyEvent.KEYCODE_BACK)
        await("Series screen with the played episode card focused") { it.hasLabel(SMOKE_SEASON) && it.focusedLabelContains(SMOKE_EPISODE) }
    }

    /** From the first episode card: an episode no Source has opens the nothing-playable screen, and its Back returns. */
    private fun findNothingToPlay() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("the episode no Source has focused") { it.focusedLabelContains(SMOKE_UNPLAYABLE_EPISODE) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("the nothing-playable screen with Back focused and Choose Stream offered") {
            it.hasLabel("Couldn't play") && it.hasLabel("Choose Stream") && it.focusedLabelContains("Back")
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Series screen with that episode card focused") { it.hasLabel(SMOKE_SEASON) && it.focusedLabelContains(SMOKE_UNPLAYABLE_EPISODE) }
    }

    /**
     * From the second episode card: Back while "Finding a Stream…" returns to the Series screen on the episode's card,
     * and cancels the Lookup, so the Source's late answer goes unread: a Lookup still running would log the torrent it drops.
     */
    private fun leaveWhileFindingAStream(catalogue: SmokeCatalogue) {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("the episode whose Source holds its answer focused") { it.focusedLabelContains(SMOKE_SLOW_EPISODE) }
        remote.shell("logcat -c")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Finding a Stream") { it.hasLabel("Finding a Stream") }
        press(KeyEvent.KEYCODE_BACK)
        await("Series screen with that episode card focused") { it.hasLabel(SMOKE_SEASON) && it.focusedLabelContains(SMOKE_SLOW_EPISODE) }

        catalogue.answerSlowEpisode()
        SystemClock.sleep(3_000)
        val log = remote.shell("logcat -d -s Celastrina:I")
        assertTrue("Dropped Stream" !in log, "Back didn't cancel the Lookup, which went on to read the Source's answer:\n$log")
        await("Series screen, still") { it.hasLabel(SMOKE_SEASON) && it.focusedLabelContains(SMOKE_SLOW_EPISODE) }
    }

    private fun openChooseStream() {
        await("the Play button focused after a Stream is found") {
            it.focusedLabelContains("Play") || it.focusedLabelContains("Resume")
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Choose Stream button focused") { it.focusedLabelContains("Choose Stream") }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        screen("Choose Stream", "Smoke video")
    }

    private fun openSetting(label: String, downPresses: Int) {
        await("Sources setting focused") { it.focusedLabelContains("Sources") }
        repeat(downPresses) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        await("$label setting focused") { it.focusedLabelContains(label) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun openActivity(extra: Pair<String, Int>? = null) {
        closeActivity()
        press(KeyEvent.KEYCODE_WAKEUP)
        val intent = Intent(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .setClassName(context.packageName, "dev.celastrina.app.MainActivity")
        extra?.let { (name, value) -> intent.putExtra(name, value) }
        activity = instrumentation.startActivitySync(intent)
    }

    private fun press(key: Int, longPress: Boolean = false) = remote.press(key, longPress)

    private fun screen(vararg labels: String) {
        await("${labels.joinToString(" / ")} visible with D-pad focus") { screen ->
            labels.all(screen::hasLabel) && screen.hasInputFocus
        }
    }

    private var lastScreen = VisibleScreen()

    private fun await(description: String, matches: (VisibleScreen) -> Boolean) {
        remote.await(description) { root -> VisibleScreen().apply { read(root) }.also { lastScreen = it }.takeIf(matches) }
    }

    private class VisibleScreen {
        private val labels = mutableListOf<String>()
        private val focusedLabels = mutableListOf<String>()
        var hasInputFocus = false
            private set

        fun hasLabel(label: String) = labels.any { it.contains(label, ignoreCase = true) }
        fun focusedLabelContains(label: String) = focusedLabels.any { it.contains(label, ignoreCase = true) }

        fun read(node: AccessibilityNodeInfo, parentFocused: Boolean = false) {
            if (!node.isVisibleToUser) return
            val focused = parentFocused || node.isFocused
            hasInputFocus = hasInputFocus || focused
            val readable = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            labels.addAll(readable)
            if (focused) focusedLabels.addAll(readable)
            repeat(node.childCount) { node.getChild(it)?.let { child -> read(child, focused) } }
        }

        override fun toString() = "labels=$labels, focused=$hasInputFocus $focusedLabels"
    }
}
