---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: texture-atlas, packing, auto-layout
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# MaxRects-BSSF Layout Algorithm

> **Official Turboism plugin** · **Status: Preview**

Adds a MaxRects-BSSF automatic layout algorithm to Cubism's texture-atlas workflow.

| Detail | Value |
|---|---|
| Version | `0.1.0` |
| Plugin ID | `dev.turboism.plugin.texture-atlas` |
| Category | `modeling` |
| Tags | texture-atlas, packing, auto-layout |
| Turboism API | `[0.1.0,0.2.0)` |
| Requires Cubism | Yes |
| Interface | `none` |
| License | Project License |

## What it does

- Registers MaxRects-BSSF and native layout choices with the texture-atlas editor.
- The native automatic-layout entry packs only the images issued for the current texture page, with fixed/automatic scale, rotation and optional within-page region parallelism. Unplaced items return to native overflow; other pages are not searched. Explicit complete-atlas SDK requests retain a separate planning path.
- Persists the selected layout mode, algorithm, and parallel-search preference.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires Cubism. Turboism currently admits exact reviewed Editor artifacts `5.2.03`, `5.3.02`, and `5.3.03`; this plugin exposes each host-facing feature only when its declared services and capabilities are available.
- **Interface mode:** `none`.
- **Plugin dependencies:** None declared.

## Install and enable

This official plugin is a **store candidate**, not yet a published store listing. Until marketplace publication, install it through Turboism's official release packaging, then enable it in **Plugin Management**. Disable or uninstall it from the same window when the workflow is not needed.

## How to use

1. Open Cubism's texture-atlas editor and choose the automatic layout workflow.
2. Select MaxRects-BSSF or the native algorithm and choose whether parallel search is enabled.
3. Run automatic layout; the plugin validates the complete plan before applying it.

## Performance records

### Latest: 500-item serial/parallel host matrix, four PASS

| Dataset | Texture size (px) | Mode | Entry (ms) | Full UI (ms) |
| --- | --- | --- | ---: | ---: |
| Circle500 | 4096×4096 | Serial | 354.2930 | 705.4973 |
| Circle500 | 4096×4096 | Parallel | 418.3253 | 911.0772 |
| Geometry500 | 8192×8192 | Serial | 477.5247 | 808.9469 |
| Geometry500 | 8192×8192 | Parallel | 461.0903 | 869.6254 |

N=1 fresh host per case: all500 placed at scale1, no overlap/overflow, paired actual input hashes identical; actual modes, worker threads, normal exit and safe cleanup verified. Geometry parallel entry was about3.4% faster, but UI7.5% slower; Circle parallel was slower on both clocks. Single samples do not prove stable gains; retain serial default. Raw UI includes input-probe cost. Native was not rerun; no new native speedup ratio. Circle uses equal-sized circles; Geometry heterogeneous sizes/shapes. [Raw data, probe cost and full evidence](../../validation/texture-atlas-current-page/HOST-UI-500-PIN-FIXED.md).500 does not cover the <32 preflight; q!=1 remains offline-tested. All100-item and historical tables below are preserved.

### Previous: post-fix 100-item host matrix

**Latest measurement: all four 100-item cases PASS after synchronizing the q verification-record pins.** N=1 fresh host per case; actual new serial/parallel branches verified. Paired input hashes match; all100 placed at scale1, no overlap/overflow, normal exit and safe cleanup verified.

| Dataset | Texture size (px) | Mode | Entry (ms) | Full UI (ms) |
| --- | --- | --- | ---: | ---: |
| Circle100 | 4096×4096 | Serial | 89.2337 | 391.1679 |
| Circle100 | 4096×4096 | Parallel | 235.0498 | 529.3501 |
| Geometry100 | 4096×4096 | Serial | 173.9537 | 455.9776 |
| Geometry100 | 4096×4096 | Parallel | 261.3549 | 559.0466 |

Parallel was slower for these100-item samples; retain serial default rather than generalizing to all sizes. Raw UI time includes input-probe overhead; output validation follows progress close. Native was not rerun: these are not new native speedup measurements.100 items do not cover the <32 preflight; q!=1 defense remains offline-tested only. [Full evidence and CSV](../../validation/texture-atlas-current-page/INGRESS-DIAGNOSIS.md).

**Historical failures retained:** the initial four cases, one unchanged-artifact retry and two diagnostics failed before layout (seven attempts). The q-record update omitted trust-record pin updates; external-session interference was not established as the cause. [Original four failures and CSV](../../validation/texture-atlas-current-page/HOST-UI-100-Q.md) and all historical timings below remain preserved; new PASS results do not overwrite them.

**Latest measurements: prioritize the 500-image UI timing and expanded100/1000/2500 UI tables below. They measure confirm action to progress-window close, with same-run entry timings alongside. Texture dimensions are current-page width×height in pixels, not PSD canvas dimensions. Historical entry-only results are retained separately.**

### Historical Circle entry-only benchmark (retained)

This worktree's preview implementation completed 160 real calls in isolated Cubism 5.3.03 / GE-Proton10-34, Java 17.0.3.1, i7-9750H and Xvfb llvmpipe. Each fixture has 20 calls per implementation: discard the first 4 as warmup and retain 16 (8 after switching algorithms, 8 consecutive). Medians use the same method-entry-to-return boundary:

| Current-page inputs | Texture size (px) | Native ms | MaxRects-BSSF ms | Native / new |
|---:|---|---:|---:|---:|
| 100 | 4096² | 101.49 | 71.76 | **1.41×** |
| 500 | 4096² | 722.79 | 88.33 | **8.18×** |
| 1000 | 8192² | 2287.37 | 107.31 | **21.32×** |
| 2500 | 8192² | 13896.44 | 176.00 | **78.96×** |

Mesh mode, margin=3, automatic scale, rotation allowed, parallel off. Undo restored identical input hashes before each call. Actual branches and geometry checks passed: both implementations placed every image at scale 1 without overlaps or out-of-page bounds, with matching item/visual LayerRef transforms.

**Limits:** These are not end-to-end UI timings or guarantees for all models or legacy. Margin heuristics are not pixel-identical, and these cases did not require shrinking or overflow. Switching overhead and slow samples are retained rather than selecting only the fastest calls; parallelism is not always faster. Dense scaling, parallel, multiple pages and save/reopen still require full acceptance testing.

See the [host A/B report](../../validation/texture-atlas-current-page/HOST-AB.md) for methodology, P95, repeat/switch strata and installation-provenance limits, and the [CSV](../../validation/texture-atlas-current-page/host-ab-samples.csv) for all 160 samples.

### Geometry: heterogeneous image shapes and sizes

Circle contains equal-sized circles within each model; Geometry contains geometric images of different sizes and shapes. The new unified-queue runs below use DW-Proton rather than the historical Circle GE-Proton/Xvfb setup. These are fresh-host single calls, **not warmed medians**; do not pool the datasets/environments.

| Geometry images | Texture size (px) | Native ms | New ms | Single-run ratio | Scale / placed (both) |
| --- | --- | ---: | ---: | ---: | --- |
| 100 | 4096×4096 | 2470.633 | 109.1223 | 22.64× | 1 / 100 |
| 500 | 8192×8192 | 240731.9269 | 336.6122 | 715.16× | 1 / 500 |
| 1000 | 8192×8192 | 1634730.3306 | 404.6034 | 4040.33× | 1 / 1000 |

All 100/500/1000-image pairs passed identical-input, geometry, official identity, unchanged-file, normal-exit and bound-cgroup cleanup checks. Native1000 took 27 min 14.73 s. See the [Geometry queue report](../../validation/texture-atlas-current-page/HOST-GEOMETRY-QUEUE.md) and [six accepted raw samples](../../validation/texture-atlas-current-page/host-geometry-queue-samples.csv).

Final2500: new serial completed its only invocation in **11544.0627 ms**, placing all 2500 images on 8192² at **scale 0.97265625**, with full manager PASS. **Native remained incomplete after more than 2 hours and was cancelled at the user's request.** The manager confirmed safe task-bound cgroup cleanup; this is not normal exit or native validation PASS. No completed native timing/final scale or speedup ratio is available. No retry was performed. Fixed prepared/job identities: [2500 one-shot ledger](../../validation/texture-atlas-current-page/HOST-GEOMETRY-2500-ONCE.md).

Additional [dense-page functional checks](../../validation/texture-atlas-current-page/HOST-DENSE.md): with 500 images on a 1024² page, automatic scale was 0.3826 for new versus 0.3450 native, both placing all images. At fixed 100%, rotation off, new placed 81 versus native 51, returning the remainder as overflow without shrinking. Region tasks were observed on three ForkJoin workers, but parallelism gave no quality benefit and slower consecutive calls in this small sample. These checks do not update the speedup table; incomplete input restoration after one native Undo following overflow remains under investigation.

### 500-image UI timing: confirm action → layout progress-window close

Each Circle500/Geometry500 implementation ran once in a fresh host, on the same DW-Proton/:0 environment. UI timing starts at actual OK action dispatch and ends when the exact layout progress window becomes hidden; it excludes command polling, simulated button press duration and model loading, and does not promise completion of every GPU frame.

| Dataset | Texture size (px) | Native entry ms | New entry ms | Entry speedup | Native UI ms | New UI ms | UI speedup |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Circle500 | 4096×4096 | 1157.4114 | 175.4170 | 6.60× | 1527.4977 | 596.8814 | **2.56×** |
| Geometry500 | 8192×8192 | 267829.4619 | 386.2683 | 693.38× | 268164.2065 | 746.3221 | **359.31×** |

These are **raw instrumented wall times**. Included input snapshot/observer setup costs were 25.7645/42.6029 ms for Circle native/new and 16.0731/17.0149 ms for Geometry native/new. Output geometry validation, hashes and JSON writing were deferred until after window close. No probe-subtracted estimate is presented as an uninstrumented measurement. New-algorithm UI overhead outside the entry was approximately 421/360 ms, so entry speedup is not perceived UI speedup.

All four jobs fully passed host acceptance. Each pair had identical actual input hashes, scale 1.0, all 500 placed, no overlap/out-of-bounds and matching LayerRefs. Single calls are not historical Circle hot medians. See the [UI timing report/job evidence](../../validation/texture-atlas-current-page/HOST-UI-500.md) and [four raw samples](../../validation/texture-atlas-current-page/host-ui-500-samples.csv). No 2500 rerun.

### Subsequently authorized 100/1000/2500 UI matrix

Separate new authorization; one invocation per case, existing500 samples retained. Entry/UI values below are ms; UI includes separately recorded input-probe overhead.

| Dataset/count | Texture size (px) | Native entry ms | New entry ms | Native UI ms | New UI ms | UI speedup |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Circle100 | 4096×4096 | 264.4586 | 88.3663 | 511.3520 | 331.7776 | 1.54× |
| Circle1000 | 8192×8192 | 3979.5575 | 226.9234 | 4378.3728 | 579.8752 | 7.55× |
| Circle2500 | 8192×8192 | 18239.0310 | 375.8108 | 19311.8896 | 903.6158 | 21.37× |
| Geometry100 | 4096×4096 | 2266.4700 | 90.2465 | 2526.1596 | 341.4371 | 7.40× |
| Geometry1000 | 8192×8192 | Incomplete | 711.1450 | 30-minute task hard limit | 1155.4422 | — |
| Geometry2500 | 8192×8192 | Not triggered | 9670.4371 | Pre-layout UI tree timeout | 10371.0697 | — |

10 of12 additional jobs fully passed. Native Geometry1000 reached the1800s task limit including startup, not a completed layout time. Native Geometry2500 failed before layout triggering, not a30-minute layout timeout; its historical >2h cancellation remains unchanged. Both failures were safely cleaned in their bound cgroups; no retry or ratio. Successful pairs have identical input, scale1 and all images placed; new Geometry2500 placed all2500 at0.97265625. See the [expanded report](../../validation/texture-atlas-current-page/HOST-UI-EXPANDED.md) and [12 raw records including failures](../../validation/texture-atlas-current-page/host-ui-expanded-samples.csv).

## Capabilities

| Declared capability | User effect |
|---|---|
| `cubism.texture-atlas.layout` | Registers and applies automatic texture-atlas layout algorithms when the reviewed editor service is available. |

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Read the complete active texture-atlas authoring snapshot. |
| `turboism.cubism.model.write` | `application` | Apply a validated complete texture-atlas layout plan through Editor authoring state. |
| `turboism.config.plugin.read` | `application` | Restore the selected automatic texture-atlas layout mode. |
| `turboism.config.plugin.write` | `application` | Persist the selected automatic texture-atlas layout mode. |

## Privacy and data

### Network

Makes no network connections.

### Local data

Stores layout settings in plugin configuration at `texture-atlas/layout.cfg`. It reads and writes the active texture-atlas authoring state only when the workflow runs.

### Telemetry

No telemetry is sent by this plugin.

Plugin lifecycle and failure records can appear in Turboism's session log and Cubism's host log with the plugin ID attached.

## Status and limitations

Offline follow-up: parallel requests below 32 items try serial packing first and retain regional fallback when incomplete; partition admission remains 16. In 120 synthetic cases, placement count/scale were unchanged; some timings regressed, so this is not a universal speedup claim. Automatic scale remains a bounded heuristic, not a guaranteed maximum. See [offline evidence](../../validation/texture-atlas-current-page/OFFLINE-POLICY.md). Historical host tables predate this change and the q guard; no new host run was performed.

- **Status:** Preview.
- Requires an active texture-atlas editor session and reviewed model read/write services.
- Planning or application failure is reported without applying a partial layout; the native algorithm remains available as a fallback.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Automatic layout choice is missing | Confirm the plugin is enabled and the current host exposes the texture-atlas layout capability. |
| Layout is not applied | Check the Turboism log for packing or validation failure and verify that a texture-atlas session is active. |

## Support and license

- **Project website:** [https://turboism.dev](https://turboism.dev)
- **Publisher:** Turboism Contributors
- **License:** Project License
- **Plugin ID:** `dev.turboism.plugin.texture-atlas`
