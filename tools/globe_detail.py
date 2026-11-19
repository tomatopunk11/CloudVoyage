"""Build matching offline texture levels from NASA's June Blue Marble JPEG."""

import argparse
from pathlib import Path

from PIL import Image, ImageEnhance

WIDTH, HEIGHT = 21600, 10800


def write_tiles(source, output, tile_size):
    output.mkdir(parents=True, exist_ok=True)
    for row, y in enumerate(range(0, source.height, tile_size)):
        for col, x in enumerate(range(0, source.width, tile_size)):
            tile = source.crop((x, y, x + tile_size, y + tile_size))
            tile = ImageEnhance.Color(tile).enhance(.75)
            tile.save(output / f"{col}-{row}.jpg", quality=88, optimize=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--preview-only", action="store_true")
    args = parser.parse_args()
    Image.MAX_IMAGE_PIXELS = WIDTH * HEIGHT
    with Image.open(args.source) as original:
        if original.size != (WIDTH, HEIGHT):
            parser.error(f"expected the {WIDTH}×{HEIGHT} source image")
        args.output.mkdir(parents=True, exist_ok=True)
        overview = original.resize((3600, 1800), Image.Resampling.LANCZOS)
        ImageEnhance.Color(overview).enhance(.75).save(
            args.output / "earth-preview.jpg", quality=88, optimize=True)
        if not args.preview_only:
            write_tiles(original, args.output / "earth-detail", 600)


if __name__ == "__main__":
    main()
