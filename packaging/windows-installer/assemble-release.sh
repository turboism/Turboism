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

# ---------- 1. Gradle 组装共享 payload（Java 安装器 / NSIS / ZIP 同源） ----------
echo "[assemble] gradle: stageInstallerPayload"
./gradlew stageInstallerPayload -PinstallerVersion="$VER" --console=plain
if [[ ! -f "$stage/turboism-agent.jar" || ! -d "$stage/plugins" ]]; then
  echo "error: staged payload incomplete at $stage" >&2
  exit 1
fi
mkdir -p "$dist"

# ---------- 3. 生成 plugin-sections.nsh ----------
python3 - "$stage" "$pkg_dir/plugin-sections.nsh" "$repo_root/packaging/release-plugins.txt" <<'PYEOF'
"""按 release-plugins.txt（唯一权威）生成 NSIS 插件 Section，并校验 staging 与清单严格一致。

fail-closed 规则：清单缺失/空行/注释/非插件项/重复/未排序即退出；staging 中的插件
JAR 必须与清单（不含运行时 core）逐项一致 —— 多出的 JAR（含已排除的占位插件）或
缺失的 JAR 均使组装失败。
"""
import json
import re
import sys
import zipfile
from pathlib import Path

stage = Path(sys.argv[1])
out = Path(sys.argv[2])
manifest = Path(sys.argv[3])

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
    pid = meta["id"]
    if pid == "turboism.core":
        sys.exit(f"error: {jar}: runtime-owned core ID must not be packaged")
    plugins.append({
        "module": module,
        "id": pid,
        "name": meta.get("name", pid),
        "version": meta.get("version", ""),
        "description": meta.get("description", ""),
    })
plugins.sort(key=lambda p: p["id"])

lines = []
lines.append("; 由 assemble-release.sh 按 release-plugins.txt 权威清单生成，勿手改。")
lines.append("; Full($Mode==1) 由隐藏载荷 Section 安装全部插件 JAR；可见 Section 只承载")
lines.append("; 勾选状态（disabledPlugins 元数据）；Lite 模式由 ModeLeave 取消全部可见 Section。")
lines.append("")

# 隐藏载荷 Section：Full 模式安装全部插件 JAR；Lite 模式不写任何 JAR（$Mode 守卫）。
# 隐藏 Section 不出现在组件页，用户无法取消选中；勾选只控制 disabledPlugins。
lines.append('Section "-插件载荷" SecPluginPayload')
lines.append("  ${If} $Mode == 1")
lines.append('    SetOutPath "$INSTDIR\\plugins"')
for p in plugins:
    lines.append(f'    File "/oname={p["module"]}.jar" "${{STAGING_DIR}}/plugins/{p["module"]}.jar"')
lines.append("  ${EndIf}")
lines.append("SectionEnd")
lines.append("")

def sanitize(s: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "_", s)

# 可见插件 Section：仅保留勾选元数据（组件页），不携带 File 指令。
for p in plugins:
    sec = "SEC_" + sanitize(p["id"])
    title = p["name"] + (" " + p["version"] if p["version"] else "")
    lines.append(f'Section "{nsis_escape(title)}" {sec}')
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
lines.append("; 从 $existingDisabled 中逐 id 移除全部当前捆绑插件 id（重选已捆绑插件即启用）。")
lines.append("; 每个 id 通过通用 RemoveItemFromList 辅助删除，避免长度受限的合并 id 字符串。")
lines.append("Function RemoveBundledFromExistingDisabled")
for p in plugins:
    lines.append('  StrCpy $0 "$existingDisabled"')
    lines.append(f'  StrCpy $1 "{p["id"]}"')
    lines.append("  Call RemoveItemFromList")
    lines.append('  StrCpy $existingDisabled "$0"')
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
# 两种 zip 都携带顶层公共文件和 graal/lib；Lite 仅排除 plugins/，
# Full 额外携带获批插件 JAR。config.json 由共享 payload 的
# config.template.json 生成；Java 安装器专属文件（config.template.json、
# README.java-installer.txt、uninstall.command）不进入 Windows zip。
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
            full = os.path.join(root, f)
            arc = os.path.relpath(full, src).replace(os.sep, "/")
            if lite and arc.startswith("plugins/"):
                continue  # Lite excludes plugin JARs but keeps common Graal host libraries.
            z.write(full, arc)
    names = set(z.namelist())
    graal = [name for name in names if name.startswith("graal/lib/") and name.endswith(".jar")]
    if not graal:
        raise SystemExit("error: Windows zip is missing the common Graal host closure")
    if lite and any(name.startswith("plugins/") for name in names):
        raise SystemExit("error: Lite zip unexpectedly contains plugin payload")
    if not lite and not any(name.startswith("plugins/") and name.endswith(".jar") for name in names):
        raise SystemExit("error: Full zip is missing plugin payload")
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
