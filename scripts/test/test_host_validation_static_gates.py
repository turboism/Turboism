#!/usr/bin/env python3
"""Focused tests for prepare-time static gates (L0) and cluster annotation (L1)."""

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PREVIEW = Path(__file__).resolve().parents[1] / "preview"
sys.path.insert(0, str(PREVIEW))

import host_validation as scheduler
import host_validation_static_gates as gates


class StaticGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest = scheduler.load_manifest(
            PREVIEW / "host-validation-tasks.json", {})

    def request(self, spec: str):
        return scheduler.parse_request(spec, "test-run", self.manifest)

    def evaluate(self, spec: str, environment=None):
        return gates.evaluate(self.request(spec), scheduler.Request,
                              self.manifest, environment or {})

    def test_admission_refusal_without_verified_static_write_evidence(self):
        with self.assertRaises(gates.StaticGateRefusal) as caught:
            self.evaluate("parameter-batch-transfer:5002@assisted")
        self.assertEqual("SKIP_STATIC", caught.exception.skip)
        self.assertEqual("admission", caught.exception.reason)
        self.assertIn("cubism.editor-model.write", caught.exception.detail)

    def test_admission_boundary_matches_records(self):
        for version in ("5002", "5103"):
            with self.subTest(version=version), self.assertRaises(gates.StaticGateRefusal):
                self.evaluate(f"parameter-batch-transfer:{version}@assisted")
        for version in ("5201", "5304"):
            request, _ = self.evaluate(f"parameter-batch-transfer:{version}@assisted")
            self.assertIn("assisted", request.variant)

    def test_free_license_refusal(self):
        # The observed-FREE table is empty today (every install runs FREE-start
        # mode and the parameter limit is non-blocking); inject a tier to prove
        # the gate still refuses when populated.
        environment = self._fixture_environment()
        with mock.patch.dict(gates.INSTALL_LICENSE_TIER, {"5100": "free"}):
            with self.assertRaises(gates.StaticGateRefusal) as caught:
                self.evaluate("parameter-batch-transfer:5100@assisted", environment)
        self.assertEqual("SKIP_LICENSE", caught.exception.skip)
        self.assertEqual("license", caught.exception.reason)

    def test_free_start_is_universal_not_a_refusal(self):
        # 5100 hit the FREE param-limit dialog on earlier runs yet the model
        # still loads — license must not refuse. Admission still does (writes).
        with self.assertRaises(gates.StaticGateRefusal) as caught:
            self.evaluate("parameter-batch-transfer:5100@assisted")
        self.assertEqual("admission", caught.exception.reason)

    def test_unknown_license_falls_through_to_other_gates(self):
        # 5101 is not in the observed-FREE table, so the license gate stays
        # silent; the admission wall still refuses the write path.
        with self.assertRaises(gates.StaticGateRefusal) as caught:
            self.evaluate("parameter-batch-transfer:5101@assisted")
        self.assertEqual("admission", caught.exception.reason)

    def test_fixture_newer_than_target_routes_to_assisted(self):
        environment = self._fixture_environment()
        for spec, assisted in (
            ("clipmask-viewer:5000", "assisted"),
            ("psd-clip-mask:5000@matrix", "matrix-assisted"),
            ("edit:5000", "assisted"),
        ):
            with self.subTest(spec=spec):
                request, outcome = self.evaluate(spec, environment)
                self.assertEqual(assisted, request.variant)
                self.assertTrue(outcome.routed)
                self.assertTrue(any("fixture-version-routing" in n for n in outcome.notes))

    def test_fixture_at_or_below_target_keeps_variant(self):
        environment = self._fixture_environment()
        request, outcome = self.evaluate("clipmask-viewer:5304", environment)
        self.assertEqual("standard", request.variant)
        self.assertFalse(outcome.routed)

    def test_newer_fixture_without_assisted_variant_only_notes(self):
        environment = self._fixture_environment()
        request, outcome = self.evaluate("boundingbox-warp-mirror:5000@analyse", environment)
        self.assertEqual("analyse", request.variant)
        self.assertFalse(outcome.routed)
        self.assertTrue(any("no assisted variant" in n for n in outcome.notes))

    def _fixture_environment(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "fixture.cmo3"
        path.write_bytes(b"newer-document")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        environment = {"TURBOISM_HOST_VALIDATION_FIXTURE_5303": str(path)}
        table = dict(gates.FIXTURE_DOC_VERSION)
        table[digest] = "5.2.03"
        patcher = mock.patch.dict(gates.FIXTURE_DOC_VERSION, table, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)
        free = set(gates.FREE_LIMIT_EXCEEDED_FIXTURES)
        free.add(digest)
        patcher2 = mock.patch.object(gates, "FREE_LIMIT_EXCEEDED_FIXTURES", free)
        patcher2.start()
        self.addCleanup(patcher2.stop)
        return environment


class ClusterEquivalenceTest(unittest.TestCase):
    FAMILIES = {
        "core-model-read": {
            "strict": [
                {"rep": "5.0.00", "members": ["5.0.00", "5.0.01", "5.1.00"], "missingRoots": {}},
                {"rep": "5.3.00", "members": ["5.3.00", "5.3.04"], "missingRoots": {}},
            ],
            "shallow": [{"rep": "5.0.00", "members": ["5.0.00"], "missingRoots": {}}],
        }
    }

    def _ledger(self, entries):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "campaign-jobs.json"
        path.write_text(json.dumps({"schemaVersion": 1, "jobs": entries}))
        return path

    def test_covered_by_strict_cluster_rep_pass(self):
        ledger = self._ledger([
            {"task": "core-acquisition", "version": "5.3.00", "status": "FINAL",
             "result": {"validationStatus": "PASS"}},
        ])
        self.assertEqual(
            "5.3.00",
            gates.covered_by_equivalent("core-acquisition", "5304", self.FAMILIES, ledger))

    def test_no_equivalence_without_member_pass(self):
        ledger = self._ledger([
            {"task": "core-acquisition", "version": "5.3.00", "status": "FINAL",
             "result": {"validationStatus": "FAIL"}},
        ])
        self.assertIsNone(
            gates.covered_by_equivalent("core-acquisition", "5304", self.FAMILIES, ledger))

    def test_target_member_with_pass_does_not_self_annotate(self):
        ledger = self._ledger([
            {"task": "core-acquisition", "version": "5.0.00", "status": "FINAL",
             "result": {"validationStatus": "PASS"}},
        ])
        self.assertIsNone(
            gates.covered_by_equivalent("core-acquisition", "5000", self.FAMILIES, ledger))

    def test_unmapped_task_and_missing_table_are_noops(self):
        ledger = self._ledger([])
        self.assertIsNone(
            gates.covered_by_equivalent("mcp", "5304", self.FAMILIES, ledger))
        self.assertIsNone(
            gates.covered_by_equivalent("core-acquisition", "5304", {}, ledger))

    def test_missing_cluster_file_loads_empty(self):
        self.assertEqual({}, gates.load_cluster_table(Path("/nonexistent/clusters.json")))

    def test_real_cluster_table_shape_when_committed(self):
        table_path = gates.CLUSTERS_PATH
        if not table_path.is_file():
            self.skipTest("cluster artifact not generated in this checkout")
        families = gates.load_cluster_table()
        for name, entry in families.items():
            self.assertIn("strict", entry)
            self.assertIn("shallow", entry)
            for cluster in entry["strict"] + entry["shallow"]:
                self.assertIn("rep", cluster)
                self.assertIn("members", cluster)
                self.assertIn("missingRoots", cluster)


class BundleLinkageGateTest(unittest.TestCase):
    """Synthetic-jar coverage for the prepare-time bundle linkage gate."""

    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.dir = Path(directory.name)

    # -- minimal class-file writer ----------------------------------------

    @staticmethod
    def _class_bytes(name, super_name="java/lang/Object", interfaces=(),
                     methods=(), fields=(), refs=(), access=0x0021):
        import struct
        pool = []

        def add(raw):
            pool.append(raw)
            return len(pool)

        def utf8(text):
            data = text.encode("utf-8")
            return add(b"\x01" + struct.pack(">H", len(data)) + data)

        def cls(internal):
            return add(b"\x07" + struct.pack(">H", utf8(internal)))

        def nat(member_name, descriptor):
            return add(b"\x0c" + struct.pack(">HH", utf8(member_name), utf8(descriptor)))

        ref_tag = {"field": 9, "method": 10, "interface": 11}
        this_i = cls(name)
        super_i = cls(super_name) if super_name else 0
        iface_indexes = [cls(interface) for interface in interfaces]
        field_indexes = [(utf8(n), utf8(d)) for n, d in fields]
        method_indexes = [(utf8(n), utf8(d)) for n, d in methods]
        for kind, owner, member_name, descriptor in refs:
            add(bytes([ref_tag[kind]]) + struct.pack(">HH", cls(owner), nat(member_name, descriptor)))

        out = bytearray()
        out += b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, 52)
        out += struct.pack(">H", len(pool) + 1) + b"".join(pool)
        out += struct.pack(">HHH", access, this_i, super_i)
        out += struct.pack(">H", len(iface_indexes)) + b"".join(
            struct.pack(">H", i) for i in iface_indexes
        )
        for table in (field_indexes, method_indexes):
            out += struct.pack(">H", len(table))
            for name_i, desc_i in table:
                out += struct.pack(">HHHH", 0x0001, name_i, desc_i, 0)
        out += struct.pack(">H", 0)
        return bytes(out)

    def jar(self, filename, classes, entry_date=(2026, 10, 9, 0, 0, 0),
            resource_date=None):
        import zipfile
        path = self.dir / filename
        with zipfile.ZipFile(path, "w") as archive:
            for internal, data in classes.items():
                info = zipfile.ZipInfo(internal + ".class", entry_date)
                archive.writestr(info, data)
            # A second, differently dated entry keeps the jar out of the
            # date-normalized (reproducible-build) heuristic path.
            if resource_date is not None:
                info = zipfile.ZipInfo("META-INF/extra.txt", resource_date)
                archive.writestr(info, b"x")
        return path

    def agent_jar(self, extra=(), date=(2026, 10, 9, 0, 0, 0)):
        classes = {
            "dev/turboism/sdk/PluginContext": self._class_bytes(
                "dev/turboism/sdk/PluginContext", interfaces=("dev/turboism/sdk/ContextBase",),
                access=0x0601, methods=(("parameters", "()Ldev/turboism/sdk/Parameters;"),)),
            "dev/turboism/sdk/ContextBase": self._class_bytes(
                "dev/turboism/sdk/ContextBase", access=0x0601,
                methods=(("log", "(Ljava/lang/String;)V"),)),
            "dev/turboism/sdk/Parameters": self._class_bytes(
                "dev/turboism/sdk/Parameters",
                methods=(("count", "()I"),)),
            "dev/turboism/sdk/SubContext": self._class_bytes(
                "dev/turboism/sdk/SubContext",
                super_name="dev/turboism/sdk/Parameters",
                methods=()),
            "dev/turboism/sdk/SwingWindow": self._class_bytes(
                "dev/turboism/sdk/SwingWindow",
                super_name="javax/swing/JFrame", methods=()),
            "dev/turboism/runtime/Runtime": self._class_bytes(
                "dev/turboism/runtime/Runtime", methods=(("boot", "()V"),)),
            **{n: c for n, c in extra},
        }
        return self.jar("turboism-agent.jar", classes, entry_date=date)

    # -- the gate -----------------------------------------------------------

    def test_linkable_bundle_passes(self):
        agent = self.agent_jar()
        plugin = self.jar("plugin.jar", {
            "dev/turboism/tests/Probe": self._class_bytes(
                "dev/turboism/tests/Probe", refs=(
                    ("interface", "dev/turboism/sdk/PluginContext",
                     "parameters", "()Ldev/turboism/sdk/Parameters;"),
                    ("method", "dev/turboism/sdk/Parameters", "count", "()I"),
                    ("method", "dev/turboism/runtime/Runtime", "boot", "()V"),
                )),
        })
        notes = gates.check_bundle_jars(agent, [("plugin.jar", plugin)], "task:5302")
        self.assertEqual((), notes)

    def test_removed_sdk_member_refuses_with_jar_and_call(self):
        agent = self.agent_jar()
        plugin = self.jar("mcp.jar", {
            "dev/turboism/mcp/Server": self._class_bytes(
                "dev/turboism/mcp/Server", refs=(
                    ("interface", "dev/turboism/sdk/PluginContext",
                     "parameterQuery", "()Ljava/lang/String;"),
                )),
        })
        with self.assertRaises(gates.StaticGateRefusal) as caught:
            gates.check_bundle_jars(agent, [("mcp.jar", plugin)], "mcp:5302")
        refusal = caught.exception
        self.assertEqual("SKIP_STATIC", refusal.skip)
        self.assertEqual("stale-bundle", refusal.reason)
        self.assertIn("mcp.jar", refusal.detail)
        self.assertIn("parameterQuery", refusal.detail)
        self.assertIn("dev/turboism/sdk/PluginContext", refusal.detail)

    def test_inherited_member_resolves_through_super_chain(self):
        agent = self.agent_jar()
        plugin = self.jar("probe.jar", {
            "dev/turboism/tests/Probe": self._class_bytes(
                "dev/turboism/tests/Probe", refs=(
                    # count() is declared on Parameters; SubContext inherits it.
                    ("method", "dev/turboism/sdk/SubContext", "count", "()I"),
                    # log() lives on the PluginContext super-interface.
                    ("interface", "dev/turboism/sdk/PluginContext",
                     "log", "(Ljava/lang/String;)V"),
                    # Object members resolve on interfaces.
                    ("interface", "dev/turboism/sdk/PluginContext",
                     "toString", "()Ljava/lang/String;"),
                )),
        })
        self.assertEqual((), gates.check_bundle_jars(agent, [("probe.jar", plugin)], "t:5302"))

    def test_external_ancestor_member_does_not_refuse(self):
        # SwingWindow's members inherited from javax/swing/JFrame leave the
        # bundle; the gate gives out-of-bundle ancestors the benefit of the
        # doubt instead of demanding a JDK index.
        agent = self.agent_jar()
        plugin = self.jar("window.jar", {
            "dev/turboism/tests/Probe": self._class_bytes(
                "dev/turboism/tests/Probe", refs=(
                    ("method", "dev/turboism/sdk/SwingWindow", "setVisible", "(Z)V"),
                )),
        })
        self.assertEqual((), gates.check_bundle_jars(agent, [("window.jar", plugin)], "t:5302"))

    def test_missing_owner_class_refuses(self):
        agent = self.agent_jar()
        plugin = self.jar("plugin.jar", {
            "dev/turboism/tests/Probe": self._class_bytes(
                "dev/turboism/tests/Probe", refs=(
                    ("method", "dev/turboism/sdk/RemovedApi", "call", "()V"),
                )),
        })
        with self.assertRaises(gates.StaticGateRefusal) as caught:
            gates.check_bundle_jars(agent, [("plugin.jar", plugin)], "t:5302")
        self.assertIn("dev/turboism/sdk/RemovedApi", caught.exception.detail)

    def test_non_turboism_references_are_ignored(self):
        agent = self.agent_jar()
        plugin = self.jar("plugin.jar", {
            "dev/turboism/tests/Probe": self._class_bytes(
                "dev/turboism/tests/Probe", refs=(
                    ("method", "com/live2d/cubism/CEAppCtrl", "main", "()V"),
                    ("method", "java/lang/Thread", "sleep", "(J)V"),
                    ("field", "com/live2d/whatever/Gone", "X", "I"),
                )),
        })
        self.assertEqual((), gates.check_bundle_jars(agent, [("plugin.jar", plugin)], "t:5302"))

    def test_missing_field_refuses(self):
        agent = self.agent_jar()
        plugin = self.jar("plugin.jar", {
            "dev/turboism/tests/Probe": self._class_bytes(
                "dev/turboism/tests/Probe", refs=(
                    ("field", "dev/turboism/sdk/Parameters",
                     "registry", "Ldev/turboism/sdk/Registry;"),
                )),
        })
        with self.assertRaises(gates.StaticGateRefusal):
            gates.check_bundle_jars(agent, [("plugin.jar", plugin)], "t:5302")

    def test_argv_drives_the_gate(self):
        agent = self.agent_jar()
        bad = self.jar("aux.jar", {
            "dev/turboism/tests/Aux": self._class_bytes(
                "dev/turboism/tests/Aux", refs=(
                    ("method", "dev/turboism/sdk/Parameters", "gone", "()V"),
                )),
        })
        argv = ["--name", "t", "--agent", str(agent),
                "--aux-agent", f"{bad}:aux.jar"]
        with self.assertRaises(gates.StaticGateRefusal) as caught:
            gates.check_bundle_linkage(argv, "t:5302")
        self.assertIn("aux.jar", caught.exception.detail)

    def test_plugin_without_agent_refuses(self):
        plugin = self.jar("p.jar", {
            "dev/turboism/tests/Probe": self._class_bytes("dev/turboism/tests/Probe")})
        with self.assertRaises(gates.StaticGateRefusal):
            gates.check_bundle_linkage(
                ["--plugin", f"{plugin}:p.jar"], "t:5302")

    def test_freshness_skew_warns_but_does_not_refuse(self):
        agent = self.jar("turboism-agent.jar", {
            "dev/turboism/sdk/Parameters": self._class_bytes(
                "dev/turboism/sdk/Parameters", methods=(("count", "()I"),)),
        }, entry_date=(2026, 9, 1, 0, 0, 0), resource_date=(2026, 9, 1, 1, 0, 0))
        plugin = self.jar("plugin.jar", {
            "dev/turboism/tests/Probe": self._class_bytes(
                "dev/turboism/tests/Probe", refs=(
                    ("method", "dev/turboism/sdk/Parameters", "count", "()I"),
                )),
        }, entry_date=(2026, 10, 9, 0, 0, 0), resource_date=(2026, 10, 9, 1, 0, 0))
        notes = gates.check_bundle_jars(agent, [("plugin.jar", plugin)], "t:5302")
        self.assertTrue(any("bundle-freshness" in note for note in notes))

    def test_date_normalized_jars_fall_back_to_mtime(self):
        # Reproducible-build jars share one entry timestamp; it is not a build
        # signal, so the heuristic must not scream "weeks apart" on it.
        agent = self.jar("turboism-agent.jar", {
            "dev/turboism/sdk/Parameters": self._class_bytes(
                "dev/turboism/sdk/Parameters", methods=(("count", "()I"),)),
        }, entry_date=(2020, 1, 1, 0, 0, 0))
        plugin = self.jar("plugin.jar", {
            "dev/turboism/tests/Probe": self._class_bytes(
                "dev/turboism/tests/Probe", refs=(
                    ("method", "dev/turboism/sdk/Parameters", "count", "()I"),
                )),
        }, entry_date=(1980, 2, 1, 0, 0, 0))
        notes = gates.check_bundle_jars(agent, [("plugin.jar", plugin)], "t:5302")
        self.assertFalse(any("bundle-freshness" in note for note in notes))

    def test_same_generation_bundle_has_no_freshness_note(self):
        agent = self.jar("turboism-agent.jar", {
            "dev/turboism/sdk/Parameters": self._class_bytes(
                "dev/turboism/sdk/Parameters", methods=(("count", "()I"),)),
        }, entry_date=(2026, 10, 9, 1, 0, 0), resource_date=(2026, 10, 9, 2, 0, 0))
        plugin = self.jar("plugin.jar", {
            "dev/turboism/tests/Probe": self._class_bytes(
                "dev/turboism/tests/Probe", refs=(
                    ("method", "dev/turboism/sdk/Parameters", "count", "()I"),
                )),
        }, entry_date=(2026, 10, 9, 3, 0, 0), resource_date=(2026, 10, 9, 4, 0, 0))
        self.assertEqual((), gates.check_bundle_jars(agent, [("plugin.jar", plugin)], "t:5302"))

    def test_bundle_jar_inputs_parses_composite_and_input_placeholder(self):
        agent, plugins = gates.bundle_jar_inputs([
            "--name", "x",
            "--agent", "@INPUT@/inputs/0/turboism-agent.jar",
            "--plugin", "@INPUT@/inputs/1/mcp.jar:mcp.jar",
            "--aux-agent", "@INPUT@/inputs/2/aux.jar:aux.jar",
            "--fixture-local", "@INPUT@/inputs/3/fixture.cmo3",
        ], Path("/prepared/abc"))
        self.assertEqual(Path("/prepared/abc/inputs/0/turboism-agent.jar"), agent)
        self.assertEqual(
            [("mcp.jar", Path("/prepared/abc/inputs/1/mcp.jar")),
             ("aux.jar", Path("/prepared/abc/inputs/2/aux.jar"))],
            plugins,
        )


if __name__ == "__main__":
    unittest.main()
