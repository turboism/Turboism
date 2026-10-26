#!/usr/bin/env python3
"""Read-only MCP discovery via a trusted local stdio launch descriptor.

No tools/call, prompts/get, arbitrary resource read, credential export or model edits.
The operator must trust the descriptor: its command is executed as argv, never a shell.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone

MAX_LINE = 8 * 1024 * 1024
MAX_TOTAL = 32 * 1024 * 1024
MAX_REPORT = 4 * 1024 * 1024
MAX_PAGES = 64
MAX_ENTRIES = 5000
PROTOCOLS = {"2025-11-25", "2025-06-18", "2025-03-26"}
EDITOR_COMMANDS = "turboism://host/editor-commands"
READ_METHODS = {"initialize", "tools/list", "resources/list", "resources/templates/list", "prompts/list", "resources/read"}


class AuditError(Exception):
    """Only constant error codes, never native messages/configuration, cross this boundary."""


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def load_launch(path, server="turboism"):
    try:
        if path.stat().st_size > 128 * 1024:
            raise AuditError("CONFIG_TOO_LARGE")
        value = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(value, dict) and "mcpServers" in value:
            value = value["mcpServers"][server]
        if not isinstance(value, dict) or set(value) - {"command", "args", "env", "type"}:
            raise AuditError("INVALID_STDIO_CONFIG")
        if value.get("type", "stdio") != "stdio":
            raise AuditError("STDIO_REQUIRED")
        command, args, env = value["command"], value.get("args", []), value.get("env", {})
        if not isinstance(command, str) or not command.strip() or len(command) > 4096:
            raise AuditError("INVALID_STDIO_CONFIG")
        if not isinstance(args, list) or len(args) > 64 or any(not isinstance(a, str) or len(a) > 4096 for a in args):
            raise AuditError("INVALID_STDIO_CONFIG")
        if any("\0" in a for a in [command, *args]):
            raise AuditError("INVALID_STDIO_CONFIG")
        if not isinstance(env, dict) or len(env) > 128 or any(not isinstance(k, str) or not isinstance(v, str) or not k or "=" in k or "\0" in k + v for k, v in env.items()):
            raise AuditError("INVALID_STDIO_CONFIG")
        return [command, *args], env
    except AuditError:
        raise
    except (OSError, ValueError, TypeError, KeyError):
        raise AuditError("CONFIG_UNAVAILABLE_OR_INVALID") from None


class StdioClient:
    def __init__(self, argv, env, timeout=15):
        self.timeout, self.next_id, self.total = timeout, 0, 0
        self.incoming = queue.Queue(maxsize=16)
        self.process = None
        self.thread = None
        self.argv, self.env = argv, env

    def __enter__(self):
        try:
            self.process = subprocess.Popen(self.argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env={**os.environ, **self.env}, shell=False)
        except (OSError, ValueError):
            raise AuditError("BRIDGE_START_FAILED") from None
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()
        return self

    def _read(self):
        try:
            while True:
                line = self.process.stdout.readline(MAX_LINE + 1)
                if not line:
                    self.incoming.put_nowait(AuditError("BRIDGE_CLOSED"))
                    return
                if len(line) > MAX_LINE:
                    self.incoming.put_nowait(AuditError("RESPONSE_TOO_LARGE"))
                    return
                self.total += len(line)
                if self.total > MAX_TOTAL:
                    self.incoming.put_nowait(AuditError("RESPONSE_BUDGET_EXCEEDED"))
                    return
                message = json.loads(line, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
                if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
                    raise ValueError()
                self.incoming.put_nowait(message)
        except (ValueError, UnicodeError, OSError):
            try:
                self.incoming.put_nowait(AuditError("INVALID_BRIDGE_PROTOCOL"))
            except queue.Full:
                pass
        except queue.Full:
            # A flooded queue cannot yield a successful catalog; the request times out/fails.
            pass

    def _send(self, message):
        try:
            self.process.stdin.write(encoded(message) + b"\n")
            self.process.stdin.flush()
        except (OSError, ValueError):
            raise AuditError("BRIDGE_WRITE_FAILED") from None

    def initialized(self):
        self._send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def request(self, method, params):
        if method not in READ_METHODS or (method == "resources/read" and params != {"uri": EDITOR_COMMANDS}):
            raise AuditError("READ_SCOPE_REJECTED")
        self.next_id += 1
        request_id = self.next_id
        self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        deadline = time.monotonic() + self.timeout
        for _ in range(33):
            try:
                message = self.incoming.get(timeout=max(0, deadline - time.monotonic()))
            except queue.Empty:
                raise AuditError("BRIDGE_TIMEOUT") from None
            if isinstance(message, AuditError):
                raise message
            if "id" not in message and "method" in message:
                continue
            if type(message.get("id")) is not int or message.get("id") != request_id or "method" in message:
                raise AuditError("UNEXPECTED_BRIDGE_MESSAGE")
            if "error" in message:
                # Never include the native error message/data (may contain paths or headers).
                code = message["error"].get("code") if isinstance(message["error"], dict) else None
                raise AuditError(f"RPC_ERROR_{code}" if type(code) is int else "RPC_ERROR")
            result = message.get("result")
            if not isinstance(result, dict):
                raise AuditError("INVALID_RPC_RESULT")
            return result
        raise AuditError("NOTIFICATION_BUDGET_EXCEEDED")

    def __exit__(self, *_):
        if self.process is None:
            return
        try:
            self.process.stdin.close()
        except OSError:
            pass
        try:
            self.process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try:
                self.process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=1)
        self.process.stdout.close()
        self.thread.join(timeout=.2)


def pages(client, method, key):
    result, cursor, seen_cursors, identities = [], None, set(), set()
    for _ in range(MAX_PAGES):
        page = client.request(method, {} if cursor is None else {"cursor": cursor})
        entries = page.get(key)
        if not isinstance(entries, list) or len(result) + len(entries) > MAX_ENTRIES:
            raise AuditError("INVALID_OR_OVERSIZED_CATALOG")
        for entry in entries:
            identity_key = "uriTemplate" if key == "resourceTemplates" else "uri" if key == "resources" else "name"
            if not isinstance(entry, dict) or not isinstance(entry.get(identity_key), str):
                raise AuditError("INVALID_CATALOG_ENTRY")
            identity = entry[identity_key]
            if identity in identities:
                raise AuditError("DUPLICATE_CATALOG_ENTRY")
            identities.add(identity)
            result.append(entry)
        cursor = page.get("nextCursor")
        if cursor is None:
            return result
        if not isinstance(cursor, str) or not cursor or len(cursor) > 4096 or cursor in seen_cursors:
            raise AuditError("INVALID_OR_REPEATED_CURSOR")
        seen_cursors.add(cursor)
    raise AuditError("PAGE_BUDGET_EXCEEDED")


def identifier(value, prefix):
    if not isinstance(value, str) or len(value) > 4096 or not value.startswith(prefix) or any(ord(c) < 32 for c in value):
        raise AuditError("INVALID_PUBLIC_IDENTITY")
    return value


def collect(client):
    initialized = client.request("initialize", {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "turboism-readonly-catalog-audit", "version": "1"}})
    protocol = initialized.get("protocolVersion")
    if protocol not in PROTOCOLS:
        raise AuditError("UNSUPPORTED_NEGOTIATED_PROTOCOL")
    capabilities = initialized.get("capabilities")
    if not isinstance(capabilities, dict) or not all(isinstance(capabilities.get(k), dict) for k in ("tools", "resources", "prompts")):
        raise AuditError("REQUIRED_DISCOVERY_CAPABILITY_UNAVAILABLE")
    client.initialized()
    tools = pages(client, "tools/list", "tools")
    resources = pages(client, "resources/list", "resources")
    templates = pages(client, "resources/templates/list", "resourceTemplates")
    prompts = pages(client, "prompts/list", "prompts")
    if not any(r["uri"] == EDITOR_COMMANDS for r in resources):
        raise AuditError("EDITOR_COMMAND_RESOURCE_MISSING")
    read = client.request("resources/read", {"uri": EDITOR_COMMANDS})
    contents = read.get("contents")
    if not isinstance(contents, list) or len(contents) != 1 or not isinstance(contents[0], dict) or contents[0].get("uri") != EDITOR_COMMANDS or contents[0].get("mimeType") != "application/json":
        raise AuditError("INVALID_EDITOR_COMMAND_RESOURCE")
    try:
        command_data = json.loads(contents[0]["text"])
        commands = command_data["availableDirectCommands"]
        if not isinstance(commands, list) or len(commands) > 5000 or any(not isinstance(c, str) or not re.fullmatch(r"[a-z][a-z0-9_.-]{0,127}", c) for c in commands) or len(set(commands)) != len(commands):
            raise ValueError()
    except (KeyError, TypeError, ValueError):
        raise AuditError("INVALID_EDITOR_COMMAND_RESOURCE") from None
    # Only public identities and schema digests are retained: no raw initialize data,
    # descriptions, config, environment, stdio transcript, Bearer or session header.
    public_tools = []
    for tool in tools:
        if not isinstance(tool.get("inputSchema"), dict) or ("outputSchema" in tool and not isinstance(tool["outputSchema"], dict)):
            raise AuditError("INVALID_TOOL_SCHEMA")
        public_tools.append({"name": identifier(tool["name"], "turboism."), "inputSchemaSha256": digest(tool["inputSchema"]), "outputSchemaSha256": digest(tool["outputSchema"]) if "outputSchema" in tool else None})
    return {"schemaVersion": 1, "status": "DISCOVERY_SUCCEEDED", "capturedAt": datetime.now(timezone.utc).isoformat(), "protocolVersion": protocol,
            "scope": "Read-only catalog and editor-command discovery; no tool calls, model editing, saving or host acceptance",
            "tools": public_tools, "resources": [identifier(r["uri"], "turboism://") for r in resources],
            "resourceTemplates": [identifier(t["uriTemplate"], "turboism://") for t in templates],
            "prompts": [identifier(p["name"], "") for p in prompts], "availableDirectCommands": commands,
            "typedContractsSha256": digest(command_data.get("typedContracts")),
            "counts": {"tools": len(tools), "resources": len(resources), "resourceTemplates": len(templates), "prompts": len(prompts)}}


def write_report(path, report):
    data = encoded(report) + b"\n"
    if len(data) > MAX_REPORT:
        raise AuditError("REPORT_BUDGET_EXCEEDED")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="wb", dir=path.parent, prefix=".catalog-", delete=False) as file:
            temporary = Path(file.name)
            file.write(data)
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, help="Trusted local stdio descriptor; never copied to report")
    parser.add_argument("--server", default="turboism")
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("live-mcp-catalog.json"))
    parser.add_argument("--timeout", type=float, default=15)
    args = parser.parse_args(argv)
    try:
        config = args.config or (Path(os.environ["TURBOISM_MCP_CONFIG"]) if os.environ.get("TURBOISM_MCP_CONFIG") else None)
        if config is None:
            raise AuditError("NO_STDIO_CONFIG")
        if not 0 < args.timeout <= 60:
            raise AuditError("INVALID_TIMEOUT")
        if config.resolve() == args.output.resolve() or (args.output.exists() and config.samefile(args.output)):
            raise AuditError("OUTPUT_MUST_NOT_REPLACE_CONFIG")
        launch, env = load_launch(config, args.server)
        with StdioClient(launch, env, args.timeout) as client:
            report = collect(client)
        write_report(args.output, report)
        print("DISCOVERY_SUCCEEDED: catalog identities and schema hashes only; not model acceptance")
        return 0
    except AuditError as failure:
        print(str(failure), file=sys.stderr)
        return 2
    except (OSError, ValueError, TypeError, subprocess.SubprocessError):
        print("COLLECTION_FAILED", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
