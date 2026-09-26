package com.paopao.music.nowplaying

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

private data class ModeLine(val track: Long?, val index: Int, val text: String, val words: List<LyricWord>)
private data class ModePiece(val raster: LyricRaster, val offset: Int, val x: Float, val y: Float, val rotation: Float, val scale: Float)

@Composable
internal fun FoliaLyricStage(
    trackId: Long?,
    index: Int,
    lines: List<NowPlayingLyric>,
    fallback: String,
    clock: PlaybackClock,
    playing: Boolean,
    foreground: Boolean,
    reducedMotion: Boolean,
    mode: FoliaLyricMode,
    typeface: Typeface,
    color: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val line = lines.getOrNull(index)
    val incoming = remember(trackId, index, line, fallback) {
        ModeLine(trackId, index, line?.let { if (it.words.isEmpty()) it.text else it.words.joinToString("") { w -> w.text } }
            ?.takeIf { it.isNotBlank() } ?: fallback, line?.words.orEmpty())
    }
    val visualTime = rememberVisualTime(playing && foreground && !reducedMotion)
    if (foreground && (line?.words?.isNotEmpty() == true || mode == FoliaLyricMode.FUME)) {
        DisposableEffect(clock) {
            clock.frameClients++
            onDispose { clock.frameClients-- }
        }
    }
    if (mode == FoliaLyricMode.FUME && lines.isNotEmpty()) {
        ArticleLyrics(lines, index, clock, typeface, color, reducedMotion || !playing || !foreground, modifier)
        return
    }
    var current by remember(mode) { mutableStateOf(incoming) }
    var previous by remember(mode) { mutableStateOf<ModeLine?>(null) }
    var changedAt by remember(mode) { mutableLongStateOf(-1000L) }
    LaunchedEffect(incoming, mode) {
        previous = current.takeIf { it.index != incoming.index && it.track == incoming.track && playing && foreground && !reducedMotion }
        changedAt = if (previous == null) visualTime.longValue - 1000L else visualTime.longValue
        current = incoming
    }
    LaunchedEffect(reducedMotion, foreground) {
        if (reducedMotion || !foreground) {
            previous = null
            changedAt = visualTime.longValue - 1000L
        }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        fun pieces(item: ModeLine) = makeModePieces(item.text, mode, width, height, density.density * density.fontScale, typeface)
        val currentPieces = remember(current.text, mode, width, height, density, typeface) { pieces(current) }
        val old = previous
        val oldPieces = remember(old?.text, mode, width, height, density, typeface) { old?.let(::pieces).orEmpty() }
        val paint = remember(color) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_IN)
        } }
        val glow = remember(accent) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(accent.toArgb(), PorterDuff.Mode.SRC_IN)
        } }
        val destination = remember { RectF() }
        Canvas(Modifier.fillMaxSize().semantics { text = AnnotatedString(current.text) }) {
            val p = ((visualTime.longValue - changedAt) / 850f).coerceIn(0f, 1f)
            val canvas = drawContext.canvas.nativeCanvas
            if (old != null && p < 0.5f) {
                val saved = canvas.save()
                canvas.translate(-size.width * p * 0.13f, -size.height * p * 0.06f)
                drawModePieces(canvas, oldPieces, old, mode, clock, visualTime, 1f, (1f - p * 2).pow(2), true, paint, glow, destination)
                canvas.restoreToCount(saved)
            }
            drawModePieces(canvas, currentPieces, current, mode, clock, visualTime, if (reducedMotion) 1f else p, 1f, reducedMotion, paint, glow, destination)
        }
    }
}

private fun makeModePieces(text: String, mode: FoliaLyricMode, width: Int, height: Int, fontDensity: Float, typeface: Typeface): List<ModePiece> {
    val sections = lyricSections(text, modeRowCount(mode, text))
    if (mode == FoliaLyricMode.LUMINOUS || sections.size == 1) {
        val raster = buildLyricRaster(text, (width * 0.92f).toInt(), (height * 0.70f).toInt(), fontDensity, false, typeface)
        return listOf(ModePiece(raster, 0, (width - raster.bitmap.width) / 2f, (height - raster.bitmap.height) / 2f, 0f, 1f))
    }
    val rowHeight = (height * 0.84f / sections.size).coerceAtLeast(1f)
    return sections.mapIndexed { i, section ->
        val hero = mode == FoliaLyricMode.MINDSCAPE && i == sections.size / 2
        val raster = buildLyricRaster(
            section.text, (width * 0.79f).toInt(), (rowHeight * 0.94f).toInt(),
            fontDensity, false, typeface,
            alignment = if (mode == FoliaLyricMode.PARTITA) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER,
            maximumFontSize = if (hero) 78 else if (mode == FoliaLyricMode.MINDSCAPE) 54 else 60,
        )
        val side = if (i % 2 == 0) -1f else 1f
        ModePiece(
            raster, section.start,
            (width - raster.bitmap.width) / 2f + side * width * if (mode == FoliaLyricMode.PARTITA) 0.065f else 0.035f,
            height * 0.08f + rowHeight * i + (rowHeight - raster.bitmap.height) / 2f,
            if (mode == FoliaLyricMode.MINDSCAPE) side * 4f else side * 1.5f,
            1f,
        )
    }
}

private fun drawModePieces(
    canvas: NativeCanvas, pieces: List<ModePiece>, line: ModeLine, mode: FoliaLyricMode,
    clock: PlaybackClock, visualTime: LongState, progress: Float, opacity: Float, still: Boolean,
    paint: Paint, glow: Paint, destination: RectF,
) {
    val lit = if (line.words.isEmpty()) Float.MAX_VALUE else litCharacters(line.words, clock.position.longValue + LyricLeadMs)
    pieces.forEachIndexed { pieceIndex, piece ->
        val raster = piece.raster
        val savedPiece = canvas.save()
        val rowDelay = pieceIndex * 0.08f
        val p = ((progress - rowDelay) / (1f - rowDelay)).coerceIn(0f, 1f)
        val remain = (1f - p).pow(3)
        val side = if (pieceIndex % 2 == 0) -1f else 1f
        canvas.translate(piece.x + if (mode == FoliaLyricMode.PARTITA) side * raster.em * remain * 2.5f else 0f, piece.y)
        canvas.rotate(piece.rotation, raster.bitmap.width / 2f, raster.bitmap.height / 2f)
        if (mode == FoliaLyricMode.PARTITA) {
            val oldAlpha = glow.alpha
            glow.alpha = (opacity * 38f).toInt().coerceIn(0, 255)
            glow.strokeWidth = 1f
            canvas.drawLine(0f, raster.bitmap.height * 0.85f, raster.bitmap.width.toFloat(), raster.bitmap.height * 0.85f, glow)
            glow.alpha = oldAlpha
        }
        raster.glyphs.forEachIndexed { i, glyph ->
            val start = piece.offset + glyph.start
            val end = piece.offset + glyph.end
            val sung = ((lit - start) / (end - start)).coerceIn(0f, 1f)
            val pulse = if (still || sung <= 0f || sung >= 1f) 0f else sin(sung * PI).toFloat()
            val delay = i.toFloat() / raster.glyphs.size.coerceAtLeast(1) * 0.22f
            val enter = ((p - delay) / (1f - delay)).coerceIn(0f, 1f)
            val entry = (1f - enter).pow(3)
            val r = glyph.bounds
            val saved = canvas.save()
            val drift = if (still) 0f else sin(visualTime.longValue * 0.001 + i * 0.3).toFloat() * raster.em * 0.015f
            val entryY = when (mode) {
                FoliaLyricMode.LUMINOUS -> raster.em * 0.7f * entry
                FoliaLyricMode.MINDSCAPE -> raster.em * 1.4f * entry * if (i % 2 == 0) 1f else -1f
                else -> raster.em * 0.4f * entry
            }
            canvas.translate(r.exactCenterX(), r.exactCenterY() + entryY + drift - pulse * raster.em * 0.12f)
            val scale = 1f - entry * 0.45f + pulse * if (mode == FoliaLyricMode.LUMINOUS) 0.13f else 0.08f
            canvas.scale(scale, scale)
            if (mode == FoliaLyricMode.MINDSCAPE && !still) canvas.rotate(entry * (if (i % 2 == 0) -16f else 16f))
            destination.set(-r.width() / 2f, -r.height() / 2f, r.width() / 2f, r.height() / 2f)
            val alpha = opacity * (enter * 4f).coerceIn(0f, 1f)
            paint.alpha = (alpha * if (sung >= 1f) 240 else 85).toInt().coerceIn(0, 255)
            canvas.drawBitmap(raster.bitmap, r, destination, paint)
            if (pulse > 0f) {
                val halo = canvas.save()
                canvas.scale(1.055f, 1.055f)
                glow.alpha = (pulse * alpha * 65f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(raster.bitmap, r, destination, glow)
                canvas.restoreToCount(halo)
            }
            if (sung > 0f && sung < 1f) {
                canvas.clipRect(destination.left, destination.top, destination.left + destination.width() * sung, destination.bottom)
                paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(raster.bitmap, r, destination, paint)
            }
            canvas.restoreToCount(saved)
        }
        canvas.restoreToCount(savedPiece)
    }
}

@Composable
private fun ArticleLyrics(
    lines: List<NowPlayingLyric>, active: Int, clock: PlaybackClock, typeface: Typeface,
    color: Color, still: Boolean, modifier: Modifier,
) {
    val camera = remember { Animatable(active.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(active, still) {
        if (still || kotlin.math.abs(camera.value - active) > 4f) camera.snapTo(active.coerceAtLeast(0).toFloat())
        else camera.animateTo(active.coerceAtLeast(0).toFloat(), tween(950))
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth
        val rowHeight = constraints.maxHeight / 3.4f
        val first = (active - 2).coerceAtLeast(0)
        val last = (active + 3).coerceAtMost(lines.lastIndex)
        val rows = remember(lines, first, last, width, rowHeight, density, typeface) {
            (first..last).map { i ->
                val line = lines[i]
                val text = if (line.words.isEmpty()) line.text else line.words.joinToString("") { it.text }
                i to buildLyricRaster(
                    text, (width * 0.9f).toInt(), rowHeight.toInt().coerceAtLeast(1),
                    density.density * density.fontScale, false, typeface, Layout.Alignment.ALIGN_NORMAL,
                    maximumFontSize = if (i % 5 == 2) 58 else 42,
                )
            }
        }
        val paint = remember(color) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_IN)
        } }
        val destination = remember { RectF() }
        Canvas(Modifier.fillMaxSize().semantics { text = AnnotatedString(lines.getOrNull(active)?.text.orEmpty()) }) {
            val canvas = drawContext.canvas.nativeCanvas
            rows.forEach { (index, raster) ->
                val y = size.height * 0.40f + (index - camera.value) * rowHeight - raster.bitmap.height / 2f
                val x = size.width * 0.05f + if (index % 5 == 2) size.width * 0.03f else 0f
                val line = lines[index]
                val lit = when {
                    index < active -> Float.MAX_VALUE
                    index > active -> 0f
                    line.words.isEmpty() -> Float.MAX_VALUE
                    else -> litCharacters(line.words, clock.position.longValue + LyricLeadMs)
                }
                raster.glyphs.forEach { glyph ->
                    val fraction = ((lit - glyph.start) / (glyph.end - glyph.start)).coerceIn(0f, 1f)
                    val base = when { index < active -> 62; index > active -> 15; else -> if (fraction > 0f) 245 else 25 }
                    paint.alpha = base
                    val r = glyph.bounds
                    destination.set(x + r.left, y + r.top, x + r.right, y + r.bottom)
                    canvas.drawBitmap(raster.bitmap, r, destination, paint)
                }
                if (index == active) {
                    paint.alpha = 90
                    canvas.drawRect(x - 10f, y + 12f, x - 8f, y + raster.bitmap.height - 12f, paint)
                }
            }
        }
    }
}
