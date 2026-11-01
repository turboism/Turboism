"""Tests for the framework self-update activation resource generator (issue #50).

Covers the release-side contract only: enabled builds emit exactly the two documents the runtime parser
accepts (public material, pinned endpoint), disabled builds remove only those two owned files, and every
malformed keyring/policy input fails closed. No network, no private keys, no Gradle.
"""
from __future__ import annotations

import base64
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/release"))
from turboism_release import framework_client
from turboism_release.contracts import ReleaseError

SPKI = bytes.fromhex("302a300506032b6570032100") + bytes(range(32))
OTHER_SPKI = bytes.fromhex("302a300506032b6570032100") + bytes(range(1, 33))
RSA_SPKI = bytes.fromhex("30820122300d06092a864886f70d01010105000382010f00") + bytes(32)
PLACEHOLDER = "BASE64_ED25519_X509_SPKI"
PRIVATE_PEM = (
    "-----BEGIN PRIVATE KEY-----\n"
    "MC4CAQAwBQYDK2VwBCIEIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n"
    "-----END PRIVATE KEY-----\n"
)


def encoded(key: bytes = SPKI) -> str:
    return base64.b64encode(key).decode("ascii")


def keyring(keys=None, revoked=None, **extra) -> str:
    document = {
        "schemaVersion": 1,
        "keys": {"framework-official-v1": encoded()} if keys is None else keys,
        "revoked": [] if revoked is None else revoked,
    }
    document.update(extra)
    return json.dumps(document)


def enabled_environment(keys=None, revoked=None) -> dict:
    return {
        framework_client.ENV_ENABLED: "true",
        framework_client.ENV_KEYS: keyring(keys=keys, revoked=revoked),
    }


class PrepareTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = Path(tempfile.mkdtemp(prefix="turboism-framework-client-"))
        self.addCleanup(self._cleanup)

    def _cleanup(self) -> None:
        for path in sorted(self.directory.rglob("*"), reverse=True):
            path.unlink() if path.is_file() else path.rmdir()
        self.directory.rmdir()

    def read(self, name: str) -> bytes:
        return (self.directory / name).read_bytes()

    def test_enabled_writes_both_resources_in_runtime_schema(self) -> None:
        prepared = framework_client.prepare(self.directory, enabled_environment())
        self.assertTrue(prepared.enabled)
        self.assertEqual(
            {path.name for path in prepared.written}, set(framework_client.RESOURCE_NAMES)
        )
        self.assertEqual(prepared.removed, ())

        policy = json.loads(self.read(framework_client.CLIENT_RESOURCE))
        self.assertEqual(set(policy), {"schemaVersion", "enabled", "endpoint"})
        self.assertEqual(policy["schemaVersion"], 1)
        self.assertIs(policy["enabled"], True)
        self.assertEqual(policy["endpoint"], "https://api.turboism.dev/v1/framework/")

        keyring_document = json.loads(self.read(framework_client.KEYS_RESOURCE))
        self.assertEqual(set(keyring_document), {"schemaVersion", "keys", "revoked"})
        self.assertEqual(keyring_document["schemaVersion"], 1)
        self.assertEqual(keyring_document["keys"], {"framework-official-v1": encoded()})
        self.assertEqual(keyring_document["revoked"], [])

    def test_generated_documents_stay_within_runtime_limits(self) -> None:
        framework_client.prepare(self.directory, enabled_environment())
        policy = self.read(framework_client.CLIENT_RESOURCE)
        keyring_bytes = self.read(framework_client.KEYS_RESOURCE)
        self.assertLessEqual(len(policy), framework_client.MAX_POLICY_BYTES)
        self.assertLessEqual(len(keyring_bytes), framework_client.MAX_KEYRING_BYTES)
        for payload in (policy, keyring_bytes):
            self.assertNotIn(b"\r", payload)
            self.assertTrue(payload.endswith(b"\n"))
            # Only public material: no private-key markers and no PEM armor may ever be emitted.
            self.assertNotIn(b"PRIVATE", payload)
            self.assertNotIn(b"BEGIN", payload)

    def test_disabled_removes_only_the_two_owned_files(self) -> None:
        framework_client.prepare(self.directory, enabled_environment())
        sibling = self.directory / "keep-me.json"
        sibling.write_text("{}", encoding="utf-8")
        nested = self.directory / "nested"
        nested.mkdir()
        (nested / "keep.txt").write_text("x", encoding="utf-8")

        prepared = framework_client.prepare(self.directory, {framework_client.ENV_ENABLED: "false"})
        self.assertFalse(prepared.enabled)
        self.assertEqual(
            {path.name for path in prepared.removed}, set(framework_client.RESOURCE_NAMES)
        )
        self.assertEqual(prepared.written, ())
        self.assertEqual(sorted(path.name for path in self.directory.iterdir()), ["keep-me.json", "nested"])
        self.assertEqual((nested / "keep.txt").read_text(encoding="utf-8"), "x")
        self.assertTrue(self.directory.is_dir(), "disabling must never delete the output directory")

    def test_disable_is_idempotent_and_requires_no_keys(self) -> None:
        first = framework_client.prepare(self.directory, {})
        second = framework_client.prepare(self.directory, {framework_client.ENV_ENABLED: "false"})
        for prepared in (first, second):
            self.assertFalse(prepared.enabled)
            self.assertEqual(prepared.written, ())
            self.assertEqual(prepared.removed, ())

    def test_switching_enabled_to_disabled_leaves_no_activation_resource(self) -> None:
        framework_client.prepare(self.directory, enabled_environment())
        framework_client.prepare(self.directory, {framework_client.ENV_ENABLED: "false"})
        for name in framework_client.RESOURCE_NAMES:
            self.assertFalse((self.directory / name).exists(), f"{name} must not survive disabling")

    def test_flag_is_strict(self) -> None:
        self.assertFalse(framework_client.enabled_from_environment({}))
        self.assertFalse(framework_client.enabled_from_environment({framework_client.ENV_ENABLED: ""}))
        self.assertFalse(framework_client.enabled_from_environment({framework_client.ENV_ENABLED: "false"}))
        self.assertTrue(framework_client.enabled_from_environment({framework_client.ENV_ENABLED: "true"}))
        for value in ("TRUE", "True", "1", "0", "yes", " true", "true "):
            with self.assertRaises(ReleaseError, msg=value):
                framework_client.enabled_from_environment({framework_client.ENV_ENABLED: value})

    def test_enabled_requires_provisioned_keys(self) -> None:
        for environment in (
            {framework_client.ENV_ENABLED: "true"},
            {framework_client.ENV_ENABLED: "true", framework_client.ENV_KEYS: ""},
        ):
            with self.assertRaises(ReleaseError):
                framework_client.prepare(self.directory, environment)
            self.assertEqual(list(self.directory.iterdir()), [], "a failed run must write nothing")

    def test_empty_or_fully_revoked_keyring_is_rejected(self) -> None:
        for environment in (
            enabled_environment(keys={}),
            enabled_environment(revoked=["framework-official-v1"]),
        ):
            with self.assertRaises(ReleaseError):
                framework_client.prepare(self.directory, environment)
            self.assertEqual(list(self.directory.iterdir()), [])

    def test_partially_revoked_keyring_keeps_only_usable_keys(self) -> None:
        keys = {"framework-official-v1": encoded(), "framework-official-v2": encoded(OTHER_SPKI)}
        keyring_document = framework_client.trusted_keyring(
            keyring(keys=keys, revoked=["framework-official-v1"])
        )
        self.assertEqual(keyring_document["keys"], keys)
        self.assertEqual(keyring_document["revoked"], ["framework-official-v1"])

    def test_active_keys_export_matches_the_signing_producer_schema(self) -> None:
        # The signing lane reads a bare {keyId: base64 SPKI} object; it must never receive the wrapper document
        # and must never receive a revoked key.
        keys = {"framework-official-v1": encoded(), "framework-official-v2": encoded(OTHER_SPKI)}
        exported = framework_client.active_keys(keyring(keys=keys, revoked=["framework-official-v1"]))
        self.assertEqual(exported, {"framework-official-v2": encoded(OTHER_SPKI)})
        self.assertIsInstance(json.loads(json.dumps(exported)), dict)
        for value in exported.values():
            raw = base64.b64decode(value, validate=True)
            self.assertEqual(len(raw), 44)
            self.assertTrue(raw.startswith(bytes.fromhex("302a300506032b6570032100")))
        self.assertEqual(framework_client.active_keys(keyring()), {"framework-official-v1": encoded()})
        with self.assertRaises(ReleaseError):
            framework_client.active_keys(keyring(revoked=["framework-official-v1"]))
        with self.assertRaises(ReleaseError):
            framework_client.active_keys(None)

    def test_invalid_key_material_is_rejected(self) -> None:
        for key_id, value in (
            ("framework-official-v1", PLACEHOLDER),
            ("framework-official-v1", PRIVATE_PEM),
            ("framework-official-v1", encoded(RSA_SPKI)),
            ("framework-official-v1", encoded(bytes(32))),
            ("framework-official-v1", encoded(SPKI)[:-4]),
            ("framework-official-v1", "not base64!!"),
            ("Framework-Official", encoded()),
            ("framework official", encoded()),
            ("a" * 65, encoded()),
        ):
            with self.assertRaises(ReleaseError, msg=f"{key_id}={value[:24]}"):
                framework_client.trusted_keyring(keyring(keys={key_id: value}))
        with self.assertRaises(ReleaseError):
            framework_client.trusted_keyring(keyring(keys={"framework-official-v1": 7}))

    def test_invalid_documents_are_rejected(self) -> None:
        documents = (
            "",
            "null",
            "[]",
            "{}",
            json.dumps({"schemaVersion": 2, "keys": {}, "revoked": []}),
            json.dumps({"schemaVersion": True, "keys": {"k": encoded()}, "revoked": []}),
            json.dumps({"schemaVersion": 1, "keys": {"k": encoded()}}),
            json.dumps({"schemaVersion": 1, "keys": {"k": encoded()}, "revoked": [], "extra": 1}),
            json.dumps({"schemaVersion": 1, "keys": [], "revoked": []}),
            json.dumps({"schemaVersion": 1, "keys": {"k": encoded()}, "revoked": "k"}),
            json.dumps({"schemaVersion": 1, "keys": {"k": encoded()}, "revoked": ["k", "k"]}),
            json.dumps({"schemaVersion": 1, "keys": {"k": encoded()}, "revoked": [7]}),
            json.dumps(
                {"schemaVersion": 1, "keys": {f"k-{index}": encoded() for index in range(9)}, "revoked": []}
            ),
            json.dumps(
                {
                    "schemaVersion": 1,
                    "keys": {"k": encoded()},
                    "revoked": [f"r-{index}" for index in range(33)],
                }
            ),
            '{"schemaVersion":1,"schemaVersion":1,"keys":{"k":"%s"},"revoked":[]}' % encoded(),
        )
        for document in documents:
            with self.assertRaises(ReleaseError, msg=document[:60]):
                framework_client.trusted_keyring(document)

    def test_owned_resource_that_is_not_a_plain_file_is_refused(self) -> None:
        blocked = self.directory / framework_client.CLIENT_RESOURCE
        blocked.mkdir()
        (blocked / "payload.txt").write_text("x", encoding="utf-8")
        with self.assertRaises(ReleaseError):
            framework_client.prepare(self.directory, enabled_environment())
        with self.assertRaises(ReleaseError):
            framework_client.prepare(self.directory, {framework_client.ENV_ENABLED: "false"})
        self.assertTrue((blocked / "payload.txt").is_file(), "refusal must not delete anything")

    def test_output_directory_is_created_and_reused(self) -> None:
        nested = self.directory / "generated" / "framework-client"
        prepared = framework_client.prepare(nested, enabled_environment())
        self.assertTrue(prepared.enabled)
        self.assertTrue((nested / framework_client.CLIENT_RESOURCE).is_file())
        framework_client.prepare(nested, {framework_client.ENV_ENABLED: "false"})
        self.assertEqual(list(nested.iterdir()), [], "the owned files are removed, the directory is kept")


class CommandLineTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = Path(tempfile.mkdtemp(prefix="turboism-framework-client-cli-"))
        self.addCleanup(self._cleanup)

    def _cleanup(self) -> None:
        for path in sorted(self.directory.rglob("*"), reverse=True):
            path.unlink() if path.is_file() else path.rmdir()
        self.directory.rmdir()

    def run_main(self, environment: dict) -> tuple:
        output = io.StringIO()
        with mock.patch.dict(os.environ, environment, clear=True), contextlib.redirect_stdout(output):
            status = framework_client.main(["--output-dir", str(self.directory)])
        return status, output.getvalue()

    def test_cli_enables_and_disables(self) -> None:
        status, text = self.run_main(enabled_environment())
        self.assertEqual(status, 0)
        self.assertIn("enabled", text)
        self.assertNotIn(encoded()[:16], text, "the command must never print key material")
        self.assertTrue((self.directory / framework_client.CLIENT_RESOURCE).is_file())

        status, text = self.run_main({framework_client.ENV_ENABLED: "false"})
        self.assertEqual(status, 0)
        self.assertIn("disabled", text)
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_cli_rejects_invalid_configuration_without_writing(self) -> None:
        for environment in (
            {framework_client.ENV_ENABLED: "yes"},
            {framework_client.ENV_ENABLED: "true"},
            {framework_client.ENV_ENABLED: "true", framework_client.ENV_KEYS: "{"},
        ):
            status, text = self.run_main(environment)
            self.assertEqual(status, 2, environment)
            self.assertEqual(text, "")
            self.assertEqual(list(self.directory.iterdir()), [], environment)

    def test_cli_output_directory_is_required(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=True), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                framework_client.main([])
        self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
