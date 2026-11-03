# Module contracts

A contract is written **before** a boundary is enforced or a package is moved. It
answers who owns the code, what it may depend on, what it promises, and how a
regression is caught. Contracts for `:runtime` internals are recorded here; SDK
surface contracts stay in `sdk/api-contracts/` and the SDK ABI gates.

## Template

```markdown
### <boundary name>

- **Owner**: who reviews changes across this boundary.
- **Purpose**: one sentence; what the code is responsible for and is not.
- **Allowed dependencies**: compile, runtime and test edges, by package/module.
- **Forbidden**: directions or imports that must fail review.
- **Public API**: the types other packages may use; everything else is internal.
- **Threading / IO**: which thread owns the state, what blocks, what is async.
- **Tests**: where the behaviour is pinned, including failure paths.
- **Backward compatibility**: on-disk format, plugin lifecycle or ABI obligations.
- **Enforcement**: the gate that fails when the contract is violated.
```

## `:runtime` preview ↔ preview report

- **Owner**: preview runtime maintainers (see `.github/CODEOWNERS`).
- **Purpose**: `dev.turboism.preview.report` renders neutral documents
  (capability/warning/verification/config reports) from evidence it is given. It
  does not read runtime state, plugin state or the host.
- **Allowed dependencies**: `report` may depend on `adapter.host`, `failure`,
  `i18n`, `mapping.verification` and the SDK. `preview` may depend on `report`.
- **Forbidden**: `report` importing anything from `dev.turboism.preview`
  (including nested `LocalPluginRuntime` types), and any `report` type reading
  files or runtime state on its own initiative.
- **Public API**: `PreviewReportSnapshotFactory`, `PreviewReportType`,
  `PreviewReportDocuments`, `PreviewReportWriter`, the validators. Consumers pass
  runtime evidence in.
- **Threading / IO**: documents are built on the calling thread; `report` writes
  only through `PreviewReportWriter` and never schedules work.
- **Tests**: `runtime/src/test/java/dev/turboism/preview/report/*` for the
  document contract; `PreviewCapabilityCatalogRegistryTest` pins the canonical
  capability closure (69 entries) next to the factory it validates.
- **Backward compatibility**: report documents are a closed set consumed by the
  preview agent; adding a document type is a reviewed change.
- **Enforcement**: `checkDependencyInventory` (no cycle) plus the runtime package
  rules in `gradle/module-boundaries.gradle.kts`.

**What changed (pilot, #70 step 3a)**: `PreviewReportSnapshotFactory` read
`LocalPluginRuntime` state directly, which made `report` depend on `preview`.
The factory is the bridge between runtime evidence and report documents, so it
moved to `dev.turboism.preview`; `PreviewReportDocuments` gained a public
`json()` accessor so the factory keeps the shared deterministic mapper instead of
the package-private field. `PreviewCapabilityCatalogRegistryTest` moved with it,
because it calls the factory's package-private `canonicalCapabilityIds()`.

## `:runtime` adapter ↔ adapter host

- **Owner**: host adapter maintainers (see `.github/CODEOWNERS`).
- **Purpose**: `dev.turboism.adapter` holds the host adapter SPI and the
  composition types (`RuntimeHostAdapters`, `HostVerificationEvidence`).
  `dev.turboism.adapter.host` implements the SPI against a verified host session.
- **Allowed dependencies**: `adapter.host` may depend on `adapter`;
  `adapter` must not depend on `adapter.host`.
- **Forbidden**: any `dev.turboism.adapter` type importing a `adapter.host` type,
  and any adapter type reaching into `shell`, `preview`, `distribution` or
  `bootstrap` (already enforced for the whole `adapter` layer).
- **Public API**: `RuntimeHostAdapters`, `HostVerificationEvidence` (+ nested
  `Slice`), `VerifiedRuntimeHostAdaptersFactory`, `HostSession`.
- **Threading / IO**: adapters are created on the host thread and are not
  thread-safe; verification evidence is immutable and safe to hand across threads.
- **Tests**: `runtime/src/test/java/dev/turboism/adapter/**` — the factory tests
  keep exercising the composition helpers, which stay package-private.
- **Backward compatibility**: the SPI is consumed by first-party plugins through
  `core-contract`; moving or renaming types here is a reviewed change and must
  keep the compiled-class gates green.
- **Enforcement**: `checkDependencyInventory`, the `adapter` rules in
  `gradle/module-boundaries.gradle.kts`, and the ASM dependency model.

**What changed (pilot, #70 step 3a)**: `VerifiedRuntimeHostAdaptersFactory` (in
`adapter`) consumed `HostVerificationEvidence` from `adapter.host`, closing the
cycle. The evidence record is shared input between the two packages, so it moved
to `dev.turboism.adapter` — the lower package — and `adapter.host` now imports it
downward. The factory stayed where it is: it composes package-private
`RuntimeHostAdapters` helpers, and widening those to `public` would have been a
larger surface change than moving one data record.

## `:runtime` runtime.log ↔ core

- **Owner**: runtime infrastructure maintainers (see `.github/CODEOWNERS`).
- **Purpose**: `dev.turboism.runtime.log` is infrastructure: diagnostics counters
  and, before this change, the Cubism log-stream host. Infrastructure sits **below**
  the framework core and never depends on it.
- **Allowed dependencies**: `core.*` may depend on `runtime.log`;
  `runtime.log` may depend only on the JDK, `core.reflect` and the SDK.
- **Forbidden**: any import in `dev.turboism.runtime.log` of
  `dev.turboism.core.*` (except `core.reflect`), and any plugin-facing log
  adapter living here.
- **Public API**: `RuntimeDiagnostics`. The Cubism log service is an adapter, not
  infrastructure.
- **Threading / IO**: `RuntimeDiagnostics` is lock-free and safe from any thread;
  it never writes files.
- **Tests**: `runtime/src/test/java/dev/turboism/runtime/log/**` for the counters;
  the adapter's tests live with the adapter.
- **Backward compatibility**: none on disk; the class name was internal.
- **Enforcement**: `checkDependencyInventory` plus the layering rules in
  `gradle/module-boundaries.gradle.kts`.

**What changed (`#70` step 3b)**: `CubismLogServiceHost` — a Cubism host adapter
that reflects into the Log4j2 context and needs the event broker, the scheduler
and the work executor — lived under `runtime.log` and imported four `core.*`
packages. It moved to `dev.turboism.adapter.cubism.service.log`, matching
`CubismClipMaskServiceImpl` and the other `adapter.cubism.service.*` adapters.
`runtime.log` now only exposes `RuntimeDiagnostics` and imports nothing upward.

## `:runtime` scheduler ↔ executors (core.runtime.contract)

- **Owner**: runtime infrastructure maintainers (see `.github/CODEOWNERS`).
- **Purpose**: `dev.turboism.core.runtime.contract` holds the types shared by the
  scheduler and the executor implementations: `PluginTask` (one submitted task)
  and `RuntimeCancellationToken` (the cancellation handle a task runs under). It
  depends on nothing above it — only the JDK and the SDK cancellation API.
- **Allowed dependencies**: `core.runtime` may depend on `contract` and on the
  executor packages (`core.runtime.work`, `core.runtime.sidecar`); the executors
  may depend on `contract`; `contract` depends on neither.
- **Forbidden**: imports from `contract` into `core.runtime`, `work` or
  `sidecar`; and any executor importing `core.runtime` directly.
- **Public API**: `PluginTask`, `RuntimeCancellationToken`.
- **Threading / IO**: `PluginTask` is an immutable record; the token is
  thread-safe and cancellation is observable from any thread.
- **Tests**: `runtime/src/test/java/dev/turboism/core/runtime/**` covers the
  budget policy and cancellation semantics; the executors' tests stay with their
  packages.
- **Backward compatibility**: none on disk; `PluginTask` is internal to
  `:runtime` and reaches plugins only through `core-contract` types.
- **Enforcement**: `checkDependencyInventory` plus the `core` rules in
  `gradle/module-boundaries.gradle.kts`.

**What changed (`#70` step 3c)**: `RuntimeScheduler` composes the executor set
while the executors consumed `PluginTask` and `RuntimeCancellationToken` from
`core.runtime`, closing a 3-package cycle. Both types moved into the new
`dev.turboism.core.runtime.contract` package below both sides — 67 files
reference `PluginTask`, so this was an ast-grep codemod over imports, not a
hand-edited move. No production logic changed.

## `:runtime` hostread ↔ adapter.cubism

- **Owner**: host-read and Cubism adapter maintainers (see `.github/CODEOWNERS`).
- **Purpose**: `dev.turboism.hostread` is the **port** side of async host reads:
  request/result types, the async service and the executor lane. It never knows
  which adapter answers. `dev.turboism.adapter.cubism` implements the port.
- **Allowed dependencies**: `adapter.cubism` (and every adapter) may depend on
  `hostread`; `hostread` depends only on the SDK, `permissions`, `failure` and
  `adapter.ui.SafeModeDiagnostic`-free primitives.
- **Forbidden**: any `dev.turboism.hostread` import of `dev.turboism.adapter.*`.
- **Public API**: `ProjectWorkspaceHostReadSource`, `ProjectWorkspaceHostReadResult`,
  `RuntimeAsyncHostReadService`; the adapter-side bridge is
  `ProjectWorkspaceReadSources.from(ProjectWorkspaceAdapter)`.
- **Threading / IO**: reads run on the shared host-read lane, never on the caller
  thread; results are immutable.
- **Tests**: `runtime/src/test/java/dev/turboism/hostread/**` for the service and
  lane; `ProjectWorkspaceReadSourcesTest` for the adapter bridge.
- **Backward compatibility**: SDK-facing types live under `sdk.hostread`; the
  runtime port is internal.
- **Enforcement**: `checkDependencyInventory` plus the module-boundary rules.

**What changed (`#70` step 3d)**: `ProjectWorkspaceHostReadSource.from(adapter)`
lived in the port package and imported `ProjectWorkspaceAdapter` and
`SafeModeDiagnostic`, so the port depended on an implementation. The bridge moved
to `adapter.cubism.ProjectWorkspaceReadSources` and its test moved with it.
`CubismReadPermissionGate` moved from `adapter.cubism.service.read` up into
`adapter.cubism`, because the facade used it and the read service implements it —
the remaining direction (composed service → adapter primitives) is the valid one.
`hostread` now imports no adapter package at all.

## `:runtime` editor / edit / editor.history / editor.transaction

- **Owner**: editor adapter maintainers (see `.github/CODEOWNERS`).
- **Purpose**: `adapter.cubism.editor` wraps the Editor-backed accessors,
  `adapter.cubism.edit` owns edit sessions, `adapter.cubism.editor.transaction`
  owns authoring transactions and `adapter.cubism.editor.history` captures undo
  history. `adapter.cubism.editor.host` holds the host-thread primitives all of
  them run on.
- **Layering (low to high)**: `editor.host` → `editor.transaction` →
  `editor.history` → `edit` → `editor`.
- **Allowed dependencies**: `edit` and `editor` may depend on `history` and
  `transaction`; `history` may depend on `transaction`; everything may depend on
  `editor.host`. `transaction` depends on none of the others' implementations.
- **Forbidden**: `transaction` importing `editor`, `edit` or `editor.history`;
  `editor.host` importing anything above it (it depends only on `ui.host` and Swing).
- **Public API**: `EditorHostThread` (host-thread primitives),
  `EditorAuthoringTransactionCoordinator`, `VerifiedEditorAuthoringTransactionHost`,
  `AuthoringHistoryPort` (what a transaction needs from the recorder),
  `EditorHistoryPort` (the recorder side of that port).
- **Threading / IO**: every authoring write asserts it runs on the host thread
  through `EditorHostThread`; the history port is called on that same thread.
- **Tests**: `runtime/src/test/java/dev/turboism/adapter/cubism/{edit,editor}/**`.
- **Backward compatibility**: internal to `:runtime`; plugins never see these
  types.
- **Enforcement**: `checkDependencyInventory` plus the `adapter` rules in
  `gradle/module-boundaries.gradle.kts`.

**What changed (`#70` step 3e)**: `EditorHostThread` moved from
`adapter.cubism.editor` into the new `adapter.cubism.editor.host` package, so the
transaction package stopped depending on the editor package for it. The history
coupling was inverted instead of moved: `VerifiedEditorAuthoringTransactionHost`
now takes an `AuthoringHistoryPort` and no longer imports
`EditorHistorySnapshotProvider` or `EditorHistoryMetadataRegistry`;
`EditorHistoryPort` in the history package implements that port and is supplied
by the two editor call sites. Leaf-package cycles went from 2 to 1.

## `:runtime` UI surfaces and selector contracts

- **Owner**: UI surface and host-verification maintainers (see `.github/CODEOWNERS`).
- **Purpose**: `ui.host` holds toolkit-level host primitives (EDT dispatch, families,
  snapshots, palette host layout); `ui.contribution` holds the descriptor/authority
  vocabulary every surface shares; `ui.filter`, `ui.mesh`, `ui.palette` and
  `ui.toolbar` are surfaces. `mapping.verification` owns host-artifact verification,
  and `mapping.selector` owns the per-slice selector contracts (ids,
  aliases, supported versions) as a pure leaf.
- **Allowed dependencies**: surfaces may depend on `ui.host`, `ui.contribution` and
  on adapter primitives; adapter packages may depend on `ui.contribution` and
  `mapping.verification*`; `mapping.selector` depends on the SDK and
  `internal.ReviewedVersions` only.
- **Forbidden**: `mapping.selector` importing anything above it
  (notably `VerifiedMemberResolver` — that is why the admission checks live in
  `mapping.verification.SelectorAdmission`); `ui.palette` being reachable from
  `ui.filter`/`ui.toolbar` for a shared record; `adapter.*` importing `ui.mesh`.
- **Public API**: `LogPaletteHostStructure`, `MeshToolbarContributionDescriptor`,
  `MeshToolbarSliderContributionDescriptor`, `SelectorAdmission`, and the selector
  contracts themselves.
- **Threading / IO**: `LogPaletteHostStructure` builds Swing components and is
  host-thread only; the selector contracts are immutable data.
- **Tests**: `runtime/src/test/java/dev/turboism/ui/**`,
  `runtime/src/test/java/dev/turboism/mapping/**`.
- **Backward compatibility**: selector contracts feed the compatibility matrix
  (`compatibility/cubism/compatibility-matrix.json`) and the generated
  verification manifest, both regenerated in the same commit.
- **Enforcement**: `checkDependencyInventory`, the `mapping`/`adapter`/`ui` rules in
  `gradle/module-boundaries.gradle.kts` (waiver map is now empty), and the
  compatibility-matrix freshness gate.

**What changed (`#70` step 3f)**: the 8-package cluster was broken by moving shared
vocabulary down instead of inventing new seams — `LogPaletteHostStructure` to
`ui.host`, the two mesh toolbar descriptors to `ui.contribution`, and the mesh/
modeling selector contracts to `mapping.selector`, deleting the last
module-boundary waiver. The contracts' resolver-dependent `authorizes(...)` helpers
became `SelectorAdmission`, because leaving them in the contract classes was the one
edge that made the selector package depend on the resolver. Leaf-package cycles:
1 → **0**.

## Next boundaries (not yet extracted)

None at package granularity. The next step in #70's sequence is not another move but
the extraction itself (step 3): pull the pure persistence codec/migration kernel out
of `:runtime` behind the contracts already recorded here, then the compatibility
policy, each as its own reviewable PR with module contracts and the existing
bytecode/ABI gates ratcheting the direction.
