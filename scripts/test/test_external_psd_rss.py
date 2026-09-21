#!/usr/bin/env python3
"""Offline safety/measurement tests; synthetic proc files are never host evidence."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[2] / "validation/external-psd-edit-host-probe/rss.py"
spec = importlib.util.spec_from_file_location("external_psd_rss", MODULE)
rss = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rss)


class RssObservationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.proc, self.groups = self.root / "proc", self.root / "groups"
        self.group = "/user.slice/task.scope"
        self.scope_path = self.groups / self.group[1:]
        self.scope_path.mkdir(parents=True)
        for name, text in (("self/cgroup", "0::" + self.group + "\n"),
                           ("sys/kernel/random/boot_id", "boot-test\n")):
            path = self.proc / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        stat = self.scope_path.stat()
        self.metadata = dict(cgroupPath=self.group, cgroupDevice=stat.st_dev,
                             cgroupInode=stat.st_ino, bootId="boot-test")
        self.scope = rss.Scope(self.metadata, self.groups, self.proc)
        self.process(123, 456)
        (self.scope_path / "cgroup.procs").write_text("123\n")

    def process(self, pid, ticks, argv=None):
        path = self.proc / str(pid)
        path.mkdir(exist_ok=True)
        # Field 3 is state; ticks occupy field 22, after 19 fields starting at state.
        (path / "stat").write_text(f"{pid} (java (test) exe) " + " ".join(["S"] + ["0"] * 18 + [str(ticks)]))
        (path / "cgroup").write_text("0::" + self.group + "\n")
        (path / "cmdline").write_bytes(b"java.exe\0" + (rss.MAIN_CLASS if argv is None else argv) + b"\0")
        (path / "status").write_text("VmRSS:\t120 kB\nVmHWM:\t150 kB\n")

    def test_exact_process_and_memory_units(self):
        self.assertEqual((123, 456, 120 * 1024, 150 * 1024), self.scope.sample())
        self.process(456, 789, rss.MAIN_CLASS + b"Fake")
        (self.scope_path / "cgroup.procs").write_text("123\n456\n")
        self.assertEqual(123, self.scope.sample()[0])

    def test_pid_reuse_and_ambiguous_main_rejected(self):
        with self.assertRaises(rss.IdentityChanged):
            self.scope.sample((123, 999))
        self.process(456, 789)
        (self.scope_path / "cgroup.procs").write_text("123\n456\n")
        with self.assertRaises(rss.IdentityChanged):
            self.scope.sample()

    def test_scope_and_process_membership_change_rejected(self):
        (self.proc / "123/cgroup").write_text("0::/unrelated.scope\n")
        with self.assertRaises(rss.IdentityChanged):
            self.scope.sample()
        self.metadata["cgroupInode"] += 1
        with self.assertRaises(rss.IdentityChanged):
            rss.Scope(self.metadata, self.groups, self.proc)

    def test_terminal_binding_and_peak_semantics(self):
        terminal = self.root / "result.properties"
        terminal.write_text("runId=queue-test\nstatus=PASS\n")
        report = rss.collect(self.scope, terminal, "queue-test", 1)
        self.assertTrue(report["complete"])
        self.assertEqual("NOT_EVALUATED", report["performanceStatus"])
        self.assertEqual(150 * 1024, report["processLifetimeHighWaterRssBytes"])
        with self.assertRaises(rss.IdentityChanged):
            rss.terminal_status(terminal, "another-task")
        terminal.write_text("runId=queue-test\nstatus=PASS\nstatus=FAIL\n")
        with self.assertRaises(rss.IdentityChanged):
            rss.terminal_status(terminal, "queue-test")

    def test_zero_samples_not_complete_and_memory_unknown_rejected(self):
        terminal = self.root / "result.properties"
        terminal.write_text("runId=queue-test\nstatus=PASS\n")
        (self.scope_path / "cgroup.procs").write_text("")
        report = rss.collect(self.scope, terminal, "queue-test", 1)
        self.assertFalse(report["complete"])
        self.assertEqual("UNAVAILABLE", report["observationStatus"])
        for value in ("VmRSS: 0 kB\nVmHWM: 0 kB", "VmRSS: 100 kB", "VmRSS: 200 kB\nVmHWM: 100 kB"):
            with self.assertRaises((ValueError, KeyError)):
                rss.memory_bytes(value)

    def test_missing_process_after_binding_never_rebinds(self):
        with patch.object(self.scope, "sample", side_effect=[(123, 456, 120, 150), None]), \
             patch.object(rss, "terminal_status", side_effect=[None, "PASS"]), \
             patch.object(rss.time, "sleep"):
            report = rss.collect(self.scope, self.root / "unused", "queue-test", 1)
        self.assertFalse(report["complete"])
        self.assertEqual(1, report["missedAfterBinding"])


if __name__ == "__main__":
    unittest.main()
