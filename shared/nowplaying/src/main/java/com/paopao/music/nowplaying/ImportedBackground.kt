package com.paopao.music.nowplaying

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal const val MAX_BACKGROUND_BYTES = 32L * 1024 * 1024
internal const val MAX_BACKGROUND_VIDEO_BYTES = 128L * 1024 * 1024

private const val TAG = "ImmersiveBg"

/** 起播后等第一帧上屏的时间，超时认为这一轮没出画。 */
private const val FRAME_GRACE_MS = 2000L

/** prepare 慢的车机另给一段宽限，别把「慢」当成「坏」。 */
private const val PREPARE_GRACE_MS = 8000L

/** 自动重建播放器的次数上限，等价于用户手动切换几次背景。 */
private const val MAX_RESTARTS = 3

private val MP4_BRANDS = setOf(
    "isom", "iso2", "iso4", "iso5", "iso6", "avc1", "mmp4", "mp41", "mp42", "mp71",
    "M4V ", "M4VH", "M4VP", "MSNV", "dash", "3gp4", "3gp5", "3g2a", "qt  ",
)

internal fun isSupportedImageHeader(header: ByteArray): Boolean {
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    fun starts(prefix: ByteArray) = header.size >= prefix.size && prefix.indices.all { header[it] == prefix[it] }
    return starts(png) ||
        starts(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) ||
        starts("GIF87a".toByteArray()) || starts("GIF89a".toByteArray()) ||
        (header.size >= 12 && starts("RIFF".toByteArray()) && header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()))
}

/** MP4/QuickTime 容器：第 5-8 字节为 "ftyp"，紧跟 4 字节 brand。 */
internal fun isSupportedVideoHeader(header: ByteArray): Boolean {
    if (header.size < 12) return false
    if (!header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) return false
    return String(header, 8, 4, Charsets.ISO_8859_1) in MP4_BRANDS
}

internal fun copyImageBounded(
    input: InputStream,
    output: OutputStream,
    limit: Long = MAX_BACKGROUND_BYTES,
    label: String = "图片",
): Long {
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "$label 不能超过 ${limit / (1024 * 1024)} MB" }
        output.write(buffer, 0, count)
    }
    require(total > 0) { "$label 文件为空" }
    return total
}

internal fun backgroundFile(context: Context, name: String?): File? {
    if (name == null || !name.matches(Regex("background-[a-f0-9-]+\\.img"))) return null
    val folder = File(context.filesDir, "kugou_immersive/backgrounds")
    return File(folder, name).takeIf { it.isFile && it.canonicalFile.parentFile == folder.canonicalFile }
}

/** 读取文件头，用于判断导入的背景是图片还是视频。 */
internal fun backgroundHeader(file: File): ByteArray = file.inputStream().use { stream ->
    val buffer = ByteArray(12)
    buffer.copyOf(stream.read(buffer).coerceAtLeast(0))
}

internal suspend fun importBackground(context: Context, uri: Uri, store: ImmersivePreferenceStore) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val label = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0)?.replace('\n', ' ')?.take(100) else null
    } ?: "自定义背景"
    val folder = File(context.filesDir, "kugou_immersive/backgrounds").apply { check(mkdirs() || isDirectory) }
    val pending = File(folder, "background-${UUID.randomUUID()}.img")
    var committed = false
    try {
        val input = resolver.openInputStream(uri) ?: error("无法读取选择的图片")
        val header = input.use { stream ->
            val bytes = ByteArray(12)
            val count = stream.read(bytes)
            bytes.copyOf(count.coerceAtLeast(0))
        }
        val video = isSupportedVideoHeader(header)
        require(video || isSupportedImageHeader(header)) { "仅支持 JPEG、PNG、GIF、WebP 图片和 MP4 视频" }
        val kind = if (video) "视频" else "图片"
        val limit = if (video) MAX_BACKGROUND_VIDEO_BYTES else MAX_BACKGROUND_BYTES
        resolver.openInputStream(uri)?.use { stream ->
            pending.outputStream().use { copyImageBounded(stream, it, limit, kind) }
        } ?: error("无法读取选择的文件")
        currentCoroutineContext().ensureActive()
        if (video) validateBackgroundVideo(pending) else (decodeBackground(pending) as? AnimatedImageDrawable)?.stop()
        currentCoroutineContext().ensureActive()
        val previous = backgroundFile(context, store.read().imageName)
        check(
            store.preferences.edit()
                .putString("image_file", pending.name)
                .putString("image_label", label)
                .putBoolean("image_video", video)
                .putString("backdrop", ImmersiveBackdrop.CUSTOM.name)
                .commit(),
        ) { "无法保存背景" }
        committed = true
        previous?.delete()
    } finally {
        if (!committed) pending.delete()
    }
}

/**
 * 只校验容器里有视频轨道，不限制时长和分辨率。
 *
 * 这里刻意不用 MediaPlayer.prepare()：车机大多只有一路硬解，导入时先占一个解码器，
 * 紧接着页面又开一个，第二个偶发拿不到解码器就是一块黑屏（切一次背景才恢复）。
 * MediaExtractor 只读容器和轨道信息，不申请解码器。
 */
internal fun validateBackgroundVideo(file: File) {
    val extractor = MediaExtractor()
    try {
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (error: Exception) {
            throw IllegalArgumentException("视频无法解析，请换一个 MP4 文件", error)
        }
        val video = (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
            runCatching { extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) }
                .getOrNull()
                ?.takeIf { it.startsWith("video/") }
        }
        requireNotNull(video) { "视频没有可播放的画面轨道" }
    } finally {
        runCatching { extractor.release() }
    }
}

internal suspend fun clearBackground(context: Context, store: ImmersivePreferenceStore) = withContext(Dispatchers.IO) {
    val previous = backgroundFile(context, store.read().imageName)
    val editor = store.preferences.edit().remove("image_file").remove("image_label").remove("image_video")
    if (store.read().backdrop == ImmersiveBackdrop.CUSTOM) editor.putString("backdrop", ImmersiveBackdrop.INK.name)
    check(editor.commit()) { "无法移除背景" }
    previous?.delete()
}

internal fun decodeBackground(file: File): Drawable =
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        require(w > 0 && h > 0 && w.toLong() * h <= 80_000_000L) { "图片分辨率过大" }
        val ratio = minOf(1f, 1600f / maxOf(w, h))
        decoder.setTargetSize((w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

@Composable
internal fun ImportedImageBackground(
    name: String?,
    running: Boolean,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var drawable by remember(name) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(name) {
        try {
            drawable = withContext(Dispatchers.IO) {
                val file = backgroundFile(context, name) ?: error("背景图片已不存在，请重新选择")
                decodeBackground(file)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onError("背景图片无法加载，请重新选择")
        }
    }
    DisposableEffect(drawable, running) {
        val animation = drawable as? AnimatedImageDrawable
        animation?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        if (running) animation?.start() else animation?.stop()
        onDispose { animation?.stop() }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { if (it.drawable !== drawable) it.setImageDrawable(drawable) },
        onRelease = { (it.drawable as? AnimatedImageDrawable)?.stop(); it.setImageDrawable(null) },
        modifier = modifier,
    )
}

/**
 * 自定义视频背景：TextureView + MediaPlayer 循环播放，音量为 0。
 *
 * 用 TextureView 而不是 SurfaceView：宿主把功能包嵌进 ComposeView 时，
 * SurfaceView 是独立图层，和歌词层叠加会出现层级与黑闪问题。
 *
 * 播放器由纹理回调创建，而不是由 Compose 状态驱动：MediaPlayer 要在 prepare 之前
 * 就拿到 Surface，之后再 setSurface 补绑不会重新显示画面，这正是之前间歇黑屏的原因。
 */
@Composable
internal fun ImportedVideoBackground(
    name: String?,
    running: Boolean,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // AndroidView 会复用同一个 TextureView；背景文件变化时只切换播放器，不能换宿主对象。
    val host = remember { VideoBackgroundHost() }
    LaunchedEffect(host, name) {
        val file = withContext(Dispatchers.IO) { backgroundFile(context, name) }
        if (file == null) {
            onError("背景视频已不存在，请重新选择")
            return@LaunchedEffect
        }
        host.configure(file, onError)
    }
    AndroidView(
        factory = { host.createView(it) },
        update = { host.setRunning(running) },
        onRelease = { host.detachView() },
        modifier = modifier,
    )
    // 换背景或离开页面时释放解码器，否则车机上会一直占着一个视频解码实例。
    DisposableEffect(host) { onDispose { host.release() } }
}

/**
 * 一个 TextureView 加它的 MediaPlayer。
 *
 * 播放器生命周期完全跟着纹理走（系统 VideoView 也是这个顺序）：
 * 纹理可用时建播放器，先 setDataSource / setSurface 再 prepareAsync；纹理销毁时释放播放器。
 * 旋转、切后台引起的纹理重建因此会自动重新走一遍正确顺序，不需要 Compose 侧再协商时序。
 *
 * 车机上偶发「播放器已经起播、却一帧都没上屏」的黑屏，手动切一次背景才能恢复。
 * 这里加了首帧看门狗：起播后 [FRAME_GRACE_MS] 内纹理一帧都没收到，就自动重建播放器，
 * 最多 [MAX_RESTARTS] 次 —— 等价于用户手动切背景，但不用用户来切。
 */
private class VideoBackgroundHost {
    private val handler = Handler(Looper.getMainLooper())
    private val frameCheck = Runnable { checkFirstFrame() }
    private var view: TextureView? = null
    private var texture: SurfaceTexture? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var file: File? = null
    private var onError: ((String) -> Unit)? = null
    private var running = false
    private var prepared = false
    private var rendered = false
    private var openedAt = 0L
    private var restarts = 0
    private var videoWidth = 0
    private var videoHeight = 0

    /** 旋转、分屏、首次布局都会触发，用来重算 CENTER_CROP。 */
    private val layoutListener = View.OnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
        (changed as? TextureView)?.let(::applyCenterCrop)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            this@VideoBackgroundHost.texture = texture
            open(texture)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            applyCenterCrop(view)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (this@VideoBackgroundHost.texture === texture) this@VideoBackgroundHost.texture = null
            closePlayer()
            return true
        }

        /** 纹理收到帧＝解码器真的在出画，这是不依赖厂商 info 回调的首帧信号。 */
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            markRendered()
        }
    }

    fun createView(context: Context): TextureView {
        detachView()
        return TextureView(context).apply {
            surfaceTextureListener = textureListener
            addOnLayoutChangeListener(layoutListener)
            view = this
        }
    }

    fun configure(source: File, reporter: (String) -> Unit) {
        onError = reporter
        if (file == source) return
        closePlayer()
        file = source
        restarts = 0
        val current = view ?: return
        if (current.isAvailable) open(current.surfaceTexture)
    }

    /** 每次重组都会调用，只做状态收敛，不重建播放器。 */
    fun setRunning(value: Boolean) {
        running = value
        applyPlayback()
    }

    fun detachView() {
        view?.let { attached ->
            attached.removeOnLayoutChangeListener(layoutListener)
            attached.surfaceTextureListener = null
        }
        view = null
        texture = null
        closePlayer()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        detachView()
        file = null
        onError = null
    }

    private fun open(target: SurfaceTexture?) {
        val source = file ?: return
        if (target == null || player != null) return
        prepared = false
        rendered = false
        videoWidth = 0
        videoHeight = 0
        openedAt = SystemClock.elapsedRealtime()
        val created = MediaPlayer()
        player = created
        try {
            created.setDataSource(source.absolutePath)
            created.setVolume(0f, 0f)
            created.isLooping = true
            // setSurface 必须早于 prepareAsync，否则画面不会投到这块纹理上。
            surface = Surface(target).also(created::setSurface)
            created.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                applyCenterCrop(view)
            }
            created.setOnPreparedListener {
                prepared = true
                applyPlayback()
            }
            created.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    Log.d(TAG, "背景视频首帧已上屏")
                    markRendered()
                }
                false
            }
            created.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "背景视频播放出错 what=$what extra=$extra")
                fail()
                true
            }
            created.prepareAsync()
            scheduleFrameCheck()
        } catch (error: Exception) {
            Log.w(TAG, "背景视频打不开", error)
            fail()
        }
    }

    private fun scheduleFrameCheck() {
        handler.removeCallbacks(frameCheck)
        if (rendered || player == null) return
        handler.postDelayed(frameCheck, FRAME_GRACE_MS)
    }

    /**
     * 起播后迟迟没有画面：多数是这一轮解码器没能出画，重建播放器就能恢复。
     * 重建前先让解码器从头再渲染一次，成本更低。
     */
    private fun checkFirstFrame() {
        val media = player ?: return
        if (rendered) return
        if (!prepared) {
            if (SystemClock.elapsedRealtime() - openedAt < PREPARE_GRACE_MS) {
                scheduleFrameCheck()
                return
            }
            Log.w(TAG, "背景视频 ${PREPARE_GRACE_MS}ms 仍未 prepare 完")
        } else {
            Log.w(TAG, "背景视频起播 ${FRAME_GRACE_MS}ms 内没有画面")
        }
        if (restarts >= MAX_RESTARTS) {
            Log.w(TAG, "背景视频自恢复 $MAX_RESTARTS 次仍无画面")
            fail()
            return
        }
        restarts++
        if (restarts == 1 && prepared) {
            runCatching { media.seekTo(0) }
            scheduleFrameCheck()
        } else {
            Log.w(TAG, "重建背景视频播放器（第 $restarts 次）")
            val target = texture
            closePlayer()
            open(target)
        }
    }

    /** 首帧信号只认一次：个别车机收到帧后不重绘，顺手刷一帧并重算一次缩放。 */
    private fun markRendered() {
        if (rendered) return
        rendered = true
        restarts = 0
        handler.removeCallbacks(frameCheck)
        applyCenterCrop(view)
        view?.invalidate()
        if (!running) runCatching { player?.pause() }
    }

    private fun fail() {
        closePlayer()
        onError?.invoke("背景视频无法播放，请重新选择")
    }

    private fun applyPlayback() {
        val media = player ?: return
        if (!prepared) {
            if (running) scheduleFrameCheck()
            return
        }
        runCatching { if (running) media.start() else if (media.isPlaying) media.pause() }
        if (running || !rendered) scheduleFrameCheck() else handler.removeCallbacks(frameCheck)
    }

    /**
     * 用 setTransform 而不是 scaleX/scaleY：后者是 View 属性，旋转后会残留旧比例，
     * 而且只在绑定时算过一次，横屏首次测量 view 尺寸还是 0，缩放因此不完整。
     */
    private fun applyCenterCrop(target: TextureView?) {
        val media = player ?: return
        if (target == null) return
        val width = videoWidth.takeIf { it > 0 } ?: media.videoWidth
        val height = videoHeight.takeIf { it > 0 } ?: media.videoHeight
        val viewWidth = target.width
        val viewHeight = target.height
        if (width <= 0 || height <= 0 || viewWidth <= 0 || viewHeight <= 0) return
        val scale = maxOf(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
        if (!scale.isFinite() || scale <= 0f) return
        target.setTransform(
            Matrix().apply {
                setScale(width * scale / viewWidth, height * scale / viewHeight, viewWidth / 2f, viewHeight / 2f)
            },
        )
    }

    private fun closePlayer() {
        handler.removeCallbacks(frameCheck)
        prepared = false
        rendered = false
        videoWidth = 0
        videoHeight = 0
        player?.let { media ->
            runCatching { media.setOnErrorListener(null) }
            runCatching { media.setOnInfoListener(null) }
            runCatching { media.setSurface(null) }
            runCatching { media.reset() }
            runCatching { media.release() }
        }
        player = null
        runCatching { surface?.release() }
        surface = null
    }
}
