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

## Next boundaries (not yet extracted)

Contract `core.runtime` ↔ `runtime.log` (5-cycle), then
`adapter.cubism.{edit,editor,history,transaction}` (4-cycle). Both need a
contract before any file moves; see `architecture/DEPENDENCY_INVENTORY.md` for the
current cycle list.
