package dev.celastrina.app

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Moves focus here once the frame that shows it has been drawn. A tv-material Surface (a Button, Card or ListItem)
 * focused in the same frame it first appears doesn't show its focused style: it starts watching its own focus only
 * after that frame, so it never sees the focus arrive. Every screen that puts focus somewhere as it opens uses this.
 */
suspend fun FocusRequester.requestFocusOnceShown() {
    withFrameNanos { }
    requestFocus()
}

/** How many frames [requestFocusUntil] keeps asking over. */
private const val FOCUS_ATTEMPTS = 10

/**
 * Moves focus here, asking again each frame for a few frames until [focused] says it arrived. Coming back from the
 * player, the player's View gives focus back to Compose a frame or two after home shows, and Compose then focuses
 * home's first focusable item, the Search button, over a request made once.
 */
suspend fun FocusRequester.requestFocusUntil(focused: () -> Boolean) {
    repeat(FOCUS_ATTEMPTS) {
        withFrameNanos { }
        if (focused()) return
        runCatching { requestFocus() }
    }
}
