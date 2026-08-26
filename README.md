# Turboism

Turboism is a Windows-first runtime enhancement tool and plugin framework for
Live2D Cubism Editor. It uses Java 17, a thin Java Agent bootstrap, a
version-routed runtime, and SDK-only first-party plugins.

[Download the latest release](https://github.com/Turboism/Turboism/releases/latest)
· [Changelog](CHANGELOG.md)

## Compatibility

| Item | Status |
| --- | --- |
| Host platform | Windows x64 is the supported Cubism host platform. |
| Cubism Editor | Exact-version adapters for **5.2.03** and **5.3.02**. Other versions fail closed and are not claimed compatible. |
| Installer | NSIS `.exe` is preferred on Windows; Lite/Full Windows ZIPs and a Java 17 IzPack installer are also published. |
| macOS | Java installer/payload packaging is preview-only; Cubism host readiness is not claimed. |
| Linux | Installer/payload semantics only; running Cubism Editor as a Turboism host is unsupported. |

Turboism does not bundle Cubism Editor. A separately installed, licensed copy
of a supported version is required. Published installers are currently
unsigned; verify the accompanying `.sha256` files after downloading.

## Current capabilities

The 0.42.0 release includes:

- a Java 17 agent runtime, public plugin SDK, lifecycle, permissions,
  configuration, localization, tasks, events, actions, menus, toolbars,
  workspace services, transactions, Undo-aware Editor writes, and
  exact-version host adapters;
- a built-in Turboism menu, toolbar, settings UI, plugin manager, package
  inspection, and Plugin Directory integration;
- parameter batch transfer with optional inversion;
- mesh-edit mirror-axis enhancements;
- Recent Files hover thumbnails;
- clip-mask inspection, Cubism palette filtering, palette label styling,
  scene-palette ordering, texture-atlas statistics and MaxRects-BSSF layout;
- physics-editor workflow enhancements, UI themes, performance statistics,
  WebDAV auto-backup, and a loopback MCP server;
- an isolated, permission-checked Graal script host when a compatible GraalVM
  runtime is available.

Capabilities remain subject to plugin permissions, active document/session
state, exact Cubism version availability, and verified host evidence. Turboism
fails closed when a required adapter or host capability is unavailable.

## Install on Windows

Download one of the following from the
[latest GitHub Release](https://github.com/Turboism/Turboism/releases/latest):

- `TurboismInstaller-<version>.exe` — recommended Windows installer;
- `turboism-<version>-full.zip` — agent plus the reviewed first-party plugin bundle;
- `turboism-<version>-lite.zip` — agent and common runtime without plugin JARs;
- `TurboismInstaller-<version>.jar` — Java 17 installer, primarily an alternate packaging route.

Each artifact has a sibling SHA-256 sidecar. After downloading both files,
verify from their directory, for example:

```bash
sha256sum -c TurboismInstaller-0.42.0.exe.sha256
```

Run `configure_turboism.ps1` after installation to discover or select Cubism
Editor 5.2.03 / 5.3.02 installations and create Turboism-owned launch
shortcuts. The launcher invokes the official `CubismEditor5.bat`; it does not
modify the Cubism installation.

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
:testing:test-support
:testing:integration-tests
```

First-party plugins follow the same boundary as third-party plugins: they depend on `:sdk` with `compileOnly` scope and do not access runtime or `com.live2d.*` types directly.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the current design.

## Current direction

Turboism is developed by product and framework capability rather than numbered migration phases:

- a unified Turboism-owned Cubism object API;
- Editor-owned authoring writes with transaction and Undo support;
- consistent `before` / `on` / `after` invocation semantics;
- exact-version Cubism 5.2.03 and 5.3.02 providers;
- semantic project, selection, model-tree and UI adapters;
- SDK-only restoration of official Parameter, Mesh, PSD, UI and performance workflows;
- production hooks only where explicit APIs and callbacks are insufficient.

See [ROADMAP.md](ROADMAP.md) for the active order.

## Verification

During implementation, run the narrowest affected compile or test task, for example:

```bash
./gradlew :sdk:test --tests '<affected test class>'
./gradlew :runtime:test --tests '<affected test class>'
./gradlew :plugins:<plugin>:test
```

After a meaningful implementation slice is coherent, run the fast structural gate:

```bash
./gradlew devCheck
```

A bare multi-project `check` is intentionally not the daily command because it expands every subproject's test task.

Runtime and packaged integration:

```bash
./gradlew checkIntegration
```

Full automated verification for a coherent completed change:

```bash
./gradlew checkCompletedCommit
```

`checkCompletedCommit` includes ordinary tests, integration, documentation and metadata checks, API-tool selftests, and repository hygiene selftests. It is the normal completed-change gate.

Release-oriented verification:

```bash
./gradlew checkRelease \
  -PinstallerVersion=<release-version> \
  -PturboismRelease=true
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

Turboism publishes one public SDK tier. Removing the former preview marker does not remove or disable implemented SDK functionality. Before the first formal release, maintainers review the generated public classfile surface; the first released SDK artifact establishes the compatibility baseline for later releases.

Cubism Editor version availability is declared with `@CubismEditor` at public type or method boundaries and with exact-version catalogs where finer command granularity is required. Permissions, active-session state, verified adapters, and backend capabilities remain independent runtime checks.

Ordinary additive getters and setters do not require a dedicated capability row, permission, schema, ADR, or migration report. Permissions describe real risk boundaries rather than individual methods.

## Cubism and Editor state

Turboism aims to expose natural object APIs such as:

```java
CubismModel model = context.cubism().model().active();
CubismParameter parameter = model.parameters().find(ParameterId.of("ParamAngleX"));

parameter.setValue(parameter.getValue() + 1.0f);
```

For an Editor-attached model, the Editor authoring model remains the only write source of truth. Runtime routes writes through validation, host-thread dispatch, transaction, Undo, dirty-state handling and version-specific providers. Cubism Core is used for evaluation and result reads rather than as a second independently synchronized authoring state.

## Documentation tracking

`docs/`, `cubism-ref/`, `evidence/`, `generated-references/`, `host-evidence/`, and `research/` are local-only so research notes, licensed references, generated reports, host evidence, AI review artifacts, and machine-specific paths do not enter remote history. Repository builds, tests, and release tooling depend only on tracked product sources and public contracts such as `compatibility/cubism/`, `sdk/api-contracts/`, reproducible probe source under `validation/`, and `packaging/`. Generated reports are written under `build/reports/`.

## Compliance

Turboism does not distribute Cubism Editor, replace its licensing, or authorize copying private Cubism source, resources, binaries, decompiled method bodies, or authorization-bypass logic.

## Prerequisites

- A JDK 17 toolchain (the agent and runtime are compiled with `-release 17`).
- A separately installed, licensed copy of Live2D Cubism Editor for exact-host
  validation. Turboism does not bundle or install Cubism.

## Documentation

Public documentation lives at <https://docs.turboism.dev>. `ARCHITECTURE.md`
and `ROADMAP.md` in this repository describe the current design and direction.

## Non-affiliation

Turboism is an independent project. It is not affiliated with, endorsed by, or
sponsored by Live2D Inc. "Live2D" and "Cubism" are trademarks of Live2D Inc.;
Turboism uses those names only to describe interoperability.
