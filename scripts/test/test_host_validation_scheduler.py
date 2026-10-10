#!/usr/bin/env python3
"""Offline contract tests for the exact-host resource scheduler."""

from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
PREVIEW = ROOT / "scripts" / "preview"
REVIEWED_VERSIONS = (
    "5000", "5001", "5002", "5003", "5004", "5005", "5006",
    "5100", "5101", "5102", "5103", "5200", "5201", "5202", "5203",
    "5300", "5301", "5302", "5303", "5304",
)
sys.path.insert(0, str(PREVIEW))

import host_validation as scheduler  # noqa: E402
queue = scheduler.queue


class HostValidationSchedulerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest_path = PREVIEW / "host-validation-tasks.json"
        cls.manifest = scheduler.load_manifest(cls.manifest_path)

    def request(self, spec: str) -> scheduler.Request:
        return scheduler.parse_request(spec, "test-run", self.manifest)

    def test_manifest_covers_supported_wrappers_and_resource_boundaries(self) -> None:
        expected = {
            "animation-timeline",
            "atlas",
            "backup",
            "backup-interactive",
            "backup-webdav",
            "boundingbox-warp-mirror",
            "clipmask-viewer",
            "core-acquisition",
            "core-owned-moc",
            "dialog-automation",
            "edit",
            "edit-protocol",
            "fps",
            "host-locale",
            "incremental-update",
            "mcp",
            "model-update-skip",
            "parameter",
            "parameter-batch-transfer",
            "protected-export",
            "psd-clip-mask",
            "recent-preview",
            "selection-brush",
            "selection-lag",
            "separate-save-path",
            "session-standard",
            "startup-suppression",
            "status-bar",
            "theme",
            "texture-relations",
            "psd-export-observation",
            "update-check",
            "warp-deformer-alt-symmetry",
            "workspace",
        }
        self.assertEqual(expected, set(self.manifest.tasks))
        self.assertEqual(1, self.manifest.resources["host-slot"].capacity)
        self.assertEqual(
            {"host-slot": 1, "display-input": 1, "performance-host": 1},
            self.manifest.tasks["fps"].resources,
        )
        self.assertEqual(
            {"host-slot": 1, "display-input": 1, "interactive-desktop": 1},
            self.manifest.tasks["backup-interactive"].resources,
        )
        self.assertFalse(self.manifest.tasks["dialog-automation"].runnable)
        self.assertFalse(self.manifest.tasks["backup-interactive"].runnable)

    def test_mcp_reserves_exact_host_and_display_for_native_close(self) -> None:
        task = self.manifest.tasks["mcp"]
        self.assertEqual(REVIEWED_VERSIONS, task.versions)
        self.assertEqual({"host-slot": 1, "display-input": 1}, task.resources)
        with mock.patch("subprocess.run", side_effect=AssertionError("Plan must not start the host")):
            for version in task.versions:
                command = scheduler.render_command(self.request("mcp:" + version), self.manifest)
                self.assertTrue(command[1].endswith("run-mcp-host-validation.sh"))
                self.assertEqual([version, "test-run"], command[2:])
        with self.assertRaises(scheduler.SchedulerError):
            self.request("mcp:5400")

    def test_owned_moc_is_separate_from_mcp_and_requires_observed_core_tuple(self) -> None:
        task = self.manifest.tasks["core-owned-moc"]
        self.assertEqual(REVIEWED_VERSIONS, task.versions)
        self.assertEqual({"host-slot": 1, "display-input": 1}, task.resources)
        with mock.patch("subprocess.run", side_effect=AssertionError("Plan must not start the host")):
            for version in task.versions:
                command = scheduler.render_command(self.request("core-owned-moc:" + version), self.manifest)
                self.assertTrue(command[1].endswith("run-core-owned-moc-host-validation.sh"))
                self.assertEqual([version, "test-run"], command[2:])
        with self.assertRaises(scheduler.SchedulerError):
            self.request("core-owned-moc:5400")
        wrapper = (PREVIEW / "run-core-owned-moc-host-validation.sh").read_text()
        self.assertIn("state/core-owned-moc-result.properties", wrapper)
        self.assertIn("--require-fixture-unchanged", wrapper)
        self.assertNotIn("--client-script", wrapper)
        self.assertIn("-Dturboism.optimization.mesaGlThread=false", wrapper)
        self.assertIn("--linux-env 'mesa_glthread=false'", wrapper)

    def test_texture_relation_and_psd_observation_have_reviewed_exact_version_boundaries(self) -> None:
        self.assertEqual(REVIEWED_VERSIONS, self.manifest.tasks["texture-relations"].versions)
        self.assertEqual(REVIEWED_VERSIONS[-5:], self.manifest.tasks["psd-export-observation"].versions)
        for name in ("texture-relations", "psd-export-observation"):
            self.assertEqual({"host-slot": 1, "display-input": 1}, self.manifest.tasks[name].resources)
            for version in self.manifest.tasks[name].versions:
                command = scheduler.render_command(self.request(name + ":" + version + "@assisted"), self.manifest)
                self.assertEqual([version, "test-run"], command[2:4])
                self.assertIn("-Dturboism.validation.textures.startFree=true", command)
            with self.assertRaises(scheduler.SchedulerError):
                self.request(name + ":5400")
        with self.assertRaises(scheduler.SchedulerError):
            self.request("psd-export-observation:5203")

    def test_assisted_core_preflight_is_explicit_and_reserves_display(self) -> None:
        task = self.manifest.tasks["core-acquisition"]
        self.assertEqual(REVIEWED_VERSIONS, task.versions)
        self.assertEqual({"host-slot": 1, "display-input": 1}, task.resources)
        standard = scheduler.render_command(self.request("core-acquisition:5302"), self.manifest)
        self.assertEqual(["5302", "test-run"], standard[2:])
        assisted = scheduler.render_command(self.request("core-acquisition:5302@assisted"), self.manifest)
        self.assertEqual([
            "5302", "test-run", "--jvm-option", "-Dturboism.coreAcquisition.startFree=true",
            "--jvm-option", "-Dturboism.optimization.mesaGlThread=false",
            "--linux-env", "mesa_glthread=false",
        ], assisted[2:])

    def test_save_only_export_never_silently_replaces_readable_profile(self) -> None:
        standard = scheduler.render_command(self.request("psd-export-observation:5304"), self.manifest)
        self.assertNotIn("-Dturboism.validation.textures.exportSaveOnly=true", standard)
        saved = scheduler.render_command(self.request("psd-export-observation:5304@saved-only-assisted"), self.manifest)
        self.assertIn("-Dturboism.validation.textures.exportSaveOnly=true", saved)
        self.assertIn("-Dturboism.validation.textures.startFree=true", saved)

    # A wrapper that intentionally lets variant args override a pinned runner
    # option names the overridable names in a comment of this shape; without it
    # a name declared by both sides reaches the runner twice and fails argument
    # validation (the session-standard prepare failure on every -assisted run).
    CONDITIONAL_RUNNER_ARGS = re.compile(r"^\s*#\s*conditional-runner-args:\s*(\S.*?)\s*$")
    PIN_PATTERNS = (
        ("--linux-env", re.compile(r"--linux-env\s+['\"]?([A-Za-z_][A-Za-z0-9_]*)=")),
        ("--windows-env", re.compile(r"--windows-env\s+['\"]?([A-Za-z_][A-Za-z0-9_]*)=")),
        ("-D property", re.compile(r"--jvm-option\s+['\"]?-D([^\s='\"{}]+)=")),
    )

    def _wrapper_pins_and_conditionals(self, task: scheduler.Task) -> tuple[set, set]:
        """(kind, name) literals the wrapper hands the runner, and the names it
        marks variant-overridable via `# conditional-runner-args: ...`."""
        source = (self.manifest.root / task.command).read_text(encoding="utf-8")
        conditional: set[str] = set()
        code_lines: list[str] = []
        for line in source.splitlines():
            marker = self.CONDITIONAL_RUNNER_ARGS.match(line)
            if marker:
                conditional.update(marker.group(1).split())
                continue
            if line.lstrip().startswith("#"):
                continue
            code_lines.append(line)
        pinned: set[tuple[str, str]] = set()
        code = "\n".join(code_lines)
        for kind, pattern in self.PIN_PATTERNS:
            for match in pattern.finditer(code):
                pinned.add((kind, match.group(1)))
        return pinned, {name[2:] if name.startswith("-D") else name for name in conditional}

    @staticmethod
    def _declared_options(argv: list[str]) -> list[tuple[str, str]]:
        """(kind, name) entries declared by an expanded argument template."""
        declared: list[tuple[str, str]] = []
        previous = None
        for token in argv:
            if previous in ("--linux-env", "--windows-env"):
                match = re.match(r"([A-Za-z_][A-Za-z0-9_]*)=", token)
                if match:
                    declared.append((previous, match.group(1)))
            elif previous == "--jvm-option":
                match = re.match(r"-D([^\s='\"{}]+)=", token)
                if match:
                    declared.append(("-D property", match.group(1)))
            previous = token
        return declared

    def test_no_runner_option_name_repeats_in_expanded_argv(self) -> None:
        """Statically expand the final runner argv — the wrapper's pinned
        literals plus the selected variant's args — for every task x variant
        and assert no --linux-env/--windows-env name or -D property repeats.
        The runner fails repeated environment names at argument validation and
        a repeated -D property shadows silently; both belong in this gate."""
        problems: list[str] = []
        for task in self.manifest.tasks.values():
            pinned, conditional = self._wrapper_pins_and_conditionals(task)
            templates = (
                [(None, task.arguments)]
                if task.arguments is not None
                else sorted(task.variants.items())
            )
            for variant, template in templates:
                spec = task.name + ("@" + variant if variant else "")
                argv = [arg.format(version="5304", runLabel="check") for arg in template]
                seen: set[tuple[str, str]] = set()
                for kind, option in self._declared_options(argv):
                    if (kind, option) in seen:
                        problems.append(f"{spec}: repeats {kind} name {option} within its own args")
                    elif (kind, option) in pinned and option not in conditional:
                        problems.append(
                            f"{spec}: declares {kind} {option} which {Path(task.command).name} "
                            "also pins unconditionally; the runner rejects the repeated name "
                            "(mark it `# conditional-runner-args:` or drop one declaration)"
                        )
                    seen.add((kind, option))
        self.assertEqual([], problems)

    def test_update_check_runner_waits_for_agent_readiness_before_the_deferred_gl_gate(self) -> None:
        """update-check passes mesaGlThread=true in its current/new/demo variants, so the
        runner refuses the workload when the deferred GL error check marker is missing.
        Without a --ready-marker the readiness wait is skipped entirely and the gate
        fires seconds after launch, before the agent can log deferred=ACTIVE — the
        deterministic failure the wl-r1/r2 runs hit. Assert the runner declares a
        readiness marker (the variant args themselves carry no marker)."""
        task = self.manifest.tasks["update-check"]
        script = (self.manifest.root / task.command).read_text(encoding="utf-8")
        self.assertIn("--ready-marker", script,
                      "update-check runner must wait on a readiness marker before the "
                      "deferred GL gate; mesa-on variants cannot pass otherwise")

    def test_runner_seam_versions_match_reviewed_lifecycle_coverage(self) -> None:
        """The runner resolves mesaGlThread on for versions it marks
        uniform_seam_reviewed; the runtime admits the deferred GL error check
        on exactly the generations the uniform-location lifecycle pin table
        covers (DeferredGlErrorCheckTarget binds admission to that predicate).
        Replay the runner's own version dispatch so a drift between the two
        tables fails before a host slot is burned."""
        runner_source = (PREVIEW / "run-cubism-host-validation.sh").read_text(encoding="utf-8")
        seam = runner_source.index("uniform_seam_reviewed")
        case_start = runner_source.rindex('case "$version" in', 0, seam)
        depth = 0
        case_end = None
        offset = case_start
        for line in runner_source[case_start:].splitlines(keepends=True):
            depth += len(re.findall(r"\bcase\b", line)) - len(re.findall(r"\besac\b", line))
            offset += len(line)
            if depth == 0:
                case_end = offset
                break
        self.assertIsNotNone(case_end, "version dispatch case block has no closing esac")
        dispatch = runner_source[case_start:case_end]

        seam_reviewed = set()
        for version in REVIEWED_VERSIONS:
            result = subprocess.run(
                ["bash", "-c",
                 'version="$1"; fail() { exit 42; }\n' + dispatch +
                 '\nprintf "SEAM=%s\\n" "${uniform_seam_reviewed:-unset}"',
                 "_", version],
                text=True, capture_output=True)
            self.assertEqual(0, result.returncode, f"version {version}: {result.stderr.strip()}")
            if "SEAM=1" in result.stdout:
                seam_reviewed.add(version)

        pins_path = (
            ROOT / "runtime/src/main/resources/dev/turboism/adapter/cubism"
            "/class-pins/uniform-location-lifecycle.json"
        )
        pin_versions = {
            key.replace(".", "") for key in json.loads(pins_path.read_text(encoding="utf-8"))
        }
        self.assertEqual(
            pin_versions,
            seam_reviewed,
            "runner uniform_seam_reviewed drifted from the reviewed lifecycle pin table",
        )
        target_source = (
            ROOT / "runtime/src/main/java/dev/turboism/adapter/cubism/optimization"
            "/deferred/DeferredGlErrorCheckTarget.java"
        ).read_text(encoding="utf-8")
        self.assertIn(
            "supportedEditorVersion",
            target_source,
            "deferred-GL admission must stay bound to the seam coverage predicate",
        )

    def test_non_mcp_startup_assistance_is_explicit_and_snapshotted(self) -> None:
        for name in ("parameter-batch-transfer", "animation-timeline", "workspace"):
            standard = scheduler.render_command(self.request(name + ":5304"), self.manifest)
            self.assertNotIn("build/native-startup-assistance-agent.jar:native-startup-assistance-agent.jar", standard)
            assisted = scheduler.render_command(self.request(name + ":5304@assisted"), self.manifest)
            self.assertIn("--aux-agent", assisted)
            self.assertIn("build/native-startup-assistance-agent.jar:native-startup-assistance-agent.jar", assisted)
            self.assertIn("-Dturboism.validation.startupAssistance=true", assisted)
        self.assertEqual(REVIEWED_VERSIONS[-9:], self.manifest.tasks["animation-timeline"].versions)
        with self.assertRaises(scheduler.SchedulerError):
            self.request("animation-timeline:5103")

    def test_atlas_has_fixed_preliminary_cases_and_exact_version(self) -> None:
        task = self.manifest.tasks["atlas"]
        self.assertEqual({"host-slot": 1, "display-input": 1, "performance-host": 1}, task.resources)
        self.assertEqual({f"geometry-{count}-{implementation}" for count in (100, 500, 1000, 2500)
                          for implementation in ("native", "new")} |
                         {f"ui-{dataset}-{count}-{implementation}" for dataset in ("circle", "geometry")
                          for count in (100, 500, 1000, 2500)
                          for implementation in ("native", "new")} |
                         {f"ui-{dataset}-{count}-polygon" for dataset in ("circle", "geometry")
                          for count in (100, 500, 1000)} |
                         {f"ui-{dataset}-{count}-new-parallel" for dataset in ("circle", "geometry")
                          for count in (100, 500)}, set(task.variants))
        self.assertEqual("geometry-100-new", self.request("atlas:5303").variant)
        for spec in ("atlas:9999", "atlas:5303@geometry-2500-both",
                     "atlas:5303@geometry-2499-new", "atlas:5303@geometry-100-both",
                     "atlas:5303@ui-geometry-2499-native", "atlas:5303@ui-circle-101-new",
                     "atlas:5303@ui-circle-1000-new-parallel", "atlas:5303@ui-geometry-500-native-parallel",
                     "atlas:5303@ui-circle-2500-polygon", "atlas:5303@geometry-100-polygon"):
            with self.subTest(spec=spec), self.assertRaises(scheduler.SchedulerError):
                self.request(spec)
        for spec in ("atlas:5203@ui-circle-100-polygon", "atlas:5302@ui-geometry-1000-polygon"):
            with self.subTest(spec=spec):
                self.assertEqual(spec.split("@", 1)[1], self.request(spec).variant)
        with mock.patch("subprocess.run", side_effect=AssertionError("Plan must not execute wrappers")):
            command = scheduler.render_command(self.request("atlas:5303"), self.manifest)
            self.assertTrue(command[1].endswith("run-atlas-host-validation.sh"))
            self.assertEqual(["5303", "test-run", "geometry-100-new"], command[2:])

    def test_local_env_loads_data_without_overriding_exported_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            env_file.write_text(
                "# local-only\n"
                "TURBOISM_HOST_VALIDATION_SSH_HOST=env@example.invalid\n"
                "TURBOISM_HOST_VALIDATION_FIXTURE_5302='/remote/fixture with spaces.cmo3'\n",
                encoding="utf-8",
            )
            host_key = "TURBOISM_HOST_VALIDATION_SSH_HOST"
            fixture_key = "TURBOISM_HOST_VALIDATION_FIXTURE_5302"
            values = scheduler.parse_local_env(env_file)
            environment = {host_key: "exported@example.invalid"}
            for name, value in values.items():
                environment.setdefault(name, value)
            self.assertEqual("exported@example.invalid", environment[host_key])
            self.assertEqual("/remote/fixture with spaces.cmo3", environment[fixture_key])

    def test_parser_selects_default_and_explicit_variants(self) -> None:
        default = self.request("parameter:5302")
        explicit = self.request("host-locale:5203@ja")
        self.assertEqual("matrix", default.variant)
        self.assertEqual("ja", explicit.variant)
        with self.assertRaisesRegex(scheduler.SchedulerError, "does not support version"):
            scheduler.parse_request("workspace:9999", "test-run", self.manifest)
        with self.assertRaisesRegex(scheduler.SchedulerError, "does not define variants"):
            scheduler.parse_request("core-owned-moc:5302@matrix", "test-run", self.manifest)

    def test_planner_serializes_all_versions_in_fifo_order(self) -> None:
        requests = [
            self.request("workspace:5302"),
            self.request("recent-preview:5203"),
            self.request("status-bar:5302"),
            self.request("fps:5302"),
        ]
        waves = scheduler.plan_waves(requests, self.manifest.resources)
        self.assertEqual(
            [["workspace"], ["recent-preview"], ["status-bar"], ["fps"]],
            [[request.task.name for request in wave] for wave in waves],
        )

    def test_rendered_command_reuses_wrapper_and_common_placement_options(self) -> None:
        request = self.request("psd-clip-mask:5203@read")
        command = scheduler.render_command(request, self.manifest)
        self.assertEqual("bash", command[0])
        self.assertTrue(command[1].endswith("run-psd-clip-mask-host-validation.sh"))
        self.assertEqual(["5203", "read", "test-run"], command[2:5])
        self.assertNotIn("--ssh-host", command)
        self.assertNotIn("--ssh-key", command)

    def test_cli_list_and_plan_do_not_require_host_access(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, scheduler.main(["list"]))
        self.assertIn("workspace\t" + ",".join(REVIEWED_VERSIONS) + "\t", output.getvalue())

        output = io.StringIO()
        with contextlib.redirect_stdout(output), mock.patch.object(scheduler.queue, "Store", side_effect=AssertionError("plan touched queue")), mock.patch.object(scheduler.subprocess, "run", side_effect=AssertionError("plan executed a process")):
            self.assertEqual(0, scheduler.main([
                "plan", "workspace:5302", "fps:5203", "--run-label", "offline",
            ]))
        rendered = output.getvalue()
        self.assertIn("local-only FIFO", rendered)
        self.assertIn("1. workspace:5302", rendered)
        self.assertIn("2. fps:5203", rendered)
        self.assertIn("workspace:5302", rendered)
        self.assertIn("fps:5203", rendered)

        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            self.assertEqual(2, scheduler.main(["run", "dialog-automation:5302"]))
        self.assertIn("cannot run blocked tasks", errors.getvalue())

    def test_manifest_rejects_unknown_resources_and_unsafe_commands(self) -> None:
        source = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory(dir=PREVIEW) as directory:
            invalid = Path(directory) / "manifest.json"
            source["tasks"]["workspace"]["resources"]["missing"] = 1
            invalid.write_text(json.dumps(source), encoding="utf-8")
            with self.assertRaisesRegex(scheduler.SchedulerError, "unknown resource"):
                scheduler.load_manifest(invalid)

    def test_local_admission_is_atomic_and_owner_scoped(self) -> None:
        # Preserve the old lease test's exclusivity/owner guarantees, without SSH stubs.
        with tempfile.TemporaryDirectory() as directory:
            store = scheduler.queue.Store(Path(directory) / "queue")
            path = store.root / "admission.lock"
            owner = scheduler.queue.FileLock(path).acquire()
            other = scheduler.queue.FileLock(path)
            with self.assertRaises(scheduler.queue.QueueError):
                other.acquire()
            other.close()  # A non-owner cannot release the owner's lock.
            with self.assertRaises(scheduler.queue.QueueError):
                scheduler.queue.FileLock(path).acquire()
            owner.close()
            with scheduler.queue.FileLock(path):
                pass
            store.submit("prepared", "digest", "one")
            job = store.claim()
            store.quarantine(job["job_id"], "cleanup unknown")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, scheduler.main(["release-stale", "--older-than", "3600", "--force"]))
            self.assertIsNone(store.claim())

    def test_attempt_ids_include_entropy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = scheduler.queue.Store(Path(directory) / "queue")
            first = store.submit("prepared", "digest", "first")
            second = store.submit("prepared", "digest", "second")
            self.assertNotEqual(first["job_id"], second["job_id"])
            self.assertRegex(first["job_id"], scheduler.SAFE_NAME)
            self.assertRegex(second["job_id"], scheduler.SAFE_NAME)

    def test_legacy_remote_configuration_cannot_trigger_connections(self) -> None:
        with mock.patch.object(scheduler.subprocess, "run", side_effect=AssertionError("network execution")):
            for arguments in (["plan", "workspace:5302", "--ssh-host", "remote"],
                              ["status", "--ssh-key=/secret"], ["run", "workspace:5302", "--keep-going"]):
                with contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(2, scheduler.main(arguments))

    def test_recovery_confirmation_requires_reason(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = scheduler.queue.Store(Path(directory) / "queue")
            with mock.patch.object(scheduler.queue, "Store", return_value=store), \
                 mock.patch.object(scheduler.queue, "recover", side_effect=AssertionError("invalid recovery invoked")), \
                 contextlib.redirect_stderr(io.StringIO()):
                for arguments in (["recover", "--confirm", "job"],
                                  ["recover", "--confirm", "job", "--reason", " "],
                                  ["recover", "--inspect", "job", "--reason", "reviewed"]):
                    self.assertEqual(2, scheduler.main(arguments))


class _WaitStore:
    def __init__(self, database: Path) -> None:
        self.database = database
        self.submit_calls = 0

    def submit(self, *arguments, **kwargs):
        self.submit_calls += 1
        raise AssertionError("waiter must never submit a job")


class HostValidationWaitTest(unittest.TestCase):
    @staticmethod
    def coded_error(message: str, code: int) -> sqlite3.OperationalError:
        failure = sqlite3.OperationalError(message)
        failure.sqlite_errorcode = code
        return failure

    @staticmethod
    def terminal_database(directory: str, state: str) -> tuple[queue.Store, dict]:
        store = queue.Store(Path(directory) / "queue")
        job = store.submit("prepared", "digest", "wait-test")
        with store.transaction() as db:
            db.execute("UPDATE jobs SET state=? WHERE job_id=?", (state, job["job_id"]))
        return store, job

    def wait_after_one_lock(self, state: str) -> tuple[int, dict, int, str]:
        with tempfile.TemporaryDirectory() as directory:
            store, job = self.terminal_database(directory, state)
            waiter = _WaitStore(store.database)
            connection_calls = []
            real_read_job = scheduler.queue.read_only_job

            def connect(database, **kwargs):
                connection_calls.append((database, kwargs))
                if len(connection_calls) == 1:
                    raise self.coded_error("locking protocol", sqlite3.SQLITE_PROTOCOL)
                return sqlite3.connect(database, **kwargs)

            def read_job(database, job_id):
                return real_read_job(database, job_id, connect=connect)

            output = io.StringIO()
            with contextlib.redirect_stdout(output), \
                 mock.patch.object(scheduler.queue, "read_only_job", side_effect=read_job), \
                 mock.patch.object(scheduler.time, "sleep") as sleep:
                result = scheduler.wait_job(waiter, job["job_id"], timeout=10)
            record = json.loads(output.getvalue())
            return result, record["job"], len(connection_calls), sleep.call_count

    def test_lock_retry_returns_same_job_success(self) -> None:
        result, job, connections, sleeps = self.wait_after_one_lock("succeeded")
        self.assertEqual(0, result)
        self.assertEqual("succeeded", job["state"])
        self.assertEqual(2, connections)
        self.assertEqual(1, sleeps)

    def test_lock_retry_returns_same_job_failed(self) -> None:
        result, job, connections, sleeps = self.wait_after_one_lock("failed")
        self.assertEqual(1, result)
        self.assertEqual("failed", job["state"])
        self.assertEqual(2, connections)
        self.assertEqual(1, sleeps)

    def test_lock_retry_is_bounded_and_never_submits(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "queue.sqlite3"
            database.write_bytes(b"test database placeholder")
            waiter = _WaitStore(database)
            connection_calls = []
            real_read_job = scheduler.queue.read_only_job

            def connect(database_uri, **kwargs):
                connection_calls.append(database_uri)
                raise self.coded_error("database is locked", sqlite3.SQLITE_BUSY)

            def read_job(database_path, job_id):
                return real_read_job(database_path, job_id, connect=connect)

            with mock.patch.object(scheduler.queue, "read_only_job", side_effect=read_job), \
                 mock.patch.object(scheduler.time, "sleep") as sleep:
                with self.assertRaisesRegex(
                    scheduler.SchedulerError,
                    r"infrastructure wait failure.*retry limit",
                ):
                    scheduler.wait_job(waiter, "job", timeout=10)
            self.assertEqual(scheduler.WAIT_JOB_READ_RETRY_LIMIT + 1, len(connection_calls))
            self.assertEqual(scheduler.WAIT_JOB_READ_RETRY_LIMIT, sleep.call_count)
            self.assertEqual(0, waiter.submit_calls)

    def test_lock_retry_stops_at_wait_deadline(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "queue.sqlite3"
            database.write_bytes(b"test database placeholder")
            waiter = _WaitStore(database)
            now = [100.0]
            connection_calls = []
            real_read_job = scheduler.queue.read_only_job

            def connect(database_uri, **kwargs):
                connection_calls.append(database_uri)
                raise self.coded_error("locking protocol", sqlite3.SQLITE_PROTOCOL)

            def read_job(database_path, job_id):
                return real_read_job(database_path, job_id, connect=connect)

            def clock():
                return now[0]

            def advance(seconds):
                now[0] += seconds

            with mock.patch.object(scheduler.queue, "read_only_job", side_effect=read_job), \
                 mock.patch.object(scheduler.time, "monotonic", side_effect=clock), \
                 mock.patch.object(scheduler.time, "sleep", side_effect=advance) as sleep:
                with self.assertRaisesRegex(
                    scheduler.SchedulerError,
                    r"infrastructure wait failure.*deadline",
                ):
                    scheduler.wait_job(waiter, "job", timeout=0.06)
            self.assertEqual(2, len(connection_calls))
            self.assertEqual(2, sleep.call_count)
            self.assertEqual(0, waiter.submit_calls)

    def test_lock_retry_budget_spans_successful_polls(self) -> None:
        waiter = _WaitStore(Path("/unused/queue.sqlite3"))
        lock = self.coded_error("database is locked", sqlite3.SQLITE_BUSY)
        running = {"state": "running"}
        responses = [lock, running, lock, running, lock, running, lock]
        calls = []

        def read_job(database, job_id):
            calls.append((database, job_id))
            response = responses.pop(0)
            if isinstance(response, BaseException):
                raise response
            return response

        with mock.patch.object(scheduler.queue, "read_only_job", side_effect=read_job), \
             mock.patch.object(scheduler.time, "sleep") as sleep:
            with self.assertRaisesRegex(
                scheduler.SchedulerError,
                r"infrastructure wait failure.*retry limit",
            ):
                scheduler.wait_job(waiter, "job", timeout=10)
        self.assertEqual(7, len(calls))
        self.assertEqual(6, sleep.call_count)
        self.assertEqual([], responses)
        self.assertEqual(0, waiter.submit_calls)

    def test_nonterminal_poll_does_not_sleep_past_wait_deadline(self) -> None:
        waiter = _WaitStore(Path("/unused/queue.sqlite3"))
        now = [100.0]

        def clock():
            return now[0]

        def advance(seconds):
            now[0] += seconds

        with mock.patch.object(scheduler.queue, "read_only_job", return_value={"state": "running"}), \
             mock.patch.object(scheduler.time, "monotonic", side_effect=clock), \
             mock.patch.object(scheduler.time, "sleep", side_effect=advance) as sleep:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = scheduler.wait_job(waiter, "job", timeout=0.06)
        self.assertEqual(3, result)
        self.assertEqual(1, sleep.call_count)
        self.assertAlmostEqual(0.06, sleep.call_args.args[0])
        self.assertLessEqual(sleep.call_args.args[0], 0.06 + 1e-12)
        self.assertTrue(json.loads(output.getvalue())["waitTimedOut"])
        self.assertEqual(0, waiter.submit_calls)

    def test_non_lock_sqlite_error_fails_immediately(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "queue.sqlite3"
            database.write_bytes(b"test database placeholder")
            waiter = _WaitStore(database)
            calls = []

            def read_job(database_path, job_id):
                calls.append((database_path, job_id))
                raise self.coded_error("locking protocol", sqlite3.SQLITE_ERROR)

            with mock.patch.object(scheduler.queue, "read_only_job", side_effect=read_job), \
                 mock.patch.object(scheduler.time, "sleep") as sleep:
                with self.assertRaisesRegex(scheduler.SchedulerError, r"queue wait failure"):
                    scheduler.wait_job(waiter, "job", timeout=10)
            self.assertEqual(1, len(calls))
            sleep.assert_not_called()
            self.assertEqual(0, waiter.submit_calls)

    def test_lock_classifier_uses_exact_message_only_without_error_code(self) -> None:
        self.assertTrue(queue.is_retryable_read_error(sqlite3.OperationalError("locking protocol")))
        self.assertTrue(queue.is_retryable_read_error(sqlite3.OperationalError("DATABASE IS LOCKED")))
        self.assertFalse(queue.is_retryable_read_error(sqlite3.OperationalError("locking protocol: extra")))
        self.assertFalse(
            queue.is_retryable_read_error(
                self.coded_error("database is locked", sqlite3.SQLITE_ERROR)
            )
        )

    def test_unknown_schema_fails_without_a_wait_retry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store, job = self.terminal_database(directory, "failed")
            with store.transaction() as db:
                db.execute("UPDATE metadata SET version=99")
            waiter = _WaitStore(store.database)

            with mock.patch.object(scheduler.time, "sleep") as sleep:
                with self.assertRaisesRegex(
                    scheduler.queue.QueueError,
                    r"unsupported queue schema",
                ):
                    scheduler.wait_job(waiter, job["job_id"], timeout=10)
            sleep.assert_not_called()
            self.assertEqual(0, waiter.submit_calls)

    def test_wait_command_does_not_initialize_a_missing_database(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = root / "queue.sqlite3"
            with mock.patch.object(scheduler.queue, "account_root", return_value=root), \
                 mock.patch.object(scheduler.queue, "Store", side_effect=AssertionError("wait initialized Store")), \
                 contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, scheduler.main(["wait", "missing-job"]))
            self.assertFalse(database.exists())

    def test_read_only_job_uses_ro_query_only_and_rejects_write(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store, job = self.terminal_database(directory, "failed")
            calls = []
            real_connect = sqlite3.connect

            def connect(database_uri, **kwargs):
                calls.append((database_uri, kwargs))
                return real_connect(database_uri, **kwargs)

            self.assertEqual(
                job["job_id"],
                scheduler.queue.read_only_job(store.database, job["job_id"], connect=connect)["job_id"],
            )
            self.assertEqual(1, len(calls))
            self.assertIn("?mode=ro", calls[0][0])
            self.assertTrue(calls[0][1]["uri"])
            with scheduler.queue.read_only_connection(store.database) as database:
                self.assertEqual(1, database.execute("PRAGMA query_only").fetchone()[0])
                with self.assertRaisesRegex(sqlite3.OperationalError, r"readonly"):
                    database.execute("CREATE TABLE forbidden_write(value TEXT)")

            waiter = _WaitStore(store.database)
            with mock.patch.object(scheduler.time, "sleep") as sleep:
                with self.assertRaisesRegex(queue.QueueError, r"unknown job id"):
                    scheduler.wait_job(waiter, "missing-job", timeout=10)
            sleep.assert_not_called()
            self.assertEqual(0, waiter.submit_calls)

    def test_read_only_connection_closes_when_pragma_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "queue.sqlite3"
            path.write_bytes(b"placeholder")

            class FailingConnection:
                def __init__(self):
                    self.closed = False

                def execute(self, statement):
                    raise sqlite3.OperationalError("query_only setup failed")

                def close(self):
                    self.closed = True

            connection = FailingConnection()

            with self.assertRaisesRegex(sqlite3.OperationalError, r"query_only setup failed"):
                with scheduler.queue.read_only_connection(path, connect=lambda *a, **k: connection):
                    self.fail("failing PRAGMA unexpectedly yielded a connection")
            self.assertTrue(connection.closed)

    def test_corrupt_read_only_database_fails_without_retry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "queue.sqlite3"
            database.write_bytes(b"not a sqlite database")
            waiter = _WaitStore(database)

            with mock.patch.object(scheduler.time, "sleep") as sleep:
                with self.assertRaisesRegex(
                    scheduler.SchedulerError,
                    r"queue wait failure",
                ):
                    scheduler.wait_job(waiter, "job", timeout=10)
            sleep.assert_not_called()
            self.assertEqual(0, waiter.submit_calls)

    def test_missing_read_only_database_is_not_created(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing" / "queue.sqlite3"
            calls = []

            def connect(*arguments, **kwargs):
                calls.append((arguments, kwargs))
                raise AssertionError("missing database must be rejected before connect")

            with self.assertRaisesRegex(scheduler.queue.QueueError, r"does not exist"):
                scheduler.queue.read_only_job(path, "job", connect=connect)
            self.assertFalse(path.exists())
            self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
