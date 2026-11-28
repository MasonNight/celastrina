package dev.celastrina.app

import android.app.Instrumentation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.test.fail

/** Drives the app the way the remote does, through UI Automation, and reads what its active window shows. */
internal class Remote(private val instrumentation: Instrumentation) {
    private val automation get() = instrumentation.uiAutomation
    private val packageName = instrumentation.targetContext.packageName

    fun press(key: Int, longPress: Boolean = false) {
        // UI Automation's shell UID can wake a sleeping TV and send D-pad inputs even while focus is transitioning.
        shell(if (longPress) "input keyevent --longpress $key" else "input keyevent $key")
    }

    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    /** The app's active window, or null while another app's (or none) is in front. */
    val root: AccessibilityNodeInfo? get() = automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == packageName }

    /** Waits up to 12 s for [find] to find something in the app's active window, and returns it. */
    fun <T : Any> await(description: String, find: (AccessibilityNodeInfo) -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            root?.let(find)?.let { return it }
            SystemClock.sleep(100)
        }
        fail("Expected $description. Last screen: ${automation.rootInActiveWindow?.describe()}")
    }
}

/** This node or the first of its descendants that [matches]. */
internal fun AccessibilityNodeInfo.find(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    if (matches(this)) return this
    for (i in 0 until childCount) getChild(i)?.find(matches)?.let { return it }
    return null
}

/** Every visible node with a label or focus, as `*text|description@bounds`, a star marking focus. */
internal fun AccessibilityNodeInfo.describe(): String = buildList {
    fun visit(node: AccessibilityNodeInfo) {
        if (!node.isVisibleToUser) return
        val bounds = Rect().also(node::getBoundsInScreen)
        if (node.text != null || node.contentDescription != null || node.isFocused) {
            add("${if (node.isFocused) "*" else ""}${node.text ?: ""}|${node.contentDescription ?: ""}@$bounds")
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
    }
    visit(this@describe)
}.joinToString("; ")
