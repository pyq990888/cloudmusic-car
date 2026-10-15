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
import androidx.compose.ui.platform.LocalContext
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
    // 沉浸页要用的偏好和字体趁播放器刚打开就读出来：等用户滑过去时已经是热的，
    // 不用再等 SharedPreferences 解析和字体文件构建。
    val context = LocalContext.current
    val immersiveFont = rememberImmersivePreferences().second.font
    LaunchedEffect(context, immersiveFont) { preloadLyricTypeface(context, immersiveFont) }
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
                // 相邻页提前组合：沉浸页的冷启动（歌词光栅化、GL 表面与着色器）挪到播放器打开时，
                // 不再压在手势的第一帧上。
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val active = pager.currentPage == page
                // 拖动与惯性期间不算停稳；沉浸页的逐帧重活（GL 背景、歌词填色）都等翻页结束再开，
                // 免得和翻页动画抢帧。
                val settled = !pager.isScrollInProgress
                when (page) {
                    PlayerPages.IMMERSIVE -> ImmersiveLyricsScreen(
                        source, onClose = onClose, accentColor = accent, active = active, settled = settled,
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
