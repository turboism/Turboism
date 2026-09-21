# Event coverage

What the plugin-facing event system actually delivers, how delivery is
filtered, and which host origins remain unsupported. This document describes
implemented behavior, not aspirations; an entry is listed only when a real
runtime producer publishes it.

## Delivery model

- Runtime-published events match by **supertype**: a subscription on a sealed
  family root (for example `ParameterValueEvent`) receives every member state,
  and a subscription on the `TurboismEvent` root receives every runtime-owned
  event the subscriber is permitted to see. Subscribing to a root does not
  require declaring every member permission — filtering happens per concrete
  event at delivery time.
- Plugin-published events are routed by **exact type only**
  (`publishExact`): a `TurboismEvent` root subscription never receives an
  arbitrary plugin-defined type. Custom plugin events are also not
  retained/replayed through the runtime observation channel.
- Each concrete runtime-owned event type is gated by its own observe
  permission (`turboism.*.observe`, plus `turboism.event.subscribe` for the
  subscription itself). A subscriber without the concrete permission simply
  never sees that event type; the denial is recorded as a
  `DELIVERY_PERMISSION_DENIED` diagnostic, not a failed subscription.
  Subscription types that can receive a mutable `*Before` transform state
  additionally require `turboism.cubism.model.intercept` — observing is not
  enough to mutate.
- Queued delivery is asynchronous and per-generation ordered. The two
  synchronous transform overloads (value transform and checkpointed reference
  transform) apply the same permission filtering on the caller thread.
- Some runtime observations are **retained**: the latest value replays to a
  late subscriber immediately after registration. Others are **live-only** —
  most importantly `PluginLifecycleEvent`, which is never replayed because a
  lifecycle verdict is only meaningful when observed as it happens.

## Runtime-owned event families

The runtime is the sole publisher of these contracts; plugins cannot publish
them (`RuntimeEventContractCatalog` rejects plugin publication):

- `sdk.event.cubism` semantic families: parameter value, part opacity/name,
  drawable opacity/visibility/lock/geometry, deformer opacity/visibility/lock,
  warp-deformer grid, rotation-deformer base angle/form, model update,
  cubism operation lifecycle, editor startup/exit, project file lifecycle.
- `sdk.cubism.event.SelectionChangedEvent` — selection transitions.
- `sdk.cubism.backup.BackupCompletedEvent` — backup completion.
- `sdk.appearance.AppearanceChangedEvent` — host appearance changes.
- `sdk.action.ActionInvocationEvent` — action invocation lifecycle.
- `sdk.ui.table` scene-table events — header clicks, item order, snapshots.
- `sdk.runtime.CubismLogBatchEvent` — batched host log lines.
- `sdk.runtime.PluginLifecycleEvent` — plugin load/unload verdicts.
- `sdk.performance.PerformanceSampleEvent` — periodic performance samples.

## Synchronous transforms vs asynchronous observations

Not every `*Before` state is a synchronous interceptor:

- `ParameterValueEvent.Before` is a **synchronous transform**: each subscriber
  receives a distinct mutable candidate inside a callback scope, and the
  validated result feeds the next subscriber before the host write proceeds.
  Mutating through this state requires the `turboism.cubism.model.intercept`
  permission, which the subscription schema demands for every subscription
  type that can receive a `*Before` member — the observe permissions alone do
  not grant it.
- Immutable `Before` records — for example `CubismOperationLifecycleEvent.Before`
  — are **asynchronous observations** of an operation that already started.
  They are delivered through the mailbox like any other event; a subscriber
  cannot veto or mutate the operation through them.

## Correlation payloads are not events

`CubismOperationEvent` is an immutable correlation **payload**: sequence,
operation, origin, and optional subject identity shared by all phases of one
semantic operation. It intentionally carries no `TurboismEvent` marker, so it
cannot be subscribed to or forged onto the bus. It is delivered only as the
payload of `CubismOperationLifecycleEvent` phases. Its absence from the event
contract catalog is by design, not missing coverage.

## Plugin lifecycle events

`PluginLifecycleEvent` reports `LOAD`/`UNLOAD` phases with `SUCCEEDED`,
`FAILED`, or `TIMED_OUT` outcomes, carrying only the stable plugin identity and
the admitted generation (`NO_ADMITTED_GENERATION` when admission never
happened). A verdict is published at most once per outcome: a timed-out unload
emits `TIMED_OUT`, then exactly one terminal `SUCCEEDED` or `FAILED` verdict
when the retained cleanup actually settles. A `FAILED` verdict is never
followed by a contradicting success. Subscription requires
`turboism.plugin.lifecycle.observe`.

## Selection observation

`SelectionChangedEvent` transitions come from the session host snapshot
source. The query path (`SelectionQueryService`) and the session-bounded
observer share **one source and one version domain**: whichever path reads a
newer snapshot commits the baseline and publishes the transition, so a query
and the observer never double-publish and a stale read can never regress a
newer baseline. The observer runs on the shared bounded host-read lane — no
per-subscription thread — and advances on a subscriber-driven schedule.

Current limitation, stated plainly: every verified `HostSnapshotSource`
implementation today returns a constant empty selection
(`EMPTY_SELECTION`). Real document/model snapshots flow through the same
commit path, but native object-selection transitions are not yet observed
because no host adapter produces them, and no native push hook exists.
Selection events therefore describe the shared-baseline machinery honestly
rather than claiming native object-selection coverage.

## Unsupported origins

The following host surfaces are **not** covered by any event and no
fabricated hook is implied: Undo/Redo operations, editor-operation origins
beyond those enumerated above, and arbitrary native object-selection pushes.
Producers are only added where a verified ingress exists.

## Trusted compatibility paths

Two subscription surfaces exist outside the public SDK contract and are not
permission-filtered the way plugin subscriptions are: legacy adapter
registrations inside the runtime itself, and unbound raw-broker subscribers
(`RuntimeEventBroker.subscribe(pluginId, ...)` without an admitted owner).
Both are explicit compatibility seams for runtime/test code; they are not
reachable from plugin code and are not part of the SDK contract.

## Compatibility note

The five formerly nested legacy event families
(`ActionInvocationEvent`, `AppearanceChangedEvent`, `BackupCompletedEvent`,
`CubismLogBatchEvent`, `PerformanceSampleEvent`) now implement the top-level
`sdk.event.TurboismEvent` marker, which itself extends the legacy nested
`EventBus.TurboismEvent` marker — so existing subscribers keep working. These
families stay runtime-owned and are not plugin-exportable contract types.

The change does alter their structural ABI: `implements` clauses are part of
the per-type digest, so an `eventExports`/`eventImports` `abiSha256` pin whose
payload closure reaches one of these SDK types is stale. Recompute structural
pins with the author-facing CLI against the matching runtime/SDK, for example:

```bash
java -cp <runtime classes> dev.turboism.core.event.PublicEventAbiCli \
    dev.turboism.sdk.action.ActionInvocationEvent
```

See `sdk/public-event-contracts.md` §2 for the pin model and what the digests
cover.
