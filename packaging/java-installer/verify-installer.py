#!/usr/bin/env python3
"""Deterministic non-GUI verification for the Turboism Java installer.

Drives the IzPack installer in console mode (Java 17+, no GUI) and asserts
the frozen acceptance conditions, including the R2 repairs:

  1.  The installer JAR and its SHA-256 sidecar exist and match; the sidecar
      names only the sibling JAR so `sha256sum -c` works after download.
  2.  JAR contains en/zh/ja language resources (built-in langpacks and the
      locale-suffixed CustomLangPack variants), generated uninstaller support,
      one required common pack, one required Full-only plugin-payload pack
      (every bundled plugin JAR) and one optional metadata-only selection pack
      per non-core plugin, plus the complete Windows launch-helper payload.
      writes all bundled ids to disabledPlugins.
  4.  Full install defaults all plugins; deselecting two still installs every
      bundled JAR (payload pack) while disabledPlugins reflects the deselected
      ids; reselecting a previously disabled bundled plugin enables it while
      unrelated disabled ids remain; result is sorted.
  5.  Existing config merge preserves unrelated valid fields (including large
      integer and exponent numbers, without emitting non-finite JSON) and
      never damages the source on malformed, strict-number, oversized
      (including the exact MAX+1 consumed-byte boundary), symlink,
      non-regular, canonical-identity, or malformed-escape cases. A
      deterministic Java regression (ConfigMergeRegression) additionally
      covers strict number lexing, canonical v1 identity, the consumed-byte
      cap with deterministic concurrent growth, and atomic
      REPLACE_EXISTING replacement.
      retired ids from disabledPlugins. Preserved or leftover retired
      descriptors (NSIS/manual/renamed) are denied by the runtime's shared
      PluginJarContract boundary (PLUGIN_RETIRED_ID) before entrypoint
      loading; the installer itself never deletes unverifiable entries and
      the NSIS installer deletes no plugin JARs.
  6.  Locale probes (eng/chn/jpn) observe the translated Turboism-owned
      common-pack label, the localized wizard headline, and the localized
      Full and Lite mode names/descriptions emitted live by the installer.
  7.  Uninstall invokes the shipped generated uninstaller entrypoint
      (`java -Dturboism.uninstall.deleteConfig=... -jar <home>/Uninstaller/
      uninstaller.jar -console`) and waits for the SelfModifier background
      chain to reach its terminal state; both delete-config and
      preserve-config branches complete without retries; a synthetic
      third-party plugin file is left untouched; relocated copies of the
      shipped uninstaller cannot perform custom deletion in either a wrong
      shape or a matching `<unrelated>/Uninstaller/uninstaller.jar` shape,
      and the original home's custom sentinels survive the mismatched copy.
      verification-owned `-Djava.io.tmpdir=<task-temp>`; the fixed global
      `iz-Turboism.tmp` lock path is never created, overwritten, renamed or
      deleted — a read-only snapshot proves it untouched (absent stays
      absent; a pre-existing file/symlink/special path is byte/target/mode-
      identical at the end) — and cleanup is limited to paths owned by this
      verifier. Every captured Java subprocess runs under a deterministic
      UTF-8 console contract (JVM `-Dfile.encoding=UTF-8` and explicit
      Python `encoding="utf-8"`). macOS additionally checks that the
      installed uninstall.command is a regular non-symlink executable file.
  8.  The plugin payload matches the sole release-plugin allowlist
      `packaging/release-plugins.txt` exactly (the frozen 17 approved
      projects; runtime-owned core is never a payload plugin), and the seven
      excluded public modules' IDs/JARs are absent from the payload, packs, and
      selection surface — the shared manifest is the regression oracle.
  9.  Managed fx packaging fails closed: simulated unsupported Windows rejects
      Full before payload mutation while Thin and Lite remain platform-independent
      byte-absence modes. A selected platform symlink rejects Full without
      touching its target; a Full upgrade removes pre-existing non-current
      Linux and macOS platform directories. Thin/Lite transitions over an
      existing Full home fail closed before config or payload mutation until
      the managed runtime is removed through a separate explicit action.

Runnable on Linux/macOS/Windows with Java 17. stdlib-only.
"""

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import stat
import zipfile

JAVA = "java"
TIMEOUT = 360  # generous; console installs extract ~8 MB

# Verification-owned temporary root: passed as -Djava.io.tmpdir to every JVM
# and exported as TMPDIR/TEMP/TMP so the SelfModifier child phases inherit it.
TASK_TMP = None
PROC_ENV = None

# The fixed global IzPack lock path (java.io.tmpdir/iz-<appname>.tmp) used by
# installers of this and other processes. The verifier must never create,
# overwrite, rename or delete it; it only records and re-verifies its
# presence/type/content without touching it (R15).
GLOBAL_LOCK = os.path.join(tempfile.gettempdir(), "iz-Turboism.tmp")

# The three installer locales (declaration order in installer.xml <locale>).
# The console language list is printed in that order; the indices are parsed
# from the first run's output rather than assumed.
LOCALES = ["eng", "chn", "jpn"]
LOCALIZED_PACK_LABEL = {
    "eng": "Turboism Core",
    "chn": "Turboism 核心",
    "jpn": "Turboism コア",
}
LOCALIZED_HEADLINE = {
    "eng": "Welcome",
    "chn": "欢迎",
    "jpn": "ようこそ",
}
LOCALIZED_EULA_MARKER = {
    "eng": "The Simplified Chinese text is authoritative",
    "chn": "本版本以简体中文文本为正式文本",
    "jpn": "簡体字中国語文を正文とします",
}
# Turboism-owned InstallationGroupPanel strings (CustomLangPack): the
# install-side listener emits the localized mode name and description for
# both modes on every run, and the probes must observe them live.
LOCALIZED_MODE = {
    "eng": {
        "full": ("Full installation (bundled plugins)",
                 "Installs the Turboism agent and all approved release plugins. You can deselect individual plugins on the next page."),
        "thin": ("Thin installation (bundled plugins, no fx runtime)",
                 "Installs all approved release plugins without native fx runtime bytes."),
        "lite": ("Lite installation (no plugins)",
                 "Installs only the Turboism agent and common files. No first-party plugin JAR is copied."),
    },
    "chn": {
        "full": ("完整安装（发布插件）",
                 "安装 Turboism 代理与全部获准发布的插件。可在下一页取消勾选个别插件。"),
        "thin": ("轻量安装（发布插件，不含 fx 运行时）",
                 "安装全部获准发布的插件，但不包含 fx 原生运行时字节。"),
        "lite": ("精简安装（不含插件）",
                 "仅安装 Turboism 代理与公共文件，不复制任何第一方插件 JAR。"),
    },
    "jpn": {
        "full": ("フルインストール（公開対象プラグイン）",
                 "Turboism エージェントと公開が承認された全プラグインをインストールします。次のページで個別に選択を解除できます。"),
        "thin": ("Thin インストール（公開対象プラグイン、fx ランタイムなし）",
                 "公開が承認された全プラグインをインストールしますが、fx ネイティブランタイムは含みません。"),
        "lite": ("ライトインストール（プラグインなし）",
                 "Turboism エージェントと共通ファイルのみをインストールします。ファーストパーティプラグインの JAR はコピーされません。"),
    },
}
UNINSTALL_DELETE_CONFIG_PROP = "turboism.uninstall.deleteConfig"
EULA_ACKNOWLEDGEMENT_KEYS = ("independent", "license", "backup", "asIs")
EULA_ACKNOWLEDGEMENT_TEXT = {
    "eng": (
        "Turboism is an independent third-party project, not an official Live2D product.",
        "Cubism still requires lawful authorization; Turboism does not provide, replace, or bypass license verification.",
        "user-authorized plugins, scripts, MCP, API, or automation can modify, overwrite, or delete project content",
        "Turboism is open source and provided as-is, without guarantees of continued compatibility, no errors, or successful recovery.",
    ),
    "chn": (
        "Turboism 是独立的第三方项目，并非 Live2D 的官方产品。",
        "Cubism 仍需要合法授权；Turboism 不提供、不替代也不绕过许可证验证。",
        "经用户授权的插件、脚本、MCP、API 或自动化操作可能修改、覆盖或删除项目内容",
        "Turboism 是开源软件，按现状提供，不保证持续兼容、无错误或能够成功恢复。",
    ),
    "jpn": (
        "Turboism は独立した第三者プロジェクトであり、Live2D の公式製品ではないことを理解します。",
        "Cubism には引き続き適法な許諾が必要であり、Turboism はライセンス検証を提供、代替、または回避しないことを理解します。",
        "ユーザーが許可したプラグイン、スクリプト、MCP、API、または自動化によりプロジェクト内容が変更、上書き、削除される場合があり",
        "Turboism はオープンソースであり現状有姿で提供され、継続的な互換性、無エラー、または復旧の成功は保証されないことを理解します。",
    ),
}


def eula_acknowledgement_record():
    return "".join(
        '<acknowledgement id="%s" accepted="true"/>\n' % key
        for key in EULA_ACKNOWLEDGEMENT_KEYS
    )


def assert_eula_acknowledgement_rejection(jar, payload_plugins):
    base = tempfile.mkdtemp(prefix="turboism-eula-reject ")
    target = os.path.join(base, "home")
    answers = install_answers("lite", target, payload_plugins=payload_plugins)
    answers[3] = "2"  # reject the first required acknowledgement
    clear_task_lock()
    rc, out = run_console(jar, answers)
    check("EULA acknowledgement rejection aborts console install", rc != 0, "rc=%s" % rc)
    check("EULA acknowledgement rejection leaves payload absent",
          not os.path.lexists(os.path.join(target, "turboism-agent.jar")))
    shutil.rmtree(base, ignore_errors=True)


def assert_automated_eula_gate(jar):
    base = tempfile.mkdtemp(prefix="turboism-eula-auto ")
    rejected = os.path.join(base, "rejected.xml")
    common = '<AutomatedInstallation langpack="eng">\n<panel id="eulaAcknowledgements">\n%s</panel>\n</AutomatedInstallation>\n'
    open(rejected, "w", encoding="utf-8").write(
        common % '<acknowledgement id="independent" accepted="true"/>\n'
    )
    # The compiled Java regression exercises accepted, omitted, false,
    # duplicate, and unknown records directly against the exact automation
    # helper. This live IzPack probe additionally proves sibling helper binding:
    # an incomplete record reaches the custom gate before payload extraction.
    clear_task_lock()
    proc = subprocess.run(
        java_cmd() + ["-jar", jar, "-auto", rejected], input="", stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, encoding="utf-8", env=PROC_ENV, timeout=TIMEOUT
    )
    check("automated EULA incomplete record rejects", proc.returncode != 0,
          "rc=%s output=%s" % (proc.returncode, proc.stdout[-500:]))
    check("automated EULA rejection names acknowledgement contract",
          "acknowledgement" in proc.stdout.lower(), proc.stdout[-500:])
    shutil.rmtree(base, ignore_errors=True)


# Frozen release-plugin allowlist — sole authority is packaging/release-plugins.txt.
# This exact 17-project list plus the eight excluded public module names is the regression
# oracle; the id/name for every listed module comes from its committed
# plugin.json descriptor at verification time (see load_plugin_metadata), so
# production drift from the shared manifest or the source descriptors fails.
MANIFEST_EXPECTED = [
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
# The eight public-exclusion modules: absent from the manifest and therefore
# from every release payload, pack, section, and selection surface. Their
# committed ids are read from each module's plugin.json descriptor at
# verification time, never derived from module names.
EXCLUDED_PUBLIC_MODULES = (
    "bounding-box",
    "context-menu",
    "demo",
    "parameter",
    "project-inspector",
    "project-panel",
    "psd-import",
    "turboism-with-fx",
)
# Renamed algorithm identity: module/JAR atlas-maxrects-bssf carries the
# MaxRects-BSSF display name and the historical texture-atlas compatibility id.
ALGORITHM_MODULE = "atlas-maxrects-bssf"
ALGORITHM_NAME = "MaxRects-BSSF Layout Algorithm"
ALGORITHM_COMPAT_ID = "dev.turboism.plugin.texture-atlas"
# Retired fake plugin modules and their embedded ids (retirement slice):
# neither the manifest nor any payload may ever produce them again.
RETIRED_MODULES = ("log-filter", "clip-mask", "perf-opt", "render-opt")
RETIRED_PLUGIN_IDS = [
    "dev.turboism.plugin.logfilter",
    "dev.turboism.plugin.clipmask",
    "dev.turboism.plugin.perfopt",
    "dev.turboism.plugin.renderopt",
]

FX_VERSION = "0.0.5"
FX_SOURCE_COMMIT = "df7e6245e1992758d4060c97477ceafa27770551"
FX_PLATFORM = {
    "linux-x86_64": (11870712, "27a5e9474fd749d6ca2503ab93765176a93ffbd0f0e7173e8f2e3e4c6b51876f", "fx"),
    "linux-aarch64": (10133856, "35e972dc8be31b736a0d7fd733157f9d77a6a46dee33e0172ee51cd27915577d", "fx"),
    "macos-x86_64": (12307081, "3170e25c2238b73971d992936b482d058282cb19d7beb34098e808d71c244428", "fx"),
    "macos-aarch64": (6431792, "caad628680cd2af24d79063f109965b71c24f69c7b06318b50178c76cc40d0c9", "fx"),
    "windows-x86_64": (11144192, "a36b0b209d933e4757d7e1a961d259d39a8d370b68cbde8e9cba227603ac63c2", "fx.exe"),
}


def fail(msg):
    print("FAIL: %s" % msg, file=sys.stderr)
    sys.exit(1)


def check(name, cond, detail=""):
    if not cond:
        fail("%s %s" % (name, detail))
    print("  ok: %s" % name)


def java_cmd(extra_flags=()):
    """Base java command with the verification-owned tmpdir pinned and a
    deterministic UTF-8 console contract (R16): every captured Java
    subprocess decodes/encodes through JVM file.encoding=UTF-8 so the
    en/zh/ja console evidence is byte-deterministic on any host default
    charset (Windows cp1252, POSIX locales)."""
    return [JAVA, "-Djava.io.tmpdir=%s" % TASK_TMP,
            "-Dfile.encoding=UTF-8"] + list(extra_flags)

def clear_task_lock():
    """Removes a stale IzPack lock inside OUR task tmpdir only. The lock file
    is normally deleted on JVM exit; this covers crashed previous runs and
    never touches any global path."""
    try:
        os.unlink(os.path.join(TASK_TMP, "iz-Turboism.tmp"))
    except FileNotFoundError:
        pass


def run_console(jar, answers, timeout=TIMEOUT, java_flags=()):
    """Runs `java -jar <jar> -console` with piped answers.

    stdin is deliberately kept open: the console prompts read incrementally
    and an early EOF aborts the install. Returns (exit_code, output).
    """
    cmd = java_cmd(java_flags) + ["-jar", jar, "-console"]
    proc = subprocess.Popen(
        cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, encoding="utf-8", bufsize=1, env=PROC_ENV)
    chunks = []

    def reader():
        for line in proc.stdout:
            chunks.append(line)

    thread = threading.Thread(target=reader, daemon=True)
    thread.start()
    try:
        proc.stdin.write("\n".join(answers) + "\n")
        proc.stdin.flush()
        rc = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        fail("installer timed out; output so far:\n" + "".join(chunks))
    thread.join(timeout=5)
    return rc, "".join(chunks)


def assert_utf8_contract():
    """R16: executable static assertion of the deterministic UTF-8 console
    contract — the base java command used for every captured Java subprocess
    (installer, uninstaller, relocated uninstaller, regression) pins JVM
    file.encoding=UTF-8, and all Popen captures decode with explicit
    encoding="utf-8" (strict). The live en/zh/ja locale probes therefore
    observe real UTF-8 output under any host default charset."""
    cmd = java_cmd()
    check("base java command pins -Dfile.encoding=UTF-8",
          "-Dfile.encoding=UTF-8" in cmd, "cmd=%s" % cmd)


def run_java_regression(regression_jar):
    """Runs the deterministic regression against the exact classes shipped in
    the installer, including v1 state compatibility, hash-guarded independent
    cleanup, exact-byte takeover restoration, and conflict preservation."""
    check("regression jar exists", os.path.isfile(regression_jar), regression_jar)
    cmd = java_cmd() + ["-cp", regression_jar, "dev.turboism.installer.ConfigMergeRegression"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", env=PROC_ENV)
    out, _ = proc.communicate(timeout=300)
    print(out, end="")
    check("java config-merge regression passed", proc.returncode == 0,
          "rc=%s" % proc.returncode)


def load_plugin_inventory(payload):
    """Reads the bundled plugin ids/modules from the shared staged payload
    (the same single source the installer.xml generator uses)."""
    plugins = []
    for jar in sorted(os.listdir(os.path.join(payload, "plugins"))):
        if not jar.endswith(".jar"):
            continue
        with zipfile.ZipFile(os.path.join(payload, "plugins", jar)) as z:
            meta = json.loads(z.read("META-INF/turboism/plugin.json"))
        pid = meta.get("id")
        if not isinstance(pid, str) or not pid.strip():
            fail("anonymous plugin id in %s: plugin.json has no usable id" % jar)
        pid = pid.strip()
        plugins.append({
            "module": jar[:-4],
            "id": pid,
            "name": meta.get("name", pid),
            "version": meta.get("version", ""),
            "description": meta.get("description", ""),
        })
    ids = [p["id"] for p in plugins]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        fail("duplicate plugin ids in staged payload: %s" % duplicates)
    plugins.sort(key=lambda p: p["id"])
    return plugins


def load_properties(path):
    values = {}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\r\n")
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        check("managed fx manifest line has '='", bool(separator), line)
        check("managed fx manifest has unique keys", key not in values, key)
        values[key] = value
    return values


def assert_managed_fx_payload(payload):
    root = os.path.join(payload, "runtimes", "fx", FX_VERSION)
    check("staged managed fx root exists", os.path.isdir(root), root)
    check("staged managed fx platforms are exact",
          sorted(os.listdir(root)) == sorted(FX_PLATFORM),
          "actual=%s" % sorted(os.listdir(root)))
    for platform, (expected_size, expected_hash, executable_name) in FX_PLATFORM.items():
        directory = os.path.join(root, platform)
        expected_files = {
            executable_name, "LICENSE", "THIRD_PARTY_NOTICES.md",
            "TURBOISM-DISTRIBUTION-NOTICE.txt", "manifest.properties",
        }
        check("managed fx %s file inventory" % platform,
              set(os.listdir(directory)) == expected_files,
              "actual=%s" % sorted(os.listdir(directory)))
        executable = os.path.join(directory, executable_name)
        check("managed fx %s executable is regular" % platform,
              os.path.isfile(executable) and not os.path.islink(executable), executable)
        check("managed fx %s executable size" % platform,
              os.path.getsize(executable) == expected_size,
              str(os.path.getsize(executable)))
        digest = hashlib.sha256(open(executable, "rb").read()).hexdigest()
        check("managed fx %s executable hash" % platform,
              digest == expected_hash, digest)
        manifest = load_properties(os.path.join(directory, "manifest.properties"))
        check("managed fx %s manifest version" % platform,
              manifest.get("fxVersion") == FX_VERSION)
        check("managed fx %s manifest source commit" % platform,
              manifest.get("sourceCommit") == FX_SOURCE_COMMIT)
        for notice in ("LICENSE", "THIRD_PARTY_NOTICES.md",
                       "TURBOISM-DISTRIBUTION-NOTICE.txt"):
            check("managed fx %s %s non-empty" % (platform, notice),
                  os.path.getsize(os.path.join(directory, notice)) > 0)


def load_release_manifest(path):
    """Parses the sole release-plugin allowlist (packaging/release-plugins.txt)
    fail-closed: blank/comment lines, malformed or non-plugin entries,
    duplicates, unsorted order, or drift from the frozen 17-project allowlist
    are fatal. Returns the allowlisted plugin module names (manifest entries
    minus the runtime-owned core)."""
    check("release manifest exists", os.path.isfile(path), path)
    raw = open(path, encoding="utf-8").read().splitlines()
    invalid = [l for l in raw if not l.strip() or l.strip().startswith("#")]
    check("release manifest forbids blank/comment lines", not invalid,
          "found=%s" % invalid[:3])
    lines = [l.strip() for l in raw if l.strip() and not l.strip().startswith("#")]
    entry = re.compile(r"^:plugins:[a-z0-9-]+$")
    malformed = [l for l in lines if not entry.match(l)]
    check("release manifest entries are plugin paths", not malformed,
          "bad=%s" % malformed[:3])
    check("release manifest has no duplicates", len(set(lines)) == len(lines))
    check("release manifest is ASCII-sorted", lines == sorted(lines))
    check("release manifest matches the frozen 17-project allowlist",
          lines == MANIFEST_EXPECTED, "n=%d" % len(lines))
    return [l[len(":plugins:"):] for l in lines if l != ":plugins:core"]


def load_plugin_metadata(manifest_path, modules):
    """Reads committed plugin.json descriptors for the given modules — the
    regression oracle for payload identity. The canonical
    packaging/release-plugins.txt path anchors the repository root; each
    descriptor is read only at the exact
    plugins/<module>/src/main/resources/META-INF/turboism/plugin.json path
    (no scans, no id/name inference). Fail-closed on manifest path shape,
    missing or non-regular descriptor, non-object JSON, blank/non-string
    id/name, and duplicate module or id. Returns
    {module: {"id": id, "name": name}}."""
    canonical = os.path.normpath(manifest_path)
    check("release manifest path has canonical packaging shape",
          os.path.basename(canonical) == "release-plugins.txt"
          and os.path.basename(os.path.dirname(canonical)) == "packaging",
          canonical)
    root = os.path.dirname(os.path.dirname(canonical))
    metadata = {}
    seen_ids = set()
    for module in modules:
        check("metadata module listed once", module not in metadata, module)
        descriptor = os.path.join(root, "plugins", module, "src", "main",
                                  "resources", "META-INF", "turboism",
                                  "plugin.json")
        check("plugin descriptor exists and is a regular file",
              os.path.isfile(descriptor) and not os.path.islink(descriptor), descriptor)
        with open(descriptor, encoding="utf-8") as f:
            meta = json.load(f)
        check("plugin descriptor is a JSON object", isinstance(meta, dict),
              descriptor)
        pid = meta.get("id")
        pname = meta.get("name")
        check("plugin descriptor has nonblank string id",
              isinstance(pid, str) and bool(pid.strip()), descriptor)
        check("plugin descriptor has nonblank string name",
              isinstance(pname, str) and bool(pname.strip()), descriptor)
        check("plugin descriptor id unique across metadata",
              pid.strip() not in seen_ids, pid)
        seen_ids.add(pid.strip())
        metadata[module] = {"id": pid.strip(), "name": pname.strip()}
    return metadata


def install_answers(mode, target, lang_index=0, deselect=(), payload_plugins=None,
                    install_graal=False):
    answers = [str(lang_index), "1", "1"]  # language, welcome, MIT license
    answers += ["1"] * len(EULA_ACKNOWLEDGEMENT_KEYS)  # all required custom acknowledgements
    answers += ["1"]  # stock EULA
    # IzPack sorts groups by id in console mode: full, lite, then thin.
    if mode == "full":
        answers += ["y"]
    elif mode == "thin":
        answers += ["n", "n", "y"]
    else:
        answers += ["n", "y"]
    answers += ["1"]  # group panel continue
    # The optional managed-Graal pack is Windows-only. It is available in
    # Full and Lite, default-unselected, and precedes plugin selection packs.
    if os.name == "nt":
        answers.append("y" if install_graal else "n")
    if mode in ("full", "thin"):
        for p in payload_plugins:
            answers.append("n" if p["id"] in deselect else "y")
    answers += ["1"]  # packs panel continue
    answers.append(target)
    if os.path.isdir(target):
        answers.append("y")  # overwrite warning (existing directory)
    else:
        answers.append("O")  # create-directory confirmation
    answers += ["1", "n", "1"]  # target continue, finish script, finish
    return answers


def discover_language_indices(jar):
    """Runs the installer console once, parses the language list, then quits
    at the welcome panel. Returns {iso3: index}."""
    base = tempfile.mkdtemp(prefix="turboism-langdisc ")
    target = os.path.join(base, "home")
    clear_task_lock()
    rc, out = run_console(jar, ["0", "2"])
    indices = {}
    for line in out.splitlines():
        m = re.match(r"^(\d+)\s+\[[ x]\]\s+(\w+)$", line.strip())
        if m:
            indices[m.group(2).lower()] = int(m.group(1))
    shutil.rmtree(base, ignore_errors=True)
    for lang in LOCALES:
        check("language list contains %s" % lang, lang in indices,
              "parsed=%s" % sorted(indices.keys()))
    check("language list has distinct indices",
          len(set(indices.values())) == len(indices))
    return indices


def assert_locale_probe(jar, payload_plugins, lang_index, iso3):
    """Installs in the given locale and observes live: the translated
    Turboism-owned common-pack label, the localized built-in wizard
    headline, and the localized Full/Lite mode names and descriptions
    emitted by the install-side listener through IzPack's message lookup."""
    base = tempfile.mkdtemp(prefix="turboism-locale-%s " % iso3)
    target = os.path.join(base, "home with spaces")
    clear_task_lock()
    rc, out = run_console(jar, install_answers("lite", target, lang_index=lang_index))
    check("locale %s install exit 0" % iso3, rc == 0, "rc=%s" % rc)
    check("locale %s translated pack label" % iso3,
          "Pack '%s' required" % LOCALIZED_PACK_LABEL[iso3] in out,
          "output did not contain the localized common pack label")
    check("locale %s built-in headline" % iso3,
          LOCALIZED_HEADLINE[iso3] in out,
          "output did not contain the localized wizard headline")
    check("locale %s EULA text" % iso3,
          LOCALIZED_EULA_MARKER[iso3] in out,
          "output did not contain the localized EULA")
    for acknowledgement in EULA_ACKNOWLEDGEMENT_TEXT[iso3]:
        check("locale %s EULA acknowledgement" % iso3, acknowledgement in out,
              "output did not contain the localized acknowledgement")
    for mode in ("full", "thin", "lite"):
        name, description = LOCALIZED_MODE[iso3][mode]
        check("locale %s %s mode name" % (iso3, mode), name in out,
              "output did not contain the localized %s mode name" % mode)
        check("locale %s %s mode description" % (iso3, mode), description in out,
              "output did not contain the localized %s mode description" % mode)
    shutil.rmtree(base, ignore_errors=True)


def assert_default_install_does_not_download_graal(jar, payload_plugins):
    base = tempfile.mkdtemp(prefix="turboism-no-graal ")
    target = os.path.join(base, "home")
    clear_task_lock()
    rc, out = run_console(jar, install_answers("lite", target))
    check("default-unselected managed Graal install exits 0", rc == 0,
          "rc=%s" % rc)
    check("default-unselected managed Graal creates no runtime",
          not os.path.lexists(os.path.join(target, "graal", "runtime")))
    shutil.rmtree(base, ignore_errors=True)


def assert_thin_install(jar, payload_plugins):
    base = tempfile.mkdtemp(prefix="turboism-thin ")
    target = os.path.join(base, "home with spaces")
    clear_task_lock()
    rc, out = run_console(jar, install_answers("thin", target, payload_plugins=payload_plugins))
    check("thin install exit 0", rc == 0, "rc=%s" % rc)
    installed = sorted(os.listdir(os.path.join(target, "plugins")))
    expected_modules = sorted(p["module"] + ".jar" for p in payload_plugins)
    check("thin installs every bundled plugin jar", installed == expected_modules, str(installed))
    check("thin has no managed fx runtime",
          not os.path.lexists(os.path.join(target, "runtimes", "fx")))
    config = json.load(open(os.path.join(target, "config.json")))
    check("thin defaults all plugins enabled", "disabledPlugins" not in config)
    shutil.rmtree(base, ignore_errors=True)


def assert_lite_install(jar, payload_plugins):
    base = tempfile.mkdtemp(prefix="turboism-lite ")
    target = os.path.join(base, "home with spaces")
    clear_task_lock()
    rc, out = run_console(jar, install_answers("lite", target))
    check("lite install exit 0", rc == 0, "rc=%s" % rc)
    check("lite no plugins dir", not os.path.isdir(os.path.join(target, "plugins")))
    check("lite has no managed fx runtime",
          not os.path.lexists(os.path.join(target, "runtimes", "fx")))
    check("lite common pack required line", "Turboism Core' required" in out)
    config = json.load(open(os.path.join(target, "config.json")))
    expected = sorted(p["id"] for p in payload_plugins)
    check("lite disabledPlugins == all bundled", config.get("disabledPlugins") == expected,
          str(config.get("disabledPlugins")))
    check("lite canonical fields", config["worktreeId"] == "turboism-runtime"
          and config["pluginDirs"] == ["plugins"]
          and config["format"] == "turboism.runtime.config"
          and config["schemaVersion"] == 1
          and config["launcher"] == {"cubismJvm": "graalvm"})
    ucmd = os.path.join(target, "uninstall.command")
    if sys.platform == "darwin":
        check("mac uninstall.command is regular non-symlink",
              os.path.isfile(ucmd) and not os.path.islink(ucmd))
        check("mac uninstall.command is executable", os.access(ucmd, os.X_OK))
    else:
        check("non-mac does not install uninstall.command", not os.path.lexists(ucmd))
    shutil.rmtree(base, ignore_errors=True)


def assert_unsupported_windows_full(jar, payload_plugins):
    java_flags = ("-Dos.name=Windows 11", "-Dos.arch=amd64")
    base = tempfile.mkdtemp(prefix="turboism-windows-full ")
    target = os.path.join(base, "home")
    clear_task_lock()
    rc, out = run_console(
        jar,
        install_answers("full", target, payload_plugins=payload_plugins),
        java_flags=java_flags,
    )
    check("unsupported Windows full rejects", rc != 0, "rc=%s" % rc)
    check("unsupported Windows full leaves config absent",
          not os.path.lexists(os.path.join(target, "config.json")))
    check("unsupported Windows full leaves agent absent",
          not os.path.lexists(os.path.join(target, "turboism-agent.jar")))
    check("unsupported Windows full leaves plugins absent",
          not os.path.lexists(os.path.join(target, "plugins")))
    check("unsupported Windows full leaves fx absent",
          not os.path.lexists(os.path.join(target, "runtimes", "fx")))
    shutil.rmtree(base, ignore_errors=True)



def assert_selected_fx_platform_symlink_rejected(jar, payload_plugins):
    platform = current_fx_platform()
    if platform is None:
        print("  skip: selected managed fx platform symlink (unsupported host)")
        return
    base = tempfile.mkdtemp(prefix="turboism-fx-selected-link ")
    target = os.path.join(base, "home")
    outside = os.path.join(base, "outside")
    selected = os.path.join(target, "runtimes", "fx", FX_VERSION, platform)
    os.makedirs(os.path.dirname(selected))
    os.makedirs(outside)
    sentinel = os.path.join(outside, "sentinel")
    sentinel_bytes = b"selected platform symlink target"
    open(sentinel, "wb").write(sentinel_bytes)
    try:
        os.symlink(outside, selected)
    except (OSError, NotImplementedError):
        print("  skip: selected managed fx platform symlink unavailable")
        shutil.rmtree(base, ignore_errors=True)
        return
    clear_task_lock()
    rc, out = run_console(
        jar,
        install_answers("full", target, payload_plugins=payload_plugins),
    )
    check("selected managed fx platform symlink rejects Full", rc != 0, "rc=%s" % rc)
    check("selected managed fx platform symlink diagnostic",
          "managed fx runtime ancestor is a symlink" in out)
    check("selected managed fx platform remains a symlink", os.path.islink(selected))
    check("selected managed fx platform target remains intact",
          open(sentinel, "rb").read() == sentinel_bytes)
    shutil.rmtree(base, ignore_errors=True)


def current_fx_platform():
    if sys.platform.startswith("linux"):
        os_id = "linux"
    elif sys.platform == "darwin":
        os_id = "macos"
    elif sys.platform == "win32":
        os_id = "windows"
    else:
        return None
    machine = __import__("platform").machine().lower()
    if machine in ("amd64", "x86_64", "x64"):
        architecture = "x86_64"
    elif machine in ("aarch64", "arm64"):
        architecture = "aarch64"
    else:
        return None
    if os_id == "windows" and architecture != "x86_64":
        return None
    return os_id + "-" + architecture


def snapshot_tree(root):
    """Captures an ordinary install subtree without following symlinks."""
    snapshot = {}
    if not os.path.lexists(root):
        return snapshot
    for current, directories, files in os.walk(root, followlinks=False):
        relative = os.path.relpath(current, root)
        snapshot[(relative, "directory")] = os.lstat(current).st_mode
        for name in sorted(directories + files):
            path = os.path.join(current, name)
            key = os.path.normpath(os.path.join(relative, name))
            if os.path.islink(path):
                snapshot[(key, "symlink")] = os.readlink(path)
            elif os.path.isfile(path):
                snapshot[(key, "file")] = (
                    os.lstat(path).st_mode,
                    hashlib.sha256(open(path, "rb").read()).hexdigest(),
                )
    return snapshot


def assert_nonfull_transition_from_full_rejected(jar, payload_plugins, mode):
    base = tempfile.mkdtemp(prefix="turboism-full-to-%s " % mode)
    target = os.path.join(base, "home")
    clear_task_lock()
    rc, out = run_console(
        jar,
        install_answers("full", target, deselect=(), payload_plugins=payload_plugins),
    )
    check("%s transition setup Full exit 0" % mode, rc == 0, "rc=%s" % rc)
    platform = current_fx_platform()
    assert platform is not None
    assert_installed_fx_runtime(target, platform)
    config_path = os.path.join(target, "config.json")
    config_before = open(config_path, "rb").read()
    tree_before = snapshot_tree(target)

    clear_task_lock()
    rc, out = run_console(
        jar,
        install_answers(mode, target, payload_plugins=payload_plugins),
    )
    check("Full-to-%s transition rejects" % mode, rc != 0, "rc=%s" % rc)
    check("Full-to-%s transition diagnostic" % mode,
          "managed fx runtime to be removed explicitly" in out)
    check("Full-to-%s preserves config bytes" % mode,
          open(config_path, "rb").read() == config_before)
    check("Full-to-%s preserves installed tree" % mode,
          snapshot_tree(target) == tree_before)
    shutil.rmtree(base, ignore_errors=True)


def assert_nonfull_transitions_from_full_rejected(jar, payload_plugins):
    for mode in ("thin", "lite"):
        assert_nonfull_transition_from_full_rejected(jar, payload_plugins, mode)


def assert_installed_fx_runtime(target, platform):
    expected_size, expected_hash, executable_name = FX_PLATFORM[platform]
    directory = os.path.join(target, "runtimes", "fx", FX_VERSION, platform)
    executable = os.path.join(directory, executable_name)
    check("full installs current managed fx runtime %s" % platform,
          os.path.isfile(executable) and not os.path.islink(executable), executable)
    check("installed managed fx executable size",
          os.path.getsize(executable) == expected_size, str(os.path.getsize(executable)))
    digest = hashlib.sha256(open(executable, "rb").read()).hexdigest()
    check("installed managed fx executable hash", digest == expected_hash, digest)
    if not platform.startswith("windows-"):
        check("installed managed fx executable is runnable", os.access(executable, os.X_OK))
    for notice in ("LICENSE", "THIRD_PARTY_NOTICES.md",
                   "TURBOISM-DISTRIBUTION-NOTICE.txt", "manifest.properties"):
        check("installed managed fx notice %s" % notice,
              os.path.isfile(os.path.join(directory, notice)))
    other = sorted(set(FX_PLATFORM) - {platform})
    check("full install excludes non-current managed fx platforms",
          not any(os.path.lexists(os.path.join(target, "runtimes", "fx", FX_VERSION, value))
                  for value in other), "other=%s" % other)


def assert_full_install(jar, payload, payload_plugins):
    deselect = {payload_plugins[0]["id"], payload_plugins[-1]["id"]}
    base = tempfile.mkdtemp(prefix="turboism-full ")
    target = os.path.join(base, "full home")
    platform = current_fx_platform()
    if platform is not None:
        fx_root = os.path.join(target, "runtimes", "fx", FX_VERSION)
        payload_root = os.path.join(
            os.path.abspath(payload), "runtimes", "fx", FX_VERSION,
        )
        payload_root = os.path.normpath(payload_root)
        for platform_name in FX_PLATFORM:
            directory = os.path.join(fx_root, platform_name)
            shutil.copytree(os.path.join(payload_root, platform_name), directory)
    clear_task_lock()
    rc, out = run_console(jar, install_answers("full", target, deselect=deselect, payload_plugins=payload_plugins))
    check("full install exit 0", rc == 0, "rc=%s" % rc)
    installed = sorted(os.listdir(os.path.join(target, "plugins")))
    # r6: 载荷 pack 安装全部捆绑 JAR；勾选只控制 disabledPlugins
    expected_modules = sorted(p["module"] + ".jar" for p in payload_plugins)
    check("full installs every bundled jar (payload pack)", installed == expected_modules, str(installed))
    if platform is not None:
        assert_installed_fx_runtime(target, platform)
    config = json.load(open(os.path.join(target, "config.json")))
    check("full disabledPlugins == deselected", config.get("disabledPlugins") == sorted(deselect),
          str(config.get("disabledPlugins")))
    shutil.rmtree(base, ignore_errors=True)


def assert_full_defaults_all(jar, payload_plugins):
    base = tempfile.mkdtemp(prefix="turboism-fullall ")
    target = os.path.join(base, "home")
    clear_task_lock()
    rc, out = run_console(jar, install_answers("full", target, deselect=(), payload_plugins=payload_plugins))
    check("full-all install exit 0", rc == 0, "rc=%s" % rc)
    installed = sorted(os.listdir(os.path.join(target, "plugins")))
    check("full-all installs every plugin jar",
          installed == sorted(p["module"] + ".jar" for p in payload_plugins), str(installed))
    platform = current_fx_platform()
    if platform is not None:
        assert_installed_fx_runtime(target, platform)
    config = json.load(open(os.path.join(target, "config.json")))
    check("full-all omits empty disabledPlugins", "disabledPlugins" not in config)
    shutil.rmtree(base, ignore_errors=True)


def assert_reselection(jar, payload_plugins):
    """Full install over an existing config that disables a bundled plugin
    plus an unrelated id: the reselected bundled plugin must be enabled
    (removed from disabledPlugins) while the unrelated id is preserved."""
    bundled = [p["id"] for p in payload_plugins]
    first = bundled[0]
    unrelated = "dev.turboism.plugin.not-bundled"
    base = tempfile.mkdtemp(prefix="turboism-reselect ")
    target = os.path.join(base, "home")
    os.makedirs(target)
    existing = {
        "format": "turboism.runtime.config",
        "schemaVersion": 1,
        "worktreeId": "old-worktree",
        "pluginDirs": ["plugins"],
        "disabledPlugins": [first, unrelated],
        "logLevel": "DEBUG",
    }
    with open(os.path.join(target, "config.json"), "w") as f:
        json.dump(existing, f, indent=2)
    clear_task_lock()
    rc, out = run_console(jar, install_answers("full", target, deselect=(), payload_plugins=payload_plugins))
    check("reselection install exit 0", rc == 0, "rc=%s" % rc)
    installed = sorted(os.listdir(os.path.join(target, "plugins")))
    check("reselection installs every bundled jar",
          installed == sorted(p["module"] + ".jar" for p in payload_plugins), str(installed))
    config = json.load(open(os.path.join(target, "config.json")))
    disabled = config.get("disabledPlugins", [])
    check("reselection enables previously disabled bundled plugin", first not in disabled,
          str(disabled))
    check("reselection preserves unrelated disabled id", unrelated in disabled, str(disabled))
    check("reselection preserves other fields", config.get("logLevel") == "DEBUG")
    check("reselection result sorted", disabled == sorted(disabled), str(disabled))
    shutil.rmtree(base, ignore_errors=True)


def assert_config_merge(jar):
    base = tempfile.mkdtemp(prefix="turboism-merge ")
    target = os.path.join(base, "home")
    os.makedirs(target)
    existing = {
        "format": "turboism.runtime.config",
        "schemaVersion": 1,
        "worktreeId": "old-worktree",
        "pluginDirs": ["plugins"],
        "disabledPlugins": ["dev.turboism.plugin.mesh-edit-mirror-axis-enhance", "dev.turboism.plugin.other"],
        "logLevel": "DEBUG",
        "maxLogStorageMiB": 128,
        "hooks": {"enabled": True, "names": ["a", "b"]},
        "nested": {"deep": {"deeper": [1, 2, 3]}},
    }
    with open(os.path.join(target, "config.json"), "w") as f:
        json.dump(existing, f, indent=2)
    clear_task_lock()
    rc, out = run_console(jar, install_answers("lite", target))
    check("merge install exit 0", rc == 0, "rc=%s" % rc)
    config = json.load(open(os.path.join(target, "config.json")))
    check("merge preserves logLevel", config.get("logLevel") == "DEBUG")
    check("merge preserves maxLogStorageMiB", config.get("maxLogStorageMiB") == 128)
    check("merge preserves hooks object", config.get("hooks") == {"enabled": True, "names": ["a", "b"]})
    check("merge preserves nested object", config.get("nested") == {"deep": {"deeper": [1, 2, 3]}})
    check("merge forces worktreeId", config.get("worktreeId") == "turboism-runtime")
    check("merge forces pluginDirs", config.get("pluginDirs") == ["plugins"])
    disabled = config.get("disabledPlugins")
    check("merge lite disables all bundled ids",
          all(bundled_id in disabled for bundled_id in ALL_BUNDLED_IDS))
    check("merge preserves unrelated disabled id", "dev.turboism.plugin.other" in disabled)
    check("merge union is sorted", disabled == sorted(disabled))
    shutil.rmtree(base, ignore_errors=True)



def write_fixture_jar(path, plugin_id):
    """Synthesizes a minimal plugin JAR whose embedded plugin.json carries the
    given id (the same identity authority the installer cleanup uses)."""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("META-INF/turboism/plugin.json", json.dumps({
            "format": "turboism.plugin.meta",
            "schemaVersion": 3,
            "id": plugin_id,
            "name": "Fixture " + plugin_id,
        }))


def assert_retired_upgrade(jar, payload_plugins):
    """Managed upgrade over an existing install carrying retired official JARs
    and retired disabledPlugins ids (retirement slice): JARs whose embedded id
    is retired are removed even under a renamed filename; unreadable JARs,
    foreign-id JARs, non-JAR files and everything outside the managed plugins
    directory are preserved with actionable diagnostics; only the four retired
    ids are pruned from disabledPlugins; unrelated config fields survive."""
    unrelated = "dev.turboism.plugin.not-bundled"
    base = tempfile.mkdtemp(prefix="turboism-retire ")
    target = os.path.join(base, "home")
    plugins = os.path.join(target, "plugins")
    os.makedirs(plugins)
    existing = {
        "format": "turboism.runtime.config",
        "schemaVersion": 1,
        "worktreeId": "old-worktree",
        "pluginDirs": ["plugins"],
        "disabledPlugins": RETIRED_PLUGIN_IDS + [unrelated],
        "logLevel": "DEBUG",
    }
    with open(os.path.join(target, "config.json"), "w") as f:
        json.dump(existing, f, indent=2)
    # retired embedded ids: canonical and renamed filenames
    write_fixture_jar(os.path.join(plugins, "log-filter.jar"), "dev.turboism.plugin.logfilter")
    write_fixture_jar(os.path.join(plugins, "renamed-archive.jar"), "dev.turboism.plugin.renderopt")
    # known retired filename with a foreign id -> preserved
    write_fixture_jar(os.path.join(plugins, "clip-mask.jar"), "dev.turboism.plugin.someone-else")
    # retained successor id under a non-payload name -> preserved
    write_fixture_jar(os.path.join(plugins, "successor-copy.jar"), "dev.turboism.plugin.clipmask-viewer")
    # unreadable entries -> preserved
    with open(os.path.join(plugins, "perf-opt.jar"), "wb") as f:
        f.write(b"not a zip archive")
    with open(os.path.join(plugins, "notes.txt"), "w") as f:
        f.write("not a jar")
    # outside the managed plugins directory -> never inspected or changed
    outside_jar = os.path.join(base, "outside-log-filter.jar")
    write_fixture_jar(outside_jar, "dev.turboism.plugin.logfilter")
    clear_task_lock()
    rc, out = run_console(jar, install_answers("full", target, deselect=(), payload_plugins=payload_plugins))
    check("retired upgrade install exit 0", rc == 0, "rc=%s" % rc)
    check("retired canonical jar removed",
          not os.path.exists(os.path.join(plugins, "log-filter.jar")))
    check("retired renamed jar removed",
          not os.path.exists(os.path.join(plugins, "renamed-archive.jar")))
    check("foreign-id jar preserved",
          os.path.exists(os.path.join(plugins, "clip-mask.jar")))
    check("retained successor jar preserved",
          os.path.exists(os.path.join(plugins, "successor-copy.jar")))
    check("unreadable jar preserved",
          os.path.exists(os.path.join(plugins, "perf-opt.jar")))
    check("non-jar file preserved", os.path.exists(os.path.join(plugins, "notes.txt")))
    check("outside-home retired jar untouched", os.path.exists(outside_jar))
    check("removal diagnostics emitted", "removed retired plugin" in out,
          "console:%s" % [l for l in out.splitlines() if "retired" in l][:4])
    check("preservation diagnostics emitted", "is not retired" in out,
          "console:%s" % [l for l in out.splitlines() if "retired" in l][:4])
    config = json.load(open(os.path.join(target, "config.json")))
    disabled = config.get("disabledPlugins", [])
    check("retired ids pruned from disabledPlugins",
          not any(d in RETIRED_PLUGIN_IDS for d in disabled), str(disabled))
    check("unrelated disabled id preserved", unrelated in disabled, str(disabled))
    check("retired upgrade preserves other fields", config.get("logLevel") == "DEBUG")
    shutil.rmtree(base, ignore_errors=True)


def assert_number_preservation(jar):
    """Large integers and exponent numbers must round-trip through the bounded
    parser without becoming non-finite values or invalid JSON."""
    base = tempfile.mkdtemp(prefix="turboism-numbers ")
    target = os.path.join(base, "home")
    os.makedirs(target)
    existing = ('{"format":"turboism.runtime.config","schemaVersion":1,'
                '"bigInt":123456789012345678901234567890,'
                '"hugeExp":1e400,'
                '"exp":1.5e10,'
                '"tiny":0.0000001,'
                '"negExp":-2.5e-300,'
                '"disabledPlugins":["dev.turboism.plugin.mesh-edit-mirror-axis-enhance"]}')
    with open(os.path.join(target, "config.json"), "w") as f:
        f.write(existing)
    clear_task_lock()
    rc, out = run_console(jar, install_answers("lite", target))
    check("number-preservation install exit 0", rc == 0, "rc=%s" % rc)
    text = open(os.path.join(target, "config.json")).read()
    check("numbers never serialized as Infinity/NaN",
          "Infinity" not in text and "NaN" not in text, text[:200])
    check("exponent number preserved as valid JSON number", "1E+400" in text, text[:200])
    parsed = json.loads(text)
    check("big integer preserved exactly",
          parsed.get("bigInt") == 123456789012345678901234567890, str(parsed.get("bigInt")))
    check("exponent value survives", math.isinf(parsed.get("hugeExp")))
    check("decimal exponent preserved", parsed.get("exp") == 1.5e10)
    check("tiny decimal preserved", parsed.get("tiny") == 0.0000001)
    check("negative exponent preserved", parsed.get("negExp") == -2.5e-300)
    shutil.rmtree(base, ignore_errors=True)


def assert_size_boundary(jar):
    """Exactly MAX_CONFIG_BYTES of valid config merges; one more byte fails
    closed from the bytes actually consumed, leaving the source intact."""
    base = tempfile.mkdtemp(prefix="turboism-size ")
    target = os.path.join(base, "home")
    os.makedirs(target)
    # valid JSON of exactly MAX bytes: three strings at the parser's string
    # bound (16 KiB each) plus trailing whitespace (legal JSON)
    parts = ['{"format":"turboism.runtime.config","schemaVersion":1']
    for i in range(3):
        parts.append(',"p%d":"%s"' % (i, "x" * (16 * 1024)))
    body = "".join(parts) + "}"
    spaces = 64 * 1024 - len(body)
    check("size boundary construction fits with padding", spaces >= 0)
    exact = body + " " * spaces
    check("size boundary construction is exact",
          len(exact.encode("utf-8")) == 64 * 1024)
    cfg = os.path.join(target, "config.json")
    with open(cfg, "wb") as f:
        f.write(exact.encode("utf-8"))
    clear_task_lock()
    rc, out = run_console(jar, install_answers("lite", target))
    check("exact MAX-byte config merges (exit 0)", rc == 0, "rc=%s" % rc)
    config = json.load(open(cfg))
    check("exact MAX-byte config preserved its pad fields",
          config.get("p0") == "x" * (16 * 1024) and config.get("p2") == "x" * (16 * 1024))
    maxPlusOne = exact.encode("utf-8") + b" "
    with open(cfg, "wb") as f:
        f.write(maxPlusOne)
    clear_task_lock()
    rc, out = run_console(jar, install_answers("lite", target))
    check("MAX+1-byte config aborts install", rc != 0, "rc=%s" % rc)
    check("MAX+1-byte config source intact", open(cfg, "rb").read() == maxPlusOne,
          "content changed")
    shutil.rmtree(base, ignore_errors=True)


def assert_fail_closed(jar, name, setup, expect_rc_nonzero=True):
    base = tempfile.mkdtemp(prefix="turboism-fail-%s " % name)
    target = os.path.join(base, "home")
    os.makedirs(target)
    if setup(target) is False:
        shutil.rmtree(base, ignore_errors=True)
        return
    before = {}
    cfg = os.path.join(target, "config.json")
    if os.path.lexists(cfg):
        if os.path.islink(cfg):
            before["type"] = "symlink"
            before["bytes"] = os.readlink(cfg)
        elif os.path.isfile(cfg):
            before["type"] = "file"
            before["bytes"] = open(cfg, "rb").read()
        else:
            # non-regular target (directory, fifo, ...)
            before["type"] = "special"
            before["mode"] = os.lstat(cfg).st_mode
    clear_task_lock()
    rc, out = run_console(jar, install_answers("lite", target))
    check("%s aborts install" % name, expect_rc_nonzero and rc != 0, "rc=%s" % rc)
    if "type" in before:
        if before["type"] == "symlink":
            check("%s source symlink untouched" % name,
                  os.path.islink(cfg) and os.readlink(cfg) == before["bytes"])
        elif before["type"] == "file":
            check("%s source intact" % name, open(cfg, "rb").read() == before["bytes"])
        else:
            check("%s source type untouched" % name,
                  os.lstat(cfg).st_mode == before["mode"])
    else:
        check("%s source untouched" % name, os.path.isdir(cfg))
    shutil.rmtree(base, ignore_errors=True)


def assert_install_home_symlink_rejected(jar):
    base = tempfile.mkdtemp(prefix="turboism-home-link ")
    outside = os.path.join(base, "outside")
    target = os.path.join(base, "home")
    os.makedirs(outside)
    sentinel = os.path.join(outside, "sentinel")
    open(sentinel, "wb").write(b"preserve")
    try:
        os.symlink(outside, target)
    except (OSError, NotImplementedError):
        print("  skip: install-home symlink case (not supported on this host)")
        shutil.rmtree(base, ignore_errors=True)
        return
    clear_task_lock()
    rc, out = run_console(jar, install_answers("lite", target))
    check("install-home symlink aborts Lite", rc != 0, "rc=%s" % rc)
    check("install-home symlink remains a link", os.path.islink(target))
    check("install-home symlink target remains unchanged",
          os.listdir(outside) == ["sentinel"] and open(sentinel, "rb").read() == b"preserve")
    shutil.rmtree(base, ignore_errors=True)


def assert_strict_numbers_fail_closed(jar):
    """RFC 8259 number lexing is strict: leading zeros, missing fraction or
    exponent digits and other malformed spellings fail closed."""
    cases = {
        "leading-zero": '{"format":"turboism.runtime.config","schemaVersion":1,"x":01}',
        "leading-zero-neg": '{"format":"turboism.runtime.config","schemaVersion":1,"x":-01}',
        "missing-fraction-digits": '{"format":"turboism.runtime.config","schemaVersion":1,"x":1.}',
        "missing-exp-digits": '{"format":"turboism.runtime.config","schemaVersion":1,"x":1e}',
        "missing-exp-sign-digits": '{"format":"turboism.runtime.config","schemaVersion":1,"x":1e+}',
        "bare-plus": '{"format":"turboism.runtime.config","schemaVersion":1,"x":+1}',
        "double-dot": '{"format":"turboism.runtime.config","schemaVersion":1,"x":1.2.3}',
    }
    for name, text in cases.items():
        assert_fail_closed(jar, "strict-number-" + name,
                           lambda t, text=text: open(os.path.join(t, "config.json"), "w").write(text))


def assert_canonical_identity_fail_closed(jar):
    """An existing config must be canonical runtime-config v1: exact
    format=turboism.runtime.config and integral schemaVersion=1. Wrong,
    missing, or type-invalid identity fails closed without source mutation."""
    cases = {
        "missing-format": '{"schemaVersion":1}',
        "wrong-format": '{"format":"other.runtime.config","schemaVersion":1}',
        "schema-version-string": '{"format":"turboism.runtime.config","schemaVersion":"1"}',
        "schema-version-two": '{"format":"turboism.runtime.config","schemaVersion":2}',
    }
    for name, text in cases.items():
        assert_fail_closed(jar, "canonical-" + name,
                           lambda t, text=text: open(os.path.join(t, "config.json"), "w").write(text))


def run_shipped_uninstaller(uninstaller_jar, delete_config, timeout=120):
    """Invokes the shipped generated uninstaller entrypoint exactly once:

        java -Dturboism.uninstall.deleteConfig=<true|false>
             -jar <home>/Uninstaller/uninstaller.jar -console

    The outer JVM (SelfModifier phase 1) exits quickly after spawning the
    background phase 2/3 chain; this helper waits for the chain to reach the
    terminal file state (Uninstaller dir and agent gone, runtime dirs gone,
    config per branch). No retries are performed.
    """
    cmd = java_cmd() + ["-D%s=%s" % (UNINSTALL_DELETE_CONFIG_PROP, "true" if delete_config else "false"),
                        "-jar", uninstaller_jar, "-console"]
    proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", bufsize=1, env=PROC_ENV)
    chunks = []

    def reader():
        for line in proc.stdout:
            chunks.append(line)

    thread = threading.Thread(target=reader, daemon=True)
    thread.start()
    try:
        rc = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        fail("uninstaller phase 1 timed out")
    thread.join(timeout=5)
    if rc != 0:
        fail("uninstaller entrypoint exited %s; output:\n%s" % (rc, "".join(chunks)))

    home = os.path.dirname(os.path.dirname(uninstaller_jar))
    deadline = time.time() + timeout
    while time.time() < deadline:
        un_gone = not os.path.exists(os.path.join(home, "Uninstaller"))
        agent_gone = not os.path.exists(os.path.join(home, "turboism-agent.jar"))
        runtime_dirs_gone = all(
            not os.path.exists(os.path.join(home, d)) for d in ("logs", "state", "cache"))
        cfg = os.path.join(home, "config.json")
        config_ok = (not os.path.exists(cfg)) if delete_config else os.path.isfile(cfg)
        if un_gone and agent_gone and runtime_dirs_gone and config_ok:
            return home
        time.sleep(0.5)
    fail("shipped uninstaller chain did not reach terminal state within %ss "
         "(home=%s)" % (timeout, home))


def assert_malformed_utf8_fail_closed(jar):
    """R14: canonical format/schema with a malformed UTF-8 byte inside an
    unrelated string must fail closed (nonzero install, source byte-identical).
    The strict decoder must never silently replace the byte and rewrite the
    field."""
    def setup(t):
        cfg = os.path.join(t, "config.json")
        with open(cfg, "wb") as f:
            f.write(b'{"format":"turboism.runtime.config","schemaVersion":1,'
                    b'"note":"a\xffb"}')
    assert_fail_closed(jar, "malformed-utf8", setup)


def assert_uninstall(jar, payload_plugins, delete_config):
    base = tempfile.mkdtemp(prefix="turboism-uninst-%s " % ("del" if delete_config else "keep"))
    target = os.path.join(base, "home")
    clear_task_lock()
    rc, out = run_console(jar, install_answers("full", target, deselect=(), payload_plugins=payload_plugins))
    check("uninstall setup install exit 0", rc == 0, "rc=%s" % rc)
    # synthetic third-party plugin file + runtime dirs
    plugins_dir = os.path.join(target, "plugins")
    with open(os.path.join(plugins_dir, "third-party.jar"), "w") as f:
        f.write("synthetic third-party plugin")
    os.makedirs(os.path.join(target, "logs", "sub"))
    os.makedirs(os.path.join(target, "state"))
    os.makedirs(os.path.join(target, "cache"))
    if os.name == "nt":
        # A completed takeover cleanup must not leave the fixed installer backup
        # directory chain behind and keep an otherwise removable home alive.
        os.makedirs(os.path.join(target, "installer", "shortcut-backups"))
    uninstaller = os.path.join(target, "Uninstaller", "uninstaller.jar")
    home = run_shipped_uninstaller(uninstaller, delete_config)
    check("uninstall removes agent", not os.path.exists(os.path.join(home, "turboism-agent.jar")))
    check("uninstall removes uninstaller", not os.path.exists(os.path.join(home, "Uninstaller")))
    check("uninstall removes runtime dirs",
          not os.path.exists(os.path.join(home, "logs"))
          and not os.path.exists(os.path.join(home, "state"))
          and not os.path.exists(os.path.join(home, "cache")))
    check("uninstall removes installer-owned managed fx runtime",
          not os.path.exists(os.path.join(home, "runtimes", "fx", FX_VERSION)))
    if os.name == "nt":
        check("uninstall removes empty takeover backup directories",
              not os.path.exists(os.path.join(home, "installer", "shortcut-backups"))
              and not os.path.exists(os.path.join(home, "installer")))
    check("uninstall preserves third-party plugin",
          os.path.isfile(os.path.join(home, "plugins", "third-party.jar")))
    config = os.path.join(home, "config.json")
    if delete_config:
        check("uninstall deletes config.json", not os.path.exists(config))
    else:
        check("uninstall preserves config.json", os.path.isfile(config))
    shutil.rmtree(base, ignore_errors=True)


def run_relocated_uninstaller(copied_jar, cwd, timeout=120):
    """Runs a relocated copy of the shipped generated uninstaller exactly once
    (no retry, no bypass) with the delete-config property forced on, and
    returns (rc, output). The caller then waits for the SelfModifier chain's
    terminal state."""
    cmd = java_cmd() + ["-D%s=true" % UNINSTALL_DELETE_CONFIG_PROP,
                        "-jar", copied_jar, "-console"]
    proc = subprocess.Popen(cmd, cwd=cwd, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", bufsize=1, env=PROC_ENV)
    chunks = []

    def reader():
        for line in proc.stdout:
            chunks.append(line)

    thread = threading.Thread(target=reader, daemon=True)
    thread.start()
    try:
        rc = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        fail("relocated uninstaller phase 1 timed out; output:\n%s" % "".join(chunks))
    thread.join(timeout=5)
    if rc != 0:
        fail("relocated uninstaller entrypoint exited %s; output:\n%s" % (rc, "".join(chunks)))
    return rc, "".join(chunks)


def wait_original_home_terminal(home, timeout=120):
    """The relocated copy's IzPack phase deletes the ORIGINAL install's
    installer-owned pack files (its embedded install.log records them); wait
    for that terminal state."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if (not os.path.exists(os.path.join(home, "Uninstaller"))
                and not os.path.exists(os.path.join(home, "turboism-agent.jar"))):
            return
        time.sleep(0.5)
    fail("relocated uninstaller chain did not reach terminal state (home=%s)" % home)


def snapshot_paths(paths):
    """{name: (path, bytes|None)} — None means the file was absent."""
    snap = {}
    for name, path in paths.items():
        try:
            with open(path, "rb") as f:
                snap[name] = (path, f.read())
        except FileNotFoundError:
            snap[name] = (path, None)
    return snap


def assert_snapshot_unchanged(label, snap):
    for name, (path, content) in snap.items():
        if content is None:
            check("%s: %s still absent" % (label, name), not os.path.exists(path))
            continue
        with open(path, "rb") as f:
            now = f.read()
        check("%s: %s byte-identical" % (label, name), now == content,
              "content changed (len %d -> %d)" % (len(content), len(now)))


def add_home_custom_sentinels(home):
    """Creates the custom-cleanup targets (config.json content snapshot is
    taken by the caller; here logs/state/cache and a third-party plugin jar)
    in an installed home."""
    plugins_dir = os.path.join(home, "plugins")
    with open(os.path.join(plugins_dir, "third-party.jar"), "w") as f:
        f.write("synthetic third-party plugin")
    os.makedirs(os.path.join(home, "logs"))
    os.makedirs(os.path.join(home, "state"))
    os.makedirs(os.path.join(home, "cache"))
    for p in (os.path.join(home, "logs", "a.log"),
              os.path.join(home, "state", "b"),
              os.path.join(home, "cache", "c")):
        with open(p, "w") as f:
            f.write("sentinel")
    return {
        "config": os.path.join(home, "config.json"),
        "logs": os.path.join(home, "logs", "a.log"),
        "state": os.path.join(home, "state", "b"),
        "cache": os.path.join(home, "cache", "c"),
        "third-party": os.path.join(plugins_dir, "third-party.jar"),
    }


def add_relocated_home_sentinels(relocated_home):
    """config.json + logs/state/cache sentinel files in a relocated home (the
    former user.dir fallback target)."""
    open(os.path.join(relocated_home, "config.json"), "w").write('{"worktreeId":"unrelated-home"}')
    for d, sentinel in (("logs", "a.log"), ("state", "b"), ("cache", "c")):
        os.makedirs(os.path.join(relocated_home, d))
        with open(os.path.join(relocated_home, d, sentinel), "w") as f:
            f.write("sentinel")
    return {
        "config": os.path.join(relocated_home, "config.json"),
        "logs": os.path.join(relocated_home, "logs", "a.log"),
        "state": os.path.join(relocated_home, "state", "b"),
        "cache": os.path.join(relocated_home, "cache", "c"),
    }


def assert_malformed_identity_safety(jar, payload_plugins):
    """Relocated copies of the shipped generated uninstaller must never
    perform custom deletion, in two shapes:
      (a) wrong shape: <workdir>/uninstaller.jar (no Uninstaller directory);
      (b) matching shape: <unrelated>/Uninstaller/uninstaller.jar — the shape
          matches, but the jar's embedded install.log still records the
          ORIGINAL install home, so the normalized-home binding fails closed.
    Each copy runs exactly once (no retry/bypass); the custom sentinels
    (config.json, logs, state, cache) in the relocated home must stay
    byte-identical, and the original installed home's own custom sentinels
    must not be custom-cleaned by the mismatched copy."""
    def fresh_install():
        base = tempfile.mkdtemp(prefix="turboism-reloc ")
        home = os.path.join(base, "home")
        clear_task_lock()
        rc, out = run_console(jar, install_answers("full", home, deselect=(), payload_plugins=payload_plugins))
        check("relocation setup install exit 0", rc == 0, "rc=%s" % rc)
        return base, home, os.path.join(home, "Uninstaller", "uninstaller.jar")

    # (a) wrong shape: <workdir>/uninstaller.jar
    base, home, uninstaller = fresh_install()
    workdir = os.path.join(base, "workdir")
    os.makedirs(workdir)
    malformed_jar = os.path.join(workdir, "uninstaller.jar")
    shutil.copy2(uninstaller, malformed_jar)
    open(os.path.join(workdir, "config.json"), "w").write('{"worktreeId":"unrelated-workdir"}')
    for d in ("logs", "state", "cache"):
        os.makedirs(os.path.join(workdir, d))
    work_sentinels = {
        "config": os.path.join(workdir, "config.json"),
        "logs": os.path.join(workdir, "logs", "a.log"),
        "state": os.path.join(workdir, "state", "b"),
        "cache": os.path.join(workdir, "cache", "c"),
    }
    with open(os.path.join(workdir, "logs", "a.log"), "w") as f:
        f.write("sentinel")
    with open(os.path.join(workdir, "state", "b"), "w") as f:
        f.write("sentinel")
    with open(os.path.join(workdir, "cache", "c"), "w") as f:
        f.write("sentinel")
    home_sentinels = add_home_custom_sentinels(home)
    work_snap = snapshot_paths(work_sentinels)
    home_snap = snapshot_paths(home_sentinels)
    run_relocated_uninstaller(malformed_jar, cwd=workdir)
    wait_original_home_terminal(home)
    assert_snapshot_unchanged("wrong-shape workdir", work_snap)
    assert_snapshot_unchanged("wrong-shape original home", home_snap)
    shutil.rmtree(base, ignore_errors=True)

    # (b) matching shape: <unrelated>/Uninstaller/uninstaller.jar
    base, home, uninstaller = fresh_install()
    unrelated = os.path.join(base, "unrelated")
    os.makedirs(os.path.join(unrelated, "Uninstaller"))
    copied = os.path.join(unrelated, "Uninstaller", "uninstaller.jar")
    shutil.copy2(uninstaller, copied)
    unrelated_sentinels = add_relocated_home_sentinels(unrelated)
    home_sentinels = add_home_custom_sentinels(home)
    unrelated_snap = snapshot_paths(unrelated_sentinels)
    home_snap = snapshot_paths(home_sentinels)
    run_relocated_uninstaller(copied, cwd=unrelated)
    wait_original_home_terminal(home)
    assert_snapshot_unchanged("matching-shape unrelated home", unrelated_snap)
    assert_snapshot_unchanged("matching-shape original home", home_snap)
    shutil.rmtree(base, ignore_errors=True)


def assert_jar_layout(jar, payload, installer_xml_path):
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        for lang in ("eng", "chn", "jpn"):
            check("jar langpack %s" % lang, "resources/langpacks/%s.xml" % lang in names)
        for variant in ("", "_eng", "_chn", "_jpn"):
            check("jar custom langpack %s" % (variant or "base"),
                  "resources/CustomLangPack.xml%s" % variant in names)
        for iso3, suffix in (("eng", "_eng"), ("chn", "_chn"), ("jpn", "_jpn")):
            text = z.read("resources/CustomLangPack.xml" + suffix).decode("utf-8")
            for key in EULA_ACKNOWLEDGEMENT_KEYS:
                check("jar %s localized EULA acknowledgement %s" % (iso3, key),
                      'id="EulaAcknowledgementPanel.%s"' % key in text)
        check("generated installer.xml exists", os.path.isfile(installer_xml_path),
              installer_xml_path)
        installer_xml = open(installer_xml_path, encoding="utf-8").read()
        acknowledgement_panel = 'classname="dev.turboism.installer.EulaAcknowledgementPanel" id="eulaAcknowledgements"'
        stock_eula_panel = 'classname="LicencePanel" id="eula"'
        check("custom EULA acknowledgement panel precedes stock EULA",
              acknowledgement_panel in installer_xml and stock_eula_panel in installer_xml
              and installer_xml.index(acknowledgement_panel) < installer_xml.index(stock_eula_panel))
        check("jar uninstaller classes", "com/izforge/izpack/uninstaller/Uninstaller.class" in names)
        check("jar uninstall listener",
              "dev/turboism/installer/TurboismUninstallerListener.class" in names)
        check("jar install listener",
              "dev/turboism/installer/TurboismInstallerListener.class" in names)
        for class_name in (
                "EulaAcknowledgements", "EulaAcknowledgementPanel",
                "EulaAcknowledgementConsolePanel", "EulaAcknowledgementPanelAutomationHelper"):
            check("jar EULA acknowledgement class " + class_name,
                  "dev/turboism/installer/%s.class" % class_name in names)
        check("jar config template resource", "turboism/config.template.json" in names)
        eula_resources = {
            "resources/LicencePanel.eula": "EULA.en.txt",
            "resources/LicencePanel.eula_eng": "EULA.en.txt",
            "resources/LicencePanel.eula_chn": "EULA.zh-Hans.txt",
            "resources/LicencePanel.eula_jpn": "EULA.ja.txt",
        }
        for resource, staged_name in eula_resources.items():
            check("jar EULA resource %s" % resource, resource in names)
            expected = open(os.path.join(payload, staged_name), "rb").read()
            check("jar EULA resource %s byte-identical" % resource,
                  z.read(resource) == expected)
        agent_path = os.path.join(payload, "turboism-agent.jar")
        with zipfile.ZipFile(agent_path) as agent:
            check("staged agent contains managed Graal CLI",
                  "dev/turboism/graal/ManagedGraalRuntimeCli.class" in agent.namelist())
        fx_pack_name = "resources/packs/pack-Managed fx Runtime"
        check("jar managed fx runtime pack", fx_pack_name in names)
        fx_pack = z.read(fx_pack_name)
        platform = current_fx_platform()
        if platform is not None:
            runtime_root = os.path.join(payload, "runtimes", "fx", FX_VERSION, platform)
            for name in ("fx", "LICENSE", "THIRD_PARTY_NOTICES.md",
                         "TURBOISM-DISTRIBUTION-NOTICE.txt", "manifest.properties"):
                content = open(os.path.join(runtime_root, name), "rb").read()
                check("jar managed fx %s embeds %s" % (platform, name), content in fx_pack)
        core_pack_name = "resources/packs/pack-Turboism Core"
        check("jar Windows core pack", core_pack_name in names)
        managed_pack_names = [name for name in names
                              if name.startswith("resources/packs/pack-")
                              and "GraalVM" in name]
        check("jar has one optional managed Graal pack", len(managed_pack_names) == 1,
              "packs=%s" % managed_pack_names)
        core_pack = z.read(core_pack_name)
        graal_library_root = os.path.join(payload, "graal", "lib")
        check("staged Windows Graal host library directory exists",
              os.path.isdir(graal_library_root), graal_library_root)
        required_graal_prefixes = (
            "graal-host-", "jackson-annotations-", "jackson-core-",
            "jackson-databind-", "collections-", "jniutils-",
            "js-isolate-windows-amd64-community-", "nativebridge-",
            "nativeimage-", "polyglot-", "truffle-api-", "word-",
        )
        graal_libraries = sorted(
            name for name in os.listdir(graal_library_root)
            if name.endswith(".jar")
        )
        for prefix in required_graal_prefixes:
            matches = [name for name in graal_libraries
                       if name.startswith(prefix)]
            check("staged Windows Graal library %s" % prefix,
                  bool(matches), "libraries=%s" % graal_libraries)
            for name in matches:
                with open(os.path.join(graal_library_root, name), "rb") as f:
                    check("jar embeds Windows Graal library %s" % name,
                          f.read() in core_pack)
        for helper in ("launch-cubism-turboism.ps1", "cubism-launch-common.ps1",
                       "configure_turboism.ps1", "install-managed-graal.ps1"):
            staged = os.path.join(payload, helper)
            check("staged Windows helper %s is regular" % helper,
                  os.path.isfile(staged) and not os.path.islink(staged), staged)
            with open(staged, "rb") as f:
                helper_bytes = f.read()
            check("staged Windows helper %s is non-empty" % helper, bool(helper_bytes))
            check("jar Windows helper %s embedded in core pack" % helper,
                  helper_bytes in core_pack)


def assert_sidecar(jar, sha_path):
    """Verifies a portable sibling SHA-256 sidecar for the installer JAR."""
    check("sidecar exists", os.path.isfile(sha_path), sha_path)
    content = open(sha_path).read()
    actual = hashlib.sha256(open(jar, "rb").read()).hexdigest()
    expected = "%s  %s\n" % (actual, os.path.basename(jar))
    check("sidecar has portable 'hash  filename' format",
          content == expected, repr(content))


def snapshot_global_lock():
    """Read-only snapshot of the fixed global iz-Turboism.tmp path (the
    IzPack lock used by installers of this and other processes). R15: the
    verifier never creates, overwrites, renames or deletes it — if it is
    absent before, it must stay absent; if a concurrently starting real
    installer holds it, its type/content or symlink target is recorded and
    re-verified at the end, without changing or following an unsafe special
    path. Returns one of ("absent",), ("file", bytes|None),
    ("symlink", target), ("special", st_mode); None bytes mean the file
    existed but could not be read (e.g. held open by another process)."""
    if not os.path.lexists(GLOBAL_LOCK):
        return ("absent",)
    st = os.lstat(GLOBAL_LOCK)
    if stat.S_ISLNK(st.st_mode):
        return ("symlink", os.readlink(GLOBAL_LOCK))
    if stat.S_ISREG(st.st_mode):
        try:
            with open(GLOBAL_LOCK, "rb") as f:
                return ("file", f.read())
        except OSError:
            return ("file", None)
    return ("special", st.st_mode)


def assert_global_lock_untouched(before):
    """The global lock path must be exactly as snapshot_global_lock recorded
    before the matrix ran: absent stays absent (the verifier never created a
    sentinel there), and a pre-existing file/symlink/special path is
    unchanged. There is deliberately no cleanup function that unlinks the
    global path; only the verifier-owned TASK_TMP/iz-Turboism.tmp is cleared
    between runs (clear_task_lock)."""
    now = snapshot_global_lock()
    if before[0] == "absent":
        check("global lock path absent before and after", now[0] == "absent",
              "path appeared during verification")
    elif before[0] == "file":
        if before[1] is None:
            check("global lock path still present (unreadable)",
                  now[0] == "file" and now[1] is None, "state changed")
        else:
            check("global lock file %s byte-identical" % GLOBAL_LOCK,
                  now[0] == "file" and now[1] == before[1], "content changed")
    elif before[0] == "symlink":
        check("global lock symlink target unchanged",
              now[0] == "symlink" and now[1] == before[1], "target changed")
    else:
        check("global lock special path unchanged",
              now[0] == "special" and now[1] == before[1], "mode changed")


def assert_plugin_identity(payload_plugins, included_metadata, excluded_metadata):
    """Built-JAR metadata is the identity authority (never module-name or
    filename derived); committed plugin.json descriptors are the regression
    oracle. Asserts every included payload module's id/name equals its
    descriptor, excluded modules and their committed ids are absent, the
    renamed algorithm display identity plus its compatibility id holds, and
    the required present/absent plugin facts hold."""
    by_module = {p["module"]: p for p in payload_plugins}
    actual = {module: {"id": p["id"], "name": p["name"]}
              for module, p in by_module.items()}
    check("included payload identities equal committed metadata (module+id+name)",
          actual == included_metadata,
          "actual=%s expected=%s" % (sorted(actual.items()),
                                     sorted(included_metadata.items())))
    found_modules = set(by_module) & set(EXCLUDED_PUBLIC_MODULES)
    check("excluded public modules absent from payload", not found_modules,
          "found=%s" % sorted(found_modules))
    excluded_ids = {m["id"] for m in excluded_metadata.values()}
    found_ids = set(p["id"] for p in payload_plugins) & excluded_ids
    check("excluded public ids absent from payload", not found_ids,
          "found=%s" % sorted(found_ids))
    found_retired_modules = sorted(set(by_module) & set(RETIRED_MODULES))
    check("retired fake modules absent from payload", not found_retired_modules,
          "found=%s" % found_retired_modules)
    found_retired_ids = sorted(
        p["id"] for p in payload_plugins if p["id"] in RETIRED_PLUGIN_IDS)
    check("retired fake ids absent from payload", not found_retired_ids,
          "found=%s" % found_retired_ids)
    alg = by_module.get(ALGORITHM_MODULE)
    check("algorithm module carries renamed display identity + compatibility id",
          alg is not None and alg["name"] == ALGORITHM_NAME
          and alg["id"] == ALGORITHM_COMPAT_ID,
          "actual=%s" % (alg or "absent"))
    for module, expected_name in (
            ("parameter-batch-transfer", "Parameter Batch Transfer"),
            ("perf-stats", "Performance Statistics"),
            ("backup", "WebDAV Auto-Backup Sync Plugin"),
            ("mcp", "Turboism MCP Server")):
        p = by_module.get(module)
        check("payload includes %s (%s)" % (expected_name, module),
              p is not None and p["name"] == expected_name
              and p["id"] == included_metadata[module]["id"],
              "actual=%s" % (p or "absent"))
    for module, expected_name in (
            ("parameter", "Parameter Tools Plugin"),
            ("project-inspector", "Project Inspector")):
        check("payload excludes %s (%s)" % (expected_name, module),
              module not in by_module,
              "found=%s" % (by_module.get(module) or "absent"))


ALL_BUNDLED_IDS = []


def main():
    global TASK_TMP, PROC_ENV
    parser = argparse.ArgumentParser()
    parser.add_argument("--installer", required=True, help="path to TurboismInstaller-<version>.jar")
    parser.add_argument("--sha256", required=True, help="path to the .sha256 sidecar")
    parser.add_argument("--payload", required=True, help="shared staged payload directory")
    parser.add_argument("--regression-jar", required=True,
                        help="path to the config-merge regression jar")
    parser.add_argument("--manifest", required=True,
                        help="path to packaging/release-plugins.txt")
    parser.add_argument("--installer-xml", required=True,
                        help="path to the generated IzPack installer.xml")
    args = parser.parse_args()

    jar = os.path.abspath(args.installer)
    if not os.path.isfile(jar):
        fail("installer jar not found: %s" % jar)
    payload_plugins = load_plugin_inventory(args.payload)
    assert_managed_fx_payload(args.payload)
    if len(payload_plugins) < 3:
        fail("payload plugin inventory too small: %d" % len(payload_plugins))
    global ALL_BUNDLED_IDS
    ALL_BUNDLED_IDS = [p["id"] for p in payload_plugins]
    print("verifying installer: %s (%d bundled plugins)" % (jar, len(payload_plugins)))

    # Shared-manifest + committed-descriptor regression oracle: the staged
    # payload must equal the allowlisted plugin modules (core excluded) and
    # never carry one of the eight excluded public modules or their committed
    # ids; every included payload module's id/name is compared exactly to its
    # committed plugin.json descriptor (see assert_plugin_identity).
    manifest_modules = load_release_manifest(args.manifest)
    included_metadata = load_plugin_metadata(args.manifest, manifest_modules)
    excluded_metadata = load_plugin_metadata(args.manifest,
                                             list(EXCLUDED_PUBLIC_MODULES))
    shared_ids = ({m["id"] for m in included_metadata.values()}
                  & {m["id"] for m in excluded_metadata.values()})
    check("included and excluded committed plugin ids are disjoint",
          not shared_ids, "shared=%s" % sorted(shared_ids))
    payload_modules = sorted(p["module"] for p in payload_plugins)
    check("payload plugin modules equal manifest allowlist (core excluded)",
          payload_modules == sorted(manifest_modules),
          "payload=%s manifest=%s" % (payload_modules, sorted(manifest_modules)))
    payload_ids = set(p["id"] for p in payload_plugins)
    check("runtime-owned core absent from payload", "turboism.core" not in payload_ids)
    assert_plugin_identity(payload_plugins, included_metadata, excluded_metadata)

    # Verification-owned isolation root: every JVM (installer, uninstaller,
    # and SelfModifier child phases via TMPDIR/TEMP/TMP) uses this tmpdir.
    TASK_TMP = tempfile.mkdtemp(prefix="turboism-verify-")
    PROC_ENV = os.environ.copy()
    PROC_ENV["TMPDIR"] = TASK_TMP
    PROC_ENV["TEMP"] = TASK_TMP
    PROC_ENV["TMP"] = TASK_TMP
    # deterministic console: print UTF-8 evidence (zh/ja) on any host console
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    lock_state = snapshot_global_lock()
    assert_utf8_contract()
    try:
        run_java_regression(args.regression_jar)

        assert_sidecar(jar, args.sha256)
        assert_jar_layout(jar, args.payload, args.installer_xml)

        lang_indices = discover_language_indices(jar)
        for iso3 in LOCALES:
            assert_locale_probe(jar, payload_plugins, lang_indices[iso3], iso3)

        assert_eula_acknowledgement_rejection(jar, payload_plugins)
        assert_automated_eula_gate(jar)
        assert_default_install_does_not_download_graal(jar, payload_plugins)
        assert_lite_install(jar, payload_plugins)
        assert_install_home_symlink_rejected(jar)
        assert_unsupported_windows_full(jar, payload_plugins)
        assert_thin_install(jar, payload_plugins)
        supported_fx = current_fx_platform() is not None
        if supported_fx:
            assert_full_defaults_all(jar, payload_plugins)
            assert_full_install(jar, args.payload, payload_plugins)
            assert_nonfull_transitions_from_full_rejected(jar, payload_plugins)
            assert_selected_fx_platform_symlink_rejected(jar, payload_plugins)
            assert_reselection(jar, payload_plugins)
        assert_config_merge(jar)
        if supported_fx:
            assert_retired_upgrade(jar, payload_plugins)
        assert_number_preservation(jar)
        assert_size_boundary(jar)

        def malformed_setup(t):
            open(os.path.join(t, "config.json"), "w").write("{broken json!!!")

        assert_fail_closed(jar, "malformed", malformed_setup)
        assert_fail_closed(jar, "oversized",
                           lambda t: open(os.path.join(t, "config.json"), "wb").write(b" " * (70 * 1024)))
        unicode_escapes = {
            "bad-unicode-escape": b'{"x": "\\uZZZZ"}',
            "arabic-indic-unicode-escape":
                '{"x": "\\u١000"}'.encode("utf-8"),
            "fullwidth-unicode-escape":
                '{"x": "\\u０000"}'.encode("utf-8"),
        }
        for name, source in unicode_escapes.items():
            assert_fail_closed(jar, name,
                               lambda t, source=source: open(
                                   os.path.join(t, "config.json"), "wb"
                               ).write(source))
        assert_strict_numbers_fail_closed(jar)
        assert_canonical_identity_fail_closed(jar)
        assert_malformed_utf8_fail_closed(jar)

        def symlink_setup(t):
            try:
                os.symlink(os.path.join(t, "elsewhere.json"), os.path.join(t, "config.json"))
                return True
            except (OSError, NotImplementedError):
                print("  skip: symlink case (not supported on this host)")
                return False

        assert_fail_closed(jar, "symlink", symlink_setup)

        def fifo_setup(t):
            try:
                import stat
                os.mkfifo(os.path.join(t, "config.json"))
                return True
            except (OSError, NotImplementedError, AttributeError):
                print("  skip: fifo case (not supported on this host)")
                return False

        assert_fail_closed(jar, "non-regular-fifo", fifo_setup)
        assert_fail_closed(jar, "not-a-file",
                           lambda t: os.makedirs(os.path.join(t, "config.json")))
        if supported_fx:
            assert_uninstall(jar, payload_plugins, delete_config=True)
            assert_uninstall(jar, payload_plugins, delete_config=False)

            # the uninstaller phases must have run inside the task tmpdir
            iz_logs = [f for f in os.listdir(TASK_TMP)
                       if f.startswith("izpack") and f.endswith(".log")]
            check("uninstaller phases confined to task tmpdir", len(iz_logs) >= 2,
                  "found=%s" % iz_logs[:5])

            assert_malformed_identity_safety(jar, payload_plugins)

        assert_global_lock_untouched(lock_state)
        print("checkJavaInstaller passed: all acceptance conditions verified on this host.")
    finally:
        shutil.rmtree(TASK_TMP, ignore_errors=True)


if __name__ == "__main__":
    main()
