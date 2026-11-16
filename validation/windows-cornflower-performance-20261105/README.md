# Windows 5.3.04 Cornflower Large-Model Performance Optimization Host Screening (2026-11-05)

## Purpose and Conclusion

On native Windows x64 Cubism 5.3.04, the 763,166,356-byte desktop fixture, named here only by its SHA-256 `83326ac519dd5e4cad9b39e4c7234410cc62bf0d1f5241364561d6906292b6da`, measured the optimization switches of the current Performance page. The goal was to find the **curated combination that changes no model viewport pixel**, not to turn everything on.

Conclusion:

- **Curated Tier 1 (recommended default, navigation and rendering)**: model update skip + uniform location cache + deferred end-of-frame GL check (Mesa GL thread switch) + upload elision + mesh setup elision + clip mask same-frame deduplication + mask bounding-box scissor clear + extended interpolation incremental setup. In the complete 200-event × 4-leg wheel test, the ON legs of model update skip averaged **231.2/229.0 ms → 73.0/71.6 ms (about 3.2×)**; the adjacent calibration control was about **2.3×**. The model viewport stayed pixel-identical to the baseline (0 differences in the `x>=500` region; the whole-window screenshot hash of the Tier 1 calibration round also equalled the baseline).
- **Editing Tier 2**: Tier 1 + incremental update (including glue affecters). Suited to parameter, deformer, and hierarchy editing; on pure camera zoom the incremental update adds cost (about 103.5 ms in this round's wheel test, slower than Tier 1 at 73–86 ms), so it does not enter the default configuration.
- **Do not turn everything on**: the wheel combination with every switch enabled showed **43,582 differing model viewport pixels** (bounding box x=618..933, y=53..698) and was not faster than the curated combination; `warpPositionProjection` failed to install on this host. `meshInterpolation`, `shaderUniform`, and `matrixScratch` kept the model viewport identical when added alone but were slower on the wheel; the older allocation and texture cache switches gave no primary-metric gain.
- **Pipelined canvas readback (asyncReadback)** is not a default recommendation: its pixel self-check agreed, but the canvas lags the UI by one frame, and this round reproduced no stable extra speedup (within noise against off), so a strictly visually consistent scenario should keep it off.

## Environment and Artifacts

- Host: Windows 11 x64, official Cubism Editor 5.3.04 installation, bundled Java 17.0.3.1, Intel UHD Graphics 770 / OpenGL 4.6, i7-13700, 16 GB.
- Model: the desktop source file was copied into a local temporary fixture directory before the test; the source and the copy shared the same SHA-256, and the source stayed unmodified throughout. `run-envelope.properties` records `sourceUnchanged=true`.
- Performance code agent (most rounds): `6016a33b92f887b940776d40b2f9a97a9667ec23b9f196d6425918dd736976c7`. The final complete Tier 1 round used `9f5aa2838f5b4c3f46d88332e7586863a8e0218b6ef5d73a0e59106ea07608e3`; that difference is only the six-language "recommended combination" hint text updated afterwards, and the performance code is unchanged.

## Method

- The wheel workload of the `model-update-skip` exerciser: a real AWT wheel event → native repaint → EDT barrier, 200 events per leg (16 in the calibration rounds), OFF/ON/ON/OFF four legs, with the host restarted every round.
- The canvas is fixed at 1513×1252, and the exact camera raw state is restored before and after the wheel. Each leg uses a Robot to capture a PNG of the canvas parent area and also records the whole-window hash. Cross-round visual comparison uses the model region `x>=500` inside the canvas to exclude an unrelated black floating window that occasionally appears in the left blank margin; the whole-window hash of the Tier 1 calibration round equals the baseline exactly.
- Resource sampling: process CPU, heap used, working set, and GC every 250 ms; no JFR, no forced GC, and no waiting for the GPU.
- The final combination really takes effect through the `launcher` field of `config.json`, not only through `-D` overrides.
- The isolated home shows the Cubism trial welcome dialog at startup, so the test uses the local `LocalDialogAgent` to click "continue" automatically and never mistakes the dialog for a model load timeout. That agent clicks only the explicit continue or trial welcome button and never purchase, activate, or cancel.

## Results

### Complete 200 Events per Leg (Final Comparison)

| Configuration | Mode | Leg mean ms | p50 ms | p95 ms | Model viewport pixels |
|---|---|---:|---:|---:|---|
| Baseline (only the modelUpdateSkip leg switch allowed) | OFF | 324.8 / 269.3 | 308.1 / 267.8 | 442.7 / 301.6 | baseline |
| Baseline | ON | 231.2 / 229.0 | 230.2 / 230.4 | 258.1 / 256.4 | baseline |
| Tier 1 (8 curated switches, config.json) | OFF | 100.1 / 106.0 | 99.1 / 99.1 | 111.7 / 151.7 | 0 differences from baseline |
| Tier 1 (8 curated switches, config.json) | ON | **73.0 / 71.6** | 72.8 / 71.5 | 78.9 / 75.4 | 0 differences from baseline |

- ON leg against the baseline ON leg: **about 3.17× / 3.20×**; p95 about 258→79 ms and 256→75 ms.
- OFF leg: baseline 324.8/269.3 → Tier 1 100.1/106.0 ms, which shows that the rendering, upload, and mask switches of the curated combination beyond modelUpdateSkip already give a large gain by themselves.
- Resources (sampled in the same round, not precisely attributed): baseline heap used averaged about 4.0 GB and working set about 8.86 GB; Tier 1 heap used averaged about 3.2–3.5 GB and working set about 7.96 GB. CPU (normalized to one core) fell from about 150–155% to about 122–149%, with no counter-evidence of "faster but more CPU". The data is affected by host load and is no general memory promise.

### Calibration Screening Rounds (Faster, Used for Attribution)

| Configuration (added on top of modelUpdateSkip) | ON leg mean ms | Model viewport difference from baseline | Note |
|---|---:|---:|---|
| Baseline (modelUpdateSkip only) | 193.7 / 190.1 | 0 | baseline |
| Uniform location cache + deferred end-of-frame GL | 106.2 / 98.9 | 0 | about 1.9× |
| Above + upload elision + mesh setup elision | 83.3 / 76.1 | 0 | about 2.3× |
| Above + clip mask deduplication + scissor clear | 99.5 / 92.8 | 0 | mask clear pixels saved 96.7% |
| Tier 1 (8 curated switches) | 86.1 / 80.2 | 0; the whole-window hash also equals the baseline | calibration evidence of the recommended combination |
| Tier 1 + the three allocation and texture cache switches | 77.3 / 73.3 | 0 | the three give no primary-metric gain and stay optional |
| Tier 1 + meshInterpolation | 102.6 / 105.2 | 0 | visually identical but slower on the wheel, not recommended |
| Tier 1 + shaderUniform + matrixScratch | 102.5 / 107.1 | 0 | visually identical but slower on the wheel, not recommended |
| Tier 1 + incrementalUpdate + affecters | 103.5 / 103.9 | 0 | slower on pure camera zoom; editing scenarios are evaluated separately |
| Everything on (async, meshInterp, shader, matrix, old caches, and more) | 124.3 / 95.2 | **43,582 differing pixels** | rejected; `warpPositionProjection` failed to install |
| Everything on with async off | 92.8 / 93.6 | **43,582 differing pixels** | shows the difference is not caused by async; the whole combination stays rejected |

Additional notes:

- With only `uniformLocationCache` on and `mesaGlThread` off, the end-of-frame deferred check is unavailable (`uniform-seam-unavailable`), and the uniform cache alone gives no stable gain and may even be slower. Always enable the uniform location cache together with the deferred end-of-frame GL check.
- With only `mesaGlThread` on and the uniform cache off, the deferred check is INACTIVE; on Windows the precondition for enabling it is also lost.
- `clipMaskScissorClear` recorded about 1.626 billion baseline clear pixels per leg in this model's wheel rounds and really cleared about 53 million, a 96.7% saving; this is a very clear fill and bandwidth gain of the curated combination.
- `meshSetupElision` skipped 13,374 of 40,212 buffer refreshes (about 33%) on the modelUpdateSkip ON leg and 0 on the OFF leg, which matches the dependency.
- The `asyncReadback` log reads `pipelined=ACTIVE oneFrameLatency=true`; its own pixel self-check found no mismatch, but this round showed no stable extra throughput and it carries the visual and interaction cost of a one-frame-late image.
- Incremental update kept the model viewport identical on the wheel but was slower than Tier 1; its value lies in parameter, deformer, and hierarchy editing, not in pure camera navigation. The artmesh drag probe was blocked this round by a one-pixel canvas size identity check (expected 1513×1252, actual 1513×1253) and produced no new drag or Undo conclusion; the existing 5.3.04 Undo/Redo pixel acceptance of incremental update is in `validation/incremental-update-host-probe/`.

## Recommended Configuration

### Tier 1 (Default Recommendation)

`validation/windows-cornflower-performance-20261105/tier1-config.json`, core fields:

```json
"launcher": {
  "modelUpdateSkip": true,
  "uniformLocationCache": true,
  "uploadElision": true,
  "meshSetupElision": true,
  "mesaGlThread": true,
  "clipMaskDeduplication": true,
  "clipMaskScissorClear": true,
  "extendedInterpolationIncrementalSetup": true,
  "incrementalUpdate": false,
  "asyncReadback": false,
  "meshInterpolation": false,
  "shaderUniform": false,
  "matrixScratch": false,
  "imageArchiveReuse": false,
  "floatArrayParseCache": false,
  "textureUploadPreparation": false,
  "warpPositionProjection": false,
  "inputPathElision": false,
  "zgc": true,
  "memoryProfile": "system"
}
```

Dependency and combination notes: on Windows `mesaGlThread` installs only the deferred end-of-frame GL check (Mesa multithreading is Linux and Proton only) and requires `uniformLocationCache`; `meshSetupElision` requires `modelUpdateSkip`; `clipMaskScissorClear` depends on `clipMaskDeduplication`. `uploadElision` and `uniformLocationCache` may be enabled together: the mesh content comparison is disabled, but the line overlay split still works.

### Tier 2 (Heavy Editing)

`validation/windows-cornflower-performance-20261105/editing-config.json`: Tier 1 + `incrementalUpdate=true` + `incrementalUpdateAffecters=true`. For sessions dense in parameter, deformer, and hierarchy editing; a pure navigation session should stay on Tier 1.

### Optional and Not Recommended

- `asyncReadback`: enable only when a one-frame-late canvas is acceptable; this round does not recommend it as a default.
- `inputPathElision`: meaningful only for a Wine or Proton input path and gives no gain on native Windows.
- `meshInterpolation`, `shaderUniform`, `matrixScratch`, `imageArchiveReuse`, `floatArrayParseCache`, `textureUploadPreparation`, and `warpPositionProjection` stay out of the default combination. The first three were slower on this round's wheel; the last four give no primary-metric gain, and `warpPositionProjection` failed to install on this host.
- Do not turn everything on: the all-on combination showed model viewport pixel differences and cannot be offered as a "no visual problem" option.

## Limits

- One model, one host version (5.3.04), one complete comparison; no claim of cross-version or all-model generality.
- The wheel metric is "event to native repaint barrier", not a physical display FPS; it does not cover the complete interaction matrix of dragging, parameter editing, and Undo/Redo.
- Host load varies between complete rounds: the complete round showed about 3.2× and the adjacent calibration round about 2.3×; both are clearly better than the baseline, but 3.2× is no stable guarantee.
- The canvas parent screenshot occasionally includes an unrelated floating window on the left; the comparison of the model region `x>=500` and the whole-window hash of the Tier 1 calibration round matching the baseline exactly are the basis of the visual conclusion.
- The performance evidence is local in `build/perf-evidence/<run>/plugin-state/` (`wheel-benchmark.txt`, `window-leg-*.png`, `run-envelope.properties`); `build/` never enters Git. This report and the two config files are in `validation/windows-cornflower-performance-20261105/`.

## Incidental Changes

- The six-language Performance page "recommended combination" hint now states the curated large-model combination, the editing combination, the optional cost of asynchronous readback, and the measured "do not turn everything on" conclusion; the uniform location cache hint no longer claims "do not use together with upload elision".
- `scripts/preview/launch-cubism-host-probe-benchmark.ps1` accepts `-javaagent:` through `-JvmOption` (local dialog helper and test agents).
- `CanvasWheelWorkload` gained the optional `-Dturboism.validation.saveWindowShots=true`, which saves each leg's window screenshot as `window-leg-N.png` for cross-round visual checks.
- No performance optimization implementation changed; the product performance code matches the measured agent above, and only the recommendation text and the validation tooling changed.
