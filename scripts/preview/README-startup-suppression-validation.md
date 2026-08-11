# Turboism Startup Suppression Host Validation

Exact-host verification that the premain startup suppression (skip splash /
skip update check / skip information) is genuinely effective on official
Cubism 5.2.03 and 5.3.02 installations.

The suppression is applied by `StartupSuppressionInstaller` during premain,
before any plugin loads, so the probe plugin verifies the observable result
of the transformation:

- `CECubismEditorApp.e()` (the private splash factory) returns `null` — the
  transformed body is `ACONST_NULL; ARETURN`; an untransformed body would
  create and show the native splash window;
- the startup entry `CECubismEditorApp.a(String[])` still exists — the class
  was transformed in place, not replaced;
- the preview runtime report reached MATCHED/READY/RUNNING, proving the host
  booted normally under the transformed startup path.

The update-check and information calls are removed in the **same atomic**
`StartupSuppressionTransformer.transformClass` pass that rewrites `e()`;
the premain console lines `STARTUP_SUPPRESSION_TRANSFORM_TRANSFORMED` and
`status=INSTALLED, effectiveUpdate=true ...` are collected from
`cubism-console.txt` as the transformation evidence. The probe never mutates
the model, never creates Undo, and touches no authoring state.

## Build

```bash
./gradlew previewBundle :sdk:jar
bash validation/startup-suppression-host-probe/build.sh
```

Artifacts:

- `build/preview/<worktree-id>/turboism-agent.jar`
- `build/startup-suppression-host-validation-exerciser.jar`
- `validation/startup-suppression-host-probe/home-config.json`
  (`hooks.startup.skipUpdateCheck/skipSplash/skipInformation = true`)

## Usage

```bash
# Parse-only review (no SSH, no Cubism launch)
bash scripts/preview/run-startup-suppression-host-validation.sh 5302 review --dry-run

# Real-host runs, one version per session (serial; do not run in parallel)
bash scripts/preview/run-startup-suppression-host-validation.sh 5302 r1
bash scripts/preview/run-startup-suppression-host-validation.sh 5203 r1
```

The wrapper delegates to the generic runner
`scripts/preview/run-cubism-host-validation.sh`; it never launches Cubism's
`java.exe` directly. It stages `home-config.json` into the task-scoped
Turboism home before the official `CubismEditor5.bat` launch via the generic
runner's `--home-config <local-config.json>` option (any validation that must
seed `<home>/config.json` before premain can use it; the option is additive
and defaults to no injection).

## Evidence

Remote task root: `<local-home>/TurboismValidation/startup-suppression/<version>-<label>/<taskId>/`
Local evidence: `build/host-validation/startup-suppression/<version>/<taskId>/`

Key files:

- `cubism-console.txt` — premain lines:
  `STARTUP_SUPPRESSION_ARTIFACT_HASH_MILLIS_*`,
  `STARTUP_SUPPRESSION_INSTALLED_<version>`,
  `Turboism startup suppression status=INSTALLED, ... effective*=true`,
  `STARTUP_SUPPRESSION_TRANSFORM_TRANSFORMED`
- `result/startup-suppression-result.properties` — structured probe result
  (`terminal=PASS|FAIL`, `splash.assertion/expected/actual/status`, failures)
- `identity-before.properties`, `cloned-identity.properties`,
  `final-hashes.properties` — exact JAR/BAT/fixture identity, unchanged
  before and after the run
- `turboism.log` — `STARTUP_SUPPRESSION_VALIDATION_READY`,
  `STARTUP_SUPPRESSION_VALIDATION_RESULT status=PASS|FAIL`

Reviewed host identities (must not change without human re-approval):

- Cubism 5.2.03 JAR SHA-256 `bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd`
- Cubism 5.3.02 JAR SHA-256 `988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21`

## Known boundaries

- Only the non-ANGLE `Live2D_Cubism.jar` code source is pinned; an ANGLE
  variant launch fails closed (no transformation) instead of mis-transforming.
- The suppression requires a schema-valid `<home>/config.json`; otherwise the
  runtime reports `STARTUP_SUPPRESSION_CONFIG_*` diagnostics and stays
  disabled.
