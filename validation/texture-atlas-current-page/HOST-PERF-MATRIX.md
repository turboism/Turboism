# HOST-PERF-MATRIX: Three Hosts × Two Datasets × Three Implementations Host Performance Matrix

## Scope Statement

**54 real automatic layout calls** ran in the local isolated queue (GE-Proton10-34, task-specific cgroup, single slot serial): Cubism 5.2.03 / 5.3.02 / 5.3.03 × circle / geometry × 100/500/1000 images × native / MaxRects-BSSF / Dalsoo Polygon, **N=1 per cell**.

This is a single measurement in this test environment under this timing boundary, not a statistical median and not a performance guarantee for every model. Circle (uniform size) and geometry (varying size) are two independent distributions, and neither conclusion may be extrapolated to the other.

## Timing and Memory Definitions

- `uiMs`: from dispatching the ActionEvent of the dialog OK to the progress window `jp.noids.framework.e.a.f` reporting `SHOWING_CHANGED=false`. An instrumented wall clock that includes about 13 ms of input probe overhead (`uiInputProbeMs` is listed separately).
- `methodMs`: the exact duration from entry to return of the layout method itself.
- `heapMB`: peak heap used sampled every 50 ms by `MemoryMXBean` inside the probe.
- `cgMB`: `memory.peak` of the task-specific cgroup, covering the whole Proton and JVM process tree including Cubism itself and the Wine infrastructure. **This is a process-level figure, not algorithm usage.**

Raw data: `host-perf-matrix-samples.csv` (methodMs, heap, cgroup, window-period memory.current peak, GC delta, inputHash, run id) and `host-perf-matrix-summary.json`.

## Correctness Verification

- Every cell reported `placed=count`, `scale=1.0`, no overflow, and finite output inside the page.
- Branch attribution: a native cell reports `branch=native`, and a plugin cell reports `branch=handled` (dalsoo or maxrects really took over, with no silent fallback).
- All 9 runs of one `dataset:count` shared an identical `inputHash`, so the three hosts and three implementations consumed the same fixture.

## Circle (Uniform Circles, 8192² Page)

UI wall clock in ms (OK to progress window close):

| Host | Count | native | MaxRects | Polygon |
|---|---:|---:|---:|---:|
| 5203 | 100 | 252 | 216 | 1,306 |
| 5203 | 500 | 1,004 | 306 | 167,612 |
| 5203 | 1000 | 2,511 | 512 | 1,347,980 |
| 5302 | 100 | 264 | 243 | 1,291 |
| 5302 | 500 | 1,041 | 355 | 164,659 |
| 5302 | 1000 | 2,710 | 477 | 1,392,547 |
| 5303 | 100 | 278 | 228 | 1,290 |
| 5303 | 500 | 941 | 324 | 166,334 |
| 5303 | 1000 | 2,568 | 380 | 1,436,883 |

## Geometry (Varying Sizes, 8192² Page)

| Host | Count | native | MaxRects | Polygon |
|---|---:|---:|---:|---:|
| 5203 | 100 | 2,155 | 273 | 1,337 |
| 5203 | 500 | 193,046 | 446 | 49,671 |
| 5203 | 1000 | 1,228,413 | 652 | 633,211 |
| 5302 | 100 | 1,743 | 288 | 1,402 |
| 5302 | 500 | 192,693 | 472 | 86,713 |
| 5302 | 1000 | 1,243,873 | 556 | 741,601 |
| 5303 | 100 | 1,712 | 245 | 1,138 |
| 5303 | 500 | 189,211 | 568 | 83,171 |
| 5303 | 1000 | 1,258,442 | 734 | 630,819 |

## Peak JVM Heap Used (MB)

| Dataset | Count | native range | MaxRects range | Polygon range |
|---|---:|---:|---:|---:|
| circle | 100 | 329–696 | 253–458 | 487–530 |
| circle | 500 | 562–1,117 | 341–517 | 832–1,318 |
| circle | 1000 | 1,390–1,561 | 631–707 | 1,533–1,673 |
| geometry | 100 | 682–1,135 | 287–424 | 406–516 |
| geometry | 500 | 1,472–1,512 | 659–962 | 1,240–1,541 |
| geometry | 1000 | 1,324–1,697 | 747–1,047 | 1,343–1,475 |

Every cgroup process-tree peak fell between 2.3 and 4.7 GB with no stable difference between the three implementations (Cubism itself plus the Wine base dominates). See the csv for per-cell values.

## Infrastructure Jitter and Retries

5 cells failed on the first attempt and succeeded on one retry, with evidence kept in `~/TurboismValidation/atlas/`:

- `5302 circle-100-polygon`, `5302 geometry-500-new`, `5303 circle-500-native`: the JVM hung during startup (the log stopped at core enable with CPU near 0) and was killed at the 1800 s timeout.
- `5303 geometry-1000-polygon`: the first attempt timed out at 30 minutes (near-zero CPU in the late packing phase) and the retry finished in 2700 s (uiMs=630,819).
- `5303 geometry-1000-native`: the process exited by itself with rc=1 and the retry finished (uiMs=1,258,442).

A timeout or failure never enters a ratio; every cell in the tables above is a measured value from one successful run.

## Conclusions

1. **MaxRects is fastest everywhere**: methodMs stayed at or below 0.5 s in all 18 cells (geometry-1000 only 0.28–0.49 s), and its heap peak was generally the lowest.
2. **Native degrades sharply on geometry**: about 3.2 minutes at 500 and about 20 minutes at 1000, while circle-1000 takes only about 2.3 s. The same algorithm differs about 550× across datasets, which confirms the distribution dependence.
3. **Dalsoo Polygon has a severe scale bottleneck**: about 1.1 s at 100 is usable; circle-500 takes about 2.8 min and geometry-500 about 1.4 min; **circle-1000 takes about 23 min and geometry-1000 about 10.5 min**. On circle-1000 Polygon is about 570× slower than native (1,347 s versus 2.35 s). All three hosts reproduce it, so it is an algorithmic complexity problem rather than environmental noise. It matches the combined complexity of "the host issues FREE = 18 rotation candidates × per-vertex × per-placed-vertex × per-angle contact search", and the root cause is analysed separately.
4. **No significant difference between host versions**; the magnitudes agree, and the heap peak of a slow native case is about 1.3–1.7 GB, well below the ceiling.

## Follow-Up: Kernel Space Index Optimization (Host Re-Measurement After the Matrix)

After the matrix exposed the dalsoo scale bottleneck, `Bin` received two optimizations that **strictly preserve the output**:

- Placed polygons enter a uniform grid by `outBb` (cells of page ÷32), and `isFeasible` queries only the polygons in cells that intersect the candidate bounding box. A skipped polygon would necessarily be rejected by the built-in bounding-box test of `overlapStrict`, so the semantics are unchanged.
- A vertex contact candidate first runs an out-of-page check on the translated bounding box and only then allocates the translated ring and runs the feasibility test (Dalalah, Abey, and corner-lattice paths all changed the same way).

Offline signature verification (rect/lshape/mixed × 300–500 × FREE/QUARTER, including cases that do not fill the page and trigger the lattice fallback): 4 cases kept their placement signature byte for byte and shortened the time by 0.5–2.5×.

Host re-measurement (5.3.03, same fixture, same queue boundary, N=1):

| Cell | uiMs before | uiMs after | Speedup |
|---|---:|---:|---:|
| circle-500-polygon | 166,334 | 31,683 | 5.3× |
| circle-1000-polygon | 1,436,883 | 162,946 | **8.8×** |
| geometry-500-polygon | 83,171 | 13,599 | 6.1× |

All three cells reported placed=count, scale=1.0, and branch=handled. Note that after the optimization the circle-1000 JVM heap peak rose to about 5.4 GB (1.5 GB before), because the higher throughput raises allocation per unit time and with it GC pressure.

**The remaining gap is structural**: candidate enumeration is still O(placed × candidate vertices × placed vertices × rotation steps), and the optimized circle-1000 at 163 s is still about 70× slower than native at 2.3 s. Further speedup needs either a smaller candidate set, which changes placement decisions so the output is no longer bit-identical, or a different kernel strategy. That is a design trade-off and outside this output-preserving optimization.
