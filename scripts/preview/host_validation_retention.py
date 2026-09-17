"""Retention for the local validation queue; default inspection never opens Store.

No host is launched or signalled here. Production roots cannot be overridden by
CLI/environment. Tests inject roots, a clock and a harmless busy probe in Python.
"""
from __future__ import annotations

import contextlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import sqlite3
import stat
import tarfile
import tempfile
import time
from typing import Any, Iterator

import host_validation_queue as queue
from host_validation_evidence import checked_path, layout

DAY = 86400
DEFAULT_POLICY = {"schemaVersion": 1, "enabled": False, "writersMigrated": False,
                  "successDays": 3, "failureDays": 14, "preparedDays": 7,
                  "stagingHours": 24, "logDays": 30, "minFreeGiB": 20, "maxItems": 10}
KINDS = {"prefix", "environment", "logs", "prepared", "staging"}


def read_json(path: Path) -> Any:
    checked_path(path)
    if path.stat().st_uid != os.getuid() or not path.is_file():
        raise queue.QueueError(f"unsafe metadata: {path}")
    with path.open() as stream:
        return json.load(stream)


def policy(root: Path) -> dict[str, Any]:
    path = root / "retention-policy.json"
    result = dict(DEFAULT_POLICY)
    if path.exists() or path.is_symlink():
        given = read_json(path)
        if not isinstance(given, dict) or set(given) - set(result):
            raise queue.QueueError("unknown retention policy fields")
        result.update(given)
    if type(result["schemaVersion"]) is not int or result["schemaVersion"] != 1:
        raise queue.QueueError("unsupported retention policy schema")
    for field in ("enabled", "writersMigrated"):
        if type(result[field]) is not bool:
            raise queue.QueueError(f"{field} must be boolean")
    for field in ("successDays", "failureDays", "preparedDays", "stagingHours", "logDays", "minFreeGiB"):
        if type(result[field]) not in (int, float) or not math.isfinite(result[field]) or result[field] <= 0:
            raise queue.QueueError(f"{field} must be finite and positive")
    if result["logDays"] < max(result["successDays"], result["failureDays"]):
        raise queue.QueueError("log retention must cover environment retention")
    if type(result["maxItems"]) is not int or not 1 <= result["maxItems"] <= 100:
        raise queue.QueueError("maxItems must be 1..100")
    return result


def check_space(root: Path, extra_paths: tuple[Path, ...] = (), *, probe=shutil.disk_usage) -> None:
    minimum = policy(root)["minFreeGiB"] * 1024 ** 3
    for path in (root, *extra_paths):
        checked_path(path)
        while not path.exists():
            path = path.parent
        if probe(path).free < minimum:
            raise queue.QueueError(f"insufficient free space at {path}; need {minimum / 1024**3:g} GiB; no protected data was removed")


def root_identity(root: Path) -> list[int]:
    checked_path(root)
    info = root.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise queue.QueueError("retention state root must be private and owned by current UID")
    return [info.st_dev, info.st_ino]


@contextlib.contextmanager
def copied_database(database: Path):
    """Read a stable DB/WAL pair without SQLite creating live -shm/-wal files.

    A writer/checkpoint racing the copy causes bounded retry, never an immutable
    read of a live database (which would silently ignore committed WAL records).
    """
    names = [database, database.with_name(database.name + "-wal")]
    def signature(path):
        checked_path(path)
        try:
            info = path.stat()
        except FileNotFoundError:
            return None
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            raise queue.QueueError("unsafe queue database/WAL")
        return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
    if database.with_name(database.name + "-journal").exists():
        raise queue.QueueError("queue rollback journal present; retry after writer finishes")
    with tempfile.TemporaryDirectory(prefix="turboism-retention-read-") as temporary:
        destination = Path(temporary) / database.name
        for attempt in range(3):
            before = [signature(path) for path in names]
            if before[0] is None:
                raise queue.QueueError("queue database missing")
            try:
                for path, identity in zip(names, before):
                    target = Path(temporary) / path.name
                    if identity is None:
                        target.unlink(missing_ok=True)
                        continue
                    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
                    with os.fdopen(fd, "rb") as source, target.open("wb") as output:
                        shutil.copyfileobj(source, output)
                if before != [signature(path) for path in names]:
                    continue
            except FileNotFoundError:
                continue
            yield destination
            return
        raise queue.QueueError("queue changed during read-only snapshot; retry")


class Snapshot:
    """A consistent read-only SQLite backup; no schema migration or lock-file creation."""
    validate_completion = staticmethod(queue.Store.validate_completion)

    def __init__(self, root: Path):
        self.root = root
        self.identity = root_identity(root)
        database = checked_path(root / "queue.sqlite3")
        if database.stat().st_uid != os.getuid():
            raise queue.QueueError("queue owner mismatch")
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        try:
            with copied_database(database) as copied:
                with contextlib.closing(sqlite3.connect(copied)) as source:
                    source.backup(self.db)
            if self.db.execute("SELECT version FROM metadata").fetchone()[0] != queue.SCHEMA:
                raise queue.QueueError("unsupported queue schema")
            self.job_rows = [dict(row) for row in self.db.execute("SELECT * FROM jobs ORDER BY sequence")]
            self.host_row = dict(self.db.execute("SELECT * FROM host WHERE singleton=1").fetchone())
            tables = {row[0] for row in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            self.objects = {(row["kind"], row["object_id"]): dict(row) for row in
                            self.db.execute("SELECT * FROM retention_objects")} if "retention_objects" in tables else {}
        except (sqlite3.Error, TypeError, KeyError, IndexError) as failure:
            raise queue.QueueError(f"cannot read a consistent queue snapshot: {failure}") from failure
        finally:
            self.db.close()

    def jobs(self, job_id=None):
        return [row for row in self.job_rows if job_id is None or row["job_id"] == job_id]


def describe(snapshot: Snapshot, prepared_id: str) -> dict[str, Any]:
    if not re.fullmatch(r"[a-f0-9]{64}", prepared_id):
        raise queue.QueueError("invalid prepared identity")
    path = snapshot.root / "prepared" / prepared_id / "prepared.json"
    retirement = snapshot.root / "retention-prepared" / (prepared_id + ".json")
    if retirement.exists() or retirement.is_symlink():
        descriptor = read_json(retirement)["descriptor"]
    elif path.exists() or path.is_symlink():
        descriptor = read_json(path)
    else:
        record = snapshot.objects.get(("prepared", prepared_id), {})
        descriptor = json.loads(record.get("metadata", "{}" )).get("descriptor")
    if not isinstance(descriptor, dict):
        raise queue.QueueError(f"prepared descriptor missing: {prepared_id}")
    body = dict(descriptor)
    if body.pop("digest", None) != prepared_id or queue.digest_json(body) != prepared_id:
        raise queue.QueueError("prepared descriptor digest mismatch")
    return descriptor


def safe_outcome(snapshot: Snapshot, job: dict[str, Any]) -> dict[str, Any]:
    if not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", job["job_id"]):
        raise queue.QueueError("invalid job identity")
    if job["state"] not in queue.TERMINAL:
        raise queue.QueueError("job is not terminal")
    if job["state"] == "cancelled" and job["attempt_id"] is None and job["run_id"] is None:
        return {"noHostSideEffects": True}
    directory = checked_path(snapshot.root / "jobs" / job["job_id"])
    # Reject links before the existing final-verdict reader opens any record.
    for name in ("outcome.json", "evidence/lifecycle-result.json", "containment.json", "runner-identity.json"):
        checked_path(directory / name)
    outcome = queue.durable_outcome(snapshot, job)
    if json.loads(job["evidence_json"] or "null") != outcome or outcome.get("terminalState") != job["state"]:
        raise queue.QueueError("database and final verdict disagree")
    if job["identity_json"] and queue.identity_alive(json.loads(job["identity_json"])):
        raise queue.QueueError("supervisor remains active")
    if outcome.get("finalizedBy") != "contained-supervisor" and outcome.get("noHostSideEffects") is not True:
        raise queue.QueueError("no authoritative final containment proof")
    return outcome


def walk_manifest(path: Path, *, links: bool = True) -> dict[str, list[Any]]:
    """lstat only: never follow Wine links; disallow mount crossings/special files."""
    checked_path(path)
    # st_dev alone misses bind mounts of the same filesystem.
    for line in Path("/proc/self/mountinfo").read_text().splitlines():
        raw = line.split()[4]
        mount = Path(re.sub(r"\\([0-7]{3})", lambda match: chr(int(match[1], 8)), raw))
        if path == mount or path in mount.parents:
            raise queue.QueueError("artifact contains a mount boundary")
    base_device = path.stat().st_dev
    entries: dict[str, list[Any]] = {}
    stack = [(path, ".")]
    while stack:
        current, relative = stack.pop()
        info = current.lstat()
        if info.st_dev != base_device:
            raise queue.QueueError("artifact crosses a filesystem boundary")
        kind = stat.S_IFMT(info.st_mode)
        if kind not in (stat.S_IFDIR, stat.S_IFREG, stat.S_IFLNK) or (kind == stat.S_IFLNK and not links):
            raise queue.QueueError(f"unsupported artifact entry: {current}")
        entries[relative] = [kind, info.st_dev, info.st_ino, info.st_size,
                             info.st_mtime_ns, info.st_ctime_ns,
                             os.readlink(current) if kind == stat.S_IFLNK else ""]
        if kind == stat.S_IFDIR:
            # Recheck directory identity across listing; immutable task ownership is
            # separately required, and deletion reopens every component by fd.
            for child in sorted(current.iterdir(), reverse=True):
                stack.append((child, child.name if relative == "." else relative + "/" + child.name))
            after = current.lstat()
            if (info.st_dev, info.st_ino, info.st_mtime_ns, info.st_ctime_ns) != (
                    after.st_dev, after.st_ino, after.st_mtime_ns, after.st_ctime_ns):
                raise queue.QueueError("artifact changed while enumerating")
    return entries


def entry(kind: str, object_id: str, paths: list[Path], expires: float, context: dict[str, Any]) -> dict[str, Any]:
    manifests = {str(p): walk_manifest(p) for p in paths}
    size = sum(v[3] for tree in manifests.values() for v in tree.values() if v[0] == stat.S_IFREG)
    result = {"kind": kind, "id": object_id, "paths": [str(p) for p in paths], "expiresAt": expires,
              "context": context, "apparentBytes": size,
              "fileCount": sum(len(tree) for tree in manifests.values()), "manifests": manifests}
    result["fingerprint"] = queue.digest_json(result)
    return result


def protected_source_paths(snapshot: Snapshot) -> list[Path]:
    paths = [snapshot.root]
    for prepared in sorted({j["prepared_id"] for j in snapshot.job_rows} |
                           {key[1] for key in snapshot.objects if key[0] == "prepared"}):
        descriptor = describe(snapshot, prepared)
        paths.extend(Path(i["source"]) for i in descriptor["sourceInputs"])
        paths.extend(Path(i["path"]) for i in descriptor["hostDependencies"])
        paths.append(Path(descriptor["source"]["worktree"]))
        argv = descriptor["argv"]
        if "--golden-prefix" in argv:
            paths.append(Path(argv[argv.index("--golden-prefix") + 1]))
    return paths


def overlap(left: Path, right: Path) -> bool:
    return left == right or left in right.parents or right in left.parents


def task_layout(snapshot: Snapshot, job: dict[str, Any], descriptor: dict[str, Any], outcome: dict[str, Any]):
    paths = layout(descriptor, snapshot.root / "prepared" / job["prepared_id"], job)
    task = paths["task"]
    if outcome.get("details", {}).get("taskDir") != str(task):
        raise queue.QueueError("canonical task ownership mismatch")
    # Canonical run nesting plus creation/finish inode binding; never infer from mtime.
    record = snapshot.objects.get(("job", job["job_id"]), {})
    binding = json.loads(record.get("metadata", "{}"))
    info = task.stat()
    if binding.get("taskIdentity") != [info.st_dev, info.st_ino] or binding.get("taskDir") != str(task):
        raise queue.QueueError("task directory is unbound or has been replaced")
    if info.st_uid != os.getuid():
        raise queue.QueueError("task owner mismatch")
    return paths


def plan(root: Path | None = None, *, now: float | None = None) -> dict[str, Any]:
    root = queue.account_root() if root is None else root
    now = time.time() if now is None else now
    if not math.isfinite(now):
        raise queue.QueueError("invalid clock")
    snapshot = Snapshot(root)
    rules = policy(root)
    candidates, retained = [], []
    global_reasons = []
    if snapshot.host_row["state"] != "idle" or any(j["state"] in queue.ACTIVE for j in snapshot.job_rows):
        global_reasons.append("host is active, quarantined or not proven idle")
    if any(j["state"] == "queued" for j in snapshot.job_rows):
        global_reasons.append("queued host work takes priority over collection")
    try:
        protected_paths = protected_source_paths(snapshot)
    except (OSError, ValueError, KeyError, TypeError, queue.QueueError) as failure:
        protected_paths = []
        global_reasons.append(f"cannot establish all protected input paths: {failure}")
    safe_jobs, descriptors = {}, {}
    for job in snapshot.job_rows:
        jid = job["job_id"]
        record = snapshot.objects.get(("job", jid))
        try:
            if record is None:
                raise queue.QueueError("historical job is not adopted")
            if record["pin"]:
                raise queue.QueueError("pinned: " + record["pin"])
            if json.loads(record["metadata"]).get("unadopted"):
                raise queue.QueueError("historical job is not adopted")
            descriptor = describe(snapshot, job["prepared_id"])
            if "--keep-prefix" in descriptor["argv"] and not json.loads(record["metadata"]).get("keepPrefixReleased"):
                raise queue.QueueError("explicit --keep-prefix hold")
            outcome = safe_outcome(snapshot, job)
            safe_jobs[jid] = outcome
            descriptors[job["prepared_id"]] = descriptor
            expires = job["updated_at"] + DAY * rules["successDays" if job["state"] == "succeeded" else "failureDays"]
            if outcome.get("noHostSideEffects"):
                continue
            paths = task_layout(snapshot, job, descriptor, outcome)
            if any(overlap(paths["task"], p) for p in protected_paths):
                raise queue.QueueError("task overlaps protected input/worktree/state")
            # Distinct tasks must not share or nest their runtime environments.
            for other in snapshot.job_rows:
                if other["job_id"] == jid or not other["run_id"]:
                    continue
                other_descriptor = describe(snapshot, other["prepared_id"])
                other_task = layout(other_descriptor, root / "prepared" / other["prepared_id"], other)["task"]
                if overlap(paths["task"], other_task):
                    raise queue.QueueError("task overlaps another run")
            context = {"job": jid, "prepared": job["prepared_id"], "verdict": queue.digest_json(outcome),
                       "binding": json.loads(record["metadata"]),
                       "preserveRuntimeLogs": "--result-marker" in descriptor["argv"],
                       "ordinaryFixture": {"name": paths["fixture"].relative_to(paths["task"]).as_posix(),
                           "sha256": outcome.get("details", {}).get("postContainmentChecks", {}).get("fixtureCopySha256")
                           if outcome.get("fixtureUnchanged") is True else None},
                       "coreResult": ("turboism-home/" + descriptor["argv"][descriptor["argv"].index("--result-file") + 1]
                                      if "--result-file" in descriptor["argv"] else None)}
            if now < expires:
                retained.append({"kind": "job", "id": jid, "expiresAt": expires, "reason": "diagnostic retention"})
            else:
                if paths["prefix"].exists() or paths["prefix"].is_symlink():
                    candidates.append(entry("prefix", jid, [paths["prefix"]], expires, context))
                payload = sorted(p for p in paths["task"].iterdir() if p.name not in {"prefix", "evidence"})
                if payload:
                    candidates.append(entry("environment", jid, payload, expires, context))
            log_expiry = job["updated_at"] + DAY * rules["logDays"]
            if now >= log_expiry and not paths["prefix"].exists() and not any(p.name not in {"prefix", "evidence"} for p in paths["task"].iterdir()):
                # Only known pure log artifacts; mixed state/evidence archives remain.
                directory = root / "jobs" / jid
                logs = [p for p in (directory / "runner.log", directory / "retention-archive/logs.tar.gz") if p.exists()]
                if logs:
                    verify_core_archive(root, jid, context["verdict"])
                    candidates.append(entry("logs", jid, logs, log_expiry, context))
        except (OSError, ValueError, KeyError, TypeError, queue.QueueError) as failure:
            safe_jobs.pop(jid, None)
            retained.append({"kind": "job", "id": jid, "reason": str(failure)})
    for (kind, object_id), record in sorted(snapshot.objects.items()):
        if kind not in {"prepared", "staging"} or record["retired"]:
            continue
        path = root / kind / object_id
        try:
            if not path.exists() and not path.is_symlink():
                continue
            if not re.fullmatch(r"[a-f0-9]{64}" if kind == "prepared" else r"[A-Za-z0-9_-]+", object_id):
                raise queue.QueueError("invalid artifact identity")
            if record["pin"]:
                raise queue.QueueError("pinned: " + record["pin"])
            metadata = json.loads(record["metadata"])
            info = path.stat()
            if [info.st_dev, info.st_ino] != [metadata.get("device"), metadata.get("inode")]:
                raise queue.QueueError("artifact directory was replaced")
            references = [j for j in snapshot.job_rows if j["prepared_id"] == object_id] if kind == "prepared" else []
            if kind == "prepared":
                if any(j["job_id"] not in safe_jobs for j in references):
                    raise queue.QueueError("input has active, pinned, historical or unverified references")
                if references:
                    expires = max(j["updated_at"] + DAY * rules["successDays" if j["state"] == "succeeded" else "failureDays"] for j in references)
                else:
                    expires = record["created_at"] + DAY * rules["preparedDays"]
                descriptor = describe(snapshot, object_id)
                # Exclude the state-root ancestor itself, but protect sources which
                # are *inside* another captured input tree.
                if any(overlap(path, p) for p in protected_paths if p != root):
                    raise queue.QueueError("prepared directory is a protected source")
                context = {"descriptor": descriptor, "references": [j["job_id"] for j in references]}
            else:
                owner = metadata.get("owner")
                if (not isinstance(owner, dict) or set(owner) != {"bootId", "pid", "startTicks", "uid"}
                        or type(owner["uid"]) is not int or owner["uid"] != os.getuid()
                        or type(owner["pid"]) is not int or owner["pid"] <= 0
                        or type(owner["startTicks"]) is not int or owner["startTicks"] < 0
                        or not isinstance(owner["bootId"], str) or not owner["bootId"]):
                    raise queue.QueueError("staging creator identity unknown")
                if queue.identity_alive(owner):
                    raise queue.QueueError("staging creator still active")
                expires = record["created_at"] + rules["stagingHours"] * 3600
                context = {"owner": owner}
            if now < expires:
                raise queue.QueueError("retention period not elapsed")
            if kind == "prepared":
                retirement = root / "retention-prepared" / (object_id + ".json")
                if retirement.exists():
                    original = read_json(retirement)
                    # A crash may have removed a subset. Never accept new/replaced
                    # content; directory timestamps alone change during deletion.
                    for name, actual in walk_manifest(path).items():
                        expected = original["manifest"].get(name)
                        if expected is None or (actual[:3] != expected[:3] if actual[0] == stat.S_IFDIR else actual != expected):
                            raise queue.QueueError("retiring input contains changed/new entries")
                else:
                    queue.PreparedStore(snapshot).load(object_id)
            candidates.append(entry(kind, object_id, [path], expires, context))
        except (OSError, ValueError, KeyError, TypeError, queue.QueueError) as failure:
            retained.append({"kind": kind, "id": object_id, "reason": str(failure)})
    for kind in ("prepared", "staging"):
        base = checked_path(root / kind)
        if base.exists():
            for path in sorted(base.iterdir()):
                if (kind, path.name) not in snapshot.objects:
                    retained.append({"kind": kind, "id": path.name, "reason": "historical artifact not registered"})
    result = {"schemaVersion": 1, "root": str(root), "rootIdentity": snapshot.identity,
              "generatedAt": now, "policy": rules, "blocked": global_reasons,
              "candidates": candidates, "retained": retained,
              "sizeNote": "apparentBytes includes shared CoW data; reclaimable physical bytes are unknown"}
    result["planDigest"] = queue.digest_json(result)
    return result


@contextlib.contextmanager
def parent_fd(path: Path) -> Iterator[tuple[int, str]]:
    checked_path(path)
    fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parts[1:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
        yield fd, path.name
    finally:
        os.close(fd)


def remove_manifest(path: Path, manifest: dict[str, list[Any]]) -> None:
    """fd-relative removal, never shutil traversal through an ancestor or Wine link."""
    def remove(fd: int, name: str, relative: str):
        expected = manifest[relative]
        info = os.stat(name, dir_fd=fd, follow_symlinks=False)
        current = [stat.S_IFMT(info.st_mode), info.st_dev, info.st_ino, info.st_size,
                   info.st_mtime_ns, info.st_ctime_ns]
        if current != expected[:6]:
            raise queue.QueueError("artifact changed before removal")
        if stat.S_ISDIR(info.st_mode):
            child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            try:
                bound = os.fstat(child)
                if (bound.st_dev, bound.st_ino) != (info.st_dev, info.st_ino):
                    raise queue.QueueError("directory replaced while opening")
                for item in sorted(os.listdir(child)):
                    key = item if relative == "." else relative + "/" + item
                    if key not in manifest:
                        raise queue.QueueError("unexpected artifact entry")
                    remove(child, item, key)
                if os.listdir(child):
                    raise queue.QueueError("artifact received late entries")
                again = os.stat(name, dir_fd=fd, follow_symlinks=False)
                if (again.st_dev, again.st_ino) != (bound.st_dev, bound.st_ino):
                    raise queue.QueueError("directory moved during removal")
                os.rmdir(name, dir_fd=fd)
            finally:
                os.close(child)
        else:
            os.unlink(name, dir_fd=fd)
    with parent_fd(path) as (fd, name):
        remove(fd, name, ".")
        os.fsync(fd)


def _tar_files(paths: list[Path], base: Path, archive: Path, *, logs: bool,
               preserve_runtime_logs: bool = False, core_result: str | None = None,
               omitted: dict[str, str] | None = None) -> None:
    with tarfile.open(archive, "w:gz", dereference=False) as tar:
        for path in paths:
            manifest = walk_manifest(path, links=False)
            for relative, expected in sorted(manifest.items()):
                source = path if relative == "." else path / relative
                if expected[0] != stat.S_IFREG:
                    continue
                name = source.relative_to(base).as_posix()
                if omitted and name in omitted:
                    continue
                # File suffix is not semantics: a state/result.log can be core proof.
                # Marker-only runs need their runtime logs retained as original evidence.
                is_log = (name.startswith("turboism-home/logs/") and name != core_result
                          and not preserve_runtime_logs)
                if logs != is_log:
                    continue
                with parent_fd(source) as (fd, leaf):
                    handle = os.open(leaf, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=fd)
                    with os.fdopen(handle, "rb") as stream:
                        info = os.fstat(stream.fileno())
                        if [stat.S_IFMT(info.st_mode), info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns] != expected[:6]:
                            raise queue.QueueError("evidence changed during archival")
                        member = tarfile.TarInfo(name)
                        member.size, member.mode, member.mtime = info.st_size, 0o600, int(info.st_mtime)
                        tar.addfile(member, stream)
                        after = os.fstat(stream.fileno())
                        if (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns) != (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns):
                            raise queue.QueueError("evidence changed during archival")
    with archive.open("rb") as stream:
        os.fsync(stream.fileno())


def ensure_archive(root: Path, item: dict[str, Any]) -> dict[str, Any]:
    """Durable independently verified evidence before deleting any environment."""
    directory = checked_path(root / "jobs" / item["id"] / "retention-archive")
    queue.private_directory(directory)
    manifest_path = directory / "manifest.json"
    task = Path(item["context"]["binding"]["taskDir"])
    if manifest_path.exists():
        record = read_json(manifest_path)
        if record.get("job") != item["id"] or record.get("verdict") != item["context"]["verdict"]:
            raise queue.QueueError("archive belongs to another verdict")
        for path in sorted(task.iterdir()):
            if path.name == "prefix":
                continue
            original = record.get("sources", {}).get(str(path))
            if original is None:
                raise queue.QueueError("new task payload is not covered by retained archive")
            for name, actual in walk_manifest(path, links=False).items():
                expected = original.get(name)
                if expected is None or (actual[:3] != expected[:3] if actual[0] == stat.S_IFDIR else actual != expected):
                    raise queue.QueueError("changed task payload is not covered by retained archive")
        for name, digest in record["archives"].items():
            if name not in {"core.tar.gz", "logs.tar.gz"} or queue.file_digest(checked_path(directory / name)) != digest:
                raise queue.QueueError("retained archive hash mismatch")
        return record
    paths = [p for p in sorted(task.iterdir()) if p.name != "prefix"]
    # Retained records are small compared to prefixes; never archive the clone.
    sources = {str(p): walk_manifest(p, links=False) for p in paths}
    total = sum(value[3] for tree in sources.values() for value in tree.values() if value[0] == stat.S_IFREG)
    if shutil.disk_usage(directory).free < total + 64 * 1024**2:
        raise queue.QueueError("not enough space to preserve evidence before collection")
    omitted = {}
    fixture = item["context"].get("ordinaryFixture", {})
    for source_path, tree in sources.items():
        for relative, details in tree.items():
            if details[0] != stat.S_IFREG:
                continue
            source = Path(source_path) if relative == "." else Path(source_path) / relative
            name = source.relative_to(task).as_posix()
            if name == item["context"].get("coreResult") or name.startswith("evidence/"):
                continue
            if name == fixture.get("name") and isinstance(fixture.get("sha256"), str) and queue.file_digest(source) == fixture["sha256"]:
                omitted[name] = "unchanged canonical fixture; digest retained in final evidence"
            elif name.startswith(("agents/", "turboism-home/plugins/")) or name in {
                    "turboism-agent.jar", "launch.sh", "launch.bat", "archive-cubism-host-evidence.sh", "turboism-home/config.json"}:
                omitted[name] = "temporary deployment/configuration; prepared descriptor retained"
            elif name.startswith("turboism-home/state/dev.turboism.plugin.mcp/") and (
                    source.name == "mcp-connection.json" or source.name.startswith(("mcp-connection-", ".mcp-connection-"))):
                omitted[name] = "private connection credentials are not long-term evidence"
    hashes = {}
    for name, logs in (("core.tar.gz", False), ("logs.tar.gz", True)):
        temporary = directory / ("." + name + ".pending")
        # A prior interrupted archive is never considered validated. Rebuild from
        # source; no source deletion can precede manifest publication.
        if temporary.exists() or temporary.is_symlink():
            checked_path(temporary)
            temporary.unlink()
        _tar_files(paths, task, temporary, logs=logs,
                   preserve_runtime_logs=item["context"].get("preserveRuntimeLogs", False),
                   core_result=item["context"].get("coreResult"), omitted=omitted)
        with tarfile.open(temporary, "r:gz") as tar:
            for member in tar:
                if not member.isfile() or Path(member.name).is_absolute() or ".." in Path(member.name).parts:
                    raise queue.QueueError("unsafe evidence archive member")
                stream = tar.extractfile(member)
                while stream.read(1024 * 1024):
                    pass
        hashes[name] = queue.file_digest(temporary)
        os.replace(temporary, directory / name)
    if sources != {str(p): walk_manifest(p, links=False) for p in paths}:
        raise queue.QueueError("task evidence changed during archival")
    record = {"schemaVersion": 1, "job": item["id"], "verdict": item["context"]["verdict"], "archives": hashes, "sources": sources, "omitted": omitted}
    queue.atomic_json(manifest_path, record)
    return record


def verify_core_archive(root: Path, job_id: str, verdict: str) -> dict[str, Any]:
    directory = checked_path(root / "jobs" / job_id / "retention-archive")
    record = read_json(directory / "manifest.json")
    if record.get("job") != job_id or record.get("verdict") != verdict:
        raise queue.QueueError("core archive belongs to another verdict")
    if queue.file_digest(checked_path(directory / "core.tar.gz")) != record["archives"]["core.tar.gz"]:
        raise queue.QueueError("preserved core archive is corrupt; logs retained")
    logs = directory / "logs.tar.gz"
    if logs.exists() and queue.file_digest(checked_path(logs)) != record["archives"]["logs.tar.gz"]:
        raise queue.QueueError("preserved log archive is corrupt")
    return record


def mutate_hold(root: Path, action: str, job_id: str, reason: str) -> dict[str, Any]:
    if not reason.strip() or len(reason) > 2048:
        raise queue.QueueError("a bounded nonempty operator reason is required")
    store = queue.Store(root)
    with queue.storage_lock(root, exclusive=True):
        snapshot = Snapshot(root)
        rows = snapshot.jobs(job_id)
        if not rows:
            raise queue.QueueError("unknown job id")
        job = rows[0]
        record = snapshot.objects.get(("job", job_id))
        metadata = json.loads(record["metadata"]) if record else {}
        if action == "adopt":
            outcome = safe_outcome(snapshot, job)
            descriptor = describe(snapshot, job["prepared_id"])
            if not outcome.get("noHostSideEffects"):
                task = layout(descriptor, root / "prepared" / job["prepared_id"], job)["task"]
                if outcome.get("details", {}).get("taskDir") != str(task):
                    raise queue.QueueError("task path mismatch")
                if any(overlap(task, p) for p in protected_source_paths(snapshot)):
                    raise queue.QueueError("task overlaps a protected path")
                info = task.stat()
                metadata.update(taskDir=str(task), taskIdentity=[info.st_dev, info.st_ino])
        elif record is None:
            # A hold may protect an unadopted historical job without enabling GC.
            if action != "pin":
                raise queue.QueueError("job has not been adopted")
            metadata["unadopted"] = True
        if action == "adopt":
            metadata.pop("unadopted", None)
        if action == "unpin":
            metadata["keepPrefixReleased"] = True
        with store.transaction() as db:
            db.execute("""INSERT INTO retention_objects(kind,object_id,created_at,pin,metadata)
                       VALUES('job',?,?,?,?) ON CONFLICT(kind,object_id) DO UPDATE SET pin=excluded.pin,metadata=excluded.metadata""",
                       (job_id, time.time(), reason if action == "pin" else (record["pin"] if record and action == "adopt" else ""), queue.canonical_json(metadata)))
            if action == "adopt" and ("prepared", job["prepared_id"]) not in snapshot.objects:
                descriptor = queue.PreparedStore(store).load(job["prepared_id"])
                source = root / "prepared" / job["prepared_id"]
                info = source.stat()
                db.execute("INSERT INTO retention_objects(kind,object_id,created_at,metadata) VALUES('prepared',?,?,?)",
                           (job["prepared_id"], time.time(), queue.canonical_json({"device": info.st_dev, "inode": info.st_ino})))
            store.event(db, job_id, "retention-" + action, reason=reason, operatorUid=os.getuid())
    return {"action": action, "job": job_id, "reason": reason}


def apply(report: dict[str, Any], approval: str, root: Path | None = None, *, now=None, busy=None) -> dict[str, Any]:
    root = queue.account_root() if root is None else root
    body = dict(report)
    digest = body.pop("planDigest", None)
    if not isinstance(approval, str) or approval != digest or queue.digest_json(body) != digest:
        raise queue.QueueError("approval does not match the exact collection plan")
    if report.get("schemaVersion") != 1 or report.get("root") != str(root) or report.get("rootIdentity") != root_identity(root):
        raise queue.QueueError("collection plan root identity mismatch")
    if not policy(root)["writersMigrated"]:
        raise queue.QueueError("retire old queue writers and confirm writersMigrated before collection")
    store = queue.Store(root)
    if busy is None:
        busy = queue.RunnerBackend().busy
    result = {"planDigest": digest, "removed": [], "skipped": [], "errors": []}
    with queue.storage_lock(root, exclusive=True), queue.FileLock(root / "admission.lock"):
        # Admission excludes managed launches, busy() conservatively detects external
        # sessions. Never acquire ownership of or signal an external process.
        if busy():
            raise queue.QueueBusy("external host session; collection skipped")
        current = plan(root, now=now)
        if current["blocked"]:
            raise queue.QueueBusy("; ".join(current["blocked"]))
        if current["policy"] != report["policy"]:
            raise queue.QueueError("retention policy changed since approval")
        available = {(i["kind"], i["id"]): i for i in current["candidates"]}
        for requested in report["candidates"][:current["policy"]["maxItems"]]:
            key = (requested["kind"], requested["id"])
            item = available.get(key)
            if item is None or item["fingerprint"] != requested["fingerprint"]:
                result["skipped"].append({"kind": key[0], "id": key[1], "reason": "changed, retained or already collected"})
                continue
            try:
                archive = ensure_archive(root, item) if item["kind"] in {"prefix", "environment"} else None
                if item["kind"] == "logs":
                    archive = verify_core_archive(root, item["id"], item["context"]["verdict"])
                receipts = queue.private_directory(root / "retention-receipts")
                receipt_path = receipts / (item["fingerprint"] + ".json")
                receipt = {"schemaVersion": 1, "planDigest": digest,
                           "item": {key: value for key, value in item.items() if key not in {"manifests", "context"}},
                           "archive": archive, "state": "intent"}
                # Audit survives a transaction rollback or process crash mid-delete.
                queue.atomic_json(receipt_path, receipt)
                if item["kind"] == "prepared":
                    retired = queue.private_directory(root / "retention-prepared")
                    marker = retired / (item["id"] + ".json")
                    if not marker.exists():
                        queue.atomic_json(marker, {"descriptor": item["context"]["descriptor"],
                            "manifest": item["manifests"][item["paths"][0]], "planDigest": digest})
                with store.transaction() as db:
                    # SQLite blocks old submit/state writers during final recheck/removal.
                    host = db.execute("SELECT state FROM host WHERE singleton=1").fetchone()[0]
                    active = db.execute("SELECT 1 FROM jobs WHERE state IN ('starting','running','cleaning','recovering','quarantined') LIMIT 1").fetchone()
                    if host != "idle" or active:
                        raise queue.QueueError("host state changed before removal")
                    for path in item["paths"]:
                        if walk_manifest(Path(path)) != item["manifests"][path]:
                            raise queue.QueueError("artifact changed since approved inventory")
                    if item["kind"] == "prepared":
                        db.execute("UPDATE retention_objects SET retired=1,metadata=? WHERE kind='prepared' AND object_id=?",
                                   (queue.canonical_json({"descriptor": item["context"]["descriptor"]}), item["id"]))
                    for path in item["paths"]:
                        remove_manifest(Path(path), item["manifests"][path])
                    if item["kind"] == "staging":
                        db.execute("UPDATE retention_objects SET retired=1 WHERE kind='staging' AND object_id=?", (item["id"],))
                    store.event(db, item["id"] if item["kind"] in {"prefix", "environment", "logs"} else None,
                                "retention-collected", artifactKind=item["kind"], objectId=item["id"], planDigest=digest)
                queue.atomic_json(receipt_path, {**receipt, "state": "complete"})
                result["removed"].append({"kind": item["kind"], "id": item["id"]})
            except (OSError, ValueError, KeyError, TypeError, tarfile.TarError, queue.QueueError) as failure:
                result["errors"].append({"kind": item["kind"], "id": item["id"], "reason": str(failure)})
                break  # Fail closed; leave the rest of this batch untouched.
    return result


def inventory(root: Path) -> dict[str, Any]:
    """Legacy discovery only. No adoption, path inference or deletion capability."""
    checked_path(root)
    found = []
    for base, directories, _ in os.walk(root, followlinks=False):
        for name in list(directories):
            path = Path(base) / name
            if path.is_symlink():
                directories.remove(name)
            elif name in {"prefix", "proton-prefix"}:
                info = path.stat()
                found.append({"path": str(path), "device": info.st_dev, "inode": info.st_ino,
                              "mtime": info.st_mtime, "reason": "legacy ownership requires individual review", "eligible": False})
                directories.remove(name)
            elif name in {"pfx", "turboism-home", "home", "evidence", "agents", "plugins", "artifacts", ".git"}:
                directories.remove(name)
    return {"schemaVersion": 1, "root": str(root), "legacyPrefixes": found,
            "count": len(found), "deletionAuthorized": False}


def add_parser(commands) -> None:
    gc = commands.add_parser("gc", help="inspect/collect expired managed validation artifacts")
    modes = gc.add_subparsers(dest="gc_command", required=True)
    modes.add_parser("plan", help="read-only JSON plan; no registration, migration or deletion")
    execute = modes.add_parser("apply", help="recheck and apply an explicitly approved JSON plan")
    execute.add_argument("--plan", type=Path, required=True)
    execute.add_argument("--approve", required=True, help="exact planDigest approved by the operator")
    for name in ("pin", "unpin", "adopt"):
        command = modes.add_parser(name)
        command.add_argument("job")
        command.add_argument("--reason", required=True)
    modes.add_parser("run", help="scheduled report; deletes only when enabled and writersMigrated")
    legacy = modes.add_parser("inventory", help="read-only unowned legacy prefix inventory")
    legacy.add_argument("--legacy-root", type=Path, required=True)


def main(args, *, root: Path | None = None) -> int:
    root = queue.account_root() if root is None else root
    if args.gc_command == "inventory":
        result = inventory(args.legacy_root)
    elif args.gc_command in {"pin", "unpin", "adopt"}:
        result = mutate_hold(root, args.gc_command, args.job, args.reason)
    elif args.gc_command == "apply":
        result = apply(read_json(args.plan.absolute()), args.approve, root)
    else:
        result = plan(root)
        if args.gc_command == "run" and result["policy"]["enabled"]:
            if result["blocked"]:
                result = {"skipped": result["blocked"], "planDigest": result["planDigest"]}
            else:
                try:
                    result = apply(result, result["planDigest"], root)
                except queue.QueueBusy as contention:
                    result = {"skipped": [str(contention)], "planDigest": result["planDigest"]}
    if args.gc_command == "run" and "candidates" in result:
        # Scheduled dry runs must not create enormous journal entries from inode manifests.
        result = {**result, "candidates": [{key: value for key, value in item.items()
                   if key not in {"manifests", "context"}} for item in result["candidates"]]}
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 1 if result.get("errors") else 0
