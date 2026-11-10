#!/usr/bin/env python3
"""Contract tests for the offline Auto Mesh dump renderer.

The renderer is the only consumer of the probe's dump layout, so these tests pin the
layout itself: one directory per probed mesh, one subdirectory per variant, and the
texture raster the probe writes. They run without a Cubism host and without the probe.
"""
from __future__ import annotations

import struct
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "preview"))

try:
    import render_automesh_dump as renderer
except ImportError:  # pragma: no cover - Pillow is a repository dependency
    renderer = None

TEXTURE_WIDTH = 32
TEXTURE_HEIGHT = 32
OPAQUE_PIXEL = 0xFF000000 | (0x40 << 16) | (0x90 << 8) | 0xC0
TRANSPARENT_PIXEL = 0x00000000
SQUARE_UVS = [(0.25, 0.25), (0.75, 0.25), (0.75, 0.75), (0.25, 0.75)]
SQUARE_INDICES = [0, 1, 2, 0, 2, 3]


def write_variant(directory: Path, name: str, uvs, indices, with_texture: bool = True) -> None:
    """Write one dumped variant in the probe's layout."""
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "positions.txt").write_text(
        "".join(f"{u},{v}\n" for u, v in uvs), encoding="utf-8"
    )
    (directory / "uvs.txt").write_text("".join(f"{u},{v}\n" for u, v in uvs), encoding="utf-8")
    (directory / "indices.txt").write_text("".join(f"{index}\n" for index in indices), encoding="utf-8")
    meta = [
        "id=ArtMesh342",
        f"variant={name}",
        f"vertices={len(uvs)}",
        f"triangles={len(indices) // 3}",
        "digest=0123456789abcdef",
        "u0=0.25",
        "v0=0.25",
        "u1=0.75",
        "v1=0.75",
    ]
    if with_texture:
        meta.extend([
            f"texture.width={TEXTURE_WIDTH}",
            f"texture.height={TEXTURE_HEIGHT}",
            f"texture.uv2px.0={float(TEXTURE_WIDTH)}",
            "texture.uv2px.1=0.0",
            "texture.uv2px.2=0.0",
            "texture.uv2px.3=0.0",
            f"texture.uv2px.4={float(TEXTURE_HEIGHT)}",
            "texture.uv2px.5=0.0",
        ])
    (directory / "meta.properties").write_text("\n".join(meta) + "\n", encoding="utf-8")
    if not with_texture:
        return
    with (directory / "texture.bin").open("wb") as stream:
        stream.write(struct.pack(">ii", TEXTURE_WIDTH, TEXTURE_HEIGHT))
        for y in range(TEXTURE_HEIGHT):
            for x in range(TEXTURE_WIDTH):
                inside = TEXTURE_WIDTH // 4 <= x < TEXTURE_WIDTH * 3 // 4
                inside = inside and TEXTURE_HEIGHT // 4 <= y < TEXTURE_HEIGHT * 3 // 4
                stream.write(struct.pack(">I", OPAQUE_PIXEL if inside else TRANSPARENT_PIXEL))


@unittest.skipIf(renderer is None, "Pillow is unavailable")
class RendererTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.mesh = self.root / "ArtMesh342"
        write_variant(self.mesh / "native-run0", "native-run0", SQUARE_UVS, SQUARE_INDICES)
        write_variant(self.mesh / "native-run1", "native-run1", SQUARE_UVS, SQUARE_INDICES)
        write_variant(
            self.mesh / "turbo-mesh.region",
            "turbo-mesh.region",
            SQUARE_UVS + [(0.5, 0.5)],
            [0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4],
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_load_mesh_orders_native_baselines_first(self) -> None:
        variants = renderer.load_mesh(self.mesh)
        self.assertEqual(
            ["native-run0", "native-run1", "turbo-mesh.region"],
            [variant.name for variant in variants],
        )
        self.assertEqual(2, variants[0].triangles)
        self.assertEqual(4, variants[2].triangles)

    def test_texture_raster_round_trips_through_the_dump(self) -> None:
        variant = renderer.load_variant(self.mesh / "native-run0")
        self.assertIsNotNone(variant.texture)
        self.assertEqual((TEXTURE_WIDTH, TEXTURE_HEIGHT), variant.texture.size)
        self.assertEqual(0, variant.texture.getpixel((0, 0))[3])

    def test_uv_to_pixel_affine_is_read_from_the_dump(self) -> None:
        variant = renderer.load_variant(self.mesh / "native-run0")
        affine = renderer.uv_to_pixel(variant.meta)
        self.assertEqual((16.0, 16.0), renderer.project((0.5, 0.5), affine))

    def test_render_mesh_writes_one_png_with_a_panel_per_variant(self) -> None:
        output = renderer.render_mesh(self.mesh, self.root / "rendered" / "ArtMesh342.png")
        self.assertTrue(output.is_file())
        with renderer.Image.open(output) as image:
            self.assertEqual(renderer.PANEL_SIZE * 3, image.width)
            self.assertEqual(renderer.PANEL_SIZE + renderer.PANEL_TITLE_HEIGHT, image.height)

    def test_render_mesh_without_texture_still_draws_the_mesh(self) -> None:
        bare = self.root / "hfa10"
        write_variant(bare / "turbo-mesh.adaptive", "turbo-mesh.adaptive", SQUARE_UVS, SQUARE_INDICES, False)
        output = renderer.render_mesh(bare, self.root / "rendered" / "hfa10.png")
        self.assertTrue(output.is_file())

    def test_main_reports_an_empty_dump_without_writing_an_image(self) -> None:
        empty = self.root / "empty"
        empty.mkdir()
        self.assertEqual(2, renderer.main([str(empty)]))

    def test_main_renders_every_probed_mesh(self) -> None:
        self.assertEqual(0, renderer.main([str(self.root)]))
        self.assertTrue((self.root / "rendered" / "ArtMesh342.png").is_file())


if __name__ == "__main__":
    unittest.main()
