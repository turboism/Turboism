# Phase 4: reviewed version selections and unified gates

Baseline: `28be80a0e`; branch: `refactor/version-table-phase-4`. No host validation,
push, main merge, private assets, or changes to mapping-packs/profiles/core-api.

## Converted sites

Batch 1 replaced 309 Set expressions in 29 files (52 former registry groups,
including one adjacent evidence group). `ReviewedVersions.since`, `line`, and
`between` select only canonical table members. The SDK utility is generated
alongside the runtime release table, so SDK commands can use it without a
runtime dependency. It describes release metadata, rather than host operations,
and lives in `dev.turboism.sdk`.

- `bootstrap/src/main/java/dev/turboism/bootstrap/VerifiedControlAppearanceHookInstaller.java`
- `bootstrap/src/main/java/dev/turboism/bootstrap/VerifiedProtectedExportHookInstaller.java`
- `runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorModelStatisticsAccess.java`
- `runtime/src/main/java/dev/turboism/adapter/cubism/editor/history/VerifiedNativeEditBeginHookInstaller.java`
- `runtime/src/main/java/dev/turboism/adapter/cubism/integration/NativeEditToggleInjector.java`
- `runtime/src/main/java/dev/turboism/adapter/cubism/integration/VerifiedEditApiDispatchInstaller.java`
- `runtime/src/main/java/dev/turboism/adapter/cubism/integration/VerifiedEditToggleHookInstaller.java`
- `runtime/src/main/java/dev/turboism/adapter/cubism/mesh/MeshMirrorHostProfile.java`
- `runtime/src/main/java/dev/turboism/adapter/cubism/mesh/MeshToolSessionSelectorContract.java`
- `runtime/src/main/java/dev/turboism/adapter/cubism/modeling/ModelingSelectionSelectorContract.java`
- `runtime/src/main/java/dev/turboism/adapter/cubism/warpalt/WarpAltMirrorHostProfile.java`
- `runtime/src/main/java/dev/turboism/exportsettings/VerifiedProtectedExportHostOperations.java`
- `runtime/src/main/java/dev/turboism/ui/filter/DeformerNodeSourceProfile.java`
- `runtime/src/main/java/dev/turboism/ui/mesh/MeshToolbarSelectorContract.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/ClipMaskVerificationManifest.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/ProjectWorkspaceVerificationManifest.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/StartupSuppressionVerificationManifest.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/StatusBarVerificationManifest.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/selector/EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/selector/EditorMocExportSelectorContract.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/selector/EditorParameterInterpolationSelectorContract.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/selector/EditorRawImagePsdReplaceSelectorContract.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/selector/EditorRawImagePsdSelectorContract.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/selector/EditorRenderPixelsSelectorContract.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/selector/EditorTextureRelationsSelectorContract.java`
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/selector/EditorTextureSelectorContract.java`
- `sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorCommand.java`
- `sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorFileCommand.java`
- `sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorParameterizedCommand.java`

Batch 2 collapsed six file-level/repeated-value surfaces:

- `StartupSuppressionProfile`: 20 shape rows become four range groups.
- `OffCanvasBackgroundHostAccess`: 20 version-to-owner cases become six range groups; unique last-patch owners remain exact ranges.
- `VerifiedTextureAtlasSelectorContract`: 20 profile rows become two ranges with the same profile factory arguments.
- `CanvasCompositeElisionTarget`: exact digest lookup plus the existing disjoint admitted ranges; null still returns empty.
- `CubismNativeIconCatalog`: exact canonical artifact lookup restricted to 5.2.00+.
- `ExportSettingsHostProfile.supportedHostVersions`: canonical table order filtered by the existing 5.1.00+ range.

Follow-up batch also replaces MCP's three ordered version Lists and Glue's
nullable schema enum, preserving list order and the final null, plus the
model-instance contract's evidence-backed 5.0.00–5.2.03 absence Set. These five
expressions were compared independently (`/tmp/vt4-equivalence-residual.json`).

`CoreVersionExpectation` already read the generated table on the baseline.

## Retained sites and evidence

The remaining 46 former registry groups are represented by 42 file exceptions,
not another version roster. Java exceptions contain paths and evidence; they
cannot permit bare version Sets. An exceptional bare collection would need its
own exact evidence-backed collection entry; none is present. The two shell
validation lanes retain their exact version selection for audit.

Exact per-artifact bytecode hashes, distinct selector/owner/method tuples,
mapping provenance and generated manifest pins remain explicit: inferring them
from a range would either hide distinct observed facts or require a new mapping
schema without reducing the underlying observations. Profile object Lists retain
their values and order. Evidence-bound validation lanes stay pinned; widening
those lanes is outside this refactor.

- `runtime/src/main/java/dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgePreparation.java`: Literal version table derived from verification records; the set names the reviewed versions this surface admits.
- `runtime/src/main/java/dev/turboism/adapter/cubism/optimization/glerror/GlGetErrorElisionTarget.java`: Optimization target pin table derived from verification records; the transform is admitted on exactly these reviewed versions.
- `runtime/src/main/java/dev/turboism/adapter/cubism/optimization/modelupdate/ModelUpdateSkipTarget.java`: Optimization target pin table derived from verification records; the transform is admitted on exactly these reviewed versions.
- `runtime/src/main/java/dev/turboism/adapter/cubism/optimization/modelupdate/incremental/IncrementalUpdateTarget.java`: Optimization target pin table derived from verification records; the transform is admitted on exactly these reviewed versions.
- `runtime/src/main/java/dev/turboism/adapter/cubism/optimization/uploadelision/SkippedFrameUploadElisionTarget.java`: Optimization target pin table derived from verification records; the transform is admitted on exactly these reviewed versions.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/selector/EditorModelInstanceReadSelectorContract.java`: RenderType.ONION_SKIN_FOR_MODELING enum member is absent on these exact jars (verified via javap on every 5.0.x/5.1.x/5.2.x reference); the member first appears on 5.3.00.
- `bootstrap/src/main/java/dev/turboism/bootstrap/ObjectContextMenuHostProfile.java`: Host profile version table derived from verification records; profiles bind reviewed versions to their verified shape.
- `bootstrap/src/main/java/dev/turboism/bootstrap/ParameterPointContextMenuHostProfile.java`: Host profile version table derived from verification records; profiles bind reviewed versions to their verified shape.
- `bootstrap/src/main/java/dev/turboism/bootstrap/PerformanceFpsHookInstaller.java`: Named arms are fast paths; the fallback admits every reviewed version (see monotonicity exception).
- `bootstrap/src/main/java/dev/turboism/bootstrap/VerifiedPerformanceProbeInstaller.java`: Literal version table derived from verification records; the set names the reviewed versions this surface admits.
- `runtime/src/main/java/dev/turboism/adapter/cubism/core/CorePublicApiProviderFactory.java`: Identity switch that passes every admitted version through; the arms exist only for profile-id divergence.
- `runtime/src/main/java/dev/turboism/adapter/cubism/editor/EditorTextureReplacementDiagnostic.java`: Property-gated diagnostic authored and reviewed against the exact 5.3.02 host; a tool, not an admission set.
- `runtime/src/main/java/dev/turboism/adapter/cubism/filechooser/FileChooserHistoryHostProfile.java`: Task-scoped host profile pin for the exact-host file-chooser validation lane.
- `runtime/src/main/java/dev/turboism/adapter/cubism/optimization/uniform/UniformLocationLifecycleTransformer.java`: Optimization target pin table derived from verification records; the transform is admitted on exactly these reviewed versions.
- `runtime/src/main/java/dev/turboism/adapter/cubism/textureatlas/cache/AtlasCacheReuseTarget.java`: Optimization target pin table derived from verification records; the transform is admitted on exactly these reviewed versions.
- `runtime/src/main/java/dev/turboism/adapter/cubism/textureatlas/image/AtlasTileBboxTarget.java`: Optimization target pin table derived from verification records; the transform is admitted on exactly these reviewed versions.
- `runtime/src/main/java/dev/turboism/adapter/cubism/warpalt/WarpAltMirrorHostProfile.java`: The reviewed 5.3.04 JAR moves the canvas toolbar from context/a/b to context/b/b; the former owner is now a TimerTask. WarpAltMirrorReviewedArtifactsTest checks the exact mount/layout selectors and button surfaces against every reviewed JAR, while the complete 5.3.x admission set remains unchanged.
- `runtime/src/main/java/dev/turboism/exportsettings/ExportSettingsHostProfile.java`: Host profile version table derived from verification records; profiles bind reviewed versions to their verified shape.
- `runtime/src/main/java/dev/turboism/exportsettings/ProtectedExportChooserProfile.java`: Host profile version table derived from verification records; profiles bind reviewed versions to their verified shape.
- `runtime/src/main/java/dev/turboism/mapping/verification/EditorModelRuntimeScope.java`: Selects the optional 5.3.03 validation-candidate runtime scope; not an admission set (see monotonicity exception).
- `runtime/src/main/java/dev/turboism/ui/workspace/WorkspaceControlAdmission.java`: Slice-id dispatch keys; authorizes() admits every reviewed version (see monotonicity exception).
- `scripts/preview/run-atlas-image-shadow-host-validation.sh`: Blocked T040/T029 shadow scene wrapper pins exact obfuscated class/shape/patched bytecode SHA-256 digests for 5.2.03, 5.3.02, and 5.3.03 (verified by test_atlas_image_shadow_host_validation.sh).
- `scripts/preview/run-atlas-nesting-host-validation.sh`: UI lifecycle timing is pinned to the observed 5.3.03 progress window (see validation/atlas-nesting-host-probe/REPORT.md). Other versions run functional validation without UI timing; admission versions remain derived from the plugin compatibility matrix.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/AutoBackupVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/BoundingBoxOverlayButtonVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/ClipMaskVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/ControlAppearanceVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/EditorModelVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/EmbeddedPanelVerificationManifest.java`: ui-embedded-panel records exist for all twenty reviewed generations after 5.0.x propagation; the manifest lists exactly the emitted record set
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/FileChooserHistoryVerificationManifest.java`: Task-scoped runtime admission for the exact-host 5.3.03 validation lane.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/MainToolbarVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/NativeControlAppearancePersistenceVerificationManifest.java`: Task-scoped runtime admission for the exact-host 5.3.03 validation lane.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/ParameterLifecycleVerificationManifest.java`: Task-scoped runtime admission for the exact-host 5.3.03 validation lane.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/PerformanceFpsVerificationManifest.java`: Task-scoped runtime admission for the exact-host 5.3.03 validation lane.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/ProjectLifecycleVerificationManifest.java`: Task-scoped runtime admission for the exact-host 5.3.03 validation lane.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/ProjectWorkspaceVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/ProtectedExportVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/StartupSuppressionVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/StatusBarVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/TextureAtlasVerificationManifest.java`: Task-scoped runtime admission for the exact-host 5.3.03 validation lane.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/TopMenuVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.
- `scripts/verification-sources/templates/dev/turboism/mapping/verification/WorkspaceControlVerificationManifest.java`: Verification manifest record-version table derived from the verification records it admits.

## Equivalence and validation

- `/tmp/vt4_verify_equivalence.py` re-expands every converted Set over all 20
  canonical releases: **309 × 20 = 6,180 equal membership decisions**. Inputs and
  per-expression results are in `/tmp/vt4-equivalence-batch1.json`.
- The batch-2 one-off comparisons check all 20 Startup shape and OffCanvas owner
  values, both texture profile argument groups, exact artifact admissions and
  the ordered export list. Results: `/tmp/vt4-equivalence-batch2.json`.
- Correctly filtered runtime/bootstrap installer, admission and profile tests:
  `/tmp/vt4-batch2.log`, **BUILD SUCCESSFUL**.
- Final MCP Glue/History/Texture and isolated facade/EDT tests: `/tmp/vt4-followup.log`, **BUILD SUCCESSFUL**.
- Unified SelfTest: 67 cases, including all meaningful old negative fixtures,
  malformed trust roots, stale/evidenceless/count-mismatched exceptions,
  annotation and JSON holes, record regressions/tampering/missing pins,
  task drift, wrapper enumeration and range consumer equivalence.
- Unified SelfTest plus scheduler callers: 103 cases, all pass.
- Checkpoint `cd7be9726`: all five legacy checker CLIs and the new CLI passed
  on the same tree (`/tmp/vt4-parallel-*.log`). Legacy deletion is a separate
  revertable commit, `3d88f78d9`.
- Compatibility matrix: capability, command and plugin admissions are unchanged.
  The only derived metadata change is recognition of MCP's full-version history
  gate, previously missed by the literal-only parser. Its JSON and four generated
  developer documents remain committed.

## Old → new rule mapping

| Previous gate | Unified rule | Preserved behavior |
| --- | --- | --- |
| completeness | R1 | Generated trust roots checked byte-for-byte; consumer derivation, catalog, profile fields/coverage, prose, close routes, task/pin subset and pin SHA syntax |
| verification record index | R1 | Materialized filename/byte equality, unique/version-consistent record IDs, nonvacuous manifest pins and placeholder resolution |
| monotonicity | R2 | Family capability/alias closure from canonical source expansion; class pins, tasks, MCP, Java annotations/literals and reviewed range selections |
| registry | R3 | Bare version collections rejected; residual value tables need evidence; stale, duplicate and count-drift exceptions still rejected |
| host validation versions | R4 | Task versions equal capability/pin/plugin intersections; wrapper enumerations require exact evidence |

R4 reads reviewed releases and VERIFIED_STATIC family capability expansion
rather than matrix version lists. It reads class-pin keys directly. The retained
matrix supplies group/plugin/alias metadata. Task manifest versions remain
materialized, checked declarations to preserve the scheduler's JSON schema.
`python3 scripts/check_reviewed_releases.py --update-host-tasks` replaces the
retired host checker's `--update` command and refreshes all version arrays from
those source intersections; no per-version task-list edits are required. Running
this command refreshed all 58 tasks with byte-identical output.

Removed rules/workflows: independently comparing handwritten generated Java
version rosters or packaging filename lists is already obsolete after Phases
2–3; generated-output byte equality and materialized byte/index checks replace
those restatements. The registry's basis/reason approval and `--update` workflow
are retired: bare sets are banned, while exact value facts have evidence
exceptions. R3 no longer restates every Java fact-table version in JSON.

All 13 `version-monotonicity-exceptions.json` entries remain unchanged, including
the editor-model alias regression. `check_sdk_cubism_annotations.py` and its
baseline are untouched. Gradle has one R1–R4 task and one SelfTest, shared by
`devCheck`, `checkCompletedCommit` and bootstrap resource packaging.

## Gate log and operational caveats

The final full gate is run with the requested command and judged only by the
literal BUILD SUCCESSFUL / BUILD FAILED in `/tmp/vt4-gate.log`; the final response
records its result. An initial attempt failed before tests because the Gradle
daemon could not stat sandbox-only `.agents` directories. Empty worktree-local
`.agents`, `.codex` and `.aws` directories align the filesystem views; no
configuration/state files were written.

The next full attempt reported failures in
`CubismFacadeImplTest.genericDeformerProjectionPreservesSubtypeWritesAndLifecycle`
and `EdtDispatchTest.postStartInterruptWithCompensationReleasesTheCaller`. Both
classes passed in isolation without code changes (`/tmp/vt4-followup.log`); the
failed full log is retained at `/tmp/vt4-gate-runtime-failures.log`.

An earlier incorrectly filtered runtime invocation remains waiting in the
unrelated Wayland screenshot test (`ScreenshotCaptureHostOperationsTest`, native
`ScreencastHelper`). It was not killed or stopped. Correctly filtered tests use
Xvfb and a separate build directory. No Cubism host loops were run.
