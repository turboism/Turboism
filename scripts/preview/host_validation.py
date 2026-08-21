#!/usr/bin/env python3
"""Resource-aware dispatcher for Turboism exact-host validation wrappers.

This scheduler owns admission only. Existing wrappers and
run-cubism-host-validation.sh remain responsible for project copies, Cubism
launch, result polling, evidence, and task-owned cleanup.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import datetime as dt
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import threading
import time
from typing import Any, Iterable

FORMAT = "turboism.host-validation.tasks"
SCHEMA_VERSION = 1
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
SAFE_REMOTE_ROOT = re.compile(r"^/[A-Za-z0-9._/-]+$")
BUSY_EXIT = 75
HEARTBEAT_SECONDS = 30


class SchedulerError(RuntimeError):
    pass


@dataclasses.dataclass(frozen=True)
class Resource:
    name: str
    capacity: int
    description: str


@dataclasses.dataclass(frozen=True)
class Task:
    name: str
    description: str
    command: str
    versions: tuple[str, ...]
    resources: dict[str, int]
    arguments: tuple[str, ...] | None
    variants: dict[str, tuple[str, ...]]
    default_variant: str | None
    runnable: bool
    blocked_reason: str | None


@dataclasses.dataclass(frozen=True)
class Manifest:
    path: Path
    root: Path
    scheduler_root: str
    resources: dict[str, Resource]
    tasks: dict[str, Task]


@dataclasses.dataclass(frozen=True)
class Request:
    task: Task
    version: str
    variant: str | None
    run_label: str

    @property
    def spec(self) -> str:
        suffix = f"@{self.variant}" if self.variant else ""
        return f"{self.task.name}:{self.version}{suffix}"


@dataclasses.dataclass(frozen=True)
class Lease:
    owner: str
    request: Request
    slots: tuple[str, ...]


@dataclasses.dataclass(frozen=True)
class RunResult:
    request: Request
    return_code: int


def require_dict(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise SchedulerError(f"{label} must be an object")
    return value


def require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise SchedulerError(f"{label} must be a non-empty string")
    return value


def require_string_list(value: Any, label: str) -> tuple[str, ...]:
    if not isinstance(value, list) or not value:
        raise SchedulerError(f"{label} must be a non-empty array")
    result = tuple(require_string(item, f"{label} entry") for item in value)
    return result


def validate_remote_root(value: str) -> str:
    if not SAFE_REMOTE_ROOT.fullmatch(value) or "//" in value or "/../" in f"{value}/":
        raise SchedulerError("scheduler root must be a normalized absolute host path")
    return value.rstrip("/") or "/"


def load_manifest(path: Path) -> Manifest:
    manifest_path = path.resolve()
    try:
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise SchedulerError(f"cannot read manifest {manifest_path}: {failure}") from failure
    data = require_dict(raw, "manifest")
    if data.get("format") != FORMAT:
        raise SchedulerError(f"manifest format must be {FORMAT}")
    if data.get("schemaVersion") != SCHEMA_VERSION:
        raise SchedulerError(f"manifest schemaVersion must be {SCHEMA_VERSION}")
    scheduler_root = validate_remote_root(require_string(
        data.get("defaultSchedulerRoot"), "defaultSchedulerRoot"
    ))

    resource_values = require_dict(data.get("resources"), "resources")
    resources: dict[str, Resource] = {}
    for name, value in sorted(resource_values.items()):
        if not SAFE_NAME.fullmatch(name):
            raise SchedulerError(f"invalid resource name: {name}")
        fields = require_dict(value, f"resource {name}")
        capacity = fields.get("capacity")
        if not isinstance(capacity, int) or isinstance(capacity, bool) or capacity < 1:
            raise SchedulerError(f"resource {name} capacity must be a positive integer")
        resources[name] = Resource(
            name, capacity, require_string(fields.get("description"), f"resource {name} description")
        )
    if not resources:
        raise SchedulerError("manifest must declare resources")

    root = Path(__file__).resolve().parents[2]
    task_values = require_dict(data.get("tasks"), "tasks")
    tasks: dict[str, Task] = {}
    for name, value in sorted(task_values.items()):
        if not SAFE_NAME.fullmatch(name):
            raise SchedulerError(f"invalid task name: {name}")
        fields = require_dict(value, f"task {name}")
        command = require_string(fields.get("command"), f"task {name} command")
        command_path = (root / command).resolve()
        scripts_root = (root / "scripts" / "preview").resolve()
        if not command_path.is_relative_to(scripts_root) or not command_path.is_file():
            raise SchedulerError(f"task {name} command must be an existing scripts/preview file")
        versions = require_string_list(fields.get("versions"), f"task {name} versions")
        if any(version not in {"5203", "5302"} for version in versions):
            raise SchedulerError(f"task {name} contains an unsupported exact host version")

        request_values = require_dict(fields.get("resources"), f"task {name} resources")
        requests: dict[str, int] = {}
        for resource_name, quantity in sorted(request_values.items()):
            resource = resources.get(resource_name)
            if resource is None:
                raise SchedulerError(f"task {name} references unknown resource {resource_name}")
            if not isinstance(quantity, int) or isinstance(quantity, bool) or not 1 <= quantity <= resource.capacity:
                raise SchedulerError(
                    f"task {name} resource {resource_name} must be between 1 and {resource.capacity}"
                )
            requests[resource_name] = quantity
        if "host-slot" not in requests:
            raise SchedulerError(f"task {name} must reserve host-slot")

        arguments_value = fields.get("arguments")
        variants_value = fields.get("variants")
        if (arguments_value is None) == (variants_value is None):
            raise SchedulerError(f"task {name} must declare exactly one of arguments or variants")
        arguments = None if arguments_value is None else require_string_list(
            arguments_value, f"task {name} arguments"
        )
        variants: dict[str, tuple[str, ...]] = {}
        if variants_value is not None:
            for variant_name, variant_args in sorted(require_dict(
                variants_value, f"task {name} variants"
            ).items()):
                if not SAFE_NAME.fullmatch(variant_name):
                    raise SchedulerError(f"task {name} has invalid variant {variant_name}")
                variants[variant_name] = require_string_list(
                    variant_args, f"task {name} variant {variant_name}"
                )
            if not variants:
                raise SchedulerError(f"task {name} variants must not be empty")
        default_variant = fields.get("defaultVariant")
        if default_variant is not None:
            default_variant = require_string(default_variant, f"task {name} defaultVariant")
            if default_variant not in variants:
                raise SchedulerError(f"task {name} defaultVariant is not declared")
        elif variants:
            raise SchedulerError(f"task {name} with variants requires defaultVariant")

        for template in ([arguments] if arguments is not None else variants.values()):
            for argument in template:
                unresolved = re.findall(r"\{[^{}]+\}", argument)
                if any(item not in {"{version}", "{runLabel}"} for item in unresolved):
                    raise SchedulerError(f"task {name} has an unsupported argument placeholder")

        runnable = fields.get("runnable", True)
        if not isinstance(runnable, bool):
            raise SchedulerError(f"task {name} runnable must be boolean")
        blocked_reason = fields.get("blockedReason")
        if blocked_reason is not None:
            blocked_reason = require_string(blocked_reason, f"task {name} blockedReason")
        if not runnable and blocked_reason is None:
            raise SchedulerError(f"task {name} must explain why it is not runnable")

        tasks[name] = Task(
            name=name,
            description=require_string(fields.get("description"), f"task {name} description"),
            command=command,
            versions=versions,
            resources=requests,
            arguments=arguments,
            variants=variants,
            default_variant=default_variant,
            runnable=runnable,
            blocked_reason=blocked_reason,
        )
    if not tasks:
        raise SchedulerError("manifest must declare tasks")
    return Manifest(manifest_path, root, scheduler_root, resources, tasks)


def parse_request(spec: str, run_label: str, manifest: Manifest) -> Request:
    if spec.count("@") > 1:
        raise SchedulerError(f"invalid task spec: {spec}")
    base, separator, requested_variant = spec.partition("@")
    if base.count(":") != 1:
        raise SchedulerError(f"task spec must be name:version[@variant]: {spec}")
    name, version = base.split(":", 1)
    task = manifest.tasks.get(name)
    if task is None:
        raise SchedulerError(f"unknown host-validation task: {name}")
    if version not in task.versions:
        raise SchedulerError(f"task {name} does not support version {version}")
    variant: str | None = None
    if task.variants:
        variant = requested_variant if separator else task.default_variant
        if variant not in task.variants:
            choices = ", ".join(task.variants)
            raise SchedulerError(f"task {name} variant must be one of: {choices}")
    elif separator:
        raise SchedulerError(f"task {name} does not define variants")
    return Request(task, version, variant, run_label)


def render_command(request: Request, manifest: Manifest, ssh_host: str, ssh_key: Path) -> list[str]:
    template = request.task.arguments
    if template is None:
        assert request.variant is not None
        template = request.task.variants[request.variant]
    values = {"{version}": request.version, "{runLabel}": request.run_label}
    arguments = [values.get(argument, argument) for argument in template]
    placement = ["--ssh-host", ssh_host, "--ssh-key", str(ssh_key.resolve())]
    return [
        "bash",
        str((manifest.root / request.task.command).resolve()),
        *arguments,
        *placement,
    ]


def plan_waves(requests: Iterable[Request], resources: dict[str, Resource]) -> list[list[Request]]:
    waves: list[list[Request]] = []
    usage: list[dict[str, int]] = []
    for request in requests:
        placed = False
        for index, current in enumerate(usage):
            if all(
                current.get(name, 0) + quantity <= resources[name].capacity
                for name, quantity in request.task.resources.items()
            ):
                waves[index].append(request)
                for name, quantity in request.task.resources.items():
                    current[name] = current.get(name, 0) + quantity
                placed = True
                break
        if not placed:
            waves.append([request])
            usage.append(dict(request.task.resources))
    return waves


def resource_text(resources: dict[str, int]) -> str:
    return ",".join(f"{name}={quantity}" for name, quantity in sorted(resources.items()))


def shell_command(command: list[str]) -> str:
    return shlex.join(command)


class RemoteLeases:
    def __init__(self, ssh_host: str, ssh_key: Path, scheduler_root: str, resources: dict[str, Resource]):
        if not ssh_host or any(character in ssh_host for character in "\n\r\0"):
            raise SchedulerError("ssh host must be non-empty single-line text")
        if not ssh_key.is_file():
            raise SchedulerError(f"SSH key does not exist: {ssh_key}")
        self.ssh_host = ssh_host
        self.ssh_key = ssh_key.resolve()
        self.scheduler_root = validate_remote_root(scheduler_root)
        self.resources = resources
        self.ssh = [
            "ssh", "-i", str(self.ssh_key), "-o", "IdentitiesOnly=yes", "-o", "ConnectTimeout=10",
        ]

    def _run(self, script: str, arguments: list[str], *, capture: bool = True) -> subprocess.CompletedProcess[str]:
        remote = "bash -s -- " + " ".join(shlex.quote(argument) for argument in arguments)
        return subprocess.run(
            [*self.ssh, self.ssh_host, remote], input=script, text=True,
            capture_output=capture, check=False,
        )

    def acquire(
        self,
        request: Request,
        owner: str,
        wait_seconds: int,
        poll_seconds: int,
        cancelled: threading.Event | None = None,
    ) -> Lease:
        deadline = time.monotonic() + wait_seconds
        resource_args = [
            f"{name}={quantity}:{self.resources[name].capacity}"
            for name, quantity in sorted(request.task.resources.items())
        ]
        script = r'''set -euo pipefail
root=$1; owner=$2; task=$3; started=$4; shift 4
mkdir -p "$root/leases"
acquired=''
cleanup() {
  printf '%s\n' "$acquired" | while IFS= read -r slot; do
    [ -n "$slot" ] || continue
    [ "$(cat "$slot/owner" 2>/dev/null || true)" = "$owner" ] && rm -rf -- "$slot"
  done
}
for request in "$@"; do
  resource=${request%%=*}
  rest=${request#*=}
  quantity=${rest%%:*}
  capacity=${rest#*:}
  mkdir -p "$root/leases/$resource"
  held=0
  index=1
  while [ "$index" -le "$capacity" ] && [ "$held" -lt "$quantity" ]; do
    slot="$root/leases/$resource/slot-$index"
    if mkdir "$slot" 2>/dev/null; then
      printf '%s\n' "$owner" > "$slot/owner"
      printf '%s\n' "$task" > "$slot/task"
      printf '%s\n' "$started" > "$slot/startedEpoch"
      printf '%s\n' "$started" > "$slot/heartbeatEpoch"
      acquired="${acquired}${acquired:+
}$slot"
      held=$((held + 1))
    fi
    index=$((index + 1))
  done
  if [ "$held" -ne "$quantity" ]; then cleanup; exit 75; fi
done
printf '%s\n' "$acquired"
'''
        while True:
            if cancelled is not None and cancelled.is_set():
                raise SchedulerError(f"cancelled while waiting for resources for {request.spec}")
            started = str(int(time.time()))
            result = self._run(
                script, [self.scheduler_root, owner, request.spec, started, *resource_args]
            )
            if result.returncode == 0:
                slots = tuple(line for line in result.stdout.splitlines() if line)
                expected = sum(request.task.resources.values())
                if len(slots) != expected:
                    self.release_owner(owner)
                    raise SchedulerError(f"lease acquisition returned {len(slots)} of {expected} slots")
                return Lease(owner, request, slots)
            if result.returncode != BUSY_EXIT:
                message = result.stderr.strip() or result.stdout.strip() or f"ssh exit {result.returncode}"
                raise SchedulerError(f"cannot acquire resources for {request.spec}: {message}")
            if time.monotonic() >= deadline:
                raise SchedulerError(f"timed out waiting for resources for {request.spec}")
            if cancelled is None:
                time.sleep(poll_seconds)
            elif cancelled.wait(poll_seconds):
                raise SchedulerError(f"cancelled while waiting for resources for {request.spec}")

    def heartbeat(self, owner: str) -> None:
        script = r'''set -euo pipefail
root=$1; owner=$2; now=$3
find "$root/leases" -mindepth 2 -maxdepth 2 -type d -name 'slot-*' -print0 2>/dev/null |
while IFS= read -r -d '' slot; do
  [ "$(cat "$slot/owner" 2>/dev/null || true)" = "$owner" ] || continue
  printf '%s\n' "$now" > "$slot/heartbeatEpoch"
done
'''
        self._run(script, [self.scheduler_root, owner, str(int(time.time()))])

    def release_owner(self, owner: str) -> None:
        script = r'''set -euo pipefail
root=$1; owner=$2
find "$root/leases" -mindepth 2 -maxdepth 2 -type d -name 'slot-*' -print0 2>/dev/null |
while IFS= read -r -d '' slot; do
  [ "$(cat "$slot/owner" 2>/dev/null || true)" = "$owner" ] || continue
  rm -rf -- "$slot"
done
'''
        result = self._run(script, [self.scheduler_root, owner])
        if result.returncode != 0:
            message = result.stderr.strip() or f"ssh exit {result.returncode}"
            print(f"host-validation scheduler: lease release warning: {message}", file=sys.stderr)

    def status(self) -> list[dict[str, str]]:
        script = r'''set -euo pipefail
root=$1
find "$root/leases" -mindepth 2 -maxdepth 2 -type d -name 'slot-*' -print0 2>/dev/null |
while IFS= read -r -d '' slot; do
  resource=$(basename "$(dirname "$slot")")
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$resource" "$(basename "$slot")" \
    "$(cat "$slot/owner" 2>/dev/null || true)" \
    "$(cat "$slot/task" 2>/dev/null || true)" \
    "$(cat "$slot/startedEpoch" 2>/dev/null || echo 0)" \
    "$(cat "$slot/heartbeatEpoch" 2>/dev/null || echo 0)"
done
'''
        result = self._run(script, [self.scheduler_root])
        if result.returncode != 0:
            raise SchedulerError(result.stderr.strip() or f"status ssh exit {result.returncode}")
        rows = []
        for line in result.stdout.splitlines():
            fields = line.split("\t")
            if len(fields) == 6:
                rows.append(dict(zip(
                    ("resource", "slot", "owner", "task", "started", "heartbeat"), fields
                )))
        return sorted(rows, key=lambda row: (row["resource"], row["slot"]))

    def release_stale(self, older_than: int) -> list[str]:
        script = r'''set -euo pipefail
root=$1; cutoff=$2
find "$root/leases" -mindepth 2 -maxdepth 2 -type d -name 'slot-*' -print0 2>/dev/null |
while IFS= read -r -d '' slot; do
  heartbeat=$(cat "$slot/heartbeatEpoch" 2>/dev/null || echo 0)
  case "$heartbeat" in ''|*[!0-9]*) heartbeat=0 ;; esac
  [ "$heartbeat" -lt "$cutoff" ] || continue
  owner=$(cat "$slot/owner" 2>/dev/null || true)
  printf '%s\t%s\n' "$slot" "$owner"
  rm -rf -- "$slot"
done
'''
        cutoff = int(time.time()) - older_than
        result = self._run(script, [self.scheduler_root, str(cutoff)])
        if result.returncode != 0:
            raise SchedulerError(result.stderr.strip() or f"release-stale ssh exit {result.returncode}")
        return [line for line in result.stdout.splitlines() if line]


def owner_id(request: Request, index: int) -> str:
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    raw = f"{request.task.name}-{request.version}-{os.getpid()}-{index}-{stamp}"
    return re.sub(r"[^A-Za-z0-9._-]", "-", raw)[:128]


def run_one(
    request: Request,
    index: int,
    manifest: Manifest,
    leases: RemoteLeases,
    ssh_host: str,
    ssh_key: Path,
    wait_seconds: int,
    poll_seconds: int,
    stop: threading.Event,
) -> RunResult:
    if stop.is_set():
        return RunResult(request, 130)
    owner = owner_id(request, index)
    lease = leases.acquire(request, owner, wait_seconds, poll_seconds, stop)
    print(f"[scheduler] acquired {request.spec}: {', '.join(lease.slots)}", flush=True)
    heartbeat_stop = threading.Event()

    def heartbeat() -> None:
        while not heartbeat_stop.wait(HEARTBEAT_SECONDS):
            leases.heartbeat(owner)

    heartbeat_thread = threading.Thread(target=heartbeat, name=f"lease-heartbeat-{index}", daemon=True)
    heartbeat_thread.start()
    command = render_command(request, manifest, ssh_host, ssh_key)
    print(f"[scheduler] run {request.spec}: {shell_command(command)}", flush=True)
    try:
        process = subprocess.Popen(command, cwd=manifest.root)
        while True:
            try:
                return_code = process.wait(timeout=1)
                break
            except subprocess.TimeoutExpired:
                if stop.is_set():
                    process.terminate()
                    try:
                        return_code = process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        return_code = process.wait()
                    break
        return RunResult(request, return_code)
    finally:
        heartbeat_stop.set()
        heartbeat_thread.join(timeout=2)
        leases.release_owner(owner)
        print(f"[scheduler] released {request.spec}", flush=True)


def list_tasks(manifest: Manifest) -> int:
    print("TASK\tVERSIONS\tVARIANTS\tRESOURCES\tRUNNABLE\tDESCRIPTION")
    for task in manifest.tasks.values():
        variants = ",".join(task.variants) if task.variants else "-"
        runnable = "yes" if task.runnable else f"no: {task.blocked_reason}"
        print(
            f"{task.name}\t{','.join(task.versions)}\t{variants}\t"
            f"{resource_text(task.resources)}\t{runnable}\t{task.description}"
        )
    return 0


def show_plan(requests: list[Request], manifest: Manifest, ssh_host: str, ssh_key: Path) -> int:
    waves = plan_waves(requests, manifest.resources)
    for wave_index, wave in enumerate(waves, start=1):
        print(f"wave {wave_index}:")
        for request in wave:
            state = "runnable" if request.task.runnable else f"blocked: {request.task.blocked_reason}"
            print(f"  {request.spec} [{resource_text(request.task.resources)}] {state}")
            print(f"    {shell_command(render_command(request, manifest, ssh_host, ssh_key))}")
    return 0 if all(request.task.runnable for request in requests) else 1


def run_requests(args: argparse.Namespace, requests: list[Request], manifest: Manifest) -> int:
    blocked = [request for request in requests if not request.task.runnable]
    if blocked:
        details = "; ".join(f"{item.spec}: {item.task.blocked_reason}" for item in blocked)
        raise SchedulerError(f"cannot run blocked tasks: {details}")
    leases = RemoteLeases(
        args.ssh_host, Path(args.ssh_key), args.scheduler_root or manifest.scheduler_root, manifest.resources
    )
    waves = plan_waves(requests, manifest.resources)
    stop = threading.Event()
    previous_handlers: dict[int, Any] = {}

    def stop_handler(signum: int, _frame: Any) -> None:
        print(f"host-validation scheduler: received signal {signum}; stopping", file=sys.stderr)
        stop.set()

    for signum in (signal.SIGINT, signal.SIGTERM):
        previous_handlers[signum] = signal.signal(signum, stop_handler)
    failures: list[RunResult] = []
    try:
        sequence = 0
        for wave_index, wave in enumerate(waves, start=1):
            if stop.is_set():
                break
            print(f"[scheduler] wave {wave_index}/{len(waves)}: {', '.join(item.spec for item in wave)}")
            wave_failed = False
            with concurrent.futures.ThreadPoolExecutor(max_workers=len(wave)) as executor:
                futures: list[tuple[Request, concurrent.futures.Future[RunResult]]] = []
                for request in wave:
                    sequence += 1
                    futures.append((request, executor.submit(
                        run_one, request, sequence, manifest, leases, args.ssh_host,
                        Path(args.ssh_key), args.wait_seconds, args.poll_seconds, stop,
                    )))
                for request, future in futures:
                    try:
                        result = future.result()
                    except SchedulerError as failure:
                        print(f"host-validation scheduler: {failure}", file=sys.stderr)
                        failures.append(RunResult(request, 1))
                        wave_failed = True
                        continue
                    if result.return_code != 0:
                        failures.append(result)
                        wave_failed = True
                        print(
                            f"[scheduler] FAIL {result.request.spec} exit={result.return_code}",
                            file=sys.stderr,
                        )
                    else:
                        print(f"[scheduler] PASS {result.request.spec}")
            if wave_failed and not args.keep_going:
                break
    finally:
        for signum, handler in previous_handlers.items():
            signal.signal(signum, handler)
    if stop.is_set() and not failures:
        return 130
    return 1 if failures else 0


def status_command(args: argparse.Namespace, manifest: Manifest) -> int:
    leases = RemoteLeases(
        args.ssh_host, Path(args.ssh_key), args.scheduler_root or manifest.scheduler_root, manifest.resources
    )
    rows = leases.status()
    if not rows:
        print("no active host-validation leases")
        return 0
    now = int(time.time())
    print("RESOURCE\tSLOT\tTASK\tOWNER\tAGE_SECONDS\tHEARTBEAT_AGE_SECONDS")
    for row in rows:
        try:
            started = int(row["started"])
            heartbeat = int(row["heartbeat"])
        except ValueError:
            started = heartbeat = 0
        print(
            f"{row['resource']}\t{row['slot']}\t{row['task']}\t{row['owner']}\t"
            f"{max(0, now - started)}\t{max(0, now - heartbeat)}"
        )
    return 0


def release_stale_command(args: argparse.Namespace, manifest: Manifest) -> int:
    if not args.force:
        raise SchedulerError("release-stale requires --force")
    leases = RemoteLeases(
        args.ssh_host, Path(args.ssh_key), args.scheduler_root or manifest.scheduler_root, manifest.resources
    )
    released = leases.release_stale(args.older_than)
    if not released:
        print("no stale host-validation leases released")
    else:
        for row in released:
            print(f"released\t{row}")
    return 0


def add_remote_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--ssh-host", default="local-user@validation-host.invalid")
    parser.add_argument(
        "--ssh-key", default=str(Path.home() / ".ssh" / "id_ed25519_validation")
    )
    parser.add_argument("--scheduler-root")


def build_parser(default_manifest: Path) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(default_manifest))
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("list", help="list declared exact-host tasks and resources")

    plan = subparsers.add_parser("plan", help="show deterministic resource-compatible waves")
    plan.add_argument("tasks", nargs="+")
    plan.add_argument("--run-label", default="scheduled")
    add_remote_options(plan)

    run = subparsers.add_parser("run", help="run task waves with remote resource leases")
    run.add_argument("tasks", nargs="+")
    run.add_argument("--run-label", default="scheduled")
    run.add_argument("--wait-seconds", type=int, default=3600)
    run.add_argument("--poll-seconds", type=int, default=5)
    run.add_argument("--keep-going", action="store_true")
    add_remote_options(run)

    status = subparsers.add_parser("status", help="show active remote resource leases")
    add_remote_options(status)

    release = subparsers.add_parser("release-stale", help="remove explicitly confirmed stale leases")
    release.add_argument("--older-than", type=int, default=3600)
    release.add_argument("--force", action="store_true")
    add_remote_options(release)
    return parser


def main(argv: list[str] | None = None) -> int:
    default_manifest = Path(__file__).with_name("host-validation-tasks.json")
    parser = build_parser(default_manifest)
    args = parser.parse_args(argv)
    try:
        manifest = load_manifest(Path(args.manifest))
        if args.command == "list":
            return list_tasks(manifest)
        if args.command in {"plan", "run"}:
            if not SAFE_NAME.fullmatch(args.run_label):
                raise SchedulerError("run label must be a safe bounded label")
            requests = [parse_request(spec, args.run_label, manifest) for spec in args.tasks]
            if args.command == "plan":
                return show_plan(requests, manifest, args.ssh_host, Path(args.ssh_key))
            if args.wait_seconds < 1 or args.poll_seconds < 1:
                raise SchedulerError("wait and poll intervals must be positive integers")
            return run_requests(args, requests, manifest)
        if args.command == "status":
            return status_command(args, manifest)
        if args.command == "release-stale":
            if args.older_than < 300:
                raise SchedulerError("release-stale --older-than must be at least 300 seconds")
            return release_stale_command(args, manifest)
        raise SchedulerError(f"unknown command: {args.command}")
    except SchedulerError as failure:
        print(f"host-validation scheduler: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
