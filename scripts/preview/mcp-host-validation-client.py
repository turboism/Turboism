#!/usr/bin/env python3
"""Task-local bearer-token raw HTTP MCP client for exact-host validation."""

from __future__ import annotations

import base64
import hashlib
import struct
import subprocess
import json
import math
import os
import re
import signal
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable

PROTOCOL_VERSION = "2025-11-25"
# Exact reviewed Cubism version set admitted by the compatibility records. The
# capability ledger reports this full set for operations whose backing
# capabilities verify on every reviewed artifact.
def reviewed_versions():
    # A task-local copy is staged by the runner; repository invocations read the table directly.
    here = Path(__file__).resolve()
    source = here.with_name('reviewed-releases.json')
    if not source.is_file():
        source = here.parents[2] / 'compatibility/cubism/reviewed-releases.json'
    data = json.loads(source.read_text(encoding='utf-8'))
    if data.get('format') != 'turboism.reviewed-releases/v1':
        raise ValueError('invalid reviewed release table')
    versions = [r['version'] for r in data['releases']]
    if (not versions or any(not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]{2}', v) for v in versions)
            or versions != sorted(set(versions), key=lambda v: tuple(map(int, v.split('.'))))):
        raise ValueError('invalid reviewed version order')
    return versions


ALL_REVIEWED_VERSIONS = reviewed_versions()
# Exact catalog only: discovery does not establish execution or visual acceptance.
EXPECTED_TOOLS = {
    "turboism.assets.binding.bind",
    "turboism.assets.binding.read",
    "turboism.assets.binding.result",
    "turboism.assets.images.cancel",
    "turboism.assets.images.register",
    "turboism.assets.images.result",
    "turboism.assets.relations.read",
    "turboism.assets.sources.read",
    "turboism.atlas.apply",
    "turboism.atlas.cancel",
    "turboism.atlas.plan",
    "turboism.atlas.read",
    "turboism.atlas.result",
    "turboism.authoring.bindings.initialize",
    "turboism.authoring.bindings.initialize_plan",
    "turboism.authoring.bindings.initialize_result",
    "turboism.authoring.keyforms.preview",
    "turboism.authoring.keyforms.result",
    "turboism.authoring.keyforms.write",
    "turboism.authoring.masks.read",
    "turboism.authoring.masks.result",
    "turboism.authoring.masks.write",
    "turboism.authoring.meshes.create",
    "turboism.authoring.meshes.create_plan",
    "turboism.authoring.meshes.create_result",
    "turboism.authoring.meshes.evaluated.read",
    "turboism.authoring.meshes.read",
    "turboism.authoring.meshes.triangulate",
    "turboism.authoring.properties.read",
    "turboism.authoring.properties.result",
    "turboism.authoring.properties.write",
    "turboism.authoring.rotations.read",
    "turboism.authoring.warps.read",
    "turboism.capabilities.read",
    "turboism.documents.file_result",
    "turboism.documents.new_model",
    "turboism.documents.new_result",
    "turboism.documents.read",
    "turboism.documents.save_as",
    "turboism.documents.open",
    "turboism.editor_commands.execute",
    "turboism.export.moc.cancel",
    "turboism.export.moc.execute",
    "turboism.export.moc.plan",
    "turboism.export.moc.result",
    "turboism.files.grant_status",
    "turboism.files.inspect",
    "turboism.files.inspect_result",
    "turboism.files.request_grant",
    "turboism.files.revoke_grant",
    "turboism.glues.read",
    "turboism.glues.write",
    "turboism.history.read",
    "turboism.history.redo",
    "turboism.history.undo",
    "turboism.model_objects.apply",
    "turboism.parameter_bindings.apply",
    "turboism.parameters.apply",
    "turboism.parameters.structure.apply",
    "turboism.parameters.structure.plan",
    "turboism.parameters.structure.read",
    "turboism.parameters.structure.result",
    "turboism.render.capture",
    "turboism.render.pose_grid",
    "turboism.render.pose_grid.cancel",
    "turboism.render.pose_grid.release",
    "turboism.render.pose_grid.result",
    "turboism.render.pose_grid.status",
    "turboism.render.state",
    "turboism.rig.mesh.plan",
    "turboism.rig.rotation.plan",
    "turboism.rig.transfer_geometry.plan",
    "turboism.rig.warp.plan",
    "turboism.textures.read",
    "turboism.textures.write",
    "turboism.transaction.execute",
    "turboism.validation.geometry",
    "turboism.validation.keyform_continuity",
    "turboism.validation.raster",
    "turboism.validation.warp",
}

EXPECTED_RESOURCES = {
    "turboism://active/document",
    "turboism://active/model/overview",
    "turboism://active/model/hierarchy",
    "turboism://active/model/clip-masks",
    "turboism://active/model/parameters",
    "turboism://active/model/statistics",
    "turboism://active/model/textures",
    "turboism://active/model/parameter-bindings",
    "turboism://active/document/history",
    "turboism://environment/cubism-core",
    "turboism://environment/workspace",
    "turboism://environment/workspace/layout",
    "turboism://environment/diagnostics",
    "turboism://environment/runtime-diagnostics",
    "turboism://host/editor-commands",
}
EXPECTED_TEMPLATES = {
    "turboism://active/model/parameters/{parameterId}",
    "turboism://active/model/parameters/{parameterId}/bindings",
}
EXPECTED_PROMPTS = {
    "inspect_active_document",
    "edit_model_structure",
    "normalize_parameters",
    "repair_parameter_bindings",
    "recover_document_history",
    "run_editor_command",
    "diagnose_environment",
    "inspect_model_diagnostics",
}


class ValidationFailure(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str,
                         headers: Any, newurl: str) -> None:
        raise ValidationFailure("MCP validation must not follow HTTP redirects")


class McpClient:
    def __init__(self, endpoint: str, advertised_version: str, token: str) -> None:
        parsed = urllib.parse.urlsplit(endpoint)
        if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1"
                or parsed.username is not None or parsed.password is not None
                or parsed.path != "/mcp" or parsed.query or parsed.fragment
                or parsed.port is None or not 1 <= parsed.port <= 65535):
            raise ValidationFailure("connection endpoint is not numeric loopback /mcp")
        # Never send local model data through an inherited HTTP proxy or redirect.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        if advertised_version != PROTOCOL_VERSION:
            raise ValidationFailure("connection protocol version is unexpected")
        self.endpoint = endpoint
        self.protocol_version = advertised_version
        self.token = token
        self.session_id: str | None = None
        self.next_id = 1

    def initialize(self) -> dict[str, Any]:
        status, headers, body = self._post(
            {
                "jsonrpc": "2.0",
                "id": self._id(),
                "method": "initialize",
                "params": {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": {"name": "turboism-exact-host-validation", "version": "1"},
                },
            },
            include_session=False,
            include_version=False,
        )
        require(status == 200, f"initialize HTTP status={status}")
        self.session_id = headers.get("mcp-session-id")
        require(bool(self.session_id), "initialize omitted MCP-Session-Id")
        result = rpc_result(body)
        require(result.get("protocolVersion") == PROTOCOL_VERSION, "protocol negotiation mismatch")
        capabilities = object_value(result.get("capabilities"), "capabilities")
        require(set(capabilities) >= {"tools", "resources", "prompts"}, "capabilities incomplete")
        status, _, notification_body = self._post(
            {"jsonrpc": "2.0", "method": "notifications/initialized"}
        )
        require(status == 202 and not notification_body, "initialized notification failed")
        return result

    def call(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        request: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": self._id(),
            "method": method,
        }
        if params is not None:
            request["params"] = params
        status, _, body = self._post(request)
        require(status == 200, f"{method} HTTP status={status}")
        return rpc_result(body)

    def delete_session(self) -> None:
        if self.session_id is None:
            return
        request = urllib.request.Request(self.endpoint, method="DELETE")
        request.add_header("MCP-Session-Id", self.session_id)
        request.add_header("Authorization", "Bearer " + self.token)
        try:
            with self.opener.open(request, timeout=15) as response:
                require(response.status == 200, f"DELETE session HTTP status={response.status}")
        except urllib.error.HTTPError as failure:
            raise ValidationFailure(f"DELETE session HTTP status={failure.code}") from failure
        finally:
            self.session_id = None

    def _id(self) -> int:
        value = self.next_id
        self.next_id += 1
        return value

    def _post(
        self,
        message: dict[str, Any],
        *,
        include_session: bool = True,
        include_version: bool = True,
    ) -> tuple[int, dict[str, str], bytes]:
        request = urllib.request.Request(
            self.endpoint,
            data=json.dumps(message, separators=(",", ":")).encode("utf-8"),
            method="POST",
        )
        request.add_header("Accept", "application/json, text/event-stream")
        request.add_header("Content-Type", "application/json")
        request.add_header("Authorization", "Bearer " + self.token)
        if include_version:
            request.add_header("MCP-Protocol-Version", self.protocol_version)
        if include_session and self.session_id is not None:
            request.add_header("MCP-Session-Id", self.session_id)
        try:
            with self.opener.open(request, timeout=45) as response:
                return response.status, lower_headers(response.headers.items()), response.read()
        except urllib.error.HTTPError as failure:
            return failure.code, lower_headers(failure.headers.items()), failure.read()


class ReadOnlyInspectionClient(McpClient):
    """Test-lane allowlist: reject writes before any HTTP request is constructed."""
    TOOLS = frozenset({"turboism.capabilities.read", "turboism.history.read", "turboism.render.state",
        "turboism.render.capture", "turboism.authoring.meshes.read", "turboism.authoring.warps.read",
        "turboism.authoring.rotations.read"})
    METHODS = frozenset({"tools/list", "resources/list", "resources/templates/list", "prompts/list"})

    def call(self, method, params=None):
        allowed = method in self.METHODS
        if method == "tools/call":
            allowed = isinstance(params, dict) and params.get("name") in self.TOOLS
        elif method == "resources/read":
            allowed = isinstance(params, dict) and params.get("uri") in EXPECTED_RESOURCES
        elif method == "prompts/get":
            allowed = isinstance(params, dict) and params.get("name") in {
                "inspect_active_document", "diagnose_environment", "inspect_model_diagnostics"}
        require(allowed, "Read-only inspection rejected operation")
        return super().call(method, params)


class HeadEditingClient(ReadOnlyInspectionClient):
    """Closed test lane: geometry and pose/history only; no file or structure mutation."""
    TOOLS = ReadOnlyInspectionClient.TOOLS | frozenset({
        "turboism.authoring.keyforms.preview", "turboism.authoring.keyforms.write", "turboism.authoring.keyforms.result",
        "turboism.history.undo", "turboism.history.redo", "turboism.render.pose_grid",
        "turboism.render.pose_grid.result", "turboism.render.pose_grid.release"})


class InjectedWriteResponseLoss(ConnectionError):
    """Test-only response discarded after HTTP completion, before JSON-RPC decoding."""


class ResponseLossHeadEditingClient(HeadEditingClient):
    """Drop exactly one keyform-write response; preserve the same live MCP session."""
    injected_response_losses = 0

    def _post(self, message, **options):
        response = super()._post(message, **options)
        params = message.get("params", {})
        if (message.get("method") == "tools/call" and isinstance(params, dict)
                and params.get("name") == "turboism.authoring.keyforms.write"
                and self.injected_response_losses == 0):
            self.injected_response_losses += 1
            # Do not inspect, retain, or return the write's status/body to the workflow.
            # This models loss at the receiving client boundary, not server crash/rollback.
            raise InjectedWriteResponseLoss("Injected keyform response loss before RPC decode")
        return response


def reconcile_injected_keyform_write(client, request, evidence):
    loss = {"boundary": "AFTER_HTTP_READ_BEFORE_JSON_RPC_DECODE", "writeAttempts": 1,
            "responseDiscarded": False, "resultQueries": 0, "writeReplayed": False}
    evidence["responseLoss"] = loss
    try:
        tool_result(client, "turboism.authoring.keyforms.write", request)
    except InjectedWriteResponseLoss:
        loss["responseDiscarded"] = True
    else:
        raise ValidationFailure("Expected keyform response loss was not injected")
    # No replay, new operation ID, reconnect or inference from current geometry.
    # NOT_FOUND/unknown does not authorize cleanup; the caller has no receipt yet.
    loss["resultQueries"] += 1
    result = tool_result(client, "turboism.authoring.keyforms.result", {"operationId": request["operationId"]})
    require(result.get("operationId") == request["operationId"] and result.get("ok") is True
            and result.get("outcome") == "APPLIED" and isinstance(result.get("receipt"), dict),
            "Lost write response could not be reconciled; do not replay or clean up")
    loss["resultQueries"] += 1
    repeated = tool_result(client, "turboism.authoring.keyforms.result", {"operationId": request["operationId"]})
    require(repeated == result, "Retained keyform result changed across read-only queries")
    loss["retainedResultStable"] = True
    return result


class PacedHeadQaClient(HeadEditingClient):
    """Stay below the reviewed wrapper's existing 600/minute budget; never replay a request."""
    def _pace(self):
        previous = getattr(self, "_previous_request", None)
        now = time.monotonic()
        if previous is not None:
            delay = 0.2 - (now - previous)
            if delay > 0: time.sleep(delay)
        self._previous_request = time.monotonic()

    def _post(self, message, **options):
        self._pace()
        return super()._post(message, **options)

    def delete_session(self):
        if self.session_id is not None: self._pace()
        return super().delete_session()


class HeadPersistenceClient(HeadEditingClient):
    """Head rig file validation: grant-bound CMO3 commands only, no binding initialization."""
    TOOLS = HeadEditingClient.TOOLS | frozenset({"turboism.files.request_grant", "turboism.files.grant_status",
        "turboism.documents.read", "turboism.documents.open", "turboism.documents.save_as", "turboism.documents.file_result"})


class NativeObservationProxy:
    """Test-only MCP projection. No file paths, history/pose writes or generic dispatch."""
    TOOLS = frozenset({"turboism.render.state", "turboism.render.capture"})
    URIS = frozenset({"turboism://active/document", "turboism://active/model/overview"})

    def __init__(self, client, state_root, task_id, require_vision_canary=False, expected_harness="claude"):
        self.client, self.state_root = client, state_root
        self.require_vision_canary = require_vision_canary
        self.expected_harness = expected_harness
        self.trace = {"runId": task_id, "modelMutation": "NONE", "calls": [], "images": [], "rejected": 0}
        self.initialized = False

    def dispatch(self, method, params):
        require(len(self.trace["calls"]) < (64 if isinstance(self, NativeEditingProxy) else 32), "Native observer request budget exceeded")
        if params is None and method in {"tools/list", "resources/list", "resources/templates/list", "notifications/initialized"}:
            params = {}
        require(isinstance(params, dict) and len(params) <= 64, "Invalid observer parameters")
        # MCP envelope extensions are advisory: project only operation fields and never forward extensions.
        fields = {"initialize": {"protocolVersion", "capabilities", "clientInfo"},
                  "notifications/initialized": set(), "tools/list": {"cursor"}, "resources/list": {"cursor"},
                  "resources/templates/list": {"cursor"}, "resources/read": {"uri"}, "tools/call": {"name", "arguments"}}
        kept = fields.get(method, set())
        ignored = len(set(params) - kept)
        params = {key: value for key, value in params.items() if key in kept}
        self.trace["calls"].append({"method": method, "ignoredProtocolFields": ignored})
        if method == "tools/call" and "arguments" not in params:
            params["arguments"] = {}
        if method == "initialize":
            require(not self.initialized, "Observer already initialized")
            self.initialized = True
            result = self.client.initialize()
            return {**result, "capabilities": {"tools": {}, "resources": {}}}
        require(self.initialized, "Observer not initialized")
        if method == "notifications/initialized": return None
        if method == "tools/list":
            require(set(params) <= {"cursor"} and params.get("cursor") is None, "Observer catalog does not accept cursor")
            return {"tools": [t for t in collect_pages(self.client, "tools/list", "tools") if t["name"] in self.TOOLS]}
        if method == "resources/list":
            require(set(params) <= {"cursor"} and params.get("cursor") is None, "Observer catalog does not accept cursor")
            return {"resources": [r for r in collect_pages(self.client, "resources/list", "resources") if r["uri"] in self.URIS]}
        if method == "resources/templates/list":
            require(set(params) <= {"cursor"} and params.get("cursor") is None, "Observer templates do not accept cursor")
            return {"resourceTemplates": []}
        if method in {"resources/read", "tools/call"} and self.require_vision_canary:
            ready = self.state_root / "native-observe-vision-ready.json"
            require(ready.is_file() and not ready.is_symlink() and ready.stat().st_size <= 4096, "Image canary not verified")
            gate = json.loads(ready.read_text())
            require(set(gate) == {"schemaVersion", "harness", "visionCanaryPassed", "model"}
                    and gate["schemaVersion"] == 1 and gate["harness"] == self.expected_harness
                    and gate["visionCanaryPassed"] is True and isinstance(gate["model"], str) and gate["model"]
                    and (self.expected_harness != "codex" or gate["model"] == "gpt-6.1-sol"),
                    "Image canary not verified")
            self.trace["visionCanaryGateVerified"] = True
        if method == "resources/read":
            require(set(params) == {"uri"} and params["uri"] in self.URIS, "Observer resource rejected")
            self.trace["calls"][-1]["uri"] = params["uri"]
            return self.client.call(method, params)
        require(method == "tools/call" and set(params) == {"name", "arguments"}
                and params["name"] in self.TOOLS, "Observer operation rejected")
        args = params["arguments"]
        require(isinstance(args, dict), "Invalid observer tool arguments")
        self.trace["calls"][-1]["tool"] = params["name"]
        if params["name"] not in NativeObservationProxy.TOOLS:
            return self.edit_dispatch(params["name"], args)
        if params["name"] == "turboism.render.state":
            require(not args, "Observer state arguments rejected")
        else:
            require(set(args) == {"expectedState", "width", "height"}
                    and type(args["width"]) is int and type(args["height"]) is int
                    and args["width"] == args["height"] == 512
                    and len(self.trace["images"]) < 2, "Observer capture budget/arguments rejected")
        result = self.client.call(method, params)
        if params["name"] == "turboism.render.capture":
            projection = object_value(result.get("structuredContent"), "observer capture result")
            require(projection.get("ok") is True, "Native observer capture failed")
            blocks = [b for b in result.get("content", []) if b.get("type") == "image"]
            require(len(blocks) == 1 and blocks[0].get("mimeType") == "image/png", "Observer missing typed PNG")
            data = base64.b64decode(blocks[0]["data"], validate=True)
            require(len(data) <= 1024 * 1024, "Observer PNG exceeds budget")
            require(data[:16] == b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR" and len(data) >= 33
                    and struct.unpack(">II", data[16:24]) == (512, 512)
                    and projection.get("width") == projection.get("height") == 512
                    and projection.get("byteLength") == len(data)
                    and projection.get("state") == args["expectedState"], "Observer PNG/state metadata mismatch")
            digest = hashlib.sha256(data).hexdigest()
            require(digest == projection.get("sha256"), "Observer PNG hash mismatch")
            self.trace["images"].append({"sha256": digest, "captureId": projection["captureId"], "state": projection["state"]})
            (self.state_root / f"mcp-native-observe-{len(self.trace['images'])}.png").write_bytes(data)
        return result


class NativeEditingProxy(NativeObservationProxy):
    """One model-authored preview/write, scoped to an observed independent synthetic mesh form."""
    TOOLS = NativeObservationProxy.TOOLS | frozenset({"turboism.authoring.meshes.read",
        "turboism.authoring.keyforms.preview", "turboism.authoring.keyforms.write", "turboism.authoring.keyforms.result"})

    def __init__(self, client, state_root, task_id, scope):
        super().__init__(client, state_root, task_id, True, "codex")
        self.scope, self.snapshots, self.preview, self.expected = scope, {}, None, None
        self.trace["modelMutation"] = "ONE_BOUNDED_ORDINARY_MESH_FORM"

    def edit_dispatch(self, name, args):
        if name == "turboism.authoring.meshes.read":
            require(set(args) <= {"documentId", "modelId", "objectId", "snapshotId", "offset", "limit"}
                    and all(args.get(k) == self.scope[k] for k in ("documentId", "modelId", "objectId")), "Edit read scope rejected")
            result = self.client.call("tools/call", {"name": name, "arguments": args})
            data = object_value(result.get("structuredContent"), "edit read")
            require(data.get("ok") is True and data.get("totalRows", 100001) <= 2048, "Edit read unavailable/over budget")
            token = data["snapshotId"]
            cached = self.snapshots.setdefault(token, {"data": data, "rows": {}})
            require(data["state"] == cached["data"]["state"] and data["totalRows"] == cached["data"]["totalRows"], "Mixed edit snapshot pages")
            for index, row in enumerate(data["rows"], data["offset"]):
                require(index not in cached["rows"] or cached["rows"][index] == row, "Edit snapshot page changed")
                cached["rows"][index] = row
            if len(cached["rows"]) == data["totalRows"] and self.expected is not None and self.trace.get("write", {}).get("outcome") == "APPLIED":
                actual = [cached["rows"][i] for i in range(data["totalRows"])]
                if stored_rows_equal(actual, self.expected): self.trace["agentReadbackVerified"] = True
            return result
        if name == "turboism.authoring.keyforms.result":
            require(args == {"operationId": self.scope["operationId"]}, "Edit result scope rejected")
            result = self.client.call("tools/call", {"name": name, "arguments": args})
            data = object_value(result.get("structuredContent"), "edit retained result")
            if self.trace.get("writeAttempted") and data.get("operationId") == self.scope["operationId"] and data.get("outcome") == "APPLIED" and data.get("receipt"):
                self.trace["write"] = data
            return result
        require(name in {"turboism.authoring.keyforms.preview", "turboism.authoring.keyforms.write"}, "Edit operation rejected")
        require(set(args) == {"documentId", "modelId", "operationId", "targets"}
                and all(args.get(k) == self.scope[k] for k in ("documentId", "modelId", "operationId")), "Edit request identity rejected")
        targets = args["targets"]
        require(isinstance(targets, list) and len(targets) == 1, "Edit permits one target")
        target = targets[0]
        require(set(target) == {"kind", "objectId", "snapshotId", "edits"}
                and target["kind"] == "art_mesh" and target["objectId"] == self.scope["objectId"], "Edit target rejected")
        cached = self.snapshots.get(target["snapshotId"])
        require(cached is not None and len(cached["rows"]) == cached["data"]["totalRows"], "Read complete stored snapshot before planning")
        rows = [cached["rows"][i] for i in range(cached["data"]["totalRows"])]
        require(isinstance(target["edits"], list) and len(target["edits"]) == 1, "Edit permits one form")
        edit = target["edits"][0]
        require(set(edit) == {"formGuid", "coordinates", "positions"} and edit["formGuid"] == self.scope["formGuid"]
                and edit["coordinates"] == self.scope["coordinates"], "Edit form/tuple rejected")
        require(any(r.get("kind") == "ordinary_write_eligibility" and r.get("formGuid") == edit["formGuid"]
                    and r.get("snapshotEligible") is True for r in rows), "Edit form not independently eligible")
        points = sorted([r for r in rows if r.get("kind") == "form_point" and r.get("formGuid") == edit["formGuid"]], key=lambda r:r["index"])
        require(1 < len(points) <= 128 and [r["index"] for r in points] == list(range(len(points)))
                and isinstance(edit["positions"], list) and len(edit["positions"]) == len(points), "Edit point count rejected")
        span = max(r["x"] for r in points) - min(r["x"] for r in points)
        changed = []
        expected = json.loads(json.dumps(rows))
        for index, (old, new) in enumerate(zip(points, edit["positions"])):
            require(isinstance(new, dict) and set(new) == {"x", "y"}, "Edit point fields rejected")
            x, y = finite_number(new["x"], "edit x"), finite_number(new["y"], "edit y")
            require(stored_rows_equal([{"y":old["y"]}], [{"y":y}]), "Only x may change")
            if not stored_rows_equal([{"x":old["x"]}], [{"x":x}]):
                require(0 < x-old["x"] <= min(4, span/32), "Edit exceeds small positive x budget")
                changed.append(index)
                next(r for r in expected if r.get("kind") == "form_point" and r.get("formGuid") == edit["formGuid"] and r["index"] == index)["x"] = x
        require(len(changed) == 1, "Edit requires exactly one changed vertex")
        if name.endswith(".write"):
            require(self.preview == args and not self.trace.get("writeAttempted"), "Exact successful preview required; no write replay")
            self.trace["writeAttempted"] = True
            publish_atomic(self.state_root / "mcp-native-observe-proxy.json", json.dumps(self.trace))
        result = self.client.call("tools/call", {"name": name, "arguments": args})
        data = object_value(result.get("structuredContent"), "edit result")
        if name.endswith(".preview"):
            require(data.get("ok") is True and data.get("applied") is False, "Edit preview failed")
            self.preview, self.expected = json.loads(json.dumps(args)), expected
            publish_atomic(self.state_root / "mcp-native-edit-plan-private.json", json.dumps({"before":rows,"expected":expected,"request":args}))
            self.trace["previewVerified"] = True
        else:
            self.trace["write"] = data
            require(data.get("outcome") == "APPLIED" and data.get("receipt"), "Edit did not return confirmed commit")
        return result


def native_observation_stdio(home, task_id):
    state = home / "state"
    connection = await_connection(state / "dev.turboism.plugin.mcp/mcp-connection.json", 30)
    token = (state / "dev.turboism.plugin.mcp/mcp.token").read_text().strip()
    require(bool(re.fullmatch(r"[0-9a-f]{64}", token)), "Malformed observer token")
    client = McpClient(connection["endpoint"], connection["protocolVersion"], token)
    runtime = json.loads((home / "native-observe/runtime.json").read_text())
    editing = runtime.get("workflow") == "keyform-edit"
    proxy = NativeEditingProxy(client, state, task_id, json.loads((state / "mcp-native-edit-scope.json").read_text())) if editing else NativeObservationProxy(client, state, task_id, require_vision_canary=runtime.get("harness") in {"claude", "codex"},
                                   expected_harness=runtime.get("harness"))
    try:
        while True:
            line = sys.stdin.buffer.readline(65537)
            if not line: break
            require(len(line) <= 65536 and line.endswith(b"\n"), "Observer input budget exceeded")
            message = json.loads(line)
            request_id = message.get("id")
            try:
                require(isinstance(message, dict) and message.get("jsonrpc") == "2.0", "Invalid observer message")
                result = proxy.dispatch(message.get("method"), message.get("params", {}))
                reply = {"jsonrpc": "2.0", "id": request_id, "result": result}
            except Exception as failure:
                proxy.trace["rejected"] += 1
                proxy.trace["lastRejectionType"] = type(failure).__name__
                reply = {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32602, "message": "Native observer request failed; no automatic replay"}}
            publish_atomic(state / "mcp-native-observe-proxy.json", json.dumps(proxy.trace, indent=2) + "\n")
            if request_id is not None:
                print(json.dumps(reply, separators=(",", ":")), flush=True)
    finally:
        client.delete_session()


def validate_native_observer(home, task_id, client, *, editing=False):
    runtime_path = home / "native-observe/runtime.json"
    runtime = json.loads(runtime_path.read_text())
    before = tool_call(client, "turboism.render.state", {})
    process = subprocess.Popen([runtime["javaExecutable"], "-Djava.awt.headless=true", "-cp",
        str(home / "native-observe/probe.jar"), "dev.turboism.plugin.acp.NativeObserve",
        str(runtime_path), str(home), task_id, str(Path(sys.executable).resolve()), str(Path(__file__).resolve())],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        process.wait(timeout=300)
    finally:
        # Only this sidecar's newly created process group; never the shared worker or host.
        # Closing a harness is not evidence of cancelling any host operation.
        try: os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError: pass
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            process.wait(timeout=5)
    if not editing:
        require(before == tool_call(client, "turboism.render.state", {}), "Native observer changed render state")
    require(process.returncode == 0, "Real native observer did not complete; inspect private typed report")
    report = json.loads((home / "state/mcp-native-observe-private.json").read_text())
    trace = json.loads((home / "state/mcp-native-observe-proxy.json").read_text())
    require(report.get("status") == "CAPTURE_DELIVERED_REVIEW_REQUIRED" and trace["rejected"] == 0, "Native observer did not deliver an image")
    if runtime.get("harness") in {"claude", "codex"}:
        require(report.get("visionCanaryPassed") is True
                and trace.get("visionCanaryGateVerified") is True, "Native image canary or tool isolation not verified")
    if runtime.get("harness") == "claude":
        require(report.get("systemToolsVerified") is True, "Claude tool catalog not verified")
    if runtime.get("harness") == "codex":
        require(report.get("model") == "gpt-6.1-sol" and report.get("runtimeDeclaredImageInput") is True
                and report.get("configurationIsolationVerified") is True, "Codex model/config isolation not verified")
    require(trace["images"] and [i["sha256"] for i in report["images"]] == [i["sha256"] for i in trace["images"]], "ACP/proxy image mismatch")
    require(any(c.get("uri") == "turboism://active/document" for c in trace["calls"]), "Native agent did not read the active document")
    require(any(c["method"] == "resources/list" for c in trace["calls"]), "Native agent did not discover resources")
    return report


def validate_native_editor(home, task_id, client, samples):
    state_root = home / "state"
    before = tool_call(client, "turboism.render.state", {})
    mesh = next(x for x in samples if x["kind"] == "art_mesh")
    target, _ = ordinary_edit(mesh)  # Scope only; this generated edit is never sent to the agent or writer.
    chosen = target["edits"][0]
    poses = ordinary_poses([mesh], [target])
    scope = {"documentId": before["state"]["documentId"], "modelId": before["state"]["modelId"],
             "objectId": mesh["objectId"], "formGuid": chosen["formGuid"], "coordinates":chosen["coordinates"],
             "operationId":task_id + "-agent-one"}
    publish_atomic(state_root / "mcp-native-edit-scope.json", json.dumps(scope))
    evidence = {"runId":task_id,"status":"FAIL","restored":False,"model":"gpt-6.1-sol"}
    receipt = None
    def reread():
        binding = tool_call(client, "turboism.render.state", {})["state"]
        return [read_stored_object(client, x["kind"], x["objectId"], binding) for x in samples]
    def verify_rows(expected_mesh):
        actual = reread()
        require(all(all(a.get(k) == b.get(k) for k in ("kind", "objectId", "sourceGuid"))
                    and stored_rows_equal(a["rows"], expected_mesh if a["kind"] == "art_mesh" else b["rows"])
                    for a,b in zip(actual,samples)), "Mesh or non-target stored forms differ")
    def undo_known():
        history = history_snapshot(client)
        args = history_guard_arguments(history, undo=True)
        require(history["position"] == receipt["historyAfter"]["position"]
                and args["expectedTopEntryId"] == receipt["historyEntryId"]
                and args.get("expectedTransactionId") == receipt["transactionId"], "Edit cleanup history identity mismatch")
        tool_call(client,"turboism.history.undo",args)
    try:
        evidence["renderBefore"] = validate_ordinary_pose_capture(client,state_root,task_id+"-before",poses)
        report = validate_native_observer(home,task_id,client,editing=True)
        trace = json.loads((state_root/"mcp-native-observe-proxy.json").read_text())
        receipt = trace.get("write",{}).get("receipt")
        require(trace.get("previewVerified") and trace.get("agentReadbackVerified") and receipt, "Agent preview/write/readback incomplete")
        plan = json.loads((state_root/"mcp-native-edit-plan-private.json").read_text())
        require(stored_rows_equal(plan["before"],mesh["rows"]),"Agent read a changed baseline")
        verify_rows(plan["expected"])
        evidence["renderAfter"] = validate_ordinary_pose_capture(client,state_root,task_id+"-after",poses)
        require(evidence["renderBefore"]["frames"][0]["sha256"] != evidence["renderAfter"]["frames"][0]["sha256"], "Edited target pose has no pixel change")
        undo_known();verify_rows(mesh["rows"]);evidence["undoVerified"] = True
        tool_call(client,"turboism.history.redo",history_guard_arguments(history_snapshot(client),undo=False))
        verify_rows(plan["expected"]);evidence["redoVerified"] = True
        evidence["status"] = "PASS"
    finally:
        try:
            trace_path = state_root/"mcp-native-observe-proxy.json"
            trace = json.loads(trace_path.read_text()) if trace_path.exists() else {}
            receipt = trace.get("write",{}).get("receipt") or receipt
            if receipt:
                history = history_snapshot(client)
                if history["position"] == receipt["historyAfter"]["position"]: undo_known()
                require(history_snapshot(client)["position"] == receipt["historyBefore"]["position"], "Edit final history position mismatch")
                verify_rows(mesh["rows"])
                final = tool_call(client,"turboism.render.state",{})
                require(final["parameters"] == before["parameters"] and all(final["state"][k] == before["state"][k]
                        for k in ("documentId","modelId","historyGeneration")), "Edit final pose/identity changed")
                evidence["restored"] = True
            elif trace.get("writeAttempted"):
                evidence["outcomeUnknown"] = True
        finally:
            publish_atomic(state_root/"mcp-native-edit-verification.json",json.dumps(evidence,indent=2))
    require(evidence["status"] == "PASS" and evidence["restored"],"Native edit not accepted/restored")
    return evidence


def capture_read_only_inventory(client, state_root, expected_state):
    """Private semantic inputs, with bounded, repeated reads; no inferred labels or GUIDs."""
    uris = ("turboism://active/model/hierarchy", "turboism://active/model/parameters",
            "turboism://active/model/parameter-bindings")
    before = tool_call(client, "turboism.render.state", {})
    require(before.get("state") == expected_state, "Inventory started with stale render state")
    resources = {uri: resource_json(client, uri) for uri in uris}
    encoded = json.dumps(resources, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    require(len(encoded) <= 4 * 1024 * 1024, "Inventory byte budget exceeded")
    assert_no_absolute_paths(resources)
    hierarchy = resources[uris[0]]
    require(hierarchy.get("availability") == "AVAILABLE", "Inventory hierarchy unavailable")
    pending = [object_value(hierarchy.get("root"), "inventory root")]
    counts = {}
    node_count = 0
    while pending:
        node = object_value(pending.pop(), "inventory node")
        node_count += 1
        require(node_count <= 100000, "Inventory hierarchy node budget exceeded")
        kind = text_value(node.get("kind"), "inventory node kind")
        counts[kind] = counts.get(kind, 0) + 1
        pending.extend(array_value(node.get("children"), "inventory node children"))
    parameters = array_value(resources[uris[1]].get("parameters"), "inventory parameters")
    bindings = array_value(resources[uris[2]].get("parameterBindings"), "inventory parameter bindings")
    require(len(parameters) <= 10000 and len(bindings) <= 10000, "Inventory parameter budget exceeded")
    require(resources == {uri: resource_json(client, uri) for uri in uris},
            "Inventory resources changed between reads")
    require(before == tool_call(client, "turboism.render.state", {}), "Inventory changed state or pose")
    summary = {"status": "PASS", "state": expected_state, "resourceCount": len(uris),
        "nodeCount": node_count, "nodeKinds": counts, "parameterCount": len(parameters),
        "parameterBindingGroupCount": len(bindings), "canonicalResourcesBytes": len(encoded),
        "canonicalResourcesSha256": hashlib.sha256(encoded).hexdigest(),
        "semanticMapping": "NOT_VERIFIED", "identityLimit": "Display IDs are not native GUIDs"}
    # Full resources remain private to the task, never part of the public audit summary.
    publish_atomic(state_root / "mcp-read-only-inventory-private.json", encoded.decode() + "\n")
    publish_atomic(state_root / "mcp-read-only-inventory.json", json.dumps(summary, indent=2) + "\n")
    return summary


# A test-only candidate profile, never an authorization or a general semantic classifier.
HEAD_CANDIDATE_PROFILE = (
    ("face_rotation", "ROTATION_DEFORMER", "Face Rotation", "rotation_deformer", ("ParamAngleZ",), None),
    ("face_warp", "WARP_DEFORMER", "Face Warp", "warp_deformer", ("ParamAngleX", "ParamAngleY"), "face_rotation"),
    ("face_contour", "ART_MESH", "Contour", "art_mesh", ("ParamAngleX", "ParamAngleY"), "face_warp"),
)


def select_head_candidates(resources):
    hierarchy = resources["turboism://active/model/hierarchy"]
    require(hierarchy.get("availability") == "AVAILABLE", "Head candidate hierarchy unavailable")
    pending = [object_value(hierarchy.get("root"), "head candidate root")]
    nodes = []
    while pending:
        node = object_value(pending.pop(), "head candidate node")
        nodes.append(node)
        require(len(nodes) <= 100000, "Head candidate node budget exceeded")
        pending.extend(array_value(node.get("children"), "head candidate children"))
    parameters = array_value(resources["turboism://active/model/parameters"].get("parameters"), "head parameters")
    groups = array_value(resources["turboism://active/model/parameter-bindings"].get("parameterBindings"), "head bindings")
    selected = {}
    for role, kind, name, wire_kind, axes, parent_role in HEAD_CANDIDATE_PROFILE:
        candidates = [n for n in nodes if n.get("kind") == kind and n.get("name") == name]
        if not candidates:
            return []  # This fixture does not match the reviewed candidate profile.
        require(len(candidates) == 1, "Head candidate name is ambiguous")
        node = candidates[0]
        require(sum(n.get("kind") == kind and n.get("id") == node.get("id") for n in nodes) == 1,
                "Head candidate object identity is ambiguous")
        if parent_role:
            require(node.get("parentId") == selected[parent_role]["objectId"], "Head candidate parent mismatch")
        bindings = {}
        for group in groups:
            for binding in array_value(group.get("bindings"), "head binding group"):
                target = object_value(binding.get("target"), "head binding target")
                if target.get("type") == wire_kind and target.get("id") == node.get("id"):
                    parameter_id = text_value(group.get("parameterId"), "head binding parameter")
                    require(binding.get("parameterId") == parameter_id and binding.get("family") == "keyform_grid",
                            "Head candidate binding family or parameter mismatch")
                    require(parameter_id not in bindings, "Head candidate duplicate parameter binding")
                    bindings[parameter_id] = [point.get("value") for point in binding["points"]]
        require(set(bindings) == set(axes) and all(sorted(v) == [-30.0, 0.0, 30.0] for v in bindings.values()),
                "Head candidate axis/key profile mismatch")
        require(all(sum(p.get("id") == axis for p in parameters) == 1 for axis in axes),
                "Head candidate parameter display identity is ambiguous")
        selected[role] = {"role": role, "kind": wire_kind, "objectId": text_value(node.get("id"), "head object ID"),
                          "parameterIds": list(axes)}
    return list(selected.values())


def summarize_head_candidate(candidate, snapshot):
    rows = snapshot["rows"]
    axes = [r for r in rows if r.get("kind") == "axis"]
    require(len(axes) == len(candidate["parameterIds"])
            and {a["parameterId"] for a in axes} == set(candidate["parameterIds"]),
            "Head stored axes differ from inspected bindings")
    by_guid = {a["parameterGuid"]: a for a in axes}
    require(len(by_guid) == len(axes) and all(sorted(a["keys"]) == [-30.0, 0.0, 30.0] for a in axes),
            "Head stored native axes are ambiguous or have unexpected keys")
    cells = [r for r in rows if r.get("kind") == "cell"]
    form_kind = {"art_mesh": "mesh_form", "warp_deformer": "warp_form", "rotation_deformer": "rotation_form"}[candidate["kind"]]
    forms = [r["formGuid"] for r in rows if r.get("kind") == form_kind]
    require(len(forms) == len(set(forms)), "Head stored form identity is ambiguous")
    tuples = set()
    for cell in cells:
        coordinates = cell["coordinates"]
        require(len(coordinates) == len(axes) and {c["parameterGuid"] for c in coordinates} == set(by_guid),
                "Head stored cell has an incomplete tuple")
        require(all(c["value"] in by_guid[c["parameterGuid"]]["keys"] for c in coordinates),
                "Head stored cell has an unknown axis key")
        value_tuple = tuple(sorted((c["parameterGuid"], c["value"]) for c in coordinates))
        require(value_tuple not in tuples and cell["formGuid"] in forms, "Head stored cell duplicates or references an unknown form")
        tuples.add(value_tuple)
    require(len(cells) == 3 ** len(axes), "Head stored cell grid is incomplete")
    eligibility = [r for r in rows if r.get("kind") == "ordinary_write_eligibility"]
    require(len(eligibility) == len(forms) and {r["formGuid"] for r in eligibility} == set(forms),
            "Head form eligibility is incomplete")
    return {**candidate, **{k: snapshot[k] for k in ("sourceGuid", "rowCount", "rowsSha256")},
        "axes": [{k: a[k] for k in ("parameterId", "parameterGuid", "keys")} for a in axes],
        "cellCount": len(cells), "formCount": len(forms),
        "eligibleFormCount": sum(r.get("snapshotEligible") is True for r in eligibility),
        "hostWriteAdmission": "UNPROVEN", "semanticMapping": "CANDIDATE_ONLY"}


def capture_head_candidate_authoring(client, state_root, expected_state):
    resources = json.loads((state_root / "mcp-read-only-inventory-private.json").read_text(encoding="utf-8"))
    candidates = select_head_candidates(resources)
    if not candidates:
        summary = {"status": "UNAVAILABLE", "reason": "CANDIDATE_PROFILE_NOT_MATCHED", "objects": []}
    else:
        before = tool_call(client, "turboism.render.state", {})
        require(before.get("state") == expected_state, "Head candidates started with stale state")
        snapshots = [read_stored_object(client, c["kind"], c["objectId"], expected_state) for c in candidates]
        objects = [summarize_head_candidate(c, s) for c, s in zip(candidates, snapshots)]
        require(before == tool_call(client, "turboism.render.state", {}), "Head candidate reads changed state or pose")
        # The ordinary inventory resources are not immutable server snapshots; check them again.
        require(resources == {uri: resource_json(client, uri) for uri in resources}, "Head candidate inventory changed")
        summary = {"status": "READ_VERIFIED_CANDIDATE_ONLY", "state": expected_state, "objects": objects,
                   "profile": "Hiyori PRO t11 names, hierarchy and ordinary bindings; not a general classifier"}
        publish_atomic(state_root / "mcp-head-candidate-authoring-private.json", json.dumps(snapshots, indent=2) + "\n")
    publish_atomic(state_root / "mcp-head-candidates.json", json.dumps(summary, indent=2) + "\n")
    return summary


def finish_read_only_inspection(client, state_root, task_id, before, authoring, render):
    require_history_unchanged(before, history_snapshot(client), "Read-only inspection changed history")
    summary = {"runId": task_id, "mode": "READ_ONLY_INSPECTION", "status": "PASS", "modelMutation": "NONE",
        "historyUnchanged": True, "renderStatePreserved": True, "nativeRenderSha256": render["sha256"],
        "state": render["state"], "objects": [{key: row[key] for key in (
            "kind", "objectId", "sourceGuid", "rowCount", "rowsSha256")} for row in authoring]}
    assert_no_absolute_paths(summary)
    publish_atomic(state_root / "mcp-read-only-inspection.json", json.dumps(summary, indent=2) + "\n")
    return summary


def choose_glue_intensity_mutation(
    glues: list[Any],
) -> tuple[str, float, float]:
    """Returns one bounded mutation, preferring a Glue with two distinct endpoints."""
    candidates = [object_value(item, "glue") for item in glues]
    for require_distinct_endpoints in (True, False):
        for glue in candidates:
            first = glue.get("drawableAId")
            second = glue.get("drawableBId")
            if require_distinct_endpoints and (not isinstance(first, str) or first == second):
                continue
            glue_id = text_value(glue.get("id"), "glue.id")
            original = finite_number(glue.get("intensity"), "glue.intensity")
            require(0.0 <= original <= 1.0, "Glue intensity is outside [0,1]")
            candidate = original + 0.1 if original <= 0.9 else original - 0.1
            candidate = max(0.0, min(1.0, candidate))
            if not math.isclose(candidate, original, rel_tol=0.0, abs_tol=1.0e-6):
                return glue_id, original, candidate
    raise ValidationFailure("no reversible Glue intensity mutation candidate is available")


def history_guard_arguments(
    snapshot: dict[str, Any],
    *,
    undo: bool,
    steps: int = 1,
) -> dict[str, Any]:
    """Builds stale-safe history arguments for the top Undo or next Redo entry."""
    require(steps > 0, "history steps must be positive")
    generation = integer_value(snapshot.get("generation"), "history.generation")
    revision = integer_value(snapshot.get("revision"), "history.revision")
    position = integer_value(snapshot.get("position"), "history.position")
    entries = array_value(snapshot.get("entries"), "history.entries")
    guarded_index = position - 1 if undo else position
    require(0 <= guarded_index < len(entries), "guarded history entry is unavailable")
    entry = object_value(entries[guarded_index], "history entry")
    entry_id = text_value(entry.get("entryId"), "history entryId")
    arguments: dict[str, Any] = {
        "expectedGeneration": generation,
        "expectedRevision": revision,
        "steps": steps,
        "expectedTopEntryId": entry_id,
    }
    transaction_id = entry.get("transactionId")
    if transaction_id is not None:
        arguments["expectedTransactionId"] = text_value(
            transaction_id,
            "history transactionId",
        )
    return arguments


def history_snapshot(client: McpClient) -> dict[str, Any]:
    result = tool_call(client, "turboism.history.read", {})
    snapshot = object_value(result.get("snapshot"), "history snapshot")
    require(snapshot.get("availability") == "AVAILABLE", "native history is unavailable")
    entries = array_value(snapshot.get("entries"), "history.entries")
    for expected_index, item in enumerate(entries):
        entry = object_value(item, f"history.entries[{expected_index}]")
        require(
            integer_value(entry.get("index"), "history entry index") == expected_index,
            "history entry indexes are not contiguous",
        )
        text_value(entry.get("entryId"), "history entryId")
        transaction_id = entry.get("transactionId")
        if transaction_id is not None:
            text_value(transaction_id, "history transactionId")
    return snapshot


def history_identity(snapshot: dict[str, Any]) -> tuple[Any, ...]:
    entries = []
    for item in array_value(snapshot.get("entries"), "history.entries"):
        entry = object_value(item, "history entry")
        entries.append((
            entry.get("entryId"),
            entry.get("transactionId"),
            entry.get("label"),
            entry.get("significant"),
        ))
    return (
        snapshot.get("availability"),
        integer_value(snapshot.get("generation"), "history.generation"),
        integer_value(snapshot.get("revision"), "history.revision"),
        integer_value(snapshot.get("position"), "history.position"),
        tuple(entries),
    )


def require_history_unchanged(
    expected: dict[str, Any],
    actual: dict[str, Any],
    label: str,
) -> None:
    require(history_identity(expected) == history_identity(actual), label)


def glue_get_result(client: McpClient, glue_id: str) -> dict[str, Any]:
    result = tool_call(client, "turboism.glues.read", {
        "operation": "get",
        "id": glue_id,
    })
    require(result.get("availability") == "AVAILABLE", "Glue provider is unavailable")
    object_value(result.get("stateToken"), "Glue state token")
    object_value(result.get("result"), "Glue projection")
    return result


def optional_glue_get_result(
    client: McpClient,
    glue_id: str,
) -> dict[str, Any] | None:
    result = tool_result(client, "turboism.glues.read", {
        "operation": "get",
        "id": glue_id,
    })
    if result.get("ok") is True:
        return result
    error = object_value(result.get("error"), "Glue read error")
    if error.get("code") == "GLUE_NOT_FOUND":
        return None
    raise ValidationFailure(
        "Glue lookup failed unexpectedly: " + sanitize(str(error.get("code")))
    )


def validate_capability_ledger(client: McpClient) -> tuple[int, int]:
    result = tool_call(client, "turboism.capabilities.read", {})
    operations = {
        text_value(object_value(item, "capability operation").get("name"), "operation.name"):
            object_value(item, "capability operation")
        for item in array_value(result.get("operations"), "capability operations")
    }
    require(set(operations) == EXPECTED_TOOLS, "capability operation catalog mismatch")
    require("turboism.history.move" not in operations, "legacy history.move remains public")
    for name, effect in (
        ("turboism.history.read", "READ"),
        ("turboism.history.undo", "HISTORY_CONTROL"),
        ("turboism.history.redo", "HISTORY_CONTROL"),
    ):
        operation = operations[name]
        require(operation.get("effect") == effect, f"{name} effect mismatch")
        require(operation.get("affinity") == "UI_THREAD", f"{name} is not UI-thread bound")
        require(operation.get("transactionEligible") is False,
                f"{name} must not be transaction eligible")
        require(array_value(operation.get("supportedVersions"), "history versions") ==
            ALL_REVIEWED_VERSIONS, f"{name} exact-version set mismatch")

    glue_write = operations["turboism.glues.write"]
    require(glue_write.get("effect") == "UNDOABLE_WRITE", "Glue write effect is not undoable")
    require(glue_write.get("affinity") == "UI_THREAD", "Glue write affinity is not UI_THREAD")
    require(glue_write.get("transactionEligible") is True,
            "Glue write is not transaction eligible")
    supported = array_value(glue_write.get("supportedVersions"), "Glue supported versions")
    require(supported == ALL_REVIEWED_VERSIONS,
            "Glue provider versions are not exact or complete")
    write_operations = {
        text_value(object_value(item, "Glue operation").get("operation"), "Glue operation name"):
            object_value(item, "Glue operation")
        for item in array_value(glue_write.get("operations"), "Glue operation availability")
    }
    require(set(write_operations) == {
        "set_name", "set_id", "set_intensity", "set_drawable_a", "set_drawable_b",
        "create", "delete",
    }, "Glue operation availability ledger is incomplete")
    for name in (
        "set_name", "set_id", "set_intensity", "set_drawable_a", "set_drawable_b",
    ):
        operation = write_operations[name]
        require(operation.get("availability") == "AVAILABLE",
                f"Glue operation {name} is not available")
        require(operation.get("effect") == "UNDOABLE_WRITE",
                f"Glue operation {name} has the wrong effect")
        require(operation.get("transactionEligible") is True,
                f"Glue operation {name} is not transaction eligible")
        require(operation.get("undoVerification") in {
            "RUNTIME_VERIFIED", "EXACT_HOST_VERIFIED",
        }, f"Glue operation {name} has no Undo verification")
        require(array_value(operation.get("supportedVersions"), "operation versions") == supported,
                f"Glue operation {name} version set differs from the provider")
    for name in ("create", "delete"):
        operation = write_operations[name]
        require(operation.get("availability") == "RUNTIME_UNAVAILABLE",
                f"Glue operation {name} must be explicitly unavailable")
        require(operation.get("transactionEligible") is False,
                f"Unavailable Glue operation {name} is transaction eligible")
        require("verified Editor provider" in text_value(operation.get("reason"), "reason"),
                f"Glue operation {name} has no provider-gap reason")

    coverage = object_value(result.get("coverage"), "SDK coverage ledger")
    require(integer_value(coverage.get("schemaVersion"), "coverage.schemaVersion") == 1,
            "SDK coverage schema version mismatch")
    tracked = array_value(coverage.get("trackedOwners"), "coverage trackedOwners")
    require("dev.turboism.sdk.cubism.model.Glue" in tracked,
            "Glue SDK owner is absent from coverage")
    entries = array_value(coverage.get("entries"), "coverage entries")
    exceptions = array_value(
        coverage.get("temporaryPublicExceptions"),
        "coverage temporary exceptions",
    )
    exception_names = {
        text_value(object_value(item, "temporary exception").get("endpoint"), "endpoint")
        for item in exceptions
    }
    require(exception_names == {
        "turboism.model_objects.apply",
        "turboism.parameters.apply",
        "turboism.parameter_bindings.apply",
    }, "temporary apply exception set is unexpected")
    return len(entries), len(exceptions)


def alternate_intensity(original: float, delta: float) -> float:
    candidate = original + delta if original + delta <= 1.0 else original - delta
    candidate = max(0.0, min(1.0, candidate))
    require(not math.isclose(candidate, original, rel_tol=0.0, abs_tol=1.0e-6),
            "could not choose a different Glue intensity")
    return candidate


def choose_temporary_glue_id(client: McpClient, task_id: str) -> str:
    digest = hashlib.sha256(task_id.encode("utf-8")).hexdigest()[:12]
    for sequence in range(100):
        candidate = f"McpGlueProbe{digest}{sequence:02d}"
        if optional_glue_get_result(client, candidate) is None:
            return candidate
    raise ValidationFailure("could not allocate a task-unique Glue ID")


def transaction_ref(step: str, pointer: str) -> dict[str, Any]:
    return {"$ref": {"step": step, "pointer": pointer}}


def require_glue_state(
    client: McpClient,
    glue_id: str,
    *,
    name: str,
    intensity: float,
    drawable_a: str,
    drawable_b: str,
) -> dict[str, Any]:
    result = glue_get_result(client, glue_id)
    glue = object_value(result.get("result"), "Glue projection")
    require(glue.get("id") == glue_id, "Glue ID readback mismatch")
    require(glue.get("name") == name, "Glue name readback mismatch")
    require(
        math.isclose(
            finite_number(glue.get("intensity"), "Glue intensity"),
            intensity,
            rel_tol=0.0,
            abs_tol=1.0e-5,
        ),
        "Glue intensity readback mismatch",
    )
    require(glue.get("drawableAId") == drawable_a, "Glue drawable A readback mismatch")
    require(glue.get("drawableBId") == drawable_b, "Glue drawable B readback mismatch")
    return result


def restore_glue_direct(
    client: McpClient,
    original_id: str,
    temporary_id: str,
    original_name: str,
    original_intensity: float,
    original_drawable_a: str,
    original_drawable_b: str,
) -> None:
    current = optional_glue_get_result(client, original_id)
    current_id = original_id
    if current is None:
        current = optional_glue_get_result(client, temporary_id)
        current_id = temporary_id
    require(current is not None, "Glue cleanup cannot locate the original or temporary ID")

    projection = object_value(current.get("result"), "Glue cleanup projection")
    repairs: list[tuple[str, str, Any]] = []
    if projection.get("name") != original_name:
        repairs.append(("set_name", "name", original_name))
    if not math.isclose(
        finite_number(projection.get("intensity"), "Glue cleanup intensity"),
        original_intensity,
        rel_tol=0.0,
        abs_tol=1.0e-5,
    ):
        repairs.append(("set_intensity", "intensity", original_intensity))
    if projection.get("drawableAId") != original_drawable_a:
        repairs.append(("set_drawable_a", "drawableId", original_drawable_a))
    if projection.get("drawableBId") != original_drawable_b:
        repairs.append(("set_drawable_b", "drawableId", original_drawable_b))
    for operation, field, value in repairs:
        tool_call(client, "turboism.glues.write", {
            "operation": operation,
            "id": current_id,
            field: value,
        })
    if current_id != original_id:
        tool_call(client, "turboism.glues.write", {
            "operation": "set_id",
            "id": current_id,
            "newId": original_id,
        })
    require_glue_state(
        client,
        original_id,
        name=original_name,
        intensity=original_intensity,
        drawable_a=original_drawable_a,
        drawable_b=original_drawable_b,
    )


def validate_reversible_glue_authoring(
    client: McpClient,
    task_id: str,
) -> tuple[str, str]:
    listed = tool_call(client, "turboism.glues.read", {
        "operation": "list",
        "offset": 0,
        "limit": 200,
    })
    provider = object_value(listed.get("provider"), "Glue provider")
    active_version = text_value(provider.get("activeVersion"), "Glue activeVersion")
    require(active_version in ALL_REVIEWED_VERSIONS,
            "Glue provider is not bound to an exact reviewed version")
    page = object_value(listed.get("result"), "Glue page")
    glues = array_value(page.get("items"), "Glue items")
    require(bool(glues), "the exact-host fixture contains no Glue relation")
    original_id, original_intensity, standalone_intensity = \
        choose_glue_intensity_mutation(glues)
    initial = glue_get_result(client, original_id)
    original = object_value(initial.get("result"), "original Glue")
    original_name = text_value(original.get("name"), "Glue name")
    original_drawable_a = text_value(original.get("drawableAId"), "Glue drawable A")
    original_drawable_b = text_value(original.get("drawableBId"), "Glue drawable B")
    require(original_drawable_a != original_drawable_b,
            "the selected Glue does not have two distinct ArtMesh endpoints")
    temporary_id = choose_temporary_glue_id(client, task_id)
    short_tag = hashlib.sha256(task_id.encode("utf-8")).hexdigest()[:8]
    transaction_name = (original_name + " MCP " + short_tag)[:1024]
    if transaction_name == original_name:
        transaction_name = ("MCP Glue " + short_tag)[:1024]
    rollback_name = ("MCP rollback " + short_tag)[:1024]
    transaction_intensity = alternate_intensity(original_intensity, 0.2)
    missing_art_mesh = "McpMissingArtMesh" + short_tag
    completed = False
    primary_failure: Exception | None = None

    try:
        baseline_history = history_snapshot(client)
        require(
            integer_value(baseline_history.get("position"), "history.position")
            == len(array_value(baseline_history.get("entries"), "history.entries")),
            "exact-host Glue validation requires history to start at its tip",
        )

        for operation, arguments in (
            ("create", {"operation": "create"}),
            ("delete", {"operation": "delete", "id": original_id}),
        ):
            rejected = tool_result(client, "turboism.glues.write", arguments)
            require(rejected.get("ok") is False, f"Glue {operation} unexpectedly succeeded")
            require(rejected.get("outcome") == "UNAVAILABLE",
                    f"Glue {operation} was not explicitly unavailable")
            error = object_value(rejected.get("error"), "Glue provider-gap error")
            require(error.get("code") == "NO_VERIFIED_PROVIDER",
                    f"Glue {operation} did not report the provider gap")
        require_history_unchanged(
            baseline_history,
            history_snapshot(client),
            "unavailable Glue operations changed history",
        )

        current = glue_get_result(client, original_id)
        stale_state = dict(object_value(current.get("stateToken"), "Glue state token"))
        stale_state["historyRevision"] = integer_value(
            stale_state.get("historyRevision"),
            "Glue state historyRevision",
        ) + 1
        stale = tool_result(client, "turboism.glues.write", {
            "operation": "set_intensity",
            "id": original_id,
            "intensity": standalone_intensity,
            "expectedState": stale_state,
        })
        require(stale.get("ok") is False and stale.get("outcome") == "REJECTED_STALE",
                "stale Glue write was not rejected")
        require_glue_state(
            client,
            original_id,
            name=original_name,
            intensity=original_intensity,
            drawable_a=original_drawable_a,
            drawable_b=original_drawable_b,
        )
        require_history_unchanged(
            baseline_history,
            history_snapshot(client),
            "stale Glue write changed history",
        )

        current = glue_get_result(client, original_id)
        no_change = tool_call(client, "turboism.glues.write", {
            "operation": "set_intensity",
            "id": original_id,
            "intensity": original_intensity,
            "expectedState": object_value(current.get("stateToken"), "Glue state token"),
        })
        require(no_change.get("outcome") == "NO_CHANGE", "Glue no-op did not report NO_CHANGE")
        no_change_history = object_value(no_change.get("history"), "Glue no-op history")
        require(integer_value(no_change_history.get("entriesAdded"), "entriesAdded") == 0,
                "Glue no-op created an Undo entry")
        require_history_unchanged(
            baseline_history,
            history_snapshot(client),
            "Glue no-op changed history",
        )

        current = glue_get_result(client, original_id)
        standalone_before = history_snapshot(client)
        changed = tool_call(client, "turboism.glues.write", {
            "operation": "set_intensity",
            "id": original_id,
            "intensity": standalone_intensity,
            "expectedState": object_value(current.get("stateToken"), "Glue state token"),
        })
        require(changed.get("outcome") == "APPLIED", "standalone Glue write was not applied")
        changed_projection = object_value(
            object_value(changed.get("result"), "Glue write result").get("readback"),
            "Glue write readback",
        )
        require(math.isclose(
            finite_number(changed_projection.get("intensity"), "Glue write intensity"),
            standalone_intensity,
            rel_tol=0.0,
            abs_tol=1.0e-5,
        ), "standalone Glue write readback is incorrect")
        standalone_after = history_snapshot(client)
        require(
            integer_value(standalone_after.get("position"), "history.position")
            == integer_value(standalone_before.get("position"), "history.position") + 1,
            "standalone Glue write did not append one Undo entry",
        )
        top = object_value(
            array_value(standalone_after.get("entries"), "history.entries")[
                integer_value(standalone_after.get("position"), "history.position") - 1
            ],
            "standalone Glue history entry",
        )
        entry_id = text_value(top.get("entryId"), "standalone history entryId")
        transaction_id = text_value(
            top.get("transactionId"),
            "standalone history transactionId",
        )
        changed_history = object_value(changed.get("history"), "Glue write history")
        require(changed_history.get("entryId") == entry_id,
                "standalone Glue result entryId differs from history")
        require(changed_history.get("transactionId") == transaction_id,
                "standalone Glue result transactionId differs from history")

        undo = tool_call(
            client,
            "turboism.history.undo",
            history_guard_arguments(standalone_after, undo=True),
        )
        require(undo.get("outcome") == "MOVED", "standalone Glue Undo did not move")
        require_glue_state(
            client,
            original_id,
            name=original_name,
            intensity=original_intensity,
            drawable_a=original_drawable_a,
            drawable_b=original_drawable_b,
        )
        undone = history_snapshot(client)
        redo = tool_call(
            client,
            "turboism.history.redo",
            history_guard_arguments(undone, undo=False),
        )
        require(redo.get("outcome") == "MOVED", "standalone Glue Redo did not move")
        require_glue_state(
            client,
            original_id,
            name=original_name,
            intensity=standalone_intensity,
            drawable_a=original_drawable_a,
            drawable_b=original_drawable_b,
        )
        redone = history_snapshot(client)
        final_standalone_undo = tool_call(
            client,
            "turboism.history.undo",
            history_guard_arguments(redone, undo=True),
        )
        require(final_standalone_undo.get("outcome") == "MOVED",
                "standalone Glue final Undo did not move")
        require_glue_state(
            client,
            original_id,
            name=original_name,
            intensity=original_intensity,
            drawable_a=original_drawable_a,
            drawable_b=original_drawable_b,
        )

        transaction_before = history_snapshot(client)
        transaction = tool_call(client, "turboism.transaction.execute", {
            "label": "MCP Glue all-writes " + short_tag,
            "steps": [
                {
                    "id": "read",
                    "tool": "turboism.glues.read",
                    "arguments": {"operation": "get", "id": original_id},
                },
                {
                    "id": "name",
                    "tool": "turboism.glues.write",
                    "arguments": {
                        "operation": "set_name",
                        "id": transaction_ref("read", "/result/id"),
                        "name": transaction_name,
                    },
                },
                {
                    "id": "intensity",
                    "tool": "turboism.glues.write",
                    "arguments": {
                        "operation": "set_intensity",
                        "id": transaction_ref("name", "/result/readback/id"),
                        "intensity": transaction_intensity,
                    },
                },
                {
                    "id": "drawableA",
                    "tool": "turboism.glues.write",
                    "arguments": {
                        "operation": "set_drawable_a",
                        "id": transaction_ref("intensity", "/result/readback/id"),
                        "drawableId": original_drawable_b,
                    },
                },
                {
                    "id": "drawableB",
                    "tool": "turboism.glues.write",
                    "arguments": {
                        "operation": "set_drawable_b",
                        "id": transaction_ref("drawableA", "/result/readback/id"),
                        "drawableId": original_drawable_a,
                    },
                },
                {
                    "id": "identifier",
                    "tool": "turboism.glues.write",
                    "arguments": {
                        "operation": "set_id",
                        "id": transaction_ref("drawableB", "/result/readback/id"),
                        "newId": temporary_id,
                    },
                },
                {
                    "id": "verify",
                    "tool": "turboism.glues.read",
                    "arguments": {
                        "operation": "get",
                        "id": transaction_ref("identifier", "/result/readback/id"),
                    },
                },
            ],
        })
        require(transaction.get("outcome") == "COMMITTED",
                "Glue all-writes transaction did not commit")
        require(len(array_value(transaction.get("steps"), "transaction steps")) == 7,
                "Glue transaction did not execute every step")
        receipt = object_value(transaction.get("receipt"), "transaction receipt")
        receipt_entry_id = text_value(receipt.get("historyEntryId"), "receipt historyEntryId")
        receipt_transaction_id = text_value(receipt.get("transactionId"), "receipt transactionId")
        transaction_after = history_snapshot(client)
        require(
            integer_value(transaction_after.get("position"), "history.position")
            == integer_value(transaction_before.get("position"), "history.position") + 1,
            "Glue transaction did not create exactly one Undo position",
        )
        transaction_entry = object_value(
            array_value(transaction_after.get("entries"), "history.entries")[
                integer_value(transaction_after.get("position"), "history.position") - 1
            ],
            "Glue transaction history entry",
        )
        require(transaction_entry.get("entryId") == receipt_entry_id,
                "transaction receipt entryId differs from history")
        require(transaction_entry.get("transactionId") == receipt_transaction_id,
                "transaction receipt transactionId differs from history")
        require_glue_state(
            client,
            temporary_id,
            name=transaction_name,
            intensity=transaction_intensity,
            drawable_a=original_drawable_b,
            drawable_b=original_drawable_a,
        )

        transaction_undo = tool_call(
            client,
            "turboism.history.undo",
            history_guard_arguments(transaction_after, undo=True),
        )
        require(transaction_undo.get("outcome") == "MOVED", "Glue transaction Undo failed")
        require_glue_state(
            client,
            original_id,
            name=original_name,
            intensity=original_intensity,
            drawable_a=original_drawable_a,
            drawable_b=original_drawable_b,
        )
        transaction_undone = history_snapshot(client)
        transaction_redo = tool_call(
            client,
            "turboism.history.redo",
            history_guard_arguments(transaction_undone, undo=False),
        )
        require(transaction_redo.get("outcome") == "MOVED", "Glue transaction Redo failed")
        require_glue_state(
            client,
            temporary_id,
            name=transaction_name,
            intensity=transaction_intensity,
            drawable_a=original_drawable_b,
            drawable_b=original_drawable_a,
        )
        transaction_redone = history_snapshot(client)
        transaction_final_undo = tool_call(
            client,
            "turboism.history.undo",
            history_guard_arguments(transaction_redone, undo=True),
        )
        require(transaction_final_undo.get("outcome") == "MOVED",
                "Glue transaction final Undo failed")
        require_glue_state(
            client,
            original_id,
            name=original_name,
            intensity=original_intensity,
            drawable_a=original_drawable_a,
            drawable_b=original_drawable_b,
        )

        rollback_before = history_snapshot(client)
        rolled_back = tool_result(client, "turboism.transaction.execute", {
            "label": "MCP Glue rollback " + short_tag,
            "steps": [
                {
                    "id": "read",
                    "tool": "turboism.glues.read",
                    "arguments": {"operation": "get", "id": original_id},
                },
                {
                    "id": "name",
                    "tool": "turboism.glues.write",
                    "arguments": {
                        "operation": "set_name",
                        "id": transaction_ref("read", "/result/id"),
                        "name": rollback_name,
                    },
                },
                {
                    "id": "invalid",
                    "tool": "turboism.glues.write",
                    "arguments": {
                        "operation": "set_drawable_a",
                        "id": transaction_ref("name", "/result/readback/id"),
                        "drawableId": missing_art_mesh,
                    },
                },
            ],
        })
        require(rolled_back.get("ok") is False and rolled_back.get("outcome") == "ROLLED_BACK",
                "failed Glue transaction did not report ROLLED_BACK")
        require_glue_state(
            client,
            original_id,
            name=original_name,
            intensity=original_intensity,
            drawable_a=original_drawable_a,
            drawable_b=original_drawable_b,
        )
        require_history_unchanged(
            rollback_before,
            history_snapshot(client),
            "rolled-back Glue transaction changed native history",
        )

        completed = True
        return "GLUE_ALL_WRITES_CHANGED_UNDONE_REDONE_RESTORED", active_version
    except Exception as failure:
        primary_failure = failure
        raise
    finally:
        if not completed:
            try:
                restore_glue_direct(
                    client,
                    original_id,
                    temporary_id,
                    original_name,
                    original_intensity,
                    original_drawable_a,
                    original_drawable_b,
                )
            except Exception as cleanup_failure:
                if primary_failure is None:
                    raise
                raise ValidationFailure(
                    "Glue validation failed and direct cleanup also failed "
                    f"({cleanup_failure.__class__.__name__})"
                ) from primary_failure



def validate_native_render(client: McpClient, state_root: Path) -> dict[str, Any]:
    """Require current native pixels through the public typed MCP result, never a thumbnail."""
    before = tool_call(client, "turboism.render.state", {})
    state = object_value(before.get("state"), "render state")
    parameters = array_value(before.get("parameters"), "render parameters")
    stale = dict(state)
    stale["poseHash"] = "0" * 64 if state.get("poseHash") != "0" * 64 else "1" * 64
    rejected = client.call("tools/call", {"name": "turboism.render.capture", "arguments": {
        "expectedState": stale, "width": 256, "height": 256}})
    require(object_value(rejected.get("structuredContent"), "stale render").get("ok") is False,
            "stale render state was accepted")
    require(not any(isinstance(block, dict) and block.get("type") == "image"
                    for block in array_value(rejected.get("content"), "stale content")),
            "stale render returned an image")
    result = client.call("tools/call", {"name": "turboism.render.capture", "arguments": {
        "expectedState": state, "width": 256, "height": 256}})
    metadata = object_value(result.get("structuredContent"), "render metadata")
    require(result.get("isError") is not True and metadata.get("ok") is True,
            "native render capture unavailable or failed")
    require(metadata.get("state") == state and metadata.get("parameters") == parameters,
            "native render pose or state mismatch")
    require(metadata.get("renderer") == "cubism.native.renderPixels", "native renderer identity mismatch")
    require(metadata.get("width") == 256 and metadata.get("height") == 256,
            "native render dimensions mismatch")
    blocks = array_value(result.get("content"), "render content")
    require(len(blocks) == 2, "typed render must return JSON and one image")
    text = object_value(blocks[0], "render JSON")
    require(text.get("type") == "text" and json.loads(text_value(text.get("text"), "render text")) == metadata,
            "render text differs from metadata")
    image = object_value(blocks[1], "render image")
    require(image.get("type") == "image" and image.get("mimeType") == "image/png",
            "render lost its typed PNG image")
    encoded = text_value(image.get("data"), "render image data")
    require(len(encoded) <= 4 * 1024 * 1024, "render image exceeds client budget")
    pixels = base64.b64decode(encoded, validate=True)
    require(pixels[:16] == b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR" and len(pixels) >= 33,
            "render image is not PNG")
    require(struct.unpack(">II", pixels[16:24]) == (256, 256), "PNG dimensions differ from metadata")
    require(len(pixels) == metadata.get("byteLength")
            and hashlib.sha256(pixels).hexdigest() == metadata.get("sha256"),
            "PNG bytes differ from metadata")
    after = tool_call(client, "turboism.render.state", {})
    require(after.get("state") == state and after.get("parameters") == parameters,
            "capture changed document, history or pose")
    # Write only after every check. No endpoint, token, session header or base64 in reports.
    (state_root / "mcp-native-render.png").write_bytes(pixels)
    (state_root / "mcp-native-render.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return metadata



def read_stored_object(client: McpClient, kind: str, object_id: str, state: dict[str, Any]) -> dict[str, Any]:
    domain = {"art_mesh": "meshes", "warp_deformer": "warps", "rotation_deformer": "rotations"}[kind]
    tool = f"turboism.authoring.{domain}.read"
    arguments = {"documentId": state["documentId"], "modelId": state["modelId"],
                 "objectId": object_id, "limit": 128}
    first = tool_call(client, tool, arguments)
    snapshot = text_value(first.get("snapshotId"), "authoring snapshot ID")
    rows: list[Any] = []
    page = first
    for _ in range(1000):
        require(page.get("kind") == kind and page.get("objectId") == arguments["objectId"],
                "authoring target changed between pages")
        require(page.get("snapshotId") == snapshot and page.get("sourceGuid") == first.get("sourceGuid")
                and page.get("state") == first.get("state") and page.get("totalRows") == first.get("totalRows"),
                "authoring pages do not belong to one snapshot")
        require(page.get("offset") == len(rows), "authoring page offset mismatch")
        rows.extend(array_value(page.get("rows"), "authoring rows"))
        require(len(rows) <= 100000, "authoring row budget exceeded")
        next_offset = page.get("nextOffset")
        if next_offset is None:
            break
        require(next_offset == len(rows) and bool(page["rows"]), "authoring cursor did not advance")
        page = tool_call(client, tool, {**arguments, "snapshotId": snapshot, "offset": next_offset})
    else:
        raise ValidationFailure("authoring page budget exceeded")
    require(len(rows) == first.get("totalRows"), "authoring snapshot incomplete")
    binding = object_value(first.get("state"), "authoring binding")
    require(all(binding.get(key) == state[key] for key in (
        "documentId", "modelId", "historyGeneration", "historyRevision")), "authoring state mismatch")
    repeat = tool_call(client, tool, {**arguments, "snapshotId": snapshot})
    require(repeat == first, "immutable authoring page changed when reread")
    encoded = json.dumps(rows, sort_keys=True, separators=(",", ":")).encode()
    require(len(encoded) <= 4 * 1024 * 1024, "authoring byte budget exceeded")
    return {"kind": kind, "objectId": arguments["objectId"], "sourceGuid": first.get("sourceGuid"),
            "snapshotId": snapshot, "state": binding, "rowCount": len(rows),
            "rowsSha256": hashlib.sha256(encoded).hexdigest(), "rows": rows}


def validate_stored_authoring(client: McpClient, state_root: Path) -> list[dict[str, Any]]:
    before = tool_call(client, "turboism.render.state", {})
    state = object_value(before.get("state"), "authoring state")
    hierarchy = resource_json(client, "turboism://active/model/hierarchy")
    require(hierarchy.get("availability") == "AVAILABLE", "authoring hierarchy unavailable")
    pending = [object_value(hierarchy.get("root"), "hierarchy root")]
    objects: dict[str, list[str]] = {}
    count = 0
    kinds = {"ART_MESH": ("art_mesh", "meshes"), "WARP_DEFORMER": ("warp_deformer", "warps"),
             "ROTATION_DEFORMER": ("rotation_deformer", "rotations")}
    while pending:
        node = object_value(pending.pop(), "hierarchy node")
        count += 1
        require(count <= 100000, "hierarchy exceeds client budget")
        kind = node.get("kind")
        if kind in kinds:
            objects.setdefault(kind, []).append(text_value(node.get("id"), "authoring object ID"))
        pending.extend(array_value(node.get("children"), "hierarchy children"))
    require(set(objects) == set(kinds), "fixture must contain Mesh, Warp and Rotation objects")
    evidence = []
    for kind, (wire_kind, domain) in kinds.items():
        tool = f"turboism.authoring.{domain}.read"
        attempts = []
        first = None
        arguments = {}
        for object_id in objects[kind][:32]:
            arguments = {"documentId": state["documentId"], "modelId": state["modelId"],
                         "objectId": object_id, "limit": 128}
            candidate = tool_result(client, tool, arguments)
            if candidate.get("ok") is True:
                if first is None:
                    first = candidate
                if any(row.get("kind") == "ordinary_write_eligibility" and row.get("snapshotEligible") is True
                       for row in array_value(candidate.get("rows"), "candidate rows")):
                    first = candidate
                    break
                continue
            error = candidate.get("error")
            code = error.get("code") if isinstance(error, dict) else error
            attempts.append({"objectId": object_id, "code": code})
            require(code in {"INVALID_ARGUMENT", "UNAVAILABLE"}, "authoring read failed unexpectedly")
        if first is None:
            (state_root / "mcp-stored-authoring-rejections.json").write_text(
                json.dumps({"kind": wire_kind, "rejectedCandidates": attempts}, indent=2) + "\n")
            (state_root / "mcp-authoring-diagnostic-request").write_text("READ_ONLY\n")
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline and not (state_root / "mcp-authoring-diagnostic.json").is_file():
                time.sleep(0.1)
        require(first is not None, f"No readable {wire_kind} in bounded fixture candidates")
        # Scanning candidates may evict an earlier fallback from the bounded server cache.
        # Acquire the selected object again before consuming any pages or planning a write.
        selected = read_stored_object(client, wire_kind, first["objectId"], state)
        selected["rejectedCandidates"] = attempts
        evidence.append(selected)
    after = tool_call(client, "turboism.render.state", {})
    require(before == after, "stored authoring read changed state or pose")
    (state_root / "mcp-stored-authoring.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return evidence


def stored_rows_equal(actual: list[Any], expected: list[Any]) -> bool:
    """Compare SDK float32 values by bits, retaining exact integer/identity fields."""
    float_fields = {"x", "y", "angle", "originX", "originY", "scale", "opacity",
                    "minimum", "maximum", "defaultValue", "value", "keys", "baseAngle"}
    def native(value, field=""):
        if isinstance(value, dict): return {key: native(item, key) for key, item in value.items()}
        if isinstance(value, list): return [native(item, field) for item in value]
        if field in float_fields and isinstance(value, (int, float)) and not isinstance(value, bool):
            require(math.isfinite(value), "Nonfinite stored float")
            return ("float32", struct.pack("!f", value).hex())
        return value
    return native(actual) == native(expected)


def ordinary_edit(snapshot: dict[str, Any]) -> tuple[dict[str, Any], list[Any]]:
    """Build a small exact-float edit to an existing independent ordinary form only."""
    rows = snapshot["rows"]
    eligible = [row["formGuid"] for row in rows if row.get("kind") == "ordinary_write_eligibility"
                and row.get("snapshotEligible") is True]
    require(bool(eligible), "No independent non-default ordinary form")
    axes = {row["parameterGuid"]: row for row in rows if row.get("kind") == "axis"}
    renderable = [row["formGuid"] for row in rows if row.get("kind") == "cell" and row.get("formGuid") in eligible
                  and all(c["parameterGuid"] in axes and axes[c["parameterGuid"]]["minimum"] <= c["value"] <= axes[c["parameterGuid"]]["maximum"]
                          for c in row["coordinates"])]
    guid = renderable[0] if renderable else eligible[0]
    cells = [row for row in rows if row.get("kind") == "cell" and row.get("formGuid") == guid]
    require(len(cells) == 1, "Ordinary target cell is not independent")
    edit = {"formGuid": guid, "coordinates": cells[0]["coordinates"]}
    expected = json.loads(json.dumps(rows))
    def delta(value, step):
        result = struct.unpack("!f", struct.pack("!f", value + step))[0]
        require(math.isfinite(result) and result != value, "No finite representable geometry delta")
        return result
    if snapshot["kind"] == "rotation_deformer":
        forms = [r for r in expected if r.get("kind") == "rotation_form" and r.get("formGuid") == guid]
        require(len(forms) == 1, "Missing stored Rotation form")
        forms[0]["angle"] = delta(forms[0]["angle"], 5.0)
        edit["form"] = {key: forms[0][key] for key in (
            "angle", "originX", "originY", "scale", "reflectedX", "reflectedY")}
    else:
        points = sorted([r for r in expected if r.get("kind") == "form_point" and r.get("formGuid") == guid],
                        key=lambda row: row["index"])
        require(0 < len(points) <= 4096 and [r["index"] for r in points] == list(range(len(points))),
                "Invalid or over-budget ordinary points")
        points[0]["x"] = delta(points[0]["x"], 4.0)
        field = "positions" if snapshot["kind"] == "art_mesh" else "controlPoints"
        edit[field] = [{"x": row["x"], "y": row["y"]} for row in points]
    return {"kind": snapshot["kind"], "objectId": snapshot["objectId"],
            "snapshotId": snapshot["snapshotId"], "edits": [edit]}, expected


def bounded_head_edit(snapshot):
    """Test perturbation in the stored parent space; never a character-design algorithm."""
    rows = snapshot["rows"]
    axes = {r["parameterGuid"]: r for r in rows if r.get("kind") == "axis"}
    expected_ids = {"ParamAngleZ"} if snapshot["kind"] == "rotation_deformer" else {"ParamAngleX", "ParamAngleY"}
    require(len(axes) == len(expected_ids) and {a["parameterId"] for a in axes.values()} == expected_ids,
            "Head edit has unexpected or ambiguous axes")
    positive_axis = "ParamAngleZ" if snapshot["kind"] == "rotation_deformer" else "ParamAngleX"
    cells = [r for r in rows if r.get("kind") == "cell" and len(r["coordinates"]) == len(axes)
             and {c["parameterGuid"] for c in r["coordinates"]} == set(axes)
             and all(c["value"] == (30 if axes[c["parameterGuid"]]["parameterId"] == positive_axis else 0)
                     for c in r["coordinates"])]
    require(len(cells) == 1, "Head edit requires the exact positive-X or positive-Z cell")
    cell = cells[0]
    guid = cell["formGuid"]
    require(sum(r.get("kind") == "cell" and r.get("formGuid") == guid for r in rows) == 1,
            "Head edit rejects shared form")
    eligibility = [r for r in rows if r.get("kind") == "ordinary_write_eligibility" and r.get("formGuid") == guid]
    require(len(eligibility) == 1 and eligibility[0].get("snapshotEligible") is True,
            "Head edit requires an independent non-default ordinary form")
    expected = json.loads(json.dumps(rows))
    edit = {"formGuid": guid, "coordinates": cell["coordinates"]}
    def float32(value):
        require(math.isfinite(value), "Nonfinite head edit")
        try: return struct.unpack("!f", struct.pack("!f", value))[0]
        except (OverflowError, struct.error): raise ValidationFailure("Unrepresentable head edit")
    if snapshot["kind"] == "rotation_deformer":
        forms = [r for r in expected if r.get("kind") == "rotation_form" and r.get("formGuid") == guid]
        require(len(forms) == 1, "Head rotation form missing")
        old = finite_number(forms[0]["angle"], "head angle")
        changed = float32(old + 0.5)
        require(changed != float32(old) and abs(changed - float32(old)) <= 0.50001, "Head angular delta exceeds budget")
        forms[0]["angle"] = changed
        edit["form"] = {k: forms[0][k] for k in ("angle", "originX", "originY", "scale", "reflectedX", "reflectedY")}
    else:
        points = sorted([r for r in expected if r.get("kind") == "form_point" and r.get("formGuid") == guid], key=lambda r: r["index"])
        require(1 < len(points) <= 4096 and [r["index"] for r in points] == list(range(len(points))), "Head point budget/order invalid")
        xs = [finite_number(r["x"], "head local x") for r in points]
        span = max(xs) - min(xs)
        require(math.isfinite(span) and span > 0, "Head form has no finite positive local x extent")
        delta = span / 256
        for point in points:
            original = float32(point["x"])
            changed = float32(original + delta)
            require(math.isfinite(changed) and 0 < changed - original <= span / 128, "Head positional delta is not representable within budget")
            point["x"] = changed
        field = "positions" if snapshot["kind"] == "art_mesh" else "controlPoints"
        edit[field] = [{"x": r["x"], "y": r["y"]} for r in points]
    return {"kind": snapshot["kind"], "objectId": snapshot["objectId"], "snapshotId": snapshot["snapshotId"], "edits": [edit]}, expected


def ordinary_poses(samples: list[dict[str, Any]], targets: list[dict[str, Any]]) -> list[dict[str, Any]]:
    require(len(samples) == len(targets) and bool(samples), "Ordinary pose targets missing")
    poses = []
    for sample, target in zip(samples, targets):
        axes = {row["parameterGuid"]: row for row in sample["rows"] if row.get("kind") == "axis"}
        values = []
        coordinates = target["edits"][0]["coordinates"]
        require(len(coordinates) == len(axes) and {c["parameterGuid"] for c in coordinates} == set(axes),
                "Ordinary pose must cover every native axis exactly once")
        for coordinate in coordinates:
            axis = axes[coordinate["parameterGuid"]]
            require(axis["minimum"] <= coordinate["value"] <= axis["maximum"], "Stored form pose outside live range")
            values.append({"parameterId": axis["parameterId"], "value": coordinate["value"]})
        require(values and len({v["parameterId"] for v in values}) == len(values), "Ambiguous ordinary pose parameter IDs")
        poses.append({"label": sample["kind"], "values": values})
    return poses


HEAD_POSE_IDS = ("ParamAngleX", "ParamAngleY", "ParamAngleZ", "ParamEyeLOpen", "ParamEyeROpen", "ParamMouthOpenY")
HEAD_CROP = {"x": 256, "y": 0, "width": 512, "height": 384}


def head_combination_poses(parameters):
    for parameter_id in HEAD_POSE_IDS:
        matches = [p for p in parameters if p.get("id") == parameter_id]
        require(len(matches) == 1, "Head QA requires unambiguous parameter definitions")
        p = matches[0]
        requested = (-30, 0, 30) if parameter_id.startswith("ParamAngle") else (0, 1)
        require(all(p["minimumValue"] <= value <= p["maximumValue"] for value in requested),
                "Head QA values exceed discovered parameter range")
    poses, seen = [], set()
    def add(label, x=0, y=0, z=0, left=1, right=1, mouth=0):
        values = (x, y, z, left, right, mouth)
        if values not in seen:
            seen.add(values)
            poses.append({"label": label, "values": [{"parameterId": k, "value": v} for k, v in zip(HEAD_POSE_IDS, values)]})
    for y in (-30, 0, 30):
        for x in (-30, 0, 30): add(f"xy-{x}-{y}", x, y)
    for z in (-30, 30):
        for y in (-30, 30):
            for x in (-30, 30): add(f"xyz-{x}-{y}-{z}", x, y, z)
    for x, y, z in ((0, 0, 0), (-30, -30, -30), (30, 30, 30)):
        for eye in (0, 1):
            for mouth in (0, 1): add(f"expression-{x}-{y}-{z}-eye{eye}-mouth{mouth}", x, y, z, eye, eye, mouth)
    add("wink-left", left=0)
    add("wink-right", right=0)
    require(len(poses) == 28, "Head QA coverage changed; review the finite pose set")
    return poses


def validate_head_pose_suite(client, state_root, operation, poses):
    require(len(poses) == 28, "Head QA pose budget/profile mismatch")
    initial = tool_call(client, "turboism.render.state", {})
    history = history_snapshot(client)
    frames, captures = [], set()
    summary = {"operationId": operation, "scope": "MULTIPLE_INDEPENDENT_SCANS", "status": "RUNNING",
               "artQuality": "NOT_AUTOMATICALLY_ACCEPTED", "renderWidth": 1024, "renderHeight": 1024, "crop": HEAD_CROP,
               "frames": frames, "parametersRestored": False, "historyPositionRestored": False}
    try:
        for index, pose in enumerate(poses):
            scan_id = f"{operation}-q{index:02d}"
            result = validate_ordinary_pose_capture(client, state_root, scan_id, [pose], width=1024, height=1024, crop=HEAD_CROP)
            frame = object_value(result["frames"][0], "head QA frame")
            require(frame["label"] == pose["label"] and frame["captureId"] not in captures, "Head QA frame order or capture mismatch")
            require(all(frame["state"].get(k) == initial["state"][k] for k in ("documentId", "modelId", "historyGeneration")),
                    "Head QA frame scope changed between independent scans")
            captures.add(frame["captureId"])
            frames.append({**frame, "suiteIndex": index, "scanOperationId": scan_id})
        final = tool_call(client, "turboism.render.state", {})
        require(final["parameters"] == initial["parameters"]
                and all(final["state"][k] == initial["state"][k] for k in ("documentId", "modelId", "historyGeneration")),
                "Head QA suite failed to restore parameters or scope")
        require(history_snapshot(client)["position"] == history["position"], "Head QA suite changed final history position")
        summary.update(status="PASS", parametersRestored=True, historyPositionRestored=True)
        return summary
    except Exception as failure:
        summary.update(status="FAIL", exceptionClass=type(failure).__name__, failedSuiteIndex=len(frames))
        raise
    finally:
        publish_atomic(state_root / f"{operation}-suite.json", json.dumps(summary, indent=2) + "\n")


def validate_ordinary_pose_capture(client: McpClient, state_root: Path, operation: str,
                                   poses: list[dict[str, Any]], *, width=512, height=512, crop=None) -> dict[str, Any]:
    initial = tool_call(client, "turboism.render.state", {})
    before = history_snapshot(client)
    metadata = None
    primary = None
    verification = {"operationId": operation, "status": "RUNNING", "scanHistoryRestored": False}
    image_files = []
    try:
        request = {"operationId": operation, "expectedState": initial["state"], "width": width, "height": height,
                   "poses": poses, "restorePolicy": "always", "timeBudgetMs": 30000}
        if crop is not None: request["crop"] = crop
        try:
            result = client.call("tools/call", {"name": "turboism.render.pose_grid", "arguments": request})
        except (TimeoutError, urllib.error.URLError):
            # Result lookup only. A transport timeout never authorizes replay of the scan.
            result = client.call("tools/call", {"name": "turboism.render.pose_grid.result",
                                                "arguments": {"operationId": operation}})
        deadline = time.monotonic() + 30
        metadata = object_value(result.get("structuredContent"), "pose scan metadata")
        while metadata.get("outcome") == "RUNNING" and time.monotonic() < deadline:
            time.sleep(.1)
            result = client.call("tools/call", {"name": "turboism.render.pose_grid.result",
                                                "arguments": {"operationId": operation}})
            metadata = object_value(result.get("structuredContent"), "pose scan metadata")
        require(metadata.get("ok") is True and metadata.get("outcome") == "COMPLETED"
                and metadata.get("parametersRestored") is True, "Native pose scan did not complete with verified restoration")
        require(metadata.get("operationId") == operation and metadata.get("initialState") == initial["state"]
                and metadata.get("initialParameters") == initial["parameters"], "Pose scan initial identity mismatch")
        frames = array_value(metadata.get("frames"), "pose frames")
        contents = array_value(result.get("content"), "pose contents")
        require(len(frames) == len(poses) and len(contents) == len(poses) + 1, "Pose scan lost frames or typed images")
        require(contents[0].get("type") == "text" and json.loads(contents[0]["text"]) == metadata,
                "Pose scan text differs from structured result")
        baseline = {x["id"]: x["value"] for x in initial["parameters"]}
        target_ids = {value["parameterId"] for pose in poses for value in pose["values"]}
        seen_captures = set()
        for index, (pose, frame) in enumerate(zip(poses, frames)):
            require(frame.get("index") == index and frame.get("label") == pose["label"]
                    and frame.get("imageContentIndex") == index + 1, "Pose to image association mismatch")
            require(frame.get("captureId") not in seen_captures and isinstance(frame.get("captureId"), str),
                    "Pose scan reused a capture identity")
            seen_captures.add(frame["captureId"])
            desired = {key: baseline[key] for key in target_ids}
            desired.update({v["parameterId"]: v["value"] for v in pose["values"]})
            actual = {x["id"]: x["value"] for x in frame["parameters"]}
            require(actual == desired and len(actual) == len(frame["parameters"]), "Frame parameters do not match requested pose")
            require(all(frame["state"].get(key) == initial["state"][key] for key in (
                "documentId", "modelId", "historyGeneration")), "Frame document/model/history changed")
            block = contents[index + 1]
            require(block.get("type") == "image" and block.get("mimeType") == "image/png", "Pose image lost typed PNG")
            encoded = text_value(block.get("data"), "pose image data")
            require(len(encoded) <= 4 * 1024 * 1024, "Pose image exceeds budget")
            pixels = base64.b64decode(encoded, validate=True)
            require(pixels[:16] == b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR" and len(pixels) >= 33,
                    "Pose image is not PNG")
            image_width, image_height = (crop["width"], crop["height"]) if crop is not None else (width, height)
            require(struct.unpack(">II", pixels[16:24]) == (image_width, image_height)
                    and frame.get("width") == image_width and frame.get("height") == image_height, "Pose PNG dimensions mismatch")
            if crop is not None:
                require(frame.get("crop") == crop and frame.get("renderWidth") == width
                        and frame.get("renderHeight") == height and frame.get("pixelCoordinates") == "origin-top-left,x-right,y-down",
                        "Pose crop/source render metadata mismatch")
            require(len(pixels) == frame.get("byteLength") and hashlib.sha256(pixels).hexdigest() == frame.get("sha256"),
                    "Pose PNG bytes differ from metadata")
            image_files.append((f"{operation}-{index}.png", pixels))
        after = tool_call(client, "turboism.render.state", {})
        require(after["parameters"] == initial["parameters"], "Pose scan did not restore all parameters")
        verification["status"] = "PASS"
        return metadata
    except Exception as failure:
        primary = failure
        verification["failure"] = {"exceptionClass": type(failure).__name__, "message": sanitize(str(failure))}
        raise
    finally:
        try:
            if metadata is not None and metadata.get("outcome") != "RUNNING" and metadata.get("parametersRestored") is True:
                after = history_snapshot(client)
                require(after["generation"] == before["generation"], "Pose scan changed history generation")
                if after["position"] == before["position"] + 1:
                    receipt = object_value(metadata.get("receipt"), "pose transaction receipt")
                    args = history_guard_arguments(after, undo=True)
                    require(args["expectedTopEntryId"] == receipt.get("historyEntryId")
                            and args.get("expectedTransactionId") == receipt.get("transactionId"), "Pose history receipt mismatch")
                    tool_call(client, "turboism.history.undo", args)
                else:
                    require(after["position"] == before["position"], "Unexpected pose history delta")
                restored = tool_call(client, "turboism.render.state", {})
                require(restored["parameters"] == initial["parameters"] and history_snapshot(client)["position"] == before["position"],
                        "Pose history cleanup changed restored parameters or position")
                verification["scanHistoryRestored"] = True
                tool_call(client, "turboism.render.pose_grid.release", {"operationId": operation})
        except Exception as cleanup_failure:
            verification["status"] = "FAIL"
            verification["cleanupError"] = {"exceptionClass": type(cleanup_failure).__name__}
            if primary is None: raise
        finally:
            if verification["status"] != "PASS" or not verification["scanHistoryRestored"]:
                verification["status"] = "FAIL"
            verification["result"] = metadata
            (state_root / f"{operation}.json").write_text(json.dumps(verification, indent=2) + "\n")
            for filename, pixels in image_files:
                (state_root / filename).write_bytes(pixels)


def validate_native_geometry_rollback(state_root: Path, task_id: str, samples: list[dict[str, Any]],
                                      plans: list[dict[str, Any]], *, timeout_seconds: float = 150) -> dict[str, Any]:
    require(len(samples) == len(plans) == 3, "Missing rollback targets")
    state = samples[0]["state"]
    request = {"runId": task_id, "documentId": state["documentId"], "modelId": state["modelId"],
               "targets": [{"kind": item["kind"], "objectId": item["objectId"], "sourceGuid": item["sourceGuid"],
                            "formGuid": plan["edits"][0]["formGuid"]} for item, plan in zip(samples, plans)]}
    publish_atomic(state_root / "mcp-geometry-rollback-request.json", json.dumps(request))
    path = state_root / "mcp-geometry-rollback-result.json"
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if path.is_file():
            require(path.stat().st_size <= 65536, "Geometry rollback evidence exceeds budget")
            report = object_value(json.loads(path.read_text()), "geometry rollback report")
            require(report.get("runId") == task_id and report.get("status") == "PASS"
                    and report.get("allSnapshotsRestored") is True, "Native geometry rollback not proven")
            baseline = report.get("beforeSnapshotHashes")
            require(isinstance(baseline, list) and len(baseline) == 3
                    and all(isinstance(x, str) and re.fullmatch(r"[a-f0-9]{64}", x) for x in baseline),
                    "Missing native baseline fingerprints")
            attempts = report.get("attempts")
            require(isinstance(attempts, list) and len(attempts) == 3, "Missing native rollback boundaries")
            for boundary, attempt in enumerate(attempts, 1):
                require(attempt.get("failureAfter") == boundary and attempt.get("verifiedSetterCount") == boundary
                        and attempt.get("injected") is True and attempt.get("outcome") == "ROLLED_BACK",
                        "Native fault did not follow confirmed setters")
                require(attempt.get("restoredSnapshotHashes") == baseline
                        and attempt.get("historyPositionBefore") == attempt.get("historyPositionAfter")
                        and isinstance(attempt.get("historyPositionBefore"), int)
                        and bool(attempt.get("transactionId")) and bool(attempt.get("diagnosticId")),
                        "Missing native restoration receipt")
                hashes = attempt.get("changedSnapshotHashes")
                for prefix in ("publicHistory", "transactionHistory"):
                    before_history = object_value(attempt.get(prefix + "Before"), "native history before")
                    require(set(before_history) == {"generation", "revision", "position", "entriesSha256", "entryCount",
                                                    "documentBindingId", "managerBindingId", "canUndo", "canRedo"}
                            and all(type(before_history[key]) is int and before_history[key] >= 0
                                    for key in ("generation", "revision", "position", "entryCount"))
                            and before_history["position"] <= before_history["entryCount"]
                            and re.fullmatch(r"[a-f0-9]{64}", str(before_history["entriesSha256"]))
                            and all(isinstance(before_history[key], str) and bool(before_history[key])
                                    for key in ("documentBindingId", "managerBindingId"))
                            and all(type(before_history[key]) is bool for key in ("canUndo", "canRedo")),
                            "Incomplete native history observer evidence")
                    require(before_history == attempt.get(prefix + "After")
                            and before_history.get("position") == attempt["historyPositionBefore"],
                            "Native history observer did not restore its own full baseline")
                require(isinstance(hashes, list) and len(hashes) == boundary
                        and all(isinstance(x, str) and re.fullmatch(r"[a-f0-9]{64}", x) and x != baseline[i]
                                for i, x in enumerate(hashes)), "No actual intermediate native geometry change")
            return report
        time.sleep(min(.25, max(0, deadline - time.monotonic())))
    raise ValidationFailure("Native geometry rollback timed out; no recovery retry is authorized")


def validate_native_geometry_persistence(state_root: Path, task_id: str, samples: list[dict[str, Any]],
                                         plans: list[dict[str, Any]], *, timeout_seconds: float = 420) -> dict[str, str]:
    require(len(samples) == len(plans) == 3, "Missing geometry persistence targets")
    state = samples[0]["state"]
    request = {"runId": task_id, "documentId": state["documentId"], "modelId": state["modelId"],
               "targets": [{"kind": item["kind"], "objectId": item["objectId"], "sourceGuid": item["sourceGuid"],
                            "formGuid": plan["edits"][0]["formGuid"]} for item, plan in zip(samples, plans)]}
    publish_atomic(state_root / "mcp-geometry-persistence-request.json", json.dumps(request))
    path = state_root / "mcp-geometry-persistence-result.properties"
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if path.is_file():
            require(path.stat().st_size <= 65536, "Geometry persistence evidence exceeds budget")
            report: dict[str, str] = {}
            for line in path.read_text().splitlines():
                if not line or line.startswith("#"): continue
                key, separator, value = line.partition("=")
                require(bool(separator) and key not in report, "Invalid geometry persistence report")
                report[key] = value
            require(report.get("runId") == task_id and report.get("status") == "PASS"
                    and report.get("geometrySaveReopen") == "PASS", "Native geometry persistence not proven")
            require(all(report.get(key) == "true" for key in ("fixtureUnchanged", "originalReopened", "freshNativeDocument"))
                    and report.get("persistenceObjectKinds") == "3", "Incomplete native geometry round-trip")
            before_hashes = report.get("savedGeometryHashes", "").split(",")
            require(len(before_hashes) == 3 and all(re.fullmatch(r"[a-f0-9]{64}", value) for value in before_hashes)
                    and report["savedGeometryHashes"] == report.get("reopenedGeometryHashes"), "Stored geometry changed on disk round-trip")
            require(re.fullmatch(r"[a-f0-9]{64}", report.get("savedBindingsHash", ""))
                    and report["savedBindingsHash"] == report.get("reopenedBindingsHash")
                    and re.fullmatch(r"[a-f0-9]{64}", report.get("savedFileSha256", "")), "Missing persisted binding/file fingerprints")
            return report
        time.sleep(min(.25, max(0, deadline - time.monotonic())))
    raise ValidationFailure("Native geometry persistence timed out; old document/history handles must not be reused")


def validate_ordinary_geometry(client: McpClient, state_root: Path, task_id: str, *, samples=None, planner=None, persistence=True, pose_override=None, capture=None, response_loss=False) -> None:
    evidence: dict[str, Any] = {"runId": task_id, "status": "RUNNING", "phase": "DISCOVER", "comparison": "SDK_FLOAT32_BITS_AND_EXACT_IDENTITIES"}
    path = state_root / "mcp-ordinary-geometry.json"
    planner = ordinary_edit if planner is None else planner
    capture = validate_ordinary_pose_capture if capture is None else capture
    originals = validate_stored_authoring(client, state_root) if samples is None else samples
    require([x["kind"] for x in originals] == ["art_mesh", "warp_deformer", "rotation_deformer"], "Unexpected geometry target order")
    current = originals
    prep_receipt = None
    prep_position = None
    write_receipt = None
    write_restored = False
    prep_restored = False
    native_rollback_known = True
    document_restoration_known = True
    def state():
        return tool_call(client, "turboism.render.state", {})["state"]
    def reread(samples):
        binding = state()
        return [read_stored_object(client, item["kind"], item["objectId"], binding) for item in samples]
    def equal_rows(actual, expected, message):
        require(len(actual) == len(expected) and all(stored_rows_equal(a["rows"], b["rows"]) for a, b in zip(actual, expected)), message)
    def undo_receipt(receipt, expected_position=None):
        history = history_snapshot(client)
        position = receipt["historyAfter"].get("position") if expected_position is None else expected_position
        require(position is not None and history["position"] == position, "Cleanup history position changed")
        args = history_guard_arguments(history, undo=True)
        require(args["expectedTopEntryId"] == receipt["historyEntryId"], "Cleanup history entry changed")
        require(args.get("expectedTransactionId") == receipt["transactionId"], "Cleanup transaction changed")
        tool_call(client, "turboism.history.undo", args)
    try:
        rotation = next(x for x in current if x["kind"] == "rotation_deformer")
        if not any(r.get("snapshotEligible") is True for r in rotation["rows"]):
            require(not any(r.get("kind") == "axis" for r in rotation["rows"]), "Rotation has unsupported existing axes")
            # Explicit fixture preparation uses a real discovered axis/GUID and the typed initializer.
            # This creates bindings/forms; it never edits or unlocks the default stored form.
            axes = [r for x in current for r in x["rows"] if r.get("kind") == "axis"
                    and r["minimum"] < r["defaultValue"] < r["maximum"]]
            require(bool(axes), "No discovered ordinary parameter for fixture preparation")
            axis = axes[0]
            plan = tool_call(client, "turboism.authoring.bindings.initialize_plan", {
                "documentId": rotation["state"]["documentId"], "modelId": rotation["state"]["modelId"],
                "objectId": rotation["objectId"], "kind": rotation["kind"],
                "authoringSnapshotId": rotation["snapshotId"], "axes": [{
                    "parameterId": axis["parameterId"], "parameterGuid": axis["parameterGuid"],
                    "keys": [axis["minimum"], axis["defaultValue"], axis["maximum"]]}]})
            evidence["phase"] = "PREPARE_ROTATION"
            preparation_before = history_snapshot(client)
            prep_position = preparation_before["position"] + 1
            prepared = tool_result(client, "turboism.authoring.bindings.initialize", {
                "documentId": rotation["state"]["documentId"], "modelId": rotation["state"]["modelId"],
                "operationId": task_id + "-rotation-prepare", "planId": plan["planId"], "target": {
                    "objectId": rotation["objectId"], "kind": rotation["kind"], "sourceGuid": rotation["sourceGuid"],
                    "axes": plan["axes"]}})
            prep_receipt = prepared.get("receipt")
            evidence["preparation"] = prepared
            require(prepared.get("ok") is True and prepared.get("outcome") == "APPLIED" and prep_receipt,
                    "Rotation fixture preparation not confirmed")
            require(history_snapshot(client)["position"] == prep_position,
                    "Rotation preparation did not create one native Undo step")
            current = reread(current)
        plans = [planner(item) for item in current]
        evidence["phase"] = "NATIVE_FAULT_ROLLBACK"
        native_before_state = state()
        native_rollback_known = False
        evidence["nativeRollback"] = validate_native_geometry_rollback(
            state_root, task_id, current, [target for target, _ in plans])
        native_after_state = state()
        require(all(native_before_state[key] == native_after_state[key] for key in
                    ("documentId", "modelId", "historyGeneration", "poseHash")), "Native rollback changed render scope or pose")
        evidence["nativeRollbackRenderState"] = {"before": native_before_state, "after": native_after_state}
        restored = reread(current)
        equal_rows(restored, current, "MCP readback disagrees with native rollback restoration")
        native_rollback_known = True
        current = restored
        plans = [planner(item) for item in current]
        poses = ordinary_poses(current, [target for target, _ in plans]) if pose_override is None else pose_override
        evidence["phase"] = "RENDER_BEFORE"
        evidence["renderBefore"] = capture(client, state_root, task_id + "-poses-before", poses)
        current = reread(current)
        plans = [planner(item) for item in current]
        expected = [{**sample, "rows": expected_rows} for sample, (_, expected_rows) in zip(current, plans)]
        operation = task_id + "-ordinary-three-kinds"
        binding = state()
        request = {"documentId": binding["documentId"], "modelId": binding["modelId"],
                   "operationId": operation, "targets": [plan for plan, _ in plans]}
        evidence["phase"] = "PREVIEW"
        preview = tool_call(client, "turboism.authoring.keyforms.preview", request)
        require(preview.get("applied") is False, "Preview reported a mutation")
        before = history_snapshot(client)
        evidence["request"] = request
        evidence["before"] = current
        evidence["phase"] = "WRITE"
        # Never replay writes after a timeout/unknown outcome. Receipt-based cleanup below is guarded.
        changed = (reconcile_injected_keyform_write(client, request, evidence) if response_loss
                   else tool_result(client, "turboism.authoring.keyforms.write", request))
        evidence["write"] = changed
        write_receipt = changed.get("receipt")
        require(changed.get("ok") is True and changed.get("outcome") == "APPLIED" and write_receipt,
                "Ordinary geometry write not confirmed")
        require(all(x.get("readbackVerified") is True for x in changed["targets"]), "Writer readback not verified")
        after = history_snapshot(client)
        require(after["position"] == before["position"] + 1, "Three-kind edit is not one native Undo step")
        committed = reread(current)
        evidence["observedAfter"] = committed
        evidence["expectedAfter"] = expected
        equal_rows(committed, expected, "Stored write did not preserve exact expected geometry and other forms")
        evidence["after"] = committed
        evidence["phase"] = "RENDER_AFTER"
        evidence["renderAfter"] = capture(client, state_root, task_id + "-poses-after", poses)
        before_frames, after_frames = evidence["renderBefore"]["frames"], evidence["renderAfter"]["frames"]
        evidence["poseImageChanges"] = [{"label": a["label"], "beforeSha256": a["sha256"], "afterSha256": b["sha256"],
                                         "changed": a["sha256"] != b["sha256"]} for a, b in zip(before_frames, after_frames)]
        require(any(x["changed"] for x in evidence["poseImageChanges"]), "No rendered pose changed after geometry writes")
        evidence["phase"] = "UNDO"
        undo_receipt(write_receipt)
        equal_rows(reread(current), current, "Undo did not exactly restore all stored rows")
        write_restored = True
        evidence["phase"] = "REDO"
        write_restored = False
        tool_call(client, "turboism.history.redo", history_guard_arguments(history_snapshot(client), undo=False))
        equal_rows(reread(current), expected, "Redo did not exactly restore all edited rows")
        evidence["phase"] = "FINAL_UNDO"
        undo_receipt(write_receipt)
        equal_rows(reread(current), current, "Final Undo did not restore prepared geometry")
        write_restored = True
        if persistence:
            evidence["phase"] = "PERSISTENCE_REDO"
            write_restored = False
            tool_call(client, "turboism.history.redo", history_guard_arguments(history_snapshot(client), undo=False))
            persistence_samples = reread(current)
            equal_rows(persistence_samples, expected, "Persistence Redo differs from verified geometry")
            evidence["phase"] = "SAVE_REOPEN"
            # Native close/reopen changes document, model and history bindings. Old receipts cannot
            # authorize cleanup after dispatch, including a failed or timed-out file workflow.
            document_restoration_known = False
            evidence["geometryPersistence"] = validate_native_geometry_persistence(
                state_root, task_id, persistence_samples, request["targets"])
            equal_rows(reread(originals), originals, "Fixture preparation was not exactly restored")
            write_restored = True
            prep_restored = bool(prep_receipt)
            document_restoration_known = True
        evidence["phase"] = "RESTORED"
        evidence["status"] = "PASS"
    except Exception as failure:
        evidence["failure"] = {"exceptionClass": failure.__class__.__name__, "message": sanitize(str(failure))}
        raise
    finally:
        try:
            if document_restoration_known and native_rollback_known and write_receipt and not write_restored:
                undo_receipt(write_receipt)
                equal_rows(reread(current), current, "Failure cleanup did not restore geometry")
                write_restored = True
            if document_restoration_known and native_rollback_known and prep_receipt and not prep_restored:
                undo_receipt(prep_receipt, prep_position)
                equal_rows(reread(originals), originals, "Failure cleanup did not restore original bindings")
                prep_restored = True
        except Exception as cleanup_failure:
            evidence["cleanupError"] = {"exceptionClass": cleanup_failure.__class__.__name__}
            if "failure" not in evidence:
                raise
        finally:
            evidence["writeRestored"] = write_restored
            evidence["nativeRollbackVerified"] = native_rollback_known and "nativeRollback" in evidence
            evidence["geometryPersistenceVerified"] = document_restoration_known and "geometryPersistence" in evidence
            evidence["preparationRestored"] = prep_restored if prep_receipt else "NOT_NEEDED"
            if evidence["status"] != "PASS":
                evidence["status"] = "FAIL"
            path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    if len(sys.argv) not in (3, 4) or (len(sys.argv) == 4 and sys.argv[3] not in {"--read-only-inspection", "--head-edit", "--head-save", "--head-qa", "--native-observe", "--native-edit"}):
        print("usage: mcp-host-validation-client.py <turboism-home> <task-id> [--read-only-inspection|--head-edit|--head-save|--head-qa]", file=sys.stderr)
        return 2
    native_edit = len(sys.argv) == 4 and sys.argv[3] == "--native-edit"
    native_observe = len(sys.argv) == 4 and sys.argv[3] == "--native-observe"
    read_only = native_observe or len(sys.argv) == 4 and sys.argv[3] == "--read-only-inspection"
    head_edit = len(sys.argv) == 4 and sys.argv[3] == "--head-edit"
    head_save = len(sys.argv) == 4 and sys.argv[3] == "--head-save"
    head_qa = len(sys.argv) == 4 and sys.argv[3] == "--head-qa"
    home = Path(sys.argv[1]).resolve()
    task_id = sanitize(sys.argv[2])
    state_root = home / "state"
    result_path = state_root / "mcp-host-validation.properties"
    connection_path = state_root / "dev.turboism.plugin.mcp" / "mcp-connection.json"
    token_path = state_root / "dev.turboism.plugin.mcp" / "mcp.token"
    report = ["schemaVersion=1", f"runId={task_id}", "client=python-stdlib-http"]
    status = "FAIL"
    client: McpClient | None = None
    mutations: list[str] = []
    try:
        connection = await_connection(connection_path, 300)
        endpoint = text_value(connection.get("endpoint"), "endpoint")
        require("authorization" not in connection, "connection file contains authorization material")
        token = token_path.read_text(encoding="utf-8").strip()
        require(bool(re.fullmatch(r"[0-9a-f]{64}", token)), "mcp.token is malformed")
        require(token not in connection_path.read_text(encoding="utf-8"),
                "connection file leaks the bearer token")
        advertised_version = text_value(connection.get("protocolVersion"), "protocolVersion")
        report.append("assertion.connectionFile.status=PASS")
        report.append("assertion.tokenGatedConnection.status=PASS")
        client = (ReadOnlyInspectionClient if read_only else ResponseLossHeadEditingClient if head_edit else PacedHeadQaClient if head_qa else HeadPersistenceClient if head_save else McpClient)(endpoint, advertised_version, token)
        initialized = client.initialize()
        report.append("assertion.initialize.status=PASS")
        report.append(f"serverVersion={sanitize(str(initialized.get('protocolVersion')))}")

        tools = collect_pages(client, "tools/list", "tools")
        tool_names = {text_value(object_value(item, "tool").get("name"), "tool.name") for item in tools}
        require(tool_names == EXPECTED_TOOLS, f"tool catalog mismatch: {sorted(tool_names)}")
        require(all(isinstance(object_value(item, "tool").get("outputSchema"), dict) for item in tools),
                "one or more tools omit outputSchema")
        report.append(f"toolCount={len(tools)}")
        report.append("assertion.tools.status=PASS")

        coverage_entries, temporary_exceptions = validate_capability_ledger(client)
        report.append(f"coverageEntryCount={coverage_entries}")
        report.append(f"temporaryPublicExceptionCount={temporary_exceptions}")
        report.append("assertion.capabilityLedger.status=PASS")

        resources = collect_pages(client, "resources/list", "resources")
        resource_uris = {text_value(object_value(item, "resource").get("uri"), "resource.uri")
                         for item in resources}
        require(resource_uris == EXPECTED_RESOURCES, f"resource catalog mismatch: {sorted(resource_uris)}")
        report.append(f"resourceCount={len(resources)}")
        report.append("assertion.resources.status=PASS")

        templates = collect_pages(client, "resources/templates/list", "resourceTemplates")
        template_uris = {
            text_value(object_value(item, "template").get("uriTemplate"), "template.uriTemplate")
            for item in templates
        }
        require(template_uris == EXPECTED_TEMPLATES, f"template catalog mismatch: {sorted(template_uris)}")
        report.append(f"templateCount={len(templates)}")
        report.append("assertion.templates.status=PASS")

        prompts = collect_pages(client, "prompts/list", "prompts")
        prompt_names = {text_value(object_value(item, "prompt").get("name"), "prompt.name")
                        for item in prompts}
        require(prompt_names == EXPECTED_PROMPTS, f"prompt catalog mismatch: {sorted(prompt_names)}")
        for prompt_name in (
            "inspect_active_document",
            "diagnose_environment",
            "inspect_model_diagnostics",
        ):
            prompt = client.call("prompts/get", {"name": prompt_name, "arguments": {}})
            require(len(array_value(prompt.get("messages"), "prompt messages")) == 1,
                    f"prompt {prompt_name} did not render")
        report.append(f"promptCount={len(prompts)}")
        report.append("assertion.prompts.status=PASS")

        document = await_resource(
            client,
            "turboism://active/document",
            lambda value: value.get("ok") is True
                and value.get("document") is not None
                and value.get("model") is not None,
            "active document and model",
        )
        assert_no_absolute_paths(document)
        report.append("assertion.document.status=PASS")
        report.append("assertion.noAbsolutePaths.status=PASS")
        inspection_history = history_snapshot(client) if read_only else None
        authoring = validate_stored_authoring(client, state_root)
        report.append("assertion.storedAuthoringThreeKindsAndImmutablePages.status=PASS")
        report.append(f"storedAuthoringObjectCount={len(authoring)}")
        render = validate_native_render(client, state_root)
        report.append("assertion.nativeRenderTypedImage.status=PASS")
        report.append("assertion.nativeRenderStaleAndStatePreservation.status=PASS")
        report.append(f"nativeRenderSha256={render['sha256']}")
        if native_edit:
            mutations.append("NATIVE_CODEX_ONE_KEYFORM_ATTEMPTED")
            validate_native_editor(home, task_id, client, authoring)
            report.append("assertion.realNativeAgentKeyformWriteReadbackUndoRedoRestore.status=PASS")
            client.delete_session()
            status = "PASS"
            return 0
        if read_only:
            if native_observe:
                validate_native_observer(home, task_id, client)
                report.append("assertion.realNativeAgentResourcesAndImageDelivery.status=PASS")
                report.append("modelVisionAcceptance=REQUIRES_SEPARATE_REVIEW")
            capture_read_only_inventory(client, state_root, render["state"])
            report.append("assertion.readOnlyInventoryRepeatedResources.status=PASS")
            head_candidates = capture_head_candidate_authoring(client, state_root, render["state"])
            report.append(f"headCandidateStatus={head_candidates['status']}")
            finish_read_only_inspection(client, state_root, task_id, inspection_history, authoring, render)
            report.append("assertion.readOnlyInspectionHistoryAndRender.status=PASS")
            report.append("inspectionMode=READ_ONLY_INSPECTION")
            client.delete_session()
            report.append("assertion.sessionDelete.status=PASS")
            status = "PASS"
            return 0

        if head_edit or head_save or head_qa:
            capture_read_only_inventory(client, state_root, render["state"])
            candidates = capture_head_candidate_authoring(client, state_root, render["state"])
            require(candidates["status"] == "READ_VERIFIED_CANDIDATE_ONLY", "Head editing requires the complete inspected profile")
            snapshots = json.loads((state_root / "mcp-head-candidate-authoring-private.json").read_text(encoding="utf-8"))
            by_kind = {item["kind"]: item for item in snapshots}
            ordered = [by_kind[k] for k in ("art_mesh", "warp_deformer", "rotation_deformer")]
            if head_save:
                mutations.append("BOUNDED_HEAD_PRODUCTION_SAVE_OPEN_ATTEMPTED")
                validate_production_save_open(client, state_root, task_id, samples=ordered,
                                              planner=bounded_head_edit, initialize_binding=False)
                report.append("assertion.headProductionSaveOpenGeometryBindingsAndPixels.status=PASS")
            elif head_qa:
                resources = json.loads((state_root / "mcp-read-only-inventory-private.json").read_text(encoding="utf-8"))
                poses = head_combination_poses(resources["turboism://active/model/parameters"]["parameters"])
                mutations.append("BOUNDED_HEAD_COMBINATION_SCAN_AND_EDIT_ATTEMPTED")
                validate_ordinary_geometry(client, state_root, task_id, samples=ordered, planner=bounded_head_edit,
                                          persistence=False, pose_override=poses, capture=validate_head_pose_suite)
                report.append("assertion.headCombinationFramesAndParameterRestoration.status=PASS")
                report.append("artQuality=NOT_AUTOMATICALLY_ACCEPTED")
            else:
                mutations.append("BOUNDED_HEAD_ORDINARY_GEOMETRY_ATTEMPTED")
                validate_ordinary_geometry(client, state_root, task_id, samples=ordered, planner=bounded_head_edit, persistence=False, response_loss=True)
                report.append("assertion.headWriteResponseLossReconciledWithoutReplay.status=PASS")
                report.append("assertion.headGeometryWriteUndoRedoRollbackAndRender.status=PASS")
                report.append("persistence=NOT_TESTED_IN_HEAD_EDIT_LANE")
            client.delete_session()
            report.append("assertion.sessionDelete.status=PASS")
            status = "PASS"
            return 0



        core = await_resource(
            client,
            "turboism://environment/cubism-core",
            lambda value: isinstance(value.get("version"), dict)
                and isinstance(value.get("capabilities"), dict),
            "Cubism Core diagnostics",
        )
        require(set(object_value(core.get("version"), "Core version")) == {"major", "minor", "patch"},
                "Core version shape mismatch")
        report.append("assertion.cubismCore.status=PASS")

        workspace = resource_json(client, "turboism://environment/workspace")
        require(workspace.get("availability") in {"AVAILABLE", "UNAVAILABLE"},
                "workspace availability is invalid")
        if workspace.get("availability") == "AVAILABLE":
            require(isinstance(workspace.get("available"), list), "workspace list is unavailable")
        report.append(f"workspaceAvailability={sanitize(str(workspace.get('availability')))}")
        report.append("assertion.workspace.status=PASS")

        workspace_layout = resource_json(client, "turboism://environment/workspace/layout")
        require(workspace_layout.get("availability") in {"AVAILABLE", "UNAVAILABLE"},
                "workspace layout availability is invalid")
        assert_no_absolute_paths(workspace_layout)
        report.append(f"workspaceLayoutAvailability={sanitize(str(workspace_layout.get('availability')))}")
        report.append("assertion.workspaceLayout.status=PASS")

        diagnostics = resource_json(client, "turboism://environment/diagnostics")
        require(isinstance(diagnostics.get("problems"), list), "diagnostics problems are unavailable")
        require(isinstance(diagnostics.get("truncated"), bool), "diagnostics truncation flag is invalid")
        assert_sanitized_diagnostics(diagnostics)
        report.append("assertion.diagnosticsSanitized.status=PASS")

        model_statistics = await_resource(
            client,
            "turboism://active/model/statistics",
            lambda value: isinstance(value.get("parameterCount"), int)
                and isinstance(value.get("textureCount"), int),
            "active model statistics",
        )
        require(model_statistics.get("offscreenRenderingCount") is None
                or isinstance(model_statistics.get("offscreenRenderingCount"), int),
                "offscreen statistics are neither null nor integer")
        report.append("assertion.modelStatistics.status=PASS")

        model_textures = await_resource(
            client,
            "turboism://active/model/textures",
            lambda value: all(isinstance(value.get(key), list) for key in (
                "rawImages", "modelImageGroups", "textureAtlases"
            )),
            "active model textures",
        )
        assert_no_absolute_paths(model_textures)
        report.append("assertion.modelTextures.status=PASS")

        parameters = await_resource(
            client, "turboism://active/model/parameters",
            lambda value: isinstance(value.get("parameters"), list) and bool(value.get("parameters")),
            "active model parameters",
        )
        parameter_values = array_value(parameters.get("parameters"), "parameters")
        # Establish native texture evidence before any authoring or history operation.
        validate_native_texture_roundtrip(state_root, task_id)
        report.append("assertion.textureNativeLayersAndPixels.status=PASS")
        report.append("assertion.textureSaveReopen.status=PASS")
        report.append("texturePersistence=FIVE_OPERATION_KINDS_SAVED_AND_REOPENED")
        rejected_count = validate_audit_input_guards(client, parameter_values)
        report.append(f"auditRejectedRequestCount={rejected_count}")
        report.append("assertion.auditPreflightNoMutation.status=PASS")
        # Glue's original-history invariant requires the untouched history tip. Run it before
        # other reversible matrices, whose final Undo intentionally leaves a Redo tail.
        glue_mutation, glue_version = validate_reversible_glue_authoring(client, task_id)
        mutations.append(glue_mutation)
        report.append(f"glueProviderVersion={sanitize(glue_version)}")
        report.append("assertion.glueRead.status=PASS")
        report.append("assertion.glueWriteReadback.status=PASS")
        report.append("assertion.glueNoOpAndStale.status=PASS")
        report.append("assertion.glueUndoRedo.status=PASS")
        report.append("assertion.glueTransaction.status=PASS")
        report.append("assertion.glueRollback.status=PASS")
        report.append("assertion.glueFinalRestoration.status=PASS")

        mutations.append("ORDINARY_GEOMETRY_MATRIX_ATTEMPTED")
        validate_ordinary_geometry(client, state_root, task_id)
        report.append("assertion.ordinaryGeometryThreeKindsUndoRedoRestoration.status=PASS")
        inversion = validate_reversible_binding_inversion(client, parameter_values)
        mutations.append(inversion)
        report.append("assertion.explicitBindingScope.status=PASS")
        report.append("assertion.bindingUndoRedoRestoration.status=PASS")

        parameters = await_resource(
            client,
            "turboism://active/model/parameters",
            lambda value: isinstance(value.get("parameters"), list)
                and bool(value.get("parameters")),
            "active model parameters",
        )
        parameter_values = array_value(parameters.get("parameters"), "parameters")
        report.append(f"parameterCount={len(parameter_values)}")
        report.append("assertion.parameterRead.status=PASS")

        parameter_mutation = validate_reversible_parameter_write(client, parameter_values)
        mutations.append(parameter_mutation)
        report.append("assertion.parameterWriteReadback.status=PASS")
        report.append("assertion.parameterWriteCleanup.status=PASS")

        texture_operations = validate_reversible_texture_authoring(client, task_id)
        mutations.append("TEXTURE_LIBRARY_CHANGED_UNDONE_REDONE_AND_RESTORED")
        report.append(f"textureAuthoringOperationCount={texture_operations}")
        report.append("assertion.textureWriteReadback.status=PASS")
        report.append("assertion.textureUndoRedoRestoration.status=PASS")
        report.append("assertion.textureDeletionScope.status=PASS")

        await_resource(
            client,
            "turboism://active/model/hierarchy",
            lambda value: value.get("ok") is True,
            "active model hierarchy",
        )
        report.append("assertion.hierarchy.status=PASS")

        history_resource = await_resource(
            client,
            "turboism://active/document/history",
            lambda value: value.get("availability") == "AVAILABLE",
            "active document history",
        )
        history = history_snapshot(client)
        for field in ("generation", "revision", "position"):
            require(history_resource.get(field) == history.get(field),
                    f"history resource and history.read differ at {field}")
        before_stale = history_identity(history)
        stale_history = tool_result(client, "turboism.history.undo", {
            "expectedGeneration": integer_value(history.get("generation"), "history.generation") + 1,
            "expectedRevision": integer_value(history.get("revision"), "history.revision"),
            "steps": 1,
        })
        require(stale_history.get("ok") is False,
                "stale history guard unexpectedly succeeded")
        require(stale_history.get("outcome") == "REJECTED_STALE",
                "stale history guard did not report REJECTED_STALE")
        require(before_stale == history_identity(history_snapshot(client)),
                "stale history guard changed native history")
        report.append("assertion.historyRead.status=PASS")
        report.append("assertion.historyGuard.status=PASS")

        commands = resource_json(client, "turboism://host/editor-commands")
        available = set(array_value(commands.get("availableDirectCommands"), "available commands"))
        if "hide.or.restore.palette" in available:
            command_result = tool_call(client, "turboism.editor_commands.execute", {
                "kind": "direct", "commandId": "hide.or.restore.palette"
            })
            require(command_result.get("status") == "EXECUTED", "safe direct command did not execute")
            tool_call(client, "turboism.editor_commands.execute", {
                "kind": "direct", "commandId": "hide.or.restore.palette"
            })
            report.append("commandValidation=EXECUTED_AND_RESTORED")
        else:
            report.append("commandValidation=UNAVAILABLE")
        report.append("assertion.editorCommandCatalog.status=PASS")

        unknown = rpc_error(client, "resources/read", {"uri": "turboism://validation/not-found"})
        require(unknown.get("code") == -32002, "unknown resource did not map to -32002")
        report.append("assertion.resourceNotFound.status=PASS")

        if glue_version == "5.3.03":
            validate_authorized_model_open(client, state_root, task_id)
            report.append("assertion.authorizedOpenExistingTaskCopy.status=PASS")
            validate_authorized_fresh_model_open(client, state_root, task_id)
            report.append("assertion.authorizedOpenFreshDocumentGeometryBindings.status=PASS")
            validate_production_save_open(client, state_root, task_id)
            report.append("assertion.productionSaveAsOpenModifiedGeometryBindings.status=PASS")
        else:
            report.append("authorizedOpenExistingTaskCopy=NOT_ADMITTED_EXACT_VERSION")
        client.delete_session()
        report.append("assertion.sessionDelete.status=PASS")
        status = "PASS"
    except Exception as failure:
        report.append(f"error={sanitize(failure.__class__.__name__ + ': ' + str(failure))}")
    finally:
        if client is not None and client.session_id is not None:
            try:
                client.delete_session()
            except Exception as failure:
                report.append(f"cleanupError={sanitize(failure.__class__.__name__)}")
        report.append("authentication=bearer-write-gated")
        report.append(
            "modelMutation="
            + sanitize(",".join(mutations) if mutations else "NONE")
        )
        report.append(f"status={status}")
        publish_atomic(result_path, "\n".join(report) + "\n")
    return 0 if status == "PASS" else 1


def validate_authorized_model_open(client: McpClient, state_root: Path, task_id: str,
                                   *, timeout_seconds: float = 60) -> dict[str, Any]:
    """Exercise the production chooser grant and OPEN on the already-open task copy.

    The test-only responder approves that exact READ purpose and task fixture once.
    This proves dispatch/focus behavior, not first-open deserialization or persistence.
    """
    before = tool_call(client, "turboism.documents.read", {})
    operation = task_id + "-authorized-open"
    grant_operation = operation + "-grant"
    grant = tool_call(client, "turboism.files.request_grant", {
        "operationId": grant_operation, "purpose": "Turboism validation CMO3 OPEN " + task_id,
        "mode": "READ", "allowedExtensions": ["cmo3"]})
    deadline = time.monotonic() + timeout_seconds
    while grant.get("state") == "PENDING" and time.monotonic() < deadline:
        time.sleep(0.25)
        grant = tool_call(client, "turboism.files.grant_status", {"operationId": grant_operation})
    require(grant.get("state") == "GRANTED" and grant.get("grantId"), "Test READ chooser grant was not granted")
    request = {"operationId": operation, "grantId": grant["grantId"],
               "documentSnapshotId": before["snapshotId"]}
    result = tool_call(client, "turboism.documents.open", request)
    require(result.get("outcome") == "EXECUTED" and result.get("code") ==
            "NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS", "Authorized OPEN was not dispatched")
    require(result.get("persistenceVerified") is False and result.get("reopenVerified") is False,
            "OPEN incorrectly claimed persistence")
    require(tool_call(client, "turboism.documents.file_result", {"operationId": operation}) == result,
            "OPEN retained result differs")
    require(tool_call(client, "turboism.documents.open", request) == result, "OPEN replay differs")
    after = tool_call(client, "turboism.documents.read", {})
    require(before.get("state") == after.get("state"), "Already-open task copy changed document inventory or authoring state")
    consumed = tool_call(client, "turboism.files.grant_status", {"operationId": grant_operation})
    require(consumed.get("state") == "CONSUMED", "OPEN did not consume READ grant")
    evidence = {"status": "PASS", "scope": "ALREADY_OPEN_TASK_COPY", "runId": task_id,
                "testChooserApproval": True, "result": result, "before": before, "after": after,
                "grantState": consumed["state"], "firstOpenVerified": False,
                "persistenceVerified": False, "reopenVerified": False}
    assert_no_absolute_paths(evidence)
    publish_atomic(state_root / "mcp-authorized-open.json", json.dumps(evidence, indent=2) + "\n")
    return evidence


def await_fresh_open_report(state_root: Path, name: str, task_id: str, expected_status: str,
                            timeout_seconds: float = 150) -> dict[str, Any]:
    path = state_root / name
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if path.is_file():
            require(not path.is_symlink() and path.stat().st_size <= 256 * 1024, "Invalid fresh OPEN evidence")
            value = object_value(json.loads(path.read_text()), "fresh OPEN evidence")
            require(value.get("runId") == task_id and value.get("status") == expected_status,
                    "Fresh OPEN native stage did not confirm " + expected_status)
            return value
        time.sleep(0.25)
    raise ValidationFailure("Fresh OPEN native stage timed out; no replay or cleanup writes")


def validate_authorized_fresh_model_open(client: McpClient, state_root: Path, task_id: str,
                                         *, timeout_seconds: float = 60) -> dict[str, Any]:
    """Close only the owned task document via test probe; production MCP alone reopens it."""
    evidence: dict[str, Any] = {"runId": task_id, "status": "FAIL", "phase": "INSPECT",
                               "testChooserApproval": True, "scope": "CLOSED_TASK_COPY_FRESH_LOAD",
                               "persistenceVerified": False}
    try:
        samples = validate_stored_authoring(client, state_root)
        state = tool_call(client, "turboism.render.state", {})["state"]
        request = {"runId": task_id, "documentId": state["documentId"], "modelId": state["modelId"],
                   "targets": [{"kind": item["kind"], "objectId": item["objectId"],
                                "sourceGuid": item["sourceGuid"],
                                "formGuid": next(row["formGuid"] for row in item["rows"] if row.get("formGuid"))}
                               for item in samples]}
        evidence["before"] = samples
        evidence["phase"] = "CLOSE_OWNED_TASK_DOCUMENT"
        publish_atomic(state_root / "mcp-fresh-open-prepare.json", json.dumps(request))
        closed = await_fresh_open_report(state_root, "mcp-fresh-open-closed.json", task_id, "CLOSED")
        require(closed.get("noActiveDocument") is True, "Probe did not confirm an empty active document")
        evidence["closed"] = closed
        empty = tool_call(client, "turboism.documents.read", {})
        require(empty.get("state", {}).get("activeDocument") is None
                and empty.get("state", {}).get("authoringState") is None,
                "Public document inventory still has an authoring document")
        evidence["emptyInventory"] = empty
        evidence["phase"] = "READ_GRANT"
        operation = task_id + "-fresh-open"
        grant_operation = operation + "-grant"
        grant = tool_call(client, "turboism.files.request_grant", {
            "operationId": grant_operation, "purpose": "Turboism validation CMO3 FRESH OPEN " + task_id,
            "mode": "READ", "allowedExtensions": ["cmo3"]})
        deadline = time.monotonic() + timeout_seconds
        while grant.get("state") == "PENDING" and time.monotonic() < deadline:
            time.sleep(0.25)
            grant = tool_call(client, "turboism.files.grant_status", {"operationId": grant_operation})
        require(grant.get("state") == "GRANTED" and grant.get("grantId"), "Fresh READ grant unavailable")
        evidence["phase"] = "PRODUCTION_OPEN"
        result = tool_call(client, "turboism.documents.open", {
            "operationId": operation, "grantId": grant["grantId"], "documentSnapshotId": empty["snapshotId"]})
        evidence["openResult"] = result
        require(result.get("outcome") == "EXECUTED" and result.get("code") ==
                "NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS", "Fresh OPEN dispatch unconfirmed; never replay")
        require(result.get("persistenceVerified") is False and result.get("reopenVerified") is False,
                "Dispatch incorrectly claims persistence or reopened content")
        require(tool_call(client, "turboism.documents.file_result", {"operationId": operation}) == result,
                "Retained fresh OPEN result mismatch")
        evidence["phase"] = "REDISCOVER"
        deadline = time.monotonic() + timeout_seconds
        while True:
            discovered = tool_call(client, "turboism.documents.read", {})
            active = discovered.get("state", {}).get("activeDocument")
            if isinstance(active, dict) and active.get("kind") == "MODEL" and discovered["state"].get("authoringState"):
                break
            require(time.monotonic() < deadline, "Fresh document readiness timed out; do not redispatch")
            time.sleep(0.25)
        evidence["loadedInventory"] = discovered
        loaded_state = tool_call(client, "turboism.render.state", {})["state"]
        after = [read_stored_object(client, item["kind"], item["objectId"], loaded_state) for item in samples]
        for before, observed in zip(samples, after):
            require(before["sourceGuid"] == observed["sourceGuid"] and stored_rows_equal(before["rows"], observed["rows"]),
                    "Production fresh OPEN changed source identity or complete stored rows")
        evidence["after"] = after
        evidence["phase"] = "VERIFY_NATIVE_IDENTITY_AND_FULL_RECORDS"
        publish_atomic(state_root / "mcp-fresh-open-verify.json", json.dumps({"runId": task_id}))
        native = await_fresh_open_report(state_root, "mcp-fresh-open-result.json", task_id, "PASS")
        require(native.get("freshNativeDocument") is True and native.get("geometryKinds") == 3
                and native.get("bindingsEqual") is True and native.get("taskFileIdentityVerified") is True
                and native.get("fixtureUnchanged") is True, "Fresh native identity/geometry/bindings not verified")
        for key in ("GeometryHashes", "BindingsHash"):
            require(native.get("before" + key) and native["before" + key] == native.get("after" + key),
                    "Fresh OPEN complete native record hashes differ")
        hashes = native.get("beforeGeometryHashes")
        require(isinstance(hashes, list) and len(hashes) == 3
                and all(isinstance(h, str) and re.fullmatch(r"[0-9a-f]{64}", h) for h in hashes)
                and isinstance(native.get("beforeBindingsHash"), str)
                and re.fullmatch(r"[0-9a-f]{64}", native["beforeBindingsHash"]), "Incomplete native geometry/binding hashes")
        require(isinstance(closed.get("fixtureSha256"), str)
                and re.fullmatch(r"[0-9a-f]{64}", closed["fixtureSha256"])
                and native.get("fixtureSha256") == closed["fixtureSha256"], "Task fixture bytes changed")
        require(tool_call(client, "turboism.files.grant_status", {"operationId": grant_operation}).get("state") == "CONSUMED",
                "Fresh OPEN did not consume READ grant")
        evidence["nativeVerification"] = native
        evidence["firstOpenFromNoDocumentVerified"] = True
        evidence["reopenedGeometryBindingsVerified"] = True
        evidence["phase"] = "READY_VERIFIED"
        evidence["status"] = "PASS"
        return evidence
    except Exception as failure:
        evidence["failure"] = {"exceptionClass": failure.__class__.__name__, "message": sanitize(str(failure))}
        raise
    finally:
        assert_no_absolute_paths(evidence)
        publish_atomic(state_root / "mcp-authorized-fresh-open.json", json.dumps(evidence, indent=2) + "\n")


def validation_grant(client: McpClient, task_id: str, operation: str, purpose: str, mode: str,
                     timeout_seconds: float = 60) -> str:
    value = tool_call(client, "turboism.files.request_grant", {"operationId": operation,
        "purpose": "Turboism validation " + purpose + " " + task_id,
        "mode": mode, "allowedExtensions": ["cmo3"]})
    deadline = time.monotonic() + timeout_seconds
    while value.get("state") == "PENDING" and time.monotonic() < deadline:
        time.sleep(.25)
        value = tool_call(client, "turboism.files.grant_status", {"operationId": operation})
    require(value.get("state") == "GRANTED" and value.get("grantId"), "Validation file grant unavailable")
    return value["grantId"]


def validation_file_dispatch(client: McpClient, tool: str, args: dict[str, Any]) -> dict[str, Any]:
    require(tool in {"turboism.documents.open", "turboism.documents.save_as"}, "Unexpected validation file command")
    try:
        result = tool_result(client, tool, args)
    except Exception:
        # Read-only reconciliation, never repeat the write or invent a new operation ID.
        result = tool_result(client, "turboism.documents.file_result", {"operationId": args["operationId"]})
    require(result.get("outcome") == "EXECUTED" and result.get("persistenceVerified") is False
            and result.get("reopenVerified") is False, "File dispatch outcome unconfirmed; no retry or cleanup writes")
    require(tool_result(client, "turboism.documents.file_result", {"operationId": args["operationId"]}) == result,
            "File dispatch retained outcome mismatch")
    return result


def validation_open_from_empty(client: McpClient, grant: str, operation: str,
                               timeout_seconds: float = 60) -> dict[str, Any]:
    empty = tool_call(client, "turboism.documents.read", {})
    require(empty.get("state", {}).get("activeDocument") is None
            and empty.get("state", {}).get("authoringState") is None, "Owned document was not closed")
    result = validation_file_dispatch(client, "turboism.documents.open", {
        "operationId": operation, "grantId": grant, "documentSnapshotId": empty["snapshotId"]})
    require(result.get("code") == "NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS", "OPEN dispatch code mismatch")
    deadline = time.monotonic() + timeout_seconds
    while True:
        inventory = tool_call(client, "turboism.documents.read", {})
        active = inventory.get("state", {}).get("activeDocument")
        if isinstance(active, dict) and active.get("kind") == "MODEL" and inventory["state"].get("authoringState"):
            return {"emptyInventory": empty, "result": result, "loadedInventory": inventory}
        require(time.monotonic() < deadline, "Model readiness timed out; never repeat OPEN")
        time.sleep(.25)


def persistence_request(task_id: str, state: dict[str, Any], samples: list[dict[str, Any]]) -> dict[str, Any]:
    return {"runId": task_id, "documentId": state["documentId"], "modelId": state["modelId"], "targets": [
        {"kind": item["kind"], "objectId": item["objectId"], "sourceGuid": item["sourceGuid"],
         "formGuid": next(row["formGuid"] for row in item["rows"] if row.get("formGuid"))} for item in samples]}


def validate_production_save_open(client: McpClient, state_root: Path, task_id: str, *, samples=None, planner=None, initialize_binding=True) -> dict[str, Any]:
    evidence: dict[str, Any] = {"runId": task_id, "status": "FAIL", "phase": "BASELINE",
        "scope": "PRODUCTION_GRANT_SAVE_AS_CLOSE_GRANT_OPEN_RESTORE", "testChooserApproval": True}
    def state(): return tool_call(client, "turboism.render.state", {})["state"]
    def reread(samples):
        current = state()
        return [read_stored_object(client, x["kind"], x["objectId"], current) for x in samples]
    def equal(actual, expected, message):
        require(len(actual) == len(expected) and all(a["sourceGuid"] == b["sourceGuid"]
            and stored_rows_equal(a["rows"], b["rows"]) for a, b in zip(actual, expected)), message)
    planner = ordinary_edit if planner is None else planner
    try:
        original = validate_stored_authoring(client, state_root) if samples is None else samples
        require([x["kind"] for x in original] == ["art_mesh", "warp_deformer", "rotation_deformer"], "Unexpected persistence target order")
        evidence["original"] = original
        publish_atomic(state_root / "mcp-production-save-baseline.json", json.dumps(persistence_request(task_id, state(), original)))
        baseline = await_fresh_open_report(state_root, "mcp-production-save-baseline-result.json", task_id, "BASELINE")
        evidence["nativeBaseline"] = baseline
        rotation = next(x for x in original if x["kind"] == "rotation_deformer")
        if initialize_binding:
            require(not any(r.get("kind") == "axis" for r in rotation["rows"]), "Persistence fixture Rotation must start unbound")
            axis = next(r for x in original for r in x["rows"] if r.get("kind") == "axis"
                        and r["minimum"] < r["defaultValue"] < r["maximum"])
            evidence["phase"] = "INITIALIZE_BINDING"
            plan = tool_call(client, "turboism.authoring.bindings.initialize_plan", {
                "documentId": rotation["state"]["documentId"], "modelId": rotation["state"]["modelId"],
                "objectId": rotation["objectId"], "kind": rotation["kind"], "authoringSnapshotId": rotation["snapshotId"],
                "axes": [{"parameterId": axis["parameterId"], "parameterGuid": axis["parameterGuid"],
                          "keys": [axis["minimum"], axis["defaultValue"], axis["maximum"]]}]})
            initialized = tool_call(client, "turboism.authoring.bindings.initialize", {
                "documentId": rotation["state"]["documentId"], "modelId": rotation["state"]["modelId"],
                "operationId": task_id + "-production-save-bind", "planId": plan["planId"],
                "target": {"objectId": rotation["objectId"], "kind": rotation["kind"],
                           "sourceGuid": rotation["sourceGuid"], "axes": plan["axes"]}})
            require(initialized.get("outcome") == "APPLIED", "Persistence binding initialization unconfirmed")
            evidence["initialization"] = initialized
        else:
            require(any(r.get("kind") == "axis" for r in rotation["rows"])
                    and any(r.get("snapshotEligible") is True for r in rotation["rows"]),
                    "Prebound persistence requires existing eligible Rotation forms")
            evidence["initialization"] = "NOT_NEEDED_EXISTING_BINDINGS_PRESERVED"
        prepared = reread(original)
        edits = [planner(item) for item in prepared]
        poses = ordinary_poses(prepared, [target for target, _ in edits])
        evidence["phase"] = "RENDER_BEFORE"
        evidence["renderBefore"] = validate_ordinary_pose_capture(client, state_root, task_id + "-save-before", poses)
        prepared = reread(prepared)
        edits = [planner(item) for item in prepared]
        request = {"documentId": prepared[0]["state"]["documentId"], "modelId": prepared[0]["state"]["modelId"],
                   "operationId": task_id + "-production-save-geometry", "targets": [x for x, _ in edits]}
        evidence["phase"] = "WRITE_GEOMETRY"
        tool_call(client, "turboism.authoring.keyforms.preview", request)
        write = tool_call(client, "turboism.authoring.keyforms.write", request)
        evidence["write"] = write
        require(write.get("outcome") == "APPLIED" and all(x.get("readbackVerified") is True for x in write["targets"]),
                "Production persistence geometry write unconfirmed")
        modified = reread(prepared)
        expected = [{**sample, "rows": rows} for sample, (_, rows) in zip(prepared, edits)]
        equal(modified, expected, "Geometry differs from planned complete rows")
        require(all(not stored_rows_equal(a["rows"], b["rows"]) for a, b in zip(modified, prepared)),
                "Every primitive must contain an actual geometry edit")
        evidence["modified"] = modified
        evidence["phase"] = "RENDER_MODIFIED"
        evidence["renderModified"] = validate_ordinary_pose_capture(client, state_root, task_id + "-save-modified", poses)
        require(any(a["sha256"] != b["sha256"] for a, b in zip(evidence["renderBefore"]["frames"],
                evidence["renderModified"]["frames"])), "Modified geometry has no rendered difference")
        modified = reread(modified)
        evidence["phase"] = "WRITE_GRANT_SAVE_AS"
        save_operation = task_id + "-production-save-as"
        save_grant_op = save_operation + "-grant"
        grant = validation_grant(client, task_id, save_grant_op, "SAVE AS", "WRITE")
        # Chooser interaction may change state; only a fresh complete binding may authorize SAVE_AS.
        modified = reread(modified)
        equal(modified, expected, "Geometry changed during SAVE_AS authorization")
        save = validation_file_dispatch(client, "turboism.documents.save_as", {
            "operationId": save_operation, "grantId": grant, "expectedState": modified[0]["state"],
            "overwritePolicy": "REJECT_EXISTING"})
        evidence["saveResult"] = save
        require(tool_call(client, "turboism.files.grant_status", {"operationId": save_grant_op}).get("state") == "CONSUMED",
                "SAVE_AS did not consume WRITE grant")
        evidence["phase"] = "CLOSE_SAVED_DOCUMENT"
        saved_samples = reread(modified)
        equal(saved_samples, expected, "SAVE_AS changed geometry")
        publish_atomic(state_root / "mcp-production-save-prepare.json", json.dumps(persistence_request(task_id, state(), saved_samples)))
        closed = await_fresh_open_report(state_root, "mcp-production-save-closed.json", task_id, "CLOSED")
        evidence["nativeClosed"] = closed
        evidence["phase"] = "READ_GRANT_OPEN_SAVED"
        read_op = task_id + "-production-saved-open"
        grant = validation_grant(client, task_id, read_op + "-grant", "SAVED CMO3 OPEN", "READ")
        evidence["savedOpen"] = validation_open_from_empty(client, grant, read_op)
        loaded = reread(saved_samples)
        equal(loaded, expected, "Saved file did not retain modified complete geometry")
        evidence["loaded"] = loaded
        publish_atomic(state_root / "mcp-production-save-verify.json", json.dumps({"runId": task_id}))
        native = await_fresh_open_report(state_root, "mcp-production-save-result.json", task_id, "PASS")
        require(native.get("freshNativeDocument") is True and native.get("bindingsEqual") is True
                and native.get("taskFileIdentityVerified") is True and native.get("geometryKinds") == 3,
                "Saved file native identity or complete records not verified")
        for key in ("GeometryHashes", "BindingsHash"):
            require(native.get("before" + key) and native["before" + key] == native.get("after" + key),
                    "Saved file complete native records differ")
        if initialize_binding:
            require(native["beforeBindingsHash"] != baseline["bindingsHash"], "Binding modification was not exercised")
        else:
            require(native["beforeBindingsHash"] == baseline["bindingsHash"], "Existing head bindings changed before save")
        require(native.get("savedFileSha256") == closed.get("savedFileSha256")
                and isinstance(native.get("savedFileSha256"), str)
                and re.fullmatch(r"[0-9a-f]{64}", native["savedFileSha256"]), "Saved file hash unverified")
        evidence["nativeSavedVerification"] = native
        evidence["phase"] = "RENDER_RELOADED"
        evidence["renderReloaded"] = validate_ordinary_pose_capture(client, state_root, task_id + "-save-reloaded", poses)
        require([x["sha256"] for x in evidence["renderModified"]["frames"]] ==
                [x["sha256"] for x in evidence["renderReloaded"]["frames"]], "Saved/reloaded pose pixels differ")
        evidence["phase"] = "RESTORE_ORIGINAL_DOCUMENT"
        publish_atomic(state_root / "mcp-production-save-restore-close.json", json.dumps({"runId": task_id}))
        evidence["restoreClosed"] = await_fresh_open_report(state_root, "mcp-production-save-restore-closed.json",
                                                           task_id, "RESTORE_CLOSED")
        restore_op = task_id + "-production-restore"
        grant = validation_grant(client, task_id, restore_op + "-grant", "ORIGINAL CMO3 RESTORE", "READ")
        evidence["originalOpen"] = validation_open_from_empty(client, grant, restore_op)
        equal(reread(original), original, "Original task geometry not restored")
        publish_atomic(state_root / "mcp-production-save-restore-verify.json", json.dumps({"runId": task_id}))
        restored = await_fresh_open_report(state_root, "mcp-production-save-restore-result.json", task_id, "PASS")
        require(restored.get("originalRestored") is True and restored.get("fixtureSha256") == baseline.get("fixtureSha256")
                and restored.get("restoredGeometryHashes") == baseline.get("geometryHashes")
                and restored.get("restoredBindingsHash") == baseline.get("bindingsHash")
                and restored.get("savedFileSha256") == native.get("savedFileSha256"), "Original/native/file restoration not proven")
        evidence["restoration"] = restored
        evidence["phase"] = "RESTORED"
        evidence["geometryBindingsPersistenceVerified"] = True
        evidence["posePixelsPersistenceVerified"] = True
        evidence["status"] = "PASS"
        return evidence
    except Exception as failure:
        evidence["failure"] = {"exceptionClass": failure.__class__.__name__, "message": sanitize(str(failure))}
        raise
    finally:
        # File dispatch may change document/model/history bindings or have an unknown outcome.
        # Never undo against old receipts, close another document, or replay SAVE_AS/OPEN here.
        assert_no_absolute_paths(evidence)
        publish_atomic(state_root / "mcp-production-save-open.json", json.dumps(evidence, indent=2) + "\n")


def validate_native_texture_roundtrip(
    state_root: Path, task_id: str, *, timeout_seconds: float = 420,
) -> dict[str, str]:
    """Require exact-run native layer/pixel and save/reopen evidence before final PASS."""
    require(bool(task_id) and sanitize(task_id) == task_id, "invalid native round-trip run ID")
    request = state_root / "mcp-texture-roundtrip-request.properties"
    result = state_root / "mcp-texture-roundtrip-result.properties"
    publish_atomic(request, f"runId={task_id}\nstatus=REQUESTED\n")
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if result.is_file():
            require(result.stat().st_size <= 65536, "native round-trip evidence exceeds its bound")
            values: dict[str, str] = {}
            for line in result.read_text(encoding="utf-8").splitlines():
                if not line or line.startswith("#"):
                    continue
                key, separator, value = line.partition("=")
                require(bool(separator) and key not in values, "invalid native round-trip evidence")
                values[key] = value
            require(values.get("runId") == task_id, "native round-trip belongs to another task")
            require(values.get("status") == "PASS",
                    "native texture round-trip failed: " + values.get("error", "missing terminal result"))
            for key in ("nativeLayerPixelUndoRedo", "saveReopen"):
                require(values.get(key) == "PASS", f"native round-trip lacks {key} evidence")
            for key in ("fixtureUnchanged", "originalReopened"):
                require(values.get(key) == "true", f"native round-trip lacks {key} evidence")
            require(values.get("persistenceOperationKinds") == "5", "not all texture operations were persisted")
            for before, after in (("savedFingerprint", "reopenedFingerprint"),
                                  ("rawPixelsBefore", "rawPixelsRestored")):
                digest = values.get(before, "")
                require(len(digest) == 64 and all(char in "0123456789abcdef" for char in digest)
                        and digest == values.get(after), "native round-trip fingerprint mismatch")
            return values
        time.sleep(min(0.25, max(0, deadline - time.monotonic())))
    raise ValidationFailure("native texture round-trip timed out without complete evidence")


def texture_content(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Compare actual library metadata independently from advancing state tokens."""
    return {key: array_value(snapshot.get(key), f"textures.{key}")
            for key in ("rawImages", "modelImageGroups", "textureAtlases")}


def texture_snapshot(client: McpClient) -> dict[str, Any]:
    snapshot = tool_call(client, "turboism.textures.read", {"operation": "list"})
    text_value(snapshot.get("stateToken"), "texture read correlation token")
    object_value(snapshot.get("state"), "texture expected state")
    texture_content(snapshot)
    assert_no_absolute_paths(snapshot)
    return snapshot


def texture_write_cycle(
    client: McpClient,
    arguments: dict[str, Any],
    check: Callable[[dict[str, Any], dict[str, Any], dict[str, Any]], None],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Prove one native write, one Undo, one Redo and complete metadata restoration."""
    before = texture_snapshot(client)
    original = texture_content(before)
    position = integer_value(history_snapshot(client).get("position"), "texture history position")
    after: dict[str, Any] = {}
    receipt: dict[str, Any] = {}
    try:
        receipt = tool_call(client, "turboism.textures.write", {
            **arguments, "expectedState": before["state"],
        })
        require(receipt.get("outcome") == "APPLIED" and receipt.get("retryable") is False,
                "texture write did not return a confirmed non-retryable receipt")
        after = texture_content(texture_snapshot(client))
        changed = history_snapshot(client)
        require(integer_value(changed.get("position"), "texture changed position") == position + 1,
                "texture write did not create exactly one native Undo entry")
        require(after != original, "texture write made no observable library change")
        check(original, after, receipt)
        for undo, expected, expected_position in ((True, original, position),
                                                   (False, after, position + 1),
                                                   (True, original, position)):
            moved = tool_call(client, "turboism.history.undo" if undo else "turboism.history.redo",
                              history_guard_arguments(history_snapshot(client), undo=undo))
            require(moved.get("outcome") == "MOVED", "texture native history did not move")
            require(integer_value(history_snapshot(client).get("position"), "texture restored position")
                    == expected_position, "texture native history position mismatch")
            require(texture_content(texture_snapshot(client)) == expected,
                    "texture native Undo/Redo did not restore the actual library metadata")
    finally:
        current = history_snapshot(client)
        current_position = integer_value(current.get("position"), "texture cleanup position")
        if current_position == position + 1:
            tool_call(client, "turboism.history.undo", history_guard_arguments(current, undo=True))
        require(integer_value(history_snapshot(client).get("position"), "texture final position") == position,
                "texture cleanup cannot safely resolve an unexpected history position")
        require(texture_content(texture_snapshot(client)) == original,
                "texture cleanup left changed library metadata")
    return original, after, receipt


def validate_reversible_texture_authoring(client: McpClient, task_id: str) -> int:
    baseline = texture_snapshot(client)
    original = texture_content(baseline)
    before_history = history_snapshot(client)
    raw_images = original["rawImages"]
    images = [image for group in original["modelImageGroups"]
              for image in array_value(object_value(group, "texture group").get("modelImages"), "model images")]
    require(bool(raw_images) and bool(images), "texture authoring fixture needs raw and model images")
    raw_id = text_value(object_value(raw_images[0], "raw image").get("id"), "raw id")
    image_id = text_value(object_value(images[0], "model image").get("id"), "model image id")
    name = "TurboismTexture-" + task_id[-16:]
    token = object_value(baseline["state"], "texture expected state")
    stale = dict(token)
    stale["historyRevision"] = integer_value(token.get("historyRevision"), "texture revision") + 1
    invalids = [
        {"operation": "add_model_image_group", "name": name, "expectedState": stale},
        {"operation": "remove_raw_image", "id": raw_id, "confirmDelete": False, "expectedState": token},
        {"operation": "remove_model_image", "id": image_id, "expectedState": token},
        {"operation": "add_texture_atlas", "name": name, "widthPixels": 0, "heightPixels": 64,
         "expectedState": token},
        {"operation": "remove_texture_atlas", "id": "missing-" + task_id[-16:], "confirmDelete": True,
         "expectedState": token},
    ]
    for arguments in invalids:
        _, _, body = client._post({"jsonrpc": "2.0", "id": client._id(), "method": "tools/call",
                                 "params": {"name": "turboism.textures.write", "arguments": arguments}})
        envelope = object_value(json.loads(body), "texture rejection envelope")
        result = envelope.get("result")
        rejected = isinstance(envelope.get("error"), dict) or (
            isinstance(result, dict) and result.get("isError") is True)
        require(rejected, "unsafe texture request was not rejected")
        require(texture_content(texture_snapshot(client)) == original, "rejected texture request changed state")
        require_history_unchanged(before_history, history_snapshot(client), "rejected texture request changed history")

    def group_created(before: dict[str, Any], after: dict[str, Any], receipt: dict[str, Any]) -> None:
        require(len(after["modelImageGroups"]) == len(before["modelImageGroups"]) + 1,
                "model image group was not created")
        require(sum(group.get("groupName") == name for group in after["modelImageGroups"]) == 1,
                "new model image group name is not unambiguous")

    texture_write_cycle(client, {"operation": "add_model_image_group", "name": name}, group_created)

    def atlas_created(before: dict[str, Any], after: dict[str, Any], receipt: dict[str, Any]) -> None:
        old_ids = {value["id"] for value in before["textureAtlases"]}
        added = [value for value in after["textureAtlases"] if value["id"] not in old_ids]
        require(len(added) == 1 and added[0].get("name") == name,
                "new atlas has no unambiguous native identity")
        require(added[0].get("width") == 64 and added[0].get("height") == 64, "atlas size mismatch")
        # The native ID must be retained in the receipt, independently of a later metadata read.
        text_value(receipt.get("receipt"), "texture write receipt")
        require(receipt.get("id") == added[0]["id"], "atlas receipt omitted its generated native ID")

    texture_write_cycle(client, {"operation": "add_texture_atlas", "name": name,
                                 "widthPixels": 64, "heightPixels": 64}, atlas_created)

    def removed_id(key: str, target: str) -> Callable[[dict[str, Any], dict[str, Any], dict[str, Any]], None]:
        def check(before: dict[str, Any], after: dict[str, Any], receipt: dict[str, Any]) -> None:
            require({value["id"] for value in after[key]} == {value["id"] for value in before[key]} - {target},
                    "texture removal affected a different identity set")
        return check

    # A fixture without an atlas gets one reversible task-local setup edit, never a silent skip.
    atlas_setup = not bool(original["textureAtlases"])
    setup_position = integer_value(history_snapshot(client).get("position"), "atlas setup position")
    try:
        if atlas_setup:
            state = texture_snapshot(client)
            tool_call(client, "turboism.textures.write", {"operation": "add_texture_atlas", "name": name,
                      "widthPixels": 64, "heightPixels": 64, "expectedState": state["state"]})
        atlases = texture_content(texture_snapshot(client))["textureAtlases"]
        require(bool(atlases), "atlas setup produced no atlas")
        atlas_id = text_value(atlases[0].get("id"), "atlas deletion id")
        texture_write_cycle(client, {"operation": "remove_texture_atlas", "id": atlas_id,
                                     "confirmDelete": True}, removed_id("textureAtlases", atlas_id))
    finally:
        if atlas_setup:
            current = history_snapshot(client)
            if integer_value(current.get("position"), "atlas setup cleanup") == setup_position + 1:
                tool_call(client, "turboism.history.undo", history_guard_arguments(current, undo=True))
            require(texture_content(texture_snapshot(client)) == original, "atlas setup was not restored")

    def model_image_removed(before: dict[str, Any], after: dict[str, Any], receipt: dict[str, Any]) -> None:
        def ids(value: dict[str, Any]) -> set[str]:
            return {image["id"] for group in value["modelImageGroups"] for image in group["modelImages"]}
        require(ids(after) == ids(before) - {image_id}, "model-image removal changed the wrong images")

    texture_write_cycle(client, {"operation": "remove_model_image", "id": image_id,
                                 "confirmDelete": True}, model_image_removed)
    original_hierarchy = resource_json(client, "turboism://active/model/hierarchy")

    def raw_image_removed(before: dict[str, Any], after: dict[str, Any], receipt: dict[str, Any]) -> None:
        removed_id("rawImages", raw_id)(before, after, receipt)
        require(after["modelImageGroups"] == before["modelImageGroups"], "raw removal deleted model images")
        require(after["textureAtlases"] == before["textureAtlases"], "raw removal modified texture atlases")
        require(resource_json(client, "turboism://active/model/hierarchy") == original_hierarchy,
                "raw removal changed model object hierarchy")

    texture_write_cycle(client, {"operation": "remove_raw_image", "id": raw_id,
                                 "confirmDelete": True}, raw_image_removed)
    require(texture_content(texture_snapshot(client)) == original, "texture matrix did not restore its baseline")
    return 5


def validate_audit_input_guards(client: McpClient, parameters: list[Any]) -> int:
    parameter = object_value(parameters[0], "guard parameter")
    parameter_id = text_value(parameter.get("id"), "guard parameter id")
    original = finite_number(parameter.get("value"), "guard original value")
    minimum = finite_number(parameter.get("minimumValue"), "guard minimum")
    maximum = finite_number(parameter.get("maximumValue"), "guard maximum")
    candidate = minimum if not math.isclose(original, minimum) else maximum
    operation = {"operation": "set_value", "parameterId": parameter_id, "value": candidate}
    parameter_uri = "turboism://active/model/parameters/" + urllib.parse.quote(parameter_id, safe="")
    initial_history = history_snapshot(client)
    hierarchy = resource_json(client, "turboism://active/model/hierarchy")
    count = 0
    invalid_calls = [
        ("turboism.parameters.apply", {"operations": [operation, 7]}),
        ("turboism.parameters.apply", {"operations": []}),
        ("turboism.parameter_bindings.apply", {"operations": []}),
        ("turboism.parameter_bindings.apply", {"operations": [{
            "operation": "invert", "parameterId": "McpMissingParameter",
            "targets": [{"type": "art_mesh", "id": "McpMissingTarget"}]}]}),
        ("turboism.parameter_bindings.apply", {"operations": [{
            "operation": "unbind", "parameterId": parameter_id,
            "target": {"type": "art_mesh", "id": "McpMissingTarget"}}, 7]}),
        ("turboism.model_objects.apply", {"operations": [{
            "operation": "create", "kind": "part", "name": "McpMustNotBeCreated"}, 7]}),
    ]
    for name, arguments in invalid_calls:
        error = rpc_error(client, "tools/call", {"name": name, "arguments": arguments})
        require(error.get("code") == -32602, "malformed MCP arguments were not rejected")
        count += 1
    for invalid_id in (None, [], {}, True, 1.5):
        status, _, body = client._post({"jsonrpc": "2.0", "id": invalid_id,
            "method": "tools/call", "params": {"name": "turboism.parameters.apply",
                "arguments": {"operations": [operation]}}})
        response = object_value(json.loads(body), "invalid-id response")
        require(status == 200 and object_value(response.get("error"), "invalid-id error").get("code") == -32600,
                "invalid RPC ID was not rejected before dispatch")
        count += 1
    require_history_unchanged(initial_history, history_snapshot(client), "invalid requests changed history")
    require(resource_json(client, "turboism://active/model/hierarchy") == hierarchy,
            "invalid requests changed the model hierarchy")
    require(math.isclose(finite_number(resource_json(client, parameter_uri).get("value"), "guard value"),
                         original, rel_tol=0.0, abs_tol=1.0e-6), "invalid requests changed a parameter")
    return count


def validate_reversible_binding_inversion(client: McpClient, parameters: list[Any]) -> str:
    normal_ids = {item["id"] for item in parameters if item.get("type") == "normal"}
    uri = "turboism://active/model/parameter-bindings"
    before = resource_json(client, uri)
    bindings = [binding for group in array_value(before.get("parameterBindings"), "binding groups")
                for binding in array_value(group.get("bindings"), "bindings")
                if group.get("parameterId") in normal_ids and binding.get("family") == "keyform_grid"]
    candidate = next((binding for binding in bindings if len(binding.get("points", [])) > 1), None)
    require(candidate is not None, "fixture has no invertible normal keyform binding")
    target = object_value(candidate.get("target"), "inversion target")
    affected = {binding["parameterId"] for binding in bindings if binding.get("target") == target}
    history_before = history_snapshot(client)
    position = integer_value(history_before.get("position"), "inversion initial position")
    restored = False
    try:
        output = tool_call(client, "turboism.parameter_bindings.apply", {"operations": [{
            "operation": "invert_all_bindings", "scope": "all_target_bindings", "targets": [target]}]})
        item = object_value(array_value(output.get("results"), "inversion results")[0], "inversion receipt")
        receipt = object_value(item.get("result"), "inversion write result")
        require(item.get("ok") is True and receipt.get("outcome") == "APPLIED"
                and receipt.get("retryable") is False, "inversion receipt is not confirmed")
        require(receipt.get("scope") == "all_target_bindings"
                and set(receipt.get("affectedParameterIds", [])) == affected,
                "inversion receipt does not identify the complete affected scope")
        require({binding["parameterId"] for binding in receipt.get("affectedBindings", [])} == affected,
                "inversion readback omits affected parameter bindings")
        changed = history_snapshot(client)
        require(integer_value(changed.get("position"), "inversion changed position") == position + 1,
                "inversion did not produce exactly one native Undo entry")
        for tool, undo in (("turboism.history.undo", True), ("turboism.history.redo", False),
                           ("turboism.history.undo", True)):
            moved = tool_call(client, tool, history_guard_arguments(history_snapshot(client), undo=undo))
            require(moved.get("outcome") == "MOVED", "binding history movement failed")
        require(resource_json(client, uri) == before, "binding metadata did not restore after final Undo")
        require(history_snapshot(client).get("position") == position, "binding history position did not restore")
        restored = True
        return "ALL_BINDINGS_CHANGED_UNDONE_REDONE_AND_RESTORED"
    finally:
        if not restored:
            current = history_snapshot(client)
            if current.get("position") == position + 1:
                tool_call(client, "turboism.history.undo", history_guard_arguments(current, undo=True))


def validate_reversible_parameter_write(
    client: McpClient,
    parameters: list[Any],
) -> str:
    selected: dict[str, Any] | None = None
    mutation_value = 0.0
    for item in parameters:
        parameter = object_value(item, "parameter")
        current = finite_number(parameter.get("value"), "parameter.value")
        minimum = finite_number(parameter.get("minimumValue"), "parameter.minimumValue")
        maximum = finite_number(parameter.get("maximumValue"), "parameter.maximumValue")
        default = finite_number(parameter.get("defaultValue"), "parameter.defaultValue")
        if maximum <= minimum:
            continue
        candidate = default
        if math.isclose(candidate, current, rel_tol=0.0, abs_tol=1.0e-6):
            step = (maximum - minimum) / 100.0
            candidate = current + step if current + step <= maximum else current - step
        if not math.isclose(candidate, current, rel_tol=0.0, abs_tol=1.0e-6):
            selected = parameter
            mutation_value = candidate
            break
    if selected is None:
        raise ValidationFailure("no reversible parameter mutation candidate is available")

    parameter_id = text_value(selected.get("id"), "parameter.id")
    original = finite_number(selected.get("value"), "parameter.value")
    resource_uri = (
        "turboism://active/model/parameters/"
        + urllib.parse.quote(parameter_id, safe="")
    )
    initial_history = history_snapshot(client)
    initial_position = integer_value(initial_history.get("position"), "initial history.position")
    restored = False
    primary_failure: Exception | None = None
    try:
        changed = tool_call(client, "turboism.parameters.apply", {
            "operations": [{
                "operation": "set_value",
                "parameterId": parameter_id,
                "value": mutation_value,
            }],
            "stopOnError": True,
        })
        require(changed.get("ok") is True, "parameter mutation batch failed")
        receipt = object_value(array_value(changed.get("results"), "parameter results")[0], "parameter receipt")
        write = object_value(receipt.get("result"), "parameter write result")
        require(receipt.get("ok") is True and write.get("outcome") == "APPLIED"
                and write.get("retryable") is False, "parameter confirmed receipt is incomplete")
        changed_state = resource_json(client, resource_uri)
        changed_value = finite_number(changed_state.get("value"), "changed parameter.value")
        require(
            math.isclose(changed_value, mutation_value, rel_tol=0.0, abs_tol=1.0e-5),
            "parameter mutation was not visible on resource readback",
        )

        changed_history = history_snapshot(client)
        changed_position = integer_value(changed_history.get("position"), "changed history.position")
        require(changed_position == initial_position + 1,
                "parameter mutation did not create exactly one Undo entry")
        moved = tool_call(
            client,
            "turboism.history.undo",
            history_guard_arguments(changed_history, undo=True),
        )
        require(moved.get("outcome") == "MOVED", "history cleanup did not Undo")
        restored_state = resource_json(client, resource_uri)
        restored_value = finite_number(restored_state.get("value"), "restored parameter.value")
        require(
            math.isclose(restored_value, original, rel_tol=0.0, abs_tol=1.0e-5),
            "history cleanup value was not visible on resource readback",
        )
        redone = tool_call(client, "turboism.history.redo",
                           history_guard_arguments(history_snapshot(client), undo=False))
        require(redone.get("outcome") == "MOVED", "parameter Redo did not move")
        require(math.isclose(finite_number(resource_json(client, resource_uri).get("value"), "redo value"),
                             mutation_value, rel_tol=0.0, abs_tol=1.0e-5), "parameter Redo value mismatch")
        final_undo = tool_call(client, "turboism.history.undo",
                              history_guard_arguments(history_snapshot(client), undo=True))
        require(final_undo.get("outcome") == "MOVED", "parameter final Undo did not move")
        require(math.isclose(finite_number(resource_json(client, resource_uri).get("value"), "final value"),
                             original, rel_tol=0.0, abs_tol=1.0e-5), "parameter final restoration mismatch")
        restored = True
        return "PARAMETER_CHANGED_UNDONE_REDONE_AND_RESTORED"
    except Exception as failure:
        primary_failure = failure
        raise
    finally:
        if not restored:
            try:
                tool_call(client, "turboism.parameters.apply", {
                    "operations": [{
                        "operation": "set_value",
                        "parameterId": parameter_id,
                        "value": original,
                    }],
                    "stopOnError": True,
                })
            except Exception as cleanup_failure:
                if primary_failure is None:
                    raise
                raise ValidationFailure(
                    "parameter mutation failed and cleanup also failed "
                    f"({cleanup_failure.__class__.__name__})"
                ) from primary_failure


def collect_pages(client: McpClient, method: str, key: str) -> list[Any]:
    values: list[Any] = []
    cursor: str | None = None
    while True:
        params = {} if cursor is None else {"cursor": cursor}
        page = client.call(method, params)
        values.extend(array_value(page.get(key), key))
        next_cursor = page.get("nextCursor")
        if next_cursor is None:
            return values
        cursor = text_value(next_cursor, "nextCursor")


def await_resource(
    client: McpClient,
    uri: str,
    ready: Any,
    label: str,
    timeout_seconds: int = 300,
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last: dict[str, Any] | None = None
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            last = resource_json(client, uri)
            if ready(last):
                return last
        except Exception as failure:
            last_error = failure
        time.sleep(1)
    detail = last_error.__class__.__name__ if last_error else sanitize(str(last))
    raise ValidationFailure(f"{label} did not become ready ({detail})")


def resource_json(client: McpClient, uri: str) -> dict[str, Any]:
    result = client.call("resources/read", {"uri": uri})
    contents = array_value(result.get("contents"), "contents")
    require(len(contents) == 1, f"resource {uri} returned {len(contents)} contents")
    content = object_value(contents[0], "resource content")
    require(content.get("mimeType") == "application/json", "resource MIME type mismatch")
    text = text_value(content.get("text"), "resource text")
    return object_value(json.loads(text), "resource JSON")


def tool_result(client: McpClient, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    result = client.call("tools/call", {"name": name, "arguments": arguments})
    structured = object_value(result.get("structuredContent"), "structuredContent")
    contents = array_value(result.get("content"), "tool content")
    require(len(contents) == 1, "tool content block count mismatch")
    block = object_value(contents[0], "tool content block")
    require(block.get("type") == "text", "tool content block is not text")
    require(json.loads(text_value(block.get("text"), "tool text")) == structured,
            "tool text differs from structuredContent")
    return structured


def tool_call(client: McpClient, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    structured = tool_result(client, name, arguments)
    if structured.get("ok") is False:
        details = {key: structured[key] for key in ("outcome", "diagnosticId", "code")
                   if key in structured}
        if isinstance(structured.get("error"), dict):
            details["errorCode"] = structured["error"].get("code")
        steps = []
        for step in structured.get("steps", []):
            if not isinstance(step, dict):
                continue
            output = step.get("output")
            output = output if isinstance(output, dict) else {}
            error = output.get("error")
            if output.get("ok") is not False and not step.get("diagnosticId"):
                continue
            steps.append({"id": step.get("id"), "diagnosticId": step.get("diagnosticId"),
                          "outcome": output.get("outcome"),
                          "errorCode": error.get("code") if isinstance(error, dict) else output.get("code")})
        details["failedSteps"] = steps
        details["completedStepCount"] = len(structured.get("steps", [])) - len(steps)
        raise ValidationFailure(f"tool {name} failed: {sanitize(json.dumps(details))}")
    return structured


def rpc_error(client: McpClient, method: str, params: dict[str, Any]) -> dict[str, Any]:
    request = {"jsonrpc": "2.0", "id": client._id(), "method": method, "params": params}
    status, _, body = client._post(request)
    require(status == 200, f"{method} error HTTP status={status}")
    envelope = object_value(json.loads(body), "RPC envelope")
    return object_value(envelope.get("error"), "RPC error")


def rpc_result(body: bytes) -> dict[str, Any]:
    envelope = object_value(json.loads(body), "RPC envelope")
    if "error" in envelope:
        error = object_value(envelope["error"], "RPC error")
        raise ValidationFailure(
            f"RPC error code={error.get('code')} message={sanitize(str(error.get('message')))}"
        )
    return object_value(envelope.get("result"), "RPC result")


def await_connection(path: Path, timeout_seconds: int) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            if path.is_file():
                return object_value(json.loads(path.read_text(encoding="utf-8")), "connection file")
        except Exception as failure:
            last_error = failure
        time.sleep(0.5)
    detail = last_error.__class__.__name__ if last_error else "missing"
    raise ValidationFailure(f"MCP connection file timed out ({detail})")


def assert_no_absolute_paths(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            lower = str(key).lower()
            if lower in {"filepath", "projectdirectory", "path"} and item is not None:
                raise ValidationFailure(f"absolute path field exposed at {path}.{key}")
            assert_no_absolute_paths(item, f"{path}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            assert_no_absolute_paths(item, f"{path}[{index}]")


def assert_sanitized_diagnostics(value: dict[str, Any]) -> None:
    assert_no_absolute_paths(value)
    for item in array_value(value.get("problems"), "diagnostic problems"):
        problem = object_value(item, "diagnostic problem")
        require(set(problem) == {"code", "severity", "message"},
                "diagnostic problem exposed unexpected fields")
        message = text_value(problem.get("message"), "diagnostic message")
        require("\n" not in message and "\r" not in message,
                "diagnostic message is multiline")
        require(len(message) <= 512, "diagnostic message exceeds bound")
        require("file:" not in message.lower(), "diagnostic message exposed file URI")


def publish_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(text, encoding="utf-8")
    os.replace(temporary, path)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationFailure(message)


def object_value(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValidationFailure(f"{label} is not an object")
    return value


def array_value(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValidationFailure(f"{label} is not an array")
    return value


def text_value(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValidationFailure(f"{label} is not non-empty text")
    return value


def integer_value(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValidationFailure(f"{label} is not an integer")
    return value


def finite_number(value: Any, label: str) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValidationFailure(f"{label} is not a number")
    result = float(value)
    if not math.isfinite(result):
        raise ValidationFailure(f"{label} is not finite")
    return result


def lower_headers(items: Any) -> dict[str, str]:
    return {str(key).lower(): str(value) for key, value in items}


def sanitize(value: str) -> str:
    text = " ".join(value.replace("\r", " ").replace("\n", " ").split())
    filtered = "".join(character if 32 <= ord(character) < 127 else "?" for character in text)
    return filtered[:512] or "unknown"


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--native-mcp-stdio":
        native_observation_stdio(Path(sys.argv[2]).resolve(), sanitize(sys.argv[3]))
    else:
        raise SystemExit(main())
