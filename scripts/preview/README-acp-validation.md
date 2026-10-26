# Turboism ACP exact-host validation bundle

This task-local bundle validates the production ACP plugin end to end inside a real
Cubism Editor session: the probe opens the Agent window through the public action
catalog, the ACP plugin attaches the credential-free stdio bridge to a scripted fake
agent, and that agent performs one `rename` mutation through the token-gated
`TurboismMcpBridge` with a guarded undo and an SDK cross-read. The bundle never runs
against the golden Proton prefix directly and never stages the MCP token outside the
bridge process.

## Managed local execution

Build `previewBundle`, `:plugins:mcp:jar`, and `:plugins:acp:jar`, then run
`validation/acp-fake-agent/build.sh`, `validation/acp-host-probe/build.sh`, and
`scripts/preview/package-windows-acp-validation.sh`. Use the existing ignored `.env`
fixture and exact-host settings, or set `TURBOISM_ENV_FILE` explicitly for an isolated
worktree.

```bash
python3 scripts/preview/host_validation.py plan acp:5303
python3 scripts/preview/host_validation.py prepare acp:5303 --run-label acp-stdio
python3 scripts/preview/host_validation.py submit --prepared PREPARED_ID --request-id UNIQUE_REQUEST --json
python3 scripts/preview/host_validation.py wait JOB_ID
```

Substitute returned IDs. Versions `5203`, `5302`, and `5303` share the same single
host slot and require the `display-input` resource because the 5.3.02 close route uses
a Robot Alt+F4. No `--client-script` and no `--remote-*` hook is used, so the entry is
admitted by the standard manifest review only. Preparation and wrapper `--dry-run` do
not launch Cubism and are not a readiness verdict.

```bash
bash scripts/preview/run-acp-host-validation.sh 5303 acp-dryrun --dry-run
```

## What the runner stages

- `mcp.jar`, `acp.jar`, and `acp-host-validation-probe.jar` under `home/plugins/`;
  all three auto-load, and ACP declares a required dependency on the MCP plugin.
- `config/dev.turboism.plugin.acp/settings.properties` seeded per version with
  `agentId=custom` and the version's bundled JRE `java.exe` as `customCommand`
  (forward slashes, no `acpSessionId`, so the first open issues `session/new`).
- `acp-validation/acp-fake-agent.jar` and `acp-validation/agent.properties` under the
  task home, referenced through `{HOME}` JVM options:
  `turboism.acp.validation.bridge=true`,
  `turboism.acp.validation.bridgeClassPath={HOME}\acp-validation\acp-fake-agent.jar`,
  and `turboism.acp.validation.bridgeConfig={HOME}\acp-validation\agent.properties`.

## Evidence and judging

The probe writes `state/acp-host-validation.properties` under the task home with
`schemaVersion=1`, the runner-bound `runId`, one `assertion.*.status` row per check,
`assertion.persistence.status=NOT_APPLICABLE` (save/reopen persistence is covered by
the accepted `mcp` capability evidence `textureSaveReopen`), and the terminal
`status=PASS` or `status=FAIL`. The fake agent's own ledger rows are copied under
`assertion.agent.*`. The runner passes only when the terminal `status=PASS` line
appears, the fixture hash is unchanged, and the golden jar and BAT hashes re-verify.

Fake agent assertions: exactly one stdio `mcpServers` entry with empty `env`, no
token material in the payload (compared against the real `mcp.token` contents),
bridge `initialize`, `tools/list` containing `turboism.model_objects.apply`,
rename applied, renamed readback, guarded undo restoring the original name, a
balanced history position, and a clean bridge exit. Probe assertions: the stdio
launch descriptor was published, the action-catalog open succeeded, the agent
reached a terminal result, the probe's SDK history position matches the agent's
`historyPositionBefore`, and no object name still carries the rename prefix.

The probe opens the MCP connection window through its registered action, verifies
that it is visible, records `mcpConnectionWindowOpened`, and captures
`state/mcp-connection-window.png` before hiding it. This exercises table header
localization during the actual window constructor.

The probe also loads the staged production ACP JAR in a separate classloader and
checks Claude/Codex/Pi capability detection with controlled `.cmd` fixtures under
a path containing spaces. These run in the host Windows JRE and emit version and
help only on stderr. They validate Windows process invocation, not the user's
installed CLI binaries. Results are recorded as `windowsHarnessProbe.*`.

The probe also captures the actual Agent Swing window as
`state/acp-agent-window.png`, checks the enabled harness foreground and hidden
unsupported options, and verifies that only the Turboism-owned session appears
even when the agent lists an unrelated CLI session. It checks the persisted
session binding and scoped session index. The probe makes only the task-local
copy of the ACP settings writable; the frozen queue inputs remain unchanged.
After checking persistence it restores that copy so the runner's seeded-file
integrity checks still apply.
These host checks use the scripted agent; native CLI smoke tests run separately.

## Artifacts

The bundle keeps the task-scoped evidence directory, the redacted runner log, the
Turboism home copy, and the re-verified hash manifests. Everything lives under the
task ID; nothing is written outside `~/TurboismValidation` and the worktree.

## Native render through the real ACP transcript (5.3.03)

The managed `5303` lane also enables `TURBOISM_ACP_FAKE_NATIVE_RENDER=1` for the
scripted agent. Other versions keep this additional acceptance lane disabled.
The agent discovers all tool pages with a bounded, non-repeating cursor chain,
reads `render.state`, calls the production `render.capture` at 256×256, checks the
native renderer identity, exact document/model/revision/pose, PNG header/size/hash,
and verifies the capture did not change state or parameters. It forwards the
original metadata text and image as ACP typed tool content; no image base64 goes
into a text block. Rendering runs after guarded rename/undo restoration.

The host probe waits for the production preview worker, then inspects the actual
Agent window's `StyledDocument`. It requires exactly one image icon with all
256×256 ARGB pixels equal to the native PNG, and a matching tool/capture/document/
model/revision/dimensions label. It rejects base64 in transcript text and captures
the window after the checks. This is a scripted ACP-v1 path with a real host,
bridge, production render and Swing UI; it does **not** prove a real model saw or
understood the image, nor does it validate native Pi/Codex/Claude providers.

Evidence is `state/dev.turboism.plugin.acp/agent-native-render.{json,png}`,
`state/acp-transcript-render.png`, `state/acp-agent-window.png`, and the agent/host
properties reports (`nativeRenderTypedImage`, `nativeRenderAcpForwarded`,
`nativeRenderSwing`, `nativeRenderNoBase64Text`). The probe never reads plugin
private fields or injects image icons. Offline checks:

```bash
bash validation/acp-fake-agent/build.sh selftest
bash validation/acp-host-probe/build.sh selftest
```

The first test uses a local stub MCP service and rejects corrupt image hash,
state and type before forwarding. The second checks the independent Swing evidence
matcher with matching/missing/wrong/duplicate icons, stale provenance and base64
text. Neither offline check proves host or model acceptance.

Packaging also checks the three staged JAR identities and every required plugin
dependency against the actual stamped versions. A stale range fails before host
launch (`validation/acp-host-probe/check_bundle.py`); the probe follows the
production ACP plugin's current `[0.1.0,0.68.0)` MCP dependency window.


## Raw-output-only render forwarding

For exact 5.3.03, the scripted ACP agent forwards its verified production MCP render
response as `tool_call_update.rawOutput`, without an ACP `content` projection. The
production client must convert that MCP envelope into typed text/image content and
show the same PNG pixels and reported capture provenance in the existing Swing UI.
The agent records `nativeRenderAcpFormat=RAW_OUTPUT_ONLY`; the host probe retains its
full ARGB/source identity checks. The fake-agent subprocess selftest rejects wrong
PNG hash, changed render state and lost image type before forwarding anything.

This tests the ACP result-display path with a scripted agent, not external model
image consumption, automatic artistic judgment or durable operation recovery.
Native adapters with explicit nonempty ACP content keep their previous path; raw
output is a fallback and is never merged into existing images or used to change
the agent-reported tool status.
