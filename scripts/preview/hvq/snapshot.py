"""Snapshot preparation, source input isolation, hook validation, and digest verification."""
from __future__ import annotations

import errno
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from typing import TYPE_CHECKING, Any

from .common import (
    QUEUE_PROTOCOL,
    SCHEMA,
    QueueError,
    atomic_json,
    canonical_json,
    digest_json,
    process_identity,
    storage_lock,
)

if TYPE_CHECKING:
    from .store import Store

INPUT_FLAGS = frozenset({"--bundle-root", "--agent", "--home-config", "--fixture-local",
    "--fixture-host", "--fixture-remote", "--remote-pre-launch", "--remote-post-launch",
    "--remote-pre-cleanup"})
COMPOSITE_FLAGS = frozenset({"--plugin", "--aux-agent", "--home-file", "--home-dir", "--client-script"})
BOOLEAN_FLAGS = frozenset({"--require-fixture-unchanged", "--keep-prefix",
    "--remote-pre-launch-background", "--remote-pre-launch-args-only", "--focus-editor-window"})
VALUE_FLAGS = frozenset({"--name", "--version", "--fixture-sha256", "--fixture-name",
    "--result-marker", "--result-file", "--result-pass-line", "--result-fail-line",
    "--ready-marker", "--failure-marker", "--trigger", "--jvm-option", "--windows-env",
    "--linux-env", "--cubism-java", "--cubism-java-console-marker", "--run-label", "--agent-timeout",
    "--agent-host-class", "--ready-timeout", "--result-timeout", "--exit-timeout",
    "--poll-seconds", "--golden-prefix", "--host-root", "--remote-root", "--display",
    "--proton-wrapper", "--proton-runner", "--graphics-device", "--local-evidence-dir", "--transport",
    "--remote-pre-launch-arg", "--aux-agent-before-main", "--client-python",
    "--editor-window-niri", "--editor-window-python"})

# Job-local Linux launch environment: only reviewed Mesa/Proton debug names may
# be snapshotted into a prepared job. The snapshotted Runner re-validates the
# same whitelist at execution; this check stops a hand-edited request earlier.
LINUX_ENV_NAME = re.compile(r"^(?:mesa_glthread|GALLIUM_HUD|GALLIUM_HUD_PERIOD|MESA_[A-Z0-9_]{1,48})$")
LINUX_ENV_VALUE = re.compile(r"^[A-Za-z0-9._:,=+/-]{1,200}$")

# Reviewed pre-launch hook inventory: hook file name -> (protocol flags the
# invocation must carry, error description). Each entry is an explicit review of
# that one hook; the queue still snapshots the hook file and digests its closure.
REVIEWED_PRE_LAUNCH_HOOKS = {
    # Self-contained stdlib-only task-clone configuration writer. NMT must be
    # initialized by the old Java launcher, not injected via JAVA_TOOL_OPTIONS.
    "stage-direct-nmt-launcher.py": (
        frozenset({"--require-fixture-unchanged", "--plugin", "-XX:+DisableAttachMechanism",
                   "-Dturboism.validation.atlasImageShadow.resourceObservation=true",
                   "-Dturboism.validation.observerFree.optIn=T099_NMT_HEAP_PAGES_DIAGNOSTIC_V1"}),
        "direct NMT launcher hook requires its reviewed controlled diagnostic protocol",
    ),
    "fps-resize-driver.sh": (
        frozenset({"--remote-pre-launch-background", "--remote-pre-launch-args-only"}),
        "FPS hook requires its reviewed background/args-only protocol",
    ),
    # The language is passed as the hook's trailing argument; without it the
    # hook fails closed, so the flag is part of the reviewed protocol.
    "host-locale-environment-language-hook.sh": (
        frozenset({"--remote-pre-launch-arg"}),
        "host-locale environment-language hook requires its language argument",
    ),
    # 025 external-edit probe: appends a .psd -> notepad.exe open verb to the
    # task-scoped cloned prefix's system.reg only; takes no extra flags.
    "external-psd-edit-psd-association-pre-launch.sh": (
        frozenset(),
        "external-psd-edit association hook needs no extra flags",
    ),
    # The restart-phase hook copies restart-state/ into state/ and rebinds
    # stagedJar inside pending.json; the staged plugin-management state must
    # arrive as a declared --home-dir input, so the flag is part of the
    # reviewed protocol.
    "plugin-management-restart-remote-pre-launch.sh": (
        frozenset({"--home-dir"}),
        "plugin-management restart hook requires the staged state via --home-dir",
    ),
    # Read-only native history pointer collector; the task-id argument lets the
    # hook bind its evidence file to the queue task identity.
    "history-pointer-observer.py": (
        frozenset({"--remote-pre-launch-background", "--remote-pre-launch-args-only", "--task-id"}),
        "pointer hook requires its reviewed background/args-only task-id protocol",
    ),
}

# Standard-library-only Linux /proc reader. No checkout-relative imports, subprocesses,
# signals, or external data dependencies; writes one exclusive task evidence report.
EXTERNAL_PSD_RSS_HOOK = Path("validation/external-psd-edit-host-probe/rss.py")


def require_external_psd_rss_protocol(argv: list[str]) -> None:
    options: dict[str, str] = {}
    jvm: dict[str, str] = {}
    index = 0
    while index < len(argv):
        flag = argv[index]
        index += 1
        if flag in BOOLEAN_FLAGS:
            options[flag] = "true"
            continue
        if flag not in INPUT_FLAGS | COMPOSITE_FLAGS | VALUE_FLAGS or index >= len(argv):
            raise QueueError("invalid RSS hook runner protocol")
        value = argv[index]
        index += 1
        options[flag] = value
        if flag == "--jvm-option" and value.startswith("-D"):
            key, _, setting = value[2:].partition("=")
            jvm[key] = setting
    expected = {"phase": "pipeline", "contentProfile": "f1", "cycles": "10", "performance": "1"}
    if (options.get("--name") != "external-psd-edit-pipeline"
            or options.get("--version") != "5302"
            or options.get("--result-file") !=
                "state/dev.turboism.validation.externalpsd/external-psd-edit-result.properties"
            or any(jvm.get("turboism.validation.externalpsd." + key) != value
                   for key, value in expected.items())
            or any(flag in options for flag in ("--trigger", "--remote-pre-launch-arg",
                "--remote-pre-launch-background", "--remote-pre-launch-args-only"))):
        raise QueueError("RSS hook requires the reviewed synchronous F1 ten-cycle pipeline protocol")


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def runtime_digest(root: Path) -> str:
    """Fingerprint installed runtime trees without copying or following links."""
    if not root.is_absolute() or ".." in root.parts or any(p.is_symlink() for p in (root, *root.parents)):
        raise QueueError("host runtime must be an absolute non-symlink path")
    if root.is_file():
        before = root.stat()
        result = file_digest(root)
        after = root.stat()
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise QueueError("host runtime changed while hashing")
        return result
    if not root.is_dir():
        raise QueueError("host runtime dependency must be an existing file or directory")
    entries: dict[str, Any] = {}
    observed = []
    def scan_error(failure: OSError) -> None:
        raise QueueError("cannot inspect complete host runtime directory") from failure
    for directory, dirs, files in os.walk(root, followlinks=False, onerror=scan_error):
        for path in [Path(directory), *(Path(directory) / name for name in sorted(dirs + files))]:
            relative = path.relative_to(root).as_posix()
            if relative in entries:
                continue
            before = path.lstat()
            observed.append((path, before))
            mode = stat.S_IMODE(before.st_mode)
            if stat.S_ISLNK(before.st_mode):
                try:
                    target = path.resolve(strict=True)
                except (OSError, RuntimeError) as failure:
                    raise QueueError("host runtime contains an unresolved symlink") from failure
                if not target.is_relative_to(root):
                    raise QueueError("host runtime symlink escapes its runtime directory")
                entries[relative] = ["symlink", mode, os.readlink(path)]
            elif stat.S_ISDIR(before.st_mode):
                entries[relative] = ["directory", mode]
            elif stat.S_ISREG(before.st_mode):
                entries[relative] = ["file", mode, file_digest(path)]
            else:
                raise QueueError("host runtime contains a special file")
    for path, before in observed:
        after = path.lstat()
        if (before.st_dev, before.st_ino, before.st_mode, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
                after.st_dev, after.st_ino, after.st_mode, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise QueueError("host runtime changed while hashing")
    return digest_json(entries)


def tree_inventory(root: Path) -> dict[str, str]:
    if root.is_symlink():
        raise QueueError("snapshot source may not be a symlink")
    if root.is_file():
        return {".": file_digest(root)}
    if not root.is_dir():
        raise QueueError(f"missing snapshot input: {root}")
    entries: dict[str, str] = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise QueueError(f"snapshot input contains symlink: {path}")
        if path.is_file():
            entries[path.relative_to(root).as_posix()] = file_digest(path)
        elif path.is_dir():
            entries[path.relative_to(root).as_posix() + "/"] = "directory"
        else:
            raise QueueError("snapshot input contains a special file")
    return entries


def copy_verified(source: Path, destination: Path) -> None:
    source = source.absolute()
    if any(part.is_symlink() for part in (source, *source.parents)):
        raise QueueError("snapshot path must not traverse symlinks")
    before = tree_inventory(source)
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if source.is_dir():
        shutil.copytree(source, destination)
    else:
        shutil.copyfile(source, destination)
        destination.chmod(source.stat().st_mode & 0o700)
    if before != tree_inventory(source) or before != tree_inventory(destination):
        raise QueueError("input changed while preparing snapshot")
    for path in ([destination] if destination.is_file() else destination.rglob("*")):
        if path.is_file():
            with path.open("rb") as stream:
                os.fsync(stream.fileno())


def memory_observer_dependency(argv: list[str], source_root: Path, task_spec: str) -> dict[str, str] | None:
    """One reviewed closure, not a general custom-hook registration mechanism."""
    options: dict[str, list[str]] = {}
    index = 0
    while index < len(argv):
        flag = argv[index]
        if flag in BOOLEAN_FLAGS:
            options.setdefault(flag, []).append("")
            index += 1
        elif flag in INPUT_FLAGS | COMPOSITE_FLAGS | VALUE_FLAGS and index + 1 < len(argv):
            options.setdefault(flag, []).append(argv[index + 1])
            index += 2
        else:
            raise QueueError(f"unsupported normalized runner option: {flag}")
    hook = str(source_root / "scripts/test/start-task-memory-observer.sh")
    if hook not in options.get("--remote-pre-launch", []):
        return None
    required = {"--remote-pre-launch": [hook], "--name": ["native-resource"],
                "--version": ["5302"], "--remote-pre-launch-background": [""]}
    if task_spec != "native-resource:5302" or any(options.get(key) != value for key, value in required.items()):
        raise QueueError("memory observer requires its reviewed task/background protocol")
    if any(key in options for key in ("--remote-pre-launch-args-only", "--remote-post-launch",
                                      "--remote-pre-cleanup", "--client-script", "--home-dir")):
        raise QueueError("memory observer closure cannot include extra hooks or shadow directories")
    helpers = ("measure-task-memory.py", "host_memory_identity.py", "host_resource_counters.py")
    expected = {str(source_root / "scripts/test" / name) + ":validation/" + name for name in helpers}
    # host_memory_identity.py resolves the reviewed jar digest through these two staged siblings.
    reviewed_reader = source_root / "scripts/preview/reviewed_host_artifacts.py"
    reviewed_source = source_root / "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java"
    expected |= {f"{reviewed_reader}:validation/{reviewed_reader.name}",
                 f"{reviewed_source}:validation/{reviewed_source.name}"}
    supplied = options.get("--home-file", [])
    if len(supplied) != len(expected) or set(supplied) != expected:
        raise QueueError("memory observer requires exact unshadowed helper dependency inventory")
    interpreter = Path(sys.executable).resolve(strict=True)
    if (options.get("--remote-pre-launch-arg") != [str(interpreter)]
            or not interpreter.is_file() or not os.access(interpreter, os.X_OK)):
        raise QueueError("memory observer requires the preparing Python interpreter")
    return {"option": "memory-observer-python", "path": str(interpreter),
            "sha256": runtime_digest(interpreter)}


def mcp_client_dependency(argv: list[str], source_root: Path, task_spec: str) -> dict[str, str] | None:
    """Admit only the reviewed MCP stdlib client, never arbitrary client scripts."""
    options: dict[str, list[str]] = {}
    index = 0
    while index < len(argv):
        flag = argv[index]
        if flag in BOOLEAN_FLAGS:
            options.setdefault(flag, []).append("")
            index += 1
        elif flag in INPUT_FLAGS | COMPOSITE_FLAGS | VALUE_FLAGS and index + 1 < len(argv):
            options.setdefault(flag, []).append(argv[index + 1])
            index += 2
        else:
            raise QueueError(f"unsupported normalized runner option: {flag}")
    if not task_spec.startswith("mcp:") and options.get("--name") != ["mcp"]:
        # Another task family; its own reviewer decides whether its client is
        # admitted. A client flag with no claiming reviewer is still rejected
        # downstream in _capture.
        return None
    versions = options.get("--version", [])
    if (options.get("--name") != ["mcp"] or len(versions) != 1
            or versions[0] not in {
                "5000", "5001", "5002", "5003", "5004", "5005", "5006", "5100", "5101", "5102",
                "5103", "5200", "5201", "5202", "5203", "5300", "5301", "5302", "5303", "5304"}
            or task_spec not in {"direct-runner", "mcp:" + versions[0],
                                 "mcp:" + versions[0] + "@standard",
                                 "mcp:" + versions[0] + "@free-fixture"}):
        raise QueueError("custom client requires reviewed dependency inventory for the exact MCP task")
    if task_spec.endswith("@free-fixture"):
        required_jvm = {
            "-Dturboism.validation.startFree=true",
            "-Dturboism.validation.freeFixture=true",
            "-Dturboism.optimization.mesaGlThread=false",
        }
        jvm = options.get("--jvm-option", [])
        if (any([option for option in jvm if option.startswith(value.split("=", 1)[0] + "=")] != [value]
                for value in required_jvm)
                or options.get("--linux-env") != ["mesa_glthread=false"]):
            raise QueueError("FREE fixture variant requires exact SDK preparation and render configuration")
    client = source_root / "scripts/preview/mcp-host-validation-client.py"
    expected = {
        "--client-script": [str(client) + ":mcp-host-validation-client.py"],
        "--result-file": ["state/mcp-host-validation.properties"],
        "--require-fixture-unchanged": [""],
    }
    if any(options.get(flag) != values for flag, values in expected.items()):
        raise QueueError("MCP client requires exact unshadowed dependency inventory and result protocol")
    if any(flag in options for flag in ("--remote-pre-launch", "--remote-post-launch",
            "--remote-pre-cleanup", "--remote-pre-launch-background", "--remote-pre-launch-args-only",
            "--focus-editor-window")):
        raise QueueError("MCP client dependency inventory cannot include extra hooks or desktop focus control")
    interpreter = Path(sys.executable).resolve(strict=True)
    if (options.get("--client-python") != [str(interpreter)]
            or not interpreter.is_file() or not os.access(interpreter, os.X_OK)):
        raise QueueError("MCP client requires the preparing Python interpreter")
    return {"option": "mcp-client-python", "path": str(interpreter),
            "sha256": runtime_digest(interpreter)}


def edit_protocol_client_dependency(argv: list[str], source_root: Path, task_spec: str) -> dict[str, str] | None:
    """Admit only the reviewed edit-protocol stdlib WebSocket client."""
    options: dict[str, list[str]] = {}
    index = 0
    while index < len(argv):
        flag = argv[index]
        if flag in BOOLEAN_FLAGS:
            options.setdefault(flag, []).append("")
            index += 1
        elif flag in INPUT_FLAGS | COMPOSITE_FLAGS | VALUE_FLAGS and index + 1 < len(argv):
            options.setdefault(flag, []).append(argv[index + 1])
            index += 2
        else:
            raise QueueError(f"unsupported normalized runner option: {flag}")
    if not task_spec.startswith("edit-protocol:") and options.get("--name") != ["edit-protocol"]:
        return None
    versions = options.get("--version", [])
    if (options.get("--name") != ["edit-protocol"] or len(versions) != 1
            or versions[0] not in {
                "5000", "5001", "5002", "5003", "5004", "5005", "5006", "5100", "5101", "5102",
                "5103", "5200", "5201", "5202", "5203", "5300", "5301", "5302", "5303", "5304"}
            or task_spec not in {"direct-runner", "edit-protocol:" + versions[0],
                                 "edit-protocol:" + versions[0] + "@standard",
                                 "edit-protocol:" + versions[0] + "@assisted"}):
        raise QueueError("custom client requires reviewed dependency inventory for the exact edit-protocol task")
    if task_spec.endswith("@assisted"):
        aux = options.get("--aux-agent", [])
        jvm = options.get("--jvm-option", [])
        required_jvm = {
            "-Dturboism.validation.startupAssistance=true",
            "-Dturboism.optimization.mesaGlThread=false",
        }
        if (len(aux) != 1 or not aux[0].endswith(":native-startup-assistance-agent.jar")
                or any([option for option in jvm if option.startswith(value.split("=", 1)[0] + "=")] != [value]
                        for value in required_jvm)
                or options.get("--linux-env") != ["mesa_glthread=false"]):
            raise QueueError("assisted variant requires the exact startup-assistance inventory")
    client = (source_root / "validation/protocol-edit-compat-host-probe"
              / "client/protocol_edit_host_probe.py")
    expected = {
        "--client-script": [str(client) + ":protocol-edit-host-probe.py"],
        "--result-file": ["state/edit-protocol-host-validation-result.properties"],
        "--require-fixture-unchanged": [""],
    }
    if any(options.get(flag) != values for flag, values in expected.items()):
        raise QueueError("edit-protocol client requires exact unshadowed dependency inventory and result protocol")
    if any(flag in options for flag in ("--remote-pre-launch", "--remote-post-launch",
            "--remote-pre-cleanup", "--remote-pre-launch-background", "--remote-pre-launch-args-only",
            "--focus-editor-window")):
        raise QueueError("edit-protocol client dependency inventory cannot include extra hooks or desktop focus control")
    interpreter = Path(sys.executable).resolve(strict=True)
    if (options.get("--client-python") != [str(interpreter)]
            or not interpreter.is_file() or not os.access(interpreter, os.X_OK)):
        raise QueueError("edit-protocol client requires the preparing Python interpreter")
    return {"option": "edit-protocol-client-python", "path": str(interpreter),
            "sha256": runtime_digest(interpreter)}


def task_resources(task_spec: str) -> list[str] | None:
    """Declared resource names for a catalogue task spec; None when the spec is
    not a catalogue task (fail-closed serial scheduling)."""
    name = task_spec.split(":", 1)[0].split("@", 1)[0]
    tasks_file = Path(__file__).resolve().parent.parent / "host-validation-tasks.json"
    if not tasks_file.is_file():
        tasks_file = Path(__file__).resolve().parent / "host-validation-tasks.json"
    try:
        data = json.loads(tasks_file.read_text(encoding="utf-8"))
        resources = data["tasks"][name]["resources"]
    except (OSError, ValueError, KeyError, TypeError):
        return None
    if not isinstance(resources, dict) or not all(type(key) is str for key in resources):
        return None
    return sorted(resources)


class PreparedStore:
    def __init__(self, store: Store):
        self.store = store

    def capture(self, request: dict[str, Any], source_root: Path,
                task_spec: str, *, resources: list[str] | None = None) -> dict[str, Any]:
        from host_validation_retention import check_space
        if self.store.production:
            check_space(self.store.root)
        with storage_lock(self.store.root):
            return self._capture(request, source_root, task_spec, resources)

    def _capture(self, request: dict[str, Any], source_root: Path,
                 task_spec: str, resources: list[str] | None = None) -> dict[str, Any]:
        argv = request.get("argv")
        if request.get("schemaVersion") != SCHEMA or not isinstance(argv, list) or not argv:
            raise QueueError("invalid normalized runner request")
        if any(not isinstance(arg, str) or "\0" in arg or "@INPUT@" in arg for arg in argv):
            raise QueueError("invalid runner argument")
        # Normalized argv owns placement/defaults; ambient .env is never a dependency.
        if request.get("environment", {}) != {}:
            raise QueueError("normalized runner must express all configuration as argv")
        source_root = source_root.resolve(strict=True)
        core_dispatch = any(flag == "--name" and value == "official-core"
                            for flag, value in zip(argv, argv[1:]))
        if task_spec == "official-core:5302" or core_dispatch:
            if task_spec != "official-core:5302":
                raise QueueError("standalone Core dispatch requires its exact registered task")
            from host_validation_official_core import options
            options(argv)
        viewer_dispatch = any(flag == "--name" and value == "official-viewer"
                              for flag, value in zip(argv, argv[1:]))
        if task_spec == "official-viewer:5302" or viewer_dispatch:
            if task_spec != "official-viewer:5302":
                raise QueueError("official Viewer dispatch requires its exact registered task")
            from host_validation_official_viewer import options
            options(argv)
        memory_dependency = memory_observer_dependency(argv, source_root, task_spec)
        mcp_dependency = mcp_client_dependency(argv, source_root, task_spec)
        edit_protocol_dependency = edit_protocol_client_dependency(argv, source_root, task_spec)
        if self.store.production and "--host-root" in argv:
            from host_validation_retention import check_space
            check_space(self.store.root, (Path(argv[argv.index("--host-root") + 1]),))
        with tempfile.TemporaryDirectory(dir=self.store.root / "staging") as temporary:
            stage = Path(temporary)
            with self.store.transaction() as db:
                db.execute("INSERT INTO retention_objects(kind,object_id,created_at,metadata) VALUES('staging',?,?,?)",
                           (stage.name, time.time(), canonical_json({"owner": process_identity(os.getpid()),
                            "device": stage.stat().st_dev, "inode": stage.stat().st_ino})))
            tool_dir = stage / "tool/scripts/preview"
            tool_dir.mkdir(mode=0o700, parents=True)
            for path in sorted((source_root / "scripts/preview").iterdir()):
                if path.suffix in {".sh", ".py", ".json"} and path.is_file():
                    copy_verified(path, tool_dir / path.name)
            # reviewed_host_artifacts.py resolves the reviewed jar digests from this file at the same
            # relative position inside the snapshot, so the runner never restates them.
            reviewed_source = source_root / "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java"
            if reviewed_source.is_file():
                copy_verified(reviewed_source, stage / "tool" / reviewed_source.relative_to(source_root))
            hvq_source = source_root / "scripts/preview/hvq"
            if not (hvq_source / "__init__.py").is_file():
                raise QueueError("missing canonical runner/helper: hvq")
            hvq_tool = tool_dir / "hvq"
            hvq_tool.mkdir(mode=0o700, parents=True)
            for path in sorted(hvq_source.iterdir()):
                if path.suffix == ".py" and path.is_file():
                    copy_verified(path, hvq_tool / path.name)
            for required in ("run-cubism-host-validation.sh", "host-validation-env.sh", "host-validation-transport.sh",
                             "archive-cubism-host-evidence.sh", "host_validation.py", "host_validation_queue.py",
                             "host_validation_containment.py", "host_validation_evidence.py"):
                if not (tool_dir / required).is_file():
                    raise QueueError(f"missing canonical runner/helper: {required}")
            if not (hvq_tool / "__init__.py").is_file():
                raise QueueError("missing canonical runner/helper: hvq")
            rendered: list[str] = []
            source_inputs: list[dict[str, Any]] = []
            host_dependencies: list[dict[str, str]] = [
                item for item in (memory_dependency, mcp_dependency, edit_protocol_dependency)
                if item is not None
            ]
            if task_spec in ("official-viewer:5302", "official-core:5302"):
                host_dependencies.append({"option": "private-display-server", "path": "/usr/bin/Xvfb",
                                          "sha256": runtime_digest(Path("/usr/bin/Xvfb"))})
            if "--focus-editor-window" in argv:
                if not (tool_dir / "focus-cubism-validation-window.py").is_file():
                    raise QueueError("missing canonical editor window helper")
                for flag, installed in (("--editor-window-niri", shutil.which("niri")),
                                        ("--editor-window-python", sys.executable)):
                    if not installed or flag not in argv or argv.count(flag) != 1:
                        raise QueueError("window helper requires exact installed dependencies")
                    position = argv.index(flag)
                    if position + 1 >= len(argv):
                        raise QueueError("window helper dependency path is missing")
                    actual = Path(argv[position + 1]).resolve()
                    if actual != Path(installed).resolve() or not actual.is_file():
                        raise QueueError("window helper dependency must match the preparing host")
                    host_dependencies.append({"option": flag, "path": str(actual),
                                              "sha256": runtime_digest(actual)})
            elif "--editor-window-niri" in argv or "--editor-window-python" in argv:
                raise QueueError("window helper dependencies require --focus-editor-window")
            index = 0
            while index < len(argv):
                flag = argv[index]
                if flag in BOOLEAN_FLAGS:
                    rendered.append(flag)
                    index += 1
                    continue
                if flag not in INPUT_FLAGS | COMPOSITE_FLAGS | VALUE_FLAGS or index + 1 >= len(argv):
                    raise QueueError(f"unsupported normalized runner option: {flag}")
                value = argv[index + 1]
                index += 2
                if flag in {"--editor-window-niri", "--editor-window-python"}:
                    value = next(item["path"] for item in host_dependencies if item["option"] == flag)
                if flag == "--transport" and value != "local":
                    raise QueueError("only local host execution is supported")
                if flag == "--graphics-device" and value not in {"inherit", "nvidia"}:
                    raise QueueError("graphics device must be inherit or nvidia")
                if flag == "--linux-env":
                    name, separator, env_value = value.partition("=")
                    if not separator or not LINUX_ENV_NAME.fullmatch(name) \
                            or not LINUX_ENV_VALUE.fullmatch(env_value):
                        raise QueueError(
                            "--linux-env entries are restricted to reviewed Mesa debug variables")
                if flag in ("--client-script", "--client-python") \
                        and mcp_dependency is None and edit_protocol_dependency is None:
                    raise QueueError("custom client requires reviewed dependency inventory")
                if flag in {"--proton-wrapper", "--proton-runner"}:
                    dependency = Path(value)
                    if flag == "--proton-wrapper" and not dependency.is_file():
                        raise QueueError("Proton wrapper must be an existing absolute file")
                    host_dependencies.append({"option": flag, "path": value, "sha256": runtime_digest(dependency)})
                if flag == "--local-evidence-dir":
                    continue  # Each attempt owns its evidence destination, never the source checkout.
                if flag in INPUT_FLAGS | COMPOSITE_FLAGS:
                    suffix = ""
                    source_value = value
                    if flag in COMPOSITE_FLAGS and ":" in value:
                        source_value, tail = value.split(":", 1)
                        suffix = ":" + tail
                    source = Path(source_value)
                    if not source.is_absolute():
                        raise QueueError("normalized inputs must be absolute paths")
                    # Only enumerated hooks have a reviewed dependency closure.
                    # A script's location in scripts/preview is not an approval.
                    if flag in {"--remote-pre-launch", "--remote-post-launch", "--remote-pre-cleanup"}:
                        # The memory observer's full closure is checked by
                        # memory_dependency() before any snapshot is created.
                        memory_hook = (
                            flag == "--remote-pre-launch"
                            and memory_dependency is not None
                            and source == source_root / "scripts/test/start-task-memory-observer.sh"
                        )
                        # 025 external-edit RSS observer is a reviewed post-launch hook.
                        rss_hook = (flag == "--remote-post-launch"
                                    and source == source_root / EXTERNAL_PSD_RSS_HOOK)
                        if rss_hook:
                            require_external_psd_rss_protocol(argv)
                        elif not memory_hook:
                            final_nmt_hook = (flag == "--remote-pre-cleanup"
                                              and source.name == "capture-direct-nmt-launcher.py"
                                              and source.parent == source_root / "scripts/preview")
                            reviewed = (REVIEWED_PRE_LAUNCH_HOOKS.get(
                                "stage-direct-nmt-launcher.py" if final_nmt_hook else source.name)
                                        if (flag == "--remote-pre-launch" or final_nmt_hook)
                                        and source.parent == source_root / "scripts/preview" else None)
                            if reviewed is None:
                                raise QueueError("custom hook requires reviewed dependency inventory")
                            required_flags, description = reviewed
                            if not required_flags.issubset(argv):
                                raise QueueError(description)
                            if source.name == "stage-direct-nmt-launcher.py" or final_nmt_hook:
                                # This synchronous writer accepts only the runner's
                                # standard context, never caller-supplied arguments.
                                options: dict[str, list[str]] = {}
                                option_index = 0
                                while option_index < len(argv):
                                    option = argv[option_index]
                                    if option in BOOLEAN_FLAGS:
                                        options.setdefault(option, []).append("")
                                        option_index += 1
                                    elif option in INPUT_FLAGS | COMPOSITE_FLAGS | VALUE_FLAGS and option_index + 1 < len(argv):
                                        options.setdefault(option, []).append(argv[option_index + 1])
                                        option_index += 2
                                    else:
                                        raise QueueError(f"unsupported normalized runner option: {option}")
                                if (task_spec != "atlas-image-shadow:5303"
                                        or options.get("--name") != ["atlas-image-shadow"]
                                        or options.get("--version") != ["5303"]
                                        or options.get("--remote-pre-launch") != [str(source_root / "scripts/preview/stage-direct-nmt-launcher.py")]
                                        or ("--remote-pre-cleanup" in options and options["--remote-pre-cleanup"] != [
                                            str(source_root / "scripts/preview/capture-direct-nmt-launcher.py")])
                                        or any(flag in options for flag in (
                                            "--remote-pre-launch-background", "--remote-pre-launch-args-only",
                                            "--remote-pre-launch-arg", "--remote-post-launch"))):
                                    raise QueueError(description)
                    relative = Path("inputs") / str(len(source_inputs)) / source.name
                    copy_verified(source, stage / relative)
                    source_inputs.append({"source": str(source), "path": relative.as_posix(),
                                          "inventory": tree_inventory(stage / relative)})
                    value = "@INPUT@/" + relative.as_posix() + suffix
                rendered.extend([flag, value])
            try:
                head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source_root, text=True).strip()
                dirty = subprocess.check_output(["git", "diff", "HEAD", "--binary"], cwd=source_root)
                untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=source_root)
                source_state = hashlib.sha256(dirty + untracked)
                for name in untracked.split(b"\0"):
                    if name:
                        untracked_path = source_root / os.fsdecode(name)
                        source_state.update(canonical_json(tree_inventory(untracked_path)).encode())
                dirty_hash = source_state.hexdigest()
            except subprocess.CalledProcessError as failure:
                raise QueueError("cannot record source revision") from failure
            if resources is None:
                resources = task_resources(task_spec)
            if resources is not None:
                resources = sorted(resources)
            descriptor = {"schemaVersion": SCHEMA, "taskSpec": task_spec, "argv": rendered,
                "queueProtocol": QUEUE_PROTOCOL, "resources": resources,
                "source": {"worktree": str(source_root), "head": head, "dirtyDigest": dirty_hash},
                "sourceInputs": source_inputs, "hostDependencies": host_dependencies, "inventory": tree_inventory(stage)}
            digest = digest_json(descriptor)
            atomic_json(stage / "prepared.json", {**descriptor, "digest": digest})
            destination = self.store.root / "prepared" / digest
            try:
                os.rename(stage, destination)
            except OSError as failure:
                if failure.errno not in (errno.EEXIST, errno.ENOTEMPTY):
                    raise
                self.load(digest)
            directory_fd = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
            for path in destination.rglob("*"):
                if path.is_file():
                    path.chmod(path.stat().st_mode & 0o500)
            with self.store.transaction() as db:
                db.execute("""INSERT INTO retention_objects(kind,object_id,created_at,metadata)
                           VALUES('prepared',?,?,?) ON CONFLICT(kind,object_id) DO UPDATE SET
                           retired=0,created_at=excluded.created_at,metadata=excluded.metadata""",
                           (digest, time.time(), canonical_json({"device": destination.stat().st_dev,
                            "inode": destination.stat().st_ino})))
                db.execute("DELETE FROM retention_objects WHERE kind='staging' AND object_id=?", (stage.name,))
            retirement = self.store.root / "retention-prepared" / (digest + ".json")
            if retirement.exists():
                retirement.unlink()  # Explicit verified re-prepare revives this input.
            # TemporaryDirectory cleanup tolerates a moved directory.
            return self.load(digest)

    def load(self, prepared_id: str) -> dict[str, Any]:
        if not re.fullmatch(r"[0-9a-f]{64}", prepared_id):
            raise QueueError("invalid prepared id")
        root = self.store.root / "prepared" / prepared_id
        try:
            descriptor = json.loads((root / "prepared.json").read_text())
        except (OSError, ValueError) as failure:
            raise QueueError("prepared input is missing or corrupt") from failure
        body = {key: value for key, value in descriptor.items() if key != "digest"}
        if descriptor.get("digest") != prepared_id or digest_json(body) != prepared_id:
            raise QueueError("prepared descriptor digest mismatch")
        actual = tree_inventory(root)
        actual.pop("prepared.json", None)
        if actual != descriptor.get("inventory"):
            raise QueueError("prepared input digest mismatch")
        return descriptor

    def command(self, prepared_id: str, evidence_dir: Path) -> list[str]:
        descriptor = self.load(prepared_id)
        for dependency in descriptor["hostDependencies"]:
            if runtime_digest(Path(dependency["path"])) != dependency["sha256"]:
                raise QueueError("host runtime dependency changed after preparation")
        root = self.store.root / "prepared" / prepared_id
        args = [arg.replace("@INPUT@", str(root)) for arg in descriptor["argv"]]
        return ["bash", str(root / "tool/scripts/preview/run-cubism-host-validation.sh"),
                *args, "--local-evidence-dir", str(evidence_dir)]
