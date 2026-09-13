# Geometry — unified queue validation

## Dataset and measurement scope

Circle uses equal-sized circles within each model. Geometry uses model images with different sizes and geometric shapes. Geometry is not a repeat of the uniform-circle workload.

These are fresh-host **single-invocation** acceptance runs under the new manager, not warmed medians or P95 results. The environment is Cubism 5.3.03 / Windows Java 17.0.3.1 / DW-Proton 11.0-9, DISPLAY :0. Historical Circle used GE-Proton and Xvfb; do not pool the two environments or imply a controlled Circle-vs-Geometry comparison.

Timer scope is the native current-page layout entry (including the selected production ingress path), excluding probe snapshots/reflection/geometry/file IO, input construction, and post-return UI updates. Only issued current-page images are considered. Auto scale, rotation enabled, mesh mode, new planner serial. No cross-page optimization.

## Completed acceptance results

Only jobs with structured PASS, exact identity, source/staged hashes, normal exit and bound-cgroup cleanup are accepted here.

| Images | Page | Native ms | New ms | Native/new | Scale native/new | Placed native/new | Samples |
| --- | --- | ---: | ---: | ---: | --- | --- | --- |
| 100 | 4096 × 4096 | 2470.633 | 109.1223 | 22.64× | 1 / 1 | 100 / 100 | 1 per implementation |
| 500 | 8192 × 8192 | 240731.9269 | 336.6122 | 715.16× | 1 / 1 | 500 / 500 | 1 per implementation |
| 1000 | 8192 × 8192 | 1634730.3306 | 404.6034 | 4040.33× | 1 / 1 | 1000 / 1000 | 1 per implementation |

The ratio is a single-run observation, not a statistical performance guarantee. Both runs have input hash `54103bb8af49409d3ae153fdacc82285e19abca0cecb72f9128e621f98d1e21b`, no overflow/overlap/out-of-bounds, finite transforms and matching item/LayerRef transforms.

The 500-image pair has identical input hash `715429961888bf58875e07671b8a4e5d5aebfedd907bdbee56aaf1a35369424d` and the same full-fit geometry/transform checks. Native time is approximately 240.73 seconds versus 0.337 seconds new, not a unit conversion between unequal scopes.

| Case | Job ID | State |
| --- | --- | --- |
| 100 new | `a485a443-d34d-4f17-9cd0-52739bc18438` | PASS |
| 100 native | `fcbb1326-6a56-4b81-9064-a40aac959d32` | PASS |
| 500 new | `d158b9fb-cdf9-4dae-b4ef-c51aecb9fae4` | PASS |
| 500 native | `0146a87e-e4b4-4699-8d9e-1706593ad25e` | PASS |
| 1000 new | `b374c029-afc1-4313-9cc8-890f9a97a52d` | PASS; 404.6034 ms, scale=1, all 1000 placed on 8192² |
| 1000 native | `3d4af397-15cb-4eb4-abc6-4872c5a8ee6c` | PASS; 27 min 14.73 s, scale=1, all 1000 placed |

500/1000 tasks were prepared before any of them was submitted, then queued FIFO. No builds during their measurements. The persistent worker owns execution independently of this Agent's waiter.

The 1000-image pair has identical input hash `a12c36e338adab8bbaa9745e3ba661bb254f69dc926c52603328741becfd10f8`. Both have complete geometry/transform and lifecycle acceptance, including normal exit and bound cgroup `populated=0` without `cgroup.kill`.

## Retained failed driver bring-up runs

- `d12ec128-4f61-4f20-abfb-f7bdf488382b`: auxiliary Agent rejected the run-ID-only fixture filename before layout. Fixed wrapper explicitly supplies the source filename suffix. Offline normalized-request regression reproduced failure then passed.
- `990a0a60-119a-452c-84e5-8109f8e48843`: layout geometry passed (new 113.5254 ms), but driver requested Chinese `取消`; actual native atlas control is `Cancel`. Normal exit was not achieved, so this is **not** an accepted run and is excluded from the comparison table. Both failed attempts retain evidence and have manager-confirmed safe bound-scope cleanup.

The successful driver writes algorithm assertions before native Exit to avoid a JVM-exit/file-write race; the outside manager independently requires normal exit before declaring success.

## Evidence and pending gates

Authoritative job evidence: `~/.local/state/turboism/host-validation/jobs/<job-id>/`. Use manager `status` to resolve the exact task directory and `turboism-home/state/atlas-validation/validation.json`; do not guess the newest directory.

Local transcripts: `build/atlas-main-integration/`. Capability dependency review: `scripts/preview/README-atlas-queue-validation.md`.

The final Geometry2500 one-shot test proceeded after the six preliminary jobs passed. New achieved full manager PASS in 11544.0627 ms, all 2500 placed on 8192² at scale0.97265625. **Native remained incomplete after more than 2 hours and was cancelled by explicit user request.** Manager-confirmed task-bound cgroup cleanup is safe, but native normal exit/validation PASS and completed timing/final scale are unavailable. No speedup ratio or repeat run. See [the final one-shot ledger](HOST-GEOMETRY-2500-ONCE.md) for immutable IDs and cancellation evidence. The automatic notification heartbeat has been removed. The separate overflow/Undo investigation remains open and is not covered by this matrix.
