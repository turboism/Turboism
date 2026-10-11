#!/usr/bin/env python3
"""Synthetic-tree fixtures for the compatibility matrix generator."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/generate_compatibility_matrix.py"
SPEC = importlib.util.spec_from_file_location(
    "generate_compatibility_matrix", SCRIPT
)
GEN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(GEN)

VERSIONS = ("5.0.00", "5.1.00", "5.2.00")

HOST_ARTIFACTS = """
public final class ReviewedHostArtifacts {
    public static final String CUBISM_5_0_00_VERSION = "5.0.00";
    public static final String CUBISM_5_1_00_VERSION = "5.1.00";
    public static final String CUBISM_5_2_00_VERSION = "5.2.00";
}
"""

CONTRACT = """
package dev.turboism.mesh;
import java.util.Set;
public final class MeshToolSessionSelectorContract {
    public static final String CAPABILITY_ID = "cubism.mesh-tool.session";
    public static final Set<String> SUPPORTED_VERSIONS = Set.of("5.2.00");
}
"""

INTERCEPTOR = """
import java.util.Set;
import java.util.Map;
final class CubismEditorApiAvailabilityInterceptor {
    private static Map<Class<?>, Set<String>> requiredCapabilities() {
        final Map<Class<?>, Set<String>> required = new java.util.LinkedHashMap<>();
        required.put(dev.turboism.sdk.cubism.write.WriteService.class, Set.of("cubism.editor-model.write"));
        return required;
    }
}
"""

SDK_FACADE = """
package dev.turboism.sdk.cubism;
import dev.turboism.sdk.CubismEditor;
public interface CubismFacade {
    @CubismEditor(from = "5.1.00")
    default Svc svc() { return null; }
    @CubismEditor({"5.0.00", "5.1.00", "5.2.00"})
    default ReadService reads() { return null; }
}
"""

SDK_SVC = """
package dev.turboism.sdk.cubism;
public interface Svc {
}
"""

COMMAND_ENUM = """
package dev.turboism.sdk.cubism.command;
import java.util.Set;
public enum EditorCommand {
    EVERYWHERE(Set.of(
            "5.0.00", "5.1.00", "5.2.00")),
    LATE_ONLY(Set.of("5.2.00")),
    DEAD(Set.of());
}
"""

PLUGIN_DESCRIPTOR = json.dumps(
    {
        "format": "turboism.plugin.meta",
        "schemaVersion": 3,
        "id": "dev.turboism.plugin.toy",
        "name": "Toy Plugin",
        "version": "0.1.0",
        "capabilities": ["cubism.clipmask.read", "cubism.mesh.custom-tools"],
        "permissions": [],
        "environment": {"requiresCubism": True, "ui": "swing"},
    }
)

PLUGIN_JAVA = """
package dev.turboism.plugin.toy;
import dev.turboism.sdk.cubism.Svc;
public final class ToyPlugin {
    void run(CubismContext context) {
        context.cubism().svc();
    }
}
"""

README_EN = """---
pluginId: dev.turboism.plugin.toy
---

# Toy Plugin

## What it does

Things.

## Requirements and compatibility

- Turboism API: `[0.1.0,0.2.0)`.

## Install and enable
"""

README_ZH = README_EN.replace(
    "## Requirements and compatibility", "## 要求与兼容性"
).replace("# Toy Plugin", "# 玩具插件")
README_JA = README_EN.replace(
    "## Requirements and compatibility", "## 要件と互換性"
).replace("# Toy Plugin", "# トイプラグイン")


def _verification_record(version: str, family: str, caps: list[str]) -> str:
    return json.dumps(
        {
            "format": "turboism.static.verification.record",
            "schemaVersion": 1,
            "verificationId": f"cubism-{version}.{family}.static",
            "adapterSliceId": f"adapter.{family}",
            "capabilityIds": caps,
            "cubismVersion": version,
            "status": "VERIFIED_STATIC",
            "selectors": [],
        }
    )


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def build_tree(root: Path) -> None:
    _write(
        root,
        "runtime/src/main/java/dev/turboism/mapping/verification/"
        "ReviewedHostArtifacts.java",
        HOST_ARTIFACTS,
    )
    records = {
        "clipmask": ["cubism.clipmask.read"],
        "editor-model": [
            "cubism.editor-model.read",
            "cubism.mesh-tool.session",
        ],
    }
    for version in VERSIONS:
        for family, caps in records.items():
            _write(
                root,
                f"compatibility/cubism/verification/cubism-{version}-{family}.json",
                _verification_record(version, family, caps),
            )
    _write(
        root,
        "compatibility/cubism/verification/cubism-5.2.00-extra.json",
        _verification_record(
            "5.2.00", "extra", ["cubism.editor-model.write"]
        ),
    )
    for family in ("clipmask", "editor-model"):
        _write(
            root,
            f"scripts/verification-sources/families/{family}.json",
            json.dumps(
                {
                    "format": "turboism.verification-source-family/v1",
                    "family": family,
                    "records": [
                        f"cubism-{v}-{family}.json" for v in VERSIONS
                    ],
                }
            ),
        )
    _write(
        root,
        "scripts/verification-sources/templates/dev/turboism/mapping/verification/"
        "selector/MeshToolSessionSelectorContract.java",
        CONTRACT,
    )
    _write(root, GEN.INTERCEPTOR.as_posix(), INTERCEPTOR)
    _write(
        root,
        "sdk/src/main/java/dev/turboism/sdk/cubism/CubismFacade.java",
        SDK_FACADE,
    )
    _write(root, "sdk/src/main/java/dev/turboism/sdk/cubism/Svc.java", SDK_SVC)
    _write(
        root,
        "sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorCommand.java",
        COMMAND_ENUM,
    )
    module = "plugins/toy"
    _write(root, f"{module}/{GEN.PLUGIN_DESCRIPTOR.as_posix()}", PLUGIN_DESCRIPTOR)
    _write(
        root,
        f"{module}/src/main/java/dev/turboism/plugin/toy/ToyPlugin.java",
        PLUGIN_JAVA,
    )
    _write(root, f"{module}/README.md", README_EN)
    _write(root, f"{module}/README_zh.md", README_ZH)
    _write(root, f"{module}/README_ja.md", README_JA)
    _write(
        root,
        "validation/cubism-version-matrix/inventory.json",
        json.dumps(
            {
                "versions": [
                    {"version": v, "code": v.replace("0", "")}
                    for v in VERSIONS
                ]
            }
        ),
    )
    _write(
        root,
        "validation/cubism-version-matrix/campaign-jobs.json",
        json.dumps(
            {
                "jobs": [
                    {
                        "task": "toy",
                        "version": "5.0.00",
                        "status": "FINAL",
                        "result": {
                            "terminalState": "succeeded",
                            "validationStatus": "PASS",
                        },
                    },
                    {
                        "task": "toy",
                        "version": "5.10.0",
                        "status": "FINAL",
                        "terminalState": "failed",
                    },
                    {
                        "task": "toy",
                        "version": "5.2.00",
                        "status": "CANCELLED",
                    },
                ]
            }
        ),
    )
    GEN.TASK_PLUGIN_ALIASES.setdefault("toy", "toy")


class MatrixTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        build_tree(self.root)
        self.matrix = GEN.build_matrix(self.root)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def plugin(self, directory: str = "toy") -> dict:
        return next(
            p for p in self.matrix["plugins"] if p["directory"] == directory
        )

    def test_reviewed_versions(self) -> None:
        self.assertEqual(list(VERSIONS), self.matrix["reviewedVersions"])

    def test_capability_verified_and_admitted(self) -> None:
        caps = {c["id"]: c for c in self.matrix["capabilities"]}
        self.assertEqual(
            list(VERSIONS), caps["cubism.clipmask.read"]["admitted"]
        )
        # Verified on all three but the selector contract narrows admission.
        self.assertEqual(
            ["5.2.00"], caps["cubism.mesh-tool.session"]["admitted"]
        )
        self.assertEqual(["5.2.00"], caps["cubism.editor-model.write"]["admitted"])

    def test_commands_only_subset_listed(self) -> None:
        names = {c["name"]: c["versions"] for c in self.matrix["commands"]}
        self.assertNotIn("EVERYWHERE", names)
        self.assertEqual(["5.2.00"], names["LATE_ONLY"])
        self.assertEqual([], names["DEAD"])

    def test_sdk_constraints(self) -> None:
        apis = {e["api"]: e["versions"] for e in self.matrix["sdkApiConstraints"]}
        self.assertEqual(
            ["5.1.00", "5.2.00"],
            apis["dev.turboism.sdk.cubism.CubismFacade#svc()"],
        )
        self.assertNotIn(
            "dev.turboism.sdk.cubism.CubismFacade#reads()", apis
        )

    def test_plugin_supported_and_deltas(self) -> None:
        plugin = self.plugin()
        self.assertEqual(list(VERSIONS), plugin["supportedVersions"])
        self.assertEqual(["5.2.00"], plugin["fullFeatureVersions"])
        notes = {n["key"]: n["versions"] for n in plugin["partialFeatures"]}
        # The declared capability resolves to the single capabilityId it
        # aliases; the note keys on that id.
        self.assertEqual(["5.2.00"], notes["cubism.mesh-tool.session"])
        # The referenced SDK accessor narrows svc() to 5.1.00+.
        self.assertEqual(
            ["5.1.00", "5.2.00"],
            notes["dev.turboism.sdk.cubism.Svc"],
        )

    def test_campaign_normalizes_codes_and_statuses(self) -> None:
        tasks = self.matrix["hostValidation"]["toy"]
        self.assertEqual("PASS", tasks["5.0.00"]["validationStatus"])
        self.assertEqual("FAIL", tasks["5.1.00"]["validationStatus"])
        self.assertNotIn("5.2.00", tasks)  # cancelled jobs are not terminal
        host = self.plugin()["hostValidation"]["toy"]
        self.assertIn("5.1.00", host)

    def test_rendered_docs(self) -> None:
        zh = GEN.render_plugins_doc(self.matrix, "zh")
        self.assertIn("`toy`", zh)
        self.assertIn("全部审核版本", zh)
        en = GEN.render_capabilities_doc(self.matrix, "en")
        self.assertIn("LATE_ONLY", en)
        self.assertIn("cubism.mesh-tool.session", en)

    def test_readme_block_idempotent(self) -> None:
        plugin = self.plugin()
        once = GEN.update_readme(
            README_EN, plugin, "README.md", self.matrix["reviewedVersions"]
        )
        self.assertIn(GEN.BLOCK_BEGIN, once)
        self.assertIn(GEN.BLOCK_END, once)
        twice = GEN.update_readme(
            once, plugin, "README.md", self.matrix["reviewedVersions"]
        )
        self.assertEqual(once, twice)
        # The capability-section body is preserved verbatim.
        self.assertIn("- Turboism API: `[0.1.0,0.2.0)`.", once)
        self.assertIn("## Install and enable", once)

    def test_generate_then_check(self) -> None:
        self.assertEqual(0, GEN.main(["--root", str(self.root)]))
        self.assertEqual(0, GEN.main(["--root", str(self.root), "--check"]))
        (self.root / "compatibility/cubism/plugins.md").write_text("stale")
        self.assertEqual(1, GEN.main(["--root", str(self.root), "--check"]))


if __name__ == "__main__":
    unittest.main()
