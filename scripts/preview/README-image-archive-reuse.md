# Native PNG archive reuse (experimental)

**Status: exact-host controlled validation passed on 2026-09-05; still experimental, not validated for ordinary work.** Two complete isolated official Editor runs demonstrated decode/archive round trips and controlled savings. A 660-second passive initial-load/idle window observed two archive checks but **zero reuse selections**. Do not infer everyday UI, FPS or retained-memory improvements from the forced-cycle results.

## Scope and activation

This experiment targets only the reviewed Cubism Editor 5.3.02 artifact. It does not change model evaluation, GPU uploads, texture layout, Undo, or project persistence.

The startup JVM property is `-Dturboism.optimization.imageArchiveReuse=true`. It defaults to off. Safe mode and the disabled-hook ID `cubism.image-archive-reuse` reject installation; other Editor artifacts are not admitted. Setting the same JVM property to false makes already-installed callbacks inert. Re-enabling from a launch where the hook was never installed requires a restart.

The feature is packaged in the ordinary Turboism agent. Its auxiliary validation agent is separate and excluded from the product. Installation emits `TURBOISM_IMAGE_ARCHIVE_REUSE installation=COMPLETE`; not-admitted and failed installations are explicit.

## Behavior

When the native lazy decoder assigns a decoded image, the callback records weak resource/image/PNG identities and SHA-256 fingerprints of the compressed bytes, dimensions and dense ARGB pixels. It observes the image before the getter returns it to callers. At a later native archive, it checks those identities and content fingerprints again under the native resource monitor.

Only if all checks match does it substitute the existing PNG bytes for the redundant encoder call. The original native output stream, byte-array publication, counters, image flush and disposal logic still run. IO errors remain inside the original native exception handling. The callback never retries encoding into a partially written stream.

Raw pixel mutation without `setUpdated`, compressed-byte mutation, normal invalidation, changed dimensions/representations and missing proofs all fall back to the native encoder. SHA-256 is a probabilistic content check, not a mathematical collision-free equality proof. Concurrent unsynchronized mutation of native pixels is outside the guarantees of both this experiment and the native encoder.

Only the native default color representation with a dense int pixel buffer is eligible. Resources above 4096 x 4096 pixels (total area), encoded payloads above 64 MiB and non-PNG buffers fall back. Metadata is bounded to 1,024 entries and uses weak identities; it does not retain image/pixel/PNG payloads. Fingerprinting uses at most 64 KiB scratch per invocation and occurs at decode/archive, never per frame.

This avoids a particular encoding operation; it does not eliminate all native archive allocations, nor does it promise a reduction in process resident memory or improved FPS. Decode now includes fingerprint overhead, which must be included in any comparison.

## Offline verification and packaging

```sh
./gradlew --no-daemon --max-workers=1 devCheck checkImageArchiveValidationBundle
./gradlew --no-daemon --max-workers=1 :runtime:test \
  --tests '*ImageArchiveReuse*Test' --tests '*PngArchiveReuseCacheTest'
./gradlew --no-daemon --max-workers=1 :bootstrap:test \
  --tests '*VerifiedImageArchiveReuseInstallerTest'
```

The optional actual-JAR transformation test accepts the local test JVM property `turboism.test.cubism5302Jar`; it hashes the supplied artifact and performs only bytecode analysis/transformation, not a host launch. It is not a substitute for real-host evidence.

`buildImageArchiveHostProbe` builds `build/image-archive-host-validation-exerciser.jar`. `checkImageArchiveValidationBundle` checks that the production agent contains the optimization and excludes this auxiliary agent.

## Exact-host validation and current limitations

The feature wrapper is `scripts/preview/run-image-archive-host-validation.sh`; it delegates identity checks, official BAT launch, task-local CoW prefix/home/fixture and cleanup to the generic runner. Configuration comes from the ignored `.env`, or the operator-selected `TURBOISM_ENV_FILE`. When running directly on the Cubism machine, explicitly select `--transport local`: no SSH credentials or network transport are used. SSH remains the default for remote operators; local execution is never an automatic authentication fallback.

```sh
# Build first using the commands above. All commands below run from this worktree.
TURBOISM_ENV_FILE=/path/to/operator.env \
  bash scripts/preview/run-image-archive-host-validation.sh png-r1 --transport local --dry-run
TURBOISM_ENV_FILE=/path/to/operator.env \
  bash scripts/preview/run-image-archive-host-validation.sh png-r1 --transport local
# Optional passive observation BEFORE any validator-driven image access or archive:
TURBOISM_ENV_FILE=/path/to/operator.env \
  bash scripts/preview/run-image-archive-host-validation.sh png-idle --transport local \
  --jvm-option '-Dturboism.validation.imageArchive.naturalIdleSeconds=660' --result-timeout 900
```

This machine runs the reviewed 5.3.02 JAR (`988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21`), bundled Windows Java `17.0.3.1+2-LTS`, through DW-Proton 11.0-9, with Mesa Intel UHD 630 rendering. The fixture source SHA-256 is `57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4`. These are Proton results, not native Windows results.

Complete runs, each using the same production agent with alternating property off/on:

| Run | Real fixture image | Off/on median decode + archive | Off/on median thread allocation | Reuse selections |
| --- | --- | --- | --- | --- |
| `pi-local-r2-20260905T062004Z-1758975` | 1000 x 1000, 8 pairs | 85.214 / 57.761 ms | 20,721,008 / 12,218,472 bytes | 26 including synthetic checks |
| `pi-local-natural-r4-20260905T062725Z-1771260` | 1000 x 1000, 8 pairs | 81.303 / 55.139 ms | 20,721,128 / 12,218,552 bytes | 26 including synthetic checks |

The 1024 x 1024 synthetic image used 12 pairs per run. The real fixture has seven model images; only one satisfied this validator's size selection. Each measured operation includes native decoding and the added fingerprint work as well as archive. The validator actively invokes these operations: **controlled forced-cycle benchmark**, not a native UI interaction benchmark. Allocation is cumulative allocation on the executing thread, not peak/retained memory. CPU timing is coarse on this Windows JVM. Schema 2 retains ordered off/on wall/CPU/allocation samples rather than only sorted aggregates. No official-without-Turboism baseline, GPU timing, upload-byte measurement or whole-application latency comparison exists yet.

Both complete runs verified unmodified pixels, raw-pixel mutation fallback, `setUpdated` invalidation, live disable, decoded-image release, zero callback failures, callback removal and original class-byte restoration. The runner verified unchanged source/fixture and official JAR/BAT hashes and normal launcher exit. Results are local under `build/host-validation/image-archive-reuse/5302/image-archive-reuse-5302-<run>/`; `result/result.properties` is the auxiliary result and `final-hashes.properties` contains the runner identity/exit evidence. The passive window in r4 had 2 archive checks, 0 decode observations and 0 reuse selections; native timers were not altered.

Failure evidence is retained, not relabeled: `pi-local-r1-20260905T061649Z-1753195` produced a passing Windows CRLF result but the runner failed to recognize its exact terminal line. A regression-tested line-ending fix preceded r2. `pi-local-natural-r3-20260905T062530Z-1767117` exited before readiness, with no terminal validation result; its startup failure remains unexplained. The same configuration passed in r4. A crash log inherited from the golden prefix dated August 3 is not evidence of a crash in this run.

Later native workflow validation created an atlas through Ctrl+T, performed native Undo/Redo (atlas counts 0→1→0→1), saved only the task copy, and reopened that saved copy in another official isolated session. Seven source-image and atlas pixel digests matched, and native PNG export/decoder round-trip passed. Runs: `pi-atlas-workflow-r9-20260905T080744Z-1949783` and `pi-atlas-reopen-r12-20260905T082202Z-1971669` (under the same image-archive run prefix). `--workflow` explicitly permits writes to the runner-created copy while still preserving the original source hash. Reopen verification supplies the saved copy/hash plus `turboism.validation.imageArchive.expectedContentDigest`; the validator materializes a lazy atlas through its normal native cached-image consumer before comparison.

Earlier installation now observes initial decoding before Runtime startup, and invalid startup config fails closed. A repeat 660-second window after native atlas creation/Undo/Redo/save (`pi-atlas-natural-r13-20260905T082405Z-1975790`) still recorded 2 archive fallbacks with no old PNG buffers and zero reuse. Its 4 initial proofs covered only 40,000 pixels. A subsequent 447 MB real model loaded successfully (849 images, 6 atlases); its controlled image tests also passed, but no natural archive benefit is claimed. Keep PNG reuse experimental and off. The separate texture-upload preparation is the validated allocation optimization; local runner/probe improvements alone are not native-performance gains.
