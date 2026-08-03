# Turboism

Turboism is a runtime enhancement tool and plugin framework for Live2D Cubism Editor.
It uses Java 17, a thin Java Agent bootstrap, a version-routed runtime, and SDK-only first-party plugins.

## Architecture

```text
Plugin -> SDK -> Runtime policy -> versioned Adapter/Provider -> Cubism/Editor
```

The authoritative module list is `settings.gradle.kts`:

```text
:bootstrap
:runtime
:sdk
:plugins:*
:testframework
:tests
```

First-party plugins follow the same boundary as third-party plugins: they depend on `:sdk` with `compileOnly` scope and do not access runtime or `com.live2d.*` types directly.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the current design.

## Current direction

Turboism is developed by product and framework capability rather than numbered migration phases:

- a unified Turboism-owned Cubism object API;
- Editor-owned authoring writes with transaction and Undo support;
- consistent `before` / `on` / `after` invocation semantics;
- version-routed Cubism 5.3.02 providers;
- semantic project, selection, model-tree and UI adapters;
- SDK-only restoration of official Parameter, Mesh, PSD, UI and performance workflows;
- production hooks only where explicit APIs and callbacks are insufficient.

See [ROADMAP.md](ROADMAP.md) for the active order.

## Verification

Daily development:

```bash
./gradlew devCheck
```

Run focused tests named by the current SDD acceptance conditions after the feature slice is coherent. A bare multi-project `check` is intentionally not the daily command because it expands every subproject's test task.

Runtime and packaged integration:

```bash
./gradlew checkIntegration
```

Release-oriented verification:

```bash
./gradlew checkRelease
```

Exact-host validation is opt-in and automation-first. `scripts/preview/run-cubism-host-validation.sh` is the shared exact-host runner: it clones a task-scoped Proton prefix, stages any test-only SDK plugins, launches the official `CubismEditor5.bat`, polls structured readiness/results, collects hashes and logs, and cleans up only the current process tree. Feature wrappers provide their own plugins and assertions:

```bash
./gradlew validateParameterHost5302
./gradlew validateParameterHost5203 -PturboismHostValidationMode=binding-matrix
./gradlew validateThemeHost5302
./gradlew validateThemeHost5203
```

Use `bash scripts/preview/run-cubism-host-validation.sh --help` for a new validation plugin. Screenshots are reserved for visual-only assertions or targeted failure diagnosis.

## API policy

New SDK APIs are Preview by default. Promotion to Stable is explicit release work and must establish a compatibility baseline from the released artifact; historical phase snapshots are not reused as current gates.

Ordinary getters, setters and Preview additions do not require a dedicated capability row, permission, schema, ADR or migration report. Permissions describe real risk boundaries rather than individual methods.

## Cubism and Editor state

Turboism aims to expose natural object APIs such as:

```java
CubismModel model = context.cubism().model().active();
CubismParameter parameter = model.parameters().find(ParameterId.of("ParamAngleX"));

parameter.setValue(parameter.getValue() + 1.0f);
```

For an Editor-attached model, the Editor authoring model remains the only write source of truth. Runtime routes writes through validation, host-thread dispatch, transaction, Undo, dirty-state handling and version-specific providers. Cubism Core is used for evaluation and result reads rather than as a second independently synchronized authoring state.

## Local documentation

`docs/` and `docs_internal/` are local-only working areas and are ignored by Git. Repository builds, tests, and release tooling must depend only on tracked code and machine assets such as `cubism-ref/`, `validation/`, and `packaging/`.

## Compliance

Turboism does not distribute Cubism Editor, replace its licensing, or authorize copying private Cubism source, resources, binaries, decompiled method bodies, or authorization-bypass logic.
