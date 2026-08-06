#!/usr/bin/env python3
"""config.json 合并逻辑的单元验证。

本脚本逐条镜像 installer.nsi 中 MergeAndWriteConfig / ReadExistingDisabledPlugins
的 NSIS 实现（';' 分隔列表 + 插入排序去重 + 从模板重建 JSON），验证：
  - 旧 disabledPlugins 保留并与新选择合并（升序、去重）
  - worktreeId / pluginDirs 固定覆盖
  - Lite 模式不收集未勾选插件
  - 空列表不写出 disabledPlugins 字段
  - 输出可被 json.load 解析且符合 RuntimeConfigValidator 约束

注意：与 NSIS 一致，既有 config 的其它字段（logLevel/hooks 等）不保留，
由运行时默认值补全（见 installer.nsi 注释；configure_turboism.ps1 完整保留）。
"""

import json
import sys


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


def installer_write_config(mode, unchecked, existing_text):
    existing = extract_existing_disabled(existing_text) if existing_text is not None else []
    if mode == "lite":
        unchecked = []                            # Lite 模式不收集未勾选插件
    return build_config_json(nsis_merge(unchecked, existing))


def check(name, cond, detail=""):
    if not cond:
        print(f"FAIL: {name} {detail}")
        sys.exit(1)
    print(f"  ok: {name}")


def main():
    a = "dev.turboism.plugin.demo"
    b = "dev.turboism.plugin.logfilter"
    c = "dev.turboism.plugin.ui-theme"

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

    # T3: Full、未勾选 {c}、既有 {b,a} → 合并升序
    existing = json.dumps({"format": "turboism.runtime.config", "schemaVersion": 1,
                           "worktreeId": "old-wt", "pluginDirs": ["plugins"],
                           "disabledPlugins": [b, a]}, indent=2)
    out = installer_write_config("full", [c], existing)
    doc = json.loads(out)
    check("T3 合并升序", doc["disabledPlugins"] == [a, b, c], str(doc.get("disabledPlugins")))
    check("T3 worktreeId 覆盖", doc["worktreeId"] == "turboism-runtime")

    # T4: Full、全选、既有 {a} → 保留既有
    existing = '{"disabledPlugins": ["' + a + '"]}'
    out = installer_write_config("full", [], existing)
    doc = json.loads(out)
    check("T4 保留既有", doc["disabledPlugins"] == [a])

    # T5: Lite、无既有配置 → 模板（无 disabledPlugins）
    out = installer_write_config("lite", [a, b], None)
    doc = json.loads(out)
    check("T5 lite 无 disabledPlugins", "disabledPlugins" not in doc)

    # T6: Lite、既有 {a,b} → 保留既有
    existing = '{"disabledPlugins": ["' + b + '","' + a + '"]}'
    out = installer_write_config("lite", [c], existing)
    doc = json.loads(out)
    check("T6 lite 保留既有", doc["disabledPlugins"] == [a, b], str(doc.get("disabledPlugins")))

    # T7: 去重 —— 未勾选 {a}、既有 {a,b}
    out = installer_write_config("full", [a], '{"disabledPlugins": ["' + a + '","' + b + '"]}')
    doc = json.loads(out)
    check("T7 去重", doc["disabledPlugins"] == [a, b], str(doc.get("disabledPlugins")))

    # T8: 既有无 disabledPlugins + 未勾选 {z} → 新写出
    out = installer_write_config("full", [a], '{"worktreeId": "x"}')
    doc = json.loads(out)
    check("T8 无既有时新写出", doc["disabledPlugins"] == [a])

    # T9: 多行/紧凑混合格式的既有配置（运行时可读样式）
    existing = ('{\n  "format": "turboism.runtime.config",\n  "schemaVersion": 1,\n'
                '  "worktreeId": "turboism-runtime",\n  "pluginDirs": ["plugins"],\n'
                '  "disabledPlugins": ["' + c + '", "' + a + '"],\n  "logLevel": "DEBUG"\n}')
    out = installer_write_config("full", [b], existing)
    doc = json.loads(out)
    check("T9 既有样式兼容", doc["disabledPlugins"] == [a, b, c], str(doc.get("disabledPlugins")))
    check("T9 其它字段按文档不保留", "logLevel" not in doc)

    # T10: 全部 22 插件全禁场景（排序规模）
    all_ids = ["dev.turboism.plugin.p%02d" % i for i in range(22)]
    existing = '{"disabledPlugins": ["%s"]}' % '","'.join(reversed(all_ids))
    out = installer_write_config("full", [all_ids[0]], existing)
    doc = json.loads(out)
    check("T10 全量排序", doc["disabledPlugins"] == sorted(all_ids))

    # 输出有效性：schemaVersion/format 完整
    for label, out in [("T3", out)]:
        doc = json.loads(out)
        assert doc["format"] == "turboism.runtime.config" and doc["schemaVersion"] == 1

    print("config merge 模拟验证通过：10 个用例全部 ok")


if __name__ == "__main__":
    main()
