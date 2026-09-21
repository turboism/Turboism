#!/usr/bin/env python3
"""Read-only RSS observer, used as a queued pipeline's synchronous post-launch hook.

The admitted Linux cgroup and one exact Cubism main-class process bound by PID/start ticks
are observed. This never signals processes or infers a performance PASS. VmHWM covers the
process lifetime; sampled VmRSS covers the recorded interval, including startup if observed.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import pwd
import sys
import time

MAIN_CLASS = b"com.live2d.cubism.CECubismEditorApp"
PERIOD_SECONDS = 0.05


class IdentityChanged(RuntimeError):
    pass


def process_start_ticks(text: str) -> int:
    # A Linux comm may contain spaces and parentheses. Field 22 follows the final ')'.
    end = text.rfind(")")
    if end < 0:
        raise ValueError("invalid process stat")
    fields = text[end + 1:].split()
    if len(fields) < 20:
        raise ValueError("short process stat")
    return int(fields[19])


def memory_bytes(text: str) -> tuple[int, int]:
    values = {}
    for line in text.splitlines():
        parts = line.split()
        if parts and parts[0] in ("VmRSS:", "VmHWM:"):
            if len(parts) != 3 or parts[2] != "kB" or parts[0] in values:
                raise ValueError("invalid or duplicate RSS field")
            values[parts[0]] = int(parts[1]) * 1024
    rss, hwm = values["VmRSS:"], values["VmHWM:"]
    if rss <= 0 or hwm < rss:
        raise ValueError("unavailable or inconsistent RSS")
    return rss, hwm


def process_observation(proc: Path, pid: int, group: str):
    process = proc / str(pid)
    before = process_start_ticks((process / "stat").read_text())
    if (process / "cgroup").read_text().splitlines() != ["0::" + group]:
        raise IdentityChanged("process left the admitted cgroup")
    argv = (process / "cmdline").read_bytes().split(b"\0")
    if MAIN_CLASS not in argv:
        return None
    rss, hwm = memory_bytes((process / "status").read_text())
    after = process_start_ticks((process / "stat").read_text())
    if before != after or (process / "cgroup").read_text().splitlines() != ["0::" + group]:
        raise IdentityChanged("process identity changed during RSS read")
    return pid, before, rss, hwm


class Scope:
    def __init__(self, metadata: dict, group_root=Path("/sys/fs/cgroup"), proc=Path("/proc")):
        self.metadata, self.proc = metadata, proc
        group = Path(metadata["cgroupPath"])
        if not group.is_absolute() or ".." in group.parts or group == Path("/"):
            raise IdentityChanged("unsafe cgroup path")
        self.group = str(group)
        self.path = group_root / group.relative_to("/")
        self.identity = metadata["cgroupDevice"], metadata["cgroupInode"]
        self.check()

    def check(self):
        stat = self.path.stat()
        if (stat.st_dev, stat.st_ino) != self.identity:
            raise IdentityChanged("admitted cgroup was replaced")
        if (self.proc / "sys/kernel/random/boot_id").read_text().strip() != self.metadata["bootId"]:
            raise IdentityChanged("boot identity changed")
        if (self.proc / "self/cgroup").read_text().splitlines() != ["0::" + self.group]:
            raise IdentityChanged("observer is outside its admitted cgroup")

    def sample(self, expected=None):
        self.check()
        candidates = []
        for pid in sorted(set(map(int, (self.path / "cgroup.procs").read_text().split()))):
            try:
                value = process_observation(self.proc, pid, self.group)
            except (FileNotFoundError, ProcessLookupError):
                continue  # Short-lived launchers may leave between enumeration and read.
            if value is not None:
                candidates.append(value)
        self.check()
        if len(candidates) > 1:
            raise IdentityChanged("multiple Cubism main-class processes in task scope")
        if not candidates:
            return None
        value = candidates[0]
        if expected is not None and value[:2] != expected:
            raise IdentityChanged("bound Cubism PID/start ticks changed")
        return value


def terminal_status(path: Path, run_id: str):
    if not path.exists():
        return None
    fields = {}
    for line in path.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep:
            if key in fields:
                raise IdentityChanged("duplicate terminal property")
            fields[key] = value
    if fields.get("runId") != run_id:
        raise IdentityChanged("RSS terminal result belongs to a different run")
    return fields.get("status") if fields.get("status") in ("PASS", "FAIL", "BLOCKED") else None


def report_identity(metadata: dict, expected: dict) -> dict:
    if metadata.get("schemaVersion") != 1 or any(metadata.get(k) != v for k, v in expected.items()):
        raise IdentityChanged("queue containment identity mismatch")
    digest = metadata.get("preparedDigest")
    if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        raise IdentityChanged("queue containment prepared digest is unavailable")
    return dict(expected, preparedDigest=digest)


def machine_description(proc=Path("/proc")) -> dict:
    result = {"kernel": " ".join(os.uname()), "logicalCpuCount": os.cpu_count(),
              "cpuModel": "UNAVAILABLE", "physicalMemoryBytes": "UNAVAILABLE"}
    try:
        models = sorted({line.partition(":")[2].strip()
                         for line in (proc / "cpuinfo").read_text().splitlines()
                         if line.partition(":")[0].strip() == "model name"})
        if models:
            result["cpuModel"] = models
        for line in (proc / "meminfo").read_text().splitlines():
            fields = line.split()
            if len(fields) == 3 and fields[0] == "MemTotal:" and fields[2] == "kB":
                result["physicalMemoryBytes"] = int(fields[1]) * 1024
    except (OSError, ValueError) as failure:
        result["diagnostic"] = str(failure)
    return result


def collect(scope: Scope, terminal: Path, run_id: str, timeout: int) -> dict:
    started = time.monotonic_ns()
    deadline = started + timeout * 1_000_000_000
    report = {"schemaVersion": 1, "runId": run_id, "periodMillis": 50,
              "sampleCount": 0, "missedAfterBinding": 0, "peakSampledRssBytes": 0,
              "processLifetimeHighWaterRssBytes": 0, "maximumSampleGapNanos": 0,
              "measurement": "Linux VmRSS sampled; VmHWM since Cubism process startup",
              "scope": "post-launch through probe terminal; includes startup when observed",
              "performanceStatus": "NOT_EVALUATED"}
    bound, last, terminal = None, None, Path(terminal)
    while time.monotonic_ns() < deadline:
        value = scope.sample(bound)
        now = time.monotonic_ns()
        if value is not None:
            pid, ticks, rss, hwm = value
            if bound is None:
                bound = pid, ticks
                report.update(pid=pid, processStartTicks=ticks, firstSampleNanos=now - started)
            if last is not None:
                report["maximumSampleGapNanos"] = max(report["maximumSampleGapNanos"], now - last)
            last = now
            report["sampleCount"] += 1
            report["peakSampledRssBytes"] = max(report["peakSampledRssBytes"], rss)
            report["processLifetimeHighWaterRssBytes"] = max(report["processLifetimeHighWaterRssBytes"], hwm)
        elif bound is not None:
            report["missedAfterBinding"] += 1
        status = terminal_status(terminal, run_id)
        if status:
            report["probeTerminalStatus"] = status
            break
        time.sleep(PERIOD_SECONDS)
    report["elapsedNanos"] = time.monotonic_ns() - started
    report["complete"] = bool(report.get("probeTerminalStatus") and report["sampleCount"]
                              and not report["missedAfterBinding"])
    report["observationStatus"] = "OBSERVED" if report["complete"] else "UNAVAILABLE"
    return report


def main(argv: list[str]) -> int:
    if len(argv) != 11:
        raise ValueError("expected the generic Runner's eleven hook context arguments")
    task, home, evidence, _prefix, _fixture, run_id, version, timeout, *_ = argv
    task, home, evidence = map(Path, (task, home, evidence))
    if version != "5302" or task.name != run_id or home != task / "turboism-home" or evidence != task / "evidence":
        raise IdentityChanged("hook context is not the expected task")
    if any(not p.is_absolute() or ".." in p.parts or any(a.is_symlink() for a in (p, *p.parents))
           for p in (task, home, evidence)):
        raise IdentityChanged("unsafe hook context path")
    job = os.environ["TURBOISM_QUEUE_JOB_ID"]
    if not job or any(c not in "0123456789abcdef-" for c in job):
        raise IdentityChanged("unsafe queue job ID")
    root = Path(pwd.getpwuid(os.getuid()).pw_dir) / ".local/state/turboism/host-validation"
    metadata = json.loads((root / "jobs" / job / "containment.json").read_text())
    expected = {"jobId": job, "attemptId": os.environ["TURBOISM_QUEUE_ATTEMPT_ID"],
                "runId": os.environ["TURBOISM_QUEUE_RUN_ID"]}
    if expected["runId"] != run_id:
        raise IdentityChanged("queue containment identity mismatch")
    expected = report_identity(metadata, expected)
    seconds = int(timeout)
    if not 1 <= seconds <= 1800:
        raise ValueError("RSS observation timeout must be within 1..1800 seconds")
    output = evidence / "external-psd-rss.json"
    # Exclusive creation preserves any earlier report. The Runner owns cleanup on all failures.
    with output.open("x") as stream:
        try:
            report = collect(Scope(metadata), home / "state/dev.turboism.validation.externalpsd/external-psd-edit-result.properties", run_id, seconds)
        except Exception as failure:
            report = {"observationStatus": "UNAVAILABLE", "complete": False,
                      "error": type(failure).__name__ + ": " + str(failure)}
            report.update(expected)
            json.dump(report, stream, indent=2)
            stream.write("\n")
            raise
        report.update(expected)
        report["machine"] = machine_description()
        report["cgroup"] = {k: metadata[k] for k in ("bootId", "cgroupPath", "cgroupDevice", "cgroupInode")}
        json.dump(report, stream, indent=2)
        stream.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
