#!/usr/bin/env python3
"""Local durable FIFO queue for Turboism exact-host validation wrappers.

This scheduler owns admission only. Existing wrappers and
run-cubism-host-validation.sh remain responsible for project copies, Cubism
launch, result polling, evidence, and task-owned cleanup.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from typing import Any

import host_validation_queue as queue

FORMAT = "turboism.host-validation.tasks"
SCHEMA_VERSION = 1
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
SAFE_REMOTE_ROOT = re.compile(r"^/[A-Za-z0-9._/-]+$")
BUSY_EXIT = 75
HEARTBEAT_SECONDS = 30


class SchedulerError(RuntimeError):
    pass


def parse_local_env(path: Path) -> dict[str, str]:
    """Parse TURBOISM_* key/value data without executing the local file."""
    if not path.is_file():
        return {}
    values: dict[str, str] = {}
    assignment = re.compile(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = assignment.fullmatch(line)
        if match is None:
            raise SchedulerError("invalid .env assignment (values are not printed)")
        name, value = match.groups()
        if not name.startswith("TURBOISM_"):
            raise SchedulerError(f".env key must start with TURBOISM_: {name}")
        value = value.strip()
        if not value.startswith(("\"", "'")) and any(character.isspace() for character in value):
            raise SchedulerError(
                f"unquoted whitespace in .env key {name} (value is not printed)"
            )
        if value.startswith(("\"", "'")):
            if len(value) < 2 or value[-1] != value[0]:
                raise SchedulerError(
                    f"unmatched quote in .env key {name} (value is not printed)"
                )
            value = value[1:-1]
        values[name] = value
    return values


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


def load_manifest(path: Path, environment: dict[str, str] | None = None) -> Manifest:
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
    placement = os.environ if environment is None else environment
    scheduler_root = str(queue.account_root())

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


def render_command(request: Request, manifest: Manifest) -> list[str]:
    templates = request.task.variants[request.variant] if request.variant else request.task.arguments
    if templates is None:
        raise SchedulerError(f"cannot run blocked task: {request.task.blocked_reason}")
    return ["bash", str(manifest.root / request.task.command),
            *(value.format(version=request.version, runLabel=request.run_label) for value in templates)]


def plan_waves(requests: list[Request], resources: dict[str, Resource]) -> list[list[Request]]:
    # Retained API name; the executable policy is deliberately single-session FIFO.
    return [[request] for request in requests]


def resource_text(resources: dict[str, int]) -> str:
    return ",".join(f"{name}={amount}" for name, amount in sorted(resources.items()))


def list_tasks(manifest: Manifest) -> int:
    print("TASK\tVERSIONS\tVARIANTS\tRESOURCES\tRUNNABLE\tDESCRIPTION")
    for task in manifest.tasks.values():
        print(f"{task.name}\t{','.join(task.versions)}\t{','.join(task.variants) or '-'}\t"
              f"{resource_text(task.resources)}\t{'yes' if task.runnable else task.blocked_reason}\t{task.description}")
    return 0


def prepare_request(request: Request, manifest: Manifest, store: queue.Store,
                    environment: dict[str, str]) -> dict[str, Any]:
    if not request.task.runnable:
        raise SchedulerError(f"cannot run blocked task: {request.task.blocked_reason}")
    with tempfile.TemporaryDirectory(dir=store.root / "staging") as directory:
        result = subprocess.run([*render_command(request, manifest), "--prepare-dir", directory],
            cwd=manifest.root, env=environment, text=True, capture_output=True)
        if result.returncode:
            raise SchedulerError(result.stderr.strip() or "wrapper preparation failed")
        path = Path(directory) / "runner-request.json"
        if not path.is_file():
            raise SchedulerError("wrapper does not support preparation; update this checkout before submitting")
        return queue.PreparedStore(store).capture(json.loads(path.read_text()), manifest.root, request.spec)


def emit(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, sort_keys=True), flush=True)


def wait_job(store: queue.Store, job_id: str, timeout: int | None = None) -> int:
    deadline = None if timeout is None else time.monotonic() + timeout
    while True:
        job = store.jobs(job_id)[0]
        if job["state"] in queue.TERMINAL or job["state"] == "quarantined":
            emit({"schemaVersion": 1, "job": job})
            return 0 if job["state"] == "succeeded" else 75 if job["state"] == "quarantined" else 1
        if deadline is not None and time.monotonic() >= deadline:
            emit({"schemaVersion": 1, "job": job, "waitTimedOut": True})
            return 3
        time.sleep(0.2)  # Only a client waiter; never controls scheduling or cancellation.


def build_parser(default_manifest: Path) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Local-only durable single-session host verification queue")
    parser.add_argument("--manifest", default=str(default_manifest))
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("list")
    for name in ("plan", "prepare", "run"):
        command = commands.add_parser(name)
        command.add_argument("tasks", nargs="+" if name != "prepare" else 1)
        command.add_argument("--run-label", default="r1")
    submit = commands.add_parser("submit")
    submit.add_argument("--prepared", required=True)
    submit.add_argument("--request-id", required=True)
    submit.add_argument("--timeout-seconds", type=int, default=1800)
    submit.add_argument("--json", action="store_true")
    status = commands.add_parser("status")
    status.add_argument("job", nargs="?")
    status.add_argument("--json", action="store_true")
    wait = commands.add_parser("wait")
    wait.add_argument("job")
    wait.add_argument("--timeout-seconds", type=int)
    cancel = commands.add_parser("cancel")
    cancel.add_argument("job")
    events = commands.add_parser("events")
    events.add_argument("--job")
    events.add_argument("--after", type=int, default=0)
    events.add_argument("--follow", action="store_true")
    commands.add_parser("serve")
    recover = commands.add_parser("recover")
    mode = recover.add_mutually_exclusive_group(required=True)
    mode.add_argument("--inspect", dest="job")
    mode.add_argument("--confirm", dest="confirm")
    recover.add_argument("--reason")
    commands.add_parser("_admit", help=argparse.SUPPRESS)
    enqueue = commands.add_parser("_enqueue-runner", help=argparse.SUPPRESS)
    enqueue.add_argument("--request", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    arguments = sys.argv[1:] if argv is None else argv
    if any(value.split("=", 1)[0] in {"--ssh-host", "--ssh-key", "--scheduler-root",
            "--keep-going", "--wait-seconds", "--poll-seconds", "release-stale"} for value in arguments):
        print("host-validation: old SSH/wave/lease options are retired; use the local queue and recover --inspect", file=sys.stderr)
        return 2
    parser = build_parser(Path(__file__).with_name("host-validation-tasks.json"))
    args = parser.parse_args(arguments)
    try:
        if args.command == "_admit":
            emit(queue.validate_admission())
            return 0
        root = Path(__file__).resolve().parents[2]
        if args.command in {"list", "plan", "prepare", "run"}:
            environment = dict(parse_local_env(Path(os.environ.get("TURBOISM_ENV_FILE", root / ".env"))))
            environment.update(os.environ)
            manifest = load_manifest(Path(args.manifest), environment)
            if args.command == "list":
                return list_tasks(manifest)
            if not SAFE_NAME.fullmatch(args.run_label):
                raise SchedulerError("run label must be a safe bounded label")
            requests = [parse_request(spec, args.run_label, manifest) for spec in args.tasks]
            if args.command == "plan":
                print("local-only FIFO; one host session, including cleanup; no SSH")
                for index, request in enumerate(requests, 1):
                    state = "runnable" if request.task.runnable else f"blocked: {request.task.blocked_reason}"
                    print(f"{index}. {request.spec} {state}")
                    if request.task.runnable:
                        print("   " + shlex.join(render_command(request, manifest)))
                return 0 if all(request.task.runnable for request in requests) else 1
            if any(not request.task.runnable for request in requests):
                raise SchedulerError("cannot run blocked tasks")
            store = queue.Store()
            prepared = [prepare_request(request, manifest, store, environment) for request in requests]
            if args.command == "prepare":
                emit({"schemaVersion": 1, "preparedId": prepared[0]["digest"]})
                return 0
            jobs = [store.submit(item["digest"], item["digest"], str(uuid.uuid4())) for item in prepared]
            for job in jobs:
                emit({"schemaVersion": 1, "job": job})
            queue.wake(store)
            results = [wait_job(store, job["job_id"]) for job in jobs]
            return max(results, default=0)
        store = queue.Store()
        if args.command == "_enqueue-runner":
            request = json.loads(Path(args.request).read_text())
            prepared = queue.PreparedStore(store).capture(request, root, "direct-runner")
            job = store.submit(prepared["digest"], prepared["digest"], str(uuid.uuid4()))
            emit({"schemaVersion": 1, "job": job})
            queue.wake(store)
            return wait_job(store, job["job_id"])
        if args.command == "submit":
            prepared = queue.PreparedStore(store).load(args.prepared)
            job = store.submit(args.prepared, prepared["digest"], args.request_id, args.timeout_seconds)
            queue.wake(store)
            emit({"schemaVersion": 1, "job": job, "workerOnline": queue.worker_online(store)})
            return 0
        if args.command == "status":
            emit({"schemaVersion": 1, "host": store.host(), "jobs": store.jobs(args.job),
                  "workerOnline": queue.worker_online(store)})
            return 0
        if args.command == "wait":
            if args.timeout_seconds is not None and args.timeout_seconds < 1:
                raise SchedulerError("wait timeout must be positive")
            return wait_job(store, args.job, args.timeout_seconds)
        if args.command == "cancel":
            emit({"schemaVersion": 1, "job": store.cancel(args.job)})
            queue.wake(store)
            return 0
        if args.command == "events":
            if args.after < 0:
                raise SchedulerError("event cursor must be nonnegative")
            while True:
                events = store.events(args.after, args.job)
                for event in events:
                    emit({"schemaVersion": 1, **event})
                    args.after = event["event_id"]
                if not args.follow and len(events) < 1000:
                    return 0
                if not events:
                    time.sleep(0.2)
        if args.command == "recover":
            if args.confirm and (args.reason is None or not args.reason.strip()):
                raise queue.QueueError("recover --confirm requires a nonempty --reason")
            if not args.confirm and args.reason is not None:
                raise queue.QueueError("--reason is only valid with recover --confirm")
            report = queue.recover(store, args.confirm or args.job, args.reason if args.confirm else None)
            emit({"schemaVersion": 1, **report})
            return 0 if report["safe"] else 75
        if args.command == "serve":
            stop = threading.Event()
            previous = {sig: signal.signal(sig, lambda *_: stop.set()) for sig in (signal.SIGINT, signal.SIGTERM)}
            try:
                queue.Worker(store).serve(stop=stop)
            finally:
                for sig, handler in previous.items():
                    signal.signal(sig, handler)
            return 0
        raise SchedulerError("unknown command")
    except (SchedulerError, queue.QueueError, OSError, ValueError) as failure:
        print(f"host-validation: {failure}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130  # Client interruption is not job cancellation.


if __name__ == "__main__":
    raise SystemExit(main())
