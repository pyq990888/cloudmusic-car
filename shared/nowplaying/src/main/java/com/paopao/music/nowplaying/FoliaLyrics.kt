package com.paopao.music.nowplaying

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
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
private data class ModePiece(
    val raster: LyricRaster, val offset: Int, val x: Float, val y: Float, val rotation: Float, val scale: Float,
    /** KINTSUGI: seam split per glyph, parallel to [LyricRaster.glyphs]; null in other modes. */
    val seams: List<GlyphSeam>? = null,
    /** MIRROR: water line in raster bitmap coordinates, below the lowest glyph bottom. */
    val mirrorAxis: Float = 0f,
)

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
    /** 翻页停稳才跑逐帧动画；未停稳时画面冻在当前这一帧。 */
    animating: Boolean = foreground,
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
    // 逐帧动画的总开关：在播放、页面前台、且翻页已经停稳。
    val moving = animating && playing
    val visualTime = rememberVisualTime(moving && !reducedMotion)
    if (moving && (line?.words?.isNotEmpty() == true || mode == FoliaLyricMode.FUME)) {
        DisposableEffect(clock) {
            clock.frameClients++
            onDispose { clock.frameClients-- }
        }
    }
    if (mode == FoliaLyricMode.FUME && lines.isNotEmpty()) {
        ArticleLyrics(lines, index, clock, typeface, color, accent, reducedMotion || !moving, modifier)
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
        // 首句也走后台线程光栅化：在组合里做 StaticLayout + 大位图 + 模糊会直接吃掉滑动的一帧。
        var shown by remember(mode) { mutableStateOf<StageLine?>(null) }
        var leaving by remember(mode) { mutableStateOf(emptyList<StageLine>()) }
        val animate = moving && !reducedMotion
        // A plain container, not snapshot state: MIRROR ripples are mutated from the draw pass.
        val mirror = remember(mode) { MirrorState() }
        LaunchedEffect(incoming, cache) {
            val built = cache[incoming.text] ?: withContext(Dispatchers.Default) { pieces(incoming.text) }
            val last = shown
            val now = visualTime.longValue
            if (last == null) {
                shown = StageLine(incoming, built)
            } else when {
                last.line.track == incoming.track && last.line.index == incoming.index -> {
                    if (built !== last.pieces || last.line != incoming) {
                        mirror.clear()
                        shown = StageLine(incoming, built, last.enteredAt)
                    }
                }
                animate && last.line.track == incoming.track -> {
                    // Lines still dissolving keep going; dropping them mid-way would pop.
                    leaving = leaving.filter { now - it.leftAt < TransitionMs } + StageLine(last.line, last.pieces, last.enteredAt, now)
                    mirror.clear()
                    shown = StageLine(incoming, built, now)
                }
                else -> {
                    leaving = emptyList()
                    mirror.clear()
                    shown = StageLine(incoming, built)
                }
            }
        }
        LaunchedEffect(reducedMotion, foreground) {
            if (reducedMotion || !foreground) {
                leaving = emptyList()
                mirror.clear()
                shown = shown?.let { StageLine(it.line, it.pieces) }
            }
        }
        val brush = remember(color, accent) { LyricBrush(color, accent) }
        Canvas(Modifier.fillMaxSize().semantics { text = AnnotatedString(incoming.text) }) {
            val canvas = drawContext.canvas.nativeCanvas
            val now = visualTime.longValue
            leaving.forEach { drawStageLine(canvas, it, mode, clock, now, false, brush, mirror, false) }
            shown?.let { drawStageLine(canvas, it, mode, clock, now, reducedMotion, brush, mirror, true) }
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

    // KINTSUGI: the fixed gold vein. The three-stop ramp spans one glyph height; the per-glyph
    // local matrix slides it so the bright stop lands on the seam head.
    val seamShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(0x14E8C57A, 0xE6E8C57A.toInt(), 0x1AE8C57A),
        floatArrayOf(0f, 0.53125f, 1f),
        Shader.TileMode.CLAMP,
    )
    val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = seamShader
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = KintsugiGold }

    // MIRROR: additive ripple rings, the gradient axis hairline and the reflection fade ramp.
    private val accentRgb = accent.toArgb() and 0xFFFFFF
    val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        this.color = accent.toArgb()
    }
    val water = Paint().apply { this.color = 0xFF04080A.toInt() }
    private val axisLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private var axisWidth = -1f

    /** Axis hairline shader; rebuilt only when the raster width changes. */
    fun axisLinePaint(width: Float): Paint {
        if (width != axisWidth) {
            axisWidth = width
            axisLine.shader = LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(accentRgb, (0x1A shl 24) or accentRgb, accentRgb),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        return axisLine
    }

    private val mirrorFade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var fadeAxis = Float.NaN
    private var fadeBand = Float.NaN

    /** Downward erase ramp over the reflection band; rebuilt only when the axis moves. */
    fun mirrorFadePaint(axis: Float, band: Float): Paint {
        if (axis != fadeAxis || band != fadeBand) {
            fadeAxis = axis
            fadeBand = band
            mirrorFade.shader = LinearGradient(0f, axis, 0f, axis + band, intArrayOf(0, 0xFF000000.toInt()), null, Shader.TileMode.CLAMP)
        }
        return mirrorFade
    }

    // SOFTFOCUS: a unit radial accent glow for sustained notes, scaled per glyph through [matrix].
    val spotShader = RadialGradient(0f, 0f, 1f, intArrayOf(accent.toArgb(), accent.toArgb() and 0xFFFFFF), null, Shader.TileMode.CLAMP)
    val spot = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = spotShader }
}

private fun makeModePieces(text: String, mode: FoliaLyricMode, width: Int, height: Int, fontDensity: Float, typeface: Typeface): List<ModePiece> {
    if (mode == FoliaLyricMode.KINTSUGI || mode == FoliaLyricMode.MIRROR || mode == FoliaLyricMode.SOFTFOCUS) {
        // Single-line modes: one raster across the stage; MIRROR reserves the lower half for water.
        val raster = buildLyricRaster(
            text, (width * 0.92f).toInt(), (height * 0.70f).toInt(), fontDensity, false, typeface,
            glow = true, softBlur = if (mode == FoliaLyricMode.SOFTFOCUS) SoftBlurRadius else 0f,
        )
        var axis = 0f
        if (mode == FoliaLyricMode.MIRROR) {
            var bottom = 0
            raster.glyphs.forEach { bottom = maxOf(bottom, it.bounds.bottom) }
            axis = bottom + raster.em * MirrorAxisGap
        }
        val seams = if (mode == FoliaLyricMode.KINTSUGI) buildSeams(text, raster) else null
        val x = (width - raster.bitmap.width) / 2f
        val y = if (mode == FoliaLyricMode.MIRROR) height * MirrorPieceY else (height - raster.bitmap.height) / 2f
        return listOf(ModePiece(raster, 0, x, y, 0f, 1f, seams, axis))
    }
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
    now: Long, still: Boolean, brush: LyricBrush, mirror: MirrorState, shown: Boolean,
) {
    val leaving = ((now - stage.leftAt) / TransitionMs.toFloat()).coerceAtLeast(0f)
    if (leaving >= 1f) return
    val progress = if (still) 1f else ((now - stage.enteredAt) / TransitionMs.toFloat()).coerceIn(0f, 1f)
    val line = stage.line
    val pieces = stage.pieces
    val lit = if (line.words.isEmpty()) Float.MAX_VALUE else litCharacters(line.words, clock.position.longValue + LyricLeadMs)
    // The split modes share the single-piece layout but draw entirely on their own terms.
    if (mode == FoliaLyricMode.KINTSUGI || mode == FoliaLyricMode.MIRROR || mode == FoliaLyricMode.SOFTFOCUS) {
        val piece = pieces.firstOrNull() ?: return
        when (mode) {
            FoliaLyricMode.KINTSUGI -> drawKintsugi(canvas, line, piece, lit, leaving, progress, now, still, brush)
            FoliaLyricMode.MIRROR -> drawMirror(canvas, stage, line, piece, lit, leaving, progress, now, still, brush, mirror, shown)
            else -> drawSoftFocus(canvas, line, piece, lit, leaving, progress, now, still, brush)
        }
        return
    }
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

// ---------------------------------------------------------------------------
// Shared helpers for the split single-line modes (KINTSUGI / MIRROR / SOFTFOCUS).
// ---------------------------------------------------------------------------

/** Deterministic [0,1) hash of an integer seed: same value every frame and every run. */
private fun hash01(seed: Int): Float {
    var x = seed * 0x9E3779B9.toInt()
    x = x xor (x ushr 16)
    x *= 0x7FEB352D
    x = x xor (x ushr 15)
    x *= 0x846CA68B.toInt()
    x = x xor (x ushr 16)
    return (x ushr 8) / 16777216f
}

/** Accent-halo heat for the split modes: brightest at the singing front, cooling behind it. */
private fun haloHeat(sung: Float, lit: Float, end: Int): Float = when {
    sung >= 1f -> 0.3f + 0.7f * exp(-(lit - end).coerceAtLeast(0f) * 0.45f)
    sung > 0f -> 0.5f + 0.5f * sung
    else -> 0f
}

/** True while [offset] sits inside a note held for more than 1.1 s. */
private fun ModeLine.longNote(offset: Int): Boolean {
    var cursor = 0
    for (w in words) {
        cursor += w.text.length
        if (offset < cursor) return w.endMs - w.startMs > 1100L
    }
    return false
}

/** Entry progress of one glyph, staggered in reading order over the first 70 % of the entrance. */
private fun glyphEnter(progress: Float, order: Int, total: Int): Float {
    val delay = order.toFloat() / total * 0.3f
    return ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
}

/** Exit progress of one glyph, staggered in reading order over the last 55 % of the exit. */
private fun glyphExit(leaving: Float, order: Int, total: Int): Float =
    ((leaving - order.toFloat() / total * 0.45f) / 0.55f).coerceIn(0f, 1f)

/**
 * One KINTSUGI glyph: a seam curve splits it into a left and a right half, all in raster bitmap
 * coordinates. [dust] holds three exit particles as (dx in glyph widths, rise scale, alpha scale)
 * triples; [phase] seeds the sustained-note seam breathing.
 */
private class GlyphSeam(
    val left: Path,
    val right: Path,
    val seam: Path,
    val phase: Float,
    val dust: FloatArray,
)

// ---------------------------------------------------------------------------
// KINTSUGI (金缮): deterministic seams cut every glyph; the halves clamp
// together on entry and split apart on exit while a fixed gold vein follows the
// sung fraction. Tuning constants:
// ---------------------------------------------------------------------------
private val KintsugiGold = 0xFFE8C57A.toInt()
private const val KintsugiSepEnter = 0.05f       // entry clamp distance, em
private const val KintsugiSepExit = 0.11f        // exit split distance, em
private const val KintsugiClipPad = 0.22f        // clip paths overshoot the glyph box, em
private const val KintsugiSeamPad = 0.16f        // seam curve overshoots the glyph box, em
private const val KintsugiSeamWidth = 0.04f      // core vein stroke width, em
private const val KintsugiSeamGlowWidth = 0.11f  // glow pass stroke width, em
private const val KintsugiSeamGlowAlpha = 0.30f  // glow pass alpha, fraction of the vein alpha
private const val KintsugiDustRise = 0.40f       // exit dust rise over the exit, em
private const val KintsugiDustAlpha = 0.55f      // exit dust peak alpha
private const val ModeHaloAlpha = 0.62f          // halo pass alpha shared by the split modes

/** Builds the per-glyph seam geometry for KINTSUGI; every value is hashed, never random. */
private fun buildSeams(text: String, raster: LyricRaster): List<GlyphSeam> {
    val em = raster.em
    val clipPad = em * KintsugiClipPad
    val seamPad = em * KintsugiSeamPad
    return raster.glyphs.mapIndexed { ordinal, glyph ->
        val code = text.codePointAt(glyph.start.coerceIn(0, text.length - 1))
        val seed = code * 131 + ordinal * 7
        val r = glyph.bounds
        val seamX = r.left + r.width() * (0.25f + hash01(seed + 1) * 0.5f)
        val botX = seamX + r.width() * (hash01(seed + 2) - 0.5f) * 0.55f
        val bowX = (seamX + botX) / 2f + r.width() * (hash01(seed + 3) - 0.5f) * 0.175f
        val midY = (r.top + r.bottom) / 2f
        val left = Path().apply {
            moveTo(r.left - clipPad, r.top - clipPad)
            lineTo(seamX, r.top - clipPad)
            quadTo(bowX, midY, botX, r.bottom + clipPad)
            lineTo(r.left - clipPad, r.bottom + clipPad)
            close()
        }
        val right = Path().apply {
            moveTo(seamX, r.top - clipPad)
            lineTo(r.right + clipPad, r.top - clipPad)
            lineTo(r.right + clipPad, r.bottom + clipPad)
            lineTo(botX, r.bottom + clipPad)
            quadTo(bowX, midY, seamX, r.top - clipPad)
            close()
        }
        val seam = Path().apply {
            moveTo(seamX, r.top - seamPad)
            quadTo(bowX, midY, botX, r.bottom + seamPad)
        }
        val dust = FloatArray(9)
        for (k in 0 until 3) {
            dust[k * 3] = (hash01(seed + 11 + k * 3) - 0.5f) * 1.6f
            dust[k * 3 + 1] = 0.7f + hash01(seed + 12 + k * 3) * 0.6f
            dust[k * 3 + 2] = 0.4f + hash01(seed + 13 + k * 3) * 0.6f
        }
        GlyphSeam(left, right, seam, hash01(seed + 4) * (2f * PI.toFloat()), dust)
    }
}

private fun drawKintsugi(
    canvas: NativeCanvas, line: ModeLine, piece: ModePiece, lit: Float, leaving: Float,
    progress: Float, now: Long, still: Boolean, brush: LyricBrush,
) {
    val raster = piece.raster
    val seams = piece.seams ?: return
    val em = raster.em
    val total = raster.glyphs.size.coerceAtLeast(1)
    val saved = canvas.save()
    canvas.translate(piece.x, piece.y)
    raster.glyphs.forEachIndexed { i, glyph ->
        val seam = seams.getOrNull(i) ?: return@forEachIndexed
        val t = glyphExit(leaving, i, total)
        if (t >= 1f) return@forEachIndexed
        val start = glyph.start
        val end = glyph.end
        val sung = ((lit - start) / (end - start)).coerceIn(0f, 1f)
        val singing = sung > 0f && sung < 1f
        val enter = glyphEnter(progress, i, total)
        val alpha = (when {
            sung >= 1f -> 0.96f
            singing -> 0.32f + 0.62f * sung
            else -> 0.30f
        }) * (1f - t).pow(1.5f) * enter
        if (alpha <= 0.01f) return@forEachIndexed
        val r = glyph.bounds
        brush.dst.set(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        val halo = raster.glow
        if (halo != null && !still) {
            val heat = haloHeat(sung, lit, end)
            if (heat > 0f) {
                val b = raster.blur * 2
                brush.src.set(r.left - b, r.top - b, r.right + b, r.bottom + b)
                brush.halo.set(brush.dst.left - b, brush.dst.top - b, brush.dst.right + b, brush.dst.bottom + b)
                brush.glowAlpha(alpha * heat * ModeHaloAlpha)
                canvas.drawBitmap(halo, brush.src, brush.halo, brush.glow)
            }
        }
        // The halves clamp together on entry and split apart again on exit.
        val sep = (1f - easeOutBack(enter)) * em * KintsugiSepEnter + t * t * em * KintsugiSepExit
        brush.text.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.clipPath(seam.left)
        brush.dst.set(r.left - sep, r.top - sep * 0.4f, r.right - sep, r.bottom - sep * 0.4f)
        canvas.drawBitmap(raster.bitmap, r, brush.dst, brush.text)
        canvas.restore()
        canvas.save()
        canvas.clipPath(seam.right)
        brush.dst.set(r.left + sep, r.top + sep * 0.4f, r.right + sep, r.bottom + sep * 0.4f)
        canvas.drawBitmap(raster.bitmap, r, brush.dst, brush.text)
        canvas.restore()
        // The gold vein: its bright head follows the sung fraction along the seam.
        val veinLit = when {
            sung >= 1f -> 0.60f
            singing -> 0.30f + 0.68f * sung
            else -> 0.22f + enter * 0.1f
        }
        val breathe = if (!still && singing && line.longNote(start)) 0.1f * sin(now * 0.0031 + seam.phase).toFloat() else 0f
        val vein = ((veinLit + breathe) * (1f - t)).coerceIn(0f, 1f) * enter
        if (vein > 0.02f) {
            val head = when {
                singing -> sung.coerceIn(0.08f, 0.92f)
                sung >= 1f -> 0.98f
                else -> 0.5f
            }
            brush.matrix.setScale(1f, 0.64f * r.height())
            brush.matrix.postTranslate(0f, r.top + (head - 0.34f) * r.height())
            brush.seamShader.setLocalMatrix(brush.matrix)
            brush.seam.strokeWidth = em * KintsugiSeamGlowWidth
            brush.seam.alpha = (vein * KintsugiSeamGlowAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawPath(seam.seam, brush.seam)
            brush.seam.strokeWidth = em * KintsugiSeamWidth
            brush.seam.alpha = (vein * 255f).toInt().coerceIn(0, 255)
            canvas.drawPath(seam.seam, brush.seam)
        }
        // Exit dust: three gold motes per glyph, rising and fading with the exit.
        if (t > 0.05f) {
            for (k in 0 until 3) {
                val tone = seam.dust[k * 3 + 2]
                brush.gold.alpha = ((1f - t).pow(1.5f) * enter * KintsugiDustAlpha * tone * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(
                    r.exactCenterX() + seam.dust[k * 3] * r.width(),
                    r.bottom - em * 0.25f - t * t * em * KintsugiDustRise * seam.dust[k * 3 + 1],
                    em * (0.021f + tone * 0.012f),
                    brush.gold,
                )
            }
        }
    }
    canvas.restoreToCount(saved)
}

// ---------------------------------------------------------------------------
// MIRROR (水镜): the line floats above a water line; every glyph gets a
// wobbling reflection fading with depth, and each newly sung glyph rings the
// water. Tuning constants:
// ---------------------------------------------------------------------------
private const val MirrorPieceY = 0.30f           // piece top as a fraction of stage height
private const val MirrorAxisGap = 0.7f           // water line below the lowest glyph bottom, em
private const val MirrorRise = 0.35f             // entry rise from under the water, em
private const val MirrorSink = 0.48f             // exit sink into the water, em
private const val MirrorLift = 0.048f            // singing lift, em
private const val MirrorBand = 1.9f              // reflection band under the axis, em
private const val MirrorSquash = 0.9f            // vertical compression of the reflection
private const val MirrorWobbleFast = 0.04f       // fast wobble amplitude, em
private const val MirrorWobbleSlow = 0.027f      // slow wobble amplitude, em
private const val MirrorRippleWidth = 0.021f     // ring stroke width, em
private const val MirrorRippleCenter = 0.048f    // ring centre below the axis, em
private const val MirrorRippleSmallFrom = 0.14f  // small ring rx start, em
private const val MirrorRippleSmallTo = 1.45f    // small ring rx end, em
private const val MirrorRippleBigFrom = 0.9f     // line-change ring rx start, em
private const val MirrorRippleBigTo = 6.5f       // line-change ring rx end, em
private const val MirrorRippleLifeSmall = 1250L  // small ring life, ms
private const val MirrorRippleLifeBig = 1900L    // line-change ring life, ms
private const val MirrorRippleMax = 26           // rings kept at once
private const val MirrorWaterLines = 10          // drifting dark lines below the axis

/**
 * Ripple bookkeeping for MIRROR, deliberately a plain container mutated from the draw pass:
 * ripples are discrete events, not observable state, so spawning one is the only allocation.
 */
private class MirrorState {
    class Ripple(val x: Float, val born: Long, val big: Boolean)

    val ripples = ArrayList<Ripple>(MirrorRippleMax)
    var prevLit = -1f
    var lineTrack: Long? = null
    var lineIndex = Int.MIN_VALUE
    var bigFor: StageLine? = null

    fun spawn(x: Float, born: Long, big: Boolean) {
        if (ripples.size >= MirrorRippleMax) return
        ripples += Ripple(x, born, big)
    }

    fun clear() {
        ripples.clear()
        bigFor = null
    }
}

private fun drawMirror(
    canvas: NativeCanvas, stage: StageLine, line: ModeLine, piece: ModePiece, lit: Float,
    leaving: Float, progress: Float, now: Long, still: Boolean, brush: LyricBrush,
    mirror: MirrorState, shown: Boolean,
) {
    val raster = piece.raster
    val em = raster.em
    val axis = piece.mirrorAxis
    val width = raster.bitmap.width.toFloat()
    val total = raster.glyphs.size.coerceAtLeast(1)
    val saved = canvas.save()
    canvas.translate(piece.x, piece.y)
    // Ripples are born the frame lit crosses a glyph start; a line change rings one big circle.
    if (shown) {
        if (mirror.lineTrack != line.track || mirror.lineIndex != line.index) {
            mirror.lineTrack = line.track
            mirror.lineIndex = line.index
            mirror.prevLit = -1f
        }
        if (!still) {
            raster.glyphs.forEach { glyph ->
                if (mirror.prevLit < glyph.start && lit >= glyph.start && lit < glyph.end) {
                    mirror.spawn(piece.x + glyph.bounds.exactCenterX(), now, false)
                }
            }
            mirror.prevLit = lit
        }
    } else if (!still && leaving > 0f && mirror.bigFor !== stage) {
        mirror.bigFor = stage
        mirror.spawn(piece.x + width / 2f, now, true)
    }
    // Reflection: glyphs mirrored about the axis and compressed, clipped to a fading band.
    val band = em * MirrorBand
    val layer = canvas.saveLayer(0f, axis, width, axis + band, null)
    raster.glyphs.forEachIndexed { i, glyph ->
        val r = glyph.bounds
        val t = glyphExit(leaving, i, total)
        if (t >= 1f) return@forEachIndexed
        val start = glyph.start
        val end = glyph.end
        val sung = ((lit - start) / (end - start)).coerceIn(0f, 1f)
        val singing = sung > 0f && sung < 1f
        val enter = glyphEnter(progress, i, total)
        val alpha = (when {
            sung >= 1f -> 0.26f
            singing -> 0.12f + 0.16f * sung
            else -> 0.12f
        }) * enter * (1f - t).pow(1.5f)
        if (alpha <= 0.005f) return@forEachIndexed
        val shift = (1f - easeOutBack(enter)) * em * MirrorRise + t * t * em * MirrorSink
        val wob = if (still) 0f else
            sin(now * 0.00115 + r.exactCenterX() * 0.012).toFloat() * em * MirrorWobbleFast +
                sin(now * 0.0005 + r.exactCenterX() * 0.004).toFloat() * em * MirrorWobbleSlow
        canvas.save()
        canvas.translate(wob, 1.9f * axis)
        canvas.scale(1f, -MirrorSquash)
        brush.text.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        brush.dst.set(r.left.toFloat(), r.top + shift, r.right.toFloat(), r.bottom + shift)
        canvas.drawBitmap(raster.bitmap, r, brush.dst, brush.text)
        canvas.restore()
    }
    canvas.drawRect(0f, axis, width, axis + band, brush.mirrorFadePaint(axis, band))
    canvas.restoreToCount(layer)
    // Axis hairline and drifting dark water lines.
    canvas.drawRect(0f, axis, width, axis + (em * 0.02f).coerceAtLeast(1f), brush.axisLinePaint(width))
    brush.water.alpha = 15
    for (k in 0 until MirrorWaterLines) {
        val y = axis + em * (0.113f + k * (0.177f + k * 0.0387f))
        if (y - axis > em * 2.2f) break
        val drift = if (still) 0f else sin(now * 0.0008 + k).toFloat() * em * 0.032f
        canvas.drawRect(0f, y + drift, width, y + drift + em * (0.0387f + k * 0.0048f), brush.water)
    }
    // Ripple rings are drawn once per frame, with the shown line.
    if (shown) {
        brush.ripple.strokeWidth = em * MirrorRippleWidth
        var i = 0
        while (i < mirror.ripples.size) {
            val ripple = mirror.ripples[i]
            val life = if (ripple.big) MirrorRippleLifeBig else MirrorRippleLifeSmall
            val t = (now - ripple.born).toFloat() / life
            if (t >= 1f) {
                mirror.ripples[i] = mirror.ripples[mirror.ripples.lastIndex]
                mirror.ripples.removeAt(mirror.ripples.lastIndex)
                continue
            }
            if (t >= 0f) {
                val from = if (ripple.big) MirrorRippleBigFrom else MirrorRippleSmallFrom
                val to = if (ripple.big) MirrorRippleBigTo else MirrorRippleSmallTo
                val rx = em * (from + t * (to - from))
                val peak = if (ripple.big) 0.20f else 0.38f
                brush.ripple.alpha = (peak * (1f - t) * (1f - t) * 255f).toInt().coerceIn(0, 255)
                val cy = axis + em * MirrorRippleCenter
                brush.dst.set(ripple.x - piece.x - rx, cy - rx * 0.30f, ripple.x - piece.x + rx, cy + rx * 0.30f)
                canvas.drawOval(brush.dst, brush.ripple)
            }
            i++
        }
    }
    // Glyph bodies: accent halo at the singing front, lit alpha, singing lift.
    raster.glyphs.forEachIndexed { i, glyph ->
        val r = glyph.bounds
        val t = glyphExit(leaving, i, total)
        if (t >= 1f) return@forEachIndexed
        val start = glyph.start
        val end = glyph.end
        val sung = ((lit - start) / (end - start)).coerceIn(0f, 1f)
        val singing = sung > 0f && sung < 1f
        val enter = glyphEnter(progress, i, total)
        val alpha = (when {
            sung >= 1f -> 0.96f
            singing -> 0.32f + 0.62f * sung
            else -> 0.30f
        }) * enter * (1f - t).pow(1.6f)
        if (alpha <= 0.01f) return@forEachIndexed
        val shift = (1f - easeOutBack(enter)) * em * MirrorRise + t * t * em * MirrorSink
        val lift = if (!still && singing) -em * MirrorLift * sin(sung * PI).toFloat() else 0f
        brush.dst.set(r.left.toFloat(), r.top + shift + lift, r.right.toFloat(), r.bottom + shift + lift)
        val halo = raster.glow
        if (halo != null && !still) {
            val heat = haloHeat(sung, lit, end)
            if (heat > 0f) {
                val b = raster.blur * 2
                brush.src.set(r.left - b, r.top - b, r.right + b, r.bottom + b)
                brush.halo.set(brush.dst.left - b, brush.dst.top - b, brush.dst.right + b, brush.dst.bottom + b)
                brush.glowAlpha(alpha * heat * ModeHaloAlpha)
                canvas.drawBitmap(halo, brush.src, brush.halo, brush.glow)
            }
        }
        brush.text.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmap(raster.bitmap, r, brush.dst, brush.text)
    }
    canvas.restoreToCount(saved)
}

// ---------------------------------------------------------------------------
// SOFTFOCUS (柔焦): a pre-blurred copy of the whole line sits under the
// clear glyphs; each glyph snaps into focus as it is sung. Tuning constants:
// ---------------------------------------------------------------------------
private const val SoftBlurRadius = 0.15f      // soft layer blur radius, fraction of the text size
private const val SoftLayerAlpha = 0.42f      // whole-line defocused layer alpha
private const val SoftClearAlpha = 0.96f      // clear glyph peak alpha
private const val SoftExitFade = 1.7f         // clear glyphs are gone after ~59 % of their exit
private const val SoftExitRise = 0.45f        // late-exit upward drift, em
private const val SoftBreath = 0.012f         // line breathing amplitude
private const val SoftSingScale = 0.018f      // singing micro scale
private const val SoftSpotRadius = 0.55f      // sustained-note glow radius, em
private const val SoftSpotLift = 0.10f        // spot centre above the glyph centre, em
private const val SoftSpotAlpha = 0.105f      // spot alpha, swinging +/- 0.025
private const val SoftSpotSwing = 0.025f      // spot alpha breathing amplitude

private fun drawSoftFocus(
    canvas: NativeCanvas, line: ModeLine, piece: ModePiece, lit: Float, leaving: Float,
    progress: Float, now: Long, still: Boolean, brush: LyricBrush,
) {
    val raster = piece.raster
    val em = raster.em
    val total = raster.glyphs.size.coerceAtLeast(1)
    val saved = canvas.save()
    canvas.translate(piece.x, piece.y)
    val breath = if (still) 1f else 1f + SoftBreath * sin(now * 0.00085).toFloat()
    canvas.scale(breath, breath, raster.bitmap.width / 2f, raster.bitmap.height / 2f)
    val soft = raster.soft
    if (soft != null) {
        brush.text.alpha = (SoftLayerAlpha * (1f - leaving).pow(0.85f) * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmap(soft, 0f, 0f, brush.text)
    }
    raster.glyphs.forEachIndexed { i, glyph ->
        val r = glyph.bounds
        val t = glyphExit(leaving, i, total)
        if (t >= 1f) return@forEachIndexed
        val start = glyph.start
        val end = glyph.end
        val sung = ((lit - start) / (end - start)).coerceIn(0f, 1f)
        val singing = sung > 0f && sung < 1f
        val enter = glyphEnter(progress, i, total)
        val focus = (when {
            sung >= 1f -> 1f
            singing -> easeOutCubic(sung)
            else -> 0f
        }) * enter
        val alpha = focus * SoftClearAlpha * (1f - t * SoftExitFade).coerceAtLeast(0f)
        if (alpha <= 0.01f) return@forEachIndexed
        val drift = -t * t * em * SoftExitRise
        brush.dst.set(r.left.toFloat(), r.top + drift, r.right.toFloat(), r.bottom + drift)
        val halo = raster.glow
        if (halo != null && !still) {
            val heat = haloHeat(sung, lit, end)
            if (heat > 0f) {
                val b = raster.blur * 2
                brush.src.set(r.left - b, r.top - b, r.right + b, r.bottom + b)
                brush.halo.set(brush.dst.left - b, brush.dst.top - b, brush.dst.right + b, brush.dst.bottom + b)
                brush.glowAlpha(alpha * heat * ModeHaloAlpha)
                canvas.drawBitmap(halo, brush.src, brush.halo, brush.glow)
            }
        }
        val glyphSaved = canvas.save()
        val micro = if (!still && singing) 1f + SoftSingScale * sin(sung * PI).toFloat() else 1f
        if (micro != 1f) canvas.scale(micro, micro, r.exactCenterX(), r.exactCenterY() + drift)
        brush.text.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmap(raster.bitmap, r, brush.dst, brush.text)
        if (!still && singing && line.longNote(start)) {
            val spotY = r.exactCenterY() + drift - em * SoftSpotLift
            brush.spot.alpha = ((SoftSpotAlpha + SoftSpotSwing * sin(now * 0.003 + i).toFloat()) * 255f).toInt().coerceIn(0, 255)
            brush.matrix.setScale(em * SoftSpotRadius, em * SoftSpotRadius)
            brush.matrix.postTranslate(r.exactCenterX(), spotY)
            brush.spotShader.setLocalMatrix(brush.matrix)
            canvas.drawCircle(r.exactCenterX(), spotY, em * SoftSpotRadius, brush.spot)
        }
        canvas.restoreToCount(glyphSaved)
    }
    canvas.restoreToCount(saved)
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
        // 首屏这几行同样不在组合里光栅化；换窗口时保留上一批，等新的建好再换，不会闪空。
        var rows by remember(cache) { mutableStateOf(emptyList<Pair<Int, LyricRaster>>()) }
        LaunchedEffect(cache, first, last) {
            rows = withContext(Dispatchers.Default) {
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
