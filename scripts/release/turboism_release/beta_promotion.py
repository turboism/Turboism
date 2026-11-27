"""Verify a same-SHA Beta candidate run before fast Stable promotion."""
from __future__ import annotations

import re
from typing import Any

from .build_identity import BRANCH as LEDGER_BRANCH
from .build_identity import decode_file, identity
from .channels import resolve_version
from .contracts import ReleaseError

REPOSITORY = "turboism/Turboism"
WORKFLOW_FILE = "release.yml"
WORKFLOW_PATH = ".github/workflows/release.yml"
WORKFLOW_NAME = "Product release candidate"
MAIN_BRANCH = "main"
DISPATCH_EVENT = "workflow_dispatch"
COMPLETED_STATUS = "completed"
SUCCESS_CONCLUSION = "success"
BETA_CHANNEL = "beta"
STABLE_CHANNEL = "stable"
BUILD_MODE_FULL = "full"
BUILD_MODE_FAST_STABLE = "fast-stable"
REQUIRED_BETA_JOBS = ("build", "windows-release-verification")
RUN_ID_PATTERN = re.compile(r"[1-9][0-9]{0,19}")
ATTEMPT_PATTERN = re.compile(r"[1-9][0-9]{0,9}")
SOURCE_SHA_PATTERN = re.compile(r"[a-f0-9]{40}")
JOBS_PAGE_SIZE = 100
RUN_DISCOVERY_LIMIT = 20
MAX_BUILD_NUMBER = 9007199254740991


def parse_beta_source_args(
    beta_run_id: str | None,
    beta_attempt: str | int | None,
) -> tuple[str, int] | None:
    """Parse and validate paired Beta run ID and attempt arguments."""
    run_id = "" if beta_run_id is None else str(beta_run_id).strip()
    attempt_raw = "" if beta_attempt is None else str(beta_attempt).strip()
    if not run_id and not attempt_raw:
        return None
    if not run_id or not attempt_raw:
        raise ValueError(
            "Provide both promote_from_beta_run_id and promote_from_beta_attempt together"
        )
    if not RUN_ID_PATTERN.fullmatch(run_id):
        raise ValueError("Invalid Beta source run ID")
    if not ATTEMPT_PATTERN.fullmatch(attempt_raw):
        raise ValueError("Invalid Beta source run attempt")
    return run_id, int(attempt_raw)


def _require_run_metadata(
    run: Any,
    source_sha: str,
    beta_run_id: str,
    beta_attempt: int,
) -> None:
    """Enforce workflow, repository, branch, commit, and status bindings on the Beta run."""
    if not isinstance(run, dict):
        raise ValueError("Beta source run response must be a JSON object")
    for repo_key in ("repository", "head_repository"):
        repo = run.get(repo_key)
        if not isinstance(repo, dict) or repo.get("full_name", "").lower() != REPOSITORY.lower():
            raise ValueError("Beta source run originated outside the release repository")
    if (
        str(run.get("id")) != beta_run_id
        or run.get("run_attempt") != beta_attempt
        or run.get("path") != WORKFLOW_PATH
        or run.get("name") != WORKFLOW_NAME
        or run.get("event") != DISPATCH_EVENT
        or run.get("head_branch") != MAIN_BRANCH
        or run.get("head_sha") != source_sha
        or run.get("status") != COMPLETED_STATUS
        or run.get("conclusion") != SUCCESS_CONCLUSION
    ):
        raise ValueError(
            "Beta source run must be a completed successful main workflow_dispatch of "
            "release.yml at the exact source commit"
        )


def _require_verified_jobs(jobs_payload: Any) -> None:
    """Require both the full checkRelease build job and Windows verification job to pass."""
    if not isinstance(jobs_payload, dict):
        raise ValueError("Beta source job list must be a JSON object")
    jobs = jobs_payload.get("jobs")
    total_count = jobs_payload.get("total_count", 0)
    if not isinstance(jobs, list) or type(total_count) is not int or total_count > len(jobs):
        raise ValueError("Beta source job enumeration is incomplete")
    for required_name in REQUIRED_BETA_JOBS:
        matched = [job for job in jobs if isinstance(job, dict) and job.get("name") == required_name]
        if len(matched) != 1 or matched[0].get("conclusion") != SUCCESS_CONCLUSION:
            raise ValueError(
                f"Beta source run must complete '{required_name}' successfully before fast Stable promotion"
            )


def _require_beta_ledger_entry(
    record: Any,
    source_sha: str,
    declared_version: str,
    beta_run_id: str,
    beta_attempt: int,
) -> dict[str, Any]:
    """Bind the Beta run attempt to an immutable Beta entry in the build ledger."""
    if not isinstance(record, dict):
        raise ValueError("Beta build ledger entry must be a JSON object")
    version = record.get("version")
    if not isinstance(version, str):
        raise ValueError("Beta build ledger entry lacks a valid version")
    resolve_version(declared_version, BETA_CHANNEL, version)
    expected = identity(version, source_sha, beta_run_id, beta_attempt)
    build_number = record.get("buildNumber")
    if (
        record.get("channel") != BETA_CHANNEL
        or type(build_number) is not int
        or not 1 <= build_number < MAX_BUILD_NUMBER
        or any(record.get(key) != value for key, value in expected.items())
    ):
        raise ValueError("Beta build ledger entry does not match the source commit or run attempt")
    return record


def verify_source_beta_run(
    github: Any,
    source_sha: str,
    declared_version: str,
    beta_run_id: str,
    beta_attempt: int,
    *,
    current_run_id: str = "",
) -> dict[str, Any]:
    """Verify that a prior same-SHA Beta run passed all release gates."""
    if not SOURCE_SHA_PATTERN.fullmatch(source_sha):
        raise ValueError("Invalid source commit SHA")
    parsed = parse_beta_source_args(beta_run_id, beta_attempt)
    if parsed is None:
        raise ValueError("Beta source run ID and attempt are required")
    run_id, attempt = parsed
    if current_run_id and run_id == str(current_run_id):
        raise ValueError("Fast Stable promotion cannot reference its own workflow run")
    run = github.api(f"actions/runs/{run_id}/attempts/{attempt}")
    _require_run_metadata(run, source_sha, run_id, attempt)
    jobs = github.api(f"actions/runs/{run_id}/attempts/{attempt}/jobs?per_page={JOBS_PAGE_SIZE}")
    _require_verified_jobs(jobs)
    raw_entry = github.api(f"contents/entries/{run_id}-{attempt}.json?ref={LEDGER_BRANCH}")
    record = _require_beta_ledger_entry(
        decode_file(raw_entry),
        source_sha,
        declared_version,
        run_id,
        attempt,
    )
    return {
        "runId": run_id,
        "runAttempt": attempt,
        "version": record["version"],
        "buildNumber": record["buildNumber"],
        "sourceRevision": source_sha,
    }


def find_latest_verified_beta_run(
    github: Any,
    source_sha: str,
    declared_version: str,
) -> dict[str, Any]:
    """Locate the newest verified same-SHA Beta candidate run on main."""
    if not SOURCE_SHA_PATTERN.fullmatch(source_sha):
        raise ValueError("Invalid source commit SHA")
    listing = github.api(
        f"actions/workflows/{WORKFLOW_FILE}/runs"
        f"?branch={MAIN_BRANCH}&event={DISPATCH_EVENT}&status={SUCCESS_CONCLUSION}"
        f"&head_sha={source_sha}&per_page={RUN_DISCOVERY_LIMIT}"
    )
    runs = listing.get("workflow_runs") if isinstance(listing, dict) else None
    if not isinstance(runs, list):
        raise ValueError("Unable to list release candidate runs for this commit")
    for candidate in runs:
        if not isinstance(candidate, dict):
            continue
        run_id = candidate.get("id")
        attempt = candidate.get("run_attempt")
        if run_id is None or type(attempt) is not int or attempt < 1:
            continue
        try:
            return verify_source_beta_run(
                github,
                source_sha,
                declared_version,
                str(run_id),
                attempt,
            )
        except (ValueError, KeyError, ReleaseError):
            continue
    raise ValueError(
        f"No verified Beta candidate run exists for commit {source_sha} and base version {declared_version}"
    )
