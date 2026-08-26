#!/usr/bin/env python3
"""Task-local raw HTTP MCP client for exact-host validation.

The connection bearer is read from the task-scoped Turboism home, held only in
memory, and never printed or persisted in validation evidence.
"""

from __future__ import annotations

import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

PROTOCOL_VERSION = "2025-11-25"
EXPECTED_TOOLS = {
    "turboism.model_objects.apply",
    "turboism.parameters.apply",
    "turboism.parameter_bindings.apply",
    "turboism.history.move",
    "turboism.editor_commands.execute",
}
EXPECTED_RESOURCES = {
    "turboism://active/document",
    "turboism://active/model/overview",
    "turboism://active/model/hierarchy",
    "turboism://active/model/clip-masks",
    "turboism://active/model/parameters",
    "turboism://active/model/statistics",
    "turboism://active/model/textures",
    "turboism://active/document/history",
    "turboism://environment/cubism-core",
    "turboism://environment/workspace",
    "turboism://environment/workspace/layout",
    "turboism://environment/diagnostics",
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


class McpClient:
    def __init__(self, endpoint: str, authorization: str, advertised_version: str) -> None:
        if not endpoint.startswith("http://127.0.0.1:") or not endpoint.endswith("/mcp"):
            raise ValidationFailure("connection endpoint is not numeric loopback /mcp")
        if not authorization.startswith("Bearer ") or len(authorization) <= len("Bearer ") + 23:
            raise ValidationFailure("connection authorization is malformed")
        if advertised_version != PROTOCOL_VERSION:
            raise ValidationFailure("connection protocol version is unexpected")
        self.endpoint = endpoint
        self.authorization = authorization
        self.protocol_version = advertised_version
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
        request.add_header("Authorization", self.authorization)
        request.add_header("MCP-Session-Id", self.session_id)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
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
        request.add_header("Authorization", self.authorization)
        if include_version:
            request.add_header("MCP-Protocol-Version", self.protocol_version)
        if include_session and self.session_id is not None:
            request.add_header("MCP-Session-Id", self.session_id)
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return response.status, lower_headers(response.headers.items()), response.read()
        except urllib.error.HTTPError as failure:
            return failure.code, lower_headers(failure.headers.items()), failure.read()


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: mcp-host-validation-client.py <turboism-home> <task-id>", file=sys.stderr)
        return 2
    home = Path(sys.argv[1]).resolve()
    task_id = sanitize(sys.argv[2])
    state_root = home / "state"
    result_path = state_root / "mcp-host-validation.properties"
    connection_path = state_root / "dev.turboism.plugin.mcp" / "mcp-connection.json"
    report = ["schemaVersion=1", f"runId={task_id}", "client=python-stdlib-http"]
    status = "FAIL"
    client: McpClient | None = None
    try:
        connection = await_connection(connection_path, 300)
        endpoint = text_value(connection.get("endpoint"), "endpoint")
        authorization = text_value(connection.get("authorization"), "authorization")
        advertised_version = text_value(connection.get("protocolVersion"), "protocolVersion")
        report.append("assertion.connectionFile.status=PASS")
        report.append("assertion.authorizationRedacted.status=PASS")
        client = McpClient(endpoint, authorization, advertised_version)
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
            client,
            "turboism://active/model/parameters",
            lambda value: isinstance(value.get("parameters"), list)
                and bool(value.get("parameters")),
            "active model parameters",
        )
        parameter_values = array_value(parameters.get("parameters"), "parameters")
        report.append(f"parameterCount={len(parameter_values)}")
        report.append("assertion.parameterRead.status=PASS")

        mutation = validate_reversible_parameter_write(client, parameter_values)
        report.append("assertion.parameterWriteReadback.status=PASS")
        report.append("assertion.parameterWriteCleanup.status=PASS")

        hierarchy = await_resource(
            client,
            "turboism://active/model/hierarchy",
            lambda value: value.get("ok") is True,
            "active model hierarchy",
        )
        report.append("assertion.hierarchy.status=PASS")

        history = await_resource(
            client,
            "turboism://active/document/history",
            lambda value: value.get("availability") == "AVAILABLE",
            "active document history",
        )
        generation = integer_value(history.get("generation"), "history.generation")
        revision = integer_value(history.get("revision"), "history.revision")
        position = integer_value(history.get("position"), "history.position")
        history_result = tool_call(client, "turboism.history.move", {
            "operation": "move_to",
            "expectedGeneration": generation,
            "expectedRevision": revision,
            "position": position,
        })
        require(history_result.get("outcome") in {"NO_CHANGE", "MOVED"},
                f"history no-op outcome={history_result.get('outcome')}")
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

        client.delete_session()
        report.append("assertion.sessionDelete.status=PASS")
        status = "PASS"
    except Exception as failure:  # evidence is sanitized; bearer is never interpolated here
        report.append(f"error={sanitize(failure.__class__.__name__ + ': ' + str(failure))}")
    finally:
        if client is not None and client.session_id is not None:
            try:
                client.delete_session()
            except Exception as failure:
                report.append(f"cleanupError={sanitize(failure.__class__.__name__)}")
        report.append("authorizationPersisted=false")
        report.append(f"modelMutation={mutation if 'mutation' in locals() else 'NONE'}")
        report.append(f"status={status}")
        publish_atomic(result_path, "\n".join(report) + "\n")
    return 0 if status == "PASS" else 1


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
    initial_history = resource_json(client, "turboism://active/document/history")
    require(initial_history.get("availability") == "AVAILABLE", "history is unavailable for mutation cleanup")
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
        changed_state = resource_json(client, resource_uri)
        changed_value = finite_number(changed_state.get("value"), "changed parameter.value")
        require(
            math.isclose(changed_value, mutation_value, rel_tol=0.0, abs_tol=1.0e-5),
            "parameter mutation was not visible on resource readback",
        )

        changed_history = resource_json(client, "turboism://active/document/history")
        require(changed_history.get("availability") == "AVAILABLE", "history became unavailable after mutation")
        generation = integer_value(changed_history.get("generation"), "changed history.generation")
        revision = integer_value(changed_history.get("revision"), "changed history.revision")
        changed_position = integer_value(changed_history.get("position"), "changed history.position")
        require(changed_position > initial_position, "parameter mutation did not create an Undo entry")
        moved = tool_call(client, "turboism.history.move", {
            "operation": "move_to",
            "expectedGeneration": generation,
            "expectedRevision": revision,
            "position": initial_position,
        })
        require(moved.get("outcome") in {"MOVED", "NO_CHANGE"}, "history cleanup did not move")
        restored_state = resource_json(client, resource_uri)
        restored_value = finite_number(restored_state.get("value"), "restored parameter.value")
        require(
            math.isclose(restored_value, original, rel_tol=0.0, abs_tol=1.0e-5),
            "history cleanup value was not visible on resource readback",
        )
        restored = True
        return "PARAMETER_CHANGED_AND_UNDONE"
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


def tool_call(client: McpClient, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    result = client.call("tools/call", {"name": name, "arguments": arguments})
    structured = object_value(result.get("structuredContent"), "structuredContent")
    contents = array_value(result.get("content"), "tool content")
    require(len(contents) == 1, "tool content block count mismatch")
    block = object_value(contents[0], "tool content block")
    require(block.get("type") == "text", "tool content block is not text")
    require(json.loads(text_value(block.get("text"), "tool text")) == structured,
            "tool text differs from structuredContent")
    require(structured.get("ok") is not False, f"tool {name} failed")
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
    raise SystemExit(main())
