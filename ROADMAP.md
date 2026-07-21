# Turboism Product Roadmap

This roadmap replaces the retired M1–M16 migration sequence. Work is ordered by product and framework capability, not by historical document closure.

## Current objective

Build a usable, version-routed Cubism and Editor API that third-party plugins can consume naturally, then reconnect official modeling workflows through that API.

The target interaction model is:

```text
CubismModel model = context.cubism().model().active();
CubismParameter parameter = model.parameters().find(ParameterId.of("ParamAngleX"));

parameter.setValue(parameter.getValue() + 1.0f);
```

The runtime may use different providers internally, but plugins should not have to choose among read, write, command, adapter, and synchronization planes for an ordinary object operation.

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
- real-host smoke and regression checklist;
- packaging and supply-chain checks;
- no shared mutable launcher or global installation side effects;
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
8. add only the hooks proven necessary by real consumers
9. harden third-party SDK and release packaging
```

## Not used as progress measures

The following no longer determine work order or completion:

- M1–M16 phase numbers;
- migration-board row counts;
- capability catalog size;
- one permission per method;
- fake-ready labels;
- closure reports that only validate other reports;
- mandatory PRD/schema/report creation for ordinary Preview API changes.

Progress is measured by usable SDK surfaces, real providers, safety invariants, real-host behavior and complete user workflows.
