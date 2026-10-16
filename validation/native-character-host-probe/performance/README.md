# Native import performance proofs

These test-only command-line harnesses do not enter release packaging or modify input assets. Pass the plugin and SDK JARs explicitly. Run each measurement without concurrent builds or host workloads.

```bash
javac --release 17 -cp "$plugin_jar:$sdk_jar" -d /tmp/native-import-performance-proof validation/native-character-host-probe/performance/*.java
java -Xmx1g -cp "/tmp/native-import-performance-proof:$plugin_jar:$sdk_jar" dev.turboism.validation.modelimport3d.host.NativeVisibilityScanProof
java -Xmx4g -cp "/tmp/native-import-performance-proof:$plugin_jar:$sdk_jar" dev.turboism.validation.modelimport3d.host.NativePreparationProof "$fixture_path"
```

The scan proof compares the original pixel-major vote traversal with the production row-major traversal on a deterministic sparse 39,950,000-entry depth table. It checks exact equality before reporting medians after two warm-ups. This isolates memory locality; its speedup is not a full import speedup.

The preparation proof reads an explicitly selected GLB and runs production preparation. It reports geometry/control budgets, residual error and a SHA-256 of every final draw-order rank. Repeat with an archived baseline plugin JAR to compare the same input; changing the plugin classpath does not change the harness. No private asset path is discovered automatically.

For native host stacks, opt into `-Dturboism.character.sampleStacks=true` through the normal queued validation runner. The probe writes bounded `import-stacks.txt` snapshots in its task-local state. Counts represent wall-clock stack observations, not CPU percentages. The sampler does not invoke Editor writes or change acceptance criteria. Use the shared queue cancellation protocol after enough samples; a cancelled diagnostic run is not a successful import.

## Occlusion diagnostic

`NativeOcclusionDiagnostic INPUT.glb OUTPUT_DIRECTORY` measures six yaw poses (0, ±30, ±90, 180) at 384×384. It compares source-geometry nearest depth with fixed triangle traversal in native partition order, using both interpolated stored ranks and a fresh exact-pose partition ordering. Red output pixels have the same partition owner as the reference (internal triangle ordering); orange pixels have a different owner (partition ordering or interacting internal errors). All source triangles remain present. Each pose also writes a `.png.errors.csv` report attributing internal/external errors and holes to the source-reference partition and mesh. Internal plus external equals the reported wrong count; missing is a subset of external. This attribution identifies candidates for bounded refinement without interpreting the counts as native RGBA error.

This is a geometry diagnostic, **not native rendered RGBA acceptance**: it uses exact projected source vertices rather than Cubism's interpolated vertices, thresholds source alpha, and does not simulate full alpha blending. Counts must not be described as final visual-error percentages. `-Ddiagnostic.culling=true` is an explicitly test-only paint candidate: it culls painted back faces while the nearest-depth reference retains the original material's double-sided semantics. `missing` counts source-visible pixels left uncovered by the candidate, and remains part of `wrong`/`external`. Complete holes have an infinite depth gap. This option is never a production default. Earlier culling reports that also changed the reference coverage are not comparable to the original source and cannot establish a visual improvement. Input selection is explicit and the input is read-only.

```bash
java -Xmx4g -cp "/tmp/native-import-performance-proof:$plugin_jar:$sdk_jar" dev.turboism.validation.modelimport3d.host.NativeOcclusionDiagnostic "$fixture_path" /tmp/native-occlusion-check
```

## Shared-vertex interpolation regression

Generate a tiny public fixture explicitly; it needs no private assets or Blender:

```bash
python3 validation/native-character-host-probe/performance/create_shared_seam_fixture.py /tmp/shared-seam.glb
```

It contains two differently sized triangles sharing one source vertex, a common body-parent frame and one opacity control so the production menu selects the native-rig importer. Use the regular queued host runner with `-Dturboism.character.sharedSeam=true`, `expectedTriangles=2` and `expectedControls=1`. The probe performs the import Undo/Redo check before changing parameters, then measures both yaw and pitch at -67.5, -45, -30, -15, 5, 15, 30, 45 and 67.5 degrees. It reads `evaluationSnapshot().calculatedForm()`, waits for native parameter consumption, and reports coordinate labels and maximum gap. On 5.3.04 the label is `Canvas`; tolerance is 0.001 canvas pixels. SDK `vertexPositions()` uses stored current-keyform geometry and is unsuitable for this check.

The original adaptive-density implementation separated the shared vertex by 2.96954345703125 canvas pixels. A uniform visible lattice eliminates this particular seam while retaining the full cache preflight. This does not prove correct depth ordering, alpha compositing, or complete character appearance.

The diagnostic includes a public synthetic culling-reference regression:

```bash
java -Xmx1g -cp "/tmp/native-import-performance-proof:$plugin_jar:$sdk_jar" dev.turboism.validation.modelimport3d.host.NativeOcclusionDiagnostic --self-test
```

A source-visible double-sided back face covers 22,472 reference pixels. The culled candidate must retain that reference coverage and report 22,472 missing/wrong pixels. A faulty reference that applies the candidate culling policy reports zero reference pixels and is rejected by the test. This protects the diagnostic; it does not establish correct final native character rendering.
