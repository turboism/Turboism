#!/usr/bin/env python3
"""Fail-closed fixtures for admitted-version-set completeness."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from scripts.test.reviewed_releases_fixture import table


ROOT = Path(__file__).resolve().parents[2]
from scripts import check_reviewed_releases as gate

VERSIONS = ("5.2.03", "5.3.02", "5.3.03")
COMPACT = {"5.2.03": "5203", "5.3.02": "5302", "5.3.03": "5303"}

COMPATIBILITY_CATALOG = json.dumps({
    "format": "turboism.verification-source-family/v1",
    "family": "compatibility-catalog",
    "records": [
        "cubism-5.2.03-editor-model.json",
        "cubism-5.3.02-editor-model.json",
        "cubism-5.3.03-editor-model.json",
    ],
})

INDEX_MD = "# Contracts\n\n- `profiles/draft/` — catalogues for 5.2.03, 5.3.02, and 5.3.03.\n"


def close_route_probe(cases: tuple[str, ...]) -> str:
    arms = "\n".join(f'            case "{code}" -> HostCloseRoute.SYNTHETIC_WINDOW_CLOSING;' for code in cases)
    return (
        "class Probe {\n"
        "    static HostCloseRoute hostCloseRoute(final String hostVersion) {\n"
        "        return switch (hostVersion) {\n"
        f"{arms}\n"
        '            default -> throw new IllegalArgumentException("bad");\n'
        "        };\n"
        "    }\n"
        "}\n"
    )


def write_tree(root: Path, versions: tuple[str, ...] = VERSIONS) -> None:
    def put(relative: str, text: str) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")

    put(gate.r1_RELEASES.as_posix(), table(versions))
    put(gate.generated.TEMPLATE.as_posix(), (ROOT / gate.generated.TEMPLATE).read_text())
    put(gate.generated.ENV.as_posix(), (ROOT / gate.generated.ENV).read_text())
    for path, content in gate.generated.render(root).items():
        put(path.as_posix(), content)
    for path in [gate.r1_AVAILABILITY_POLICY, gate.r1_HOST_VALIDATION_PY,
                 Path('runtime/src/main/java/dev/turboism/mapping/verification/ReviewedCubismReleases.java'),
                 Path('runtime/src/main/java/dev/turboism/mapping/verification/CorePublicApiTrustRoots.java'),
                 Path('runtime/src/main/java/dev/turboism/adapter/cubism/core/CoreVersionExpectation.java')]:
        put(path.as_posix(), (ROOT / path).read_text())
    put(gate.r1_COMPATIBILITY_CATALOG.as_posix(), COMPATIBILITY_CATALOG)
    for version in versions:
        put(f"build/generated/verification/cubism-{version}-editor-model.json", "{}")
    put(gate.r1_INDEX_MD.as_posix(), INDEX_MD)
    tasks = {
        "format": "turboism.host-validation.tasks",
        "tasks": {
            "alpha": {"command": "scripts/preview/a.sh", "versions": [COMPACT[versions[0]]]},
            "beta": {"command": "scripts/preview/b.sh",
                     "versions": [COMPACT[v] for v in versions[1:]]},
        },
    }
    put(gate.r1_HOST_VALIDATION_TASKS.as_posix(), json.dumps(tasks))
    sha = "a" * 64
    put(
        (gate.r1_CLASS_PINS_DIR / "test-hook.json").as_posix(),
        json.dumps({
            versions[0]: {"com/example/A": sha, "com/example/B": sha},
            versions[2]: {"com/example/A": sha},
        }),
    )


def violations(root: Path) -> list[str]:
    return gate.r1_check(root)


class VersionSetCompletenessTest(unittest.TestCase):
    def test_live_tree_is_consistent(self):
        self.assertEqual([], violations(ROOT))

    def test_consistent_fixture_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            self.assertEqual([], violations(root))

    def test_missing_catalog_record_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / gate.r1_COMPATIBILITY_CATALOG
            path.write_text(path.read_text().replace(
                '"cubism-5.3.02-editor-model.json",', ""
            ))
            self.assertTrue(
                any("compatibility-catalog" in v for v in violations(root))
            )

    def test_dropped_availability_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / gate.r1_AVAILABILITY_POLICY
            path.write_text(path.read_text().replace('ReviewedReleaseTable.versions()', 'List.of("5.3.03")'))
            self.assertTrue(any("AvailabilityPolicy" in v for v in violations(root)))

    def test_dropped_materialized_record_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            (root / "build/generated/verification/cubism-5.2.03-editor-model.json").unlink()
            self.assertTrue(any("materialized" in v for v in violations(root)))

    def test_unadmitted_task_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / gate.r1_HOST_VALIDATION_TASKS
            document = json.loads(path.read_text())
            document["tasks"]["alpha"]["versions"].append("5401")
            path.write_text(json.dumps(document))
            found = violations(root)
            self.assertTrue(any("host-validation-tasks" in v for v in found))

    def test_stale_index_prose_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / gate.r1_INDEX_MD
            path.write_text(path.read_text(encoding="utf-8").replace(", and 5.3.03", ""), encoding="utf-8")
            self.assertTrue(any("index.md" in v for v in violations(root)))

    def test_scheduler_whitelist_drift_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / gate.r1_HOST_VALIDATION_PY
            path.write_text(path.read_text(encoding="utf-8").replace('reviewed_host_artifacts.load()', '{}'), encoding="utf-8")
            self.assertTrue(any("host_validation.py" in v for v in violations(root)))

    def test_version_constant_mismatched_literal_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / gate.r1_REVIEWED_HOST_ARTIFACTS
            path.write_text(path.read_text().replace(
                'CUBISM_5_3_03_VERSION = "5.3.03"',
                'CUBISM_5_3_03_VERSION = "5.3.04"',
            ))
            self.assertTrue(any("generated output" in v for v in violations(root)))

    def test_artifact_constant_without_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / gate.r1_REVIEWED_HOST_ARTIFACTS
            path.write_text(path.read_text() + (
                "\n    public static final HostArtifactDigest CUBISM_5_4_01 ="
                " new HostArtifactDigest(4L, \"d\");\n"
            ))
            self.assertTrue(any("generated output" in v for v in violations(root)))

    def test_missing_site_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            (root / gate.r1_AVAILABILITY_POLICY).unlink()
            with self.assertRaises(gate.r1_CompletenessError):
                violations(root)

    def test_probe_close_route_drift_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            probe = root / gate.r1_PROBE_CLOSE_ROUTES[0]
            probe.parent.mkdir(parents=True, exist_ok=True)
            probe.write_text(close_route_probe(("5203", "5302")), encoding="utf-8")
            failures = violations(root)
            self.assertTrue(
                any("hostCloseRoute" in failure and "5.3.03" in failure for failure in failures),
                failures,
            )

    def test_probe_close_route_matching_set_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            probe = root / gate.r1_PROBE_CLOSE_ROUTES[0]
            probe.parent.mkdir(parents=True, exist_ok=True)
            probe.write_text(
                close_route_probe(("5203", "5302", "5303")), encoding="utf-8"
            )
            self.assertEqual([], violations(root))

    def test_unadmitted_class_pin_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / gate.r1_CLASS_PINS_DIR / "test-hook.json"
            document = json.loads(path.read_text())
            document["5.4.01"] = {"com/example/A": "b" * 64}
            path.write_text(json.dumps(document))
            self.assertTrue(any("class-pins" in v for v in violations(root)))

    def test_partial_class_pin_version_set_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / gate.r1_CLASS_PINS_DIR / "test-hook.json"
            document = json.loads(path.read_text())
            del document[VERSIONS[2]]
            path.write_text(json.dumps(document))
            self.assertEqual([], violations(root))

    def test_class_pin_bad_sha256_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / gate.r1_CLASS_PINS_DIR / "test-hook.json"
            document = json.loads(path.read_text())
            document[VERSIONS[0]]["com/example/A"] = "not-hex"
            path.write_text(json.dumps(document))
            self.assertTrue(any("SHA-256" in v for v in violations(root)))

    def test_missing_class_pins_dir_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            for pin in (root / gate.r1_CLASS_PINS_DIR).glob("*.json"):
                pin.unlink()
            (root / gate.r1_CLASS_PINS_DIR).rmdir()
            self.assertTrue(any("class-pins" in v for v in violations(root)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
