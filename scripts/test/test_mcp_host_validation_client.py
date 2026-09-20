from __future__ import annotations

import importlib.util
import inspect
import json
import unittest
from unittest import mock
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
        manifest = json.loads((REPO_ROOT / "scripts/preview/host-validation-tasks.json").read_text())
        self.assertEqual(["5203", "5302", "5303"], manifest["tasks"]["mcp"]["versions"])
        self.assertIn('turboism_select_fixture "$version"', text)
        self.assertIn("--require-fixture-unchanged", text)
        self.assertIn("--client-python", text)
        self.assertNotIn("/home/local-user", text)
        self.assertIn("Turboism MCP server started on the local loopback interface", text)

    def test_standard_sdk_client_uses_the_same_public_catalog(self) -> None:
        text = (REPO_ROOT / "scripts" / "preview" / "mcp-standard-client-validation.js") \
            .read_text(encoding="utf-8")
        for endpoint in CLIENT.EXPECTED_TOOLS:
            self.assertIn(f"'{endpoint}'", text)
        self.assertNotIn("name: 'turboism.history.move'", text)
        self.assertIn("name: 'turboism.history.read'", text)

    def test_tool_failure_keeps_diagnostic_codes_without_raw_messages_or_arguments(self) -> None:
        failed = {"ok": False, "outcome": "ROLLED_BACK", "diagnosticId": "transaction.failed",
                  "steps": [{"id": "read", "output": {"ok": True, "error": None}},
                            {"id": "write", "diagnosticId": "step.failed", "output": {
                                "ok": False, "error": {"code": "STALE_STATE", "message": "/private/raw-model"}}}]}
        failed["steps"] = [{"id": "successful" + str(index), "output": {"ok": True, "error": None}}
                           for index in range(20)] + failed["steps"]
        with mock.patch.object(CLIENT, "tool_result", return_value=failed):
            with self.assertRaises(CLIENT.ValidationFailure) as raised:
                CLIENT.tool_call(object(), "turboism.transaction.execute", {"secret": "do-not-emit"})
        self.assertIn("transaction.failed", str(raised.exception))
        self.assertIn("STALE_STATE", str(raised.exception))
        self.assertNotIn("successful19", str(raised.exception))
        self.assertIn('"completedStepCount": 21', str(raised.exception))
        self.assertNotIn("raw-model", str(raised.exception))
        self.assertNotIn("do-not-emit", str(raised.exception))

    def test_glue_baseline_runs_before_matrices_that_leave_redo_history(self) -> None:
        source = inspect.getsource(CLIENT.main)
        self.assertLess(source.index("validate_audit_input_guards(client"),
                        source.index("validate_reversible_glue_authoring(client"))
        for later in ("validate_reversible_binding_inversion(client", "validate_reversible_parameter_write(client"):
            self.assertLess(source.index("validate_reversible_glue_authoring(client"), source.index(later),
                            "Glue's original-history tip guard must run before an Undo leaves a Redo tail")

    def test_connection_is_numeric_loopback_without_proxy_or_redirect(self) -> None:
        client = CLIENT.McpClient("http://127.0.0.1:43123/mcp", CLIENT.PROTOCOL_VERSION)
        self.assertEqual("http://127.0.0.1:43123/mcp", client.endpoint)
        for endpoint in ("http://127.0.0.1:43123@evil.invalid/mcp", "http://localhost:43123/mcp",
                         "http://127.0.0.1/mcp", "http://127.0.0.1:43123/mcp?x=1",
                         "http://127.0.0.1:43123/mcp#fragment"):
            with self.subTest(endpoint=endpoint), self.assertRaises(CLIENT.ValidationFailure):
                CLIENT.McpClient(endpoint, CLIENT.PROTOCOL_VERSION)
        with self.assertRaises(CLIENT.ValidationFailure):
            CLIENT.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "http://evil.invalid")

    def test_audit_guards_require_protocol_rejection_and_unchanged_state(self) -> None:
        class FakeClient:
            def __init__(self):
                self.calls = []
            def _id(self):
                return 1
            def _post(self, request):
                self.calls.append(request)
                code = -32600 if type(request["id"]) is not int else -32602
                return 200, {}, json.dumps({"jsonrpc": "2.0", "id": None,
                    "error": {"code": code, "message": "rejected"}}).encode()
        history = {"availability": "AVAILABLE", "generation": 1, "revision": 2,
                   "position": 0, "entries": []}
        parameter = {"id": "ParamA", "value": 2, "minimumValue": 0, "maximumValue": 10}
        def resource(_client, uri):
            return {"ok": True, "roots": []} if uri.endswith("hierarchy") else {"value": 2}
        client = FakeClient()
        with mock.patch.object(CLIENT, "history_snapshot", return_value=history), \
             mock.patch.object(CLIENT, "resource_json", side_effect=resource):
            self.assertEqual(11, CLIENT.validate_audit_input_guards(client, [parameter]))
            self.assertEqual(11, len(client.calls))
            with mock.patch.object(client, "_post", return_value=(200, {}, b'{"result":{}}')):
                with self.assertRaises(CLIENT.ValidationFailure):
                    CLIENT.validate_audit_input_guards(client, [parameter])

    def test_explicit_inversion_requires_full_scope_receipt_and_restores_history(self) -> None:
        target = {"type": "art_mesh", "id": "Mesh1"}
        binding = {"parameterId": "ParamA", "target": target, "family": "keyform_grid",
                   "points": [{"id": "a", "value": 0}, {"id": "b", "value": 1}]}
        snapshot = {"parameterBindings": [{"parameterId": "ParamA", "bindings": [binding]}]}
        state = {"position": 0}
        calls = []
        def history(_client):
            return {"availability": "AVAILABLE", "generation": 1, "revision": len(calls),
                    "position": state["position"], "entries": [{"entryId": "entry-1"}]}
        def call(_client, name, args):
            calls.append(name)
            if name == "turboism.parameter_bindings.apply":
                self.assertEqual("all_target_bindings", args["operations"][0]["scope"])
                state["position"] = 1
                return {"ok": True, "results": [{"ok": True, "result": {
                    "outcome": "APPLIED", "retryable": False, "scope": "all_target_bindings",
                    "affectedParameterIds": ["ParamA"], "affectedBindings": [binding]}}]}
            state["position"] = 0 if name.endswith("undo") else 1
            return {"ok": True, "outcome": "MOVED"}
        with mock.patch.object(CLIENT, "history_snapshot", side_effect=history), \
             mock.patch.object(CLIENT, "resource_json", return_value=snapshot), \
             mock.patch.object(CLIENT, "tool_call", side_effect=call):
            self.assertEqual("ALL_BINDINGS_CHANGED_UNDONE_REDONE_AND_RESTORED",
                CLIENT.validate_reversible_binding_inversion(object(), [{"id": "ParamA", "type": "normal"}]))
        self.assertEqual(["turboism.parameter_bindings.apply", "turboism.history.undo",
                          "turboism.history.redo", "turboism.history.undo"], calls)
        self.assertEqual(0, state["position"])

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
