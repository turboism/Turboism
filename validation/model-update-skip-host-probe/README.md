# Model-update and uniform-location experiments

## Native pan and single-ArtMesh interaction validation

Run only in the task-scoped shared host queue. The production hook is default-on
for an exactly admitted host; no explicit enable flag is needed to prove startup.
The auto-exit exerciser must never be deployed to an ordinary user's plugin home.

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 pan-full \
  --jvm-option '-Dturboism.validation.nativeInteraction=pan' \
  --jvm-option '-Dturboism.validation.resources=true' \
  --ready-marker 'TURBOISM_UNIFORM_LOCATION installation=COMPLETE' \
  --failure-marker 'TURBOISM_UNIFORM_LOCATION installation=FAILED' \
  --result-timeout 1200
```

Use `nativeInteraction=artmesh` for the single-ArtMesh gesture. Add
`-Dturboism.validation.modelUpdateCalibration=true` for a 16-event-per-leg
calibration; the full workload has four 200-event OFF/ON/ON/OFF legs.
Results are in `interaction-benchmark.txt`, not the older wheel report.

Input uses the native AWT listeners on the single verified fixture canvas, never
SDK geometry replacement or a direct camera mutation during measurement. Pan
sends canvas-scoped Space and mouse drag events with active canvas focus; there
is no system-wide Robot input. A single continuous press spans each measured leg.
Each drag event must complete a native display before its EDT barrier. This is
serial input-to-repaint throughput, not monitor presentation FPS or a claim about
unbounded input-queue behavior. Press/release and target acquisition are separately
accounted and excluded from the measured drag window.

ArtMesh acquisition performs bounded native clicks and requires exactly one
editable ArtMesh with a current keyform. A projected triangle centroid can be
transparent; geometry coverage alone is not proof of a native picking hit. The
chosen target and acquisition attempts are recorded. Calibration initially
exposed a rectangle-selection action masquerading as dragging, which is rejected.

Geometry snapshots cover source positions, all source keyforms, interpolated
positions and calculated canvas positions. Moving an ArtMesh requires all its
vertices to move and exactly that mesh to change; other meshes and the camera
must remain unchanged. Native Undo/Redo/Undo must exactly replay the complete
geometry, preserving prior meaningful authoring edits. Native selection-only
history compaction is allowed; raw Undo cursor +1 is not assumed. The native
modified-after-saving flag is never cleared by the probe: an ON leg must match
the OFF-observed flag behavior after Undo. The original file is never written.

Pan must move the camera with every mesh, document-modified flag and Undo state
unchanged; its original camera values are restored outside measurement. Pixel
parity at both moved and restored states compares native/native-repeat/cache-on/
native at a fixed state, rejecting blank images. In-flight mouse/key state is
released in finally even on failure. Profiled runs are excluded from this driver.

5.3.03 calibration jobs: native pan `9983bb26-b2fc-4488-9e20-df309cccd58a` and
native ArtMesh `b0a02b66-8c77-4651-97ed-c4b7dbf9f702` completed with geometry,
pixel, identity/source, normal-exit and supervisor cleanup PASS. These short
calibrations do not substitute for the full measurements or other-host validation.


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

## Resource-accounted symmetric comparison

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 resource-suite \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=uniformSuite' \
  --jvm-option '-Dturboism.validation.resources=true' \
  --result-timeout 1200
```

The six legs are native, location cache, location+value cache, location+value cache,
location cache, native, with 200 wheel interactions per leg. A remains ON and B OFF.
All legs use the same forwarding wrapper; only the stated caches vary. The current
value cache compares directly against input views on hits, allocating a new stored
payload only on misses. This preserves full raw-bit comparison and does not itself
establish any additional whole-frame speedup.

`completedDisplayFrames / elapsedNanos` is completed native display throughput,
not physical presentation FPS. The completion counter advances only at display
return, not when a repaint is merely requested. Latency percentiles use all raw
observations, not averages of per-leg percentiles.

Resource observation is opt-in and runs on its own thread at 250 ms intervals,
plus boundary samples. It never forces GC, logs per frame, reads other processes,
or runs concurrently with builds. CPU deltas include all JVM process threads;
100% in the one-core scale means one fully occupied logical CPU. Machine percentage
divides by available processors; also compare CPU milliseconds per completed frame.
Heap/direct-buffer samples come from MXBeans; process working set/private commit
come from the existing host JNA plus public PSAPI on Windows/Proton. No extra native
library is downloaded. Working set, Java heap, private commit and GPU VRAM are not
interchangeable; absent counters remain -1. Peaks are sampled window peaks, not
continuous lifetime peaks. EDT allocation measures bytes allocated during the
window, not retained heap. GC collection time is not necessarily total pause time.
Sampler overhead is recorded and must not be subtracted to manufacture a speedup.

Summarize a completed suite without modifying its evidence:

```bash
python3 scripts/preview/summarize-uniform-benchmark.py PATH_TO_WHEEL_BENCHMARK.txt
python3 scripts/test/test_uniform_benchmark_summary.py
```

The summarizer rejects incomplete suites, inconsistent sample counts, native errors
and absent parity. Queue identity, source hashes, normal exit and cleanup still
require independent review of the matching terminal job result.

### Resource suite result (2026-09-16)

Job `41dae5d9-551c-423d-8ca0-f1d21dc24b0f` passed terminal identity, unchanged
fixture, normal exit and cleanup checks. Six legs completed 200 interactions each;
each configuration retained 1,017,200 draws across its two legs. Pooled raw data:

| Variant | Mean ms | p95 ms | Completed displays/s | One-core CPU % | Mean working set MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Neither uniform cache | 1092.250223 | 1146.8947 | 0.915131 | 101.4420 | 3753.028886 |
| Location cache | 226.160257 | 589.1890 | 4.403323 | 51.5515 | 2983.499392 |
| Location + value cache | 81.969020 | 98.6930 | 12.153828 | 103.6370 | 3321.921699 |

The combined experiment is 13.325159x lower mean latency and 13.280972x higher
completed-display throughput than the uncached controls in THIS run. This does not
establish an incremental benefit for value suppression. The first location-only
leg had long stalls (377.161559 ms mean, 3.6411212 s max); the later identical leg
was 75.158955 ms. Both had the expected 7800 native queries, zero cache/GL failures
and unchanged draw counts. The slow leg recorded only 172 ms of GC collection
time, insufficient to explain its extra wall time. Its samples are retained, not
filtered. A later host snapshot showed high swap occupancy, but there was no
contemporaneous swap-in/out trace; the stall's root cause is not established.

The earlier full value-only ABBA was a no-benefit result, and the later location
leg here is faster than either combined leg. Keep value suppression opt-in and do
not multiply speedups or market the apparent pooled difference as its benefit.
Sampled heap/working-set differences are affected by GC and test ordering; they
are not a demonstrated persistent memory reduction. PSAPI private-commit returned
zero in this compatibility environment and is not used as valid memory evidence.
Native Windows, real presentation FPS, GPU VRAM, pan and authoring drag remain
outside this acceptance. The standalone allocation regression passed with bounded
per-hit allocation; that micro-check is not a whole-frame speedup.

## Production narrow-hook validation (no GL proxy)

Build the production preview and this exerciser, then run through the shared queue:

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 narrow-full \
  --jvm-option '-Dturboism.optimization.uniformLocationCache=true' \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=uniformHook' \
  --jvm-option '-Dturboism.validation.resources=true' \
  --ready-marker 'TURBOISM_UNIFORM_LOCATION installation=COMPLETE' \
  --failure-marker 'TURBOISM_UNIFORM_LOCATION installation=FAILED' \
  --failure-marker 'TURBOISM_UNIFORM_LOCATION restoration=FAILED' \
  --result-timeout 1200
```

`uniformHook` never installs the old all-GL decorator, including its OFF control.
The runtime is installed before the test; its opt-in switch alone changes between
OFF/ON/ON/OFF legs. A remains ON, B OFF, value suppression absent. Query counters
come from the real hook, completed displays from an appended lightweight listener.
Both controls include the same installed-but-disabled instrumentation; this is not
an entirely uninstrumented Editor baseline.

Parity uses full canvas-composited ARGB from an explicit native paint outside the
measured window, not Robot screen capture or an assumed GL buffer format. Every
capture must advance the installed render-scope counters, execute queries and
produce nonuniform pixels; ON must additionally hit the cache. Native/native/ON/
native are compared at each fixed camera state, and two states must be distinct.
An unsupported context falling back to native is a failed exercise assertion, not
a passing optimization. The calibration first exposed shared GL contexts; the
runtime now admits them only after both bundled program-writer families and their
complete mutation scopes are attested.

`-Dturboism.uniform-location.shadow=true` checks eligible cache results against
native queries in a diagnostic-only ON leg. `modelUpdateCalibration=true` selects
short runs without pretending they satisfy full performance acceptance. Do not
reuse old proxy measurements for the narrow implementation. The installed runtime
is separate from this auto-exit test plugin; never deploy the exerciser into a
normal working Editor home.

## Allocation attribution with the narrow hook

Add `-Dturboism.validation.allocationProfile=true` and
`-Dturboism.validation.resources=true` to `uniformHook`. This is one diagnostic ON
leg, not an OFF/ON comparison. The recording contains allocation samples with
stacks; `allocation-leg-0.txt` is produced after timing stops. Categories distinguish
native matrices, scene/shader work, Turboism runtime and the validation harness.
Weights are statistical, not exact allocation bytes. The first EDT weight is
reported explicitly because some host JVMs attribute pre-recording allocation to
that event; never present such an inflated first sample as measured site bytes.
Use the separate thread-allocation counter for total allocation per measured frame.

Initial 200-event attribution located `GLContext.isShared` temporary weak keys in
our per-error callback and repeated native matrix construction in sorting/rendering.
With complete shared-program mutation coverage, the hot-path shared-state query is
logically unnecessary: either shared state is admitted. The bridge now short-circuits
that query while retaining current/context/created/ownership/mutation checks.
This does not cache context validity or weaken mutation admission.

## Reading native-interaction progress

`preparation-progress.txt` tracks discovery, focus, counter attachment and scene
inventory. A preparation-only watchdog writes a bounded thread/lock diagnostic
if these stages exceed 30 seconds, and must stop and join before any measured
window. `preparationWatcherStopped=true` records that boundary. The benchmark
summary changes to `status=RUNNING` after preparation and flushes completed legs;
`interaction-progress.txt` is the authority for the currently active leg and its
warmup/measuring phase. A partial summary is not proof of a stalled Editor. Do not
cancel a running full-size slow-control leg merely because it has not flushed its
summary yet. Failures are written before attempting UI cleanup, and no terminal
success is accepted without supervisor cleanup evidence.
