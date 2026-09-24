# Model-update and uniform-location experiments

## Independent matrix-scratch comparison

The `matrixScratch` factor keeps the verified uniform-location cache ON in every
control and changes only the opt-in invocation-local matrix candidate. It reuses
the native wheel/pan/ArtMesh workloads; it does not install an all-GL proxy. The
original installer-owned matrix admission gate must be present, armed and unchanged
at capture and leg validation. No matrix execution counter is injected in timed
windows; `matrixScratch.executionCounter=false` records that limit explicitly.
Functional installation or pixel agreement alone is not a new speedup claim.

After building `previewBundle` and `buildModelUpdateSkipHostProbe`, use the existing
shared queue wrapper (never a direct Editor launch):

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 matrix-cal \
  --jvm-option '-Dturboism.optimization.uniformLocationCache=true' \
  --jvm-option '-Dturboism.optimization.matrixScratch=true' \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=matrixScratch' \
  --jvm-option '-Dturboism.validation.modelUpdateCalibration=true' \
  --jvm-option '-Dturboism.validation.resources=true' \
  --ready-marker 'TURBOISM_MATRIX_SCRATCH installation=COMPLETE' \
  --failure-marker 'TURBOISM_MATRIX_SCRATCH installation=FAILED' \
  --failure-marker 'TURBOISM_MATRIX_SCRATCH restoration=FAILED' \
  --result-timeout 1200
```

Use the already configured, hash-pinned task fixture. Add
`-Dturboism.validation.nativeInteraction=pan` or `=artmesh` via `--jvm-option` for
the two continuous native drag cases. Omit the calibration option for 200 events
per leg only after calibration and all lifecycle/geometry/pixel checks succeed.
All controls keep Slice B and uniform-value suppression OFF. Report variants as
`locations-only` and `locations-and-matrix-scratch`, not raw native versus optimized.
The OFF/ON/ON/OFF result is additional to, not multiplied into, earlier uniform
cache speedup. CPU/memory and native Undo/Redo boundaries remain unchanged.
The factor's offline regressions check fixed uniform state, matrix request changes,
admission ownership/retirement and exact restoration of both prior preferences.


## Native pan and single-ArtMesh interaction validation

Run only in the task-scoped shared host queue. The production hook is default-on
for an exactly admitted host; no explicit enable flag is needed to prove startup.
The auto-exit exerciser must never be deployed to an ordinary user's plugin home.

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-pan 5303 pan-full \
  --jvm-option '-Dturboism.validation.resources=true' \
  --ready-marker 'TURBOISM_UNIFORM_LOCATION installation=COMPLETE' \
  --failure-marker 'TURBOISM_UNIFORM_LOCATION installation=FAILED' \
  --result-timeout 1200
```

(`on-pan`/`on-artmesh` are wrapper modes that still select
`modelUpdateWorkload=wheel`; the plugin then dispatches on
`turboism.validation.nativeInteraction`. Equivalent explicit form:
`on-wheel ... --jvm-option '-Dturboism.validation.nativeInteraction=pan'`.)

Use `on-artmesh` for the single-ArtMesh gesture. Add
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
native at a fixed state, rejecting blank images. All four paints execute in one
EDT event, so queued hover/action transitions cannot interleave the controls.
Camera, Undo, modified flag and action state must also remain exactly equal before
and after every capture. Per-leg diagnostics record full-image difference counts,
bounds and example pixels without any masking or tolerance. This corrected a
reproduced false control in which a queued hover action changed the two native
images; failed historical runs remain failures, not retroactively accepted.
In-flight mouse/key state is released in finally even on failure. Profiled runs
are excluded from this driver; captures are outside all timed windows.

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

For the production narrow-hook and matrix factors, all four same-camera pixel
controls now paint within a single EDT turn. `uniformCache.parity.*.atomicEdt=true`
records this boundary. This prevents queued hover/action changes from separating
native controls; it neither drops queued events nor masks pixel differences.
The legacy GL-proxy readback remains asynchronous and reports `atomicEdt=false`.
`CanvasWheelParityTest` exercises the real wheel parity path with a queued paint
state change and separately requires a one-pixel cached-image error to fail.

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

## Upload-target attribution (observation only)

Use the existing `modelSkip` wheel factor with
`-Dturboism.validation.modelUpdateGlCalls=true` and optionally
`-Dturboism.validation.modelUpdateUploadPayloads=true`. Keep the production
uniform cache explicitly OFF for this GL3-decorated attribution; this is not an
unproxied narrow-hook comparison. The diagnostic factor keeps model skip ON and
runs one measured leg. Leave calibration off for 200 events after readiness.

`glUploads.ARRAY_BUFFER`, `.ELEMENT_ARRAY_BUFFER` and `.OTHER` partition the
`glCalls.glBufferSubData` totals, using the very same delegate timestamps.
They are not additional time to add to that total. Counts include exceptional
calls; bytes are counted on normal return, which alone does not prove GL success.
Every native call, argument, exception and caller buffer view remains unchanged.

`glDuplicateUploads` is a further subset of normally returned uploads whose full
client payload matches the previous observed payload for that buffer/range.
`uploadPayload.arrayScanNanos` and `.elementScanNanos` partition CPU comparison
cost, including baseline copying. Element bindings are known only after an
explicit bind and are forgotten on VAO bind/deletion or direct element-binding
mutation; no VAO map or extra GL query
is used. Buffer identity shares the OpenGL buffer-name namespace across targets.
Context changes, buffer deletion/reallocation and unsupported writer families
retain conservative invalidation. Bounded mirrors are released on stop.

Neither identical submitted bytes nor a void GL return proves unchanged GPU
contents. Shared-context, mapped, shader and unobserved writes are not excluded;
`gpuResidencyVerified=false` and `completeWriteCoverage=false` remain authoritative.
No upload is omitted by this probe. Payload scans, reflection and timers perturb
execution, so these runs locate candidates rather than certify end-to-end speedup.
Short calibration timings can include compilation effects: record the full-size
observation as well, and never discard slow samples or replace production A/B
with these diagnostic numbers.

### Per-category GL delegate attribution (observation only)

Add `-Dturboism.validation.modelUpdateGlCallCategories=true` together with
`modelUpdateGlCalls=true`; the flag alone fails fast instead of silently running
without the GL probe. Each forwarded `gl*` call's delegate interval is added to
exactly one category — `draw`, `upload`, `query`, `uniformWrite`, `state`,
`readback`, `errorCheck`, `bufferLifecycle` or `other` — classified once per
method by the single prefix table in `GlCallCategory` at probe construction,
not per call. `errorCheck` is exactly `glGetError` (the unconditional
`shader/A.a(GL,String,Z)` marker), split out of `query`; `bufferLifecycle` is
every `glGen*`/`glDelete*` object creation or destruction (except
`glGenerate*`, which is content generation and stays in `other`), split out of
`other`. The report emits per-category `calls`/`nanos`/`maxNanos`,
`observedCalls`/`observedNanos` partition totals, and `observerNanos`: wrapper
bookkeeping outside the native delegate intervals, including the payload-scan
section when that observer is also enabled. `glCategories.*` partitions the
same `glCalls.*` delegate time; it is not additional time to add to those
totals. `requireValid` additionally rejects a partition that does not cover
every counted call. No `glGetError`, sequential log or per-call payload record
is added, and every call keeps its original order, arguments, return value and
exception identity. The flag is off by default; when off, no category storage
is allocated and the call path is identical to the pre-attribution probe. The
`glCategories.*` keys are additive: existing report consumers are unaffected.
These numbers decompose instrumented delegate time only; they remain
attribution evidence, not a paired speedup.

When categories are enabled the report also emits `glCategories.top.<rank>.*`
for up to `glCategories.topMethods.bound=10` methods, ranked by delegate
`nanos` descending (ties broken by method name): `method`, `calls` and `nanos`
per rank. The ranking is computed at report time from the existing per-method
metrics; the GL call path performs no sorting or allocation for it. These are
the same `glCalls.<method>.*` numbers reshaped, not additional time.

Instrumentation caveat for every `modelUpdateGlCalls` leg: wrapping the
panel's `GL3` in the probe proxy hides `GL4bcImpl` from the narrow uniform
cache, so the cache can never prove eligibility and every lookup goes native —
`uniformLocationCache` ON and OFF legs emit byte-identical call counts. Query
and uniform-write counts in instrumented legs therefore overstate production
behavior (production cache hits never reach the proxy). Treat `query`/`uniformWrite`
category numbers and `glGetUniformLocation` top-N entries as upper bounds, and
`uniformRedundant` below likewise includes writes a production cache would have
absorbed. State/draw/upload categories are unaffected by this caveat.

### Redundant-state upper bound (observation only)

`-Dturboism.validation.modelUpdateGlRedundancy=true` (requires
`modelUpdateGlCalls`; the flag alone fails fast) counts state-write calls whose
arguments are identical to the last recorded write of the same per-context
state entry. Every call still delegates exactly once — nothing is suppressed,
so the numbers are an upper bound on what call elision could ever save, not a
speedup measurement.

Tracked families (per GL context, one entry each): `glBindBuffer` per target;
`glBindTexture` per active-unit+target (unit taken from the last tracked
`glActiveTexture`); `glActiveTexture` itself; `glBindSampler` per unit;
`glUseProgram`; `glEnable`/`glDisable` per cap; `glBlendFunc`,
`glBlendFuncSeparate`, `glBlendEquation*`; `glCullFace`, `glFrontFace`,
`glDepthMask`, `glDepthFunc`, `glColorMask`, `glStencilFunc`, `glStencilOp`,
`glStencilMask`; `glViewport`, `glScissor`; `glPixelStorei` per pname;
`glEnableVertexAttribArray`/`glDisableVertexAttribArray` per index;
`glVertexAttribPointer` per index with the full argument set plus the current
`ARRAY_BUFFER` binding; and all `glUniform*`/`glProgramUniform*` writes keyed by
program+location+value (scalar, `v` and matrix forms up to 16 components).

Conservative semantics — undercounting is intentional: an entry is "known"
only after a tracked write in the same context and generation; the first call
after any invalidation is never redundant. `glDeleteBuffers/Textures/Programs/
Samplers`, `glLinkProgram`, `glBindVertexArray` (plus element-buffer and
attrib-state families), indexed/untracked variants (`glEnablei`,
`glStencilFuncSeparate`, `glBindBufferBase`, `glPixelStoref`, …) and
`glPushAttrib`/`glPopAttrib` invalidate the narrowest honest domain; a delegate
exception invalidates that call's own entry; a context-identity change or a
collection-window boundary makes every entry unknown; and any unrecognized
name in a state-write family (`glBind*`/`glEnable*`/`glUniform*`/`glVertex*`/…)
invalidates everything rather than risk stale knowledge. Entries live in fixed
preallocated tables (256 state / 512 uniform slots, 8-deep probe chains); a
crowded chain undercounts instead of colliding. Writes that cannot be keyed —
a `glBindTexture` before any observed `glActiveTexture`, a `glVertexAttribPointer`
with an unknown `ARRAY_BUFFER` binding, uniforms before `glUseProgram` — are not
recorded at all.

Report keys: `glRedundancy.enabled`, `glRedundancy.<method>.{calls,redundant,
redundantNanos}` per tracked non-uniform method, `glRedundancy.uniformRedundant.
{calls,redundant,redundantNanos}` aggregating all uniform writes (uniforms are
counted for context only — they are not this round's lever), and the summary
`glRedundancy.{calls,redundant,redundantNanos,invalidations}`. `redundant`
counts only calls that executed; `redundantNanos` is their delegate interval,
i.e. the time upper bound removable by elision. `requireValid` rejects any
method whose `redundant` exceeds `calls`.

Usage — the redundancy leg inherits the categories-leg caveat that the GL
proxy hides the concrete `GL4bcImpl` from the uniform cache, so the cache is
effectively bypassed under instrumentation. Keep the cache explicitly off so
the leg is honest about what it measures:

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 <leg-id> \
  --jvm-option '-Dturboism.validation.modelUpdateGlCalls=true' \
  --jvm-option '-Dturboism.validation.modelUpdateGlRedundancy=true' \
  --jvm-option '-Dturboism.optimization.uniformLocationCache=false' \
  --result-timeout 1200
```

(`modelUpdateGlCallCategories` may be added to the same leg for the method
top-N context, or omitted to keep the report narrower; it is not required.)

#### Production design sketch (not implemented)

Two hook points could turn this observation into suppression:

- Host call-site transforms: rewrite redundant `INVOKEINTERFACE GL.*` sites in
  the render path (same mechanism as the elision hook). Pro: domain-precise —
  only the audited renderer call sites change. Con: misses redundant calls
  issued from other paths (JOGL internals, other renderers), so the win is
  partial by construction.
- Pinned `GL4bcImpl` method bodies: prepend a cached-state check inside each
  state-write method on the verified artifact. Pro: sees every caller,
  including Swing/JOGL-internal state writes that reset our assumptions — which
  is exactly why call-site rewriting alone would be incorrect: any GL call not
  passing through the audited sites can silently change state. Con: runs on all
  GL traffic including reads, and the cache must be per-context, keyed on the
  `GLContext` like this tracker.

Correctness requirements either way: shared contexts mean state learned under
one drawable is valid only for the same `GLContext` identity (and shared-group
peers can still mutate it out-of-band — so entries must be treated as hints
invalidated at every context make-current boundary and by any call observed
through a non-instrumented path); readback calls never change state but error
paths must invalidate on exception; `glPushAttrib`/`glPopAttrib`, deletion,
relink and VAO switches invalidate the same domains this tracker does.
Fail-closed: any unreviewed method-shape drift, unrecognized mutator, context
transition or transform mismatch disables suppression entirely — never suppress
when the state model is incomplete, which is precisely why this probe only
counts an upper bound and never skips a call.

### Redundant-state elision timing leg (experimental transform on pinned JOGL)

`-Dturboism.validation.redundantStateElision=true` implements the
`GL4bcImpl`-level option from the sketch above. The bundled `jogl-all.jar`
(`7dbedb4b…`, byte-identical across 5.2.03/5.3.02/5.3.03) is digest-attested;
`GL4bcImpl` is rewritten so each tracked setter first consults a per-context
state table and returns early when the arguments exactly equal the recorded
state. Intercepting at the implementation — not at host call sites — covers
every Java caller (host renderer, JOGL internals, the GLJPanel backing path);
the class has no other state-writing dispatch path.

Tracked setters and their keys: `glUseProgram`; `glEnable`/`glDisable` by
capability; `glBlendFunc`/`glBlendFuncSeparate` (aliased to one signature) and
`glBlendEquation`/`glBlendEquationSeparate`; `glCullFace`/`glFrontFace`;
`glDepthMask`/`glDepthFunc`/`glColorMask`; `glStencilFunc`/`glStencilOp`/
`glStencilMask`; `glActiveTexture`; `glBindTexture` keyed by (active unit,
target); `glBindSampler` by unit; `glEnableVertexAttribArray`/
`glDisableVertexAttribArray` by index; `glBindBuffer` by target.

State is kept per `GL4bcImpl` instance (one per GLContext) and only the
thread that owns the context may consult it — a foreign thread clears and
re-records. Fail-open invalidation covers every untracked mutation surface:
`glDelete*` bumps a global epoch (shared-group name reuse); VAO
(`glBindVertexArray`, `glVertexArray*`, `glBindVertexBuffer`), framebuffer and
renderbuffer binds, program lifecycle (`glLinkProgram`, `glUseProgramStages`,
`glBindProgram*`, `glProgramBinary`), push/pop (`glPush*`/`glPop*` — state can
be restored wholesale), indexed/ARB/EXT/APPLE variants of every tracked domain
(`glEnablei`, `glStencilFuncSeparate`, `glBindBufferBase`, `glBindTextures`,
`glBindImageTexture`, …), display-list and NV command-list replay
(`glCallList`, `glDrawCommandsStates*`, …) each clear the calling context; a
thrown tracked call clears its context before rethrowing. Unknown state always
passes through — the experiment can only under-count savings, never suppress
a real change.

Log markers: `TURBOISM_STATE_ELISION elision=ACTIVE sites=N` on successful
rewrite (N = instrumented methods: 21 tracked + discovered invalidators); the
close marker dumps every `elided/passed` per site plus per-method
`*Invalidations` and the clear counters.

ABBA usage — the `redundantState` factor toggles only the elision gate;
model-update skip, the uniform cache and (optionally) production upload
elision stay at their production configuration so the legs measure the
stacked real increment:

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 <leg-id> \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=redundantState' \
  --jvm-option '-Dturboism.validation.redundantStateElision=true' \
  --jvm-option '-Dturboism.optimization.uploadElision=true' \
  --result-timeout 1200
```

Report keys per leg: `leg.N.stateElision.{calls,elided,passed,passGate,
passNoBaseline,passChanged,passUnknownUnit,entries,contexts,epochClears,
contextClears,threadClears,exceptionClears,observerFailures}`, per-site
`leg.N.stateElision.<site>{Calls,Elided,Passed}` and per-method
`*Invalidations`, alongside the usual `leg.N.canvasPixelDigest`/`Parity`.
`canvasPixelParity` compares before/after-leg pixels; cross-leg digest
equality across off/on/on/off is the pixel-correctness evidence. The same
factor works in `NativeInteractionWorkload` (`nativeInteraction=pan|artmesh`)
with identical semantics — the uniform hook stays ON and only the state
gate toggles; the drag legs additionally assert the cross-leg baseline/moved/
restored geometry digests.

Composition: this experiment transforms only `jogamp/opengl/gl4/GL4bcImpl`
method entries; the uniform lifecycle transformer wraps different bodies
(`shader/*` plus the program-mutation methods, which here are entry-level
invalidators). Both are entry/exit observers that compose through the
retransformation chain — each sees the previous round's bytecode and the
shape checks only cover the official instructions.

### glGetError elision timing leg (experimental transform, not a proxy)

`-Dturboism.validation.glGetErrorElision=true` enables a completely separate
experiment: an agent-level bytecode transform that replaces each reviewed
`invokeinterface com/jogamp/opengl/GL.glGetError()I` inside the exact marker
method `com/live2d/graphics3d/shader/A.a(GL;Ljava/lang/String;Z)I` with a
constant `GL_NO_ERROR` (`pop; iconst_0`, identical operand-stack shape). It is
for uninstrumented timing legs: it does **not** use or require the GL
submission probe, the payload observer or category attribution, and it must
never be combined with them in the same leg — under the proxy a non-zero
`glGetError` result would contradict the elided marker and inflate
`nonzeroErrors`.

Fail-closed contract: the hook only installs when the flag is set, the host
artifact is a reviewed digest (5.3.02, 5.3.03; 5.2.03 has no such method),
the marker method's `ReviewedMethodShape` equals the official artifact's
reference shape, and the rewritten site count equals the reviewed count. Any
owner/method/descriptor or bytecode drift leaves the class untouched. On close
the class must hash back to its pre-rewrite SHA-256.

Driver verification markers in the runtime log (all under
`TURBOISM_GL_ERROR_ELISION`):

- `installation=NOT_ADMITTED` — flag off (or gate refused); nothing changed.
- `installation=FAILED <error>` — install threw; nothing changed.
- `installation=COMPLETE` — the install attempt finished; this alone does not
  prove a rewrite (a NOT_ADMITTED leg also reaches it).
- `elision=ACTIVE sites=<n> target=com/live2d/graphics3d/shader/A.a(Lcom/jogamp/opengl/GL;Ljava/lang/String;Z)I`
  — the rewrite is installed; this line is the effectiveness marker.

Semantics: elided calls see `GL_NO_ERROR`, so the marker's error branch never
fires and no `GLException` can be thrown from it. That is the intended
experiment (error-check cost removed), not a parity guarantee — a leg with
elision active is a hypothesis test, not a correctness run.

Usage: pass `-Dturboism.validation.glGetErrorElision=true` via `--jvm-option`
to the host-validation wrapper like any other JVM option; it is read by the
javaagent at `HOST_RESOLVED`, before the preview runtime starts.

#### Composition conflict with the uniform-location lifecycle transform

Observed on host: with `glGetErrorElision` and the uniform-location cache
enabled in the same leg, the uniform hook fails closed with
`TURBOISM_UNIFORM_LOCATION installation=FAILED ... uniform dependency body
mismatch: com/live2d/graphics3d/shader/A.a`. Both features transform the same
method, and they do not compose today.

Mechanism. `VerifiedUniformLocationInstaller` verifies every target and
dependency by capturing the class bytes and comparing the reviewed method
shape against the reference bytes in the official JAR (`verify()` /
`capture()` in the installer). `capture()` performs a `retransformClasses`
with an observing transformer, so the bytes it sees are the output of the
**whole registered retransform chain** replayed on that class — not the
pristine JAR bytes. Once `GlGetErrorElisionTransformer` is registered, every
retransformation of `shader/A` replays its rewrite, so the uniform installer's
captured body differs from the JAR reference and `verify()` throws. Install
order does not matter for the capture itself: even if uniform installs first,
any later retransformation (including the installer's own close-time
`sha256(capture(target))` restore check) replays the elision rewrite, so the
recorded baseline hash never matches what capture returns afterwards.

Candidate resolutions, in increasing scope:

1. **Validate dependencies against a pre-chain baseline.** Keep per-class
   snapshots of bytes captured before any Turboism retransformer runs (or have
   the installer temporarily remove/disable the elision transformer during
   `capture()`), and compare method shapes against that baseline. Risk: the
   JVM has no API to ask for "bytes before transformer X"; a baseline registry
   must be maintained by the hook layer itself, and every later verifier —
   including the restore-time SHA-256 check — must agree on which baseline to
   compare. Doable but turns verification into bookkeeping about chain order.
2. **Install elision after uniform.** Ordering alone does not fix it: when
   elision registers later, its own `ReviewedMethodShape` gate reads the
   uniform-transformed body of `shader/A.a`, sees drift, and fails closed —
   the leg silently loses the elision experiment (only visible via a missing
   `elision=ACTIVE` marker), and the uniform restore check still captures
   post-elision bytes afterwards. Order swaps the failure mode; it does not
   remove it.
3. **Relax elision's precondition on `shader/A`.** Teach the elision target a
   second reviewed shape (the post-uniform-transform body). This composes the
   rewrite, but it does not fix the uniform side at all — its verifier still
   compares captured bytes against JAR originals at install and at close —
   and it doubles the reviewed-shape surface: accepting a second shape means
   auditing the exact uniform output bytes per host version.
4. **Joint single-pass transform.** Have one verified installer apply both
   rewrites to `shader/A.a` in one pass, verify method shapes against the JAR
   baseline before mutation, and record per-stage hashes so restore can
   distinguish "my rewrite" from chain output. This is the correct composition
   model but is a real bootstrap change, not a validation-scope tweak.

Recommendation: keep fail-closed behavior. The two features are alternative
hypotheses for the same budget — an elision leg wants uniform caching off
anyway (the cache changes how often `A.a` runs), and the instrumented legs
already disable the cache because the proxy bypasses it. Do not combine them
in one leg; if composition is ever productized it needs option 1 or 4 with
baseline-preserving verification, never a relaxed gate alone.

### Mesa glthread A/B investigation (`--linux-env` implemented; mechanism verified, no host evidence yet)

Hypothesis under test: if Mesa `glthread` is enabled on the Proton + Mesa
26.1 iris host, the EDT only enqueues GL calls while a worker thread does the
driver work, letting Java-side render preparation overlap with driver time —
the ~38% EDT GL block and ~18% Java preparation currently serialize on one
thread. The field thread list showed `gdrv0` (a Gallium driver worker) but no
glthread worker, consistent with glthread being off today.

**Mechanism.** `--windows-env` writes Windows-side `set` lines inside the
prefix — the wrong channel for `mesa_glthread`, which Linux-side Mesa reads
via `getenv` inside the `java.exe` wine process. The runner now owns a
first-class `--linux-env NAME=value` option: each assignment is whitelist
checked (`mesa_glthread`, `MESA_[A-Z0-9_]+`, `GALLIUM_HUD`,
`GALLIUM_HUD_PERIOD`; values limited to `[A-Za-z0-9._:,=+/-]`), lands in
normalized argv → `runner-request.json` → the prepared snapshot (which also
snapshots `tool/scripts`, so only jobs prepared with this branch support it),
and is emitted as literal `export` lines in the generated `launch.sh` before
the Proton wrapper call. The enumerable managed names are `unset` first so an
ambient `mesa_glthread`/`GALLIUM_HUD*` in the worker environment cannot leak
into a job that did not declare it — OFF legs stay genuinely off. Ambient
`MESA_*` names other than those still pass through (not enumerable); never
run measured legs under a shell that exports Mesa overrides.

**Evidence written per run.** `launch-environment.properties` records the
requested assignments. After readiness markers and again after the terminal
result, the runner snapshots the task-bound `java.exe` process (selected by
exact `WINEPREFIX=<task prefix>` match in `/proc/<pid>/environ`, never a comm
substring): `java-process.<phase>.properties` (pid, start, `glThreadCount`),
`java-threads.<phase>.txt` (every `task/*/comm`), and
`java-environ.<phase>.properties` (whitelisted names actually inherited,
`mesa_glthread=ABSENT` when missing). Mesa 26.1.5 names the glthread worker
via `util_queue` `"%s%i"` naming on queue `"gl"` → comm **`gl0`**; `gdrv0` in
field lists is a separate Gallium driver thread. Mesa also prints
`ATTENTION: default value of option mesa_glthread overridden by environment`
to the process stderr when the env var overrides the drirc default — that
lands in `cubism-console.txt` and corroborates activation independently of
the thread snapshot.

**Why the env var wins.** Mesa 26.1.5 `dri_context.c` resolves glthread in
order: `mesa_glthread_driver` (drirc driver default) → disabled if fewer than
4 total/5 big CPUs → `mesa_glthread_app_profile` (drirc per-app) →
`mesa_glthread` env var, which overrides all of the above when present. A
final `thread_safe` gate can still veto. This machine has no `~/.drirc` or
`/etc/drirc`; `/usr/share/drirc.d/00-mesa-defaults.conf` contains 319 app
rules matched by Wine-visible exe name, none for `java`/`java.exe`, and no
global `mesa_glthread` override — so the env var is the sole effective lever
and nothing silently re-enables/disables it. `~/.drirc` must not be edited
(global state visible to other tasks).

**Driver usage (cross-run ABBA — glthread is process-level, cannot toggle
within a run).** Same wheel harness, production uniform + uploadElision +
inputPath stack kept constant:

```bash
# OFF leg (repeat for the OFF slots; linux-env absent entirely)
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 glt-off \
  --jvm-option '-Dturboism.optimization.uploadElision=true' \
  --jvm-option '-Dturboism.optimization.inputPath=true' \
  --result-timeout 1200

# ON leg
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 glt-on \
  --jvm-option '-Dturboism.optimization.uploadElision=true' \
  --jvm-option '-Dturboism.optimization.inputPath=true' \
  --linux-env 'mesa_glthread=true' \
  --result-timeout 1200
```

Run the sequence OFF/ON/ON/OFF/OFF/ON to bound drift. Judging requires
`java-environ.result.properties` showing `java.<pid>.mesa_glthread=true` AND
`java-process.result.properties glThreadCount>=1` (comm `gl0` in
`java-threads.result.txt`) on ON legs, with `mesa_glthread=ABSENT` and
`glThreadCount=0` on OFF legs; then compare the wheel `eventMs` across runs.
`GALLIUM_HUD` is admitted for attended debug legs only — it draws into the GL
framebuffer and breaks canvas pixel digests, never use it in measured legs.
`--graphics-device nvidia` bypasses Mesa entirely (NVIDIA proprietary GL), so
glthread legs are meaningless there — keep `inherit` (iris) for this
experiment.

### Skipped-frame upload elision upper bound (experimental transform, not a proxy)

T08 attribution measured `glBufferSubData` as the largest single native
hotspot (175/636 EDT native samples ≈ 10 ms/event by ReadPixels calibration),
with ~2,377 uploads/event including ~954 index uploads whose payloads were
99.6% byte-identical to the previous frame (r3). This experiment measures the
**upper bound** of suppressing repeated uploads on frames where slice A
already skipped the model update — geometry is then unchanged by
construction.

Flag (default OFF):

```text
-Dturboism.validation.skippedFrameUploadElision=true
```

Mechanism — host call-site transform, no GL proxy:

- The reviewed private upload method `b(GL2ES2,int)` inside the persistent-VBO
  wrappers `com/live2d/graphics3d/mesh/a/b` (float attributes) and
  `.../mesh/a/c` (index data) is rewritten in place: the entry of the
  `glBufferData` block (gated by the second `ILOAD 3; IFEQ` pair) and the entry
  of the `glBufferSubData` block (gated by `k(); IFEQ`) each gain a
  loader-neutral `BiPredicate` consult (the `turboism.upload-elision.predicate`
  slot in `System.getProperties()`); `true` jumps straight to the post-upload
  join. Both blocks contain only the upload call and its pure argument
  evaluation, so binding calls, `glDeleteBuffers`/`glGenBuffers` regeneration,
  dirty-flag clearing and the trailing bookkeeping are untouched — only the
  upload operation is suppressed. The upload call is wrapped in a `Throwable` handler that
  notifies the `turboism.upload-elision.failure` slot and rethrows; the
  `glGenBuffers` site notifies `turboism.upload-elision.lifecycle` so a
  regenerated buffer clears the tracker before the guarded call is reached.
- The frame gate is `turboism.model-update-skip.frame-skipped`, an
  `AtomicBoolean` slot published by the model-update-skip bridge: cleared at
  every predicate entry and set to the real skip decision only on the
  non-probe path, so a stale skip decision cannot survive into a non-skipped
  frame (a render with no intervening update call keeps the flag, which is
  still safe — the host never rewrote the buffer contents; documented as a
  residual attribution caveat, not a correctness one).
- Elision requires `armed` (the `turboism.upload-elision.gate` slot, flipped
  per leg by the workload) AND the frame-skipped flag AND a recorded baseline
  for the same `(gl context identity, buffer name)`. Two compare modes,
  selected once at install time via
  `-Dturboism.validation.skippedFrameUploadElision.compare=identity|content`
  (default `identity`):
  - `identity`: byte size, `Buffer` object identity and `position`/`limit`
    must equal the baseline. **No payload bytes are compared** — the pure
    upper bound.
  - `content`: meta signature (size + region) must match AND the payload is
    compared byte-for-byte against a retained snapshot of the last uploaded
    content via `Buffer.mismatch` (JDK vectorized compare). Different buffer
    objects with equal bytes elide; the same buffer object mutated in place
    still passes (the host's `a(float[])`/`a(int[])` path refills the reused
    direct buffer). Snapshots retain one payload copy per tracked entry —
    `snapshotBytes` reports the retained total (the experiment's memory cost,
    explicitly reported per leg and at close), `compares`/`compareNanos`
    report comparison work.
- The first qualifying upload always executes and records the baseline (and
  the snapshot, in content mode). Context identity changes, buffer lifecycle
  notifications, the first call of a non-skipped-frame run, native upload
  exceptions and any observer failure all clear the whole table.

Pass-reason accounting per leg (`leg.N.uploadElision.*`):

- `floatCalls/floatElided/floatPassed`, `indexCalls/indexElided/indexPassed`
  — split by wrapper kind (`mesh/a/b` vs `mesh/a/c`).
- `passGate` — frame not skipped or leg gate disarmed; `passNoBaseline` —
  first-seen `(gl,name)` (also covers the undercount case where the table is
  at capacity: `failedInserts` counts those inserts separately);
  `passSize`, `passRegion`, `passBuffer` — baseline exists but the byte
  size, position/limit or buffer object differs (`passBuffer` only in
  identity mode); `passContent` — meta matched but payload bytes differ
  (content mode only).
- `entries`/`peakEntries`/`capacity`/`grows`/`failedInserts` — the baseline
  table grows from 512 slots up to 16,384 (doubling + rehash on probe
  exhaustion); `failedInserts` counts the cap-exhausted undercount case.
- `compares`/`compareNanos`/`contentElided`/`snapshotBytes`/
  `snapshotBytesPeak`/`snapshotBudgetSkips` — content-mode comparison work
  and retained snapshot memory (live total and high-water mark;
  `snapshotBudgetSkips` counts copies refused by the
  `turboism.optimization.uploadElision.snapshotBudget` byte cap, default
  64 MiB — refused entries keep any older snapshot and simply pass).
- `threadClears` — a consult on a different thread clears the table and
  counts an observer failure (GL calls are expected on a single render
  thread; an unreviewed sharing pattern fails open).
- Leg emission splits counter keys (reported as per-leg deltas) from gauge
  keys (`entries`, `capacity`, `peakEntries`, `snapshotBytes`,
  `snapshotBytesPeak`, `mode`), which report absolute readings — a delta of
  a cumulative peak would show 0 on every leg after the table first fills
  (the leg.1 zeros seen in the T08d run).

#### First-measure findings (5303 heavy wheel, identity mode)

- 21.5% of consults elided; 79% passed. Every leg's `clears` stayed 0 — no
  context, lifecycle or exception clears — so all passes were signature
  misses. The reason counters exist to attribute those misses.
- Bytecode research (`mesh/a/a`, `mesh/a/b`, `mesh/a/c`, `shader/A`): the
  payload buffer is a **persistent direct buffer reused across frames** —
  `A.a(FloatBuffer,[FIII)` clears+puts into the same object while capacity
  suffices and only `allocateDirect`s on growth, so object identity, position
  (0) and limit (capacity) are stable when the content size is stable. The
  base-class `l()` marks dirty *and* bumps a private `k` version counter, but
  `l()` only runs from property setters while `a(float[])`/`a(int[])` write
  content without bumping `k` — so the counter is not a trustworthy
  content-version signature and is not used.
- Expectation for the reason split: `passBuffer` dominates iff the host
  re-wraps or re-allocates payload buffers per frame (auxiliary UI meshes);
  `passNoBaseline`/`passSize` dominate iff new buffers appear per frame;
  `passGate` dominates iff uploads run while the frame-skipped flag is low.

#### T08d findings (grown table, both compare modes)

Exact-host reruns (`2fb0591c` identity / `a70e41a1` content, 5303 heavy
wheel, pixel digests identical, `failedInserts=0`):

- On skipped frames **every guarded upload repeated byte-identically** —
  475,400 consults/leg, 100% elided under content compare.
- `mismatch` cost ≈ 225 ms / 200 events ≈ 1.1 ms/event for ~2,377
  comparisons; ON legs ≈ 53.9 ms vs OFF leg3 ≈ 60.5 ms → ≈6.5 ms/event
  (~11%) net saving.
- Productionization followed as `turboism.optimization.uploadElision`
  (default off): same transformers and bridge, content mode forced, gate
  armed unconditionally, hook id `cubism.render.upload-elision` under the
  startup policy, `NOT_ADMITTED reason=modelUpdateSkip-disabled` when the
  skipped-frame precondition cannot exist. The settings-page toggle persists
  `launcher.uploadElision`; the managed launcher emits the flag only when
  enabled. See
  `docs/agents/perf-upload-elision-productization-20260924.md`.

#### Second-measure findings and the fixes they drove

Exact-host runs (identity `2fb0591c`, content `a70e41a1`, 5303 heavy wheel)
exposed two observer defects — both fixed before this table had any meaning:

- **`passNoBaseline` was a table-exhaustion artifact.** `calls=475,400`,
  `elided=102,200`, `passNoBaseline=373,200`, every other reason 0, all
  clears 0. Heavy legs upload through ≈2,377 distinct buffers per event
  while the original table was a fixed 512 slots / 8-probe window — the
  511 elisions per event were exactly the table capacity, and every other
  `(gl,name)` fell off the probe chain and reported `NO_BASELINE`. The
  table now grows 512→16,384 with rehash and reports
  `entries/peakEntries/capacity/grows/failedInserts`.
- **Content mode never compared.** `elided=0`, `passContent=102,200`,
  `compares=0`, `snapshotBytes=0`: the snapshot-vs-source check gated on
  `snapshot.getClass() == buffer.getClass()`, but the retained copy is a
  heap buffer while the host presents `Direct*BufferU` views — the class
  test always failed before the compare ran. The compare now dispatches on
  element type (`instanceof FloatBuffer` …) and compares a `duplicate()`;
  `snapshotBytes` reflects the real retained total and `snapshotBytesPeak`
  is the high-water mark.
- What the clean identity run does show: the 21.5% elision that survived
  the 512-slot ceiling (float 51,200/284,600, index 51,000/190,800) were
  genuine repeats — the question the grown table answers is how much of the
  373,200 `NO_BASELINE` was capacity artifact vs genuinely distinct
  buffers.

Markers:

- `TURBOISM_UPLOAD_ELISION elision=ACTIVE sites=4` — both wrappers rewrote
  (2 sites each). `installation=COMPLETE` alone is not evidence.
- Close marker: `TURBOISM_UPLOAD_ELISION closed elided=N passed=N calls=N
  clears=... *Clears= observerFailures= float*/index* pass* compares=
  compareNanos= contentElided= snapshotBytes= snapshotBytesPeak= entries=
  peakEntries= capacity= failedInserts= grows= restored=true|false`.
- Per-leg workload keys: `leg.N.uploadElision.{calls,elided,passed,clears,
  contextClears,nonSkippedClears,lifecycleClears,exceptionClears,
  observerFailures,entries,capacity,peakEntries,failedInserts,grows,armed,
  floatCalls,floatElided,floatPassed,indexCalls,indexElided,indexPassed,
  passGate,passNoBaseline,passSize,passBuffer,passRegion,passContent,
  compares,compareNanos,contentElided,snapshotBytes,snapshotBytesPeak,
  mode}`.

Admission: reviewed 5.3.02/5.3.03 only. 5.2.03 lacks the `shader/A.a(Buffer)J`
size helper the bridge resolves (its `shader/A` is an unrelated Kotlin
lambda), so it fails closed to `installation=NOT_ADMITTED`.

#### Pixel parity

`leg.N.canvasPixelParity` / `leg.N.canvasPixelDigest` are already emitted for
every factor other than `modelSkip`, and the leg fails fast with
"buffering change altered canvas pixels" if the toggle repaint differs. Using
`uploadElision` as the factor therefore yields both keys plus a screen-capture
digest per leg at the base camera; the driver compares
`leg.{0,3}.canvasPixelDigest` (gate off) against `leg.{1,2}.canvasPixelDigest`
(gate on) for equality, and `canvasPixelParity=true` must hold on every leg.
This captures the leg-boundary frame only — in-frame corruption between
boundaries is not covered by this gate; treat digests as a smoke check, not a
full-frame equality proof.

#### ABBA usage (in-run legs; gate toggled per leg)

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 <leg-id> \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=uploadElision' \
  --jvm-option '-Dturboism.validation.skippedFrameUploadElision=true' \
  --result-timeout 1200
# content-compare run (adds a per-entry payload snapshot; watch snapshotBytes):
... --jvm-option '-Dturboism.validation.skippedFrameUploadElision.compare=content'

# production-configuration run: content compare + unconditionally armed
# (the workload leg gate still toggles arm/disarm for ABBA):
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5303 <leg-id> \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=uploadElision' \
  --jvm-option '-Dturboism.optimization.uploadElision=true' \
  --result-timeout 1200
```

One run yields off/on/on/off legs (workload variants `0,1,1,0`): the flag
installs the transform and bridge; the workload's `uploadElision` factor arms
the gate on enabled legs and disarms it on control legs. Model-update skip
stays enabled on every leg (the wrapper's `-Dturboism.optimization.modelUpdateSkip`
default), because skipped frames are the precondition under test. Keep the
instrumented probes OFF in these legs — `modelUpdateGlCalls` wraps GL in the
submission proxy, which changes upload timing and double-counts nothing.

A cross-run control without the transform is also meaningful:

```bash
... <off-leg-id> --jvm-option '-Dturboism.validation.skippedFrameUploadElision=false'
```

#### Pan / ArtMesh acceptance runs (uploadElision factor)

`NativeInteractionWorkload` accepts `modelUpdateFactor=uploadElision`: the legs
keep the uniform-location hook ON and toggle only the elision gate, so each leg
differs in a single variable. The same `leg.N.uploadElision.*` keys are emitted,
plus `leg.N.geometryDigest` (baseline before the gesture),
`leg.N.movedGeometryDigest` (after the drag) and
`leg.N.restoredGeometryDigest` (after native restore/undo). After all four legs
the workload asserts `crossLegBaselineParity`, `crossLegMovedGeometryParity` and
`crossLegRestoredGeometryParity` — any digest difference across legs fails the
run, so ON legs can never silently diverge from the OFF controls.

```bash
# pan: expects changedMeshCount=0 on every leg; baseline/moved/restored
# digests equal across all four legs.
bash scripts/preview/run-model-update-skip-host-validation.sh on-pan 5303 <leg-id> \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=uploadElision' \
  --jvm-option '-Dturboism.optimization.uploadElision=true' \
  --ready-marker 'TURBOISM_UPLOAD_ELISION elision=ACTIVE' \
  --result-timeout 1200

# artmesh: single-mesh authoring drag; expects changedMeshCount=1 per leg and
# moved/restored digest parity across OFF and ON legs. A drag is NOT a
# skipped-frame workload, so elided=0 is the expected result — the run proves
# no correctness regression and no slowdown, not savings.
bash scripts/preview/run-model-update-skip-host-validation.sh on-artmesh 5303 <leg-id> \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=uploadElision' \
  --jvm-option '-Dturboism.optimization.uploadElision=true' \
  --ready-marker 'TURBOISM_UPLOAD_ELISION elision=ACTIVE' \
  --result-timeout 1200

# 5.3.02 wheel run (identical wrapper bytecode; admitted by digest):
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5302 <leg-id> \
  --jvm-option '-Dturboism.validation.modelUpdateFactor=uploadElision' \
  --jvm-option '-Dturboism.optimization.uploadElision=true' \
  --ready-marker 'TURBOISM_UPLOAD_ELISION elision=ACTIVE' \
  --result-timeout 1200
```

Add `--failure-marker 'TURBOISM_UPLOAD_ELISION installation=NOT_ADMITTED'` to a
run that is expected to admit. On 5.2.03 the admission must fail closed —
`shader/A` there is an unrelated Kotlin `Function0` lambda and the size helper
lives on `shader/y` — so the evidence is the runtime-log line
`TURBOISM_UPLOAD_ELISION installation=NOT_ADMITTED`; do NOT pass
`modelUpdateFactor=uploadElision` on 5.2.03 because the workload requires the
installed gate and fails by design without it. A minimal fail-closed check:

```bash
bash scripts/preview/run-model-update-skip-host-validation.sh on-wheel 5203 <leg-id> \
  --jvm-option '-Dturboism.optimization.uploadElision=true' \
  --result-timeout 600
```

The wheel workload still passes (upload elision absent is not a failure for
other factors); grep the runtime log for `installation=NOT_ADMITTED`.

#### Composition with other transforms

The transform rewrites only `mesh/a/b` and `mesh/a/c` — disjoint from the
glGetError elision (`shader/A.a(GL,String,Z)`) and from the uniform-location
lifecycle transform (`shader/GShader`, `shader/A`, JOGL). Its dependency
verification reads `shader/A.a(Buffer)J`, a method neither other transform
touches, so installing this experiment alongside either leaves their shape
gates unaffected; conversely our captures of `shader/A` replay other
transformers' output but only verify the untouched `a(Buffer)J` method.
**Uniform caching keeps its production default in both arms** — no mutual
exclusion needed (the mesh classes were never in the uniform lifecycle
transform's target set; verified against `Role` targets).

#### Residual risks / unmeasured assumptions

- Buffer object identity + position/limit + byte size equality is a proxy for
  "same bytes": a same-object buffer refilled between frames without an
  update would be wrongly elided. Under the wheel workload slice A always
  skips, so refills do not occur on measured legs; a mixed workload would
  over-attribute. This is the intended upper-bound approximation.
- `glGenBuffers` regeneration clears via the lifecycle slot, but host paths
  that replace `i()`'s content outside the reviewed method would not be
  noticed until the name no longer matches (still fail-open: a different name
  passes).
- The consult adds ~100–200 ns per guarded call even when passing; the
  in-run ABBA comparison includes this cost in both arms' `passed` paths, so
  the measured delta is the net upper bound, not the gross upload time saved.
