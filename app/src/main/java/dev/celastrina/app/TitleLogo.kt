package dev.celastrina.app

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import androidx.tv.material3.Text

/** A Title's logo, or its [name] as text in [textStyle] when there is no logo or it won't load (story 25). */
@Composable
fun TitleLogo(name: String, logoUrl: String?, textStyle: TextStyle, maxHeight: Dp, maxWidth: Dp) {
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    if (logoUrl == null || logoFailed) {
        Text(name, style = textStyle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    } else {
        AsyncImage(
            model = logoUrl,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onState = { if (it is AsyncImagePainter.State.Error) logoFailed = true },
            modifier = Modifier.heightIn(max = maxHeight).widthIn(max = maxWidth),
        )
    }
}
