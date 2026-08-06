#!/usr/bin/env bash
# Lane A: Turboism Windows release payload assembly.
#
# 用法: assemble-release.sh <version> [--skip-nsis]
#
# 流程:
#   1. ./gradlew :bootstrap:jar + 各 :plugins:<module>:jar
#   2. 组装 staging（turboism-agent.jar、plugins/<module>.jar、config.json、
#      启动器、README.txt、LICENSE.txt）
#   3. 从每个插件 jar 的 META-INF/turboism/plugin.json 读取 id/name/version/
#      description，生成 plugin-sections.nsh
#   4. 产出 turboism-<ver>-lite.zip / turboism-<ver>-full.zip + .sha256
#   5. makensis 构建 TurboismInstaller-<ver>.exe + .sha256（--skip-nsis 跳过）
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

worktree_id="${TURBOISM_WORKTREE_ID:-$(bash "$repo_root/scripts/dev/worktree-id.sh")}"
# 固定路径：installer.nsi 的缺省 STAGING_DIR/OUT_DIR 与此一致，支持独立 makensis 编译
stage="$repo_root/build/windows-installer/staging"
stage_lite="$repo_root/build/windows-installer/staging-lite"
dist="$repo_root/build/windows-installer/dist"

# ---------- 1. Gradle 构建 ----------
tasks=(":bootstrap:jar")
plugin_modules=()
for m in "$repo_root"/plugins/*/; do
  m="$(basename "$m")"
  [[ "$m" == "core" ]] && continue          # core 为运行时内置，不出现在 payload
  plugin_modules+=("$m")
  tasks+=(":plugins:$m:jar")
done
if [[ ${#plugin_modules[@]} -eq 0 ]]; then
  echo "error: no plugin modules found under plugins/" >&2
  exit 1
fi
echo "[assemble] gradle: ${tasks[*]}"
./gradlew "${tasks[@]}" --console=plain

# ---------- 2. 组装 staging ----------
rm -rf "$stage" "$stage_lite"
mkdir -p "$stage/plugins" "$stage_lite" "$dist"

pick_jar() {
  # $1 = glob；输出最新匹配的 jar（排除 sources/javadoc）
  local glob="$1" f=""
  # shellcheck disable=SC2086
  f="$(ls -t $glob 2>/dev/null | grep -v -- '-sources\.jar$' | grep -v -- '-javadoc\.jar$' | head -1)"
  if [[ -z "$f" || ! -f "$f" ]]; then
    echo "error: no jar matching: $glob" >&2
    exit 1
  fi
  printf '%s\n' "$f"
}

agent_jar="$(pick_jar "$repo_root/build/worktree/$worktree_id/bootstrap/libs/turboism-agent-*.jar")"
cp "$agent_jar" "$stage/turboism-agent.jar"
echo "[assemble] agent: $agent_jar"

for m in "${plugin_modules[@]}"; do
  jar="$(pick_jar "$repo_root/build/worktree/$worktree_id/$m/libs/$m-*.jar")"
  cp "$jar" "$stage/plugins/$m.jar"
  echo "[assemble] plugin: $jar"
done

cp "$pkg_dir/config.template.json" "$stage/config.json"
cp "$pkg_dir/launch-cubism-turboism.bat" "$stage/"
cp "$pkg_dir/launch-cubism-turboism.ps1" "$stage/"
sed "s/__VERSION__/$VER/g" "$pkg_dir/README.txt.template" > "$stage/README.txt"
cp "$repo_root/LICENSE" "$stage/LICENSE.txt"

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
# 使用 python3 zipfile（避免依赖 zip CLI）
zip_dir() {
  local src="$1" out="$2"
  python3 - "$src" "$out" <<'PYEOF'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        dirs.sort()
        for f in sorted(files):
            full = os.path.join(root, f)
            arc = os.path.relpath(full, src).replace(os.sep, "/")
            z.write(full, arc)
PYEOF
}

zip_dir "$stage" "$dist/turboism-$VER-full.zip"
# lite：staging 顶层文件（不含 plugins/）
(
  cd "$stage"
  find . -maxdepth 1 -type f -exec cp -t "$stage_lite/" {} +
)
zip_dir "$stage_lite" "$dist/turboism-$VER-lite.zip"
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
    cd "$dist"
    sha256sum "TurboismInstaller-$VER.exe" > "TurboismInstaller-$VER.exe.sha256"
  )
fi

# ---------- 汇总 ----------
echo
echo "[assemble] 产物:"
find "$dist" -maxdepth 1 -type f -printf '  %f (%s bytes)\n' | LC_ALL=C sort
echo "[assemble] staging: $stage"
