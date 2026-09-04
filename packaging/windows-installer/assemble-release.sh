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
#   3. 产出 turboism-<ver>-lite.zip / turboism-<ver>-full.zip + .sha256
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
echo "[assemble] gradle: stageInstallerPayload"
./gradlew stageInstallerPayload \
  -PinstallerVersion="$VER" \
  -PturboismRelease=true \
  --console=plain
if [[ ! -f "$stage/turboism-agent.jar" || ! -d "$stage/plugins" ]]; then
  echo "error: staged payload incomplete at $stage" >&2
  exit 1
fi
mkdir -p "$dist" "$generated"

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

modules = [l[len(":plugins:"):] for l in entries if l != ":plugins:core"]
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
        for locale, suffix in (("eng", "en"), ("chn", "zh_Hans"), ("jpn", "ja")):
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
    pid = meta["id"]
    if pid == "turboism.core":
        sys.exit(f"error: {jar}: runtime-owned core ID must not be packaged")
    plugins.append({
        "module": module,
        "id": pid,
        "name": meta.get("name", pid),
        "version": meta.get("version", ""),
        "description": meta.get("description", ""),
        "localized": localized,
    })
plugins.sort(key=lambda p: p["id"])

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
    ("turboism.ico", stage / "turboism.ico"),
    ("turboism.png", stage / "turboism.png"),
    ("README.txt", stage / "README.txt"),
    ("README.zh.txt", stage / "README.zh.txt"),
    ("README.ja.txt", stage / "README.ja.txt"),
    ("LICENSE", stage / "LICENSE.txt"),
    ("EULA.en.txt", stage / "EULA.en.txt"),
    ("EULA.zh-Hans.txt", stage / "EULA.zh-Hans.txt"),
    ("EULA.ja.txt", stage / "EULA.ja.txt"),
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
lines.append("; Full($Mode==1) 由隐藏载荷 Section 安装全部插件 JAR；可见 Section")
lines.append("; 为全新安装和升级收集 disabledPlugins，其他有效用户配置保持不变。")
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

# 隐藏载荷 Section：Full 模式安装全部插件 JAR；Lite 模式不写任何 JAR（$Mode 守卫）。
# 先用内嵌 SHA-256 清单生成计划，再只解压需要替换的条目；写入前仍校验源和目标。
# 隐藏 Section 不出现在组件页；可见勾选在配置前置阶段更新 disabledPlugins。
lines.append('Section "-插件载荷" SecPluginPayload')
lines.append("  ${If} $Mode == 1")
lines.append("    nsExec::ExecToLog '\"$SYSDIR\\WindowsPowerShell\\v1.0\\powershell.exe\" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"$INSTDIR\\install-jar-payload.ps1\" -ManifestPath \"$PLUGINSDIR\\Turboism-payload-manifests\\payload-plugins.sha256\" -DestinationRoot \"$INSTDIR\" -PlanRoot \"$PLUGINSDIR\\Turboism-plugin-plan\" -PlanOnly'")
lines.append("    Pop $0")
lines.append("    ${If} $0 != 0")
lines.append('      MessageBox MB_ICONSTOP "$(PayloadInstallError)"')
lines.append("      Abort")
lines.append("    ${EndIf}")
for index, (relative, path) in enumerate(plugin_payload):
    parent = str(Path(relative).parent).replace("/", "\\")
    lines.append(f'    ${{If}} ${{FileExists}} "$PLUGINSDIR\\Turboism-plugin-plan\\{index:04d}.need"')
    lines.append(f'      SetOutPath "$PLUGINSDIR\\Turboism-plugin-payload\\{parent}"')
    lines.append(f'      File "/oname={path.name}" "${{STAGING_DIR}}/{path.relative_to(stage).as_posix()}"')
    lines.append("    ${EndIf}")
lines.append("    nsExec::ExecToLog '\"$SYSDIR\\WindowsPowerShell\\v1.0\\powershell.exe\" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"$INSTDIR\\install-jar-payload.ps1\" -SourceRoot \"$PLUGINSDIR\\Turboism-plugin-payload\" -ManifestPath \"$PLUGINSDIR\\Turboism-payload-manifests\\payload-plugins.sha256\" -DestinationRoot \"$INSTDIR\"'")
lines.append("    Pop $0")
lines.append("    ${If} $0 != 0")
lines.append('      MessageBox MB_ICONSTOP "$(PayloadInstallError)"')
lines.append("      Abort")
lines.append("    ${EndIf}")
lines.append('    RMDir /r "$PLUGINSDIR\\Turboism-plugin-payload"')
lines.append('    RMDir /r "$PLUGINSDIR\\Turboism-plugin-plan"')
lines.append("  ${EndIf}")
lines.append('  Delete "$PLUGINSDIR\\Turboism-payload-manifests\\payload-plugins.sha256"')
lines.append('  RMDir "$PLUGINSDIR\\Turboism-payload-manifests"')
lines.append("SectionEnd")
lines.append("")

def sanitize(s: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "_", s)

# 可见插件 Section：三语言显示名/描述均来自插件自身 i18n 资源。
for p in plugins:
    key = sanitize(p["id"])
    for locale, nsis_lang in (("eng", "LANG_ENGLISH"), ("chn", "LANG_SIMPCHINESE"), ("jpn", "LANG_JAPANESE")):
        title = p["localized"][locale]["name"] + (" " + p["version"] if p["version"] else "")
        description = p["localized"][locale]["description"]
        lines.append(f'LangString PLUGIN_NAME_{key} ${{{nsis_lang}}} "{nsis_escape(title)}"')
        lines.append(f'LangString PLUGIN_DESC_{key} ${{{nsis_lang}}} "{nsis_escape(description)}"')
for p in plugins:
    key = sanitize(p["id"])
    sec = "SEC_" + key
    lines.append(f'Section "$(PLUGIN_NAME_{key})" {sec}')
    lines.append("SectionEnd")
    lines.append("")

lines.append("; 组件页悬停描述")
lines.append("!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN")
for p in plugins:
    key = sanitize(p["id"])
    sec = "SEC_" + key
    lines.append(f'  !insertmacro MUI_DESCRIPTION_TEXT ${{{sec}}} "$(PLUGIN_DESC_{key})"')
lines.append("!insertmacro MUI_FUNCTION_DESCRIPTION_END")
lines.append("")

lines.append("; 按模式设置全部插件 Section 的选中状态（$0: 1 = 选中, 0 = 取消）")
lines.append("Function SetPluginSectionsSelected")
for p in plugins:
    sec = "SEC_" + sanitize(p["id"])
    lines.append(f"  SectionGetFlags ${{{sec}}} $1")
    lines.append("  IntOp $1 $1 & ${SECTION_OFF}")
    lines.append("  ${If} $0 == 1")
    lines.append("    IntOp $1 $1 | ${SF_SELECTED}")
    lines.append("  ${EndIf}")
    lines.append(f"  SectionSetFlags ${{{sec}}} $1")
lines.append("FunctionEnd")
lines.append("")

lines.append("; 导出完整捆绑插件 id 清单，供前置配置提交保留无关禁用项。")
lines.append("Function SetBundledPluginIds")
lines.append(f'  StrCpy $bundledPluginIds "{";".join(p["id"] for p in plugins)}"')
lines.append("FunctionEnd")
lines.append("")

lines.append("; 收集未勾选插件 id 到 $uncheckedPluginIds（';' 分隔）")
lines.append("Function CollectUncheckedPluginIds")
for p in plugins:
    sec = "SEC_" + sanitize(p["id"])
    lines.append(f"  SectionGetFlags ${{{sec}}} $1")
    lines.append("  IntOp $2 $1 & ${SF_SELECTED}")
    lines.append("  ${If} $2 == 0")
    lines.append(f'    ${{If}} $uncheckedPluginIds == ""')
    lines.append(f'      StrCpy $uncheckedPluginIds "{p["id"]}"')
    lines.append("    ${Else}")
    lines.append(f'      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;{p["id"]}"')
    lines.append("    ${EndIf}")
    lines.append("  ${EndIf}")
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
# 两种 zip 都携带顶层公共文件和 graal/lib；Lite 排除 plugins/。
# Full 携带获批插件 JAR。config.json 由共享 payload 的 config.template.json 生成；
# Java 安装器专属文件（config.template.json、README.java-installer.txt、
# uninstall.command）不进入 Windows zip。
zip_dir() {
  local src="$1" out="$2" lite="$3"
  python3 - "$src" "$out" "$lite" <<'PYEOF'
import hashlib, os, stat, sys, zipfile
src, out, lite = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
EXCLUDED = {"config.template.json", "README.java-installer.txt", "uninstall.command", "install-managed-graal.ps1"}
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
            if lite and arc.startswith("plugins/"):
                continue  # Lite excludes plugins but keeps common Graal libraries.
            write_bytes(z, arc, open(full, "rb").read(), stat.S_IMODE(os.stat(full).st_mode))
    names = set(z.namelist())
    graal = [name for name in names if name.startswith("graal/lib/") and name.endswith(".jar")]
    if not graal:
        raise SystemExit("error: Windows zip is missing the common Graal host closure")
    if lite and any(name.startswith("plugins/") for name in names):
        raise SystemExit("error: Lite zip unexpectedly contains plugin payload")
    if not lite and not any(name.startswith("plugins/") and name.endswith(".jar") for name in names):
        raise SystemExit("error: Full zip is missing plugin payload")
    # 历史契约：zip 内含 config.json（模板内容）
    config = os.path.join(src, "config.template.json")
    write_bytes(z, "config.json", open(config, "rb").read(), stat.S_IMODE(os.stat(config).st_mode))
PYEOF
}

zip_dir "$stage" "$dist/turboism-$VER-full.zip" 0
zip_dir "$stage" "$dist/turboism-$VER-lite.zip" 1
# sidecar 只记录同目录文件名，下载后可直接在附件目录执行 `sha256sum -c *.sha256`。
(
  cd "$dist"
  sha256sum "turboism-$VER-lite.zip" > "turboism-$VER-lite.zip.sha256"
  sha256sum "turboism-$VER-full.zip" > "turboism-$VER-full.zip.sha256"
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
  for lang in en zh-Hans ja; do
    { printf '\xef\xbb\xbf'; cat "$repo_root/packaging/eula/EULA.$lang.txt"; } > "$generated/eula/EULA.$lang.txt"
  done
  # VIProductVersion 需要纯数字 x.y.z.w；无法从 VER 推导时跳过版本资源
  ver_numeric=""
  if [[ "$VER" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(\.([0-9]+))?$ ]]; then
    ver_numeric="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}.${BASH_REMATCH[5]:-0}"
  else
    ver_numeric="$(printf '%s' "$VER" | sed -E 's/[^0-9]+/./g; s/^\.+//; s/\.+$//')"
    parts=(${ver_numeric//./ })
    if [[ ${#parts[@]} -lt 3 ]]; then
      ver_numeric=""
    else
      while [[ ${#parts[@]} -lt 4 ]]; do ver_numeric="$ver_numeric.0"; parts+=("0"); done
    fi
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
