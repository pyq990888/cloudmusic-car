package com.paopao.music.nowplaying

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberBackdropArtwork(source: NowPlayingSource, cover: String?, imageName: String?, backdrop: ImmersiveBackdrop): Bitmap? {
    val needsImage = backdrop in listOf(
        ImmersiveBackdrop.COMMON, ImmersiveBackdrop.MONET, ImmersiveBackdrop.NOMAND, ImmersiveBackdrop.LATENT, ImmersiveBackdrop.FLUID,
    )
    val thumbnail = backdrop == ImmersiveBackdrop.FLUID
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(needsImage, thumbnail, cover, imageName) {
        bitmap = null
        if (!needsImage) return@LaunchedEffect
        try {
            bitmap = withContext(Dispatchers.IO) {
                val drawable = cover?.let {
                    source.imageLoader(context).execute(
                        ImageRequest.Builder(context).data(source.artworkUrl(it, 640)).size(640).allowHardware(false).build(),
                    ).drawable
                } ?: backgroundFile(context, imageName)?.let { file ->
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        val ratio = minOf(1f, 640f / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } ?: return@withContext null
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                val ratio = minOf(1f, 640f / maxOf(w, h))
                Bitmap.createBitmap((w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, it.width, it.height)
                    drawable.draw(Canvas(it))
                }.let { full -> if (thumbnail) fluidThumbnail(full) else full }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            bitmap = null
        }
    }
    return bitmap
}

/** Halve in steps so the 24 px result averages the whole cover instead of point-sampling it. */
private fun fluidThumbnail(source: Bitmap): Bitmap {
    var current = source
    while (maxOf(current.width, current.height) > 48) {
        val next = Bitmap.createScaledBitmap(current, (current.width / 2).coerceAtLeast(1), (current.height / 2).coerceAtLeast(1), true)
        if (current !== source) current.recycle()
        current = next
    }
    return Bitmap.createScaledBitmap(current, 24, 24, true).also { if (current !== source && current !== it) current.recycle() }
}
