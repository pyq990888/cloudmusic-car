package com.paopao.music.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveEffectsTest {
    @Test
    fun automaticMotionUsesEveryVariantAndIsStableAcrossSeeks() {
        val sequence = (0..3).map { resolveLyricMotion(LyricMotion.AUTO, it) }
        assertEquals(4, sequence.toSet().size)
        assertFalse(sequence.contains(LyricMotion.AUTO))
        assertEquals(sequence[2], resolveLyricMotion(LyricMotion.AUTO, 6))
        assertEquals(LyricMotion.WAVE, resolveLyricMotion(LyricMotion.WAVE, 50))
    }

    @Test
    fun everyGlyphLandsExactlyAtItsMeasuredLocation() {
        for (motion in LyricMotion.entries.drop(1)) {
            for (index in 0..59) {
                assertEquals(GlyphPose(), lyricGlyphPose(motion, 1f, false, index, 60, index / 10))
            }
        }
    }

    @Test
    fun outgoingGlyphsDisappearBeforeTheNewLineSettles() {
        for (motion in LyricMotion.entries.drop(1)) {
            assertEquals(0f, lyricGlyphPose(motion, 0.5f, true, 0, 10, 0).alpha, 0f)
            assertEquals(1f, lyricGlyphPose(motion, 0f, true, 0, 10, 0).alpha, 0f)
        }
    }

    @Test
    fun staggerIsVisibleAndAllPosesStayFinite() {
        val early = lyricGlyphPose(LyricMotion.TURN, 0.25f, false, 0, 20, 0)
        val late = lyricGlyphPose(LyricMotion.TURN, 0.25f, false, 19, 20, 0)
        assertTrue(early.x < late.x)
        for (motion in LyricMotion.entries) {
            for (step in -1..11) {
                val pose = lyricGlyphPose(motion, step / 10f, false, 0, 0, 0)
                assertTrue(listOf(pose.x, pose.y, pose.scale, pose.alpha, pose.rotateX, pose.rotateY).all { it.isFinite() })
                assertTrue(pose.alpha in 0f..1f)
                assertTrue(pose.scale > 0f)
            }
        }
    }

    @Test
    fun rightSwipeFromCoverReachesTheThirdPageAndLeftSwipeKeepsLyrics() {
        assertEquals(PlayerPages.IMMERSIVE, PlayerPages.COVER - 1)
        assertEquals(PlayerPages.LYRICS, PlayerPages.COVER + 1)
        assertEquals(3, PlayerPages.COUNT)
    }

    @Test
    fun backgroundsAreDistinctAndSilverIsRemoved() {
        assertEquals(12, ImmersiveBackdrop.entries.size)
        assertEquals(12, ImmersiveBackdrop.entries.map { it.swatch }.toSet().size)
        assertFalse(ImmersiveBackdrop.entries.any { it.name.contains("SILVER") })
        assertFalse(ImmersiveBackdrop.entries.any { it.name == "TRAILS" || it.name == "NOMAND" })
        assertEquals(listOf("歌词粒子", "月蚀星环", "夜店赛博"), ImmersiveBackdrop.entries.filter { it in setOf(ImmersiveBackdrop.KINETIC, ImmersiveBackdrop.ECLIPSE, ImmersiveBackdrop.CYBER) }.map { it.label })
    }
}
