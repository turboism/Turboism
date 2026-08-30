#!/usr/bin/env python3
"""Checks en/zh-CN/ja installer and plugin metadata localization parity."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
LANGPACK = ROOT / "packaging/java-installer/CustomLangPack.xml"
variants = [LANGPACK, Path(str(LANGPACK) + "_eng"), Path(str(LANGPACK) + "_chn"), Path(str(LANGPACK) + "_jpn")]
key_sets = []
for path in variants:
    root = ET.parse(path).getroot()
    keys = [entry.attrib["id"] for entry in root if entry.tag.endswith("str")]
    if len(keys) != len(set(keys)):
        raise SystemExit(f"duplicate installer localization key: {path}")
    key_sets.append(set(keys))
if any(keys != key_sets[0] for keys in key_sets[1:]):
    raise SystemExit("installer CustomLangPack locale keys are not in parity")

manifest = ROOT / "packaging/release-plugins.txt"
modules = [line.rsplit(":", 1)[-1] for line in manifest.read_text().splitlines() if line != ":plugins:core"]
for module in modules:
    base = ROOT / "plugins" / module / "src/main/resources/META-INF/turboism/i18n"
    locale_keys = []
    for suffix in ("en", "zh_Hans", "ja"):
        path = base / f"messages_{suffix}.properties"
        values = {}
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line or line.startswith(("#", "!")) or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
        for key in ("plugin.name", "plugin.description"):
            if not values.get(key):
                raise SystemExit(f"{module}: {path.name} missing {key}")
        locale_keys.append(set(values))
    common = {"plugin.name", "plugin.description"}
    if any(not common.issubset(keys) for keys in locale_keys):
        raise SystemExit(f"{module}: localized installer metadata is not in parity")
print("installer localization parity PASS")
