#!/usr/bin/env python3
"""Fail-closed fixtures for admitted-version-set completeness."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_version_set_completeness.py"
SPEC = importlib.util.spec_from_file_location("check_version_set_completeness", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CHECK)

VERSIONS = ("5.2.03", "5.3.02", "5.3.03")
COMPACT = {"5.2.03": "5203", "5.3.02": "5302", "5.3.03": "5303"}

REVIEWED_HOST_ARTIFACTS = """
public final class ReviewedHostArtifacts {
    public static final String CUBISM_5_2_03_VERSION = "5.2.03";
    public static final String CUBISM_5_3_02_VERSION = "5.3.02";
    public static final String CUBISM_5_3_03_VERSION = "5.3.03";
    public static final HostArtifactDigest CUBISM_5_2_03 = new HostArtifactDigest(1L, "a");
    public static final HostArtifactDigest CUBISM_5_3_02 = new HostArtifactDigest(2L, "b");
    public static final HostArtifactDigest CUBISM_5_3_03 = new HostArtifactDigest(3L, "c");
}
"""

RELEASE_DETECTOR = """
final class CubismEditorReleaseDetector {
    private static int pinnedBuild(final String version) {
        return switch (version) {
            case "5.2.03" -> 502_030_002;
            case "5.3.02" -> 503_020_001;
            case "5.3.03" -> 503_030_001;
            default -> throw new IllegalArgumentException(version);
        };
    }
}
"""

AVAILABILITY_POLICY = """
final class CubismEditorAvailabilityPolicy {
    private static final List<String> REVIEWED_VERSIONS = List.of("5.2.03", "5.3.02", "5.3.03");
}
"""

BOOTSTRAP = """
tasks.processResources {
    listOf(
        "cubism-5.2.03-editor-model.json",
        "cubism-5.3.02-editor-model.json",
        "cubism-5.3.03-editor-model.json"
    ).forEach { record ->
        from(rootProject.file("compatibility/cubism/verification/$record")) {
            into("META-INF/turboism/verification")
        }
    }
}
"""

HOST_VALIDATION_PY = '''
def parse():
    if any(version not in {"5203", "5302", "5303"} for version in versions):
        raise SchedulerError("unsupported")
'''

INDEX_MD = "# Contracts\n\n- `profiles/draft/` — catalogues for 5.2.03, 5.3.02, and 5.3.03.\n"


def write_tree(root: Path, versions: tuple[str, ...] = VERSIONS) -> None:
    def put(relative: str, text: str) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    put(CHECK.REVIEWED_HOST_ARTIFACTS.as_posix(), REVIEWED_HOST_ARTIFACTS)
    put(CHECK.RELEASE_DETECTOR.as_posix(), RELEASE_DETECTOR)
    put(CHECK.AVAILABILITY_POLICY.as_posix(), AVAILABILITY_POLICY)
    put(CHECK.BOOTSTRAP_BUILD.as_posix(), BOOTSTRAP)
    put(CHECK.HOST_VALIDATION_PY.as_posix(), HOST_VALIDATION_PY)
    put(CHECK.INDEX_MD.as_posix(), INDEX_MD)
    tasks = {
        "format": "turboism.host-validation.tasks",
        "tasks": {
            "alpha": {"command": "scripts/preview/a.sh", "versions": [COMPACT[versions[0]]]},
            "beta": {"command": "scripts/preview/b.sh",
                     "versions": [COMPACT[v] for v in versions[1:]]},
        },
    }
    put(CHECK.HOST_VALIDATION_TASKS.as_posix(), json.dumps(tasks))
    for version in versions:
        put(
            (CHECK.PROFILES_DIR / f"cubism-{version}.json").as_posix(),
            json.dumps({"profileId": f"cubism-{version}", "cubismVersion": version}),
        )


def violations(root: Path) -> list[str]:
    return CHECK.check(root)


class VersionSetCompletenessTest(unittest.TestCase):
    def test_live_tree_is_consistent(self):
        self.assertEqual([], violations(ROOT))

    def test_consistent_fixture_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            self.assertEqual([], violations(root))

    def test_missing_release_detector_arm_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.RELEASE_DETECTOR
            path.write_text(path.read_text().replace(
                '            case "5.3.03" -> 503_030_001;\n', ""
            ))
            self.assertTrue(any("pinnedBuild" in v for v in violations(root)))

    def test_dropped_availability_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.AVAILABILITY_POLICY
            path.write_text(path.read_text().replace(', "5.3.02"', ""))
            self.assertTrue(any("REVIEWED_VERSIONS" in v for v in violations(root)))

    def test_dropped_bootstrap_record_line_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.BOOTSTRAP_BUILD
            path.write_text(path.read_text().replace(
                '        "cubism-5.2.03-editor-model.json",\n', ""
            ))
            self.assertTrue(any("bootstrap" in v for v in violations(root)))

    def test_unadmitted_task_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.HOST_VALIDATION_TASKS
            document = json.loads(path.read_text())
            document["tasks"]["alpha"]["versions"].append("5401")
            path.write_text(json.dumps(document))
            found = violations(root)
            self.assertTrue(any("host-validation-tasks" in v for v in found))

    def test_missing_profile_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            (root / CHECK.PROFILES_DIR / "cubism-5.3.03.json").unlink()
            self.assertTrue(any("profiles" in v for v in violations(root)))

    def test_profile_version_field_drift_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.PROFILES_DIR / "cubism-5.3.02.json"
            document = json.loads(path.read_text())
            document["cubismVersion"] = "5.3.99"
            path.write_text(json.dumps(document))
            self.assertTrue(any("cubism-5.3.02.json" in v for v in violations(root)))

    def test_stale_index_prose_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.INDEX_MD
            path.write_text(path.read_text().replace(", and 5.3.03", ""))
            self.assertTrue(any("index.md" in v for v in violations(root)))

    def test_scheduler_whitelist_drift_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.HOST_VALIDATION_PY
            path.write_text(path.read_text().replace(', "5303"', ""))
            self.assertTrue(any("host_validation.py" in v for v in violations(root)))

    def test_version_constant_mismatched_literal_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.REVIEWED_HOST_ARTIFACTS
            path.write_text(path.read_text().replace(
                'CUBISM_5_3_03_VERSION = "5.3.03"',
                'CUBISM_5_3_03_VERSION = "5.3.04"',
            ))
            self.assertTrue(any("CUBISM_5_3_03_VERSION" in v for v in violations(root)))

    def test_artifact_constant_without_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.REVIEWED_HOST_ARTIFACTS
            path.write_text(path.read_text() + (
                "\n    public static final HostArtifactDigest CUBISM_5_4_01 ="
                " new HostArtifactDigest(4L, \"d\");\n"
            ))
            self.assertTrue(any("artifact constants" in v for v in violations(root)))

    def test_missing_site_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            (root / CHECK.AVAILABILITY_POLICY).unlink()
            with self.assertRaises(CHECK.CompletenessError):
                violations(root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
