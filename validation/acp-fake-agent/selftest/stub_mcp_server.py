#!/usr/bin/env python3
"""Minimal stdlib MCP server for the acp fake-agent selftest.

Implements the production tool/resource names the fake agent drives
(turboism.model_objects.apply, turboism.history.read, turboism.history.undo,
turboism://active/model/overview) with a two-object in-memory model and a
one-step rename history. Mutating calls require the bearer token, mirroring
the production gate.
"""

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "selftest-token-0123456789abcdef0123456789abcdef"
SESSION_ID = "selftest-session-1"
PROTOCOL_VERSION = "2025-06-18"

OBJECTS = [
    {"id": "obj-part-1", "kind": "part", "name": "Part 1"},
    {"id": "obj-mesh-1", "kind": "art_mesh", "name": "Mesh 1"},
]

LOCK = threading.Lock()
HISTORY = {"generation": 1, "revision": 0, "position": 0}
LAST_RENAME = {}


def tool_result(structured):
    text = json.dumps(structured, ensure_ascii=False)
    return {
        "jsonrpc": "2.0",
        "id": None,
        "result": {
            "structuredContent": structured,
            "content": [{"type": "text", "text": text}],
        },
    }


def handle_tools_call(name, arguments):
    global LAST_RENAME
    if name == "turboism.history.read":
        with LOCK:
            snapshot = {
                "ok": True,
                "availability": "AVAILABLE",
                "generation": HISTORY["generation"],
                "revision": HISTORY["revision"],
                "position": HISTORY["position"],
                "entries": [],
            }
        return tool_result(snapshot)
    if name == "turboism.model_objects.apply":
        operations = arguments.get("operations", [])
        if len(operations) != 1 or operations[0].get("operation") != "rename":
            return tool_result({"ok": False, "outcome": "NOT_APPLIED", "error": {"code": "INVALID_ARGUMENT"}})
        operation = operations[0]
        target = next((o for o in OBJECTS if o["id"] == operation.get("id")), None)
        if target is None or operation.get("kind") != target["kind"]:
            return tool_result({"ok": False, "outcome": "NOT_APPLIED", "error": {"code": "OBJECT_NOT_FOUND"}})
        with LOCK:
            LAST_RENAME = {"id": target["id"], "previous": target["name"]}
            target["name"] = operation.get("name")
            HISTORY["revision"] += 1
            HISTORY["position"] += 1
        return tool_result({"ok": True, "outcome": "APPLIED", "succeeded": 1, "failed": 0, "partialSuccess": False})
    if name == "turboism.history.undo":
        with LOCK:
            expected_generation = arguments.get("expectedGeneration")
            expected_revision = arguments.get("expectedRevision")
            if expected_generation != HISTORY["generation"] or expected_revision != HISTORY["revision"]:
                return tool_result({"ok": False, "outcome": "REJECTED_STALE"})
            if HISTORY["position"] <= 0:
                return tool_result({"ok": False, "outcome": "REJECTED_STALE"})
            HISTORY["position"] -= 1
            if LAST_RENAME:
                target = next((o for o in OBJECTS if o["id"] == LAST_RENAME["id"]), None)
                if target is not None:
                    target["name"] = LAST_RENAME["previous"]
                LAST_RENAME = {}
        return tool_result({"ok": True, "outcome": "MOVED"})
    return tool_result({"ok": False, "outcome": "OUTCOME_UNKNOWN", "error": {"code": "UNKNOWN_TOOL"}})


def handle_resources_read(uri):
    if uri != "turboism://active/model/overview":
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32002, "message": "resource not found"}}
    document = json.dumps({"objects": [dict(o) for o in OBJECTS]}, ensure_ascii=False)
    return {
        "jsonrpc": "2.0",
        "id": None,
        "result": {"contents": [{"uri": uri, "mimeType": "application/json", "text": document}]},
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # keep selftest output readable
        pass

    def do_POST(self):
        if self.path != "/mcp":
            self.send_error(404)
            return
        authorization = self.headers.get("Authorization", "")
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        method = body.get("method")
        request_id = body.get("id")
        needs_token = authorization != f"Bearer {TOKEN}" and method == "tools/call"
        if needs_token:
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if method == "initialize":
            payload = {
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {}, "resources": {}},
                    "serverInfo": {"name": "stub-mcp", "version": "0.0.0"},
                },
            }
            encoded = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("MCP-Session-Id", SESSION_ID)
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
            return
        if method == "tools/list":
            payload = {
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    "tools": [
                        {"name": "turboism.model_objects.apply"},
                        {"name": "turboism.history.read"},
                        {"name": "turboism.history.undo"},
                    ]
                },
            }
            encoded = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
            return
        if method == "tools/call":
            self._reply(handle_tools_call(body["params"]["name"], body["params"].get("arguments", {})), request_id)
            return
        if method == "resources/read":
            self._reply(handle_resources_read(body["params"].get("uri", "")), request_id)
            return
        # Notifications and unknown methods: acknowledged without a body.
        self.send_response(202)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _reply(self, payload, request_id):
        payload = dict(payload)
        payload["id"] = request_id
        encoded = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def main():
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    print(server.server_address[1], flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
