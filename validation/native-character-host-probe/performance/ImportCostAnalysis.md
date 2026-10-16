# Native character import cost and repeated-work analysis

## Evidence and measurement boundaries

Use existing r24/r26 artifacts; no new full import is needed to establish this breakdown. r24 is the last comparable successful full-character **structure-only** measurement, not visual/save/reopen acceptance. Same explicit asset: 940 ArtMeshes, 236,437 triangles, 111 source controls plus XYZ, 796 control patches, 8 GiB heap and 100 ms stack sampler. T035/T036 remain open.

- r24 job `82d4d414-0dc8-4c84-9f34-b9ccbe488954`, run `queue-c919bb472465425882f63f6c490caed8`, host label `5304-preparts-r24-character`.
- r26 job `b8149089-e61c-4362-9e2d-6d29897f44b8`, run `queue-2e4debcfa8154edaa69dc07f531dafac`, label `5304-deferred-parameters-r26-character`. The probe lost the automatically dismissed success dialog between two EDT reads and reported NPE. Native console recorded import success, but there is **no comparable validated ready endpoint**. Supervisor reports FAIL and safe cleanup; do not describe this as successful acceptance or a measured total speedup.
- r25 independent preparation-only comparison: 73.260 s -> 47.475 s, identical output hashes/counts. This is not a full-host time.

## Nonoverlapping stage costs

Times use consecutive import-stage timestamps. r24 total ends at manual-inspection-ready, as in the previous baseline, not the earlier verifying marker. These are wall times and include native work reached from the plugin.

| Stage | r24 seconds | r24 share | r26 seconds |
| --- | ---: | ---: | ---: |
| Source preparation | 78.766 | 12.6% | 55.048 |
| Parts and parent hierarchy | 120.352 | 19.2% | 129.989 |
| Mesh creation, image binding, final-parent conversion, neutral checks, initial draw orders | 163.817 | 26.1% | 162.843 |
| Rotation banks and evaluation barrier | 82.582 | 13.2% | 80.214 |
| Explicit atlas authoring | 8.032 | 1.3% | 8.624 |
| Source control creation/readback | 93.915 | 15.0% | 138.061 |
| Interpolation, commit and final structure verification | 79.064 | 12.6% | Not accepted |
| **Total** | **626.527** | **100%** | **No accepted endpoint** |

The delayed-parameter candidate provides no established end-to-end benefit and was withdrawn. It passed small native controls/history/persistence checks, but that does not establish performance. Do not attribute the 44-second source-control difference solely to parameter scheduling: these are single runs and include host/native/GC variation.

## Overlap inside those stages

The stages above are disjoint. The work categories below are **nested** inside them and must not be added to stage times:

1. `NativeControlAuthoring.prepareHierarchy`: each of 796 local Warps calls `createWarp`, `setTargetDeformer`, `replaceGrid` in succession. Creation updates all model instances (`EditorObjectHierarchyEditAccess`, created-source apply); grid replacement updates all model instances and the deformer palette (`EditorObjectWriteAccess.writeWarpGrid`). Parent conversion also evaluates the model. The converted default grid is then overwritten by the explicitly computed final grid. This is a concrete repeated-initialization pattern, not three independent necessary user edits.
2. The same create/reparent/replace pattern exists for anatomical/outfit Warp parents in `NativeCharacterAuthoring`. These default objects are never intended as a visible intermediate result.
3. Each ArtMesh is created with geometry/default positions, binds its image while unparented, then converts into its final parent. Creation and conversion each evaluate instances. Texture binding-before-parent conversion is an observed native requirement; simply changing the order breaks import. A final-parent creation path must explicitly preserve the image binding contract and the <=0.001 canvas-pixel neutral conversion gate.
4. Native object getters (`currentArtMesh`, `currentDeformer`, collection `find`, `id`) validate full source/instance projections. Repeating a getter in an unchanged local scope can therefore scan all objects again; it is not a cheap field access. Cross-update caches are unsafe because native reparenting rebuilds instances and Undo can replace the model state.
5. Warp morph creation captures the parameter domain and all existing forms several times for preconditions, Undo admission, post-create validation and compensation. Repeating this per key grows with the number of already-created forms. Those checks cannot simply be removed: a genuine per-Warp bulk operation needs one protected before state, complete write/readback, atomic rollback and source identity checks.
6. Yaw and pitch interpolation writes rebuild the same mesh-local interpolation extension separately. Both axes are final before import commit, so a combined two-axis setter is another bounded candidate. Root model refresh is already coalesced; claiming that this still refreshes the whole model per axis would be incorrect.
7. Palettes, native instance setup, interpolator rebuild and texture setup can occur inside writes in several plugin stages. The ~8-second explicit atlas stage is not the total texture-related cost. Repeated texture-setup warnings alone do not prove that identical texture objects were uploaded redundantly; a texture identity/count observation is needed before claiming that.

## Stack evidence without double counting

r24 `import-stacks.txt`: 5,985 observations of the EDT. Classify each sample using only its **first Runtime frame**, so a nested getter/update is not counted again as its caller:

| First Runtime site | Observations |
| --- | ---: |
| Created-source apply / model instance update | 1,002 |
| ArtMesh parent conversion / model instance update | 647 |
| Palette refresh | 433 |
| Warp-grid model instance update | 316 |
| Warp morph state read | 223 |
| Interpolation state read | 180 |
| Object ID access, two source lines combined | 295 |

The first three instance-update entries above account for 1,965/5,985 observations (~32.8%). They are wall-clock sampling evidence, **not CPU percentages or exact saved seconds**. Palette refresh adds a separate first-frame category, but can itself invoke nested native evaluation. Do not sum the count for every matching stack frame.

## Ranked next changes and fast rejection gates

1. **Create Warp in its final state**: accept final parent, dimensions and grid at creation, so default parent conversion and overwritten default geometry need not be published/evaluated. Start with a small hierarchy fixture. Count native update calls for N parents before/after; require identical hierarchy/grid snapshots, source identity protection, Undo/Redo, rollback and save/reopen. Verify parent-relative grid semantics before replacing the importer path. If the count is unchanged, reject before a full-character run.
2. **Create isolated patch Parts as a batch**: 940 Parts are currently precreated but still use individual native creation/update operations. Precreation only changes scheduling. A real batch must admit all native Undo contributions, perform one instance barrier and return current handles after the barrier. Count update calls; exercise partial admission failure and transaction recovery.
3. **Write both interpolation axes in one per-mesh operation**: preserve binding membership, all stored forms and patch Part ownership. Count local cache rebuilds; do not alter density/error budgets to obtain speed.
4. **Bulk morph keys per Warp**: remove repeated whole-domain captures by changing the atomic operation boundary, retaining one complete protected before/after domain. Count snapshot reads as key count grows; prove rollback after the middle key fails.
5. **Scoped repeated-read reuse**: retain the local ID/snapshot reductions already regression-tested. They remove duplicate work but the measurements do not show a material mesh-stage speedup. Do not spend another full import measuring this alone.

Run one full-host timing only after a small deterministic counter/fixture demonstrates reduced expensive operations. Keep input, budgets, heap and accepted final endpoint fixed; run no concurrent local build or diagnostic during timing. Reuse preparation results and saved fixtures where the measured path does not require recomputation. No geometry/control omissions or lower quality are a performance optimization here.


## r28 final-state Warp creation candidate

`Deformers.createWarp(name, part, index, target, grid)` now accepts both independent parents and a grid already expressed in target-local coordinates. The Editor implementation sets the source's final target GUID and grid before a single native Part insertion/publication. Existing `createWarp(rows, columns)` remains available. Both session and permission layers explicitly forward the new method; unsupported backends fail closed. No existing source is reparented and no coordinate conversion is silently applied.

The importer uses it for anatomical, outfit, opacity-factor and local-control Warps. The old small hierarchy exercised four follow-up target conversions; a regression requiring zero follow-up conversions/grid replacements failed on that path and passes on the final-state path. Real Runtime wiring asserts exactly one `update-instances` per initialized Warp, one native insertion Undo, unchanged final grid/Part/target after Undo/Redo, no write for a missing target and no membership/tree residue after rejected admission. This removes two explicit post-create updates for each local control Warp; 796 such Warps previously invoked those two follow-up operations. This call-count reduction is not itself a measured total-time saving.

Focused Runtime tests: 205, all passing. Complete plugin tests: 277, no failures/errors, 34 explicitly configured external-fixture skips. Full X11/Xvfb `devCheck previewBundle` passed in 4m16s, 153 tasks. Small native import, seam and reopen checks precede a single comparable complete character timing. Results and limitations are recorded in `../verification.md`.

### r28 accepted full-host result

The single gated full-character run passed supervisor and structure verification (job `6cdbe047-ed94-4f3d-938c-ff1d0cb7a12f`, run `queue-1276f5d3dbe04c989a2bad4e4b34c1ba`). Same input/heap/sampler/ready endpoint as r24, no concurrent build or diagnostic: **483.5022784 s**, versus **626.527317 s**, saving **143.025039 s / 22.828%**. Identity/fixture/normal-exit/cleanup checks pass. Counts remain 940 ArtMeshes, 236,437 triangles and 114 parameters; neutral error is unchanged. This comparison includes preparation/read-reuse changes and is one run per candidate, not isolated causal attribution.

| Disjoint stage | r24 seconds | r28 seconds |
| --- | ---: | ---: |
| Preparation and authoring handoff | 78.766 | 51.233 |
| Parts and parent hierarchy | 120.352 | 38.265 |
| Mesh/image/parent/neutral/default order | 163.817 | 156.505 |
| Rotation banks | 82.582 | 84.611 |
| Explicit atlas | 8.032 | 8.789 |
| Source controls | 93.915 | 81.664 |
| Interpolation/commit/final structure | 79.064 | 62.437 |
| **Total** | **626.527** | **483.502** |

Hierarchy initialization shows the largest stage reduction; mesh creation remains the largest cost. Continue with a true batch Part insertion counter/rollback test before another full run. Eight minutes is still excessive (T036 open). This is structure-only evidence; T035 occlusion and full-character visual/persistence acceptance remain open. See `../verification.md` for artifact hashes, small native persistence checks and exact timestamps.

## r29 batch patch-Part creation gate

`Parts.createMany(names, parent)` appends sibling Parts in input order, with distinct generated IDs for duplicate display names. The Editor implementation reserves IDs from one existing-object read, initializes sources before publication, admits **every** native insertion Undo into one contribution, then calls `update-instances` once. The root Undo listener refreshes only after the complete group replays. It registers every created source identity for dependent writers' recovery. Applied/restored membership checks read the complete source set and parent children once per check, rather than per Part. Unsupported backends fail closed.

The old three-Part regression reports **3 updates** where one is required. The new path requires **1 update**, one root entry, three insertion Undo children, distinct IDs, source/tree membership and Undo/Redo restoration. A rejected second admission exercises eager native insertion compensation, with and without native abort. Blank names reject before insertion, an empty batch is a no-op, and an 80-Part batch tests the 64-item semantic-history boundary: display detail is explicitly partial beyond that bound, while **all 80 native Undo records and source identities remain protected**.

The r28 log bounds the likely end-to-end impact: initial source-geometry/patch-Part preparation from 07:37:08.4058722Z to control hierarchy at 07:37:14.6478302Z took **6.241958 s**. The whole 38.264813-second hierarchy stage also contains local-control Warp creation. Do not attribute all 38 seconds to patch Parts. A separate full-character run for this bounded change would spend about eight minutes to measure a change confined to this six-second substage; use operation-count and small native history/persistence gates instead. The latest accepted full timing remains r28 **483.5022784 s**. No r29 full-host saving is claimed. Larger next candidates remain mesh creation/conversion and combined per-mesh interpolation/cache writes, with the same protected readback and recovery requirements.

## r30 bounded ArtMesh publication candidate

The r28 mesh/image/parent/neutral/default-order stage took **156.505 s**. It includes several nested costs; it is not all mesh construction. Creation publishes model instances once per ArtMesh, while later native parent conversion also evaluates instances. Texture binding itself already contributes a coalesced root refresh; its presence does not justify claiming an additional immediate model refresh for every binding.

Candidate: `Drawables.createManyWithSourceGeometry` publishes a batch of initialized, texture-unbound ArtMeshes in existing Parts, with separate source/default coordinates. It reuses the r29 atomic insertion/recovery mechanism, registers every created identity, admits every native insertion Undo, reserves IDs once per batch, validates all parent IDs before insertion, and returns current handles after one instance barrier. Existing single-mesh creation remains available. The importer uses **at most 32 meshes** per batch, then preserves each mesh's image-bind-before-final-parent-conversion order and the <=0.001-pixel neutral gate. Preparing all 940 at once would make early conversions evaluate all future meshes, so the importer deliberately bounds publication. Source geometry, indices, materials, controls, sampling/error budgets and placement checks are unchanged.

Red Runtime regression: three sequential creations publish **3** times where **1** is required (`/tmp/native-mesh-batch-r30-red.log`). Green checks one publication, unique IDs, exact source positions/UV/indices and default-form positions across Undo/Redo, separate Part ownership, missing-parent preflight, empty no-op, and rejected second eager insertion with both native-abort modes. Importer regression with 70 meshes requires batch sizes **32/32/6**, zero single-mesh calls, bounded future mesh publication, and image binding while each target deformer is still absent. For 940 partitions the creation barrier count is **30**, versus 940 previously; this is not an elapsed-time saving claim. Results follow in `../verification.md` after real-host gates.

### r30 accepted complete-character result

The single full run passed supervisor and structure verification: job `b698eda0-5774-46f2-94c5-1bab0d65a607`, run `queue-3b57148241fa44d3a0936e75e811af5b`. Same input, 8 GiB heap, sampler and accepted ready endpoint as r28, with no concurrent local builds/diagnostics: **419.711243 s (6m59.711s)** versus **483.5022784 s**, saving **63.7910354 s / 13.1935%**. This includes r29 Part batching and is one run per candidate, not an isolated causal attribution. Counts, source hash and neutral error are unchanged.

| Disjoint stage | r28 seconds | r30 seconds |
| --- | ---: | ---: |
| Preparation/handoff | 51.233 | 51.359 |
| Parts/control hierarchy | 38.265 | 36.656 |
| Mesh/image/parent/neutral/default order | 156.505 | 98.018 |
| Rotation banks | 84.611 | 89.748 |
| Explicit atlas | 8.789 | 8.723 |
| Source controls | 81.664 | 79.942 |
| Interpolation/commit/final structure | 62.437 | 55.265 |
| **Total** | **483.502** | **419.711** |

Nested native refresh/texture/palette work is included in these stages, not additional elapsed time. Mesh creation remains the largest stage, but rotation and source controls are now similarly material; per-mesh combined interpolation is the next bounded repeated-cache candidate. Require an actual cache rebuild counter plus banks/ownership/history/recovery/persistence gates before another full run. T035/T036 remain open: seven minutes is still excessive, and this full result is structure only. See `../verification.md` for all small/native gates, artifacts and exact endpoint timestamps.


## r31: combine interpolation axes before one local cache rebuild

The r30 ready endpoint remains the latest accepted complete-character timing: **419.711243 s**, including **55.265 s** for interpolation, commit and final verification. That stage is not exclusively cache generation. No new complete-character timing is needed to demonstrate the duplicate local work, and no new total-time reduction is claimed here.

Ranked falsifiable hypotheses: (1) separate yaw/pitch writes rebuild the same local cache twice; combined settings should reduce setup/update pairs from two to one; (2) separate contributions hash/read the intermediate cache unnecessarily; one combined contribution should eliminate the intermediate snapshot domain; (3) final model refresh/verification can remain expensive despite fewer local builds. Root model refresh was already coalesced and is not counted as a per-axis saving.

Added `parameterBindingBatch().setInterpolations(target, settings)` with fail-closed defaults and explicit Session/permission forwarding. The importer submits yaw and pitch together for each visible mesh. The native adapter validates every axis and the **combined** generated-grid budget before Undo admission, captures one source/cache recovery domain, admits native all-edit/extension Undo, applies all selected settings, then rebuilds once. One contribution listener rebuilds the restored final state during Undo/Redo; the existing root listener still refreshes instances/palettes/canvas once. Sampling, source banks, morph controls, opacity, geometry, cache budgets and neutral conversion limits are unchanged.

Deterministic tests exercise the original sequential pattern (two builds) and combined pattern (one build, same final 25-row grid), source-bank preservation, native Undo/Redo, post-write transaction abort restoring both axes/extension, final combined-budget rejection before mutation, no-op reuse, permission revocation and retained-session model replacement. Full plugin tests also require one combined two-axis call per visible mesh. External-fixture skips remain explicit.

Validation and small native persistence evidence are recorded in `verification.md`. T035 occlusion and T036 overall latency remain open.


## r32: combine rotation-form positions and draw orders

The next repeated domain is the r30 **89.748 s** rotation-bank stage. Each visible ArtMesh binds two axes, writes 24 non-default projected positions, reads the complete bank back, then separately writes the same forms' draw orders. Those two value edits each capture an all-keyform native Undo snapshot and validate the complete authoring state. The explicit source barrier already coalesces model evaluation; it is not a per-edit saving.

Ranked hypotheses were: (1) combine positions/orders to reduce Undo contributions from two to one; (2) remove the intermediate source snapshot and the second complete compare-and-set cycle; (3) binding/grid expansion may still dominate the stage after value-write consolidation. Only the first two are changed in r32.

`Drawable.setOrdinaryKeyforms(expected, positions, drawOrders)` requires identical distinct non-default form GUIDs and complete coordinate tuples in both lists (list order may differ). It reuses the verified native setters and one existing KEYFORM_EDIT contribution, preserving default/shared/morph protection, source identity, full expected-state comparison, native form identities, 4096-form / 2M-position limits, full applied/restored predicates and native Undo/Redo. Session and permission wrappers explicitly forward the API; a combined semantic operation records the whole authoring state. Stored-source batching still evaluates once at the existing barrier. Other single-field APIs retain their behavior.

The importer now writes both fields together and retains its exact final expected snapshot, placement verification and later interpolation verification. It no longer reads the intermediate position-only bank or admits a second Undo snapshot for the same rotation forms.

A real adapter/writer/coordinator deterministic test reproduces both importer patterns in the same source-only transaction domain: **9 source-geometry reads and 2 native Undo snapshots -> 4 reads and 1 snapshot**. These are call counts, not wall/CPU percentages. Final selected fields match; source geometry, axes, cells, morph keys and unselected forms are preserved. Additional tests cover caller abort, both native-abort recovery modes after refresh failure, mismatched/foreign/default/duplicate/stale targets, pre-mutation budget checks and equal-value no-ops. No geometry, control, precision or sampling reduction.

Build, native persistence and the single cumulative r31+r32 full-character timing are recorded in `verification.md`; the previous accepted baseline remains r30 **419.711243 s** until a new endpoint is accepted. T035/T036 remain open.


### Accepted cumulative r31+r32 timing

One comparable full-character structure-only job **`cfce2a55-4f72-4410-b203-8fcee21c35db`**, run **`queue-5f34d024932e45c3af04fb3d69973d93`**, label `5304-keyform-values-r32-character`: supervisor PASS, verified identity, unchanged input, normal exit, safe cleanup. All builds and small native gates finished before the run. Same explicit GLB SHA-256 `ad25dbb8cd3f12901c17f104ef26ee5a16ba688a8b6422aeccaddef19896623a`, 8 GiB heap, stack sampling and endpoint as r30.

**2026-10-16T10:06:46.838825200Z -> 10:13:09.306739800Z = 382.4679146 s (6m22.468s)**. Compared with r30 419.711243 s, **37.2433284 s / 8.87356%** less. This is a single run per candidate and combines r31 interpolation with r32 keyform-value consolidation; it does not isolate causal savings or establish repeat-run variance.

| Disjoint stage | r30 seconds | r32 seconds |
| --- | ---: | ---: |
| Source preparation/handoff | 51.359 | 51.962 |
| Parts/control hierarchy | 36.656 | 35.588 |
| Mesh/image/parent/neutral/default-order | 98.018 | 96.054 |
| Rotation banks/evaluation barrier | 89.748 | 72.706 |
| Explicit atlas | 8.723 | 8.471 |
| Source controls | 79.942 | 75.246 |
| Interpolation/commit/final verification | 55.265 | 42.441 |
| **Total** | **419.711** | **382.468** |

Nested refresh/read/Undo/cache work is already included and must not be added to this table. Counts unchanged: **940 ArtMeshes, 236437 triangles, 111 source controls plus XYZ = 114 parameters, one filename-scoped parameter group**. Maximum neutral parent conversion error unchanged at **0.0006068566462325795 Canvas px**. No new complete-character CMO3/save/reopen or visual occlusion acceptance is provided by this structure-only endpoint; small native persistence/seam gates remain separate evidence. **T035 and T036 remain open**. Remaining dominant costs are mesh/image/parent conversion, source controls and rotation bank construction; a future change must again prove repeated work before another expensive full run.


## r33 existing-capture hotspot attribution (no new host import)

`summarize_import_stacks.py` consumes the explicit r32 `import-stacks.txt` under
`5304-keyform-values-r32-character/queue-5f34d024932e45c3af04fb3d69973d93`.
It finds **3737 EDT observations: 3225 RUNNABLE, 512 WAITING**. Each RUNNABLE
observation contributes once to each table. The importer, editor-boundary and
leaf tables overlap each other and must not be added together. Schema 1 aggregates
stacks without individual timestamps: precise stage attribution is unavailable.
These are wall observations, not CPU percentages or exact seconds.

| First importer caller in r32 | RUNNABLE observations | Nested finding |
| --- | ---: | --- |
| NativeCharacterAuthoring:390, final-parent conversion | 755 | 633 include native reinitModelInstance |
| NativeControlAuthoring:195, individual Warp morph-key creation | 706 | 352 include object-domain reads; 310 include EditorWarpMorphState (overlapping categories) |
| NativeCharacterAuthoring:498, yaw/pitch axis binding | 580 | 154 include object-domain reads |
| NativeControlAuthoring:174, initialized local Warp creation | 304 | 281 include native reinitModelInstance |
| NativeCharacterAuthoring:604, combined interpolation | 166 | Already combined in r31; not two independent rebuilds |

Parent conversion remains the strongest native-rebuild observation. Texture
binding requires the new ArtMesh to be unparented, so replacing conversion with
an initialized-parent creation would violate the current binding contract. A
candidate must first prove texture binding with the final parent, or combine
binding and conversion behind one protected native operation; simply removing
the conversion/evaluation barrier is not justified.

The next source-control candidate is several morph keys in **one Warp-domain
contribution**: the importer already groups refreshes, but still invokes
`createMorphKeyform` per key and repeatedly captures/validates the same domain.
Before another full timing, prove capture count reduction with a multi-key Warp
fixture, including partial-failure recovery, shared/default-form protection,
Undo/Redo and persistence. Axis binding is another measured repeated-domain
candidate; it must preserve the complete 25-form grid and all identity gates.
No new performance saving or host acceptance is claimed by this analysis.
