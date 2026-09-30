#!/usr/bin/env python3
"""Read-only supplement for F5 saved CMO main.xml, extracted by official ArchiveReader.

No archive writer or host operation is provided. This observes serialized input affine and
clipping values; it does not establish live Undo values or independently declare F5 passed.
"""
from __future__ import annotations

import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET


class InvalidStructure(ValueError):
    pass


def unique(mapping, key, value):
    if key in mapping:
        raise InvalidStructure("duplicate identity: " + str(key))
    mapping[key] = value


class Document:
    def __init__(self, path):
        self.root = ET.parse(path).getroot()
        self.ids = {}
        for node in self.root.iter():
            if "xs.id" in node.attrib:
                unique(self.ids, node.attrib["xs.id"], node)

    def resolve(self, node):
        seen = set()
        while "xs.ref" in node.attrib:
            key = node.attrib["xs.ref"]
            if key in seen or key not in self.ids:
                raise InvalidStructure("cyclic or missing XML reference: " + key)
            seen.add(key)
            resolved = self.ids[key]
            if node.tag != resolved.tag:
                raise InvalidStructure("reference tag changed")
            node = resolved
        return node

    def field(self, node, name):
        node = self.resolve(node)
        seen = set()
        while id(node) not in seen:
            seen.add(id(node))
            fields = [child for child in node if child.get("xs.n") == name]
            if len(fields) > 1:
                raise InvalidStructure("duplicate field: " + name)
            if fields:
                return self.resolve(fields[0])
            supers = [child for child in node if child.get("xs.n") == "super"]
            if len(supers) != 1:
                break
            node = self.resolve(supers[0])
        raise InvalidStructure("missing field: " + name)

    def guid(self, node):
        value = self.resolve(node).get("uuid")
        if not value:
            raise InvalidStructure("missing GUID")
        return value

    def sequence(self, node):
        node = self.resolve(node)
        if node.get("count") != str(len(node)):
            raise InvalidStructure("collection count does not match children")
        return [self.resolve(child) for child in node]

    def definitions(self, tag):
        return [node for node in self.root.iter(tag) if "xs.ref" not in node.attrib]

    def raw_layers(self, baseline_raws):
        raws, layers = {}, {}
        for raw in self.definitions("CLayeredImage"):
            guid = self.guid(self.field(raw, "guid"))
            unique(raws, guid, "original:" + guid if guid in baseline_raws else "incoming")
            root = self.field(raw, "_rootLayer")

            def walk(node, path):
                if node.tag not in ("CLayer", "CLayerGroup"):
                    raise InvalidStructure("unknown raw layer kind")
                layer_guid = self.guid(self.field(node, "guid"))
                if self.field(node, "_layeredImage") is not raw:
                    raise InvalidStructure("raw layer owner differs")
                unique(layers, (guid, layer_guid), path)
                if node.tag == "CLayerGroup":
                    for index, child in enumerate(self.sequence(self.field(node, "_children"))):
                        walk(child, path + "/" + str(index))

            walk(root, "root")
        if sum(guid not in baseline_raws for guid in raws) > 1:
            raise InvalidStructure("multiple incoming raw identities")
        return raws, layers

    def image_ids(self):
        values = [self.guid(self.field(image, "guid")) for image in self.definitions("CModelImage")]
        if len(values) != len(set(values)):
            raise InvalidStructure("duplicate model image")
        return set(values)


def affine(node):
    keys = ("m00", "m01", "m02", "m10", "m11", "m12")
    if node.tag != "CAffine" or any(key not in node.attrib for key in keys):
        raise InvalidStructure("affine is not a six-component CAffine")
    values = [float(node.attrib[key]) for key in keys]
    if not all(math.isfinite(value) for value in values):
        raise InvalidStructure("non-finite affine")
    return [value.hex() for value in values]


def clipping(node):
    # Exact-host CLayerInputData stores AClip (including ClipByMesh), not CRect.
    # Only null has verified serialized evidence. Never infer a mesh's XML
    # representation from the separate live field reader.
    if node.tag == "null":
        if len(node) or (node.text or "").strip():
            raise InvalidStructure("malformed null clipping")
        return None
    raise InvalidStructure("unverified serialized clipping type: " + node.tag)


def saved_input_details(baseline_xml, saved_xml):
    baseline, saved = Document(baseline_xml), Document(saved_xml)
    baseline_raws = {baseline.guid(baseline.field(raw, "guid"))
                     for raw in baseline.definitions("CLayeredImage")}
    baseline_images = baseline.image_ids()
    raw_roles, layers = saved.raw_layers(baseline_raws)
    saved.image_ids()  # Reject duplicate physical ModelImage definitions.
    images = {}
    for image in saved.definitions("CModelImage"):
        image_guid = saved.guid(saved.field(image, "guid"))
        env = saved.field(saved.field(image, "inputFilterEnv"), "envValues")
        values = {}
        for entry in saved.sequence(env):
            key = saved.field(entry, "key").get("idstr")
            if not key:
                raise InvalidStructure("FilterValueId is missing idstr")
            unique(values, key, saved.field(saved.field(entry, "value"), "value"))
        if "mi_input_layerInputData" not in values or "mi_currentImageGuid" not in values:
            raise InvalidStructure("model image input environment incomplete")
        selector = values["mi_input_layerInputData"]
        if selector.tag != "CLayerSelectorMap":
            raise InvalidStructure("unexpected selector type")
        bindings = {}
        for entry in saved.sequence(saved.field(selector, "_imageToLayerInput")):
            raw_guid = saved.guid(saved.field(entry, "key"))
            if raw_guid not in raw_roles:
                raise InvalidStructure("selector references missing raw")
            records = []
            for data in saved.sequence(saved.field(entry, "value")):
                if data.tag != "CLayerInputData":
                    raise InvalidStructure("unknown layer input record")
                layer_guid = saved.guid(saved.field(saved.field(data, "layer"), "guid"))
                if (raw_guid, layer_guid) not in layers:
                    raise InvalidStructure("input layer is outside its bound raw")
                records.append(dict(layerPath=layers[raw_guid, layer_guid],
                    originalLayerGuid=layer_guid if raw_guid in baseline_raws else None,
                    affine=affine(saved.field(data, "affine")),
                    clipping=clipping(saved.field(data, "clippingOnTexturePx"))))
            unique(bindings, raw_roles[raw_guid], records)
        current = values["mi_currentImageGuid"]
        if current.tag == "null":
            current_role = None
        else:
            current_guid = saved.guid(current)
            if current_guid not in raw_roles:
                raise InvalidStructure("current raw is missing")
            current_role = raw_roles[current_guid]
        key = "original:" + image_guid if image_guid in baseline_images else "new:" + json.dumps(bindings, sort_keys=True)
        unique(images, key, dict(bindings=bindings, currentRaw=current_role,
            materialToCanvas=affine(saved.field(image, "_materialLocalToCanvasTransform"))))
    if not images:
        raise InvalidStructure("no serialized model images observed")
    return dict(scope="saved raw input affine/clipping and material-to-canvas transform",
                liveUndoInputDetails="NOT_OBSERVED", images=images)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline_xml", type=Path)
    parser.add_argument("saved_xml", type=Path)
    args = parser.parse_args()
    print(json.dumps(saved_input_details(args.baseline_xml, args.saved_xml), indent=2, sort_keys=True))
