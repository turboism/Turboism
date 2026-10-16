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
