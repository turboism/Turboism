# Warp position projection — rejected primary-metric experiment

**Default off; not recommended for ordinary use.** Exact-host numerical equivalence passed, but the measured memory/CPU/GPU tradeoff does not justify enabling this candidate. See the permanent [experiment ledger P01](NATIVE-PERFORMANCE-EXPERIMENTS.md) before any retry. No performance or general authoring-readiness claim follows from correct point values.

## Implementation and boundary

`WarpPositionProjectionBridge/Transformer` and `VerifiedWarpPositionProjectionInstaller` target only the first read-only `getAllPointRef().map(getPos)` block of exact Cubism5.3.02 `CExtendedInterpolationExtension.updateInterpolatedForms_common`. The second reference-based write loop, interpolation math, dirty behavior and subsequent native calls are untouched.

For exact attested `CWarpDeformerForm` instances, the bridge reads the native float positions, verifies the source/array identity, and constructs a new list of new native `GVector2` values through typed MethodHandles. It retains no form, array, list or point reference. Limit: 131,072 points per projection; unknown/oversized/error/closed cases return to the original block. Empty and odd-length array behavior follows the native point-count loop. The complete consumer method and relevant actual loaded getter/helper/constructor shapes must match the pinned official artifact. No Bootstrap ASM dependency was added.

Startup request: `-Dturboism.optimization.warpPositionProjection=true`. JVM17 and exact5.3.02 only, safe-mode and disabled hook `cubism.warp-position-projection` apply. Setting the property false makes callbacks inert. Close removes owned callback slots and verifies original consumer-byte restoration. It is not exposed as a recommended UI option.

## Verification already performed

- Runtime8 focused tests + Bootstrap2, no failures/skips with the configured exact test artifact: float raw bits/fresh outputs, bounds/foreign classes, in-flight close, synthetic transformed execution preserving the second write, native exception fallback, changed-method rejection and unchanged other methods.
- `devCheck`, `checkWarpPositionValidationBundle`, `checkPreviewBundleLayout` and wrapper dry-run passed.
- Untimed official shadow `warp-position-projection-5302-wp-s1-20260907T054216Z-1152727`: 6,930 callbacks, 5,166 eligible projections / **3,784,560 points**, zero raw x/y bit mismatches, zero missed initial calls, zero production failures; 849 images/6 atlases, disable/re-enable, slot cleanup, bytecode restoration, source/official hashes and normal exit passed.
- The shadow checks live native reference/getPos output at each eligible call. It is not a complete independent interactive geometry/Undo/save matrix and its extra work is never used as performance evidence.

## Measured decision

Three interleaved off/on pairs, same model/product/observer, other optimization flags off; simultaneous actual RSS/PSS, process CPU and DRM GPU counters, loading heap/GC trace, and 120-second natural idle. All six runner/sampler results PASS, original artifacts unchanged, normal exit. Performance mode does not install a shadow wrapper or retain representative host objects.

Observed medians off→on:
- loading RSS peak **3.772→4.463 GiB (+18.3%)**;
- final idle RSS **3.046→3.629 GiB (+19.1%)**;
- loading process CPU time **229.15→223.27 seconds**, but overlapping variability and slightly higher normalized CPU utilization;
- render-engine busy **about 0.140→0.131%** over measurable loading intervals; idle0%, not a meaningful GPU optimization;
- secondary observed allocation **14.8536→14.7620 GB**, only about91.6MB/0.62% less, not a reason to accept the primary tradeoff.

Memory/system load varied (MemAvailable minima1.25–2.42GiB); all six process Swap measurements were0. No unique causal explanation is asserted. The read-only portion is only part of the original reference work, and routing/MethodHandle/GC/admission costs can offset savings. The old JFR846MB attribution was sampling weight, not a forecast of this candidate's real savings. Full per-run data, IDs, hashes, possible causes and retry conditions are in the ledger. Do not discard unfavorable trials or repeat unchanged code/model/protocol to seek a favorable run.

## Reproduction (only for a justified new experiment)

```sh
./gradlew --no-daemon --max-workers=1 devCheck checkWarpPositionValidationBundle
# Set turboism.test.cubism5302Jar in the test JVM for explicit artifact coverage.
./gradlew --no-daemon --max-workers=1 :runtime:test --tests '*WarpPositionProjection*Test' \
  :bootstrap:test --tests '*VerifiedWarpPositionProjectionInstallerTest'

TURBOISM_ENV_FILE=/path/to/operator.env \
  bash scripts/preview/run-warp-position-host-validation.sh shadow fresh-short-label \
  --transport local --fixture-remote /path/to/model.cmo3 --fixture-sha256 <source-sha256> --dry-run
# Remove --dry-run for the actual official isolated Editor. Never bypass the common runner.
```

For occupancy/CPU/GPU comparison, use `off`/`on`, stage `measure-task-memory.py`, `host_resource_counters.py` and `host-task-processes.py` under `validation/`, pass the existing `start-task-memory-observer.sh` pre-launch hook, and the shared test options `turboism.validation.textureUpload.memoryIdleSeconds=120` / `turboism.validation.textureUpload.loadingTrace=true`. The historical `texture-upload/memory` state path belongs to the reused observer, not to an enabled texture optimization. Results for this feature are in `state/warp-projection/`.

No merge/push or official-file/model modifications are authorized by this experiment. A retry needs a documented new hypothesis or substantive implementation/input change and the same correctness plus primary-metric checks.
