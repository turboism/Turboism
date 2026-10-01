"""Keep scoped scenario evidence from silently covering SDK overloads."""

import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "method_inventory", Path(__file__).resolve().parents[1] / "dev/cubism_sdk_method_inventory.py"
)
INVENTORY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INVENTORY)


class MethodInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.info = SimpleNamespace(
            name="dev/turboism/sdk/Example", effective_access=1, super_name=None,
            interfaces=[], fields=[], methods=[
                SimpleNamespace(name="move", descriptor="(I)V", access=1),
                SimpleNamespace(name="move", descriptor="(J)V", access=1),
            ],
        )

    def coverage(self, scenario, descriptor=None, name="move"):
        path = self.root / (scenario + ".json")
        row = {"sdkMethod": f"Example.{name}(number)",
               "sdkSource": "sdk/src/main/java/dev/turboism/sdk/Example.java",
               "evidenceKey": "scopedResult"}
        if descriptor:
            row["sdkDescriptor"] = descriptor
        path.write_text(json.dumps({"scenario": scenario, "methods": [row]}))
        return path

    def build(self, paths):
        classes = [SimpleNamespace(info=self.info)]
        with patch.object(INVENTORY, "load_parsed_classes", return_value=(classes, "abc", 1)), \
                patch.object(INVENTORY, "api_classes", side_effect=lambda values: values):
            return INVENTORY.build_inventory(self.root / "sdk.jar", paths)

    def test_name_only_mapping_cannot_claim_both_overloads(self):
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
            self.build(self.coverage("ambiguous"))

    def test_separate_scenarios_bind_only_their_exact_descriptor(self):
        result = self.build([self.coverage("integer", "(I)V"), self.coverage("long", "(J)V")])
        self.assertFalse(result["completeFrameworkCoverage"])
        self.assertEqual(2, result["explicitlyMappedMethodCount"])
        self.assertEqual(2, len(result["coverageIndexes"]))
        methods = result["classes"][0]["methods"]
        self.assertEqual(["integer", "long"], [row["scenarioMapping"]["scenario"] for row in methods])

    def test_absent_descriptor_does_not_become_a_pending_mapping(self):
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
            self.build(self.coverage("missing", "(D)V"))

    def test_overlapping_or_duplicate_indexes_are_rejected(self):
        broad, exact = self.coverage("broad"), self.coverage("exact", "(I)V")
        with self.assertRaisesRegex(ValueError, "Overlapping"):
            self.build([broad, exact])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.build([exact, exact])


if __name__ == "__main__":
    unittest.main()
