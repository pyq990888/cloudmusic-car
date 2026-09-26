package com.paopao.music.nowplaying

internal data class ImmersiveVerse(
    val index: Int,
    val line: NowPlayingLyric?,
    val next: String?,
)

/** Preserve instrumental gaps; do not pull the next lyric forward into them. */
internal fun immersiveVerse(lines: List<NowPlayingLyric>, positionMs: Long): ImmersiveVerse {
    val index = lines.activeIndex(positionMs)
    val line = lines.getOrNull(index)?.takeIf { it.text.isNotBlank() || it.words.isNotEmpty() }
    var next: String? = null
    for (i in index + 1..lines.lastIndex) {
        if (lines[i].text.isNotBlank()) {
            next = lines[i].text
            break
        }
    }
    return ImmersiveVerse(index, line, next)
}

internal fun immersiveProgress(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0L) (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f

internal fun immersiveTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}
