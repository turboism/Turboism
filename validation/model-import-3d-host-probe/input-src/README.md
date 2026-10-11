# GLB input stage

This directory contains input planning and the SDK creation consumer for the
native importer. It is not included in the frozen joint-pose r2 or bulk r3 host
packages. Decoding and planning use the SDK artifact's strict JSON codec and
shared projection values without document access. The creation consumer writes
through public SDK operations when invoked by a caller; no code reads host internals.

`GlbSurfaceReader.read(byte[])` accepts a bounded GLB 2 document with one
embedded buffer, a selected static scene, indexed triangles, float positions,
UV0 and optional normals. It supports node hierarchy, column-major matrices,
TRS, interleaved attributes and unsigned 8/16/32-bit indices. Node instances,
primitive/material indices, original vertex/index order, UV seam slots and
source normals remain explicit. Reflected node transforms are flagged; source
winding is not silently rewritten.

The current appearance conversion is **flat opaque base color**, not PBR
lighting. Textures, alpha blending/masking, emissive color, skinning, animation,
morphs, sparse/normalized accessors, extra vertex attributes, UV repetition and
extensions are rejected explicitly. Container, JSON, hierarchy, accessor and
expanded-instance budgets are checked. External/data URI resources are never
opened. This is a declared input subset, not an arbitrary-glTF support claim.

`SurfacePatchPlan` proposes connected coplanar draw patches using shared source
indices and opposite shared-edge orientation. It never welds numerical position
or UV coincidences. Every local vertex and triangle maps back to the original
primitive. Creases remain separate; non-manifold edges require explicit splitting.
These patches are candidates: self-overlap, pose-dependent visibility and draw
order still require analysis before committing native topology. They are not
editor control-layer groups.

`GlbInputMain <authorized.glb> <new-plan.json>` emits source3D, textureUV,
source indices, material metadata and candidate patch correspondences. Output
uses CREATE_NEW and is marked `DECODED_NOT_IMPORTED`, `hostExecuted=false`,
`visibilityOrderVerified=false`. No screenshot or generated cube substitutes for
the user file.

The authorized input `~/Downloads/cube.glb` has SHA-256
`985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630`.
Its frozen task copy decodes to one primitive, 24 seam vertices, 36 indices,
24 normals and six four-vertex/two-triangle candidate patches. The input tests
cover malformed headers, bounds, external resources, unsupported features,
hierarchical TRS, reflected transforms, interleaving, seam identity, creases and
non-manifold edges, plus the actual authorized bytes (60 checks).

Reproducible compilation/test/export commands, source and SDK hashes, copied
sources and the resulting `cube-plan.json` are recorded in
`build/model-import-3d-offline/glb-reader-20261001/final/evidence.json`.
Native creation, parameter binding, material registration, occlusion and
persistence/export for these six patches remain unverified.


`SurfaceImportPlan` now compiles all patches onto one shared, float-validated
joint yaw/pitch grid. It retains the complete local-to-source correspondence and
projects every vertex at every full key tuple. It counts expanded patch vertices
before allocating projected arrays, limits each patch to 4096 keyforms, the whole
plan to 65536 keyforms and 2000000 projected vertices, and rejects key collisions.
Zero-area neutral triangles are recorded, never silently removed. The plan is not
a draw-order solution, and it does not create host objects.

`GlbProjectionMain <authorized.glb> <new-plan.json>` compiles a fixed validation
fixture: 1024 canvas, center (512,512), scale 200, origin pivot, +/-30 degree
axes, 0.05 px ideal interpolation budget. These are explicit test conditions,
not product defaults. Output uses CREATE_NEW and is marked
`PROJECTED_NOT_IMPORTED`, with native interpolation and visibility unverified.

For the actual cube, this produces 6 patches x 45 x 45 = 12150 planned keyforms,
48600 projected vertex slots. Four faces are edge-on at zero and all become
nondegenerate somewhere within the pose domain. `SurfaceImportPlanTest` checks
source/UV correspondence, seam trajectories, budget/key-collision refusals and
1000 deterministic random intermediate poses using an independent expanded
rotation matrix. Maximum measured ideal bilinear error is 0.0304272 px; this is
not native Cubism evidence. Frozen sources, SDK, commands, logs and the generated
plan are in `build/model-import-3d-offline/cube-projection-20261001/`.


`SurfaceImportGeometry.create` is now the public-SDK consumer of this plan. It
requires existing ordinary yaw/pitch axes at neutral with exactly the planned
domains and explicitly disabled repeat. Unknown repeat state is refused. It prepares every neutral mesh before creation, then creates all patches,
binds both complete axes and compiles a protected bake per returned native mesh
identity. `Created.bake` writes one SDK batch per mesh and verifies every complete
expected stored snapshot. The caller owns the bake transaction and recovery for
the earlier creation/binding stage. Material registration, culling/order, persistence
and whole-import atomicity remain separate, unverified stages.

`SurfaceImportGeometryTest` drives the actual authorized cube through reader,
planner and this SDK consumer against a synthetic stored-grid provider: six
meshes, twelve axis bindings, 12150 forms and six bulk writes. Its 47 assertions
cover source correspondence, complete UV/index preservation, full grids, complete
stored after states, non-neutral/wrong-domain/morph/repeating/unknown-repeat axis refusal before creation,
and dropped-write rejection. This is offline consumer integration, not native
Editor evidence. Frozen sources, SDK, input hash, commands and all three input
stage test results are in `build/model-import-3d-offline/cube-sdk-20261001/frozen-r2/`.


`ClosedConvexSurfaceProof` establishes a conservative precondition for a later
native culling experiment. It requires opaque, unreflected input, exact geometric
seam closure, two opposite uses per edge, connected triangles, outward supporting
planes and positive volume. Exact arithmetic on the binary-double coordinates
avoids accepting tiny cracks or inward plane violations via a tolerance. Work is
bounded to 1024 source slots, 256 triangles and 256 unique geometric positions.
The analytical seam map never rewrites source indices, UVs or native mesh slots.
Reflected instances and nonmatching seams are refused rather than repaired.

The actual cube passes as 24 source slots / 8 geometric vertices / 12 triangles /
18 edges. Fourteen assertions cover the cube, translation invariance and unsafe
counterexamples (missing/reversed/duplicate faces, one-ULP seam damage, concavity,
flatness, reflection, transparency, duplicate shells and empty input). Frozen
source/commands/results: `build/model-import-3d-offline/cube-sdk-20261001/convex-r1/`.
This does not enable culling in the consumer: native winding, edge-on transitions,
pixel visibility and save/export behavior remain unverified.


`CubeGeometryProbe` is the new SDK-only real-input geometry producer for the
separate `cube-geometry` native mode. `build-host.py --cube-input <authorized.glb>`
freezes the exact 1936-byte input, records its SHA, embeds it in the probe JAR and
declares its resource root in the generated package descriptor. The independent
build checker compares the descriptor with the frozen source plus exactly this
resource declaration; the runtime's real plugin packaging contract also runs.
The host verifies the input hash before creating its task document. Existing
modes retain their prior behavior, and the new cube section is NOT_RUN there.

The producer invokes `SurfaceImportGeometry` for all six faces and uses one bake
transaction. It captures complete stored grids before/after/Undo/Redo/evaluation,
then restores history, object counts and canvas in finally. Its 377 fixed poses
cover a critical 9x9 grid, every key along either neutral axis, diagonal cell
centers and 128 seeded random poses (duplicates removed). It does not claim that
every 2D interpolation cell was sampled. All 12150 stored keyforms are checked.
An independent Python reader decodes the exact input bytes and groups cube faces
by triangle normals, then compares source correspondence, all stored keys and
calculated positions against an expanded rotation matrix. Native tolerance is
still 0.1 px Euclidean. Culling, material sampling, persistence and plugin-free
export are not part of this geometry-only mode.

The actual Java cube producer and prior joint producer pass the synthetic SDK
integration tests, including wrong-geometry restoration and changed-input refusal.
Seven Python oracle control tests pass with the explicitly supplied synthetic
report, including missing edge-on faces, wrong UVs/bindings, stale consumption,
incorrect history and missing samples. These are offline results; host acceptance
requires a new cube-mode job, complete lifecycle proof and `verify-host.py
--expected-mode cube-geometry`. Initial producer evidence is frozen under
`build/model-import-3d-offline/cube-native-20261001/producer-r2/`.


`SurfaceBaseColorPlan` now prepares bounded 256x128 opaque sRGB PNGs from the
GLB's linear baseColorFactor, deduplicating equal quantized colors. It preserves
patch-to-source/material correspondence and owns defensive PNG copies. The actual
cube's ~0.8 linear grey encodes as (231,231,231), shared by all six faces. This is
flat base color, with no simulated lighting or PBR claim.

`SurfaceImportAppearance.bind` prevalidates every created/baked target and requires
all texture inputs to be empty before the first registration. It registers one
image per shared color and uses public `bindModelImage` for the other faces. It
then checks typed image/model/generation identity and every complete baked source
snapshot again. It does not change culling or draw order. The caller must provide
whole-import recovery if a later binding fails; this method does not claim that
all six image bindings already form an atomic transaction.

Twenty offline assertions pass for the actual cube, sRGB reference values, every
PNG texel, defensive bytes, source mismatch and invalid color refusals, shared
image identity, all-target prevalidation, missing binding, wrong generation and
full source/keyform preservation. Evidence is frozen under
`build/model-import-3d-offline/cube-appearance-20261001/r1/`. Native shared-image
rendering and front/back visibility are the next gates. New builds use the
`mi3d-base-color-input-v1` inventory (73 files); the accepted 70-file cube geometry
build retains its original `mi3d-cube-input-v1` inventory and acceptance scope.

`cube-render` extends the accepted geometry callback with six diagnostic colors
(culling off/on), then restores and binds the actual shared gray (culling on).
Each phase captures nine predeclared poses, including one-degree narrow faces.
All rendering uses public SDK calls. Recovery explicitly restores the entry pose
and awaits consumed evaluation outside EDT before comparing full state/resources.
No render capture alone is accepted: `verify-cube-render.py` independently checks
snapshot SHA-256 fingerprints, identities, consumed poses, before/after history,
resources, and all 27 PNG hashes. Its fixed four-pixel sampling grid excludes a
one-pixel edge band; triangle barycentric depth chooses the nearest 3D surface.
The disabled-culling phase must demonstrate a counterexample. Synthetic analytic
ray/box controls exercise the independent triangle oracle; they are not host
visibility evidence. New builds use the 78-source `mi3d-render-input-v1` inventory,
while existing 54/70/73-source packages retain their original contracts.

The first native render attempt exposed a public-reader limitation: `getOpacity`
reads the current authored keyform, which may be absent at interpolated parameter
values. Capture now records either the actual value or the precise unavailable
reason; it never substitutes 1 and never hides other failures. Opaque interior
alpha must still be exactly 255 in the independent raster check. The failed r1
run was collected as failure; it has no complete native report and no visibility
acceptance. The probe now defers its terminal failure marker until recovery and
report writing, preventing the wrapper from collecting incomplete failure evidence.

The second native attempt produced a complete failure report: six diagnostic
bindings succeeded and recovery exactly restored the entry state, but the
phase-start wait incorrectly demanded a new evaluation version with unchanged
parameters. Resource-only phase changes now accept an already-consumed full
parameter tuple; geometry is still checked from captured state. Changed-pose
frames continue using the original fresh-pass requirement. Controls cover tuple
order, stale/missing consumption and a never-entered pass, and ensure the strict
changed-pose wait remains unchanged. This is not yet native pixel acceptance.

R3 exited normally and preserved all 27 frames, but was rejected by the independent
pixel oracle. The mismatches are confined to face 2 at v=0.99614..0.99886 in the
original cube UVs, inside the last half-texel of the 128-high flat-color image;
other faces/poses have zero sampled mismatches. The same 55/53 points fail in
both diagnostic and original gray phases. These observations are preserved under
`cube-render-20261001/r3/edge-alpha-analysis.json`, not accepted as native PASS.

Flat-color rendering now declares `renderUvTransform=[.5,.25,.5,.25]`: native
(u,v)=(.25+.5*sourceU,.25+.5*sourceV). This invertible mapping keeps samples away
from transparent image-border filtering. Original GLB UVs, vertex/triangle
correspondence remain in each source patch; geometry-only mode retains identity
UVs. Only generated uniform-color images use this policy, not arbitrary source
textures. The independent geometry oracle checks original source UVs separately
from the declared native UV transform; no raster/alpha/error thresholds change.

### Prepared whole-import consumer

`SurfaceImport.prepare` freezes the supplied GLB bytes and compiles the existing
closed-convex proof, projection grid and opaque base-color plan without document
access. The current request supports finite positive yaw/pitch limits up to 30
degrees, a fixed 0.05 px ideal interpolation budget and the existing bounded
allocation limits. Source slots and UVs remain in the geometry plan; generated
flat-color inputs use the explicit interior UV transform.

`SurfaceImport.apply` resolves the model inside one authoring callback, rejects
both parameter IDs before any write, creates the axes and grouping Part, creates
and binds all meshes, bakes stored forms, binds shared materials and enables
culling. It returns the runtime's typed transaction result and a typed import
value only on success. It neither changes the canvas nor implements its own
rollback. Package-only stage observations allow native failure injection through
the same importer implementation.

`SurfaceImportTest` verifies the actual consumer through a synthetic SDK, including
all six stage failures, parameter collisions, unavailable transactions and exact
cube plan/result correspondence. Its synthetic service restoration is not proof
of native atomicity. The new `mi3d-import-consumer-v1` frozen inventory contains
80 sources and adds `whole-import-consumer-tests` before the render oracle test;
previous 54/70/73/78-source stages remain accepted. A native full-import probe and
an updated runtime bundle are still required before whole-import acceptance.

### Whole-import native mode

`whole-import` runs `WholeImportProbe` against the frozen authorized cube and the
actual `SurfaceImport` consumer. Each of the six stages is forced to fail once;
all must be reached and return ROLLED_BACK with the exact entry state and history.
The successful attempt must create one history entry, preserve existing content
and canvas, and restore complete mesh/material identities with one Undo/Redo.
The finally path restores the entry state. Stored snapshots are independently
checked against the GLB and full 45×45 tuples by `verify-whole-import.py`.
This mode accepts transaction/stored-state behavior only, not new raster,
persistence, atlas, export or UI evidence.

`mi3d-whole-import-v1` has 83 frozen sources and adds the independent
`whole-import-oracle-tests` command. Its producer is the actual Java importer;
protocol failure mutations and incorrect geometry/UV/binding mutations must be
rejected. No native result is accepted merely because synthetic tests pass.
