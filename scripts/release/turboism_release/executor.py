"""Local release-plan verification and production dispatch boundary."""
from __future__ import annotations

import os
import subprocess
from pathlib import Path
from typing import Any

from .contracts import ReleaseError, read_document, write_document
from .planner import initial_state


TERMINAL = {"verified"}


def load_or_create_state(plan: dict[str, Any], state_path: Path) -> dict[str, Any]:
    if state_path.exists():
        state = read_document(state_path, "state")
        if state.get("planId") != plan.get("planId"):
            raise ReleaseError("resume state belongs to a different release plan")
        expected = {step["id"] for step in plan.get("steps", [])}
        if set(state.get("steps", {})) != expected:
            raise ReleaseError("resume state step set does not match the release plan")
        return state
    state = initial_state(plan)
    write_document(state_path, state, "state")
    return state


def verify_plan_state(plan: dict[str, Any], state: dict[str, Any] | None = None) -> None:
    seen = set()
    for step in plan.get("steps", []):
        step_id = step.get("id")
        requires = step.get("requires")
        if not isinstance(step_id, str) or not step_id or step_id in seen:
            raise ReleaseError("release plan contains an invalid or duplicate step id")
        if not isinstance(requires, list) or not all(isinstance(item, str) for item in requires):
            raise ReleaseError(f"release plan step {step_id} has invalid dependencies")
        missing = [item for item in requires if item not in seen]
        if missing:
            raise ReleaseError(f"release plan step {step_id} depends on later or unknown steps: {missing}")
        seen.add(step_id)
    if plan.get("intent") == "none" and seen:
        raise ReleaseError("no-op plan must not contain mutation steps")
    if state is not None:
        for step_id, entry in state.get("steps", {}).items():
            if not isinstance(entry, dict) or entry.get("status") not in (
                "pending", "running", "verified", "failed"
            ):
                raise ReleaseError(f"state for {step_id} has an invalid status")


def require_production_confirmation(plan: dict[str, Any], production: bool, confirmation: str | None) -> None:
    if not production:
        raise ReleaseError("publish/resume is dry-run by default; pass --production to mutate remote state")
    revision = plan.get("source", {}).get("revision")
    expected = f"publish:{revision}"
    if confirmation != expected:
        raise ReleaseError(f"production confirmation must be exactly {expected}")


def dispatch_production(
    plan_path: Path,
    plan: dict[str, Any],
    *,
    candidate_run_id: str | None,
    production: bool,
    confirmation: str | None,
    workflow: str,
    repo: str,
) -> None:
    """Dispatch the protected publisher workflow; it owns every remote mutation."""
    require_production_confirmation(plan, production, confirmation)
    if candidate_run_id is None or not candidate_run_id.isdigit() or len(candidate_run_id) > 20:
        raise ReleaseError("production dispatch requires --candidate-run-id from a completed candidate workflow")
    if os.environ.get("GITHUB_ACTIONS") == "true":
        raise ReleaseError("the local dispatch command must not recursively dispatch from GitHub Actions")
    completed = subprocess.run(
        [
            "gh", "workflow", "run", workflow,
            "--repo", repo,
            "--ref", "main",
            "-f", f"candidate_run_id={candidate_run_id}",
            "-f", f"source_sha={plan['source']['revision']}",
            "-f", f"plan_id={plan['planId']}",
        ],
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or str(completed.returncode)
        raise ReleaseError(f"cannot dispatch protected release publisher: {detail}")
    print(
        f"dispatched {repo}:{workflow} for plan {plan['planId']} "
        f"from {plan_path}"
    )
