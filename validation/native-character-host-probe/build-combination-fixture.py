#!/usr/bin/env python3
"""Build a small explicit-control GLB. No private character assets are used."""
import argparse
import hashlib
import json
import struct
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
args = parser.parse_args()
positions = [-1, -.5, 0, 1, -.5, 0, 0, .5, 0]
shift_x = [.12, 0, 0] * 3
shift_y = [0, .12, 0] * 3
binary = struct.pack("<27f", *(positions + shift_x + shift_y))
controls = [
    {"id": key, "name": key, "minimum": 0, "maximum": 1}
    for key in ["Shift X", "Shift Y", "Factor A", "Factor B"]
]
root = {
    "asset": {"version": "2.0"},
    "scene": 0, "scenes": [{"nodes": [0]}],
    "extras": {"turboismControls": controls},
    "nodes": [{"mesh": 0, "name": "Source object", "extras": {
        "turboismOpacityControls": [
            {"parameter": "Factor A", "base": 1, "target": .5},
            {"parameter": "Factor B", "base": 1, "target": .25},
        ]}}],
    "buffers": [{"byteLength": len(binary)}],
    "bufferViews": [{"buffer": 0, "byteOffset": i * 36, "byteLength": 36} for i in range(3)],
    "accessors": [{"bufferView": i, "componentType": 5126, "count": 3, "type": "VEC3"} for i in range(3)],
    "extensionsUsed": ["KHR_materials_unlit"],
    "materials": [{"name": "Flat source", "doubleSided": True,
        "extensions": {"KHR_materials_unlit": {}},
        "pbrMetallicRoughness": {"baseColorFactor": [.9, .3, .2, 1]}}],
    "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "material": 0,
        "extras": {"turboismControlSamples": [
            {"parameter": "Shift X", "key": 1, "deltas": 1},
            {"parameter": "Shift Y", "key": 1, "deltas": 2},
        ]}}]}],
}
js = json.dumps(root, separators=(",", ":")).encode()
js += b" " * (-len(js) % 4)
output = (struct.pack("<III", 0x46546C67, 2, 28 + len(js) + len(binary))
          + struct.pack("<II", len(js), 0x4E4F534A) + js
          + struct.pack("<II", len(binary), 0x004E4942) + binary)
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_bytes(output)
print(args.output, len(output), hashlib.sha256(output).hexdigest())
