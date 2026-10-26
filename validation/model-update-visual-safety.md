# Model-update and upload safety

The Windows reports (no initial image until focus; children lagging during deformer drag)
remain separate from the source-level defects below. Synthetic tests do not constitute
Windows visual acceptance, and no performance workload was resumed for this change.

## Native fallback

`IncrementalUpdateBridge.beginUpdate` includes unsafe ArtPath/Affecter inputs in
`epochFull` before selecting interpolation reuse. Unsupported models therefore retain
native interpolation, deformer dirty marking and mesh deformation in the same epoch.
Editing guards continue to check both host editing flags and retain the first full
update after editing ends. Their presence does not prove every native drag entry sets
the flags throughout its transaction.

## Upload confirmation

A pre-upload consult stores candidate bytes, not confirmed GPU contents. A normal
`glBufferData`/`glBufferSubData` return stages the candidate. Only a subsequent existing,
real host GL error check with `GL_NO_ERROR` can confirm it. An earlier check, normal
Java return alone, a missing check or a synthetic validation/deferred zero cannot
establish a reusable baseline. No additional `glGetError` calls were introduced.

The verified uniform/error lifecycle forwards real inline and deferred frame-end
observations to upload tracking. Without that observer, upload elision remains native.
The bridge requires an existing, current, unshared JOGL context and tracks the GL
wrapper, context identity and native handle. Regeneration invalidates before the native
operation, including exceptional exits. GL errors, observation failures, thread changes
and lifecycle notifications discard candidates and confirmed contents.

Render entry/exit also discard upload baselines. This deliberately sacrifices reuse
across frames while complete context/resource generation coverage is unproven. In
particular, a clean deferred frame-end query does not grant next-frame reuse.

## Conservative defaults

All ten native performance switches default off on native Windows and Proton, including
geometry/atlas optimizations. Legacy mesh interpolation, model serialization and shader
uniform optimizations also require explicit JVM opt-in. Configuration readers, unavailable
UI services, preference normalization and managed validation launch defaults agree.
Existing explicit saved preferences and JVM overrides remain effective. ZGC, GraalVM
and memory settings retain their previous defaults.

Performance comparison drivers explicitly request their optimized legs and dependencies;
their historical results are not evidence for these new defaults or Windows correctness.

## Regression coverage

- Unsupported inputs after a completed baseline disable interpolation/deformer reuse.
- Candidates need native return followed by a real check; silent GL errors invalidate them.
- Context-handle changes, noncurrent/uncreated/shared contexts and render boundaries
  cannot reuse an old upload baseline.
- Both transformed upload sites notify only after native return; exceptional upload and
  regeneration paths preserve the original throwable and invalidate old resources.
- Synthetic error results cannot confirm caches; deferred checkpoints add no GL query.
- Defaults are off on both platforms, including UI fallback/normalization paths;
  explicit choices still round-trip and survive restart.

Windows cold-open (without focus intervention) and continuous parent/child deformer
motion still require real-host visual acceptance. No model is required to run the
source-level regressions above.
