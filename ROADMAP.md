# Turboism Product Roadmap

Work is ordered by product and framework capability. Track labels are parallel product concerns, not milestones or release gates, and must not evolve back into M13/M14-style phase governance.

## Current objective

Build a usable, version-routed Cubism and Editor API that third-party plugins can consume naturally, then reconnect official modeling workflows through that API.

Legacy repositories remain read-only behavior evidence rather than a new-framework skeleton. Extraction is performed per usable feature slice, without repository-wide phase closure.

The target interaction model is:

```text
CubismModel model = context.cubism().model().active();
Parameter parameter = model.parameters().find(new ParameterId("ParamAngleX"));

parameter.setValue(parameter.getValue() + 1.0f);
```

The runtime may use different providers internally, but plugins should not have to choose among read, write, command, adapter, and synchronization planes for an ordinary object operation.

Two axes define the current program and must land together:

```text
Core / Editor unification
  one SDK object graph
  Editor authoring state as the write truth
  Core evaluated state as the rendering/evaluation truth
  verified identity and generation joins

Semantic event lifecycle
  one canonical operation
  before -> native operation -> changed-only on -> after
  override-based Hooks discovered from plugin entrypoints
  no duplicate Core, Editor and plugin event sources
```

Tracks A–C are therefore one integration program, not three independently shippable systems. Core reads without Editor joins are an incomplete backend; Editor writes without the unified event lifecycle are incomplete operations; an event system around raw Core setters is invalid.

## Track A — Unified Cubism object API

### Goal

Provide Turboism-owned wrappers for the useful public Cubism model surface:

- model and canvas;
- parameters;
- parts;
- drawables, geometry, masks, colors and render order;
- deformers;
- glues;
- rendering and supported offscreen features;
- supported version and feature discovery.

### Rules

- no `com.live2d.*` leakage;
- no raw native handles;
- no caller-owned mutable views over host arrays;
- natural object methods;
- session/document generation checks;
- version differences absorbed by providers;
- unsupported members return typed unavailability rather than guessed behavior.

### Exit

A plugin can enumerate and read the complete supported object model on Cubism 5.3.02 through SDK-only code, with provider and stale-reference tests.

## Track B — Editor-owned authoring writes

### Goal

Make the same object API writable without creating a second model state.

For an Editor-attached model:

```text
parameter.setValue(value)
  -> runtime validation
  -> Editor transaction
  -> Undo / dirty state
  -> version-specific authoring provider
  -> resulting Core evaluation
```

Core mutation is not used as an independent source of authoring truth. No bidirectional synchronization layer is introduced.

### Foundation

- host-thread dispatch;
- transaction ownership;
- single and grouped Undo;
- rollback and partial-failure policy;
- stale target rejection;
- project close/model reload cleanup;
- typed write result and diagnostics;
- broad Cubism read/write permission boundary.

### Exit

Parameter and part writes pass real Cubism 5.3.02 manual validation, including Undo, redo, save/reopen consistency, document switching, and plugin disable cleanup.

## Track C — Invocation and event standard

### Goal

Wrap native and framework operations with one naming and lifecycle standard.

```text
before<Action>
  may rewrite arguments by returning the next value

on<State>Changed
  fires only after actual state change

after<Action>
  observes normal invocation completion
```

First reference family:

```text
beforeSetParameterValue
onParameterValueChanged
afterSetParameterValue
```

### Required semantics

- deterministic plugin and entrypoint ordering;
- sequential `before` return-value chaining;
- no generic cancellation/result wrapper in the initial API;
- no `on...Changed` event when effective state did not change;
- reentrancy policy;
- exception isolation;
- plugin lifecycle cleanup;
- Undo/redo origin metadata where available;
- bounded callback execution on Editor-critical paths.

### Exit

Parameter value changes work through direct calls, host UI edits, Undo and redo with consistent event outcomes and no duplicate notification.

## Track D — Editor semantic API

### Goal

Expose Editor-specific state and operations that Cubism Core cannot represent:

- project and workspace;
- active document/model;
- selection;
- model tree and object relationships;
- file chooser and user grants;
- context source;
- menu, toolbar, dialog, panel and overlay contribution;
- status and notifications;
- semantic host commands that require Editor ownership.

UI remains a separate SDK area from the Cubism object model, but both use the same lifecycle, permission and diagnostic conventions.

### Exit

Plugins can implement project inspection, selection navigation, status/UI contribution and file workflows without Swing tree traversal or raw host objects.

## Track E — Official workflow restoration

Official functionality is restored through SDK-only plugins after its required framework surface exists.

Priority families:

1. parameter CSV import/export and parameter batch tools;
2. context menu, project panel and workspace helpers;
3. color picker, theme and log-filter UI semantics;
4. clip-mask and bounding-box inspection;
5. mesh and ArtMesh mirror;
6. deformer fit/mirror/apply-to-children;
7. PSD binding repair and canvas expansion;
8. render/performance and texture-atlas assistance.

Each workflow must prove user-visible behavior, not merely plugin loading or fake-provider compatibility.

## Track F — Hook and render ingress

Hooks are implemented only for behavior that cannot be supported by explicit reads, refresh, bounded polling, Editor providers or existing callbacks.

Candidate areas:

- project/document lifecycle observation;
- context-menu opening;
- parameter mutation interception;
- Undo/redo refresh;
- render status and per-frame ingress.

Every hook requires necessity evidence, exact version routing, bounded execution, backpressure, kill switch, safe mode, cleanup and performance observation.

## Track G — Third-party and release readiness

### Framework readiness

- Stable SDK compatibility policy;
- plugin dependency and ClassLoader behavior;
- diagnostics and disabled-reason reporting;
- plugin storage, config, localization, tasks and user-file services;
- developer documentation and examples;
- predictable preview-to-stable promotion.

### Product readiness

- relocatable preview/release bundle;
- isolated launcher;
- supported Cubism version matrix;
- automated exact-host validation through test-only SDK plugins and host scripts;
- packaging and supply-chain checks;
- no shared mutable launcher or global installation side effects;
- machine-readable host evidence, with screenshots reserved for visual-only facts or failure diagnosis;
- compliance review before public release.

## Near-term order

```text
1. finalize unified object/reference model
2. implement complete Core-backed reads and 5.3.02 provider routing
3. establish Editor-owned parameter/part writes with Undo
4. implement before/on/after parameter lifecycle
5. complete Editor project/selection/model-tree API
6. restore parameter CSV and batch workflows
7. expand write families to drawable/deformer/glue operations
8. automate exact-host SDK/write/Undo/persistence matrices for each supported version
9. add only the hooks proven necessary by real consumers
10. harden third-party SDK and release packaging
```

Current implementation checkpoint:

- the unified Preview object/reference model is present and the complete supported Core read graph is projected for 5.2 and 5.3.02 through exact digest-pinned resolver evidence;
- Canvas, Parameters, Parts, Drawables, Deformers and Glues use immutable copied data, version normalization and generation-bound stale rejection without leaking or closing host objects;
- Cubism 5.3.02 Editor authoring parameters are bound through verified active-document/model selectors and invalidate on document/model-instance replacement;
- `Parameter.setValue` uses the verified Editor palette operation, plugin-scoped write permission, native `GroupUndo`/`SimpleUndo` history, changed-only dirty state, parameter/canvas refresh, failure cancellation and stale-target rejection;
- the Runtime-owned parameter lifecycle discovers ordered `ParameterHooks` from real plugin entrypoints, separates intercept/observe permissions, chains synchronous `before`, dispatches bounded changed-only `on` and normal `after`, rejects recursion, and removes Hook references before ClassLoader release;
- the Java agent has an exact-selector, host-ClassLoader-scoped ASM transformer for the verified palette operation so plugin, UI and supported internal origins converge on one lifecycle; facade/native correlation prevents duplicate publication;
- the official parameter CSV workflow now implements `CubismPlugin`; export and import use only the unified `CubismModel`/`Parameter` object graph, and import reaches `Parameter.setValue` without `cubismRead`, `ModelTransaction`, or `WriteParameterCommand`;
- synthetic transformer normal/failure recovery, queue saturation, in-flight unload quiescence, rapid 200-generation model switching, isolated-ClassLoader lifecycle cleanup, and official action/permission integration tests pass;
- the daily verification entry point is being reduced to `./gradlew devCheck`; integration and release gates remain explicit, batched commands;
- Cubism 5.3.02 Windows validation confirmed direct parameter reads/writes, metadata updates and Undo/Redo, but remaining parameter-binding and 5.2 checks must be converted from manual inspection into terminal machine-readable host matrices before they can be treated as maintained readiness evidence.

```text
1. make the parameter validation bundle launch through the official Editor launcher and emit a terminal structured result
2. automate 5.3.02 parameter read/write/lifecycle, Undo/Redo, save/reopen and performance assertions in one isolated session
3. add equivalent 5.2 Editor binding evidence without weakening exact-version admission
4. extend Editor binding and lifecycle to Part opacity and model update from verified consumers
5. complete Editor project/selection/model-tree API
6. expand write families only from verified official-plugin consumers
7. harden third-party SDK, packaging and release evidence
```

## Not used as progress measures

The following no longer determine work order or completion:

- numbered migration or implementation phases;
- migration-board row counts;
- capability catalog size;
- one permission per method;
- fake-ready labels;
- closure reports that only validate other reports;
- mandatory PRD/schema/report creation for ordinary Preview API changes.

Progress is measured by usable SDK surfaces, real providers, safety invariants, real-host behavior and complete user workflows.
