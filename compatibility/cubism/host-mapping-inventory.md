# Runtime/bootstrap host mapping inventory

Scope: reviewed source at `3559dccdfabd73c51b0daa1431ad165bba2e46d4`.
This is a maintenance inventory, not a report of incorrect mappings or new host
verification. Owners below are code-owning surfaces, not individual maintainers.
Searches of runtime/bootstrap Java for version branches, reviewed-version helpers,
Target/HostProfile classes and host owner strings were followed by inspection of
consumers and verification authoring. Search hit counts are not defect counts.
Plugins are outside this inventory.

## Version-dependent declaration candidates

Paths in this table are relative to `runtime/src/main/java/dev/turboism/` unless
prefixed with `bootstrap:` (relative to `bootstrap/src/main/java/dev/turboism/bootstrap/`).
“Candidate” means future migration work; none is silently included in this batch.

| Owner / declaration location | Authoritative source before this batch | Consumer | Existing verification | Classification |
| --- | --- | --- | --- | --- |
| `adapter/cubism/optimization/modelupdate/ModelUpdateSkipTarget` | Java per-artifact owner, descriptor, context, settings and flags | skip bridge/transformer; bootstrap VerifiedModelUpdateSkipInstaller | Target/Bridge/Transformer/Artifact tests; installer tests; independent class pins | **Migrated this batch** |
| `adapter/cubism/optimization/modelupdate/incremental/IncrementalUpdateTarget` | Java per-artifact updater/context/contextParam | incremental bridge/transformer; bootstrap VerifiedIncrementalUpdateInstaller | Bridge/Transformer/Artifact tests; installer tests; independent class pins | **Migrated this batch** |
| `bootstrap:ParameterPointContextMenuHostProfile` | reviewed guard then Q/ab/ac owner switch | ObjectContextMenuHookContributor / parameter-point context menu transformer | parameter-point context-menu profile/transformer and installer tests; parameter-points records | Candidate: bootstrap selector declaration |
| `bootstrap:ObjectContextMenuHostProfile` | reviewed switch for parameter/parts/workspace tuples | VerifiedObjectContextMenuHookInstaller | installer/profile/transformer tests; object-context-menu records | Candidate: bootstrap selector declaration |
| `adapter/cubism/filechooser/FileChooserHistoryHostProfile` | Java dialog and export-context version branches | file chooser hook/service | FileChooserHistory profile/service tests; file-chooser mapping pack is DRAFT (not equivalent VERIFIED evidence) | Candidate |
| `exportsettings/ExportSettingsHostProfile` | per-version Java profile + window panel selector | export-settings hook / operations | export-settings records; profile/installer tests | Candidate; records already describe selectors, reconcile before moving |
| `adapter/cubism/mesh/MeshMirrorHostProfile` | Java profiles for four reviewed lines | mesh mirror admission and bridge | mesh-mirror records, ClassPinTable, hook/profile/installer tests | Candidate; version list is derived, symbol tuples still authored |
| `adapter/cubism/warpalt/WarpAltMirrorHostProfile` | Java line profiles | warp-alt admission and bridge | warp-alt-mirror records, ClassPinTable, profile/transformer tests | Candidate; do not merge with mesh-mirror admission |
| `adapter/cubism/performance/PerformanceProbeTargets` | Java named generation methods and metric tuples | VerifiedPerformanceProbeInstaller / FPS installer | performance records, PerformanceProbeMethodTransformerTest and bootstrap profile tests | Candidate; shared methods do not imply equal capability coverage |
| `adapter/cubism/textureatlas/image/AtlasTileBboxTarget` | four Java class-name/digest groups | AtlasTileBboxPatcher/Transformer | patcher/transformer tests and exact class-byte checks | Candidate; grouped digest facts remain local here (not all digests are hand-written elsewhere) |
| `adapter/cubism/textureatlas/cache/AtlasCacheReuseTarget` | three Java entry-method shapes | AtlasCacheReusePatcher/Transformer | patcher/transformer/delegate tests | Candidate; stable owner with varying descriptors |
| `adapter/cubism/optimization/glerror/GlGetErrorElisionTarget` | Java shader w/y/A owner choice | GL error bridge/transformer | target/transformer/admission tests; ClassPinTable | Candidate; reviewed artifact set already derived |
| `adapter/cubism/optimization/uploadelision/SkippedFrameUploadElisionTarget` | Java shader w/y/A choice | upload-elision transformer/installer | transformer/admission tests; ClassPinTable | Candidate; relates to GL error owner but policy stays independent |
| `adapter/cubism/optimization/uniform/UniformLocationLifecycleTransformer.Role` | Java version-specific lifecycle owners | uniform location installer | call-site/lifecycle transformer and installer tests; pins | Candidate; role-specific evidence |
| `adapter/cubism/startup/StartupSuppressionProfile` | Java range-to-splash/shape mapping | startup suppression installer/transformer | startup-suppression records, class pins, profile/transformer tests | Candidate |
| `adapter/ui/OffCanvasBackgroundHostAccess` | APPEARANCE_CLASSES map; version-dependent setColor signature | off-canvas background service | OffCanvasBackgroundHostAccessTest; exact artifact and reflective shape checks | Candidate; appearance owner overlaps model-update settings, remains out of scope |
| `ui/filter/DeformerNodeSourceProfile` | Java version-to-accessor (e.g. i) choice | deformer tree filter | ui-control-appearance records and filter/accessor tests | Candidate; prefer existing selectors in a future slice |

## Generated and stable counterexamples

| Owner / surface | Authority and consumers | Verification / classification |
| --- | --- | --- |
| ReviewedHostArtifacts, ReviewedReleaseTable, ReviewedVersions helpers | `reviewed-releases.json` → generate_reviewed_releases.py; runtime/bootstrap admission and version queries | **Already generated**; checkReviewedReleases. No new version registry in this batch |
| `mapping/verification/*VerificationManifest`, `selector/*Contract` | verification-src → materialized records + verification-source families/templates; VerifiedMemberResolver and adapter consumers | **Already generated**; checkVerificationSources, record hash/alias/capability validation |
| ClassPinTable users (including both model-update Targets) | existing `adapter/cubism/class-pins/*.json`; generate_class_pins.py consumes reviewed JARs | **Resource-backed** class summaries; not Java symbol tables. Existing generators reused unchanged |
| Core public API catalog / selector contract | core-api observed inventories and policies; CorePublicApiProviderFactory | **Already generated**; core API policy/inventory/member and selector checks |
| ModelingToolHostProfile / MeshToolSessionHostProfile | generated selector contracts and verified resolver selectors | **Already record-backed**, not hand-written per-version host maps |
| ProjectLifecycleHostProfile / PhysicsEditorHostProfile / SceneTableHostProfile | Java common selectors with reviewed guard | **Stable selector constants**; lifecycle/physics/scene-table profile and installer tests. No per-version owner table found |
| InputPathElisionTarget / CanvasCompositeElisionTarget / DeferredGlErrorCheckTarget | stable Java symbol tuples; reviewed artifact enumeration derived | **Stable constants**; transformer/admission tests and existing pins. Do not migrate just because strings mention host classes |
| RedundantStateElisionTarget | fixed JOGL contracts and JOGL-specific admission | **Stable constants / separate artifact domain**; state-elision tests, not a Cubism owner-per-version table |
| NativeLabelColorTarget | palette + object identity | **Not a host symbol mapping** |
| EditorModelStatisticsAccess, VerifiedProtectedExportHostOperations | capability/version feature decisions using verified aliases | **Behavior / capability gates**, not extra owner maps; editor/protected-export records remain authoritative |
| CoreRuntimeMetadata / OwnedMocRuntime / CubismEditorReleaseDetector | Core/runtime identity and version parsing or admission policy | **Identity / policy**, not an obfuscated hook mapping candidate |

Other stable host strings in bridge implementations and generated contracts do
not imply a version declaration to migrate. This inventory does not claim that
all runtime reflection strings have become record-backed.

## Model-update authoring and schema

The explicit extension is `scripts/verification-sources/model-update-targets.json`
(format `turboism.model-update-targets/v1`), consumed by the existing
`generate_verification_sources.py` invocation. It emits only
`dev.turboism.mapping.verification.ModelUpdateTargetTable`, a typed data table
consumed by both adapters; the family-template package restriction remains intact.
No additional generator task, version registry or class-pin pipeline is introduced.
The existing verification records contain no corresponding model-update selector
rows; this schema fills that gap rather than copying another record's fields.

- `targets`: exact `{version, profile}` references, exactly one for every
  full-runtime reviewed release. No ranges, automatic propagation or neighboring
  obfuscated-name inference. Output order comes from reviewed-releases.json;
  artifact digest constants are referenced, never copied.
- `profiles`: named objects with exactly `updater`, `updateContext`, `contextParam`
  (JVM internal class names), `developSetting`, `appearanceSetting` (binary class
  names), and the boolean fields `conflictPolygonFlag`, `extendedUpdateContext`,
  `appearanceAuxSettings`. Each shared updater/context is authoritative here for
  both slices. The descriptor uses the same context references; each slice still
  loads its own ClassPinTable and verifies its own bytecode/dependency contracts.
- `entryDescriptor`: common JVM method shape with exactly one placeholder each
  for `${updateContext}`, `${contextParam}`, `${conflictPolygonFlag}`. The last
  becomes `Z` or empty. Expanded descriptors must be valid JVM method descriptors.
- Unknown fields/references/versions, absent/duplicate/conflicting target entries,
  duplicate JSON keys, invalid class names/descriptors/flags, unused profiles, and
  references absent from the existing per-slice class pins fail generation.

Java retains dependency construction, version-dependent dependency/descriptor
behavior, class pins, VERIFIED/COMPATIBLE admission, shape checks and fail-closed
installers. Shared declaration data is not shared evidence of authorization.
The generator also rejects reintroduced version constants, obfuscated declaration
literals or constructors bypassing generated rows in the migrated adapters; its
ordinary generated-source gate rejects a checked-in shadow ModelUpdateTargetTable.
All authoring, release registry, pin resources and imported loader scripts are
Gradle inputs for generation/checking; output is deterministic and cacheable.

## Adding a reviewed release

1. Review the exact artifact, update reviewed-releases.json and run
   `python3 scripts/generate_reviewed_releases.py` as usual.
2. Update the existing model-update-skip and incremental-update pins through
   `scripts/generate_class_pins.py` using that artifact. Review each feature's
   bytecode shape and dependencies independently; do not declare support from a
   matching version number or a neighboring owner's spelling.
3. Add one exact target reference in model-update-targets.json. Reuse a profile
   only after verifying all fields; otherwise add a reviewed profile. Unchanged
   shared symbols need no edits to either adapter. If existing dependency behavior
   is insufficient, review the Java behavior explicitly instead of expanding schema
   into a behavior template.
4. Run `./gradlew checkReviewedReleases checkVerificationSources`,
   `python3 scripts/test/test_ci_coverage.py`, and the model-update/runtime and
   bootstrap installer tests. The normal runtime compile generates the table.
   Run the CONTRIBUTING gates appropriate to final acceptance.
5. Keep the old baseline immutable. For genuinely added support, add separately
   reviewed expected output and tests; do not regenerate expectations from the new
   generator. Run configured artifact tests and report executed and skipped versions
   separately, then select real-host checks according to CONTRIBUTING.

## Independent equivalence baseline

`runtime/src/test/resources/dev/turboism/adapter/cubism/optimization/modelupdate/targets-before-generation.txt`
was frozen **before** editing either Target from the original Java classes at the
revision above. SHA-256:
`b698a5009379a1181eeb5ff3981c50919c4f3847b7b7708681c83c87392209e6`.
It contains all 20 skip and 20 incremental records (including artifact identity),
ordered complete dependencies, and every incremental derived descriptor/binary
owner query. `ModelUpdateSkipTargetTest.completeOutputMatchesIndependentPreMigrationBaseline`
compares actual runtime output against these independent bytes, not against output
recomputed by the new generator.

To reproduce the baseline independently, compile HostArtifactDigest,
ReviewedHostArtifacts, ReviewedReleaseTable, ClassPinTable and the two original
Targets with JDK 17 from that revision. With runtime/src/main/resources on the
classpath, print each skip record followed by each dependency; then each incremental
record followed by coreDescriptor, deformerUpdateDescriptor,
artMeshUpdateDescriptor, deformerInterpolateDescriptor, meshInterpolateDescriptor,
deformerBinary, meshFormBinary and each dependency, one entry per LF-terminated
line, in `all()` order. The test contains this serialization verbatim. This needs
no licensed host JAR and makes no claim of new real-host verification.
