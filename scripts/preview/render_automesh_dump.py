#!/usr/bin/env python3
"""Render an Auto Mesh validation dump as side-by-side PNG comparisons.

The probe writes one directory per probed ArtMesh, and one subdirectory per variant
inside it (``native-run0``, ``native-run1``, ``turbo-mesh.region``, ...). Each variant
holds ``meta.properties``, ``positions.txt``, ``uvs.txt``, ``indices.txt`` and, when the
host exposed texture pixels, ``texture.bin``.

This renderer turns that tree into one PNG per mesh: the source texture with the mesh
outline, then one panel per variant with its vertex and triangle counts in the title.
The output is a plain image, so a reviewer compares the native baseline against every
custom algorithm without a Cubism host.

Requires Pillow, which the repository already uses for its artwork scripts.

Usage: render_automesh_dump.py <dump-dir> [--output <dir>] [--scale <pixels-per-unit>]
"""

from __future__ import annotations

import argparse
import dataclasses
import struct
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover - reported as a clear message, not a traceback
    Image = None


META_FILE = "meta.properties"
POSITIONS_FILE = "positions.txt"
UVS_FILE = "uvs.txt"
INDICES_FILE = "indices.txt"
TEXTURE_FILE = "texture.bin"
NATIVE_PREFIX = "native"

PANEL_SIZE = 320
PANEL_TITLE_HEIGHT = 28
PANEL_MARGIN = 8
BACKGROUND = (24, 24, 28)
PANEL_BACKGROUND = (38, 38, 44)
TEXT_COLOR = (232, 232, 236)
MUTED_TEXT = (150, 150, 160)
MESH_LINE = (90, 200, 255)
NATIVE_LINE = (255, 190, 80)
VERTEX_DOT = (255, 255, 255)
DOT_RADIUS = 1
TRIANGLE_VERTICES = 3
ARGB_ALPHA_SHIFT = 24
ARGB_CHANNEL_MASK = 0xFF
FONT_SIZE = 12
TITLE_FONT_SIZE = 13


@dataclasses.dataclass(frozen=True)
class Variant:
    """One dumped variant of one probed mesh."""

    name: str
    directory: Path
    meta: dict[str, str]
    positions: list[tuple[float, float]]
    uvs: list[tuple[float, float]]
    indices: list[int]
    texture: "Image.Image | None"

    @property
    def vertices(self) -> int:
        return len(self.positions)

    @property
    def triangles(self) -> int:
        return len(self.indices) // TRIANGLE_VERTICES

    @property
    def is_native(self) -> bool:
        return self.name.startswith(NATIVE_PREFIX)


def read_properties(path: Path) -> dict[str, str]:
    """Parse a flat ``key=value`` file without executing it."""
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            values[key.strip()] = value.strip()
    return values


def read_points(path: Path) -> list[tuple[float, float]]:
    """Read one ``x,y`` pair per line."""
    points: list[tuple[float, float]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if not text:
            continue
        x, separator, y = text.partition(",")
        if not separator:
            raise ValueError(f"{path}: malformed point line: {text}")
        points.append((float(x), float(y)))
    return points


def read_indices(path: Path) -> list[int]:
    return [int(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def read_texture(path: Path) -> "Image.Image | None":
    """Read the ``width, height, ARGB...`` raster the probe wrote."""
    if not path.is_file():
        return None
    raw = path.read_bytes()
    if len(raw) < 8:
        return None
    width, height = struct.unpack(">ii", raw[:8])
    expected = 8 + width * height * 4
    if width <= 0 or height <= 0 or len(raw) < expected:
        return None
    image = Image.new("RGBA", (width, height))
    pixels = []
    for offset in range(8, expected, 4):
        pixel = struct.unpack(">I", raw[offset:offset + 4])[0]
        alpha = (pixel >> ARGB_ALPHA_SHIFT) & ARGB_CHANNEL_MASK
        red = (pixel >> 16) & ARGB_CHANNEL_MASK
        green = (pixel >> 8) & ARGB_CHANNEL_MASK
        blue = pixel & ARGB_CHANNEL_MASK
        pixels.append((red, green, blue, alpha))
    image.putdata(pixels)
    return image


def load_variant(directory: Path) -> Variant:
    meta = read_properties(directory / META_FILE)
    return Variant(
        name=meta.get("variant", directory.name),
        directory=directory,
        meta=meta,
        positions=read_points(directory / POSITIONS_FILE),
        uvs=read_points(directory / UVS_FILE),
        indices=read_indices(directory / INDICES_FILE),
        texture=read_texture(directory / TEXTURE_FILE),
    )


def load_mesh(mesh_directory: Path) -> list[Variant]:
    """Load every variant of one probed mesh, native baselines first."""
    variants = [
        load_variant(child)
        for child in sorted(mesh_directory.iterdir())
        if child.is_dir() and (child / META_FILE).is_file()
    ]
    return sorted(variants, key=lambda variant: (not variant.is_native, variant.name))


def uv_to_pixel(meta: dict[str, str]) -> tuple[float, ...]:
    """Return the six uv-to-pixel coefficients, defaulting to the identity raster map."""
    values = []
    for index in range(6):
        raw = meta.get(f"texture.uv2px.{index}")
        if raw is None:
            return (1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        values.append(float(raw))
    return tuple(values)


def project(uv: tuple[float, float], affine: tuple[float, ...]) -> tuple[float, float]:
    u, v = uv
    return (affine[0] * u + affine[1] * v + affine[2], affine[3] * u + affine[4] * v + affine[5])


def fit_transform(
    points: list[tuple[float, float]], size: int
) -> tuple[float, float, float]:
    """Return a scale and offset that centre a point set inside one square panel."""
    if not points:
        return (1.0, 0.0, 0.0)
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    span = max(max(xs) - min(xs), max(ys) - min(ys), 1.0)
    scale = (size - 2 * PANEL_MARGIN) / span
    offset_x = PANEL_MARGIN + ((size - 2 * PANEL_MARGIN) - (max(xs) - min(xs)) * scale) / 2 - min(xs) * scale
    offset_y = PANEL_MARGIN + ((size - 2 * PANEL_MARGIN) - (max(ys) - min(ys)) * scale) / 2 - min(ys) * scale
    return (scale, offset_x, offset_y)


def apply_transform(
    point: tuple[float, float], transform: tuple[float, float, float]
) -> tuple[float, float]:
    scale, offset_x, offset_y = transform
    return (point[0] * scale + offset_x, point[1] * scale + offset_y)


def draw_mesh(
    draw: "ImageDraw.ImageDraw",
    variant: Variant,
    affine: tuple[float, ...],
    transform: tuple[float, float, float],
    colour: tuple[int, int, int],
) -> None:
    """Draw the triangle edges and the vertices of one variant."""
    projected = [apply_transform(project(uv, affine), transform) for uv in variant.uvs]
    for offset in range(0, len(variant.indices) - TRIANGLE_VERTICES + 1, TRIANGLE_VERTICES):
        triangle = variant.indices[offset:offset + TRIANGLE_VERTICES]
        if any(index >= len(projected) for index in triangle):
            continue
        points = [projected[index] for index in triangle]
        draw.polygon(points, outline=colour)
    for point in projected:
        x, y = point
        draw.ellipse(
            (x - DOT_RADIUS, y - DOT_RADIUS, x + DOT_RADIUS, y + DOT_RADIUS), fill=VERTEX_DOT
        )


def panel_title(variant: Variant) -> str:
    return f"{variant.name}  {variant.vertices} v / {variant.triangles} t"


def texture_transform(texture: "Image.Image") -> tuple[float, float, float]:
    """Return the scale and offset that fit a texture into one panel without distortion."""
    scale = min(PANEL_SIZE / texture.width, PANEL_SIZE / texture.height)
    offset_x = (PANEL_SIZE - texture.width * scale) / 2
    offset_y = (PANEL_SIZE - texture.height * scale) / 2
    return (scale, offset_x, offset_y)


def render_panel(variant: Variant, affine: tuple[float, ...]) -> "Image.Image":
    """Render one variant over its texture, or over a dark panel when it has none.

    The mesh and the texture share one coordinate space: a uv first maps to a texture pixel
    through the dumped affine, then to the panel through the texture fit. Drawing the mesh in
    uv space instead would place it beside the image it samples.
    """
    panel = Image.new("RGB", (PANEL_SIZE, PANEL_SIZE), PANEL_BACKGROUND)
    draw = ImageDraw.Draw(panel)
    font = load_font(FONT_SIZE)
    if not variant.uvs:
        draw.text((PANEL_MARGIN, PANEL_MARGIN), "no uv data", fill=MUTED_TEXT, font=font)
        return panel
    if variant.texture is not None:
        texture = variant.texture.convert("RGBA")
        transform = texture_transform(texture)
        scaled = texture.resize(
            (max(1, round(texture.width * transform[0])), max(1, round(texture.height * transform[0]))),
            Image.NEAREST,
        )
        panel.paste(scaled, (round(transform[1]), round(transform[2])), scaled)
    else:
        transform = fit_transform([project(uv, affine) for uv in variant.uvs], PANEL_SIZE)
    draw_mesh(draw, variant, affine, transform, NATIVE_LINE if variant.is_native else MESH_LINE)
    return panel


def render_mesh(mesh_directory: Path, output: Path) -> Path:
    """Render one probed mesh and return the written PNG path."""
    variants = load_mesh(mesh_directory)
    if not variants:
        raise ValueError(f"{mesh_directory}: no dumped variant found")
    affine = uv_to_pixel(variants[0].meta)
    columns = len(variants)
    width = PANEL_SIZE * columns
    height = PANEL_SIZE + PANEL_TITLE_HEIGHT
    image = Image.new("RGB", (width, height), BACKGROUND)
    draw = ImageDraw.Draw(image)
    title_font = load_font(TITLE_FONT_SIZE)
    for column, variant in enumerate(variants):
        origin_x = column * PANEL_SIZE
        draw.text(
            (origin_x + PANEL_MARGIN, PANEL_MARGIN // 2),
            panel_title(variant),
            fill=TEXT_COLOR,
            font=title_font,
        )
        image.paste(render_panel(variant, affine), (origin_x, PANEL_TITLE_HEIGHT))
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, "PNG")
    return output


def load_font(size: int) -> "ImageFont.ImageFont":
    """Return a usable font; the bitmap fallback keeps the renderer dependency-free."""
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def mesh_directories(dump_root: Path) -> list[Path]:
    """Return every directory that carries at least one dumped variant."""
    found = []
    for child in sorted(dump_root.iterdir()):
        if child.is_dir() and any((grandchild / META_FILE).is_file() for grandchild in child.iterdir()):
            found.append(child)
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("dump", type=Path, help="dump directory the probe wrote")
    parser.add_argument("--output", type=Path, help="PNG output directory; defaults to <dump>/rendered")
    args = parser.parse_args(argv)
    if Image is None:
        print("error: Pillow is required; install it with 'python3 -m pip install Pillow'", file=sys.stderr)
        return 2
    dump_root = args.dump.resolve()
    if not dump_root.is_dir():
        print(f"error: dump directory not found: {dump_root}", file=sys.stderr)
        return 2
    output = (args.output or dump_root / "rendered").resolve()
    meshes = mesh_directories(dump_root)
    if not meshes:
        print(f"error: no dumped mesh found under {dump_root}", file=sys.stderr)
        return 2
    failures = 0
    for mesh_directory in meshes:
        try:
            written = render_mesh(mesh_directory, output / f"{mesh_directory.name}.png")
            print(f"rendered {written}")
        except (OSError, ValueError) as failure:
            print(f"error: {mesh_directory.name}: {failure}", file=sys.stderr)
            failures += 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
