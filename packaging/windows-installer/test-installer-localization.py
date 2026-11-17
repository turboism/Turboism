#!/usr/bin/env python3
"""Checks en/zh-CN/ja/ko installer and plugin metadata localization parity."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
# The native folder import flow must cover the installer's four selectable languages.
nsis = (ROOT / "packaging/windows-installer/installer.nsi").read_text(encoding="utf-8-sig")
import_keys = {}
for key, language in re.findall(r'^LangString (CubismImport\w+) \$\{LANG_(\w+)\}', nsis, re.M):
    if language in import_keys.setdefault(key, set()):
        raise SystemExit(f"duplicate native import localization: {key} {language}")
    import_keys[key].add(language)
expected_languages = {"ENGLISH", "SIMPCHINESE", "JAPANESE", "KOREAN"}
if len(import_keys) != 5 or any(languages != expected_languages for languages in import_keys.values()):
    raise SystemExit("native Cubism import localization keys are not in parity")

plugin_page_keys = {}
for key, language in re.findall(r'^LangString (Plugin(?:Selection|SelectAll)\w+) \$\{LANG_(\w+)\}', nsis, re.M):
    if language in plugin_page_keys.setdefault(key, set()):
        raise SystemExit(f"duplicate plugin selection page localization: {key} {language}")
    plugin_page_keys[key].add(language)
if len(plugin_page_keys) != 4 or any(languages != expected_languages for languages in plugin_page_keys.values()):
    raise SystemExit("plugin selection page localization keys are not in parity")

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
modules = [line.rsplit(":", 1)[-1] for line in manifest.read_text().splitlines()]
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

# The self-drawn Swing confirmation of the uninstaller branches on Locale by hand and never reads
# an IzPack langpack, so the CustomLangPack parity above does not cover it. The verifier also runs
# the uninstaller with `-console` only and never executes that GUI path. Korean was missing before
# and a Korean environment silently fell back to English text, so this check asserts the source.
uninstaller = (ROOT / "packaging/java-installer/listener-src/dev/turboism/installer"
                        / "TurboismUninstallerListener.java").read_text(encoding="utf-8")
dialog = uninstaller[uninstaller.index("static boolean resolveDeleteConfig()"):
                    uninstaller.index("private static boolean isConsoleRun()")]
tags = re.findall(r'lang\.startsWith\("(\w+)"\)', dialog)
expected = {"zh", "ja", "ko"}  # English is the base text and holds no branch
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

# The EXE and JAR uninstallers must treat config.json the same way and keep it by default. The
# verifier runs the uninstaller with `-console` and an explicit property only, so the default path
# without a property never executes. This check guards it directly.
if "return true; // default: delete" in uninstaller:
    raise SystemExit("uninstaller must not default to deleting config.json")
if "return false; // default: keep config.json" not in uninstaller:
    raise SystemExit("uninstaller must default to keeping config.json")
if "new Object[]{deleteButton, keepButton}, keepButton" not in dialog:
    raise SystemExit("uninstaller confirmation must default to the keep (No) button")
if "default keep" not in readme:
    raise SystemExit("README-java-installer.md must document the keep-by-default behavior")
print("installer localization parity PASS")
