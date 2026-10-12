# 沉浸歌词页

本文档说明酷狗音乐模块的沉浸歌词功能，包括三页播放导航、Folia 风格歌词模式、动态背景、字体、自定义图片背景和调试验证方式。

## 功能入口

播放页现在是三个横向页面：

| 页面 | 位置 | 进入方式 |
| --- | --- | --- |
| 沉浸歌词页 | 第 1 页 | 从封面页右滑 |
| 封面页 | 第 2 页 | 播放页默认页 |
| 普通歌词页 | 第 3 页 | 从封面页左滑 |

沉浸页不显示页内返回按钮。系统返回键会先回到封面页；封面页仍使用原有的收起播放页按钮。

实现位置：

- `shared/nowplaying/src/main/java/com/paopao/music/nowplaying/NowPlayingPager.kt`
- `shared/nowplaying/src/main/java/com/paopao/music/nowplaying/ImmersiveLyricsScreen.kt`
- `shared/nowplaying/src/main/java/com/paopao/music/nowplaying/NowPlayingScreen.kt`
- `kugoumusic/src/main/java/com/kugoumusic/car/ui/screens/NowPlayingScreen.kt`

页面索引定义在 `PlayerPages`：

```text
IMMERSIVE = 0
COVER     = 1
LYRICS    = 2
```

横向分页只处理页面切换。竖向切歌、歌词滚动、进度条拖动和播放控制仍由各页面自己的控件处理。

## 右上角设置

沉浸页右上角提供四组图标：

1. 歌词模式
2. 背景风格
3. 歌词字体
4. 低动态开关

设置使用 `SharedPreferences` 保存，应用重启后恢复。存储实现位于：

```text
shared/nowplaying/src/main/java/com/paopao/music/nowplaying/ImmersivePreferences.kt
```

当前设置键：

| 键 | 内容 |
| --- | --- |
| `mode` | 歌词模式 |
| `font` | 字体 |
| `backdrop` | 背景模式 |
| `reduced_motion` | 低动态开关 |
| `image_file` | 私有目录中的自定义背景文件名 |
| `image_label` | 自定义背景显示名称 |
| `image_dim` | 自定义背景遮罩透明度 |
| `image_video` | 自定义背景是否为 MP4 视频 |

## Folia 风格歌词模式

项目参考 Folia 的四种视觉语言，但使用 Android 原生 `StaticLayout`、`Canvas`、Compose 和 GLES 实现，不复制 Folia 的 Web 运行时、源代码或图形资源。

模式定义在：

```text
shared/nowplaying/src/main/java/com/paopao/music/nowplaying/ImmersiveEffects.kt
shared/nowplaying/src/main/java/com/paopao/music/nowplaying/FoliaLyricLayout.kt
shared/nowplaying/src/main/java/com/paopao/music/nowplaying/FoliaLyrics.kt
```

### 流光

单句主视觉：

- 当前句居中显示；
- 逐字时间戳驱动点亮和轻微抬升；
- 句子切换时淡入、错位和残影；
- 适合普通歌词和逐字歌词。

### 心象

自由构图：

- 一句歌词按字符或词组拆成多个片段；
- 中间片段作为视觉焦点放大；
- 左右片段交替偏移和轻微旋转；
- 适合较短、节奏感明显的句子。

### 云阶

错落行列：

- 长句分成多个视觉行；
- 行之间交替偏移；
- 使用辅助线表现层级和方向；
- 每行、每字拥有独立的入场延迟。

### 浮名

文章式阅读：

- 同时保留前句、当前句和后续句；
- 当前句使用较高亮度；
- 已唱内容逐渐变暗；
- 摄像机平滑跟随当前句，形成连续阅读视点。

四种模式共用的歌词渲染：

- 逐字点亮使用柔边擦除，不再是硬切边；
- 每行预先生成一张模糊辉光图，正在唱的字辉光最亮，已唱的字留下逐渐减弱的余辉；
- 长音（约 1.5 秒以上）的字膨胀和抬升幅度更大；
- 新句入场带轻微回弹，旧句逐字上浮、散开并淡出；
- 换句时旧句从换句前一刻的样子直接开始退场，点亮进度、位置和辉光都不跳变，余辉在退场前段平滑减弱；快速连续换句时，前几句各自播完退场；
- 每句沿用上屏时的排版，翻译或间奏提示出现导致舞台高度变化时，正在退场的旧句不会重排；下一句的歌词图层提前在后台生成，换句时主线程不排版；
- 浮名模式下，离镜头越远的行越小、越暗。

模式实现只使用已有的 `NowPlayingLyric.words` 时间数据。没有逐字时间戳时，只做整句进入和退出，不伪造逐字同步。

## 背景模式

背景渲染位于：

```text
shared/nowplaying/src/main/java/com/paopao/music/nowplaying/InkFlowBackground.kt
shared/nowplaying/src/main/java/com/paopao/music/nowplaying/BackdropArtwork.kt
```

### 内置背景

| 名称 | 说明 |
| --- | --- |
| 水墨 | 域扭曲噪声和连续墨纹 |
| 水面涟漪 | 浅色水面和中心扩散波纹 |
| 极光光幕 | 绿色光幕和暖色低位光带 |
| 通用 | 封面采样、几何漂移和光线 |
| 莫奈 | 封面采样、笔触扰动和柔和叠色 |
| 隐现 | 颜色场、网点和有序抖动 |
| 空 | 低成本星点背景 |
| 流体封面 | 封面缩成 24px 色块后做域扭曲和旋转，形成流动的柔和色场 |

背景使用 GLES 2.0，兼容 Android 9 及以上设备。渲染表面限制在较低分辨率，歌词层仍使用原始窗口分辨率，以降低车机 GPU 压力并保持文字清晰。

背景会跟随歌词节奏：每换一句歌词，背景产生一次脉冲（流速加快、中心泛光，涟漪背景会扩散出新的一圈波纹）；唱歌、间奏和暂停对应不同的能量（1 / 0.4 / 0.15），能量会平滑过渡。

背景属于氛围动画，不是音频频谱分析。当前版本没有接入 Media3 `AudioProcessor` 或音频频段数据。

### 低动态模式

低动态开启后：

- GLES 背景停止推进；
- GIF / 动态 WebP 停止播放；
- MP4 视频背景暂停；
- 歌词入场和漂移停止；
- 已有逐字时间高亮仍继续更新；
- 页面离开后停止背景和歌词的逐帧更新。

## 自定义背景（图片 / 视频）

入口位于右上角的背景菜单，支持：

- PNG
- JPEG
- GIF
- WebP
- MP4 / QuickTime（H.264 等设备原生可解码的编码）

导入流程：

1. 通过 Android 文件选择器选择文件；
2. 复制到应用私有目录；
3. 按文件头区分图片和 MP4 视频；
4. 图片校验分辨率，视频用 `MediaExtractor` 校验容器与画面轨道（只读元数据，不申请解码器）；
5. 成功后原子更新设置；
6. 删除上一个自定义背景文件。

限制：

- 图片最大文件大小：32 MiB，视频最大文件大小：128 MiB；
- 图片最大像素数：80,000,000；
- 视频不限制时长和分辨率，使用系统 `MediaPlayer` 解码，能否播放取决于车机芯片；
- 不申请广泛存储权限；
- 不永久保存外部 URI 权限；
- GIF / 动态 WebP 使用 `AnimatedImageDrawable` 播放；
- MP4 使用 `TextureView` + `MediaPlayer` 循环播放，静音，用 `setTransform` 按 CENTER_CROP 铺满并随横竖屏重算；
- 播放器生命周期跟着纹理走，`setSurface` 在 `prepareAsync` 之前完成，纹理重建后自动重走一遍；此前「先 prepare 再补绑 Surface」正是导入后偶发黑屏的原因；
- 起播后 2 秒内纹理一帧都没收到（车机上偶发拿不到硬解）就自动重建播放器，最多 3 次，等价于手动切几次背景但不需要用户操作，仍失败才回退到墨水背景并提示；
- 导入校验不占用解码器：导入时若先开一个 `MediaPlayer`，紧接着页面再开一个，车机唯一的一路硬解会偶发抢不到而黑屏；
- 暂停、低动态、离开沉浸页时视频暂停，销毁时释放解码器，避免后台占用；
- 导入失败时保留原背景。

文件实现位置：

```text
shared/nowplaying/src/main/java/com/paopao/music/nowplaying/ImportedBackground.kt
```

文件保存目录：

```text
<应用私有目录>/files/kugou_immersive/backgrounds/
```

文件名使用受限格式 `background-<UUID>.img`，避免设置值被当作路径使用。

## 字体

字体加载实现位于：

```text
shared/nowplaying/src/main/java/com/paopao/music/nowplaying/ImmersiveFonts.kt
```

当前内置字体：

| 显示名称 | 文件 |
| --- | --- |
| 系统默认 | 使用 Android 系统字体，不额外打包字体文件 |
| 四季春加糖 | `SweiSpringSugarSC.ttf` |

字体资源和许可证位于：

```text
shared/nowplaying/src/main/resources/immersive/fonts/
```

字体第一次被选择时，复制到应用私有目录：

```text
<应用私有目录>/files/kugou_immersive/fonts-v2/
```

四季春加糖字体来自上游项目，目录中保留对应许可证。修改字体资源时需要同步更新 `README.md` 和许可证文件。

打包的 `SweiSpringSugarSC.ttf` 是完整上游字体的 GB2312 子集，包含 ASCII 和 GB2312
规定的符号、假名、希腊字母、西里尔字母、标点及简体汉字。超出子集范围的字符由
`ImmersiveFonts.kt` 中配置的系统备用字体回退显示。需要重新生成子集时可运行：

```powershell
python tools/subset-kugou-font.py <完整字体文件> shared/nowplaying/src/main/resources/immersive/fonts/SweiSpringSugarSC.ttf
```

字体缓存目录升级到 `fonts-v2`。新字体首次落盘后会删除旧 `fonts-v1` 下的同名字体，
避免升级设备继续保留完整字体缓存。

## 插件宿主限制

独立 Debug APK 使用 `ComponentActivity`，可以正常打开系统文件选择器。

`.ppmusic` 功能包通过宿主传入的 `ComposeView` 运行。自定义图片导入要求宿主提供 `ActivityResultRegistryOwner`：

- 宿主提供时：背景菜单可打开系统文件选择器；
- 宿主未提供时：模式、字体和内置背景仍可用，导入操作会提示当前宿主不支持文件选择器；
- 不应通过申请存储权限绕过该限制。

功能包打包脚本会把共享资源一起放入功能包：

```text
tools/package-kugoumusic-plugin.sh
```

## 调试预览

Debug 专用预览 Activity：

```text
kugoumusic/src/debug/java/com/kugoumusic/car/preview/ImmersivePreviewActivity.kt
```

它不会进入 Release APK 或 `.ppmusic` 功能包。

构建和启动：

```powershell
.\gradlew.bat :kugoumusic:assembleDebug :kugoumusic:testDebugUnitTest
adb -s emulator-5554 install -r kugoumusic/build/outputs/apk/debug/kugoumusic-debug.apk
adb -s emulator-5554 shell am start -n com.kugoumusic.car/.preview.ImmersivePreviewActivity
```

常用参数：

```powershell
# 从封面页启动，验证三页横向手势
--ez standard true

# 中文歌词、英文歌词、无歌词
--ei track 0
--ei track 1
--ei track 2

# 固定时间暂停
--el position 73500 --ez paused true

# 缩短句间隔，快速观察四种模式
--el line_interval 4000
```

## 验证清单

修改沉浸歌词功能后至少验证：

- 封面右滑进入沉浸页；
- 封面左滑进入普通歌词页；
- 沉浸页左滑回封面；
- 竖屏长句不裁切；
- 歌词没有逐字时间戳时不产生假同步；
- 四种歌词模式都能切换；
- 系统默认字体和四季春加糖都可切换，字体加载失败时回退到系统字体；
- 内置背景都能切换；
- 静态图片可以导入；
- GIF / WebP 可以导入并播放；
- MP4 视频可以导入并循环播放，删除按钮可移除；
- 横竖屏切换后视频仍然铺满，不出现缩放不完整或残留旧比例；
- 连续多次导入视频、切后台再回到沉浸页，画面仍正常显示；
- 导入后偶发不出画时能自动恢复，无需手动切背景（`adb logcat -s ImmersiveBg` 可见自恢复日志）；
- 暂停、低动态、离开页面后动态效果停止（含视频暂停）；
- 重启后模式、字体、背景和自定义图片仍可恢复；
- `:kugoumusic:testDebugUnitTest` 通过；
- `:app:compileDebugKotlin` 和 `:qqmusic:compileDebugKotlin` 通过；
- release 类包不包含 Debug 预览 Activity。

最近一次验证包含 40 项单元测试，全部通过。
