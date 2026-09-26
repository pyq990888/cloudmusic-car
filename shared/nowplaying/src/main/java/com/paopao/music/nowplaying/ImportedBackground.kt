package com.paopao.music.nowplaying

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal const val MAX_BACKGROUND_BYTES = 32L * 1024 * 1024

internal fun isSupportedImageHeader(header: ByteArray): Boolean {
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    fun starts(prefix: ByteArray) = header.size >= prefix.size && prefix.indices.all { header[it] == prefix[it] }
    return starts(png) ||
        starts(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) ||
        starts("GIF87a".toByteArray()) || starts("GIF89a".toByteArray()) ||
        (header.size >= 12 && starts("RIFF".toByteArray()) && header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()))
}

internal fun copyImageBounded(input: InputStream, output: OutputStream, limit: Long = MAX_BACKGROUND_BYTES): Long {
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "图片不能超过 32 MB" }
        output.write(buffer, 0, count)
    }
    require(total > 0) { "图片文件为空" }
    return total
}

internal fun backgroundFile(context: Context, name: String?): File? {
    if (name == null || !name.matches(Regex("background-[a-f0-9-]+\\.img"))) return null
    val folder = File(context.filesDir, "kugou_immersive/backgrounds")
    return File(folder, name).takeIf { it.isFile && it.canonicalFile.parentFile == folder.canonicalFile }
}

internal suspend fun importBackground(context: Context, uri: Uri, store: ImmersivePreferenceStore) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val label = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0)?.replace('\n', ' ')?.take(100) else null
    } ?: "自定义背景"
    val folder = File(context.filesDir, "kugou_immersive/backgrounds").apply { check(mkdirs() || isDirectory) }
    val pending = File(folder, "background-${UUID.randomUUID()}.img")
    var committed = false
    try {
        val input = resolver.openInputStream(uri) ?: error("无法读取选择的图片")
        input.use { stream -> pending.outputStream().use { copyImageBounded(stream, it) } }
        currentCoroutineContext().ensureActive()
        val header = pending.inputStream().use { stream ->
            val bytes = ByteArray(12)
            val count = stream.read(bytes)
            bytes.copyOf(count.coerceAtLeast(0))
        }
        require(isSupportedImageHeader(header)) { "仅支持 JPEG、PNG、GIF 和 WebP 图片" }
        val decoded = decodeBackground(pending)
        (decoded as? AnimatedImageDrawable)?.stop()
        currentCoroutineContext().ensureActive()
        val previous = backgroundFile(context, store.read().imageName)
        check(
            store.preferences.edit()
                .putString("image_file", pending.name)
                .putString("image_label", label)
                .putString("backdrop", ImmersiveBackdrop.CUSTOM.name)
                .commit(),
        ) { "无法保存背景" }
        committed = true
        previous?.delete()
    } finally {
        if (!committed) pending.delete()
    }
}

internal suspend fun clearBackground(context: Context, store: ImmersivePreferenceStore) = withContext(Dispatchers.IO) {
    val previous = backgroundFile(context, store.read().imageName)
    val editor = store.preferences.edit().remove("image_file").remove("image_label")
    if (store.read().backdrop == ImmersiveBackdrop.CUSTOM) editor.putString("backdrop", ImmersiveBackdrop.INK.name)
    check(editor.commit()) { "无法移除背景" }
    previous?.delete()
}

internal fun decodeBackground(file: File): Drawable =
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        require(w > 0 && h > 0 && w.toLong() * h <= 80_000_000L) { "图片分辨率过大" }
        val ratio = minOf(1f, 1600f / maxOf(w, h))
        decoder.setTargetSize((w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

@Composable
internal fun ImportedImageBackground(
    name: String?,
    running: Boolean,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var drawable by remember(name) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(name) {
        try {
            drawable = withContext(Dispatchers.IO) {
                val file = backgroundFile(context, name) ?: error("背景图片已不存在，请重新选择")
                decodeBackground(file)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onError("背景图片无法加载，请重新选择")
        }
    }
    DisposableEffect(drawable, running) {
        val animation = drawable as? AnimatedImageDrawable
        animation?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        if (running) animation?.start() else animation?.stop()
        onDispose { animation?.stop() }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { if (it.drawable !== drawable) it.setImageDrawable(drawable) },
        onRelease = { (it.drawable as? AnimatedImageDrawable)?.stop(); it.setImageDrawable(null) },
        modifier = modifier,
    )
}
