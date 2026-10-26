# Plugin author guide

Compile against `sdk/` only. Runtime internals and `com.live2d.*` classes are
not plugin dependencies. The SDK has one public tier; permissions and Cubism
version availability are separate checks.

## Start and package

Use the [plugin template quick start](../templates/plugin-template/README.md#quick-start):
create a repository from the template, run its setup script, then run
`./gradlew build` with JDK 17 or newer. Setup downloads the SDK and optional event
processor and verifies their SHA-256 sidecars. Customize
[HelloPlugin.java](../templates/plugin-template/src/main/java/dev/example/hello/HelloPlugin.java)
and its descriptor together. This complete example is compiled by the existing
`checkExternalPluginTemplate` gate; the examples linked below are production
plugin sources compiled by `devCheck`.

Keep the SDK dependency `compileOnly` and the event processor on
`annotationProcessor`; neither belongs in the plugin JAR. Install the resulting
`build/libs/` JAR into the installation's `plugins/` directory, enable it in
Plugin Management, and restart. See the template for offline setup and pinned
release selection.

The descriptor's `turboismApi` uses `[min,max)`: the minimum is inclusive and the
maximum exclusive. Choose the minimum framework API that supplies every SDK
member you use; compiling against a new SDK does not make its additions work on
an older runtime. See [API compatibility](../compatibility/plugin-api.md) for
base-version normalization and official-plugin range checks. Before the first
stable release, breaking SDK changes are allowed; an API range alone does not
prove compatibility with every future pre-stable runtime.

## Descriptor and permissions

Start from the template's
[plugin.json](../templates/plugin-template/src/main/resources/META-INF/turboism/plugin.json).
Keep `id`, entrypoint class names, localization keys and Cubism sidecar identity
in sync. Declare resources outside `META-INF/` under allowed `resources` prefixes.
The template uses schema v3; shared event-contract artifacts require v5 (see
[public event contracts](public-event-contracts.md)).

Declare each permission with its scope and reason. The canonical catalog is
[PermissionIds](src/main/java/dev/turboism/sdk/permission/PermissionIds.java);
[PluginPermission](src/main/java/dev/turboism/sdk/permission/PluginPermission.java)
describes the declaration. Do not copy unrelated permissions from a larger
plugin. Undeclared access is refused; a service being installed does not grant
its permissions. Declare capability requirements and `environment.requiresCubism`
when the plugin needs a live Editor host. The runtime validates metadata and
bytecode before calling entrypoints.

Resolve optional services through
[PluginContext.services()](src/main/java/dev/turboism/sdk/plugin/PluginContext.java)
and [PluginServiceDirectory](src/main/java/dev/turboism/sdk/plugin/PluginServiceDirectory.java):
`find` returns an optional; `require` throws `PluginServiceUnavailableException`
if absent. A service's `unavailable()` sentinel supports explicit fallback where
appropriate. Presence does not guarantee that every version-routed call works.
New optional services use the directory; do not add per-feature context accessors.

## Parameter folder order

`ParameterGroup.orderedChildren()` returns the actual mixed order of direct folders
and parameters as immutable `ParameterGroupChild` values. The two existing
`childGroupIds()` / `parameterIds()` lists each preserve their own order but cannot
reconstruct interleaving. `Kind` distinguishes a group ID from a parameter ID with
the same text; these IDs are not native GUIDs and do not resolve duplicate IDs.
Legacy providers fail closed rather than synthesizing an order.

`ParameterGroups.moveParameter(parameterId, targetGroupId, insertIndex)` uses a
zero-based position in the target's mixed children **after removing the moved
parameter**. The inclusive upper bound appends; negative or oversized indices
are rejected before opening an edit, and an unchanged position creates no Undo.
The existing two-argument overload appends using the same post-removal rule.
Indexed moves require a provider implementing the new overload; its default
does not discard the index by delegating to append. Read/write permission and
session generation checks also apply to these additions. Native writes still
require exact reviewed selectors; an ambient authoring transaction additionally
requires verified group Undo support. These APIs do not add group reordering,
a multi-call transaction, a MCP structure tool, or live-host acceptance.

## Lifecycle and threads

[TurboismPlugin](src/main/java/dev/turboism/sdk/plugin/TurboismPlugin.java) defines
`init`, `enable`, `disable` and `shutdown`. Initialization receives a
permission-scoped context; failed initialization prevents enablement. Register
contributions on enable and close them on disable. Enroll returned
[Registration](src/main/java/dev/turboism/sdk/plugin/Registration.java) handles in
`context.disposableScope()` as the template does. Disabling withdraws those
resources; re-enabling must register them again.

[AbstractTurboismPlugin](src/main/java/dev/turboism/sdk/plugin/AbstractTurboismPlugin.java)
is optional. If you use it, preserve its lifecycle cleanup when overriding
methods and enroll resources in its `disposalScope()`.

Lifecycle calls run on a worker lane, not Swing's event dispatch thread (EDT),
and successive transitions may use different workers. UI actions, callbacks and
background tasks can access plugin state concurrently. Synchronize shared state;
do not assume one plugin thread.

Use [UiScheduler](src/main/java/dev/turboism/sdk/ui/UiScheduler.java) for UI work.
Keep EDT callbacks short: do not wait on futures or perform blocking I/O there.
Use [PluginTaskScheduler](src/main/java/dev/turboism/sdk/task/PluginTaskScheduler.java)
for background work; inspect submission rejection and completion outcomes.
[PluginTaskKind](src/main/java/dev/turboism/sdk/task/PluginTaskKind.java) selects
compute, low-frequency refresh or long-running work. Cancellation is cooperative,
so observe the token. A task worker is not permission to read or mutate host
objects: use [AsyncHostReadService](src/main/java/dev/turboism/sdk/hostread/AsyncHostReadService.java)
and the relevant editing API's documented thread and callback scopes. Schedule
UI updates explicitly after asynchronous completion.

## Cubism versions

[`@CubismEditor`](src/main/java/dev/turboism/sdk/CubismEditor.java) describes API
availability: `value` names exact reviewed versions, `from`/`to` express bounds,
and `exclude` removes versions. These annotations are SDK declarations, not a
replacement for the plugin's metadata or runtime availability checks.

Declare supported exact versions in
[`META-INF/turboism/cubism-versions.json`](../templates/plugin-template/src/main/resources/META-INF/turboism/cubism-versions.json).
`supportedVersions` lists hosts where the plugin is usable; `fullFeatureVersions`
lists the subset with all features. The template setup derives its initial list
from release compatibility metadata; review it against your actual requirements.
Missing or invalid declarations remain undeclared in Plugin Management.

New features must adapt all reviewed Cubism versions and declare support in SDK
annotations and plugin metadata. Evidence-backed host limitations must be
recorded, not silently omitted. Follow
[CONTRIBUTING: Cubism version adaptation](../CONTRIBUTING.md#cubism-version-adaptation)
for the reviewed set, derivation tooling and first-party metadata generation.
Use the generated [capability matrix](../compatibility/cubism/capabilities.md)
when selecting APIs. Probe availability; catch
[CubismEditorApiUnavailableException](src/main/java/dev/turboism/sdk/cubism/CubismEditorApiUnavailableException.java)
at feature boundaries and disable or hide unsupported actions.

## Settings, storage and configuration

Prefer declarative
[SettingsContribution](src/main/java/dev/turboism/sdk/ui/settings/SettingsContribution.java)
through `UiHostCapabilityService.contributeSettings`. Group controls with
`SettingsTab`; bind plugin-owned values using `SettingsBinding` and validate
changes with `SettingsChangeValidator`. For typed config, use
`PluginConfigRegistry.registerUserEditableSchema` with explicit
`ConfigSchemaEditor` metadata. Registration handles belong in a disposal scope.

[SettingsControl](src/main/java/dev/turboism/sdk/ui/settings/SettingsControl.java)
is sealed: `Choice`, `Toggle`, `Text`, `FilePath` and `Note` are the current
variants. Update exhaustive switches when variants are added. `FilePath` is an
editable string with a browse action; the renderer saves on Apply/OK. Its browse
supplier returns `Optional.empty()` on cancel. Browsing does not itself grant
filesystem access. See the working
[external PSD settings contribution](../plugins/external-psd-edit/src/main/java/dev/turboism/plugin/externalpsdedit/ExternalPsdEditSettings.java)
for `FilePath`, `SettingsBinding`, localization and mediated file selection.

Use [PluginStorage](src/main/java/dev/turboism/sdk/storage/PluginStorage.java)
for bounded asynchronous access under plugin-owned `StorageRoot` directories.
`StoragePath` rejects traversal outside the roots. Inspect result errors and
truncation; atomic writes may fail when atomic replacement is unavailable.
Use [UserFileAccessService](src/main/java/dev/turboism/sdk/ui/UserFileAccessService.java)
for user-selected external files. Do not bypass mediation with host paths.
For larger binary exports, `writeBytesAtomicBounded(handle, content, maxBytes)`
requires an existing WRITE handle and a positive explicit budget up to 128 MiB.
The current Runtime copies the bytes before asynchronous atomic replacement;
`written=false` means the target was left unchanged. The original binary writer
keeps its 8 MiB limit. The compatibility default delegates only budgets up to
8 MiB and reports unavailable above that, without consuming any new file grant.
Keep the handle alive until the returned stage actually completes; cancellation
of a caller's wait does not establish that file I/O stopped. A thrown or lost
completion requires result reconciliation, not an automatic overwrite retry.

[PluginConfigRegistry](src/main/java/dev/turboism/sdk/config/PluginConfigRegistry.java)
supports scoped string values and typed schemas, codecs and migrations. Keep
read/write scope registrations open while using string config. Typed writes use
the revision from a prior read and can reject stale revisions; handle the result.
Keep UI bindings fast and move expensive persistence off the EDT. Write diagnostic
records through `context.logger()`; `PluginPaths.logsDir()` always fails closed.

## Events and failures

Use [EventBus](src/main/java/dev/turboism/sdk/event/EventBus.java) subscriptions
and register the returned handle for cleanup, as in the template. Alternatively,
`@SubscribeEvent` uses the event processor's generated subscriber catalog;
without that processor use imperative subscriptions. See
[event coverage](event-coverage.md) for domain permissions, origins and delivery
semantics, and [public event contracts](public-event-contracts.md) for sharing
event types between independently built plugins.

Ordinary observations are asynchronous. Runtime-owned events match supertype
subscriptions subject to concrete-event permissions; plugin-published events
match exact types. Mutable `Before` events have explicit synchronous callback
scopes: do not retain their mutable payload or move the transformation to a task.

Fail closed when permissions, services, host versions or session state do not
allow an operation. Check structured result statuses as well as exceptions;
an unavailable sentinel can return an empty or rejected result. Log actionable
context through `PluginLogger`, handle expected failures at the feature boundary,
and leave the host unchanged on rejection. Do not catch an error merely to retry
through runtime internals or an unmediated host API.

## UI appearance and dark mode

Every new or changed UI contribution must support both light and dark themes.
This includes toolbar buttons, canvas/GL controls, overlays, dialogs, labels,
and code-generated icons, as well as packaged image resources.

- Match the actual toolbar or panel containing the control. Prefer transparent
  icon backgrounds; when a solid background is needed, resolve the containing
  surface's theme color rather than baking in white or a sampled light gray.
  A failed theme lookup must not silently produce a light-theme plate.
- Check the glyph as well as the background. Black glyphs can disappear on a
  dark toolbar even when the image has correct alpha. Use theme variants or a
  palette that remains legible on both surfaces. Preserve antialiased alpha.
- Verify normal, hover, pressed, selected/on, off, disabled, and disabled-selected
  appearances where supported. Off is an interactive state, not disabled.
  Hover/press feedback and the current mode must remain recognizable in both
  themes; use shape or other cues in addition to color to distinguish modes.
- Do not bake the startup theme into cached raster/GL images unless they are
  invalidated and rebuilt on theme changes. Transparent backgrounds with
  translucent interaction feedback can avoid stale background colors.
- Review at the host's actual display size and supported DPI scales, on both
  light and dark containing surfaces. Include theme switching while the control
  is visible. Asset previews and pixel/alpha checks are useful preliminary
  evidence; they do not establish correct rendering in the Cubism host. Record
  which checks ran and explicitly identify any missing host visual verification.

First-party icon source and regeneration rules are in the
[artwork guide](../scripts/artwork/README.md#深浅色模式适配).

## Test and review

Test plugin logic without a live host using SDK interfaces and plugin-owned
fakes. Cover disable/re-enable cleanup, denied permissions, absent services,
unsupported versions, rejected tasks, storage/config errors and event threading.
Add the SDK to the test runtime classpath when using `compileOnly` in your build.
Then build the JAR and test installation and relevant UI/host interactions on the
supported Cubism versions; compilation alone does not verify host behavior.

Inside this repository, run the affected `:plugins:<name>:test` task during
implementation. `devCheck` compiles production examples and checks metadata;
`checkExternalPluginTemplate` builds the template as an external consumer and
validates its packaged contract. Run `checkCompletedCommit` for final acceptance.
See [testing](../testing/README.md) and
[contributor gates](../CONTRIBUTING.md#verification) for host validation and
fixture requirements. Licensed host assets stay outside version control.

## Incubating APIs

[`@Incubating`](src/main/java/dev/turboism/sdk/Incubating.java) marks experimental
API that may change or disappear, including breaking signatures. It is excluded
from exact compatibility baselines; a type annotation covers its members.
Treat dependencies on it as version-pinned and review each SDK update. Removing
the annotation promotes an API to the normal compatibility rules, subject to the
repository's pre-stable policy. An unannotated API is not proof that the first
stable release has happened.


### Editor parameter identity for authoring plans

`Parameter.id()` is the editable Cubism display ID; `Parameter.guid()` reads the opaque native Editor authoring source GUID. Match it to ordinary snapshot axis `parameterGuid`, together with document/model/generation and the full expected state. A GUID alone neither grants write permission nor revives a stale handle. Renaming a display ID does not redefine source identity, but an old ID-bound handle may be stale; rediscover the handle instead of assuming it survives a rename. A deleted/recreated same-ID parameter may have a different GUID. Do not synthesize GUIDs from display IDs, names, Core indices or object addresses.

The default `guid()` implementation fails closed. Core-only, detached and third-party backends without native authoring identity do not acquire a fabricated identity. Runtime forwards the getter through read-permission and session-generation checks; it reads the verified native parameter source, never the current value or a UI selection, and adds no Undo entry. Exact host selector availability remains authoritative. This identity accessor is a building block for safe binding initialization, not an automatic rig copy or initialization algorithm.


### Create parameter bindings from values

`model.parameterBindings(parameterId).bindValues(target, List.of(-1f, 0f, 1f))` creates a binding on a target not already bound to that parameter. The values are examples only; use the discovered parameter definition and an approved plan. Callers provide values, not fabricated native binding-point IDs. Read the binding and authoring snapshot after creation to obtain canonical point identities, axes, cells and stored forms before further edits.

The Editor backend copies and sorts finite, nonempty values and rejects duplicates within its existing binding tolerance. It uses the same exact-host admission, protected-state verification, authoring transaction participation, native Undo and rollback paths as `bind`. Permission and session wrappers check each call. Other backends inherit an explicitly unavailable default; there is no fallback that invents point IDs or invokes UI commands.

This single-axis primitive does not establish a complete independent Cartesian grid, guarantee that the original form GUID belongs to the parameter default, or verify rendered quality. A higher-level initializer must independently validate parameter GUIDs and definitions, complete target state, default-form content, all generated cells/forms and rollback before claiming a modeling workflow. The MCP `authoring.bindings.initialize_plan/initialize/initialize_result` workflow applies these checks to one previously zero-axis source. Its returned tuples still require a fresh authoring snapshot before further form writes; this is not real-host or visual acceptance.
