"""Queue database Store and SQLite connection/retry primitives."""
from __future__ import annotations

import contextlib
import json
import os
from pathlib import Path
import sqlite3
import stat
import subprocess
import time
from typing import Any, Iterator
import uuid

from . import common
from . import display
from .common import (
    ACTIVE,
    ADMINISTRATIVE,
    CLAIM_SCAN_LIMIT,
    GLOBAL_RESOURCES,
    KNOWN_RESOURCES,
    READ_ONLY_CONNECT_TIMEOUT_SECONDS,
    SCHEMA,
    TERMINAL,
    XVFB_DISPLAYS,
    _READ_LOCK_MESSAGES,
    _READ_LOCK_RESULT_CODES,
    QueueError,
    canonical_json,
    identity_alive,
    private_directory,
    storage_lock,
)


class Store:
    def __init__(self, root: Path | None = None):
        self.production = root is None or root.absolute() == common.account_root()
        self.root = private_directory(common.account_root() if root is None else root)
        for child in ("prepared", "jobs", "staging"):
            private_directory(self.root / child)
        self.database = self.root / "queue.sqlite3"
        if self.database.is_symlink():
            raise QueueError("queue database must not be a symlink")
        fd = os.open(self.database, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        os.close(fd)
        self._profiles: dict[str, tuple[str | None, list[str] | None, int]] = {}
        self.xvfb_processes: dict[str, subprocess.Popen] = {}
        self._claim_notes: set[tuple[str | None, str]] = set()
        self._anchor: sqlite3.Connection | None = None
        retry_transient_lock(self._initialize)
        # An idle query_only connection keeps this Store a wal-index client for
        # its lifetime. Without an anchor the last closing operation connection
        # checkpoints, unlinks and later rebuilds -shm/-wal between short-lived
        # calls; racing that churn is what produced transient SQLITE_PROTOCOL
        # ("locking protocol") failures for other connections that had mapped
        # or were mapping the previous wal-index.
        self._anchor = retry_transient_lock(self._open_anchor)

    def _initialize(self) -> None:
        with self.connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS retention_objects(
                    kind TEXT NOT NULL, object_id TEXT NOT NULL, created_at REAL NOT NULL,
                    pin TEXT NOT NULL DEFAULT '', retired INTEGER NOT NULL DEFAULT 0,
                    metadata TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(kind,object_id));
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
            # Additive slot columns are version-guarded so an older worker binary
            # can keep reading/writing the same live database during rollout.
            if db.execute("PRAGMA user_version").fetchone()[0] < 2:
                columns = {row[1] for row in db.execute("PRAGMA table_info(jobs)")}
                for name, ddl in (("version", "TEXT"), ("resources_json", "TEXT"),
                                  ("display", "TEXT"), ("xvfb_pid", "INTEGER")):
                    if name not in columns:
                        db.execute(f"ALTER TABLE jobs ADD COLUMN {name} {ddl}")
                db.execute("PRAGMA user_version=2")
            if db.execute("SELECT version FROM metadata").fetchone()[0] != SCHEMA:
                raise QueueError("unsupported queue schema; migration required")

    def _open_anchor(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.database, timeout=30, isolation_level=None)
        try:
            db.execute("PRAGMA query_only=ON")
            db.execute("SELECT 1 FROM sqlite_master LIMIT 1").fetchone()
        except BaseException:
            db.close()
            raise
        return db

    def close(self) -> None:
        if self._anchor is not None:
            self._anchor.close()
            self._anchor = None

    def __del__(self) -> None:
        try:
            self.close()
        except Exception:
            pass

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
        from .snapshot import PreparedStore
        with storage_lock(self.root):
            with self.connection() as db:
                prior = db.execute("SELECT 1 FROM jobs WHERE request_key=?", (request_key,)).fetchone()
                retained = db.execute("SELECT retired FROM retention_objects WHERE kind='prepared' AND object_id=?",
                                      (prepared_id,)).fetchone()
            if retained is not None and prior is None:
                if retained["retired"] or (self.root / "retention-prepared" / (prepared_id + ".json")).exists():
                    raise QueueError("prepared input retired; prepare it again before a new submission")
                # Storage ownership prevents GC here. Do not hold a SQLite write
                # transaction while hashing large inputs and delay host completion.
                PreparedStore(self).load(prepared_id)
            now = time.time()
            with self.transaction() as db:
                existing = db.execute("SELECT * FROM jobs WHERE request_key=?", (request_key,)).fetchone()
                if existing:
                    if (existing["prepared_id"], existing["digest"], existing["timeout_seconds"]) != (
                            prepared_id, digest, timeout_seconds):
                        raise QueueError("idempotency key conflicts with different inputs")
                    return dict(existing)
                job = str(uuid.uuid4())
                db.execute("INSERT INTO retention_objects(kind,object_id,created_at) VALUES('job',?,?)", (job, now))
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

    def jobs_retry(self, job_id: str, attempts: int = 6) -> list[dict[str, Any]]:
        """Supervisor diagnostic read that tolerates transient WAL lock
        contention. The read is idempotent; a persistent lock still raises so
        callers fail closed rather than fabricate a verdict."""
        for attempt in range(attempts):
            try:
                return self.jobs(job_id)
            except sqlite3.Error as failure:
                if not is_retryable_read_error(failure) or attempt == attempts - 1:
                    raise
                time.sleep(min(30.0, 5.0 * (attempt + 1)))

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

    def active_run_ids(self) -> set[str]:
        with self.connection() as db:
            rows = db.execute("SELECT run_id FROM jobs WHERE state IN ('starting','running')").fetchall()
            return {row[0] for row in rows if row[0]}

    def _job_profile(self, prepared_id: str) -> tuple[str | None, list[str] | None, int]:
        """(version, declared resources, queue protocol) from the immutable
        prepared descriptor. Unverifiable input is fail-closed serial legacy."""
        cached = self._profiles.get(prepared_id)
        if cached is not None:
            return cached
        from .snapshot import PreparedStore
        profile: tuple[str | None, list[str] | None, int] = (None, None, 1)
        try:
            descriptor = PreparedStore(self).load(prepared_id)
        except (QueueError, OSError, ValueError, KeyError, TypeError):
            pass
        else:
            protocol = descriptor.get("queueProtocol")
            protocol = protocol if type(protocol) is int else 1
            argv = descriptor.get("argv")
            version = None
            if isinstance(argv, list) and "--version" in argv:
                index = argv.index("--version")
                candidate = argv[index + 1] if index + 1 < len(argv) else None
                if isinstance(candidate, str) and candidate:
                    version = candidate
            resources = descriptor.get("resources")
            if isinstance(resources, list) and all(type(name) is str for name in resources):
                resources = sorted(resources)
            else:
                resources = None
            profile = (version, resources, protocol)
        self._profiles[prepared_id] = profile
        return profile

    def _deferred(self, db: sqlite3.Connection, job_id: str | None, kind: str,
                  **payload: Any) -> None:
        """Log a claim obstruction once per job/reason; durable but not spammy."""
        if (job_id, kind) in self._claim_notes:
            return
        self._claim_notes.add((job_id, kind))
        self.event(db, job_id, kind, **payload)

    def claim(self) -> dict[str, Any] | None:
        spawned: list[str] = []
        try:
            with self.transaction() as db:
                host = db.execute("SELECT * FROM host WHERE singleton=1").fetchone()
                if host["state"] not in {"idle", "owned"}:
                    return None
                running = db.execute(
                    "SELECT * FROM jobs WHERE state IN ('starting','running') ORDER BY sequence").fetchall()
                # A running attempt with an unversioned/legacy slot has unknown
                # resources and an old admission protocol: it blocks every claim.
                if any(row["version"] is None for row in running):
                    return None
                running_versions = {row["version"] for row in running}
                used_displays = {row["display"] for row in running if row["display"]}
                global_running = any(
                    GLOBAL_RESOURCES & set(json.loads(row["resources_json"] or "[]"))
                    for row in running)
                if global_running:
                    return None
                skipped = 0
                for job in db.execute(
                        "SELECT * FROM jobs WHERE state='queued' ORDER BY sequence").fetchall():
                    if skipped >= CLAIM_SCAN_LIMIT:
                        break
                    version, resources, protocol = self._job_profile(job["prepared_id"])
                    parallel = (protocol >= common.QUEUE_PROTOCOL and version is not None
                                and resources is not None
                                and not set(resources) - KNOWN_RESOURCES)
                    if not parallel:
                        if resources is not None and set(resources) - KNOWN_RESOURCES:
                            # An undeclared resource name can never be scheduled
                            # safely; fail closed and keep it queued.
                            self._deferred(db, job["job_id"], "claim-deferred",
                                           reason="unknown resource declaration; never claimable")
                            skipped += 1
                            continue
                        if running:
                            self._deferred(db, job["job_id"], "claim-deferred",
                                           reason="missing version or declared resources; serial-only")
                            skipped += 1
                            continue
                        return self._begin_claim(db, job)
                    if version in running_versions or (running and GLOBAL_RESOURCES & set(resources)):
                        skipped += 1
                        continue
                    # performance-host jobs measure real GPU/CPU — they must stay
                    # on the physical display :0, not a software-rendered Xvfb.
                    display = ":0" if {"interactive-desktop", "performance-host"} & set(resources) \
                        else self._allocate_display(db, job["job_id"], used_displays, spawned)
                    if display is None:
                        skipped += 1
                        continue
                    return self._begin_claim(db, job, version=version, resources=resources,
                                             display=display)
                return None
        except BaseException:
            # Rolled-back claims must not leak a worker-owned Xvfb process.
            from .display import terminate_process
            for job_id in spawned:
                process = self.xvfb_processes.pop(job_id, None)
                if process is not None:
                    terminate_process(process)
            raise

    def _allocate_display(self, db: sqlite3.Connection, job_id: str,
                          used: set[str], spawned: list[str]) -> str | None:
        """Reserve a private Xvfb display inside the claim transaction."""
        if not display.xvfb_available():
            self._deferred(db, job_id, "display-pool-unavailable", reason="no Xvfb binary")
            return None
        display_name = None
        for number in XVFB_DISPLAYS:
            candidate = f":{number}"
            if candidate in used or display.display_in_use(number):
                continue
            display_name = candidate
            break
        if display_name is None:
            self._deferred(db, job_id, "display-pool-unavailable", reason="no free display slot")
            return None
        process = display.spawn_xvfb(display_name)
        if process is None or process.poll() is not None:
            self._deferred(db, job_id, "display-pool-unavailable",
                           reason=f"Xvfb failed to start on {display_name}")
            return None
        self.xvfb_processes[job_id] = process
        spawned.append(job_id)
        used.add(display_name)
        return display_name

    def _begin_claim(self, db: sqlite3.Connection, job: Any, *,
                     version: str | None = None, resources: list[str] | None = None,
                     display: str | None = None) -> dict[str, Any]:
        attempt, run = str(uuid.uuid4()), "queue-" + uuid.uuid4().hex
        process = self.xvfb_processes.get(job["job_id"])
        xvfb_pid = process.pid if process is not None else None
        db.execute("""UPDATE jobs SET state='starting',attempt_id=?,run_id=?,version=?,
                      resources_json=?,display=?,xvfb_pid=?,updated_at=? WHERE job_id=?""",
                   (attempt, run, version,
                    canonical_json(resources) if resources is not None else None,
                    display, xvfb_pid, time.time(), job["job_id"]))
        db.execute("UPDATE host SET state='owned',job_id=?,reason='' WHERE singleton=1", (job["job_id"],))
        payload: dict[str, Any] = {"attemptId": attempt, "runId": run}
        if version is not None:
            payload.update(version=version, display=display, xvfbPid=xvfb_pid)
        self.event(db, job["job_id"], "starting", **payload)
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

    def release_display(self, job_id: str) -> None:
        """Clear the slot display/xvfb fields after the attempt is terminal."""
        with self.transaction() as db:
            db.execute("UPDATE jobs SET display=NULL,xvfb_pid=NULL,updated_at=? WHERE job_id=?",
                       (time.time(), job_id))

    def cancel(self, job_id: str) -> dict[str, Any]:
        with self.transaction() as db:
            row = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
            if row is None:
                raise QueueError("unknown job id")
            if row["state"] not in TERMINAL | ADMINISTRATIVE and not row["cancel_requested"]:
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
            # Release the host only when no attempt still occupies a slot; hand
            # the marker to a surviving parallel attempt otherwise.
            other = db.execute("""SELECT job_id FROM jobs WHERE state IN ('starting','running')
                               AND job_id<>? ORDER BY sequence LIMIT 1""", (job_id,)).fetchone()
            db.execute("""UPDATE host SET state=?,job_id=?,reason='' WHERE singleton=1
                          AND (state='owned' OR job_id=?)""",
                       ("owned" if other else "idle",
                        other["job_id"] if other else None, job_id))
            details = evidence.get("details")
            task_dir = details.get("taskDir") if isinstance(details, dict) else None
            if task_dir:
                task = Path(task_dir)
                if task.is_dir() and not task.is_symlink():
                    info = task.stat()
                    retained = db.execute("SELECT metadata FROM retention_objects WHERE kind='job' AND object_id=?", (job_id,)).fetchone()
                    if retained is not None:
                        metadata = json.loads(retained["metadata"])
                        metadata.update(taskDir=str(task), taskIdentity=[info.st_dev, info.st_ino])
                        db.execute("UPDATE retention_objects SET metadata=? WHERE kind='job' AND object_id=?",
                                   (canonical_json(metadata), job_id))
            self.event(db, job_id, state, evidence=evidence)

    def complete_retry(self, job_id: str, state: str, evidence: dict[str, Any],
                       *, recovery_reason: str | None = None, attempts: int = 6) -> None:
        """Persist the final verdict through transient SQLite lock outcomes.

        The durable verdict already lives in the attempt directory; this only
        has to survive bounded shm/lock churn. A COMMIT that reported a lock
        error may still have landed, so a retried nonterminal-attempt refusal
        re-reads the row and accepts an identical recorded verdict instead of
        treating its own committed write as a validation failure."""
        def commit() -> None:
            try:
                self.complete(job_id, state, evidence, recovery_reason=recovery_reason)
            except QueueError as failure:
                if str(failure) != "completion requires a nonterminal attempt":
                    raise
                row = self.jobs(job_id)[0]
                if row["state"] == state and json.loads(row["evidence_json"] or "null") == evidence:
                    return
                raise
        retry_transient_lock(commit, attempts=attempts)

    def events(self, after: int = 0, job_id: str | None = None) -> list[dict[str, Any]]:
        with self.connection() as db:
            rows = db.execute("""SELECT * FROM events WHERE event_id>? AND (? IS NULL OR job_id=?)
                               ORDER BY event_id LIMIT 1000""", (after, job_id, job_id)).fetchall()
            return [{**dict(row), "payload": json.loads(row["payload"])} for row in rows]


def _read_only_database_uri(database: Path) -> str:
    path = Path(database).absolute()
    if path.is_symlink():
        raise QueueError("queue database must not be a symlink")
    try:
        info = path.stat()
    except FileNotFoundError as failure:
        raise QueueError("queue database does not exist") from failure
    except OSError as failure:
        raise QueueError("cannot inspect queue database") from failure
    if not stat.S_ISREG(info.st_mode):
        raise QueueError("queue database must be a regular file")
    return path.as_uri() + "?mode=ro"


@contextlib.contextmanager
def read_only_connection(database: Path, *, connect=sqlite3.connect) -> Iterator[sqlite3.Connection]:
    """Open an existing queue database without running queue initialization."""
    uri = _read_only_database_uri(database)
    db = None
    try:
        db = connect(uri, uri=True, timeout=READ_ONLY_CONNECT_TIMEOUT_SECONDS,
                     isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA query_only=ON")
        yield db
    finally:
        if db is not None:
            db.close()


def read_only_job(database: Path, job_id: str, *, connect=sqlite3.connect) -> dict[str, Any]:
    """Read one job for a client waiter; never create or initialize its database."""
    with read_only_connection(database, connect=connect) as db:
        version = db.execute("SELECT version FROM metadata").fetchone()
        if version is None or version[0] != SCHEMA:
            raise QueueError("unsupported queue schema; migration required")
        row = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
        if row is None:
            raise QueueError("unknown job id")
        return dict(row)


def is_retryable_read_error(error: BaseException) -> bool:
    """Recognize only SQLite's explicit transient lock/protocol outcomes."""
    if not isinstance(error, sqlite3.Error):
        return False
    code = getattr(error, "sqlite_errorcode", None)
    if isinstance(code, int):
        return (code & 0xFF) in _READ_LOCK_RESULT_CODES
    return str(error).strip().casefold() in _READ_LOCK_MESSAGES


def retry_transient_lock(operation, *, attempts: int = 6, base_seconds: float = 0.05,
                         cap_seconds: float = 2.0):
    """Bounded exponential backoff for SQLite's explicit transient lock
    outcomes (BUSY/LOCKED/PROTOCOL, e.g. "locking protocol" from wal-index
    churn). Everything else, and a still-locked final attempt, raises."""
    for attempt in range(attempts):
        try:
            return operation()
        except sqlite3.Error as failure:
            if not is_retryable_read_error(failure) or attempt == attempts - 1:
                raise
            time.sleep(min(cap_seconds, base_seconds * (2 ** attempt)))
