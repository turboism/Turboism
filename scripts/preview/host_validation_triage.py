#!/usr/bin/env python3
"""Read-only root-cause triage for terminal host-validation queue jobs.

The first exception block in turboism.log (class + normalized message + first
dev.turboism frame, including its caused-by chain) is combined with probe
failure markers, cubism-console.txt ERROR heads, result-properties assertion
verdicts and the durable outcome.
An ordered rule table in host-validation-triage-rules.json maps evidence to
categories (INFRA_RETRYABLE, ADMISSION_STATIC, CAPABILITY_BOUNDARY,
PROBE_DRIFT, PASS); unmatched jobs are UNKNOWN and report their signature so
a human can extend the table. FREE-license startup markers
(START_FREE / FREE_PARAMETER_LIMIT) appear on every installation and are never
failure evidence.

This tool only reads the queue database and run evidence. It never submits,
cancels or retries jobs.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Any

import host_validation_queue as queue

CATEGORIES = ("PASS", "INFRA_RETRYABLE", "ADMISSION_STATIC", "CAPABILITY_BOUNDARY",
              "PROBE_DRIFT", "NO_EVIDENCE", "UNKNOWN")
TERMINAL_STATES = frozenset({"succeeded", "failed", "cancelled", "timed_out",
                             "blocked", "abandoned"})
JOB_SELECT = ("sequence,job_id,request_key,prepared_id,digest,state,reason,"
              "run_id,evidence_json,created_at,updated_at")

EXCEPTION_LINE = re.compile(
    r"^(?P<cls>[A-Za-z_$][\w.$]*?(?:Exception|Error|Throwable|Failure))"
    r"(?::\s*(?P<msg>.*))?$")
CAUSED_LINE = re.compile(
    r"^caused by\s+(?P<cls>[A-Za-z_$][\w.$]*?(?:Exception|Error|Throwable))"
    r"(?::\s*(?P<msg>.*))?$", re.IGNORECASE)
FRAME_LINE = re.compile(r"^\s+at\s+(?P<cls>[\w.$]+)\.(?P<method>[\w$<>]+)\(")
FAILURE_MARKER = re.compile(r"\b[A-Z0-9_]{2,}(?:VALIDATION_FAILURE|PROBE_FAILURE|_FAILURE)\b")
RESULT_MARKER = re.compile(
    r"\b[A-Z0-9_]{2,}_RESULT\s+status=|\b[A-Z0-9_]{2,}_(?:TIMED_OUT|TIMEOUT)\b"
    r"|\bTIMED_OUT\b|\bNETWORK_UNAVAILABLE\b")
HEX_RUN = re.compile(r"\b[0-9a-fA-F]{16,}\b")
UUID_RUN = re.compile(r"\b[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\b")
WINDOWS_PATH = re.compile(r"[A-Za-z]:\\[^\s\"'\]]+")
UNIX_PATH = re.compile(r"(?<![\w.-])/(?:home|tmp|var|usr|opt|proc)/[^\s\"'\]]+")
NUMBER_RUN = re.compile(r"\b\d+\b")
TASK_SPEC = re.compile(r"^(?P<task>[A-Za-z0-9._-]+):(?P<version>\d{4})(?:@(?P<variant>[A-Za-z0-9._-]+))?$")
VERSION_PREFIX = re.compile(r"^(\d{4})")


class TriageError(RuntimeError):
    pass


def normalize_message(text: str, limit: int = 160) -> str:
    text = UUID_RUN.sub("<uuid>", text)
    text = HEX_RUN.sub("<hex>", text)
    text = WINDOWS_PATH.sub("<path>", text)
    text = UNIX_PATH.sub("<path>", text)
    text = NUMBER_RUN.sub("N", text)
    return " ".join(text.split())[:limit]


def _read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def extract_signature(log_text: str) -> dict[str, Any] | None:
    """First exception block: bare exception line followed by frames or a
    caused-by section. Inline mentions inside WARN/INFO log lines do not
    start a block."""
    lines = log_text.splitlines()
    for index, line in enumerate(lines):
        if line[:1].isspace():
            continue
        match = EXCEPTION_LINE.match(line)
        if match is None or match.group("cls") == "java.lang.Throwable":
            continue
        block: list[str] = []
        for follow in lines[index + 1:]:
            if follow[:1].isspace() or CAUSED_LINE.match(follow):
                block.append(follow)
            elif not follow.strip():
                if block:
                    break
            else:
                break
        if not any(FRAME_LINE.match(item) or CAUSED_LINE.match(item) for item in block):
            continue
        causes = []
        for item in block:
            caused = CAUSED_LINE.match(item.strip())
            if caused:
                causes.append({"class": caused.group("cls"),
                               "message": normalize_message(caused.group("msg") or "")})
        frame = ""
        for item in block:
            found = FRAME_LINE.match(item)
            if found and found.group("cls").startswith("dev.turboism"):
                frame = f"{found.group('cls')}.{found.group('method')}"
                break
        return {"class": match.group("cls"),
                "message": normalize_message(match.group("msg") or ""),
                "frame": frame, "line": index + 1, "causes": causes}
    return None


def extract_markers(log_text: str, non_failure: frozenset[str]) -> dict[str, list[str]]:
    failures, results = [], []
    for line in log_text.splitlines():
        if any(token in line for token in non_failure):
            continue
        if FAILURE_MARKER.search(line):
            failures.append(normalize_message(line.split("] ", 1)[-1], 200))
        elif RESULT_MARKER.search(line):
            results.append(normalize_message(line.split("] ", 1)[-1], 200))
    return {"failures": failures, "results": results}


CONSOLE_ERROR = re.compile(
    r"^ERROR\s+\[\s*(?P<cls>[A-Za-z_$][\w.$]*?(?:Exception|Error|Throwable))"
    r"(?::\s*(?P<msg>[^\]]*?))?\s*\]")


def extract_console_markers(console_text: str, non_failure: frozenset[str]) -> list[str]:
    """Cubism console wraps host stderr as 'ERROR [ <class>: <msg> ] at ...'.
    Only the exception head lines are kept; wrapped frames and known-benign
    host messages (JOCL probing, FREE startup) are dropped."""
    markers = []
    for line in console_text.splitlines():
        if any(token in line for token in non_failure):
            continue
        match = CONSOLE_ERROR.match(line.strip())
        if match:
            markers.append(normalize_message(
                f"{match.group('cls')}: {match.group('msg') or ''}", 200))
    return markers


def parse_properties(text: str) -> dict[str, str]:
    props = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    return props


def result_properties(*directories: tuple[Path | None, str]) -> dict[str, Any]:
    merged: dict[str, str] = {}
    for directory, pattern in directories:
        if directory is None or not directory.is_dir():
            continue
        for path in sorted(directory.glob(pattern)):
            merged.update(parse_properties(_read_text(path)))
    assertions = sorted(
        key.rsplit(".", 1)[0] for key in merged
        if key.startswith("assertion.") and key.endswith(".status"))
    return {
        "status": merged.get("status") or merged.get("terminal"),
        "failures": [value for key, value in sorted(merged.items())
                     if re.fullmatch(r"failure\.\d+", key)],
        "unexpectedFailures": [
            merged[f"{name}.actual"] for name in assertions
            if name.endswith("unexpectedFailure") and f"{name}.actual" in merged],
        "assertionStatuses": {name: merged[f"{name}.status"] for name in assertions},
    }


def evidence_directories(row: dict[str, Any], jobs_root: Path,
                         validation_root: Path | None) -> tuple[Path, Path | None]:
    outcome = row.get("outcome") or {}
    details = outcome.get("details") or {}
    job_evidence = jobs_root / row["job_id"] / "evidence"
    run_evidence = None
    raw = details.get("evidenceDir")
    if raw:
        candidate = Path(raw)
        if not candidate.is_absolute() and validation_root is not None:
            candidate = validation_root / candidate
        run_evidence = candidate
    return job_evidence, run_evidence


def job_spec(row: dict[str, Any], prepared_root: Path) -> dict[str, Any]:
    """task/version/variant from the prepared descriptor's taskSpec, then the
    run directory (<task>/<version>-<label>), then the request key."""
    prepared = prepared_root / row["prepared_id"] / "prepared.json"
    if prepared.is_file():
        try:
            spec = json.loads(prepared.read_text(encoding="utf-8")).get("taskSpec")
        except (OSError, json.JSONDecodeError):
            spec = None
        match = TASK_SPEC.match(spec or "")
        if match:
            return {"task": match.group("task"), "version": match.group("version"),
                    "variant": match.group("variant"), "spec": spec}
    outcome = row.get("outcome") or {}
    task_dir = (outcome.get("details") or {}).get("taskDir") or ""
    parts = Path(task_dir).parts
    if len(parts) >= 3 and parts[-1].startswith("queue-"):
        version = VERSION_PREFIX.match(parts[-2])
        if version:
            return {"task": parts[-3], "version": version.group(1),
                    "variant": None, "spec": f"{parts[-3]}:{version.group(1)}"}
    return {"task": None, "version": None, "variant": None, "spec": None}


def _value_match(values: list[str], accepted: Any) -> bool:
    wanted = {str(item).casefold() for item in accepted}
    return any(str(value).casefold() in wanted for value in values if value is not None)


def _regex_match(pattern: str, texts: list[str]) -> bool:
    compiled = re.compile(pattern)
    return any(compiled.search(text) for text in texts)


def rule_matches(when: dict[str, Any], features: dict[str, Any]) -> bool:
    for clause in ("state", "terminalState", "validationStatus", "resultStatus",
                   "terminalPassed"):
        if clause in when and not _value_match([features[clause]], when[clause]):
            return False
    signature = features["signature"] or {"class": "", "message": "", "frame": "", "causes": []}
    classes = [signature["class"]] + [cause["class"] for cause in signature["causes"]]
    messages = [signature["message"]] + [cause["message"] for cause in signature["causes"]]
    if "exceptionClass" in when and not _regex_match(when["exceptionClass"], classes):
        return False
    if "message" in when and not _regex_match(when["message"], messages):
        return False
    if "frame" in when and not _regex_match(when["frame"], [signature["frame"]]):
        return False
    if "marker" in when and not _regex_match(when["marker"], features["markers"]):
        return False
    if "console" in when and not _regex_match(when["console"], features["consoleMarkers"]):
        return False
    if "failureText" in when and not _regex_match(when["failureText"], features["failureTexts"]):
        return False
    return True


def load_rules(path: Path | None = None) -> dict[str, Any]:
    rules_path = path or Path(__file__).with_name("host-validation-triage-rules.json")
    try:
        data = json.loads(rules_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise TriageError(f"cannot read triage rules {rules_path}: {failure}") from failure
    if data.get("schemaVersion") != 1 or not isinstance(data.get("rules"), list):
        raise TriageError(f"triage rules {rules_path} must be schemaVersion 1 with a rules array")
    return data


def triage_row(row: dict[str, Any], rules: dict[str, Any], *, jobs_root: Path,
               prepared_root: Path, validation_root: Path | None) -> dict[str, Any]:
    outcome = row.get("outcome") or {}
    job_evidence, run_evidence = evidence_directories(row, jobs_root, validation_root)
    log_text = _read_text(job_evidence / "turboism.log")
    evidence_source = "job-dir"
    if not log_text and run_evidence:
        log_text = _read_text(run_evidence / "turboism.log")
        if log_text:
            evidence_source = "run-dir"
    console_text = _read_text(job_evidence / "cubism-console.txt")
    if not console_text and run_evidence:
        console_text = _read_text(run_evidence / "cubism-console.txt")
    non_failure = frozenset(rules.get("nonFailureMarkers") or ())
    console_markers = extract_console_markers(console_text, non_failure)
    markers = extract_markers(log_text, non_failure)
    terminal = ((outcome.get("details") or {}).get("postContainmentChecks") or {}
                ).get("terminalResult") or {}
    for marker in terminal.get("failureMarkers") or []:
        normalized = normalize_message(str(marker), 200)
        if normalized not in markers["results"]:
            markers["results"].append(normalized)
    props = result_properties(
        (job_evidence / "result", "*.properties"),
        (run_evidence.parent / "turboism-home" / "state" if run_evidence else None,
         "**/*result*.properties"),
    )
    signature = extract_signature(log_text)
    failure_texts = (markers["failures"] + props["failures"]
                     + props["unexpectedFailures"]
                     + [value for name, value in props["assertionStatuses"].items()])
    features = {
        "state": row["state"],
        "terminalState": outcome.get("terminalState") or row["state"],
        "validationStatus": outcome.get("validationStatus"),
        "resultStatus": props["status"],
        "terminalPassed": terminal.get("passed"),
        "signature": signature,
        "markers": markers["failures"] + markers["results"],
        "consoleMarkers": console_markers,
        "failureTexts": failure_texts,
    }
    category, rule_id, advice = "UNKNOWN", None, "no rule matched; extend host-validation-triage-rules.json"
    for rule in rules["rules"]:
        if rule_matches(rule.get("when") or {}, features):
            category, rule_id = rule["category"], rule["id"]
            advice = rule.get("advice", "")
            break
    notes = []
    no_signal = (not signature and not markers["failures"] and not markers["results"]
                 and not console_markers and not props["failures"]
                 and props["status"] is None and terminal.get("passed") is not True)
    if category == "UNKNOWN" and no_signal and row["state"] in {"failed", "timed_out"}:
        category, rule_id = "NO_EVIDENCE", "no-evidence"
        advice = ("no failure evidence anywhere; re-run the spec to regenerate "
                  "evidence before attributing a cause")
        if not log_text:
            notes.append("no turboism.log in job-dir or run-dir evidence "
                         "(retention may have reclaimed it)")
        else:
            notes.append("turboism.log ends without any verdict signal "
                         "(host exited before producing a verdict)")
    if not log_text and row["state"] in {"failed", "timed_out"} and category != "NO_EVIDENCE":
        notes.append("no turboism.log in job-dir or run-dir evidence "
                     "(retention may have reclaimed it)")
    if row["state"] in {"cancelled", "abandoned"} and category == "UNKNOWN":
        notes.append(f"job {row['state']} before a verdict; no failure evidence expected")
    if (signature is None and not markers["failures"] and category == "UNKNOWN"
            and row["state"] == "failed" and log_text):
        notes.append("failed without an exception block or probe failure marker")
    spec = job_spec(row, prepared_root)
    return {
        "jobId": row["job_id"], "requestKey": row["request_key"],
        "sequence": row.get("sequence"), **spec,
        "state": row["state"], "runId": row.get("run_id") or outcome.get("runId"),
        "terminalState": features["terminalState"],
        "validationStatus": features["validationStatus"],
        "resultStatus": props["status"],
        "category": category, "ruleId": rule_id, "advice": advice,
        "signature": signature,
        "evidenceAvailable": bool(log_text),
        "evidenceSource": evidence_source if log_text else None,
        "evidence": {
            "failureMarkers": markers["failures"][:8],
            "resultMarkers": markers["results"][:8],
            "consoleMarkers": console_markers[:8],
            "terminalResult": {"mode": terminal.get("mode"),
                               "passed": terminal.get("passed")} if terminal else None,
            "propFailures": props["failures"],
            "unexpectedFailures": props["unexpectedFailures"],
            "assertionStatuses": props["assertionStatuses"],
        },
        "notes": notes,
    }


def session_steps(row: dict[str, Any], jobs_root: Path,
                  prepared_root: Path, validation_root: Path | None) -> list[dict[str, Any]]:
    """Per-step records for a multi-probe session job.

    The coordinator plugin writes the aggregate
    state/session/session-result.properties listing every step's name, original
    task spec and terminal status; the runner copies it into evidence/result/.
    Falls back to the still-mounted task home when job evidence was reclaimed.
    An empty list means the row is not a completed session job.
    """
    job_evidence, run_evidence = evidence_directories(row, jobs_root, validation_root)
    text = _read_text(job_evidence / "result" / "session-result.properties")
    if not text and run_evidence:
        text = _read_text(run_evidence / "result" / "session-result.properties")
    if not text:
        details = (row.get("outcome") or {}).get("details") or {}
        task_dir = details.get("taskDir")
        if task_dir:
            text = _read_text(
                Path(task_dir) / "turboism-home" / "state" / "session"
                / "session-result.properties")
    if not text:
        return []
    props = parse_properties(text)
    try:
        count = int(props.get("steps", "0"))
    except ValueError:
        return []
    steps = []
    for index in range(count):
        # java.util.Properties.store escapes ':' in values written by the coordinator.
        spec = (props.get(f"step.{index}.spec") or "").replace("\\:", ":")
        match = TASK_SPEC.match(spec)
        steps.append({
            "name": (props.get(f"step.{index}.name") or f"step{index}").replace("\\:", ":"),
            "spec": spec,
            "task": match.group("task") if match else None,
            "version": match.group("version") if match else None,
            "variant": match.group("variant") if match else None,
            "status": props.get(f"step.{index}.status") or "UNKNOWN",
            "durationMillis": props.get(f"step.{index}.durationMillis"),
            "result": props.get(f"step.{index}.result"),
        })
    return steps


def load_outcome(row: dict[str, Any]) -> dict[str, Any]:
    raw = row.get("evidence_json")
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def iter_rows(database: Path, *, job_ids: list[str] | None = None,
              request_key_prefix: str | None = None,
              terminal_only: bool = False) -> list[dict[str, Any]]:
    with queue.read_only_connection(database) as db:
        sql, params = f"SELECT {JOB_SELECT} FROM jobs", []
        clauses = []
        if job_ids:
            clauses.append("job_id IN (%s)" % ",".join("?" * len(job_ids)))
            params.extend(job_ids)
        if request_key_prefix:
            clauses.append("request_key LIKE ?")
            params.append(request_key_prefix + "%")
        if terminal_only:
            clauses.append("state IN (%s)" % ",".join("?" * len(TERMINAL_STATES)))
            params.extend(sorted(TERMINAL_STATES))
        if clauses:
            sql += " WHERE " + " AND ".join(clauses)
        sql += " ORDER BY sequence"
        rows = [dict(item) for item in db.execute(sql, params).fetchall()]
    for row in rows:
        row["outcome"] = load_outcome(row)
    return rows


def retry_plan(records: list[dict[str, Any]], all_records: list[dict[str, Any]]) -> dict[str, Any]:
    """INFRA_RETRYABLE records whose spec has no later PASS anywhere in the
    queue history. Later is decided by the durable job sequence."""
    latest_pass: dict[str, int] = {}
    for record in all_records:
        if record["spec"] and record["category"] == "PASS":
            latest_pass[record["spec"]] = max(
                record["sequence"] or 0, latest_pass.get(record["spec"], 0))
    pending: dict[str, dict[str, Any]] = {}
    for record in records:
        if record["category"] != "INFRA_RETRYABLE" or not record["spec"]:
            continue
        if latest_pass.get(record["spec"], -1) > (record["sequence"] or 0):
            continue
        key = record["spec"]
        if key not in pending or (record["sequence"] or 0) > (pending[key]["sequence"] or 0):
            pending[key] = record
    specs = sorted(pending, key=lambda spec: (pending[spec]["sequence"] or 0))
    return {
        "specs": specs,
        "jobs": [{"spec": spec, "jobId": pending[spec]["jobId"],
                  "requestKey": pending[spec]["requestKey"],
                  "ruleId": pending[spec]["ruleId"]} for spec in specs],
        "prepareArgv": specs,
        "note": "feed specs to host_validation.py prepare; submission is a manual step",
    }


def summarize(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple[str, str], dict[str, Any]] = {}
    for record in records:
        signature = record["signature"]
        if signature:
            label = f"{signature['class']}: {signature['message']} @ {signature['frame'] or '-'}"
        elif record["evidence"]["failureMarkers"]:
            label = record["evidence"]["failureMarkers"][0]
        elif record["evidence"]["propFailures"]:
            label = record["evidence"]["propFailures"][0]
        elif record["evidence"]["consoleMarkers"]:
            label = "console: " + record["evidence"]["consoleMarkers"][0]
        else:
            label = f"<no signature; state={record['state']}>"
        key = (record["category"], label)
        group = groups.setdefault(key, {"category": record["category"], "signature": label,
                                        "ruleId": record["ruleId"], "count": 0, "jobs": []})
        group["count"] += 1
        group["jobs"].append(record["requestKey"])
    return sorted(groups.values(), key=lambda item: (-item["count"], item["category"]))


def _print_summary(records: list[dict[str, Any]], stream: Any) -> None:
    counts: dict[str, int] = {}
    for record in records:
        counts[record["category"]] = counts.get(record["category"], 0) + 1
    print(f"triaged {len(records)} job(s)", file=stream)
    for category in CATEGORIES:
        if counts.get(category):
            print(f"  {category:<20} {counts[category]}", file=stream)
    print("", file=stream)
    for group in summarize(records):
        print(f"[{group['category']}] x{group['count']} rule={group['ruleId']}", file=stream)
        print(f"    {group['signature']}", file=stream)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Read-only root-cause triage of terminal host-validation queue jobs")
    parser.add_argument("--queue-db", default=str(queue.account_root() / "queue.sqlite3"))
    parser.add_argument("--rules", default=None,
                        help="defaults to host-validation-triage-rules.json next to this script")
    select = parser.add_mutually_exclusive_group()
    select.add_argument("--job", action="append", dest="jobs", metavar="JOB_ID")
    select.add_argument("--request-key-prefix", metavar="PREFIX")
    select.add_argument("--all-terminal", action="store_true",
                        help="triage every job in a terminal state (default)")
    parser.add_argument("--retry-plan", action="store_true",
                        help="emit specs for INFRA_RETRYABLE jobs with no later PASS")
    parser.add_argument("--json", action="store_true", help="full per-job JSON records")
    parser.add_argument("--unknown-top", type=int, default=10,
                        help="top-N UNKNOWN signatures printed in the summary")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    database = Path(args.queue_db)
    root = database.parent
    rules = load_rules(Path(args.rules) if args.rules else None)
    rows = iter_rows(database, job_ids=args.jobs,
                     request_key_prefix=args.request_key_prefix,
                     terminal_only=not args.jobs and not args.request_key_prefix)
    if not rows:
        print("no matching jobs", file=sys.stderr)
        return 1
    jobs_root, prepared_root = root / "jobs", root / "prepared"
    records = [triage_row(row, rules, jobs_root=jobs_root,
                          prepared_root=prepared_root, validation_root=None)
               for row in rows]
    if args.retry_plan:
        if args.jobs or args.request_key_prefix:
            all_rows = iter_rows(database, terminal_only=True)
            all_records = [triage_row(row, rules, jobs_root=jobs_root,
                                      prepared_root=prepared_root, validation_root=None)
                           for row in all_rows]
        else:
            all_records = records
        print(json.dumps(retry_plan(records, all_records), indent=1))
        return 0
    if args.json:
        print(json.dumps(records, indent=1, ensure_ascii=False))
        return 0
    _print_summary(records, sys.stdout)
    unknown = [group for group in summarize(records) if group["category"] == "UNKNOWN"]
    if unknown:
        print("\nUNKNOWN signatures (extend host-validation-triage-rules.json):", file=sys.stdout)
        for group in unknown[:args.unknown_top]:
            print(f"  x{group['count']} {group['signature']}", file=sys.stdout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
