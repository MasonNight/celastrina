package dev.celastrina.app

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import java.io.File

/**
 * Puts artwork in the app's artwork cache as if it had been downloaded, so a screen shows it without reaching TMDB.
 * Coil serves a cache entry with empty metadata as is. Debug builds only: the minified release build renames Coil.
 */
@OptIn(ExperimentalCoilApi::class)
internal object DebugArtwork {
    fun put(context: Context, url: String, image: ByteArray) {
        val editor = checkNotNull(context.imageLoader.diskCache?.openEditor(url)) { "The artwork cache is busy with $url" }
        File(editor.metadata.toString()).writeBytes(ByteArray(0))
        File(editor.data.toString()).writeBytes(image)
        editor.commit()
    }

    fun remove(context: Context, url: String) {
        context.imageLoader.diskCache?.remove(url)
        context.imageLoader.memoryCache?.clear()
    }
}
