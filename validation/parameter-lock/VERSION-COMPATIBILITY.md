# Parameter Lock Version Compatibility

Scope: the 20 reviewed exact Cubism Editor versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04).

## Capability Coverage

- `cubism.model.observe` is computed from the model read and project read capabilities, and it covers model identity and document lifecycle dependencies.
- `cubism.model.intercept` is computed from the model write capability together with the three exact selectors the parameter hook installer uses: `parameter-operation.set-value`, `parameter-source.id`, and `id.value`, all in the `cubism.editor-model` namespace. Both the record and the selectors must be `VERIFIED_STATIC`.
- When a required capability, selector, or class pin is missing, that version cannot join the available set of that capability. The generator no longer ignores a missing declared dependency. Model observation and parameter interception have both left `unmappedCapabilities`.

The runtime still binds the parameter entry point per version through verified selectors and keeps the existing host admission and classloader checks. It adds no fuzzy version matching and copies no obfuscated class name from one version into a shared entry point.

## Repeatable Verification

```bash
python3 scripts/test/test_generate_compatibility_matrix.py
python3 scripts/generate_compatibility_matrix.py --check
TURBOISM_CUBISM_REF_ROOT=/path/to/cubism-ref \
  ./gradlew :runtime:parameterLockArtifactTest :plugins:parameter-lock:test
```

The evidence directory must hold `<exact version>/Live2D_Cubism.jar`. The dedicated task fails when no directory is configured, and the routine runtime test skips this external-evidence case when no original JAR is provided.

The per-version test reads the committed Editor-model and control-appearance records, verifies the original JAR identity and the full selector set through the production Java validator, and then runs the real parameter write and the native row UI transformer. It checks:

1. The native parameter write entry point carries `beforeNative`, `afterNative`, and `failedNative`, and calls the source-id and id-value entry points of that version.
2. No transform happens on a host classloader mismatch.
3. Every entry point of the project lifecycle profile really exists in the original JAR of that version.
4. The construction and selection entry points of single-parameter rows, two-parameter rows, and parameter group rows carry the matching native appearance bridge callback.

Plugin unit tests separately cover lock and unlock, asynchronous loading, recursive group locks, persistence, and model isolation. The JAR bytecode test does not replace in-editor acceptance of sliders, text input, and Undo/Redo; the existing host results remain authoritative in [ROW-UI-ACCEPTANCE.md](ROW-UI-ACCEPTANCE.md).

## Results of This Round (2026-10-22)

| Check | Result |
|---|---|
| Compatibility generator regression | 19 passed, including the negative cases for each missing required selector, an unverified selector, and a missing project capability |
| `:runtime:parameterLockArtifactTest` | 20 passed, 0 skipped |
| `:plugins:parameter-lock:test` | 42 passed, 0 skipped |
| Lifecycle and native control-appearance regression | 137 passed, 0 skipped |
| Parameter hook installer regression | 1 passed, 0 skipped |
| `checkCodeQuality`, Python Ruff, generated-artifact `--check` | passed |

In the current generated matrix, both the supported version set and the full-feature version set of parameter lock are the 20 reviewed versions above, and the two hook capabilities are no longer unmapped.
