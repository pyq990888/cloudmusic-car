"""Render a quick preview of an immersive lyric font."""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def main() -> None:
    font_path = Path(sys.argv[1])
    output = Path(sys.argv[2])
    img = Image.new("RGB", (1280, 720), (10, 14, 24))
    draw = ImageDraw.Draw(img)
    big = ImageFont.truetype(str(font_path), 96)
    small = ImageFont.truetype(str(font_path), 44)
    draw.text((640, 260), "夜空中最亮的星", font=big, fill=(245, 240, 228), anchor="mm")
    draw.text((640, 400), "能否听清 那仰望的人", font=small, fill=(160, 165, 178), anchor="mm")
    output.parent.mkdir(parents=True, exist_ok=True)
    img.save(output)
    print(output)


if __name__ == "__main__":
    main()
