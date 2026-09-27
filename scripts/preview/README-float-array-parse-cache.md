# Native float-array parse reuse

Status: experimental, default off, **not recommended as a load/CPU optimization** on the tested heavy model. Correctness/installation/restoration checks pass, but measured load/CPU benefit has not been established.

## Scope

Only the digest-reviewed Cubism **5.3.02** serializer `com.live2d.serialize.impl.G.a(int,List)` is eligible. Its complete method instruction/branch shape must still match the official artifact, not merely contain a Float.parseFloat call. No global JDK methods, writer code, format, geometry operations or GPU methods are changed.

The cache stores exact immutable numeric strings and values obtained from **Float.parseFloat itself**. Every call allocates a fresh float array. It retains neither caller lists nor output arrays. Metadata is bounded to 8,192 entries and 262,144 UTF-16 characters; tokens over 128 characters and arrays over 1,048,576 elements use the original method. Unknown list implementations and invalid input also use the native path, including native errors. No approximate parser or shared-array shortcut is used.

## Enable / disable

Startup: `-Dturboism.optimization.floatArrayParseCache=true` (default off). Safe mode and disabled hook `cubism.float-array-parse-cache` reject installation. Setting the JVM property to false makes callbacks fall through and clears numeric keys on the next callback; shutdown clears keys and restores the original bytecode. Starting without installation requires a restart to enable it. This is independent of the image-archive reuse switch.

## Build and verify

```sh
./gradlew --no-daemon --max-workers=1 devCheck checkFloatArrayValidationBundle
./gradlew --no-daemon --max-workers=1 :runtime:test --tests '*FloatArrayParse*Test' \
  :bootstrap:test --tests '*VerifiedFloatArrayParseCacheInstallerTest'
```

The exact artifact test uses the test JVM property `turboism.test.cubism5302Jar`; absent configuration skips that test, while a supplied missing/wrong JAR fails it. Classfile tests are not host-readiness evidence.

## Official host runs

`run-float-array-host-validation.sh <off|on|shadow> [run-label] [generic runner options]` delegates all launch, CoW prefix/home/fixture, identity and cleanup to the existing generic runner. `off` is the actual untouched native parser (no parser transformer installed), `on` enables caching, and `shadow` checks returned native-call arrays against Float.parseFloat in a separate validation run whose timings must not be used as performance results.

```sh
TURBOISM_ENV_FILE=/path/to/operator.env \
  bash scripts/preview/run-float-array-host-validation.sh on trial1 \
  --transport local --fixture-remote /path/to/source.cmo3 --fixture-sha256 <sha256> --dry-run
# Remove --dry-run to run the official isolated Editor. Alternate off/on with fresh labels.
```

The test-only validator uses shared task-window readiness helpers from the image validation source set, but does not enable or run the image optimization/forced image benchmark. It measures agent-entry to stable task-document publication, process CPU in that interval, and the sum of observed thread-allocation maxima (a lower bound, not exact total/peak/retained memory). Allocation observation is identical in off/on runs. GUI presentation latency, disk-cold loading and GPU time are not measured. Contract calls and restoration are outside the timing window. Source and copied fixture hashes must remain unchanged.

Results: `state/float-array/result.properties`, with the load snapshot separately in `load.properties`. The runner collects them under the task's `build/host-validation/float-array-parse-cache/5302/` directory. Record model/artifact identity, repeated results and limitations before recommending the switch for ordinary use.


## Experiment outcome

On the 447 MB user fixture, the first LRU implementation observed 3,571,906 token hits but regressed a paired load from 102.953 to 115.754 seconds; observed allocation also grew (14.8415 to 15.0435 GB). That implementation was replaced by fixed direct slots storing raw float bits, with no cache-node or Float-box allocation on misses.

The fixed-slot pair (`heavy-off-slots-r2-20260905T110135Z-2196008` / `heavy-on-slots-r2-20260905T110345Z-2201316`, under `float-array-parse-cache-5302-`) measured 108.130 / 109.036 seconds, 180.950 / 185.590 process CPU seconds, and 14.8477 / 14.3321 GB observed thread allocation. About 0.52 GB less observed allocation is not sufficient evidence of a useful load/CPU improvement. Keep this candidate off. Texture-upload preparation has a separately measured allocation reduction, but its [actual memory study](README-native-memory-occupancy.md) found higher loading peaks; it is not recommended as a RAM-saving option.

The earlier LRU native shadow run compared 18,860 arrays / 17,002,711 values with zero bit mismatches and no missed arrays before attachment. That is correctness evidence for that implementation, not a performance result or a substitute for the fixed-slot collision/differential tests. Both actual native modes validated array independence and restored the original serializer. No Writer/format or host-version admission was changed.

The fixed-slot implementation was subsequently checked on the actual host: `resume-slots-shadow-r1-20260906T160415Z-446054` compared 18,860 arrays / 17,002,711 values with zero mismatches and no missed arrays. Review then found and regression-tested an in-flight callback/close race; callback admission and parsing now quiesce with close so numeric keys cannot be repopulated after cleanup. Final fixed-slot shadow `cfix-r5-20260907T012436Z-826925` passed the full runner with normal launcher exit after that repair. These untimed shadow runs do not establish new performance benefit.

Retained failures: `resume-slots-close-fixed-r2-20260906T161533Z-459390` passed numerical checks but failed an unreadable-process cleanup check; the later cleanup attempt does not replace that FAIL. `resume-slots-cleanup-fixed-r3-20260907T010651Z-801212` exceeded the host's model-path limit (261 Windows path characters), never opened the task document, and failed. `cfix-r4-20260907T011945Z-820588` passed numerical checks but the runner prematurely signalled the still-exiting launcher after a shutdown log, causing exit 143. Cleanup now distinguishes provably exited processes from live read denials, waits for actual launcher exit, and never treats forced cleanup as validation PASS. Use short run labels; do not work around path failures by increasing readiness timeouts.
