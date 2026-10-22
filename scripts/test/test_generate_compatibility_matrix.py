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
    public static final Set<String> SUPPORTED_VERSIONS = Set.of("5.0.00", "5.2.00");
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
import dev.turboism.sdk.CubismEditor;
@CubismEditor
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

- Turboism API: `[0.67.0,1.0.0)`.

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
            "ui.mesh-toolbar.contribute",
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
        "scripts/version-monotonicity-exceptions.json",
        json.dumps(
            {
                "format": "turboism.version-monotonicity-exceptions/v1",
                "exceptions": [
                    {
                        "kind": "java-set",
                        "path": "runtime/src/main/java/example/Contract.java",
                        "site": "5.0.00,5.2.00",
                        "missing": ["5.1.00"],
                        "reason": "Contract admits only 5.2.00; fix in flight.",
                        "pending": "gap-fixture",
                    }
                ],
            }
        ),
    )
    _write(
        root,
        "compatibility/cubism/official-feature-timeline.json",
        json.dumps(
            {
                "format": "turboism.official-feature-timeline/v1",
                "features": [
                    {
                        "id": "late-feature",
                        "introducedIn": "5.2.00",
                        "nameZh": "晚期功能",
                        "nameEn": "Late feature",
                        "url": "https://example.com/late",
                        "relatedCommands": ["EditorCommand.LATE_ONLY"],
                    }
                ],
            }
        ),
    )
    _write(
        root,
        "compatibility/cubism/version-boundaries.json",
        json.dumps(
            {
                "format": "turboism.version-boundaries/v1",
                "boundaries": [
                    {
                        "id": "command:LATE_ONLY",
                        "absentOn": ["5.0.00", "5.1.00"],
                        "reason": "LATE_ONLY added in 5.2.00",
                        "source": "official timeline",
                        "officialSource": {
                            "version": "5.2.00",
                            "url": "https://example.com/late",
                        },
                    }
                ],
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
            ["5.0.00", "5.2.00"], caps["cubism.mesh-tool.session"]["admitted"]
        )
        self.assertEqual(["5.2.00"], caps["cubism.editor-model.write"]["admitted"])

    def test_commands_only_subset_listed(self) -> None:
        names = {c["name"]: c["versions"] for c in self.matrix["commands"]}
        self.assertNotIn("EVERYWHERE", names)
        self.assertEqual(["5.2.00"], names["LATE_ONLY"])
        self.assertEqual([], names["DEAD"])

    def test_sdk_constraints(self) -> None:
        parsed_entries, _ = GEN.parse_sdk_annotations(self.root, list(VERSIONS))
        parsed_by_api = {e["api"]: e["versions"] for e in parsed_entries}
        self.assertEqual(
            list(VERSIONS),
            parsed_by_api["dev.turboism.sdk.cubism.Svc"],
        )
        apis = {e["api"]: e["versions"] for e in self.matrix["sdkApiConstraints"]}
        self.assertEqual(
            ["5.1.00", "5.2.00"],
            apis["dev.turboism.sdk.cubism.CubismFacade#svc()"],
        )
        self.assertNotIn(
            "dev.turboism.sdk.cubism.CubismFacade#reads()", apis
        )
        self.assertNotIn("dev.turboism.sdk.cubism.Svc", apis)

    def test_plugin_supported_and_deltas(self) -> None:
        plugin = self.plugin()
        self.assertEqual(list(VERSIONS), plugin["supportedVersions"])
        self.assertEqual(["5.2.00"], plugin["fullFeatureVersions"])
        notes = {n["key"]: n["versions"] for n in plugin["partialFeatures"]}
        self.assertEqual(["5.0.00", "5.2.00"], notes["cubism.mesh.custom-tools"])
        # The referenced SDK accessor narrows svc() to 5.1.00+.
        self.assertEqual(
            ["5.1.00", "5.2.00"],
            notes["dev.turboism.sdk.cubism.Svc"],
        )

    def test_full_features_never_include_a_missing_feature(self) -> None:
        for plugin in self.matrix["plugins"]:
            for note in plugin["partialFeatures"]:
                self.assertFalse(set(plugin["fullFeatureVersions"]) & set(note["missingOn"]))

    def test_parameter_interception_requires_every_verified_hook_selector(self) -> None:
        descriptor = json.loads(PLUGIN_DESCRIPTOR)
        descriptor["capabilities"] = ["cubism.model.intercept"]
        _write(self.root, f"plugins/toy/{GEN.PLUGIN_DESCRIPTOR}", json.dumps(descriptor))
        path = self.root / "compatibility/cubism/verification/cubism-5.2.00-extra.json"
        record = json.loads(path.read_text())
        aliases = GEN.DECLARED_CAPABILITY_ALIASES["cubism.model.intercept"]["selectors"]
        record["selectors"] = [
            {"alias": alias, "status": "VERIFIED_STATIC"} for alias in aliases
        ]
        path.write_text(json.dumps(record))
        plugin = GEN.build_matrix(self.root)["plugins"][0]
        self.assertEqual(["5.2.00"], plugin["supportedVersions"])
        self.assertEqual([], plugin["unmappedCapabilities"])

        for alias in aliases:
            with self.subTest(missing=alias):
                incomplete = dict(record, selectors=[s for s in record["selectors"] if s["alias"] != alias])
                path.write_text(json.dumps(incomplete))
                plugin = GEN.build_matrix(self.root)["plugins"][0]
                self.assertEqual([], plugin["supportedVersions"])
                self.assertEqual([], plugin["fullFeatureVersions"])
        unverified = dict(record, selectors=[dict(s, status="UNVERIFIED") for s in record["selectors"]])
        path.write_text(json.dumps(unverified))
        self.assertEqual([], GEN.build_matrix(self.root)["plugins"][0]["fullFeatureVersions"])

    def test_model_observation_does_not_drop_missing_project_dependency(self) -> None:
        descriptor = json.loads(PLUGIN_DESCRIPTOR)
        descriptor["capabilities"] = ["cubism.model.observe"]
        _write(self.root, f"plugins/toy/{GEN.PLUGIN_DESCRIPTOR}", json.dumps(descriptor))
        plugin = GEN.build_matrix(self.root)["plugins"][0]
        self.assertEqual([], plugin["supportedVersions"])
        self.assertEqual([], plugin["fullFeatureVersions"])
        self.assertEqual([], plugin["unmappedCapabilities"])
        _write(
            self.root,
            "compatibility/cubism/verification/cubism-5.2.00-project-workspace.json",
            _verification_record("5.2.00", "project-workspace", ["cubism.project.read"]),
        )
        plugin = GEN.build_matrix(self.root)["plugins"][0]
        self.assertEqual(["5.2.00"], plugin["supportedVersions"])

    def test_readme_names_exact_reviewed_and_full_feature_versions(self) -> None:
        block = GEN.readme_block(self.plugin(), "zh", list(VERSIONS))
        self.assertIn("5.0.00, 5.1.00, 5.2.00", block)
        self.assertIn("完整功能", block)
        self.assertIn("5.2.00", block)

    def test_readme_does_not_bridge_unreviewed_patch_gaps(self) -> None:
        plugin = dict(self.plugin(), supportedVersions=["5.0.00", "5.0.02"],
                      fullFeatureVersions=["5.0.00", "5.0.02"], partialFeatures=[])
        block = GEN.readme_block(plugin, "en", ["5.0.00", "5.0.02"])
        self.assertIn("5.0.00, 5.0.02", block)
        self.assertNotIn("5.0.00–5.0.02", block)

    def test_campaign_normalizes_codes_and_statuses(self) -> None:
        tasks = self.matrix["hostValidation"]["toy"]
        self.assertEqual("PASS", tasks["5.0.00"]["validationStatus"])
        self.assertEqual("FAIL", tasks["5.1.00"]["validationStatus"])
        self.assertNotIn("5.2.00", tasks)  # cancelled jobs are not terminal
        host = self.plugin()["hostValidation"]["toy"]
        self.assertIn("5.1.00", host)

    def test_missing_nature_classification(self) -> None:
        plugin = self.plugin()
        notes = {n["key"]: n for n in plugin["partialFeatures"]}
        nature = notes["cubism.mesh.custom-tools"]["missingNature"]
        # 5.1.00 is an interior hole (admitted on 5.0.00 and 5.2.00); the
        # java-set exception whose site equals the admitted set marks it as
        # pending adaptation.
        self.assertEqual("pending", nature["5.1.00"])

    def test_gap_classification_never_assumes_boundary(self) -> None:
        nature = GEN.classify_gap(
            {"5.0.00", "5.1.00"}, {"5.2.00"}, [], narrowed=False
        )
        # Missing versions are never asserted to be boundaries without
        # evidence — a leading prefix stays unconfirmed.
        self.assertEqual({"5.0.00": "unknown", "5.1.00": "unknown"}, nature)
        narrowed = GEN.classify_gap(
            {"5.0.00", "5.1.00"}, {"5.2.00"}, [], narrowed=True
        )
        # A contract/pin-narrowed capability marks missing versions as
        # adaptation-pending, including the leading prefix.
        self.assertEqual({"5.0.00": "pending", "5.1.00": "pending"}, narrowed)
        bounded = GEN.classify_gap(
            {"5.0.00", "5.1.00"},
            {"5.2.00"},
            [],
            key="feat",
            boundaries={"feat": {"5.0.00"}},
        )
        self.assertEqual("boundary", bounded["5.0.00"])
        self.assertEqual("unknown", bounded["5.1.00"])

    def test_triage_bucketing_hides_noise_on_user_page(self) -> None:
        plugin = self.plugin()
        user_cell = GEN._host_cell(
            plugin["hostValidation"], self.matrix["reviewedVersions"], "en",
            user=True,
        )
        dev_cell = GEN._host_cell(
            plugin["hostValidation"], self.matrix["reviewedVersions"], "en",
            user=False,
        )
        self.assertIn("host-verified 5.0.00", user_cell)
        # The untriaged FAIL on 5.1.00 is pending noise: hidden on the user
        # page, shown as unconfirmed to developers.
        self.assertNotIn("5.1.00", user_cell)
        self.assertIn("5.1.00", dev_cell)

    def test_official_timeline_in_docs(self) -> None:
        en = GEN.render_capabilities_doc(self.matrix, "en")
        zh = GEN.render_capabilities_doc(self.matrix, "zh")
        self.assertIn("Official introduction", en)
        self.assertIn("官方引入版本", zh)
        self.assertIn("[5.2.00](https://example.com/late)", en)
        self.assertIn("Confirmed version boundaries", en)
        self.assertIn("已确认版本边界", zh)

        # User page delta with official boundary note
        note = {
            "key": "dev.turboism.sdk.cubism.model.AlphaComposition",
            "versions": ["5.2.00"],
            "missingOn": ["5.0.00", "5.1.00"],
            "missingNature": {"5.0.00": "boundary", "5.1.00": "boundary"},
            "officialSource": {
                "version": "5.2.00",
                "url": "https://example.com/alpha",
                "nameZh": "混合模式",
                "nameEn": "blend modes",
            },
        }
        text_zh = GEN._feature_text(note, "zh", list(VERSIONS), user=True)
        self.assertIn("[Cubism 5.2.00 新增混合模式](https://example.com/alpha)", text_zh)
        text_en = GEN._feature_text(note, "en", list(VERSIONS), user=True)
        self.assertIn("[Cubism 5.2.00 added blend modes](https://example.com/alpha)", text_en)

    def test_rendered_docs(self) -> None:
        zh = GEN.render_plugins_doc(self.matrix, "zh")
        self.assertIn("`toy`", zh)
        self.assertIn("全部审核版本", zh)
        en = GEN.render_capabilities_doc(self.matrix, "en")
        self.assertIn("LATE_ONLY", en)
        self.assertIn("cubism.mesh-tool.session", en)

    def test_plugin_cubism_versions_sidecar(self) -> None:
        outputs = GEN.render_all(self.root)
        rel = (
            "plugins/toy/src/main/resources/META-INF/turboism/"
            "cubism-versions.json"
        )
        document = json.loads(outputs[rel])
        self.assertEqual(
            "turboism.plugin-cubism-versions/v1", document["format"]
        )
        self.assertEqual("dev.turboism.plugin.toy", document["plugin"])
        self.assertEqual(list(VERSIONS), document["supportedVersions"])
        self.assertEqual(["5.2.00"], document["fullFeatureVersions"])

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
        self.assertIn("- Turboism API: `[0.67.0,1.0.0)`.", once)
        self.assertIn("## Install and enable", once)

    def test_generate_then_check(self) -> None:
        self.assertEqual(0, GEN.main(["--root", str(self.root)]))
        self.assertEqual(0, GEN.main(["--root", str(self.root), "--check"]))
        (self.root / "compatibility/cubism/plugins.md").write_text("stale")
        self.assertEqual(1, GEN.main(["--root", str(self.root), "--check"]))


if __name__ == "__main__":
    unittest.main()
