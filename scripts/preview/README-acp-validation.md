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

## Artifacts

The bundle keeps the task-scoped evidence directory, the redacted runner log, the
Turboism home copy, and the re-verified hash manifests. Everything lives under the
task ID; nothing is written outside `~/TurboismValidation` and the worktree.
