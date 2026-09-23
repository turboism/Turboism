"""Administrative disposition of cross-boot orphan validation attempts.

Inspection never constructs the writable Store, never creates shared queue
state and keeps its only copy inside a private temporary directory.
Confirmation revalidates every eligibility and execution condition inside the
existing worker -> storage -> admission coordination and commits the target
row plus one audit event in a single transaction. It never updates the host
row, never writes evidence files and never marks verification accepted.
"""
from __future__ import annotations

import json
import math
import os
from pathlib import Path
import re
import sqlite3
import stat
import sys
import time
import unicodedata
from typing import Any

import host_validation_queue as queue
import host_validation_retention as retention
from host_validation_evidence import checked_path, layout

SCHEMA_VERSION = 1
EVENT_KIND = "operator-abandoned"
DISPOSITION = "ABANDONED_UNVERIFIED"
PRESERVATION = "unverified-indefinite"
MAX_REASON = 2048
MAX_METADATA_BYTES = 4 * 1024 * 1024

JOB_ID = re.compile(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
RUN_ID = re.compile(r"queue-[a-f0-9]{32}")
APPROVAL = re.compile(r"[a-f0-9]{64}")
COORDINATION = ("worker.lock", "storage.lock", "admission.lock")
JOB_METADATA = (
    "outcome.json", "supervisor-error.json", "runner-identity.json",
    "containment.json", "heartbeat.json", "evidence/lifecycle-result.json",
    "evidence/final-hashes.properties", "evidence/identity-before.properties",
    "evidence/cloned-identity.properties", "evidence/fixture-before.properties",
    "evidence/fixture-after.properties", "evidence/identity-after.properties",
)


class Rejected(queue.QueueError):
    """A refused disposition; carries the diagnostic report for the caller."""

    def __init__(self, report: dict[str, Any]):
        super().__init__("administrative disposition refused")
        self.report = report


class _FinalVerdictPresent(Exception):
    """A fixed-closure copy already carries a consumable final verdict."""


def _block(code: str, detail: str) -> dict[str, str]:
    return {"code": code, "detail": detail}


def _current_boot() -> str:
    boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    if not JOB_ID.fullmatch(boot):
        raise queue.QueueError("cannot determine the current boot identity")
    return boot


def _identity(raw: Any, source: str) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise queue.QueueError(f"{source} identity is not an object")
    try:
        identity = {"pid": int(raw["pid"]), "startTicks": int(raw["startTicks"]),
                    "bootId": raw["bootId"], "uid": int(raw["uid"])}
    except (KeyError, TypeError, ValueError) as failure:
        raise queue.QueueError(f"{source} identity is incomplete") from failure
    if (identity["pid"] <= 0 or identity["startTicks"] < 0
            or isinstance(raw["pid"], bool) or isinstance(raw["startTicks"], bool)
            or isinstance(raw["uid"], bool)
            or not isinstance(identity["bootId"], str)
            or not JOB_ID.fullmatch(identity["bootId"])
            or identity["uid"] != os.getuid()):
        raise queue.QueueError(f"{source} identity is invalid")
    return identity


def _bounded_json(path: Path) -> Any:
    checked_path(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_size > MAX_METADATA_BYTES:
        raise queue.QueueError(f"unsafe or oversized metadata: {path}")
    try:
        return json.loads(path.read_bytes())
    except (ValueError, UnicodeDecodeError) as failure:
        raise queue.QueueError(f"corrupt metadata: {path}") from failure


def _metadata_record(path: Path) -> dict[str, Any]:
    checked_path(path)
    try:
        info = path.lstat()
    except FileNotFoundError:
        return {"exists": False}
    if not stat.S_ISREG(info.st_mode):
        return {"exists": True, "type": stat.S_IFMT(info.st_mode)}
    if info.st_uid != os.getuid() or info.st_size > MAX_METADATA_BYTES:
        raise queue.QueueError(f"unsafe or oversized metadata: {path}")
    return {"exists": True, "type": stat.S_IFREG, "device": info.st_dev,
            "inode": info.st_ino, "size": info.st_size, "sha256": queue.file_digest(path)}


def _listing(directory: Path) -> list[list[Any]]:
    checked_path(directory)
    try:
        entries = sorted(directory.iterdir())
    except FileNotFoundError:
        return []
    return [[entry.name, stat.S_IFMT(entry.lstat().st_mode)] for entry in entries]


def _expected(job: dict[str, Any]) -> dict[str, Any]:
    return {"jobId": job["job_id"], "attemptId": job["attempt_id"],
            "runId": job["run_id"], "preparedDigest": job["digest"]}


def _lifecycle_copy(path: Path, expected: dict[str, Any]) -> tuple[dict[str, Any], Any]:
    """A present lifecycle record must be a schema-bound object for this attempt;
    a finalized copy is a durable verdict, not an orphan candidate."""
    record = _metadata_record(path)
    parsed = None
    if record.get("exists") and record.get("type") == stat.S_IFREG:
        parsed = _bounded_json(path)
        if (not isinstance(parsed, dict)
                or type(parsed.get("schemaVersion")) is not int
                or parsed["schemaVersion"] != SCHEMA_VERSION
                or any(parsed.get(key) != value for key, value in expected.items())):
            raise queue.QueueError(f"lifecycle record is malformed or foreign: {path}")
        if parsed.get("finalizedBy") == "contained-supervisor":
            raise _FinalVerdictPresent()
    return record, parsed


def _closure(view: Any, job: dict[str, Any], historical_boot: str,
             supervisor: dict[str, Any], descriptor: dict[str, Any]) -> dict[str, Any]:
    """Fixed evidence closure; every member is bounded and link-free."""
    expected = _expected(job)
    directory = checked_path(view.root / "jobs" / job["job_id"])
    files = {name: _metadata_record(directory / name) for name in JOB_METADATA}
    for required in ("containment.json", "runner-identity.json"):
        if not files[required].get("exists"):
            raise queue.QueueError(f"required attempt identity is missing: {required}")
    containment = _bounded_json(directory / "containment.json")
    if (not isinstance(containment, dict)
            or type(containment.get("schemaVersion")) is not int
            or containment["schemaVersion"] != SCHEMA_VERSION
            or any(containment.get(key) != value for key, value in expected.items())
            or containment.get("bootId") != historical_boot):
        raise queue.QueueError("containment identity does not match this attempt")
    entry = _identity(containment.get("entryIdentity"), "containment entry")
    if entry["bootId"] != historical_boot:
        raise queue.QueueError("containment entry belongs to another boot")
    runner = _identity(_bounded_json(directory / "runner-identity.json"), "runner")
    if runner != queue.scope_identity(entry):
        raise queue.QueueError("runner identity is not the bound entry projection")
    for actor in (entry, runner):
        if queue.identity_alive(actor):
            raise queue.QueueError("a recorded attempt process is still active")
    if files["heartbeat.json"].get("exists"):
        heartbeat = _bounded_json(directory / "heartbeat.json")
        if (not isinstance(heartbeat, dict)
                or type(heartbeat.get("schemaVersion")) is not int
                or heartbeat["schemaVersion"] != SCHEMA_VERSION
                or any(heartbeat.get(key) != value for key, value in expected.items())):
            raise queue.QueueError("heartbeat does not identify this attempt")
        if _identity(heartbeat.get("supervisor"), "heartbeat supervisor") != supervisor:
            raise queue.QueueError("heartbeat supervisor is not the recorded supervisor")
        if _identity(heartbeat.get("runner"), "heartbeat runner") != queue.scope_identity(entry):
            raise queue.QueueError("heartbeat runner is not the bound entry projection")
    prepared_root = view.root / "prepared" / job["prepared_id"]
    task = layout(descriptor, prepared_root, job)["task"]
    job_record, job_copy = _lifecycle_copy(
        directory / "evidence" / "lifecycle-result.json", expected)
    task_record, task_copy = _lifecycle_copy(
        task / "evidence" / "lifecycle-result.json", expected)
    if job_copy is not None and task_copy is not None and job_copy != task_copy:
        raise queue.QueueError("job and task lifecycle copies conflict")
    return {"files": files,
            "jobListing": _listing(directory),
            "evidenceListing": _listing(directory / "evidence"),
            "task": {"path": str(task), "lifecycleResult": task_record}}


def _assess(view: Any, job: dict[str, Any], current_boot: str) -> dict[str, Any]:
    """Target-scoped eligibility; every failure is a stable blocker, never a pass."""
    blockers: list[dict[str, str]] = []
    result: dict[str, Any] = {"blockers": blockers, "closure": None, "historicalBootId": None}
    state = job["state"]
    if state in queue.ADMINISTRATIVE:
        blockers.append(_block("UNVERIFIED_DISPOSITION", "attempt is already administratively closed"))
        return result
    if state in queue.TERMINAL:
        blockers.append(_block("FINAL_VERDICT_PRESENT", f"attempt already reached {state}"))
        return result
    if state == "queued" or state not in queue.ACTIVE:
        blockers.append(_block("IDENTITY_INVALID", f"not a started active attempt: {state}"))
        return result
    if (not JOB_ID.fullmatch(job["job_id"] or "")
            or not JOB_ID.fullmatch(job["attempt_id"] or "")
            or not RUN_ID.fullmatch(job["run_id"] or "")
            or not APPROVAL.fullmatch(job["prepared_id"] or "")
            or not APPROVAL.fullmatch(job["digest"] or "")
            or job["digest"] != job["prepared_id"]
            or not isinstance(job["request_key"], str) or not job["request_key"]):
        blockers.append(_block("IDENTITY_INVALID", "incomplete attempt identity"))
        return result
    try:
        supervisor = _identity(json.loads(job["identity_json"] or "null"), "supervisor")
    except (queue.QueueError, ValueError) as failure:
        blockers.append(_block("IDENTITY_INVALID", f"supervisor identity: {failure}"))
        return result
    historical = supervisor["bootId"]
    result["historicalBootId"] = historical
    if historical == current_boot:
        blockers.append(_block("SAME_BOOT", "attempt belongs to the current boot"))
        return result
    if queue.identity_alive(supervisor):
        blockers.append(_block("CURRENT_OR_UNKNOWN_ACTIVITY", "recorded supervisor is still active"))
        return result
    try:
        descriptor = retention.describe(view, job["prepared_id"])
        result["closure"] = _closure(view, job, historical, supervisor, descriptor)
    except _FinalVerdictPresent:
        blockers.append(_block("FINAL_VERDICT_PRESENT", "task-side lifecycle already finalized"))
        return result
    except (queue.QueueError, OSError, ValueError, KeyError, TypeError) as failure:
        blockers.append(_block("IDENTITY_INVALID", str(failure)))
        return result
    try:
        verdict = queue.durable_outcome(view, job)
    except queue.QueueError as failure:
        if "no durable final supervisor verdict" not in str(failure):
            blockers.append(_block("FINAL_VERDICT_PRESENT", str(failure)))
            return result
    except (OSError, ValueError, KeyError, TypeError) as failure:
        blockers.append(_block("IDENTITY_INVALID", f"cannot evaluate evidence: {failure}"))
        return result
    else:
        if verdict is not None:
            blockers.append(_block("FINAL_VERDICT_PRESENT", "a consumable final verdict exists"))
            return result
    return result


def _disposition_digests(view: Any, job_id: str) -> list[str]:
    return sorted(queue.digest_json(row["payload"]) for row in view.event_rows
                  if row["job_id"] == job_id and row["kind"] == EVENT_KIND)


def _approval_facts(view: Any, job: dict[str, Any], current_boot: str,
                    historical_boot: str, closure: dict[str, Any]) -> dict[str, Any]:
    """Stable approval inputs only; volatile observation times are excluded."""
    return {"schemaVersion": SCHEMA_VERSION, "operation": EVENT_KIND,
            "rootIdentity": view.identity,
            "target": {"jobId": job["job_id"], "attemptId": job["attempt_id"],
                       "runId": job["run_id"], "requestKey": job["request_key"],
                       "preparedId": job["prepared_id"], "digest": job["digest"],
                       "state": job["state"], "reason": job["reason"],
                       "updatedAt": job["updated_at"], "cancelRequested": job["cancel_requested"],
                       "evidenceSha256": queue.digest_json(job["evidence_json"])},
            "historicalBootId": historical_boot, "currentBootId": current_boot,
            "host": {"state": view.host_row["state"], "jobId": view.host_row["job_id"]},
            "evidence": closure,
            "dispositions": _disposition_digests(view, job["job_id"])}


def _worker_status(root: Path) -> str:
    path = root / "worker.json"
    try:
        checked_path(path)
        identity = json.loads(path.read_text())
    except FileNotFoundError:
        return "offline"
    except (OSError, ValueError, queue.QueueError):
        return "unknown"
    try:
        return "online" if queue.identity_alive(_identity(identity, "worker")) else "offline"
    except queue.QueueError:
        return "unknown"


def _locks_missing(root: Path) -> list[str]:
    missing = []
    for name in COORDINATION:
        try:
            checked_path(root / name)
            info = (root / name).lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
                missing.append(name)
        except (FileNotFoundError, queue.QueueError):
            missing.append(name)
    return missing


def _evaluate(view: Any, job: dict[str, Any], current_boot: str, *,
              busy: Any) -> dict[str, Any]:
    """Full eligibility + queue-side execution classification for one target."""
    assessment = _assess(view, job, current_boot)
    execution: list[dict[str, str]] = []
    observation: dict[str, Any] = {"otherOrphans": []}
    host = view.host_row
    observation["host"] = {"state": host["state"], "jobId": host["job_id"]}
    if host["state"] != "idle" or host["job_id"] is not None:
        execution.append(_block("HOST_NOT_UNOWNED_IDLE",
                                f"host is {host['state']} with job_id={host['job_id']}"))
    unverifiable = []
    for row in view.job_rows:
        if row["job_id"] == job["job_id"]:
            continue
        if row["state"] in queue.TERMINAL or row["state"] in queue.ADMINISTRATIVE:
            continue
        if row["state"] == "queued":
            unverifiable.append(row["job_id"])
            continue
        other = _assess(view, row, current_boot)
        if other["blockers"]:
            unverifiable.append(row["job_id"])
        else:
            observation["otherOrphans"].append(row["job_id"])
    if unverifiable:
        execution.append(_block("CURRENT_OR_UNKNOWN_ACTIVITY",
                                "queued or unverifiable active records: " + ",".join(sorted(unverifiable))))
    try:
        externals = busy()
    except (queue.QueueError, OSError) as failure:
        externals = []
        execution.append(_block("CURRENT_OR_UNKNOWN_ACTIVITY",
                                f"cannot inspect host processes: {failure}"))
    observation["externalSessions"] = externals
    if externals:
        execution.append(_block("CURRENT_OR_UNKNOWN_ACTIVITY",
                                "external host session(s): " + ",".join(map(str, externals))))
    return {"assessment": assessment, "execution": execution, "observation": observation}


def _report(view: Any, job: dict[str, Any], current_boot: str,
            evaluation: dict[str, Any], extra: list[dict[str, str]],
            observation: dict[str, Any]) -> dict[str, Any]:
    assessment = evaluation["assessment"]
    blockers = assessment["blockers"] + evaluation["execution"] + extra
    eligible = not assessment["blockers"]
    report = {"schemaVersion": SCHEMA_VERSION, "jobId": job["job_id"],
              "attemptId": job["attempt_id"], "runId": job["run_id"],
              "preparedDigest": job["digest"], "eligible": eligible,
              "canConfirm": False, "blockers": blockers,
              "historicalBootId": assessment["historicalBootId"],
              "currentBootId": current_boot,
              "evidenceInventory": assessment["closure"],
              "observation": observation,
              "disposition": DISPOSITION, "verificationAccepted": False}
    if not blockers:
        report["canConfirm"] = True
        report["approvalDigest"] = queue.digest_json(
            _approval_facts(view, job, current_boot,
                            assessment["historicalBootId"], assessment["closure"]))
    return report


def inspect(root: Path, job_id: str, *, busy: Any = None) -> dict[str, Any]:
    """Read-only classification; creates nothing under the queue root."""
    busy = queue.external_sessions if busy is None else busy
    try:
        current_boot = _current_boot()
        snapshot = retention.Snapshot(root)
    except (queue.QueueError, OSError, ValueError, KeyError, TypeError) as failure:
        return {"schemaVersion": SCHEMA_VERSION, "jobId": job_id, "eligible": False,
                "canConfirm": False,
                "blockers": [_block("IDENTITY_INVALID", f"cannot read queue safely: {failure}")],
                "disposition": DISPOSITION, "verificationAccepted": False}
    report = {"schemaVersion": SCHEMA_VERSION, "jobId": job_id, "eligible": False,
              "canConfirm": False, "currentBootId": current_boot,
              "blockers": [], "observation": {}, "evidenceInventory": None,
              "historicalBootId": None,
              "disposition": DISPOSITION, "verificationAccepted": False}
    rows = snapshot.jobs(job_id)
    if not rows:
        report["blockers"] = [_block("IDENTITY_INVALID", "unknown job id")]
        return report
    job = rows[0]
    extra = []
    observation: dict[str, Any] = {}
    worker = _worker_status(root)
    observation["worker"] = worker
    if worker != "offline":
        extra.append(_block("WORKER_ACTIVE_OR_LOCKED", f"worker is {worker}"))
    missing = _locks_missing(root)
    if missing:
        extra.append(_block("WORKER_ACTIVE_OR_LOCKED",
                            "coordination locks are missing or unsafe: " + ",".join(missing)))
    evaluation = _evaluate(snapshot, job, current_boot, busy=busy)
    observation.update(evaluation["observation"])
    return _report(snapshot, job, current_boot, evaluation, extra, observation)


def _replay(view: Any, job: dict[str, Any],
            approval: str, reason: str) -> dict[str, Any]:
    """Read-only replay of an identical committed confirmation.

    The stored audit is a fixed protocol record: every field is type-checked
    and rebound to the live row before any receipt is returned. A receipt can
    never be upgraded into a verification result.
    """
    def refuse(code: str, detail: str) -> Rejected:
        return Rejected({"schemaVersion": SCHEMA_VERSION, "jobId": job["job_id"],
                         "blockers": [_block(code, detail)],
                         "disposition": DISPOSITION, "verificationAccepted": False})
    audits = [row for row in view.event_rows
              if row["job_id"] == job["job_id"] and row["kind"] == EVENT_KIND]
    if len(audits) != 1:
        raise refuse("IDENTITY_INVALID", "administrative state lacks a unique audit")
    try:
        payload = json.loads(audits[0]["payload"])
    except (ValueError, TypeError) as failure:
        raise refuse("IDENTITY_INVALID", f"administrative audit is unreadable: {failure}")
    valid = (
        isinstance(payload, dict)
        and type(payload.get("schemaVersion")) is int
        and payload["schemaVersion"] == SCHEMA_VERSION
        and payload.get("disposition") == DISPOSITION
        and payload.get("toState") == queue.ADMINISTRATIVE_STATE
        and payload.get("fromState") in queue.ACTIVE
        and payload.get("preservation") == PRESERVATION
        and payload.get("verificationAccepted") is False
        and type(payload.get("operatorUid")) is int
        and payload["operatorUid"] == os.getuid()
        and JOB_ID.fullmatch(payload.get("jobId") or "")
        and JOB_ID.fullmatch(payload.get("attemptId") or "")
        and RUN_ID.fullmatch(payload.get("runId") or "")
        and APPROVAL.fullmatch(payload.get("preparedDigest") or "")
        and APPROVAL.fullmatch(payload.get("approvalDigest") or "")
        and JOB_ID.fullmatch(payload.get("historicalBootId") or "")
        and JOB_ID.fullmatch(payload.get("currentBootId") or "")
        and payload["historicalBootId"] != payload["currentBootId"]
        and type(payload.get("recordedAt")) in (int, float)
        and not isinstance(payload["recordedAt"], bool)
        and math.isfinite(payload["recordedAt"])
        and (payload.get("previousReason") is None or isinstance(payload["previousReason"], str))
        and (payload.get("previousUpdatedAt") is None
             or (type(payload["previousUpdatedAt"]) in (int, float)
                 and not isinstance(payload["previousUpdatedAt"], bool)))
        and isinstance(payload.get("reason"), str)
        and isinstance(payload.get("evidenceInventory"), dict)
        and payload.get("rootIdentity") == list(view.identity)
    )
    if not valid:
        raise refuse("IDENTITY_INVALID", "administrative audit fails protocol validation")
    bound = (payload["jobId"] == job["job_id"] and payload["attemptId"] == job["attempt_id"]
             and payload["runId"] == job["run_id"] and payload["preparedDigest"] == job["digest"]
             and job["state"] == queue.ADMINISTRATIVE_STATE
             and job["reason"] == payload["reason"]
             and job["updated_at"] == payload["recordedAt"])
    if not bound:
        raise refuse("IDENTITY_INVALID", "administrative row disagrees with its audit")
    if payload["approvalDigest"] != approval or payload["reason"] != reason:
        raise refuse("CONFLICTING_CONFIRMATION",
                     "request conflicts with the committed disposition")
    return {**payload, "replayed": True}


def _normalize_reason(reason: Any) -> str:
    if not isinstance(reason, str):
        raise queue.QueueError("an operator reason is required")
    normalized = reason.strip()
    if not normalized or len(normalized) > MAX_REASON:
        raise queue.QueueError("reason must be nonempty and at most 2048 characters")
    if any(unicodedata.category(character) == "Cc" for character in normalized):
        raise queue.QueueError("reason must not contain control characters")
    return normalized


def _open_existing(root: Path) -> sqlite3.Connection:
    database = checked_path(root / "queue.sqlite3")
    try:
        info = database.stat()
    except FileNotFoundError as failure:
        raise queue.QueueError("queue database does not exist") from failure
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
        raise queue.QueueError("unsafe queue database")
    db = sqlite3.connect(str(database), timeout=30, isolation_level=None)
    db.row_factory = sqlite3.Row
    try:
        db.execute("PRAGMA synchronous=FULL")
        if db.execute("PRAGMA journal_mode").fetchone()[0] != "wal":
            raise queue.QueueError("queue is not in its managed WAL mode")
        if db.execute("SELECT version FROM metadata").fetchone()[0] != queue.SCHEMA:
            raise queue.QueueError("unsupported queue schema; migration required")
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"jobs", "host", "events", "retention_objects", "metadata"} <= tables:
            raise queue.QueueError("queue schema is incomplete")
    except BaseException:
        db.close()
        raise
    return db


class _LiveView:
    """Thin read adapter over an existing locked connection; never writes."""
    validate_completion = staticmethod(queue.Store.validate_completion)

    def __init__(self, root: Path, db: sqlite3.Connection):
        self.root = root
        self.identity = retention.root_identity(root)
        self.job_rows = [dict(row) for row in db.execute("SELECT * FROM jobs ORDER BY sequence")]
        self.host_row = dict(db.execute("SELECT * FROM host WHERE singleton=1").fetchone())
        self.event_rows = [dict(row) for row in db.execute("SELECT * FROM events ORDER BY event_id")]
        self.objects = {(row["kind"], row["object_id"]): dict(row)
                        for row in db.execute("SELECT * FROM retention_objects")}

    def jobs(self, job_id: str | None = None) -> list[dict[str, Any]]:
        return [row for row in self.job_rows if job_id is None or row["job_id"] == job_id]


def confirm(root: Path, job_id: str, approval: str, reason: Any, *, busy: Any = None) -> dict[str, Any]:
    """Atomically register one approved administrative disposition."""
    busy = queue.external_sessions if busy is None else busy
    normalized = _normalize_reason(reason)
    if not isinstance(approval, str) or not APPROVAL.fullmatch(approval):
        raise queue.QueueError("approval must be the exact digest from the reviewed report")
    snapshot = retention.Snapshot(root)
    rows = snapshot.jobs(job_id)
    if not rows:
        raise queue.QueueError("unknown job id")
    job = rows[0]
    if job["state"] in queue.ADMINISTRATIVE:
        return _replay(snapshot, job, approval, normalized)
    report = inspect(root, job_id, busy=busy)
    if not report["canConfirm"]:
        raise Rejected(report)
    if report["approvalDigest"] != approval:
        report["canConfirm"] = False
        report["blockers"].append(_block("SNAPSHOT_CHANGED", "approval does not match current facts"))
        raise Rejected(report)
    if _locks_missing(root):
        report["canConfirm"] = False
        report["blockers"].append(_block("WORKER_ACTIVE_OR_LOCKED", "coordination locks are missing"))
        raise Rejected(report)
    # Lock order: worker -> storage(exclusive) -> admission -> transaction.
    with queue.FileLock(root / "worker.lock"), queue.storage_lock(root, exclusive=True), \
            queue.FileLock(root / "admission.lock"):
        db = _open_existing(root)
        try:
            db.execute("BEGIN IMMEDIATE")
            try:
                live = _LiveView(root, db)
                row = live.jobs(job_id)[0]
                if row["state"] in queue.ADMINISTRATIVE:
                    db.execute("ROLLBACK")
                    return _replay(live, row, approval, normalized)
                current_boot = _current_boot()
                evaluation = _evaluate(live, row, current_boot, busy=busy)
                if _worker_status(root) != "offline":
                    evaluation["execution"].append(
                        _block("WORKER_ACTIVE_OR_LOCKED", "worker came online"))
                outcome = _report(live, row, current_boot, evaluation, [], {})
                if not outcome["canConfirm"]:
                    raise Rejected(outcome)
                if outcome["approvalDigest"] != approval:
                    outcome["canConfirm"] = False
                    outcome["blockers"].append(
                        _block("SNAPSHOT_CHANGED", "approved facts changed before commit"))
                    raise Rejected(outcome)
                recorded_at = time.time()
                payload = {"schemaVersion": SCHEMA_VERSION, "disposition": DISPOSITION,
                           "jobId": row["job_id"], "attemptId": row["attempt_id"],
                           "runId": row["run_id"], "preparedDigest": row["digest"],
                           "fromState": row["state"], "previousReason": row["reason"],
                           "previousUpdatedAt": row["updated_at"], "toState": queue.ADMINISTRATIVE_STATE,
                           "operatorUid": os.getuid(), "recordedAt": recorded_at,
                           "reason": normalized, "approvalDigest": approval,
                           "historicalBootId": evaluation["assessment"]["historicalBootId"],
                           "currentBootId": current_boot, "rootIdentity": live.identity,
                           "evidenceInventory": evaluation["assessment"]["closure"],
                           "preservation": PRESERVATION, "verificationAccepted": False}
                updated = db.execute(
                    "UPDATE jobs SET state=?,reason=?,updated_at=? WHERE job_id=? AND state=?",
                    (queue.ADMINISTRATIVE_STATE, normalized, recorded_at,
                     row["job_id"], row["state"]))
                if updated.rowcount != 1:
                    raise queue.QueueError("target changed during disposition")
                db.execute("INSERT INTO events(job_id,kind,payload,created_at) VALUES(?,?,?,?)",
                           (row["job_id"], EVENT_KIND, queue.canonical_json(payload), recorded_at))
                db.execute("COMMIT")
            except BaseException:
                if db.in_transaction:
                    db.execute("ROLLBACK")
                raise
        finally:
            db.close()
        return {**payload, "replayed": False}


def cli(args: Any, *, root: Path | None = None) -> int:
    root = queue.account_root() if root is None else root
    job_id = args.inspect_job or args.confirm_job
    if job_id is None or not JOB_ID.fullmatch(job_id):
        print("host-validation: abandon requires one exact job id", file=sys.stderr)
        return 2
    if args.inspect_job is not None:
        if args.approval is not None or args.reason is not None:
            print("host-validation: --approval/--reason are only valid with --confirm", file=sys.stderr)
            return 2
        report = inspect(root, job_id)
        print(json.dumps(report, ensure_ascii=False, sort_keys=True), flush=True)
        return 0 if report["canConfirm"] else 75
    if not isinstance(args.approval, str) or not APPROVAL.fullmatch(args.approval) or args.reason is None:
        print("host-validation: --confirm requires --approval DIGEST and --reason", file=sys.stderr)
        return 2
    try:
        receipt = confirm(root, job_id, args.approval, args.reason)
    except Rejected as refusal:
        print(json.dumps(refusal.report, ensure_ascii=False, sort_keys=True), flush=True)
        return 75
    except (queue.QueueError, OSError, ValueError, KeyError, TypeError) as failure:
        print(f"host-validation: {failure}", file=sys.stderr)
        return 75
    print(json.dumps(receipt, ensure_ascii=False, sort_keys=True), flush=True)
    return 0
