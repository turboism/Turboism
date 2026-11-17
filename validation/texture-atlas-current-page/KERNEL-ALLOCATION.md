# Dalsoo Candidate Allocation and Cancellation (2026-10-20)

In response to the user report of "progress frozen, memory keeps rising", this round reproduced and fixed the candidate allocation blow-up and the missing cancellation checks inside the standalone Java kernel. **The host scene of the user's real model is not reproduced, and a memory leak of retained objects is not confirmed.** The times and memory in this report are neither the whole Cubism pipeline nor a general performance guarantee for a real complex model.

## Root Cause and Change

- `Bin.cornerLattice` used to materialize `(2N+1)^2` coordinate arrays for every rotation angle. 1000 placed items meant 4,004,001 candidates, and the whole list was allocated even when the next shape obviously could not fit. It now stores only the two coordinate axes and walks their Cartesian product in the original order, so candidate storage drops from O(N²) to O(N) while enumeration stays O(N²).
- The buffered outlines of Dalalah translation and Abey rotation and translation now use search-local reusable arrays. The scoring convex hull still holds its own original outline coordinates, so buffer reuse cannot overwrite a saved best result.
- The in-page uniform grid uses a fixed slot list, which removes integer boxing and hash lookups from the hot query path.
- The point containment test of convex-hull scoring stores only the intersection X coordinate and no longer creates a translated full ring plus a small intersection array. Operation order, boundaries, and float underflow behaviour stay unchanged.
- The search now checks cancellation and thread interruption internally. It used to check mainly at the start of each shape, so the search for one complex shape could not stop in time and the worker interrupt from `shutdownNow()` was ignored. Both new cancellation tests failed before the fix and pass after it.

No candidate order, scoring, scale, or spacing contract changed. No default timeout was added and no search quality was reduced. The cancellation fix covers the cancellation flag passed into the kernel and thread interruption; **it does not claim that the cancel button of the host UI is wired**.

## Same-Input Comparison

Baseline: `c3d454042283e10634793bb6b31246a3d89a4e95`.
OpenJDK 17.0.20, local Linux, single thread, initial heap 32 MiB, maximum heap 512 MiB; new input per cell, 3 warm-up runs and 7 measured runs with the median taken. Timing covers only `PolygonPack.pack`; input construction, signatures, and the independent `Area` overlap and bounds verification sit outside the timing. `allocatedMB` is really in MiB and reads the allocated bytes of the current thread through `ThreadMXBean`; it is **not peak heap, live object size, or host RSS**. One round per version, and a short timing is sensitive to JIT and scheduling.

Page 300², 60 L shapes and 25 stars; the buffered outline is a synthetic outline grown 5% from the centre and is not a general dilation that keeps a fixed pixel spacing for any shape. Fixed scale, overflow allowed. NONE and QUARTER use Dalalah, FREE uses Abey.

| Shape | Rotation | Time before / after (ms) | Allocation before / after (MiB) |
|---|---|---:|---:|
| L shape | NONE | 31.481 / 27.287 | 19.968 / 2.064 |
| L shape | QUARTER | 83.398 / 66.473 | 80.591 / 9.879 |
| L shape | FREE | 37.499 / 34.285 | 46.929 / 13.751 |
| Star | NONE | 27.342 / 23.312 | 13.201 / 0.755 |
| Star | QUARTER | 124.113 / 117.056 | 51.283 / 2.113 |
| Star | FREE | 69.637 / 62.445 | 42.752 / 7.038 |

All six output signatures match the baseline, and the placement count, rotation, and translation are unchanged; temporary allocation fell 70.7%–95.9% and time fell 5.7%–20.3%. These are synthetic kernel samples and cannot be extrapolated into the density, duration, or multithreaded memory ceiling of the screenshot model.

Raw data: [before](kernel-allocation-before.csv), [after](kernel-allocation-after.csv).

## Constrained Heap and Repeated Layout

`--fallback`: 1000 fixed small rectangles plus one large rectangle that does not fit, page 4096², QUARTER, maximum heap 64 MiB. The baseline threw `OutOfMemoryError` in `cornerLattice`; the fix completes, keeps every fixed item, and correctly reports one overflow. The raw duration, allocation, and exit code are in [before](kernel-fallback-before.txt) and [after](kernel-fallback-after.txt). This reproduces the storage complexity; it is not the user model and does not mean every normal scenario runs within 64 MiB.

`--retention`: 60 L shapes, 40 consecutive layouts, releasing the result and requesting a Full GC every 5 runs, maximum heap 64 MiB. Both the baseline and the fix settle at about 1.36 MB of live heap after warm-up with no sustained growth across runs. See [before](kernel-retention-before.csv) and [after](kernel-retention-after.csv). This probe therefore supports "high allocation and a large transient candidate list" and does not support "the whole host leak is proven or excluded". If a real model still stalls, collect host thread stacks plus heap and GC data to localize it further.

## Reproduction

The commands below load no host and need no third-party runtime dependency. Run them from the repository root; the build files clean up automatically:

```sh
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --ref c3d454042283e10634793bb6b31246a3d89a4e95
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --mode fallback --heap-mb 64
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --mode retention --heap-mb 64
```

Adding the same `--ref` to the last two runs the baseline; the baseline fallback is expected to exit with code 1 (OOM).

Verification: all 39 plugin tests and the format check passed, including geometry differencing, the six baseline result signature regressions, the existing planning tests for outline, spacing, and locking, and the cancellation regression.

```sh
./gradlew --offline :plugins:atlas-dalsoo-polygon:test :plugins:atlas-dalsoo-polygon:spotlessJavaCheck
```
