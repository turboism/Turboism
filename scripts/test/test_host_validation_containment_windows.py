#!/usr/bin/env python3
"""Cross-platform import, identity, lock and containment contracts.

The host-validation toolchain must load and behave correctly on both supported
host classes: Linux (Proton for full Cubism host validation, Xvfb for headless
Gradle checks) and native Windows x64.  Every test here is written so that it
states which contract it proves on which host class:

* platform-neutral tests run on POSIX and Windows and cover hvq.common,
  hvq.display and host_validation_containment imports, owner/identity fencing,
  private directories, advisory locks, atomic JSON and session discovery on a
  synthetic process root;
* POSIX-only tests keep the Linux ownership/mode/descriptor fixtures;
* Windows-only tests exercise the real Job Object containment backend.

Nothing here launches Cubism, Atlas or the queue worker.
"""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
PREVIEW = ROOT / "scripts/preview"
sys.path.insert(0, str(PREVIEW))

import hvq.common as common  # noqa: E402
import hvq.display as display  # noqa: E402

MODULE_PATH = PREVIEW / "host_validation_containment.py"
SPEC = importlib.util.spec_from_file_location("host_validation_containment", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
containment = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(containment)

POSIX_HOST = hasattr(os, "getuid") and hasattr(os, "O_DIRECTORY")
WINDOWS_HOST = os.name == "nt"

IDENTITY = {
    "jobId": "job-windows-containment-test",
    "attemptId": "attempt-windows-containment-test",
    "runId": "run-windows-containment-test",
    "preparedDigest": "b" * 64,
}

# Prints its own pid, spawns a long-lived grandchild and exits: the grandchild
# must not survive containment on either backend.
GRANDCHILD_CODE = (
    "import subprocess,sys,time,pathlib\n"
    "child = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(120)'])\n"
    "pathlib.Path(sys.argv[1]).write_text(str(child.pid), encoding='utf-8')\n"
    "time.sleep(0.2)\n"
)


def _wait_for(predicate, timeout: float = 10.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.05)
    return predicate()


class ImportContractTest(unittest.TestCase):
    """The toolchain imports on every supported host class."""

    def test_imported_modules_expose_their_platform_neutral_surface(self) -> None:
        for module in (common, display, containment):
            self.assertIsNotNone(module, "module failed to import")
        for name in ("account_root", "private_directory", "atomic_json", "process_identity",
                     "identity_alive", "FileLock", "storage_lock"):
            self.assertTrue(callable(getattr(common, name)), f"hvq.common.{name} is missing")
        for name in ("wake", "external_sessions", "scope_identity", "snapshot_module"):
            self.assertTrue(callable(getattr(display, name)), f"hvq.display.{name} is missing")
        for name in ("start", "ContainedProcess", "ContainmentError", "ContainmentUnsupported"):
            self.assertTrue(hasattr(containment, name), f"containment.{name} is missing")

    def test_posix_only_primitives_are_optional_not_required(self) -> None:
        # Module import must succeed even where these stdlib modules do not
        # exist; each guarded backend reports the absence instead of crashing.
        self.assertTrue(hasattr(common, "fcntl"))
        self.assertTrue(hasattr(common, "pwd"))
        self.assertTrue(hasattr(common, "msvcrt"))
        self.assertTrue(hasattr(containment, "fcntl"))
        self.assertEqual(common.fcntl is None, os.name == "nt")
        self.assertEqual(common.pwd is None, os.name == "nt")
        self.assertEqual(common.msvcrt is None, os.name != "nt")


class AccountAndIdentityTest(unittest.TestCase):
    """Owner root and PID-lifetime identity are host-appropriate."""

    def test_account_root_is_one_domain_per_user(self) -> None:
        root = common.account_root()
        self.assertTrue(root.is_absolute(), root)
        self.assertEqual(root.name, "host-validation")
        self.assertEqual(root.parent.name, "turboism")
        # A HOME/XDG override must never split one user's lock domain.
        saved = os.environ.get("HOME")
        os.environ["HOME"] = os.path.join(tempfile.gettempdir(), "not-the-account-home")
        try:
            self.assertEqual(root, common.account_root())
        finally:
            if saved is None:
                os.environ.pop("HOME", None)
            else:
                os.environ["HOME"] = saved
        if common.pwd is not None and hasattr(os, "getuid"):
            self.assertEqual(root, Path(common.pwd.getpwuid(os.getuid()).pw_dir)
                             / ".local/state/turboism/host-validation")
        else:
            self.assertEqual(root, Path.home() / ".local/state/turboism/host-validation")

    def test_current_uid_matches_the_host_model(self) -> None:
        self.assertIsInstance(common._current_uid(), int)
        if hasattr(os, "getuid"):
            self.assertEqual(common._current_uid(), os.getuid())
        else:
            self.assertEqual(common._current_uid(), 0)

    def test_process_identity_fences_pid_reuse_and_boot_changes(self) -> None:
        current = common.process_identity(os.getpid())
        self.assertIsNotNone(current)
        assert current is not None
        self.assertEqual(current["pid"], os.getpid())
        self.assertTrue(common.identity_alive(current))
        self.assertFalse(common.identity_alive({**current, "startTicks": current["startTicks"] + 1}))
        self.assertFalse(common.identity_alive({**current, "bootId": "some-other-boot"}))
        if WINDOWS_HOST:
            self.assertEqual(current["bootId"], "windows")
        else:
            self.assertEqual(current["bootId"],
                             Path("/proc/sys/kernel/random/boot_id").read_text().strip())

    def test_missing_process_has_no_identity(self) -> None:
        self.assertIsNone(common.process_identity(2 ** 22 + 12345))


class PrivateStorageTest(unittest.TestCase):
    """Private directories, atomic JSON and advisory locks."""

    def test_private_directory_and_atomic_json_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = common.private_directory(Path(raw) / "queue" / "jobs")
            self.assertTrue(root.is_dir())
            if POSIX_HOST:
                self.assertEqual(0, root.stat().st_mode & 0o077)
            common.atomic_json(root / "record.json", {"schemaVersion": 1, "z": [1, 2]})
            self.assertEqual({"schemaVersion": 1, "z": [1, 2]},
                             json.loads((root / "record.json").read_text(encoding="utf-8")))
            self.assertEqual([], sorted(p.name for p in root.iterdir() if p.name.startswith(".")))

    def test_admission_lock_rejects_a_second_owner(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "admission.lock"
            with common.FileLock(path):
                with self.assertRaises(common.QueueBusy):
                    common.FileLock(path).acquire()
            with common.FileLock(path):
                pass  # Released cleanly; a new owner may take it.

    def test_storage_lock_separates_readers_from_collection(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            with common.storage_lock(root):
                # A collection may not run while a reader holds the domain.
                # (Windows has no shared byte-range lock, so on that host a
                # reader also excludes other readers; POSIX keeps them
                # concurrent, and this contract is unchanged either way.)
                with self.assertRaises(common.QueueBusy):
                    with common.storage_lock(root, exclusive=True):
                        pass
            with common.storage_lock(root, exclusive=True):
                with self.assertRaises(common.QueueBusy):
                    with common.storage_lock(root):
                        pass
            with common.storage_lock(root, exclusive=True):
                pass


class DisplaySessionTest(unittest.TestCase):
    """Wake-up hints and session discovery never require POSIX-only sockets."""

    def test_wake_is_a_harmless_hint_on_every_host(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            class _Store:
                root = Path(raw)
            display.wake(_Store)  # No listener exists; durable state wins.

    def test_external_sessions_honours_a_synthetic_proc_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            proc = Path(temporary) / "proc"
            proc.mkdir()

            def entry(pid: int, comm: str, cmdline: bytes, environ: bytes = b"") -> None:
                directory = proc / str(pid)
                directory.mkdir()
                (directory / "comm").write_text(comm, encoding="utf-8")
                (directory / "cmdline").write_bytes(cmdline)
                (directory / "environ").write_bytes(environ)

            entry(1111, "java", b"java\x00-jar\x00/opt/cubism/Live2D_Cubism.jar")
            entry(2222, "java", b"java\x00-jar\x00/state/queue-abc123/Live2D_Cubism.jar")
            entry(3333, "bash", b"bash\x00helper\x00CubismEditor")
            # A "bash" command line that merely mentions Cubism is never a
            # candidate; an owned run_id makes its own session task-owned.
            self.assertEqual([1111, 2222], display.external_sessions(proc_root=proc))
            self.assertEqual([1111], display.external_sessions({"queue-abc123"}, proc_root=proc))

    def test_external_sessions_is_empty_without_a_process_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            missing = Path(temporary) / "absent-proc"
            self.assertEqual([], display.external_sessions(proc_root=missing))
        if not Path("/proc").is_dir():
            # Native Windows has no process root, so nothing foreign is reported.
            self.assertEqual([], display.external_sessions())


class ContainmentSeamTest(unittest.TestCase):
    """The containment seam selects exactly one backend per host class."""

    def test_backend_selection_matches_the_host(self) -> None:
        supported = containment._windows_containment_supported()
        self.assertEqual(supported, WINDOWS_HOST and containment._CGROUP_ROOT == Path("/sys/fs/cgroup"))
        self.assertEqual(containment._CGROUP_ROOT, Path("/sys/fs/cgroup"))

    def test_prepare_directory_and_atomic_record(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = containment._prepare_directory(Path(raw) / "job")
            self.assertTrue(directory.is_dir())
            containment._atomic_write_json(directory / "containment.json", IDENTITY)
            self.assertEqual(IDENTITY, json.loads(
                (directory / "containment.json").read_text(encoding="utf-8")))
            with self.assertRaises(ValueError):
                containment._prepare_directory(directory / ".." / "job")

    def test_start_rejects_invalid_input_before_any_host_side_effect(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            with self.assertRaises(ValueError):
                containment.start([], directory=Path(raw), environment={}, admission_fd=0,
                                  identity=IDENTITY, output=sys.stdout.buffer)
            with self.assertRaises(ValueError):
                containment.start(["/bin/true"], directory=Path(raw), environment={},
                                  admission_fd=-1, identity=IDENTITY, output=sys.stdout.buffer)
            with self.assertRaises(ValueError):
                containment.start(["/bin/true"], directory=Path(raw), environment={},
                                  admission_fd=0, identity={"jobId": "only"},
                                  output=sys.stdout.buffer)

    @unittest.skipUnless(POSIX_HOST, "BLOCKED: POSIX ownership and mode bits required")
    def test_posix_private_directory_requires_private_mode(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            private = containment._prepare_directory(Path(raw) / "job")
            private.chmod(0o755)
            with self.assertRaises(PermissionError):
                containment._prepare_directory(private)


class PosixBackendTest(unittest.TestCase):
    @unittest.skipUnless(POSIX_HOST, "BLOCKED: Linux cgroup backend")
    def test_linux_backend_still_requires_systemd_containment(self) -> None:
        self.assertFalse(containment._windows_containment_supported())
        self.assertIsNotNone(containment.fcntl)
        self.assertTrue(callable(containment.ContainedProcess.finish))


class WindowsJobContainmentTest(unittest.TestCase):
    """Real Job Object containment; proves descendant cleanup, never a PID list."""

    @unittest.skipUnless(WINDOWS_HOST, "BLOCKED: Windows Job Object backend")
    def test_job_object_binds_payload_and_kills_descendants(self) -> None:
        with tempfile.TemporaryDirectory(prefix="containment-windows-") as raw:
            root = Path(raw)
            directory = containment._prepare_directory(root / "job")
            marker = root / "grandchild.pid"
            admission = os.open(root / "admission.lock", os.O_RDWR | os.O_CREAT, 0o600)
            environment = {key: os.environ[key] for key in ("PATH", "SYSTEMROOT", "TEMP")
                           if key in os.environ}
            contained = None
            try:
                with (directory / "runner.log").open("wb") as output:
                    contained = containment.start(
                        [sys.executable, "-c", GRANDCHILD_CODE, str(marker)],
                        directory=directory,
                        environment=environment,
                        admission_fd=admission,
                        identity=IDENTITY,
                        output=output,
                    )
                    self.assertIsInstance(contained, containment.WindowsJobContainedProcess)
                    self.assertTrue(_wait_for(marker.exists), "grandchild never started")
                    grandchild = int(marker.read_text(encoding="utf-8"))
                    grandchild_identity = common.process_identity(grandchild)
                    self.assertIsNotNone(grandchild_identity)
                    self.assertTrue(common.identity_alive(grandchild_identity))
                    record = json.loads((directory / "containment.json").read_text(encoding="utf-8"))
                    self.assertEqual(record["state"], "BOUND")
                    self.assertEqual(record["backend"], "windows-job")
                    self.assertEqual(record["schemaVersion"], 1)
                    self.assertEqual(record["jobId"], IDENTITY["jobId"])
                    self.assertEqual(record["entryIdentity"], contained.entry_identity)
                    self.assertEqual(record["identity"]["runId"], IDENTITY["runId"])
                    proof = contained.finish(timeout_seconds=20)
                self.assertEqual(proof["cleanup"], "safe", proof)
                self.assertEqual(proof["schemaVersion"], 1)
                self.assertEqual(proof["backend"], "windows-job")
                self.assertEqual(proof["exitCode"], 0)
                self.assertFalse(proof["timedOut"])
                self.assertEqual(proof["kernelProof"]["errors"], [])
                self.assertTrue(proof["kernelProof"]["originalJobBound"])
                self.assertEqual(proof["kernelProof"]["jobActiveProcesses"], 0)
                self.assertEqual(proof["kernelProof"]["finalReading"]["populated"], 0)
                self.assertTrue(_wait_for(
                    lambda: common.process_identity(grandchild) is None),
                    "contained descendant survived job cleanup")
                persisted = json.loads((directory / "containment.json").read_text(encoding="utf-8"))
                self.assertEqual(persisted["state"], "FINISHED")
                self.assertEqual(persisted["cleanup"], "safe")
                self.assertEqual(persisted["kernelProof"], proof["kernelProof"])
            finally:
                if contained is not None:
                    try:
                        if contained._finish_result is None:
                            contained.finish(timeout_seconds=20)
                    finally:
                        contained.close()
                os.close(admission)

    @unittest.skipUnless(WINDOWS_HOST, "BLOCKED: Windows Job Object backend")
    def test_job_proof_reports_a_nonzero_exit_code(self) -> None:
        with tempfile.TemporaryDirectory(prefix="containment-windows-exit-") as raw:
            root = Path(raw)
            directory = containment._prepare_directory(root / "job")
            admission = os.open(root / "admission.lock", os.O_RDWR | os.O_CREAT, 0o600)
            contained = None
            try:
                with (directory / "runner.log").open("wb") as output:
                    contained = containment.start(
                        [sys.executable, "-c", "raise SystemExit(3)"],
                        directory=directory,
                        environment={"PATH": os.environ.get("PATH", "")},
                        admission_fd=admission,
                        identity=IDENTITY,
                        output=output,
                    )
                    proof = contained.finish(timeout_seconds=20)
                self.assertEqual(proof["exitCode"], 3)
                self.assertEqual(proof["cleanup"], "safe", proof)
            finally:
                if contained is not None:
                    try:
                        if contained._finish_result is None:
                            contained.finish(timeout_seconds=20)
                    finally:
                        contained.close()
                os.close(admission)


if __name__ == "__main__":
    unittest.main()
