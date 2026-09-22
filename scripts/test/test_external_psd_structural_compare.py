#!/usr/bin/env python3
"""Synthetic evidence tests only; these never establish real-host acceptance."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "validation/external-psd-edit-host-probe/structural_compare.py"
spec = importlib.util.spec_from_file_location("structural_compare", SOURCE)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def encode(properties):
    def escape(value):
        return (str(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t").replace("\f", "\\f").replace("=", "\\=").replace(":", "\\:"))
    return "\n".join(escape(k) + "=" + escape(v) for k, v in properties.items()).encode("ascii") + b"\n"


class StructuralCompareTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.native = self.root / "native"
        self.sdk = self.root / "sdk"
        self.native.mkdir()
        self.sdk.mkdir()
        self.left = self.collection("native")
        self.right = self.collection("sdk")

    def collection(self, phase):
        props = {"runId": phase + "-run", "phase": "structure-" + phase, "status": "PASS",
                 "structure.collection": "PASS", "contentProfile": "f1", "structure.variant": "add",
                 "structure.source.sha256": "b" * 64}
        if phase == "native":
            props.update({"structure.native.chooserComplete": "true",
                          "structure.native.command.invocations": "1",
                          "structure.native.host.jar.sha256": audit.HOST_SHA})
        else:
            props.update({"structure.sdk.importCompletion.status": "APPLIED",
                          "structure.sdk.importCompletion.consumedRevision": "true"})
        for stage in audit.STAGES:
            values = dict(audit.DECLARATIONS, historyDocument=phase + "-document",
                          historyManager=phase + "-history", canvas="[2048, 2048]", groups="groups",
                          historyPosition=str(2 + (stage not in ("before", "undo"))),
                          historyEntries=str(2 + (stage != "before")))
            for kind, fields in audit.FIELDS.items():
                values[kind + ".count"] = "1"
                for field in fields:
                    values[kind + ".object." + field] = "value:" + field
            values["inputs.object"] = "0:input"
            props.update({"structure." + stage + "." + key: value for key, value in values.items()})
        post = {"fixtureCopySha256": "a" * 64, "fixtureSourceSha256": "a" * 64,
                "clonedJarSha256": audit.HOST_SHA, "goldenJarSha256": audit.HOST_SHA,
                "stagedArtifacts": [{"staged": name, "sourceSha256": digest, "stagedSha256": digest}
                                    for name, digest in (("turboism-agent.jar", "c" * 64),
                                                         ("external-psd-edit-host-probe.jar", "d" * 64))]}
        detail = {"validationComplete": True, "goldenUnchanged": True, "taskOwnedCleanup": True,
                  "identityActualJarSha256": audit.HOST_SHA, "identityExpectedJarSha256": audit.HOST_SHA,
                  "postContainmentChecks": post}
        detail.update({key: "a" * 64 for key in ("sourceFixtureBeforeSha256", "sourceFixtureAfterSha256",
                                                "fixtureBeforeSha256", "fixtureAfterSha256")})
        life = {"jobId": phase + "-job", "attemptId": phase + "-attempt", "runId": phase + "-run",
                "preparedDigest": phase + "-digest", "terminalState": "succeeded", "cleanup": "safe",
                "finalizedBy": "contained-supervisor", "validationStatus": "PASS", "runnerExitCode": 0,
                "normalExit": True, "identityVerified": True, "fixtureUnchanged": True, "details": detail}
        return props, life

    def write(self, directory, collection):
        props, life = copy.deepcopy(collection)
        data = encode(props)
        life["details"]["postContainmentChecks"]["terminalResult"] = {
            "sha256": hashlib.sha256(data).hexdigest(), "passed": True, "passSeen": True,
            "failSeen": False, "failureMarkers": []}
        job = {"job_id": life["jobId"], "attempt_id": life["attemptId"], "run_id": life["runId"],
               "digest": life["preparedDigest"], "state": life["terminalState"], "evidence_json": json.dumps(life)}
        (directory / "external-psd-edit-result.properties").write_bytes(data)
        (directory / "bound-job.json").write_text(json.dumps(job))
        (directory / "lifecycle-result.json").write_text(json.dumps(life))

    def compare(self):
        self.write(self.native, self.left)
        self.write(self.sdk, self.right)
        return audit.compare(self.native, self.sdk)

    def test_equal_observations_do_not_claim_acceptance(self):
        result = self.compare()
        self.assertEqual("OBSERVED_FIELDS_MATCH", result["status"])
        self.assertEqual("NOT_CLAIMED", result["F5"])
        self.assertEqual("NOT_CLAIMED", result["SC006"])
        self.assertIn("dirty state", result["unobserved"])
        self.assertEqual({}, result["differences"]["undo"])

    def test_preserves_color_geometry_names_and_order_differences(self):
        for field in ("mesh.object.multiply", "mesh.object.screen", "mesh.object.geometry",
                      "raw.object.name", "image.object.bindings", "groups", "inputs.object"):
            with self.subTest(field=field):
                self.right = self.collection("sdk")
                self.right[0]["structure.after." + field] = "different"
                result = self.compare()
                self.assertEqual("OBSERVED_FIELDS_MISMATCH", result["status"])
                self.assertEqual("different", result["differences"]["after"][field]["sdk"])

    def test_missing_observation_on_both_sides_is_rejected(self):
        for field in ("raw.object.size", "image.object.bindings", "mesh.object.multiply", "inputs.object"):
            with self.subTest(field=field):
                self.left, self.right = self.collection("native"), self.collection("sdk")
                for props, _ in (self.left, self.right):
                    del props["structure.after." + field]
                with self.assertRaisesRegex(ValueError, "incomplete|missing"):
                    self.compare()

    def test_failed_or_unsafe_tasks_rejected(self):
        for key, bad in (("terminalState", "failed"), ("validationStatus", "FAIL"),
                         ("normalExit", False), ("cleanup", "unsafe"), ("identityVerified", False)):
            with self.subTest(key=key):
                self.right = self.collection("sdk")
                self.right[1][key] = bad
                with self.assertRaises(ValueError):
                    self.compare()

    def test_sha_and_probe_mismatch_rejected(self):
        for field in ("fixtureAfterSha256", "identityActualJarSha256", "post-fixture", "probe"):
            with self.subTest(field=field):
                self.right = self.collection("sdk")
                detail = self.right[1]["details"]
                if field == "post-fixture":
                    detail["postContainmentChecks"]["fixtureSourceSha256"] = "e" * 64
                elif field == "probe":
                    artifact = detail["postContainmentChecks"]["stagedArtifacts"][1]
                    artifact["sourceSha256"] = artifact["stagedSha256"] = "e" * 64
                else:
                    detail[field] = "e" * 64
                with self.assertRaises(ValueError):
                    self.compare()

    def test_tampered_terminal_or_lifecycle_rejected(self):
        self.compare()
        terminal = self.sdk / "external-psd-edit-result.properties"
        terminal.write_bytes(terminal.read_bytes() + b"#changed\n")
        with self.assertRaisesRegex(ValueError, "terminal SHA mismatch"):
            audit.compare(self.native, self.sdk)
        self.compare()
        path = self.sdk / "lifecycle-result.json"
        life = json.loads(path.read_text())
        life["runId"] = "different"
        path.write_text(json.dumps(life))
        with self.assertRaisesRegex(ValueError, "evidence differ"):
            audit.compare(self.native, self.sdk)

    def test_different_variant_or_source_rejected(self):
        for key, bad in (("structure.variant", "delete"), ("structure.source.sha256", "e" * 64)):
            self.right = self.collection("sdk")
            self.right[0][key] = bad
            with self.assertRaisesRegex(ValueError, "comparison inputs differ"):
                self.compare()

    def test_same_task_cannot_be_its_own_control(self):
        self.right[1]["jobId"] = self.left[1]["jobId"]
        with self.assertRaisesRegex(ValueError, "tasks must be independent"):
            self.compare()

    def test_incomplete_command_or_consumption_rejected(self):
        self.left[0]["structure.native.command.invocations"] = "2"
        with self.assertRaisesRegex(ValueError, "native command proof"):
            self.compare()
        self.left = self.collection("native")
        self.right[0]["structure.sdk.importCompletion.consumedRevision"] = "false"
        with self.assertRaisesRegex(ValueError, "consume"):
            self.compare()

    def test_history_identity_position_and_scope_are_checked(self):
        for key, bad in (("historyManager", "changed"), ("historyPosition", "9"),
                         ("historyEntries", "9"), ("colors.scope", "evaluated")):
            self.right = self.collection("sdk")
            self.right[0]["structure.undo." + key] = bad
            with self.assertRaises(ValueError):
                self.compare()

    def test_java_properties_escapes_unicode_and_duplicates(self):
        parsed = audit.properties(b"#comment\r\na\\=b\\:c=value\\=1\\:2\\\\x\\n\\t\\r\\f\r\n"
                                  b"unicode=\\u6d4b\\u8bd5\\ud83d\\ude00\nlatin=\xe9\x85\n")
        self.assertEqual("value=1:2\\x\n\t\r\f", parsed["a=b:c"])
        self.assertEqual("测试😀", parsed["unicode"])
        self.assertEqual("é\x85", parsed["latin"])
        for bad in (b"a=1\na=2\n", b"a=1\n\\u0061=2\n", b"a=\\uFFFFF\\u0\n",
                    b"no separator\n", b"a=trailing\\\n", b"a=\\ud800\n"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                audit.properties(bad)

    def test_cli_exit_codes_distinguish_match_mismatch_and_rejection(self):
        self.compare()
        def run(expected):
            result = subprocess.run([sys.executable, str(SOURCE), str(self.native), str(self.sdk)],
                                    capture_output=True, text=True, check=False)
            self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
            return json.loads(result.stdout)
        self.assertEqual("OBSERVED_FIELDS_MATCH", run(0)["status"])
        self.right[0]["structure.saved.raw.object.name"] = "changed"
        self.compare()
        self.assertEqual("OBSERVED_FIELDS_MISMATCH", run(1)["status"])
        (self.sdk / "external-psd-edit-result.properties").write_bytes(b"changed")
        self.assertEqual("REJECTED", run(2)["status"])


if __name__ == "__main__":
    unittest.main()
