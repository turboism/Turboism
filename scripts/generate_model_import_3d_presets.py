#!/usr/bin/env python3
"""Rebuild the plugin's small, textured GLB primitives using only the standard library."""

import json
import math
from pathlib import Path
import struct
import zlib


DESTINATION = (
    Path(__file__).resolve().parents[1]
    / "plugins/model-import-3d/src/main/resources/META-INF/turboism/model-import-3d/presets"
)


def checker_png():
    """256px UV grid; red U and blue V borders make orientation visible."""
    side = 256
    rows = bytearray()
    for y in range(side):
        rows.append(0)
        for x in range(side):
            value = 232 if (x // 32 + y // 32) % 2 == 0 else 168
            rgb = (value, value, value)
            if x % 32 < 2 or y % 32 < 2:
                rgb = (96, 96, 96)
            if x < 4:
                rgb = (220, 70, 60)
            if y >= side - 4:
                rgb = (60, 100, 220)
            rows.extend(rgb)

    def chunk(kind, payload):
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", zlib.crc32(kind + payload))
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", side, side, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows, 9))
        + chunk(b"IEND", b"")
    )


class Mesh:
    def __init__(self):
        self.positions = []
        self.normals = []
        self.uvs = []
        self.indices = []

    def vertex(self, position, normal, uv):
        index = len(self.positions)
        self.positions.append(position)
        self.normals.append(normal)
        self.uvs.append(uv)
        return index

    def triangle(self, a, b, c):
        p, q, r = (self.positions[i] for i in (a, b, c))
        u, v = [q[i] - p[i] for i in range(3)], [r[i] - p[i] for i in range(3)]
        cross = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        if sum(x * x for x in cross) < 1e-16:
            return
        normal = [sum(self.normals[j][i] for j in (a, b, c)) for i in range(3)]
        if sum(cross[i] * normal[i] for i in range(3)) < 0:
            b, c = c, b
        self.indices.extend((a, b, c))

    def grid(self, columns, rows, point):
        grid = []
        for j in range(rows + 1):
            line = []
            for i in range(columns + 1):
                u, v = i / columns, j / rows
                position, normal = point(u, v)
                line.append(self.vertex(position, normal, (u, v)))
            grid.append(line)
        for j in range(rows):
            for i in range(columns):
                a, b, c, d = grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i]
                self.triangle(a, b, c)
                self.triangle(a, c, d)

    def write(self, name, double_sided=False):
        binary = bytearray()
        views, accessors = [], []

        def append(payload, target=None):
            while len(binary) % 4:
                binary.append(0)
            view = {"buffer": 0, "byteOffset": len(binary), "byteLength": len(payload)}
            if target:
                view["target"] = target
            views.append(view)
            binary.extend(payload)
            return len(views) - 1

        for data, kind in ((self.positions, "VEC3"), (self.normals, "VEC3"), (self.uvs, "VEC2")):
            values = [value for vertex in data for value in vertex]
            view = append(struct.pack(f"<{len(values)}f", *values), 34962)
            accessor = {"bufferView": view, "componentType": 5126, "count": len(data), "type": kind}
            if data is self.positions:
                accessor["min"] = [min(p[i] for p in data) for i in range(3)]
                accessor["max"] = [max(p[i] for p in data) for i in range(3)]
            accessors.append(accessor)
        view = append(struct.pack(f"<{len(self.indices)}H", *self.indices), 34963)
        accessors.append({"bufferView": view, "componentType": 5123, "count": len(self.indices), "type": "SCALAR"})
        image_view = append(checker_png())
        doc = {
            "asset": {"version": "2.0", "generator": "Turboism primitive presets"},
            "scene": 0,
            "scenes": [{"nodes": [0]}],
            "nodes": [{"name": name, "mesh": 0}],
            "meshes": [{"name": name, "primitives": [{
                "attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}, "indices": 3, "material": 0,
            }]}],
            "materials": [{"name": "Replaceable UV grid", "doubleSided": double_sided,
                           "pbrMetallicRoughness": {"baseColorTexture": {"index": 0},
                                                    "metallicFactor": 0, "roughnessFactor": 1}}],
            "textures": [{"source": 0, "sampler": 0}],
            "samplers": [{"wrapS": 33071, "wrapT": 33071}],
            "images": [{"bufferView": image_view, "mimeType": "image/png", "name": "UV grid 256"}],
            "buffers": [{"byteLength": len(binary)}], "bufferViews": views, "accessors": accessors,
        }
        text = json.dumps(doc, separators=(",", ":")).encode()
        text += b" " * (-len(text) % 4)
        binary += b"\0" * (-len(binary) % 4)
        glb = struct.pack("<5I", 0x46546C67, 2, 28 + len(text) + len(binary), len(text), 0x4E4F534A)
        glb += text + struct.pack("<2I", len(binary), 0x004E4942) + binary
        (DESTINATION / f"{name}.glb").write_bytes(glb)
        print(f"{name}: {len(glb)} bytes, {len(self.indices) // 3} triangles")


def generate():
    DESTINATION.mkdir(parents=True, exist_ok=True)
    cube = Mesh()
    for axis in range(3):
        for sign in (-1, 1):
            def face(u, v, axis=axis, sign=sign):
                p, n = [0.0] * 3, [0.0] * 3
                p[axis], n[axis] = sign, sign
                p[(axis + 1) % 3], p[(axis + 2) % 3] = 2 * u - 1, 2 * v - 1
                return p, n
            first = len(cube.uvs)
            cube.grid(1, 1, face)
            tile = axis * 2 + (sign == 1)
            for i in range(first, len(cube.uvs)):
                u, v = cube.uvs[i]
                cube.uvs[i] = ((tile % 3 + u) / 3, (tile // 3 + v) / 2)
    cube.write("cube")

    tetrahedron = Mesh()
    radius = math.sqrt(8 / 9)
    corners = [(0, 1, 0)] + [
        (radius * math.cos(math.tau * i / 3), -1 / 3, radius * math.sin(math.tau * i / 3))
        for i in range(3)
    ]
    face_uvs = ((0.5, 1), (0, 1 - math.sqrt(3) / 2), (1, 1 - math.sqrt(3) / 2))
    for tile, face in enumerate(((0, 1, 2), (0, 2, 3), (0, 3, 1), (1, 3, 2))):
        center = [sum(corners[j][axis] for j in face) / 3 for axis in range(3)]
        length = math.sqrt(sum(value * value for value in center))
        normal = [value / length for value in center]
        slots = []
        for corner, (u, v) in zip(face, face_uvs):
            slots.append(tetrahedron.vertex(corners[corner], normal, ((tile % 2 + u) / 2, (tile // 2 + v) / 2)))
        tetrahedron.triangle(*slots)
    tetrahedron.write("tetrahedron")

    triangle = Mesh()
    for x, y in ((-math.sqrt(3) / 2, -0.5), (math.sqrt(3) / 2, -0.5), (0, 1)):
        triangle.vertex((x, y, 0), (0, 0, 1), ((x + 1) / 2, (y + 1) / 2))
    triangle.triangle(0, 1, 2)
    triangle.write("triangle", double_sided=True)

    square = Mesh()
    square.grid(1, 1, lambda u, v: ((2 * u - 1, 2 * v - 1, 0), (0, 0, 1)))
    square.write("square", double_sided=True)

    cylinder = Mesh()
    cylinder.grid(16, 1, lambda u, v: (
        (math.cos(math.tau * u), 2 * v - 1, math.sin(math.tau * u)),
        (math.cos(math.tau * u), 0, math.sin(math.tau * u)),
    ))
    cylinder.uvs = [(u, v / 2) for u, v in cylinder.uvs]
    for sign in (-1, 1):
        center_u = 0.25 if sign == -1 else 0.75
        center = cylinder.vertex((0, sign, 0), (0, sign, 0), (center_u, 0.75))
        edge = []
        for i in range(16):
            x, z = math.cos(math.tau * i / 16), math.sin(math.tau * i / 16)
            edge.append(cylinder.vertex((x, sign, z), (0, sign, 0), (center_u + x / 4, 0.75 + z / 4)))
        for i in range(16):
            cylinder.triangle(center, edge[i], edge[(i + 1) % 16])
    cylinder.write("cylinder")

    sphere = Mesh()

    def globe(u, v):
        latitude, longitude = math.pi * v, math.tau * u
        p = (math.sin(latitude) * math.cos(longitude), math.cos(latitude), math.sin(latitude) * math.sin(longitude))
        return p, p

    sphere.grid(16, 8, globe)
    sphere.write("sphere")

    annulus = Mesh()
    for i in range(32):
        indices = []
        for radius, angle in ((0.5, i), (1.0, i), (1.0, i + 1), (0.5, i + 1)):
            x, y = radius * math.cos(math.tau * angle / 32), radius * math.sin(math.tau * angle / 32)
            indices.append(annulus.vertex((x, y, 0), (0, 0, 1), ((x + 1) / 2, (y + 1) / 2)))
        a, b, c, d = indices
        annulus.triangle(a, b, c)
        annulus.triangle(a, c, d)
    annulus.write("annulus", double_sided=True)

    circle = Mesh()
    center = circle.vertex((0, 0, 0), (0, 0, 1), (0.5, 0.5))
    edge = []
    for i in range(32):
        x, y = math.cos(math.tau * i / 32), math.sin(math.tau * i / 32)
        edge.append(circle.vertex((x, y, 0), (0, 0, 1), ((x + 1) / 2, (y + 1) / 2)))
    for i in range(32):
        circle.triangle(center, edge[i], edge[(i + 1) % 32])
    circle.write("circle", double_sided=True)

    inward_ring = Mesh()
    inward_ring.grid(32, 1, lambda u, v: (
        (math.cos(math.tau * u), 0.3 * (v - 0.5), math.sin(math.tau * u)),
        (-math.cos(math.tau * u), 0, -math.sin(math.tau * u)),
    ))
    inward_ring.write("inward-ring", double_sided=True)

    torus = Mesh()

    def tube(u, v):
        a, b = math.tau * u, math.tau * v
        radius = 1 + 0.3 * math.cos(b)
        return ((radius * math.cos(a), radius * math.sin(a), 0.3 * math.sin(b)),
                (math.cos(b) * math.cos(a), math.cos(b) * math.sin(a), math.sin(b)))

    torus.grid(16, 8, tube)
    torus.write("torus")


if __name__ == "__main__":
    generate()
