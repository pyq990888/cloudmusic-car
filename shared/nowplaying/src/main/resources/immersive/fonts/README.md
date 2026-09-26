# Immersive Player Fonts

The bundled display font is provided for offline use. Its OFL license is
distributed beside the font binary. System Default uses Android's system font
and does not bundle a font file.

| File | Upstream |
| --- | --- |
| SweiSpringSugarSC.ttf | https://github.com/max32002/swei-spring/tree/main/WebFont/CJK%20SC |

`SweiSpringSugarSC.ttf` is a GB2312 subset generated from the full upstream
font. It contains ASCII and the assigned GB2312 symbols, kana, Greek letters,
Cyrillic letters, punctuation, and simplified Chinese characters. Glyphs
outside that set use Android's configured fallback font.

Regenerate the bundled font from the full upstream TTF or WOFF2 after
installing `fonttools`:

```bash
python tools/subset-kugou-font.py path/to/SweiSpringSugarSC.woff2 \
  shared/nowplaying/src/main/resources/immersive/fonts/SweiSpringSugarSC.ttf
```

Folia's web source, runtime, and graphical assets are not included.
