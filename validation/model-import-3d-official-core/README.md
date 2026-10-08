# Independent official Core capture

This recorder runs in a separate JVM with only the official `Live2DCubismCore.jar`
and its own recorder JAR. It rejects JVM agents and Turboism SDK/runtime classes.
The classpath must contain exactly those two JARs, without duplicates or manifest
`Class-Path` extensions. The SDK exclusion checks its actual `sdk/plugin/PluginContext`
resource, and the offline regression includes the built SDK JAR.
It does not load an Editor or plugin, and does not implement a substitute evaluator.

Build reproducibly into a new directory:

```sh
python3 -B validation/model-import-3d-official-core/build.py \
  --core '/absolute/path/to/official/Live2DCubismCore.jar' \
  --out build/model-import-3d-offline/official-core-build-unique
```

The build captures source/Core/recorder hashes, tool versions, dependency output
and command results in `build.json`. Use the copied Core and recorder artifacts
with the matching official native Core library on the validation platform:

```sh
java -cp 'official-core-capture.jar:Live2DCubismCore.jar' \
  dev.modelimport.validation.OfficialCoreCapture \
  /absolute/path/to/exported.moc3 /new/output/directory CubeYaw CubePitch
```

Run the offline input and isolation checks separately:

```sh
python3 -B validation/model-import-3d-official-core/test-recorder.py \
  --build build/model-import-3d-offline/official-core-build-unique \
  --sdk /absolute/path/to/built/sdk.jar
```

Use `;` as the classpath separator on Windows. Clear inherited Java agent options.
The official Windows Core library must run in the authorized isolated Proton job;
compiling the recorder on Linux does not prove native Core execution.

Output uses `mi3d-official-core-capture-v2` and records all 377 frozen geometry
poses (critical points, axis keys, diagonal midpoints and seeded random points),
copied evaluated drawable arrays, parameter
keys, canvas units, process identity, and exact artifact origins. Its status is
`CAPTURED_NOT_VERIFIED`: independent source geometry, texture/raster, MOC origin,
and process/lifecycle verification are still required. A valid capture alone is
not acceptance of official export or completion of 3D import.

The recorder also accepts the explicit fifth argument `surface-dense-49-v1`.
This profile retains the 32 MiB MOC and 64 MiB JSON limits and requires the
complete one-mesh/four-slot plane or two-mesh/six-slot seam layout. It records
9921 ordered poses: all 49×49 keys, cell and edge midpoints (97×97 combined),
plus 512 float32 poses from Java `Random(0x3344494d)`. The offline recorder test
compares every pose to the independent surface geometry inventory. Output uses
`mi3d-surface-official-core-capture-v1` / `mi3d-surface-dense-9921-v1` and remains
`CAPTURED_NOT_VERIFIED`. This recorder profile is currently an offline preparation;
surface export receipt admission, queue launch routing and independent capture
verification are not yet connected. Existing cube and apple profiles are preserved.

`verify-surface-geometry.py` now checks the complete 9921-pose inventory against
the original plane or discontinuous seam GLB and the admitted packed snapshot.
It keeps the fixed 1024 canvas, (512,512) export origin, 100 pixels/unit, and
0.1 pixel geometry tolerance. It checks source slots/topology, atlas UVs, all
parameter definitions/keys, default unused values, draw order and color bounds.
`test-surface-geometry.py --export-artifacts <accepted-atlas-artifacts>` supplies
synthetic positions and corruption controls; a pass does not establish actual
Core execution. The surface bundle/queue/process path remains to be connected.

`verify-geometry.py` checks this inventory against the authorized source cube at
0.1 canvas-pixel Euclidean tolerance, with fixed export origin/units and stable
drawable IDs, topology, UVs and dense parameter keys. Its caller must supply the
mesh IDs in the admitted export's source-face order. It does not infer a transform
or correspondence from the result and does not admit process identity, artifact
provenance or raster visibility. Native execution must still establish the
declared Core-to-canvas coordinate convention. Its synthetic mutation tests run
with `python3 -B validation/model-import-3d-official-core/test-geometry.py`.

`verify-raster.py` samples the admitted Core vertices, render order and exported
512px texture at the nine frozen display poses. It compares visible source-face
identity to independent 3D depth and checks the source base color. The sample grid
and one-pixel edge exclusion are inherited unchanged from the native cube oracle.
Its coordinate, UV-origin and winding conventions are explicit and never fitted
to a capture. This is a software raster check, not official renderer execution;
geometry, process and artifact admission are prerequisites. Run its synthetic
counterexamples with `python3 -B validation/model-import-3d-official-core/test-raster.py`.

The winding convention is grounded in the official framework's
[DrawMeshOpenGL implementation](https://github.com/Live2D/CubismNativeFramework/blob/45de21229a9faab9675525bc59deddbe089b0e95/src/Rendering/OpenGL/CubismRenderer_OpenGLES2.cpp#L1140):
`glFrontFace(GL_CCW)` in Y-up coordinates means negative signed area after the
fixed Y-down canvas conversion. Each source triangle's indices are reversed;
vertex identity and UV correspondence are unchanged. The checker rejects the
opposite winding. Parameter inventories include standard unused Editor parameters
only when independently bound to the accepted export snapshot; those parameters
must have no keys and stay at their recorded defaults. Core parameter values are
compared as float32. Constant opacity and color modulation allow at most four
float32 machine epsilons for bilinear roundoff, far below an 8-bit channel step;
the geometry tolerance remains exactly 0.1 canvas pixel.

After collecting a real standalone capture, `verify-content.py` binds both checks
to one accepted export archive and frozen recorder build:

```sh
python3 -B validation/model-import-3d-official-core/verify-content.py \
  --capture /path/to/core-capture.json --cube /path/to/authorized/cube.glb \
  --export-acceptance /path/to/export/acceptance.json \
  --export-archive /path/to/export/turboism-home-logs-state.tar \
  --recorder-build /path/to/frozen/recorder/build --out /new/content-check.json
```

It checks matching MOC bytes, Core/recorder JAR identities, export run identity,
and the recorded two-JAR Windows classpath before geometry/raster comparison.
It rejects duplicate JSON keys and nonfinite values. The result remains
`BOUND_CORE_CONTENT_CHECKED_NOT_PROCESS_ADMITTED`: independent queue containment
and actual standalone-process identity must also pass before a no-plugin claim.

`prepare-bundle.py` freezes the accepted MOC/texture, recorder/Core JARs, the exact
5.3.02 installation's Java 17 runtime, Core JNI DLL and its VCRuntime dependency.
It records every file's size/hash, rejects links and verifies sources again after
copying. It neither starts a process nor admits a queue task:

```sh
python3 -B validation/model-import-3d-official-core/prepare-bundle.py \
  --editor /path/to/official/Live2D-Cubism-5.3 \
  --recorder /path/to/frozen/recorder/build \
  --export-acceptance /path/to/export/acceptance.json --out /new/bundle
python3 -B validation/model-import-3d-official-core/test-bundle.py --bundle /new/bundle
```

The `official-core:5302` queue task uses `host_validation_official_core.py` through
a restricted dispatch in the common runner. It requires durable queue admission
before task creation, clones the golden prefix, checks the staged runtime against
the official installation and launches only the fixed recorder class. The frozen
post-containment helper checks actual JAR origins/JVM arguments and rejects forced
cleanup. A successful queue result means standalone capture only; run the content
gate separately. It does not run the Editor or load an Agent/plugin.
After launcher return, a bounded read-only observation waits for task-scope
helpers to exit naturally and records PID/start-time identities. It sends no
signals. The supervisor's independent final cgroup proof remains authoritative;
a timeout or forced cleanup still fails admission.

Source-textured cube exports use an explicit `--source-texture` GLB with the
frozen source hash and a 1024px atlas. Content and bundle admission also accept
`ACCEPTED_WITH_USER_APPROVED_PIXEL_DEVIATION` receipts only with the complete
63-entry bounded sampling record (0.1%, RGB tolerance 2, exact alpha). This
retains the qualified export status; it does not imply zero sampling error or
official renderer execution. Run `test-content.py` for receipt rejection cases.
