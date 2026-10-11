#!/usr/bin/env python3
"""Fail-closed fixtures for patch-line version-set monotonicity."""
from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_version_monotonicity.py"
SPEC = importlib.util.spec_from_file_location("check_version_monotonicity", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = CHECK
SPEC.loader.exec_module(CHECK)

VERSIONS = ("5.2.03", "5.3.02", "5.3.03", "5.3.04")

REVIEWED_HOST_ARTIFACTS = """
public final class ReviewedHostArtifacts {
    public static final String CUBISM_5_2_03_VERSION = "5.2.03";
    public static final String CUBISM_5_3_02_VERSION = "5.3.02";
    public static final String CUBISM_5_3_03_VERSION = "5.3.03";
    public static final String CUBISM_5_3_04_VERSION = "5.3.04";
}
"""

JAVA_CONTRACT = """
final class Contract {
    static final java.util.Set<String> SUPPORTED = java.util.Set.of(
            "5.2.03", "5.3.02", "5.3.03", "5.3.04");
}
"""

JAVA_HOST_TASKS = {
    "format": "turboism.host-validation.tasks",
    "tasks": {
        "alpha": {"command": "a.sh", "versions": ["5203", "5302", "5303", "5304"]},
    },
}


def verification_record(version: str, aliases: list[str]) -> str:
    return json.dumps(
        {
            "capabilityIds": ["cubism.demo.read"],
            "selectors": [{"alias": alias, "kind": "method"} for alias in aliases],
        }
    )


def write_tree(root: Path) -> None:
    def put(relative: str, text: str) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    put(CHECK.REVIEWED_HOST_ARTIFACTS.as_posix(), REVIEWED_HOST_ARTIFACTS)
    for version in VERSIONS:
        put(
            (CHECK.VERIFICATION_DIR / f"cubism-{version}-demo.json").as_posix(),
            verification_record(version, ["cubism.demo.alpha", "cubism.demo.beta"]),
        )
    put(
        (CHECK.CLASS_PINS_DIR / "demo.json").as_posix(),
        json.dumps({version: {} for version in VERSIONS}),
    )
    put(CHECK.HOST_VALIDATION_TASKS.as_posix(), json.dumps(JAVA_HOST_TASKS))
    put("runtime/src/main/java/dev/turboism/demo/Contract.java", JAVA_CONTRACT)


def live(root: Path) -> list[CHECK.Finding]:
    _, findings = CHECK.check(root)
    return findings


def write_exceptions(root: Path, entries: list[dict]) -> None:
    path = root / CHECK.EXCEPTIONS_FILE
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"exceptions": entries}), encoding="utf-8")


def exception(**overrides) -> dict:
    entry = {
        "kind": "java-set",
        "path": "runtime/src/main/java/dev/turboism/demo/Contract.java",
        "site": "5.2.03,5.3.02,5.3.03",
        "missing": ["5.3.04"],
        "reason": "test fixture",
        "pending": "gap-demo",
    }
    entry.update(overrides)
    return entry


class VersionMonotonicityTest(unittest.TestCase):
    def test_live_tree_passes(self):
        suppressed, findings = CHECK.check(ROOT)
        self.assertEqual([], findings, [f.message() for f in findings])
        self.assertTrue(suppressed)

    def test_consistent_fixture_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            self.assertEqual([], live(root))

    def test_collection_literal_hole_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / "runtime/src/main/java/dev/turboism/demo/Contract.java"
            path.write_text(JAVA_CONTRACT.replace(', "5.3.04"', ""))
            findings = live(root)
            self.assertTrue(
                any(f.kind == "java-set" and f.missing == ("5.3.04",) for f in findings),
                [f.message() for f in findings],
            )

    def test_exception_suppresses_matching_finding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / "runtime/src/main/java/dev/turboism/demo/Contract.java"
            path.write_text(JAVA_CONTRACT.replace(', "5.3.04"', ""))
            write_exceptions(root, [exception()])
            suppressed, findings = CHECK.check(root)
            self.assertEqual([], findings)
            self.assertEqual(1, len(suppressed))

    def test_exception_missing_coverage_still_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / "runtime/src/main/java/dev/turboism/demo/Contract.java"
            path.write_text(JAVA_CONTRACT.replace(', "5.3.03", "5.3.04"', ""))
            write_exceptions(root, [exception(missing=["5.3.04"])])
            _, findings = CHECK.check(root)
            self.assertTrue(
                any("5.3.03" in f.missing for f in findings),
                [f.message() for f in findings],
            )

    def test_stale_exception_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            write_exceptions(root, [exception()])
            findings = live(root)
            self.assertTrue(
                any(f.kind == "stale-exception" for f in findings),
                [f.message() for f in findings],
            )

    def test_exception_without_pending_or_evidence_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            entry = exception()
            del entry["pending"]
            write_exceptions(root, [entry])
            self.assertTrue(live(root))

    def test_annotation_value_hole_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / "runtime/src/main/java/dev/turboism/demo/Contract.java"
            path.write_text(
                'class A {\n    @dev.turboism.sdk.CubismEditor({"5.3.02", "5.3.04"})\n'
                "    void m() {}\n}\n"
            )
            findings = live(root)
            self.assertTrue(
                any(
                    f.kind == "java-annotation" and f.missing == ("5.3.03",)
                    for f in findings
                ),
                [f.message() for f in findings],
            )

    def test_annotation_range_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / "runtime/src/main/java/dev/turboism/demo/Contract.java"
            path.write_text(
                'class A {\n    @dev.turboism.sdk.CubismEditor(from = "5.3.02", to = "5.3.99")\n'
                "    void m() {}\n}\n"
            )
            self.assertEqual([], live(root))

    def test_stray_version_tokens_fold_into_file_set(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / "runtime/src/main/java/dev/turboism/demo/Contract.java"
            path.write_text(
                "class A {\n"
                '    static final String A = "5.3.02";\n'
                "    boolean f(String v) {\n"
                "        return ReviewedHostArtifacts.CUBISM_5_3_03_VERSION.equals(v);\n"
                "    }\n}\n"
            )
            findings = live(root)
            self.assertTrue(
                any(f.kind == "java-file" and f.missing == ("5.3.04",) for f in findings),
                [f.message() for f in findings],
            )

    def test_line_max_version_pin_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / "runtime/src/main/java/dev/turboism/demo/Contract.java"
            path.write_text('class A { static final String V = "5.3.04"; }\n')
            self.assertEqual([], live(root))

    def test_verification_record_alias_regression_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.VERIFICATION_DIR / "cubism-5.3.03-demo.json"
            path.write_text(verification_record("5.3.03", ["cubism.demo.alpha"]))
            findings = live(root)
            self.assertTrue(
                any(
                    f.kind == "verification-record"
                    and "aliases" in f.site
                    and "cubism.demo.beta" in f.missing
                    for f in findings
                ),
                [f.message() for f in findings],
            )

    def test_verification_record_added_alias_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            for version in ("5.3.03", "5.3.04"):
                path = root / CHECK.VERIFICATION_DIR / f"cubism-{version}-demo.json"
                path.write_text(
                    verification_record(
                        version,
                        ["cubism.demo.alpha", "cubism.demo.beta", "cubism.demo.new"],
                    )
                )
            self.assertEqual([], live(root))

    def test_class_pin_hole_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.CLASS_PINS_DIR / "demo.json"
            document = json.loads(path.read_text())
            del document["5.3.03"]
            path.write_text(json.dumps(document))
            findings = live(root)
            self.assertTrue(
                any(f.kind == "class-pins" and f.missing == ("5.3.03",) for f in findings),
                [f.message() for f in findings],
            )

    def test_class_pin_dropped_lower_patch_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.CLASS_PINS_DIR / "demo.json"
            document = json.loads(path.read_text())
            del document["5.2.03"], document["5.3.02"], document["5.3.03"]
            path.write_text(json.dumps(document))
            self.assertEqual([], live(root))

    def test_host_validation_task_hole_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.HOST_VALIDATION_TASKS
            document = json.loads(path.read_text())
            document["tasks"]["alpha"]["versions"] = ["5302", "5303"]
            path.write_text(json.dumps(document))
            findings = live(root)
            self.assertTrue(
                any(
                    f.kind == "host-validation-task"
                    and f.site == "alpha"
                    and f.missing == ("5.3.04",)
                    for f in findings
                ),
                [f.message() for f in findings],
            )

    def test_mcp_coverage_hole_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            path = root / CHECK.MCP_SDK_COVERAGE
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                json.dumps(
                    {
                        "entries": [
                            {
                                "sdkMethod": "demo.Op#m()V",
                                "supportedVersions": ["5.3.02", "5.3.03"],
                            }
                        ]
                    }
                )
            )
            findings = live(root)
            self.assertTrue(
                any(f.kind == "mcp-coverage" for f in findings),
                [f.message() for f in findings],
            )

    def test_missing_reviewed_artifacts_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root)
            (root / CHECK.REVIEWED_HOST_ARTIFACTS).unlink()
            with self.assertRaises(CHECK.MonotonicityError):
                CHECK.check(root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
