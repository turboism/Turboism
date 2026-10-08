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
