# Circle 500: Dense Current Page, Region Parallelism, and Fixed Scale

In an isolated host this batch changed the current page of a model copy from 4096² to 1024² through the native "change texture size…" command and still laid out only the 500 images the native caller passed for the current page. The environment, core JAR identity, and isolation rules are in [HOST-AB.md](HOST-AB.md).

**The functional verification passed, but this is not a new official performance baseline.** The automatic-scale serial and parallel batches ran 8 times each (4 native, 4 new); each fixed-scale comparison used a new process and the original copy with a single call. The sample size, warm-up, and switching distribution do not support updating the 160-run baseline table in the README from this batch.

## Automatic Scale Really Shrank and Placed Everything

margin=3, mesh mode, rotation allowed, automatic scale with requestedScale=0.

| Implementation | Placed / overflow | Final scale |
|---|---|---:|
| Native | 500 / 0 | 0.3450134771 |
| New, parallel=false | 500 / 0 | 0.3826196053 |
| New, parallel=true | 500 / 0 | 0.3826196053 |

The linear scale the new implementation retains is about **10.90%** higher than native, so it does not buy speed with a smaller scale. The margin heuristic of native and of the new implementation differ, so this cannot claim pixel-identical quality and cannot divide this batch's times into an equal-quality speedup.

- Every input hash matched across both batches (each round started with a native Undo); the native output hash was stable, and the serial and parallel output hashes of the new implementation were identical.
- Every geometry check passed: finite matrix, no overlap, inside the page, and item consistent with the visible LayerRef.
- A null initial item transform is the normal state; the validation probe records that fact and never disguises it as an identity matrix.

## Parallelism Is Not Only a UI Checkbox

The validation probe observed the real third argument of the production `CurrentPageTextureAtlasPlanner.plan(..., boolean)` and recorded the thread and call count of `bestPacking`; it replaced no production algorithm.

- Serial runs always observed `plannerParallel=false`, one `bestPacking` call, on the host calling thread.
- Parallel runs always observed `plannerParallel=true`, 43 `bestPacking` calls, really using `ForkJoinPool.commonPool-worker-1/2/3` plus the host calling thread.
- This proves that region tasks really execute and that the switch is not inert. The final output matched serial, which means this sample gained no quality.

Retained method times of the four new-implementation runs in run order (ms):

- Serial: 289.3982, 51.2359, 235.2989, 35.7271.
- Parallel: 356.2473, 76.9528, 193.4771, 58.4196.

The sample is small, from different processes, and disturbed by startup and switching, so it is no stable speed ratio. Both consecutive parallel values were higher than the matching serial value; **parallelism must not be advertised as always faster, and the default should stay off**. A later evaluation can weigh partition failure, extra probing, and thread scheduling cost against the model distribution instead of changing the heuristic from this small sample.

## Fixed Scale: No Silent Shrink, Overflow Keeps the Original Transform

Page 1024², margin=3, mesh, fixed 100% (requestedScale=1), rotation forbidden. Both implementations used their own fresh process and the same original copy, and the input hash matched at function entry.

| Implementation | Input | Placed | Overflow | Final scale | Single method time (ms) |
|---|---:|---:|---:|---:|---:|
| Native | 500 | 51 | 449 | 1.0 | 628.1432 |
| New | 500 | 81 | 419 | 1.0 | 81.4713 |

- Both sides really kept scale 1 and did not apply the known legacy behaviour of multiplying by 0.8 after a failure.
- The geometry and item/LayerRef checks passed.
- Every overflow item was additionally compared between input and output: both the item transform and the visible LayerRef transform were unchanged.
- The new implementation returned only the 81 placements of the current page and handed the other 419 instances to native overflow; it searched no next page.
- The placed counts differ and each side ran once, so **no performance speedup is computed**.

## Open Problem That Must Stay Recorded: One Undo After Overflow Does Not Restore the Input

An earlier attempt to reuse one session for the fixed-scale A/B saw the first native call return 449 overflow items; after one native undo, the next call received only 51 inputs instead of 500. The input hash gate stopped the test, and the two unequal inputs were never mixed into one measurement.

This happened after native mode, so it cannot be attributed directly to a regression of the new planner. The bytecode of the native caller `com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.v$a.run()` shows:

- BC 101 calls the layout method;
- BC 105–181 consumes the overflow items one by one;
- BC 160 creates `jp.noids.design.layer.b.s`, BC 168 calls `b(true)`, and BC 173 calls `redo()` directly to remove the layer;
- BC 182–238 updates the list and the view.

The native history boundary and the asynchronous completion timing still need to be separated, so "one undo restores the whole operation that produced overflow" must not be listed as passing. The current fixed-scale verification used an independent original copy, which neither bypassed the hash gate nor changed the host undo or removal flow.

## Evidence and Reproduction

Structured summary: [host-dense-summary.json](host-dense-summary.json). Raw results, UI state, and the agent build matching the source version stay local:

| Scenario | Task directory |
|---|---|
| Automatic serial, 8 runs | `build/texture-ab-500-xrz1dbmf/` |
| Automatic parallel, 8 runs | `build/texture-ab-500-wg863vnn/` |
| Fixed 1, no rotation, new implementation, independent copy | `build/texture-ab-500-jo1yq90c/` |
| Fixed 1, no rotation, native, independent copy | `build/texture-ab-500-hb95zz5s/` |
| Trial rejected by the input hash gate | `build/texture-ab-500-o6p_9si2/` |

```bash
python3 validation/texture-atlas-current-page/run-host-ab.py --layers 500 --rounds 4 --page-size 1024
python3 validation/texture-atlas-current-page/run-host-ab.py --layers 500 --rounds 4 --page-size 1024 --parallel
python3 validation/texture-atlas-current-page/run-host-ab.py --layers 500 --rounds 1 --implementation new --page-size 1024 --fixed-scale-one --no-rotation
python3 validation/texture-atlas-current-page/run-host-ab.py --layers 500 --rounds 1 --implementation native --page-size 1024 --fixed-scale-one --no-rotation
```

Every passing batch stopped its prefix and Xvfb, and the recorded source JAR, BAT, and model hashes plus the unsaved copy hash were unchanged. Neither the production installation nor the source model was modified. The new probe belongs to validation only; the extra planner observation costs a small amount of measurement time and must not be mixed with the phase timings of earlier runs that lacked it.

Still needed: a legacy same-entry comparison, a full save and reopen, multi-page invariance, the history recovery boundary with overflow, and parallel quality and performance verification over a richer size distribution.
