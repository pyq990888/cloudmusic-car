"""Subset the immersive-preview fonts to the characters used in the HTML and
inline them as base64. Re-runnable: it restores placeholders first, then
re-injects fresh subsets.

The display font this preview page renders with is not bundled with the app, so
it is kept outside the repository and passed in as an argument. The app's own
lyric font is read from shared/nowplaying/src/main/resources.

Usage:
    python tools/build-immersive-preview-fonts.py <preview-font.ttf>
"""

import base64
import io
import re
import sys
from pathlib import Path

from fontTools import subset

ROOT = Path(__file__).resolve().parent.parent
HTML = ROOT / "docs" / "immersive-effects-preview-v2.html"
BUNDLED_FONT = ROOT / "shared/nowplaying/src/main/resources/immersive/fonts/SweiSpringSugarSC.ttf"
PLACEHOLDERS = ["__FONT_ZEN_B64__", "__FONT_SWEI_B64__"]

# Matches an already-injected data URI, so the script can be re-run.
INJECTED = re.compile(r"url\(data:font/ttf;base64,[^)]*\)")


def subset_bytes(path: Path, text: str) -> bytes:
    options = subset.Options()
    options.desubroutinize = True
    options.layout_features = ["*"]
    options.notdef_outline = True
    options.recalc_bounds = True
    options.drop_tables += ["DSIG"]
    font = subset.load_font(str(path), options)
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(text=text)
    subsetter.subset(font)
    buf = io.BytesIO()
    subset.save_font(font, buf, options)
    return buf.getvalue()


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(f"usage: {Path(sys.argv[0]).name} <preview-font.ttf>")
    preview_font = Path(sys.argv[1])
    if not preview_font.is_file():
        sys.exit(f"no such font: {preview_font}")
    font_files = [preview_font, BUNDLED_FONT]

    html = HTML.read_text(encoding="utf-8")

    matches = list(INJECTED.finditer(html))
    if len(matches) != len(PLACEHOLDERS):
        sys.exit(f"expected {len(PLACEHOLDERS)} injected font urls, found {len(matches)}")
    # Restore placeholders (in file order) so the script is idempotent.
    for match, placeholder in zip(reversed(matches), reversed(PLACEHOLDERS)):
        html = html[: match.start()] + f"url(data:font/ttf;base64,{placeholder})" + html[match.end():]

    chars = {ch for ch in html if ord(ch) > 0x7F}
    chars |= {chr(c) for c in range(0x20, 0x7F)}
    text = "".join(sorted(chars))
    print(f"subsetting to {len(chars)} characters")

    for placeholder, path in zip(PLACEHOLDERS, font_files):
        data = subset_bytes(path, text)
        b64 = base64.b64encode(data).decode("ascii")
        html = html.replace(placeholder, b64)
        print(f"{path.name}: {path.stat().st_size / 1024:.0f} KiB -> {len(b64) / 1024:.0f} KiB base64")

    HTML.write_text(html, encoding="utf-8", newline="")
    print(f"wrote {HTML}")


if __name__ == "__main__":
    main()
