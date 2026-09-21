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
  Thin Java Agent entrypoint and launch artifact. The shipped fat JAR relocates
  every private implementation library (Jackson, ASM, Resilience4j, SLF4J) under
  dev.turboism.agent.shaded.*, so its Boot-Class-Path entry no longer exposes
  those package names to host or plugin classloaders. dev.turboism.* framework
  types stay unrelocated: bootstrap-injected bridges and the single shared SDK
  type identity depend on it. This is not a sandbox for untrusted plugins.

:sdk
  The only public dependency for first-party and third-party plugins.

:core-contract
  Internal management and composition contracts (dev.turboism.internal.core.*)
  shared by the runtime and framework shell: plugin management, updates, JVM
  and feature settings, ShellServices, and ShellAdmission/ShellHandle. Depends
  only on :sdk. These are not plugin-facing APIs; ordinary plugins cannot
  compile against them and their classloaders refuse dev.turboism.internal.*.

:runtime
  Plugin runtime, policies, Cubism/Editor adapters, providers, mapping,
  hook infrastructure, transactions, diagnostics, shared services, and the
  framework shell (`dev.turboism.shell`): the built-in Turboism menu,
  main-toolbar home entry, embedded panel, settings, logs, About, plugin
  management, and update hints. The shell is runtime-owned framework code,
  not a plugin; it consumes the same PluginContext surfaces plugins use
  under the reserved `turboism.core` identity, which external packages
  remain forbidden from declaring. Runtime production dependencies never include
  :plugins:*. Explicit null shell admission runs headless; the boundary gate
  removes dev/turboism/shell/** from a real runtime JAR before loading and
  closing an independently compiled external plugin.

:plugins:*
  First-party plugins. They are treated like external consumers and depend
  on :sdk with compileOnly scope.

:testing:test-support
  Fake hosts, fixtures, and reusable test support.

:testing:integration-tests
  Cross-module and packaged integration tests.
```

The platform uses coarse Gradle modules and package-level internal organization. A new conceptual area does not automatically justify a new Gradle module.

Package ownership is intentionally non-overlapping:

```text
dev.turboism.sdk.cubism.hook
  Override-based plugin lifecycle hooks; no registration bus.

dev.turboism.sdk.cubism.event
  Cubism and Editor semantic event families, including per-object lifecycle
  payloads, selection transitions and the CubismOperationEvent correlation
  payload. Runtime-owned domain families also live in sdk.action,
  sdk.appearance, sdk.cubism.backup, sdk.performance, sdk.runtime and
  sdk.ui.table; delivery permissions and supported origins are documented
  in sdk/event-coverage.md.

dev.turboism.sdk.event
  Generic event transport, EventBus contracts, and the top-level TurboismEvent
  marker that every shipped event family implements.

dev.turboism.sdk.cubism.id
  Shared identities used across reads, queries, events and transactions.

dev.turboism.core.runtime
  Scheduling policy, timers and cancellation context.

dev.turboism.core.runtime.work
  Per-plugin bounded execution, admission, backpressure, timeout and circuit breaking.

dev.turboism.core.runtime.sidecar
  Isolated heavy-work dispatch and supervision.
```

Deprecated package shapes such as `sdk.event.cubism`, `sdk.cubism.callback`, feature-local
`DocumentId`, and callback-named plugin work executors are not compatibility
surfaces and must not be reintroduced.

Several plugins also keep a `b1/` package tree (`b1/domain`, sometimes
`b1/application`). `b1` marks a legacy-plugin migration wave, not a
host-adaptation or compatibility surface: `b1/domain` holds pure, deterministic
behavior and state declarations salvaged from the pre-SDK codebase (value
objects, enums, reducers), while `b1/application` is reserved for typed config
and lifecycle orchestration. B1 code may depend only on the JDK,
`dev.turboism.sdk.*`, and same-plugin classes — never on
runtime/core/hook/mapping/adapter/preview packages, `com.live2d.*`, or host
I/O. When a behavior graduates out of the migration wave, move it to a stable
plugin-owned package name rather than treating `b1` as permanent structure.

## 3. Public API model

SDK APIs use Turboism-owned types only. They must not expose:

- `com.live2d.*` classes;
- host UI widgets;
- native handles;
- host ClassLoaders;
- unrestricted filesystem paths;
- runtime implementation types;
- mutable arrays whose ownership belongs to Cubism.

Turboism publishes one public SDK tier. Before the first formal release, maintainers review the generated public classfile surface without treating a pre-release snapshot as a compatibility promise. The first released SDK artifact establishes the compatibility baseline for later releases. Cubism Editor version restrictions are declared separately with `@CubismEditor` and exact-version catalogs.

Exact API baselines accumulate one per reviewed SDK revision and stay in release verification. A baseline may be retired only once a stable (1.x) SDK baseline supersedes it, and the retirement must be recorded in that release's notes; the newest baseline is never retired.

Sole documented exception to the no-UI-type surface rule: `dev.turboism.sdk.ui.window.TurboismWindowFactory` constructs plugin-owned JDK Swing windows and applies the Turboism window icon; it is not part of the `UiHostCapabilityService` host contract. No other package may expose JDK UI types.

Ordinary additive SDK APIs do not require a dedicated capability row, permission, schema, ADR, migration report, promotion step, or pre-release baseline re-issue. Those mechanisms remain required only when the underlying security, persistence, compatibility, or migration boundary actually changes.

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

Third-party public event contracts (descriptor schema v5) let independently built plugins share an event type without editing the SDK: each plugin embeds the same published class-only contract JAR under `META-INF/turboism/contracts/` and declares it in `eventContracts`. The session binds one contract classloader per artifact hash, so provider and consumer resolve identical `Class` identity; the consumer's loader never parents to the provider's implementation loader. The reachable payload closure is restricted to JDK platform types, `dev.turboism.sdk.*`, and the artifact itself — verified at preflight before entrypoints run. See `sdk/public-event-contracts.md` for the authoring model.

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

Permissions describe risk boundaries rather than individual methods. The canonical catalog is `dev.turboism.sdk.permission.PermissionIds`; manifests declare ids through the `turboism.permission` schema. Representative ids:

```text
turboism.cubism.model.read / turboism.cubism.model.write
turboism.file.read / turboism.file.write
turboism.network.fetch
turboism.process.run
turboism.host.unsafe
```

Permission ids are declared at the granularity a reviewer must approve and the runtime must be able to revoke. Surfaces that attach a plugin to shared host UI therefore carry their own contribution permissions — `turboism.ui.menu.contribute`, `turboism.ui.toolbar.main.contribute`, `turboism.ui.panel.contribute`, `turboism.ui.context-menu.contribute`, `turboism.ui.dialog.contribute`, `turboism.ui.canvas.hint`, and so on — rather than riding on a blanket UI grant. First-party manifests declare them exactly the way third-party plugins do; the fine granularity exists so each contribution can be audited and revoked independently.

Ownership, namespace, quota, lifecycle, and cleanup then bound what a granted plugin may do at runtime; they complement a declared permission rather than replace it. Plugin-private facilities that cannot cross a risk boundary — localization, the plugin logger and paths, bounded task scheduling, the disposable scope — carry no dedicated permission id.

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

Verification is layered by cost and risk:

```text
focused verification
  The smallest affected compile or test selection during implementation.

devCheck
  Production compilation plus inexpensive permanent structural boundaries after
  a meaningful implementation slice.

checkIntegration
  Packaged runtime, plugin, bundle, and affected cross-module behavior.

checkCompletedCommit
  The full automated repository gate after a coherent change is ready: ordinary
  tests, integration, Javadoc/metadata, API-tool and boundary selftests, and hygiene.

checkRelease
  Completed-commit verification plus supply-chain, historical, Java-installer, and
  other release-artifact checks; requires an explicit installer version.

host validation
  Explicit exact-version Cubism execution for the affected feature; never a default gate.
```

A bare multi-project `check` is not the daily workflow because Gradle expands every subproject's same-named task. Broad suites are run only when their affected surface justifies them.

### Local host-validation admission

`scripts/preview/host_validation.py` separates immutable preparation and durable
submission from a separately supervised `serve` worker. All managed worktrees use
one current-UID queue root and one host-admission lock; supported Cubism versions
share a single session slot through task-owned cleanup. Build and prepare work
may run in parallel, but must not launch the host or execute UI hooks.

The worker consumes verified input/tool snapshots, records an attempt before
launch permission, and passes inherited admission ownership to the common
Runner. Completion requires matching structured lifecycle and cleanup evidence;
an expired heartbeat, vanished PID or exit code alone cannot release the host.
Unknown ownership/cleanup quarantines the host for evidence-based inspection.
External sessions are never killed or adopted. Old checkouts and unmanaged
launchers must be retired at a safe boundary before rollout; the queue does not
claim to intercept arbitrary manual official-BAT launches.

The Linux backend binds a fresh delegated systemd user cgroup before acknowledging
Runner execution. Its outside supervisor holds kernel directory/events/kill FDs;
late children and `setsid` remain covered without PID-tree or environment matching.
Only bound-cgroup empty/destruction proof permits finalization. A snapshot finalizer
then rechecks official files, runtime dependencies, fixture and staged artifacts,
archives evidence and removes only a successful task prefix. Preliminary Runner
results cannot claim final cleanup. Unsupported containment fails closed.
See `scripts/preview/README-host-validation-scheduling.md` for local-only CLI,
recovery, service lifecycle and Agent adoption. The service is opt-in and is not
installed or started by Gradle. Actual exact-host acceptance is a separate gate,
not a conclusion drawn from the scheduler's isolated process tests.

Real-host verification is automation-first. Test-only plugins call the public SDK and emit structured assertions. Host scripts launch the official Editor, poll readiness and terminal result markers, batch compatible assertions in one session, and collect hashes, values, Undo/Redo, persistence, cleanup, and timing evidence. Every run copies the selected host project into its task directory and prefixes the copy name with the validation purpose/run ID; independent CoW prefixes and homes isolate sequential runs, not permission to open concurrent Cubism windows. The golden prefix and source project remain immutable shared inputs. All managed exact-host runs share the global admission slot until cleanup is proven. UI automation is reserved for entry points that cannot yet be reached through a semantic SDK operation. Screenshots are last-resort evidence for visual-only facts or failure diagnosis.

Fake providers, static selector records, classfile inspection, and document consistency never promote a provider to real-host readiness.

## 12. Specification-Driven Development

Turboism uses short-form SDD rather than mandatory project-wide TDD. Before implementation, freeze the goal, non-goals, affected boundary, risk lane, observable acceptance conditions, forbidden shortcuts, and final verification batch.

- Lane A covers plugin-private or internal pure logic using existing boundaries: short spec, focused verification, diff review.
- Lane B covers additive shared SDK/runtime seams without host-sensitive behavior: one design checkpoint, focused plus affected integration verification, final review.
- Lane C covers host mappings/reflection, Editor writes and Undo, hooks, host UI attachment, security/supply-chain boundaries, released compatibility contracts, and real-host readiness claims: exact identity, isolation, fail-closed behavior, and automated real-host evidence remain required.

TDD may be used locally for pure algorithms, parsers, and reproducible bug fixes. It is not a requirement to produce one test or contract per method, and broad suites are not rerun after every edit.

M1–M16, M13/M14-style stages, migration boards, capability/readiness ledgers, and closure reports do not define current work or completion. Tracked authority is this architecture, `ROADMAP.md`, the current task specification, executable tests, and exact-host results. A local ignored `AGENTS.md` may describe operator workflow but is not a repository contract.
