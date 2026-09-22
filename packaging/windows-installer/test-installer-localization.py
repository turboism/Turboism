#!/usr/bin/env python3
"""Checks en/zh-CN/ja/ko installer and plugin metadata localization parity."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
LANGPACK = ROOT / "packaging/java-installer/CustomLangPack.xml"
variants = [LANGPACK, Path(str(LANGPACK) + "_eng"), Path(str(LANGPACK) + "_chn"),
            Path(str(LANGPACK) + "_jpn"), Path(str(LANGPACK) + "_kor")]
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
    for suffix in ("en", "zh_Hans", "ja", "ko"):
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

# 卸载器的自绘 Swing 确认框是手写 Locale 分支，不走 IzPack langpack，因此不被上面
# 的 CustomLangPack parity 覆盖；而验证器只以 `-console` 运行卸载器，从不执行该 GUI
# 路径。韩语此前缺失，韩语环境会静默回退到英文文案——故此处直接对源码断言。
uninstaller = (ROOT / "packaging/java-installer/listener-src/dev/turboism/installer"
                        / "TurboismUninstallerListener.java").read_text(encoding="utf-8")
dialog = uninstaller[uninstaller.index("static boolean resolveDeleteConfig()"):
                    uninstaller.index("private static boolean isConsoleRun()")]
tags = re.findall(r'lang\.startsWith\("(\w+)"\)', dialog)
expected = {"zh", "ja", "ko"}  # 英文是 base 文案，不以分支形式出现
if set(tags) != expected:
    raise SystemExit(f"uninstaller confirmation locales {sorted(set(tags))} != {sorted(expected)}")
for tag in tags:
    start = dialog.index('lang.startsWith("%s")' % tag)
    nxt = dialog.find('lang.startsWith("', start + 1)
    body = dialog[start:nxt if nxt != -1 else len(dialog)]
    if "message =" not in body or "title =" not in body:
        raise SystemExit(f"uninstaller confirmation locale {tag} must set message and title")

readme = (ROOT / "packaging/java-installer/README-java-installer.md").read_text(encoding="utf-8")
if "en/zh/ja/ko confirmation" not in readme:
    raise SystemExit("README-java-installer.md must document all four uninstaller locales")

# EXE 与 JAR 卸载器对 config.json 的默认处置必须一致：都默认保留。验证器只以
# `-console` 加显式属性运行卸载器，因此“无属性”的默认路径从不被执行——这里直接守卫。
if "return true; // default: delete" in uninstaller:
    raise SystemExit("uninstaller must not default to deleting config.json")
if "return false; // default: keep config.json" not in uninstaller:
    raise SystemExit("uninstaller must default to keeping config.json")
if "new Object[]{deleteButton, keepButton}, keepButton" not in dialog:
    raise SystemExit("uninstaller confirmation must default to the keep (No) button")
if "default keep" not in readme:
    raise SystemExit("README-java-installer.md must document the keep-by-default behavior")
print("installer localization parity PASS")
