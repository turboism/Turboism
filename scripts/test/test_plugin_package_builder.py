#!/usr/bin/env python3
from __future__ import annotations

import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BUILDER = REPO / "scripts/release/package-plugin.py"


def flags(path: Path) -> list[tuple[bytes, int]]:
    data = path.read_bytes()
    result = []
    cursor = 0
    while (cursor := data.find(b"PK\x01\x02", cursor)) >= 0:
        flag = struct.unpack_from("<H", data, cursor + 8)[0]
        length = struct.unpack_from("<H", data, cursor + 28)[0]
        result.append((data[cursor + 46:cursor + 46 + length], flag))
        cursor += 1
    return result


def main() -> None:
    with tempfile.TemporaryDirectory() as raw:
        root = Path(raw)
        jar = root / "plugin.jar"
        descriptor = b'{"format":"turboism.plugin.meta","schemaVersion":2,"id":"example.plugin","name":"Example","version":"1.0.0","entrypoints":["example.Plugin"],"turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Test"}],"website":"https://example.test","resources":[],"i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]}}'
        readmes = {
            "META-INF/turboism/readme/README.md": "# Example\n",
            "META-INF/turboism/readme/README_zh.md": "# 示例\n",
            "META-INF/turboism/readme/README_ja.md": "# サンプル\n",
        }
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/turboism/plugin.json", descriptor)
            archive.writestr("example/Plugin.class", b"class")
            for name, content in readmes.items():
                archive.writestr(name, content.encode("utf-8"))
        jar_bytes = jar.read_bytes()
        package = root / "plugin.tplugin"
        subprocess.run([sys.executable, str(BUILDER), str(jar), str(package)], check=True,
                       env={"SOURCE_DATE_EPOCH": "1785456000"})
        entries = flags(package)
        assert [name for name, _ in entries] == [
            b"META-INF/turboism/package.json", b"plugin/plugin.jar"
        ], entries
        assert all(flag & ~0x0808 == 0 for _, flag in entries), entries
        assert all(all(byte < 128 for byte in name) or flag & 0x0800 for name, flag in entries), entries
        with zipfile.ZipFile(package) as outer:
            packaged_jar = outer.read("plugin/plugin.jar")
        assert packaged_jar == jar_bytes
        with zipfile.ZipFile(__import__("io").BytesIO(packaged_jar)) as inner:
            for name, content in readmes.items():
                assert inner.read(name) == content.encode("utf-8")


if __name__ == "__main__":
    main()
