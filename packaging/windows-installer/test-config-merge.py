#!/usr/bin/env python3
"""config.json 合并逻辑 + Full/Lite 插件载荷语义的单元验证。

本脚本逐条镜像 installer.nsi 中 MergeAndWriteConfig / ReadExistingDisabledPlugins /
RemoveItemFromList 的 NSIS 实现（';' 分隔列表 + 逐 id 移除 + 插入排序去重 + 从模板
重建 JSON），并镜像 assemble-release.sh 生成的隐藏载荷 Section（$Mode==1 时安装
全部插件 JAR，Lite 不写任何 JAR），验证 r6 契约：
  - Full 安装始终携带全部捆绑插件 JAR；勾选只控制 disabledPlugins
  - 重选已捆绑插件 → 从既有 disabledPlugins 移除该捆绑 id（通用逐 id 删除，
    不使用长度受限的合并 id 字符串）；无关 id 保留
  - Lite 不安装任何插件 JAR；disabledPlugins 写入全部捆绑 id（Full→Lite 后
    陈旧 JAR 无法加载）
  - NSIS JSON 数组：前后缀无多余引号，项恰好一次引号、以 "," 分隔
  - worktreeId / pluginDirs 固定覆盖；空列表不写出 disabledPlugins 字段
  - 输出可被 json.load 解析且符合 RuntimeConfigValidator 约束

注意：与 NSIS 一致，既有 config 的其它字段（logLevel/hooks 等）不保留，
由运行时默认值补全（见 installer.nsi 注释；configure_turboism.ps1 完整保留）。

packaging/release-plugins.txt 仍是发布载荷的唯一权威清单：本脚本将其作为显式
16 项目 / 10 公开排除模块回归 oracle（清单漂移即失败），但下方模拟器的合成
id/module fixture 与该清单相互独立 —— Gradle 模块名不是插件 id 的通用约定
（如 atlas-maxrects-bssf、clip-mask 与 id 不同形），真实 id 由
verify-installer.py 与 assemble-release.sh 从各 JAR 的
META-INF/turboism/plugin.json 读取并逐一校验（见 SPEC.md）。
"""

import json
import re
import sys
from pathlib import Path

MANIFEST_PATH = Path(__file__).resolve().parent.parent / "release-plugins.txt"

# 冻结的 16 项目批准清单 —— 回归 oracle：清单增删/改序/公开排除模块回归即失败。
EXPECTED_PATHS = [
    ":plugins:atlas-maxrects-bssf",
    ":plugins:backup",
    ":plugins:clipmask-viewer",
    ":plugins:core",
    ":plugins:cubism-tab-filter",
    ":plugins:log-filter",
    ":plugins:mcp",
    ":plugins:mesh",
    ":plugins:palette-label-style",
    ":plugins:parameter-batch-transfer",
    ":plugins:perf-stats",
    ":plugins:physics-editor",
    ":plugins:recent-preview",
    ":plugins:scene-palette-enhancer",
    ":plugins:texture-atlas-stats",
    ":plugins:ui-theme",
]
# 十个公开排除模块：必须从清单及一切发布载荷/选择面缺席（回归 oracle）
EXCLUDED = {"bounding-box", "clip-mask", "context-menu", "demo", "parameter",
            "perf-opt", "project-inspector", "project-panel", "psd-import",
            "render-opt"}


def check(name, cond, detail=""):
    if not cond:
        print(f"FAIL: {name} {detail}")
        sys.exit(1)
    print(f"  ok: {name}")


def load_manifest():
    """回归 oracle：从唯一权威 release-plugins.txt 校验清单 —— 空行/注释/非插件项/
    重复/未排序/偏离冻结 16 项/含公开排除模块均失败。返回的模块名仅供 oracle 使用，
    不用于推导模拟器的插件 id（真实 id 以各 JAR 的 plugin.json 为准）。"""
    raw = MANIFEST_PATH.read_text(encoding="utf-8").splitlines()
    invalid = [l for l in raw if not l.strip() or l.strip().startswith("#")]
    check("清单无空行/注释", not invalid, f"found={invalid[:3]}")
    lines = [l.strip() for l in raw if l.strip() and not l.strip().startswith("#")]
    entry = re.compile(r"^:plugins:[a-z0-9-]+$")
    bad = [l for l in lines if not entry.match(l)]
    check("清单项均为插件路径", not bad, f"bad={bad[:3]}")
    check("清单无重复", len(set(lines)) == len(lines))
    check("清单按 ASCII 升序", lines == sorted(lines))
    check("清单与冻结 16 项目一致", lines == EXPECTED_PATHS, f"n={len(lines)}")
    modules = [l[len(":plugins:"):] for l in lines if l != ":plugins:core"]
    check("公开排除模块不在清单", not (set(modules) & EXCLUDED),
          f"found={set(modules) & EXCLUDED}")
    return modules


# 独立合成 fixture：三对 id/module 仅用于镜像 NSIS config 合并与载荷 Section 语义，
# 不派生自真实插件清单 —— Gradle 模块名不是插件 id 的通用约定。真实捆绑 id 由
# verify-installer.py 与 assemble-release.sh 从各 JAR 的 plugin.json 读取校验。
BUNDLED = [
    ("dev.turboism.plugin.alpha", "plugin-alpha"),
    ("dev.turboism.plugin.beta", "plugin-beta"),
    ("dev.turboism.plugin.gamma", "plugin-gamma"),
]
BUNDLED_IDS = [i for i, _ in BUNDLED]
BUNDLED_MODULES = sorted(m for _, m in BUNDLED)
UNRELATED = "dev.turboism.plugin.not-bundled"

load_manifest()  # 回归 oracle：清单漂移（增删/改序/占位回归）即失败


def split_first(lst: str):
    """镜像 NSIS SplitFirst：$0 = ';' 分隔列表 → 首段 + 剩余。"""
    if ";" in lst:
        i = lst.index(";")
        return lst[:i], lst[i + 1:]
    return lst, ""


def extract_existing_disabled(text: str):
    """镜像 NSIS ReadExistingDisabledPlugins 的字符串扫描（含 \\" 与 \\\\ 转义跳过）。"""
    needle = '"disabledPlugins"'
    pos = text.find(needle)
    if pos == -1:
        return []
    pos += len(needle)
    while pos < len(text) and text[pos] != "[":
        pos += 1
    if pos >= len(text):
        return []
    pos += 1
    ids = []
    while pos < len(text):
        ch = text[pos]
        if ch == "]":
            break
        if ch == '"':
            pos += 1
            buf = ""
            while pos < len(text):
                ch = text[pos]
                if ch == "\\":          # 跳过转义字符
                    pos += 2
                    continue
                if ch == '"':
                    pos += 1
                    break
                buf += ch
                pos += 1
            if buf:
                ids.append(buf)
        else:
            pos += 1
    return ids


def remove_item(lst, item):
    """镜像 NSIS RemoveItemFromList：删除全部匹配项，其余保持原序（逐 id 调用）。"""
    return [x for x in lst if x != item]


def nsis_merge(unchecked, existing):
    """镜像 NSIS MergeAndWriteConfig：拼接 → 逐项插入排序（升序、去重）。"""
    combined = list(unchecked) + list(existing)   # NSIS: $unchecked;$existing
    sorted_list = []
    while combined:
        ident = combined.pop(0)                   # NSIS: SplitFirst $disabledFinal
        head = []
        walk = list(sorted_list)
        while True:
            if not walk:
                head.append(ident)
                break
            cand = walk.pop(0)
            if cand == ident:                     # 重复：保留既有项
                head.append(cand)
                head.extend(walk)
                break
            if cand > ident:                      # StrCmp greater → 插到 cand 前
                head.append(ident)
                head.append(cand)
                head.extend(walk)
                break
            head.append(cand)                     # cand < ident：继续
        sorted_list = head
    return sorted_list


def build_config_json(disabled):
    parts = ['{"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"turboism-runtime","pluginDirs":["plugins"]']
    if disabled:
        parts.append(',"disabledPlugins":["' + '","'.join(disabled) + '"]')
    parts.append("}\r\n")
    return "".join(parts)


def installer_write_config(mode, unchecked, existing_text, bundled_ids=BUNDLED_IDS):
    """镜像 SecConfig → MergeAndWriteConfig：
    - 先由 RemoveBundledFromExistingDisabled 从既有列表逐 id 移除全部捆绑 id；
    - 再合并本次未勾选插件（Lite 下 ModeLeave 已取消全部 Section → 全部捆绑 id）。"""
    existing = extract_existing_disabled(existing_text) if existing_text is not None else []
    for bid in bundled_ids:
        existing = remove_item(existing, bid)
    if mode == "lite":
        unchecked = list(bundled_ids)             # Lite 模式收集全部捆绑 id
    return build_config_json(nsis_merge(unchecked, existing))


def nsis_jars_after(mode, prev_jars):
    """镜像隐藏载荷 Section：Full($Mode==1) 安装全部插件 JAR；Lite 不写任何 JAR
    （此前安装的 JAR 保留，但被 disabledPlugins=全部捆绑 id 禁用）。"""
    if mode == "full":
        return sorted(BUNDLED_MODULES)
    return sorted(prev_jars)


def main():
    a, b, c = BUNDLED_IDS

    # T1: Full、全选、无既有配置 → 模板（无 disabledPlugins）
    out = installer_write_config("full", [], None)
    doc = json.loads(out)
    check("T1 模板字段", doc["worktreeId"] == "turboism-runtime" and doc["pluginDirs"] == ["plugins"]
          and doc["format"] == "turboism.runtime.config" and doc["schemaVersion"] == 1)
    check("T1 无 disabledPlugins", "disabledPlugins" not in doc)

    # T2: Full、未勾选 2 个、无既有配置 → 升序写出
    out = installer_write_config("full", [c, a], None)
    doc = json.loads(out)
    check("T2 升序", doc["disabledPlugins"] == [a, c], str(doc.get("disabledPlugins")))
    # T2b: JSON 数组形状 —— 前后缀无多余引号，项恰好一次引号、以 "," 分隔
    expected_fragment = '"disabledPlugins":["' + a + '","' + c + '"]'
    check('T2b JSON 数组形状（无多余引号、"," 分隔）', expected_fragment in out,
          "fragment=%s" % expected_fragment + " out=%s" % out[:160])

    # T3: Full、未勾选 {b}、既有 {u2, a, u1}（u1/u2 无关、a 为捆绑）
    #     → 捆绑 a 被移除（重选启用），无关 id 保留，合并升序
    existing = json.dumps({"format": "turboism.runtime.config", "schemaVersion": 1,
                           "worktreeId": "old-wt", "pluginDirs": ["plugins"],
                           "disabledPlugins": [UNRELATED + ".2", a, UNRELATED + ".1"]}, indent=2)
    out = installer_write_config("full", [b], existing)
    doc = json.loads(out)
    check("T3 合并升序且无关保留", doc["disabledPlugins"] == sorted([b, UNRELATED + ".1", UNRELATED + ".2"]),
          str(doc.get("disabledPlugins")))
    check("T3 已捆绑被移除（重选启用）", a not in doc.get("disabledPlugins", []))
    check("T3 worktreeId 覆盖", doc["worktreeId"] == "turboism-runtime")

    # T4: Full、全选、既有 {a, u} → 捆绑 a 移除，无关 u 保留
    existing = '{"disabledPlugins": ["' + a + '","' + UNRELATED + '"]}'
    out = installer_write_config("full", [], existing)
    doc = json.loads(out)
    check("T4 全选后捆绑启用、无关保留", doc["disabledPlugins"] == [UNRELATED], str(doc.get("disabledPlugins")))

    # T4b: 回归 —— 既有配置含重复的捆绑 id（同一 id 多次出现），后续 Full 重选
    #       必须移除全部副本，无关 id 保留
    existing = '{"disabledPlugins": ["' + a + '","' + UNRELATED + '","' + a + '"]}'
    out = installer_write_config("full", [b], existing)
    doc = json.loads(out)
    check("T4b 重复捆绑 id 全部移除、无关保留", doc["disabledPlugins"] == [b, UNRELATED],
          str(doc.get("disabledPlugins")))

    # T5: Lite、无既有配置 → 全部捆绑 id 写入 disabledPlugins（无插件 JAR）
    out = installer_write_config("lite", [a, b], None)
    doc = json.loads(out)
    check("T5 lite 禁用全部捆绑 id", doc["disabledPlugins"] == BUNDLED_IDS, str(doc.get("disabledPlugins")))

    # T6: Lite、既有 {b, u} → 捆绑 b 移除后并入全部捆绑 id，无关 u 保留
    existing = '{"disabledPlugins": ["' + b + '","' + UNRELATED + '"]}'
    out = installer_write_config("lite", [c], existing)
    doc = json.loads(out)
    check("T6 lite 全部捆绑 + 无关保留", doc["disabledPlugins"] == sorted(BUNDLED_IDS + [UNRELATED]),
          str(doc.get("disabledPlugins")))

    # T7: 去重 —— 未勾选 {a}、既有 {a, u}
    out = installer_write_config("full", [a], '{"disabledPlugins": ["' + a + '","' + UNRELATED + '"]}')
    doc = json.loads(out)
    check("T7 去重", doc["disabledPlugins"] == [a, UNRELATED], str(doc.get("disabledPlugins")))

    # T8: 既有无 disabledPlugins + 未勾选 {a} → 新写出
    out = installer_write_config("full", [a], '{"worktreeId": "x"}')
    doc = json.loads(out)
    check("T8 无既有时新写出", doc["disabledPlugins"] == [a])

    # T9: 多行/紧凑混合格式的既有配置（运行时可读样式）
    existing = ('{\n  "format": "turboism.runtime.config",\n  "schemaVersion": 1,\n'
                '  "worktreeId": "turboism-runtime",\n  "pluginDirs": ["plugins"],\n'
                '  "disabledPlugins": ["' + c + '", "' + a + '"],\n  "logLevel": "DEBUG"\n}')
    out = installer_write_config("full", [b], existing)
    doc = json.loads(out)
    check("T9 既有样式兼容（捆绑移除、无关无）", doc["disabledPlugins"] == [b], str(doc.get("disabledPlugins")))
    check("T9 其它字段按文档不保留", "logLevel" not in doc)

    # T10: 全量场景 —— 既有全部 22 个捆绑 id（逆序）+ 无关，未勾选 {p00}
    all_ids = ["dev.turboism.plugin.p%02d" % i for i in range(22)]
    existing = '{"disabledPlugins": ["%s"]}' % '","'.join(list(reversed(all_ids)) + [UNRELATED])
    out = installer_write_config("full", [all_ids[0]], existing, bundled_ids=all_ids)
    doc = json.loads(out)
    check("T10 全量捆绑移除 + 无关保留", doc["disabledPlugins"] == sorted([all_ids[0], UNRELATED]),
          str(doc.get("disabledPlugins")))

    # ---- 插件载荷库存模拟（隐藏载荷 Section + 勾选语义）----
    # TI1: 全新部分 Full（未勾选 {a, c}）→ JAR 全量安装；disabledPlugins=[a,c]
    jars = nsis_jars_after("full", [])
    check("TI1 Full 安装全部 JAR", jars == BUNDLED_MODULES, str(jars))
    out = installer_write_config("full", [a, c], None)
    doc = json.loads(out)
    check("TI1 disabledPlugins == 未勾选", doc["disabledPlugins"] == [a, c], str(doc.get("disabledPlugins")))

    # TI2: 后续重选 Full（未勾选 {b}，既有 TI1 配置）→ JAR 库存完整；
    #     配置 = (既有 - 捆绑) ∪ 本次未勾选
    out = installer_write_config("full", [b], out)
    doc = json.loads(out)
    jars = nsis_jars_after("full", jars)
    check("TI2 重选后 JAR 库存完整", jars == BUNDLED_MODULES, str(jars))
    check("TI2 重选后配置跟随当前选择", doc["disabledPlugins"] == [b], str(doc.get("disabledPlugins")))

    # TI2b: 既有包含无关 id 的重选 → 无关保留
    existing = '{"disabledPlugins": ["' + a + '","' + UNRELATED + '"]}'
    out = installer_write_config("full", [b], existing)
    doc = json.loads(out)
    check("TI2b 重选保留无关 id", doc["disabledPlugins"] == [b, UNRELATED], str(doc.get("disabledPlugins")))

    # TI3: 全新 Lite → 无插件 JAR；禁用全部捆绑 id
    jars = nsis_jars_after("lite", [])
    check("TI3 Lite 全新无插件 JAR", jars == [], str(jars))
    out = installer_write_config("lite", [], None)
    doc = json.loads(out)
    check("TI3 Lite 禁用全部捆绑 id", doc["disabledPlugins"] == BUNDLED_IDS, str(doc.get("disabledPlugins")))

    # TI4: Full→Lite → 不写新 JAR（旧 JAR 留盘但被禁用）
    jars = nsis_jars_after("lite", BUNDLED_MODULES)
    check("TI4 Full→Lite 不写新 JAR（旧 JAR 留盘）", jars == BUNDLED_MODULES, str(jars))
    existing = '{"disabledPlugins": ["' + a + '"]}'
    out = installer_write_config("lite", [], existing)
    doc = json.loads(out)
    check("TI4 Full→Lite 禁用全部捆绑 id", doc["disabledPlugins"] == BUNDLED_IDS, str(doc.get("disabledPlugins")))

    # 输出有效性：schemaVersion/format 完整
    for label, out in [("T3", out)]:
        doc = json.loads(out)
        assert doc["format"] == "turboism.runtime.config" and doc["schemaVersion"] == 1

    print("config merge + payload 模拟验证通过：15 个用例全部 ok")


if __name__ == "__main__":
    main()
