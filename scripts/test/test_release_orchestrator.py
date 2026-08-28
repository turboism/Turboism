#!/usr/bin/env python3
"""Tests for deterministic multi-component Turboism release planning."""
from __future__ import annotations

import copy
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "scripts/release"


def load_package():
    import sys

    if str(PACKAGE) not in sys.path:
        sys.path.insert(0, str(PACKAGE))
    from turboism_release import candidate as candidate_builder
    from turboism_release import contracts
    from turboism_release import executor
    from turboism_release import planner
    from turboism_release import remote
    return candidate_builder, contracts, executor, planner, remote


candidate_builder, contracts, executor, planner, remote = load_package()


HASH_A = "a" * 64
HASH_B = "b" * 64
SOURCE = "1" * 40


def artifacts(version="0.43.0"):
    names = [
        f"TurboismInstaller-{version}.exe",
        f"TurboismInstaller-{version}.exe.sha256",
        f"TurboismInstaller-{version}.jar",
        f"TurboismInstaller-{version}.jar.sha256",
        f"turboism-{version}-full.zip",
        f"turboism-{version}-full.zip.sha256",
        f"turboism-{version}-lite.zip",
        f"turboism-{version}-lite.zip.sha256",
    ]
    return [
        {"name": name, "size": index + 1, "sha256": f"{index + 1:064x}",
         "relativePath": f"framework/{name}", "mediaType": "application/octet-stream"}
        for index, name in enumerate(names)
    ]


def candidate(*, eligible=True, plugins=None):
    return {
        "format": "turboism.release-candidate",
        "schemaVersion": 1,
        "source": {
            "repository": "turboism/Turboism",
            "revision": SOURCE,
            "tag": "v0.43.0" if eligible else None,
        },
        "framework": {
            "eligible": eligible,
            "version": "0.43.0",
            "changelog": {"date": "2026-09-01", "sha256": HASH_A},
            "artifacts": artifacts() if eligible else [],
            "bundledPlugins": [],
        },
        "plugins": {
            "policySha256": HASH_B,
            "candidates": list(plugins or []),
        },
    }


def observation(version="0.43.0", missing=()):
    return {
        "version": version,
        "assets": [
            {"name": item["name"], "size": item["size"], "sha256": item["sha256"]}
            for item in artifacts(version)
            if item["name"] not in missing
        ],
    }


def plugin_candidate(version="0.2.0", *, built=True):
    return {
        "project": ":plugins:backup",
        "module": "backup",
        "id": "dev.turboism.plugin.backup",
        "version": version,
        "jarRelativePath": f"plugins/backup-{version}.jar",
        "jarSha256": HASH_A,
        "jarSize": 42,
        "descriptorSha256": HASH_B,
        "policy": {
            "channel": "stable",
            "cubismVersions": ["5.2.03", "5.3.02"],
            "repository": "https://github.com/turboism/Turboism",
            "support": "https://github.com/turboism/Turboism/issues",
        },
        "policySha256": "c" * 64,
        "built": built,
    }


def catalog(version=None, *, jar_hash=HASH_A, descriptor_hash=HASH_B):
    releases = [] if version is None else [{
        "version": version,
        "channel": "stable",
        "cubismVersions": ["5.2.03", "5.3.02"],
        "artifact": {"sha256": jar_hash, "descriptorSha256": descriptor_hash},
    }]
    plugins = [] if version is None else [{
        "id": "dev.turboism.plugin.backup",
        "repository": "https://github.com/turboism/Turboism",
        "support": "https://github.com/turboism/Turboism/issues",
        "releases": releases,
    }]
    return {
        "format": "turboism.plugin.catalog",
        "schemaVersion": 2,
        "catalogVersion": 4,
        "catalogSha256": "d" * 64,
        "keyId": "turboism-official-v1",
        "plugins": plugins,
    }


class ContractTest(unittest.TestCase):
    def test_canonical_plan_is_deterministic(self):
        first = planner.make_plan(
            candidate(), github=observation(), updates=observation(), catalog=None, channel="stable")
        second = planner.make_plan(
            copy.deepcopy(candidate()), github=copy.deepcopy(observation()),
            updates=copy.deepcopy(observation()), catalog=None, channel="stable")
        self.assertEqual(first, second)
        self.assertEqual(first["planId"], contracts.plan_id({k: v for k, v in first.items() if k != "planId"}))

    def test_rejects_local_paths_and_secret_keys(self):
        with self.assertRaises(contracts.ReleaseError):
            contracts.canonical_bytes({"path": "/workspace/private"})
        with self.assertRaises(contracts.ReleaseError):
            contracts.canonical_bytes({"apiToken": "x"})

    def test_round_trip_rejects_duplicate_json_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "candidate.json"
            path.write_text('{"format":"turboism.release-candidate","format":"x","schemaVersion":1}')
            with self.assertRaises(contracts.ReleaseError):
                contracts.read_document(path, "candidate")


class RemoteAdapterTest(unittest.TestCase):
    @mock.patch("turboism_release.remote.urllib.request.urlopen")
    def test_remote_requests_identify_the_release_orchestrator(self, urlopen):
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = b"{}"
        urlopen.return_value = response

        remote._bytes_url("https://updates.turboism.dev/example.json")

        request = urlopen.call_args.args[0]
        self.assertEqual(request.get_header("User-agent"), "turboism-release-orchestrator/1")
        self.assertEqual(request.get_header("Accept-encoding"), "identity")

    @mock.patch("turboism_release.planner.urllib.request.urlopen")
    def test_plan_observation_requests_identify_the_release_orchestrator(self, urlopen):
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = b"{}"
        urlopen.return_value = response

        planner.read_json_source("https://updates.turboism.dev/example.json", "updates", required=True)

        request = urlopen.call_args.args[0]
        self.assertEqual(request.get_header("User-agent"), "turboism-release-orchestrator/1")
        self.assertEqual(request.get_header("Accept-encoding"), "identity")


class DecisionMatrixTest(unittest.TestCase):
    def make(self, value, github=None, updates=None, plugin_catalog=None):
        return planner.make_plan(
            value,
            github=github,
            updates=updates,
            catalog=plugin_catalog,
            channel="stable",
        )

    def test_none(self):
        plan = self.make(candidate(), observation(), observation())
        self.assertEqual(plan["intent"], "none")
        self.assertEqual(plan["steps"], [])

    def test_framework(self):
        plan = self.make(candidate(), None, None)
        self.assertEqual(plan["intent"], "framework")
        self.assertIn("framework.github", [step["id"] for step in plan["steps"]])

    def test_plugins(self):
        value = candidate(eligible=False, plugins=[plugin_candidate()])
        plan = self.make(value, plugin_catalog=catalog())
        self.assertEqual(plan["intent"], "plugins")
        self.assertEqual(plan["plugins"]["actions"][0]["action"], "publish")

    def test_combined(self):
        value = candidate(plugins=[plugin_candidate()])
        plan = self.make(value, github=None, updates=None, plugin_catalog=catalog())
        self.assertEqual(plan["intent"], "combined")
        ids = [step["id"] for step in plan["steps"]]
        self.assertLess(ids.index("plugin-directory.publish"), ids.index("framework.github"))
        self.assertLess(ids.index("updates.release-manifest"), ids.index("updates.channel-pointer"))

    def test_partial_framework_draft_is_resume(self):
        missing = {artifacts()[0]["name"]}
        github = observation(missing=missing)
        github["draft"] = True
        plan = self.make(candidate(), github, observation())
        self.assertEqual(plan["framework"]["action"], "resume")
        self.assertEqual(plan["framework"]["github"]["missing"], sorted(missing))

    def test_complete_framework_draft_is_resume_until_published(self):
        github = observation()
        github["draft"] = True
        plan = self.make(candidate(), github, observation())
        self.assertEqual(plan["framework"]["action"], "resume")
        self.assertEqual(plan["framework"]["github"]["missing"], [])

    def test_incomplete_published_framework_is_rejected(self):
        missing = {artifacts()[0]["name"]}
        github = observation(missing=missing)
        github["draft"] = False
        with self.assertRaisesRegex(contracts.ReleaseError, "published but missing"):
            self.make(candidate(), github, observation())

    def test_same_framework_version_different_bytes_rejected(self):
        remote = observation()
        remote["assets"][0]["sha256"] = HASH_A
        with self.assertRaisesRegex(contracts.ReleaseError, "VERSION_NOT_BUMPED"):
            self.make(candidate(), remote, observation())

    def test_extra_framework_asset_rejected(self):
        remote = observation()
        remote["assets"].append({"name": "ninth.txt", "size": 1, "sha256": HASH_A})
        with self.assertRaisesRegex(contracts.ReleaseError, "unexpected"):
            self.make(candidate(), remote, observation())

    def test_newer_remote_framework_rejected(self):
        with self.assertRaisesRegex(contracts.ReleaseError, "newer version"):
            self.make(candidate(), observation("0.44.0"), observation())

    def test_identical_plugin_is_noop(self):
        value = candidate(eligible=False, plugins=[plugin_candidate("0.2.0")])
        plan = self.make(value, plugin_catalog=catalog("0.2.0"))
        self.assertEqual(plan["intent"], "none")

    def test_same_plugin_version_different_bytes_rejected(self):
        value = candidate(eligible=False, plugins=[plugin_candidate("0.2.0")])
        with self.assertRaisesRegex(contracts.ReleaseError, "VERSION_NOT_BUMPED"):
            self.make(value, plugin_catalog=catalog("0.2.0", jar_hash="e" * 64))

    def test_lower_plugin_version_rejected(self):
        value = candidate(eligible=False, plugins=[plugin_candidate("0.1.0")])
        with self.assertRaisesRegex(contracts.ReleaseError, "not higher"):
            self.make(value, plugin_catalog=catalog("0.2.0"))

    def test_policy_only_plugin_update(self):
        value = candidate(eligible=False, plugins=[plugin_candidate("0.2.0")])
        remote = catalog("0.2.0")
        remote["plugins"][0]["support"] = "https://example.invalid/old"
        plan = self.make(value, plugin_catalog=remote)
        self.assertEqual(plan["plugins"]["actions"][0]["action"], "metadata-update")

    def test_untagged_candidate_is_not_framework_eligible(self):
        value = candidate(eligible=False)
        plan = self.make(value)
        self.assertEqual(plan["intent"], "none")
        self.assertEqual(plan["framework"]["reason"], "source has no exact release tag")

    def test_store_plugin_must_be_built(self):
        value = candidate(eligible=False, plugins=[plugin_candidate(built=False)])
        with self.assertRaisesRegex(contracts.ReleaseError, "has not been built"):
            self.make(value, plugin_catalog=catalog())


class CandidateBuilderTest(unittest.TestCase):
    @mock.patch("turboism_release.candidate._load_script")
    def test_framework_artifacts_passes_roster_and_sibling_stage(self, loader):
        version = "0.42.0"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            dist = root / "payload" / "dist"
            stage = dist.parent / "staging"
            roster = repo / "packaging" / "release-plugins.txt"
            dist.mkdir(parents=True)
            stage.mkdir()
            roster.parent.mkdir(parents=True)
            roster.write_text(":plugins:core\n:plugins:mcp\n", encoding="utf-8")
            for item in artifacts(version):
                (dist / item["name"]).write_bytes(b"payload")

            verifier = mock.Mock()
            loader.return_value = verifier
            result = candidate_builder.framework_artifacts(repo, dist, version)

            verifier.verify.assert_called_once_with(
                dist.resolve(), version, roster.resolve(), stage.resolve()
            )
            self.assertEqual(8, len(result))
            self.assertEqual(
                sorted(item["name"] for item in artifacts(version)),
                sorted(item["name"] for item in result),
            )

    @mock.patch("turboism_release.candidate.bundled_plugins", return_value=[])
    @mock.patch("turboism_release.candidate.plugin_candidates")
    @mock.patch("turboism_release.candidate.framework_artifacts")
    @mock.patch("turboism_release.candidate.changelog_entry")
    @mock.patch("turboism_release.candidate.git_source")
    @mock.patch("turboism_release.candidate.framework_version", return_value="0.42.0")
    def test_build_candidate_routes_repo_root_into_framework_verification(
        self,
        _version,
        source,
        changelog,
        framework,
        plugins,
        _bundled,
    ):
        source.return_value = {
            "repository": "turboism/Turboism",
            "revision": SOURCE,
            "tag": None,
        }
        changelog.return_value = {"date": "2026-08-25", "sha256": HASH_A}
        framework.return_value = []
        plugins.return_value = {"policySha256": HASH_B, "candidates": []}
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory) / "repo"
            dist = Path(directory) / "payload" / "dist"
            repo.mkdir()
            dist.mkdir(parents=True)
            candidate_builder.build_candidate(
                repo, dist=dist, market_dir=None, require_tag=False
            )
        framework.assert_called_once_with(repo.resolve(), dist.resolve(), "0.42.0")


class ManifestScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = ROOT / "scripts/release/build-updates-manifests.py"

    def test_framework_manifest_is_deterministic_and_schema_v2(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate_path = root / "candidate.json"
            contracts.write_document(candidate_path, candidate(), "candidate")
            first = root / "release-one.json"
            second = root / "release-two.json"
            common = [
                "python3", str(self.script),
                "--candidate", str(candidate_path),
                "--source-run-id", "12345",
                "--source-run-attempt", "2",
                "--published-at", "2026-09-01T00:00:00.000Z",
            ]
            subprocess.run([*common, "--output", str(first)], check=True, capture_output=True, text=True)
            subprocess.run([*common, "--output", str(second)], check=True, capture_output=True, text=True)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            document = json.loads(first.read_text())
            self.assertEqual(document["schemaVersion"], 2)
            self.assertEqual(document["provenance"]["repository"], "turboism/Turboism")
            self.assertEqual(document["provenance"]["workflow"], ".github/workflows/release.yml")
            self.assertEqual(len(document["assets"]), 8)
            self.assertNotIn("catalogFingerprint", document)

    def test_plugin_pointer_binds_verified_plan_candidates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = candidate(eligible=False, plugins=[plugin_candidate()])
            plan = planner.make_plan(value, github=None, updates=None, catalog=catalog(), channel="stable")
            plan_path = root / "plan.json"
            contracts.write_document(plan_path, plan, "plan")
            catalog_state = root / "catalog-state.json"
            catalog_state.write_text(json.dumps({
                "schemaVersion": 2,
                "catalogFingerprint": {
                    "catalogVersion": "5",
                    "generation": 5,
                    "sha256": "d" * 64,
                    "keyId": "turboism-official-v1",
                    "url": "https://plugin.turboism.dev/api/v2/catalog.json",
                },
                "plugins": [{
                    "id": "dev.turboism.plugin.backup",
                    "version": "0.2.0",
                    "jarSha256": HASH_A,
                }],
            }))
            output = root / "plugin-store-latest.json"
            completed = subprocess.run([
                "python3", str(self.script),
                "--plan", str(plan_path),
                "--catalog-state", str(catalog_state),
                "--published-at", "2026-09-01T00:00:00.000Z",
                "--output", str(output),
            ], capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            document = json.loads(output.read_text())
            self.assertEqual(document["schemaVersion"], 2)
            self.assertNotIn("assetKey", document)
            self.assertEqual(document["catalogFingerprint"]["sha256"], "d" * 64)


class ExecutorTest(unittest.TestCase):
    def test_dispatch_requires_candidate_run_id(self):
        plan = planner.make_plan(candidate(), github=None, updates=None, catalog=None, channel="stable")
        with self.assertRaisesRegex(contracts.ReleaseError, "candidate-run-id"):
            executor.dispatch_production(
                Path("plan.json"),
                plan,
                candidate_run_id=None,
                production=True,
                confirmation=f"publish:{SOURCE}",
                workflow="release-publisher.yml",
                repo="turboism/Turboism",
            )

    @mock.patch.dict(os.environ, {"GITHUB_ACTIONS": ""})
    @mock.patch("turboism_release.executor.subprocess.run")
    def test_dispatch_uses_workflow_inputs_only(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, stdout="", stderr="")
        plan = planner.make_plan(candidate(), github=None, updates=None, catalog=None, channel="stable")
        executor.dispatch_production(
            Path("plan.json"),
            plan,
            candidate_run_id="12345",
            production=True,
            confirmation=f"publish:{SOURCE}",
            workflow="release-publisher.yml",
            repo="turboism/Turboism",
        )
        command = run.call_args.args[0]
        self.assertIn("candidate_run_id=12345", command)
        self.assertIn(f"source_sha={SOURCE}", command)
        self.assertIn(f"plan_id={plan['planId']}", command)
        self.assertFalse(any(item.startswith("plan_json=") for item in command))

    @mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true"})
    @mock.patch("turboism_release.executor.subprocess.run")
    def test_dispatch_rejects_github_actions(self, run):
        plan = planner.make_plan(candidate(), github=None, updates=None, catalog=None, channel="stable")
        with self.assertRaisesRegex(contracts.ReleaseError, "recursively dispatch"):
            executor.dispatch_production(
                Path("plan.json"),
                plan,
                candidate_run_id="12345",
                production=True,
                confirmation=f"publish:{SOURCE}",
                workflow="release-publisher.yml",
                repo="turboism/Turboism",
            )
        run.assert_not_called()


class CliStaticTest(unittest.TestCase):
    def test_public_cli_and_docs_do_not_hardcode_workspace(self):
        paths = [
            ROOT / "scripts/release/turboism-release.py",
            *sorted((ROOT / "scripts/release/turboism_release").glob("*.py")),
        ]
        for path in paths:
            text = path.read_text(encoding="utf-8")
            if path.name == "contracts.py":
                self.assertIn('"/workspace/"', text, path)
                continue
            self.assertNotIn('"/workspace/', text, path)
            self.assertNotIn("'/workspace/", text, path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
