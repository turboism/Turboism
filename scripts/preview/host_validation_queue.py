"""Local-only durable validation queue. No host launch occurs at import time.

The public entry point is host_validation.py. Explicit roots/backends are library
injection points for isolated tests, not production CLI/environment overrides.
"""
from __future__ import annotations

import contextlib
import fcntl
import errno
import hashlib
import json
import os
from pathlib import Path
import pwd
import sqlite3
import stat
import time
from typing import Any, Iterator
import uuid
import re
import shutil
import subprocess
import tempfile
import multiprocessing
import select
import signal
import socket

SCHEMA = 1
TERMINAL = frozenset({"succeeded", "failed", "cancelled", "timed_out", "blocked"})
ACTIVE = frozenset({"starting", "running", "cleaning", "recovering", "quarantined"})


class QueueError(RuntimeError):
    """A rejected operation; never permission to bypass admission."""


class NoHostSideEffects(QueueError):
    """A verified read-only preflight failed before any contained process start."""


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def digest_json(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode()).hexdigest()


def account_root() -> Path:
    # HOME/XDG/cwd must not split one user's host into independent lock domains.
    return Path(pwd.getpwuid(os.getuid()).pw_dir) / ".local/state/turboism/host-validation"


def private_directory(path: Path) -> Path:
    path = path.absolute()
    for part in reversed((path, *path.parents)):
        if part.is_symlink():
            raise QueueError(f"symlink in queue directory: {part}")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.stat()
    if info.st_uid != os.getuid() or not stat.S_ISDIR(info.st_mode):
        raise QueueError("queue directory must be owned by the current user")
    if stat.S_IMODE(info.st_mode) & 0o077:
        raise QueueError("queue directory must not be accessible to other users")
    return path


def atomic_json(path: Path, value: Any) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(canonical_json(value) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        temporary.unlink(missing_ok=True)


def process_identity(pid: int) -> dict[str, Any] | None:
    try:
        base = Path("/proc") / str(pid)
        fields = (base / "stat").read_text().rsplit(")", 1)[1].split()
        if fields[0] == "Z":
            return None
        return {"pid": pid, "startTicks": int(fields[19]),
                "bootId": Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                "uid": base.stat().st_uid}
    except (FileNotFoundError, ProcessLookupError):
        return None


def identity_alive(identity: dict[str, Any]) -> bool:
    return process_identity(identity["pid"]) == identity


class FileLock:
    def __init__(self, path: Path):
        self.path = path
        self.fd: int | None = None

    def acquire(self) -> "FileLock":
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            os.close(fd)
            raise QueueError("unsafe admission lock")
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as failure:
            os.close(fd)
            raise QueueError(f"already owned: {self.path.name}") from failure
        self.fd = fd
        return self

    def close(self) -> None:
        if self.fd is not None:
            # Do not LOCK_UN: the supervisor may still hold an inherited copy.
            os.close(self.fd)
            self.fd = None

    def __enter__(self) -> "FileLock":
        return self.acquire()

    def __exit__(self, *_: Any) -> None:
        self.close()


class Store:
    def __init__(self, root: Path | None = None):
        self.root = private_directory(account_root() if root is None else root)
        for child in ("prepared", "jobs", "staging"):
            private_directory(self.root / child)
        self.database = self.root / "queue.sqlite3"
        if self.database.is_symlink():
            raise QueueError("queue database must not be a symlink")
        fd = os.open(self.database, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        os.close(fd)
        with self.connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS metadata(version INTEGER NOT NULL);
                INSERT INTO metadata SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM metadata);
                CREATE TABLE IF NOT EXISTS jobs(
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id TEXT NOT NULL UNIQUE, request_key TEXT NOT NULL UNIQUE,
                    prepared_id TEXT NOT NULL, digest TEXT NOT NULL,
                    timeout_seconds INTEGER NOT NULL, state TEXT NOT NULL,
                    reason TEXT NOT NULL DEFAULT '', created_at REAL NOT NULL,
                    updated_at REAL NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
                    attempt_id TEXT UNIQUE, run_id TEXT UNIQUE,
                    identity_json TEXT, evidence_json TEXT);
                CREATE TABLE IF NOT EXISTS host(
                    singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                    state TEXT NOT NULL, job_id TEXT, reason TEXT NOT NULL);
                INSERT OR IGNORE INTO host VALUES(1, 'idle', NULL, '');
                CREATE TABLE IF NOT EXISTS events(
                    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id TEXT, kind TEXT NOT NULL, payload TEXT NOT NULL,
                    created_at REAL NOT NULL);
            """)
            if db.execute("SELECT version FROM metadata").fetchone()[0] != SCHEMA:
                raise QueueError("unsupported queue schema; migration required")

    @contextlib.contextmanager
    def connection(self) -> Iterator[sqlite3.Connection]:
        db = sqlite3.connect(self.database, timeout=30, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA synchronous=FULL")
        try:
            yield db
        finally:
            db.close()

    @contextlib.contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                yield db
                db.execute("COMMIT")
            except BaseException:
                db.execute("ROLLBACK")
                raise

    @staticmethod
    def event(db: sqlite3.Connection, job: str | None, kind: str, **payload: Any) -> None:
        db.execute("INSERT INTO events(job_id,kind,payload,created_at) VALUES(?,?,?,?)",
                   (job, kind, canonical_json(payload), time.time()))

    def submit(self, prepared_id: str, digest: str, request_key: str,
               timeout_seconds: int = 1800) -> dict[str, Any]:
        if not request_key or len(request_key) > 128 or "\0" in request_key:
            raise QueueError("request id must contain 1..128 non-NUL characters")
        if type(timeout_seconds) is not int or not 1 <= timeout_seconds <= 86400:
            raise QueueError("timeout must be an integer in 1..86400 seconds")
        now = time.time()
        with self.transaction() as db:
            existing = db.execute("SELECT * FROM jobs WHERE request_key=?", (request_key,)).fetchone()
            if existing:
                if (existing["prepared_id"], existing["digest"], existing["timeout_seconds"]) != (
                        prepared_id, digest, timeout_seconds):
                    raise QueueError("idempotency key conflicts with different inputs")
                return dict(existing)
            job = str(uuid.uuid4())
            db.execute("""INSERT INTO jobs(job_id,request_key,prepared_id,digest,timeout_seconds,
                       state,created_at,updated_at) VALUES(?,?,?,?,?,'queued',?,?)""",
                       (job, request_key, prepared_id, digest, timeout_seconds, now, now))
            self.event(db, job, "queued", preparedId=prepared_id)
            return dict(db.execute("SELECT * FROM jobs WHERE job_id=?", (job,)).fetchone())

    def jobs(self, job_id: str | None = None) -> list[dict[str, Any]]:
        with self.connection() as db:
            if job_id is None:
                rows = db.execute("SELECT * FROM jobs ORDER BY sequence").fetchall()
            else:
                rows = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchall()
                if not rows:
                    raise QueueError("unknown job id")
            return [dict(row) for row in rows]

    def host(self) -> dict[str, Any]:
        with self.connection() as db:
            return dict(db.execute("SELECT * FROM host WHERE singleton=1").fetchone())

    def external_busy(self, pids: list[int]) -> None:
        with self.transaction() as db:
            row = db.execute("SELECT state,reason FROM host WHERE singleton=1").fetchone()
            if row["state"] not in {"idle", "external-busy"}:
                return
            state = "external-busy" if pids else "idle"
            reason = "external Cubism session(s): " + ",".join(map(str, pids)) if pids else ""
            if (state, reason) != (row["state"], row["reason"]):
                db.execute("UPDATE host SET state=?,reason=? WHERE singleton=1", (state, reason))
                self.event(db, None, state, reason=reason)

    def claim(self) -> dict[str, Any] | None:
        with self.transaction() as db:
            host = db.execute("SELECT * FROM host WHERE singleton=1").fetchone()
            if host["state"] != "idle":
                return None
            job = db.execute("SELECT * FROM jobs WHERE state='queued' ORDER BY sequence LIMIT 1").fetchone()
            if job is None:
                return None
            attempt, run = str(uuid.uuid4()), "queue-" + uuid.uuid4().hex
            db.execute("UPDATE jobs SET state='starting',attempt_id=?,run_id=?,updated_at=? WHERE job_id=?",
                       (attempt, run, time.time(), job["job_id"]))
            db.execute("UPDATE host SET state='owned',job_id=?,reason='' WHERE singleton=1", (job["job_id"],))
            self.event(db, job["job_id"], "starting", attemptId=attempt, runId=run)
            return dict(db.execute("SELECT * FROM jobs WHERE job_id=?", (job["job_id"],)).fetchone())

    def acknowledge(self, job_id: str, attempt_id: str, identity: dict[str, Any]) -> None:
        if not identity_alive(identity) or identity["uid"] != os.getuid():
            raise QueueError("supervisor identity mismatch")
        with self.transaction() as db:
            result = db.execute("""UPDATE jobs SET state='running',identity_json=?,updated_at=?
                                WHERE job_id=? AND attempt_id=? AND state='starting'""",
                                (canonical_json(identity), time.time(), job_id, attempt_id))
            if result.rowcount != 1:
                raise QueueError("attempt handshake is no longer valid")
            self.event(db, job_id, "running", attemptId=attempt_id, identity=identity)

    def cancel(self, job_id: str) -> dict[str, Any]:
        with self.transaction() as db:
            row = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
            if row is None:
                raise QueueError("unknown job id")
            if row["state"] not in TERMINAL and not row["cancel_requested"]:
                state = "cancelled" if row["state"] == "queued" else row["state"]
                db.execute("UPDATE jobs SET state=?,cancel_requested=1,updated_at=? WHERE job_id=?",
                           (state, time.time(), job_id))
                self.event(db, job_id, "cancel-requested", state=state)
        return self.jobs(job_id)[0]

    def quarantine(self, job_id: str, reason: str) -> None:
        with self.transaction() as db:
            row = db.execute("SELECT state,reason FROM jobs WHERE job_id=?", (job_id,)).fetchone()
            if row is None or row["state"] not in ACTIVE:
                raise QueueError("cannot quarantine an unowned/terminal job")
            if row["state"] == "quarantined" and row["reason"] == reason:
                return
            db.execute("UPDATE jobs SET state='quarantined',reason=?,updated_at=? WHERE job_id=?",
                       (reason, time.time(), job_id))
            db.execute("UPDATE host SET state='quarantined',job_id=?,reason=? WHERE singleton=1",
                       (job_id, reason))
            self.event(db, job_id, "quarantined", reason=reason)

    @staticmethod
    def validate_completion(row: Any, state: str, evidence: dict[str, Any]) -> None:
        if not isinstance(evidence, dict) or type(evidence.get("schemaVersion")) is not int or evidence["schemaVersion"] != 1:
            raise QueueError("unsupported lifecycle evidence schema")
        if state not in TERMINAL:
            raise QueueError("invalid terminal state")
        expected = {"jobId": row["job_id"], "attemptId": row["attempt_id"],
                    "runId": row["run_id"], "preparedDigest": row["digest"]}
        if any(evidence.get(key) != value for key, value in expected.items()):
            raise QueueError("lifecycle evidence identity mismatch")
        if evidence.get("cleanup") != "safe":
            raise QueueError("cleanup is not proven safe")
        if state == "succeeded" and not (
            evidence.get("validationStatus") == "PASS" and
            evidence.get("identityVerified") is True and
            evidence.get("fixtureUnchanged") is True and
            evidence.get("normalExit") is True
        ):
            raise QueueError("PASS requires structured host, fixture and exit evidence")

    def complete(self, job_id: str, state: str, evidence: dict[str, Any],
                 *, recovery_reason: str | None = None) -> None:
        with self.transaction() as db:
            row = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
            if row is None or row["state"] not in ACTIVE:
                raise QueueError("completion requires a nonterminal attempt")
            self.validate_completion(row, state, evidence)
            if recovery_reason is not None:
                if not recovery_reason.strip():
                    raise QueueError("recovery confirmation requires an operator reason")
                self.event(db, job_id, "operator-recovery", reason=recovery_reason, operatorUid=os.getuid())
            db.execute("UPDATE jobs SET state=?,evidence_json=?,updated_at=? WHERE job_id=?",
                       (state, canonical_json(evidence), time.time(), job_id))
            db.execute("UPDATE host SET state='idle',job_id=NULL,reason='' WHERE singleton=1 AND job_id=?", (job_id,))
            self.event(db, job_id, state, evidence=evidence)

    def events(self, after: int = 0, job_id: str | None = None) -> list[dict[str, Any]]:
        with self.connection() as db:
            rows = db.execute("""SELECT * FROM events WHERE event_id>? AND (? IS NULL OR job_id=?)
                               ORDER BY event_id LIMIT 1000""", (after, job_id, job_id)).fetchall()
            return [{**dict(row), "payload": json.loads(row["payload"])} for row in rows]


INPUT_FLAGS = frozenset({"--bundle-root", "--agent", "--home-config", "--fixture-local",
    "--fixture-host", "--fixture-remote", "--remote-pre-launch", "--remote-post-launch",
    "--remote-pre-cleanup"})
COMPOSITE_FLAGS = frozenset({"--plugin", "--aux-agent", "--home-file", "--home-dir", "--client-script"})
BOOLEAN_FLAGS = frozenset({"--require-fixture-unchanged", "--keep-prefix",
    "--remote-pre-launch-background", "--remote-pre-launch-args-only"})
VALUE_FLAGS = frozenset({"--name", "--version", "--fixture-sha256", "--fixture-name",
    "--result-marker", "--result-file", "--result-pass-line", "--result-fail-line",
    "--ready-marker", "--failure-marker", "--trigger", "--jvm-option", "--windows-env",
    "--cubism-java", "--cubism-java-console-marker", "--run-label", "--agent-timeout",
    "--agent-host-class", "--ready-timeout", "--result-timeout", "--exit-timeout",
    "--poll-seconds", "--golden-prefix", "--host-root", "--remote-root", "--display",
    "--proton-wrapper", "--proton-runner", "--local-evidence-dir", "--transport",
    "--remote-pre-launch-arg"})


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


class PreparedStore:
    def __init__(self, store: Store):
        self.store = store

    def capture(self, request: dict[str, Any], source_root: Path,
                task_spec: str) -> dict[str, Any]:
        argv = request.get("argv")
        if request.get("schemaVersion") != SCHEMA or not isinstance(argv, list) or not argv:
            raise QueueError("invalid normalized runner request")
        if any(not isinstance(arg, str) or "\0" in arg or "@INPUT@" in arg for arg in argv):
            raise QueueError("invalid runner argument")
        # Normalized argv owns placement/defaults; ambient .env is never a dependency.
        if request.get("environment", {}) != {}:
            raise QueueError("normalized runner must express all configuration as argv")
        source_root = source_root.resolve(strict=True)
        with tempfile.TemporaryDirectory(dir=self.store.root / "staging") as temporary:
            stage = Path(temporary)
            tool_dir = stage / "tool/scripts/preview"
            tool_dir.mkdir(mode=0o700, parents=True)
            for path in sorted((source_root / "scripts/preview").iterdir()):
                if path.suffix in {".sh", ".py", ".json"} and path.is_file():
                    copy_verified(path, tool_dir / path.name)
            for required in ("run-cubism-host-validation.sh", "host-validation-env.sh", "host-validation-transport.sh",
                             "archive-cubism-host-evidence.sh", "host_validation.py", "host_validation_queue.py",
                             "host_validation_containment.py", "host_validation_evidence.py"):
                if not (tool_dir / required).is_file():
                    raise QueueError(f"missing canonical runner/helper: {required}")
            rendered: list[str] = []
            source_inputs: list[dict[str, Any]] = []
            host_dependencies: list[dict[str, str]] = []
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
                if flag == "--transport" and value != "local":
                    raise QueueError("only local host execution is supported")
                if flag == "--client-script":
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
                    # Only the catalogue FPS driver has an enumerated hook closure.
                    # A script's location in scripts/preview is not an approval.
                    if flag in {"--remote-pre-launch", "--remote-post-launch", "--remote-pre-cleanup"}:
                        if flag != "--remote-pre-launch" or source != source_root / "scripts/preview/fps-resize-driver.sh":
                            raise QueueError("custom hook requires reviewed dependency inventory")
                        if not {"--remote-pre-launch-background", "--remote-pre-launch-args-only"}.issubset(argv):
                            raise QueueError("FPS hook requires its reviewed background/args-only protocol")
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
            descriptor = {"schemaVersion": SCHEMA, "taskSpec": task_spec, "argv": rendered,
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


def wake(store: Store) -> None:
    with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as client:
        client.setblocking(False)
        try:
            client.sendto(b"wake", str(store.root / "wake.sock"))
        except (FileNotFoundError, ConnectionRefusedError, BlockingIOError):
            pass  # Durable state, not a datagram, is authoritative.


def external_sessions() -> list[int]:
    """Read-only conservative check; never kill or adopt an unknown session."""
    found = []
    for path in Path("/proc").iterdir():
        if not path.name.isdigit():
            continue
        try:
            command = (path / "cmdline").read_bytes().replace(b"\0", b" ")
            # Do not match tools merely discussing Cubism in their prompts.
            executable = (path / "comm").read_text().strip().lower()
            if ("java" in executable or "cubism" in executable) and any(
                text in command for text in (b"CubismEditor", b"CECubismEditorApp", b"Live2D_Cubism")
            ):
                found.append(int(path.name))
        except (FileNotFoundError, ProcessLookupError):
            continue
        except PermissionError as failure:
            raise QueueError("cannot inspect host processes safely") from failure
    return found


def scope_identity(raw: dict[str, Any]) -> dict[str, Any]:
    # Containment metadata also records gid and serializes kernel ticks as text.
    # Project only the queue's exact PID-lifetime identity fields.
    return {"pid": int(raw["pid"]), "startTicks": int(raw["startTicks"]),
            "bootId": raw["bootId"], "uid": int(raw["uid"])}


def snapshot_module(prepared_root: Path, name: str) -> Any:
    import importlib.util
    if name not in {"host_validation_containment", "host_validation_evidence"}:
        raise QueueError("unsupported supervisor helper")
    path = prepared_root / "tool/scripts/preview" / (name + ".py")
    specification = importlib.util.spec_from_file_location(name + "_snapshot", path)
    if specification is None or specification.loader is None:
        raise QueueError("missing prepared supervisor helper")
    module = importlib.util.module_from_spec(specification)
    # Loading a verified helper must not mutate its snapshot with __pycache__.
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


class RunnerBackend:
    """Production backend: only a verified prepared Runner on the fixed host."""
    def busy(self) -> list[int]:
        return external_sessions()

    def run(self, store: Store, job: dict[str, Any], admission_fd: int) -> dict[str, Any]:
        if store.root != account_root():
            raise QueueError("production backend cannot run in a test queue")
        directory = private_directory(store.root / "jobs" / job["job_id"])
        evidence_dir = directory / "evidence"
        try:
            command = PreparedStore(store).command(job["prepared_id"], evidence_dir)
        except (QueueError, OSError, ValueError, KeyError, TypeError) as failure:
            raise NoHostSideEffects(str(failure)) from failure
        injected_code = {"BASH_ENV", "ENV", "PYTHONPATH", "PYTHONHOME", "LD_PRELOAD", "LD_LIBRARY_PATH",
                         "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "SHELLOPTS", "BASHOPTS"}
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("TURBOISM_", "BASH_FUNC_")) and key not in injected_code}
        environment.update({"TURBOISM_QUEUE_JOB_ID": job["job_id"],
            "TURBOISM_QUEUE_ATTEMPT_ID": job["attempt_id"], "TURBOISM_QUEUE_RUN_ID": job["run_id"],
            "TURBOISM_QUEUE_ADMISSION_FD": str(admission_fd), "TURBOISM_ENV_FILE": "/dev/null",
            "TURBOISM_QUEUE_SUPERVISOR_PID": str(os.getpid()), "PYTHONDONTWRITEBYTECODE": "1"})
        prepared = PreparedStore(store)
        descriptor = prepared.load(job["prepared_id"])
        if descriptor["digest"] != job["digest"]:
            raise QueueError("job and prepared digest mismatch")
        prepared_root = store.root / "prepared" / job["prepared_id"]
        containment = snapshot_module(prepared_root, "host_validation_containment")
        finalizer = snapshot_module(prepared_root, "host_validation_evidence")
        expected = {"jobId": job["job_id"], "attemptId": job["attempt_id"],
                    "runId": job["run_id"], "preparedDigest": job["digest"]}
        deadline = time.monotonic() + job["timeout_seconds"]
        contained = None
        proof = None
        requested = None
        cleanup_started = None
        with (directory / "runner.log").open("ab", buffering=0) as log:
            try:
                contained = containment.start(command, directory=directory, environment=environment,
                    admission_fd=admission_fd, identity=expected, output=log)
                process = contained.process
                identity = scope_identity(contained.entry_identity)
                atomic_json(directory / "runner-identity.json", identity)
                heartbeat_at = 0.0
                while process.poll() is None:
                    now = time.monotonic()
                    if now >= heartbeat_at:
                        atomic_json(directory / "heartbeat.json", {
                            "schemaVersion": 1, **expected, "observedAt": time.time(),
                            "supervisor": process_identity(os.getpid()), "runner": identity,
                        })
                        heartbeat_at = now + 5.0  # Diagnostics, never an expiring safety lease.
                    cancel = store.jobs(job["job_id"])[0]["cancel_requested"]
                    if requested is None and (cancel or now >= deadline):
                        requested = "cancelled" if cancel else "timed_out"
                        cleanup_started = now
                        # Give this exact Runner a short chance to archive its phase
                        # evidence. The pinned cgroup handles, not this PID, own cleanup.
                        if hasattr(os, "pidfd_open") and hasattr(signal, "pidfd_send_signal"):
                            try:
                                pidfd = os.pidfd_open(identity["pid"])
                                try:
                                    if identity_alive(identity):
                                        signal.pidfd_send_signal(pidfd, signal.SIGTERM)
                                finally:
                                    os.close(pidfd)
                            except ProcessLookupError:
                                pass
                    if not identity_alive(identity) or (cleanup_started is not None and now >= cleanup_started + 5):
                        break
                    time.sleep(0.1)
                remaining = 30.0 if cleanup_started is None else max(0.1, 30.0 - (time.monotonic() - cleanup_started))
                proof = contained.finish(timeout_seconds=remaining)
                if proof.get("cleanup") != "safe":
                    raise QueueError("kernel containment cleanup is unknown; host quarantined")
                # Revalidate fixed snapshots after the contained processes are gone.
                descriptor = prepared.load(job["prepared_id"])
                path = evidence_dir / "lifecycle-result.json"
                preliminary = json.loads(path.read_text()) if path.is_file() else None
                if preliminary is not None and not isinstance(preliminary, dict):
                    raise QueueError("lifecycle evidence must be an object")
                if requested is None and store.jobs(job["job_id"])[0]["cancel_requested"]:
                    requested = "cancelled"
                return finalizer.finalize(job, descriptor, prepared_root, directory, preliminary,
                                          proof, process.returncode, requested)
            finally:
                if contained is not None:
                    try:
                        if proof is None:
                            contained.finish(timeout_seconds=30)
                    finally:
                        contained.close()


def supervised_attempt(root: Path, job: dict[str, Any], backend: Any, admission_fd: int,
                       worker_fd: int, child_socket: socket.socket, parent_socket: socket.socket) -> None:
    """No backend side effect until the parent persists identity and sends ACK."""
    parent_socket.close()
    os.close(worker_fd)
    child_socket.settimeout(10)
    store = Store(root)
    directory = private_directory(root / "jobs" / job["job_id"])
    try:
        child_socket.sendall(canonical_json(process_identity(os.getpid())).encode())
        if child_socket.recv(1) != b"A":
            return
        if store.jobs(job["job_id"])[0]["cancel_requested"]:
            evidence = {"schemaVersion": 1, "jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"],
                "cleanup": "safe", "validationStatus": "UNKNOWN", "terminalState": "cancelled",
                "noHostSideEffects": True}
        else:
            evidence = backend.run(store, job, admission_fd)
        atomic_json(directory / "outcome.json", evidence)
    except NoHostSideEffects as failure:
        atomic_json(directory / "outcome.json", {"schemaVersion": 1,
            "jobId": job["job_id"], "attemptId": job["attempt_id"], "runId": job["run_id"],
            "preparedDigest": job["digest"], "cleanup": "safe", "validationStatus": "UNKNOWN",
            "terminalState": "failed", "noHostSideEffects": True, "reason": str(failure)})
    except BaseException as failure:
        atomic_json(directory / "supervisor-error.json", {"error": str(failure), "at": time.time()})
    finally:
        child_socket.close()
        os.close(admission_fd)


class Worker:
    def __init__(self, store: Store, backend: Any | None = None):
        self.store = store
        self.backend = RunnerBackend() if backend is None else backend

    def reconcile(self) -> None:
        # Reattach observation to a live acknowledged supervisor, not execution.
        # Keep its admission valid while it finishes, without ever rerunning it.
        host = self.store.host()
        if host["job_id"] is None:
            return
        job = self.store.jobs(host["job_id"])[0]
        if job["identity_json"] and identity_alive(json.loads(job["identity_json"])):
            return
        directory = self.store.root / "jobs" / job["job_id"]
        if job["state"] != "quarantined" or (directory / "outcome.json").is_file() or (directory / "evidence/lifecycle-result.json").is_file():
            self.finish(job)

    def finish(self, job: dict[str, Any]) -> None:
        directory = self.store.root / "jobs" / job["job_id"]
        try:
            evidence = durable_outcome(self.store, job)
            identity = json.loads(job["identity_json"]) if job["identity_json"] else None
            if identity is not None and identity_alive(identity):
                raise QueueError("supervisor still active")
            runner_identity = directory / "runner-identity.json"
            if runner_identity.exists() and identity_alive(json.loads(runner_identity.read_text())):
                raise QueueError("runner still active")
            # Unknown external sessions are handled by the next idle dispatch
            # check; they are not evidence that this contained attempt leaked.
            self.store.complete(job["job_id"], evidence["terminalState"], evidence)
        except (OSError, ValueError, KeyError, TypeError, QueueError) as failure:
            self.store.quarantine(job["job_id"], str(failure))

    def serve(self, *, stop: Any | None = None, max_jobs: int | None = None) -> None:
        count = 0
        with FileLock(self.store.root / "worker.lock") as worker_lock:
            self.reconcile()
            socket_path = self.store.root / "wake.sock"
            if socket_path.exists() or socket_path.is_symlink():
                if not stat.S_ISSOCK(socket_path.lstat().st_mode):
                    raise QueueError("unsafe worker socket")
                socket_path.unlink()
            with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as listener:
                listener.bind(str(socket_path))
                os.chmod(socket_path, 0o600)
                atomic_json(self.store.root / "worker.json", process_identity(os.getpid()))
                try:
                    while stop is None or not stop.is_set():
                        if max_jobs is not None and count >= max_jobs:
                            break
                        self.reconcile()
                        if self.store.host()["state"] in {"idle", "external-busy"}:
                            self.store.external_busy(self.backend.busy())
                        if self.store.host()["state"] == "idle":
                            with FileLock(self.store.root / "admission.lock") as admission:
                                job = self.store.claim()
                                if job is not None:
                                    self.execute(job, admission.fd, worker_lock.fd, listener)
                                    count += 1
                                    continue
                        ready, _, _ = select.select([listener], [], [], 1)
                        if ready:
                            listener.recv(4096)
                finally:
                    socket_path.unlink(missing_ok=True)
                    (self.store.root / "worker.json").unlink(missing_ok=True)

    def execute(self, job: dict[str, Any], admission_fd: int, worker_fd: int,
                listener: socket.socket) -> None:
        parent, child = socket.socketpair()
        parent.settimeout(10)
        process = multiprocessing.get_context("fork").Process(target=supervised_attempt,
            args=(self.store.root, job, self.backend, admission_fd, worker_fd, child, parent))
        try:
            process.start()
            child.close()
            identity = json.loads(parent.recv(4096))
            if identity != process_identity(process.pid):
                raise QueueError("child handshake identity mismatch")
            self.store.acknowledge(job["job_id"], job["attempt_id"], identity)
            parent.sendall(b"A")
            while process.is_alive():
                ready, _, _ = select.select([listener, process.sentinel], [], [], 1)
                if listener in ready:
                    listener.recv(4096)
            process.join()
            self.finish(self.store.jobs(job["job_id"])[0])
        except BaseException as failure:
            self.store.quarantine(job["job_id"], f"attempt interrupted: {failure}")
            raise
        finally:
            parent.close()
            child.close()


def worker_online(store: Store) -> bool:
    try:
        identity = json.loads((store.root / "worker.json").read_text())
        return isinstance(identity, dict) and identity_alive(identity)
    except (OSError, ValueError, KeyError):
        return False


def verify_containment_membership(store: Store, job: dict[str, Any]) -> dict[str, Any]:
    metadata = json.loads((store.root / "jobs" / job["job_id"] / "containment.json").read_text())
    expected = {"jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"]}
    if metadata.get("schemaVersion") != 1 or any(metadata.get(k) != v for k, v in expected.items()):
        raise QueueError("containment metadata identity mismatch")
    cgroup = Path(metadata["cgroupPath"])
    if not cgroup.is_absolute() or ".." in cgroup.parts or cgroup == Path("/"):
        raise QueueError("invalid containment cgroup path")
    current_groups = Path("/proc/self/cgroup").read_text().splitlines()
    if current_groups != ["0::" + str(cgroup)]:
        raise QueueError("Runner is not in its admitted cgroup")
    kernel_path = Path("/sys/fs/cgroup") / cgroup.relative_to("/")
    current = kernel_path.stat()
    if (current.st_dev, current.st_ino) != (metadata["cgroupDevice"], metadata["cgroupInode"]):
        raise QueueError("containment cgroup identity changed")
    if not identity_alive(scope_identity(metadata["entryIdentity"])):
        raise QueueError("containment entry identity is no longer live")
    return metadata


def validate_admission() -> dict[str, Any]:
    """Runner's mandatory gate. Environment labels alone never authorize launch."""
    required = ("TURBOISM_QUEUE_JOB_ID", "TURBOISM_QUEUE_ATTEMPT_ID", "TURBOISM_QUEUE_RUN_ID",
                "TURBOISM_QUEUE_ADMISSION_FD", "TURBOISM_QUEUE_SUPERVISOR_PID")
    if any(not os.environ.get(key) for key in required):
        raise QueueError("missing worker admission labels")
    root = account_root()
    if not (root / "queue.sqlite3").is_file():
        raise QueueError("no local queue admission exists")
    store = Store()
    try:
        job = store.jobs(os.environ["TURBOISM_QUEUE_JOB_ID"])[0]
        fd = int(os.environ["TURBOISM_QUEUE_ADMISSION_FD"])
        identity = json.loads(job["identity_json"] or "null")
        if (job["state"] != "running" or job["cancel_requested"] or job["attempt_id"] != os.environ["TURBOISM_QUEUE_ATTEMPT_ID"]
                or job["run_id"] != os.environ["TURBOISM_QUEUE_RUN_ID"]
                or identity is None or not identity_alive(identity)
                or identity["pid"] != int(os.environ["TURBOISM_QUEUE_SUPERVISOR_PID"])):
            raise QueueError("attempt identity is not authorized")
        expected = (root / "admission.lock").stat()
        actual = os.fstat(fd)
        if (expected.st_dev, expected.st_ino) != (actual.st_dev, actual.st_ino):
            raise QueueError("inherited admission descriptor mismatch")
        # A separate open description must be busy; an unlocked matching file is insufficient.
        probe = FileLock(root / "admission.lock")
        try:
            probe.acquire()
        except QueueError as failure:
            if "already owned" not in str(failure):
                raise
        else:
            probe.close()
            raise QueueError("admission descriptor is not locked")
        current = os.getppid()
        ancestor_found = False
        for _ in range(16):
            if current == identity["pid"]:
                ancestor_found = True
                break
            if current <= 1:
                break
            fields = (Path("/proc") / str(current) / "stat").read_text().rsplit(")", 1)[1].split()
            current = int(fields[1])
        if not ancestor_found:
            raise QueueError("runner is not a descendant of its recorded supervisor")
        host = store.host()
        if host["state"] != "owned" or host["job_id"] != job["job_id"]:
            raise QueueError("host is quarantined or owned by another attempt")
        containment = verify_containment_membership(store, job)
        return {"schemaVersion": 1, "jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"],
                "cleanupOwner": "supervisor", "containment": containment}
    except (KeyError, ValueError, OSError) as failure:
        raise QueueError("missing or invalid worker admission") from failure


def durable_outcome(store: Store, job: dict[str, Any]) -> dict[str, Any]:
    """Consume a final durable verdict; never synthesize one from preliminary flags."""
    directory = store.root / "jobs" / job["job_id"]
    outcome_path = directory / "outcome.json"
    final_path = directory / "evidence/lifecycle-result.json"
    outcome_exists = outcome_path.exists() or outcome_path.is_symlink()
    outcome = json.loads(outcome_path.read_text()) if outcome_exists else None
    final = json.loads(final_path.read_text()) if final_path.exists() else None
    if outcome_exists:
        if not isinstance(outcome, dict):
            raise QueueError("outcome must be an object")
        if outcome.get("finalizedBy") != "contained-supervisor":
            if isinstance(final, dict) and final.get("finalizedBy") == "contained-supervisor" and final != outcome:
                raise QueueError("outcome conflicts with final lifecycle")
            store.validate_completion(job, outcome["terminalState"], outcome)
            return outcome
        if outcome != final:
            raise QueueError("outcome conflicts with or lacks final lifecycle")
    if not isinstance(final, dict) or final.get("finalizedBy") != "contained-supervisor":
        raise QueueError("no durable final supervisor verdict")
    store.validate_completion(job, final["terminalState"], final)
    proof = final.get("containment")
    metadata = json.loads((directory / "containment.json").read_text())
    if not isinstance(proof, dict) or not isinstance(metadata, dict):
        raise QueueError("missing final containment proof")
    expected = {"jobId": job["job_id"], "attemptId": job["attempt_id"], "runId": job["run_id"], "preparedDigest": job["digest"]}
    for record in (metadata, proof):
        if type(record.get("schemaVersion")) is not int or record["schemaVersion"] != SCHEMA or record.get("cleanup") != "safe":
            raise QueueError("invalid final containment proof schema/cleanup")
        if any(record.get(key) != value for key, value in expected.items()):
            raise QueueError("final containment proof identity mismatch")
    fields = ("bootId", "scopeUnit", "cgroupPath", "cgroupDevice", "cgroupInode", "entryIdentity", "kernelProof")
    if metadata.get("state") != "FINISHED" or any(key not in proof or proof[key] != metadata.get(key) for key in fields):
        raise QueueError("final verdict disagrees with durable bound containment evidence")
    runner_identity = json.loads((directory / "runner-identity.json").read_text())
    if runner_identity != scope_identity(proof["entryIdentity"]) or identity_alive(runner_identity):
        raise QueueError("final verdict runner identity is missing, changed, or still active")
    kernel = proof["kernelProof"]
    if not isinstance(kernel, dict) or kernel.get("originalCgroupBound") is not True or kernel.get("errors") != []:
        raise QueueError("kernel cleanup proof is incomplete")
    for key in ("bootId", "cgroupPath", "cgroupDevice", "cgroupInode"):
        if kernel.get(key) != proof[key]:
            raise QueueError("kernel cleanup proof does not identify the bound scope")
    reading = kernel.get("finalReading", {})
    if not isinstance(reading, dict) or not (reading.get("kind") == "destroyed" or
            (reading.get("kind") == "same" and type(reading.get("populated")) is int and reading["populated"] == 0)):
        raise QueueError("kernel cleanup has no final empty/destroyed reading")
    details = final.get("details", {})
    if not isinstance(details, dict) or details.get("taskOwnedCleanup") is not True:
        raise QueueError("final task cleanup is incomplete")
    if final["terminalState"] == "succeeded":
        checks = details.get("postContainmentChecks")
        terminal = checks.get("terminalResult") if isinstance(checks, dict) else None
        if not isinstance(terminal, dict) or terminal.get("passed") is not True:
            raise QueueError("final terminal result was not verified after containment")
    return final

def recover(store: Store, job_id: str, reason: str | None = None) -> dict[str, Any]:
    job = store.jobs(job_id)[0]
    report: dict[str, Any] = {"safe": False, "jobId": job_id, "state": job["state"]}
    if reason is not None and not reason.strip():
        raise QueueError("recovery confirmation requires an operator reason")
    if job["state"] not in ACTIVE:
        return {**report, "safe": True, "reason": "job is already terminal or not started"}
    try:
        if job["identity_json"] and identity_alive(json.loads(job["identity_json"])):
            raise QueueError("recorded supervisor is still active")
        directory = store.root / "jobs" / job_id
        runner_identity_path = directory / "runner-identity.json"
        if runner_identity_path.exists() and identity_alive(json.loads(runner_identity_path.read_text())):
            raise QueueError("recorded runner is still active")
        if external_sessions():
            raise QueueError("host contains active external/unknown Cubism sessions")
        evidence = durable_outcome(store, job)
        # Inspection and confirmation apply exactly the same evidence rules.
        store.validate_completion(job, evidence["terminalState"], evidence)
        report.update(safe=True, evidence=evidence)
        if reason is not None:
            with FileLock(store.root / "worker.lock"), FileLock(store.root / "admission.lock"):
                # Prevent racing a worker; revalidate process state before committing.
                fresh = store.jobs(job_id)[0]
                if fresh["identity_json"] and identity_alive(json.loads(fresh["identity_json"])):
                    raise QueueError("supervisor became active during recovery")
                store.complete(job_id, evidence["terminalState"], evidence, recovery_reason=reason)
                wake(store)
    except (OSError, ValueError, KeyError, TypeError, QueueError) as failure:
        report.update(safe=False, reason=str(failure))
    return report
