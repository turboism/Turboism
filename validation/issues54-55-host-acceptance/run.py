#!/usr/bin/env python3
"""Acceptance client for the existing queue; never launches or signals Editor."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import pwd
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "scripts/preview/host_validation.py"


def invoke(arguments: list[str], output: Path) -> dict:
    result = subprocess.run([sys.executable, str(CLI), *arguments], cwd=ROOT,
                            capture_output=True, text=True)
    output.write_text(result.stdout)
    output.with_suffix(".stderr").write_text(result.stderr)
    if result.returncode:
        raise RuntimeError(f"queue client failed ({result.returncode}); see {output.name}")
    return json.loads(result.stdout)


def manifest(output: Path) -> Path:
    data = json.loads((ROOT / "scripts/preview/host-validation-tasks.json").read_text())
    versions = data["tasks"]["incremental-update"]["versions"]
    data["tasks"] = {k: data["tasks"][k] for k in ("model-update-skip", "incremental-update")}
    for task in data["tasks"].values():
        for arguments in task["variants"].values():
            arguments.extend(["--jvm-option", "-Dturboism.validation.nativeStartupFreeOnly=true"])
    for name, wrapper in (("product-ui-lifecycle", "product-ui-lifecycle"),
                          ("incremental-undo", "incremental-undo")):
        data["tasks"][name] = {
            "description": "Task-only functional acceptance; no timing claim.",
            "command": f"scripts/preview/run-{wrapper}-host-validation.sh",
            "versions": versions,
            "resources": {"host-slot": 1, "display-input": 1},
            "arguments": ["{version}", "--run-label", "{runLabel}", "--jvm-option",
                          "-Dturboism.validation.nativeStartupFreeOnly=true"],
        }
    path = output / "tasks.json"
    path.write_text(json.dumps(data, indent=2) + "\n")
    return path


def copy_once(source: str | Path, target: str | Path) -> str:
    source, target = Path(source), Path(target)
    if target.exists():
        if source.read_bytes() != target.read_bytes():
            raise RuntimeError(f"preserved evidence changed: {target}")
        return str(target)
    return shutil.copy2(source, target)


def preserve(job: dict, output: Path) -> dict:
    """Copy only this task's evidence; never open the shared live database."""
    evidence = json.loads(job.get("evidence_json") or "{}")
    directory = output / "jobs" / job["job_id"]
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "job.json").write_text(json.dumps(job, indent=2))
    (directory / "outcome.json").write_text(json.dumps(evidence, indent=2))
    account = Path(pwd.getpwuid(__import__("os").getuid()).pw_dir)
    queue = account / ".local/state/turboism/host-validation"
    source = queue / "jobs" / job["job_id"]
    for name in ("evidence", "runner.log", "containment.json", "runner-identity.json"):
        path = source / name
        if path.is_dir():
            shutil.copytree(path, directory / name, dirs_exist_ok=True, copy_function=copy_once)
        elif path.is_file():
            copy_once(path, directory / name)
    prepared = queue / "prepared" / job["prepared_id"] / "prepared.json"
    if prepared.is_file():
        copy_once(prepared, directory / "prepared.json")
    task = evidence.get("details", {}).get("taskDir")
    if task:
        home = Path(task) / "turboism-home"
        for name in ("state", "logs"):
            path = home / name
            if path.is_dir():
                shutil.copytree(path, directory / ("home-" + name), dirs_exist_ok=True, copy_function=copy_once)
    return evidence


def run(spec: str, label: str, output: Path, tasks: Path) -> dict:
    stem = label + "-" + spec.replace(":", "-").replace("@", "-")
    print("PREPARE " + spec, flush=True)
    prepared = invoke(["--manifest", str(tasks), "prepare", spec, "--run-label", label,
                       "--full-matrix"], output / (stem + "-prepare.json"))
    submitted = invoke(["submit", "--prepared", prepared["preparedId"], "--request-id", stem,
                        "--full-matrix", "--json"], output / (stem + "-submit.json"))
    job = submitted["job"]
    print("SUBMIT " + spec + " " + job["job_id"], flush=True)
    result = subprocess.run([sys.executable, str(CLI), "wait", job["job_id"]], cwd=ROOT,
                            capture_output=True, text=True)
    (output / (stem + "-wait.json")).write_text(result.stdout)
    (output / (stem + "-wait.stderr")).write_text(result.stderr)
    if not result.stdout.strip():
        raise RuntimeError("wait produced no structured outcome; inspect queue before continuing")
    job = json.loads(result.stdout)["job"]
    evidence = preserve(job, output)
    row = {"spec": spec, "label": label, "jobId": job["job_id"],
           "preparedId": job["prepared_id"], "runId": job["run_id"], "state": job["state"],
           **{key: evidence.get(key) for key in ("validationStatus", "cleanup", "normalExit",
                                                "identityVerified", "fixtureUnchanged")}}
    with (output / "matrix.jsonl").open("a") as stream:
        stream.write(json.dumps(row) + "\n")
    print("RESULT " + spec + " " + job["state"] + " cleanup=" + str(row["cleanup"]), flush=True)
    if row["cleanup"] != "safe" or job["state"] == "quarantined":
        raise RuntimeError("unproven cleanup; retain evidence and inspect before further admission")
    return row


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", required=True, type=Path)
    parser.add_argument("--label", required=True)
    parser.add_argument("specs", nargs="+")
    args = parser.parse_args()
    output = args.evidence_root.resolve()
    if output.is_relative_to(ROOT):
        raise SystemExit("raw evidence must survive workspace archival: choose an external directory")
    output.mkdir(parents=True, exist_ok=True)
    tasks = manifest(output)
    failed = []
    for spec in args.specs:
        row = run(spec, args.label, output, tasks)
        if row["state"] != "succeeded":
            failed.append(spec)
    if failed:
        raise SystemExit("Completed independent tasks; inspect failed assertions: " + ", ".join(failed))


if __name__ == "__main__":
    main()
