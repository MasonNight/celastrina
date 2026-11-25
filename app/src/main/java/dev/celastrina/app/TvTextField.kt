package dev.celastrina.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * A labelled text box for the remote. Moving onto it only highlights it; pressing the centre button opens the
 * on-screen keyboard, and the keyboard's Done (or Back) returns to the box, so D-pad moves never get stuck in a
 * keyboard. A [secret] value shows as dots.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
    focusFirst: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    val box = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (focusFirst) LaunchedEffect(Unit) { box.requestFocusOnceShown() }
    // Null until the box is first opened, so a box that was never edited doesn't take focus.
    var wasEditing by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(editing) {
        if (editing) {
            field.requestFocus()
            keyboard?.show()
        } else if (wasEditing == true) {
            box.requestFocus()
        }
        wasEditing = editing
    }
    // The field reports itself unfocused as soon as it appears, before it takes focus; only losing focus it had ends editing.
    var fieldHadFocus by remember { mutableStateOf(false) }
    fun finish() {
        editing = false
        fieldHadFocus = false
    }

    Column(modifier.widthIn(max = 800.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color(0xFF9AA0AA), modifier = Modifier.padding(bottom = 4.dp))
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else keyboardType,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { finish() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .onFocusChanged {
                        when {
                            it.isFocused -> fieldHadFocus = true
                            fieldHadFocus && editing -> finish()
                        }
                    }
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                    .background(Color(0xFF202020), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            Surface(
                onClick = { editing = true },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF202020), focusedContainerColor = Color(0xFF3A3A3A)),
                modifier = Modifier.fillMaxWidth().focusRequester(box),
            ) {
                val shown = when {
                    value.isEmpty() -> placeholder
                    secret -> "•".repeat(value.length)
                    else -> value
                }
                Text(
                    shown,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) Color(0xFF7A808A) else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
