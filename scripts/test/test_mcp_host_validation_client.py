from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CLIENT_PATH = REPO_ROOT / "scripts" / "preview" / "mcp-host-validation-client.py"
SPEC = importlib.util.spec_from_file_location("mcp_host_validation_client", CLIENT_PATH)
assert SPEC is not None and SPEC.loader is not None
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)


class McpHostValidationClientTest(unittest.TestCase):

    def test_public_catalog_matches_read_write_transaction_cutover(self) -> None:
        self.assertEqual({
            "turboism.model_objects.apply",
            "turboism.parameters.apply",
            "turboism.parameter_bindings.apply",
            "turboism.glues.read",
            "turboism.glues.write",
            "turboism.history.read",
            "turboism.history.undo",
            "turboism.history.redo",
            "turboism.transaction.execute",
            "turboism.capabilities.read",
            "turboism.editor_commands.execute",
        }, CLIENT.EXPECTED_TOOLS)
        self.assertNotIn("turboism.history.move", CLIENT.EXPECTED_TOOLS)
        self.assertTrue({
            "turboism://active/model/parameter-bindings",
            "turboism://environment/runtime-diagnostics",
        } <= CLIENT.EXPECTED_RESOURCES)

    def test_selects_a_bounded_reversible_glue_intensity_mutation(self) -> None:
        selected = CLIENT.choose_glue_intensity_mutation([
            {"id": "GlueA", "intensity": 0.45},
            {"id": "GlueB", "intensity": 1.0},
        ])

        self.assertEqual("GlueA", selected[0])
        self.assertEqual(0.45, selected[1])
        self.assertGreaterEqual(selected[2], 0.0)
        self.assertLessEqual(selected[2], 1.0)
        self.assertNotEqual(selected[1], selected[2])

    def test_mcp_runner_accepts_all_three_reviewed_editor_versions(self) -> None:
        text = (REPO_ROOT / "scripts" / "preview" / "run-mcp-host-validation.sh") \
            .read_text(encoding="utf-8")
        self.assertIn("<5203|5302|5303>", text)
        self.assertIn("5302|5303)", text)
        self.assertIn("--require-fixture-unchanged", text)

    def test_standard_sdk_client_uses_the_same_public_catalog(self) -> None:
        text = (REPO_ROOT / "scripts" / "preview" / "mcp-standard-client-validation.js") \
            .read_text(encoding="utf-8")
        for endpoint in CLIENT.EXPECTED_TOOLS:
            self.assertIn(f"'{endpoint}'", text)
        self.assertNotIn("name: 'turboism.history.move'", text)
        self.assertIn("name: 'turboism.history.read'", text)

    def test_history_guard_targets_top_undo_or_next_redo_entry(self) -> None:
        snapshot = {
            "generation": 7,
            "revision": 11,
            "position": 1,
            "entries": [
                {"entryId": "entry-a", "transactionId": "transaction-a"},
                {"entryId": "entry-b", "transactionId": None},
            ],
        }

        self.assertEqual({
            "expectedGeneration": 7,
            "expectedRevision": 11,
            "steps": 1,
            "expectedTopEntryId": "entry-a",
            "expectedTransactionId": "transaction-a",
        }, CLIENT.history_guard_arguments(snapshot, undo=True))

        self.assertEqual({
            "expectedGeneration": 7,
            "expectedRevision": 11,
            "steps": 1,
            "expectedTopEntryId": "entry-b",
        }, CLIENT.history_guard_arguments(snapshot, undo=False))


if __name__ == "__main__":
    unittest.main()
