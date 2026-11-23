// PROTOTYPE — throwaway. "Prototype the browsing screens" (MasonNight/celastrina#13).
// Three structurally different browsing variants, cycled with the remote's ≡ (Menu) key
// or launched directly: adb shell am start -n dev.celastrina.prototype.browsing/.MainActivity -e variant B
// The player and Choose Stream screens are shared across variants.
package dev.celastrina.prototype.browsing

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

sealed interface Screen {
    data object Home : Screen
    data class Detail(val t: Title) : Screen
    data class Choose(val t: Title, val ep: Episode?) : Screen
    data class Play(val t: Title, val ep: Episode?, val startAt: StreamOffer? = null) : Screen
}

val variants = listOf("A" to "Hero + stacked rows", "B" to "Category tabs + spotlight", "C" to "Side rail + grid")

class MainActivity : ComponentActivity() {
    private var variant by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        variant = variants.indexOfFirst { it.first == intent.getStringExtra("variant") }.coerceAtLeast(0)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
                LaunchedEffect(variant) { while (stack.size > 1) stack.removeAt(stack.lastIndex) } // switching variant returns home
                val nav = remember {
                    object : Nav {
                        override fun detail(t: Title) { stack.add(Screen.Detail(t)) }
                        override fun play(t: Title, ep: Episode?, startAt: StreamOffer?) { stack.add(Screen.Play(t, ep, startAt)) }
                        override fun chooseStream(t: Title, ep: Episode?) { stack.add(Screen.Choose(t, ep)) }
                        override fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
                    }
                }
                BackHandler { nav.back() }
                Box(Modifier.fillMaxSize().background(Bg)) {
                    when (val s = stack.last()) {
                        Screen.Home -> when (variant) { 0 -> HomeA(nav); 1 -> HomeB(nav); else -> HomeC(nav) }
                        is Screen.Detail -> when (variant) { 0 -> DetailA(s.t, nav); 1 -> DetailB(s.t, nav); else -> DetailC(s.t, nav) }
                        is Screen.Choose -> ChooseStream(s.t, s.ep, onPick = { o -> stack.removeAt(stack.lastIndex); stack.add(Screen.Play(s.t, s.ep, o)) })
                        is Screen.Play -> Player(s.t, s.ep, s.startAt)
                    }
                    if (stack.last() !is Screen.Play) {
                        val (k, n) = variants[variant]
                        PrototypeSwitcher("$k · $n", Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) variant = (variant + 1) % variants.size
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
