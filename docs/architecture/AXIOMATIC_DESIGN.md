# Axiomatic Design Baseline (`#335`)

## 1. Scope and Independence Axiom

This document applies Nam P. Suh's Axiomatic Design (AD) **Independence Axiom** to Turboism.
The goal is to preserve independent plugin workflows and expose hidden functional coupling across shared Cubism host state, lifecycle hooks, and runtime services.

A directed acyclic build or import graph (`docs/architecture/dependency-inventory.json`) proves compilation and classloader boundaries only.
Static acyclicity **never** proves functional independence: two plugins with zero code dependencies can still interfere through shared host parameters, Undo brackets, UI defaults, or event queues.

The **Information Axiom** (minimizing information content via empirical success probabilities) remains out of scope until reliable multi-version telemetry exists.
The authoritative machine-readable companion to this document is `docs/architecture/design-matrix.json`, validated against `docs/architecture/design-matrix.schema.json` by `checkAxiomaticDesignMatrix`.

## 2. Structural Baseline vs Functional Matrix

Turboism keeps structural dependency inventories strictly separate from functional effect matrices:

| Artifact | Question Answered | Granularity | Authority |
| --- | --- | --- | --- |
| `docs/architecture/dependency-inventory.json` | Which modules and `:runtime` packages import each other statically? | 50 Gradle nodes (40 plugins), 93 non-test edges, 36 `:runtime` top packages | `checkDependencyInventory`, `checkModuleBoundaries` |
| `docs/architecture/design-matrix.json` | How does changing or operating Design Parameter $DP_j$ affect Functional Requirement $FR_i$ at runtime? | 5 constraints (`C1`–`C5`), 17 `FR`/`DP` pairs, 4 `PV`s, 3 peer submatrices | `checkAxiomaticDesignMatrix` + interaction tests |

Reconciled structural figures:

- **50 Gradle graph nodes** in `settings.gradle.kts` (40 `:plugins:*` modules and 10 framework/test modules; 49 declare outgoing project edges and `:sdk` is the sink).
- **93 non-test configuration edges** (including `annotationProcessor :event-processor` and `compileOnly :sdk`) and **99 test-only configuration edges**.
- **0 non-test module cycles** and **0 direct production edges between plugins** (`:plugins:atlas-*` share 3 test-only edges).
- **0 `:runtime` leaf-package cycles**.
- **36 `:runtime` top-level packages**, **159 top-level edges**, and **0 top-level package cycles** (the former nine-package top-level SCC was eliminated by enforcing acyclic `core`, `config`, `automesh`, and `ui` layering in `checkModuleBoundaries`).

Static package acyclicity enforces compilation order only; functional independence remains governed by `design-matrix.json` and behavioral interaction tests.

## 3. System Constraints (`C1`–`C5`)

Security, legal boundaries, host-version admission, resource budgets, and single-truth host invariants constrain multiple `FR`/`DP` pairs simultaneously rather than mapping to one local knob:

- **`C1` — Host legal and binary integrity boundary**: Never redistribute Cubism binaries, bypass licensing, or weave unverified host bytecode (`VerifiedInstall`, `checkModuleBoundaries`, `checkRepositoryHygiene`).
- **`C2` — SDK isolation and ABI boundary**: Plugins compile against `:sdk` (`compileOnly`) only; `com.live2d.*`, runtime internals, host ClassLoaders, and unapproved Swing/native handles never cross `:sdk` (`checkPluginBoundaries`, `checkSdkApiCurrent`).
- **`C3` — Exact-version fail-closed compatibility**: Capabilities outside reviewed releases (`5.0.00–5.3.05`, 21 exact versions; Tier-1 `5.2.03` and `5.3.02+`) or missing verified selectors fail closed per capability without blocking official Editor startup (`:compatibility-policy`, `checkCompatibilityMatrix`).
- **`C4` — Interactive responsiveness and bounded execution**: Plugin tasks, callbacks, and EDT handoffs obey bounded queues, timeouts, and `EdtDispatch` rules (`checkCodeQuality`, `RuntimeScheduler`).
- **`C5` — Single Editor write truth and generation safety**: Attached-document writes execute on the host thread through Editor authoring transactions with native Undo parity and fail closed across document, model, and plugin generations (`EditorAuthoringTransactionCoordinator`, `PluginGenerationGuard`).

## 4. Functional Requirements and Design Parameters (`FR1`–`FR17`)

The 11 product domains refine into 17 measurable, solution-independent `FR`s paired 1-to-1 with traceable `DP`s in topological order:

| Order | Domain | Functional Requirement (`FR`) | Acceptance Criterion | Design Parameter (`DP`) |
| --- | --- | --- | --- | --- |
| 1 | `D1` Host integration | **`FR1`**: Install, verify, and restore runtime bytecode hooks without blocking or corrupting official Cubism startup. | Every hook attests target digests before weave, refuses unverified hosts cleanly, and hashes back to original bytes on close. | **`DP1`**: `VerifiedInstall` agent bootstrap, `HookManifest`/`WeavePlan` ordering (`:bootstrap`, `dev.turboism.bootstrap`). |
| 2 | `D2` Compatibility | **`FR2`**: Admit reviewed Cubism versions and gate per-slice capabilities without cross-version selector drift. | All 21 reviewed releases resolve exact capability sets offline; unknown versions fail closed. | **`DP2`**: `:compatibility-policy` predicates, `VerifiedMemberResolver`, and `CubismEditorApiAvailabilityInterceptor`. |
| 3 | `D3` Plugin lifecycle | **`FR3`**: Isolate plugin loading, service wiring, generation fencing, and teardown across concurrent plugins. | Failing, disabling, or fencing one plugin generation revokes its handles and hooks while neighbor plugins stay active. | **`DP3`**: `LocalPluginRuntime`, `PluginClassLoader`, `PluginGenerationGuard`, `DisposableScope`, `CorePluginContext`, and `RuntimeEventBroker`. |
| 4 | `D4` Persistence | **`FR4`**: Persist, validate, migrate, and recover typed configuration and scoped plugin storage atomically. | Schemas round-trip deterministically, migrations capture `.recovery-points`, and failed migrations preserve prior bytes. | **`DP4`**: `:persistence-core`, `TypedConfigDocumentStore`, and confined `RuntimePluginStorage`. |
| 5 | `D5` Responsiveness | **`FR5`**: Bound callback latency, background execution, and opt-in host render optimizations within interactive budgets. | Executors enforce budgets/timeouts, EDT handoffs use `EdtDispatch`, and render hooks expose persisted kill switches. | **`DP5`**: `RuntimeScheduler`, `PluginWorkExecutorRegistry`, `EdtDispatch`, `RuntimeAsyncHostReadService`, and opt-in render hooks. |
| 6 | `D6` UI extension | **`FR6`**: Apply persisted theme colors before initial GL scene caching and restore native appearance on demand. | Valid selections inject FlatLaf defaults before scene init, invalid files emit one bounded diagnostic, and restore deletes overrides. | **`DP6`**: `EarlyThemeAppearanceBootstrap`, `ThemeRuntimeProperties`, `RuntimeAppearanceService`, and `ThemeSelectionConfig`. |
| 7 | `D6` UI extension | **`FR7`**: Contribute, localize, and revoke menus, toolbars, context menus, panels, and shell views per plugin generation. | Contributions enforce granular permissions, bind localization, and detach cleanly on registration or generation close. | **`DP7`**: `EditorUiContributionAuthority`, `UiCapabilityBundle` registries, `dev.turboism.shell`, and workspace UI plugins. |
| 8 | `D7` Shared state | **`FR8`**: Execute host-thread model mutations with atomic Undo parity, lifecycle ordering, and coalesced selection observation. | Committed transactions record one verified Undo entry, failures compensate to prior history, and concurrent edit scopes reject cleanly. | **`DP8`**: `EditorAuthoringTransactionCoordinator` (`editScopeGate`), `EditorBackedCubismModelAccess`, `ParameterLifecycleCoordinator`, and `SelectionQueryServiceImpl`. |
| 9 | `D8` Parameter editing | **`FR9`**: Retain locked parameter values against interactive, synchronous, and programmatic edits per model GUID. | Locked parameters return their prior value in `Before` interception on the active GUID without cross-model leak or post-disable retention. | **`DP9`**: `ParameterLockPlugin.beforeSetParameterValue` (`ParameterHookRegistry` `Before` adapter) and `ParameterLockState`. |
| 10 | `D8` Parameter editing | **`FR10`**: Propagate normalized parameter edits across synchronized parameter sets without oscillation feedback loops. | Unlocked driver edits update unlocked synchronized targets proportionally on the UI thread with zero secondary bounce. | **`DP10`**: `ParameterSyncPlugin` (`@SubscribeEvent ParameterValueEvent.On`), `ParameterSyncEngine`, `ProgrammaticSuppressionGuard`, and conflated `UiScheduler` flush. |
| 11 | `D8` Parameter editing | **`FR11`**: Import, export, transfer, and bind parameter values and animation keyforms in grouped authoring transactions. | Batch operations validate inputs up front and commit or roll back as atomic Undo units. | **`DP11`**: `:plugins:parameter`, `:plugins:parameter-batch-transfer`, `:plugins:frame-animation-import`, and `:plugins:physics-editor`. |
| 12 | `D9` Mesh editing | **`FR12`**: Register, switch, and clean up custom mesh-editing tools under single-active-tool session mutual exclusion. | Activating one tool revokes any prior `ActivationLease`; disabling or failing one tool leaves peer mesh tools operable. | **`DP12`**: `MeshToolCoordinator`, `RuntimeMeshToolRegistry`, and `:plugins:{selection-brush,geometric-mesh-layout,turbo-mesh}`. |
| 13 | `D9` Mesh editing | **`FR13`**: Edit ArtMesh and deformer geometry with mirror symmetry, row/column deform, and 3D import transactions. | Symmetric edits and 3D imports preserve geometry invariants and record reversible native Undo steps. | **`DP13`**: `MeshCapabilityBundle` bridges, `TemporaryDeformToolRegistry`, and mirror/warp/3D-import plugins. |
| 14 | `D10` Resources | **`FR14`**: Pack texture atlases, synchronize external PSD edits, back up projects, and export protected bundles safely. | Resource workflows preserve source integrity and quiesce background work on shutdown. | **`DP14`**: `TextureAtlasLayoutCoordinator`, `RuntimePsdReplaceService`, `ExportSettingsContributionService`, and atlas/PSD/backup/export plugins. |
| 15 | `D10` Automation | **`FR15`**: Execute external MCP, ACP, and out-of-process script commands through permission-gated SDK and transaction boundaries. | GraalVM runs outside the host JVM and external writes obey identical permission, transaction, and generation fences. | **`DP15`**: `:graal-host`, `McpConnectionService`, `ScriptService`, and `:plugins:{mcp,acp,command-palette}`. |
| 16 | `D11` Self-update | **`FR16`**: Stage, verify, and apply transactional framework self-updates and signed marketplace plugin changes across launch leases. | Manifest and catalog signatures verify before staging, launch leases serialize apply-on-restart, and failures roll back cleanly. | **`DP16`**: `FrameworkUpdateCoordinator`, `FrameworkUpdateLaunchLease`, `UpdateManifestVerifier`, `PluginCatalogVerifier`, `PendingPluginOperations`, and `TurboismAgent.handshakeBeforeStart`. |
| 17 | `D5` Responsiveness | **`FR17`**: Accelerate native GL render, matrix scratch, and warp-deform host paths with stateful generation-fenced bridges and kill switches. | Each bridge verifies exact host contracts, drains GL resources across generations, and disables cleanly via persisted kill switches. | **`DP17`**: `dev.turboism.adapter.cubism.optimization`, `GlDeleteQueue`, `WarpDeformEditBridge`, and `MatrixScratchBridge`. |

## 5. Investigated Interaction Surfaces

### 5.1 Parameter Lock (`DP9`) and Parameter Synchronization (`DP10`)

Code inspection and cross-plugin integration testing (`ParameterLockSyncInteractionTest`) verify the exact SDK write path:

1. **Disjoint lifecycle phases**: `ParameterLockPlugin` overrides `beforeSetParameterValue` (adapted to synchronous `ParameterValueEvent.Before` via `ParameterHookRegistry`), whereas `ParameterSyncPlugin` subscribes to `ParameterValueEvent.On` via `@SubscribeEvent` (`ParameterHookRegistry` skips legacy `onParameterValueChanged` adaptation when `@SubscribeEvent` is present, preventing duplicate delivery).
2. **SDK write path and readback suppression**: When an unlocked synchronized driver `A` changes, `ParameterLifecycleCoordinator` publishes `ParameterValueEvent.On(A)`. `ParameterSyncPlugin` computes normalized target values and schedules `flushPendingSync()` on `UiScheduler` (outside `A`'s synchronous `parameterWriteActive` guard). `flushPendingSync()` calls `targetParam.setValue(targetVal)` through `PermissionCheckedParameter`, which re-enters `ParameterLifecycleCoordinator.setValue`.
3. **Feedback-loop prevention**: When target `B` is locked, `ParameterLockPlugin.beforeSetParameterValue` returns `B.getValue()` (`oldValue`). In `EditorBackedCubismModelAccess.setParameterValue`, `Float.compare(oldValue, expectedValue) == 0` exits before mutating the model or creating Undo entries. Post-write readback observes `oldValue == finalValue`, so `ParameterLifecycleCoordinator.publishCompletion` suppresses `ParameterValueEvent.On(B)`. `B` never bounces a reverse sync back onto `A`, while unlocked partner `C` still updates.
4. **Verified matrix shape**: `DP9` affects `FR10` (locking a parameter blocks incoming sync writes and prevents edits on a locked driver from propagating), while `DP10` never violates `FR9`. The `(FR9, FR10) × (DP9, DP10)` submatrix is **DECOUPLED (Lower-Triangular)**: $\begin{bmatrix} X & 0 \\ x & X \end{bmatrix}$. Runtime enable/disable order (`lock -> sync` vs `sync -> lock`) is invariant because phase ordering (`Before -> invoke -> changed-only On`) is owned by `ParameterLifecycleCoordinator`.
5. **Observed edge quirks (`EXC-AD-002`)**:
   - Before `lockState.hasLoaded(modelGuid)` finishes async storage load, `ParameterLockPlugin.beforeSetParameterValue` fails closed by returning `parameter.getValue()` for *all* parameters on that model.
   - `ParameterSyncPlugin.flushPendingSync()` calls `suppressionGuard().markProgrammaticWrite(targetId, targetVal)` *before* `targetParam.setValue(targetVal)`. When `DP9` rejects that write, no `On` event consumes the guard entry, leaving a 500 ms suppression window on `targetId` (and an unread `lastAppliedValues` map entry).

### 5.2 Early Theme Bootstrap (`DP6`)

`EarlyThemeAppearanceBootstrap` consumes `config/dev.turboism.plugin.uitheme/ui-theme/selection.cfg` (`turboism-typed-config-v1`, schema v1, key `selected-theme`, sentinel `__native__`) and `data/dev.turboism.plugin.uitheme/themes/<id>.zip` before plugins load (`EXC-AD-001`).
Cubism caches its off-canvas GL background color (`SGFramework.h$d`) on first scene access, so FlatLaf custom defaults (`ThemeRuntimeProperties`) must be registered before plugin initialization.

**Evaluation of a runtime-owned contract**: Moving startup selection to a versioned runtime-owned document (for example `config/turboism.core/appearance/startup-theme-v1.cfg` written through `AppearanceService` and decoded by `:persistence-core`) would remove the runtime-to-plugin path coupling without changing host initialization timing.
However, changing the persisted path today without dual-read migration and release-derived golden snapshots (`#69`) risks dropping existing users' persisted themes on upgrade.
Accordingly, the closed exception stays bounded to `ui-theme` v1 and is now verified end-to-end across modules by `EarlyThemeBootstrapInteractionTest`.

### 5.3 Composition Root (`CorePluginContext`)

Inspection of `dev.turboism.kernel.context` (`CorePluginContext`, `CorePluginContextComposition`, `CubismContextServices`, `MeshCapabilityBundle`, `UiCapabilityBundle`, `HostCapabilityBundle`, and `CorePluginPermissionScopedServices`) confirms `EXC-AD-004`:

- `CorePluginContext` owns service assembly, permission-gate wrapping (`CubismPermissionGate`, `ServiceGateInstaller`), API version interception (`CubismEditorApiAvailabilityInterceptor`), and `DisposableScope` registration only.
- Domain policy (lock interception, sync planning, mesh session leases, transaction rollback, typed-config migration) resides in dedicated feature coordinators or plugins, never in `CorePluginContext`.
- High import fan-out in `dev.turboism.kernel` reflects its role as the composition root and does **not** justify splitting `:runtime` into additional Gradle modules.

### 5.4 Shared Host State (`DP3`, `DP8`, `DP12`)

Shared host-state coordination relies on four explicit runtime mechanisms verified in `SharedEditorStateInteractionTest` and `MeshToolCrossPluginLifecycleTest`:

1. **Authoring vs edit-session exclusion**: `EditorAuthoringTransactionCoordinator` shares an atomic `editScopeGate` with external edit sessions. Concurrent root transactions or active sessions fail closed with `REJECTED_SCOPE` (`authoring.edit-scope-conflict`) rather than interleaving Undo brackets.
2. **Reverse rollback compensation**: Callback or refresh failures inside `AuthoringTransaction` unwind admitted `EditorUndoContribution` entries in reverse order and verify `historyBefore.equals(after)` (`ROLLED_BACK` vs `RECOVERY_FAILED`).
3. **Document, model, and plugin generations**: `EditorAuthoringTransactionCoordinator.Binding` pins `(documentIdentity, documentGeneration, modelIdentity, modelGeneration, hostThread)`. `PluginGenerationGuard` fences disabled/timed-out plugin generations while still admitting zero-arity terminal releases (`close`, `cancel`, `dispose`, `release`, `unregister`) and `UiScheduler.runUiTeardown`.
4. **Peer mesh-tool isolation**: `MeshToolCoordinator` enforces a single active `ActivationLease` across all mesh plugins (`selection-brush`, `geometric-mesh-layout`, `turbo-mesh`). Switching tools, disabling one plugin, or throwing from `MeshTool.activate` cleans up only that tool's lease and toolbar descriptors (`UNCOUPLED` peer submatrix).

## 6. Matrix Evaluation, Unresolved Entries, and Required Sequencing

Under the documented ordering `(FR1..FR17) × (DP1..DP17)` in `docs/architecture/design-matrix.json`:

| Metric | Count | Classification Impact |
| --- | --- | --- |
| Primary diagonal entries (`X`, $i = j$) | 17 | Every `FR` has one primary traceable `DP` |
| Lower-triangular observed effects (`x`, $i > j$, `OBSERVED`) | 73 | Platform layers (`DP1`–`DP8`) and `DP9` (`parameter-lock`) feed downstream workflows one-way |
| Lower-triangular hypothesized effects (`x`, $i > j$, `HYPOTHESIS`) | 2 | `(FR11, DP10)` and `(FR15, DP10)` async sync propagation after batch/MCP `setValue` |
| Upper-triangular observed or hypothesized effects (`x`, $i < j$) | 0 | Zero confirmed reverse functional coupling |
| Unknown entries (`?`, `UNVERIFIED` / `HYPOTHESIS`) | 4 | `1` lower-triangular `(FR13, DP12)` + `3` upper-triangular `(FR5, DP7)`, `(FR5, DP9)`, `(FR10, DP15)` |
| Observed functional-effect SCCs | 0 | Acyclic lower-triangular functional graph over all confirmed effects |
| Potential functional SCCs if `?` entries prove nonzero | 2 | `["FR5/DP5", "FR6/DP6", "FR7/DP7", "FR8/DP8", "FR9/DP9"]` and `["FR10/DP10", "FR15/DP15"]` |

Sibling remediation branches (`theme-sec`, `param-lock`, `sync-decouple`, `exec-hygiene`) target `EXC-AD-001`, `EXC-AD-002`, `EXC-AD-003`, `(FR5, DP9)`, and `(FR10, DP15)`. After those branches merge, `@coordinator` can transition verified cells to `OBSERVED` or `0` in `design-matrix.json`.

Design classifications:

- **Peer mesh tools submatrix (`mesh-tool-peers`)**: **UNCOUPLED** (diagonal).
- **Parameter lock/sync (`parameter-lock-sync`) and early theme (`early-theme-bootstrap`) submatrices**: **DECOUPLED** (lower-triangular).
- **System matrix over observed entries**: **DECOUPLED** (lower-triangular; zero observed upper-triangular effects and zero observed functional SCCs).
- **Whole-system matrix including unknown (`?`) entries**: **UNVERIFIED** (provisionally decoupled pending the four recorded host-performance and async-MCP verification tasks; static module acyclicity is not used to claim full independence).

### Required Sequencing for Triangular Designs

Because a lower-triangular (`DECOUPLED`) system satisfies independence only when `DP`s are adjusted in forward order, runtime initialization and user workflows must preserve four sequencing rules:

1. **Platform before plugins (`DP1 -> DP2 -> DP4/DP5 -> DP3`)**: Complete hook verification, exact-version capability resolution, persistence store setup, and scheduler initialization before activating plugin generations.
2. **Early theme before UI surfaces (`DP6 -> DP7`)**: Run `EarlyThemeAppearanceBootstrap` before GL scene initialization and before plugin/shell UI surfaces render.
3. **Locks before sync, and sync-pause before batch sweeps (`DP8 -> DP9 -> DP10 -> DP11/DP15`)**: Configure parameter locks (`DP9`) before relying on `parameter-sync` (`DP10`), and pause `parameter-sync` (`parameter-sync.toggle-global`) before executing batch CSV imports, animation keyform binds, or MCP pose-grid sweeps.
4. **Serialized edit and tool leases (`DP8 -> DP12 / DP13 / DP14`)**: Acquire at most one `editScopeGate` transaction/session or `MeshToolCoordinator` `ActivationLease` at a time, and exit active custom mesh-tool sessions before launching competing host-wide edits.
5. **Self-update staging and optimization bridges (`DP1..DP8 -> DP16 / DP17`)**: Stage signed updates behind launch leases for next-session application (`DP16`), and gate stateful render or warp bridges behind verified contracts and kill switches (`DP17`).

## 7. Process Domain (`DP × PV`) and Axiom 2 Information Content Hotspots

Turboism models Suh's Process Domain `[B]` (`DP × PV`) in `docs/architecture/design-matrix.json` using four Process Variables (`PV1`–`PV4`):

| PV | Process Variable | Primary Artifacts | Governed `DP`s |
| --- | --- | --- | --- |
| **`PV1`** | Gradle module and gate evaluation | `build.gradle.kts`, `gradle/gates.txt` | All `DP1`–`DP17` (`X` for compiled/gated modules) |
| **`PV2`** | Contract and slice materialization | `scripts/materialize_verification_records.py`, `scripts/materialize_draft_contracts.py` | `DP1`, `DP2` (`X`), `DP5`, `DP7`, `DP8`, `DP12`, `DP13`, `DP14`, `DP17` (`X`) |
| **`PV3`** | Multi-channel installer and config packaging | `packaging/java-installer/.../ConfigMerge.java`, `packaging/windows-installer/configure_turboism.ps1` | `DP1`, `DP4`, `DP16` (`X`) |
| **`PV4`** | Version-isolated host validation queue | `scripts/preview/host_validation.py` | `DP2`, `DP5`, `DP6`, `DP8`, `DP12`, `DP14`, `DP15`, `DP17` |

### Quantitative Suh Independence Metrics (`--report`)

`scripts/dev/axiomatic_design_matrix.py --report` computes off-diagonal density, Reangularity ($R$), and Semangularity ($S$) at reference sensitivity $\alpha = 0.25$.
Use these metrics to track coupling drift as new `FR`/`DP` pairs enter the matrix without altering `--check` exit semantics.

### Axiom 2 Information Content Hotspots

Three operational hotspots dominate implementation entropy (Axiom 2 Information Content) across `[A]` and `[B]`:

1. **Multi-writer `config.json` entropy (`PV3` × `DP4`/`DP16`)**: Java installers, Windows scripts, and runtime services all mutate launcher and install metadata. Consolidate schema ownership and atomic merge rules to prevent drift across upgrade channels.
2. **Constructor and adapter concentration (`DP3`/`DP17`)**: `CorePluginContext` aggregates 31+ service slots and 19 constructors, while `dev.turboism.adapter.cubism.optimization` concentrates stateful host bridges. Keep policy out of wiring constructors and isolate each bridge behind `ReviewedHostContract` kill switches.
3. **Configuration Cache coupling on `PV1`**: `build.gradle.kts` and `gradle/gates.txt` evaluate process-wide gates across all 50 Gradle nodes. Isolate task inputs and avoid configuration-time I/O so `PV1` remains cacheable and deterministic.

## 8. Recorded Exceptions and Follow-Up Items

| ID | Status | Owner | Summary and Follow-Up |
| --- | --- | --- | --- |
| `EXC-AD-001` | Accepted closed exception | `sole-maintainer` | `EarlyThemeAppearanceBootstrap` reads `ui-theme/selection.cfg` (schema v1) before plugin load; revisit runtime-owned storage alongside `#69` migration snapshots. |
| `EXC-AD-002` | Tracked interaction quirk | `sole-maintainer` | `ParameterLockPlugin` holds values fail-closed until async preload finishes, and rejected `ParameterSyncPlugin` writes leave a 500 ms `ProgrammaticSuppressionGuard` entry; track readback cleanup in `parameter-sync`. |
| `EXC-AD-003` | Tracked interaction quirk | `sole-maintainer` | `ParameterSyncPlugin` flushes partner writes asynchronously via `UiScheduler` after synchronous `McpPoseGridDomain` or batch `AuthoringTransaction` sweeps; execute verification tasks on `(FR10, DP15)`, `(FR11, DP10)`, and `(FR15, DP10)`. |
| `EXC-AD-004` | Boundary verified | `sole-maintainer` | `CorePluginContext` exhibits high static import fan-out as the service-wiring seam while owning zero domain policy; no `:runtime` module split is warranted. |

## 9. Governance and Maintenance

`checkAxiomaticDesignMatrix` runs in `devCheck` via `scripts/dev/axiomatic_design_matrix.py --check`:

```bash
python3 scripts/dev/axiomatic_design_matrix.py --report
python3 scripts/dev/axiomatic_design_matrix.py --check
```

The gate validates `docs/architecture/design-matrix.json` against `docs/architecture/design-matrix.schema.json`, verifies every referenced `FR`, `DP`, `PV`, and evidence path exists, enforces `verificationTask` rules on nonzero and unknown cells, cross-checks `structuralBaseline` against `docs/architecture/dependency-inventory.json`, validates `processMatrix` referential integrity, and recomputes triangular counts and Tarjan functional SCCs.
