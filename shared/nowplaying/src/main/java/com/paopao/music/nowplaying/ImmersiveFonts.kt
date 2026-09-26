package com.paopao.music.nowplaying

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private object FontResourceAnchor

@Composable
internal fun rememberLyricTypeface(font: LyricFont, onError: (String) -> Unit): Typeface {
    val context = LocalContext.current.applicationContext
    var loaded by remember(font) { mutableStateOf(Typeface.create(font.fallback, Typeface.NORMAL)) }
    LaunchedEffect(font, context) {
        try {
            loaded = withContext(Dispatchers.IO) { loadLyricTypeface(context, font) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onError("${font.label}不可用，已使用系统备用字体")
        }
    }
    return loaded
}

@Synchronized
private fun loadLyricTypeface(context: Context, font: LyricFont): Typeface {
    val name = font.fileName ?: return Typeface.create(font.fallback, Typeface.NORMAL)
    val folder = File(context.filesDir, "kugou_immersive/fonts-v1").apply { mkdirs() }
    val file = File(folder, name)
    if (!file.exists()) {
        val input = FontResourceAnchor::class.java.classLoader?.getResourceAsStream("immersive/fonts/$name")
            ?: error("Missing font resource: $name")
        val temporary = File(folder, "$name.part")
        try {
            input.use { stream -> temporary.outputStream().use { stream.copyTo(it) } }
            check(temporary.renameTo(file)) { "Unable to install font" }
        } finally {
            temporary.delete()
        }
    }
    return Typeface.Builder(file).apply {
        setWeight(font.weight)
        setFallback(font.fallback)
    }.build()
}
