#!/usr/bin/env python3
"""config.json 生命周期 + Full/Lite 插件载荷语义的单元验证。

本脚本镜像 installer.nsi 与 configure_turboism.ps1 的配置策略：
  - 全新安装才从当前模板创建 config.json，并由插件选择生成 disabledPlugins；
  - 已有当前 schema 的 config.json 按原始字节跳过，不因重装或模式选择被覆盖；
  - 显式 legacy v0（无 schemaVersion）仅通过受限迁移升级到 v1，保留已知用户设置；
  - 未知旧字段、未来 schema、损坏或超限配置失败关闭，不降级也不截断原文件；
  - Full 安装始终携带全部捆绑插件 JAR；Lite 不写任何插件 JAR；
  - 卸载失败关闭后置顺序和精确 LICENSE 删除仍保持不变。

packaging/release-plugins.txt 仍是发布载荷的唯一权威清单：本脚本将其作为显式
17 项目 / 8 公开排除模块回归 oracle（清单漂移即失败），但下方模拟器的合成
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
FX_RUNTIME_DIR = (Path(__file__).resolve().parent.parent / "fx-runtime" /
                  "windows-x86_64")

# 冻结的 17 项目批准清单 —— 回归 oracle：清单增删/改序/公开排除模块回归即失败。
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
    ":plugins:ui-theme",
]
# 八个公开排除模块：必须从清单及一切发布载荷/选择面缺席（回归 oracle）
EXCLUDED = {"bounding-box", "context-menu", "demo", "parameter",
            "project-inspector", "project-panel", "psd-import",
            "turboism-with-fx"}


def check(name, cond, detail=""):
    if not cond:
        print(f"FAIL: {name} {detail}")
        sys.exit(1)
    print(f"  ok: {name}")


def load_manifest():
    """回归 oracle：从唯一权威 release-plugins.txt 校验清单 —— 空行/注释/非插件项/
    重复/未排序/偏离冻结 17 项/含公开排除模块均失败。返回的模块名仅供 oracle 使用，
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
    check("清单与冻结 17 项目一致", lines == EXPECTED_PATHS, f"n={len(lines)}")
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

REAL_MODULES = load_manifest()  # 回归 oracle：清单漂移（增删/改序/占位回归）即失败


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


CURRENT_SCHEMA = 1
V0_FIELDS = {
    "format", "schemaVersion", "worktreeId", "pluginDirs", "disabledPlugins",
    "logLevel", "maxLogStorageMiB", "locale", "safeMode", "diagnostics",
    "hooks", "launcher", "cubismJvm", "graalVmPath",
}


def migrate_v0(doc):
    unknown = set(doc) - V0_FIELDS
    if unknown:
        raise ValueError("unsupported legacy fields: " + ",".join(sorted(unknown)))
    if doc.get("format") not in (None, "turboism.runtime.config"):
        raise ValueError("unsupported legacy format")
    migrated = {
        "format": "turboism.runtime.config",
        "schemaVersion": CURRENT_SCHEMA,
        "worktreeId": "turboism-runtime",
        "pluginDirs": ["plugins"],
    }
    for field in (
        "worktreeId", "pluginDirs", "disabledPlugins", "logLevel",
        "maxLogStorageMiB", "locale", "safeMode", "diagnostics", "hooks",
    ):
        if field in doc:
            migrated[field] = doc[field]
    launcher = dict(doc.get("launcher", {"cubismJvm": "graalvm"}))
    if set(launcher) - {"cubismJvm", "graalVmPath"}:
        raise ValueError("unsupported legacy launcher field")
    for field in ("cubismJvm", "graalVmPath"):
        if field in doc:
            if field in launcher and "launcher" in doc:
                raise ValueError("duplicate legacy launcher field")
            launcher[field] = doc[field]
    migrated["launcher"] = launcher
    return migrated


def installer_write_config(mode, unchecked, existing_text, bundled_ids=BUNDLED_IDS):
    """镜像 SecConfig：current v1 原字节跳过，v0 迁移，缺失时按选择新建。"""
    if existing_text is not None:
        existing = json.loads(existing_text)
        schema = existing.get("schemaVersion", 0)
        if schema == CURRENT_SCHEMA:
            if existing.get("format") != "turboism.runtime.config":
                raise ValueError("current schema format mismatch")
            return existing_text
        if schema != 0:
            raise ValueError("unsupported schema migration")
        return json.dumps(migrate_v0(existing), ensure_ascii=False, separators=(",", ":")) + "\r\n"
    if mode == "lite":
        unchecked = list(bundled_ids)
    return build_config_json(nsis_merge(unchecked, []))


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
                      if "nsExec::ExecToLog" in line and call in line)
    guard = "\n".join(lines[exec_index:exec_index + 7])
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


def check_config_migration_contract():
    """Updates preserve current config bytes and migrate only the explicit v0 shape."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    section = text[text.index('Section "-写入配置" SecConfig'):
                   text.index("SectionEnd", text.index('Section "-写入配置" SecConfig'))]
    check("CM1 current config enters migration gate instead of NSIS rewrite",
          '${FileExists} "$INSTDIR\\config.json"' in section
          and "-MigrateConfig" in section
          and section.index("-MigrateConfig") < section.index("Call MergeAndWriteConfig"))
    check("CM2 same schema is explicitly left unchanged",
          "TURBOISM_CONFIG unchanged schemaVersion=1" in configure
          and "if ($schema -eq 1)" in configure)
    check("CM3 v0 migration is explicit and bounded",
          "Convert-RuntimeConfigV0ToV1" in configure
          and "if ($schema -ne 0)" in configure
          and "config.json exceeds 64 KiB" in configure)
    check("CM4 migration publishes atomically and fails closed",
          "[System.IO.File]::Replace($temporary, $configPath, $null, $true)" in configure
          and "CONFIG_MIGRATION_FAILED" in configure
          and "ConfigMigrationError" in section
          and "Abort" in section)


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
    graal_create = text[text.index("Function GraalCreate"):
                        text.index("FunctionEnd", text.index("Function GraalCreate"))]
    check("GI11 Graal page rows are contiguous at 12pt",
          '${NSD_CreateLabel} 0 0 100% 24u "$(GraalPageTitle)"' in graal_create
          and '${NSD_CreateLabel} 0 24u 100% 42u "$(GraalPageDescription)"' in graal_create
          and '${NSD_CreateRadioButton} 0 67u 100% 16u "$(GraalNowChoice)"' in graal_create
          and '${NSD_CreateRadioButton} 0 84u 100% 16u "$(GraalLaterChoice)"' in graal_create
          and '${NSD_CreateLabel} 12u 101u 96% 42u "$(GraalProgressHint)"' in graal_create)


def check_configurator_flow_contract():
    """Configurator is post-install, exact-versioned, resizable, logged and selection-bound."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    configure = (INSTALLER_NSI.parent / "configure_turboism.ps1").read_text(encoding="utf-8")
    common = (INSTALLER_NSI.parent / "cubism-launch-common.ps1").read_text(encoding="utf-8")
    check("CF1 launch choices precede payload installation",
          text.index("Page custom LaunchOptionsCreate LaunchOptionsLeave")
          < text.index("MUI_PAGE_INSTFILES"))
    directory_page = text.index("MUI_PAGE_DIRECTORY")
    discovery_page = text.index("Page custom CubismDiscoveryCreate CubismDiscoveryLeave")
    launch_page = text.index("Page custom LaunchOptionsCreate LaunchOptionsLeave")
    check("CF1b exact Cubism discovery is visible before launch choices and payload mutation",
          directory_page < discovery_page < launch_page < text.index("MUI_PAGE_INSTFILES"))
    discovery_start = text.index("Function CubismDiscoveryCreate")
    discovery_end = text.index("FunctionEnd", discovery_start)
    discovery_create = text[discovery_start:discovery_end]
    check("CF1c pre-install discovery stages the current exact verifier payload",
          all(path in discovery_create for path in (
              '${STAGING_DIR}/turboism-agent.jar',
              '${STAGING_DIR}/configure_turboism.ps1',
              '${STAGING_DIR}/cubism-launch-common.ps1'))
          and "$PLUGINSDIR\\Turboism-discovery-$CubismDiscoveryGeneration" in discovery_create)
    scanner_exec = next(line for line in discovery_create.splitlines()
                        if "InstallerDiscoveryOutput" in line)
    check("CF1d pre-install discovery launches asynchronously without taking focus",
          scanner_exec.strip().startswith('ExecShell ""')
          and "SW_HIDE" in scanner_exec
          and "ExecWait" not in scanner_exec
          and "nsExec" not in scanner_exec)
    check("CF1e pre-install discovery disables Next before timer polling",
          discovery_create.index("EnableWindow $CubismDiscoveryNext 0")
          < discovery_create.index("${NSD_CreateTimer} CubismDiscoveryPoll 250"))
    poll_start = text.index("Function CubismDiscoveryPoll")
    poll_end = text.index("FunctionEnd", poll_start)
    poll = text[poll_start:poll_end]
    fail_start = text.index("Function CubismDiscoveryFail")
    fail_end = text.index("FunctionEnd", fail_start)
    fail = text[fail_start:fail_end]
    back_start = text.index("Function CubismDiscoveryBack")
    back_end = text.index("FunctionEnd", back_start)
    back = text[back_start:back_end]
    check("CF1f discovery completion and recoverable failures release the wizard",
          "${NSD_KillTimer} CubismDiscoveryPoll" in poll
          and "Call CubismDiscoveryEnableNext" in poll
          and "${NSD_KillTimer} CubismDiscoveryPoll" in fail
          and "Call CubismDiscoveryEnableNext" in fail
          and "CubismDiscoveryTimeout" in poll)
    check("CF1g Back navigation invalidates the poll and restores shared Next",
          "${NSD_OnBack} CubismDiscoveryBack" in discovery_create
          and "${NSD_KillTimer} CubismDiscoveryPoll" in back
          and "EnableWindow $CubismDiscoveryNext 1" in back
          and "StrCpy $CubismDiscoveryComplete 0" in back)
    early_scan = configure.index("if (-not [string]::IsNullOrWhiteSpace($InstallerDiscoveryOutput))")
    check("CF1h configurator discovery mode is headless and precedes state/log/UI setup",
          "[string]$InstallerDiscoveryOutput" in configure
          and "Write-CubismInstallerDiscoveryReport" in configure[early_scan:configure.index("$statePath", early_scan)]
          and early_scan < configure.index("$statePath") < configure.index("Add-Type -AssemblyName System.Windows.Forms"))
    check("CF1i discovery exporter preserves exact admission and complete atomic publication",
          "function Write-CubismInstallerDiscoveryReport" in common
          and "Get-CubismDiscoveryRoots" in common
          and "Get-CubismInstallations" in common
          and "TURBOISM_CUBISM_SCAN_V1" in common
          and "$publish = $false" in common
          and "$publish = $true" in common
          and "if ($publish -and (Test-CubismNormalFile $temporary))" in common
          and "[System.IO.File]::Move($temporary, $output)" in common)
    check("CF1i2 discovery report preserves localized labels and non-ASCII paths",
          "[System.Text.UnicodeEncoding]::new($false, $true, $true)" in common
          and "FileReadUTF16LE $CubismDiscoveryHandle $line" in poll)
    check("CF1i3 result parsing cannot overwrite the open report handle",
          'FileOpen $CubismDiscoveryHandle "$CubismDiscoveryResult" r' in poll
          and "FileClose $CubismDiscoveryHandle" in poll
          and "FileReadUTF16LE $0 $line" not in poll)
    discovery_keys = (
        "CubismDiscoveryTitle", "CubismDiscoveryScanning", "CubismDiscoveryComplete",
        "CubismDiscoveryNone", "CubismDiscoveryFailed", "CubismDiscoveryTimeout",
    )
    check("CF1j discovery page has English, Simplified Chinese, and Japanese text",
          all(text.count("LangString %s " % key) == 3 for key in discovery_keys)
          and all(('LangString CubismDiscoveryTitle ${LANG_%s}' % language) in text
                  for language in ("ENGLISH", "SIMPCHINESE", "JAPANESE"))
          and 'LangString CubismDiscoveryTitle ${LANG_SIMPCHINESE} "Cubism 安装"' in text)
    check("CF1j1 discovery page avoids explanatory and implementation-defense wording",
          "CubismDiscoverySubtitle" not in text
          and '!insertmacro MUI_HEADER_TEXT "$(CubismDiscoveryTitle)" ""' in discovery_create
          and "精确身份" not in text
          and "Exact application JARs" not in text
          and 'LangString CubismDiscoveryScanning ${LANG_SIMPCHINESE} "正在扫描已安装的 Cubism 编辑器……"' in text)
    check("CF1j2 scaled discovery list exposes complete paths horizontally",
          "${NSD_AddStyle} $CubismDiscoveryList ${WS_HSCROLL}" in discovery_create
          and "${LB_SETHORIZONTALEXTENT} 8192" in poll)
    check("CF1k exact artifact probes and the entire pre-install worker are time-bounded",
          "$script:CubismArtifactProbeTimeoutMilliseconds = 20000" in common
          and "WaitForExit($script:CubismArtifactProbeTimeoutMilliseconds)" in common
          and "try { $process.Kill() }" in common
          and "[switch]$InstallerDiscoveryWorker" in configure
          and "$discoveryProcess.WaitForExit(105000)" in configure
          and "try { $discoveryProcess.Kill() }" in configure
          and '-FailureCode "TimeoutException"' in configure)
    check("CF1l Back/forward navigation reuses one discovery worker",
          "Var CubismDiscoveryStarted" in text
          and "${If} $CubismDiscoveryStarted == 1" in discovery_create
          and "StrCpy $CubismDiscoveryStarted 1" in discovery_create
          and discovery_create.count("InstallerDiscoveryOutput") == 1)
    check("CF1m every DISPLAY record contains only a bounded label and path",
          "-MaximumLength 520" in common
          and '[void]$lines.Add("DISPLAY|[$label] $root")' in common
          and "$candidate.Reason" not in common
          and " — $reason" not in common
          and "[int]$MaximumLength = 900" in common)
    success = text[text.index("Function .onInstSuccess"):
                   text.index("FunctionEnd", text.index("Function .onInstSuccess"))]
    check("CF2 successful installation performs hidden headless initial configuration",
          success.count("nsExec::ExecToLog") == 5
          and "ExecWait" not in success
          and "-NonInteractive" in success
          and "-InitializeSelection" in success
          and "-EnableShortcuts" in success
          and "-DisableShortcuts" in success
          and "-IntegrateBat" in success
          and "-DisableBat" in success
          and "Exec '" not in success)
    check("CF2a every installer-time PowerShell console is hidden",
          "ExecWait" not in text
          and "ExecShell \"\" \"$SYSDIR\\WindowsPowerShell\\v1.0\\powershell.exe\"" in scanner_exec
          and "SW_HIDE" in scanner_exec
          and text.count("nsExec::ExecToLog") == 13)
    check("CF2b BAT integration elevates only the selected helper operation",
          "RequestExecutionLevel user" in text
          and "Start-Process" in configure
          and "-Verb RunAs" in configure
          and "-Wait" in configure
          and "-PassThru" in configure
          and "[switch]$Elevated" in configure)
    disable_guard_start = configure.index("if ($DisableBat -and -not $Elevated)")
    integrate_guard_start = configure.index(
        "if ($IntegrateBat -and -not $Elevated)", disable_guard_start)
    disable_guard = configure[disable_guard_start:integrate_guard_start]
    check("CF2c fresh unchecked BAT choice exits without requesting UAC",
          "Read-CubismInstallationState" in disable_guard
          and "$disableBatState.BatIntegrations.Count -eq 0" in disable_guard
          and "exit 0" in disable_guard
          and disable_guard.index("exit 0")
          < disable_guard.index("Invoke-ElevatedConfiguratorMode")
          and "($IntegrateBat -or $DisableBat)" not in configure)
    check("CF3 main installer scales the frame and page content together",
          'SetFont "MS Shell Dlg" 12' in text
          and "MUI_CUSTOMFUNCTION_GUIINIT ResizeInstallerWindow" not in text
          and "Function ResizeInstallerWindow" not in text)
    check("CF4 configurator is large, resizable and maximizable",
          "ClientSize = New-Object System.Drawing.Size(1080, 900)" in configure
          and "MinimumSize = New-Object System.Drawing.Size(900, 720)" in configure
          and "$form.MaximizeBox = $true" in configure
          and "Anchor = 'Top, Bottom, Left, Right'" in configure)
    check("CF5 candidate selection resolves exact versions from application artifacts",
          "Get-CubismVersionFromArtifact" in common
          and "ReviewedHostArtifactCli" in common
          and "HostArtifactDigest.from" in (INSTALLER_NSI.parent.parent.parent / "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifactCli.java").read_text(encoding="utf-8")
          and "Get-CubismVersionFromPath" not in common
          and "application artifacts are selectable" in common)
    check("CF6 BAT integration is selected in the configurator after candidates",
          "$batCheck" in configure
          and "Invoke-CubismBatIntegration" in configure
          and "SELECTION_SAVE" in configure)
    check("CF7 configurator writes actionable installer diagnostics",
          "configure-turboism.log" in configure
          and "CONFIGURATION_FAILED" in configure
          and "BAT_INTEGRATION_OK" in configure
          and 'Log: " + $installerLogPath' in configure)
    check("CF8 managed launch passes quoted JVM arguments through an ephemeral BAT",
          "function New-CubismManagedOptionsBat" in common
          and "managed Cubism JVM option contains an unsupported BAT character" in common
          and "Java environment option variables are deliberately" in common
          and '[Environment]::SetEnvironmentVariable($name, $null, "Process")' in common
          and "-Duser.dir=$root" in common
          and "-Dturboism.home=$canonicalHome" in common
          and "-javaagent:$Agent=home=$canonicalHome;timeoutSeconds=120" in common)




def check_managed_fx_contract():
    """The exact Windows fx payload is visible but unselected by default."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    fx = FX_RUNTIME_DIR / "fx.exe"
    import hashlib
    check("FX1 exact Windows executable exists",
          fx.is_file() and fx.stat().st_size == 11144192)
    check("FX1 exact Windows executable hash is pinned",
          hashlib.sha256(fx.read_bytes()).hexdigest()
          == "a36b0b209d933e4757d7e1a961d259d39a8d370b68cbde8e9cba227603ac63c2")
    section_start = text.index('Section /o "$(ManagedFxSection)"')
    section_end = text.index("SectionEnd", section_start)
    section = text[section_start:section_end]
    check("FX2 managed fx is optional and unselected by default",
          text[section_start:].startswith('Section /o "$(ManagedFxSection)"')
          and "${If} $Mode == 1" not in section
          and '-DestinationRoot "$INSTDIR"' in section)
    check("FX2b managed fx has localized component labels",
          all(f"LangString ManagedFxSection ${{{language}}}" in text
              for language in ("LANG_ENGLISH", "LANG_SIMPCHINESE", "LANG_JAPANESE")))
    expected = (
        "fx.exe", "LICENSE", "THIRD_PARTY_NOTICES.md",
        "TURBOISM-DISTRIBUTION-NOTICE.txt", "manifest.properties"
    )
    generator = (INSTALLER_NSI.parent / "assemble-release.sh").read_text(encoding="utf-8")
    check("FX3 NSIS carries the exact Windows managed runtime inventory",
          all(name in generator[generator.index("fx_payload = ["):
                                generator.index("def write_checksum_manifest")]
              for name in expected)
          and 'write_checksum_manifest("payload-fx.sha256", fx_payload)' in generator)
    check("FX3b managed fx checks destination hashes before extraction",
          "payload-fx.sha256" in section
          and "-PlanOnly" in section
          and "Call ExtractManagedFxPayload" in section
          and section.index("-PlanOnly") < section.index("Call ExtractManagedFxPayload")
          and '-SourceRoot "$PLUGINSDIR\\Turboism-fx-payload"' in section)
    uninstall = text[text.index('Section "Uninstall"'):
                     text.index("SectionEnd", text.index('Section "Uninstall"'))]
    check("FX4 uninstall removes only the installer-owned Windows runtime files",
          all(('Delete "$INSTDIR\\runtimes\\fx\\0.0.5\\windows-x86_64\\'
               + name + '"') in uninstall for name in expected)
          and 'RMDir /r "$INSTDIR\\runtimes"' not in uninstall)


def check_jar_payload_contract():
    """Every permanent static NSIS payload entry is extracted only when SHA-256 differs."""
    text = INSTALLER_NSI.read_text(encoding="utf-8")
    helper = (INSTALLER_NSI.parent / "install-jar-payload.ps1").read_text(encoding="utf-8")
    generator = (INSTALLER_NSI.parent / "assemble-release.sh").read_text(encoding="utf-8")
    generated_plugins = (INSTALLER_NSI.parent / "plugin-sections.nsh").read_text(encoding="utf-8")
    gradle = (INSTALLER_NSI.parents[1] / "java-installer" / "installer.gradle.kts").read_text(
        encoding="utf-8"
    )
    check("PAY1 helper verifies manifest SHA-256 before and after writing",
          "Test-PayloadFileUnchanged $destinationFile $entry.Hash" in helper
          and "Get-PayloadFileHash $sourceFile" in helper
          and "source checksum does not match its manifest" in helper
          and "destination checksum does not match after copy" in helper
          and 'Write-Output "SKIP|$($entry.Relative)"' in helper)
    check("PAY2 helper rejects empty, duplicate, and unsafe manifests",
          "Installer payload manifest is empty" in helper
          and "Installer payload manifest contains a duplicate path" in helper
          and "Installer payload path escapes its root" in helper
          and "Installer payload destination is a reparse point" in helper)
    check("PAY3 generator creates core, plugin, and fx checksum manifests",
          all(('write_checksum_manifest("payload-%s.sha256"' % category) in generator
              for category in ("core", "plugins", "fx"))
          and "f'plugins/{p[\"module\"]}.jar'" in generator)
    permanent_core = (
        "turboism-agent.jar", "install-jar-payload.ps1",
        "launch-cubism-turboism.bat", "launch-cubism-turboism.ps1",
        "configure_turboism.ps1", "cubism-launch-common.ps1",
        "install-managed-graal.ps1", "turboism.ico", "turboism.png",
        "README.txt", "README.zh.txt", "README.ja.txt", "LICENSE",
        "EULA.en.txt", "EULA.zh-Hans.txt", "EULA.ja.txt",
    )
    core_payload_source = generator[generator.index("core_payload = ["):
                                    generator.index("plugin_payload = [")]
    check("PAY4 every permanent core file is checksum-managed",
          all(('"%s"' % name) in core_payload_source for name in permanent_core)
          and 'stage / "graal" / "lib"' in core_payload_source)
    core_section = text[text.index('Section "-核心文件" SecCore'):
                        text.index("SectionEnd", text.index('Section "-核心文件" SecCore'))]
    check("PAY5 core plan precedes conditional extraction and verified copy",
          "payload-core.sha256" in core_section
          and "-PlanOnly" in core_section
          and "Call ExtractCorePayload" in core_section
          and core_section.index("-PlanOnly") < core_section.index("Call ExtractCorePayload")
          and '-SourceRoot "$PLUGINSDIR\\Turboism-core-payload"' in core_section)
    check("PAY6 permanent core files are not directly extracted to INSTDIR",
          all(('File "${STAGING_DIR}/%s"' % name) not in core_section
              for name in permanent_core if name != "LICENSE")
          and 'File "${LICENSE_FILE}"' not in core_section)
    check("PAY7 plugin payload uses destination-relative manifest paths",
          "payload-plugins.sha256" in generated_plugins
          and '-DestinationRoot "$INSTDIR"' in generated_plugins
          and '$PLUGINSDIR\\Turboism-plugin-payload\\plugins' in generated_plugins
          and '-SourceRoot "$PLUGINSDIR\\Turboism-plugin-payload"' in generated_plugins)
    plugin_markers = re.findall(r'Turboism-plugin-plan\\(\d{4})\.need', generated_plugins)
    plugin_files = re.findall(
        r'File "/oname=([^\"]+\.jar)" "\$\{STAGING_DIR\}/plugins/[^\"]+"',
        generated_plugins,
    )
    check("PAY7b plugin markers form a complete manifest-aligned sequence",
          plugin_markers == [f"{index:04d}" for index in range(len(REAL_MODULES))]
          and len(plugin_files) == len(set(plugin_files)) == len(REAL_MODULES)
          and sorted(Path(name).stem for name in plugin_files) == sorted(REAL_MODULES))
    check("PAY8 generated extractors align marker indices and target names",
          "def append_extractor" in generator
          and "Path(relative).name" in generator
          and '${{FileExists}}' in generator
          and "ExtractCorePayload" in generator
          and "ExtractManagedFxPayload" in generator)
    check("PAY9 checksum helper is staged and uninstalled",
          gradle.count('from("packaging/windows-installer/install-jar-payload.ps1")') == 1
          and '"packaging/windows-installer/install-jar-payload.ps1"' in gradle
          and 'Delete "$INSTDIR\\install-jar-payload.ps1"' in text)
    check("PAY10 temporary extracted payloads are removed before finish",
          all(path in text for path in (
              'RMDir /r "$PLUGINSDIR\\Turboism-core-payload"',
              'RMDir /r "$PLUGINSDIR\\Turboism-core-plan"',
              'RMDir /r "$PLUGINSDIR\\Turboism-fx-payload"',
              'RMDir /r "$PLUGINSDIR\\Turboism-fx-plan"'))
          and 'RMDir /r "$PLUGINSDIR\\Turboism-plugin-payload"' in generated_plugins
          and 'RMDir /r "$PLUGINSDIR\\Turboism-plugin-plan"' in generated_plugins)


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
    check("I4 installed icon assets are checksum-staged and removed",
          '("turboism.ico", stage / "turboism.ico")' in assemble
          and '("turboism.png", stage / "turboism.png")' in assemble
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
    check("L6 installer keeps BAT integration explicit without defensive help copy",
          "only if explicitly selected" in text
          and "仅在明确勾选时" in text
          and "当前支持版本：5.2.03, 5.3.02, 5.3.03" in text
          and "Currently supported versions: 5.2.03, 5.3.02, 5.3.03" in text
          and "BatIntegrationHelp" not in text
          and "$integrateCubismBat" in text
          and "$batCheck" in configure)
    check("L6b Start-menu and BAT controls are independent and reversible",
          "$createStartMenu" in text
          and "$integrateCubismBat" in text
          and "Disable-CubismShortcutIntegration" in common
          and "Restore-CubismBatIntegrations" in common)
    launch_options = text[text.index("Function LaunchOptionsCreate"):
                          text.index("FunctionEnd", text.index("Function LaunchOptionsLeave"))]
    check("L6d launch options are independent, localized, and tightly spaced",
          all(('LangString DesktopShortcutOption ${LANG_%s}' % lang) in text
              for lang in ("ENGLISH", "SIMPCHINESE", "JAPANESE"))
          and '${NSD_CreateCheckbox} 0 30u 100% 18u "$(StartMenuOption)"' in launch_options
          and '${NSD_CreateCheckbox} 0 50u 100% 18u "$(DesktopShortcutOption)"' in launch_options
          and '${NSD_CreateCheckbox} 0 70u 100% 32u "$(BatIntegrationOption)"' in launch_options
          and "$DesktopShortcutCheckbox" in launch_options
          and "${NSD_GetState} $DesktopShortcutCheckbox $createDesktopShortcut" in launch_options
          and "StrCpy $createDesktopShortcut 1" in text
          and "${NSD_Check} $DesktopShortcutCheckbox" in launch_options)
    check("L6e installer title includes the configured Turboism version",
          'Name "Turboism ${VER}"' in text)
    check("L6f desktop shortcut creation, opt-out cleanup, and uninstall are symmetric",
          'CreateShortCut "$DESKTOP\\Turboism_Launch_Cubism.lnk"' in start_menu
          and start_menu.count('Delete "$DESKTOP\\Turboism_Launch_Cubism.lnk"') == 1
          and 'Delete "$DESKTOP\\Turboism_Launch_Cubism.lnk"' in uninstall
          and "${AndIf} $createDesktopShortcut == 0" in text)
    check("L6c fresh official BAT backups preserve exact source bytes",
          "[System.IO.File]::Copy($bat, $backup, $false)" in common
          and "Cubism BAT backup verification failed" in common
          and "[int64]$backupFile.Length -ne $originalLength" in common
          and "(Get-CubismSha256 $backup) -ine $originalHash" in common)
    check("L7 finish page launches Turboism by default and can open the installation directory",
          "MUI_FINISHPAGE_RUN_FUNCTION LaunchTurboism" in text
          and "MUI_FINISHPAGE_RUN_TEXT \"$(FinishLaunchTurboismText)\"" in text
          and "MUI_FINISHPAGE_RUN_NOTCHECKED" not in text
          and "MUI_FINISHPAGE_SHOWREADME_FUNCTION OpenInstallDirectory" in text
          and all(('LangString FinishLaunchTurboismText ${LANG_%s}' % lang) in text
                  for lang in ("ENGLISH", "SIMPCHINESE", "JAPANESE"))
          and "FinishOpenFolderText" in text
          and 'Exec \'"$SYSDIR\\cmd.exe" /D /S /C ""$INSTDIR\\launch-cubism-turboism.bat""\'' in text
          and 'ExecShell "" "$INSTDIR\\launch-cubism-turboism.bat"' not in text
          and 'explorer.exe' in text)
    install_success = text[text.index("Function .onInstSuccess"):
                           text.index("FunctionEnd", text.index("Function .onInstSuccess"))]
    check("L7b discovery payload is removed before the Finish page",
          'RMDir /r "$CubismDiscoveryWorkDir"' in install_success
          and 'StrCpy $CubismDiscoveryWorkDir ""' in install_success
          and install_success.index('RMDir /r "$CubismDiscoveryWorkDir"')
              < install_success.index("-InitializeSelection"))
    check("L8 managed launcher prints the Turboism banner before Cubism selection",
          "function Write-TurboismLauncherBanner" in launcher
          and "For you, a bouquet." in launcher
          and 'Join-Path $turboismHome "README.txt"' in launcher
          and launcher.index("\nWrite-TurboismLauncherBanner\n")
              < launcher.index("$cubism = Resolve-SelectedCandidate"))




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
    check("Simplified Chinese MIT acceptance consistently uses 我同意",
          'LangString LicenseBottomText ${LANG_SIMPCHINESE} "如果您同意 MIT License，请勾选下方复选框后继续。"' in text
          and 'LangString LicenseAcceptText ${LANG_SIMPCHINESE} "我同意 MIT License"' in text
          and '我接受 MIT License' not in text)
    statement_declaration = text[first + len(
        '!insertmacro MUI_PAGE_LICENSE "${LICENSE_FILE}"'):statement]
    check("EULA statement page keeps the complete localized scrollable body",
          'MUI_LICENSEPAGE_TEXT_TOP "$(EulaTopText)"' in statement_declaration
          and "MUI_LICENSEPAGE_CHECKBOX" not in statement_declaration
          and "MUI_PAGE_CUSTOMFUNCTION_SHOW" not in statement_declaration
          and "MUI_PAGE_CUSTOMFUNCTION_LEAVE" not in statement_declaration)
    check("Simplified Chinese EULA agree button consistently uses 我同意(I)",
          '!define MUI_LICENSEPAGE_BUTTON "$(EulaAgreeButtonText)"' in statement_declaration
          and 'LangString EulaAgreeButtonText ${LANG_SIMPCHINESE} "我同意(&I)"' in text
          and '我接受(&I)' not in text)
    acknowledgements = (
        "我确认 Turboism 是独立第三方项目，并非 Live2D 官方产品。",
        "我确认使用 Cubism 仍需合法、有效的授权；Turboism 不提供、替代或绕过 Cubism 的许可校验。",
        "我确认我已理解：由我启动或授权的插件、脚本、MCP、API 和自动化操作可能修改、覆盖或删除工程内容，并将自行保留独立备份。",
        "我确认我已理解：Turboism 是按现状提供的开源项目，不保证持续兼容、无错误或成功恢复。",
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
    check("NSIS acknowledgement rows are compact, contiguous, and fully visible",
          '${NSD_CreateCheckbox} 0 0 100% 24u "$(EulaAck1)"' in create
          and '${NSD_CreateCheckbox} 0 24u 100% 30u "$(EulaAck2)"' in create
          and '${NSD_CreateCheckbox} 0 54u 100% 38u "$(EulaAck3)"' in create
          and '${NSD_CreateCheckbox} 0 92u 100% 30u "$(EulaAck4)"' in create)
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
    generator = (INSTALLER_NSI.parent / "assemble-release.sh").read_text(encoding="utf-8")
    check("NSIS license pages use BOM-prefixed UTF-8 EULA copies",
          'printf \'\\xef\\xbb\\xbf\'' in generator
          and 'mkdir -p "$generated/eula"' in generator
          and '-DEULA_DIR="$generated/eula"' in generator
          and '-DEULA_DIR="$repo_root/packaging/eula"' not in generator
          and 'EULA.$lang.txt' in generator)
    core_payload = generator[generator.index("core_payload = ["):
                             generator.index("plugin_payload = [")]
    uninstall_start = text.index('Section "Uninstall"')
    uninstall_end = text.index("SectionEnd", uninstall_start)
    uninstall = text[uninstall_start:uninstall_end]
    for name in ("EULA.en.txt", "EULA.zh-Hans.txt", "EULA.ja.txt"):
        check("NSIS checksum-packages %s" % name,
              ('("%s", stage / "%s")' % (name, name)) in core_payload)
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
    check("G1b discovery accepts GraalVM metadata across vendors and Java versions",
          "function Test-CubismCompatibleGraalJava" in common
          and 'GRAALVM_VERSION="[^"\\r\\n]+"' in common
          and 'JAVA_VERSION="[^"\\r\\n]+"' in common
          and 'IMPLEMENTOR="GraalVM Community"' not in common
          and 'GRAALVM_VERSION="25\\.2\\.4"' not in common
          and 'JAVA_VERSION="25\\.0\\.4"' not in common)
    discovery = common[common.index("function Find-CubismGraalJava"):
                       common.index("function Resolve-CubismGraalJava")]
    check("G1c custom GraalVM path is consulted after automatic discovery",
          "function Read-CubismGraalVmPath" in common
          and '$configured = Read-CubismGraalVmPath -TurboismHome $TurboismHome' in discovery
          and discovery.index('$candidates += (Join-Path $TurboismHome "graalvm\\bin\\java.exe")')
              < discovery.index('$configured = Read-CubismGraalVmPath'))
    check("G1d custom path accepts the documented bin directory level",
          '(Split-Path -Leaf $path) -ieq "bin"' in discovery
          and 'Join-Path $path "java.exe"' in discovery)
    check("G1e missing optional custom path is strict-mode safe",
          '$launcherProperty.Value.PSObject.Properties["graalVmPath"]' in common
          and '$null -eq $pathProperty' in common)
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
    cleanup = find_stmt(texts, lambda t: "nsExec::ExecToLog" in t and "-Cleanup" in t)
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

    # T3: 已有 current v1 时，无论本次插件选择如何都保持原始字节。
    existing = json.dumps({"format": "turboism.runtime.config", "schemaVersion": 1,
                           "worktreeId": "user-runtime", "pluginDirs": ["custom"],
                           "disabledPlugins": [a, UNRELATED], "logLevel": "DEBUG"}, indent=2)
    out = installer_write_config("full", [b], existing)
    check("T3 current schema 原字节跳过", out == existing)

    # T4: Lite 更新也不能以模式选择覆盖已有 current config。
    out = installer_write_config("lite", BUNDLED_IDS, existing)
    check("T4 current schema 不受 Lite 更新覆盖", out == existing)

    # T5: Lite 全新安装仍写入全部捆绑 id。
    out = installer_write_config("lite", [a, b], None)
    doc = json.loads(out)
    check("T5 fresh lite 禁用全部捆绑 id", doc["disabledPlugins"] == BUNDLED_IDS,
          str(doc.get("disabledPlugins")))

    # T6: schema-less v0 迁移到 v1，并保留已知用户设置而不套用本次选择。
    legacy = json.dumps({"worktreeId": "legacy-runtime", "pluginDirs": ["custom"],
                         "disabledPlugins": [a, UNRELATED], "logLevel": "DEBUG",
                         "cubismJvm": "bundled"})
    out = installer_write_config("full", [b], legacy)
    doc = json.loads(out)
    check("T6 v0 迁移到 current schema",
          doc["format"] == "turboism.runtime.config" and doc["schemaVersion"] == 1)
    check("T6 v0 保留用户设置",
          doc["worktreeId"] == "legacy-runtime" and doc["pluginDirs"] == ["custom"]
          and doc["disabledPlugins"] == [a, UNRELATED] and doc["logLevel"] == "DEBUG")
    check("T6 v0 JVM 字段迁入 launcher", doc["launcher"]["cubismJvm"] == "bundled")

    # T7: 未知 legacy 字段失败关闭。
    try:
        installer_write_config("full", [], '{"legacyUnknown":true}')
        check("T7 未知 v0 字段失败关闭", False)
    except ValueError:
        check("T7 未知 v0 字段失败关闭", True)

    # T8: 未来 schema 不得被旧安装器降级。
    try:
        installer_write_config("full", [],
                               '{"format":"turboism.runtime.config","schemaVersion":2}')
        check("T8 future schema 禁止降级", False)
    except ValueError:
        check("T8 future schema 禁止降级", True)

    # T9: current schema 可以包含安装器不理解的新字段，仍按字节跳过。
    existing = ('{\n  "format":"turboism.runtime.config",\n  "schemaVersion":1,\n'
                '  "futureCurrentField":{"ownedBy":"runtime"}\n}')
    check("T9 current schema 未知字段仍不覆盖",
          installer_write_config("full", [a], existing) == existing)

    # ---- 插件载荷库存模拟（隐藏载荷 Section + 勾选语义）----
    # TI1: 全新部分 Full（未勾选 {a, c}）→ JAR 全量安装；disabledPlugins=[a,c]
    jars = nsis_jars_after("full", [])
    check("TI1 Full 安装全部 JAR", jars == BUNDLED_MODULES, str(jars))
    out = installer_write_config("full", [a, c], None)
    doc = json.loads(out)
    check("TI1 disabledPlugins == 未勾选", doc["disabledPlugins"] == [a, c], str(doc.get("disabledPlugins")))

    # TI2: 后续 Full 更新仍铺设完整 JAR，但 current config 保持原始选择。
    original = out
    out = installer_write_config("full", [b], out)
    doc = json.loads(out)
    jars = nsis_jars_after("full", jars)
    check("TI2 更新后 JAR 库存完整", jars == BUNDLED_MODULES, str(jars))
    check("TI2 更新不覆盖 current config", out == original and doc["disabledPlugins"] == [a, c])

    # TI2b: legacy v0 迁移保留它原有的 disabledPlugins，不套用安装页重选。
    existing = '{"disabledPlugins": ["' + a + '","' + UNRELATED + '"]}'
    out = installer_write_config("full", [b], existing)
    doc = json.loads(out)
    check("TI2b v0 迁移保留插件设置", doc["disabledPlugins"] == [a, UNRELATED],
          str(doc.get("disabledPlugins")))

    # TI3: 全新 Lite → 无插件 JAR；禁用全部捆绑 id
    jars = nsis_jars_after("lite", [])
    check("TI3 Lite 全新无插件 JAR", jars == [], str(jars))
    out = installer_write_config("lite", [], None)
    doc = json.loads(out)
    check("TI3 Lite 禁用全部捆绑 id", doc["disabledPlugins"] == BUNDLED_IDS, str(doc.get("disabledPlugins")))

    # TI4: Full→Lite 更新不写新 JAR，也不覆盖已有 current config。
    jars = nsis_jars_after("lite", BUNDLED_MODULES)
    check("TI4 Full→Lite 不写新 JAR（当前旧 JAR 留盘）", jars == BUNDLED_MODULES, str(jars))
    existing = json.dumps({"format": "turboism.runtime.config", "schemaVersion": 1,
                           "worktreeId": "turboism-runtime", "pluginDirs": ["plugins"],
                           "disabledPlugins": [a]})
    out = installer_write_config("lite", [], existing)
    doc = json.loads(out)
    check("TI4 Full→Lite 保持 current config", out == existing and doc["disabledPlugins"] == [a])

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
    check_config_migration_contract()
    check_managed_graal_installer_contract()
    check_configurator_flow_contract()
    check_managed_fx_contract()
    check_jar_payload_contract()
    check_icon_contract()
    check_launcher_and_shortcut_contract()
    check_eula_contract()
    check_graal_fallback_contract()
    check_uninstall_config_option()
    check_uninstall_postcondition()
    print("config merge + payload 模拟 + uninstall 后置验证通过：全部用例 ok")


if __name__ == "__main__":
    main()
