"""Turboism first-party corner signature for icons embedded in native toolbars.

For the Turboism framework and first-party plugins only, not third-party plugins.
Requires Pillow. Work on the original artwork, never on an already badged PNG.
The T shape remains identifiable without color; disabled states use muted gray.
"""

from pathlib import Path

from PIL import Image, ImageDraw


# Normalized geometry shared by the raster and vector exports.
LAYERS = (
    ((0, 0, 1, 1), "keyline"),
    ((0.1, 0.1, 0.9, 0.9), "background"),
    ((0.22, 0.23, 0.78, 0.39), "foreground"),
    ((0.42, 0.36, 0.58, 0.78), "foreground"),
)
PALETTES = {
    "normal": {"keyline": "#f4f3ff", "background": "#6752c7", "foreground": "#ffffff"},
    "disabled": {"keyline": "#e6e6e6", "background": "#bdbdbd", "foreground": "#eeeeee"},
}
EXPORT_SIZES = (7, 9, 10, 13, 18, 34, 64)
ASSET_DIR = Path(__file__).resolve().parent / "assets/plugin-badge"


def render_plugin_badge(size, *, disabled=False):
    """Return a standalone RGBA badge at its intended raster size."""
    if not isinstance(size, int) or size < 7:
        raise ValueError("badge size must be an integer of at least 7 pixels")
    scale = 8
    badge = Image.new("RGBA", (size * scale, size * scale))
    draw = ImageDraw.Draw(badge)

    def box(bounds, color):
        draw.rectangle(tuple(round(v * size * scale) for v in bounds), fill=color)

    palette = PALETTES["disabled" if disabled else "normal"]
    for bounds, role in LAYERS:
        box(bounds, palette[role])
    return badge.resize((size, size), Image.Resampling.LANCZOS)


def with_plugin_badge(image, *, disabled=False):
    image = image.convert("RGBA")
    # Wide source images (e.g. the symmetry controls) are scaled by the host.
    size = max(7, round(image.width * 0.28))
    margin = max(1, round(image.width / 32))
    badge = render_plugin_badge(size, disabled=disabled)
    image.alpha_composite(badge, (image.width - size - margin, image.height - size - margin))
    return image


def export_badge_assets():
    """Export standalone PNGs and font-independent SVGs from the same geometry."""
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    for state, palette in PALETTES.items():
        suffix = "-disabled" if state == "disabled" else ""
        stem = f"turboism-plugin-badge{suffix}"
        rectangles = []
        for (left, top, right, bottom), role in LAYERS:
            rectangles.append(
                f'  <rect x="{left * 100:g}" y="{top * 100:g}" '
                f'width="{(right - left) * 100:g}" height="{(bottom - top) * 100:g}" '
                f'fill="{palette[role]}"/>'
            )
        svg = '\n'.join([
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">',
            f'  <title>Turboism first-party badge ({state})</title>',
            *rectangles,
            '</svg>',
            '',
        ])
        (ASSET_DIR / f"{stem}.svg").write_text(svg, encoding="utf-8")
        for size in EXPORT_SIZES:
            render_plugin_badge(size, disabled=state == "disabled").save(
                ASSET_DIR / f"{stem}-{size}.png", optimize=True
            )


if __name__ == "__main__":
    export_badge_assets()
