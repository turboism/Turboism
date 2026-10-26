import contextlib
import io
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest import mock

import collect_live_catalog as audit

SECRET = "PRIVATE_BEARER_SESSION_SENTINEL"


def entry(name):
    return {"name": name, "description": SECRET, "inputSchema": {"type": "object", "description": SECRET}, "outputSchema": {"type": "object"}}


class Fake:
    def __init__(self):
        self.calls = []
        self.repeated = False
        self.duplicate = False
        self.protocol = "2025-11-25"
        self.capabilities = {"tools": {}, "resources": {}, "prompts": {}}

    def initialized(self):
        self.calls.append(("notifications/initialized", {}))

    def request(self, method, params):
        self.calls.append((method, params))
        if method == "initialize":
            return {"protocolVersion": self.protocol, "capabilities": self.capabilities, "serverInfo": {"secret": SECRET}}
        if method == "tools/list":
            if not params:
                return {"tools": [entry("turboism.first")], "nextCursor": SECRET}
            return {"tools": [entry("turboism.first" if self.duplicate else "turboism.second")], **({"nextCursor": SECRET} if self.repeated else {})}
        if method == "resources/list":
            return {"resources": [{"uri": audit.EDITOR_COMMANDS, "description": SECRET}]}
        if method == "resources/templates/list":
            return {"resourceTemplates": [{"uriTemplate": "turboism://parameter/{id}", "description": SECRET}]}
        if method == "prompts/list":
            return {"prompts": [{"name": "inspect_model", "description": SECRET}]}
        if method == "resources/read" and params == {"uri": audit.EDITOR_COMMANDS}:
            return {"contents": [{"uri": audit.EDITOR_COMMANDS, "mimeType": "application/json", "text": json.dumps({"availableDirectCommands": ["save", "new.model"], "typedContracts": {"private": SECRET}})}]}
        raise AssertionError((method, params))


class CollectorTest(unittest.TestCase):
    def test_readonly_discovery_pagination_and_nonexport(self):
        client = Fake()
        report = audit.collect(client)
        self.assertEqual({"tools": 2, "resources": 1, "resourceTemplates": 1, "prompts": 1}, report["counts"])
        self.assertEqual(["save", "new.model"], report["availableDirectCommands"])
        self.assertEqual("DISCOVERY_SUCCEEDED", report["status"])
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertTrue(all(len(t["inputSchemaSha256"]) == 64 for t in report["tools"]))
        self.assertEqual({"cursor": SECRET}, client.calls[3][1])
        self.assertEqual({"uri": audit.EDITOR_COMMANDS}, client.calls[-1][1])
        self.assertFalse(any(method == "tools/call" for method, _ in client.calls))

    def test_bad_pagination_never_returns_partial_success(self):
        for field, error in [("repeated", "INVALID_OR_REPEATED_CURSOR"), ("duplicate", "DUPLICATE_CATALOG_ENTRY")]:
            client = Fake()
            setattr(client, field, True)
            with self.assertRaisesRegex(audit.AuditError, error):
                audit.collect(client)

    def test_negotiation_and_capabilities_fail_before_catalog_reads(self):
        client = Fake()
        client.protocol = "unknown"
        with self.assertRaises(audit.AuditError):
            audit.collect(client)
        self.assertEqual(["initialize"], [method for method, _ in client.calls])
        client = Fake()
        client.capabilities = {"tools": {}}
        with self.assertRaisesRegex(audit.AuditError, "CAPABILITY_UNAVAILABLE"):
            audit.collect(client)
        self.assertEqual(1, len(client.calls))

    def test_transport_rejects_tool_calls_and_arbitrary_reads_before_io(self):
        client = audit.StdioClient([], {})
        for method, params in [("tools/call", {"name": "save"}), ("prompts/get", {}), ("resources/read", {"uri": "file:///private"}), ("resources/read", {"uri": audit.EDITOR_COMMANDS, "extra": True})]:
            with self.assertRaisesRegex(audit.AuditError, "READ_SCOPE_REJECTED"):
                client.request(method, params)

    def test_config_is_bounded_and_never_accepted_as_http(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.write_text(json.dumps({"mcpServers": {"turboism": {"command": sys.executable, "args": ["bridge.py"], "env": {"TOKEN": SECRET}}}}))
            argv, env = audit.load_launch(path)
            self.assertEqual([sys.executable, "bridge.py"], argv)
            self.assertEqual(SECRET, env["TOKEN"])
            path.write_text(json.dumps({"type": "http", "url": SECRET}))
            with self.assertRaises(audit.AuditError):
                audit.load_launch(path)

    def test_cli_without_configuration_cannot_write_success(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(os.environ, {}, clear=True):
            out = Path(directory) / "catalog.json"
            with contextlib.redirect_stderr(io.StringIO()) as stderr:
                self.assertEqual(2, audit.main(["--output", str(out)]))
            self.assertIn("NO_STDIO_CONFIG", stderr.getvalue())
            self.assertFalse(out.exists())

    def test_config_cannot_be_overwritten_by_output(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "config.json"
            config.write_text(SECRET)
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, audit.main(["--config", str(config), "--output", str(config)]))
            self.assertEqual(SECRET, config.read_text())

    def fixture(self, directory, initialization_failure=False):
        root = Path(directory)
        script = root / "fake_bridge.py"
        script.write_text('''import json, os, sys
for line in sys.stdin:
    request=json.loads(line)
    with open(os.environ['REQUEST_LOG'],'a') as log: log.write(json.dumps(request)+'\\n')
    if 'id' not in request: continue
    method=request['method']
    if method=='initialize' and os.environ.get('FAIL_INIT')=='1':
        print(json.dumps({'jsonrpc':'2.0','id':request['id'],'error':{'code':-32000,'message':os.environ['PRIVATE']}}),flush=True)
        continue
    if method=='initialize': result={'protocolVersion':'2025-11-25','capabilities':{'tools':{},'resources':{},'prompts':{}},'serverInfo':{'private':os.environ['PRIVATE']}}
    elif method=='tools/list': result={'tools':[{'name':'turboism.example','inputSchema':{'type':'object','description':os.environ['PRIVATE']}}]}
    elif method=='resources/list': result={'resources':[{'uri':'turboism://host/editor-commands'}]}
    elif method=='resources/templates/list': result={'resourceTemplates':[]}
    elif method=='prompts/list': result={'prompts':[]}
    elif method=='resources/read': result={'contents':[{'uri':request['params']['uri'],'mimeType':'application/json','text':json.dumps({'availableDirectCommands':['save'],'typedContracts':{'private':os.environ['PRIVATE']}})}]}
    else: raise AssertionError(method)
    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
''')
        config = root / "config.json"
        config.write_text(json.dumps({"command": sys.executable, "args": [str(script)], "env": {"REQUEST_LOG": str(root / "requests.jsonl"), "PRIVATE": SECRET, "FAIL_INIT": "1" if initialization_failure else "0"}}))
        return config, root / "requests.jsonl"

    def test_real_stdio_fake_bridge_proves_only_allowed_wire_methods(self):
        with tempfile.TemporaryDirectory() as directory:
            config, log = self.fixture(directory)
            out = Path(directory) / "catalog.json"
            with contextlib.redirect_stdout(io.StringIO()) as stdout:
                self.assertEqual(0, audit.main(["--config", str(config), "--output", str(out)]))
            self.assertNotIn(SECRET, stdout.getvalue() + out.read_text())
            report = json.loads(out.read_text())
            self.assertEqual(1, report["counts"]["tools"])
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(["initialize", "notifications/initialized", "tools/list", "resources/list", "resources/templates/list", "prompts/list", "resources/read"], [c["method"] for c in calls])
            self.assertEqual({"uri": audit.EDITOR_COMMANDS}, calls[-1]["params"])
            self.assertFalse(list(Path(directory).glob(".catalog-*")))

    def test_initialize_minus_32000_cannot_create_or_replace_success_report(self):
        with tempfile.TemporaryDirectory() as directory:
            config, log = self.fixture(directory, True)
            out = Path(directory) / "catalog.json"
            for exists in (False, True):
                if exists:
                    out.write_text("old evidence")
                with contextlib.redirect_stderr(io.StringIO()) as stderr:
                    self.assertEqual(2, audit.main(["--config", str(config), "--output", str(out)]))
                self.assertIn("RPC_ERROR_-32000", stderr.getvalue())
                self.assertNotIn(SECRET, stderr.getvalue())
                self.assertEqual("old evidence" if exists else None, out.read_text() if out.exists() else None)
            self.assertEqual(["initialize", "initialize"], [json.loads(line)["method"] for line in log.read_text().splitlines()])

    def test_response_timeout_terminates_only_the_launched_fake_bridge(self):
        with audit.StdioClient([sys.executable, "-c", "import time; time.sleep(30)"], {}, timeout=.03) as client:
            with self.assertRaisesRegex(audit.AuditError, "BRIDGE_TIMEOUT"):
                client.request("initialize", {})
        self.assertIsNotNone(client.process.poll())

    def test_server_requests_are_not_executed_or_answered(self):
        script = "import json; print(json.dumps({'jsonrpc':'2.0','id':77,'method':'tools/call','params':{'private':'" + SECRET + "'}}),flush=True)"
        with audit.StdioClient([sys.executable, "-c", script], {}, timeout=2) as client:
            with self.assertRaisesRegex(audit.AuditError, "UNEXPECTED_BRIDGE_MESSAGE"):
                client.request("initialize", {})

    def test_page_entry_and_report_budgets_fail_before_report_replacement(self):
        with mock.patch.object(audit, "MAX_PAGES", 1):
            with self.assertRaisesRegex(audit.AuditError, "PAGE_BUDGET_EXCEEDED"):
                audit.collect(Fake())
        with mock.patch.object(audit, "MAX_ENTRIES", 1):
            with self.assertRaisesRegex(audit.AuditError, "OVERSIZED_CATALOG"):
                audit.collect(Fake())
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "report.json"
            out.write_text("previous evidence")
            with mock.patch.object(audit, "MAX_REPORT", 10):
                with self.assertRaisesRegex(audit.AuditError, "REPORT_BUDGET_EXCEEDED"):
                    audit.write_report(out, {"data": "x" * 20})
            self.assertEqual("previous evidence", out.read_text())
            self.assertFalse(list(Path(directory).glob(".catalog-*")))

    def test_cli_sanitizes_subprocess_exceptions_with_sensitive_argv(self):
        with tempfile.TemporaryDirectory() as directory:
            config, _ = self.fixture(directory)
            out = Path(directory) / "catalog.json"
            with mock.patch.object(audit.StdioClient, "__enter__", side_effect=subprocess.TimeoutExpired([SECRET], 1)):
                with contextlib.redirect_stderr(io.StringIO()) as stderr:
                    self.assertEqual(2, audit.main(["--config", str(config), "--output", str(out)]))
                self.assertEqual("COLLECTION_FAILED\n", stderr.getvalue())
                self.assertFalse(out.exists())

    def test_protocol_malformed_oversized_and_closed_stdio_fail_without_secrets(self):
        scripts = ["print('not-json',flush=True)", "print('x'*100,flush=True)", "pass"]
        for script in scripts:
            with mock.patch.object(audit, "MAX_LINE", 80):
                with audit.StdioClient([sys.executable, "-c", script], {}, timeout=2) as client:
                    with self.assertRaises(audit.AuditError) as raised:
                        client.request("initialize", {})
                    self.assertNotIn(SECRET, str(raised.exception))


if __name__ == "__main__":
    unittest.main()
