package com.kugoumusic.car.preview

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.ImageLoader
import com.kugoumusic.car.data.MusicCache
import com.kugoumusic.car.ui.theme.KuGouMusicTheme
import com.paopao.music.nowplaying.PlayerPages
import com.paopao.music.nowplaying.FoliaLyricMode
import com.paopao.music.nowplaying.ImmersiveBackdrop
import com.paopao.music.nowplaying.ImmersivePreferenceStore
import com.paopao.music.nowplaying.LyricFont
import com.paopao.music.nowplaying.LyricWord
import com.paopao.music.nowplaying.NowPlayingLyric
import com.paopao.music.nowplaying.NowPlayingNeighbors
import com.paopao.music.nowplaying.NowPlayingQueueItem
import com.paopao.music.nowplaying.NowPlayingScreen
import com.paopao.music.nowplaying.NowPlayingSource
import com.paopao.music.nowplaying.NowPlayingTrack
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow

/** Offline visual fixture; never included in release APKs or plugin packages. */
class ImmersivePreviewActivity : ComponentActivity() {
    private val source = PreviewSource()
    private var resumePlayback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        source.lineIntervalMs = intent.getLongExtra("line_interval", 10000L).coerceIn(2400L, 10000L)
        source.jumpTo(intent.getIntExtra("track", 0).coerceIn(0, 2))
        source.seekTo(intent.getLongExtra("position", 0L))
        if (intent.getBooleanExtra("paused", false)) source.togglePlay()
        // Debug-only: pin a mode/backdrop/font so a screenshot run is deterministic.
        val store = ImmersivePreferenceStore(this)
        intent.getStringExtra("mode")?.let { name -> enumValues<FoliaLyricMode>().firstOrNull { it.name.equals(name, true) }?.let(store::mode) }
        intent.getStringExtra("backdrop")?.let { name -> enumValues<ImmersiveBackdrop>().firstOrNull { it.name.equals(name, true) }?.let(store::backdrop) }
        intent.getStringExtra("font")?.let { name -> enumValues<LyricFont>().firstOrNull { it.name.equals(name, true) }?.let(store::font) }
        if (intent.hasExtra("reduced")) store.reduced(intent.getBooleanExtra("reduced", false))
        setContent {
            KuGouMusicTheme {
                NowPlayingScreen(
                    source, Color(0xFFB9DCCF), onClose = ::finish, immersiveEnabled = true,
                    initialPage = if (intent.getBooleanExtra("standard", false)) PlayerPages.COVER else PlayerPages.IMMERSIVE,
                )
            }
        }
    }

    override fun onStop() {
        resumePlayback = source.isPlaying.value
        if (resumePlayback) source.togglePlay()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (resumePlayback) source.togglePlay()
        resumePlayback = false
    }
}

private class PreviewSource : NowPlayingSource {
    var lineIntervalMs = 10000L
    private val tracks = listOf(
        NowPlayingTrack(1, "让此刻慢下来", "沉浸歌词 · 原创演示", "INK / 01", null, 90000L),
        NowPlayingTrack(2, "Somewhere in the quiet", "Immersive lyrics · original demo", "RIPPLE / 02", null, 90000L),
        NowPlayingTrack(3, "无词之境", "纯音乐演示", "STILL / 03", null, 90000L),
    )
    override val current = MutableStateFlow<NowPlayingTrack?>(tracks[0])
    override val isPlaying = MutableStateFlow(true)
    override val queue = MutableStateFlow(tracks.mapIndexed { i, t -> NowPlayingQueueItem(i, t) })
    override val isFm = MutableStateFlow(false)
    override val sourceName = MutableStateFlow<String?>("离线演示")
    override val playMode = MutableStateFlow(0)
    override val neighbors = MutableStateFlow(NowPlayingNeighbors(2, 1))
    override val lyrics = MutableStateFlow(demoLyrics(0))
    override val likedIds = MutableStateFlow<Set<Long>>(emptySet())
    private var anchor = SystemClock.elapsedRealtime()
    private var position = 0L

    override fun positionMs(): Long {
        val elapsed = position + if (isPlaying.value) SystemClock.elapsedRealtime() - anchor else 0L
        return if (isPlaying.value) elapsed.mod(durationMs()) else elapsed.coerceIn(0L, durationMs())
    }
    override fun durationMs() = lineIntervalMs * 9L
    override fun togglePlay() {
        position = positionMs()
        anchor = SystemClock.elapsedRealtime()
        isPlaying.value = !isPlaying.value
    }
    override fun seekTo(ms: Long) { position = ms.coerceIn(0L, durationMs()); anchor = SystemClock.elapsedRealtime() }
    override fun next() = jumpTo((tracks.indexOfFirst { it.id == current.value?.id } + 1).mod(tracks.size))
    override fun previous() = jumpTo((tracks.indexOfFirst { it.id == current.value?.id } - 1).mod(tracks.size))
    override fun jumpTo(index: Int) {
        current.value = tracks[index].copy(durationMs = durationMs())
        lyrics.value = demoLyrics(index, lineIntervalMs)
        neighbors.value = NowPlayingNeighbors((index - 1).mod(tracks.size), (index + 1).mod(tracks.size))
        seekTo(0)
    }
    override fun cyclePlayMode() { playMode.value = (playMode.value + 1) % 3 }
    override fun fmTrash() = next()
    override fun toggleLike(trackId: Long) {
        likedIds.value = likedIds.value.let { if (trackId in it) it - trackId else it + trackId }
    }
    override fun artworkUrl(url: String?, px: Int) = url
    override fun imageLoader(context: Context): ImageLoader = MusicCache.imageLoader(context)
}

private fun demoLyrics(track: Int, intervalMs: Long = 10000L): List<NowPlayingLyric> {
    if (track == 2) return emptyList()
    val sentences = if (track == 0) listOf(
        "让此刻慢下来",
        "让声音 穿过人海",
        "窗外的光 落进心里",
        "所有远方 都在此刻",
        "风越过山 海漫过岸",
        "把世界 调成静音",
        "留一首歌 陪着自己",
        "在每一个平凡的夜晚听见心里那片安静而辽阔的海",
    ) else listOf(
        "Let the quiet\ncarry us home",
        "A little light\nbeyond the window",
        "Every road becomes\na melody",
        "We let the world\nslow down",
        "Somewhere in the quiet\nwe begin again",
        "Nothing to chase\nnowhere to hide",
        "Let this moment\nstay a little longer",
        "And all the distant lights remind us there is still a place to call our own",
    )
    return sentences.mapIndexed { index, text ->
        val start = index * intervalMs
        val tokens = if (track == 0) text.map(Char::toString) else Regex("\\S+\\s*").findAll(text).map { it.value }.toList()
        val step = intervalMs * 74L / 100L / tokens.size
        NowPlayingLyric(
            timeMs = start,
            text = text,
            translation = if (track == 1) listOf("让安静带我们回家", "窗外，微光正亮", "每条路都有自己的旋律", "让世界慢下来",
                "在寂静中重新出发", "不必追赶，也不必躲藏", "让这一刻再停留片刻", "遥远的灯火，也有归处")[index] else null,
            words = tokens.mapIndexed { i, token -> LyricWord(token, start + i * step, start + (i + 1) * step) },
        )
    }
}
