#!/usr/bin/env python3
"""Deterministic non-GUI verification for the Turboism Java installer.

Drives the IzPack installer in console mode (Java 17+, no GUI) and asserts
the frozen acceptance conditions, including the R2 repairs:

  1.  The installer JAR and its SHA-256 sidecar exist and match; the sidecar
      is a repository-root-relative `sha256sum -c` line.
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
      `packaging/release-plugins.txt` exactly (the frozen 18 approved
      projects; runtime-owned core is never a payload plugin), and the four
      excluded placeholder IDs/JARs are absent from the payload, packs, and
      selection surface — the shared manifest is the regression oracle.

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
# Turboism-owned InstallationGroupPanel strings (CustomLangPack): the
# install-side listener emits the localized mode name and description for
# both modes on every run, and the probes must observe them live.
LOCALIZED_MODE = {
    "eng": {
        "full": ("Full installation (all plugins)",
                 "Installs the Turboism agent and every first-party plugin. You can deselect individual plugins on the next page."),
        "lite": ("Lite installation (no plugins)",
                 "Installs only the Turboism agent and common files. No first-party plugin JAR is copied."),
    },
    "chn": {
        "full": ("完整安装（全部插件）",
                 "安装 Turboism 代理与全部第一方插件。可在下一页取消勾选个别插件。"),
        "lite": ("精简安装（不含插件）",
                 "仅安装 Turboism 代理与公共文件，不复制任何第一方插件 JAR。"),
    },
    "jpn": {
        "full": ("フルインストール（全プラグイン）",
                 "Turboism エージェントと全ファーストパーティプラグインをインストールします。次のページで個別に選択を解除できます。"),
        "lite": ("ライトインストール（プラグインなし）",
                 "Turboism エージェントと共通ファイルのみをインストールします。ファーストパーティプラグインの JAR はコピーされません。"),
    },
}
UNINSTALL_DELETE_CONFIG_PROP = "turboism.uninstall.deleteConfig"

# Frozen release-plugin allowlist — sole authority is packaging/release-plugins.txt.
# This exact list plus the excluded placeholder ids is the regression oracle:
# production drift from the shared manifest fails verification.
MANIFEST_EXPECTED = [
    ":plugins:atlas-maxrects-bssf",
    ":plugins:clip-mask",
    ":plugins:clipmask-viewer",
    ":plugins:core",
    ":plugins:cubism-tab-filter",
    ":plugins:demo",
    ":plugins:log-filter",
    ":plugins:mesh",
    ":plugins:palette-label-style",
    ":plugins:parameter",
    ":plugins:perf-opt",
    ":plugins:physics-editor",
    ":plugins:project-inspector",
    ":plugins:recent-preview",
    ":plugins:render-opt",
    ":plugins:scene-palette-enhancer",
    ":plugins:texture-atlas-stats",
    ":plugins:ui-theme",
]
# The four pure placeholder projects: absent from the manifest and therefore
# from every release payload, pack, section, and selection surface.
EXCLUDED_PLACEHOLDER_IDS = (
    "dev.turboism.plugin.bounding-box",
    "dev.turboism.plugin.context-menu",
    "dev.turboism.plugin.project-panel",
    "dev.turboism.plugin.psd-import",
)


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


def run_console(jar, answers, timeout=TIMEOUT):
    """Runs `java -jar <jar> -console` with piped answers.

    stdin is deliberately kept open: the console prompts read incrementally
    and an early EOF aborts the install. Returns (exit_code, output).
    """
    cmd = java_cmd() + ["-jar", jar, "-console"]
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
        plugins.append({
            "module": jar[:-4],
            "id": meta["id"],
            "name": meta.get("name", meta["id"]),
            "version": meta.get("version", ""),
            "description": meta.get("description", ""),
        })
    plugins.sort(key=lambda p: p["id"])
    return plugins


def load_release_manifest(path):
    """Parses the sole release-plugin allowlist (packaging/release-plugins.txt)
    fail-closed: blank/comment lines, malformed or non-plugin entries,
    duplicates, unsorted order, or drift from the frozen 18-project allowlist
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
    check("release manifest matches the frozen 18-project allowlist",
          lines == MANIFEST_EXPECTED, "n=%d" % len(lines))
    return [l[len(":plugins:"):] for l in lines if l != ":plugins:core"]


def install_answers(mode, target, lang_index=0, deselect=(), payload_plugins=None):
    answers = [str(lang_index), "1", "1"]  # language, welcome, license
    # console InstallationGroupPanel: asks per group (full first, then lite)
    answers += ["n", "y"] if mode == "lite" else ["y"]
    answers += ["1"]  # group panel continue
    if mode == "full":
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
    for mode in ("full", "lite"):
        name, description = LOCALIZED_MODE[iso3][mode]
        check("locale %s %s mode name" % (iso3, mode), name in out,
              "output did not contain the localized %s mode name" % mode)
        check("locale %s %s mode description" % (iso3, mode), description in out,
              "output did not contain the localized %s mode description" % mode)
    shutil.rmtree(base, ignore_errors=True)


def assert_lite_install(jar, payload_plugins):
    base = tempfile.mkdtemp(prefix="turboism-lite ")
    target = os.path.join(base, "home with spaces")
    clear_task_lock()
    rc, out = run_console(jar, install_answers("lite", target))
    check("lite install exit 0", rc == 0, "rc=%s" % rc)
    check("lite no plugins dir", not os.path.isdir(os.path.join(target, "plugins")))
    check("lite common pack required line", "Turboism Core' required" in out)
    config = json.load(open(os.path.join(target, "config.json")))
    expected = sorted(p["id"] for p in payload_plugins)
    check("lite disabledPlugins == all bundled", config.get("disabledPlugins") == expected,
          str(config.get("disabledPlugins")))
    check("lite canonical fields", config["worktreeId"] == "turboism-runtime"
          and config["pluginDirs"] == ["plugins"]
          and config["format"] == "turboism.runtime.config"
          and config["schemaVersion"] == 1)
    ucmd = os.path.join(target, "uninstall.command")
    if sys.platform == "darwin":
        check("mac uninstall.command is regular non-symlink",
              os.path.isfile(ucmd) and not os.path.islink(ucmd))
        check("mac uninstall.command is executable", os.access(ucmd, os.X_OK))
    else:
        check("non-mac does not install uninstall.command", not os.path.lexists(ucmd))
    shutil.rmtree(base, ignore_errors=True)


def assert_full_install(jar, payload_plugins):
    deselect = {payload_plugins[0]["id"], payload_plugins[-1]["id"]}
    base = tempfile.mkdtemp(prefix="turboism-full ")
    target = os.path.join(base, "full home")
    clear_task_lock()
    rc, out = run_console(jar, install_answers("full", target, deselect=deselect, payload_plugins=payload_plugins))
    check("full install exit 0", rc == 0, "rc=%s" % rc)
    installed = sorted(os.listdir(os.path.join(target, "plugins")))
    # r6: 载荷 pack 安装全部捆绑 JAR；勾选只控制 disabledPlugins
    expected_modules = sorted(p["module"] + ".jar" for p in payload_plugins)
    check("full installs every bundled jar (payload pack)", installed == expected_modules, str(installed))
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
        "disabledPlugins": ["dev.turboism.plugin.mesh", "dev.turboism.plugin.other"],
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
                '"disabledPlugins":["dev.turboism.plugin.mesh"]}')
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
    uninstaller = os.path.join(target, "Uninstaller", "uninstaller.jar")
    home = run_shipped_uninstaller(uninstaller, delete_config)
    check("uninstall removes agent", not os.path.exists(os.path.join(home, "turboism-agent.jar")))
    check("uninstall removes uninstaller", not os.path.exists(os.path.join(home, "Uninstaller")))
    check("uninstall removes runtime dirs",
          not os.path.exists(os.path.join(home, "logs"))
          and not os.path.exists(os.path.join(home, "state"))
          and not os.path.exists(os.path.join(home, "cache")))
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


def assert_jar_layout(jar, payload):
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        for lang in ("eng", "chn", "jpn"):
            check("jar langpack %s" % lang, "resources/langpacks/%s.xml" % lang in names)
        for variant in ("", "_eng", "_chn", "_jpn"):
            check("jar custom langpack %s" % (variant or "base"),
                  "resources/CustomLangPack.xml%s" % variant in names)
        check("jar uninstaller classes", "com/izforge/izpack/uninstaller/Uninstaller.class" in names)
        check("jar uninstall listener",
              "dev/turboism/installer/TurboismUninstallerListener.class" in names)
        check("jar install listener",
              "dev/turboism/installer/TurboismInstallerListener.class" in names)
        check("jar config template resource", "turboism/config.template.json" in names)
        core_pack_name = "resources/packs/pack-Turboism Core"
        check("jar Windows core pack", core_pack_name in names)
        core_pack = z.read(core_pack_name)
        for helper in ("cubism-launch-common.ps1", "configure_turboism.ps1"):
            staged = os.path.join(payload, helper)
            check("staged Windows helper %s is regular" % helper,
                  os.path.isfile(staged) and not os.path.islink(staged), staged)
            with open(staged, "rb") as f:
                helper_bytes = f.read()
            check("staged Windows helper %s is non-empty" % helper, bool(helper_bytes))
            check("jar Windows helper %s embedded in core pack" % helper,
                  helper_bytes in core_pack)


def assert_sidecar(jar, sha_path):
    """Verifies the SHA-256 sidecar: repository-root-relative path and a hash
    that matches the installer JAR byte-for-byte (sha256sum -c compatible)."""
    check("sidecar exists", os.path.isfile(sha_path), sha_path)
    content = open(sha_path).read().strip()
    parts = content.split("  ", 1)
    check("sidecar has 'hash  relpath' format", len(parts) == 2, repr(content))
    digest, relpath = parts
    check("sidecar hash is 64 hex chars",
          re.fullmatch(r"[0-9a-f]{64}", digest) is not None, digest)
    actual = hashlib.sha256(open(jar, "rb").read()).hexdigest()
    check("sidecar hash matches jar", digest == actual, "jar=%s" % actual)
    expected_rel = os.path.relpath(os.path.abspath(jar), os.getcwd()).replace(os.sep, "/")
    check("sidecar path is repository-root-relative", relpath == expected_rel, relpath)


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
    args = parser.parse_args()

    jar = os.path.abspath(args.installer)
    if not os.path.isfile(jar):
        fail("installer jar not found: %s" % jar)
    payload_plugins = load_plugin_inventory(args.payload)
    if len(payload_plugins) < 3:
        fail("payload plugin inventory too small: %d" % len(payload_plugins))
    global ALL_BUNDLED_IDS
    ALL_BUNDLED_IDS = [p["id"] for p in payload_plugins]
    print("verifying installer: %s (%d bundled plugins)" % (jar, len(payload_plugins)))

    # Shared-manifest regression oracle: the staged payload must equal the
    # allowlisted plugin modules (core excluded) and never carry one of the
    # four excluded placeholder ids.
    manifest_modules = load_release_manifest(args.manifest)
    payload_modules = sorted(p["module"] for p in payload_plugins)
    check("payload plugin modules equal manifest allowlist (core excluded)",
          payload_modules == sorted(manifest_modules),
          "payload=%s manifest=%s" % (payload_modules, sorted(manifest_modules)))
    payload_ids = set(p["id"] for p in payload_plugins)
    check("excluded placeholder ids absent from payload",
          not (payload_ids & set(EXCLUDED_PLACEHOLDER_IDS)),
          "found=%s" % sorted(payload_ids & set(EXCLUDED_PLACEHOLDER_IDS)))
    check("runtime-owned core absent from payload", "turboism.core" not in payload_ids)

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
        assert_jar_layout(jar, args.payload)

        lang_indices = discover_language_indices(jar)
        for iso3 in LOCALES:
            assert_locale_probe(jar, payload_plugins, lang_indices[iso3], iso3)

        assert_lite_install(jar, payload_plugins)
        assert_full_defaults_all(jar, payload_plugins)
        assert_full_install(jar, payload_plugins)
        assert_reselection(jar, payload_plugins)
        assert_config_merge(jar)
        assert_number_preservation(jar)
        assert_size_boundary(jar)

        def malformed_setup(t):
            open(os.path.join(t, "config.json"), "w").write("{broken json!!!")

        assert_fail_closed(jar, "malformed", malformed_setup)
        assert_fail_closed(jar, "oversized",
                           lambda t: open(os.path.join(t, "config.json"), "wb").write(b" " * (70 * 1024)))
        assert_fail_closed(jar, "bad-unicode-escape",
                           lambda t: open(os.path.join(t, "config.json"), "wb").write(b'{"x": "\\uZZZZ"}'))
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
