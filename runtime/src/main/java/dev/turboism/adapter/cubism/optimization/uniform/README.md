# Narrow uniform-location cache

Default-on runtime implementation replacing the validation-only all-GL proxy.
Only exact verified hosts are admitted. It does not include uniform-value suppression.
The Performance settings checkbox persists `launcher.uniformLocationCache`;
explicit false is retained by managed launchers and stops reuse in an installed
hook. Enabling after startup denial requires restarting the Editor.

```text
-Dturboism.optimization.uniformLocationCache=false
```

Scope: exact reviewed Cubism 5.2.03, 5.3.02 and 5.3.03, the separately pinned
bundled JOGL, JVM 17+, a current created GL4bcImpl context and enabled hook policy.
The error-observer class is `shader.y` on 5.2.03 and `shader.A` on 5.3.x; each
method body is verified against that Editor's own original archive. An unknown
version or changed bundled JOGL remains native. Passing offline artifact checks
is not a replacement for exact-host interaction and pixel validation.
Safe mode / disabled `cubism.render.uniform-location-cache` denies installation.
Other versions, JOGL binaries, context implementations and failed lifecycle coverage
remain native. Shared contexts require complete bundled program-mutation coverage. This is not blanket compatibility admission.

## Boundaries

- Only GShader.preDraw_exe's material glGetUniformLocation site is conditional.
  No Object[] is generated at that site. Other GL calls are not proxied.
- SGFramework.g.render3d opens and finally closes the frame cache. It deliberately
  avoids the CEViewContext class used by the existing FPS instrumentation.
- Native shader error results confirm pending locations only in the same context
  and thread; no additional GL error queries are made or consumed.
- Concrete GL4bcImpl core/ARB and GLES3Impl link, program-binary and delete entries
  retire the active frame. The pinned JOGL writer inventory must match both complete
  method families. Begin/finally-end tokens prevent new baselines while any native
  mutation is in flight, including another shared context/thread. Native mutation
  calls execute outside the bridge monitor. Nested/reentrant rendering, context
  transitions and errors cannot revive a retired frame.
- Every frame releases its keys, pending values and host references. Retention is
  bounded to 4096 locations. Lifecycle failures retire reuse; absent or malformed
  typed callbacks retain the original query and native exception behavior.
- All original draw, buffer-upload, uniform-write and error instructions remain.
  A cache hit includes a valid -1 location, but not an unsupported negative value.
- Installation publishes callbacks only after all five class transforms succeed.
  Closing removes callbacks first, then all transforms, and checks original class
  hashes. A failed restoration is logged, not silently called successful.

`-Dturboism.uniform-location.shadow=true` retains native queries and compares each
eligible cached result. It is diagnostic only, not a performance measurement.
Counters are available through the loader-neutral supplier in system-property slot
`turboism.uniform-location.stats`; they contain scalars, not host objects.

## Verification

```bash
./gradlew --no-watch-fs :runtime:test --tests 'dev.turboism.adapter.cubism.optimization.uniform.*'
./gradlew --no-watch-fs :bootstrap:test --tests '*VerifiedUniformLocationInstallerTest'
./gradlew --no-watch-fs devCheck
```

Set `TURBOISM_UNIFORM_HOST_JAR` to the reviewed application JAR to execute artifact
checks. Missing explicit reference input is an artifact-test skip, not a host PASS.
Synthetic transformed-code tests and instrumentation-protocol tests do not launch
or execute the Editor. Actual JVM retransformation, frame/pixel parity and measured
net benefit of the narrow path require the separate exact-host validation run.
Do not reuse the old proxy's speedup, FPS or memory numbers for this implementation.

Initial exact-host calibration found that the modeling canvas uses a shared context;
its former blanket shared-context rejection produced zero hits despite successful
installation. The shared mutation coverage above replaces that restriction, not
its correctness requirements. New real-host evidence must exercise nonzero hits
and preserve pixel parity; successful installation alone is never sufficient.
