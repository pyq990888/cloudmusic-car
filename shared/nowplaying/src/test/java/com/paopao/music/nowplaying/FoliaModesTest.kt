package com.paopao.music.nowplaying

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FoliaModesTest {
    @Test fun exactFourRequestedModesAreExposed() {
        assertEquals(listOf("流光", "心象", "云阶", "浮名"), FoliaLyricMode.entries.map { it.label })
    }

    @Test fun slicesPreserveEveryCharacterAndOriginalOffsets() {
        for (text in listOf("风越过山，海漫过岸。", "Every road becomes a melody", "A😀B🎵C", "line one\nline two")) {
            val sections = lyricSections(text, 3)
            assertEquals(text, sections.joinToString("") { it.text })
            sections.forEach { assertEquals(text.substring(it.start, it.end), it.text) }
            assertTrue(sections.zipWithNext().all { (a, b) -> a.end == b.start })
            assertTrue(sections.all { it.text.isNotEmpty() && !Character.isLowSurrogate(it.text.first()) })
        }
    }

    @Test fun modeLayoutsAreDifferentAndShortLinesAreBounded() {
        val line = "让每一首歌在车窗之外的夜色里慢慢流动"
        assertEquals(1, modeRowCount(FoliaLyricMode.LUMINOUS, line))
        assertTrue(modeRowCount(FoliaLyricMode.MINDSCAPE, line) > 1)
        assertEquals(2, modeRowCount(FoliaLyricMode.MINDSCAPE, "让声音 穿过人海"))
        assertTrue(modeRowCount(FoliaLyricMode.PARTITA, line) > 1)
        assertEquals(1, lyricSections("风", 4).size)
        assertTrue(lyricSections("", 4).isEmpty())
    }

    @Test fun invalidStoredPreferencesFallBack() {
        assertEquals(FoliaLyricMode.LUMINOUS, enumValue("deleted", FoliaLyricMode.LUMINOUS))
        assertEquals(LyricFont.SYSTEM, enumValue("INTER", LyricFont.SYSTEM))
        assertEquals(LyricFont.SYSTEM, enumValue("NOTO_SERIF", LyricFont.SYSTEM))
        assertEquals(LyricFont.SYSTEM, enumValue("IBM_MONO", LyricFont.SYSTEM))
    }

    @Test fun supportedImageHeadersAreValidatedIndependentlyOfFileName() {
        assertTrue(isSupportedImageHeader(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)))
        assertTrue(isSupportedImageHeader("GIF89a012345".toByteArray()))
        assertTrue(isSupportedImageHeader("RIFF1234WEBP".toByteArray()))
        assertFalse(isSupportedImageHeader("filename.jpg".toByteArray()))
        assertFalse(isSupportedImageHeader("RIFF1234WAVE".toByteArray()))
        assertFalse(isSupportedImageHeader(byteArrayOf()))
    }

    @Test fun copyIsBoundedAndPreservesOriginalImageBytes() {
        val bytes = ByteArray(20000) { (it % 127).toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyImageBounded(ByteArrayInputStream(bytes), out, bytes.size.toLong()))
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test fun mp4HeadersAreRecognisedAndKeptApartFromImages() {
        fun ftyp(brand: String) = "0000ftyp${brand}0000".toByteArray(Charsets.ISO_8859_1)
        assertTrue(isSupportedVideoHeader(ftyp("isom")))
        assertTrue(isSupportedVideoHeader(ftyp("mp42")))
        assertTrue(isSupportedVideoHeader(ftyp("qt  ")))
        assertFalse(isSupportedVideoHeader(ftyp("XXXX")))
        assertFalse(isSupportedVideoHeader("0000ftyp".toByteArray()))
        assertFalse(isSupportedVideoHeader(byteArrayOf()))
        assertFalse(isSupportedVideoHeader(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)))
        assertFalse(isSupportedImageHeader(ftyp("isom")))
    }

    @Test fun videoCopyUsesItsOwnLimitAndLabel() {
        val bytes = ByteArray(64) { 7 }
        val out = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyImageBounded(ByteArrayInputStream(bytes), out, MAX_BACKGROUND_VIDEO_BYTES, "视频"))
        assertArrayEquals(bytes, out.toByteArray())
        assertTrue(MAX_BACKGROUND_VIDEO_BYTES > MAX_BACKGROUND_BYTES)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedImageIsRejected() {
        copyImageBounded(ByteArrayInputStream(ByteArray(101)), ByteArrayOutputStream(), 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyImageIsRejected() {
        copyImageBounded(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
    }
}
