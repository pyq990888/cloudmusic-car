package com.paopao.music.nowplaying

internal data class LyricSection(val text: String, val start: Int, val end: Int)

/** Layout-only slices keep source offsets, punctuation and word timing unchanged. */
internal fun lyricSections(text: String, requestedCount: Int): List<LyricSection> {
    if (text.isEmpty()) return emptyList()
    val points = text.codePoints().toArray()
    val count = requestedCount.coerceIn(1, points.size)
    val boundaries = ArrayList<Int>()
    boundaries += 0
    var start = 0
    for (i in 1 until count) {
        var end = text.offsetByCodePoints(0, points.size * i / count)
        if (text.any { it == ' ' }) {
            val before = text.lastIndexOf(' ', end.coerceAtMost(text.lastIndex))
            val after = text.indexOf(' ', end)
            val candidate = listOf(before, after).filter { it > start && it < text.lastIndex }
                .minByOrNull { kotlin.math.abs(it - end) }
            if (candidate != null) end = candidate + 1
        }
        if (end > start && end < text.length) {
            boundaries += end
            start = end
        }
    }
    boundaries += text.length
    return boundaries.zipWithNext().map { (from, to) -> LyricSection(text.substring(from, to), from, to) }
}

internal fun modeRowCount(mode: FoliaLyricMode, text: String): Int {
    val length = text.codePointCount(0, text.length)
    return when (mode) {
        FoliaLyricMode.LUMINOUS, FoliaLyricMode.FUME -> 1
        FoliaLyricMode.MINDSCAPE -> when { length > 48 -> 4; length > 20 -> 3; length > 5 -> 2; else -> 1 }
        FoliaLyricMode.PARTITA -> when { length > 42 -> 4; length > 18 -> 3; length > 5 -> 2; else -> 1 }
    }
}
