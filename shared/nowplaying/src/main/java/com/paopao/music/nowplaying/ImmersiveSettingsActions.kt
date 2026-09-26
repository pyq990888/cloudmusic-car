package com.paopao.music.nowplaying

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ImmersiveSettingsActions(
    store: ImmersivePreferenceStore,
    settings: ImmersivePreferences,
    tint: Color,
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
    Row {
        Box {
            ImmersiveAction(Icons.Rounded.Animation, "歌词模式", onClick = { menu = "mode" }, tint = tint)
            DropdownMenu(expanded = menu == "mode", onDismissRequest = dismiss, containerColor = Color(0xFF171C1B)) {
                FoliaLyricMode.entries.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(value.label, color = Color.White) },
                        leadingIcon = { RadioButton(selected = settings.mode == value, onClick = null) },
                        onClick = { store.mode(value); dismiss() },
                    )
                }
            }
        }
        Box {
            ImmersiveAction(Icons.Rounded.Palette, "背景风格", onClick = { menu = "background" }, tint = tint, enabled = !importing)
            if (importing) CircularProgressIndicator(Modifier.size(48.dp).padding(12.dp), color = tint, strokeWidth = 2.dp)
            DropdownMenu(
                expanded = menu == "background", onDismissRequest = dismiss,
                containerColor = Color(0xFF171C1B), modifier = Modifier.heightIn(max = 520.dp).width(256.dp),
            ) {
                ImmersiveBackdrop.entries.filter { it != ImmersiveBackdrop.CUSTOM || settings.imageName != null }.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(value.label, color = Color.White) },
                        leadingIcon = { Box(Modifier.size(15.dp).clip(CircleShape).background(value.swatch)) },
                        trailingIcon = { RadioButton(selected = settings.backdrop == value, onClick = null) },
                        onClick = { store.backdrop(value); dismiss() },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("导入图片 / GIF / WebP", color = Color.White, fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Rounded.FileUpload, null, tint = Color.White) },
                    onClick = {
                        dismiss()
                        if (picker == null) onError("当前宿主未提供文件选择器，请在独立 APK 中导入")
                        else try {
                            picker.launch(arrayOf("image/png", "image/jpeg", "image/gif", "image/webp"))
                        } catch (_: Exception) {
                            onError("无法打开系统文件选择器")
                        }
                    },
                )
                if (settings.imageName != null) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("背景遮罩", color = Color.White, fontSize = 13.sp)
                        var dim by remember(settings.imageDim) { mutableStateOf(settings.imageDim) }
                        Slider(value = dim, onValueChange = { dim = it }, onValueChangeFinished = { store.dim(dim) }, valueRange = 0f..0.8f)
                    }
                    DropdownMenuItem(
                        text = { Text("移除导入的图片", color = Color(0xFFFFB6AF)) },
                        onClick = {
                            dismiss()
                            scope.launch {
                                try { clearBackground(context, store) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { onError("移除背景失败") }
                            }
                        },
                    )
                }
            }
        }
        Box {
            ImmersiveAction(Icons.Rounded.FontDownload, "歌词字体", onClick = { menu = "font" }, tint = tint)
            DropdownMenu(expanded = menu == "font", onDismissRequest = dismiss, containerColor = Color(0xFF171C1B)) {
                LyricFont.entries.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(value.label, color = Color.White) },
                        leadingIcon = { RadioButton(selected = settings.font == value, onClick = null) },
                        onClick = { store.font(value); dismiss() },
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
