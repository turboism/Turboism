#!/usr/bin/env python3
"""Pure and real-kernel tests for host_validation_containment.py.

The real test creates only a random, user-owned systemd scope containing
short-lived Python processes.  It never starts Cubism/Atlas and never sends a
PID signal.  If the host cannot provide the documented kernel capability, the
real test reports an explicit BLOCKED skip rather than claiming PASS.
"""

from __future__ import annotations

import errno
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts/preview/host_validation_containment.py"
SPEC = importlib.util.spec_from_file_location("host_validation_containment", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
containment = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(containment)


IDENTITY = {
    "jobId": "job-containment-test",
    "attemptId": "attempt-containment-test",
    "runId": "run-containment-test",
    "preparedDigest": "a" * 64,
}


class _FakeProcess:
    def __init__(self, returncode: int | None = 0) -> None:
        self.returncode = returncode

    def poll(self) -> int | None:
        return self.returncode

    def wait(self, timeout: float | None = None) -> int:
        if self.returncode is None:
            raise subprocess.TimeoutExpired("fake", timeout)
        return self.returncode


class PureContainmentTests(unittest.TestCase):
    def test_private_directory_rejects_symlinks_and_public_mode(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            private = containment._prepare_directory(root / "private")
            self.assertEqual(0, private.stat().st_mode & 0o077)
            (root / "link").symlink_to(private, target_is_directory=True)
            with self.assertRaises(ValueError):
                containment._prepare_directory(root / "link" / "child")
            private.chmod(0o755)
            with self.assertRaises(PermissionError):
                containment._prepare_directory(private)

    def test_event_parser_rejects_ambiguous_records(self):
        for text in ("populated 1\npopulated 0\n", "populated 0\nmalformed\n"):
            with self.assertRaises(ValueError):
                containment._parse_cgroup_events(text)
    def test_event_parser_requires_populated(self) -> None:
        self.assertEqual(
            containment._parse_cgroup_events("populated 0\n")['populated'],
            0,
        )
        self.assertEqual(
            containment._parse_cgroup_events("populated=1\n")['populated'],
            1,
        )
        with self.assertRaises(ValueError):
            containment._parse_cgroup_events("frozen=0\n")
        with self.assertRaises(ValueError):
            containment._parse_cgroup_events("populated=2\n")

    def test_scope_destroyed_between_path_check_and_events_read_is_proven(self) -> None:
        process = containment.ContainedProcess.__new__(containment.ContainedProcess)
        process._cgroup_path = Path("/not-a-real-cgroup")
        process._dir_identity = {"dev": 1, "inode": 2}
        process._events_fd = -1
        with mock.patch.object(containment, "_path_identity_state", side_effect=["same", "missing"]), \
             mock.patch.object(containment, "_read_fd_text", side_effect=containment._CgroupReadError(OSError(errno.ENODEV, "removed"))):
            self.assertEqual("destroyed", process._observe_cgroup()["kind"])

    def test_cgroup_path_requires_generated_scope_suffix(self) -> None:
        unit = "turboism-queue-" + "a" * 32 + ".scope"
        containment._validate_cgroup_path(f"/user.slice/user@1000.service/{unit}", unit)
        with self.assertRaises(containment.ContainmentError):
            containment._validate_cgroup_path("/user.slice/other.scope", unit)
        with self.assertRaises(containment.ContainmentError):
            containment._validate_cgroup_path(f"/user.slice/../{unit}", unit)

    def test_finish_fake_events_is_safe_without_real_kernel_access(self) -> None:
        with tempfile.TemporaryDirectory(prefix="containment-fake-") as raw:
            root = Path(raw)
            cgroup = root / "fake.scope"
            cgroup.mkdir()
            events = cgroup / "cgroup.events"
            events.write_text("populated=0\nfrozen=0\n", encoding="utf-8")
            kill = cgroup / "cgroup.kill"
            kill.write_bytes(b"")
            containment_path = root / "containment.json"
            metadata = {
                "schemaVersion": 1,
                **IDENTITY,
                "cleanup": "unknown",
                "state": "BOUND",
                "scopeUnit": "turboism-queue-" + "b" * 32 + ".scope",
                "cgroupPath": "/fake.scope",
                "cgroupDevice": cgroup.stat().st_dev,
                "cgroupInode": cgroup.stat().st_ino,
                "bootId": "fake-boot",
                "entryIdentity": {"pid": 1, "startTicks": "2", "bootId": "fake-boot", "uid": os.getuid()},
                "kernelReadings": {"initial": {"populated": 0, "events": "populated=0\n"}},
            }
            containment_path.write_text(json.dumps(metadata), encoding="utf-8")
            dir_fd = os.open(cgroup, os.O_RDONLY | os.O_DIRECTORY)
            events_fd = os.open(events, os.O_RDONLY)
            kill_fd = os.open(kill, os.O_WRONLY)
            try:
                process = containment.ContainedProcess(
                    process=_FakeProcess(),
                    metadata=metadata,
                    containment_path=containment_path,
                    cgroup_path=cgroup,
                    cgroup_dir_fd=dir_fd,
                    events_fd=events_fd,
                    kill_fd=kill_fd,
                    request_path=root / "request.json",
                )
                proof = process.finish(timeout_seconds=1)
                self.assertEqual(proof["cleanup"], "safe")
                self.assertEqual(proof["schemaVersion"], 1)
                self.assertEqual(proof["jobId"], IDENTITY["jobId"])
                self.assertEqual(proof["kernelProof"]["finalReading"]["populated"], 0)
                process.close()
            finally:
                for fd in (events_fd, kill_fd, dir_fd):
                    try:
                        os.close(fd)
                    except OSError:
                        pass

    def test_finish_fake_malformed_events_is_unknown(self) -> None:
        with tempfile.TemporaryDirectory(prefix="containment-fake-unknown-") as raw:
            root = Path(raw)
            cgroup = root / "fake.scope"
            cgroup.mkdir()
            events = cgroup / "cgroup.events"
            events.write_text("frozen=0\n", encoding="utf-8")
            kill = cgroup / "cgroup.kill"
            kill.write_bytes(b"")
            containment_path = root / "containment.json"
            metadata = {
                "schemaVersion": 1,
                **IDENTITY,
                "cleanup": "unknown",
                "state": "BOUND",
                "scopeUnit": "turboism-queue-" + "c" * 32 + ".scope",
                "cgroupPath": "/fake.scope",
                "cgroupDevice": cgroup.stat().st_dev,
                "cgroupInode": cgroup.stat().st_ino,
                "bootId": "fake-boot",
                "entryIdentity": {"pid": 1, "startTicks": "2", "bootId": "fake-boot", "uid": os.getuid()},
            }
            containment_path.write_text(json.dumps(metadata), encoding="utf-8")
            dir_fd = os.open(cgroup, os.O_RDONLY | os.O_DIRECTORY)
            events_fd = os.open(events, os.O_RDONLY)
            kill_fd = os.open(kill, os.O_WRONLY)
            try:
                process = containment.ContainedProcess(
                    process=_FakeProcess(),
                    metadata=metadata,
                    containment_path=containment_path,
                    cgroup_path=cgroup,
                    cgroup_dir_fd=dir_fd,
                    events_fd=events_fd,
                    kill_fd=kill_fd,
                    request_path=root / "request.json",
                )
                proof = process.finish(timeout_seconds=0.2)
                self.assertEqual(proof["cleanup"], "unknown")
                self.assertTrue(proof["kernelProof"]["errors"])
                process.close()
            finally:
                for fd in (events_fd, kill_fd, dir_fd):
                    try:
                        os.close(fd)
                    except OSError:
                        pass

    def test_invalid_start_rejected_before_host_capability_probe(self) -> None:
        with mock.patch.object(containment.shutil, "which", side_effect=AssertionError("host probe reached")):
            with self.assertRaises(ValueError):
                containment.start(
                    ["/bin/true"],
                    directory=Path(tempfile.gettempdir()),
                    environment={},
                    admission_fd=-1,
                    identity=IDENTITY,
                    output=io.BytesIO(),
                )

    def test_normalized_command_allows_empty_option_values(self) -> None:
        containment._validate_command(["bash", "runner.sh", "--fixture-sha256", ""])
        with self.assertRaises(ValueError):
            containment._validate_command([""])
        with self.assertRaises(ValueError):
            containment._validate_command(["bash", "bad\0argument"])

    def test_helper_never_uses_pid_signal_or_shell_execution(self) -> None:
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertNotIn("os.kill(", source)
        self.assertNotIn("signal.", source)
        self.assertNotIn("shell=True", source)
        self.assertNotIn("subprocess.run(", source)


@unittest.skipUnless(sys.platform.startswith("linux"), "BLOCKED: Linux cgroup test requires Linux")
class RealKernelContainmentTest(unittest.TestCase):
    def test_random_scope_ack_fd_inheritance_late_setsid_and_cgroup_kill(self) -> None:
        if not shutil_which("systemd-run"):
            self.skipTest("BLOCKED: systemd-run is unavailable")
        if not (Path("/sys/fs/cgroup") / "cgroup.controllers").exists():
            self.skipTest("BLOCKED: unified cgroup v2 is unavailable")

        with tempfile.TemporaryDirectory(prefix="containment-kernel-") as raw:
            # Exercise a socket directory as deep as the real account-home queue.
            directory = Path(raw) / ("j" * (89 - len(os.fsencode(raw)) - 1))
            directory.mkdir(mode=0o700)
            marker = directory / "markers"
            marker.mkdir()
            admission_read, admission_write = os.pipe()
            output_path = directory / "systemd-run.out"
            command = """
import json
import os
from pathlib import Path
import subprocess
import sys
import time

marker = Path(os.environ["CONTAINMENT_MARKER"])
fd = int(os.environ["CONTAINMENT_ADMISSION_FD"])
try:
    os.fstat(fd)
    (marker / "admission-fd-ok").touch()
except OSError:
    (marker / "admission-fd-failed").touch()
record = json.loads((marker.parent / "containment.json").read_text(encoding="utf-8"))
if record.get("state") != "BOUND":
    (marker / "ack-order-failed").write_text(record.get("state", "missing"), encoding="utf-8")
child_code = "import pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text(str(__import__('os').getpid())); time.sleep(30)"
child = subprocess.Popen(
    [sys.executable, "-c", child_code, str(marker / "late-pid")],
    start_new_session=True,
    close_fds=True,
)
(marker / "root-pid").write_text(str(os.getpid()), encoding="utf-8")
(marker / "entered").touch()
# The launch root exits normally; the setsid child must remain contained.
sys.exit(0)
"""
            environment = {
                "CONTAINMENT_MARKER": str(marker),
                "CONTAINMENT_ADMISSION_FD": str(admission_read),
                "PYTHONUNBUFFERED": "1",
            }
            contained = None
            try:
                with output_path.open("wb") as output:
                    contained = containment.start(
                        [sys.executable, "-c", command],
                        directory=directory,
                        environment=environment,
                        admission_fd=admission_read,
                        identity=IDENTITY,
                        output=output,
                    )
                    self.assertTrue(_wait_for(marker / "entered"))
                    self.assertTrue((marker / "admission-fd-ok").exists())
                    self.assertFalse((marker / "ack-order-failed").exists())
                    self.assertTrue(_wait_for(marker / "late-pid"))
                    late_pid = int((marker / "late-pid").read_text(encoding="utf-8"))
                    root_pid = int((marker / "root-pid").read_text())
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        contained.process.poll()  # Reap the test's own exited launch root.
                        if _wait_for_proc_gone(root_pid, timeout=0.05):
                            break
                    self.assertFalse((Path('/proc') / str(root_pid)).exists())
                    self.assertEqual(contained.metadata["cgroupPath"], containment._read_process_cgroup(late_pid))
                    self.assertTrue(contained.metadata["cgroupPath"].endswith(contained.metadata["scopeUnit"]))
                    self.assertEqual(contained.entry_identity["bootId"], contained.metadata["bootId"])
                    proof = contained.finish(timeout_seconds=10)
                self.assertEqual(proof["cleanup"], "safe", proof)
                self.assertTrue(proof["kernelProof"]["cgroupKillWritten"])
                self.assertIn(
                    proof["kernelProof"]["cleanupMethod"],
                    {"cgroup.kill+populated=0", "cgroup.kill+cgroup-destroyed"},
                )
                self.assertTrue(_wait_for_proc_gone(late_pid))
                persisted = json.loads((directory / "containment.json").read_text(encoding="utf-8"))
                self.assertEqual(persisted["cleanup"], "safe")
                self.assertEqual(persisted["jobId"], IDENTITY["jobId"])
                print(
                    "REAL_KERNEL_CONTAINMENT PASS "
                    f"scope={proof['scopeUnit']} cgroup={proof['cgroupPath']} "
                    f"cleanupMethod={proof['kernelProof']['cleanupMethod']} "
                    f"lateChildGone={not (Path('/proc') / str(late_pid)).exists()}"
                )
            finally:
                if contained is not None:
                    try:
                        if contained._finish_result is None:
                            contained.finish(timeout_seconds=10)
                    finally:
                        contained.close()
                os.close(admission_read)
                os.close(admission_write)


@unittest.skipUnless(sys.platform.startswith("linux"), "BLOCKED: Linux cgroup admission requires Linux")
class RealKernelAdmissionTest(unittest.TestCase):
    def test_real_queue_admission_validates_scope_membership_and_pid_types(self):
        sys.path.insert(0, str(MODULE_PATH.parent))
        import host_validation_queue as queue
        with tempfile.TemporaryDirectory(prefix="containment-admission-") as raw:
            store = queue.Store(Path(raw) / "queue")
            store.submit("prepared", "d" * 64, "test")
            job = store.claim()
            store.acknowledge(job["job_id"], job["attempt_id"], queue.process_identity(os.getpid()))
            identity = {"jobId": job["job_id"], "attemptId": job["attempt_id"],
                        "runId": job["run_id"], "preparedDigest": job["digest"]}
            directory = queue.private_directory(store.root / "jobs" / job["job_id"])
            code = ("import sys; from pathlib import Path; sys.path.insert(0,sys.argv[1]); "
                    "import host_validation_queue as q; q.account_root=lambda:Path(sys.argv[2]); "
                    "print(q.canonical_json(q.validate_admission()))")
            contained = None
            with queue.FileLock(store.root / "admission.lock") as lock, (directory / "test.log").open("w+b") as output:
                environment = {"TURBOISM_QUEUE_JOB_ID": job["job_id"],
                    "TURBOISM_QUEUE_ATTEMPT_ID": job["attempt_id"], "TURBOISM_QUEUE_RUN_ID": job["run_id"],
                    "TURBOISM_QUEUE_ADMISSION_FD": str(lock.fd), "TURBOISM_QUEUE_SUPERVISOR_PID": str(os.getpid())}
                try:
                    contained = containment.start([sys.executable, "-c", code, str(MODULE_PATH.parent), str(store.root)],
                        directory=directory, environment=environment, admission_fd=lock.fd, identity=identity, output=output)
                    returncode = contained.process.wait(timeout=10)
                    output.seek(0)
                    text = output.read().decode()
                    self.assertEqual(0, returncode, text)
                    admitted = json.loads(text)
                    self.assertEqual("supervisor", admitted["cleanupOwner"])
                    self.assertEqual(job["run_id"], admitted["runId"])
                    self.assertEqual(contained.metadata["cgroupInode"], admitted["containment"]["cgroupInode"])
                    proof = contained.finish(timeout_seconds=10)
                    self.assertEqual("safe", proof["cleanup"], proof)
                finally:
                    if contained is not None:
                        if contained._finish_result is None:
                            contained.finish(timeout_seconds=10)
                        contained.close()


def shutil_which(name: str) -> str | None:
    import shutil

    return shutil.which(name)


def _wait_for(path: Path, timeout: float = 5.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            return True
        time.sleep(0.05)
    return path.exists()


def _wait_for_proc_gone(pid: int, timeout: float = 5.0) -> bool:
    deadline = time.monotonic() + timeout
    path = Path("/proc") / str(pid)
    while time.monotonic() < deadline:
        if not path.exists():
            return True
        time.sleep(0.05)
    return not path.exists()


if __name__ == "__main__":
    unittest.main(verbosity=2)
