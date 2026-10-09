#!/usr/bin/env python3
"""Prepare-time static gates (L0) and bytecode-cluster annotation (L1) for
local-only host validation.

L0 gates — evaluated in prepare before a job reaches the queue:

- Admission: tasks that write through gated capability slices need
  VERIFIED_STATIC evidence in compatibility/cubism/verification/ records.
  Missing evidence == the runtime admission wall will refuse; skip early.
- License: runtime-observed FREE installations cancel fixtures that exceed
  the per-object parameter limit; skip early instead of burning a host slot.
- Fixture routing: the shared .cmo3 fixtures are authored by a specific
  editor version. Older editors block on the native "newer file" dialog;
  route to the task's assisted variant (NativeStartupAssistanceAgent) which
  confirms that dialog.
- Bundle linkage: every dev/turboism member referenced by --plugin/--aux-agent
  jars must resolve inside the bundle (agent jar + same-bundle jars), so a
  mixed-generation bundle is refused before queueing instead of dying with
  NoSuchMethodError on the host. Runs wherever the normalized runner argv is
  known: prepare/run via prepare_request, direct wrappers via _enqueue-runner,
  and prepared-snapshot submission via submit. Plan stays advisory (wrappers
  do not run at plan time) through plan_bundle_notes. A >24h build-time skew
  between bundle jars adds a non-refusing warning.

L1 — bytecode-equivalence annotation only. The committed cluster table
(compatibility/cubism/clusters.json, produced by
scripts/dev/cubism_reachability_clusters.py --emit-json) is read-only here;
nothing is recomputed at prepare time. Only strict clusters may annotate a
job as covered-by-equivalent; shallow clusters are ordering hints and never
suppress work. A missing table is a complete no-op.
"""

from __future__ import annotations

import hashlib
import json
import re
import struct
import sys
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
VERIFICATION_DIR = ROOT / "compatibility" / "cubism" / "verification"
CLUSTERS_PATH = ROOT / "compatibility" / "cubism" / "clusters.json"
CAMPAIGN_JOBS_PATH = ROOT / "validation" / "cubism-version-matrix" / "campaign-jobs.json"


class StaticGateRefusal(Exception):
    """A spec refused at prepare time; the job must not enter the queue."""

    def __init__(self, skip: str, reason: str, detail: str) -> None:
        super().__init__(f"{skip}(reason={reason}): {detail}")
        self.skip = skip
        self.reason = reason
        self.detail = detail


def dotted(version: str) -> str:
    """'5002' -> '5.0.02'."""
    return f"{version[0]}.{version[1]}.{version[2:]}"


# ---------------------------------------------------------------------------
# L0 admission: required capability slices per task.
#
# Only tasks with a runtime-proven admission boundary are listed. The boundary
# version set is the same on both sides: runtime FAIL_ADMISSION on
# 5.0.x/5.1.x parameter writes matches missing 'cubism.editor-model.write'
# capability evidence in those versions' verification records.
TASK_REQUIRED_CAPABILITIES: dict[str, tuple[str, ...]] = {
    "parameter-batch-transfer": ("cubism.editor-model.write",),
    # parameter panel write legs go through the same Parameter.setValue slice
    # the batch-transfer probes proved admission-blocked on 5.0.x/5.1.x.
    "parameter": ("cubism.editor-model.write",),
    # backup's probe dirties the model via Parameter.setValue first
    # (observed runtime failure "dirty via Parameter.setValue failed:
    # IllegalStateException" on 5.1.x — the same admission wall).
    "backup": ("cubism.editor-model.write",),
    "animation-timeline": ("cubism.editor-model.animation-timeline.write",),
}

# Editor-model verification record per version. Other slices (psd, workspace)
# are not admission-gated today.
_RECORD_TASK = "editor-model"


def load_verified_capabilities(version: str) -> tuple[set[str], str | None]:
    """(capabilities, record_path_or_None). Missing/unparseable record -> empty set."""
    path = VERIFICATION_DIR / f"cubism-{dotted(version)}-{_RECORD_TASK}.json"
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return set(), None
    if record.get("status") != "VERIFIED_STATIC":
        return set(), str(path)
    capabilities = record.get("capabilityIds")
    if not isinstance(capabilities, list):
        return set(), str(path)
    return {item for item in capabilities if isinstance(item, str)}, str(path)


# ---------------------------------------------------------------------------
# License observations (NOT a gate).
#
# Every install runs in FREE-start mode: the startup assistance agent selects
# "Start as FREE version" whenever the license dialog appears, on ALL versions
# including ones where parameter-batch-transfer passes fully. The native
# "FREE version - max parameters per object: 2" dialog is acknowledged and the
# model still loads — it is a convenience-limit notice, not a load rejection.
# The earlier FAIL_LICENSE classification (5100/5200/5300 PBT, 5300/5301
# timeline) was re-attributed to `Runtime-owned shell failed to start`, an
# infra failure. The tables stay as documentation; nothing refuses on them.
INSTALL_LICENSE_TIER: dict[str, str] = {}
FREE_LIMIT_EXCEEDED_FIXTURES = {
    "57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4",
}


# ---------------------------------------------------------------------------
# L0 fixture routing: sha256 -> authoring editor document version.
#
# The .cmo3 container is obfuscated/encrypted; naive header parsing reports
# unusable 0.0.0 versions and is NOT trusted. Versions below come from the
# native "Version of the file" dialog shown when an older editor opens the
# fixture. Unknown digests resolve to no routing decision (conservative).
FIXTURE_DOC_VERSION: dict[str, str] = {
    # 测试 混合模式.cmo3 — dialog reports "Cubism 5.2.3"
    "57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4": "5.2.03",
}


def fixture_path_for(task_name: str, version: str, environment: dict[str, str]) -> str | None:
    """Mirror turboism_select_fixture (scripts/preview/host-validation-env.sh)
    plus the task-specific overrides used by runners."""
    if task_name == "parameter-batch-transfer" and version == "5302":
        override = environment.get("TURBOISM_HOST_VALIDATION_FIXTURE_PBT_5302")
        if override:
            return override
    return (
        environment.get(f"TURBOISM_HOST_VALIDATION_FIXTURE_{version}")
        or environment.get("TURBOISM_HOST_VALIDATION_FIXTURE_5303")
    )


def fixture_doc_version(path: str | None) -> tuple[str | None, str | None]:
    """(document version, digest). Unknown/unreadable -> (None, digest|None)."""
    if not path:
        return None, None
    try:
        digest = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    except OSError:
        return None, None
    return FIXTURE_DOC_VERSION.get(digest), digest


def _semver_tuple(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def assisted_variant(task: Any, current: str | None) -> str | None:
    """The variant carrying NativeStartupAssistanceAgent for this request:
    prefer the <current>-assisted counterpart, else a single assisted variant."""
    if current and f"{current}-assisted" in task.variants:
        return f"{current}-assisted"
    candidates = [name for name in task.variants if "assisted" in name]
    return candidates[0] if len(candidates) == 1 else None


# ---------------------------------------------------------------------------
# L0 bundle linkage: the prepared snapshot must not mix artifact generations.
#
# A plugin jar compiled against an older SDK generation classloads fine and
# then dies with NoSuchMethodError on the first removed-member call (observed
# on-host: an mcp.jar built 10-01 calling PluginContext.parameterQuery() next
# to a 10-09 turboism-agent.jar). The gate links every dev/turboism member
# reference in --plugin/--aux-agent jars against the bundle's class universe
# (the agent jar plus every same-bundle jar) before a job may queue.
#
# The class-file parser below is self-contained on purpose: PreparedStore
# snapshots only scripts/preview into the staged tool directory, so this
# module cannot depend on scripts/dev helpers. _decode_mutf8 and the
# constant-pool walk mirror scripts/dev/cubism_version_matrix.py
# (decode_mutf8/parse_class); both stay private to this gate.

DEV_TURBOISM_PREFIX = "dev/turboism/"
BUNDLE_SKEW_SECONDS = 24 * 60 * 60
# zip entry timestamps at or below this are packaging placeholders
# (SOURCE_DATE_EPOCH-style 1980 epochs), not build times.
_JAR_EPOCH_PLACEHOLDER = 347155200  # 1981-01-01

# java/lang/Object is the implicit supertype of every interface; its member
# set is fixed by the JVM specification, so interface references such as
# itf.toString() resolve exactly without a JDK jar.
_OBJECT_MEMBERS = frozenset({
    ("clone", "()Ljava/lang/Object;"),
    ("equals", "(Ljava/lang/Object;)Z"),
    ("finalize", "()V"),
    ("getClass", "()Ljava/lang/Class;"),
    ("hashCode", "()I"),
    ("notify", "()V"),
    ("notifyAll", "()V"),
    ("toString", "()Ljava/lang/String;"),
    ("wait", "()V"),
    ("wait", "(J)V"),
    ("wait", "(JI)V"),
})


def _decode_mutf8(data: bytes) -> str:
    """Decode class-file modified UTF-8 (mirror of
    cubism_version_matrix.decode_mutf8)."""
    chars = []
    i = 0
    while i < len(data):
        b = data[i]
        if b < 0x80:
            chars.append(chr(b))
            i += 1
        elif b & 0xE0 == 0xC0:
            chars.append(chr(((b & 0x1F) << 6) | (data[i + 1] & 0x3F)))
            i += 2
        else:
            chars.append(
                chr(
                    ((b & 0x0F) << 12)
                    | ((data[i + 1] & 0x3F) << 6)
                    | (data[i + 2] & 0x3F)
                )
            )
            i += 3
    text = "".join(chars)
    out = []
    i = 0
    while i < len(text):
        c = text[i]
        if (
            i + 1 < len(text)
            and 0xD800 <= ord(c) <= 0xDBFF
            and 0xDC00 <= ord(text[i + 1]) <= 0xDFFF
        ):
            out.append(chr(0x10000 + ((ord(c) - 0xD800) << 10) + (ord(text[i + 1]) - 0xDC00)))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


@dataclass(frozen=True)
class _BundleClass:
    name: str | None
    super_name: str | None
    interfaces: tuple[str | None, ...]
    members: frozenset  # (name, descriptor) across fields and methods
    refs: tuple         # (kind, owner, name, descriptor); kind in {"field", "method"}


def _parse_bundle_class(data: bytes) -> _BundleClass:
    """One constant-pool walk yielding the declared member set plus every
    Methodref/InterfaceMethodref/Fieldref the class file references."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("bad magic")
    pos = 8
    (cp_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    utf8: list[str | None] = [None] * cp_count
    cls = [0] * cp_count
    nat = [None] * cp_count
    ref_entries: list[tuple[int, int, int]] = []
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (ln,) = struct.unpack_from(">H", data, pos)
            pos += 2
            utf8[i] = _decode_mutf8(data[pos:pos + ln])
            pos += ln
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag == 7:
            (cls[i],) = struct.unpack_from(">H", data, pos)
            pos += 2
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11):
            ref_entries.append((tag, *struct.unpack_from(">HH", data, pos)))
            pos += 4
        elif tag == 12:
            nat[i] = struct.unpack_from(">HH", data, pos)
            pos += 4
        elif tag in (17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"bad constant-pool tag {tag}")
        i += 1

    def cname(idx: int) -> str | None:
        if idx <= 0 or idx >= cp_count:
            return None
        ni = cls[idx]
        return utf8[ni] if ni else None

    access, this_i, super_i = struct.unpack_from(">HHH", data, pos)
    pos += 6
    (iface_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    interfaces = []
    for _ in range(iface_count):
        (ci,) = struct.unpack_from(">H", data, pos)
        pos += 2
        interfaces.append(cname(ci))
    members = set()
    for _ in range(2):  # fields then methods, same on-disk shape
        (count,) = struct.unpack_from(">H", data, pos)
        pos += 2
        for _ in range(count):
            _access, name_i, desc_i = struct.unpack_from(">HHH", data, pos)
            pos += 6
            members.add((utf8[name_i], utf8[desc_i]))
            (attr_count,) = struct.unpack_from(">H", data, pos)
            pos += 2
            for _ in range(attr_count):
                pos += 2
                (ln,) = struct.unpack_from(">I", data, pos)
                pos += 4 + ln
    refs = []
    for tag, ci, ni in ref_entries:
        owner = cname(ci)
        entry = nat[ni]
        if owner is None or entry is None:
            continue
        name, desc = utf8[entry[0]], utf8[entry[1]]
        refs.append(("field" if tag == 9 else "method", owner, name, desc))
    return _BundleClass(
        cname(this_i), cname(super_i), tuple(interfaces),
        frozenset(members), tuple(refs),
    )


def _jar_classes(path: Path) -> list[_BundleClass]:
    try:
        with zipfile.ZipFile(path) as archive:
            classes = []
            for entry in archive.namelist():
                if not entry.endswith(".class"):
                    continue
                try:
                    classes.append(_parse_bundle_class(archive.read(entry)))
                except ValueError as failure:
                    raise ValueError(f"invalid class file {entry} in {path.name}: {failure}") from failure
            return classes
    except zipfile.BadZipFile as failure:
        raise ValueError(f"not a readable jar: {path}") from failure


class _BundleIndex:
    """Lazy dev/turboism class table across the bundle's jars. Entry names
    locate classes without parsing; a class file is parsed only when a
    reference actually resolves against it. Members union across jars
    (plugin classloaders may shadow); super/interfaces keep the first
    provider's shape."""

    def __init__(self, jars: list[tuple[str, Path]]):
        self._locations: dict[str, list[tuple[zipfile.ZipFile, str]]] = {}
        self._cache: dict[str, tuple[set, str | None, tuple] | None] = {}
        for _label, path in jars:
            archive = zipfile.ZipFile(path)  # kept open for the check's lifetime
            for entry in archive.namelist():
                if not entry.endswith(".class"):
                    continue
                name = entry[:-len(".class")]
                if name.startswith("META-INF/versions/"):
                    name = name.split("/", 3)[3] if name.count("/") >= 3 else name
                if not name.startswith(DEV_TURBOISM_PREFIX):
                    continue
                self._locations.setdefault(name, []).append((archive, entry))

    def __contains__(self, name: str) -> bool:
        return name in self._locations

    def get(self, name: str) -> tuple[set, str | None, tuple] | None:
        """(members, super_name, interfaces) merged over every provider, or
        None when no bundle jar carries the class."""
        if name in self._cache:
            return self._cache[name]
        providers = self._locations.get(name)
        if not providers:
            self._cache[name] = None
            return None
        members: set = set()
        super_name: str | None = None
        interfaces: list[str] = []
        for archive, entry in providers:
            node = _parse_bundle_class(archive.read(entry))
            members |= set(node.members)
            super_name = super_name or node.super_name
            interfaces += node.interfaces
        self._cache[name] = (members, super_name, tuple(interfaces))
        return self._cache[name]


def _resolve_member(index: _BundleIndex, owner: str, name: str, desc: str) -> str | None:
    """Resolve one dev/turboism member reference inside the bundle index,
    walking super classes and interfaces. Returns a failure detail or None.

    A hierarchy that leaves the bundle (JDK, com.live2d.*, other host code)
    may legitimately supply the member — e.g. an SDK window type inheriting
    Swing methods — so an out-of-bundle ancestor resolves by benefit of the
    doubt. Only a fully in-bundle hierarchy that lacks the member refuses."""
    if owner.startswith("["):  # array references delegate to Object
        return None
    if owner not in index:
        return f"no bundle jar provides class {owner}"
    seen: set[str] = set()
    stack = [owner]
    external = False
    while stack:
        current = stack.pop()
        if current in seen:
            continue
        seen.add(current)
        if current == "java/lang/Object":
            if (name, desc) in _OBJECT_MEMBERS:
                return None
            continue
        entry = index.get(current)
        if entry is None:
            external = True
            continue
        members, super_name, interfaces = entry
        if (name, desc) in members:
            return None
        if super_name:
            stack.append(super_name)
        stack.extend(interfaces)
    return None if external else f"no bundle class provides {owner}.{name}{desc}"


def _link_findings(plugins: list[tuple[str, Path]],
                   index: _BundleIndex) -> list[str]:
    findings = []
    for label, path in plugins:
        for node in _jar_classes(path):
            seen_refs = set()
            for kind, owner, name, desc in node.refs:
                if not owner.startswith(DEV_TURBOISM_PREFIX):
                    continue
                key = (owner, name, desc)
                if key in seen_refs:
                    continue
                seen_refs.add(key)
                detail = _resolve_member(index, owner, name, desc)
                if detail is not None:
                    source = f"{node.name}.class" if node.name else path.name
                    findings.append(
                        f"{label}: {source} references {owner}.{name}{desc} — {detail}"
                    )
    return findings


def _jar_built_at(path: Path) -> float | None:
    """Best-effort build time: the newest zip entry timestamp when entries
    carry real per-file dates (Gradle keeps source timestamps), else the file
    mtime. A jar whose entries all share one timestamp is date-normalized
    (reproducible-build epoch like 1980/2020), not a build signal."""
    stamps: set[float] = set()
    try:
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                try:
                    stamps.add(time.mktime((*info.date_time, 0, 0, -1)))
                except (OverflowError, OSError, ValueError):
                    continue
    except (OSError, zipfile.BadZipFile):
        pass
    stamps.discard(0)
    if len(stamps) > 1 and max(stamps) > _JAR_EPOCH_PLACEHOLDER:
        return max(stamps)
    try:
        return path.stat().st_mtime
    except OSError:
        return None


def _freshness_notes(jars: list[tuple[str, Path]]) -> list[str]:
    """Warn (never refuse) when bundle jars' build times diverge by >24h —
    the classic mixed-generation bundle signature."""
    stamps = [(label, _jar_built_at(path)) for label, path in jars]
    stamps = [(label, value) for label, value in stamps if value is not None]
    notes = []
    if len(stamps) < 2:
        return notes
    oldest = min(stamps, key=lambda item: item[1])
    newest = max(stamps, key=lambda item: item[1])
    if newest[1] - oldest[1] > BUNDLE_SKEW_SECONDS:
        hours = (newest[1] - oldest[1]) / 3600
        notes.append(
            f"bundle-freshness: {oldest[0]} and {newest[0]} were built "
            f"{hours:.0f}h apart (>24h); the bundle may mix artifact generations"
        )
    return notes


def bundle_jar_inputs(argv: list[str], input_root: Path | None = None):
    """(agent_path, [(remote_label, plugin_path)]) from a normalized runner
    argv or a prepared descriptor argv (@INPUT@/... resolved under
    input_root). Mirrors PreparedStore's composite split on the first ':'.
    --home-file jar values are bundle-loaded too (installed plugin targets,
    staged premain agents); --home-dir trees are not recursed into."""
    agent: Path | None = None
    plugins: list[tuple[str, Path]] = []
    index = 0
    while index < len(argv):
        flag = argv[index]
        if flag in {"--agent", "--plugin", "--aux-agent", "--home-file"} \
                and index + 1 < len(argv):
            value = argv[index + 1]
            index += 2
            if flag == "--agent":
                source, remote = value, ""
            else:
                source, _, remote = value.partition(":")
            if input_root is not None and source.startswith("@INPUT@/"):
                source = str(input_root / source[len("@INPUT@/"):])
            path = Path(source)
            if flag == "--agent":
                agent = path
            elif path.name.endswith(".jar"):
                plugins.append((remote or path.name, path))
        else:
            index += 1
    return agent, plugins


def check_bundle_jars(agent: Path | None, plugins: list[tuple[str, Path]],
                      spec: str) -> tuple[str, ...]:
    """Link-check a bundle. Raises StaticGateRefusal(SKIP_STATIC,
    reason=stale-bundle) naming the jar and call that cannot link; returns
    advisory freshness notes otherwise."""
    jars = ([("turboism-agent.jar", agent)] if agent is not None else []) + list(plugins)
    for label, path in jars:
        if not path.is_file():
            raise StaticGateRefusal(
                "SKIP_STATIC", "stale-bundle",
                f"{spec}: bundle jar missing: {path}",
            )
    try:
        index = _BundleIndex(jars)
        findings = _link_findings(plugins, index)
    except (OSError, ValueError, zipfile.BadZipFile) as failure:
        raise StaticGateRefusal(
            "SKIP_STATIC", "stale-bundle",
            f"{spec}: bundle jar unreadable: {failure}",
        ) from failure
    if findings:
        shown = findings[:5]
        rest = f" (+{len(findings) - len(shown)} more)" if len(findings) > len(shown) else ""
        raise StaticGateRefusal(
            "SKIP_STATIC", "stale-bundle",
            f"{spec}: plugin/aux jars cannot link against the bundled agent: "
            + "; ".join(shown) + rest,
        )
    return tuple(_freshness_notes(jars))


def check_bundle_linkage(argv: list[str], spec: str,
                         input_root: Path | None = None) -> tuple[str, ...]:
    """Prepare/plan-stage gate over a normalized runner request argv."""
    agent, plugins = bundle_jar_inputs(argv, input_root)
    if not plugins:
        return ()
    if agent is None:
        raise StaticGateRefusal(
            "SKIP_STATIC", "stale-bundle",
            f"{spec}: bundle declares plugin jars but no --agent jar",
        )
    return check_bundle_jars(agent, plugins, spec)


def _sanitize_worktree_id(raw: str) -> str | None:
    value = re.sub(r"-{2,}", "-", re.sub(r"[^a-z0-9.-]+", "-", raw.lower())).strip("-")
    return value or None


def _git_head_branch(root: Path) -> str | None:
    """Resolve the checked-out branch without spawning git (plan must not
    execute processes)."""
    git = root / ".git"
    try:
        if git.is_file():
            content = git.read_text(encoding="utf-8").strip()
            if not content.startswith("gitdir:"):
                return None
            head_path = Path(content[7:].strip()) / "HEAD"
        else:
            head_path = git / "HEAD"
        head = head_path.read_text(encoding="utf-8").strip()
    except OSError:
        return None
    if head.startswith("ref: refs/heads/"):
        return head[len("ref: refs/heads/"):]
    return None


def _resolve_worktree_id(environment: dict[str, str], root: Path) -> str | None:
    """Pure-python mirror of scripts/dev/worktree-id.sh resolution order."""
    raw = environment.get("TURBOISM_WORKTREE_ID", "")
    if not raw:
        try:
            raw = (root / ".turboism-worktree-id").read_text(encoding="utf-8").split()[0]
        except (OSError, IndexError):
            branch = _git_head_branch(root)
            if branch in ("main", "master"):
                raw = "main"
            elif branch and branch.startswith("worktree/"):
                raw = branch[len("worktree/"):]
            elif branch:
                raw = branch
            else:
                raw = root.name
    return _sanitize_worktree_id(raw)


def plan_bundle_notes(arguments: list[str], root: Path,
                      environment: dict[str, str]) -> tuple[str, ...]:
    """Best-effort plan-time bundle check over manifest-declared
    --agent/--plugin/--aux-agent jars plus the conventional
    build/preview/<worktree>/turboism-agent.jar. Advisory only: wrappers may
    inject further inputs, so prepare remains the authoritative gate."""
    declared: list[tuple[str, Path]] = []
    agent: Path | None = None
    notes: list[str] = []
    index = 0
    while index < len(arguments):
        flag = arguments[index]
        if flag in {"--agent", "--plugin", "--aux-agent", "--home-file"} \
                and index + 1 < len(arguments):
            value = arguments[index + 1]
            index += 2
            if flag == "--agent":
                source, remote = value, ""
            else:
                source, _, remote = value.partition(":")
            path = Path(source)
            if not path.is_absolute():
                path = root / path
            if flag == "--agent":
                agent = path
            elif path.name.endswith(".jar"):
                declared.append((remote or path.name, path))
        else:
            index += 1
    for label, path in declared:
        if not path.is_file():
            notes.append(f"bundle-input: declared {label} input missing: {path}")
    declared = [(label, path) for label, path in declared if path.is_file()]
    if agent is None:
        worktree = _resolve_worktree_id(environment, root)
        if worktree:
            candidate = root / "build" / "preview" / worktree / "turboism-agent.jar"
            if candidate.is_file():
                agent = candidate
    if agent is None or not agent.is_file() or not declared:
        return tuple(notes) + tuple(_freshness_notes(declared))
    try:
        index = _BundleIndex([("turboism-agent.jar", agent)] + declared)
        findings = _link_findings(declared, index)
    except (OSError, ValueError, zipfile.BadZipFile):
        findings = []
    for finding in findings[:3]:
        notes.append(f"bundle-link-advisory: {finding}")
    return tuple(notes) + tuple(_freshness_notes([("turboism-agent.jar", agent)] + declared))


def audit_bundle_dir(bundle: Path) -> tuple[bool, list[str]]:
    """Read-only link + freshness check over an assembled bundle directory
    (turboism-agent.jar at the root plus every other jar inside it)."""
    agent = bundle / "turboism-agent.jar"
    plugins = sorted(
        (path.relative_to(bundle).as_posix(), path)
        for path in bundle.rglob("*.jar")
        if path != agent
    )
    if not agent.is_file():
        return False, ["no turboism-agent.jar at bundle root"]
    if not plugins:
        return True, ["no plugin/aux jars in bundle"]
    try:
        notes = check_bundle_jars(agent, plugins, bundle.name)
    except StaticGateRefusal as refusal:
        return False, [refusal.detail]
    return True, list(notes)


# ---------------------------------------------------------------------------
# L1 cluster equivalence: task -> mapping-pack family. Only strict clusters
# annotate; shallow clusters are ordering hints and never consulted here.
TASK_CLUSTER_FAMILY: dict[str, str] = {
    "core-acquisition": "core-model-read",
    "core-owned-moc": "core-owned-moc",
    "clipmask-viewer": "clipmask",
    "workspace": "workspace-control",
}


def load_cluster_table(path: Path = CLUSTERS_PATH) -> dict[str, Any]:
    try:
        table = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    families = table.get("families")
    return families if isinstance(families, dict) else {}


def _ledger_pass_versions(task_name: str, path: Path = CAMPAIGN_JOBS_PATH) -> set[str]:
    """Versions with a FINAL PASS ledger entry for this task."""
    try:
        entries = json.loads(path.read_text(encoding="utf-8")).get("jobs", [])
    except (OSError, ValueError):
        return set()
    passed = set()
    for entry in entries:
        if entry.get("task") != task_name or entry.get("status") != "FINAL":
            continue
        result = entry.get("result") or {}
        if result.get("validationStatus") == "PASS" and isinstance(entry.get("version"), str):
            passed.add(entry["version"])
    return passed


def covered_by_equivalent(
    task_name: str,
    version: str,
    families: dict[str, Any],
    ledger_path: Path = CAMPAIGN_JOBS_PATH,
) -> str | None:
    """Strict-cluster equivalence: a FINAL PASS for this task already exists on
    another member of the target's strict cluster -> that member's version.
    Returns None when unmappable, absent, or no member PASS exists."""
    family = TASK_CLUSTER_FAMILY.get(task_name)
    if not family:
        return None
    target = dotted(version)
    for cluster in (families.get(family) or {}).get("strict") or []:
        members = cluster.get("members") or []
        if target not in members:
            continue
        rep = cluster.get("rep")
        others = ([rep] if rep else []) + [item for item in members if item != rep]
        passed = _ledger_pass_versions(task_name, ledger_path)
        for member in dict.fromkeys(others):
            if member != target and member in passed:
                return member
        return None
    return None


# ---------------------------------------------------------------------------
# Gate evaluation.


@dataclass(frozen=True)
class GateOutcome:
    spec: str
    routed: bool            # variant was rerouted to an assisted variant
    notes: tuple[str, ...]


def evaluate(request: Any, request_type: Any, manifest: Any,
             environment: dict[str, str]) -> tuple[Any, GateOutcome]:
    """Apply L0 gates. Returns (request, outcome); request may be rerouted to
    an assisted variant. Raises StaticGateRefusal for admission/license."""
    task, version = request.task, request.version
    notes: list[str] = []

    doc_version, digest = fixture_doc_version(fixture_path_for(task.name, version, environment))
    if INSTALL_LICENSE_TIER.get(version) == "free" and digest in FREE_LIMIT_EXCEEDED_FIXTURES:
        # Currently unreachable: no install is proven FREE-locked, and the
        # FREE parameter limit is a non-blocking notice. Kept so a genuinely
        # observed FREE-locked install can be refused by adding it to the table.
        raise StaticGateRefusal(
            "SKIP_LICENSE",
            "license",
            f"{request.spec}: FREE installation on Cubism {dotted(version)} cancels "
            "fixtures exceeding the per-object parameter limit",
        )

    missing = [
        capability
        for capability in TASK_REQUIRED_CAPABILITIES.get(task.name, ())
        if capability not in load_verified_capabilities(version)[0]
    ]
    if missing:
        raise StaticGateRefusal(
            "SKIP_STATIC",
            "admission",
            f"{request.spec}: no VERIFIED_STATIC evidence for {', '.join(missing)} "
            f"on Cubism {dotted(version)}; the runtime admission wall will refuse this write path",
        )
    routed = False
    if doc_version and _semver_tuple(doc_version) > _semver_tuple(dotted(version)):
        assisted = assisted_variant(task, request.variant)
        if assisted and request.variant != assisted:
            request = request_type(task, version, assisted, request.run_label)
            routed = True
            notes.append(
                f"fixture-version-routing: fixture document {doc_version} > target "
                f"{dotted(version)}; routed to @{assisted} (NativeStartupAssistanceAgent)"
            )
        elif not assisted:
            notes.append(
                f"fixture-version-routing: fixture document {doc_version} > target "
                f"{dotted(version)} but no assisted variant exists; the native "
                "newer-file dialog may block this run"
            )
    return request, GateOutcome(request.spec, routed, tuple(notes))


# ---------------------------------------------------------------------------
# Read-only bundle audit CLI:
#   host_validation_static_gates.py bundle-check <bundle-dir> [more dirs...]
# Links every bundle jar against its turboism-agent.jar and prints one verdict
# per directory; exit 1 when any bundle cannot link.


def _audit_main(argv: list[str]) -> int:
    if len(argv) < 2 or argv[0] != "bundle-check":
        print("usage: host_validation_static_gates.py bundle-check <bundle-dir> [...]",
              file=sys.stderr)
        return 2
    failures = 0
    for name in argv[1:]:
        bundle = Path(name)
        if not bundle.is_dir():
            print(f"{bundle}: SKIP (not a directory)")
            continue
        linked, details = audit_bundle_dir(bundle)
        status = "LINKED" if linked else "STALE"
        print(f"{bundle}: {status}")
        for detail in details:
            print(f"  {detail}")
        failures += 0 if linked else 1
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(_audit_main(sys.argv[1:]))
