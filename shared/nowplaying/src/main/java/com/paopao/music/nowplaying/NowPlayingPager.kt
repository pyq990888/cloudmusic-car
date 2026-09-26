package com.paopao.music.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.launch

/** Spatial order preserves the original cover -> left swipe -> lyrics gesture. */
object PlayerPages {
    const val IMMERSIVE = 0
    const val COVER = 1
    const val LYRICS = 2
    const val COUNT = 3
}

@Composable
internal fun NowPlayingPager(
    source: NowPlayingSource,
    accent: Color,
    onClose: () -> Unit,
    initialPage: Int,
    onImmersiveChanged: (Boolean) -> Unit,
) {
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, PlayerPages.COUNT - 1)) { PlayerPages.COUNT }
    val scope = rememberCoroutineScope()
    var overlayOpen by remember { mutableStateOf(false) }
    var immersiveControls by remember { mutableStateOf(true) }
    var immersiveForeground by remember { mutableStateOf(Color.White) }
    val returnToCover: () -> Unit = { scope.launch { pager.animateScrollToPage(PlayerPages.COVER) }; Unit }
    LaunchedEffect(pager.currentPage) {
        overlayOpen = false
        onImmersiveChanged(pager.currentPage == PlayerPages.IMMERSIVE)
    }
    BackHandler(enabled = pager.currentPage != PlayerPages.COVER && !overlayOpen, onBack = returnToCover)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !overlayOpen,
                beyondViewportPageCount = 0,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val active = pager.currentPage == page
                when (page) {
                    PlayerPages.IMMERSIVE -> ImmersiveLyricsScreen(
                        source, onClose = onClose, accentColor = accent, active = active,
                        onOverlayChanged = { if (active) overlayOpen = it },
                        onControlsChanged = { if (active) immersiveControls = it },
                        onForegroundChanged = { immersiveForeground = it },
                    )
                    else -> StandardNowPlayingScreen(
                        source, accent, onClose, active = active, singlePage = true,
                        lyricsOnly = page == PlayerPages.LYRICS,
                        onShowLyrics = { scope.launch { pager.animateScrollToPage(PlayerPages.LYRICS) } },
                        onOverlayChanged = { if (active) overlayOpen = it },
                    )
                }
            }
        }
        if (!overlayOpen && (pager.currentPage != PlayerPages.IMMERSIVE || immersiveControls)) {
            val tint = if (pager.currentPage == PlayerPages.IMMERSIVE) immersiveForeground else Color.White
            Row(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("沉浸歌词页", "封面页", "普通歌词页").forEachIndexed { index, label ->
                    Box(
                        Modifier.size(32.dp).semantics {
                            contentDescription = label
                            selected = pager.currentPage == index
                        }.clickable { scope.launch { pager.animateScrollToPage(index) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(if (pager.currentPage == index) 8.dp else 5.dp).clip(CircleShape)
                                .background(tint.copy(alpha = if (pager.currentPage == index) 0.95f else 0.32f)),
                        )
                    }
                }
            }
        }
    }
}
