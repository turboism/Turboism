# Turboism Architecture

## 1. Product boundary

Turboism is a runtime enhancement tool and plugin framework for Live2D Cubism Editor.
It does not modify or redistribute Cubism, replace its licensing, or authorize access to private Cubism source, resources, binaries, or security mechanisms.

The architecture is organized around one dependency direction:

```text
Plugin -> SDK -> Runtime policy -> versioned Adapter/Provider -> Cubism/Editor
```

Plugins describe user workflows. Runtime owns host access, lifecycle, safety, threading, transactions, diagnostics, and compatibility.

## 2. Modules

The authoritative project list is `settings.gradle.kts`.

```text
:bootstrap
  Thin Java Agent entrypoint and launch artifact.

:sdk
  The only public dependency for first-party and third-party plugins.

:runtime
  Plugin runtime, policies, Cubism/Editor adapters, providers, mapping,
  hook infrastructure, transactions, diagnostics, and shared services.

:plugins:*
  First-party plugins. They are treated like external consumers and depend
  on :sdk with compileOnly scope.

:testframework
  Fake hosts, fixtures, and reusable test support.

:tests
  Cross-module and packaged integration tests.
```

The platform uses coarse Gradle modules and package-level internal organization. A new conceptual area does not automatically justify a new Gradle module.

## 3. Public API model

SDK APIs use Turboism-owned types only. They must not expose:

- `com.live2d.*` classes;
- host UI widgets;
- native handles;
- host ClassLoaders;
- unrestricted filesystem paths;
- runtime implementation types;
- mutable arrays whose ownership belongs to Cubism.

New SDK APIs are Preview by default. Stable compatibility is reserved for reviewed public contracts, security boundaries, metadata formats, and APIs with real external consumers.

Ordinary Preview additions do not require a new capability row, permission, schema, ADR, or migration report.

## 4. Unified Cubism object API

Turboism should cover the useful public Cubism object model without mirroring its ownership hazards.

Representative shape:

```java
CubismModel model = context.cubism().model().active();
CubismParameter parameter = model.parameters().find(ParameterId.of("ParamAngleX"));

float value = parameter.getValue();
parameter.setValue(value + 1.0f);
```

The public surface is object-oriented and unified. Read and write operations are not split into multiple user-visible planes merely to reflect internal implementation layers.

Internally, the runtime distinguishes ownership and execution paths:

```text
read/evaluation result
  -> Core-backed or Editor-backed provider

Editor-attached authoring write
  -> Editor transaction
  -> validation and stale checks
  -> Undo / dirty-state integration
  -> version-specific provider
  -> resulting Core evaluation

Turboism-owned detached model
  -> owned-model provider and lifecycle
```

For an Editor-attached model, Editor authoring state is the only write source of truth. Turboism does not maintain bidirectional synchronization between a separate Core mutation state and Editor state.

Core remains responsible for evaluation, rendering-facing state, and result reads. A natural `setValue` call is routed through the Editor authoring path when the object belongs to an Editor document.

## 5. Reference lifecycle

Model objects and child references are bound to a session/document generation.
They must fail closed after events such as:

- project close;
- document switch;
- model reload;
- object deletion;
- plugin disable;
- provider replacement;
- unsupported host-version transition.

Stale failures are typed and diagnostic. The runtime must not guess a replacement host object from an old reference.

## 6. Invocation and event lifecycle

Wrapped operations use one consistent lifecycle:

```text
before -> invoke -> on state change -> after completion
```

Semantics:

```text
before
  Runs before the operation. For value-setting hooks, each override returns the
  value passed to the next hook and the final value is sent to the native call.
  The initial API does not expose a generic cancellation or context/result type.

on
  Runs only when observable state actually changed.
  It is a notification and does not rewrite the completed result.

after
  Runs after normal invocation completion, including no-change completion where
  the operation contract permits it. It performs post-processing and observation,
  not a second mutation of the completed call.
```

Representative naming:

```text
beforeSetParameterValue(parameter, value)
onParameterValueChanged(parameter, oldValue, newValue)
afterSetParameterValue(parameter, value)
```

The same naming grammar should be applied across parts, drawables, deformers, project operations, selection, UI contributions, and semantic Editor commands.

## 7. Host-semantic operations

Some operations cannot be represented safely as ordinary property access. Examples include:

- grouped parameter changes;
- mesh or deformer transformations;
- PSD binding repair;
- ordered clip-mask replacement;
- project import/export;
- selection navigation;
- theme application and restoration;
- semantic host UI operations.

These remain typed operations owned by runtime adapters. They share the same object API, transaction, event, permission, and diagnostic model; they are not free-form string commands.

## 8. Permissions and capabilities

Permissions describe risk boundaries rather than individual methods:

```text
turboism.cubism.read
turboism.cubism.write
turboism.user-file.read
turboism.user-file.write
turboism.network
turboism.process
turboism.host.unsafe
```

Routine UI contributions, local configuration, plugin storage, localization, diagnostics, events, and bounded tasks are governed primarily through ownership, namespace, quota, lifecycle, and cleanup.

The terms are independent:

```text
permission  authorization to cross a risk boundary
capability  provider and host-version support for a feature family
operation   a concrete invocation and diagnostic identity
```

No one-to-one mapping is required.

## 9. Hook policy

Hooks are connection mechanisms, not the default implementation model.
A hook is introduced only when explicit reads, refresh, bounded polling, or an adapter callback cannot satisfy the required behavior.

Every production hook requires:

- a demonstrated consumer and necessity;
- exact version/mapping selection;
- bounded callback work;
- coalescing or backpressure where events can burst;
- kill switch and safe mode;
- plugin-disable and document-close cleanup;
- diagnostics;
- manual performance evidence for critical paths.

Plugins never register bytecode transformers directly.

## 10. Transactions and writes

Editor authoring writes must provide:

- host-thread dispatch;
- validation before mutation;
- single or grouped Undo;
- atomic commit where the operation claims atomicity;
- rollback or explicit partial-failure semantics;
- stale-target rejection;
- cleanup on project close, model reload, and plugin disable;
- structured results and diagnostics.

A write permission grants access to the write boundary; it does not bypass operation-specific validation.

## 11. Verification model

Verification is layered:

```text
check
  Compile, unit tests, module boundaries, plugin metadata, Stable SDK compatibility.

checkIntegration
  Runtime/plugin integration, preview agent, bundle and report validation.

checkRelease
  Integration plus supply-chain, API tooling, packaging and release checks.

checkLegacyGovernance
  Historical migration ledgers and retired evidence consistency only.
```

Real product readiness additionally requires supported Cubism-version observation. Fake providers and document self-consistency do not promote a provider to real-host readiness.
