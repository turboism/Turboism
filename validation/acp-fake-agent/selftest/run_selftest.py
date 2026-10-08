#!/usr/bin/env python3
"""Drives the acp fake agent as a real subprocess over the ACP stdio protocol.

Launches the agent with --limit-modules java.base,java.net.http (the Cubism
bundled JRE module set), attaches the production TurboismMcpBridge from the
mcp plugin jar against a local stub MCP server, and judges the agent's
terminal result file.
"""

import json
import os
import pathlib
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

SELFTEST_DIR = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(SELFTEST_DIR))
import stub_mcp_server  # noqa: E402

EXPECTED_ASSERTIONS = [
    "mcpServersStdio",
    "payloadCredentialFree",
    "bridgeMcpInitialize",
    "toolsListHasApply",
    "renameApplied",
    "renameReadback",
    "undoRestored",
    "historyPositionBalanced",
    "bridgeClosed",
]

TOKEN = "selftest-token-0123456789abcdef0123456789abcdef"


def fail(message):
    print(f"selftest FAIL: {message}", file=sys.stderr)
    sys.exit(1)


def main():
    if len(sys.argv) != 4:
        print("usage: run_selftest.py <fake-agent.jar> <mcp.jar> <java>", file=sys.stderr)
        sys.exit(2)
    agent_jar = pathlib.Path(sys.argv[1]).resolve()
    mcp_jar = pathlib.Path(sys.argv[2]).resolve()
    java = sys.argv[3]
    for artifact in (agent_jar, mcp_jar):
        if not artifact.is_file():
            fail(f"missing artifact: {artifact}")

    server = subprocess.Popen(
        [sys.executable, str(SELFTEST_DIR / "stub_mcp_server.py")],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        port = int(server.stdout.readline().strip())
        with tempfile.TemporaryDirectory(prefix="acp-fake-selftest-") as workdir:
            run(port, pathlib.Path(workdir), agent_jar, mcp_jar, java)
    finally:
        server.terminate()
        server.wait(timeout=10)


def run(port, workdir, agent_jar, mcp_jar, java):
    state_dir = workdir / "state" / "dev.turboism.plugin.mcp"
    state_dir.mkdir(parents=True)
    (state_dir / "mcp.token").write_text(TOKEN + "\n", encoding="utf-8")
    (state_dir / "mcp-connection.json").write_text(
        json.dumps({"endpoint": f"http://127.0.0.1:{port}/mcp"}) + "\n", encoding="utf-8"
    )
    agent_state_dir = workdir / "state" / "dev.turboism.plugin.acp"
    agent_state_dir.mkdir(parents=True)
    result_file = agent_state_dir / "agent-result.properties"
    scenario = workdir / "agent.properties"
    scenario.write_text(
        f"resultFile={result_file}\ntokenFile={state_dir / 'mcp.token'}\nstepTimeoutSeconds=60\n",
        encoding="utf-8",
    )

    process = subprocess.Popen(
        [
            java,
            "--limit-modules",
            "java.base,java.net.http",
            f"-Dturboism.acp.validation.bridgeConfig={scenario}",
            "-cp",
            str(agent_jar),
            "acp",
        ],
        cwd=agent_state_dir,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env={**os.environ, "TURBOISM_ACP_FAKE_RUN_ID": "selftest-run-0001"},
    )

    def send(message):
        process.stdin.write(json.dumps(message) + "\n")
        process.stdin.flush()

    def receive():
        line = process.stdout.readline()
        if not line:
            stderr = process.stderr.read() if process.poll() is not None else ""
            fail(f"agent closed stdout early; stderr={stderr.strip()[:2000]}")
        return json.loads(line)

    send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": 1}})
    initialized = receive()
    if initialized.get("result", {}).get("protocolVersion") != 1:
        fail(f"agent returned an unexpected initialize result: {initialized}")

    bridge_args = ["-cp", str(mcp_jar), "dev.turboism.plugin.mcp.TurboismMcpBridge", str(state_dir)]
    send(
        {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "session/new",
            "params": {
                "cwd": str(agent_state_dir),
                "mcpServers": [
                    {"name": "turboism", "command": java, "args": bridge_args, "env": []}
                ],
            },
        }
    )
    session = receive()
    if session.get("result", {}).get("sessionId") != "fake-session-1":
        fail(f"agent returned an unexpected session/new result: {session}")

    deadline = time.time() + 90
    while not result_file.is_file():
        if time.time() > deadline:
            fail(f"agent did not write {result_file} within 90s")
        if process.poll() is not None:
            fail(f"agent exited early with {process.returncode}; stderr={process.stderr.read()[:2000]}")
        time.sleep(0.5)
    process.stdin.close()
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        fail("agent did not exit after stdin EOF")

    judge(result_file)
    unauthenticated = urllib.request.Request(
        f"http://127.0.0.1:{port}/mcp",
        data=json.dumps(
            {
                "jsonrpc": "2.0",
                "id": 99,
                "method": "tools/call",
                "params": {
                    "name": "turboism.model_objects.apply",
                    "arguments": {"operations": [{"operation": "rename", "kind": "part", "id": "obj-part-1", "name": "X"}]},
                },
            }
        ).encode(),
        method="POST",
    )
    unauthenticated.add_header("Content-Type", "application/json")
    try:
        urllib.request.urlopen(unauthenticated)
        fail("unauthenticated mutating call unexpectedly succeeded against the stub")
    except urllib.error.HTTPError as error:
        if error.code != 401:
            fail(f"unauthenticated mutating call returned {error.code}, expected 401")
    print("selftest PASS")


def judge(result_file):
    rows = {}
    for line in result_file.read_text(encoding="utf-8").splitlines():
        key, _, value = line.partition("=")
        rows[key] = value
    if rows.get("schemaVersion") != "1":
        fail(f"result file schemaVersion is {rows.get('schemaVersion')!r}")
    if rows.get("runId") == "missing-run-id" or not rows.get("runId"):
        fail(f"result file runId is {rows.get('runId')!r}")
    for assertion in EXPECTED_ASSERTIONS:
        status = rows.get(f"assertion.{assertion}.status")
        if status != "PASS":
            reason = rows.get(f"assertion.{assertion}.reason", "-")
            fail(f"assertion {assertion} is {status!r} ({reason})")
    if rows.get("status") != "PASS":
        fail(f"terminal status is {rows.get('status')!r}")


if __name__ == "__main__":
    main()
