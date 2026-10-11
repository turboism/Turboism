#!/usr/bin/env python3
"""Fail-closed fixtures for the SDK @CubismEditor annotation baseline gate."""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_sdk_cubism_annotations.py"
SPEC = importlib.util.spec_from_file_location(
    "check_sdk_cubism_annotations", SCRIPT
)
CHECK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CHECK)

ANNOTATED_SERVICE = """
package dev.turboism.sdk.cubism;
import dev.turboism.sdk.CubismEditor;
@CubismEditor(from = "5.2.00")
public interface AnnotatedService {
    void work();
}
"""

UNANNOTATED_SERVICE = """
package dev.turboism.sdk.cubism;
public interface PlainService {
    void work();
}
"""

CHILD_OF_ANNOTATED = """
package dev.turboism.sdk.cubism;
public interface ChildService extends AnnotatedService {
}
"""

NESTED_TYPES = """
package dev.turboism.sdk.cubism;
import dev.turboism.sdk.CubismEditor;
@CubismEditor({"5.2.00", "5.3.00"})
public interface Outer {
    interface Nested {
        void work();
    }
    enum Kind {
        A,
        B
    }
    record Payload(String id) {
    }
}
"""

OUTSIDE_SCOPE = """
package dev.turboism.sdk.action;
public interface PlainActionService {
    void work();
}
"""

PACKAGE_PRIVATE = """
package dev.turboism.sdk.cubism;
interface HiddenService {
    void work();
}
"""


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _base_tree(root: Path) -> None:
    _write(
        root,
        "sdk/src/main/java/dev/turboism/sdk/cubism/AnnotatedService.java",
        ANNOTATED_SERVICE,
    )
    _write(
        root,
        "sdk/src/main/java/dev/turboism/sdk/action/PlainActionService.java",
        OUTSIDE_SCOPE,
    )
    _write(
        root,
        "sdk/src/main/java/dev/turboism/sdk/cubism/HiddenService.java",
        PACKAGE_PRIVATE,
    )


class AnnotationGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        _base_tree(self.root)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def _baseline(self, *fqns: str) -> None:
        CHECK.write_baseline(self.root, sorted(fqns))

    def test_clean_tree_passes_with_empty_baseline(self) -> None:
        self._baseline()
        self.assertEqual(0, CHECK.run(self.root, False))

    def test_new_unannotated_type_fails(self) -> None:
        self._baseline()
        _write(
            self.root,
            "sdk/src/main/java/dev/turboism/sdk/cubism/PlainService.java",
            UNANNOTATED_SERVICE,
        )
        self.assertEqual(1, CHECK.run(self.root, False))

    def test_unannotated_type_passes_once_registered(self) -> None:
        fqn = "dev.turboism.sdk.cubism.PlainService"
        _write(
            self.root,
            "sdk/src/main/java/dev/turboism/sdk/cubism/PlainService.java",
            UNANNOTATED_SERVICE,
        )
        self._baseline(fqn)
        self.assertEqual(0, CHECK.run(self.root, False))

    def test_interface_parent_annotation_covers_child(self) -> None:
        _write(
            self.root,
            "sdk/src/main/java/dev/turboism/sdk/cubism/ChildService.java",
            CHILD_OF_ANNOTATED,
        )
        self._baseline()
        self.assertEqual(0, CHECK.run(self.root, False))

    def test_enclosing_type_annotation_covers_nested_types(self) -> None:
        _write(
            self.root,
            "sdk/src/main/java/dev/turboism/sdk/cubism/Outer.java",
            NESTED_TYPES,
        )
        index = CHECK.SdkTypeIndex()
        index.load(self.root)
        observed = index.unannotated_public_types()
        self.assertEqual([], observed)

    def test_stale_entry_after_annotation_fails(self) -> None:
        fqn = "dev.turboism.sdk.cubism.PlainService"
        _write(
            self.root,
            "sdk/src/main/java/dev/turboism/sdk/cubism/PlainService.java",
            UNANNOTATED_SERVICE,
        )
        self._baseline(fqn)
        _write(
            self.root,
            "sdk/src/main/java/dev/turboism/sdk/cubism/PlainService.java",
            UNANNOTATED_SERVICE.replace(
                "public interface PlainService",
                "@dev.turboism.sdk.CubismEditor public interface PlainService",
            ),
        )
        self.assertEqual(1, CHECK.run(self.root, False))

    def test_stale_entry_after_removal_fails(self) -> None:
        self._baseline("dev.turboism.sdk.cubism.Gone")
        self.assertEqual(1, CHECK.run(self.root, False))

    def test_method_annotation_does_not_cover_type(self) -> None:
        _write(
            self.root,
            "sdk/src/main/java/dev/turboism/sdk/cubism/PlainService.java",
            UNANNOTATED_SERVICE.replace(
                "void work();",
                "@dev.turboism.sdk.CubismEditor(from = \"5.2.00\") void work();",
            ),
        )
        self._baseline()
        self.assertEqual(1, CHECK.run(self.root, False))


if __name__ == "__main__":
    unittest.main()
