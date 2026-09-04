#!/usr/bin/env python3
"""Task-local credential-free raw HTTP MCP client for exact-host validation."""

from __future__ import annotations

import hashlib
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
    "turboism.glues.read",
    "turboism.glues.write",
    "turboism.history.read",
    "turboism.history.undo",
    "turboism.history.redo",
    "turboism.transaction.execute",
    "turboism.capabilities.read",
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


class McpClient:
    def __init__(self, endpoint: str, advertised_version: str) -> None:
        if not endpoint.startswith("http://127.0.0.1:") or not endpoint.endswith("/mcp"):
            raise ValidationFailure("connection endpoint is not numeric loopback /mcp")
        if advertised_version != PROTOCOL_VERSION:
            raise ValidationFailure("connection protocol version is unexpected")
        self.endpoint = endpoint
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
        if include_version:
            request.add_header("MCP-Protocol-Version", self.protocol_version)
        if include_session and self.session_id is not None:
            request.add_header("MCP-Session-Id", self.session_id)
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return response.status, lower_headers(response.headers.items()), response.read()
        except urllib.error.HTTPError as failure:
            return failure.code, lower_headers(failure.headers.items()), failure.read()


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
        require(array_value(operation.get("supportedVersions"), "history versions") == [
            "5.2.03", "5.3.02", "5.3.03",
        ], f"{name} exact-version set mismatch")

    glue_write = operations["turboism.glues.write"]
    require(glue_write.get("effect") == "UNDOABLE_WRITE", "Glue write effect is not undoable")
    require(glue_write.get("affinity") == "UI_THREAD", "Glue write affinity is not UI_THREAD")
    require(glue_write.get("transactionEligible") is True,
            "Glue write is not transaction eligible")
    supported = array_value(glue_write.get("supportedVersions"), "Glue supported versions")
    require(supported == ["5.2.03", "5.3.02", "5.3.03"],
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
    require(active_version in {"5.2.03", "5.3.02", "5.3.03"},
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
    mutations: list[str] = []
    try:
        connection = await_connection(connection_path, 300)
        endpoint = text_value(connection.get("endpoint"), "endpoint")
        require("authorization" not in connection, "connection file contains authorization material")
        advertised_version = text_value(connection.get("protocolVersion"), "protocolVersion")
        report.append("assertion.connectionFile.status=PASS")
        report.append("assertion.credentialFreeConnection.status=PASS")
        client = McpClient(endpoint, advertised_version)
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

        hierarchy = await_resource(
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
        report.append("authentication=NONE")
        report.append(
            "modelMutation="
            + sanitize(",".join(mutations) if mutations else "NONE")
        )
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
