# Native PNG archive reuse (experimental)

**Status: implemented and offline-tested, but not real-host validated.** The attempted official-runner launch was blocked by the execution tool. No Cubism process was started for this change, and no real performance gain has been measured. Keep the feature disabled for ordinary work until exact-host validation succeeds.

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

## Pending exact-host validation

The feature wrapper is `scripts/preview/run-image-archive-host-validation.sh`, which delegates all identity checks, official BAT launch, task-local prefix/home/fixture handling and cleanup to the existing generic runner. It uses the standard ignored `.env` configuration; an isolated worktree can use `TURBOISM_ENV_FILE` to point to its operator-selected configuration. `--dry-run` checks setup without starting the host. Do not bypass an execution-tool denial through another launch mechanism.

The validator performs alternating disabled/enabled decode-plus-archive measurements, includes fingerprint cost, checks a synthetic alpha image and up to two images from the active fixture, tests raw pixel mutation and normal invalidation, and checks decoded-image release and pixel round trips. Where supported it measures per-thread CPU time and cumulative temporary allocation, which are not peak or retained memory measurements.

It also disables the feature and verifies original class bytes are restored before attempting a normal task-window close. The runner separately checks process termination and unchanged source/fixture hashes. Results are in the task-local `state/image-archive/result.properties`, with intermediate progress in `progress.properties`.

A PASS result alone checks behavior; measured benefit must also be reviewed, including the added initial decode cost. Until both the actual-host checks and performance review pass, this is an experimental candidate, not a ready-to-enable optimization.
