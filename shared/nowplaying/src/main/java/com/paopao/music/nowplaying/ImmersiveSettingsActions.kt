package com.paopao.music.nowplaying

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val SettingsMenuShape = RoundedCornerShape(18.dp)
private val SettingsMenuContainer = Color.Black
private val SettingsMenuBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
private val SettingsMenuText = Color.White
private val SettingsMenuSecondary = Color.White.copy(alpha = 0.62f)
private val SettingsMenuDanger = Color(0xFFFFB4AB)
private val SettingsMenuItemPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

@Composable
internal fun ImmersiveSettingsActions(
    store: ImmersivePreferenceStore,
    settings: ImmersivePreferences,
    tint: Color,
    textSize: TextUnit,
    accentColor: Color,
    onOverlayChanged: (Boolean) -> Unit,
    onInteraction: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(menu, importing) { onOverlayChanged(menu != null || importing) }
    val owner = LocalActivityResultRegistryOwner.current
    val picker = if (owner != null) rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                try {
                    importBackground(context, uri, store)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onError(error.message ?: "图片导入失败，原背景未改变")
                } finally {
                    importing = false
                    onInteraction()
                }
            }
        }
    } else null
    val dismiss = { menu = null; onInteraction() }
    val itemColors = MenuDefaults.itemColors(
        textColor = SettingsMenuText,
        leadingIconColor = SettingsMenuSecondary,
        trailingIconColor = accentColor,
        disabledTextColor = SettingsMenuText.copy(alpha = 0.38f),
        disabledLeadingIconColor = SettingsMenuText.copy(alpha = 0.3f),
        disabledTrailingIconColor = accentColor.copy(alpha = 0.38f),
    )
    val radioColors = RadioButtonDefaults.colors(
        selectedColor = accentColor,
        unselectedColor = SettingsMenuSecondary,
        disabledSelectedColor = accentColor.copy(alpha = 0.38f),
        disabledUnselectedColor = SettingsMenuSecondary.copy(alpha = 0.38f),
    )
    val menuWidth = if (textSize.value > 14f) 304.dp else 264.dp
    Row {
        Box {
            ImmersiveAction(Icons.Rounded.Animation, "歌词模式", onClick = { menu = "mode" }, tint = tint)
            SettingsDropdownMenu(
                expanded = menu == "mode",
                onDismissRequest = dismiss,
                modifier = Modifier.width(menuWidth),
            ) {
                FoliaLyricMode.entries.forEachIndexed { index, value ->
                    // Single-line glyph effects, multi-row layouts, and the article mode read as three families.
                    if (index == 4 || index == 6) SettingsMenuDivider()
                    val selected = settings.mode == value
                    DropdownMenuItem(
                        text = { SettingsMenuLabel(value.label, textSize, if (selected) accentColor else SettingsMenuText) },
                        leadingIcon = { RadioButton(selected = selected, onClick = null, colors = radioColors) },
                        onClick = { store.mode(value); dismiss() },
                        colors = itemColors,
                        contentPadding = SettingsMenuItemPadding,
                    )
                }
            }
        }
        Box {
            ImmersiveAction(Icons.Rounded.Palette, "背景风格", onClick = { menu = "background" }, tint = tint, enabled = !importing)
            if (importing) CircularProgressIndicator(Modifier.size(48.dp).padding(12.dp), color = tint, strokeWidth = 2.dp)
            SettingsDropdownMenu(
                expanded = menu == "background", onDismissRequest = dismiss,
                modifier = Modifier.heightIn(max = 520.dp).width(menuWidth),
            ) {
                ImmersiveBackdrop.entries.filter { it != ImmersiveBackdrop.CUSTOM || settings.imageName != null }.forEachIndexed { index, value ->
                    // Scenes and lyric effects, artwork sampling, and the import slot read as three families.
                    if (index == 10 || index == 14) SettingsMenuDivider()
                    val selected = settings.backdrop == value
                    DropdownMenuItem(
                        text = { SettingsMenuLabel(value.label, textSize, if (selected) accentColor else SettingsMenuText) },
                        leadingIcon = { Box(Modifier.size(15.dp).clip(CircleShape).background(value.swatch)) },
                        trailingIcon = { RadioButton(selected = selected, onClick = null, colors = radioColors) },
                        onClick = { store.backdrop(value); dismiss() },
                        colors = itemColors,
                        contentPadding = SettingsMenuItemPadding,
                    )
                }
                SettingsMenuDivider()
                DropdownMenuItem(
                    text = { SettingsMenuLabel("导入图片 / GIF / MP4", textSize) },
                    leadingIcon = { Icon(Icons.Rounded.FileUpload, null, tint = SettingsMenuText) },
                    onClick = {
                        dismiss()
                        if (picker == null) onError("当前宿主未提供文件选择器，请在独立 APK 中导入")
                        else try {
                            picker.launch(
                                arrayOf("image/png", "image/jpeg", "image/gif", "image/webp", "video/mp4", "video/quicktime"),
                            )
                        } catch (_: Exception) {
                            onError("无法打开系统文件选择器")
                        }
                    },
                    colors = itemColors,
                    contentPadding = SettingsMenuItemPadding,
                )
                if (settings.imageName != null) {
                    val kind = if (settings.imageVideo) "视频" else "图片"
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "$kind 背景遮罩",
                            color = SettingsMenuText,
                            fontSize = textSize,
                            lineHeight = textSize * 1.35f,
                        )
                        var dim by remember(settings.imageDim) { mutableStateOf(settings.imageDim) }
                        Slider(value = dim, onValueChange = { dim = it }, onValueChangeFinished = { store.dim(dim) }, valueRange = 0f..0.8f)
                    }
                    DropdownMenuItem(
                        text = { SettingsMenuLabel("移除导入的$kind", textSize, SettingsMenuDanger) },
                        onClick = {
                            dismiss()
                            scope.launch {
                                try { clearBackground(context, store) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { onError("移除背景失败") }
                            }
                        },
                        colors = itemColors,
                        contentPadding = SettingsMenuItemPadding,
                    )
                }
            }
        }
        Box {
            ImmersiveAction(Icons.Rounded.FontDownload, "歌词字体", onClick = { menu = "font" }, tint = tint)
            SettingsDropdownMenu(
                expanded = menu == "font",
                onDismissRequest = dismiss,
                modifier = Modifier.width(menuWidth),
            ) {
                LyricFont.entries.forEach { value ->
                    val selected = settings.font == value
                    DropdownMenuItem(
                        text = { SettingsMenuLabel(value.label, textSize, if (selected) accentColor else SettingsMenuText) },
                        leadingIcon = { RadioButton(selected = selected, onClick = null, colors = radioColors) },
                        onClick = { store.font(value); dismiss() },
                        colors = itemColors,
                        contentPadding = SettingsMenuItemPadding,
                    )
                }
            }
        }
        ImmersiveAction(
            Icons.Rounded.MotionPhotosOff, "低动态",
            onClick = { store.reduced(!settings.reducedMotion) }, selected = settings.reducedMotion,
            tint = tint, accent = settings.backdrop.accent,
        )
    }
}

@Composable
private fun SettingsDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = SettingsMenuShape,
        containerColor = SettingsMenuContainer,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        border = SettingsMenuBorder,
        content = content,
    )
}

@Composable
private fun SettingsMenuDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 12.dp))
}

@Composable
private fun SettingsMenuLabel(
    text: String,
    size: TextUnit,
    color: Color = Color.Unspecified,
) {
    Text(
        text,
        color = color,
        fontSize = size,
        lineHeight = size * 1.35f,
    )
}
