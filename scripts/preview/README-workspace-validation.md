# Turboism Windows Workspace Validation

Disposable, exact-host validation harness for the Workspace control slice
(`PluginContext.workspace()`). It proves the real
`PluginContext -> RuntimeWorkspaceService -> WorkspaceCoordinator` path while a
**separate validation javaagent** supplies the candidate exact-version
`WorkspaceHostProvider`. Production bootstrap composition is not modified:
`PluginContext.workspace()` stays UNAVAILABLE until the operator or the opt-in automatic matrix connects the provider.
connects the provider.

Nothing in this bundle modifies the Cubism installation, its launchers, its
licensing, or the original fixture. The outer BAT only sets validation-scoped
`JAVA_TOOL_OPTIONS`/`turboism.home` and then `call`s the official
`CubismEditor5.bat` with an isolated fixture copy.

This is **not** production admission or readiness evidence. Exact-version runs
are Lane C: every run must record the identity matrix below, and the main agent
must review the finished harness before launch.

## Contents

| File | Purpose |
| --- | --- |
| `turboism-agent.jar` | Production Turboism agent freshly built by `:bootstrap:jar` for this worktree (`build/worktree/<worktree>/bootstrap/libs`); packaging fails closed if the jar is missing, ambiguous, or embeds stale verification records. |
| `workspace-validation-agent.jar` | Disposable validation javaagent; `Premain-Class` manifest plus `Class-Path: turboism-agent.jar`. |
| `plugins/workspace-validation-probe.jar` | SDK-only validation plugin (only `PluginContext.workspace()` / `cubism()` / `tasks()` / `paths()`). |
| `cubism-5.2-workspace-control.json` | Exact 5.2.03 candidate workspace-control verification record (static, JAR-metadata; candidate selectors only). |
| `cubism-5.3.02-workspace-control.json` | Exact 5.3.02 candidate workspace-control verification record (static, JAR-metadata; candidate selectors only). |
| `launch-workspace-validation.bat.template` | Outer BAT template; edit the five `SET` lines, then rename to `.bat`. |
| `submit-workspace-command.ps1` | Command helper writing bounded `.cmd` files (temp + atomic rename). |
| `README.md` | This document. |
| `SHA256SUMS.txt` | Hashes of every packaged file. |

No Cubism binaries or assets are packaged.

## Preflight (offline)

```bash
./gradlew :bootstrap:jar :tests:testClasses --no-daemon --console=plain
scripts/preview/package-windows-workspace-validation.sh <bundle-dir>
```

Verify the bundle `SHA256SUMS.txt` against the files with `sha256sum -c` after
copying to the Windows host.

Before launch, record: Editor-reported version, `Live2D_Cubism.jar` size and
SHA-256, official BAT SHA-256, production agent SHA-256, validation agent
SHA-256, probe JAR SHA-256, record SHA-256 (packaged records are also hashed in
the bundle), source-fixture SHA-256, isolated-copy SHA-256, installation path,
prefix path, and evidence path. Fail closed on any mismatch.

## Automated matrix

The bounded automatic matrix reuses this probe, the validation javaagent, and the
shared exact-host runner in one task-scoped session:

```bash
./gradlew validateWorkspaceHost5302 --no-daemon --console=plain
./gradlew validateWorkspaceHost5203 --no-daemon --console=plain
# or, after packaging:
bash scripts/preview/run-workspace-host-validation.sh 5302 prep --dry-run
```

The probe writes `state/workspace-host-validation.properties` and emits
`WORKSPACE_HOST_VALIDATION_RESULT status=PASS|FAIL`. A PASS requires the active
host/document/model snapshot, typed disconnect baseline and fence, connect,
workspace switch/restore and default commands, EDT call-count evidence, and
reconnect availability; the generic runner separately enforces the unchanged
fixture hash and task-owned cleanup. The matrix does not claim persistence across
restart.

## Setup (per run, on the Windows host)

1. Copy the whole bundle to a task-scoped directory, e.g. `Z:\TurboismValidation\<task>\home`.
2. Copy the disposable fixture model next to it: `Z:\TurboismValidation\<task>\home\fixture.cmo3`.
   Keep the original model untouched and hash it before/after the run.
3. Edit `launch-workspace-validation.bat.template`:
   - `CUBISM_BAT` → official BAT of the exact version under test (5.2 or 5.3);
   - `TURBOISM_HOME` → the task-scoped bundle directory;
   - `FIXTURE` → the isolated fixture copy.
4. Rename to `launch-workspace-validation.bat`.

Optional: pass the validation-agent option `workspaceControlRecord=<path>` to
use a different record; the default is the record embedded in the production
agent (extracted to `state\verification\cubism-<profile>-workspace-control.json`).
A mismatched record or artifact fails closed at resolver creation.

## Launch and readiness

Close every other Cubism/Proton process for the task, then run
`launch-workspace-validation.bat`. Wait for:

- `logs\turboism.log` containing `Host adapter ... connected` (host=ACTIVE);
- an actual modeling document open on the fixture;
- `state\validation-agent.txt` with `status=DISCONNECTED`, the artifact identity
  (`artifactSha256` must equal the reviewed 5.2.03
  `bcc6e34f...d2b3dd` or 5.3.02 `988ef6a8...4c84f21`), the record hash, and the
  provider description.

Initial readiness is **DISCONNECTED**: the provider is created READY but is not
connected until the operator places the explicit `validation-agent.connect`
marker (see below). Until then, every probe command deterministically returns
typed UNAVAILABLE — that is the intended fail-closed baseline. If the agent
writes `status=FAILED` or times out, stop and review before proceeding.

## Degraded diagnostic mode (production host=FAILED baseline)

The authorized exact-version run observed the production `PreviewRuntime` reaching
`HostSession.FAILED` (`Host adapter entered FAILED: CONNECTION_FAILED` in
`logs\turboism.log`) — an unrelated aggregate-adapter baseline also visible in
other exact-host tasks, not a workspace-provider failure. When that baseline
blocks the normal ACTIVE gate, the workspace slice can still be exercised
narrowly by launching the validation agent with the explicit degraded option.

Exact agent option syntax (add to the validation-agent `-javaagent` option in
`JAVA_TOOL_OPTIONS`, after `home=...;timeoutSeconds=...`):

```text
allowDegradedRuntime=true
```

Example full validation-agent option:

```text
-javaagent:Z:\path\workspace-validation-agent.jar=home=Z:\path\workspace-validation;timeoutSeconds=180;allowDegradedRuntime=true
```

Rules:

- Default behavior is unchanged: without the option, only `hostState=ACTIVE`
  admits the runtime; a FAILED runtime times out and the evidence fails closed
  with the last observed state. Invalid/unknown/duplicate option values are
  rejected at premain.
- With `allowDegradedRuntime=true`, an already-created runtime in
  `hostState=FAILED` is admitted. `SAFE_MODE` and `CLOSED` are never
  admissible in any mode; the timeout still fails closed.
- Exact artifact/classloader/record verification is unchanged and still runs
  before provider creation; the READY-but-DISCONNECTED marker gate and the
  connect/disconnect controls are unchanged.
- Evidence records the observed production host state and degraded mode in
  every rewrite: `validation-agent.txt` contains `hostState=FAILED` and
  `degradedMode=true` identity lines, an event `production runtime admitted:
  degraded mode: host=FAILED`, and a DISCONNECTED detail prefixed
  `DEGRADED mode: ...`. Degraded classification follows the **admitted host
  state**, never the option flag: enabling the option on an actually ACTIVE
  runtime records `hostState=ACTIVE`, `degradedMode=false`, and the normal
  detail. A degraded run can never be mistaken for a healthy production
  session, and a healthy run is never mislabeled degraded.

What degraded mode proves and does not prove:

- It proves only the exact workspace slice and the public SDK path
  (`PluginContext.workspace()` → `RuntimeWorkspaceService` →
  `WorkspaceCoordinator` → exact-version `WorkspaceHostProvider`), through the
  SDK-only probe, with the provider still gated by the explicit connect marker.
- It does **not** satisfy `host=ACTIVE`, Cubism facade readiness, model/
  document proof, production admission, or readiness of any kind. No
  production capability may be admitted or enabled based on a degraded-mode
  run. The normal required real-host matrix below still requires ACTIVE.

## Command protocol

Commands are files in `state\dev.turboism.validation.workspace\commands\`
(the plugin's `PluginContext.paths().stateDir()`; the probe never constructs
paths under `turboism.home`):

```text
<seq>-<operation>.cmd          e.g. 1-status.cmd
content: one operation line, plus one argument line for switch:
  2-switch.cmd  ->  "switch\n<opaque workspace id>"
```

Operations: `status`, `current`, `readiness`, `switch <id>`, `update-default`,
`reset-default`. `switch` targets only an opaque id returned by a previous
`status`/`current` result; ids are never guessed.

Rules (enforced by the probe):

- sequence is a positive integer without leading zeros, strictly increasing;
  regressions/duplicates are recorded `DUPLICATE` and removed without touching
  any accepted result; the durable watermark persists in
  `protocol-state.txt`;
- file names must match `[1-9][0-9]{0,5}-[a-z0-9-]{1,64}\.cmd`; malformed names
  move to `rejected\` and are recorded;
- command content is at most 4096 bytes; oversized files move to `rejected\`;
- at most 64 commands are processed per scan;
- every accepted command is **claimed** by an atomic move into `inflight\`
  before any SDK call, executed at most once per claim, and the claim is
  removed only after the result file and the durable watermark are published.
  A crash or I/O failure between those steps leaves the claim: the next scan
  fails closed with `INFLIGHT_UNRESOLVED` and processes nothing until the
  operator resolves it (inspect the claimed file, then move it aside). An
  uncertain mutation is never auto-retried;
- the durable watermark must be readable and monotonic: an unreadable file
  fails closed (`WATERMARK_INVALID`), a disk watermark lower than the
  in-memory value fails closed (`WATERMARK_REGRESSED`), accepted result files
  above the watermark fail closed (`ACCEPTED_RESULTS_ABOVE_WATERMARK`), and
  accepted results without a watermark file fail closed
  (`RESULTS_WITHOUT_WATERMARK`). The watermark is never reset to zero
  automatically. Recovery is an explicit operator decision: restore a
  consistent watermark equal to the highest accepted result sequence, or
  archive/clear the accepted results and start a fresh sequence; deleting only
  the watermark while accepted results remain fails closed.

The watermark and every result file are published via same-directory
temp-then-atomic-move (replace fallback when atomic moves are unsupported);
the helper publishes command files the same way, so the probe can never observe
a partial two-line `switch` command.

Helper (from the bundle directory, PowerShell only):

```powershell
.\submit-workspace-command.ps1 -Sequence 1 -Operation status
.\submit-workspace-command.ps1 -Sequence 2 -Operation current
.\submit-workspace-command.ps1 -Sequence 3 -Operation readiness
.\submit-workspace-command.ps1 -Sequence 4 -Operation switch -Argument modeling
.\submit-workspace-command.ps1 -Sequence 5 -Operation update-default
.\submit-workspace-command.ps1 -Sequence 6 -Operation reset-default
```

Results are written to `state\dev.turboism.validation.workspace\results\<seq>-<op>.txt`
and the append-only `evidence.txt`. Result files are key=value lines:

```text
status=OK
sequence=000004
command=switch
argument=bW9kZWxpbmc=
thread=turboism-task-<id>
edt=false
declaredPermissions=turboism.cubism.model.read,turboism.cubism.project.read,turboism.host.unsafe
outcome=CHANGED
result.availability=AVAILABLE
result.currentId=bW9kZWxpbmc=
result.currentName=TW9kZWxpbmc=
result.availableCount=2
result.available.0.id=bW9kZWxpbmc=
result.available.0.name=TW9kZWxpbmc=
```

Mutations add `outcome=CHANGED|NO_CHANGE|UNAVAILABLE|NOT_FOUND|FAILED` and a
`result.*` status block. Permission denial is recorded as `status=DENIED` with
`permissionDenied=true`; unexpected failures as `status=ERROR` with `error=`.
Typed UNAVAILABLE results are recorded as `status=OK` evidence with
`availability=UNAVAILABLE` — the operation failed closed as designed.

### Encoding of host-derived values

All host-derived ids and names (workspace ids/names, document ids/names, model
ids) are Base64-encoded UTF-8, reversibly, so hostile values cannot inject
newlines or fake key=value lines. Values larger than 512 UTF-8 bytes are
redacted as `__REDACTED__`. Decode with PowerShell:

```powershell
[System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('bW9kZWxpbmc='))
# -> modeling
```

or on Linux: `printf 'bW9kZWxpbmc=' | base64 -d`. The opaque id passed to
`switch` is the decoded plain id value. All free text (errors, denials,
diagnostics) is sanitized to a single line and capped.

### Readiness command

`readiness` is a bounded SDK-only read through the facade snapshot APIs
(`PluginContext.cubism().activeDocument()` / `activeModel()`) that records:
`hostPresent=`, the active `DocumentSnapshot` identity
(`documentId`/`documentName`, Base64), `modelPresent=` plus `modelId=` (Base64),
and the active model snapshot (`activeModelPresent=`, `activeModelId=`,
`activeModelName=`, Base64). Unexpected host failures surface as command
`status=ERROR` evidence; they are never masked as "unavailable". This is the
actual modeling-document signal; it is validation-only and never exposes raw
host objects.

## Agent connect/disconnect control (stale-call fail-closed check)

The probe runs as one scheduled low-frequency plugin task guarded by a scan
lock. `disable`/`shutdown` clear the enabled flag under the lock, cancel the
handle, and return only after any in-flight scan has left the lock; a scan that
starts afterwards observes the disabled flag before any service or I/O, so no
probe polling survives disable (covered by the offline lifecycle tests).

The validation agent starts READY-but-DISCONNECTED and polls two marker files
under `state\`:

| Marker | Effect |
| --- | --- |
| `validation-agent.connect` | Connects the provider to the production `WorkspaceCoordinator` (marker consumed, event recorded). |
| `validation-agent.disconnect` | Disconnects the provider (marker consumed, event recorded). |

If both markers exist at once the ambiguity fails closed: the provider is
disconnected if it was connected, both markers are consumed, and the agent
remains DISCONNECTED. While
disconnected, every probe command must return typed UNAVAILABLE with
`diagnosticCode=workspace.provider.unavailable`, and the provider call counts
must stop changing. This proves stale calls fail closed after disconnect.
`validation-agent.txt` records provider call counts
(`counts=read=... switch=... update=... reset=... onEdt=... offEdt=...
lastThread=... lastOnEdt=...`); `onEdt`/`lastOnEdt` prove the host operations
ran on the AWT EDT and `lastThread` records the executing thread name.

### Interpreting the provider `switch` count

The counting wrapper increments `switch` for every `switchTo` the coordinator
dispatches — including NO_CHANGE and NOT_FOUND outcomes. The underlying Cubism
`changeWorkspace` side effect happens only for CHANGED: the provider resolves
the id from the live existing-workspace list, returns NO_CHANGE before touching
Cubism when the target is already current, and returns NOT_FOUND without
invoking Cubism when the id is absent (established by the offline provider unit
tests). Treat the count as "coordinator dispatches", not "Cubism layout
changes": combine it with the exact typed outcome and the visible Editor
workspace state.

## Required real-host matrix (record per version)

Normal-mode requirement: this matrix applies to ACTIVE runs only. Degraded
mode (see above) can cover only the narrow workspace-slice items and never
replaces items 1, 4–11, or any readiness claim.

For each exact version (serial runs, isolated CoW prefix, official BAT):

1. identity matrix (above) and `host=ACTIVE` + actual modeling document
   (`readiness` shows `hostPresent=true`, a document identity, and a model);
2. baseline: `status`/`current`/`readiness` before the connect marker → typed
   UNAVAILABLE / host-present true, provider counts frozen at zero;
3. place the `validation-agent.connect` marker → `state\validation-agent.txt`
   becomes `CONNECTED`;
4. `current` returns the initial workspace id/name and the live available list;
5. `switch` to an id from `available` → `outcome=CHANGED`, post-switch id/name
   match, visible layout changes; visible Editor workspace controls agree;
6. `switch` to the current id → `NO_CHANGE`, and the wrapper `switch` count
   increments while the visible layout does not change;
7. `switch` to an id absent from `available` → `NOT_FOUND`, no layout change;
8. `update-default`: alter a disposable layout, invoke, switch away/back (or
   restart as required), prove stored default restoration;
9. `reset-default`: alter the layout again, invoke, prove exact restoration to
   the saved default;
10. disconnect marker → `status`/mutations all typed UNAVAILABLE, counts
    frozen; connect marker → AVAILABLE again, counts resume;
11. persistence across clean close/reopen when persistence is claimed;
12. workspace layout writes must produce **no model Undo entry** and leave the
    original model hash unchanged (workspace layout is not model authoring; the
    generic parameter/Part Undo/Redo rule does not apply here);
13. task-owned process cleanup only; prefix and evidence task-scoped.
