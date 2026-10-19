# Geometry500: native / Dalsoo / BSSF (2026-10-19)

Cubism 5.3.03; same captured framework, plugins, auxiliary probe and fixture SHA-256; exact input snapshots identical. Fresh host per algorithm, serial queue, automatic scale, rotation enabled (Dalsoo QUARTER), margin 3, mesh mode, page 8192x8192. N=1 each, instrumented wall time; no warmup/repeated statistics.

| Algorithm | Method ms | UI OK to progress close ms | Input probe ms | Scale | Placed |
| --- | ---: | ---: | ---: | ---: | ---: |
| dalsoo | 12196.0070 | 12510.9717 | 18.2980 | 1.0 | 500 |
| native | 192937.9585 | 193160.8592 | 12.2780 | 1.0 | 500 |
| bssf | 201.6938 | 453.1920 | 28.2511 | 1.0 | 500 |

All 500 transforms changed in each run. All finite, bounded, zero AABB overlaps/overflow, matching LayerRef transforms; final manager PASS with identity/fixture/normal-exit/safe-cleanup evidence. Same final scale/full-fit does not establish optimal layout density.

Dalsoo/native method ratio: 15.820x faster. BSSF/Dalsoo method ratio: 60.468x faster. BSSF/Dalsoo UI ratio: 27.606x faster.

Raw UI time includes input probe overhead, excludes host startup and post-close validation. Method timer excludes input/output snapshots. Raw precision and job/prepared identities are retained in [the three samples](host-geometry500-three-way-samples.csv). Task-scoped final status, timing snapshots and logs were retained locally under `.artifacts/dalsoo-diagnosis/host/`; these local files are not required to render the plugin READMEs.

## Input and artifact identity

Fixture: `test-assets/texture-atlas-layout/cmo3/geometry/atlas_mapping_geometry_500.cmo3`.
Dalsoo used the repaired registered polygon dispatch path, Abey kernel, QUARTER rotation,
no locks, serial execution, auto-scale tolerance 0.005 and maximum 12 attempts.
All 500 outlines came from `DRAW_DATA_SHAPES`, with no bounds fallback:
500 rings / 2000 vertices. Do not generalize this sample to complex concave
contours, FREE rotation, dense pages or other hosts. This is a three-algorithm
comparison of the fixed build, not a before/after repair benchmark.

| Input | SHA-256 |
| --- | --- |
| Production agent | `8888a3c679f0cfdc74bfda6e7df897b4bfc5eec58dbfcb8ab6be6917f0f33793` |
| MaxRects-BSSF plugin | `e7f2877568b3e49390ed3ee5faf349180e9346109ad12858aa1a321f01c7c6bf` |
| Dalsoo plugin | `2ad5ce15addafa11818f54f2834c09e0b1f8b537b75695673a1f0cb615791bb8` |
| Auxiliary probe | `2cb09e329518c3bb6e2a56f7e4e762de35d32b70ee5a61ab8d9c14ca40652c51` |
| Geometry500 fixture | `99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b` |
| Actual input snapshot (identical in all three runs) | `715429961888bf58875e07671b8a4e5d5aebfedd907bdbee56aaf1a35369424d` |
| Reviewed Cubism 5.3.03 JAR | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` |

## Acceptance and measurement boundaries

All jobs completed with final manager `succeeded` / `PASS`, identity and artifact
hashes verified, source/copy fixtures unchanged, normal host exit proven and
safe task-bound cgroup cleanup. Each algorithm changed all 500 item transforms.
Geometry checks cover finite affines, page bounds, zero AABB overlap, no overflow
and agreement between item and visible LayerRef transforms.

The method timer excludes input/output reflection and geometry checks. The UI
timer starts at actual settings OK ActionEvent dispatch and ends at the exact
progress window becoming hidden. Its raw wall time includes the input snapshot
and observer setup shown separately above. Output checks/hashes/JSON are deferred
until after progress close. Host/model startup, command polling and simulated
button press delays are excluded; GPU frame completion is not promised.
No uninstrumented estimate, repeated statistics or best-case selection is claimed.

These runs are separate from the historical host matrices; their earlier values
and failures remain unchanged.
