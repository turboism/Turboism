# Model-update and uniform-location experiments

This is **validation tooling, not a normal production plugin**. The exerciser
writes a terminal result and exits its test Editor process. Never install it in
a working user's ordinary Turboism home.

## Offline verification

```bash
bash scripts/test/test_uniform_location_trial.sh
./gradlew --no-watch-fs :runtime:test --tests '*modelupdate*'
./gradlew --no-watch-fs devCheck previewBundle buildModelUpdateSkipHostProbe
```

## Exact-host uniform-location trial

Read the repository's host-validation scheduling runbook and configure the ignored
`.env` with the approved exact host, Proton runner and immutable fixture identity.
The existing runner owns task copies, official-BAT launch, the shared admission
queue and cleanup. Do not run builds concurrently with performance measurement.

Full OFF/ON/ON/OFF, 200 wheel events per leg:

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 uniform-full \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=uniformCache' \
  --result-timeout 1200
```

Both controls use the same forwarding GL wrapper; only ON suppresses eligible
repeated location queries. Model-update skip stays ON, incremental updates OFF.
No GL-call timing, JFR, payload observation or forced GPU completion is enabled.
Capture and parity checks are outside all measured windows.

To verify exact returned values without suppressing any query, add:

```text
--jvm-option '-Dturboism.validation.uniformCacheShadow=true'
```

For a short diagnostic/calibration run add:

```text
--jvm-option '-Dturboism.validation.modelUpdateCalibration=true'
```

Calibration is not full performance acceptance. A separate `modelSkip` factor run
without any GL decorator is the unwrapped-native control; that factor toggles A,
not the uniform cache. Do not compare different factors as if their counters had
the same meaning.

## Correctness and output

The cache is restricted to a single display, GL context and thread. Query results
are admitted only after the application's own GL_NO_ERROR observation. No error
query is added or swallowed. Link, program-binary and delete calls invalidate;
errors/exceptions clear; frame end releases entries. All draws, uniform writes
and buffer writes still execute. There are at most 4096 retained entries.

`wheel-benchmark.txt` reports raw interaction durations, p50/p95/p99, query and draw
counts, and native framebuffer parity. Its latency is wheel dispatch through the
native repaint's EDT barrier, **not physical screen presentation or mouse-to-photon**.

Pixel parity uses real glReadPixels output, excludes pack padding and preserves
the supplied buffer's position. It rejects blank frames, requires different images
at two zoom states, and compares native/native/cached/native at each fixed camera
position. Inverse wheel events only restore the displayed rounded zoom; never use
that as proof that the entire camera transform is bit-identical. Robot screen
capture has been observed to return uniform black in the test compatibility layer
and must not certify this experiment's correctness.

The top-level `performanceAccepted=false` is intentional: a terminal PASS proves
the stated workload/lifecycle assertions, not automatic rollout to every host.
Review OFF/ON results and unwrapped controls independently. A future production
implementation still needs precise host admission, safe-mode/config integration,
shared-context/concurrent program-lifecycle coverage, native Windows validation,
and pan/drag/edit acceptance. Do not ship this reflective test decorator as that
production implementation.
