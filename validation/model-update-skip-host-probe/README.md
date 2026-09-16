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
errors/exceptions clear; frame end releases entries. In the `uniformCache` factor,
all draws, uniform writes and buffer writes still execute. There are at most 4096
retained entries. The separate `uniformValues` factor below changes only confirmed
redundant scalar/single-matrix writes; it is not enabled by the location factor.

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

## Post-cache diagnostic profiling

Use `uniformCache` with `-Dturboism.validation.modelUpdateJfr=true` for a single
cache-ON JFR leg. The report marks `diagnosticOnly=true`; this leg is not a paired
performance result. GL call decorators, forced GPU completion and model digest
probes remain incompatible with the uniform experiment. Keep them out of net-benefit
measurements. JFR sample frequency is not an exact wall-time percentage.

## Exact uniform-value experiment (not production)

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 values-full \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=uniformValues' \
  --result-timeout 600
```

This ABBA factor keeps the established location cache ON in all four legs. Only
uniform-value suppression is toggled. It supports exact `glUniform1i/1f/2f/4f` and
single `glUniformMatrix4fv` writes, including FloatBuffer and float-array views.
It compares every raw float bit (including matrix tails and signed zero), count,
transpose, program and location. Native program binding and writes must first be
confirmed by an existing GL_NO_ERROR. Attempted replacements revoke the old value
before invocation; errors/exceptions, relink/binary/delete, frame/context/thread
changes invalidate. Unsupported array writes conservatively invalidate the current
program; direct program-uniform/pipeline operations retire the value cache for the
frame. At most 4096 entries of at most 16 words each are retained; this is not a
bound on total JVM memory or transient allocation.

No draws, buffer writes or error queries are removed. This is still a validation
experiment with unverified shared-context writer coverage. It must not be installed
as a normal user plugin. Native framebuffer parity remains mandatory outside timed
legs, and `skippedUniformWrites` must be nonzero to establish that the candidate was
actually exercised. Counter reduction alone is not a speedup claim. Location-shadow
mode is rejected for this factor rather than misrepresented as value-write shadow
validation.

### Full value-trial result (2026-09-16)

Exact-host job `64be3394-20ae-45f6-9201-9689ec0fb69d` completed with identity,
unchanged fixture, normal exit and safe cleanup. The 200-event ABBA means were
76.505973, 76.029425, 74.240440 and 72.072746 ms respectively. Both ON legs
suppressed 2,365,200 of 2,438,600 uniform writes (97.0%) and retained all 508,600
draw calls per leg, with nonuniform native framebuffer parity at two zoom states.
Nevertheless the pooled OFF mean (74.289360 ms) was lower than ON (75.134933 ms).
This implementation has **no demonstrated net performance benefit** and remains
an opt-in validation experiment, not an accepted additional speedup. The previously
validated location-query cache is a different optimization.
