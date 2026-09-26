package com.paopao.music.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImmersiveLyricsTest {
    private val lines = listOf(
        NowPlayingLyric(1000L, "First", null),
        NowPlayingLyric(3000L, "", null),
        NowPlayingLyric(6000L, "Second", "Translation"),
    )

    @Test
    fun introDoesNotSingTheFirstLineEarly() {
        val verse = immersiveVerse(lines, 0L)
        assertEquals(-1, verse.index)
        assertNull(verse.line)
        assertEquals("First", verse.next)
    }

    @Test
    fun blankLinePreservesInstrumentalBreak() {
        val verse = immersiveVerse(lines, 4000L)
        assertEquals(1, verse.index)
        assertNull(verse.line)
        assertEquals("Second", verse.next)
    }

    @Test
    fun seekingBackRecomputesActiveLineAndSkipsBlankPreview() {
        assertEquals("Second", immersiveVerse(lines, 7000L).line?.text)
        val verse = immersiveVerse(lines, 1500L)
        assertEquals("First", verse.line?.text)
        assertEquals("Second", verse.next)
    }

    @Test
    fun emptyLyricsAndFinalLineHaveNoNextPreview() {
        assertNull(immersiveVerse(emptyList(), 9000L).line)
        assertNull(immersiveVerse(lines, 9000L).next)
        assertEquals("Translation", immersiveVerse(lines, 9000L).line?.translation)
    }

    @Test
    fun progressIsBoundedAndHandlesUnknownDuration() {
        assertEquals(0f, immersiveProgress(1000, -1), 0f)
        assertEquals(0f, immersiveProgress(-1000, 10000), 0f)
        assertEquals(0.5f, immersiveProgress(5000, 10000), 0f)
        assertEquals(1f, immersiveProgress(Long.MAX_VALUE, 10000), 0f)
    }

    @Test
    fun timeClampsNegativePositionsAndSupportsLongTracks() {
        assertEquals("0:00", immersiveTime(-10))
        assertEquals("1:05", immersiveTime(65000))
        assertEquals("120:00", immersiveTime(7200000))
    }
}
