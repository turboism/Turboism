#!/usr/bin/env bash
# Lane A: Turboism Windows release payload assembly.
#
# 用法: assemble-release.sh <version> [--skip-nsis]
#
# 流程:
#   1. Gradle 组装共享 payload（stageInstallerPayload，与 Java 安装器同源：
#      turboism-agent.jar、plugins/<module>.jar、config.template.json、
#      启动器、README.txt / README.zh.txt / README.ja.txt、LICENSE.txt）
#   2. 按 packaging/release-plugins.txt（唯一权威）读取获批插件模块，校验
#      staging 严格一致后生成 plugin-sections.nsh
#   3. 产出 turboism-<ver>.zip + .sha256
#      （zip 内容与历史版本一致：config.json 由 config.template.json 生成，
#       Java 安装器专属文件 config.template.json/README.java-installer.txt/
#       uninstall.command 不进入 zip）
#   4. makensis 构建 TurboismInstaller-<ver>.exe + .sha256（--skip-nsis 跳过）
#
# 产物目录: build/windows-installer/<ver>/  (staging/ 与 dist/)

set -euo pipefail

VER="${1:-}"
SKIP_NSIS=0
if [[ "${2:-}" == "--skip-nsis" ]]; then
  SKIP_NSIS=1
fi
if [[ -z "$VER" ]]; then
  echo "usage: assemble-release.sh <version> [--skip-nsis]" >&2
  exit 2
fi

pkg_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$pkg_dir/../.." && pwd)"
cd "$repo_root"

# 固定路径：installer.nsi 的缺省 STAGING_DIR/OUT_DIR 与此一致，支持独立 makensis 编译
stage="$repo_root/build/windows-installer/staging"
dist="$repo_root/build/windows-installer/dist"
generated="$repo_root/build/windows-installer/generated"

# ---------- 1. Gradle 组装共享 payload（Java 安装器 / NSIS / ZIP 同源） ----------
echo "[assemble] gradle: stageInstallerPayload :event-processor:jar"
./gradlew stageInstallerPayload :event-processor:jar \
  -PinstallerVersion="$VER" \
  -PturboismVersion="$VER" \
  -PturboismRelease=true \
  --console=plain
if [[ ! -f "$stage/turboism-agent.jar" || ! -d "$stage/plugins" ]]; then
  echo "error: staged payload incomplete at $stage" >&2
  exit 1
fi
mkdir -p "$dist" "$generated"
# Reassembly must not leak obsolete full/thin assets into the new exact roster.
find "$dist" -maxdepth 1 -type f -delete

# ---------- 3. 生成插件 Section 与安装前 JAR 校验清单 ----------
python3 - "$stage" "$pkg_dir/plugin-sections.nsh" "$repo_root/packaging/release-plugins.txt" "$generated" <<'PYEOF'
"""按 release-plugins.txt（唯一权威）生成 NSIS 插件 Section，并校验 staging 与清单严格一致。

fail-closed 规则：清单缺失/空行/注释/非插件项/重复/未排序即退出；staging 中的插件
JAR 必须与清单（不含运行时 core）逐项一致 —— 多出的 JAR（含已排除的占位插件）或
缺失的 JAR 均使组装失败。
"""
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

stage = Path(sys.argv[1])
out = Path(sys.argv[2])
manifest = Path(sys.argv[3])
generated = Path(sys.argv[4])
generated.mkdir(parents=True, exist_ok=True)

if not manifest.is_file():
    sys.exit(f"error: release plugin manifest missing: {manifest}")

raw = manifest.read_text(encoding="utf-8").splitlines()
invalid = [l for l in raw if not l.strip() or l.strip().startswith("#")]
if invalid:
    sys.exit(f"error: release plugin manifest forbids blank/comment lines: {invalid[:3]}")
entries = [l.strip() for l in raw if l.strip() and not l.strip().startswith("#")]
entry = re.compile(r"^:plugins:[a-z0-9-]+$")
bad = [l for l in entries if not entry.match(l)]
if bad:
    sys.exit(f"error: release plugin manifest contains non-plugin entries: {bad[:3]}")
if len(set(entries)) != len(entries):
    sys.exit("error: release plugin manifest contains duplicates")
if entries != sorted(entries):
    sys.exit("error: release plugin manifest is not ASCII-sorted")

modules = [l[len(":plugins:"):] for l in entries]
staged = sorted(p.stem for p in stage.glob("plugins/*.jar"))
if staged != sorted(modules):
    sys.exit(f"error: staged payload JARs do not match the release plugin manifest\n"
             f"  manifest: {sorted(modules)}\n  staged:   {staged}")

def nsis_escape(s: str) -> str:
    return s.replace("$", "$$").replace('"', '$\\"').replace("\r", " ").replace("\n", " ")

plugins = []
for module in modules:
    jar = stage / "plugins" / f"{module}.jar"
    if not jar.is_file():
        sys.exit(f"error: manifest entry {module}: staged JAR missing: {jar}")
    with zipfile.ZipFile(jar) as z:
        try:
            meta = json.loads(z.read("META-INF/turboism/plugin.json"))
        except KeyError:
            sys.exit(f"error: {jar}: missing META-INF/turboism/plugin.json")
        localized = {}
        readmes = {}
        readme_names = (("eng", "README.md"), ("chn", "README_zh.md"), ("jpn", "README_ja.md"), ("kor", "README_ko.md"))
        readme_map = dict(readme_names)
        for locale, suffix in (("eng", "en"), ("chn", "zh_Hans"), ("jpn", "ja"), ("kor", "ko")):
            resource = f"META-INF/turboism/i18n/messages_{suffix}.properties"
            try:
                text = z.read(resource).decode("utf-8")
            except KeyError:
                sys.exit(f"error: {jar}: missing installer localization {resource}")
            values = {}
            for raw_line in text.splitlines():
                if not raw_line or raw_line.startswith(("#", "!")) or "=" not in raw_line:
                    continue
                key, value = raw_line.split("=", 1)
                values[key.strip()] = value.strip()
            name = values.get("plugin.name", "")
            description = values.get("plugin.description", "")
            if not name or not description:
                sys.exit(f"error: {jar}: {resource} must define plugin.name and plugin.description")
            localized[locale] = {"name": name, "description": description}
            readme_file = readme_map[locale]
            readme_resource = f"META-INF/turboism/readme/{readme_file}"
            readme_text = None
            try:
                readme_text = z.read(readme_resource).decode("utf-8")
            except KeyError:
                repo_readme = manifest.parent.parent / "plugins" / module / readme_file
                if repo_readme.is_file():
                    readme_text = repo_readme.read_text(encoding="utf-8")
            if not readme_text:
                readme_text = f"# {name}\n\n{description}\n"
            readmes[locale] = readme_text
    pid = meta["id"]
    if pid == "turboism.core":
        sys.exit(f"error: {jar}: runtime-owned core ID must not be packaged")
    raw_tags = meta.get("tags", [])
    tags = [str(t) for t in raw_tags] if isinstance(raw_tags, list) else []
    plugins.append({
        "module": module,
        "id": pid,
        "name": meta.get("name", pid),
        "version": meta.get("version", ""),
        "description": meta.get("description", ""),
        "category": str(meta.get("category", "") or ""),
        "tags": tags,
        "localized": localized,
        "readmes": readmes,
    })
plugins.sort(key=lambda p: p["id"])

def rtf_escape(text: str) -> str:
    out_chars = []
    for ch in text:
        code = ord(ch)
        if ch in ("\\", "{", "}"):
            out_chars.append("\\" + ch)
        elif ch == "\t":
            out_chars.append("\\tab ")
        elif 32 <= code <= 126:
            out_chars.append(ch)
        elif code <= 0xFFFF:
            signed = code if code < 32768 else code - 65536
            out_chars.append(f"\\u{signed}?")
        else:
            code -= 0x10000
            high = 0xD800 + (code >> 10)
            low = 0xDC00 + (code & 0x3FF)
            for unit in (high, low):
                signed = unit if unit < 32768 else unit - 65536
                out_chars.append(f"\\u{signed}?")
    return "".join(out_chars)

_LINK_RE = re.compile(r"\[([^\]\r\n]+)\]\(([^)\r\n]+)\)")
_INLINE_RE = re.compile(r"(`[^`\r\n]+`|\*\*[^*\r\n]+\*\*|(?<!\*)\*[^*\r\n]+\*(?!\*))")

def render_rtf_inline(text: str) -> str:
    text = _LINK_RE.sub(lambda m: m.group(1), text)
    parts = []
    last = 0
    for match in _INLINE_RE.finditer(text):
        if match.start() > last:
            parts.append(rtf_escape(text[last:match.start()]))
        token = match.group(0)
        if token.startswith("`"):
            parts.append("{\\f1\\cf3 " + rtf_escape(token[1:-1]) + "}")
        elif token.startswith("**"):
            parts.append("{\\b " + rtf_escape(token[2:-2]) + "}")
        else:
            parts.append("{\\i " + rtf_escape(token[1:-1]) + "}")
        last = match.end()
    if last < len(text):
        parts.append(rtf_escape(text[last:]))
    return "".join(parts)

def strip_readme_front_matter(markdown: str) -> str:
    normalized = markdown.replace("\r\n", "\n").replace("\r", "\n")
    if normalized.startswith("---\n"):
        end = normalized.find("\n---\n", 4)
        if end >= 0:
            normalized = normalized[end + 5:]
    normalized = re.sub(r"<!--[\s\S]*?-->", "", normalized)
    return normalized.strip()

_META_LABELS = {
    "eng": ("Plugin ID", "Version", "Category", "Tags", "Summary"),
    "chn": ("插件 ID", "版本", "分类", "标签", "简介"),
    "jpn": ("プラグイン ID", "バージョン", "カテゴリ", "タグ", "概要"),
    "kor": ("플러그인 ID", "버전", "카테고리", "태그", "요약"),
}
_LOCALE_FONTS = {
    "eng": "Segoe UI",
    "chn": "Microsoft YaHei UI",
    "jpn": "Yu Gothic UI",
    "kor": "Malgun Gothic",
}

def render_plugin_readme_rtf(plugin: dict, locale: str) -> bytes:
    loc = plugin["localized"][locale]
    title = loc["name"]
    version = plugin["version"]
    pid = plugin["id"]
    category = plugin["category"]
    tags = plugin["tags"]
    description = loc["description"] or plugin["description"]
    body = strip_readme_front_matter(plugin["readmes"][locale])
    font = _LOCALE_FONTS.get(locale, "Segoe UI")
    lbl_id, lbl_ver, lbl_cat, lbl_tags, lbl_sum = _META_LABELS[locale]
    rtf = [
        "{\\rtf1\\ansi\\deff0\\uc1"
        "{\\fonttbl{\\f0\\fnil\\fcharset0 " + font + ";}{\\f1\\fmodern\\fcharset0 Consolas;}}"
        "{\\colortbl;\\red32\\green33\\blue36;\\red95\\green99\\blue104;\\red175\\green35\\blue35;\\red21\\green93\\blue252;}"
        "\\viewkind4\\pard\\cf1\\f0\\fs20 "
    ]
    header_title = f"{title}  v{version}" if version else title
    rtf.append("\\pard\\sa60\\fs26\\b " + rtf_escape(header_title) + "\\b0\\fs20\\par ")
    meta_parts = [rtf_escape(lbl_id) + ": {\\f1 " + rtf_escape(pid) + "}"]
    if version:
        meta_parts.append(rtf_escape(lbl_ver) + ": " + rtf_escape(version))
    if category:
        meta_parts.append(rtf_escape(lbl_cat) + ": " + rtf_escape(category))
    rtf.append("\\pard\\sa40\\cf2\\fs18 " + "   |   ".join(meta_parts) + "\\cf1\\fs20\\par ")
    if tags:
        rtf.append("\\pard\\sa60\\cf2\\fs18 " + rtf_escape(f"{lbl_tags}: " + ", ".join(tags)) + "\\cf1\\fs20\\par ")
    if description:
        rtf.append("\\pard\\sa100\\fs20{\\b " + rtf_escape(lbl_sum + ": ") + "}" + rtf_escape(description) + "\\par ")

    in_code = False
    code_lines = []
    para_lines = []
    table_lines = []

    def flush_para():
        if para_lines:
            rtf.append("\\pard\\sa80\\fs20 " + render_rtf_inline(" ".join(para_lines)) + "\\par ")
            para_lines.clear()

    def flush_table():
        if not table_lines:
            return
        rows = []
        for tl in table_lines:
            cells = [c.strip() for c in tl.strip()[1:-1].split("|")]
            if cells and all(re.fullmatch(r":?-{3,}:?", c) for c in cells):
                continue
            rows.append(cells)
        for idx, cells in enumerate(rows):
            joined = "  |  ".join(render_rtf_inline(c) for c in cells)
            if idx == 0:
                rtf.append("\\pard\\li120\\sa40\\fs19\\b " + joined + "\\b0\\fs20\\par ")
            else:
                rtf.append("\\pard\\li120\\sa40\\fs19 " + joined + "\\fs20\\par ")
        table_lines.clear()

    first_h1_skipped = False
    for raw in body.split("\n"):
        if raw.strip().startswith("```"):
            flush_para()
            flush_table()
            if in_code:
                for cl in code_lines:
                    rtf.append("\\pard\\li180\\sa20\\f1\\fs18\\cf3 " + rtf_escape(cl) + "\\cf1\\f0\\fs20\\par ")
                code_lines.clear()
                in_code = False
            else:
                in_code = True
            continue
        if in_code:
            code_lines.append(raw)
            continue
        stripped = raw.strip()
        if not stripped:
            flush_para()
            flush_table()
            continue
        if stripped.startswith("|") and stripped.endswith("|"):
            flush_para()
            table_lines.append(stripped)
            continue
        flush_table()
        if stripped.startswith("# ") and not first_h1_skipped:
            first_h1_skipped = True
            continue
        if stripped.startswith("### "):
            flush_para()
            rtf.append("\\pard\\sb100\\sa40\\fs21\\b " + render_rtf_inline(stripped[4:]) + "\\b0\\fs20\\par ")
            continue
        if stripped.startswith("## "):
            flush_para()
            rtf.append("\\pard\\sb140\\sa50\\fs23\\b " + render_rtf_inline(stripped[3:]) + "\\b0\\fs20\\par ")
            continue
        if stripped.startswith("# "):
            flush_para()
            rtf.append("\\pard\\sb160\\sa60\\fs25\\b " + render_rtf_inline(stripped[2:]) + "\\b0\\fs20\\par ")
            continue
        if stripped.startswith(("- ", "* ", "+ ")):
            flush_para()
            rtf.append("\\pard\\li200\\sa40\\fs20 \\u8226? " + render_rtf_inline(stripped[2:]) + "\\par ")
            continue
        m_num = re.match(r"^(\d+[.)])\s+(.+)$", stripped)
        if m_num:
            flush_para()
            rtf.append("\\pard\\li200\\sa40\\fs20 " + rtf_escape(m_num.group(1)) + " " + render_rtf_inline(m_num.group(2)) + "\\par ")
            continue
        if stripped.startswith("> "):
            flush_para()
            rtf.append("\\pard\\li200\\sa60\\cf2\\i " + render_rtf_inline(stripped[2:]) + "\\i0\\cf1\\par ")
            continue
        para_lines.append(stripped)
    flush_para()
    flush_table()
    rtf.append("}")
    return "".join(rtf).encode("ascii")

readme_out_dir = generated / "plugin-readmes"
readme_out_dir.mkdir(parents=True, exist_ok=True)
for index, p in enumerate(plugins):
    for locale in ("eng", "chn", "jpn", "kor"):
        (readme_out_dir / f"plugin-{index:04d}-{locale}.rtf").write_bytes(
            render_plugin_readme_rtf(p, locale)
        )

# Standard EXE and ZIP carry the complete, verified offline GraalJS closure.
engine_manifest = json.loads((stage / "script-engine.json").read_text(encoding="utf-8"))
engine_version = engine_manifest.get("version", "")
if (engine_manifest.get("format") != "turboism.optional-script-engine"
        or type(engine_manifest.get("schemaVersion")) is not int
        or engine_manifest["schemaVersion"] != 1
        or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:\.[0-9]+)?", engine_version)):
    sys.exit("error: invalid optional script engine manifest")
expected_engine_names = {
    f"js-isolate-windows-amd64-community-{engine_version}.jar",
    f"truffle-api-{engine_version}.jar",
}
engine_entries = engine_manifest.get("artifacts", [])
if len(engine_entries) != 2 or {entry.get("name") for entry in engine_entries} != expected_engine_names:
    sys.exit("error: optional script engine inventory must contain exactly the two pinned artifacts")
for entry in engine_entries:
    path = stage / "graal" / "lib" / entry["name"]
    if (type(entry.get("bytes")) is not int or not 0 < entry["bytes"] <= 134217728
            or not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", ""))
            or not path.is_file() or path.is_symlink()
            or path.stat().st_size != entry["bytes"]
            or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]):
        sys.exit(f"error: staged optional script engine differs from the download pin: {path.name}")

# script-engine.json 是手工清单：与 Gradle 生成的 verification-metadata.xml
# 一致性此前无机制保证。此处强制每个钉住工件都能在 Gradle 校验元数据中找到
# 同名 artifact 且 sha256 相同 —— 手工清单漂移即组装失败。
import os
import xml.etree.ElementTree as _ET
_verification = Path(os.environ.get(
    "TURBOISM_VERIFICATION_METADATA",
    manifest.parent.parent / "gradle" / "verification-metadata.xml"))
if not _verification.is_file():
    sys.exit(f"error: Gradle verification metadata missing: {_verification}")
_pins = {}
for _artifact in _ET.parse(_verification).getroot().iter():
    if not _artifact.tag.endswith("artifact"):
        continue
    for _child in _artifact:
        if _child.tag.endswith("sha256"):
            _pins[_artifact.get("name")] = _child.get("value")
for entry in engine_entries:
    if _pins.get(entry["name"]) != entry["sha256"]:
        sys.exit(
            f"error: script-engine.json pin {entry['name']} diverges from "
            f"gradle/verification-metadata.xml ({_pins.get(entry['name'])})"
        )
core_payload = [("turboism-agent.jar", stage / "turboism-agent.jar")]
core_payload.extend(
    (path.relative_to(stage).as_posix(), path)
    for path in sorted((stage / "graal" / "lib").glob("*.jar"))
)
core_payload.extend([
    ("install-jar-payload.ps1", stage / "install-jar-payload.ps1"),
    ("launch-cubism-turboism.bat", stage / "launch-cubism-turboism.bat"),
    ("launch-cubism-turboism.ps1", stage / "launch-cubism-turboism.ps1"),
    ("configure_turboism.ps1", stage / "configure_turboism.ps1"),
    ("cubism-launch-common.ps1", stage / "cubism-launch-common.ps1"),
    ("install-managed-graal.ps1", stage / "install-managed-graal.ps1"),
    ("install-script-engine.ps1", stage / "install-script-engine.ps1"),
    ("script-engine.json", stage / "script-engine.json"),
    ("retired-plugins.txt", stage / "retired-plugins.txt"),
    ("managed-graal.json", stage / "managed-graal.json"),
    ("turboism.ico", stage / "turboism.ico"),
    ("turboism.png", stage / "turboism.png"),
    ("README.txt", stage / "README.txt"),
    ("README.zh.txt", stage / "README.zh.txt"),
    ("README.ja.txt", stage / "README.ja.txt"),
    ("README.ko.txt", stage / "README.ko.txt"),
    ("LICENSE", stage / "LICENSE.txt"),
    ("EULA.en.txt", stage / "EULA.en.txt"),
    ("EULA.zh-Hans.txt", stage / "EULA.zh-Hans.txt"),
    ("EULA.ja.txt", stage / "EULA.ja.txt"),
    ("EULA.ko.txt", stage / "EULA.ko.txt"),
])
plugin_payload = [
    (f'plugins/{p["module"]}.jar', stage / "plugins" / f'{p["module"]}.jar')
    for p in plugins
]
def write_checksum_manifest(name: str, payload):
    target = generated / name
    missing = [str(path) for _, path in payload if not path.is_file()]
    if missing:
        sys.exit(f"error: installer checksum payload is incomplete: {missing[:3]}")
    content = "".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {relative}\n"
        for relative, path in payload
    )
    target.write_text(content, encoding="ascii", newline="\n")
    return target

write_checksum_manifest("payload-core.sha256", core_payload)
write_checksum_manifest("payload-plugins.sha256", plugin_payload)
lines = []
lines.append("; 由 assemble-release.sh 按 release-plugins.txt 权威清单生成，勿手改。")
lines.append("; 标准安装器携带全部第一方插件 JAR。")
lines.append("")

# 每项永久载荷都先按目标 SHA-256 生成计划；NSIS 只把缺失或变更项解压到私有临时目录。
def append_extractor(target, function_name, payload, plan_name, temporary_name):
    target.append(f"Function {function_name}")
    for index, (relative, path) in enumerate(payload):
        parent = str(Path(relative).parent).replace("/", "\\")
        output = f"$PLUGINSDIR\\{temporary_name}"
        if parent != ".":
            output += "\\" + parent
        source = path.relative_to(stage).as_posix()
        target.append(f'  ${{If}} ${{FileExists}} "$PLUGINSDIR\\{plan_name}\\{index:04d}.need"')
        target.append(f'    SetOutPath "{output}"')
        target.append(f'    File "/oname={Path(relative).name}" "${{STAGING_DIR}}/{source}"')
        target.append("  ${EndIf}")
    target.append("FunctionEnd")
    target.append("")

extract_lines = [
    "; 由 assemble-release.sh 生成：只解压目标缺失或 SHA-256 不同的永久载荷。",
]
append_extractor(
    extract_lines, "ExtractCorePayload", core_payload,
    "Turboism-core-plan", "Turboism-core-payload",
)
(generated / "payload-extract.nsh").write_bytes(
    b"\xef\xbb\xbf" + ("\n".join(extract_lines) + "\n").encode("utf-8")
)

# 隐藏载荷 Section：标准安装器携带全部插件 JAR。
# 先按用户在插件选择页的勾选写入 .skip 标记，再用内嵌 SHA-256 清单生成计划，
# 仅解压并安装用户勾选且需要替换的条目；写入前仍校验源和目标。
lines.append('Section "-插件载荷" SecPluginPayload')
lines.append("  Call WritePluginSelectionSkipMarkers")
lines.append("  nsExec::ExecToLog '\"$SYSDIR\\WindowsPowerShell\\v1.0\\powershell.exe\" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"$INSTDIR\\install-jar-payload.ps1\" -ManifestPath \"$PLUGINSDIR\\Turboism-payload-manifests\\payload-plugins.sha256\" -DestinationRoot \"$INSTDIR\" -PlanRoot \"$PLUGINSDIR\\Turboism-plugin-plan\" -PlanOnly'")
lines.append("  Pop $0")
lines.append("  ${If} $0 != 0")
lines.append('    MessageBox MB_ICONSTOP "$(PayloadInstallError)"')
lines.append("    Abort")
lines.append("  ${EndIf}")
for index, (relative, path) in enumerate(plugin_payload):
    parent = str(Path(relative).parent).replace("/", "\\")
    lines.append(f'  ${{If}} ${{FileExists}} "$PLUGINSDIR\\Turboism-plugin-plan\\{index:04d}.need"')
    lines.append(f'    SetOutPath "$PLUGINSDIR\\Turboism-plugin-payload\\{parent}"')
    lines.append(f'    File "/oname={path.name}" "${{STAGING_DIR}}/{path.relative_to(stage).as_posix()}"')
    lines.append("  ${EndIf}")
lines.append("  nsExec::ExecToLog '\"$SYSDIR\\WindowsPowerShell\\v1.0\\powershell.exe\" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"$INSTDIR\\install-jar-payload.ps1\" -SourceRoot \"$PLUGINSDIR\\Turboism-plugin-payload\" -ManifestPath \"$PLUGINSDIR\\Turboism-payload-manifests\\payload-plugins.sha256\" -DestinationRoot \"$INSTDIR\" -PlanRoot \"$PLUGINSDIR\\Turboism-plugin-plan\"'")
lines.append("  Pop $0")
lines.append("  ${If} $0 != 0")
lines.append('    MessageBox MB_ICONSTOP "$(PayloadInstallError)"')
lines.append("    Abort")
lines.append("  ${EndIf}")
lines.append('  RMDir /r "$PLUGINSDIR\\Turboism-plugin-payload"')
lines.append('  RMDir /r "$PLUGINSDIR\\Turboism-plugin-plan"')
lines.append('  Delete "$PLUGINSDIR\\Turboism-payload-manifests\\payload-plugins.sha256"')
lines.append('  RMDir "$PLUGINSDIR\\Turboism-payload-manifests"')
lines.append("SectionEnd")
lines.append("")

def sanitize(s: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "_", s)

for index in range(len(plugins)):
    lines.append(f"Var PluginState_{index}")
lines.append("")

# 插件四语言显示名/描述（均来自插件自身 i18n 资源）
for p in plugins:
    key = sanitize(p["id"])
    for locale, nsis_lang in (("eng", "LANG_ENGLISH"), ("chn", "LANG_SIMPCHINESE"), ("jpn", "LANG_JAPANESE"), ("kor", "LANG_KOREAN")):
        title = p["localized"][locale]["name"] + (" " + p["version"] if p["version"] else "")
        description = p["localized"][locale]["description"]
        lines.append(f'LangString PLUGIN_NAME_{key} ${{{nsis_lang}}} "{nsis_escape(title)}"')
        lines.append(f'LangString PLUGIN_DESC_{key} ${{{nsis_lang}}} "{nsis_escape(description)}"')
lines.append("")

lines.append("; 初始化第一方插件选择状态（默认全选）")
lines.append("Function InitPluginSelectionDefaults")
lines.append(f'  StrCpy $PluginTotalCount "{len(plugins)}"')
lines.append(f'  StrCpy $PluginSelectedCount "{len(plugins)}"')
lines.append('  StrCpy $PluginCurrentIndex "0"')
lines.append('  StrCpy $PluginReadmeDir ""')
for index in range(len(plugins)):
    lines.append(f"  StrCpy $PluginState_{index} 1")
lines.append("FunctionEnd")
lines.append("")

lines.append("; 按当前界面语言解压插件渲染后的 RTF README 到安装器私有临时目录")
lines.append("Function StagePluginReadmeFiles")
lines.append('  ${If} $PluginReadmeDir != ""')
lines.append('    ${If} ${FileExists} "$PluginReadmeDir\\plugin-0.rtf"')
lines.append("      Return")
lines.append("    ${EndIf}")
lines.append("  ${EndIf}")
lines.append("  InitPluginsDir")
lines.append('  StrCpy $PluginReadmeDir "$PLUGINSDIR\\Turboism-plugin-readmes"')
lines.append('  SetOutPath "$PluginReadmeDir"')
lines.append("  ${If} $LANGUAGE == ${LANG_SIMPCHINESE}")
for index in range(len(plugins)):
    lines.append(f'    File "/oname=plugin-{index}.rtf" "${{GENERATED_DIR}}/plugin-readmes/plugin-{index:04d}-chn.rtf"')
lines.append("  ${ElseIf} $LANGUAGE == ${LANG_JAPANESE}")
for index in range(len(plugins)):
    lines.append(f'    File "/oname=plugin-{index}.rtf" "${{GENERATED_DIR}}/plugin-readmes/plugin-{index:04d}-jpn.rtf"')
lines.append("  ${ElseIf} $LANGUAGE == ${LANG_KOREAN}")
for index in range(len(plugins)):
    lines.append(f'    File "/oname=plugin-{index}.rtf" "${{GENERATED_DIR}}/plugin-readmes/plugin-{index:04d}-kor.rtf"')
lines.append("  ${Else}")
for index in range(len(plugins)):
    lines.append(f'    File "/oname=plugin-{index}.rtf" "${{GENERATED_DIR}}/plugin-readmes/plugin-{index:04d}-eng.rtf"')
lines.append("  ${EndIf}")
lines.append("FunctionEnd")
lines.append("")

lines.append("; 填充第一方插件复选框列表（SysListView32 + LVS_EX_CHECKBOXES）")
lines.append("Function PopulatePluginSelectionList")
lines.append("  ; LVM_DELETEALLITEMS = 0x1009")
lines.append("  SendMessage $PluginSelectionList 0x1009 0 0")
for index, p in enumerate(plugins):
    key = sanitize(p["id"])
    lines.append(f"  StrCpy $0 {index}")
    lines.append(f"  StrCpy $1 $PluginState_{index}")
    lines.append(f'  StrCpy $2 "$(PLUGIN_NAME_{key})"')
    lines.append("  Call InsertPluginListViewItem")
lines.append("  System::Call '*(i 8, i $PluginCurrentIndex, i 0, i 3, i 3, p 0, i 0, i 0, p 0) p .r3'")
lines.append("  ${If} $3 != 0")
lines.append("    SendMessage $PluginSelectionList 0x102B $PluginCurrentIndex $3")
lines.append("    System::Free $3")
lines.append("  ${EndIf}")
lines.append("FunctionEnd")
lines.append("")

lines.append("; 从 SysListView32 同步原生复选框勾选状态（$4=总勾选数, $5=刚被点击复选框的索引或 -1）")
lines.append("Function SyncPluginSelectionFromListView")
lines.append("  StrCpy $4 0")
lines.append("  StrCpy $5 -1")
for index in range(len(plugins)):
    lines.append(f"  SendMessage $PluginSelectionList 0x102C {index} 0xF000 $1")
    lines.append("  IntOp $1 $1 & 0xF000")
    lines.append("  ${If} $1 == 8192")
    lines.append("    StrCpy $1 1")
    lines.append("  ${Else}")
    lines.append("    StrCpy $1 0")
    lines.append("  ${EndIf}")
    lines.append(f"  ${{If}} $1 != $PluginState_{index}")
    lines.append(f"    StrCpy $PluginState_{index} $1")
    lines.append(f"    StrCpy $5 {index}")
    lines.append("  ${EndIf}")
    lines.append("  IntOp $4 $4 + $1")
lines.append("FunctionEnd")
lines.append("")

lines.append("; 全选 ($0=1) 或全不选 ($0=0) 全部第一方插件")
lines.append("Function SetAllPluginsSelected")
for index in range(len(plugins)):
    lines.append(f"  StrCpy $PluginState_{index} $0")
lines.append("  ${If} $0 == 1")
lines.append(f'    StrCpy $PluginSelectedCount "{len(plugins)}"')
lines.append("    StrCpy $4 0x2000")
lines.append("  ${Else}")
lines.append('    StrCpy $PluginSelectedCount "0"')
lines.append("    StrCpy $4 0x1000")
lines.append("  ${EndIf}")
lines.append("  System::Call '*(i 8, i 0, i 0, i r4, i 0xF000, p 0, i 0, i 0, p 0) p .r3'")
lines.append("  ${If} $3 != 0")
lines.append("    SendMessage $PluginSelectionList 0x102B -1 $3")
lines.append("    System::Free $3")
lines.append("  ${EndIf}")
lines.append("FunctionEnd")
lines.append("")

lines.append("; 为未勾选的第一方插件写入跳过标记，并移除此前已安装的对应捆绑 JAR")
lines.append("Function WritePluginSelectionSkipMarkers")
lines.append('  CreateDirectory "$PLUGINSDIR\\Turboism-plugin-plan"')
for index, p in enumerate(plugins):
    lines.append(f'  Delete "$PLUGINSDIR\\Turboism-plugin-plan\\{index:04d}.skip"')
    lines.append(f"  ${{If}} $PluginState_{index} == 0")
    lines.append(f'    FileOpen $0 "$PLUGINSDIR\\Turboism-plugin-plan\\{index:04d}.skip" w')
    lines.append("    FileClose $0")
    lines.append(f'    Delete "$INSTDIR\\plugins\\{p["module"]}.jar"')
    lines.append("  ${EndIf}")
lines.append("FunctionEnd")
lines.append("")

lines.append("; 导出完整捆绑插件 id 清单，供前置配置提交保留无关禁用项。")
lines.append("Function SetBundledPluginIds")
lines.append(f'  StrCpy $bundledPluginIds "{";".join(p["id"] for p in plugins)}"')
lines.append("FunctionEnd")
lines.append("")

# 卸载白名单：仅删除安装器部署的插件 JAR；未知文件与第三方 JAR 保留。
lines.append("; 卸载白名单：仅删除安装器部署的插件 JAR；未知文件与第三方 JAR 保留。")
lines.append("Function un.DeleteInstallerPluginJars")
for module in modules:
    lines.append(f'  Delete "$INSTDIR\\plugins\\{module}.jar"')
lines.append("FunctionEnd")
lines.append("")

content = "\n".join(lines) + "\n"
# NSIS Unicode 模式：UTF-8 源文件需带 BOM
out.write_bytes(b"\xef\xbb\xbf" + content.encode("utf-8"))
print(f"[assemble] plugin-sections.nsh: {len(plugins)} sections -> {out}")
for p in plugins:
    print(f"  {p['id']} | {p['name']} | {p['version']}")
PYEOF

# ---------- 4. ZIP + sha256 ----------
# 使用 python3 zipfile（避免依赖 zip CLI）。
# 标准 ZIP 携带获批插件及完整 GraalJS 离线依赖。config.json 由共享 payload 的 config.template.json 生成；
# Java 安装器专属文件（config.template.json、README.java-installer.txt、
# uninstall.command）不进入 Windows zip。
zip_dir() {
  local src="$1" out="$2"
  python3 - "$src" "$out" "$VER" <<'PYEOF'
import hashlib, json, os, stat, sys, zipfile
src, out, ver = sys.argv[1], sys.argv[2], sys.argv[3]
EXCLUDED = {"config.template.json", "README.java-installer.txt", "uninstall.command"}
TIMESTAMP = (1980, 1, 1, 0, 0, 0)
def write_bytes(archive, name, data, mode):
    info = zipfile.ZipInfo(name, TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = (mode & 0xFFFF) << 16
    archive.writestr(info, data)

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        dirs.sort()
        for f in sorted(files):
            if f in EXCLUDED:
                continue
            full = os.path.join(root, f)
            arc = os.path.relpath(full, src).replace(os.sep, "/")
            write_bytes(z, arc, open(full, "rb").read(), stat.S_IMODE(os.stat(full).st_mode))
    names = set(z.namelist())
    if not any(name.startswith("plugins/") and name.endswith(".jar") for name in names):
        raise SystemExit("error: standard ZIP is missing plugin payload")
    config = os.path.join(src, "config.template.json")
    write_bytes(z, "config.json", open(config, "rb").read(), stat.S_IMODE(os.stat(config).st_mode))
    # Keep the installed variant compatible with existing update clients.
    installed_build = json.dumps({"schemaVersion": 1, "variant": "full", "version": ver}) + "\n"
    write_bytes(z, "installed-build.json", installed_build.encode("utf-8"), 0o644)
PYEOF
}

zip_dir "$stage" "$dist/turboism-$VER.zip"

# ---------- 4.5 SDK 与注解处理器开发构件 ----------
# 独立的插件开发构件：sdk jar 与发行载荷内 graal/lib 同源同字节，
# event-processor jar 由同次发布构建产出，以稳定文件名挂到 GitHub Release，供外部插件工程直接消费。
mapfile -t sdk_jars < <(find "$stage/graal/lib" -maxdepth 1 -type f -name 'sdk-*.jar' | LC_ALL=C sort)
if [[ ${#sdk_jars[@]} -ne 1 || "$(basename "${sdk_jars[0]}")" != "sdk-$VER.jar" ]]; then
  echo "error: expected exactly one staged SDK JAR named sdk-$VER.jar under $stage/graal/lib" >&2
  exit 1
fi
cp "${sdk_jars[0]}" "$dist/turboism-sdk-$VER.jar"

wt_id="$(bash "$repo_root/scripts/dev/worktree-id.sh")"
event_processor_jar="$repo_root/build/worktree/$wt_id/event-processor/libs/event-processor-$VER.jar"
if [[ ! -f "$event_processor_jar" || -L "$event_processor_jar" ]]; then
  echo "error: expected built event-processor JAR named event-processor-$VER.jar at $event_processor_jar" >&2
  exit 1
fi
cp "$event_processor_jar" "$dist/turboism-event-processor-$VER.jar"

# sidecar 只记录同目录文件名，下载后可直接在附件目录执行 `sha256sum -c *.sha256`。
(
  cd "$dist"
  sha256sum "turboism-$VER.zip" > "turboism-$VER.zip.sha256"
  sha256sum "turboism-sdk-$VER.jar" > "turboism-sdk-$VER.jar.sha256"
  sha256sum "turboism-event-processor-$VER.jar" > "turboism-event-processor-$VER.jar.sha256"
)

# ---------- 5. NSIS 安装器 ----------
if [[ "$SKIP_NSIS" == "1" ]]; then
  echo "[assemble] --skip-nsis: 跳过 makensis"
else
  if ! command -v makensis >/dev/null 2>&1; then
    echo "error: makensis not found; run with --skip-nsis or install nsis" >&2
    exit 1
  fi
  # NSIS 的 LicenseLangString 依赖 BOM 才能可靠按 UTF-8 解析 license 文本；
  # 无 BOM 的 UTF-8 中文会在非 UTF-8 ACP 下乱码。仅为 makensis 生成带 BOM 的
  # EULA 副本，源文件（Java 安装器/ZIP 使用的无 BOM 版本）保持字节不变。
  mkdir -p "$generated/eula"
  for lang in en zh-Hans ja ko; do
    { printf '\xef\xbb\xbf'; cat "$repo_root/packaging/eula/EULA.$lang.txt"; } > "$generated/eula/EULA.$lang.txt"
  done
  # VIProductVersion 需要纯数字 x.y.z.w；无法从 VER 推导时跳过版本资源
  ver_numeric=""
  if [[ "$VER" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(\.([0-9]+))?$ ]]; then
    ver_numeric="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}.${BASH_REMATCH[5]:-0}"
  elif [[ "$VER" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)-(0\.nightly\.[1-9][0-9]*|(alpha|beta|rc)\.(0|[1-9][0-9]*))$ ]]; then
    # Windows resources have only four 16-bit components; the complete build
    # identity remains in the string version and payload JARs, not a lossy counter.
    ver_numeric="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}.0"
  else
    echo "error: unsupported installer version: $VER" >&2
    exit 1
  fi
  nsis_args=(
    -WX
    -DVER="$VER"
    -DSTAGING_DIR="$stage"
    -DGENERATED_DIR="$generated"
    -DOUT_DIR="$dist"
    -DLICENSE_FILE="$repo_root/LICENSE"
    -DEULA_DIR="$generated/eula"
    -DICON_FILE="$repo_root/packaging/windows-installer/assets/turboism.ico"
  )
  if [[ -n "$ver_numeric" ]]; then
    nsis_args+=(-DVER_NUMERIC="$ver_numeric")
    echo "[assemble] VIProductVersion: $ver_numeric"
  fi
  makensis "${nsis_args[@]}" "$pkg_dir/installer.nsi"
  (
    cd "$dist"
    sha256sum "TurboismInstaller-$VER.exe" > "TurboismInstaller-$VER.exe.sha256"
  )
fi

# ---------- 汇总 ----------
echo
echo "[assemble] 产物:"
find "$dist" -maxdepth 1 -type f -printf '  %f (%s bytes)\n' | LC_ALL=C sort
echo "[assemble] staging: $stage"
