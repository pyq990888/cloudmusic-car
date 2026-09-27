package com.paopao.music.nowplaying

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Camera
import android.graphics.Canvas as NativeCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private data class KineticLine(val trackId: Long?, val index: Int, val text: String, val words: List<LyricWord>)

/**
 * Only two lyric rasters exist during a handoff. Native text layout preserves shaping;
 * cached glyph slices move at draw time without measuring text on each frame.
 */
@Composable
internal fun KineticLyricStage(
    trackId: Long?,
    index: Int,
    line: NowPlayingLyric?,
    fallback: String,
    clock: PlaybackClock,
    playing: Boolean,
    foreground: Boolean,
    reducedMotion: Boolean,
    motion: LyricMotion,
    color: Color,
    portrait: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val incoming = remember(trackId, index, line, fallback) {
        KineticLine(
            trackId, index,
            line?.let { if (it.words.isEmpty()) it.text else it.words.joinToString("") { word -> word.text } } ?: fallback,
            line?.words.orEmpty(),
        )
    }
    val visualTime = rememberVisualTime(playing && foreground && !reducedMotion)
    var current by remember { mutableStateOf(incoming) }
    var previous by remember { mutableStateOf<KineticLine?>(null) }
    var changedAt by remember { mutableLongStateOf(-1200L) }
    LaunchedEffect(incoming) {
        val changedLine = incoming.trackId != current.trackId || incoming.index != current.index
        if (changedLine && playing && foreground && !reducedMotion) {
            previous = current
            changedAt = visualTime.longValue
        } else {
            previous = null
            changedAt = visualTime.longValue - 1200L
        }
        current = incoming
    }
    val finished by remember {
        derivedStateOf { visualTime.longValue - changedAt >= 1100L }
    }
    LaunchedEffect(finished, reducedMotion, foreground) {
        if (finished || reducedMotion || !foreground) previous = null
        if (reducedMotion || !foreground) changedAt = visualTime.longValue - 1200L
    }
    if (foreground && (line?.words?.isNotEmpty() == true || !reducedMotion)) {
        DisposableEffect(clock) {
            clock.frameClients++
            onDispose { clock.frameClients-- }
        }
    }
    Box(
        modifier.fillMaxWidth().height(if (compact) 180.dp else if (portrait) 390.dp else 290.dp).clipToBounds(),
    ) {
        previous?.let { outgoing ->
            RasterLyric(
                outgoing, color, clock, visualTime, compact, motion = resolveLyricMotion(motion, outgoing.index),
                outgoing = true, reducedMotion = reducedMotion,
                progress = { ((visualTime.longValue - changedAt) / 1100f).coerceIn(0f, 1f) },
            )
        }
        RasterLyric(
            current, color, clock, visualTime, compact, motion = resolveLyricMotion(motion, current.index),
            outgoing = false, reducedMotion = reducedMotion,
            progress = { if (reducedMotion) 1f else ((visualTime.longValue - changedAt) / 1100f).coerceIn(0f, 1f) },
        )
    }
}

@Composable
internal fun rememberVisualTime(running: Boolean): LongState {
    val time = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                time.longValue += ((now - last) / 1_000_000L).coerceIn(0L, 150L)
                last = now
            }
        }
    }
    return time
}

internal data class GlyphSlice(val start: Int, val end: Int, val row: Int, val bounds: Rect)
/** [glow] shares [bitmap]'s geometry, so a glyph's bounds inflated by [blur] address its halo. */
internal data class LyricRaster(val bitmap: Bitmap, val glyphs: List<GlyphSlice>, val em: Float, val glow: Bitmap? = null, val blur: Int = 0)

@Composable
private fun RasterLyric(
    line: KineticLine,
    color: Color,
    clock: PlaybackClock,
    visualTime: LongState,
    compact: Boolean,
    motion: LyricMotion,
    outgoing: Boolean,
    reducedMotion: Boolean,
    progress: () -> Float,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val raster = remember(line.text, constraints.maxWidth, constraints.maxHeight, density, compact) {
            buildLyricRaster(
                line.text, constraints.maxWidth, constraints.maxHeight,
                density.density * density.fontScale, compact,
            )
        }
        val paint = remember(color) {
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_IN)
            }
        }
        val camera = remember(density) { Camera().apply { setLocation(0f, 0f, -12f * density.density) } }
        val matrix = remember { Matrix() }
        val destination = remember { RectF() }
        Canvas(
            Modifier.fillMaxSize().then(
                if (outgoing) Modifier else Modifier.semantics { text = AnnotatedString(line.text) },
            ),
        ) {
            val p = progress()
            val canvas = drawContext.canvas.nativeCanvas
            val x = (size.width - raster.bitmap.width) / 2f
            val y = (size.height - raster.bitmap.height) / 2f
            val lit = if (line.words.isEmpty()) Float.MAX_VALUE else litCharacters(line.words, clock.position.longValue + LyricLeadMs)
            raster.glyphs.forEachIndexed { index, glyph ->
                val pose = if (reducedMotion) GlyphPose() else lyricGlyphPose(motion, p, outgoing, index, raster.glyphs.size, glyph.row)
                if (pose.alpha <= 0.005f) return@forEachIndexed
                val fraction = ((lit - glyph.start) / (glyph.end - glyph.start)).coerceIn(0f, 1f)
                val lift = if (reducedMotion || outgoing || fraction <= 0f || fraction >= 1f) 0f else sin(fraction * PI).toFloat()
                val drift = if (reducedMotion || outgoing) 0f else sin(visualTime.longValue * 0.0014 + index * 0.20).toFloat() * 0.025f
                val r = glyph.bounds
                val halfW = r.width() / 2f
                val halfH = r.height() / 2f
                val saved = canvas.save()
                canvas.translate(
                    x + r.exactCenterX() + pose.x * raster.em,
                    y + r.exactCenterY() + (pose.y + drift - lift * 0.15f) * raster.em,
                )
                if (pose.rotateX != 0f || pose.rotateY != 0f) {
                    camera.save()
                    camera.rotateX(pose.rotateX)
                    camera.rotateY(pose.rotateY)
                    camera.getMatrix(matrix)
                    camera.restore()
                    canvas.concat(matrix)
                }
                canvas.rotate(pose.rotateZ)
                val scale = pose.scale * (1f + lift * 0.085f)
                canvas.scale(scale, scale)
                destination.set(-halfW, -halfH, halfW, halfH)
                paint.alpha = (pose.alpha * if (fraction >= 1f) 255f else 100f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(raster.bitmap, r, destination, paint)
                if (fraction > 0f && fraction < 1f) {
                    canvas.clipRect(-halfW, -halfH, -halfW + r.width() * fraction, halfH)
                    paint.alpha = (pose.alpha * 255f).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(raster.bitmap, r, destination, paint)
                }
                canvas.restoreToCount(saved)
            }
        }
    }
}

internal fun buildLyricRaster(
    text: String, width: Int, height: Int, fontDensity: Float, compact: Boolean,
    typeface: Typeface? = null,
    alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER,
    maximumFontSize: Int = if (compact) 40 else 68,
    glow: Boolean = false,
): LyricRaster {
    val padding = ((if (glow) maximumFontSize * 0.34f else 12f) * fontDensity).toInt().coerceAtLeast(4)
    val contentWidth = (width - padding * 2).coerceAtLeast(1)
    val contentHeight = (height - padding * 2).coerceAtLeast(1)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        this.typeface = typeface ?: Typeface.create("sans-serif", if (text.all { it.code < 256 }) Typeface.BOLD_ITALIC else Typeface.BOLD)
    }
    fun layout(fontSize: Int): StaticLayout {
        paint.textSize = fontSize * fontDensity
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(paint.textSize * 0.12f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .build()
    }
    val candidates = (listOf(maximumFontSize) + listOf(68, 60, 52, 44, 36, 30, 24, 20, 16).filter { it < maximumFontSize }).distinct()
    var measured = layout(candidates.last())
    for (size in candidates) {
        measured = layout(size)
        if (measured.height <= contentHeight) break
    }
    val bitmap = Bitmap.createBitmap(contentWidth + padding * 2, measured.height + padding * 2, Bitmap.Config.ARGB_8888)
    NativeCanvas(bitmap).apply {
        translate(padding.toFloat(), padding.toFloat())
        measured.draw(this)
    }
    bitmap.prepareToDraw()
    val blur = if (glow) (paint.textSize * 0.2f).toInt().coerceAtLeast(2) else 0
    val halo = if (glow) {
        Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8).also {
            paint.maskFilter = BlurMaskFilter(blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
            NativeCanvas(it).apply {
                translate(padding.toFloat(), padding.toFloat())
                measured.draw(this)
            }
            paint.maskFilter = null
            it.prepareToDraw()
        }
    } else null
    val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
    val glyphs = ArrayList<GlyphSlice>()
    var start = boundaries.first()
    var end = boundaries.next()
    while (end != BreakIterator.DONE) {
        val row = measured.getLineForOffset(start)
        if (!text.substring(start, end).isBlank()) {
            val a = measured.getPrimaryHorizontal(start)
            val b = if (end >= measured.getLineEnd(row)) {
                if (measured.isRtlCharAt(start)) measured.getLineLeft(row) else measured.getLineRight(row)
            } else measured.getPrimaryHorizontal(end)
            val left = (min(a, b) + padding).toInt().coerceIn(0, bitmap.width)
            val right = (max(a, b) + padding + 0.5f).toInt().coerceIn(left, bitmap.width)
            if (right > left) {
                glyphs += GlyphSlice(
                    start, end, row,
                    Rect(left, measured.getLineTop(row) + padding, right, measured.getLineBottom(row) + padding),
                )
            }
        }
        start = end
        end = boundaries.next()
    }
    return LyricRaster(bitmap, glyphs, paint.textSize, halo, blur)
}
