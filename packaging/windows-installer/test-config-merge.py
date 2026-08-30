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
  - 卸载失败关闭后置：托管清理返回 0 后状态文件仍存在则中止，顺序为
    清理调用/$0 非零守卫 -> cubism-installations.json 失败关闭守卫 ->
    DeleteRegKey/载荷删除；LICENSE 删除使用精确安装基线名 LICENSE
    （见 Uninstall Section 镜像检查，行号级断言）
  - worktreeId / pluginDirs 固定覆盖；空列表不写出 disabledPlugins 字段
  - 输出可被 json.load 解析且符合 RuntimeConfigValidator 约束

注意：与 NSIS 一致，既有 config 的其它字段（logLevel/hooks 等）不保留，
由运行时默认值补全（见 installer.nsi 注释；configure_turboism.ps1 完整保留）。

packaging/release-plugins.txt 仍是发布载荷的唯一权威清单：本脚本将其作为显式
18 项目 / 7 公开排除模块回归 oracle（清单漂移即失败），但下方模拟器的合成
id/module fixture 与该清单相互独立 —— Gradle 模块名不是插件 id 的通用约定
（如 atlas-maxrects-bssf 与 id 不同形），真实 id 由
verify-installer.py 与 assemble-release.sh 从各 JAR 的
META-INF/turboism/plugin.json 读取并逐一校验（见 SPEC.md）。
"""

import json
import re
import sys
from pathlib import Path

MANIFEST_PATH = Path(__file__).resolve().parent.parent / "release-plugins.txt"
INSTALLER_NSI = Path(__file__).resolve().parent / "installer.nsi"
EULA_DIR = Path(__file__).resolve().parent.parent / "eula"
ICON_DIR = Path(__file__).resolve().parent / "assets"

# 冻结的 18 项目批准清单 —— 回归 oracle：清单增删/改序/公开排除模块回归即失败。
EXPECTED_PATHS = [
    ":plugins:atlas-maxrects-bssf",
    ":plugins:backup",
    ":plugins:clipmask-viewer",
    ":plugins:core",
    ":plugins:cubism-tab-filter",
    ":plugins:history-panel",
    ":plugins:mcp",
    ":plugins:mesh-edit-mirror-axis-enhance",
    ":plugins:palette-label-style",
    ":plugins:parameter-batch-transfer",
    ":plugins:perf-stats",
    ":plugins:physics-editor",
    ":plugins:psd-clip-mask-import",
    ":plugins:recent-preview",
    ":plugins:scene-palette-enhancer",
    ":plugins:texture-atlas-stats",
    ":plugins:turboism-with-fx",
    ":plugins:ui-theme",
]
# 七个公开排除模块：必须从清单及一切发布载荷/选择面缺席（回归 oracle）
EXCLUDED = {"bounding-box", "context-menu", "demo", "parameter",
            "project-inspector", "project-panel", "psd-import"}


def check(name, cond, detail=""):
    if not cond:
        print(f"FAIL: {name} {detail}")
        sys.exit(1)
    print(f"  ok: {name}")


def load_manifest():
    """回归 oracle：从唯一权威 release-plugins.txt 校验清单 —— 空行/注释/非插件项/
    重复/未排序/偏离冻结 18 项/含公开排除模块均失败。返回的模块名仅供 oracle 使用，
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
    check("清单与冻结 18 项目一致", lines == EXPECTED_PATHS, f"n={len(lines)}")
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
RETIRED_IDS = [
    "dev.turboism.plugin.clipmask",
    "dev.turboism.plugin.logfilter",
    "dev.turboism.plugin.perfopt",
    "dev.turboism.plugin.renderopt",
]
RETIRED_MODULES = ["clip-mask", "log-filter", "perf-opt", "render-opt"]

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
    parts = ['{"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"turboism-runtime","pluginDirs":["plugins"],"launcher":{"cubismJvm":"graalvm"}']
    if disabled:
        parts.append(',"disabledPlugins":["' + '","'.join(disabled) + '"]')
    parts.append("}\r\n")
    return "".join(parts)


def installer_write_config(mode, unchecked, existing_text, bundled_ids=BUNDLED_IDS):
    """镜像 SecConfig → MergeAndWriteConfig：
    - 先由 RemoveBundledFromExistingDisabled 从既有列表逐 id 移除全部捆绑 id；
    - 再合并本次未勾选插件（Lite 下 ModeLeave 已取消全部 Section → 全部捆绑 id）。"""
    existing = extract_existing_disabled(existing_text) if existing_text is not None else []
    for retired in RETIRED_IDS:
        existing = remove_item(existing, retired)
    for bid in bundled_ids:
        existing = remove_item(existing, bid)
    if mode == "lite":
        unchecked = list(bundled_ids)             # Lite 模式收集全部捆绑 id
    return build_config_json(nsis_merge(unchecked, existing))


def nsis_jars_after(mode, prev_jars):
    """镜像 NSIS 升级：先按受控历史模块名退休旧官方 JAR，再执行
    隐藏载荷 Section。Full($Mode==1) 安装全部当前插件，Lite 不写新 JAR。"""
    retained = [module for module in prev_jars if module not in RETIRED_MODULES]
    if mode == "full":
        return sorted(set(retained) | set(BUNDLED_MODULES))
    return sorted(retained)


def uninstall_statements():
    """按出现顺序返回 Uninstall Section 的非空/非注释语句（行号, 文本）。"""
    lines = INSTALLER_NSI.read_text(encoding="utf-8").splitlines()
    start = next(i for i, l in enumerate(lines) if l.strip() == 'Section "Uninstall"')
    end = next(i for i in range(start + 1, len(lines)) if lines[i].strip() == "SectionEnd")
    return [(i + 1, l.strip()) for i, l in enumerate(lines[start + 1:end])
            if l.strip() and not l.strip().startswith(";")]


def find_stmt(texts, pred):
    for i, t in enumerate(texts):
        if pred(t):
            return i
    return None


def check_nsis_retirement_contract():
    """NSIS 升级必须与 Java 安装器共同退休四个历史模块/id。"""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    call = "-RetirePlugins"
    check("R1 NSIS 通过身份校验 helper 执行退休", call in text)
    check("R1 NSIS 从当前 staging 提取退休 helper",
          '${STAGING_DIR}/configure_turboism.ps1' in text
          and '${STAGING_DIR}/cubism-launch-common.ps1' in text
          and '$PLUGINSDIR\\Turboism-retire' in text)
    lines = text.splitlines()
    exec_index = next(i for i, line in enumerate(lines)
                      if "ExecWait" in line and call in line)
    guard = "\n".join(lines[exec_index:exec_index + 6])
    check("R1 NSIS 退休失败关闭",
          "$0 != 0" in guard and "PluginRetireError" in guard and "Abort" in guard)
    check("R1 配置器公开 RetirePlugins 模式",
          "[switch]$RetirePlugins" in configure
          and "Remove-TurboismRetiredPlugins" in configure)
    check("R1 退休授权读取嵌入 plugin.json id",
          "function Remove-TurboismRetiredPlugins" in common
          and "META-INF/turboism/plugin.json" in common)
    for ident in RETIRED_IDS:
        check("R1 身份校验 helper 包含退休 id " + ident,
              ident in common)
        check("R2 NSIS 从 disabledPlugins 删除退休 id " + ident,
              'StrCpy $1 "%s"' % ident in text)


def check_managed_graal_installer_contract():
    """Installer-time managed GraalVM remains opt-in and reuses the pinned Java service."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    bridge_path = INSTALLER_NSI.parent / "install-managed-graal.ps1"
    bridge = bridge_path.read_text(encoding="utf-8")
    check("GI1 managed Graal option defaults to later",
          "StrCpy $installManagedGraal 0" in text
          and "${NSD_Check} $GraalLaterRadio" in text)
    check("GI2 managed Graal choice is independent of Full/Lite",
          "${NSD_CreateRadioButton}" in text and "$(GraalNowChoice)" in text
          and "$(GraalLaterChoice)" in text and "Function ModeLeave" in text)
    check("GI3 no install occurs unless selected",
          "${If} $installManagedGraal == 1" in text
          and "install-managed-graal.ps1" in text)
    graal_start = text.index('Section "-托管 GraalVM"')
    graal_end = text.index("SectionEnd", graal_start)
    graal_section = text[graal_start:graal_end]
    check("GI4 selected failure warns and continues installer",
          "ManagedGraalInstallError" in graal_section
          and "DetailPrint" in graal_section
          and "MB_ICONEXCLAMATION" in graal_section
          and "Abort" not in graal_section)
    check("GI4b remaining configuration follows optional Graal section",
          graal_end < text.index('Section "-写入配置"'))
    check("GI5 bridge invokes only the managed runtime CLI",
          "dev.turboism.graal.ManagedGraalRuntimeCli install" in bridge
          and "https://" not in bridge and "sha256" not in bridge.lower())
    check("GI6 bridge requires Java 17 or newer",
          "Test-TurboismJava17" in bridge and "-ge 17" in bridge)
    check("GI7 bridge strips inherited Java options",
          "JAVA_TOOL_OPTIONS" in bridge and "_JAVA_OPTIONS" in bridge
          and "JDK_JAVA_OPTIONS" in bridge)
    check("GI8 bridge avoids PowerShell's read-only HOME variable",
          not re.search(r"(?i)\$home\b", bridge))
    check("GI9 bridge persists managed Graal diagnostics",
          "managed-graal-install.log" in bridge
          and 'Join-Path $turboismHome "logs\\installer"' in bridge
          and "Add-Content -LiteralPath $LogPath" in bridge)
    check("GI10 bridge drains and labels both output streams",
          "BeginErrorReadLine" in bridge
          and '"STDOUT "' in bridge
          and '"STDERR "' in bridge
          and "GRAAL_INSTALL_EXIT code=" in bridge
          and "GRAAL_INSTALL_EXCEPTION" in bridge)


def check_configurator_flow_contract():
    """Configurator is post-install, exact-versioned, resizable, logged and selection-bound."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    check("CF1 launch choices precede payload installation",
          text.index("Page custom LaunchOptionsCreate LaunchOptionsLeave")
          < text.index("MUI_PAGE_INSTFILES"))
    check("CF2 successful installation launches interactive configurator",
          "Function .onInstSuccess" in text
          and "configure_turboism.ps1" in text[text.index("Function .onInstSuccess"):])
    check("CF3 main installer scales the frame and page content together",
          'SetFont "MS Shell Dlg" 12' in text
          and "MUI_CUSTOMFUNCTION_GUIINIT ResizeInstallerWindow" not in text
          and "Function ResizeInstallerWindow" not in text)
    check("CF4 configurator is large, resizable and maximizable",
          "ClientSize = New-Object System.Drawing.Size(1080, 900)" in configure
          and "MinimumSize = New-Object System.Drawing.Size(900, 720)" in configure
          and "$form.MaximizeBox = $true" in configure
          and "Anchor = 'Top, Bottom, Left, Right'" in configure)
    check("CF5 candidate selection preserves exact supported patch versions",
          "5\\.(?:2\\.03|3\\.(?:02|03))" in common
          and "Only exact Cubism 5.2.03, 5.3.02, and 5.3.03" in common)
    check("CF6 BAT integration is selected in the configurator after candidates",
          "$batCheck" in configure
          and "Invoke-CubismBatIntegration" in configure
          and "SELECTION_SAVE" in configure)
    check("CF7 configurator writes actionable installer diagnostics",
          "configure-turboism.log" in configure
          and "CONFIGURATION_FAILED" in configure
          and "BAT_INTEGRATION_OK" in configure
          and 'Log: " + $installerLogPath' in configure)




def check_icon_contract():
    """The exact host-provided icon is staged into executables, shortcuts, and the form."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    ico = ICON_DIR / "turboism.ico"
    png = ICON_DIR / "turboism.png"
    check("I1 exact ICO asset exists", ico.is_file() and ico.stat().st_size == 251547)
    check("I1 exact PNG asset exists", png.is_file() and png.stat().st_size == 3503132)
    import hashlib
    check("I2 exact ICO hash is pinned",
          hashlib.sha256(ico.read_bytes()).hexdigest()
          == "77c70e14edf3a88ba38dd43d6e0b0720f2e4aa8d527b03fba0493e33509d0899")
    check("I2 exact PNG hash is pinned",
          hashlib.sha256(png.read_bytes()).hexdigest()
          == "92cb49349cc27e6f96d33a37ff6ab4d0c00ccc738eb55b3165973d47c38eafd2")
    assemble = (INSTALLER_NSI.parent / "assemble-release.sh").read_text(encoding="utf-8")
    check("I3 installer and uninstaller use the icon",
          'Icon "${ICON_FILE}"' in text and 'UninstallIcon "${ICON_FILE}"' in text
          and 'MUI_ICON "${ICON_FILE}"' in text and 'MUI_UNICON "${ICON_FILE}"' in text
          and '-DICON_FILE="$repo_root/packaging/windows-installer/assets/turboism.ico"' in assemble)
    check("I4 installed icon assets are staged and removed",
          'File "${STAGING_DIR}/turboism.ico"' in text
          and 'File "${STAGING_DIR}/turboism.png"' in text
          and 'Delete "$INSTDIR\\turboism.ico"' in text
          and 'Delete "$INSTDIR\\turboism.png"' in text)
    check("I5 Start-menu shortcuts use the installed ICO", text.count('$INSTDIR\\turboism.ico') >= 4)
    check("I6 managed Cubism shortcuts use the installed ICO",
          '$shortcut.IconLocation = "$iconPath,0"' in common)
    check("I7 configurator uses and disposes the installed ICO",
          '$form.Icon = $formIcon' in configure
          and '$formIcon.Dispose()' in configure
          and '$form.Dispose()' in configure)


def check_launcher_and_shortcut_contract():
    """Windows launch scripts avoid $HOME and publish no-space shortcut names."""
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    launcher = (INSTALLER_NSI.parent / "launch-cubism-turboism.ps1").read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    check("L1 production launcher avoids PowerShell's read-only HOME variable",
          not re.search(r"(?i)\$home\b", launcher + common))
    check("L2 generated managed shortcut names contain no spaces",
          'return "Turboism_Cubism_' in common and '"-D3D"' in common)
    check("L3 legacy managed shortcut names remain admitted for cleanup",
          "Turboism Cubism 5\\." in common)
    check("L4 installer Start Menu shortcut names contain no spaces",
          '"Turboism_Configurator"' in text
          and '"Turboism_Uninstall"' in text
          and '"Turboism_Launch_Cubism"' in text)
    legacy_start_menu = [
        "Turboism Configurator.lnk",
        "Uninstall Turboism.lnk",
        "Launch Cubism.lnk",
        "Turboism 配置器.lnk",
        "卸载 Turboism.lnk",
        "启动 Cubism.lnk",
        "Turboism 設定.lnk",
        "Turboism をアンインストール.lnk",
        "Cubism を起動.lnk",
    ]
    macro_start = text.index("!macro RemoveLegacyStartMenuShortcuts")
    macro_end = text.index("!macroend", macro_start)
    legacy_macro = text[macro_start:macro_end]
    start_menu = text[text.index('Section -"开始菜单与注册"'):
                      text.index("SectionEnd", text.index('Section -"开始菜单与注册"'))]
    uninstall = text[text.index('Section "Uninstall"'):
                     text.index("SectionEnd", text.index('Section "Uninstall"'))]
    check("L5 upgrades and uninstall share all 0.43.0 Start Menu names",
          all(('Delete "$SMPROGRAMS\\Turboism\\' + name + '"') in legacy_macro
              for name in legacy_start_menu)
          and "!insertmacro RemoveLegacyStartMenuShortcuts" in start_menu
          and "!insertmacro RemoveLegacyStartMenuShortcuts" in uninstall)
    check("L6 installer discloses explicit hash-guarded BAT integration",
          "only if explicitly selected" in text
          and "仅在明确勾选时" in text
          and "BatIntegrationHelp" in text
          and "-InitialBat" in text
          and "$batCheck" in configure)
    check("L6b Start-menu and BAT controls are independent and reversible",
          "-InitialShortcuts" in text
          and "-InitialBat" in text
          and "Disable-CubismShortcutIntegration" in common
          and "Restore-CubismBatIntegrations" in common)
    check("L7 finish page can open the installation directory",
          "MUI_FINISHPAGE_RUN" in text
          and "FinishOpenFolderText" in text
          and 'explorer.exe' in text)




def check_eula_contract():
    """MIT acceptance remains first; a separate localized EULA is mandatory and packaged."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    eula_files = {
        "en": EULA_DIR / "EULA.en.txt",
        "zh": EULA_DIR / "EULA.zh-Hans.txt",
        "ja": EULA_DIR / "EULA.ja.txt",
    }
    for locale, path in eula_files.items():
        check("EULA %s exists and is non-empty" % locale,
              path.is_file() and path.stat().st_size > 500, str(path))
    en = eula_files["en"].read_text(encoding="utf-8")
    zh = eula_files["zh"].read_text(encoding="utf-8")
    ja = eula_files["ja"].read_text(encoding="utf-8")
    root_eula = (EULA_DIR.parent.parent / "EULA.md").read_text(encoding="utf-8")
    derived_root = re.sub(r"^#{1,6}\s+", "", root_eula, flags=re.MULTILINE)
    derived_root = derived_root.replace("**", "").replace("  \n", "\n")
    check("root EULA is authoritative and packaging copy is derived exactly",
          derived_root == zh)
    check("EULA is version 2.0 and final",
          "版本：2.0" in root_eula
          and "发布日期：2026-08-30" in root_eula
          and "草案" not in root_eula)
    check("EULA preserves the complete user-supplied declaration",
          "TURBOISM 最终用户运行声明与免责声明" in root_eula
          and "## 1. 独立项目与非官方性质" in root_eula
          and "## 16. 语言" in root_eula
          and "本版本以简体中文文本为正式文本" in root_eula)
    check("localized notices identify Simplified Chinese as authoritative",
          "The Simplified Chinese text is authoritative" in en
          and "本版本以简体中文文本为正式文本" in zh
          and "簡体字中国語文を正文とします" in ja)
    first = text.index('!insertmacro MUI_PAGE_LICENSE "${LICENSE_FILE}"')
    statement = text.index('!insertmacro MUI_PAGE_LICENSE "$(EulaFile)"')
    acknowledgements_page = text.index(
        "Page custom EulaAcknowledgementsCreate EulaAcknowledgementsLeave")
    mode_page = text.index("Page custom ModeCreate ModeLeave")
    check("EULA statement and acknowledgements are separate pages after MIT License",
          first < statement < acknowledgements_page < mode_page)
    statement_declaration = text[first + len(
        '!insertmacro MUI_PAGE_LICENSE "${LICENSE_FILE}"'):statement]
    check("EULA statement page keeps the complete localized scrollable body",
          'MUI_LICENSEPAGE_TEXT_TOP "$(EulaTopText)"' in statement_declaration
          and "MUI_LICENSEPAGE_CHECKBOX" not in statement_declaration
          and "MUI_PAGE_CUSTOMFUNCTION_SHOW" not in statement_declaration
          and "MUI_PAGE_CUSTOMFUNCTION_LEAVE" not in statement_declaration)
    acknowledgements = (
        "我确认 Turboism 是独立第三方项目，并非 Live2D 官方产品。",
        "我确认使用 Cubism 仍需合法、有效的授权；Turboism 不提供、替代或绕过 Cubism 的许可校验。",
        "我理解由我启动或授权的插件、脚本、MCP、API 和自动化操作可能修改、覆盖或删除工程内容，并将自行保留独立备份。",
        "我理解 Turboism 是按现状提供的开源项目，不保证持续兼容、无错误或成功恢复。",
    )
    create_start = text.index("Function EulaAcknowledgementsCreate")
    create_end = text.index("FunctionEnd", create_start)
    create = text[create_start:create_end]
    leave_start = text.index("Function EulaAcknowledgementsLeave")
    leave_end = text.index("FunctionEnd", leave_start)
    leave = text[leave_start:leave_end]
    check("NSIS acknowledgement page shows four standard independent checkboxes",
          all(value in text for value in acknowledgements)
          and create.count("${NSD_CreateCheckbox}") == 4
          and all("EulaAck%dCheckbox" % index in create for index in range(1, 5)))
    check("NSIS reads and requires all four acknowledgement states",
          leave.count("${NSD_GetState}") == 4
          and all("$EulaAck%dState" % index in leave for index in range(1, 5))
          and "$(EulaRequired)" in leave
          and "Abort" in leave)
    check("NSIS preserves acknowledgement choices across Back navigation",
          "${NSD_OnBack} EulaAcknowledgementsSave" in create
          and "Function EulaAcknowledgementsSave" in text
          and all("$EulaAck%dState" % index in create for index in range(1, 5)))
    check("NSIS does not attach acknowledgement controls to the MUI License page",
          "CreateEulaAcknowledgement" not in text
          and "EulaUpdateNext" not in text
          and "Function EulaShow" not in text
          and "Function EulaLeave" not in text
          and "EulaNativeAccept" not in text)
    check("NSIS EULA files are localized",
          all(('LicenseLangString EulaFile ${LANG_%s}' % lang) in text
              for lang in ("ENGLISH", "SIMPCHINESE", "JAPANESE")))
    core_start = text.index('Section "-核心文件"')
    core_end = text.index("SectionEnd", core_start)
    core = text[core_start:core_end]
    uninstall_start = text.index('Section "Uninstall"')
    uninstall_end = text.index("SectionEnd", uninstall_start)
    uninstall = text[uninstall_start:uninstall_end]
    for name in ("EULA.en.txt", "EULA.zh-Hans.txt", "EULA.ja.txt"):
        check("NSIS packages %s" % name,
              ('File "${STAGING_DIR}/%s"' % name) in core)
        check("NSIS uninstalls %s" % name,
              ('Delete "$INSTDIR\\%s"' % name) in uninstall)


def check_graal_fallback_contract():
    """Managed launch must recover from a missing saved/default GraalVM choice.

    Explicit ``-CubismJava`` input remains strict, while the persisted/default
    preference may fall back to the selected Cubism installation's bundled
    Java and must show the official installation URL.
    """
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    launcher = (INSTALLER_NSI.parent / "launch-cubism-turboism.ps1").read_text(encoding="utf-8")
    check("G1 nullable GraalVM discovery helper exists",
          "function Find-CubismGraalJava" in common and 'return ""' in common)
    check("G1b discovery requires GraalVM Community 25.2.x release metadata",
          "function Test-CubismCompatibleGraalJava" in common
          and 'IMPLEMENTOR="GraalVM' in common
          and 'GRAALVM_VERSION="25\\.2\\.' in common)
    check("G2 explicit Cubism Java override remains strict for missing paths",
          "function Resolve-CubismGraalJava" in common
          and "no GraalVM java.exe is available" in common
          and "Resolve-CubismGraalJava -TurboismHome $turboismHome -ExplicitJava $CubismJava" in launcher
          and "Test-CubismNormalFile $explicit" in common)
    fallback = launcher[launcher.index('else {\n    $javaOverride = Find-CubismGraalJava'):
                        launcher.index('Write-Host ($M.Jvm', launcher.index('else {\n    $javaOverride = Find-CubismGraalJava'))]
    check("G3 saved/default GraalVM falls back to bundled mode",
          '$cubismJvm = "bundled"' in fallback and "Write-Warning $M.GraalFallback" in fallback)
    check("G4 fallback warning links the official GraalVM page",
          "https://www.graalvm.org/downloads/" in launcher)


def check_uninstall_config_option():
    """卸载确认页的配置保留选项必须属于 MUI 内页且默认保留。"""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    show_start = text.index("Function un.ConfirmShow")
    show_end = text.index("FunctionEnd", show_start)
    show = text[show_start:show_end]
    leave_start = text.index("Function un.ConfirmLeave")
    leave_end = text.index("FunctionEnd", leave_start)
    leave = text[leave_start:leave_end]
    uninstall_start = text.index('Section "Uninstall"')
    uninstall_end = text.index("SectionEnd", uninstall_start)
    uninstall = text[uninstall_start:uninstall_end]

    check("UC1 checkbox belongs to the MUI uninstall page",
          'i $mui.UnConfirmPage, i 2000' in show)
    check("UC1 checkbox is not attached to the wizard parent",
          'i $HWNDPARENT, i 2000' not in show)
    check("UC2 keep-config option defaults checked",
          "StrCpy $unKeepConfig 1" in show
          and '${BM_SETCHECK} 1 0' in show)
    check("UC3 leave callback reads checkbox state directly",
          '${BM_GETCHECK} 0 0 $unKeepConfig' in leave)
    check("UC4 config is deleted only when keep is unchecked",
          '${If} $unKeepConfig == 0' in uninstall
          and 'Delete "$INSTDIR\\config.json"' in uninstall)
    check("UC4b keep restores user config/data directories after runtime cleanup",
          'CreateDirectory "$INSTDIR\\config"' in uninstall
          and 'CreateDirectory "$INSTDIR\\data"' in uninstall)
    check("UC5 localized label describes retention",
          all(('LangString UnKeepConfigLabel ${LANG_%s}' % lang) in text
              for lang in ("ENGLISH", "SIMPCHINESE", "JAPANESE"))
          and "UnDeleteConfigLabel" not in text
          and "$unDeleteConfig" not in text)


def check_uninstall_postcondition():
    """卸载失败关闭顺序回归：清理调用/$0 非零守卫 -> 状态文件仍存在则失败关闭
    -> 之后才允许 DeleteRegKey 与载荷删除（Wine 内置 PowerShell 返回 0 而未执行
    清理的复现：NSIS 曾仅信任 $0 导致残留）；LICENSE 删除必须使用精确基线名。"""
    stmts = uninstall_statements()
    texts = [t for _, t in stmts]
    cleanup = find_stmt(texts, lambda t: "ExecWait" in t and "-Cleanup" in t)
    check("U1 托管清理调用存在", cleanup is not None)
    if cleanup is None:
        return
    guard0 = find_stmt(texts, lambda t: t == "${If} $0 != 0")
    check("U1 清理调用先于 $0 非零守卫", guard0 is not None and guard0 > cleanup)
    check("U1 $0 守卫失败关闭（ShortcutCleanupFailure + Abort）",
          guard0 is not None
          and any("ShortcutCleanupFailure" in t for t in texts[guard0:guard0 + 3])
          and "Abort" in texts[guard0:guard0 + 3])
    if guard0 is None:
        return
    state = find_stmt(texts, lambda t: "${FileExists}" in t and "cubism-installations.json" in t)
    check("U2 状态文件失败关闭守卫存在", state is not None)
    if state is None:
        return
    check("U2 状态守卫位于 $0 守卫之后", state > guard0)
    check("U2 状态守卫失败关闭（ShortcutCleanupFailure + Abort）",
          any("ShortcutCleanupFailure" in t for t in texts[state:state + 3])
          and "Abort" in texts[state:state + 3])
    regkey = find_stmt(texts, lambda t: t.startswith("DeleteRegKey"))
    payload = find_stmt(texts, lambda t: t.startswith('Delete "$INSTDIR\\'))
    check("U3 状态守卫先于 DeleteRegKey", regkey is not None and state < regkey)
    check("U3 状态守卫先于载荷删除", payload is not None and state < payload)
    lic = find_stmt(texts, lambda t: 'Delete "$INSTDIR\\LICENSE' in t)
    check("U4 删除精确安装基线名 LICENSE",
          lic is not None and texts[lic] == 'Delete "$INSTDIR\\LICENSE"')
    check("U4 无 LICENSE.txt 删除残留", not any("LICENSE.txt" in t for t in texts))


def main():
    a, b, c = BUNDLED_IDS

    # T1: Full、全选、无既有配置 → 模板（无 disabledPlugins）
    out = installer_write_config("full", [], None)
    doc = json.loads(out)
    check("T1 模板字段", doc["worktreeId"] == "turboism-runtime" and doc["pluginDirs"] == ["plugins"]
          and doc["format"] == "turboism.runtime.config" and doc["schemaVersion"] == 1
          and doc["launcher"] == {"cubismJvm": "graalvm"})
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

    # T11: 升级退休切片 —— 四个历史 id 必须从 disabledPlugins 删除，
    # 无关 id 保留；这是 Java/NSIS 两条安装路径的共同升级契约。
    existing = json.dumps({"disabledPlugins": RETIRED_IDS + [UNRELATED]})
    out = installer_write_config("full", [], existing)
    doc = json.loads(out)
    check("T11 退休 id 从升级配置移除", doc["disabledPlugins"] == [UNRELATED],
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

    # TI4: Full→Lite → 不写新 JAR（当前旧 JAR 留盘但被禁用）
    jars = nsis_jars_after("lite", BUNDLED_MODULES)
    check("TI4 Full→Lite 不写新 JAR（当前旧 JAR 留盘）", jars == BUNDLED_MODULES, str(jars))
    existing = '{"disabledPlugins": ["' + a + '"]}'
    out = installer_write_config("lite", [], existing)
    doc = json.loads(out)
    check("TI4 Full→Lite 禁用全部捆绑 id", doc["disabledPlugins"] == BUNDLED_IDS, str(doc.get("disabledPlugins")))

    # TI5: NSIS 升级先删除受控历史模块，再根据当前模式铺设 payload。
    jars = nsis_jars_after("lite", RETIRED_MODULES + BUNDLED_MODULES + ["third-party"])
    check("TI5 Lite 升级退休历史 JAR 并保留未知 JAR",
          jars == sorted(BUNDLED_MODULES + ["third-party"]), str(jars))
    jars = nsis_jars_after("full", RETIRED_MODULES + ["third-party"])
    check("TI5 Full 升级退休历史 JAR、安装当前全量并保留未知 JAR",
          jars == sorted(BUNDLED_MODULES + ["third-party"]), str(jars))

    # 输出有效性：schemaVersion/format 完整
    for label, out in [("T3", out)]:
        doc = json.loads(out)
        assert doc["format"] == "turboism.runtime.config" and doc["schemaVersion"] == 1

    check_nsis_retirement_contract()
    check_managed_graal_installer_contract()
    check_configurator_flow_contract()
    check_icon_contract()
    check_launcher_and_shortcut_contract()
    check_eula_contract()
    check_graal_fallback_contract()
    check_uninstall_config_option()
    check_uninstall_postcondition()
    print("config merge + payload 模拟 + uninstall 后置验证通过：全部用例 ok")


if __name__ == "__main__":
    main()
