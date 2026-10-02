# SDK v12 selection-tool and service-directory contract review

This revision freezes the SDK already merged with Selection Brush, so the plugin
can join the official installer/archive roster. It also accounts for the service
directory, action catalog and texture/PSD APIs merged after the current v11 anchor.
No SDK production source is changed by this contract revision. The plugin remains
Preview; its tool registries remain `@Incubating`.

## Immutable reference

- Source: `913ada16a231ee43f22b39c4adbf767bd3cb8a37`, the Selection Brush ordinary-modeling commit and the latest commit to change `sdk/`.
- Reconstructed from an isolated Git archive with `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17, without live workspace sources.
- Reference JAR: 1153496 bytes, SHA-256 `36b10bab3e6fd82c919312bcdf9940d01304f8e1457d2d5aefd219aba2514ea9`.
- Canonical dump: 8876 lines, SHA-256 `3d48ba834836db18bf654a0011b952cf1867a2d70c7ffc14f9ccf43d9b8b1838`.
- The baseline was captured outside `sdk/api-contracts/baselines`, then added as a new v12 baseline. All v2–v11 baseline files and their anchors remain unchanged.
- v11 becomes a historical exact audit. v12 compares the live JAR's complete non-incubating canonical API with the independently reconstructed reference. Both are required by `checkRelease`; no check generates or overwrites a baseline.
- Raw JAR metadata can vary with the build environment. The existing verifier binds the reference's canonical hash/line count and compares every API record, including signatures, annotations, enum ordinals and parameter metadata.

## v11 → v12 delta

The frozen v11 baseline is bound to
`181e9e9756e5dbb5a028c8f7f2c3c4b4ca76647d` and has 8526 canonical lines.
Comparing its reconstructed JAR with v12 reports **78 removed/changed records
and 428 added/changed records**: 8524 → 8874 API records, plus two header lines
in each dump. The delta comprises 55 changed identities, 23 excluded identities
and 373 new identities.

### Existing records

| Surface | Exact delta and migration |
| --- | --- |
| `PluginService` | 21 existing enum constants change ordinal after insertion of `MESH_TOOLS` (24), `MODELING_TOOLS` (25) and `ACTION_CATALOG` (27). `EDITOR_COMMANDS` moves 24 → 26; constants from `BACKUP` through `EXPORT_SETTINGS` move by +3. Persisted ordinals and direct `ordinal()` indexing require migration. Names and field descriptors are retained; name-based lookup and normal Java enum switches remain usable. |
| `PluginContext` | 34 retained methods gain `@Deprecated`: `appearance`, `availableServices`, `backup`, `contextMenu`, `cubismClipMasks`, `cubismLog`, `editorCommands`, `exportSettings`, `fileChooserHistory`, `hostDialogs`, `mainToolbar`, `mcpConnections`, `meshEdit`, `meshEditParticipation`, `meshEditUi`, `meshMirrorAxis`, `meshMirrorCounterparts`, `meshMirrorMoveParticipation`, `meshMirrorToolEligibility`, `paletteFilter`, `paletteToolbar`, `performanceStats`, `physicsEditor`, `recentFiles`, `recentPreviews`, `runtimeSettings`, `sceneTable`, `screenshots`, `uiHost`, `uiResources`, `viewContextMenu`, `warpAltMirrorParticipation`, `workspace`, `workspaceLayout`. Descriptors and default bridges remain. New code should resolve optional services through `services().get/require`. |
| `PluginContext.exportSettings()` | In addition to deprecation, the old `@CubismEditor` annotation is absent. Annotation-driven consumers must account for this metadata change; service availability still depends on verified host bindings, permissions and capabilities. |
| Event/MCP incubating declarations | 23 identities leave the non-incubating dump: five classes (`EventSubscriberRegistrar`, `GeneratedSubscriberCatalog`, `McpConnectionService`, its `Unavailable` enum, `McpHttpConnection`), 16 methods, one field and the MCP package record. The four owner types acquired `@Incubating`; the nested enum follows its owner. Their public classes and descriptors still exist in the live JAR. This is an explicit compatibility-scope change already present in the merged SDK, not evidence of stable compatibility for these declarations. Consumers should pin their framework version. |

No surviving existing class, method or field changes its name or JVM descriptor.
This does not make the ordinal, annotation or incubating-scope changes invisible
to clients. The v12 freeze records them explicitly instead of calling the whole
delta additive.

### New surfaces

| Surface | Effect |
| --- | --- |
| `VertexSelection` | Canonical immutable vertex-index selection with empty factory; indices are non-negative, sorted and unique. Although its Javadoc calls it Preview, the unannotated type is included in the v12 exact gate. |
| `PluginService.MESH_TOOLS`, `MODELING_TOOLS`; `PermissionIds.TURBOISM_UI_TOOLBAR_MESH_CONTRIBUTE`; `MainToolbarRegistry.Anchor.HOST_BRUSH_SELECTION_TOOL` | Lookup and semantic toolbar placement for Selection Brush. Ordinary modeling uses the existing main-toolbar contribution permission. Mesh/modeling tool registries, editor handles, gestures and `PluginContext.services()` are `@Incubating` and excluded under the existing policy; bundling the plugin does not stabilize those services. |
| `ActionCatalogService`, `ActionDescriptor`, `ActionRegistry.ShortcutAction`, action defaults, `PluginContext.actionCatalog()`, `PluginService.ACTION_CATALOG`, `PermissionIds.TURBOISM_ACTION_INVOKE` | Enumeration/invocation of registered plugin actions and shortcut metadata. |
| `RawLayerId`, texture source/relation records and `ModelTextures` queries | Typed texture/PSD source, layer binding and model image/group projections. |
| `PsdEditFile` operations, `PsdFileRevision` and PSD result/status types | Typed edit-file revision tokens and export, replacement and file-operation results. |
| `WarpAltMirrorParticipation` | Three additional weight-mirror diagnostic counters: applied count, last source index and last counterpart index. |
| `Incubating` annotation | Explicit marker used by the existing canonical-dump exclusion policy. No exclusion logic is changed by this revision. |

## Reproduction and gates

Reconstruct the pinned SDK to a caller-selected review path:

```sh
python3 scripts/test/reconstruct_sdk_gradle_jar.py \
  --root . --commit 913ada16a231ee43f22b39c4adbf767bd3cb8a37 \
  --gradle <gradle-8.10.2-executable> --output <review-reference.jar> \
  --reuse-gradle-user-home <gradle-cache>
python3 scripts/test/sdk_api_baseline_cli.py capture \
  --input <review-reference.jar> --package-prefix dev.turboism.sdk \
  --role exact --commit 913ada16a231ee43f22b39c4adbf767bd3cb8a37 \
  --output <review-baseline.json>
```

Run the historical audit, live freeze, mutation selftests and retained linkage:

```sh
./gradlew checkSdkV11ExactApiCompatibility checkSdkV12ExactApiCompatibility \
  checkSdkApiBaselineTool checkSdkV8Linkage checkTextureAtlasSdkV7Linkage
./gradlew checkRelease -PinstallerVersion=0.44.0 -PturboismRelease=true
```

A v11 reference used as the live input against the v12 baseline must fail; using
the wrong expected commit must also fail. The existing baseline-tool selftests
remain responsible for API mutation and identity-tampering rejection. API gates
do not replace behavior tests, package checks or exact-host validation.
