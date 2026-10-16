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
