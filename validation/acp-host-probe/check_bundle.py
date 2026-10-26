#!/usr/bin/env python3
"""Fail packaging before launch if a staged probe/production dependency cannot resolve."""
import json
import re
import sys
import zipfile
from pathlib import Path


def version(value):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value):
        raise ValueError("Expected numeric plugin version")
    return tuple(map(int, value.split(".")))


def check(root):
    manifests = {}
    for name in ("mcp.jar", "acp.jar", "acp-host-validation-probe.jar"):
        with zipfile.ZipFile(root / name) as jar:
            data = json.loads(jar.read("META-INF/turboism/plugin.json"))
        if data["id"] in manifests:
            raise ValueError("Duplicate plugin identity")
        manifests[data["id"]] = data
    required = {"dev.turboism.plugin.mcp", "dev.turboism.plugin.acp", "dev.turboism.validation.acp"}
    if set(manifests) != required:
        raise ValueError("Unexpected staged plugin identities")
    for data in manifests.values():
        for dependency in data.get("dependencies", []):
            if dependency.get("type") != "required":
                continue
            target = manifests.get(dependency["id"])
            if target is None:
                raise ValueError("Missing required staged plugin")
            bounds = re.fullmatch(r"\[([0-9.]+),([0-9.]+)\)", dependency["version"])
            if not bounds:
                raise ValueError("Unsupported dependency range; review the bundle validator")
            if not version(bounds[1]) <= version(target["version"]) < version(bounds[2]):
                raise ValueError("Staged dependency version incompatible: " + dependency["id"])
    return len(manifests)


if __name__ == "__main__":
    try:
        print(f"ACP staged dependencies: {check(Path(sys.argv[1]))} plugin manifests verified")
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error))
