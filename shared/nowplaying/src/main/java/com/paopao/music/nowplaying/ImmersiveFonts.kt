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
import java.util.concurrent.ConcurrentHashMap

private object FontResourceAnchor

private const val FontCacheFolder = "kugou_immersive/fonts-v2"
private const val LegacyFontCacheFolder = "kugou_immersive/fonts-v1"

/**
 * 进程级字体缓存。播放器一打开就预热，播放页每次进出都直接命中，
 * 不会再有「先用兜底字体画一遍、真字体到位后再画一遍」的二次光栅化和字体跳变。
 */
private val typefaces = ConcurrentHashMap<LyricFont, Typeface>()
private val fallbacks = ConcurrentHashMap<LyricFont, Typeface>()

/** 已装好的字体；尚未加载完时返回 null，调用方先用 [fallbackLyricTypeface] 顶着。 */
internal fun cachedLyricTypeface(font: LyricFont): Typeface? = typefaces[font]

internal fun fallbackLyricTypeface(font: LyricFont): Typeface =
    fallbacks.getOrPut(font) { Typeface.create(font.fallback, Typeface.NORMAL) }

/** 播放器打开时调用：趁用户还在封面页，把字体解出来，滑到沉浸歌词页时已经是热的。 */
internal suspend fun preloadLyricTypeface(context: Context, font: LyricFont) {
    if (typefaces.containsKey(font)) return
    withContext(Dispatchers.IO) { runCatching { loadLyricTypeface(context, font) } }
}

@Composable
internal fun rememberLyricTypeface(font: LyricFont, onError: (String) -> Unit): Typeface {
    val context = LocalContext.current.applicationContext
    var loaded by remember(font) { mutableStateOf(cachedLyricTypeface(font) ?: fallbackLyricTypeface(font)) }
    LaunchedEffect(font, context) {
        cachedLyricTypeface(font)?.let { ready -> loaded = ready; return@LaunchedEffect }
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
    typefaces[font]?.let { return it }
    val name = font.fileName ?: return fallbackLyricTypeface(font).also { typefaces[font] = it }
    val folder = File(context.filesDir, FontCacheFolder).apply { mkdirs() }
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
        File(context.filesDir, "$LegacyFontCacheFolder/$name").delete()
    }
    val built = Typeface.Builder(file).apply {
        setWeight(font.weight)
        setFallback(font.fallback)
    }.build()
    typefaces[font] = built
    return built
}
