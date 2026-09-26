package com.paopao.music.nowplaying

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal data class ImmersivePreferences(
    val mode: FoliaLyricMode = FoliaLyricMode.LUMINOUS,
    val font: LyricFont = LyricFont.SWEI,
    val backdrop: ImmersiveBackdrop = ImmersiveBackdrop.INK,
    val reducedMotion: Boolean = false,
    val imageName: String? = null,
    val imageLabel: String? = null,
    val imageDim: Float = 0.35f,
)

internal class ImmersivePreferenceStore(context: Context) {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences("kugou_immersive_v3", Context.MODE_PRIVATE)

    fun read() = ImmersivePreferences(
        mode = enumValue(preferences.getString("mode", null), FoliaLyricMode.LUMINOUS),
        font = enumValue(preferences.getString("font", null), LyricFont.SWEI),
        backdrop = enumValue(preferences.getString("backdrop", null), ImmersiveBackdrop.INK),
        reducedMotion = preferences.getBoolean("reduced_motion", false),
        imageName = preferences.getString("image_file", null),
        imageLabel = preferences.getString("image_label", null),
        imageDim = preferences.getFloat("image_dim", 0.35f).coerceIn(0f, 0.8f),
    )

    fun mode(value: FoliaLyricMode) { preferences.edit().putString("mode", value.name).apply() }
    fun font(value: LyricFont) { preferences.edit().putString("font", value.name).apply() }
    fun backdrop(value: ImmersiveBackdrop) { preferences.edit().putString("backdrop", value.name).apply() }
    fun reduced(value: Boolean) { preferences.edit().putBoolean("reduced_motion", value).apply() }
    fun dim(value: Float) { preferences.edit().putFloat("image_dim", value.coerceIn(0f, 0.8f)).apply() }
}

internal inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

@Composable
internal fun rememberImmersivePreferences(): Pair<ImmersivePreferenceStore, ImmersivePreferences> {
    val context = LocalContext.current
    val store = remember(context) { ImmersivePreferenceStore(context) }
    var value by remember(store) { mutableStateOf(store.read()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value = store.read() }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return store to value
}
