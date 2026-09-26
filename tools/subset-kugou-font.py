#!/usr/bin/env python3
"""Build the bundled KuGou immersive-font GB2312 subset."""

import argparse
from pathlib import Path

from fontTools import subset


ASCII = "".join(chr(codepoint) for codepoint in range(0x20, 0x7F))


def gb2312_text() -> str:
    characters = set(ASCII)
    for high in range(0xA1, 0xF8):
        for low in range(0xA1, 0xFF):
            try:
                character = bytes((high, low)).decode("gb2312")
            except UnicodeDecodeError:
                continue
            codepoint = ord(character)
            if 0xE000 <= codepoint <= 0xF8FF or codepoint == 0xFFFD:
                continue
            characters.add(character)
    return "".join(sorted(characters))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Subset the Swei Spring Sugar font to ASCII and GB2312."
    )
    parser.add_argument("source", type=Path, help="Full upstream TTF or WOFF2 font")
    parser.add_argument("output", type=Path, help="Destination subset TTF")
    args = parser.parse_args()

    source = args.source.resolve()
    output = args.output.resolve()
    if source == output:
        parser.error("source and output must be different files")
    if not source.is_file():
        parser.error(f"source font does not exist: {source}")

    output.parent.mkdir(parents=True, exist_ok=True)
    text = gb2312_text()
    subset.main(
        [
            str(source),
            f"--text={text}",
            f"--output-file={output}",
            "--layout-features=*",
            "--name-IDs=*",
            "--no-hinting",
        ]
    )


if __name__ == "__main__":
    main()
