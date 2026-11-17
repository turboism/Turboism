# Cat Interpolation Cache Performance Diagnosis

This directory holds a differential tool that runs in an isolated JVM. The runtime carries candidate wiring that is off by default, and editor drag acceptance and deployment are not complete. Never install the diagnostic agent into a user editor.

Frozen result: `build/model-import-3d-offline/cat-performance-cache-r2-20261003/manifest.json`.
The input is the delivered Cat and the SHA-verified official 5.3.02 library; the tool writes neither the model, the installation directory, nor the main editor.

| Path | Official | Candidate |
| --- | ---: | ---: |
| Cumulative main-thread allocation of a full cache refresh | 8,975,205,840 bytes | 625,092,392 bytes |
| Duration of the same cache refresh | 22.131 s | 20.102 s |
| Cumulative main-thread allocation of setup | 24,590,053,728 bytes (earlier baseline) | 16,227,000,600 bytes |
| Setup duration | 44.614 s (earlier baseline) | 35.480 s |

The times are single-round measurements and are no stable performance promise. Cumulative allocation is not the heap size occupied at one moment. The candidate keeps the official ellipse calculator and form blending and only bypasses per-form point reference creation and reuses temporary vectors. The raw float bits of the coordinates of all 512 grids and 557,568 ordinary and derived forms matched, as did alpha, draw order, and the update-version delta of every form. All 21,504 internal two-dimensional cache calls hit the candidate with no rejection. A wrong model, official library, digest, or candidate miss refuses acceptance, and an unsupported interpolation or form uses the official path.

Raw deserialization still allocates about 7.5 GB, and a 3 GB heap baseline runs out of memory. The 6 GB heap here serves a bounded diagnostic only. Editor mouse dragging, whole-model invalidation propagation, undo history, and other-thread memory are not covered, so no user stall may be called fixed. The existing CMO3 is unchanged; original faces, UVs, ordinary parameter bindings, and the 5 px precision budget are not reduced.

Reproduction command (the output directory must not exist yet):

```sh
python3 -B validation/model-import-3d-user-entry/performance/run-cache-proof.py \
  --lib '/path/to/Cubism/app/lib' \
  --asm '/path/to/asm-9.7.1.jar' \
  --model artifacts/cat-native/cache-compact-r1-20261003/cat-textured-xyz360.cmo3 \
  --out build/model-import-3d-offline/cat-cache-proof-new \
  --optimized-setup
```

Next gate: verify the candidate wiring against the official library and classloader, then cover real dragging and undo in a dedicated editor and compare latency, allocation, and digest recovery for the same mouse operation. Deserialization and plugin-free project performance need separate work.

The actual reflection runtime already passed the same full-Cat differential: `build/model-import-3d-offline/cat-performance-runtime-r1-20261003/manifest.json`. Full-cache allocation went from 8,974,800,056 to 636,102,456 bytes and the time from 22.192 to 19.901 s; every digest bit and update-version delta matched. Use `--production-runtime --optimized-setup` to select that backend. A further five no-write refusals and three official differentials for ordinary, zero, and negative zero are recorded in `cat-performance-runtime-guards-r1-20261003`.

The startup candidate lives in `runtime/.../interpolation` and in bootstrap, and it requires the explicit `-Dturboism.meshInterpolation.enabled=true`; safe mode or a disabled `cubism.mesh.interpolation` still refuses installation. It accepts only the exact official JAR and the original extension class SHA, and an unknown version, an already loaded target, or an unsupported form keeps the official path. The candidate agent, source, and the 8 gate and lifecycle test XML files are frozen in `build/model-import-3d-offline/cat-interpolation-runtime-candidate-r1-20261003`. This is neither a new deliverable Cat project nor proof of a real mouse-response improvement.

## Deterministic Surface Optimization Candidate

The production XYZ direct-vertex fallback already simplifies with a local surface error bound. Cat went from 70,576 to 48,864 faces, and partition slots from 48,497 to 35,397. The existing 5 px total budget covers the surface optimization and the parameter interpolation, with a separate 0.5 texel texture error limit. Frozen evidence: `build/model-import-3d-offline/cat-surface-optimization-r2-20261003/manifest.json`. The older non-simplified project and the cache differential above are unchanged.

An independent check reads the original GLB, verifies the retained endpoint positions and UVs, and takes fixed samples of vertices, triangle centres, and edge midpoints in both directions to compute the nearest triangle distance. It calls no Java merge proof and performs no editor acceptance.

```sh
python3 -B validation/model-import-3d-user-entry/performance/verify-cat-simplification.py \
  --source artifacts/cat-textured/source-r2/cat-textured.glb \
  --candidate build/model-import-3d-offline/cat-surface-optimization-r2-20261003/optimized-geometry.json \
  --output /tmp/cat-surface-check.json
```

The sampling is no proof of a complete surface bound, and even less of rendered pixels or sustained occlusion. A new real save, reopen, and drag still needs a dedicated host profile; the fixed 70,576-face gate of the old Cat driver cannot accept the optimized model.

## Optimized Project Measurement (2026-10-03)

`cat-optimized-cache-r1-20261003/manifest.json` binds the new Cat that passed an independent host save review, SHA `70983ff77d29ebb644f3f2bf5ac5ed5b663199d2433919dcfb795dbecdeeaba5`, 46,775,888 bytes. 512 grids, 35,397 slots, 48,864 faces; the 557,568 ordinary and derived forms still exist.

- Deserialization: 24.219 s with 7,037,428,368 bytes of cumulative main-thread allocation.
- Candidate setup: 41.212 s with 16,054,142,424 bytes.
- Official cache refresh in the same round: 25.252 s with 6,585,516,104 bytes.
- Candidate refresh in the same round: 15.665 s with 473,240,920 bytes.
- The complete digest `ba6aed0f7f545219797f135d0b21ce94fd2db27710657e7640e606dde3993d45` and the update-version delta matched exactly; 21,504 hits and 0 rejections.

A single-round duration and a cumulative allocation cannot pass as peak memory or real mouse performance. Simplification alone does not remove the project load cost. The tool gained `--optimized-save-controller <controller>`: it reruns the native save review and binds the artifact SHA before it allows an optimized model, and the old model and the frozen digest gate stay. The model was not written, and the candidate stays off by default.

## Ellipse Coefficient Precomputation Candidate

`--production-runtime --precompute-ellipse-weights` enables the default-off `turboism.meshInterpolation.coefficients` in the isolated differential tool. It precomputes the angle coefficients without caching or skipping source vertex changes, and it keeps the official two-stage double accumulation with float rounding, the final float blend, and the per-point update flag. The runtime must still satisfy the existing SHA, type, and installation gates.

`EllipseWeightProof` compares 14,400 scalars against the real official library, including boundary, non-uniform, and degenerate angles plus positive and negative zero. Full optimized-Cat differential evidence is in `cat-optimized-coefficient-cache-r1-20261003`: the digest and update-version delta of the 557,568 forms matched, and that round's refresh went from 41.399 s and 6,585,767,480 bytes to 2.120 s and 93,254,848 bytes. That round ran in parallel with an isolated editor, so it is no stable latency or heap-peak conclusion; deserialization was not optimized.

Native drag verification must save the press event with the official `l(MouseEvent)` and update the drag and release with its `a(MouseEvent)` and `b(MouseEvent)`. A bare `m(MouseEvent)` carries no press origin and may treat the absolute end point as a displacement. The reviewer rejects the old v2 carrier, and v3 must additionally satisfy a 20 px displacement consistent with the real displacement and the camera mapping (0.01 px tolerance), a complete source digest undo, and exact baseline and candidate movement coordinates. An in-task event is not equivalent to an OS mouse test.
