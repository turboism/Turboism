from __future__ import annotations

import importlib.util
import inspect
import base64
import hashlib
import struct
import zlib
import re
import json
import unittest
import tempfile
import subprocess
import shutil
import sys
from unittest import mock
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CLIENT_PATH = REPO_ROOT / "scripts" / "preview" / "mcp-host-validation-client.py"
SPEC = importlib.util.spec_from_file_location("mcp_host_validation_client", CLIENT_PATH)
assert SPEC is not None and SPEC.loader is not None
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)


class McpHostValidationClientTest(unittest.TestCase):

    def test_native_observer_rejects_writes_paths_and_oversized_captures_before_transport(self):
        with tempfile.TemporaryDirectory() as root:
            transport = mock.Mock()
            proxy = CLIENT.NativeObservationProxy(transport, Path(root), "test-run")
            proxy.initialized = True
            forbidden = [("tools/call", {"name": name, "arguments": {}})
                         for name in ("turboism.parameters.apply", "turboism.history.undo", "turboism.documents.open")]
            forbidden += [("resources/read", {"uri": "file:///tmp/secret"}),
                          ("resources/read", {"uri": "turboism://host/editor-commands"}),
                          ("unknown", {}), ("tools/list", {"cursor": "arbitrary"})]
            for args in ({"expectedState": {}, "width": 1024, "height": 512},
                         {"expectedState": {}, "width": 512, "height": 512, "crop": {}},
                         {"expectedState": {}, "width": 512.0, "height": 512}):
                forbidden.append(("tools/call", {"name": "turboism.render.capture", "arguments": args}))
            for method, params in forbidden:
                with self.subTest(method=method, params=params), self.assertRaises(CLIENT.ValidationFailure):
                    proxy.dispatch(method, params)
            transport.call.assert_not_called()

    def test_claude_observer_requires_private_canary_gate_before_host_reads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            transport = mock.Mock()
            transport.initialize.return_value = {}
            transport.call.return_value = {"ok": True}
            proxy = CLIENT.NativeObservationProxy(transport, root, "run", require_vision_canary=True)
            proxy.dispatch("initialize", {})
            ready = root / "native-observe-vision-ready.json"
            request = {"name": "turboism.render.state", "arguments": {}}
            for malformed in (None, {}, {"schemaVersion": 1, "harness": "claude", "visionCanaryPassed": False, "model": "actual"}):
                if malformed is not None: ready.write_text(json.dumps(malformed))
                with self.assertRaises(CLIENT.ValidationFailure): proxy.dispatch("tools/call", request)
            transport.call.assert_not_called()
            ready.write_text(json.dumps({"schemaVersion": 1, "harness": "claude", "visionCanaryPassed": True, "model": "actual"}))
            self.assertEqual({"ok": True}, proxy.dispatch("tools/call", request))
            self.assertTrue(proxy.trace["visionCanaryGateVerified"])
            ready.unlink()
            target = root / "other.json"
            target.write_text(json.dumps({"schemaVersion": 1, "harness": "claude", "visionCanaryPassed": True, "model": "actual"}))
            ready.symlink_to(target)
            with self.assertRaises(CLIENT.ValidationFailure): proxy.dispatch("tools/call", request)

    def test_codex_protocol_extensions_are_ignored_without_reaching_host(self):
        with tempfile.TemporaryDirectory() as directory:
            transport = mock.Mock()
            transport.call.return_value = {"ok": True}
            transport.initialize.return_value = {}
            proxy = CLIENT.NativeObservationProxy(transport, Path(directory), "run")
            proxy.dispatch("initialize", {})
            request = {"name": "turboism.render.state", "arguments": {}, "_meta": {"progressToken": 3}}
            self.assertEqual({"ok": True}, proxy.dispatch("tools/call", request))
            transport.call.assert_called_once_with("tools/call", {"name": "turboism.render.state", "arguments": {}})
            for method, result in (("tools/list", {"tools": []}), ("resources/list", {"resources": []}),
                                   ("resources/templates/list", {"resourceTemplates": []})):
                with mock.patch.object(CLIENT, "collect_pages", return_value=[]):
                    self.assertEqual(result, proxy.dispatch(method, {"_meta": {"progressToken": 0}, "cursor": None}))
            for meta in (None, {"other": 1}, {"progressToken": True}, {"progressToken": []}, {"progressToken": "x" * 129}):
                with self.subTest(meta=meta):
                    self.assertEqual({"ok": True}, proxy.dispatch("tools/call", {**request, "_meta": meta, "futureExtension": {"value": 1}}))
            self.assertEqual({"ok": True}, proxy.dispatch("resources/read", {"uri": "turboism://active/document", "path": "/tmp/secret"}))
            self.assertEqual(mock.call("resources/read", {"uri": "turboism://active/document"}), transport.call.call_args)
            self.assertTrue(all("_meta" not in call.args[1] and "futureExtension" not in call.args[1] for call in transport.call.call_args_list))
            transport.reset_mock()
            self.assertEqual({"ok": True}, proxy.dispatch("tools/call", {"name": "turboism.render.state", "futureExtension": True}))
            transport.call.assert_called_once_with("tools/call", {"name": "turboism.render.state", "arguments": {}})
            for arguments in ({"widht": 512}, {"force": True}):
                with self.assertRaises(CLIENT.ValidationFailure):
                    proxy.dispatch("tools/call", {"name": "turboism.render.state", "arguments": arguments})
            self.assertEqual(1, transport.call.call_count)

    def edit_proxy_fixture(self, directory):
        scope = {"documentId":"doc","modelId":"model","objectId":"mesh","formGuid":"form",
                 "coordinates":[{"parameterGuid":"axis","value":1}],"operationId":"operation"}
        rows = [{"kind":"ordinary_write_eligibility","formGuid":"form","snapshotEligible":True}]
        rows += [{"kind":"form_point","formGuid":"form","index":i,"x":x,"y":y}
                 for i,(x,y) in enumerate(((0,0),(64,0),(0,64)))]
        transport = mock.Mock()
        def call(method, params):
            name = params["name"]
            if name.endswith(".read"):
                return {"structuredContent":{"ok":True,"snapshotId":"snapshot","state":{},"totalRows":len(rows),"offset":0,"rows":rows}}
            if name.endswith(".preview"): return {"structuredContent":{"ok":True,"applied":False}}
            return {"structuredContent":{"operationId":"operation","outcome":"APPLIED","receipt":{"transactionId":"tx"}}}
        transport.call.side_effect = call
        proxy = CLIENT.NativeEditingProxy(transport, Path(directory), "run", scope)
        request = {"documentId":"doc","modelId":"model","operationId":"operation","targets":[{
            "kind":"art_mesh","objectId":"mesh","snapshotId":"snapshot","edits":[{
                "formGuid":"form","coordinates":scope["coordinates"],"positions":[{"x":1,"y":0},{"x":64,"y":0},{"x":0,"y":64}]}]}]}
        return proxy, transport, request

    def test_native_editor_requires_read_exact_preview_and_never_replays_write(self):
        with tempfile.TemporaryDirectory() as directory:
            proxy, transport, request = self.edit_proxy_fixture(directory)
            write = "turboism.authoring.keyforms.write"
            with self.assertRaises(CLIENT.ValidationFailure): proxy.edit_dispatch(write,request)
            transport.call.assert_not_called()
            proxy.edit_dispatch("turboism.authoring.meshes.read",{"documentId":"doc","modelId":"model","objectId":"mesh","limit":512})
            with self.assertRaises(CLIENT.ValidationFailure): proxy.edit_dispatch(write,request)
            proxy.edit_dispatch("turboism.authoring.keyforms.preview",request)
            proxy.edit_dispatch(write,request)
            with self.assertRaises(CLIENT.ValidationFailure): proxy.edit_dispatch(write,request)
            self.assertEqual(1,sum(c.args[1]["name"] == write for c in transport.call.call_args_list))
            self.assertTrue(proxy.trace["previewVerified"])
            self.assertTrue(proxy.trace["writeAttempted"])
            self.assertTrue((Path(directory)/"mcp-native-edit-plan-private.json").exists())

    def test_native_editor_rejects_scope_and_geometry_changes_before_host(self):
        with tempfile.TemporaryDirectory() as directory:
            proxy, transport, request = self.edit_proxy_fixture(directory)
            proxy.edit_dispatch("turboism.authoring.meshes.read",{"documentId":"doc","modelId":"model","objectId":"mesh"})
            transport.reset_mock()
            invalid=[]
            def changed(fn):
                r=json.loads(json.dumps(request));fn(r);invalid.append(r)
            changed(lambda r:r.update(operationId="other"))
            changed(lambda r:r["targets"][0].update(objectId="other"))
            changed(lambda r:r["targets"][0]["edits"][0].update(formGuid="default"))
            changed(lambda r:r["targets"][0]["edits"][0].update(coordinates=[]))
            changed(lambda r:r["targets"][0]["edits"][0].update(opacity=0))
            changed(lambda r:r["targets"][0]["edits"][0]["positions"][0].update(x=3))
            changed(lambda r:r["targets"][0]["edits"][0]["positions"][0].update(y=1))
            changed(lambda r:r["targets"][0]["edits"][0]["positions"][1].update(x=65))
            for r in invalid:
                with self.assertRaises(CLIENT.ValidationFailure): proxy.edit_dispatch("turboism.authoring.keyforms.preview",r)
            transport.call.assert_not_called()

    def test_native_editor_result_lookup_recovers_receipt_without_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            proxy, transport, request = self.edit_proxy_fixture(directory)
            proxy.trace["writeAttempted"] = True
            proxy.edit_dispatch("turboism.authoring.keyforms.result",{"operationId":"operation"})
            self.assertEqual("APPLIED",proxy.trace["write"]["outcome"])
            transport.call.assert_called_once_with("tools/call",{"name":"turboism.authoring.keyforms.result","arguments":{"operationId":"operation"}})
            with self.assertRaises(CLIENT.ValidationFailure):
                proxy.edit_dispatch("turboism.authoring.keyforms.result",{"operationId":"other"})

    def test_native_editor_verifies_history_and_restores_after_agent_failure(self):
        for uncertain in (False, True):
            with self.subTest(uncertain=uncertain), tempfile.TemporaryDirectory() as directory:
                home=Path(directory);state=home/"state";state.mkdir()
                sample={"kind":"art_mesh","objectId":"mesh","sourceGuid":"source","rows":[{"kind":"form_point","x":0,"y":0}]}
                expected=[{"kind":"form_point","x":1,"y":0}]
                target={"edits":[{"formGuid":"form","coordinates":[]}]}
                position=[0];moves=[]
                receipt={"historyAfter":{"position":1},"historyBefore":{"position":0},"historyEntryId":"entry","transactionId":"tx"}
                render={"state":{"documentId":"doc","modelId":"model","historyGeneration":1},"parameters":[]}
                def tool(client,name,args):
                    if name=="turboism.render.state":return render
                    if name.startswith("turboism.history."):
                        moves.append(name);position[0]=0 if name.endswith("undo") else 1
                        return {"outcome":"MOVED"}
                    raise AssertionError(name)
                def observer(*args,**kwargs):
                    trace={"writeAttempted":True}
                    if not uncertain:
                        position[0]=1
                        trace.update(write={"outcome":"APPLIED","receipt":receipt},previewVerified=True,agentReadbackVerified=True)
                        (state/"mcp-native-edit-plan-private.json").write_text(json.dumps({"before":sample["rows"],"expected":expected}))
                    (state/"mcp-native-observe-proxy.json").write_text(json.dumps(trace))
                    raise CLIENT.ValidationFailure("Agent disconnected after attempted write")
                with mock.patch.object(CLIENT,"ordinary_edit",return_value=(target,expected)), \
                     mock.patch.object(CLIENT,"ordinary_poses",return_value=[]), \
                     mock.patch.object(CLIENT,"tool_call",side_effect=tool), \
                     mock.patch.object(CLIENT,"history_snapshot",side_effect=lambda client:{"position":position[0]}), \
                     mock.patch.object(CLIENT,"history_guard_arguments",return_value={"expectedTopEntryId":"entry","expectedTransactionId":"tx"}), \
                     mock.patch.object(CLIENT,"validate_ordinary_pose_capture",return_value={"frames":[{"sha256":"before"}]}), \
                     mock.patch.object(CLIENT,"validate_native_observer",side_effect=observer), \
                     mock.patch.object(CLIENT,"read_stored_object",side_effect=lambda *args:{**sample,"rows":expected if position[0] else sample["rows"]}):
                    with self.assertRaises(CLIENT.ValidationFailure):CLIENT.validate_native_editor(home,"test-run",mock.Mock(),[sample])
                evidence=json.loads((state/"mcp-native-edit-verification.json").read_text())
                self.assertEqual("FAIL",evidence["status"])
                self.assertEqual(not uncertain,evidence["restored"])
                self.assertEqual([] if uncertain else ["turboism.history.undo"],moves)
                self.assertEqual(uncertain,evidence.get("outcomeUnknown",False))

    def test_codex_canary_gate_rejects_wrong_harness_or_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            transport = mock.Mock()
            transport.call.return_value = {"ok": True}
            proxy = CLIENT.NativeObservationProxy(transport, root, "run", require_vision_canary=True, expected_harness="codex")
            proxy.initialized = True
            ready = root / "native-observe-vision-ready.json"
            request = {"name": "turboism.render.state", "arguments": {}}
            for harness, model in (("claude", "gpt-6.1-sol"), ("codex", "other")):
                ready.write_text(json.dumps({"schemaVersion": 1, "harness": harness, "model": model, "visionCanaryPassed": True}))
                with self.assertRaises(CLIENT.ValidationFailure): proxy.dispatch("tools/call", request)
            transport.call.assert_not_called()
            ready.write_text(json.dumps({"schemaVersion": 1, "harness": "codex", "model": "gpt-6.1-sol", "visionCanaryPassed": True}))
            self.assertEqual({"ok": True}, proxy.dispatch("tools/call", request))

    def test_native_observer_records_exact_typed_image_and_enforces_capture_budget(self):
        data = b'\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR' + struct.pack('>II', 512, 512) + b'\x00' * 9
        digest = hashlib.sha256(data).hexdigest()
        metadata = {"ok": True, "sha256": digest, "captureId": "capture", "state": {},
                    "width": 512, "height": 512, "byteLength": len(data)}
        result = {"structuredContent": metadata,
                  "content": [{"type": "image", "mimeType": "image/png", "data": base64.b64encode(data).decode()}]}
        with tempfile.TemporaryDirectory() as root:
            transport = mock.Mock()
            transport.call.return_value = result
            proxy = CLIENT.NativeObservationProxy(transport, Path(root), "test-run")
            proxy.initialized = True
            request = {"name": "turboism.render.capture", "arguments": {"expectedState": {}, "width": 512, "height": 512}}
            self.assertEqual(result, proxy.dispatch("tools/call", request))
            self.assertEqual(data, (Path(root) / 'mcp-native-observe-1.png').read_bytes())
            self.assertEqual(digest, proxy.trace['images'][0]['sha256'])
            proxy.dispatch("tools/call", request)
            with self.assertRaises(CLIENT.ValidationFailure): proxy.dispatch("tools/call", request)
            self.assertEqual(2, transport.call.call_count)

    def test_native_observer_never_replays_failed_capture_or_accepts_wrong_hash(self):
        with tempfile.TemporaryDirectory() as root:
            transport = mock.Mock()
            transport.call.side_effect = CLIENT.ValidationFailure('unknown result')
            proxy = CLIENT.NativeObservationProxy(transport, Path(root), "test-run")
            proxy.initialized = True
            request = {"name": "turboism.render.capture", "arguments": {"expectedState": {}, "width": 512, "height": 512}}
            with self.assertRaises(CLIENT.ValidationFailure): proxy.dispatch("tools/call", request)
            self.assertEqual(1, transport.call.call_count)
            self.assertEqual([], proxy.trace['images'])
            transport.call.side_effect = None
            transport.call.return_value = {"structuredContent": {"ok": True, "sha256": "wrong"},
                "content": [{"type": "image", "mimeType": "image/png", "data": "cG5n"}]}
            with self.assertRaises(CLIENT.ValidationFailure): proxy.dispatch("tools/call", request)
            self.assertEqual([], list(Path(root).glob('*.png')))

    def test_native_observer_timeout_terminates_only_its_new_sidecar_group(self):
        with tempfile.TemporaryDirectory() as root:
            home = Path(root)
            (home / 'native-observe').mkdir()
            (home / 'native-observe/runtime.json').write_text(json.dumps({'javaExecutable': '/test/java'}))
            process = mock.Mock(pid=4242)
            process.wait.side_effect = [subprocess.TimeoutExpired('probe', 300), 0]
            with mock.patch.object(CLIENT.subprocess, 'Popen', return_value=process) as spawn, \
                    mock.patch.object(CLIENT.os, 'killpg') as terminate, \
                    mock.patch.object(CLIENT, 'tool_call', return_value={'state': {'documentId': 'doc'}}) as tools:
                with self.assertRaises(subprocess.TimeoutExpired): CLIENT.validate_native_observer(home, 'run', mock.Mock())
                self.assertTrue(spawn.call_args.kwargs['start_new_session'])
                terminate.assert_called_once_with(4242, CLIENT.signal.SIGTERM)
                self.assertEqual(['turboism.render.state'], [c.args[1] for c in tools.call_args_list])

    def test_read_only_inspection_rejects_writes_before_transport(self):
        client = object.__new__(CLIENT.ReadOnlyInspectionClient)
        with mock.patch.object(CLIENT.McpClient, "call", return_value={}) as transport:
            for name in CLIENT.EXPECTED_TOOLS - CLIENT.ReadOnlyInspectionClient.TOOLS:
                with self.subTest(name=name), self.assertRaises(CLIENT.ValidationFailure):
                    client.call("tools/call", {"name": name, "arguments": {}})
            for method, params in [("unknown", {}), ("resources/read", {"uri": "file:///arbitrary"}),
                                   ("prompts/get", {"name": "unknown"})]:
                with self.assertRaises(CLIENT.ValidationFailure): client.call(method, params)
            transport.assert_not_called()
            for name in CLIENT.ReadOnlyInspectionClient.TOOLS:
                client.call("tools/call", {"name": name, "arguments": {}})
            self.assertEqual(len(CLIENT.ReadOnlyInspectionClient.TOOLS), transport.call_count)

    def test_read_only_inspection_requires_history_preservation_before_success(self):
        before = {"availability": "AVAILABLE", "generation": 1, "revision": 2, "position": 0, "entries": []}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with mock.patch.object(CLIENT, "history_snapshot", return_value={**before, "revision": 3}):
                with self.assertRaises(CLIENT.ValidationFailure):
                    CLIENT.finish_read_only_inspection(None, root, "run", before, [], {})
            self.assertFalse((root / "mcp-read-only-inspection.json").exists())
            render = {"state": {"documentId": "doc", "modelId": "model"}, "sha256": "a" * 64}
            row = {"kind": "art_mesh", "objectId": "mesh", "sourceGuid": "guid", "rowCount": 2,
                   "rowsSha256": "b" * 64, "rows": [{"positions": [1, 2]}]}
            with mock.patch.object(CLIENT, "history_snapshot", return_value=before):
                result = CLIENT.finish_read_only_inspection(None, root, "run", before, [row], render)
            self.assertEqual("NONE", result["modelMutation"])
            self.assertNotIn("rows", result["objects"][0])

    def test_private_inventory_repeated_reads_and_summary_omit_resource_payloads(self):
        state = {"documentId": "doc", "modelId": "model", "historyRevision": 1}
        resources = {
            "turboism://active/model/hierarchy": {"availability": "AVAILABLE", "root": {
                "kind": "MODEL", "name": "private model", "children": [{
                    "kind": "ART_MESH", "id": "mesh", "name": "private face", "children": []}]}},
            "turboism://active/model/parameters": {"parameters": [{"id": "p", "name": "private axis"}]},
            "turboism://active/model/parameter-bindings": {"parameterBindings": []}}
        for changed in (False, True):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                reads = []
                def read(client, uri):
                    reads.append(uri)
                    if changed and len(reads) == 4: return {"availability": "UNAVAILABLE"}
                    return resources[uri]
                with mock.patch.object(CLIENT, "tool_call", return_value={"state": state}), \
                        mock.patch.object(CLIENT, "resource_json", side_effect=read):
                    if changed:
                        with self.assertRaises(CLIENT.ValidationFailure):
                            CLIENT.capture_read_only_inventory(None, root, state)
                        self.assertEqual([], list(root.iterdir()))
                    else:
                        summary = CLIENT.capture_read_only_inventory(None, root, state)
                        self.assertEqual(6, len(reads))
                        self.assertEqual(2, summary["nodeCount"])
                        self.assertEqual("NOT_VERIFIED", summary["semanticMapping"])
                        self.assertNotIn("private face", json.dumps(summary))
                        self.assertEqual(resources, json.loads((root / "mcp-read-only-inventory-private.json").read_text()))

    def test_private_inventory_rejects_stale_state_before_resource_read(self):
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(CLIENT, "tool_call", return_value={"state": {"historyRevision": 2}}), \
                mock.patch.object(CLIENT, "resource_json") as read:
            with self.assertRaises(CLIENT.ValidationFailure):
                CLIENT.capture_read_only_inventory(None, Path(directory), {"historyRevision": 1})
            read.assert_not_called()
            self.assertEqual([], list(Path(directory).iterdir()))

    @staticmethod
    def head_profile_resources():
        nodes, parameters, groups = [], {}, {}
        for role, kind, name, wire_kind, axes, parent in CLIENT.HEAD_CANDIDATE_PROFILE:
            nodes.append({"id": role, "kind": kind, "name": name, "parentId": parent, "children": []})
            for axis in axes:
                parameters[axis] = {"id": axis}
                groups.setdefault(axis, {"parameterId": axis, "bindings": []})["bindings"].append({
                    "parameterId": axis, "target": {"type": wire_kind, "id": role}, "family": "keyform_grid",
                    "points": [{"value": key} for key in [-30, 0, 30]]})
        return {"turboism://active/model/hierarchy": {"availability": "AVAILABLE", "root": {
                    "kind": "MODEL", "children": nodes}},
                "turboism://active/model/parameters": {"parameters": list(parameters.values())},
                "turboism://active/model/parameter-bindings": {"parameterBindings": list(groups.values())}}

    def test_head_candidates_require_names_parent_and_exact_axis_bindings(self):
        import copy
        base = self.head_profile_resources()
        selected = CLIENT.select_head_candidates(base)
        self.assertEqual(["face_rotation", "face_warp", "face_contour"], [c["role"] for c in selected])
        for mode in ("parent", "duplicate-name", "wrong-axis", "duplicate-parameter"):
            with self.subTest(mode=mode):
                resources = copy.deepcopy(base)
                nodes = resources["turboism://active/model/hierarchy"]["root"]["children"]
                if mode == "parent": nodes[1]["parentId"] = "body"
                elif mode == "duplicate-name": nodes.append(copy.deepcopy(nodes[1]))
                elif mode == "wrong-axis":
                    resources["turboism://active/model/parameter-bindings"]["parameterBindings"][0]["bindings"][0]["points"][0]["value"] = -10
                else:
                    resources["turboism://active/model/parameters"]["parameters"].append({"id": "ParamAngleX"})
                with self.assertRaises(CLIENT.ValidationFailure): CLIENT.select_head_candidates(resources)
        base["turboism://active/model/hierarchy"]["root"]["children"][0]["name"] = "other"
        self.assertEqual([], CLIENT.select_head_candidates(base))

    def test_head_candidate_native_tuple_form_and_eligibility_validation(self):
        import copy
        candidate = {"kind": "rotation_deformer", "role": "face_rotation", "objectId": "r", "parameterIds": ["ParamAngleZ"]}
        rows = [{"kind": "axis", "parameterGuid": "native-z", "parameterId": "ParamAngleZ", "keys": [-30, 0, 30]}]
        for i, key in enumerate([-30, 0, 30]):
            rows.extend([{"kind": "cell", "formGuid": str(i), "coordinates": [{"parameterGuid": "native-z", "value": key}]},
                         {"kind": "rotation_form", "formGuid": str(i)},
                         {"kind": "ordinary_write_eligibility", "formGuid": str(i), "snapshotEligible": key != 0}])
        snapshot = {"sourceGuid": "native-source", "rowCount": len(rows), "rowsSha256": "a" * 64, "rows": rows}
        result = CLIENT.summarize_head_candidate(candidate, snapshot)
        self.assertEqual(2, result["eligibleFormCount"])
        self.assertEqual("UNPROVEN", result["hostWriteAdmission"])
        self.assertNotIn("rows", result)
        for mode in ("tuple", "unknown-form", "missing-cell", "duplicate-axis", "missing-eligibility"):
            with self.subTest(mode=mode):
                changed = copy.deepcopy(snapshot)
                if mode == "tuple": changed["rows"][1]["coordinates"] = []
                elif mode == "unknown-form": changed["rows"][1]["formGuid"] = "missing"
                elif mode == "missing-cell": del changed["rows"][1]
                elif mode == "duplicate-axis": changed["rows"].append(copy.deepcopy(rows[0]))
                else: changed["rows"].pop()
                with self.assertRaises(CLIENT.ValidationFailure): CLIENT.summarize_head_candidate(candidate, changed)

    def test_head_candidate_state_change_cannot_publish_success(self):
        state = {"documentId": "doc"}
        candidate = {"kind": "rotation_deformer", "objectId": "r"}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "mcp-read-only-inventory-private.json").write_text("{}")
            with mock.patch.object(CLIENT, "select_head_candidates", return_value=[candidate]), \
                    mock.patch.object(CLIENT, "tool_call", side_effect=[{"state": state}, {"state": {"documentId": "other"}}]), \
                    mock.patch.object(CLIENT, "read_stored_object", return_value={}), \
                    mock.patch.object(CLIENT, "summarize_head_candidate", return_value={}):
                with self.assertRaises(CLIENT.ValidationFailure):
                    CLIENT.capture_head_candidate_authoring(None, root, state)
            self.assertFalse((root / "mcp-head-candidates.json").exists())
            self.assertFalse((root / "mcp-head-candidate-authoring-private.json").exists())

    def test_unknown_inspection_mode_fails_before_connection(self):
        with mock.patch.object(sys, "argv", ["client", "home", "run", "--unknown"]), \
                mock.patch.object(CLIENT, "await_connection") as connection:
            self.assertEqual(2, CLIENT.main())
            connection.assert_not_called()

    def test_isolated_task_reads_staged_json_and_fails_without_it(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            client = directory / CLIENT_PATH.name
            shutil.copy(CLIENT_PATH, client)
            table = directory / 'reviewed-releases.json'
            shutil.copy(REPO_ROOT / 'compatibility/cubism/reviewed-releases.json', table)
            command = [sys.executable, '-I', '-c',
                       'import runpy,sys,json; print(json.dumps(runpy.run_path(sys.argv[1])["ALL_REVIEWED_VERSIONS"]))',
                       str(client)]
            result = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(CLIENT.ALL_REVIEWED_VERSIONS, json.loads(result.stdout))
            table.unlink()
            self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)

    def test_authorized_open_dispatch_is_reconciled_without_claiming_first_open(self):
        for mode in ("ok", "wrong-result", "changed-state", "ungranted"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                calls = []
                state = {"activeDocument": {"documentId": "task"}}
                dispatched = {"ok": True, "outcome": "EXECUTED",
                              "code": "NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS",
                              "persistenceVerified": False, "reopenVerified": False}
                def call(client, name, args):
                    calls.append((name, args))
                    if name == "turboism.documents.read":
                        changed = mode == "changed-state" and any(n == "turboism.documents.open" for n, _ in calls)
                        return {"ok": True, "snapshotId": "snapshot", "state": {} if changed else state}
                    if name == "turboism.files.request_grant":
                        return {"state": "CANCELED"} if mode == "ungranted" else {"state": "GRANTED", "grantId": "opaque"}
                    if name == "turboism.files.grant_status": return {"state": "CONSUMED"}
                    if name == "turboism.documents.file_result":
                        return {} if mode == "wrong-result" else dispatched
                    if name == "turboism.documents.open": return dispatched
                    self.fail(name)
                with mock.patch.object(CLIENT, "tool_call", side_effect=call):
                    if mode == "ok":
                        result = CLIENT.validate_authorized_model_open(object(), Path(directory), "task-test")
                        self.assertEqual("PASS", result["status"])
                        self.assertFalse(result["firstOpenVerified"])
                        self.assertTrue((Path(directory) / "mcp-authorized-open.json").is_file())
                    else:
                        with self.assertRaises(CLIENT.ValidationFailure):
                            CLIENT.validate_authorized_model_open(object(), Path(directory), "task-test")
                        self.assertFalse((Path(directory) / "mcp-authorized-open.json").exists())
                self.assertLessEqual(sum(n == "turboism.documents.open" for n, _ in calls), 2)

    def test_fresh_open_requires_empty_inventory_fresh_native_identity_and_complete_rows(self):
        for mode in ("ok", "still-open", "unknown", "not-ready", "row-change", "not-fresh", "partial-hashes"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                calls = []
                samples = [{"kind": k, "objectId": k, "sourceGuid": "source-" + k,
                            "rows": [{"formGuid": "form-" + k, "value": 1}]} for k in
                           ("art_mesh", "warp_deformer", "rotation_deformer")]
                native = {"freshNativeDocument": mode != "not-fresh", "geometryKinds": 3,
                          "bindingsEqual": True, "taskFileIdentityVerified": True, "fixtureUnchanged": True,
                          "beforeGeometryHashes": ["a" * 64] * (1 if mode == "partial-hashes" else 3),
                          "afterGeometryHashes": ["a" * 64] * (1 if mode == "partial-hashes" else 3),
                          "beforeBindingsHash": "b" * 64, "afterBindingsHash": "b" * 64,
                          "fixtureSha256": "c" * 64}
                result = {"outcome": "OUTCOME_UNKNOWN" if mode == "unknown" else "EXECUTED",
                          "code": "NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS",
                          "persistenceVerified": False, "reopenVerified": False}
                def call(client, name, args):
                    calls.append(name)
                    if name == "turboism.render.state": return {"state": {"documentId": "doc", "modelId": "model"}}
                    if name == "turboism.documents.read":
                        opened = "turboism.documents.open" in calls
                        ready = mode == "still-open" or (opened and mode != "not-ready")
                        return {"snapshotId": "snapshot", "state": {
                            "activeDocument": {"kind": "MODEL"} if ready else None,
                            "authoringState": {"documentId": "doc"} if ready else None}}
                    if name == "turboism.files.request_grant": return {"state": "GRANTED", "grantId": "opaque"}
                    if name in ("turboism.documents.open", "turboism.documents.file_result"): return result
                    if name == "turboism.files.grant_status": return {"state": "CONSUMED"}
                    self.fail(name)
                def read(client, kind, object_id, state):
                    sample = next(x for x in samples if x["kind"] == kind)
                    return {**sample, "rows": [{"formGuid": "different"}]} if mode == "row-change" else sample
                def report(root, name, task, status):
                    return ({"runId": task, "status": status, "noActiveDocument": True,
                             "fixtureSha256": "c" * 64} if status == "CLOSED" else native)
                with mock.patch.object(CLIENT, "tool_call", side_effect=call), \
                     mock.patch.object(CLIENT, "validate_stored_authoring", return_value=samples), \
                     mock.patch.object(CLIENT, "read_stored_object", side_effect=read), \
                     mock.patch.object(CLIENT, "await_fresh_open_report", side_effect=report):
                    if mode == "ok":
                        out = CLIENT.validate_authorized_fresh_model_open(object(), Path(directory), "task-test", timeout_seconds=0)
                        self.assertEqual("PASS", out["status"])
                        self.assertTrue(out["firstOpenFromNoDocumentVerified"])
                    else:
                        with self.assertRaises(CLIENT.ValidationFailure):
                            CLIENT.validate_authorized_fresh_model_open(object(), Path(directory), "task-test", timeout_seconds=0)
                    saved = json.loads((Path(directory) / "mcp-authorized-fresh-open.json").read_text())
                    self.assertEqual("PASS" if mode == "ok" else "FAIL", saved["status"])
                self.assertEqual(0 if mode == "still-open" else 1, calls.count("turboism.documents.open"))
                self.assertFalse(any("history." in name for name in calls))

    def test_fresh_open_report_rejects_foreign_and_failed_native_evidence(self):
        for mode in ("foreign", "failed"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                Path(directory, "result.json").write_text(json.dumps({
                    "runId": "foreign" if mode == "foreign" else "task-test",
                    "status": "FAIL" if mode == "failed" else "PASS"}))
                with self.assertRaises(CLIENT.ValidationFailure):
                    CLIENT.await_fresh_open_report(Path(directory), "result.json", "task-test", "PASS", .1)

    def test_file_dispatch_response_loss_reconciles_without_replaying_write(self):
        for mode in ("success", "lost", "unknown", "mismatch"):
            with self.subTest(mode=mode):
                calls = []
                result = {"outcome": "OUTCOME_UNKNOWN" if mode == "unknown" else "EXECUTED",
                          "persistenceVerified": False, "reopenVerified": False}
                def call(client, tool, arguments):
                    calls.append(tool)
                    if tool == "turboism.documents.save_as" and mode == "lost": raise TimeoutError()
                    if tool == "turboism.documents.file_result" and mode == "mismatch": return {}
                    return result
                with mock.patch.object(CLIENT, "tool_result", side_effect=call):
                    if mode in ("success", "lost"):
                        self.assertEqual(result, CLIENT.validation_file_dispatch(object(), "turboism.documents.save_as", {"operationId": "op"}))
                    else:
                        with self.assertRaises(CLIENT.ValidationFailure):
                            CLIENT.validation_file_dispatch(object(), "turboism.documents.save_as", {"operationId": "op"})
                self.assertEqual(1, calls.count("turboism.documents.save_as"))
                self.assertTrue(all(x in ("turboism.documents.save_as", "turboism.documents.file_result") for x in calls))

    def test_validation_open_requires_empty_inventory_and_observed_model_readiness(self):
        for mode in ("ok", "not-empty", "not-ready"):
            with self.subTest(mode=mode):
                calls = []
                def read(client, name, args):
                    calls.append(name)
                    loaded = mode == "not-empty" or (len(calls) > 1 and mode == "ok")
                    return {"snapshotId": "snapshot", "state": {"activeDocument": {"kind": "MODEL"} if loaded else None,
                            "authoringState": {"documentId": "doc"} if loaded else None}}
                with mock.patch.object(CLIENT, "tool_call", side_effect=read), \
                     mock.patch.object(CLIENT, "validation_file_dispatch", return_value={"code": "NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS"}) as dispatch:
                    if mode == "ok": CLIENT.validation_open_from_empty(object(), "opaque", "op", timeout_seconds=0)
                    else:
                        with self.assertRaises(CLIENT.ValidationFailure):
                            CLIENT.validation_open_from_empty(object(), "opaque", "op", timeout_seconds=0)
                    self.assertEqual(0 if mode == "not-empty" else 1, dispatch.call_count)

    def test_prebound_head_persistence_preserves_bindings_and_rejects_unexpected_changes(self):
        for changed_bindings in (False, True):
            with self.subTest(changed_bindings=changed_bindings), tempfile.TemporaryDirectory() as directory:
                original = self.geometry_fixture()
                expected = [{**x, "rows": CLIENT.ordinary_edit(x)[1]} for x in original]
                stage = ["original"]
                calls = []
                def read(client, kind, object_id, current):
                    source = expected if stage[0] in ("modified", "loaded") else original
                    return next(x for x in source if x["kind"] == kind)
                def call(client, name, args):
                    calls.append(name)
                    if name == "turboism.render.state": return {"state": original[0]["state"]}
                    if name == "turboism.authoring.keyforms.preview": return {"applied": False}
                    if name == "turboism.authoring.keyforms.write":
                        stage[0] = "modified"
                        return {"outcome": "APPLIED", "targets": [{"readbackVerified": True}] * 3}
                    if name == "turboism.files.grant_status": return {"state": "CONSUMED"}
                    self.fail(name)
                def open_saved(client, grant, operation):
                    stage[0] = "loaded" if stage[0] == "modified" else "original"
                    return {"loaded": True}
                baseline = {"bindingsHash": "b" * 64, "fixtureSha256": "f" * 64, "geometryHashes": ["original"] * 3}
                bindings_hash = "d" * 64 if changed_bindings else baseline["bindingsHash"]
                native = {"freshNativeDocument": True, "bindingsEqual": True, "taskFileIdentityVerified": True,
                          "geometryKinds": 3, "beforeGeometryHashes": ["modified"] * 3, "afterGeometryHashes": ["modified"] * 3,
                          "beforeBindingsHash": bindings_hash, "afterBindingsHash": bindings_hash, "savedFileSha256": "c" * 64}
                restored = {"originalRestored": True, "fixtureSha256": baseline["fixtureSha256"],
                            "restoredGeometryHashes": baseline["geometryHashes"], "restoredBindingsHash": baseline["bindingsHash"],
                            "savedFileSha256": native["savedFileSha256"]}
                reports = [baseline, {"savedFileSha256": "c" * 64}, native, {}, restored]
                images = [{"frames": [{"sha256": value}]} for value in ("before", "modified", "modified")]
                with mock.patch.object(CLIENT, "tool_call", side_effect=call), \
                        mock.patch.object(CLIENT, "read_stored_object", side_effect=read), \
                        mock.patch.object(CLIENT, "await_fresh_open_report", side_effect=reports), \
                        mock.patch.object(CLIENT, "ordinary_poses", return_value=[]), \
                        mock.patch.object(CLIENT, "validate_ordinary_pose_capture", side_effect=images), \
                        mock.patch.object(CLIENT, "validation_grant", return_value="opaque"), \
                        mock.patch.object(CLIENT, "validation_file_dispatch", return_value={"outcome": "EXECUTED"}) as dispatch, \
                        mock.patch.object(CLIENT, "validation_open_from_empty", side_effect=open_saved) as opener:
                    if changed_bindings:
                        with self.assertRaisesRegex(CLIENT.ValidationFailure, "Existing head bindings changed"):
                            CLIENT.validate_production_save_open(None, Path(directory), "task", samples=original,
                                                                 planner=CLIENT.ordinary_edit, initialize_binding=False)
                        self.assertEqual(1, opener.call_count)
                    else:
                        result = CLIENT.validate_production_save_open(None, Path(directory), "task", samples=original,
                                                                     planner=CLIENT.ordinary_edit, initialize_binding=False)
                        self.assertEqual("PASS", result["status"])
                        self.assertTrue(result["geometryBindingsPersistenceVerified"])
                        self.assertEqual("NOT_NEEDED_EXISTING_BINDINGS_PRESERVED", result["initialization"])
                        self.assertEqual(2, opener.call_count)
                    self.assertEqual(1, dispatch.call_count)
                    self.assertFalse(any("bindings.initialize" in name or "history." in name for name in calls))

    def test_head_persistence_transport_rejects_binding_and_asset_mutations(self):
        client = object.__new__(CLIENT.HeadPersistenceClient)
        with mock.patch.object(CLIENT.McpClient, "call", return_value={}) as transport:
            for name in CLIENT.EXPECTED_TOOLS - CLIENT.HeadPersistenceClient.TOOLS:
                with self.subTest(name=name), self.assertRaises(CLIENT.ValidationFailure):
                    client.call("tools/call", {"name": name, "arguments": {}})
            transport.assert_not_called()
            for name in CLIENT.HeadPersistenceClient.TOOLS:
                client.call("tools/call", {"name": name, "arguments": {}})
            self.assertEqual(len(CLIENT.HeadPersistenceClient.TOOLS), transport.call_count)

    def test_production_save_unknown_stops_without_open_or_old_history_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            state = {"documentId": "doc", "modelId": "model"}
            axis = {"kind": "axis", "parameterId": "angle", "parameterGuid": "axis-guid",
                    "minimum": -30, "defaultValue": 0, "maximum": 30}
            samples = [{"kind": k, "objectId": k, "sourceGuid": "source-" + k, "snapshotId": "snapshot",
                        "state": state, "rows": ([axis] if k != "rotation_deformer" else []) +
                        [{"kind": "point", "formGuid": "form-" + k, "x": 0}]} for k in
                       ("art_mesh", "warp_deformer", "rotation_deformer")]
            calls = []; written = False
            def read(client, kind, object_id, current):
                sample = next(x for x in samples if x["kind"] == kind)
                return {**sample, "rows": [{**r, "x": 1} if r["kind"] == "point" and written else r for r in sample["rows"]]}
            def call(client, name, args):
                nonlocal written
                calls.append(name)
                if name == "turboism.render.state": return {"state": state}
                if name == "turboism.authoring.bindings.initialize_plan": return {"planId": "plan", "axes": [axis]}
                if name == "turboism.authoring.bindings.initialize": return {"outcome": "APPLIED"}
                if name == "turboism.authoring.keyforms.preview": return {"applied": False}
                if name == "turboism.authoring.keyforms.write":
                    written = True; return {"outcome": "APPLIED", "targets": [{"readbackVerified": True}] * 3}
                self.fail(name)
            def edit(sample):
                return ({"kind": sample["kind"], "objectId": sample["objectId"]},
                        [{**r, "x": 1} if r["kind"] == "point" else r for r in sample["rows"]])
            images = [{"frames": [{"sha256": "before"}]}, {"frames": [{"sha256": "modified"}]}]
            with mock.patch.object(CLIENT, "validate_stored_authoring", return_value=samples), \
                 mock.patch.object(CLIENT, "tool_call", side_effect=call), \
                 mock.patch.object(CLIENT, "read_stored_object", side_effect=read), \
                 mock.patch.object(CLIENT, "await_fresh_open_report", return_value={"status": "BASELINE"}), \
                 mock.patch.object(CLIENT, "ordinary_edit", side_effect=edit), \
                 mock.patch.object(CLIENT, "ordinary_poses", return_value=[]), \
                 mock.patch.object(CLIENT, "validate_ordinary_pose_capture", side_effect=images), \
                 mock.patch.object(CLIENT, "validation_grant", return_value="opaque"), \
                 mock.patch.object(CLIENT, "validation_file_dispatch", side_effect=CLIENT.ValidationFailure("unknown")) as dispatch:
                with self.assertRaises(CLIENT.ValidationFailure):
                    CLIENT.validate_production_save_open(object(), Path(directory), "task-test")
                self.assertEqual(1, dispatch.call_count)
                self.assertEqual("turboism.documents.save_as", dispatch.call_args.args[1])
            saved = json.loads((Path(directory) / "mcp-production-save-open.json").read_text())
            self.assertEqual("FAIL", saved["status"])
            self.assertEqual("WRITE_GRANT_SAVE_AS", saved["phase"])
            self.assertFalse(any("history." in x or "documents.open" in x for x in calls))
            self.assertFalse((Path(directory) / "mcp-production-save-prepare.json").exists())
            self.assertFalse((Path(directory) / "mcp-production-save-restore-close.json").exists())

    def test_public_catalog_matches_production_domain_constants(self) -> None:
        root = REPO_ROOT / "plugins/mcp/src/main/java/dev/turboism/plugin/mcp"
        sources = list(root.glob("*Domain.java")) + [
            root / "McpProductionDomainCatalog.java", root / "McpRasterValidationSchemas.java"]
        expected = set()
        for source in sources:
            for declaration in re.findall(r"static final String\s+[^;]+;", source.read_text()):
                expected.update(re.findall(r'"(turboism\.[a-z_.]+)"', declaration))
        self.assertEqual(80, len(expected))
        self.assertEqual(expected, CLIENT.EXPECTED_TOOLS)
        self.assertNotIn("turboism.history.move", CLIENT.EXPECTED_TOOLS)
        self.assertTrue({
            "turboism://active/model/parameter-bindings",
            "turboism://environment/runtime-diagnostics",
        } <= CLIENT.EXPECTED_RESOURCES)

    def test_stored_authoring_reads_three_kinds_with_immutable_pagination(self):
        state = {"documentId": "doc", "modelId": "model", "historyGeneration": 1,
                 "historyRevision": 2, "poseHash": "a" * 64}
        before = {"ok": True, "state": state, "parameters": []}
        kinds = {"meshes": ("ART_MESH", "art_mesh"), "warps": ("WARP_DEFORMER", "warp_deformer"),
                 "rotations": ("ROTATION_DEFORMER", "rotation_deformer")}
        hierarchy = {"availability": "AVAILABLE", "root": {"kind": "MODEL", "children": [
            {"id": domain + suffix, "kind": upper, "children": []}
            for domain, (upper, _) in kinds.items() for suffix in ("-editable", "-default")]}}
        def call(client, tool, arguments):
            if tool == "turboism.render.state":
                return before
            domain = tool.split(".")[-2]
            offset = arguments.get("offset", 0)
            return {"ok": True, "kind": kinds[domain][1], "objectId": arguments["objectId"],
                    "sourceGuid": "source-" + arguments["objectId"], "snapshotId": "snapshot-" + arguments["objectId"],
                    "state": {k: v for k, v in state.items() if k != "poseHash"},
                    "offset": offset, "totalRows": 2, "nextOffset": 1 if offset == 0 else None,
                    "rows": [{"kind": "ordinary_write_eligibility", "index": offset,
                              "snapshotEligible": arguments["objectId"].endswith("-editable")}]}
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(CLIENT, "tool_call", side_effect=call), \
                mock.patch.object(CLIENT, "tool_result", side_effect=call), \
                mock.patch.object(CLIENT, "resource_json", return_value=hierarchy):
            result = CLIENT.validate_stored_authoring(object(), Path(directory))
            self.assertEqual(3, len(result))
            self.assertTrue(all(item["rowCount"] == 2 for item in result))
            self.assertTrue(all(item["objectId"].endswith("-editable") for item in result))
            self.assertTrue((Path(directory) / "mcp-stored-authoring.json").is_file())
        def drift(client, tool, arguments):
            page = call(client, tool, arguments)
            if arguments.get("offset") == 1:
                page["snapshotId"] = "foreign-snapshot"
            return page
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(CLIENT, "tool_call", side_effect=drift), \
                mock.patch.object(CLIENT, "tool_result", side_effect=drift), \
                mock.patch.object(CLIENT, "resource_json", return_value=hierarchy):
            with self.assertRaises(CLIENT.ValidationFailure):
                CLIENT.validate_stored_authoring(object(), Path(directory))
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_stored_numeric_comparison_uses_native_float_bits_and_exact_indices(self):
        actual = [{"kind": "form_point", "index": 1, "formGuid": "form", "x": 356.53055, "y": 0}]
        expected = [{**actual[0], "x": 356.5305480957031}]
        self.assertTrue(CLIENT.stored_rows_equal(actual, expected))
        for field, value in (("x", 356.5306), ("index", 1.00000001), ("formGuid", "other")):
            with self.subTest(field=field):
                self.assertFalse(CLIENT.stored_rows_equal(actual, [{**expected[0], field: value}]))
        self.assertFalse(CLIENT.stored_rows_equal(actual, [{**expected[0], "y": -0.0}]))

    def geometry_fixture(self):
        samples = []
        for kind in ("art_mesh", "warp_deformer", "rotation_deformer"):
            rows = [{"kind": "ordinary_write_eligibility", "formGuid": "default", "snapshotEligible": False},
                    {"kind": "axis", "parameterGuid": "axis", "parameterId": "Angle", "minimum": -1, "maximum": 1},
                    {"kind": "ordinary_write_eligibility", "formGuid": "edit", "snapshotEligible": True},
                    {"kind": "cell", "formGuid": "edit", "coordinates": [{"parameterGuid": "axis", "value": 1}]},
                    {"kind": "source_triangle", "index": 0, "a": 0, "b": 1, "c": 2}]
            if kind == "rotation_deformer":
                rows += [{"kind": "rotation_form", "formGuid": guid, "angle": 0,
                          "originX": 1, "originY": 2, "scale": 1, "reflectedX": False, "reflectedY": True}
                         for guid in ("default", "edit")]
            else:
                rows += [{"kind": "form_point", "formGuid": guid, "index": 0, "x": 0, "y": 2}
                         for guid in ("default", "edit")]
            samples.append({"kind": kind, "objectId": kind, "snapshotId": kind + "-snapshot", "rows": rows,
                            "sourceGuid": kind + "-source", "state": {"documentId": "doc", "modelId": "model"}})
        return samples

    def test_ordinary_plan_uses_exact_tuple_and_preserves_source_and_default(self):
        for sample in self.geometry_fixture():
            original = json.loads(json.dumps(sample))
            target, changed = CLIENT.ordinary_edit(sample)
            self.assertEqual(original, sample)
            self.assertEqual("edit", target["edits"][0]["formGuid"])
            self.assertEqual([{"parameterGuid": "axis", "value": 1}], target["edits"][0]["coordinates"])
            self.assertNotEqual(sample["rows"], changed)
            self.assertEqual([r for r in sample["rows"] if r.get("formGuid") != "edit"],
                             [r for r in changed if r.get("formGuid") != "edit"])
            for row in sample["rows"]:
                if row.get("kind") == "ordinary_write_eligibility": row["snapshotEligible"] = False
            with self.assertRaises(CLIENT.ValidationFailure): CLIENT.ordinary_edit(sample)

    def test_head_edit_scales_to_parent_space_and_preserves_other_forms(self):
        for kind in ("art_mesh", "warp_deformer", "rotation_deformer"):
            for scale in (0.001, 1, 1000):
                with self.subTest(kind=kind, scale=scale):
                    sample = next(x for x in self.geometry_fixture() if x["kind"] == kind)
                    rows = sample["rows"]
                    rows[:] = [r for r in rows if r["kind"] not in ("axis", "cell", "form_point")]
                    ids = ["ParamAngleZ"] if kind == "rotation_deformer" else ["ParamAngleX", "ParamAngleY"]
                    rows.extend({"kind": "axis", "parameterGuid": axis, "parameterId": axis} for axis in ids)
                    rows.append({"kind": "cell", "formGuid": "edit", "coordinates": [
                        {"parameterGuid": axis, "value": 0 if axis == "ParamAngleY" else 30} for axis in ids]})
                    if kind != "rotation_deformer":
                        rows.extend({"kind": "form_point", "formGuid": guid, "index": i, "x": x, "y": i}
                                    for guid in ("edit", "default") for i, x in enumerate((0, scale)))
                    original = json.loads(json.dumps(sample))
                    target, changed = CLIENT.bounded_head_edit(sample)
                    self.assertEqual(original, sample)
                    self.assertEqual([r for r in rows if r.get("formGuid") != "edit"],
                                     [r for r in changed if r.get("formGuid") != "edit"])
                    edit = target["edits"][0]
                    if kind == "rotation_deformer": self.assertEqual(0.5, edit["form"]["angle"])
                    else:
                        points = edit["positions" if kind == "art_mesh" else "controlPoints"]
                        self.assertTrue(all(0 < p["x"] - x <= scale / 128 for p, x in zip(points, (0, scale))))
                        self.assertEqual([0, 1], [p["y"] for p in points])
                        sample["rows"].append({"kind": "cell", "formGuid": "edit", "coordinates": []})
                        with self.assertRaises(CLIENT.ValidationFailure): CLIENT.bounded_head_edit(sample)

    def test_head_edit_transport_cannot_request_file_structure_or_binding_mutations(self):
        client = object.__new__(CLIENT.HeadEditingClient)
        with mock.patch.object(CLIENT.McpClient, "call", return_value={}) as transport:
            for name in CLIENT.EXPECTED_TOOLS - CLIENT.HeadEditingClient.TOOLS:
                with self.subTest(name=name), self.assertRaises(CLIENT.ValidationFailure):
                    client.call("tools/call", {"name": name, "arguments": {}})
            transport.assert_not_called()
            for name in CLIENT.HeadEditingClient.TOOLS:
                client.call("tools/call", {"name": name, "arguments": {}})
            self.assertEqual(len(CLIENT.HeadEditingClient.TOOLS), transport.call_count)

    def test_response_loss_discards_only_first_write_before_rpc_decode(self):
        client = object.__new__(CLIENT.ResponseLossHeadEditingClient)
        message = {"method": "tools/call", "params": {"name": "turboism.authoring.keyforms.write"}}
        # Deliberately invalid RPC body: injection must happen before parsing it.
        with mock.patch.object(CLIENT.McpClient, "_post", return_value=(200, {}, b"invalid-rpc")) as post:
            with self.assertRaises(CLIENT.InjectedWriteResponseLoss): client._post(message)
            self.assertEqual(1, client.injected_response_losses)
            result_query = {"method": "tools/call", "params": {"name": "turboism.authoring.keyforms.result"}}
            self.assertEqual((200, {}, b"invalid-rpc"), client._post(result_query))
            self.assertEqual((200, {}, b"invalid-rpc"), client._post(message))
            self.assertEqual(3, post.call_count)
        other = object.__new__(CLIENT.ResponseLossHeadEditingClient)
        self.assertEqual(0, other.injected_response_losses)
        with mock.patch.object(CLIENT.McpClient, "_post", side_effect=TimeoutError):
            with self.assertRaises(TimeoutError): other._post(message)
        self.assertEqual(0, other.injected_response_losses)

    def test_response_loss_reconciles_only_same_operation_with_stable_receipt(self):
        for mode in ("success", "unknown", "wrong_id", "no_receipt", "warning", "unstable", "query_failure", "no_injection"):
            with self.subTest(mode=mode):
                calls = []
                evidence = {}
                result = {"operationId": "op", "ok": True, "outcome": "APPLIED", "receipt": {"historyEntryId": "entry"}}
                def call(client, name, args):
                    calls.append((name, args))
                    if name.endswith(".write"):
                        if mode == "no_injection": return result
                        raise CLIENT.InjectedWriteResponseLoss()
                    self.assertEqual("turboism.authoring.keyforms.result", name)
                    self.assertEqual({"operationId": "op"}, args)
                    if mode == "query_failure": raise TimeoutError()
                    if mode == "unknown": return {**result, "ok": False, "outcome": "OUTCOME_UNKNOWN"}
                    if mode == "wrong_id": return {**result, "operationId": "other"}
                    if mode == "no_receipt": return {**result, "receipt": None}
                    if mode == "warning": return {**result, "outcome": "APPLIED_WITH_READBACK_WARNING"}
                    if mode == "unstable" and len(calls) == 3: return {**result, "receipt": {"historyEntryId": "other"}}
                    return result
                with mock.patch.object(CLIENT, "tool_result", side_effect=call):
                    if mode == "success":
                        self.assertEqual(result, CLIENT.reconcile_injected_keyform_write(object(), {"operationId": "op"}, evidence))
                        self.assertTrue(evidence["responseLoss"]["retainedResultStable"])
                    else:
                        with self.assertRaises((CLIENT.ValidationFailure, TimeoutError)):
                            CLIENT.reconcile_injected_keyform_write(object(), {"operationId": "op"}, evidence)
                self.assertEqual(1, sum(name.endswith(".write") for name, _ in calls))
                self.assertFalse(evidence["responseLoss"]["writeReplayed"])

    def test_three_kind_native_history_matrix_restores_after_success_and_readback_failure(self):
        for mode in ("success", "without_persistence", "readback_failure", "unknown", "cleanup_failure", "native_unknown", "persistence_unknown", "loss_success", "loss_unknown"):
            corrupt = mode == "readback_failure"
            with self.subTest(mode=mode):
                originals = self.geometry_fixture()
                expected = [{**x, "rows": CLIENT.ordinary_edit(x)[1]} for x in originals]
                position = [0]
                phase = ["original"]
                written = [False]
                def history(client):
                    return {"availability": "AVAILABLE", "generation": 1, "revision": 10,
                            "position": position[0], "entries": [{"index": 0, "entryId": "entry", "transactionId": "tx"}]}
                def call(client, name, args):
                    if name == "turboism.render.state": return {"state": {"documentId": "doc", "modelId": "model", "historyGeneration": 1, "poseHash": "pose"}}
                    if name == "turboism.authoring.keyforms.preview": return {"ok": True, "applied": False}
                    if name == "turboism.history.undo": position[0] = 0; phase[0] = "original"; return {"ok": True}
                    if name == "turboism.history.redo": position[0] = 1; phase[0] = "changed"; return {"ok": True}
                    self.fail(name)
                def write(client, name, args):
                    if name == "turboism.authoring.keyforms.write":
                        self.assertFalse(written[0], "A write must never be replayed")
                        written[0] = True
                        position[0] = 1
                        phase[0] = "changed"
                        if mode.startswith("loss_"): raise CLIENT.InjectedWriteResponseLoss()
                    else:
                        self.assertEqual("turboism.authoring.keyforms.result", name)
                        self.assertEqual({"operationId": "task-ordinary-three-kinds"}, args)
                        self.assertEqual(1, position[0])
                    if mode in ("unknown", "loss_unknown"):
                        return {"ok": False, "outcome": "OUTCOME_UNKNOWN", "receipt": None}
                    return {"ok": True, "operationId": "task-ordinary-three-kinds", "outcome": "APPLIED", "receipt": {"historyAfter": {"position": 1},
                            "historyEntryId": "entry", "transactionId": "tx"}, "targets": [{"readbackVerified": True}]}
                def read(client, kind, object_id, state):
                    source = expected if phase[0] == "changed" else originals
                    item = json.loads(json.dumps(next(x for x in source if x["kind"] == kind)))
                    if (corrupt and phase[0] == "changed") or (mode == "cleanup_failure" and written[0]): item["rows"].append({"kind": "unexpected_source_change"})
                    return item
                def persist(*args):
                    if mode == "persistence_unknown": raise CLIENT.ValidationFailure("uncertain native file result")
                    position[0] = 0
                    phase[0] = "original"
                    return {"status": "PASS"}
                with tempfile.TemporaryDirectory() as directory, \
                        mock.patch.object(CLIENT, "validate_stored_authoring", return_value=originals), \
                        mock.patch.object(CLIENT, "history_snapshot", side_effect=history), \
                        mock.patch.object(CLIENT, "tool_call", side_effect=call), \
                        mock.patch.object(CLIENT, "tool_result", side_effect=write) as writer, \
                        mock.patch.object(CLIENT, "validate_native_geometry_rollback", return_value={"status": "PASS"},
                                          side_effect=CLIENT.ValidationFailure("uncertain native recovery") if mode == "native_unknown" else None), \
                        mock.patch.object(CLIENT, "validate_native_geometry_persistence", side_effect=persist) as persistence, \
                        mock.patch.object(CLIENT, "validate_ordinary_pose_capture", side_effect=lambda *args: {
                            "frames": [{"label": "pose", "sha256": phase[0]}]}), \
                        mock.patch.object(CLIENT, "read_stored_object", side_effect=read):
                    if mode not in ("success", "without_persistence", "loss_success"):
                        with self.assertRaises(CLIENT.ValidationFailure):
                            CLIENT.validate_ordinary_geometry(object(), Path(directory), "task", response_loss=mode.startswith("loss_"))
                    else:
                        CLIENT.validate_ordinary_geometry(object(), Path(directory), "task", persistence=mode != "without_persistence", response_loss=mode.startswith("loss_"))
                    if mode == "without_persistence": persistence.assert_not_called()
                    result = json.loads((Path(directory) / "mcp-ordinary-geometry.json").read_text())
                    self.assertEqual("PASS" if mode in ("success", "without_persistence", "loss_success") else "FAIL", result["status"])
                    self.assertEqual(mode not in ("unknown", "cleanup_failure", "native_unknown", "persistence_unknown", "loss_unknown"), result["writeRestored"])
                    if mode == "cleanup_failure":
                        self.assertEqual("ValidationFailure", result["cleanupError"]["exceptionClass"])
                    self.assertEqual(1 if mode in ("unknown", "persistence_unknown", "loss_unknown") else 0, position[0])
                    self.assertEqual(0 if mode == "native_unknown" else 3 if mode == "loss_success" else 2 if mode == "loss_unknown" else 1,
                                     writer.call_count, "Unknown writes must not be replayed")

    def test_geometry_persistence_requires_fresh_reopen_all_kinds_bindings_and_file_proof(self):
        samples = self.geometry_fixture()
        plans = [CLIENT.ordinary_edit(x)[0] for x in samples]
        for mode in ("success", "foreign", "cached", "geometry", "bindings", "file", "missing_kind", "timeout"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                hashes = ",".join(["a" * 64, "b" * 64, "c" * 64])
                report = {"runId": "task", "status": "PASS", "geometrySaveReopen": "PASS", "fixtureUnchanged": "true",
                          "originalReopened": "true", "freshNativeDocument": "true", "persistenceObjectKinds": "3",
                          "savedGeometryHashes": hashes, "reopenedGeometryHashes": hashes,
                          "savedBindingsHash": "d" * 64, "reopenedBindingsHash": "d" * 64, "savedFileSha256": "e" * 64}
                if mode == "foreign": report["runId"] = "foreign"
                if mode == "cached": report["freshNativeDocument"] = "false"
                if mode == "geometry": report["reopenedGeometryHashes"] = ",".join(["f" * 64] * 3)
                if mode == "bindings": report["reopenedBindingsHash"] = "f" * 64
                if mode == "file": report["savedFileSha256"] = ""
                if mode == "missing_kind": report["persistenceObjectKinds"] = "2"
                if mode != "timeout":
                    (root / "mcp-geometry-persistence-result.properties").write_text("\n".join(k + "=" + v for k, v in report.items()))
                if mode == "success":
                    self.assertEqual(report, CLIENT.validate_native_geometry_persistence(root, "task", samples, plans))
                else:
                    with self.assertRaises(CLIENT.ValidationFailure):
                        CLIENT.validate_native_geometry_persistence(root, "task", samples, plans, timeout_seconds=.001)
                request = json.loads((root / "mcp-geometry-persistence-request.json").read_text())
                self.assertEqual(3, len(request["targets"]))
                self.assertNotIn("path", request)

    def render_fixture(self, size=256):
        def chunk(kind, content):
            return (struct.pack(">I", len(content)) + kind + content
                    + struct.pack(">I", zlib.crc32(kind + content)))
        pixels = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
                  + chunk(b"IDAT", zlib.compress((b"\x00" + b"\xff\x00\x00\xff" * size) * size))
                  + chunk(b"IEND", b""))
        state = {"documentId": "doc", "modelId": "model", "historyGeneration": 1,
                 "historyRevision": 2, "poseHash": "a" * 64}
        before = {"ok": True, "state": state, "parameters": [{"id": "Angle", "value": 0}]}
        metadata = {**before, "width": size, "height": size, "renderer": "cubism.native.renderPixels",
                    "byteLength": len(pixels), "sha256": hashlib.sha256(pixels).hexdigest()}
        result = {"structuredContent": metadata, "content": [
            {"type": "text", "text": json.dumps(metadata)},
            {"type": "image", "mimeType": "image/png", "data": base64.b64encode(pixels).decode()}]}
        return before, result, pixels

    def test_ordinary_poses_require_complete_native_tuple_and_live_ranges(self):
        samples = self.geometry_fixture()
        targets = [CLIENT.ordinary_edit(x)[0] for x in samples]
        self.assertEqual(3, len(CLIENT.ordinary_poses(samples, targets)))
        for failure in ("missing_target", "missing_axis", "range"):
            broken = json.loads(json.dumps(targets))
            if failure == "missing_target": broken.pop()
            elif failure == "missing_axis": broken[0]["edits"][0]["coordinates"] = []
            else: broken[0]["edits"][0]["coordinates"][0]["value"] = 30
            with self.subTest(failure=failure), self.assertRaises(CLIENT.ValidationFailure):
                CLIENT.ordinary_poses(samples, broken)

    def test_native_geometry_rollback_requires_each_injected_boundary_and_restored_hashes(self):
        samples = self.geometry_fixture()
        plans = [CLIENT.ordinary_edit(x)[0] for x in samples]
        baseline = ["a" * 64, "b" * 64, "c" * 64]
        def history(generation, revision):
            return {"generation": generation, "revision": revision, "position": 1, "entryCount": 1,
                    "entriesSha256": "f" * 64, "documentBindingId": "doc", "managerBindingId": "manager",
                    "canUndo": True, "canRedo": False}
        for mode in ("success", "foreign", "no_change", "incomplete", "restoration", "receipt", "history", "timeout"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                report = {"runId": "task", "status": "PASS", "allSnapshotsRestored": True,
                          "beforeSnapshotHashes": baseline, "attempts": [{
                              "failureAfter": i, "verifiedSetterCount": i, "injected": True, "outcome": "ROLLED_BACK",
                              "transactionId": "tx", "diagnosticId": "diagnostic", "historyPositionBefore": 1,
                              "historyPositionAfter": 1, "restoredSnapshotHashes": baseline,
                              "changedSnapshotHashes": ["d" * 64] * i,
                              "publicHistoryBefore": history(1, 1), "publicHistoryAfter": history(1, 1),
                              "transactionHistoryBefore": history(41, 8), "transactionHistoryAfter": history(41, 8)} for i in range(1, 4)]}
                if mode == "foreign": report["runId"] = "foreign"
                if mode == "no_change": report["attempts"][1]["changedSnapshotHashes"][0] = baseline[0]
                if mode == "incomplete": report["attempts"].pop()
                if mode == "restoration": report["attempts"][2]["restoredSnapshotHashes"] = ["d" * 64] * 3
                if mode == "receipt": report["attempts"][1]["transactionId"] = None
                if mode == "history": report["attempts"][1]["transactionHistoryAfter"]["revision"] += 1
                if mode != "timeout": (root / "mcp-geometry-rollback-result.json").write_text(json.dumps(report))
                if mode == "success":
                    self.assertEqual(report, CLIENT.validate_native_geometry_rollback(root, "task", samples, plans))
                else:
                    with self.assertRaises(CLIENT.ValidationFailure):
                        CLIENT.validate_native_geometry_rollback(root, "task", samples, plans, timeout_seconds=.001)
                request = json.loads((root / "mcp-geometry-rollback-request.json").read_text())
                self.assertEqual("edit", request["targets"][0]["formGuid"])
                self.assertNotIn("edits", request["targets"][0])

    def test_head_qa_paces_transport_and_never_replays_rate_limited_write(self):
        client = object.__new__(CLIENT.PacedHeadQaClient)
        client.next_id = 1
        now = [0.0]
        sleeps = []
        def sleep(delay): sleeps.append(delay); now[0] += delay
        with mock.patch.object(CLIENT.time, "monotonic", side_effect=lambda: now[0]), \
                mock.patch.object(CLIENT.time, "sleep", side_effect=sleep), \
                mock.patch.object(CLIENT.McpClient, "_post", return_value=(429, {}, b"")) as post:
            with self.assertRaisesRegex(CLIENT.ValidationFailure, "HTTP status=429"):
                client.call("tools/call", {"name": "turboism.authoring.keyforms.write", "arguments": {}})
            self.assertEqual(1, post.call_count)
            client._post({"method": "read"})
            self.assertEqual([0.2], sleeps)
            now[0] += 1
            client._post({"method": "read"})
            self.assertEqual([0.2], sleeps)

    @staticmethod
    def head_qa_parameters():
        return [{"id": k, "minimumValue": -30 if k.startswith("ParamAngle") else 0,
                 "maximumValue": 30 if k.startswith("ParamAngle") else 1} for k in CLIENT.HEAD_POSE_IDS]

    def test_head_qa_has_exact_unique_xy_xyz_expression_and_wink_coverage(self):
        parameters = self.head_qa_parameters()
        poses = CLIENT.head_combination_poses(parameters)
        tuples = {tuple(v["value"] for v in p["values"]) for p in poses}
        self.assertEqual(28, len(tuples))
        for y in (-30, 0, 30):
            for x in (-30, 0, 30): self.assertIn((x, y, 0, 1, 1, 0), tuples)
        for z in (-30, 30):
            for y in (-30, 30):
                for x in (-30, 30): self.assertIn((x, y, z, 1, 1, 0), tuples)
        for x, y, z in ((0, 0, 0), (-30, -30, -30), (30, 30, 30)):
            for eye in (0, 1):
                for mouth in (0, 1): self.assertIn((x, y, z, eye, eye, mouth), tuples)
        self.assertIn((0, 0, 0, 0, 1, 0), tuples)
        self.assertIn((0, 0, 0, 1, 0, 0), tuples)
        with self.assertRaises(CLIENT.ValidationFailure): CLIENT.head_combination_poses(parameters + [parameters[0]])
        parameters[0]["maximumValue"] = 20
        with self.assertRaises(CLIENT.ValidationFailure): CLIENT.head_combination_poses(parameters)

    def test_head_qa_suite_releases_each_bounded_scan_and_never_replays_failure(self):
        poses = CLIENT.head_combination_poses(self.head_qa_parameters())
        state = {"documentId": "doc", "modelId": "model", "historyGeneration": 1}
        initial = {"state": state, "parameters": [{"id": "x", "value": 0}]}
        for mode in ("success", "failure", "restore"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                captured = []
                def capture(client, root, operation, requested, **options):
                    self.assertEqual({"width": 1024, "height": 1024, "crop": CLIENT.HEAD_CROP}, options)
                    self.assertEqual(1, len(requested))
                    captured.append(operation)
                    if mode == "failure" and len(captured) == 2: raise CLIENT.ValidationFailure("unknown scan outcome")
                    return {"frames": [{"label": requested[0]["label"], "captureId": operation, "state": state}]}
                final = {**initial, "parameters": []} if mode == "restore" else initial
                with mock.patch.object(CLIENT, "validate_ordinary_pose_capture", side_effect=capture), \
                        mock.patch.object(CLIENT, "tool_call", side_effect=[initial, final]), \
                        mock.patch.object(CLIENT, "history_snapshot", return_value={"position": 1}):
                    if mode == "success":
                        result = CLIENT.validate_head_pose_suite(None, Path(directory), "suite", poses)
                        self.assertEqual(28, len(result["frames"]))
                    else:
                        with self.assertRaises(CLIENT.ValidationFailure):
                            CLIENT.validate_head_pose_suite(None, Path(directory), "suite", poses)
                summary = json.loads((Path(directory) / "suite-suite.json").read_text())
                self.assertEqual("PASS" if mode == "success" else "FAIL", summary["status"])
                self.assertEqual(len(captured), len(set(captured)))
                self.assertEqual(2 if mode == "failure" else 28, len(captured))
                self.assertEqual("NOT_AUTOMATICALLY_ACCEPTED", summary["artQuality"])

    def test_pose_scan_validates_images_and_guarded_history_cleanup_without_replay(self):
        for mode in ("success", "timeout", "crop", "crop_mismatch", "image_index", "pose", "hash", "receipt", "unknown", "release"):
            with self.subTest(mode=mode):
                initial, image, pixels = self.render_fixture(512)
                poses = [{"label": "angle", "values": [{"parameterId": "Angle", "value": 1}]}]
                frame = {**image["structuredContent"], "index": 0, "label": "angle", "imageContentIndex": 1,
                         "captureId": "capture", "parameters": [{"id": "Angle", "value": 1}]}
                metadata = {"ok": True, "operationId": "scan", "outcome": "COMPLETED", "parametersRestored": True,
                            "initialState": initial["state"], "initialParameters": initial["parameters"],
                            "frames": [frame], "receipt": {"historyEntryId": "entry", "transactionId": "tx"}}
                if mode in ("crop", "crop_mismatch"):
                    frame.update(renderWidth=1024, renderHeight=1024, pixelCoordinates="origin-top-left,x-right,y-down",
                                 crop={"x": 257 if mode == "crop_mismatch" else 256, "y": 0, "width": 512, "height": 512})
                if mode == "image_index": frame["imageContentIndex"] = 0
                if mode == "pose": frame["parameters"][0]["value"] = 0
                if mode == "hash": frame["sha256"] = "b" * 64
                if mode == "receipt": metadata["receipt"]["historyEntryId"] = "foreign"
                if mode == "unknown": metadata.update(ok=False, outcome="OUTCOME_UNKNOWN", parametersRestored=False)
                result = {"structuredContent": metadata, "content": [
                    {"type": "text", "text": json.dumps(metadata)}, image["content"][1]]}
                client = mock.Mock()
                client.call.side_effect = [TimeoutError(), result] if mode == "timeout" else [result]
                position = [0]
                def history(_):
                    current = position[0]
                    position[0] = 1
                    return {"generation": 1, "revision": 2, "position": current,
                            "entries": [{"index": 0, "entryId": "entry", "transactionId": "tx"}]}
                undone = []
                def call(_, tool, args):
                    if tool == "turboism.render.state": return initial
                    if tool == "turboism.history.undo": undone.append(args); position[0] = 0; return {"ok": True}
                    if tool == "turboism.render.pose_grid.release":
                        if mode == "release": raise CLIENT.ValidationFailure("release failed")
                        return {"ok": True}
                    self.fail(tool)
                with tempfile.TemporaryDirectory() as directory, \
                        mock.patch.object(CLIENT, "history_snapshot", side_effect=history), \
                        mock.patch.object(CLIENT, "tool_call", side_effect=call):
                    root = Path(directory)
                    if mode in ("success", "timeout", "crop"):
                        CLIENT.validate_ordinary_pose_capture(client, root, "scan", poses, **({"width": 1024, "height": 1024, "crop": {"x": 256, "y": 0, "width": 512, "height": 512}} if mode in ("crop", "crop_mismatch") else {}))
                        self.assertEqual(pixels, (root / "scan-0.png").read_bytes())
                    else:
                        with self.assertRaises(CLIENT.ValidationFailure):
                            CLIENT.validate_ordinary_pose_capture(client, root, "scan", poses, **({"width": 1024, "height": 1024, "crop": {"x": 256, "y": 0, "width": 512, "height": 512}} if mode in ("crop", "crop_mismatch") else {}))
                    report = json.loads((root / "scan.json").read_text())
                    self.assertEqual("PASS" if mode in ("success", "timeout", "crop") else "FAIL", report["status"])
                    self.assertEqual(0 if mode in ("unknown", "receipt") else 1, len(undone))
                    names = [c.args[1]["name"] for c in client.call.call_args_list]
                    self.assertEqual(1, names.count("turboism.render.pose_grid"))
                    self.assertEqual(1 if mode == "timeout" else 0, names.count("turboism.render.pose_grid.result"))

    def test_native_render_persists_typed_png_only_after_state_and_hash_checks(self):
        before, result, pixels = self.render_fixture()
        client = mock.Mock()
        client.call.side_effect = [{"structuredContent": {"ok": False}, "content": []}, result]
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
                CLIENT, "tool_call", side_effect=[before, before]):
            root = Path(directory)
            metadata = CLIENT.validate_native_render(client, root)
            self.assertEqual(pixels, (root / "mcp-native-render.png").read_bytes())
            self.assertEqual(metadata, json.loads((root / "mcp-native-render.json").read_text()))
            self.assertEqual(2, client.call.call_count)

    def test_native_render_rejects_lost_type_corrupt_bytes_stale_acceptance_and_state_drift(self):
        for failure in ("type", "hash", "pose", "stale"):
            with self.subTest(failure=failure):
                before, result, _ = self.render_fixture()
                rejected = {"structuredContent": {"ok": False}, "content": []}
                after = dict(before)
                if failure == "type":
                    result["content"][1]["type"] = "text"
                elif failure == "hash":
                    result["content"][1]["data"] = base64.b64encode(b"corrupted PNG").decode()
                elif failure == "pose":
                    after["state"] = {**before["state"], "poseHash": "b" * 64}
                else:
                    rejected["structuredContent"]["ok"] = True
                client = mock.Mock()
                client.call.side_effect = [rejected, result]
                with tempfile.TemporaryDirectory() as directory, mock.patch.object(
                        CLIENT, "tool_call", side_effect=[before, after]):
                    with self.assertRaises(CLIENT.ValidationFailure):
                        CLIENT.validate_native_render(client, Path(directory))
                    self.assertEqual([], list(Path(directory).iterdir()))

    def test_native_roundtrip_requires_all_current_run_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            digest = "a" * 64
            evidence = ("runId=queue-ours\nstatus=PASS\nfixtureUnchanged=true\noriginalReopened=true\n"
                        "nativeLayerPixelUndoRedo=PASS\nsaveReopen=PASS\npersistenceOperationKinds=5\n"
                        f"savedFingerprint={digest}\nreopenedFingerprint={digest}\n"
                        f"rawPixelsBefore={digest}\nrawPixelsRestored={digest}\n")
            (root / "mcp-texture-roundtrip-result.properties").write_text(evidence)
            result = CLIENT.validate_native_texture_roundtrip(root, "queue-ours", timeout_seconds=0.1)
            self.assertEqual("PASS", result["saveReopen"])
            self.assertEqual("runId=queue-ours\nstatus=REQUESTED\n",
                             (root / "mcp-texture-roundtrip-request.properties").read_text())

    def test_native_roundtrip_rejects_foreign_run_and_missing_pixel_proof(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for text in ("runId=foreign\nstatus=PASS\n", "runId=queue-ours\nstatus=PASS\n"):
                (root / "mcp-texture-roundtrip-result.properties").write_text(text)
                with self.assertRaises(CLIENT.ValidationFailure):
                    CLIENT.validate_native_texture_roundtrip(root, "queue-ours", timeout_seconds=0.01)

    def test_texture_projection_compares_actual_content_not_state_tokens(self) -> None:
        content = {"rawImages": [{"id": "raw", "name": "Raw", "width": 16, "height": 16}],
                   "modelImageGroups": [], "textureAtlases": []}
        self.assertEqual(content, CLIENT.texture_content({**content, "stateToken": {"historyRevision": 99}}))
        with self.assertRaises(CLIENT.ValidationFailure):
            CLIENT.texture_content({"rawImages": [], "modelImageGroups": []})

    def test_texture_snapshot_uses_the_public_state_object_not_the_correlation_token(self) -> None:
        snapshot = {"ok": True, "operation": "list", "stateToken": "request-correlation",
                    "state": {"documentId": "doc", "modelId": "model", "historyGeneration": 1,
                              "historyRevision": 2},
                    "rawImages": [], "modelImageGroups": [], "textureAtlases": []}
        with mock.patch.object(CLIENT, "tool_call", return_value=snapshot):
            self.assertEqual(snapshot, CLIENT.texture_snapshot(object()))

    def test_texture_cycle_requires_one_undo_and_restores_actual_metadata(self) -> None:
        content = {"rawImages": [], "modelImageGroups": [], "textureAtlases": []}
        created = {"id": "native-atlas", "name": "Audit", "width": 64, "height": 64}
        state = {"position": 0}
        calls = []
        def history(_client):
            return {"availability": "AVAILABLE", "generation": 1, "revision": len(calls),
                    "position": state["position"], "entries": [{"entryId": "entry-1"}]}
        def read(_client):
            return {**content, "textureAtlases": [created] if state["position"] else [],
                    "stateToken": "correlation-only",
                    "state": {"documentId": "doc", "modelId": "model", "historyGeneration": 1,
                              "historyRevision": len(calls)}}
        def call(_client, name, args):
            calls.append(name)
            if name == "turboism.textures.write":
                self.assertEqual("doc", args["expectedState"]["documentId"])
                state["position"] = 1
                return {"ok": True, "outcome": "APPLIED", "retryable": False,
                        "receipt": "write-receipt", "id": "native-atlas"}
            state["position"] = 0 if name.endswith("undo") else 1
            return {"ok": True, "outcome": "MOVED"}
        checks = []
        with mock.patch.object(CLIENT, "history_snapshot", side_effect=history), \
             mock.patch.object(CLIENT, "texture_snapshot", side_effect=read), \
             mock.patch.object(CLIENT, "tool_call", side_effect=call):
            CLIENT.texture_write_cycle(object(), {"operation": "add_texture_atlas", "name": "Audit",
                "widthPixels": 64, "heightPixels": 64}, lambda before, after, receipt: checks.append(after))
        self.assertEqual(["turboism.textures.write", "turboism.history.undo",
                          "turboism.history.redo", "turboism.history.undo"], calls)
        self.assertEqual(0, state["position"])
        self.assertEqual([created], checks[0]["textureAtlases"])

    def test_texture_write_receipt_failure_still_attempts_guarded_undo(self) -> None:
        content = {"rawImages": [], "modelImageGroups": [], "textureAtlases": []}
        state = {"position": 0}
        calls = []
        def history(_client):
            return {"availability": "AVAILABLE", "generation": 1, "revision": len(calls),
                    "position": state["position"], "entries": [{"entryId": "entry-1"}]}
        def read(_client):
            return {**content, "stateToken": "correlation-only", "state": {"documentId": "doc", "modelId": "model",
                    "historyGeneration": 1, "historyRevision": len(calls)}}
        def call(_client, name, args):
            calls.append(name)
            if name == "turboism.textures.write":
                state["position"] = 1
                return {"ok": True, "outcome": "OUTCOME_UNKNOWN", "retryable": True}
            state["position"] = 0
            return {"ok": True, "outcome": "MOVED"}
        with mock.patch.object(CLIENT, "history_snapshot", side_effect=history), \
             mock.patch.object(CLIENT, "texture_snapshot", side_effect=read), \
             mock.patch.object(CLIENT, "tool_call", side_effect=call):
            with self.assertRaises(CLIENT.ValidationFailure):
                CLIENT.texture_write_cycle(object(), {"operation": "add_model_image_group", "name": "Audit"},
                                           lambda *args: None)
        self.assertEqual(["turboism.textures.write", "turboism.history.undo"], calls)
        self.assertEqual(0, state["position"])

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

    def test_mcp_runner_accepts_every_reviewed_editor_version(self) -> None:
        text = (REPO_ROOT / "scripts" / "preview" / "run-mcp-host-validation.sh") \
            .read_text(encoding="utf-8")
        reviewed = ["5000", "5001", "5002", "5003", "5004", "5005", "5006",
                    "5100", "5101", "5102", "5103",
                    "5200", "5201", "5202", "5203",
                    "5300", "5301", "5302", "5303", "5304"]
        self.assertIn('turboism_require_task_version mcp "$version"', text)
        manifest = json.loads((REPO_ROOT / "scripts/preview/host-validation-tasks.json").read_text())
        self.assertEqual(reviewed, manifest["tasks"]["mcp"]["versions"])
        self.assertIn('turboism_select_fixture "$version"', text)
        self.assertLess(text.index("TURBOISM_HOST_VALIDATION_FIXTURE_MCP:-"),
                        text.index("TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK:-"),
                        "the mcp override must be consulted before the clipmask fallback")
        self.assertLess(text.index("TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK:-"),
                        text.rindex('turboism_select_fixture "$version"'),
                        "the clipmask fallback must precede the versioned selection")
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
        token = "a" * 64
        client = CLIENT.McpClient("http://127.0.0.1:43123/mcp", CLIENT.PROTOCOL_VERSION, token)
        self.assertEqual("http://127.0.0.1:43123/mcp", client.endpoint)
        for endpoint in ("http://127.0.0.1:43123@evil.invalid/mcp", "http://localhost:43123/mcp",
                         "http://127.0.0.1/mcp", "http://127.0.0.1:43123/mcp?x=1",
                         "http://127.0.0.1:43123/mcp#fragment"):
            with self.subTest(endpoint=endpoint), self.assertRaises(CLIENT.ValidationFailure):
                CLIENT.McpClient(endpoint, CLIENT.PROTOCOL_VERSION, token)
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
