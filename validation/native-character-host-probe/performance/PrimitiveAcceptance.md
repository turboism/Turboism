# Public geometry acceptance corpus

The primary correctness gate is now small generated geometry, not a complete
character. Character assets may be an optional performance stress test only.
The generator uses Python standard-library math, PNG encoding and glTF encoding;
no Blender, downloads, private assets, LLM or character-specific preprocessing.

| Fixture | Triangles | What it isolates |
| --- | ---: | --- |
| Cube | 12 | Convex faces, UV orientation, source single-sided culling |
| Octahedron | 8 | Sloped triangles and polyhedral face transitions |
| Three-axis cross-star | 60 | Closed concave surface and internal painter ordering |
| Torus | 768 | Hole preservation and near/far surface self-occlusion |
| Cylinder | 124 | Side/cap boundaries, curved surface and winding |
| Ring and column | 892 | Independent objects with projected overlap |

Each input embeds a 512-square color/checker atlas with numbers 00–63 and direction
arrows. Individual faces/patches use different tiles. Each source mesh has one
standard glTF `Expand` morph (default 0, position delta 10% of its basis). This
also tests native routing for small standard GLBs without custom control extras.
Source object names are display labels, not importer rules. Ring/column solids
are disjoint in world space; their occlusion tests do not rely on interpenetration.
All meshes have outward winding, are closed, and use single-sided opaque unlit
materials. Additional double-sided/alpha fixtures should extend this corpus;
these six do not claim that coverage.

## Generate and run

```bash
python3 validation/native-character-host-probe/performance/create_primitive_fixtures.py /tmp/primitive-fixtures
bash validation/native-character-host-probe/performance/run_primitive_acceptance.sh \
  "$plugin_jar" "$sdk_jar" /tmp/primitive-acceptance
```

Generation checks welded triangle-edge counts (two per undirected edge), rejects
degenerate polygons and writes counts and SHA-256 identities to `manifest.json`.
Repeated generation is byte-identical. Generated binary assets remain outside the
repository; commit the generator rather than opaque binaries.

The runner records JAR hashes and uses the provided production plugin's complete
material/control fitting path, not just the geometry-only planner. It asserts
triangle counts, standard morph control counts, texture presence and native route.
The matrix includes the original 19 poses (yaw 0/±30/±90/±150/±180,
pitch ±30/±90/±180 and four yaw/pitch ±30 combinations), 40 additional
intermediate poses and 24 fractional-angle poses per fixture: **498 cases**.
Fractional angles include 7.49, 22.49 and 157.49 degrees, outside the compiler's
coarse 15-degree and front-view 3.75-degree sampling grids. Both periodic endpoints are
retained. See `geometry-results.csv`, per-pose depth images/CSV and textured source
previews. Exit 1 means a failed geometry screen; do not suppress it in CI.

## Acceptance layers

1. **Input/structure:** no source faces lost, correct winding/culling and UVs,
   texture retained, morph endpoint/default and object grouping preserved; all
   budgets preflighted. No dependence on private source paths or name matching.
2. **Fast depth screen:** with exact source projection, every selected pose must
   retain source coverage and have zero wrong-depth/missing pixels beyond the
   diagnostic's 1e-5 depth epsilon. This catches known representational errors
   cheaply. Every failure remains visible in the report.
3. **Native Cubism:** import the same generated files through the official queued
   runner. Check actual parameter consumption, XYZ, `Expand` endpoint/reset,
   source culling, seams/holes and numbered face visibility at the specified
   poses. Compare native geometry within its declared fitting/interpolation bound;
   check texture/face identity in overlapping interiors, not merely gray silhouettes.
   Verify Undo/Redo, failure rollback and save/fresh-process reopen. A float rank
   simulation cannot establish native equal-rank order or staged integer behavior.
4. **Cost:** measure source preparation, native authoring, controls, caches and
   verification separately for these small fixed inputs. Host startup is a separate
   number. Report repeat distributions before making latency claims. An expensive
   complete-character run is unnecessary for iterating on these defects.

The current offline diagnostic uses exact source positions and continuous-double
bilinear ranks with partition-index ties. Its output is a *screen*, not native
RGBA acceptance or a complete control-combination proof. Source previews are
rendered with the existing software renderer and are inspection aids, not an
independent image oracle. The first corpus run exercises saved defaults only;
`Expand` combinations at rotated poses remain a separate native gate.

## Initial baseline

Local artifacts: `/tmp/native-primitive-acceptance-r1/`.
First run: 114 poses, 108 passed, **6 failed**; no missing pixels. Cross-star has
8100 wrong-depth pixels at yaw ±150, torus 522, ring/column 185. All failures
have pitch 0. Cube/octahedron/cylinder passed this sampled depth screen. This is
not a native visual pass. Dedicated cube native history/control/save and fresh-process reopen runs pass;
evidence is recorded separately in `verification.md`. They do not approve all six
fixtures or native visibility across the full pose matrix.

## Deterministic visibility implementation

Connected supporting-plane patches retain every source triangle, UV and slot mapping.
Single-sided convex patches use source culling. Open patches whose normals leave a
common hemisphere are split spatially to avoid wrapping another component. Double-sided
surfaces use coplanar patches; they are never silently converted to single-sided surfaces.
Budgets can therefore explicitly reject complex inputs rather than drop source faces.

The 5×5 authored geometry grid remains unchanged. A bounded half-space projection solver
compiles actual screen-overlap constraints to integer draw-order keys (0…1000). It samples
the full angular domain every 15 degrees, plus front yaw/pitch ±30 degrees every 3.75 degrees.
This is a sampled approximation, not a proof of continuous-angle or arbitrary-control correctness.
Unresolved constraints refine only the implicated source triangle groups, reusing control fits.
Solver work, constraints, refinements, depth storage and native slots all have explicit limits.

The raster workspace clears only its previous coverage bounds. Regression tests compare
every depth pixel and vote with full clearing, including moving/disappearing coverage.
Fully coincident surfaces retain deterministic source precedence without redundant sampling.
Referenced-slot lower bounds reject oversized save plans before geometric clustering.

Native projection reverses Y. Source winding is retained in planning and compensated once
when authoring Cubism indices. A numbered cube proves the native front surface against the
source depth oracle. Local control grids use binary-rational origins and spans, avoiding
rounded regular-grid inverses; the 0.001 canvas-pixel conversion guard stays unchanged.

## Native pixel comparison

`check_native_primitive_pixels.py SOURCE_PLAN_DIR NATIVE_POSES_DIR ATLAS_PNG OUTPUT_DIR`
uses NumPy and Pillow. `--expected-poses 20` verifies the extended native capture set.
It consumes exported native calculated positions (Canvas coordinates), integer draw orders,
source triangle mapping, and actual Editor pixels. It rasterizes a separate nearest-depth
reference on those evaluated XY coordinates. Bilinear texture sampling models native filtering.
Only stable 3×3 texture interiors are compared (RGB tolerance 20); excluded edge pixels are
reported. This does not certify exact source-nearest sampler reproduction, silhouettes,
subpixel boundaries, all native tie cases, or exact 3D interpolation between keys.
A failed comparison exits 1 and saves marked images. Do not relabel filtering differences
as proved depth errors, or discard them to obtain an unexplained pass.

The host probe supports `turboism.character.primitiveBatch=true` with an explicitly staged
`turboism.character.fixtureDirectory`. Six fresh documents share one official queued host
process. Each input retains separate triangle/control/group/atlas/history/pose/save evidence.
Saving and fresh-process reopen remain separate gates. Generated files and private absolute
paths are not implicit CI inputs.

## Release verification, 2026-10-17

`/tmp/native-primitive-release-verification` records **498/498 PASS**, zero wrong-depth
and missing pixels in the stated source-projection screen. This supersedes the
114-case failure baseline without removing that history. Partition counts are
1/1/6/84/1/85 in corpus order. Single preparation observations were approximately
422/45/2775/3634/60/5103 ms; these are not latency distributions or host startup times.
The full plugin unit suite and Spotless pass. The three Python oracle tests include
same-color incorrect-depth and equal-integer-rank negative cases.

Final production plugin SHA-256:
`2f90c806ae088f8c8350ff92cfd5d760dc34137740d7409c5e63f496dfa67d91`.
Cubism 5.3.04 final batch run `queue-d84b67d3ec814abc9f7a74e3453a333f`
saved cube, octahedron, cross-star, torus and ring/column successfully. Cylinder
encountered the native read-only render instance-refresh guard after Undo/Redo;
a separate final-cylinder run passed. No production render guard was removed.
The probe retries only this exact read-only failure, at most twice, records retries,
and verifies the active document identity. Saving still uses the unchanged fixed
validation file grant; batch saves archive only each newly generated file.

### Separate native geometry, seams and texture metrics

The pixel tool also validates exported native triangle identity/reversed target
winding and simulates the **actual calculated integer draw orders** on native Canvas
vertices. A 3×3 same-partition interior is the explicit stable occlusion domain;
mesh-boundary errors and equal-rank ambiguity remain reported separately. This
cannot infer the real host tie policy. `--gate order-interiors` selects only this
narrow depth gate. The default `--gate all` also requires the original strict RGB
comparison; it is intentionally still failing for the differences below.

The first final-plugin native capture set (20 poses each) has these retained metrics:

| Fixture | Strict RGB poses passed | RGB interior differences | All-pixel depth differences | Stable depth interior differences |
| --- | ---: | ---: | ---: | ---: |
| Cube | 20/20 | 0 | 0 | 0 |
| Octahedron | 20/20 | 0 | 0 | 0 |
| Cross-star | 19/20 | 3 | 0 | 0 |
| Torus | 13/20 | 7 | 25 | 0 |
| Cylinder | 3/20 | 1203 | 0 | 0 |
| Ring/column | 4/20 | 2218 | 18 | 0 |

Ring/column also has one boundary pixel with equal integer orders; no stable-interior
tie remains. These edge/seam and filtering differences are **not zero-error RGBA
acceptance**. Magnified cross-star differences occur by white printed atlas features;
convex single-patch cylinder has zero depth differences but strict texture differences.
The current bilinear oracle does not reproduce every native minification/AA filter.
Do not hide those failures by increasing color tolerance. Reports and marked images:
`/tmp/native-final-orderpixels`, `/tmp/native-final-orderinteriors`.

### Version support

`generate_compatibility_matrix.py --check` and
`check_version_set_completeness.py` pass. The existing packaged generated declaration
remains 20 exact reviewed versions (5.0.00–5.3.04). Availability is derived from the
existing SDK surface; this implementation adds no host-specific API or version bypass.
The recorded native primitive campaign is **5.3.04 only**, not visual proof for all 20.

### Unrigged input routing and final persistence

A second corpus contains the same six numbered-texture solids **without bones,
Morph Targets, weights or control extras**. Generate/run it with:

```bash
TURBOISM_PRIMITIVE_STATIC=true bash validation/native-character-host-probe/performance/run_primitive_acceptance.sh "$plugin_jar" "$sdk_jar" /tmp/primitive-static
```

`/tmp/native-primitive-static-verification-v2` passes **498/498** additional poses,
so the two source-depth screens total **996 cases**. Preparation now checks the
actual production entry, not just a direct planner call. Source controls are optional.
The frozen small closed-convex route remains available only after its strict exact
proof succeeds; other supported static inputs use the sampled native visibility path.
The 300-triangle clustering bound is independent of whether source controls exist.
Without that change the static assembly had 15 wrong-depth pixels at held-out angles;
those failures remain in `/tmp/native-primitive-static-verification`.

All six final morph-bearing CMO3 files passed independent fresh-process reopen:

| Fixture | Successful queue run |
| --- | --- |
| Cube | `queue-ef695d9e397f44ba927f24f9e1c622cd` |
| Octahedron | `queue-816c067a145449628659479cc43c656c` |
| Cross-star | `queue-db8d01b00f204819b15bf9609eb9cf27` |
| Torus | `queue-067dbe2bfee441d6a82f45036e3b4020` |
| Cylinder | `queue-08220f08750e4fb0b336b26108c434f5` |
| Ring/column | `queue-0b07b5f2545a479f9e47aac496717c23` |

Banks, morph digests, parameter groups and atlas bindings match after reopening;
20 native pose captures and rotated control endpoint/reset checks complete on each.
The separate final-cylinder import is `queue-b5d1b21df9c647d7b395facebd5e9834`.
Final saved-file native order reports: `/tmp/native-primitive-final-saved-pixels/summary.json`;
120/120 stable occlusion interiors pass, while strict RGB failures remain visible.

The assembly had a 116805 ms preparation outlier in the accumulated-document batch.
An isolated official-host repeat with stack sampling,
`queue-53db6a20eb994de4b15517f5e71a52e4`, measured 5298 ms preparation and
8709 ms native authoring. The sampler identifies raster voting as the principal
preparation work. The batch slowdown was not reproduced; do not claim its cause
has been proved or include it in a low-latency guarantee. Host startup, Undo/Redo,
20-pose captures and save/reopen campaign time are separate from import latency.

Unrigged native validation also passes on 5.3.04: torus
`queue-20d0b2a6858a45e79346a357138e57ea` and assembly
`queue-7d07c7383d4c495380d16977d272974f`. Each verifies zero source-control
parameters, XYZ, one filename-scoped parameter group, topology, atlas bindings,
Undo/Redo, opacity and save. Their 40 native stable-interior depth checks pass;
reports retain 25/18 boundary depth differences and 7/2217 strict RGB differences.
Together with the six morph-bearing imports this is **160/160 native stable
occlusion-interior pose checks**, not 160 pixel-perfect RGBA passes. The static
campaign saves files; fresh-process reopening is established by the six morph-bearing
campaign above, not separately claimed for these two static saves.

After integrating local main, the full plugin suite is 283 tests, zero failures/errors,
34 explicit external-fixture skips; final route/guard checks and the three Python
negative/positive oracle tests also pass. Generated compatibility and exact-version
set checks pass without modifying admission or adding a host-version bypass.
