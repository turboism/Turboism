"""Finalize exact-host evidence only after the outside supervisor proves containment empty.

The Runner's in-container result is preliminary. This module cannot release a
queue slot or signal processes; it verifies fixed inputs again, archives evidence
and returns a lifecycle record to the single queue owner.
"""
from __future__ import annotations

from pathlib import Path
import re
import shutil
import subprocess
from typing import Any

from host_validation_queue import (
    BOOLEAN_FLAGS, QueueError, atomic_json, file_digest, tree_inventory, runtime_digest,
)

HOSTS = {
    "5203": ("Live2D Cubism 5.2", "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"),
    "5302": ("Live2D Cubism 5.3", "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"),
    "5303": ("Live2D Cubism 5.3.03", "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166"),
}


def checked_path(path: Path) -> Path:
    if not path.is_absolute() or ".." in path.parts:
        raise QueueError("evidence path must be absolute and confined")
    if any(parent.is_symlink() for parent in (path, *path.parents)):
        raise QueueError("evidence path traverses a symlink")
    return path


def layout(descriptor: dict[str, Any], prepared_root: Path, job: dict[str, Any]) -> dict[str, Any]:
    values: dict[str, list[str]] = {}
    argv = descriptor["argv"]
    index = 0
    while index < len(argv):
        flag = argv[index]
        if flag in BOOLEAN_FLAGS:
            values.setdefault(flag, []).append("true")
            index += 1
        else:
            values.setdefault(flag, []).append(argv[index + 1].replace("@INPUT@", str(prepared_root)))
            index += 2
    def one(flag: str) -> str:
        entries = values.get(flag, [])
        if len(entries) != 1:
            raise QueueError(f"expected one canonical option: {flag}")
        return entries[0]
    name, version, label = one("--name"), one("--version"), one("--run-label")
    if version not in HOSTS or any(not re.fullmatch(r"[A-Za-z0-9._-]+", v) or v in {".", ".."}
                                  for v in (name, label, job["run_id"])):
        raise QueueError("invalid canonical task identity")
    task = checked_path(Path(one("--host-root")) / name / f"{version}-{label}" / job["run_id"])
    source = checked_path(Path(one("--fixture-host" if "--fixture-host" in values else "--fixture-local")))
    explicit = values.get("--fixture-name")
    suffix = "-" + explicit[0] if explicit else ("." + source.name.rsplit(".", 1)[1] if "." in source.name else "")
    fixture = checked_path(task / (job["run_id"] + suffix))
    prefix = checked_path(task / "prefix")
    relative_host = Path("pfx/drive_c/Program Files") / HOSTS[version][0]
    golden = checked_path(Path(one("--golden-prefix")) / relative_host)
    cloned = checked_path(prefix / relative_host)
    jars = [(checked_path(Path(one("--agent"))), checked_path(task / "turboism-agent.jar"))]
    for flag, directory in (("--plugin", task / "turboism-home/plugins"), ("--aux-agent", task / "agents")):
        for entry in values.get(flag, []):
            source_name, separator, destination_name = entry.partition(":")
            destination_name = destination_name if separator else Path(source_name).name
            if not re.fullmatch(r"[A-Za-z0-9._-]+", destination_name) or destination_name in {".", ".."}:
                raise QueueError("invalid staged artifact name")
            jars.append((checked_path(Path(source_name)), checked_path(directory / destination_name)))
    return {"task": task, "prefix": prefix, "fixture": fixture, "source": source,
            "golden": golden, "cloned": cloned, "jarHash": HOSTS[version][1],
            "jars": jars, "keepPrefix": "--keep-prefix" in values, "options": values}


def terminal_result(paths: dict[str, Any]) -> dict[str, Any]:
    """Recheck canonical terminal/failure evidence after all contained writers exit."""
    options = paths["options"]
    home = checked_path(paths["task"] / "turboism-home")
    logs = checked_path(home / "logs/runtime")
    contents = []
    if logs.exists():
        tree_inventory(logs)
        contents = [file.read_bytes() for file in sorted(logs.rglob("*.log"))]
    failures = [marker for marker in options.get("--failure-marker", [])
                if marker and any(marker.encode() in raw for raw in contents)]
    marker = options.get("--result-marker", [""])[0]
    if marker:
        passed = any(marker.encode() in raw for raw in contents)
        return {"mode": "marker", "passSeen": passed, "failureMarkers": failures,
                "passed": passed and not failures}
    relative = Path(options.get("--result-file", [""])[0])
    if str(relative) == "." or relative.is_absolute() or ".." in relative.parts:
        raise QueueError("canonical final result file is required and must be task-relative")
    target = checked_path(home / relative)
    raw = target.read_bytes()
    # Match the Runner's exact LF/CRLF record semantics, not a substring or prefix.
    lines = [line.removesuffix(b"\r") for line in raw.split(b"\n")]
    pass_line = options.get("--result-pass-line", [""])[0]
    fail_line = options.get("--result-fail-line", [""])[0]
    passed = bool(pass_line) and pass_line.encode() in lines
    failed = bool(fail_line) and fail_line.encode() in lines
    return {"mode": "file", "path": str(target), "sha256": file_digest(target),
            "passSeen": passed, "failSeen": failed, "failureMarkers": failures,
            "passed": passed and not failed and not failures}

def finalize(job: dict[str, Any], descriptor: dict[str, Any], prepared_root: Path,
             directory: Path, preliminary: dict[str, Any] | None, proof: dict[str, Any],
             runner_exit: int | None, requested: str | None = None) -> dict[str, Any]:
    identity = {"jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"]}
    if type(proof.get("schemaVersion")) is not int or proof["schemaVersion"] != 1 or proof.get("cleanup") != "safe" or any(
            proof.get(key) != value for key, value in identity.items()):
        raise QueueError("matching kernel containment cleanup proof is required")
    paths = layout(descriptor, prepared_root, job)
    evidence = {"schemaVersion": 1, **identity, "cleanup": "safe", "validationStatus": "UNKNOWN",
                "identityVerified": False, "fixtureUnchanged": False, "normalExit": False,
                "runnerExitCode": runner_exit, "containment": proof, "details": {}}
    checks: dict[str, Any] = {}
    if preliminary is not None:
        if type(preliminary.get("schemaVersion")) is not int or preliminary["schemaVersion"] != 1 or any(preliminary.get(k) != v for k, v in identity.items()):
            raise QueueError("preliminary Runner evidence identity mismatch")
        details = preliminary.get("details")
        if not isinstance(details, dict) or details.get("cleanupOwner") != "supervisor":
            raise QueueError("Runner did not delegate final cleanup to its admitted supervisor")
        if details.get("taskDir") != str(paths["task"]):
            raise QueueError("Runner task directory does not match canonical prepared input")
        evidence["details"] = dict(details)
        evidence["normalExit"] = preliminary.get("normalExit") is True
        try:
            checks["fixtureSourceSha256"] = file_digest(paths["source"])
            checks["fixtureCopySha256"] = file_digest(paths["fixture"])
            evidence["fixtureUnchanged"] = (
                preliminary.get("fixtureUnchanged") is True and
                checks["fixtureSourceSha256"] == details.get("sourceFixtureBeforeSha256") and
                checks["fixtureCopySha256"] == details.get("fixtureBeforeSha256")
            )
            checks["goldenJarSha256"] = file_digest(paths["golden"] / "app/lib/Live2D_Cubism.jar")
            checks["clonedJarSha256"] = file_digest(paths["cloned"] / "app/lib/Live2D_Cubism.jar")
            checks["goldenBatSha256"] = file_digest(paths["golden"] / "CubismEditor5.bat")
            checks["clonedBatSha256"] = file_digest(paths["cloned"] / "CubismEditor5.bat")
            checks["stagedArtifacts"] = [
                {"source": str(source), "staged": str(staged),
                 "sourceSha256": file_digest(source), "stagedSha256": file_digest(staged)}
                for source, staged in paths["jars"]
            ]
            checks["hostDependencies"] = [
                {**dependency, "observedSha256": runtime_digest(checked_path(Path(dependency["path"]))) }
                for dependency in descriptor["hostDependencies"]
            ]
            evidence["identityVerified"] = (
                preliminary.get("identityVerified") is True and details.get("goldenUnchanged") is True and
                checks["goldenJarSha256"] == checks["clonedJarSha256"] == paths["jarHash"] and
                checks["goldenBatSha256"] == details.get("goldenBatBeforeSha256") and
                checks["clonedBatSha256"] == details.get("clonedBatBeforeSha256") and
                all(row["sourceSha256"] == row["stagedSha256"] for row in checks["stagedArtifacts"]) and
                all(row["sha256"] == row["observedSha256"] for row in checks["hostDependencies"])
            )
            checks["terminalResult"] = terminal_result(paths)
            if (details.get("validationComplete") is True and evidence["normalExit"] and
                    evidence["identityVerified"] and evidence["fixtureUnchanged"] and runner_exit == 0 and
                    checks["terminalResult"]["passed"] is True):
                evidence["validationStatus"] = "PASS"
            else:
                evidence["validationStatus"] = "FAIL"
        except OSError as failure:
            checks["error"] = str(failure)
            evidence["validationStatus"] = "FAIL"
    evidence["details"]["postContainmentChecks"] = checks
    task_evidence = checked_path(paths["task"] / "evidence")
    output = checked_path(directory / "evidence")
    output.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Keep the complete preliminary evidence separate from the authoritative verdict.
    if task_evidence.is_dir():
        tree_inventory(task_evidence)  # Reject links/special files, never follow them in collection.
        shutil.copytree(task_evidence, output / "final-task", dirs_exist_ok=True)
    home = checked_path(paths["task"] / "turboism-home")
    if home.is_dir():
        archiver = prepared_root / "tool/scripts/preview/archive-cubism-host-evidence.sh"
        subprocess.run(["bash", str(archiver), str(home), str(output / "final-task")],
                       check=True, timeout=30, stdin=subprocess.DEVNULL, capture_output=True)
    state = requested or ("succeeded" if evidence["validationStatus"] == "PASS" else "failed")
    if state == "succeeded" and not paths["keepPrefix"] and paths["prefix"].exists():
        checked_path(paths["prefix"])
        if not shutil.rmtree.avoids_symlink_attacks:
            raise QueueError("safe task-prefix removal is unavailable")
        shutil.rmtree(paths["prefix"])
    evidence["details"]["prefixRetained"] = paths["prefix"].exists()
    evidence["details"]["taskOwnedCleanup"] = True
    evidence["terminalState"] = state
    evidence["finalizedBy"] = "contained-supervisor"
    atomic_json(output / "lifecycle-result.json", evidence)
    if task_evidence.is_dir():
        atomic_json(task_evidence / "lifecycle-result.json", evidence)
    return evidence
