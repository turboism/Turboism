# SDK v8 current-page texture layout contract review

> Historical review: after authorized main integration, v8 remains frozen and is audited historically. The current exact gate is [v10](sdk-api-v10-review.md); the statements below describe the original v8 acceptance.

The user explicitly chose **“演进新 SDK 合同（推荐）”** after being offered strict v7 preservation versus a new contract for the complete single-page scaling/rotation feature. This authorizes contract evolution and validation, **not publishing a release or modifying a Cubism installation**. The framework release version is unchanged.

## Immutable identity

- Source anchor: `959ca8c359f24b80c86bb9699c8111d067e75694` (SDK source/tests only).
- Reference: `scripts/test/reconstruct_sdk_gradle_jar.py`, isolated Git archive, Gradle 8.10.2, Java 17.
- JAR: 842885 bytes; SHA-256 `847473082cdc105b1e3b97e551a1db3603cc73b1d23d63b6b33803f4c51f7be6`.
- Canonical `dev.turboism.sdk` dump: 6415 lines including headers; SHA-256 `d282425d565686ff9f8106767141940144c0ccecf0d49a504bfa2418d981f92f`.
- All v2–v7 baseline files and commit anchors remain unchanged. v7 becomes a historical exact audit; **v8 is the live exact gate**. Verification does not capture or overwrite any baseline.

## Reviewed v7 → v8 delta

There are **2 changed canonical records and 17 additions** (net +15). Both changes retain the JVM constructor descriptor but change reflection parameter metadata when the old canonical constructor becomes an explicit delegating constructor. This is **not exact v7 compatibility**.

| Surface | Decision / consumer impact |
|---|---|
| `TextureAtlasLayoutConstraints` seven-argument constructor | Retained; delegates to null current-page options and preserves complete-atlas policy. Parameter-name metadata changes. |
| `TextureAtlasLayoutPlan` five-argument constructor | Retained; delegates to scale=1. Parameter-name metadata changes. Existing four-argument overload is also retained. |
| `TextureAtlasLayoutConstraints.singlePageOptions` | New nullable record component, accessor and eight-argument canonical constructor. Null means complete-atlas authoring; non-null means the issued current page and requires maxPages=1. |
| `TextureAtlasSinglePageOptions` | New immutable record. requestedScale=0 means automatic; positive means absolute fixed scale; negative/NaN/infinity rejected. The native adapter normalizes the host's finite non-positive sentinel to zero. |
| `TextureAtlasLayoutConstraints.currentPage(...)` | Explicit factory; per-side fixed pixel margin, doubled inter-item gap, rotation policy and requested scale. Does not grant a target or enumerate additional images. |
| `TextureAtlasLayoutPlan.scale` and `currentPage(...)` | Positive finite absolute scale; old constructors produce one. Placement dimensions are final pixel AABBs. Source scaling does not multiply old atlas transforms. |
| `TextureAtlasPlacement.rotated` | Existing flag now accepts true for the current-page contract. Complete-atlas application still rejects rotation. Constructor acceptance alone does not authorize a host write. |

The two extended records change record component lists, generated `toString` and hash behavior. Reflection-based record serializers, component-count assumptions, cached hashes and exact text snapshots must adapt. Retaining constructor bytecode linkage is not a promise of record serialization or semantic compatibility for all consumers.

## Target and lifetime boundary

- A native current-page snapshot contains only the invocation's issued images. Missing placement IDs become original-instance overflow, including an all-overflow result. No next-page search or container mutation is implied.
- A complete-atlas target still requires every issued ID, unit scale and no rotation. The production algorithm registration dispatches these requests to the unchanged complete planners.
- The target determines the contract. Constructing a current-page plan cannot convert a persistent target, expand its input set, or extend its lifetime.
- Runtime validates dimensions, source membership, page, margin, rotation and scale against the issued target before writing. Native writes synchronize item, LayerRef and DATA_SCALE, then verify readback; tests cover rejection and restoration.
- Conservative integer bounds and fixed pixel margins do not guarantee pixel-identical native layouts. Automatic search is bounded heuristic optimization of one page, not global atlas optimization.

## Verification

```sh
./gradlew checkSdkV7ExactApiCompatibility checkSdkV8ExactApiCompatibility \
  checkTextureAtlasSdkV7Linkage checkSdkApiBaselineTool --console=plain
```

`checkTextureAtlasSdkV7Linkage` compiles old constructor calls against the pinned v7 JAR, then executes that unchanged bytecode with **only the current SDK**. It verifies those retained entry points and policies, not exact record shape. SDK contract tests also cover new scale/rotation constraints and old complete constructors.

A negative exact comparison of the v7 JAR against the v8 baseline must fail. The existing verifier mutation selftests must continue to reject unauthorized drift and identity tampering. `checkRelease` retains historical audits and requires the new v8 exact gate and v7 linkage check; no released artifacts or installation are changed here.

Executed successfully in this worktree: v7 historical exact audit, v8 live exact audit (6413 records, zero drift), v7-compiled client linkage, and baseline verifier mutation selftests. The v7-input/v8-baseline negative comparison was rejected as expected. Logs: `/tmp/texture-sdk-v8-gates.log` and `/tmp/texture-sdk-v8-negative.log`. This is not a claim that the complete `checkRelease` task has run.

Native/legacy bytecode evidence and implementation validation live in `validation/texture-atlas-current-page/`. This SDK review does **not** promote real-host readiness, prove UI speedup, or replace native A/B, Undo/Redo, cancellation and save/reopen acceptance.
