# SDK v13 contract-convergence review

This revision freezes the SDK after the pre-1.0 contract convergence merged on
top of the v12 anchor. The convergence deliberately breaks compatibility —
allowed before the first stable (1.x) baseline exists — to replace deprecated
service accessors with the service directory, retire the queue-based write path,
move JSON support inside the SDK package prefix, and reshape backup reads around
artifact handles. No SDK production source is changed by this contract revision.
The plugin remains Preview; incubating surfaces remain excluded from the exact
gate under the existing policy.

## Immutable reference

- Source: `77b9d6cff4aa7fa6afd6cefbea0f6a8bdffde501`, the service-directory Javadoc fix and the latest commit to change `sdk/`.
- Reconstructed from an isolated Git archive with `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17, without live workspace sources.
- Reference JAR: 1147150 bytes, SHA-256 `2f60e3f036ef893eeab15a27e4332e5e91b81a113b705af94f9dfda1d2a59de9`.
- Canonical dump: 7775 lines, SHA-256 `47cbb79147f1011e185a07ec165bc6de526d88d5e9f48c09eef0ff89cfe6404b`.
- The baseline was captured outside `sdk/api-contracts/baselines`, then added as a new v13 baseline. All v2–v12 baseline files and their anchors remain unchanged.
- v12 becomes a historical exact audit. v13 compares the live JAR's complete non-incubating canonical API with the independently reconstructed reference. Both are required by `checkRelease`; no check generates or overwrites a baseline.
- Raw JAR metadata can vary with the build environment. The existing verifier binds the reference's canonical hash/line count and compares every API record, including signatures, annotations, enum ordinals and parameter metadata.

## v12 → v13 delta

The frozen v12 baseline is bound to
`913ada16a231ee43f22b39c4adbf767bd3cb8a37` and has 8876 canonical lines.
Comparing its reconstructed JAR with v13 reports **1258 removed/changed records
and 157 added/changed records**: 8874 → 7773 API records, plus two header lines
in each dump. The delta comprises 46 changed identities, 1212 removed or
newly-incubating identities and 111 new identities.

This is a breaking convergence, not an additive revision. Every removal and
signature change is deliberate and carries the migration below; plugin authors
must recompile against v13.

### Existing records

| Surface | Exact delta and migration |
| --- | --- |
| `PluginService` | 40 enum-constant field records change `enum-ordinal` after `SCRIPTS`, `MESH_TOOLS`, `MODELING_TOOLS` and `MCP_CONNECTIONS` gain constant-level `@Incubating` and leave the non-incubating dump. The dump ordinal is the index within the emitted (non-incubating) constant sequence: the real JVM declaration order and `ordinal()` values are unchanged — all 48 constants keep their v12 positions. No runtime migration is needed for name-based lookup or enum switches; persisted ordinals remain valid. Only the canonical records moved. |
| `BackupSyncTarget.sync`, `BackupSyncTarget$Noop.sync` | Generic signature changes `List<File>` → `List<BackupArtifactHandle>`; the erased descriptor `(Ljava/util/List;)V` is unchanged, so already-compiled binaries still link but call sites must pass handles obtained from `EditorAutoBackupService.artifacts()` or `BackupRunResult.artifacts()`. |
| `BackupRunResult.<init>` | Generic signature and parameter metadata change `newBackupFiles: List<File>` → `artifacts: List<BackupArtifactHandle>`; the erased descriptor is unchanged. |
| `EditorAutoBackupStatus` | The `filePath` record component is deleted; the three surviving components (`lastAutoBackupTimeMillis`, `lastSavedTimeMillis`, `modifiedAfterSaving`) shift to indexes 1–3. Read artifact metadata through `BackupArtifactHandle`/`BackupArtifact` instead of host file paths. |

No surviving existing class, method or field changes its name or erased JVM
descriptor. The incubating-scope exits and signature changes above are still
breaking for source and for clients that consumed the removed declarations; the
v13 freeze records them explicitly.

### New surfaces

| Surface | Effect |
| --- | --- |
| `PluginContext.services()`, `PluginServiceDirectory` (`installed`/`find`/`require`), `PluginServices` (`of`/`empty`/`builder`), `PluginServices$Builder` (`install`/`supply`/`fallback`/`build`), `PluginServiceUnavailableException` (`serviceType`/`service`/`Optional` accessor) | The service directory ships incubating in v12 and is promoted to the stable surface in v13. `find(Class<T>)` returns `Optional<T>`; `require` throws `PluginServiceUnavailableException` whose `service()` now returns `Optional<PluginService>`. This is the only service-lookup entry point. |
| `PluginService.type()`, `PluginService.forType(Class)` | Promoted from incubating; `forType` now returns `Optional<PluginService>` instead of nullable. `resolve(PluginContext)` was deleted while still incubating and never entered the exact surface. |
| `dev.turboism.sdk.json.Json` and the `dev/turboism/sdk/json` package | Typed JSON codec replacing the internal `dev.turboism.protocol.json.StrictJson`: `parseObject` → `Map<String,?>`, `parseArray` → `List<?>`, and `stringify`/`bytes` overloads for `Map<String,?>`/`List<?>`. No raw `Object` roots, satisfying the SDK surface policy. |
| `BackupArtifactHandle` (`artifact`/`fileName`/`sizeBytes`/`temporary`/`lastModifiedMillis`/`openStream`/`discard`), `BackupRunResult.artifacts()`, `EditorAutoBackupService.artifacts()` (+ `Unavailable` implementation), `EditorAutoBackupSettings.backupDirDisplay()` | Backup reads move from host `File` paths to detached handles and metadata; reading or discarding a handle requires the `turboism.cubism.backup.observe` permission. `backupDir(): String` becomes `backupDirDisplay(): Optional<String>` and the `EditorAutoBackupSettings`/`EditorAutoBackupStatus` constructors are re-shaped to match. |
| `unavailable()`/`isAvailable()` on `CubismModelAccess`, `CoreRuntimeInfo`, `TextureAtlasLayoutService`, `TextureAtlasPolygonLayoutService`, `TextureAtlasEditorSession`, `TextureAtlasEditorUi`, `TextureAtlasLayoutAlgorithmRegistry`, plus their `Unavailable` nested enums | The corresponding `CubismFacade` accessors no longer throw `UnsupportedOperationException`; they return sentinels reporting `isAvailable() == false`. `CubismModelAccess.unavailable().active()` still throws `IllegalStateException`. |
| `PluginLocalization.text(String, String)` | Fallback lookup: null/blank keys, an unavailable service and missing keys all return the fallback, and `localization()` is non-null by contract. |

### Removed surfaces

| Surface | Migration |
| --- | --- |
| `PluginContext` deprecated service accessors — `appearance`, `availableServices`, `backup`, `contextMenu`, `cubismClipMasks`, `cubismLog`, `editorCommands`, `exportSettings`, `fileChooserHistory`, `hostDialogs`, `mainToolbar`, `mcpConnections`, `meshEdit`, `meshEditParticipation`, `meshEditUi`, `meshMirrorAxis`, `meshMirrorCounterparts`, `meshMirrorMoveParticipation`, `meshMirrorToolEligibility`, `paletteFilter`, `paletteToolbar`, `performanceStats`, `physicsEditor`, `recentFiles`, `recentPreviews`, `runtimeSettings`, `sceneTable`, `screenshots`, `uiHost`, `uiResources`, `viewContextMenu`, `warpAltMirrorParticipation`, `workspace`, `workspaceLayout` (34 methods deprecated in v12) plus `actionCatalog` | `ctx.X()` → `ctx.services().find(X.class).orElse(X.unavailable())` for optional use, or `ctx.services().require(X.class)` when the plugin declared the service. `availableServices()` → `services().installed()`. |
| `PluginContext.scripts()` | Moved to `@Incubating` with the script service; still resolvable through `services().find(ScriptService.class)`. |
| `dev.turboism.sdk.cubism.transaction` queue types (`TransactionManager`, `ModelTransaction`, `TransactionStatus`, `TransactionException` and its seven subclasses) and `CubismFacade.transactionManager()` | The queue-based write path is retired; the package keeps only the `AuthoringTransaction*` batching API. Mutate through `Parameter.setValue` and `CubismFacade.authoringTransactions()`. |
| `dev.turboism.sdk.cubism.write` (`CubismWriteCommand`, `WriteParameterCommand`, `WriteModelObjectCommand`, `WriteCanvasCommand`, `WriteClipMaskCommand`, `WriteResult`) and `BoundingBoxWriteCommand`, `DeformerWriteCommand`, `PsdBindingWriteCommand` | Command-queue writes are retired with the transaction manager; use `Parameter.setValue`, authoring transactions and the incubating `cubism.edit` session ops. The `cubism/boundingbox` and `cubism/deformer` package records leave the dump because these were their only non-incubating members. |
| `BackupRunResult.newBackupFiles()`, `EditorAutoBackupSettings.backupDir()` (+ old constructor), `EditorAutoBackupStatus.filePath` (+ old constructor) | Replaced by `artifacts()`, `backupDirDisplay()` and `BackupArtifactHandle`/`BackupArtifact` metadata; see New surfaces. |
| `dev.turboism.protocol.json.StrictJson` | Internal codec outside the scanned `dev.turboism.sdk` prefix (never in the baseline); use `dev.turboism.sdk.json.Json`. |

### @Incubating scope changes

`dev.turboism.sdk.cubism.edit` (69 class records) and `dev.turboism.sdk.script`
(11 class records) become `@Incubating` and leave the exact dump together with
`CubismFacade.edit()`, `PluginContext.scripts()` and the `PluginService`
constants `SCRIPTS`, `MESH_TOOLS`, `MODELING_TOOLS`, `MCP_CONNECTIONS`. That
accounts for 1021 of the 1212 removed/excluded identities; the declarations
still exist in the JAR and remain usable for early adopters without a stable
compatibility promise. The remaining 191 identities are the true removals
listed above.

## Reproduction and gates

Reconstruct the pinned SDK to a caller-selected review path:

```sh
python3 scripts/test/reconstruct_sdk_gradle_jar.py \
  --root . --commit 77b9d6cff4aa7fa6afd6cefbea0f6a8bdffde501 \
  --gradle <gradle-8.10.2-executable> --output <review-reference.jar> \
  --reuse-gradle-user-home <gradle-cache>
python3 scripts/test/sdk_api_baseline_cli.py capture \
  --input <review-reference.jar> --package-prefix dev.turboism.sdk \
  --role exact --commit 77b9d6cff4aa7fa6afd6cefbea0f6a8bdffde501 \
  --output <review-baseline.json>
```

Run the historical audit, live freeze, mutation selftests and retained linkage:

```sh
./gradlew checkSdkV12ExactApiCompatibility checkSdkV13ExactApiCompatibility \
  checkSdkApiBaselineTool checkSdkV8Linkage checkTextureAtlasSdkV7Linkage
./gradlew checkRelease -PinstallerVersion=0.44.0 -PturboismRelease=true
```

A v12 reference used as the live input against the v13 baseline must fail; using
the wrong expected commit must also fail. The existing baseline-tool selftests
remain responsible for API mutation and identity-tampering rejection. API gates
do not replace behavior tests, package checks or exact-host validation.
