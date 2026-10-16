#!/usr/bin/env python3
"""Write an explicit, asset-free fixture for native interpolation seam regression."""
import argparse
import json
import struct
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    binary = bytearray()
    views, accessors, meshes = [], [], []

    def accessor(values, kind, count, component=5126, bounds=None):
        offset = len(binary)
        binary.extend(struct.pack("<" + ("f" if component == 5126 else "I") * len(values), *values))
        views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(binary) - offset})
        item = {"bufferView": len(views) - 1, "componentType": component, "count": count, "type": kind}
        if bounds is not None:
            item.update({"min": bounds[0], "max": bounds[1]})
        accessors.append(item)
        return len(accessors) - 1

    triangles = [
        [(0.2, 0, 0.1), (0.1, 0.05, 0.1), (0.2, 0.05, 0.1)],
        [(0.2, 0, 0.1), (-1, -1, -1), (1, 1, 1)],
    ]
    uv = accessor([0, 0, 1, 0, 0, 1], "VEC2", 3)
    indices = accessor([0, 1, 2], "SCALAR", 3, 5125)
    names = ["Near pivot", "Outer shell"]
    for name, points in zip(names, triangles):
        bounds = ([min(p[a] for p in points) for a in range(3)],
                  [max(p[a] for p in points) for a in range(3)])
        position = accessor([v for p in points for v in p], "VEC3", 3, bounds=bounds)
        meshes.append({"name": name, "primitives": [{
            "attributes": {"POSITION": position, "TEXCOORD_0": uv}, "indices": indices,
            "material": 0, "extras": {"bodyPart": "Shared diagnostic frame"},
        }]})
    control = "Diagnostic visibility"
    document = {
        "asset": {"version": "2.0"}, "scene": 0, "scenes": [{"nodes": [0, 1]}],
        "extras": {"turboismControls": [{"id": control, "name": control, "minimum": 0, "maximum": 1}]},
        "nodes": [{"mesh": i, "name": name, "extras": {"turboismOpacityControls": [
            {"parameter": control, "base": 1, "target": 0.5}]}} for i, name in enumerate(names)],
        "meshes": meshes,
        "materials": [{"name": "Opaque", "doubleSided": True,
                       "pbrMetallicRoughness": {"baseColorFactor": [1, 0.5, 0.5, 1]}}],
        "buffers": [{"byteLength": len(binary)}], "bufferViews": views, "accessors": accessors,
    }
    encoded = json.dumps(document, separators=(",", ":")).encode()
    encoded += b" " * (-len(encoded) % 4)
    args.output.write_bytes(
        struct.pack("<III", 0x46546C67, 2, 28 + len(encoded) + len(binary))
        + struct.pack("<II", len(encoded), 0x4E4F534A) + encoded
        + struct.pack("<II", len(binary), 0x004E4942) + binary)


if __name__ == "__main__":
    main()
