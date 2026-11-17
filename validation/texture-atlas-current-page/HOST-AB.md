# Circle: Real-Entry A/B of the Native and New Single-Page Implementations

## Verified Conclusion

In the local isolated Cubism 5.3.03 / GE-Proton10-34 environment, all four Circle tiers completed **40 real automatic layout calls** each (20 native, 20 new), 160 in total.

Each implementation discards the first 4 runs as warm-up and keeps 16. The table below is the median of the hot samples from the same method entry to return, with 8 consecutive calls and 8 post-switch calls per implementation:

| Current-page inputs | Page size | Native median ms | New median ms | Native / new |
|---:|---|---:|---:|---:|
| 100 | 4096² | 101.49 | 71.76 | **1.41×** |
| 500 | 4096² | 722.79 | 88.33 | **8.18×** |
| 1000 | 8192² | 2287.37 | 107.31 | **21.32×** |
| 2500 | 8192² | 13896.44 | 176.00 | **78.96×** |

**This is a measurement of four concrete samples in this test environment under this call boundary, not a performance guarantee for every model, for a whole UI operation, or for legacy.** Both sides placed everything with no overflow and a final scale of 1; the placement positions and the margin heuristic are not pixel-identical.

## Why the Prettiest Median Is Not Enough

A trial run found a clear extra cost on the first call after the algorithm switch. The cause is not yet attributed; it may involve post-switch initialization or configuration handling, or UI and scheduling activity, and it cannot be declared a defect of one production module.

So that a one-sample difference between the two peak distributions cannot swing the median, the formal batch fixed **8 consecutive calls plus 8 post-switch calls** per implementation. The full record includes the slow cases and the warm-up, and nothing is filtered out by speed.

The subset that keeps using the same algorithm (8 samples per implementation, for reference only and no replacement for the mixed definition above):

| Inputs | Native ms | New ms | Ratio |
|---:|---:|---:|---:|
| 100 | 26.69 | 8.12 | 3.29× |
| 500 | 639.55 | 21.40 | 29.88× |
| 1000 | 2183.67 | 39.48 | 55.32× |
| 2500 | 13741.50 | 88.19 | 155.82× |

Mixed hot-sample P95 (nearest rank; with n=16 this is the maximum):

| Inputs | Native ms | New ms |
|---:|---:|---:|
| 100 | 182.64 | 176.78 |
| 500 | 945.12 | 176.50 |
| 1000 | 2752.28 | 227.35 |
| 2500 | 22256.79 | 910.76 |

This shows that a few milliseconds of plan log from the new algorithm cannot be turned directly into a UI response latency: the post-switch tail latency is still visible on small samples, and the large samples also hold slow cases.

## Input and Correctness Checks

- The run used the native automatic layout dialog: mesh mode, margin=3, automatic scale, rotation allowed, with parallel off for the new implementation.
- The native input count came from the real invocation, never from the PSD layer count.
- Before each next round the run called the texture editor's own undo instead of reusing the previous laid-out state.
- **All 40 input hashes per tier were identical**: page size, native settings, DATA_SCALE, input order, original rects, item transforms (null on the normal entry), and the matching visible LayerRef transforms.
- The native selection had to observe the production ingress returning false and then returning normally; the new selection had to observe handled=true and the whole exact method returning true.
- Every run checked that all placed matrices are finite, that the transformed rectangles stay inside the page and do not overlap, and that an item differs from its visible LayerRef matrix by less than 1e-6.
- **The 20 output hashes per implementation and tier were also identical.** The two implementations differ from each other, which is expected, and no position equality was required.
- Every result had scale=1 and placed everything. No run bought speed with a silent shrink or by placing fewer images.
- At the end of every task the model copy still matched the source hash (nothing was saved this round), the recorded source model and original installation JAR and BAT hashes were unchanged, and the test processes and the dedicated display were cleaned up.

The checks cover only the items, LayerRefs, and page data visible through this entry point; they are not a full-state verification of other pages of the model, the undo history, or save and reopen.

### Margin Semantics Difference Kept

Computed from the transformed original rectangles, the minimum axial neighbour gap and the minimum page margin are below (px, computed outside the timing interval):

| Inputs | Native gap / margin | New gap / margin |
|---:|---:|---:|
| 100 | 2 / 9 | 6 / 3 |
| 500 | 6 / 11 | 6 / 3 |
| 1000 | 4 / 10 | 6 / 3 |
| 2500 | 6 / 11 | 6 / 3 |

Both sides used the same margin=3 UI parameter, but the native buffer heuristic is not the fixed 3 px integer margin per side of the new implementation, so "pixel-identical quality" or "identical positions" must not be advertised. The comparable goal of this batch is limited to the same input, the same page, scale 1 with everything placed, and rectangle overlap and bounds checks. A dense automatic-scale case must compare quality again, and the speedups above cannot be extrapolated to it.

## Timing Boundary and Probe

After the production transformer registers, the verification-only `HostTimingProbe.java` inserts one outer timer on the exact method

```text
com/live2d/cubism/doc/modeling/ui/atlasEditor/a/c.a
(Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/a/c$c;)Z
```

The input reflection snapshot happens before the timer starts; on return the probe reads nanoTime first and only then runs the output snapshot, the geometry checks, the hashing, and the write. Both native and new cover the computation and bridging from that method entry to return. Both sides carry the same probe cost, but the external snapshots may change cache and GC state; this is an A/B inside a probed environment, not a zero-disturbance profiler conclusion.

A delegating wrapper over the existing production ingress Predicate records handled or native without replacing the planner, bypassing the production registration, or reimplementing the native algorithm. An error, an unknown branch, a differing input hash, or a missing result refuses a PASS.

This boundary **excludes the native input construction before the call and the texture generation and full UI refresh after the return**. It cannot be compared with `planMs` or with the time from mouse click to screen refresh. Each tier used one separate JVM start; three independent cold-process baselines were not completed.

The validation UI agent, the timing agent, and the Python controller all live in validation and enter no production Gradle artifact.

## Environment, Evidence, and Reproduction

The environment and installation provenance limits follow [HOST-SMOKE.md](HOST-SMOKE.md): Intel i7-9750H, local Wine and Proton, Java 17.0.3.1, Xvfb :97 with llvmpipe, an isolated CoW prefix, and a model copy. The core JAR SHA-256 is pinned to `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166`; the whole existing installation is not certified as a clean official distribution.

Formal batches (raw evidence in local build directories only; no model or connection metadata committed):

- `build/texture-ab-100-1zsyf9ck/`
- `build/texture-ab-500-ixu8qybo/`
- `build/texture-ab-1000-v7xn3b3o/`
- `build/texture-ab-2500-afvp_if1/`

The `evidence/` directory of each holds `timing-001..040.json`, the dialog snapshot, the source and build hashes, the samples, the PASS state, and the cleanup record. `home/logs/runtime/` keeps the production logs.

Reviewable summaries in the repository:

- [host-ab-samples.csv](host-ab-samples.csv): all 160 rows with the warm-up flag, the switch type, the raw evidence SHA-256, and the input and output hashes.
- [host-ab-summary.json](host-ab-summary.json): n, median, P95, and ratio per group.
- `summarize-host-ab.py` re-reads the 40 raw results and verifies the branch, the hashes, the geometry, and the quality before it exports a summary.

```bash
./gradlew :bootstrap:jar :plugins:atlas-maxrects-bssf:jar :plugins:mcp:jar
python3 validation/texture-atlas-current-page/run-host-ab.py --layers 100 --rounds 20
# Repeat with 500, 1000, and 2500; the runs must be serial (own :97 and refuse an existing Wine session).
python3 validation/texture-atlas-current-page/summarize-host-ab.py \
  build/texture-ab-100-1zsyf9ck build/texture-ab-500-ixu8qybo \
  build/texture-ab-1000-v7xn3b3o build/texture-ab-2500-afvp_if1
```

Earlier trials stay out of the formal batch: the first timing probe mishandled the normal null item transform and was fixed; another 2500 run stopped after run 30 because the undo left the button temporarily disabled, and after the controller gained a bounded wait all four tiers were re-run and passed. The failure evidence stays in its build directory, is not counted as a performance regression, and no failed batch is passed off as a pass.

## Open Items

- A legacy same-entry, same-input, same-quality host comparison; the earlier offline kernel data cannot replace it.
- The complete matrix of dense pages, fixed scale, rotation, and parallel plus multi-page invariance; the later partial functional verification of 500 images at 1024² is in [HOST-DENSE.md](HOST-DENSE.md) and is not mixed into this baseline.
- Save, close, and reopen, and full model state verification.
- The root cause of the switch-related latency above and whether it deserves optimization.

The current evidence therefore confirms that the production code takes effect and that four fully placed Circle scenarios are faster than native on this machine; it cannot confirm that every scenario is no slower than legacy, and it does not declare the complete host acceptance finished.
