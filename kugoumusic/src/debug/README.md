# Immersive Lyrics Preview

The offline preview uses the production immersive screen with a deterministic,
silent player fixture. It does not require login or fetch music. Its activity
and original sample lyrics are excluded from release APKs and plugin packages.

```powershell
.\gradlew.bat :kugoumusic:assembleDebug :kugoumusic:testDebugUnitTest
adb -s emulator-5554 install -r kugoumusic/build/outputs/apk/debug/kugoumusic-debug.apk
adb -s emulator-5554 shell am start -n com.kugoumusic.car/.preview.ImmersivePreviewActivity
```

Optional intent extras:

- `--ei track 0`: original Chinese lyrics; `1`: English and translation;
  `2`: no lyrics.
- `--el position 73500 --ez paused true`: inspect a long lyric at a fixed time.
- `--ez standard true`: start on the cover page to test swipe navigation.
- `--el line_interval 4000`: shorten the silent fixture's lines for comparing
  all four transitions in one short recording.

The three pages are spatially ordered as immersive / cover / regular lyrics.
From the cover, swipe right for the immersive page, or left for regular lyrics.
Swipe left from immersive to return to the cover. Horizontal paging does not
replace vertical track switching, lyric scrolling, or progress seeking.

The immersive page has no in-page back button. Its top-right controls select
lyric mode, background, font, and reduced motion. Swipe navigation and system
Back remain available.

Lyric modes:

- Luminous: loose line layout with timed word emphasis and afterglow.
- Mindscape: alternating lyric fragments, a larger focal fragment, and drift.
- Partita: staggered rows, guide lines, and independently timed word reveals.
- Fume: bounded article context with typed active text, passed text and a
  moving reading focus.

These are native adaptations of the four Folia visual languages, not the
Folia web renderer or its complete tuning and audio-analysis system.
LRC-only tracks do not invent word timings.

Backgrounds: ink, water ripples, aurora curtains, light trails, Common, Monet,
Nomand, Latent, Sora, and an imported image. Common uses drifting geometry;
Monet applies painterly sampling; Nomand applies fluted glass; Latent combines
color fields and ordered dithering; Sora is a moving star field. Image-based
effects prefer album artwork and fall back to the imported image.

The background menu imports PNG, JPEG, GIF, or WebP through Android's document
picker. Imports are local only, capped at 32 MiB and validated before replacing
the previous image. The file is copied into the app's private directory; no
broad storage permission or permanent external URI grant is needed. Animated
GIF/WebP playback stops when paused, off-page, or in reduced-motion mode.
The background menu also exposes a dimming slider and a remove command.
The picker requires an ActivityResultRegistryOwner from the host. A host that
does not provide one gets a clear error; the standalone APK provides one.

Modes, fonts, backgrounds and dimming persist across app restarts. Font choices
are the Android system default and Swei Spring Sugar SC. The bundled font's
license and upstream reference are included beside its binary under
`shared/nowplaying/src/main/resources/immersive/fonts`; the Kugou plugin
packaging script includes these Java resources as well.

The background remains ambient motion, not an audio spectrum or beat detector.
Low-motion mode freezes background and typography motion while preserving
timed lyric highlighting. Off-page rendering is paused.

Visual references: the user-provided lyric-speaker clip and
https://github.com/chthollyphile/folia-major (in particular its separation of
lyric motion, word timing, and background modes). This implementation uses
native Android text rasterization, Compose paging, and the existing GLES
background; it does not bundle Folia's source, assets, or web runtime.
