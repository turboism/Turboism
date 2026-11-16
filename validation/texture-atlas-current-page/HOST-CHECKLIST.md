# Native / New Single Page / Legacy Host Acceptance Checklist (Partially Executed)

This document is the complete execution protocol; it does not mean everything passed. The real entry smoke test is in [HOST-SMOKE.md](HOST-SMOKE.md), the 160-run native/new repeated same-boundary comparison is in [HOST-AB.md](HOST-AB.md), and the functional verification of 500 images at a real 1024² scale, ForkJoin parallelism, and fixed-scale overflow is in [HOST-DENSE.md](HOST-DENSE.md). Every test uses an isolated Proton prefix, Xvfb :97, and a model copy. Legacy same-entry A/B, other geometry and multi-page scenarios, full Undo recovery after overflow, and save and reopen still need acceptance.

## Safety Preparation

- Operate only on an isolated copy of a user-specified model and an isolated test installation and configuration. Never overwrite the source model, a shared working directory, an unsaved session, or the production plugin installation.
- Record the SHA-256 of the source model and of the copy; restore from the same copy for each group and never feed the previous group's layout result into the next group.
- Record the exact Cubism version and JAR SHA-256, the Java version, the system and CPU, the plugin and framework build identity, and the SDK v8 gate log.
- Run a test build that contains the current change; never measure the stale JAR of a production installation by mistake. The test environment provides startup, licence, and UI preconditions and bypasses no licence.

## Input Contract

Record per group: the current page width and height, the input ID order of this native invocation with the raw width and height digest or hash, mesh or full-image mode, fixed or automatic scale, rotation, margin, and parallel.

Check only this page: no other page is searched, no page is created, and nothing outside the copy is modified. When two implementations differ in margin or integer rounding semantics, state the difference instead of claiming a pixel-identical condition.

At minimum include:

1. Sparse fixed 1 with everything placed; fixed .5 and above 1.
2. Dense fixed 1 with overflow; all overflow.
3. Automatic scale with rotation and parallel toggled separately.
4. Non-zero, negative, and fractional mesh origins with a non-identity old atlas transform; full-image mode and mesh mode.
5. A multi-texture-page model, operating only on the same current page and verifying that the other pages stay unchanged.

## Execution Branches and Timing

- Rotate the native/new/legacy order inside a group with at least 3 cold and 15 warm calls; keep every result instead of reporting only the fastest.
- Native mode must really call the native function; a call that fell back by accident after a new-mode error never counts as a new-mode measurement.
- The new planning log must report `scope=current-page`; record the input count, placements, overflow, scale, snapshotMs, and planMs.
- The same run must carry the write-back outcome and the final entry result; `status=APPLIED` still requires verifying the canvas and the final model state, because the planning log alone does not prove the effect.
- Never mix phase times: snapshotMs excludes the native projection before the service and applyMs excludes the final UI refresh. Measure end-to-end time separately with consistent start and end boundaries.
- Take this round's events from the current log offset or timestamps without clearing the user's original log, and never mix in a success record from a previous run.

## Correctness Before Speed

For every save, check the placed and overflow native instance counts, the final absolute scale, the rotation flag, the rectangle bounds, gap, and overlap checks, the item and LayerRef transform read-back, and DATA_SCALE consistency.

A fixed scale must never shrink silently; the known legacy shrink after a fixed-scale failure and the variable-candidate defect must be labelled separately, and a wrong or lower-quality result never enters an equal-quality speedup ratio.

Also run undo then redo, cancel and failure recovery, and save copy, close, and reopen, and verify that the other pages stay unchanged. A scenario where the setter keeps rejecting the restore cannot claim a complete transaction restore and must keep all error evidence.

## Result Table

One row per run with the suggested fields:

```text
case,implementation,run,warmup,build,input_hash,page_width,page_height,model_image,requested_scale,rotation,margin,parallel,placed,overflow,final_scale,snapshot_ms,plan_ms,apply_ms,end_to_end_ms,geometry_ok,writeback_ok,entry_branch_verified,undo_redo_ok,save_reopen_ok
```

Compute cold and warm P50 and P95 of `native_time/new_time` or `legacy_time/new_time` only from rows with the same input and settings, a passed correctness check, and comparable quality. Leave a missing measurement empty rather than 0, and report results of different quality side by side instead of mixing them into one speedup ratio.

## Acceptance Conditions

- Prove that the production entry of this test build really takes effect, without a pre-registered fake planner or a fake success that only writes items.
- Handle the current page only, with every geometry, fixed-scale, visual write-back, lifecycle, and copy persistence check passed.
- Cover the agreed representative models, and be at least as fast as a correct legacy result of equal quality; explain the strategy and default for a slower parallel case.
- Without host data the status stays "not accepted", and an offline kernel ratio is never written as a speedup against native.
