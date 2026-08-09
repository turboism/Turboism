#!/usr/bin/env bash
# Lane A: Turboism Windows release payload assembly.
#
# 用法: assemble-release.sh <version> [--skip-nsis]
#
# 流程:
#   1. Gradle 组装共享 payload（stageInstallerPayload，与 Java 安装器同源：
#      turboism-agent.jar、plugins/<module>.jar、config.template.json、
#      启动器、README.txt / README.zh.txt / README.ja.txt、LICENSE.txt）
#   2. 从每个插件 jar 的 META-INF/turboism/plugin.json 读取 id/name/version/
#      description，生成 plugin-sections.nsh
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

# ---------- 1. Gradle 组装共享 payload（Java 安装器 / NSIS / ZIP 同源） ----------
echo "[assemble] gradle: stageInstallerPayload"
./gradlew stageInstallerPayload -PinstallerVersion="$VER" --console=plain
if [[ ! -f "$stage/turboism-agent.jar" || ! -d "$stage/plugins" ]]; then
  echo "error: staged payload incomplete at $stage" >&2
  exit 1
fi
mkdir -p "$dist"

# ---------- 3. 生成 plugin-sections.nsh ----------
python3 - "$stage" "$pkg_dir/plugin-sections.nsh" <<'PYEOF'
"""从 staging 插件 jar 的 META-INF/turboism/plugin.json 生成 NSIS 插件 Section。"""
import json
import re
import sys
import zipfile
from pathlib import Path

stage = Path(sys.argv[1])
out = Path(sys.argv[2])

def nsis_escape(s: str) -> str:
    return s.replace("$", "$$").replace('"', '$\\"').replace("\r", " ").replace("\n", " ")

plugins = []
for jar in sorted(stage.glob("plugins/*.jar")):
    with zipfile.ZipFile(jar) as z:
        try:
            meta = json.loads(z.read("META-INF/turboism/plugin.json"))
        except KeyError:
            sys.exit(f"error: {jar}: missing META-INF/turboism/plugin.json")
    pid = meta["id"]
    if pid == "turboism.core":
        sys.exit(f"error: {jar}: runtime-owned core ID must not be packaged")
    plugins.append({
        "module": jar.stem,
        "id": pid,
        "name": meta.get("name", pid),
        "version": meta.get("version", ""),
        "description": meta.get("description", ""),
    })
plugins.sort(key=lambda p: p["id"])

lines = []
lines.append("; 由 assemble-release.sh 从插件 jar 的 META-INF/turboism/plugin.json 生成，勿手改。")
lines.append("; 每个插件一个 Section；Lite 模式由 ModeLeave 取消选中，Section 体不执行。")
lines.append("")

def sanitize(s: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "_", s)

for p in plugins:
    sec = "SEC_" + sanitize(p["id"])
    title = p["name"] + (" " + p["version"] if p["version"] else "")
    lines.append(f'Section "{nsis_escape(title)}" {sec}')
    lines.append('  SetOutPath "$INSTDIR\\plugins"')
    lines.append(f'  File "/oname={p["module"]}.jar" "${{STAGING_DIR}}/plugins/{p["module"]}.jar"')
    lines.append("SectionEnd")
    lines.append("")

lines.append("; 组件页悬停描述")
lines.append("!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN")
for p in plugins:
    sec = "SEC_" + sanitize(p["id"])
    lines.append(f'  !insertmacro MUI_DESCRIPTION_TEXT ${{{sec}}} "{nsis_escape(p["description"])}"')
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
# zip 内容与历史发布一致：config.json 由共享 payload 的 config.template.json
# 生成；Java 安装器专属文件（config.template.json、README.java-installer.txt、
# uninstall.command）不进入 Windows zip。
zip_dir() {
  local src="$1" out="$2" lite="$3"
  python3 - "$src" "$out" "$lite" <<'PYEOF'
import os, sys, zipfile
src, out, lite = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
EXCLUDED = {"config.template.json", "README.java-installer.txt", "uninstall.command"}
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        dirs.sort()
        for f in sorted(files):
            if f in EXCLUDED:
                continue
            if lite and os.path.dirname(os.path.relpath(os.path.join(root, f), src)):
                continue  # lite：仅顶层文件（不含 plugins/）
            full = os.path.join(root, f)
            arc = os.path.relpath(full, src).replace(os.sep, "/")
            z.write(full, arc)
    # 历史契约：zip 内含 config.json（模板内容）
    z.writestr("config.json", open(os.path.join(src, "config.template.json"), "rb").read())
PYEOF
}

zip_dir "$stage" "$dist/turboism-$VER-full.zip" 0
zip_dir "$stage" "$dist/turboism-$VER-lite.zip" 1
# sidecar 内容为仓库根相对路径：`sha256sum -c build/windows-installer/dist/*.sha256`
# 需在仓库根目录执行（最终验证批次约定）
(
  cd "$repo_root"
  sha256sum "build/windows-installer/dist/turboism-$VER-lite.zip" > "build/windows-installer/dist/turboism-$VER-lite.zip.sha256"
  sha256sum "build/windows-installer/dist/turboism-$VER-full.zip" > "build/windows-installer/dist/turboism-$VER-full.zip.sha256"
)

# ---------- 5. NSIS 安装器 ----------
if [[ "$SKIP_NSIS" == "1" ]]; then
  echo "[assemble] --skip-nsis: 跳过 makensis"
else
  if ! command -v makensis >/dev/null 2>&1; then
    echo "error: makensis not found; run with --skip-nsis or install nsis" >&2
    exit 1
  fi
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
    -DOUT_DIR="$dist"
    -DLICENSE_FILE="$repo_root/LICENSE"
  )
  if [[ -n "$ver_numeric" ]]; then
    nsis_args+=(-DVER_NUMERIC="$ver_numeric")
    echo "[assemble] VIProductVersion: $ver_numeric"
  fi
  makensis "${nsis_args[@]}" "$pkg_dir/installer.nsi"
  (
    cd "$repo_root"
    sha256sum "build/windows-installer/dist/TurboismInstaller-$VER.exe" > "build/windows-installer/dist/TurboismInstaller-$VER.exe.sha256"
  )
fi

# ---------- 汇总 ----------
echo
echo "[assemble] 产物:"
find "$dist" -maxdepth 1 -type f -printf '  %f (%s bytes)\n' | LC_ALL=C sort
echo "[assemble] staging: $stage"
