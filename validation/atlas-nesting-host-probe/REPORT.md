# Bitmask Nesting Verification Record

Date: 2026-10-21. Worktree: `poor-crab`. This record separates offline planning time, host cold-start method time, and UI wait time. Nothing is published or installed into production Cubism. The earlier performance data belongs to the v5 artifact; the latest internationalization and multi-version integration is at the end.

## Delivery and Algorithm

- Standalone module `plugins/atlas-bitmask-nesting`, plugin ID `dev.turboism.plugin.texture-atlas-nesting`, algorithm ID `bitmask-nesting`, menu **Bitmask Nesting**.
- Plugin JAR: `build/worktree/poor-crab/atlas-bitmask-nesting/libs/atlas-bitmask-nesting-0.67.1-SNAPSHOT-poor-crab.jar`.
- Final v5 artifact SHA256: `620b0fab321754d50c172fa34fab4c7ebf1e95658a17e4fab47771c994b7097a`; the earlier v3 artifact was `aadc0bae812e817f68ee5f937801d2b8f2b0fcd8ed009c018ac718b41e7b1573`.
- Java 17; API `[0.67.1,1.0.0)`; the production code depends on no other layout plugin. The BSSF module serves only as a test baseline.
- With exact axis-aligned rectangles (including issued 90° multiple rotations) and no fixed obstacle, the plugin uses the standalone rectangle BSSF kernel directly. Irregular input uses a material-local multi-region union, scanline fill, an edge capsule (a normal-expanded rectangle plus an endpoint disc) rasterized directly as a supercover, `long[]` collision, a longest-solid-run and scattered-row-run prescreen, a provably safe skip, and a same-row cache.
- The default grid allows at most 512 cells per side: 8 px per cell on a 4096 page and 16 px per cell on an 8192 page. A coarse candidate that misses falls back to a fine search. The margin is independent of the raster; a host margin of 3 means at least 6 px between parts.
- The default rotation candidates are the 4 right angles of the issued direction plus the minimum-area box direction; a DENSE SDK request adds 30° candidates and allows up to 1024 cells per side. By default the search stops adding angles once the item fits the current height, which bounds the duration.
- A fixed scale never shrinks silently; automatic scale never exceeds 100%, runs at most 10 rounds, and keeps the result that places everything. A fixed or excluded part keeps its issued matrix.

## Verification Method

The host run uses only the unified repository queue `atlas-nesting:5303@VARIANT` on a task-isolated copy of Cubism 5.3.03, and each new host triggers exactly one layout. The capture wrapper calls the really registered production plugin and writes the raw input and the plan; the capture I/O is excluded from `plannerMs` but included in the host method and UI time. The independent verifier depends on neither the SDK nor the packer and checks the raw outlines, the 6 px spacing, the page bounds, and the six-element matrix actually written back.

The queue re-verifies the hashes of the source project, the task copy, the golden launcher, and the host JAR after the task is reclaimed; a passing task also requires a normal exit. A failed task never counts as an algorithm pass. No source project was modified.

The offline replay warms up 5 times and measures 9 times, reporting the median and the range; reading the capture, the independent geometry check, and PNG drawing are untimed. BSSF and nesting run serially in the same process. Offline BSSF uses the bounding rectangle and nesting uses the captured outline; the placement count and the scale must be compared together with the duration.

## Passing v3 Host Cases (the Rectangle Kernel Is Unchanged in v5)

| Input | Page | Parts | Scale | Production plannerMs | Host methodMs | Total UI ms |
|---|---|---:|---:|---:|---:|---:|
| Geometry500 nesting, final frozen build | 8192² | 500/500 | 1 | 185.627 | 434.636 | 812.604 |
| Geometry500 BSSF, separate cold host | 8192² | 500/500 | 1 | not timed separately | 340.714 | 708.297 |

This is a single cold-start observation on two different new hosts, so it cannot claim a stable speed ratio. The Geometry500 input hash matched: `715429961888bf58875e07671b8a4e5d5aebfedd907bdbee56aaf1a35369424d`.

Final-build task: `27ce6639-f9d3-48f7-813b-1d88e4dc77ff`, `queue-84d6776b22af4209a616974692048c8f`, terminal state succeeded, safe, normalExit=true. It captured 500 real host outlines with 2000 vertices; the matrices matched, the outline overlap count was 0, and the spacing check passed. Control BSSF task: `a419a61c-950b-4092-8e23-ef09710eeae9`.

**Sample evidence limit: the host outlines of Geometry100, Geometry500, and Circle500 are all four-vertex rectangles per item. A circular image is not a circular mesh; these cases prove only registration, the rectangle path, and write-back, not irregular host nesting.** The older mask build has separate passing host records for Geometry100, Geometry500, and Circle500, but they do not cover the whole final fast path.

## heavy Diagnostic Record

Source: the `heavy.cmo3` named by the local `TURBOISM_HOST_VALIDATION_FIXTURE_HEAVY`, 447432485 bytes, SHA256 `029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`.

The BSSF task `d7e9e22e-deff-4e13-a8e1-08098b84d15b` passed: 101 items on the current page, 4096², scale 1; methodMs=150.414, UI=849.782 ms. That round did not cap the heap, so maxHeap=16.59 GB, and it cannot be mixed with the later 4 GB rounds as a repeated baseline of the same configuration.

The earlier nesting heavy-v2 and final-v3 tasks exited the host with 1, called no planner, captured no input, and left no exception stack; they never count as an algorithm pass. The bootstrap had finished starting, and the 180-second parameter is only a host class lookup timeout, not the total lifetime of a started host. A heavy-only load-thread and exit record was added; the diagnostic rounds cap the isolated JVM at 4 GB, set readiness to 600 seconds, and keep the algorithm deadline at 120 seconds.

Diagnostic v4 passed: task `ebfa043a-5fb3-4abf-a785-3f19ebef2ed4`, `queue-d4d8ac89f9024bcd8e9b0a3d97fe55b0`, with succeeded, safe, normalExit, fixtureUnchanged, and identityVerified all passing. All 101 real irregular outlines with 6582 vertices were placed at scale 1.0; the raw bounding boxes intersected in 173 pairs and the independently grown outline check found 184 pairs, the real outlines and the 6 px spacing had no conflict, and every host matrix matched the plan. cold planner=3812.327 ms, method=5480.354 ms, UI=6707.421 ms; no GC occurred inside the layout window, heap peak 2.42 GB, maxHeap=4 GB.

Load sampling further localised the cost to the native `TAE__EditLayer_ModelImage.setupEditLayer → GEditableMesh2.updateIndices → triangulation` when the texture editor opens, plus an earlier texture upload. The exit record confirms this run clicked exit normally. It succeeded under the 4 GB cap, but the evidence is not enough to prove the only cause of the earlier exit=1, so no causal claim is made. The JFR after capture shows the main extra cost in `Area(BasicStroke(...))` boundary merging. v5 fills the edge capsule directly and performs no boolean merge of the expanded path, while keeping the shape union, the independent verification, and the exact spacing. The real-outline replay fell from 296.903 ms on v3 with JFR to 72.621 ms on v5 (different configuration and load, so only an optimization clue); BSSF on the same v5 replay took 6.142 ms, both at 101/101 and scale 1. Repeated data without this task's background load is listed separately.

The BSSF control task under the same 4 GB configuration, `b54ab7c5-664d-47b4-8d22-1ad0c37bd8f1` and `queue-d661cb13ad1d4d4e81fd74c9cd25eed0`, reported succeeded/safe/normalExit/fixtureUnchanged/identityVerified; method=116.382 ms, UI=1507.749 ms, and the input hash matched nesting: `ff36324cd350a23aa867dd65c7009b6150d3cec63387461e979aeea7da666f59`. Two separate cold hosts are no stable speed ratio.

v5 host task: `97784dfe-ca88-4d56-a253-68932935ff2e`, `queue-68d5c93a52304676b8bfc773817df002`. The algorithm and the independent geometry check already PASS: 101/101, scale 1, 6582 vertices, 173 intersecting bounding-box pairs, 184 exact spacing checks, zero overlap, matching matrices. planner=854.396 ms, method=2437.585 ms, UI=3157.180 ms; the whole isolated host peaked at 2.30 GB of heap (model, probe, and framework included, not the plugin alone), maxHeap=4 GB, and no GC occurred in the measurement window. The host output hash matched v3 exactly: `856b1d1398ec0e5732ec30d570a5e5c06c85ef63dd4d855318728d9f14da4e47`. The queue finally passed succeeded / safe / normalExit / fixtureUnchanged / identityVerified, and the hashes of the source file, the isolated copy, the golden launcher, and the host JAR were re-verified.

**Conclusion boundary: the rectangle fast path can approach BSSF; a really irregular scenario still costs more and has not fully caught up. A heavy cold UI run takes about 3.16 s against about 1.51 s for BSSF; a warm planning time cannot replace the first UI response.**

Control outline images: `build/nesting-replay/heavy-comparison.png` (the same 4096² page, 101 items, 100% scale). These are offline drawings of the captured raw outlines, not Cubism screenshots; the BSSF image normalizes the material-local origin to its issued bounding box, and nesting uses the planned full affine transform. Both are outline drawings without source texture colour.

Retained inputs: `build/nesting-replay/inputs/heavy.bin` (SHA256 `141e794b1419290455d7846a194343d67e39242245e528cf57c97446a04c8a01`) and `geometry-500.bin` (SHA256 `72349a5a9e0ebd6457e91b7cb5b1cbe0713df1c7aac8da098fed6d4a01b0a457`); both are geometry and hold no source image.

## Final Offline Repeated Benchmark

The host of this task exited normally, and no other build or layout ran in parallel during the measurement. The shared machine may still run other worktree tasks, and no other process was stopped or modified. Everything below is the median of 9 measurements after 5 warm-ups; the raw CSV keeps the minimum and maximum too. Rounds vary by about a factor of two, so these numbers are no stable SLA and cannot be stitched across rounds into a speedup ratio.

| Input | BSSF ms | nesting ms | Placed BSSF / nesting | Scale BSSF / nesting |
|---|---:|---:|---:|---:|
| rectangle 500 / 4096² / fixed | 26.400 | 30.987 | 500 / 500 | 1.000000 / 1.000000 |
| concave 500 / 2048² / fixed | 31.630 | 223.168 | 230 / 449 | 1.000000 / 1.000000 |
| concave 500 / 2048² / auto | 294.068 | 1198.206 | 500 / 500 | 0.747205 / 0.956250 |
| concave 500 / 4096² / fixed | 25.969 | 120.170 | 500 / 500 | 1.000000 / 1.000000 |
| heavy 101 / 4096² / auto | 11.988 | 138.455 | 101 / 101 | 1.000000 / 1.000000 |
| Geometry500 / 8192² / auto | 31.362 | 69.908 | 500 / 500 | 1.000000 / 1.000000 |

The final heavy nesting range was 91.372–243.503 ms and BSSF was 8.077–28.798 ms. On this input nesting still costs about 11.5× the warm kernel time; its benefit is that bounding boxes may intersect, the real outline and spacing stay safe, and the arrangement becomes more compact. The synthetic concave fixed-scale case placed about 95% more parts, and the automatic-scale case raised the linear size by about 28%. These are heuristic results on the specified samples and do not represent every input.

CSV: [full synthetic matrix](synthetic-benchmark.csv), [heavy real outlines](heavy-benchmark.csv), [Geometry500 real input](geometry-500-benchmark.csv). After the new plugin finished, this task's queue held no active item, and the worker it created was stopped gently.

Reproduction (from the repository root, with the inputs kept in `build/nesting-replay/inputs`):

```sh
rtk proxy ./gradlew :plugins:atlas-bitmask-nesting:nestingBenchmark --no-parallel --max-workers=1 --offline
rtk proxy ./gradlew :plugins:atlas-bitmask-nesting:capturedNestingBenchmark -PnestingCapture="$PWD/build/nesting-replay/inputs/heavy.bin" -PnestingOutput="$PWD/build/nesting-replay/heavy-final" --no-parallel --max-workers=1 --offline
```

## Checks and Known Limits

- The 13 plugin tests passed, including U-shaped nesting, multi-region input, thin diagonal edges, locking, fixed spacing, scale, the rectangle fast path, a random collision oracle across the 64-bit boundary, and an independent per-cell coverage oracle of the rounded expansion (with clipping and three right-angle rotations); the runtime QUARTER issued-angle and AABB prescreen tests passed.
- `checkModuleBoundaries checkPluginBoundaries checkPluginPermissionAudit verifyFirstPartyPluginMetadata :plugins:atlas-bitmask-nesting:spotlessCheck` passed.
- The 64 offline assertions of the validation probe and the positive and negative cases of the independent capture check passed, as did the script syntax and `git diff --check`.
- The three-language README check of the new plugin passed. The whole-repository README check still reports the existing duplicate permission declaration of three ACP documents; this round changed none of them.
- Layout covers the current page only; a complete atlas SDK request is explicitly refused. The heuristic is serial and deterministic and guarantees neither optimal density nor maximum scale. The native parallel switch is disabled.
- A missing outline falls back to an explicitly marked rectangle; the SDK conservatively fills closed holes; the outline is never rebuilt from image alpha. A coarse grid loses narrow grooves.
- The development artifact is not published; host support verification is limited to 5.3.03, and the other versions in the manifest were not fully tested this round.

## Internationalization and Multi-Version Integration (v6/v7)

- Current plugin JAR SHA256: `130a23633bd798bf8a1b731c9eb80ca0a03e9d9b0ea43a979b48b9dfcfac72ba`. v7 changes only the verification task to functional mode; the production kernel is unchanged.
- The API floor and the mainline compatibility policy are unified at `[0.67.0,1.0.0)`. The plugin name, the description, the algorithm menu, the rectangle fallback note, and the common layout refusal messages cover English, Japanese, Korean, Simplified Chinese, and Traditional Chinese.
- The version manifest, the three-language README compatibility block, and the whole-repository matrix are generated by `scripts/generate_compatibility_matrix.py`. The manifest covers all 20 reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04); the full outline feature covers the 9 versions of 5.2 and 5.3. When the outline service is unavailable it registers the rectangle planner, which the native rectangle entry point schedules, and never calls the missing outline interface.
- The 18 plugin tests passed (including real resource parsing in five languages, wrong argument substitution, and the rectangle path when the outline service is missing); the runtime layout verification test and `admissionPreflightTest` passed; the 71 offline contract assertions of the probe and the independent capture check passed. The symbols of the timing entry point, the settings dialog, and the transform selectors match all 20 reviewed records; this is static evidence only.
- The host pass is in [compatibility evidence](compatibility-evidence.json): 5.2.03, 5.3.02, and 5.3.03 all used Geometry100 with 100/100 placed, scale 1, 6 px spacing, zero outline overlap, and matching host matrices; every queue task reported succeeded / safe / normalExit / fixtureUnchanged / identityVerified. These inputs are still four-vertex rectangles and prove only the multi-version entry point and the write-back; the real irregular host evidence remains the 5.3.03 heavy run above.
- The host matrix of the first and last patch of each version line is incomplete: only the three hosts above are installed. The 5.2.00 queue found no exactly installed JAR before startup and refused, without starting a host or modifying the source project; a missing installation was not read as plugin incompatibility, and the generated support range was not narrowed.
- The first 5.3.03 round failed after layout because of a progress-window timing order assertion; the compatibility task now uses an explicit `--functional` mode, keeps the original performance mode, and passes on re-run. Functional verification makes no new UI performance comparison.
- Final whole-repository acceptance passed: `rtk proxy env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 xvfb-run -a ./gradlew --no-daemon checkCompletedCommit --max-workers=2`, 13m24s, 384 tasks (83 executed, 67 from cache, 234 up-to-date). It includes the five-language completeness of the official plugins, the READMEs, the metadata, the compatibility matrix, the version registry, the boundaries, the SDK, the format checks, and every subproject test.
- The screenshot test on the default Wayland desktop once hit a PipeWire timeout; that case passed separately on an isolated X11 display, and the full acceptance then passed in the same environment, with no `-x` exclusion and no change to the screenshot implementation. The ACP README problem mentioned in an early report was fixed on the latest mainline, and the whole-repository README check now passes.
- All three hosts exited normally; after every task reached a terminal state, the idle queue worker this task created was stopped gently. Nothing was published or pushed, and no production Cubism installation was modified.
