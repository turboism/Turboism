#!/usr/bin/env python3
"""Reject a first-party mesh-toolbar icon that ships without its DPI scale variants.

`runtime/.../ToolbarIconLoader.scaleVariantPath` resolves `.scale-125`, `.scale-150`, `.scale-175` and
`.scale-200` beside every toolbar icon. Without those files the host enlarges the 32 pixel bitmap on
a scaled display, and the button becomes blurry.

The check is fail-closed and cheap: it reads only the plugin descriptors and the PNG headers, so it
needs no Pillow and no host. Every problem is reported at once, with the file and the reason.

Usage: check_toolbar_icon_scale_variants.py [repo-root]
"""
from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

MESH_TOOLBAR_PERMISSION = "turboism.ui.toolbar.mesh.contribute"
VARIANT_SCALES = {"125": 125, "150": 150, "175": 175, "200": 200}
DESCRIPTOR = Path("src/main/resources/META-INF/turboism/plugin.json")
ICONS = Path("src/main/resources/icons")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PNG_WIDTH_OFFSET = 16
PNG_HEIGHT_OFFSET = 20
PNG_HEADER_BYTES = 24


def png_size(path: Path) -> tuple[int, int] | None:
    """Return the raster pixel size, or None when the file is not a readable PNG."""
    try:
        with path.open("rb") as stream:
            header = stream.read(PNG_HEADER_BYTES)
    except OSError:
        return None
    if len(header) < PNG_HEADER_BYTES or not header.startswith(PNG_SIGNATURE):
        return None
    width, height = struct.unpack(">II", header[PNG_WIDTH_OFFSET:PNG_HEIGHT_OFFSET + 4])
    return width, height


def mesh_toolbar_plugins(root: Path) -> list[Path]:
    """Return the plugins that declare the mesh-toolbar contribution permission."""
    plugins = []
    for descriptor in sorted((root / "plugins").glob("*/" + DESCRIPTOR.as_posix())):
        try:
            meta = json.loads(descriptor.read_text(encoding="utf-8"))
        except (OSError, ValueError) as failure:
            raise SystemExit(f"FAIL: cannot read plugin descriptor {descriptor}: {failure}") from failure
        permissions = [entry.get("id") for entry in meta.get("permissions") or []]
        if MESH_TOOLBAR_PERMISSION in permissions:
            plugins.append(descriptor.parents[5])
    return plugins


def base_icons(icons: Path) -> list[Path]:
    """Return the committed base rasters, which are the icons the host loads by name."""
    return sorted(path for path in icons.glob("*.png") if ".scale-" not in path.name)


def missing_variants(icon: Path) -> list[str]:
    """Return one message per missing or wrong-sized scale variant of one base icon."""
    size = png_size(icon)
    if size is None:
        return [f"{icon}: not a readable PNG"]
    problems = []
    for suffix, percent in VARIANT_SCALES.items():
        variant = icon.with_name(f"{icon.stem}.scale-{suffix}{icon.suffix}")
        expected = (size[0] * percent // 100, size[1] * percent // 100)
        if not variant.is_file():
            problems.append(f"{variant}: missing scale variant for {percent}%")
            continue
        actual = png_size(variant)
        if actual != expected:
            problems.append(f"{variant}: expected {expected[0]}x{expected[1]} pixels for {percent}%, got {actual}")
    return problems


def main(argv: list[str]) -> int:
    root = Path(argv[1]).resolve() if len(argv) > 1 else Path(__file__).resolve().parents[2]
    problems: list[str] = []
    checked = 0
    for plugin in mesh_toolbar_plugins(root):
        icons = plugin / ICONS
        if not icons.is_dir():
            continue
        for icon in base_icons(icons):
            checked += 1
            problems.extend(missing_variants(icon))
    if problems:
        print("FAIL: mesh-toolbar icons must ship 40, 48, 56 and 64 pixel scale variants", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    print(f"PASS: {checked} mesh-toolbar icons ship every DPI scale variant")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
