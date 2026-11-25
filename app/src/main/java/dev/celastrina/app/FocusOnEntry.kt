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
