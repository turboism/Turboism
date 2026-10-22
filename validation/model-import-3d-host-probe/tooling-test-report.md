# Model-import-3D tooling fixture repair

## Evidence and history

Baseline at `daf2102b7` on `fix/model-import-3d-tooling-fixtures`:

- Direct discovery: **195 tests, 430.813 s, 3 failures / 18 errors / 7 skips**.
- Requested Gradle task: Python **411.917 s**, Gradle **7m 18s**, literal `BUILD FAILED`.
- `37ff3dc4f` introduced the frozen-input oracles, tests and image-binding poll intentionally. `e5a8d9dff` integrated that feature branch.
- `89ee00e6a` intentionally removed deprecated `PluginContext` service accessors in favor of `services()`. `HostProbe.java` had not followed that migration; the probe itself is repaired, not the SDK reverted.
- `8d5eb2f0a` intentionally typed JSON roots as `Map<String, ?>`; the Java input fixture now copies that result into its mutable `Map<String, Object>`.
- `104bdb28b` intentionally moved these suites out of the regular gate. There is no removed oracle behavior to delete (category c has no semantic tests).

## Every baseline failure, error and skip

`a` = stale fixture/stub or API integration; `b` = absent explicit external inputs; `c` = removed behavior.

| Result | Test / subtest | Category | Cause | Fix |
| --- | --- | --- | --- | --- |
| ERROR | `test_callback_pose_compares_java_float_values (test_model_import_3d_cube_render.StateTests.test_callback_pose_compares_java_float_values)` | a | Synthetic state fixture unnecessarily reads the absent authorized frozen GLB. | Generate explicit synthetic six-face box coordinates; continue running every state mutation offline. |
| ERROR | `test_cross_binding_positive_control (test_model_import_3d_cube_render.StateTests.test_cross_binding_positive_control)` | a | Synthetic state fixture unnecessarily reads the absent authorized frozen GLB. | Generate explicit synthetic six-face box coordinates; continue running every state mutation offline. |
| ERROR | `test_explicit_unavailable_opacity_is_preserved (test_model_import_3d_cube_render.StateTests.test_explicit_unavailable_opacity_is_preserved)` | a | Synthetic state fixture unnecessarily reads the absent authorized frozen GLB. | Generate explicit synthetic six-face box coordinates; continue running every state mutation offline. |
| ERROR | `test_mutated_state_rejected (test_model_import_3d_cube_render.StateTests.test_mutated_state_rejected) (mutation='wrong_history')` | a | Synthetic state fixture unnecessarily reads the absent authorized frozen GLB. | Generate explicit synthetic six-face box coordinates; continue running every state mutation offline. |
| ERROR | `test_mutated_state_rejected (test_model_import_3d_cube_render.StateTests.test_mutated_state_rejected) (mutation='wrong_snapshot')` | a | Synthetic state fixture unnecessarily reads the absent authorized frozen GLB. | Generate explicit synthetic six-face box coordinates; continue running every state mutation offline. |
| ERROR | `test_mutated_state_rejected (test_model_import_3d_cube_render.StateTests.test_mutated_state_rejected) (mutation='wrong_binding')` | a | Synthetic state fixture unnecessarily reads the absent authorized frozen GLB. | Generate explicit synthetic six-face box coordinates; continue running every state mutation offline. |
| ERROR | `test_mutated_state_rejected (test_model_import_3d_cube_render.StateTests.test_mutated_state_rejected) (mutation='stale_consumption')` | a | Synthetic state fixture unnecessarily reads the absent authorized frozen GLB. | Generate explicit synthetic six-face box coordinates; continue running every state mutation offline. |
| ERROR | `test_mutated_state_rejected (test_model_import_3d_cube_render.StateTests.test_mutated_state_rejected) (mutation='wrong_pose')` | a | Synthetic state fixture unnecessarily reads the absent authorized frozen GLB. | Generate explicit synthetic six-face box coordinates; continue running every state mutation offline. |
| ERROR | `test_mutated_state_rejected (test_model_import_3d_cube_render.StateTests.test_mutated_state_rejected) (mutation='wrong_culling')` | a | Synthetic state fixture unnecessarily reads the absent authorized frozen GLB. | Generate explicit synthetic six-face box coordinates; continue running every state mutation offline. |
| ERROR | `test_mutated_state_rejected (test_model_import_3d_cube_render.StateTests.test_mutated_state_rejected) (mutation='wrong_recovery')` | a | Synthetic state fixture unnecessarily reads the absent authorized frozen GLB. | Generate explicit synthetic six-face box coordinates; continue running every state mutation offline. |
| ERROR | `setUpClass (test_model_import_3d_cube_render.VisibilityTests)` | b | Authorized frozen cube.glb absent. | skipUnless on explicit MI3D_RENDER_CUBE_INPUT file. |
| ERROR | `test_cli_accepts_valid_pair_on_real_layout (test_model_import_3d_persistence.PersistencePairCliTest.test_cli_accepts_valid_pair_on_real_layout)` | b | Real preserved build provenance absent; setUp scans nonexistent build directory before skip. | skipUnless on explicit TURBOISM_MI3D_BUILD_RUN path; no implicit build scan. |
| ERROR | `test_cli_rejects_lifecycle_mutation (test_model_import_3d_persistence.PersistencePairCliTest.test_cli_rejects_lifecycle_mutation)` | b | Real preserved build provenance absent; setUp scans nonexistent build directory before skip. | skipUnless on explicit TURBOISM_MI3D_BUILD_RUN path; no implicit build scan. |
| ERROR | `test_cli_runs_and_rejects_wrong_modes (test_model_import_3d_persistence.PersistencePairCliTest.test_cli_runs_and_rejects_wrong_modes)` | b | Real preserved build provenance absent; setUp scans nonexistent build directory before skip. | skipUnless on explicit TURBOISM_MI3D_BUILD_RUN path; no implicit build scan. |
| ERROR | `test_load_result_real_schema (test_model_import_3d_persistence.PersistencePairCliTest.test_load_result_real_schema)` | a | Synthetic archive-loader controls unnecessarily scan nonexistent build directory. | Remove implicit scan; run all three loader controls with synthetic evidence. |
| ERROR | `test_load_result_rejects_extra_persistence_member (test_model_import_3d_persistence.PersistencePairCliTest.test_load_result_rejects_extra_persistence_member)` | a | Synthetic archive-loader controls unnecessarily scan nonexistent build directory. | Remove implicit scan; run all three loader controls with synthetic evidence. |
| ERROR | `test_load_result_rejects_link_member (test_model_import_3d_persistence.PersistencePairCliTest.test_load_result_rejects_link_member)` | a | Synthetic archive-loader controls unnecessarily scan nonexistent build directory. | Remove implicit scan; run all three loader controls with synthetic evidence. |
| ERROR | `setUpClass (test_model_import_3d_whole_import.WholeImportTests)` | b | Authorized cube and compiled producer classpath absent; setUpClass raises RuntimeError. | skipUnless on MI3D_WHOLE_CUBE_INPUT file and MI3D_WHOLE_TEST_CLASSPATH paths. |
| FAIL | `test_java_cross_language_vectors_and_field_mutations (test_model_import_3d_cube_render.FingerprintTests.test_java_cross_language_vectors_and_field_mutations)` | b | Compiled cross-language test classpath absent. | skipUnless on MI3D_RENDER_TEST_CLASSPATH and its paths. |
| FAIL | `test_java_serialized_report_passes_oracle (mi3d_image_binding_tests.ImageBindingProtocolTest.test_java_serialized_report_passes_oracle)` | a | Probe still calls removed PluginContext accessor; Java input fixture assumes the old Json.parseObject return type. | Use services().require(EditorCommandService.class); copy the typed JSON map into a mutable LinkedHashMap. Same test was collected twice. |
| FAIL | `test_java_serialized_report_passes_oracle (test_model_import_3d_image_binding.ImageBindingProtocolTest.test_java_serialized_report_passes_oracle)` | a | Probe still calls removed PluginContext accessor; Java input fixture assumes the old Json.parseObject return type. | Use services().require(EditorCommandService.class); copy the typed JSON map into a mutable LinkedHashMap. Same test was collected twice. |
| SKIP | `test_accepts_complete_producer_report (test_model_import_3d_cube_geometry.CubeProducerOracleTest.test_accepts_complete_producer_report)` | b | requires an explicit synthetic producer report and authorized frozen input | Keep explicit missing-input skip; require the named input files or TURBOISM_MI3D_BUILD_RUN path. |
| SKIP | `test_rejects_changed_source_or_missing_edge_on_face (test_model_import_3d_cube_geometry.CubeProducerOracleTest.test_rejects_changed_source_or_missing_edge_on_face)` | b | requires an explicit synthetic producer report and authorized frozen input | Keep explicit missing-input skip; require the named input files or TURBOISM_MI3D_BUILD_RUN path. |
| SKIP | `test_rejects_missing_binding_and_changed_uv (test_model_import_3d_cube_geometry.CubeProducerOracleTest.test_rejects_missing_binding_and_changed_uv)` | b | requires an explicit synthetic producer report and authorized frozen input | Keep explicit missing-input skip; require the named input files or TURBOISM_MI3D_BUILD_RUN path. |
| SKIP | `test_rejects_missing_pose_and_recovery_failure (test_model_import_3d_cube_geometry.CubeProducerOracleTest.test_rejects_missing_pose_and_recovery_failure)` | b | requires an explicit synthetic producer report and authorized frozen input | Keep explicit missing-input skip; require the named input files or TURBOISM_MI3D_BUILD_RUN path. |
| SKIP | `test_rejects_wrong_geometry_stale_consumption_and_history (test_model_import_3d_cube_geometry.CubeProducerOracleTest.test_rejects_wrong_geometry_stale_consumption_and_history)` | b | requires an explicit synthetic producer report and authorized frozen input | Keep explicit missing-input skip; require the named input files or TURBOISM_MI3D_BUILD_RUN path. |
| SKIP | `test_build_only_real_run_and_missing_t008_dependency (test_model_import_3d_host_validation.ProtocolTest.test_build_only_real_run_and_missing_t008_dependency)` | b | no real build run present | Keep explicit missing-input skip; require the named input files or TURBOISM_MI3D_BUILD_RUN path. |
| SKIP | `test_wrapper_exit_diagnostic_forwarded_on_persistence_leg (test_model_import_3d_host_validation.ProtocolTest.test_wrapper_exit_diagnostic_forwarded_on_persistence_leg)` | b | r22 build run not present in this worktree | Keep explicit missing-input skip; require the named input files or TURBOISM_MI3D_BUILD_RUN path. |

## Runtime and coverage

The final parallel runner executes all **13** independently discovered suites with at most **4** subprocesses. It emits each suite's complete unittest output, elapsed time and a nonzero result for any failed subprocess. No hosts, queues or private assets are used.

- Parallel direct runner: **41.785 s**, **180 tests**, **0 failures / 0 errors / 26 skips**.
- Updated Gradle task: suite wall time **39.648 s**, Gradle total **1m 5s**, literal `BUILD SUCCESSFUL`.
- Serial discovery after repairs/optimizations also passes: **128.755 s**, **180 tests**, **26 skips** (before the final common-fixture construction cache).
- The baseline's host-validation `load_tests` hook collected six other suites again during wildcard discovery. It now adds them only for the historical single-file catalog entrypoint. No unique mutation controls were deleted. Previously failing setUpClass hooks now report each skipped method separately, so baseline and final test totals are not directly comparable.
- The 26 skips are five cube producer controls, eight cube visibility controls, one compiled Java fingerprint control, seven whole-import producer controls, two preserved-build host controls and three preserved-build persistence CLI controls. Synthetic state and archive-loader controls run normally.
- Deterministic PNG bytes are cached, with fresh dictionaries per caller; fixture reports are shared as immutable JSON encodings and decoded into isolated mutable values. Full native admission positive/missing-section controls remain, while single-section mutants call their real section verifier directly.
- Image-binding timeout coverage formerly slept through six 15-second unavailable polls plus the delayed-join poll. The probe retains production clock/sleeper defaults; the SDK fixture injects a monotonic clock and advances virtual time, asserting the exact delayed-join and timeout costs.
- The morph-order expansion walk already builds a fresh tree; its redundant initial full deepcopy was removed. Alpha transparency still checks every alpha byte, now using slices rather than enumerating RGB bytes in Python.
- Real byte-budget boundary tests and the complete JVM producer/assertion suite are retained. JDK compilation, dense joint evaluation and large transport-boundary checks remain the dominant costs.

## Gate decision and usage

Keep `modelImport3dHostToolingTest` opt-in: **39.648 s exceeds the approximately 30 s regular-gate budget**. The task description documents why. `checkHostToolingSelfTest` and `checkCompletedCommit` wiring remain unchanged. The CI task-command expectation is updated only for this Gradle wiring change.

```sh
./gradlew modelImport3dHostToolingTest
python3 validation/model-import-3d-host-probe/test-tooling.py
python3 -m unittest discover -s scripts/test -p 'test_model_import_3d_*.py'
```

External-input controls require explicit environment variables. Missing variables/files skip clearly; supplied malformed evidence still fails validation. No private Cubism assets, models or textures are committed.

The standalone CI-coverage script reports `ungated test: scripts/test/test_reviewed_releases.py`. Replaying that script with HEAD's original Gradle configuration and host-tooling runner reproduces the same failure; it is outside this change's scope.
