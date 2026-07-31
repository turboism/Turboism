#!/usr/bin/env python3
"""Build one strict Turboism .tplugin from a project-produced plugin JAR."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import zipfile
from datetime import datetime, timezone
from pathlib import Path

DESCRIPTOR = "META-INF/turboism/plugin.json"
MANIFEST = "META-INF/turboism/package.json"
PLUGIN_JAR = "plugin/plugin.jar"


def canonical(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def timestamp() -> str:
    epoch = os.environ.get("SOURCE_DATE_EPOCH")
    moment = datetime.fromtimestamp(int(epoch), timezone.utc) if epoch else datetime.now(timezone.utc)
    return moment.isoformat(timespec="seconds").replace("+00:00", "Z")


def entry(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    return info


def package(plugin_jar: Path, output: Path) -> None:
    jar = plugin_jar.read_bytes()
    with zipfile.ZipFile(plugin_jar) as archive:
        descriptor_bytes = archive.read(DESCRIPTOR)
    descriptor = json.loads(descriptor_bytes)
    manifest = {
        "createdAt": timestamp(),
        "files": [{"path": PLUGIN_JAR, "role": "PLUGIN_JAR", "sha256": sha256(jar), "size": len(jar)}],
        "format": "turboism.distribution.plugin-package",
        "packageId": descriptor["id"],
        "packageKind": "PLUGIN",
        "pluginDescriptorPath": f"{PLUGIN_JAR}!/{DESCRIPTOR}",
        "pluginDescriptorSha256": sha256(descriptor_bytes),
        "schemaVersion": 1,
        "version": descriptor["version"],
    }
    manifest["packageHash"] = sha256(canonical(manifest))
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    with zipfile.ZipFile(temporary, "w") as archive:
        archive.writestr(entry(MANIFEST), canonical(manifest))
        archive.writestr(entry(PLUGIN_JAR), jar)
    temporary.replace(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("plugin_jar", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    package(args.plugin_jar, args.output)


if __name__ == "__main__":
    main()
