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

Turboism is no longer developed as an M1–M16 migration program. The active work is organized around product and framework capability:

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
./gradlew check
```

Runtime and packaged integration:

```bash
./gradlew checkIntegration
```

Release-oriented verification:

```bash
./gradlew checkRelease
```

Retired migration ledgers and historical closure evidence are available through an explicit opt-in task only:

```bash
./gradlew checkLegacyGovernance
```

They are not part of the default development gate.

## API policy

New SDK APIs are Preview by default. Stable compatibility is enforced for reviewed public contracts, plugin metadata, security boundaries, and APIs with real external consumers.

Ordinary getters, setters and Preview additions do not require a dedicated capability row, permission, schema, ADR or migration report. Permissions describe real risk boundaries rather than individual methods.

## Cubism and Editor state

Turboism aims to expose natural object APIs such as:

```java
CubismModel model = context.cubism().model().active();
CubismParameter parameter = model.parameters().find(ParameterId.of("ParamAngleX"));

parameter.setValue(parameter.getValue() + 1.0f);
```

For an Editor-attached model, the Editor authoring model remains the only write source of truth. Runtime routes writes through validation, host-thread dispatch, transaction, Undo, dirty-state handling and version-specific providers. Cubism Core is used for evaluation and result reads rather than as a second independently synchronized authoring state.

## Historical migration material

`docs/migration/` contains historical inventories, plans, reports and evidence from the framework rewrite. It is retained for traceability but is not an active roadmap, API approval chain or source of default build requirements.

## Compliance

Turboism does not distribute Cubism Editor, replace its licensing, or authorize copying private Cubism source, resources, binaries, decompiled method bodies, or authorization-bypass logic.
