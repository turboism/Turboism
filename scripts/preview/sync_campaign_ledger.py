#!/usr/bin/env python3
"""Idempotent merge of terminal queue outcomes + triage into campaign-jobs.json.

Entry shape follows the dominant "cov3 queue-synced terminal evidence"
convention already in the ledger: task/version/variant, jobId, requestKey,
status, result{runId,terminalState,validationStatus,exitCode,cleanup,
evidenceDir}, scope. evidenceDir stays relative to the TurboismValidation
root, never an absolute /home path. Matching is by jobId first, then
requestKey; existing entries keep their key order and extra keys, only
status/result/triage are refreshed.

Reads the queue database and evidence strictly read-only; never submits,
cancels or retries jobs.
"""
from __future__ import annotations

import argparse
import difflib
import json
from pathlib import Path
import sys
import tempfile
from typing import Any

import host_validation_queue as queue
import host_validation_triage as triage

LEDGER = Path(__file__).resolve().parents[2] / "validation" / "cubism-version-matrix" / "campaign-jobs.json"
SCOPE = "cov3 queue-synced terminal evidence"
FINAL_STATES = frozenset({"succeeded", "failed", "timed_out", "blocked"})


class LedgerError(RuntimeError):
    pass


def load_ledger(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise LedgerError(f"cannot read ledger {path}: {failure}") from failure
    if not isinstance(data.get("jobs"), list):
        raise LedgerError(f"ledger {path} has no jobs array")
    return data


def write_ledger(path: Path, data: dict[str, Any]) -> None:
    text = json.dumps(data, indent=1, ensure_ascii=False) + "\n"
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False,
                                     encoding="utf-8") as stream:
        stream.write(text)
        temp = Path(stream.name)
    temp.replace(path)


def relative_evidence_dir(row: dict[str, Any]) -> str:
    """<task>/<version>-<label>/queue-<id>/evidence relative to the validation
    root, matching existing ledger entries; never an absolute host path."""
    details = (row.get("outcome") or {}).get("details") or {}
    raw = details.get("evidenceDir") or ""
    if not raw:
        return ""
    path = Path(raw)
    parts = path.parts
    if len(parts) >= 4 and parts[-1] == "evidence" and parts[-2].startswith("queue-"):
        return str(Path(*parts[-4:]))
    return ""


def result_object(row: dict[str, Any]) -> dict[str, Any]:
    outcome = row.get("outcome") or {}
    details = outcome.get("details") or {}
    if row["state"] in FINAL_STATES and outcome:
        return {
            "runId": outcome.get("runId") or row.get("run_id") or "",
            "terminalState": outcome.get("terminalState") or row["state"],
            "validationStatus": outcome.get("validationStatus"),
            "exitCode": outcome.get("runnerExitCode", details.get("exitCode")),
            "cleanup": outcome.get("cleanup"),
            "evidenceDir": relative_evidence_dir(row),
        }
    return {
        "runId": "", "terminalState": row["state"],
        "validationStatus": None, "exitCode": None, "cleanup": None,
        "evidenceDir": "",
    }


def build_entry(row: dict[str, Any], record: dict[str, Any]) -> dict[str, Any]:
    """New entry in the dominant cov3 key order."""
    entry: dict[str, Any] = {
        "task": record["task"] or row["request_key"],
        "version": record["version"],
        "variant": record["variant"],
        "jobId": row["job_id"],
        "requestKey": row["request_key"],
        "status": "FINAL" if row["state"] in FINAL_STATES else "CANCELLED",
        "result": result_object(row),
    }
    if row["state"] in FINAL_STATES and record["category"] != "PASS":
        entry["triage"] = triage_object(record)
    entry["scope"] = SCOPE
    return entry


def step_result_object(row: dict[str, Any], step: dict[str, Any]) -> dict[str, Any]:
    """Per-step result: the step's own verdict over the session job's lifecycle."""
    result = result_object(row)
    if row["state"] in FINAL_STATES:
        result["validationStatus"] = step["status"]
    return result


def step_request_key(row: dict[str, Any], step: dict[str, Any]) -> str:
    return f"{row['request_key']}#{step['name']}"


def build_step_entry(row: dict[str, Any], record: dict[str, Any],
                     step: dict[str, Any]) -> dict[str, Any]:
    """One ledger row per session step, recorded under the step's original
    task spec with viaSession pointing back at the session job."""
    entry: dict[str, Any] = {
        "task": step["task"] or row["request_key"],
        "version": step["version"],
        "variant": step["variant"],
        "jobId": row["job_id"],
        "requestKey": step_request_key(row, step),
        "viaSession": row["job_id"],
        "status": "FINAL" if row["state"] in FINAL_STATES else "CANCELLED",
        "result": step_result_object(row, step),
    }
    if step.get("durationMillis") is not None:
        entry["result"]["stepDurationMillis"] = step["durationMillis"]
    if (row["state"] in FINAL_STATES and step["status"] != "PASS"
            and record["category"] != "PASS"):
        entry["triage"] = triage_object(record)
    entry["scope"] = SCOPE
    return entry


def merge_step_entry(entry: dict[str, Any], row: dict[str, Any],
                     record: dict[str, Any], step: dict[str, Any]) -> bool:
    """Refresh a session-step entry in place; returns True when changed."""
    changed = False
    status = "FINAL" if row["state"] in FINAL_STATES else "CANCELLED"
    if entry.get("status") != status:
        entry["status"] = status
        changed = True
    result = step_result_object(row, step)
    existing = entry.get("result")
    if isinstance(existing, dict):
        for key, value in result.items():
            if key == "evidenceDir" and existing.get("evidenceDir"):
                continue
            if existing.get(key) != value:
                existing[key] = value
                changed = True
    elif entry.get("result") != result:
        entry["result"] = result
        changed = True
    return changed


def triage_object(record: dict[str, Any]) -> dict[str, Any]:
    signature = record.get("signature")
    return {
        "category": record["category"],
        "ruleId": record["ruleId"],
        "signature": (
            f"{signature['class']}: {signature['message']}" if signature else None),
    }


def merge_entry(entry: dict[str, Any], row: dict[str, Any],
                record: dict[str, Any]) -> bool:
    """Refresh queue-derived fields in place; returns True when changed."""
    changed = False
    status = "FINAL" if row["state"] in FINAL_STATES else "CANCELLED"
    if entry.get("status") != status:
        entry["status"] = status
        changed = True
    result = result_object(row)
    existing = entry.get("result")
    if isinstance(existing, dict):
        for key, value in result.items():
            if key == "evidenceDir" and existing.get("evidenceDir"):
                continue
            if existing.get(key) != value:
                existing[key] = value
                changed = True
    elif "terminalState" in entry:
        # Older shape keeps terminalState/validationStatus at the top level.
        if entry.get("terminalState") != result["terminalState"]:
            entry["terminalState"] = result["terminalState"]
            changed = True
        if entry.get("validationStatus") != result["validationStatus"]:
            entry["validationStatus"] = result["validationStatus"]
            changed = True
    elif entry.get("result") != result:
        entry["result"] = result
        changed = True
    if row["state"] in FINAL_STATES and record["category"] != "PASS":
        triage_value = triage_object(record)
        if entry.get("triage") != triage_value:
            entry["triage"] = triage_value
            changed = True
    return changed


def sync(ledger: dict[str, Any], rows: list[dict[str, Any]],
         records: dict[str, dict[str, Any]],
         *, jobs_root: Path | None = None,
         prepared_root: Path | None = None) -> dict[str, int]:
    by_job = {entry.get("jobId"): entry for entry in ledger["jobs"] if entry.get("jobId")}
    by_key = {entry.get("requestKey"): entry for entry in ledger["jobs"] if entry.get("requestKey")}
    stats = {"added": 0, "updated": 0, "unchanged": 0, "skipped": 0}
    for row in rows:
        record = records[row["job_id"]]
        steps = (triage.session_steps(
            row, jobs_root=jobs_root, prepared_root=prepared_root,
            validation_root=None) if jobs_root is not None else [])
        if steps:
            # A session job maps back to one ledger row per probe step.
            for step in steps:
                entry = by_key.get(step_request_key(row, step))
                if entry is None:
                    ledger["jobs"].append(build_step_entry(row, record, step))
                    stats["added"] += 1
                elif merge_step_entry(entry, row, record, step):
                    stats["updated"] += 1
                else:
                    stats["unchanged"] += 1
            continue
        entry = by_job.get(row["job_id"]) or by_key.get(row["request_key"])
        if entry is None:
            ledger["jobs"].append(build_entry(row, record))
            stats["added"] += 1
        elif merge_entry(entry, row, record):
            stats["updated"] += 1
        else:
            stats["unchanged"] += 1
    return stats


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Idempotently merge terminal queue outcomes and triage into campaign-jobs.json")
    parser.add_argument("--ledger", default=str(LEDGER))
    parser.add_argument("--queue-db", default=str(queue.account_root() / "queue.sqlite3"))
    parser.add_argument("--rules", default=None)
    select = parser.add_mutually_exclusive_group()
    select.add_argument("--job", action="append", dest="jobs", metavar="JOB_ID")
    select.add_argument("--request-key-prefix", metavar="PREFIX")
    select.add_argument("--all-terminal", action="store_true",
                        help="sync every terminal job (default)")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the planned diff without writing the ledger")
    parser.add_argument("--json", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    database, ledger_path = Path(args.queue_db), Path(args.ledger)
    root = database.parent
    rules = triage.load_rules(Path(args.rules) if args.rules else None)
    rows = triage.iter_rows(database, job_ids=args.jobs,
                            request_key_prefix=args.request_key_prefix,
                            terminal_only=not args.jobs and not args.request_key_prefix)
    if not rows:
        print("no matching jobs", file=sys.stderr)
        return 1
    jobs_root, prepared_root = root / "jobs", root / "prepared"
    records = {row["job_id"]: triage.triage_row(
        row, rules, jobs_root=jobs_root, prepared_root=prepared_root,
        validation_root=None) for row in rows}
    ledger = load_ledger(ledger_path)
    before = json.dumps(ledger, indent=1, ensure_ascii=False)
    stats = sync(ledger, rows, records,
                 jobs_root=jobs_root, prepared_root=prepared_root)
    after = json.dumps(ledger, indent=1, ensure_ascii=False)
    if args.dry_run:
        for line in difflib.unified_diff(before.splitlines(), after.splitlines(),
                                         lineterm="", n=1):
            print(line)
        print(json.dumps({"dryRun": True, **stats}))
        return 0
    if stats["added"] or stats["updated"]:
        write_ledger(ledger_path, ledger)
    print(json.dumps(stats) if args.json else
          f"ledger: +{stats['added']} added, {stats['updated']} updated, "
          f"{stats['unchanged']} unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
