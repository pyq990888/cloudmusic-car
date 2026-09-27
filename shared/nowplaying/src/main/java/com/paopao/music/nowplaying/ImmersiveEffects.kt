package com.paopao.music.nowplaying

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

internal enum class ImmersiveBackdrop(
    val label: String,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val swatch: Color,
) {
    INK("墨水生长", Color(0xFFF3F6F2), Color(0xFFB1BCB9), Color(0xFFB9DCCF), Color(0xFF51766B)),
    RIPPLE("水面涟漪", Color(0xFF152C29), Color(0xFF4E6962), Color(0xFF254F43), Color(0xFFCCDCD1)),
    AURORA("极光光幕", Color(0xFFFFF8F1), Color(0xFFC1CCC8), Color(0xFFF2C3A6), Color(0xFF86CCAD)),
    COMMON("通用", Color(0xFFF3F5FF), Color(0xFFB7BFCA), Color(0xFF88D8D0), Color(0xFF619FAD)),
    MONET("莫奈", Color(0xFFFCF6F0), Color(0xFFD4C7C6), Color(0xFFF1B7A7), Color(0xFFCEB4AB)),
    LATENT("隐现", Color(0xFFF8F3F1), Color(0xFFC7BCB9), Color(0xFFF7BAA2), Color(0xFFB98687)),
    SORA("空", Color(0xFFF3F6FF), Color(0xFFB4BED3), Color(0xFFDFBBD2), Color(0xFF7086A8)),
    FLUID("流体封面", Color(0xFFFBF7F4), Color(0xFFC9C2C0), Color(0xFFFFD6B8), Color(0xFFB07A8C)),
    KINETIC("歌词粒子", Color(0xFFFFF4E6), Color(0xFFCAB9AA), Color(0xFFEFBB76), Color(0xFF8BE0DC)),
    ECLIPSE("月蚀星环", Color(0xFFFFF2D5), Color(0xFFB8C4D2), Color(0xFFF7C98E), Color(0xFF638AA4)),
    CYBER("夜店赛博", Color(0xFFF7F7FF), Color(0xFFB6B6D2), Color(0xFFFF38D1), Color(0xFF49F6FF)),
    CUSTOM("自定义图片", Color.White, Color(0xFFD0D0D0), Color(0xFFCDE6DC), Color(0xFF9CACA6)),
}

internal enum class FoliaLyricMode(val label: String) {
    LUMINOUS("流光"),
    MINDSCAPE("心象"),
    PARTITA("云阶"),
    FUME("浮名"),
}

internal enum class LyricFont(val label: String, val fileName: String?, val fallback: String, val weight: Int) {
    SYSTEM("系统默认", null, "sans-serif", 400),
    SWEI("四季春加糖", "SweiSpringSugarSC.ttf", "serif", 500),
}

internal enum class LyricMotion(val label: String) {
    AUTO("自动轮换"),
    TURN("透视翻页"),
    DEPTH("纵深推近"),
    SCATTER("错位拼入"),
    WAVE("波浪起伏"),
}

internal fun resolveLyricMotion(preference: LyricMotion, index: Int): LyricMotion =
    if (preference != LyricMotion.AUTO) preference else LyricMotion.entries[1 + index.mod(4)]

internal data class GlyphPose(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotateX: Float = 0f,
    val rotateY: Float = 0f,
    val rotateZ: Float = 0f,
    val alpha: Float = 1f,
)

/** Distances are in em, so changing type size cannot turn a small gesture into a screen-wide jump. */
internal fun lyricGlyphPose(
    motion: LyricMotion,
    progress: Float,
    outgoing: Boolean,
    index: Int,
    count: Int,
    row: Int,
): GlyphPose {
    val p = progress.coerceIn(0f, 1f)
    if (outgoing) {
        val t = (p / 0.48f).coerceIn(0f, 1f)
        val alpha = (1f - t).pow(2)
        return when (motion) {
            LyricMotion.TURN -> GlyphPose(x = -5.5f * t, scale = 1f - 0.35f * t, rotateY = -72f * t, alpha = alpha)
            LyricMotion.DEPTH -> GlyphPose(y = -2.5f * t, scale = 1f + 0.65f * t, alpha = alpha)
            LyricMotion.SCATTER -> GlyphPose(x = (if (index % 2 == 0) -1 else 1) * 3.5f * t, y = -1.4f * t, rotateZ = 16f * t, alpha = alpha)
            else -> GlyphPose(y = -2.8f * t, rotateX = 65f * t, alpha = alpha)
        }
    }
    if (p >= 1f) return GlyphPose()
    val stagger = index.toFloat() / count.coerceAtLeast(1) * 0.28f
    val t = ((p - stagger) / (1f - stagger)).coerceIn(0f, 1f)
    val remaining = (1f - t).pow(3)
    val alpha = (t * 4.5f).coerceIn(0f, 1f)
    val side = if (index % 2 == 0) -1f else 1f
    return when (motion) {
        LyricMotion.TURN -> GlyphPose(x = 5.5f * remaining, scale = 1f - 0.25f * remaining, rotateY = 76f * remaining, alpha = alpha)
        LyricMotion.DEPTH -> GlyphPose(y = (3.5f + row * 0.4f) * remaining, scale = 1f - 0.68f * remaining, rotateX = -34f * remaining, alpha = alpha)
        LyricMotion.SCATTER -> GlyphPose(x = side * 3.8f * remaining, y = side * 1.8f * remaining, scale = 1f - 0.45f * remaining, rotateZ = side * 28f * remaining, alpha = alpha)
        else -> GlyphPose(y = (2.3f + index % 3 * 0.45f) * remaining, rotateX = -78f * remaining, scale = 1f + 0.22f * remaining, alpha = alpha)
    }
}
