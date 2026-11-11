#!/usr/bin/env python3
"""Render the Geometric Mesh Layout toolbar icons. Requires Python 3 and Pillow.

The six 32px states, each in a light and a "-dark" variant, retain the toolbar's neutral palette and pressed/selected
contrast, with a Turboism corner signature to identify the plugin tool.

The script writes the 32px base raster plus the 40/48/56/64px DPI variants that
``ToolbarIconLoader.scaleVariantPath`` resolves as ``.scale-125`` through ``.scale-200``. Every size is
downsampled from the same 8x working raster, and the corner badge keeps its proportional size.
"""

import math
from pathlib import Path
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/artwork"))
from plugin_icon_badge import with_plugin_badge


SIZE = 32
SCALE = 8
# DPI variants the runtime resolves by name; each entry is the ToolbarIconLoader suffix and its pixel size.
DPI_VARIANTS = (("125", 40), ("150", 48), ("175", 56), ("200", 64))
OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/icons"

STATES = (
    ("", None, None, "#656767"),
    ("-active", None, None, "#f0efef"),
    ("-rollover", None, None, "#656767"),
    ("-selected", None, None, "#656767"),
    ("-disabled", None, None, "#c6c6c6"),
    ("-disabled-selected", None, None, "#c6c6c6"),
)

DARK_STATES = (
    ("", None, None, "#a8a8a8"),
    ("-active", None, None, "#f0efef"),
    ("-rollover", None, None, "#e0e0e0"),
    ("-selected", None, None, "#e0e0e0"),
    ("-disabled", None, None, "#555555"),
    ("-disabled-selected", None, None, "#555555"),
)


def scaled(points):
    return [(x * SCALE, y * SCALE) for x, y in points]


def glyph_mask():
    mask = Image.new("L", (SIZE * SCALE, SIZE * SCALE))
    draw = ImageDraw.Draw(mask)

    cx, cy = 15.0, 15.5
    radius = 9.2

    # Outer circle outline representing circular mesh layout
    draw.ellipse(
        scaled(((cx - radius, cy - radius), (cx + radius, cy + radius))),
        outline=255,
        width=round(1.6 * SCALE),
    )

    # Inscribed equilateral triangle edges
    tri_pts = []
    for idx in range(3):
        angle = -math.pi / 2.0 + idx * (2.0 * math.pi / 3.0)
        tri_pts.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
    draw.polygon(scaled(tri_pts), outline=255, width=round(1.4 * SCALE))

    # Vertex squares on the three triangle corners
    half_box = 1.45
    for vx, vy in tri_pts:
        draw.rectangle(
            scaled(((vx - half_box, vy - half_box), (vx + half_box, vy + half_box))),
            fill=255,
        )

    # Center anchor crosshair
    arm = 2.6
    draw.line(
        scaled(((cx - arm, cy), (cx + arm, cy))),
        fill=255,
        width=round(1.5 * SCALE),
    )
    draw.line(
        scaled(((cx, cy - arm), (cx, cy + arm))),
        fill=255,
        width=round(1.5 * SCALE),
    )
    return mask


def render(background, frame, foreground, mask, size):
    image = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE))
    draw = ImageDraw.Draw(image)
    if background is not None:
        draw.rounded_rectangle(
            (0, 0, SIZE * SCALE - 1, SIZE * SCALE - 1),
            radius=1.5 * SCALE,
            fill=frame,
        )
        draw.rounded_rectangle(
            (SCALE, SCALE, (SIZE - 1) * SCALE - 1, (SIZE - 1) * SCALE - 1),
            radius=0.5 * SCALE,
            fill=background,
        )
    image.paste(foreground, (0, 0, SIZE * SCALE, SIZE * SCALE), mask)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def main():
    # The committed 32px raster is the reviewed artifact: a rerun refreshes the DPI variants from
    # the same drawing, and rewrites the base only when the artwork itself changed.
    redraw_base = "--base" in sys.argv[1:]
    OUTPUT.mkdir(parents=True, exist_ok=True)
    mask = glyph_mask()
    variants = [(s, "") for s in STATES] + [(s, "-dark") for s in DARK_STATES]
    for (suffix, background, frame, foreground), theme in variants:
        stem = f"geometric-mesh-layout{suffix}{theme}"
        sizes = [(SIZE, stem)] + [(size, f"{stem}.scale-{scale}") for scale, size in DPI_VARIANTS]
        for size, name in sizes:
            path = OUTPUT / f"{name}.png"
            if size == SIZE and path.exists() and not redraw_base:
                print(f"kept {path.relative_to(OUTPUT.parent)} (pass --base after an artwork change)")
                continue
            image = render(background, frame, foreground, mask, size)
            with_plugin_badge(image, disabled="disabled" in suffix).save(path, optimize=True)
            print(path.relative_to(OUTPUT.parent))


if __name__ == "__main__":
    main()
