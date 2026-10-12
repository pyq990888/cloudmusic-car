package com.paopao.music.nowplaying

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val InkWhite = Color(0xFFF3F6F2)
private val InkAccent = Color(0xFFB9DCCF)

@Composable
fun ImmersiveLyricsScreen(
    source: NowPlayingSource,
    onClose: () -> Unit,
    accentColor: Color,
    active: Boolean = true,
    onOverlayChanged: (Boolean) -> Unit = {},
    onControlsChanged: (Boolean) -> Unit = {},
    onForegroundChanged: (Color) -> Unit = {},
) {
    val track by source.current.collectAsStateWithLifecycle()
    val playing by source.isPlaying.collectAsStateWithLifecycle()
    val lines by source.lyrics.collectAsStateWithLifecycle()
    val liked by source.likedIds.collectAsStateWithLifecycle()
    val fm by source.isFm.collectAsStateWithLifecycle()
    val neighbors by source.neighbors.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lifecycleForeground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val foreground = active && lifecycleForeground
    val clock = rememberPlaybackClock(source, active = foreground)
    val verse by remember(lines, clock) {
        derivedStateOf { immersiveVerse(lines, clock.position.longValue + LyricLeadMs) }
    }
    val context = LocalContext.current
    val (store, settings) = rememberImmersivePreferences()
    val reportError: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    val typeface = rememberLyricTypeface(settings.font, reportError)
    val backdrop = settings.backdrop
    val artwork = rememberBackdropArtwork(source, track?.coverUrl, settings.imageName, backdrop)
    val textColor = backdrop.foreground
    val mutedColor = backdrop.muted
    val accent = backdrop.accent
    val reducedMotion = settings.reducedMotion
    val backdropEnergy = when {
        !playing -> 0.15f
        verse.line == null -> 0.4f
        else -> 1f
    }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val revealControls: () -> Unit = { controlsVisible = true; interaction++ }
    LaunchedEffect(active, showQueue, settingsOpen) {
        if (active) onOverlayChanged(showQueue || settingsOpen)
    }
    LaunchedEffect(active, controlsVisible) { if (active) onControlsChanged(controlsVisible) }
    LaunchedEffect(backdrop) { onForegroundChanged(textColor) }
    LaunchedEffect(interaction, playing, foreground, settingsOpen, showQueue) {
        if (!playing || !foreground || settingsOpen || showQueue) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(4500L)
        controlsVisible = false
    }
    BackHandler(enabled = active && !settingsOpen) {
        when {
            showQueue -> showQueue = false
            else -> onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (backdrop == ImmersiveBackdrop.RIPPLE) backdrop.swatch else Color(0xFF060A09))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    revealControls()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        if (backdrop == ImmersiveBackdrop.CUSTOM && settings.imageName != null) {
            val backgroundError: (String) -> Unit = { reportError(it); store.backdrop(ImmersiveBackdrop.INK) }
            if (settings.imageVideo) {
                ImportedVideoBackground(
                    settings.imageName, running = playing && foreground && !reducedMotion,
                    onError = backgroundError, modifier = Modifier.fillMaxSize(),
                )
            } else {
                ImportedImageBackground(
                    settings.imageName, running = playing && foreground && !reducedMotion,
                    onError = backgroundError, modifier = Modifier.fillMaxSize(),
                )
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.imageDim)))
        } else {
            InkFlowBackground(
                running = playing && !reducedMotion, foreground = foreground,
                backdrop = backdrop, artwork = artwork,
                beat = verse.index, energy = backdropEnergy, modifier = Modifier.fillMaxSize(),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val portrait = maxHeight > maxWidth
            val compact = maxHeight < 500.dp
            val side = if (portrait || maxWidth < 700.dp) 32.dp else 72.dp
            val metadataWidth = maxOf(80.dp, maxWidth / 2 - 152.dp)
            val topSpace = if (compact) 68.dp else 108.dp
            val bottomSpace = if (compact) 116.dp else 164.dp
            val controlsStart = maxOf(
                maxHeight * 0.114f + 8.dp,
                if (compact) 64.dp else 104.dp,
            )
            val settingsTextSize = if (portrait) {
                14.sp
            } else {
                (maxHeight.value / 40f).coerceIn(16f, 20f).sp
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = side)
                    .padding(top = topSpace, bottom = bottomSpace),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                FoliaLyricStage(
                    trackId = track?.id, index = verse.index, lines = lines,
                    fallback = track?.title ?: "此刻，听见音乐",
                    clock = clock, playing = playing, foreground = foreground,
                    reducedMotion = reducedMotion, mode = settings.mode, typeface = typeface,
                    color = textColor, accent = accent, modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                val subtitle = verse.line?.translation?.takeIf { it.isNotBlank() }
                    ?: if (verse.line == null) {
                        when {
                            track == null -> "暂无正在播放的歌曲"
                            lines.isEmpty() -> track?.artists.orEmpty()
                            else -> "间奏"
                        }
                    } else null
                if (subtitle != null) {
                    Text(
                        subtitle, color = mutedColor, fontSize = if (compact) 15.sp else 20.sp,
                        lineHeight = if (compact) 22.sp else 29.sp,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = if (compact) 8.dp else 12.dp),
                    )
                }
                if (!compact) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        verse.next.orEmpty(), color = textColor.copy(alpha = 0.42f),
                        fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(220)), exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = controlsStart,
                            end = if (compact) 16.dp else 28.dp,
                            top = 16.dp,
                            bottom = 16.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            track?.title ?: "沉浸歌词", color = textColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = metadataWidth),
                        )
                        Text(
                            track?.artists.orEmpty(), color = mutedColor, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = metadataWidth),
                        )
                    }
                    ImmersiveSettingsActions(
                        store, settings, tint = textColor,
                        textSize = settingsTextSize,
                        accentColor = accentColor,
                        onOverlayChanged = { settingsOpen = it },
                        onInteraction = revealControls, onError = reportError,
                    )
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(220)), exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                CloseButton(
                    Grid(maxHeight / 100f),
                    onClose,
                    contentDescription = "收起播放器",
                )
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(220)), exit = fadeOut(tween(600)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = side, vertical = if (compact) 8.dp else 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ImmersiveSeekBar(source, clock, track?.id, backdrop, onInteraction = revealControls)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp),
                    ) {
                        val isLiked = track?.id?.let { it in liked } == true
                        ImmersiveAction(
                            if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "喜欢",
                            selected = isLiked, enabled = track != null,
                            onClick = { track?.let { source.toggleLike(it.id) } },
                            tint = textColor, accent = accent,
                        )
                        Spacer(Modifier.weight(1f))
                        ImmersiveAction(
                            Icons.Rounded.SkipPrevious, "上一首", enabled = track != null && (!fm || neighbors.previousIndex != null),
                            onClick = source::previous, tint = textColor,
                        )
                        IconButton(
                            onClick = source::togglePlay, enabled = track != null,
                            modifier = Modifier.size(if (compact) 52.dp else 64.dp).background(textColor, CircleShape),
                        ) {
                            Icon(
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (playing) "暂停" else "播放",
                                tint = if (backdrop == ImmersiveBackdrop.RIPPLE) backdrop.swatch else Color(0xFF13231C),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        ImmersiveAction(Icons.Rounded.SkipNext, "下一首", enabled = track != null, onClick = source::next, tint = textColor)
                        Spacer(Modifier.weight(1f))
                        ImmersiveAction(Icons.AutoMirrored.Rounded.QueueMusic, "播放队列", onClick = { showQueue = true }, tint = textColor)
                    }
                }
            }
            if (showQueue) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pressable { showQueue = false },
                )
                QueuePanel(
                    source, InkAccent, Grid(6.dp), minOf(maxWidth * 0.88f, 440.dp),
                    onClose = { showQueue = false; revealControls() },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun ImmersiveSeekBar(
    source: NowPlayingSource, clock: PlaybackClock, trackId: Long?,
    backdrop: ImmersiveBackdrop, onInteraction: () -> Unit,
) {
    var drag by remember(trackId) { mutableStateOf<Float?>(null) }
    val position by remember(clock) { derivedStateOf { clock.position.longValue / 250L * 250L } }
    val duration by remember(clock) { derivedStateOf { clock.duration.longValue } }
    val fraction = drag ?: immersiveProgress(position, duration)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(immersiveTime(if (drag == null) position else (fraction * duration).toLong()), color = backdrop.muted, fontSize = 12.sp, modifier = Modifier.width(46.dp))
        Slider(
            value = fraction, enabled = duration > 0L,
            onValueChange = { drag = it; onInteraction() },
            onValueChangeFinished = {
                drag?.let { source.seekTo((it * duration).toLong()) }
                drag = null
                onInteraction()
            },
            modifier = Modifier.weight(1f).height(32.dp).semantics { contentDescription = "播放进度" },
            colors = SliderDefaults.colors(
                thumbColor = backdrop.foreground, activeTrackColor = backdrop.accent,
                inactiveTrackColor = backdrop.foreground.copy(alpha = 0.16f),
            ),
        )
        Text(immersiveTime(duration), color = backdrop.muted, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(46.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImmersiveAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    enabled: Boolean = true,
    tint: Color = InkWhite,
    accent: Color = InkAccent,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        val glyph: @Composable () -> Unit = {
            Icon(icon, label, modifier = Modifier.size(24.dp))
        }
        if (selected == null) {
            IconButton(
                onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = tint, disabledContentColor = tint.copy(alpha = 0.3f)),
                content = glyph,
            )
        } else {
            IconToggleButton(
                checked = selected, onCheckedChange = { onClick() }, enabled = enabled, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconToggleButtonColors(
                    contentColor = tint, checkedContentColor = accent, checkedContainerColor = accent.copy(alpha = 0.13f),
                ),
                content = glyph,
            )
        }
    }
}
