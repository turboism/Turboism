#!/usr/bin/env python3
"""Fail-closed fixtures for the literal version-set registry gate."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from scripts.test.reviewed_releases_fixture import table


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_version_set_registry.py"
SPEC = importlib.util.spec_from_file_location(
    "check_version_set_registry", SCRIPT
)
CHECK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CHECK)

REVIEWED_HOST_ARTIFACTS = table(['5.2.03', '5.3.02', '5.3.03', '5.3.04'])

REGISTERED_CONTRACT = """
package dev.turboism.demo;
import java.util.Set;
final class Contract {
    static final Set<String> SUPPORTED = Set.of(
            "5.2.03", "5.3.02", "5.3.03", "5.3.04");
}
"""

UNREGISTERED_CONTRACT = """
package dev.turboism.demo;
import java.util.Set;
final class OtherContract {
    static final Set<String> SUPPORTED = Set.of("5.3.02", "5.3.03", "5.3.04");
}
"""

RESIDUAL_FILE = """
package dev.turboism.demo;
final class Dispatch {
    static String slice(String version) {
        return switch (version) {
            case "5.3.02", "5.3.03" -> "new";
            default -> "old";
        };
    }
}
"""

CONTRACT_REL = "runtime/src/main/java/dev/turboism/demo/Contract.java"
OTHER_REL = "runtime/src/main/java/dev/turboism/demo/OtherContract.java"
DISPATCH_REL = "runtime/src/main/java/dev/turboism/demo/Dispatch.java"

ALL = ["5.2.03", "5.3.02", "5.3.03", "5.3.04"]
DISPATCH_SET = ["5.3.02", "5.3.03"]
NEWER = ["5.3.02", "5.3.03", "5.3.04"]


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _entry(
    kind: str,
    path: str,
    versions: list[str],
    *,
    basis: str = "verification-derived",
    reason: str = "derived from review data",
    **extra: object,
) -> dict:
    return {
        "kind": kind,
        "path": path,
        "versions": versions,
        "basis": basis,
        "reason": reason,
        **extra,
    }


def _registry(root: Path, sites: list[dict]) -> None:
    _write(
        root,
        CHECK.REGISTRY.as_posix(),
        json.dumps(
            {"format": CHECK.REGISTRY_FORMAT, "sites": sites}
        ),
    )


class RegistryGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        _write(
            self.root,
            CHECK.monotonicity.REVIEWED_HOST_ARTIFACTS.as_posix(),
            REVIEWED_HOST_ARTIFACTS,
        )
        _write(self.root, CONTRACT_REL, REGISTERED_CONTRACT)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_unregistered_collection_fails(self) -> None:
        _registry(self.root, [])
        self.assertEqual(1, CHECK.main([str(self.root)]))

    def test_registered_collection_passes(self) -> None:
        _registry(
            self.root,
            [_entry("collection", CONTRACT_REL, ALL)],
        )
        self.assertEqual(0, CHECK.main([str(self.root)]))

    def test_new_literal_in_another_file_fails(self) -> None:
        _registry(
            self.root,
            [_entry("collection", CONTRACT_REL, ALL)],
        )
        _write(self.root, OTHER_REL, UNREGISTERED_CONTRACT)
        self.assertEqual(1, CHECK.main([str(self.root)]))

    def test_unregistered_residual_file_set_fails(self) -> None:
        _registry(
            self.root,
            [_entry("collection", CONTRACT_REL, ALL)],
        )
        _write(self.root, DISPATCH_REL, RESIDUAL_FILE)
        self.assertEqual(1, CHECK.main([str(self.root)]))

    def test_registered_residual_file_set_passes(self) -> None:
        _registry(
            self.root,
            [
                _entry("collection", CONTRACT_REL, ALL),
                _entry("file", DISPATCH_REL, DISPATCH_SET),
            ],
        )
        _write(self.root, DISPATCH_REL, RESIDUAL_FILE)
        self.assertEqual(0, CHECK.main([str(self.root)]))

    def test_count_mismatch_fails(self) -> None:
        _write(
            self.root,
            CONTRACT_REL,
            REGISTERED_CONTRACT
            + """
            final class Contract2 {
                static final java.util.Set<String> AGAIN = java.util.Set.of(
                        "5.2.03", "5.3.02", "5.3.03", "5.3.04");
            }
            """,
        )
        _registry(
            self.root,
            [_entry("collection", CONTRACT_REL, ALL)],
        )
        self.assertEqual(1, CHECK.main([str(self.root)]))
        _registry(
            self.root,
            [_entry("collection", CONTRACT_REL, ALL, count=2)],
        )
        self.assertEqual(0, CHECK.main([str(self.root)]))

    def test_stale_registry_entry_fails(self) -> None:
        _registry(
            self.root,
            [
                _entry("collection", CONTRACT_REL, ALL),
                _entry("collection", OTHER_REL, NEWER),
            ],
        )
        self.assertEqual(1, CHECK.main([str(self.root)]))

    def test_missing_basis_fails(self) -> None:
        _registry(
            self.root,
            [_entry("collection", CONTRACT_REL, ALL, basis="")],
        )
        self.assertEqual(1, CHECK.main([str(self.root)]))

    def test_annotation_literal_is_not_a_site(self) -> None:
        _write(
            self.root,
            OTHER_REL,
            """
            package dev.turboism.demo;
            interface Api {
                @dev.turboism.sdk.CubismEditor({"5.3.02", "5.3.03"})
                void work();
            }
            """,
        )
        _registry(
            self.root,
            [_entry("collection", CONTRACT_REL, ALL)],
        )
        self.assertEqual(0, CHECK.main([str(self.root)]))

    def test_update_then_check(self) -> None:
        self.assertEqual(0, CHECK.main([str(self.root), "--update"]))
        # Fresh entries carry empty basis/reason and must fail until justified.
        self.assertEqual(1, CHECK.main([str(self.root)]))

    def test_unregistered_wrapper_version_set_fails(self) -> None:
        wrapper_rel = "scripts/preview/run-demo-host-validation.sh"
        _write(
            self.root,
            wrapper_rel,
            '#!/usr/bin/env bash\ncase "$version" in 5203|5302) ;; esac\n',
        )
        _registry(
            self.root,
            [_entry("collection", CONTRACT_REL, ALL)],
        )
        self.assertEqual(1, CHECK.main([str(self.root)]))
        _registry(
            self.root,
            [
                _entry("collection", CONTRACT_REL, ALL),
                _entry(
                    "file",
                    wrapper_rel,
                    ["5.2.03", "5.3.02"],
                    basis="evidence",
                    reason="documented bytecode boundary",
                ),
            ],
        )
        self.assertEqual(0, CHECK.main([str(self.root)]))


if __name__ == "__main__":
    unittest.main()
