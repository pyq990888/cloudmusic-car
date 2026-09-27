package com.paopao.music.nowplaying

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

private data class ModeLine(val track: Long?, val index: Int, val text: String, val words: List<LyricWord>)
private data class ModePiece(val raster: LyricRaster, val offset: Int, val x: Float, val y: Float, val rotation: Float, val scale: Float)

private const val TransitionMs = 1100L

/**
 * A line on stage keeps the layout it was first drawn with, so a stage resize at a line change
 * (a translation or interlude hint appearing below) never re-lays out the line that is leaving.
 * The default [enteredAt] means already in place; [leftAt] is set once the line starts leaving.
 */
private class StageLine(
    val line: ModeLine,
    val pieces: List<ModePiece>,
    val enteredAt: Long = -TransitionMs,
    val leftAt: Long = Long.MAX_VALUE,
)

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
        ArticleLyrics(lines, index, clock, typeface, color, accent, reducedMotion || !playing || !foreground, modifier)
        return
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        // Rasters (with their blurred halos) are built off the main thread one line ahead and kept
        // until that line comes on stage, so a line change never stalls a frame on text layout.
        val cache = remember(mode, width, height, density, typeface) { ConcurrentHashMap<String, List<ModePiece>>() }
        fun pieces(text: String) = cache.getOrPut(text) {
            makeModePieces(text, mode, width, height, density.density * density.fontScale, typeface)
        }
        val upcoming = lines.getOrNull(index + 1)?.let { if (it.words.isEmpty()) it.text else it.words.joinToString("") { w -> w.text } }
        LaunchedEffect(cache, upcoming) {
            if (!upcoming.isNullOrBlank()) withContext(Dispatchers.Default) { pieces(upcoming) }
        }
        SideEffect { cache.keys.retainAll(setOf(incoming.text, upcoming)) }
        var shown by remember(mode) { mutableStateOf(StageLine(incoming, pieces(incoming.text))) }
        var leaving by remember(mode) { mutableStateOf(emptyList<StageLine>()) }
        val animate = playing && foreground && !reducedMotion
        LaunchedEffect(incoming, cache) {
            val built = cache[incoming.text] ?: withContext(Dispatchers.Default) { pieces(incoming.text) }
            val last = shown
            val now = visualTime.longValue
            when {
                last.line.track == incoming.track && last.line.index == incoming.index -> {
                    if (built !== last.pieces || last.line != incoming) shown = StageLine(incoming, built, last.enteredAt)
                }
                animate && last.line.track == incoming.track -> {
                    // Lines still dissolving keep going; dropping them mid-way would pop.
                    leaving = leaving.filter { now - it.leftAt < TransitionMs } + StageLine(last.line, last.pieces, last.enteredAt, now)
                    shown = StageLine(incoming, built, now)
                }
                else -> {
                    leaving = emptyList()
                    shown = StageLine(incoming, built)
                }
            }
        }
        LaunchedEffect(reducedMotion, foreground) {
            if (reducedMotion || !foreground) {
                leaving = emptyList()
                shown = shown.let { StageLine(it.line, it.pieces) }
            }
        }
        val brush = remember(color, accent) { LyricBrush(color, accent) }
        Canvas(Modifier.fillMaxSize().semantics { text = AnnotatedString(incoming.text) }) {
            val canvas = drawContext.canvas.nativeCanvas
            val now = visualTime.longValue
            leaving.forEach { drawStageLine(canvas, it, mode, clock, now, false, brush) }
            drawStageLine(canvas, shown, mode, clock, now, reducedMotion, brush)
        }
    }
}

/** Reused paints and scratch rects; nothing here is allocated per frame. */
private class LyricBrush(color: Color, accent: Color) {
    val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_IN)
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = accent.toArgb(); strokeWidth = 1f }
    // ALPHA_8 halos take their colour from the paint.
    val glow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowRgb = accent.toArgb() and 0xFFFFFF
    /** Soft wipe edge: opaque before x = 0, transparent after x = 1; the local matrix places it on the glyph. */
    val featherShader = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(-0x1, 0), null, Shader.TileMode.CLAMP)
    val feather = Paint().apply {
        shader = featherShader
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    val matrix = Matrix()
    val src = Rect()
    val dst = RectF()
    val halo = RectF()

    fun glowAlpha(alpha: Float) { glow.color = ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or glowRgb }
}

private fun makeModePieces(text: String, mode: FoliaLyricMode, width: Int, height: Int, fontDensity: Float, typeface: Typeface): List<ModePiece> {
    val sections = lyricSections(text, modeRowCount(mode, text))
    if (mode == FoliaLyricMode.LUMINOUS || sections.size == 1) {
        val raster = buildLyricRaster(text, (width * 0.92f).toInt(), (height * 0.70f).toInt(), fontDensity, false, typeface, glow = true)
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
            glow = true,
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

/**
 * One pass draws a line both live and leaving: the exit is layered on top of the live pose, karaoke
 * fill and halo, so a line that has just started leaving looks exactly as it did a frame before.
 * Glyphs light through a feathered edge, keep a fading halo behind the sung front,
 * and long sustained words swell further than quick ones.
 */
private fun drawStageLine(
    canvas: NativeCanvas, stage: StageLine, mode: FoliaLyricMode, clock: PlaybackClock,
    now: Long, still: Boolean, brush: LyricBrush,
) {
    val leaving = ((now - stage.leftAt) / TransitionMs.toFloat()).coerceAtLeast(0f)
    if (leaving >= 1f) return
    val progress = if (still) 1f else ((now - stage.enteredAt) / TransitionMs.toFloat()).coerceIn(0f, 1f)
    val line = stage.line
    val pieces = stage.pieces
    val lit = if (line.words.isEmpty()) Float.MAX_VALUE else litCharacters(line.words, clock.position.longValue + LyricLeadMs)
    val total = pieces.sumOf { it.raster.glyphs.size }.coerceAtLeast(1)
    // The afterglow of a leaving line settles over the first part of its exit instead of dropping at once.
    val cool = (leaving / 0.45f).coerceIn(0f, 1f)
    var ordinal = 0
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
            brush.line.alpha = (p * (1f - leaving * 1.6f).coerceIn(0f, 1f) * 46f).toInt().coerceIn(0, 255)
            val y = raster.bitmap.height * 0.85f
            canvas.drawLine(0f, y, raster.bitmap.width * easeOutCubic(p), y, brush.line)
        }
        raster.glyphs.forEachIndexed { i, glyph ->
            val order = ordinal++
            // Leaving glyphs rise and scatter in reading order, like breath leaving the line.
            val t = ((leaving - order.toFloat() / total * 0.45f) / 0.55f).coerceIn(0f, 1f)
            if (t >= 1f) return@forEachIndexed
            val start = piece.offset + glyph.start
            val end = piece.offset + glyph.end
            val sung = ((lit - start) / (end - start)).coerceIn(0f, 1f)
            val singing = sung > 0f && sung < 1f
            val sustain = if (still) 0f else line.sustain(start)
            val pulse = if (still || !singing) 0f else sin(sung * PI).toFloat() * (0.55f + sustain * 0.9f)
            val delay = order.toFloat() / total * 0.3f
            val enter = ((p - delay) / (1f - delay)).coerceIn(0f, 1f)
            val back = easeOutBack(enter)
            val drift = if (still) 0f else sin(now * 0.001 + i * 0.3).toFloat() * raster.em * 0.015f
            val entryY = when (mode) {
                FoliaLyricMode.LUMINOUS -> raster.em * 0.7f
                FoliaLyricMode.MINDSCAPE -> raster.em * 1.4f * if (i % 2 == 0) 1f else -1f
                else -> raster.em * 0.4f
            } * (1f - back)
            val r = glyph.bounds
            canvas.save()
            canvas.translate(
                r.exactCenterX() + sin(order * 2.39f) * raster.em * 0.35f * t,
                r.exactCenterY() + entryY + drift - pulse * raster.em * 0.13f - raster.em * 0.9f * t * t,
            )
            val turn = if (mode == FoliaLyricMode.MINDSCAPE && !still) (1f - back) * (if (i % 2 == 0) -16f else 16f) else 0f
            canvas.rotate(turn + sin(order * 1.7f) * 14f * t)
            val scale = (0.55f + 0.45f * back + pulse * if (mode == FoliaLyricMode.LUMINOUS) 0.14f else 0.09f) * (1f + 0.22f * t)
            canvas.scale(scale, scale)
            val alpha = (enter * 4f).coerceIn(0f, 1f) * (1f - t).pow(2)
            brush.dst.set(-r.width() / 2f, -r.height() / 2f, r.width() / 2f, r.height() / 2f)
            // Halo is brightest at the singing front and settles into an afterglow a few glyphs back.
            val live = when {
                singing -> 0.45f + 0.55f * sung
                sung >= 1f -> if (line.words.isEmpty()) 0.3f else 0.28f + 0.72f * exp(-(lit - end) * 0.45f)
                else -> 0f
            } * (1f + sustain * 0.5f)
            val heat = live - (live - 0.3f).coerceAtLeast(0f) * cool
            val halo = raster.glow
            if (halo != null && heat > 0f && !still) {
                val b = raster.blur * 2
                brush.src.set(r.left - b, r.top - b, r.right + b, r.bottom + b)
                brush.halo.set(brush.dst.left - b, brush.dst.top - b, brush.dst.right + b, brush.dst.bottom + b)
                brush.glowAlpha(alpha * heat.coerceAtMost(1.3f) * 0.62f)
                canvas.drawBitmap(halo, brush.src, brush.halo, brush.glow)
            }
            brush.text.alpha = (alpha * if (sung >= 1f) 245 else 78).toInt().coerceIn(0, 255)
            canvas.drawBitmap(raster.bitmap, r, brush.dst, brush.text)
            if (singing) {
                val d = brush.dst
                val layer = canvas.saveLayer(d.left, d.top, d.right, d.bottom, null)
                brush.text.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(raster.bitmap, r, d, brush.text)
                val edge = d.width() * 0.45f
                brush.matrix.setScale(edge, 1f)
                brush.matrix.postTranslate(d.left + (d.width() + edge) * sung - edge, 0f)
                brush.featherShader.setLocalMatrix(brush.matrix)
                canvas.drawRect(d, brush.feather)
                canvas.restoreToCount(layer)
            }
            canvas.restore()
        }
        canvas.restoreToCount(savedPiece)
    }
}

private fun easeOutCubic(t: Float) = 1f - (1f - t).pow(3)

private fun easeOutBack(t: Float): Float {
    val c = 1.55f
    val u = t - 1f
    return 1f + (c + 1f) * u * u * u + c * u * u
}

/** 0 for quick syllables, 1 for notes held about 1.5 s or longer. */
private fun ModeLine.sustain(offset: Int): Float {
    var cursor = 0
    for (w in words) {
        cursor += w.text.length
        if (offset < cursor) return ((w.endMs - w.startMs - 350L) / 1150f).coerceIn(0f, 1f)
    }
    return 0f
}

@Composable
private fun ArticleLyrics(
    lines: List<NowPlayingLyric>, active: Int, clock: PlaybackClock, typeface: Typeface,
    color: Color, accent: Color, still: Boolean, modifier: Modifier,
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
        // Rows scrolling into view are built once; a line change adds one raster instead of rebuilding the window.
        val cache = remember(lines, width, rowHeight, density, typeface) { HashMap<Int, LyricRaster>() }
        val rows = remember(cache, first, last) {
            cache.keys.retainAll(first..last)
            (first..last).map { i ->
                i to cache.getOrPut(i) {
                    val line = lines[i]
                    val text = if (line.words.isEmpty()) line.text else line.words.joinToString("") { it.text }
                    buildLyricRaster(
                        text, (width * 0.9f).toInt(), rowHeight.toInt().coerceAtLeast(1),
                        density.density * density.fontScale, false, typeface, Layout.Alignment.ALIGN_NORMAL,
                        maximumFontSize = if (i % 5 == 2) 58 else 42, glow = true,
                    )
                }
            }
        }
        val brush = remember(color, accent) { LyricBrush(color, accent) }
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
                // The camera's focal row reads at full size; neighbours recede in size and light.
                val distance = kotlin.math.abs(index - camera.value)
                val focus = (1f - distance).coerceIn(0f, 1f)
                val fade = (1f - distance * 0.24f).coerceIn(0.3f, 1f)
                val saved = canvas.save()
                val scale = 0.88f + 0.12f * focus
                canvas.scale(scale, scale, x, y + raster.bitmap.height / 2f)
                raster.glyphs.forEach { glyph ->
                    val fraction = ((lit - glyph.start) / (glyph.end - glyph.start)).coerceIn(0f, 1f)
                    val r = glyph.bounds
                    val d = brush.dst
                    d.set(x + r.left, y + r.top, x + r.right, y + r.bottom)
                    val halo = raster.glow
                    if (index == active && fraction > 0f && halo != null) {
                        val heat = if (line.words.isEmpty()) 0.3f else 0.25f + 0.75f * exp(-(lit - glyph.end).coerceAtLeast(0f) * 0.4f)
                        val b = raster.blur * 2
                        brush.src.set(r.left - b, r.top - b, r.right + b, r.bottom + b)
                        brush.halo.set(d.left - b, d.top - b, d.right + b, d.bottom + b)
                        brush.glowAlpha(heat * fraction * 0.55f)
                        canvas.drawBitmap(halo, brush.src, brush.halo, brush.glow)
                    }
                    val base = when { index < active -> 70f; index > active -> 34f; else -> if (fraction >= 1f) 245f else 52f }
                    brush.text.alpha = (base * fade).toInt()
                    canvas.drawBitmap(raster.bitmap, r, d, brush.text)
                    if (index == active && fraction > 0f && fraction < 1f) {
                        val layer = canvas.saveLayer(d.left, d.top, d.right, d.bottom, null)
                        brush.text.alpha = 245
                        canvas.drawBitmap(raster.bitmap, r, d, brush.text)
                        val edge = d.width() * 0.45f
                        brush.matrix.setScale(edge, 1f)
                        brush.matrix.postTranslate(d.left + (d.width() + edge) * fraction - edge, 0f)
                        brush.featherShader.setLocalMatrix(brush.matrix)
                        canvas.drawRect(d, brush.feather)
                        canvas.restoreToCount(layer)
                    }
                }
                if (index == active) {
                    brush.line.alpha = (120 * focus).toInt()
                    brush.line.strokeWidth = 3f
                    canvas.drawLine(x - 12f, y + 14f, x - 12f, y + raster.bitmap.height - 14f, brush.line)
                    brush.line.strokeWidth = 1f
                }
                canvas.restoreToCount(saved)
            }
        }
    }
}
