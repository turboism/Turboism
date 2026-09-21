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
- Each concrete runtime-owned event type is gated by its domain permissions,
  in addition to `turboism.event.subscribe` for the subscription itself.
  Mutable `*Before` transforms require `turboism.cubism.model.intercept`;
  observations use the domain's observe permission. Direct concrete/family
  subscriptions still validate their required permissions at registration.
  A root subscription is admitted with the baseline subscription permission;
  delivery skips unauthorized concrete types and records a
  `DELIVERY_PERMISSION_DENIED` diagnostic.
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
  Receiving this state requires `turboism.cubism.model.intercept`, including
  delivery through either wildcard root. The observe permission alone does
  not grant it.
- Immutable `Before` records — for example `CubismOperationLifecycleEvent.Before`
  — are **asynchronous observations** of an operation that already started.
  They are delivered through the mailbox like any other event; a subscriber
  cannot veto or mutate the operation through them. Permission names alone
  do not imply interception: the current catalog also requires the intercept
  permission for semantic-operation and model-update `Before` observations.

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

The production `HostSessionSnapshotSource` returns a constant empty object
selection (`EMPTY_SELECTION`), as does the appearance snapshot source.
Document/model identity transitions can produce events through the shared
commit path. Native object-selection IDs and a native selection push hook
remain unavailable; tests with mutable synthetic sources verify the observer
machinery without establishing native object-selection coverage.

## Unsupported origins

`CubismOperationLifecycleEvent` currently has only `TURBOISM_API` producers;
the declared `HOST_UI`, `HOST_INTERNAL`, `UNDO`, and `REDO` origins are not
emitted. Native parameter and project-file bridges produce their own event
families, but do not fill this semantic-operation origin gap. Native
part/drawable/deformer mutation ingress is also unavailable.

Fourteen of the 40 `CubismOperation` values still have no producer:
`OPEN_PROJECT`, `CLOSE_PROJECT`, `IMPORT_PROJECT`, `EXPORT_PROJECT`,
`OPEN_DOCUMENT`, `SAVE_DOCUMENT`, `SAVE_DOCUMENT_AS`, `CLOSE_DOCUMENT`,
`SWITCH_DOCUMENT`, `RELOAD_DOCUMENT`, `CHANGE_SELECTION`, `UNDO`, `REDO`,
and `SET_PARAMETER_GROUP_LABEL_COLOR`. A document identity transition seen
by the selection observer does not emit `SWITCH_DOCUMENT`.

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

Their structural ABI digests change because the digest includes the event's
declared interfaces and inheritance surface. The digest algorithm is
unchanged: it records member type signatures but does not recursively hash
the structure of referenced payload types. Payload-closure verification is
a separate admission check. Referencing one of these SDK records from a
custom payload therefore does not by itself change that custom event's pin.

Inspect a changed SDK event's digest against the matching runtime/SDK with:

```bash
java -cp path/to/turboism-agent.jar dev.turboism.core.event.PublicEventAbiCli \
    dev.turboism.sdk.action.ActionInvocationEvent
```

For plugin-exportable contracts, recompute `abiSha256` after changing the
event's ABI and update `eventContracts[].sha256` whenever the artifact bytes
change; see [public-event-contracts.md](public-event-contracts.md) §2.
Historical SDK release baselines remain unchanged.
