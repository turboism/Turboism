#!/usr/bin/env python3
"""Render the original Selection Brush artwork. Requires Python 3 and Pillow.

The six 32px states, each in a light and a "-dark" variant, retain the toolbar's neutral palette and pressed/selected
contrast, with a Turboism corner signature to identify the plugin tool.
The default glyph has a transparent background and no frame.
The artwork is drawn here, without using host assets.
The runtime picks the "-dark" sibling of each icon when the host look-and-feel is dark.
Run from any directory; only this plugin's twelve PNG resources are overwritten.
"""

from pathlib import Path
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/artwork"))
from plugin_icon_badge import with_plugin_badge


SIZE = 32
SCALE = 8
OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/icons"

# suffix, background, frame, foreground
STATES = (
    ("", None, None, "#656767"),
    ("-active", "#727272", "#cbcbca", "#f0efef"),
    ("-rollover", "#dbdada", "#cbcbca", "#656767"),
    ("-selected", "#b3b2b3", "#cbcbca", "#656767"),
    ("-disabled", "#f2f2f2", "#e8e8e8", "#c6c6c6"),
    ("-disabled-selected", "#e2e2e2", "#e8e8e8", "#c6c6c6"),
)


# Dark-theme counterparts. Glyph #a8a8a8 and the 30% disabled alpha are sampled from the
# native BrushSelection_32x32 dark icon; #e0e0e0 is CubismDarkTheme's foreground; frames follow
# CubismDarkTheme's border (#323232 lightened 11%). State fills mirror the light ladder
# (rollover < selected < active) as lightness steps up from the #323232 dark background.
DARK_STATES = (
    ("", None, None, "#a8a8a8"),
    ("-active", "#8c8c8c", "#4e4e4e", "#f0efef"),
    ("-rollover", "#484848", "#4e4e4e", "#e0e0e0"),
    ("-selected", "#5e5e5e", "#4e4e4e", "#e0e0e0"),
    ("-disabled", "#323232", "#393939", "#555555"),
    ("-disabled-selected", "#3d3d3d", "#393939", "#555555"),
)


def scaled(points):
    return [(x * SCALE, y * SCALE) for x, y in points]


def cubic(start, control_a, control_b, end):
    """Sample a cubic curve for a filled silhouette at the working resolution."""
    points = []
    for step in range(1, 65):
        t = step / 64
        u = 1 - t
        points.append(tuple(
            u**3 * start[axis]
            + 3 * u**2 * t * control_a[axis]
            + 3 * u * t**2 * control_b[axis]
            + t**3 * end[axis]
            for axis in (0, 1)
        ))
    return points


def glyph_mask():
    mask = Image.new("L", (SIZE * SCALE, SIZE * SCALE))
    draw = ImageDraw.Draw(mask)

    # Small square mesh vertices balance the diagonal brush, without a +/- mark.
    for x, y in ((7, 7), (12, 7), (17, 7), (7, 12), (12, 12), (7, 17)):
        draw.rectangle(scaled(((x, y), (x + 2.5, y + 2.5))), fill=255)

    # Tapered handle, separated from the circular selection footprint below.
    handle = [(24.8, 6.4)]
    handle += cubic(handle[-1], (25.8, 5.8), (26.3, 6.2), (25.9, 7.3))
    handle += [(19.2, 18.7), (16.1, 16.4)]
    draw.polygon(scaled(handle), fill=255)

    # A round outline conveys the brush radius and distinguishes this selection
    # tool from native paint brushes. Keep it clear of the vertex squares.
    draw.ellipse(
        scaled(((9, 18), (18, 27))),
        outline=255,
        width=round(1.7 * SCALE),
    )
    return mask


def render(background, frame, foreground, mask):
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
    return image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    mask = glyph_mask()
    variants = [(s, "") for s in STATES] + [(s, "-dark") for s in DARK_STATES]
    for (suffix, background, frame, foreground), theme in variants:
        path = OUTPUT / f"selection-brush{suffix}{theme}.png"
        image = render(background, frame, foreground, mask)
        with_plugin_badge(image, disabled="disabled" in suffix).save(path, optimize=True)
        print(path.relative_to(OUTPUT.parent))


if __name__ == "__main__":
    main()
