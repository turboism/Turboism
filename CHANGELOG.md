[EN / English](CHANGELOG.md) · [ZH / 简体中文](CHANGELOG_zh.md) · [JP / 日本語](CHANGELOG_ja.md) · [KR / 한국어](CHANGELOG_ko.md)

# Changelog

All notable changes to Turboism are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow Semantic Versioning.

## [Unreleased]

### Added

- Animation workspace support in the SDK and runtime: plugins can enumerate animation documents,
  project timelines, tracks, attributes and keyframes, activate and rename scenes, seek playback,
  apply batched keyframe edits and curve types, and record/bake evaluated values. A pure-SDK
  `Motion3Validator` reports structural issues in motion3 data. The object model is verified
  exact-host on Cubism 5.2.03, 5.3.02 and 5.3.03 through the `animation-timeline-host-probe`.
- `PluginContext.availableServices()` and the `PluginService` enum report which optional context
  services the runtime actually installed, so plugins no longer have to probe getters or guess at
  `unavailable()` sentinels; the default fails closed with an empty set.
- `TurboismWindowFactory.installWindowIcon` installs a process-wide window-icon override, so every
  plugin-owned window carries the same product icon the user picked for the main-toolbar button
  (text vs installer mode) instead of the bundled default.
- The Performance settings tab gains two launch/editing controls: a Cubism JVM ZGC toggle
  (`launcher.zgc`, see Changed for its default) and a "Disable automatic backup (weakens crash
  recovery)" toggle that suspends the host's periodic auto-backup timer during editing while
  keeping the configured interval and cap. The observed host settings are captured to a
  plugin-state baseline before the first override and restored when the flag is off, so a crash
  while enabled cannot strand backups disabled. Manual `backupNow`/`backupAfterSave` are
  unaffected.
- Texture-atlas tile-bbox processing and output cache-reuse, both on by default. Scratch and
  compositing are bounded to the transformed tile bounding box instead of whole atlas pages, and a
  content-signature guard on `CTextureAtlas.updateTexture` supplies a duplicate of the retained
  recorded output when an identical signature reappears. On the reviewed real-host fixture this
  cut editor open from ~100 s to 15–18 s and export EDT work from ~127 s to ~15 s with
  bit-identical page digests; reviewed targets pin the exact supported archives and everything
  else fails closed.
- Guarded, opt-in performance experiments: native texture preparation, PNG archive reuse, and
  Cubism image diagnostics. All are off by default; exact-host differential probes measure them
  without claiming GPU/FPS or load-time speedups.
- The SDK now ships to plugin developers directly: every framework release carries
  `turboism-sdk-<version>.jar` plus its SHA-256 sidecar as a GitHub-only developer asset,
  `./gradlew publishToMavenLocal` yields clean `dev.turboism` coordinates, and
  `templates/plugin-template` is a standalone project that builds against the released SDK.
- The Warp Deformer Alt Symmetry plugin extends the bounding-box Alt semantics to Warp Deformer
  control points: Alt drags mirror across the vertical grid axis and Alt+Shift across the
  horizontal. A contributed toggle on the canvas-top control strip arms the axis and a native
  canvas hint shows the armed state; mirrored positions commit through the model-write path so a
  symmetric move joins native Undo/Redo. The plugin requests `turboism.cubism.model.read`,
  `turboism.cubism.model.write`, `turboism.ui.toolbar.contribute` and `turboism.ui.canvas.hint`,
  and its native mirror binds only when the verified host hook is installed. The verified
  drag-tick hook admits the exact reviewed Cubism 5.2.03, 5.3.02 and 5.3.03 artifacts —
  inside the native drag tick it provides live mirrored preview and a single undo entry —
  while unreviewed hosts keep the release-time AWT fallback with identical results.
- `Action.of` and `MenuContribution.of` build single-point contribution registrations as plain
  `SimpleAction`/`SimpleMenuContribution` values, so a plugin no longer needs an anonymous class
  for every action or menu item it contributes.

### Changed

- Ordinary CI now runs both `devCheck` and the complete `checkCompletedCommit` suite on every pull
  request and push to `main`, using Xvfb for display-dependent tests. Coverage guards reject skipped,
  filtered or soft-failed gates; channel checks now follow `main` and include root build inputs.
- Javadoc presence checks now use the JDK 17 compiler tree API instead of line-based matching,
  covering implicit public interface methods and publicly reachable nested types. Parsing and tool
  failures fail the check rather than silently falling back to incomplete results.
- Public API documentation across the SDK, runtime and official plugins now describes previously
  undocumented declarations and clarifies lifecycle, ownership, failure and result semantics.
- The built-in core plugin is folded into the runtime as the framework shell
  (`dev.turboism.shell`): it no longer ships as a plugin JAR, the reserved `turboism.core`
  identity still attributes config, tasks and logs, plugin management still lists it as the
  non-removable core row, and the plugin load report no longer lists it. The shared top-menu root
  keeps "Turboism" as its routing key but displays the framework-localized name.
- Managed launches now run Cubism on ZGC (`-XX:+UseZGC`) by default: exact-host A/B showed it
  eliminates G1's second-scale pauses (load ~1.4 s → sub-millisecond, write ~0.6 s →
  sub-millisecond) and cuts steady RSS by roughly a third. The Performance → Cubism JVM toggle
  applies on the next launch and stores only an explicit opt-out.
- CSV parameter batch imports now run inside one authoring transaction instead of committing per
  write. On the heavy-model real-host A/B, Update Parameter Structure rebuilds dropped from 37 to
  4 (-89%), median parameter-write time from 4.71 ms to 1.19 ms (-75%), and EDT allocation across
  a 35-write burst by ~99.8%.
- Runtime reads observe the host once per versioned read or scope capture through an SDK-shaped
  observation seam instead of re-projecting every accessor; the synthetic benchmark measures ~29%
  less read-path time and ~41–43% less per-call allocation.
- Cubism startup suppression (skip update check, splash, and information dialogs) is now on by
  default, including fresh installs and runs whose Turboism home has no `config.json` yet.
  Setting a `hooks.startup.skip*` flag to `false` or enabling safe mode opts out; a
  schema-invalid config still fails closed. Premain diagnostics are now buffered until the
  runtime log sink installs, so `STARTUP_SUPPRESSION_*` admission codes reach the session log.
- Release gates now hold localization to the full Cubism language matrix. Marketplace plugin
  listings must carry `plugin.name`/`plugin.description` in every declared locale
  (en, ja, ko, zh-Hans, zh-Hant), and release candidates are rejected unless the
  reviewed zh, ja and ko note translations exist. The framework message catalogs join the
  official-plugin completeness gate, which now also runs as part of `checkIntegration`.
- Every optional `PluginContext` service accessor now returns that service's `unavailable()`
  sentinel instead of throwing `UnsupportedOperationException` from the getter, and each service
  exposes `isAvailable()` for probing. Sentinels still fail closed: they report structured
  failures where the domain offers one (task submissions, host reads, storage, user files, mesh
  edits, host dialogs, script runs) and throw a stable `UnsupportedOperationException` on use
  where it does not. `ScriptService#available` is renamed `isAvailable` so the probe carries one
  name everywhere. **Plugin API migration may be required:** a plugin that caught the accessor's
  `UnsupportedOperationException` should call `isAvailable()` instead.
- The eighteen semantic event types moved from `dev.turboism.sdk.event.cubism` to
  `dev.turboism.sdk.cubism.event`; the retired package is rejected by the boundary and
  package-layout checks so the deprecated shape cannot regress. **Plugin API migration may be
  required:** update event imports.
- Install-time host hooks are declared in `META-INF/turboism/hooks` and scanned by the agent
  instead of being hand-wired: each `HookContributor` checks its own admission and forwards
  install/bind/uninstall through `HookRegistry`, so a new hook is a manifest line plus a
  contributor class rather than an edit to the agent. Verified/fail-closed admission, atomic
  record extraction, the mesh-mirror premain/bind lifecycle, and the `installation=`/`cleanup=`
  report lines are unchanged.

### Fixed

- Animation documents, scenes, tracks and attributes now enforce plugin permissions, scope liveness
  and document generations throughout the object graph. Keyframe copies reject stale or foreign
  sources while preserving valid copies between active views owned by the same plugin.
- Animation time scaling now applies the same affine transform to keyframes and their Bézier
  control-handle times instead of merely shifting the handles. Fractional handle times and identity
  scaling around large origins retain their precision; translation and copy behavior is unchanged.
- Retained `cubismRead()` and Clip Mask collection services now reject access after their owning
  plugin scope closes, including the direct PSD, clip-mask, texture-atlas, render, workspace and
  theme read paths, without invalidating other plugins.
- Physics-editor contributions are released automatically when their plugin scope closes. Closed
  services cannot register new contributions, and repeated closes cannot remove a later contribution.
- Atlas cache reuse now falls back to the original rebuild path when an input digest cannot be
  computed, including images over the existing pixel budget. Unknown digests no longer compare as
  equal and return stale pixels; ordinary unchanged inputs remain eligible for reuse.
- `Motion3Validator` checks exact numeric values before narrowing `Version` and segment-kind
  identifiers, rejecting fractional and overflow-wrapping values without throwing on extreme
  exponents in those fields. Valid mathematically equivalent representations remain accepted.
- Managed Graal installation now reconciles a verified, current-version orphan activation marker
  left by an interrupted first install before starting another download, avoiding a full download
  and probe followed by `GRAAL_RUNTIME_RECOVERY_REQUIRED`. Unknown markers and existing rollback
  safeguards remain protected.
- Each `DisposableScope` registration now owns a single release action shared by the registration
  handle and scope cleanup, including repeated or concurrent closes. Distinct registrations of
  equal resources no longer remove one another's cleanup entries.
- The three- through six-argument `CorePluginContext` convenience constructors taking host-adapter
  access now assemble omitted services through the default path instead of failing with a null
  pointer. Explicit services and the strict constructor overloads keep their existing contracts.
- Runtime test fixtures now isolate temporary host classes from same-named classpath fixtures,
  execute headless dialog checks in a separate headless JVM, and close their own settings-window
  scopes, eliminating the associated linkage failures, modal hangs and cross-test window leaks.
- Cubism 5.3.03 editor-model DRAFT mapping metadata now matches its referenced verification record.
  Regression checks validate exact counts, capability IDs and the record digest without accepting
  lossy numeric conversions; this does not enable new runtime mappings.
- Switching between two open documents now publishes the newly bound model immediately; previously
  the previous model stayed published, so evaluated reads could trace — and pin — the wrong model
  under the new binding identity.
- The editor binding-identity cache no longer retains closed document graphs for the life of the
  session (its cached identities are now held weakly), and a borrowed Core model is released once
  its binding is gone.
- Several unbounded growth paths are now bounded: the overlay button side table evicts FIFO, the
  recent-preview memory maps and the poller's emit-dedupe marks are pruned to live entries, and
  non-showing subtrees are pruned from the scene-palette poll and the palette-filter component
  walkers.
- Reflection-heavy host paths cache verified members, constructors and owner classes plus
  permanent misses; mesh-mirror scans index once per dispatch; the workspace `safeSegment`
  sanitizing patterns are precompiled; and the palette toolbar skips re-layout when nothing
  changed.
- Log redaction gates each pattern on the bytes it strictly requires, removing ~7 matcher
  allocations per observed entry; redaction output is unchanged.
- The framework shell gets a proper `URLClassLoader` over the agent jar with a system-classloader
  fallback, and all remaining window construction routes through `TurboismWindowFactory`.
- Host-validation and preview packaging fixes: the parameter plugin JAR is located by glob instead
  of a stale versioned name, validation probes ship their declared i18n catalogs, the workspace
  bundle is packaged from the canonical agent jar, the runner supports local host transport and
  Windows result lines, close-phase artifacts count toward the automated result, the
  plugin-management restart hook is admitted into the reviewed queue inventory, and the Cubism
  5.3.03 exact host joined the validation queue.
- The hook registry no longer corrupts or double-closes handles when a process-exit close pass
  races a late enrollment: close passes walk a snapshot and claim each entry atomically, and a
  handle enrolled mid-pass stays enrolled instead of being half-closed.
- The agent reads the hook manifest on the boot class path, so a manifest packaged inside the
  agent JAR resolves regardless of the process working directory.

## [0.44.0] - 2026-09-11

### Added

- Turboism now checks for stable updates against the deployed release API
  (`api.turboism.dev/v1/releases/stable.json`). The comparison uses the authoritative build number
  embedded in the installed package, so a lower build is never offered as an update and an equal build
  number with a different version is treated as an identity conflict rather than an update.
  Installations that predate build numbers keep comparing by version only and are never assigned an
  invented number.
- An available update is presented as a native Cubism hint over the drawing area, the same surface the
  host uses for its own lower-right messages, instead of an entry in the Turboism docked panel. The
  hint is keyed, so a newer offered build replaces the previous text, and it clears itself as soon as
  the update is no longer offered. Clicking it opens the fixed first-party download page; no URL from
  the release feed is opened or installed, and no installer is downloaded or executed automatically.
- The update checker is non-blocking and runs at most once per 24 hours, with a manual check that is
  always available. Automatic checks have their own persistent toggle in the Startup settings tab
  and are independent of Cubism's own update suppression.
- Plugins can show native Cubism hints over the drawing area through
  `UiHostCapabilityService.notifyCanvasHint`, `notifyDismissibleCanvasHint` and `showCanvasHintWhile`,
  with `CanvasHintNotification`, `CanvasHintHandle`, `CanvasHintPosition` and `ConditionalCanvasHint`.
  A plugin needs the new `turboism.ui.canvas.hint` permission to show one.
  The capability is version-routed through the verified 5.2.03, 5.3.02 and 5.3.03 host routes and
  reports itself unavailable on a host where the route cannot be resolved rather than approximating
  it. See the [SDK v10 review](sdk/api-contracts/sdk-api-v10-review.md); this revision is purely
  additive and requires no plugin migration.
- Published release notes now carry reviewed Korean text as well as Simplified Chinese and Japanese.
  Release documents expose it as `notesByLanguage`, English remains the fallback the website shows
  when a translation is missing, and Nightly headings and warnings are translated in all four
  languages while raw commit subjects stay in their original language, explicitly labeled.

### Changed

- The reviewed SDK exact baseline is now v10, pinned to the canvas-hint commit. The revision adds 42
  API records and removes or changes none; v9 and v8 remain historical exact audits that every
  release still runs.
- The WebDAV backup plugin is renamed from `backup` to `webdav-backup`: its Gradle module and Java
  package are `webdav-backup`/`dev.turboism.plugin.webdavbackup`, its installer artifact is
  `plugins/webdav-backup.jar` (previously `plugins/backup.jar`), its plugin id is
  `dev.turboism.plugin.webdav` (previously `dev.turboism.plugin.backup`), and its menu entry is now
  localized. Stored endpoint settings in `backup/webdav.cfg` are unaffected.
- A `release-notes/<version>.json` file that names a language outside the reviewed matrix now fails
  the release instead of silently dropping it, so a typo cannot ship a release with a missing
  translation.

### Fixed

- The release API keeps serving the last verified release snapshot while GitHub is unreachable instead
  of answering every channel with "unavailable". A snapshot stays usable for up to 24 hours, a
  transient failure of one channel no longer discards that channel's previously verified data, and a
  failed refresh preserves the previous snapshot. Publication still notifies the API immediately, and a
  new scheduled monitor reports confirmed, deduplicated incidents.
- The Windows installer provisions the managed Graal runtime without requiring a pre-installed Java:
  it downloads and validates the archive directly, initializes the complete runtime configuration for
  a standalone install, and its Graal page no longer claims an obsolete Java prerequisite. The
  provisioning path is verified on Windows PowerShell 5.1 and 7.
- Upgrading an existing install no longer leaves two WebDAV plugin entries behind. Both installers
  remove the stale pre-rename JAR from `plugins/` by its embedded plugin id (so any filename is
  covered) during a managed upgrade, and the old id `dev.turboism.plugin.backup` joined the
  retired/superseded boundary: the runtime refuses to load it, plugin management does not list it,
  and `config.json` `disabledPlugins` no longer keeps it. The pre-rename WebDAV settings dialog also
  localizes every label, button, tooltip and status message instead of always showing Chinese.

## [0.43.11] - 2026-09-11

### Added

- The installer now offers an explicit language selection instead of relying on the host locale alone,
  and Korean joins English, Simplified Chinese, and Japanese. The NSIS wizard shows the standard
  language dialog before the welcome page and keeps every locale listed regardless of the host
  language; the IzPack installer ships the `kor` langpack, its licence resource, and the modal langpack
  selector. The chosen installer language stays installer-scoped and is never written to `config.json`.
- `GET /v1/downloads/<version>.json` reports per-release download request starts. Official mirror
  starts are added to GitHub's binary `download_count`, and the response carries one `assets` row per
  binary with its name, key, SHA-256, official, GitHub, and total values that reconcile with the release
  total. Checksum sidecars, HEAD/304, failed requests, nonzero resume ranges, and verification-prefixed
  traffic are excluded, and an unknown source stays `null` instead of printing a fabricated zero.
- Stable, Beta, and Nightly releases now carry reviewed Simplified Chinese and Japanese notes
  (`notesByLanguage`) with English as the fallback, and the website selects the language locally. A
  translation whose digest no longer matches the exact English section is rejected instead of reused.
  Nightly freezes its published, ancestral baseline and the real commit subjects while the candidate is
  prepared, so commits landing afterwards cannot change what an already-built candidate says.
- Reviewed translations also enrich historical releases without rewriting them: 0.43.10 and
  0.43.10-0.nightly.3 receive display supplements bound to their exact release ID, source revision, and
  original visible body, leaving their public Release bodies, tags, receipts, files, and build numbers
  untouched.
- Framework message catalogs are now held to the same locale matrix as the official plugins by
  `verifyFrameworkCatalogs`. Plugins already fail loudly on an incomplete catalog set; the framework
  resolves its chrome through `ResourceBundle`, where a missing catalog degraded silently to English.
  The new gate also rejects a framework module shipping catalogs outside the verified roots.

### Changed

- Beta and Nightly candidates record their frozen notes context (`schemaVersion: 2`), and promotion
  binds Stable notes to the exact `CHANGELOG.md` section plus the reviewed translation digest. A
  candidate whose `CHANGELOG.md`, `release-notes/`, or notes module changed after checkout now fails
  closed instead of publishing notes that were never reviewed.
- The Java uninstaller defaults to keeping `config.json`, matching the NSIS uninstaller, and headless or
  console runs without the property keep it as well.
- The reviewed SDK v9 exact anchor moved to the host-locale fix so the SDK contract keeps the applied
  language instead of the launcher's DISPLAY locale. The canonical API dump is unchanged; only the
  bytes of `UiHostCapabilityService.hostLocale()`'s default body moved, and the v2–v8 historical
  anchors remain as audited.

### Fixed

- Plugin UI language now follows the language chosen in Cubism Editor's File → Environment Settings →
  General → Language. The launcher's `-Duser.language` only selects the build's language version and
  never changes at runtime, so it is no longer treated as the host language. Because Cubism applies the
  saved setting to the process default locale after this runtime attaches, the effective locale is
  re-resolved once the verified host is ACTIVE; an explicit `-Dturboism.locale` or `config.json` locale
  still outranks the host.
- The framework's own `ResourceBundle` catalogs now carry the complete zh-Hans/zh-Hant/en/ja/ko
  matrix. `dev.turboism.ui.panel` was missing `messages_en.properties` and
  `messages_zh_Hans.properties`, so a Simplified Chinese host silently fell through to the legacy
  script-less `messages_zh.properties`. That catalog is kept as an optional compatibility alias, but
  it can no longer stand in for the script-suffixed one.
- Toolbar icons are loaded through the display-scale variants a plugin ships (125/150/175/200%) and
  resolved as one multi-resolution icon, so the installer entry is no longer drawn from a single
  unscaled bitmap on high-DPI displays.
- The Korean branch of the Java uninstaller's confirmation dialog is localized instead of falling back
  to English text, and the four README templates now describe the uninstaller's keep-by-default
  `config.json` checkbox instead of a delete-by-default one.

## [0.43.10] - 2026-09-09

### Added

- Current-page texture-atlas packing with an explicit scale contract and guarded native packing integration.
- Captured semantic history timeline with stable navigation and a configurable core toolbar icon.
- Independent release API, GitHub release synchronization, verified streaming mirrors, and globally allocated product build identities.

### Changed

- Adopted the reviewed SDK v9 exact release baseline and demoted v8 to a historical audit. The anchor adds one presentation field: `CubismOperationEvent` gained an optional `label` so an observed native Cubism Editor edit can carry the localizable native edit name. The component is appended after `subjectId` and is explicitly not an identity. **Plugin API migration may be required:** a plugin that constructs `CubismOperationEvent` directly must pass the new fifth component; see the [SDK v9 review](sdk/api-contracts/sdk-api-v9-review.md).
- Adopted the reviewed SDK v8 exact release baseline and demoted v7 to a historical audit. The anchor captures the captured-semantic-timeline and UI surface that the v7 gate never recorded plus four new `CubismOperation` identities for native editor edits (`SET_HIERARCHY_PARENT`, `DETACH_HIERARCHY_PARENT`, `MOVE_DRAWABLE`, `SET_DRAWABLE_COLOR`, appended so no existing constant ordinal moves). **Plugin API migration may be required:** the `HistoryEntry`, `RuntimeSettings` and `PanelView.Toggle` constructors changed; see the [SDK v8 review](sdk/api-contracts/sdk-api-v8-review.md).
- Split product releases into read-only candidate builds and explicit protected GitHub promotion. Failed candidate attempts reuse the intended version; only promotion creates the official annotated tag and publishes the verified bytes without rebuilding.
- Adopted the reviewed SDK v8 current-page texture layout contract.
- Added four-language project and installation documentation and task-contained local host validation supervision.

### Fixed

- Hardened the multi-version Scene palette bridge, added exact Cubism 5.3.03 routing, and preserved unified palette cleanup.
- Matched the core toolbar image to the host home icon size.
- Hardened host validation environment handling and rejection of malformed outcomes.

## [0.43.9] - 2026-09-06

This release supersedes the unpublished 0.43.4–0.43.8 candidates.

### Added

- Synchronous authoring transactions, grouped Glue writes, stable history entry/transaction identity, expanded typed MCP read/write operations, direct plugin installation from JAR files, and restored bounding-box overlay controls.

### Changed

- Adopted the reviewed SDK v7 exact release baseline while preserving v2–v6 historical baselines. **Plugin API migration may be required:** `McpHttpConnection` now takes endpoint/protocol only and no longer exposes `authorization()`; `HistoryEntry` carries additional identity fields; exception enum ordinals changed. See the [SDK v7 review](sdk/api-contracts/sdk-api-v7-review.md).
- Unified palette toolbar/filter contributions and reduced repeated model and history scans.
- The local loopback MCP server operates without bearer authentication.
- Release packages exclude managed fx runtime bytes and the development-only Turboism with fx plugin.

### Fixed

- Stabilized snapshot-race and asynchronous plugin-disable regression tests, corrected bounding-box draft metadata, and separated native Java installer Full payload testing from Windows policy coverage without weakening production validation.
- Included installer configuration validation before payload changes, parameter batch-transfer row layout fixes, SDK interface proxy coverage, Cubism 5.3.03 texture-atlas auto-layout hook selection, and MCP/runtime transaction and lifecycle fixes.

## [0.43.8] - 2026-09-06

This release supersedes the unpublished 0.43.4–0.43.7 candidates.

### Added

- Synchronous authoring transactions, grouped Glue writes, expanded typed MCP read/write operations, direct plugin installation from JAR files, and restored bounding-box overlay controls.

### Changed

- Unified palette toolbar/filter contributions and reduced repeated model and history scans.
- The local loopback MCP server operates without bearer authentication.
- Release packages exclude managed fx runtime bytes and the development-only Turboism with fx plugin.

### Fixed

- Corrected release verification: deterministic snapshot-race and asynchronous plugin-disable tests, schema-compliant bounding-box draft metadata, and native-OS Java installer Full payload testing with separate Windows policy coverage. No production validation, lifecycle behavior, or host selectors were changed by these verification fixes.
- Included installer configuration validation before payload changes, parameter batch-transfer row layout fixes, SDK interface proxy coverage, Cubism 5.3.03 texture-atlas auto-layout hook selection, and MCP/runtime transaction and lifecycle fixes.

## [0.43.7] - 2026-09-06

This release supersedes the unpublished 0.43.4–0.43.6 candidates.

### Added

- Synchronous authoring transactions, grouped Glue writes, expanded typed MCP read/write operations, direct plugin installation from JAR files, and restored bounding-box overlay controls.

### Changed

- Unified palette toolbar/filter contributions and reduced repeated model and history scans.
- The local loopback MCP server operates without bearer authentication.
- Release packages exclude managed fx runtime bytes and the development-only Turboism with fx plugin.

### Fixed

- Corrected the Cubism 5.3.03 bounding-box draft mapping metadata to conform to the existing schema; selectors and verification records are unchanged, and the draft remains unverified.
- Made snapshot-race and asynchronous plugin-disable regression tests deterministic without changing production validation or lifecycle behavior.
- Included installer configuration validation before payload changes, parameter batch-transfer row layout fixes, SDK interface proxy coverage, Cubism 5.3.03 texture-atlas auto-layout hook selection, and MCP/runtime transaction and lifecycle fixes.

## [0.43.6] - 2026-09-06

This release supersedes the unpublished 0.43.4 and 0.43.5 candidates.

### Added

- Synchronous authoring transaction scopes, grouped Glue writes, expanded typed MCP read/write operations, direct plugin installation from JAR files, and restored bounding-box overlay controls.

### Changed

- Unified palette toolbar and filter contributions with production host lifecycle handling and reduced repeated model and history scans.
- The local loopback MCP server operates without bearer authentication.
- Release packages no longer include managed fx runtime bytes or the development-only Turboism with fx plugin.

### Fixed

- Stabilized release regression coverage: snapshot-copy races explicitly cover changed and preserved timestamps, and repeated plugin-disable tests await terminal lifecycle completion rather than callback entry. Production validation and lifecycle behavior are unchanged by these test fixes.
- Included installer configuration validation before payload changes, parameter batch-transfer row layout fixes, SDK interface proxy coverage, Cubism 5.3.03 texture-atlas auto-layout hook selection, and MCP/runtime transaction and lifecycle fixes.

## [0.43.5] - 2026-09-06

This release supersedes the unpublished 0.43.4 candidate and includes its changes below.

### Added

- Added synchronous authoring transaction scopes, grouped Glue writes, expanded typed MCP read/write operations, direct plugin installation from JAR files, and restored bounding-box overlay controls.

### Changed

- Unified palette toolbar and filter contributions with production host lifecycle handling and reduced repeated model and history scans.
- Changed the local loopback MCP server to operate without bearer authentication.
- Removed managed fx runtime bytes and the development-only Turboism with fx plugin from all release packages.

### Fixed

- Made the snapshot-copy ABA regression tests independent of filesystem timestamp resolution, covering both observable file changes and corrupt snapshots with unchanged timestamps without weakening production validation.
- Included installer configuration validation before payload changes, parameter batch-transfer row layout fixes, SDK interface proxy coverage, Cubism 5.3.03 texture-atlas auto-layout hook selection, and MCP/runtime transaction and lifecycle fixes.

## [0.43.4] - 2026-09-06

### Added

- Added synchronous authoring transaction scopes, including grouped Glue writes, and expanded typed MCP read/write operations.
- Added direct plugin installation from JAR files and restored bounding-box overlay controls.

### Changed

- Unified palette toolbar and filter contributions with production host lifecycle handling.
- Changed the local loopback MCP server to operate without bearer authentication.
- Reduced repeated model and history scans in runtime hot paths.

### Fixed

- Made Windows and Java installers validate or migrate `config.json` before payload mutation, require an integer schema token and runtime-valid v1 values, and apply upgrade plugin selections without overwriting unrelated settings.
- Removed the managed fx runtime payload from every release channel and documented that the development-only Turboism with fx plugin ships in no release package; made the optional Windows fx resolver test skip safely when its private fixture path is absent.
- Fixed overlapping parameter batch-transfer binding rows, SDK interface proxy coverage, and Cubism 5.3.03 texture-atlas auto-layout hook selection.
- Addressed MCP and runtime review findings around transaction outcomes and lifecycle handling.

## [0.43.3] - 2026-09-03

### Added

- Added a managed Windows x64 fx v0.0.5 product payload to Full installers and archives, verified by exact size and SHA-256 before launch; Windows product repair or reinstall restores it because upstream provides no Windows repair archive.
- Added the localized “For you, a bouquet” dedication to the Core About window beneath the Turboism title.
- Added the complete Simplified-Chinese-authoritative Turboism End User Runtime Declaration v2.0 at the repository root and in public installers, with four separate required acknowledgements for project identity, lawful Cubism authorization, user-content backups, and as-is operation.
- Added concise startup phase-duration diagnostics for configuration, services, host adapters, plugin loading, and final reporting.
- Added a Turboism MCP Connection window that shows the current local MCP address, its bearer token, explicit copy actions, and a bounded process-local connection and request history that never records bearer values or MCP session identifiers.
- Added a stable default MCP port `43123`, with `turboism.mcp.port=0` still selecting an ephemeral port, plus verified Claude Code, Visual Studio Code, and Codex CLI configuration examples.
- Added a direct **Turboism → fx Settings** menu entry so the runtime path, fx-owned shell, and provider setup are reachable before any MCP or ACP connection exists.
- Added saved fx provider profiles with modal add, edit, remove, and select dialogs: the fx-owned Vercel, Codex, and Grok built-ins launch their exact fx login commands, and custom OpenAI-compatible or self-hosted endpoints are served by a Turboism-owned loopback adapter.
- Added best-effort `/v1/models` discovery plus a modal manual model-ID dialog for custom provider profiles.
- Added persistent custom-provider API-key storage so a key is entered once: protected with Windows DPAPI for the current user where that succeeds, and otherwise written to `auth.json` in the plugin's own configuration directory.
- Added deterministic Windows installer payload handling that skips unchanged JARs and the fx product payload by SHA-256 and no longer shows an empty finish page.
- Added PSD Clip Mask Import progress reporting and prevented re-entry while an import is running.
- Added persistent installer-launcher and managed subprocess diagnostics and made MCP parameter and model write outcomes, explicit binding presence, and committed creates retry-safe and correlated into runtime diagnostics.

### Changed

- Made the Core About window display the framework version generated from the authoritative Gradle release version.
- Corrected the release-plugin allowlist to publish the History Panel and PSD Clip Mask Import business plugins while keeping development shells, demos, and legacy placeholders out of public installers and archives.
- Enlarged the Windows installer and made the manual configurator resizable and maximizable; installation now discovers exact supported Cubism Editor installations (5.2.03, 5.3.02, and 5.3.03), selects every compatible installation found, and applies the chosen Turboism-shortcut and hash-guarded official-BAT controls headlessly without opening the configurator.
- Made optional managed GraalVM installation failures or cancellation visible and logged without aborting the remaining Turboism installation.
- Defined ordinary bottom-status notifications as a latest-message slot and recorded every status invocation through the calling plugin's scoped Turboism logger; compact resident metrics retain independent keyed slots.
- Clarified in every official UI Theme locale that Cubism Editor should be restarted after applying a theme to ensure it is rendered correctly.
- Documented that the Windows fx candidate admits only Turboism's exact authenticated numeric-loopback HTTP MCP server and does not claim durable-session, native-tool, general networking, process, or persistence parity with official Linux/macOS fx assets.
- Made Clip Mask Viewer show a localized loading state immediately and move detached relationship indexing, counts, analysis, and graph projection off the Cubism host thread, with cancellation and stale-result guards.
- Documented that fx v0.0.5 has no Claude subscription login and that a Claude Pro/Max or Claude Code subscription is not an Anthropic API credential, so no such provider profile is offered; the custom adapter implements OpenAI Chat Completions only.
- Documented that fx's Gateway reasoning level is deliberately not forwarded to OpenAI-compatible endpoints instead of being translated into a guessed `reasoning_effort`.
- Made fx Agent windows connect automatically without requiring a provider, model, or compatibility selection; missing provider/model setup is now reported only when the user sends a prompt.
- Made custom-provider default models optional and made **Use** reconnect the selected profile immediately.

### Fixed

- Fixed MCP tool writes racing host model operations by keeping model object creation consistent with the Cubism runtime and separating runtime diagnostics resources from connection state.
- Fixed MCP writes not surviving retries, parameter bindings and committed creates being misreported, and invalid runtime requests being recorded unsafely; write warnings are now correlated with runtime diagnostics and applied schemas align with the binding runtime.
- Fixed PSD Clip Mask Import comparing model ids across namespaces, which could misfire on equal ids from different scenes; id comparison now stays within one namespace and imports report progress without re-entry.
- Fixed the Windows installer finish page appearing empty after the payload SHA-256 work, and kept installer-launcher diagnostics draining concurrent labelled stdout/stderr reliably.
- Fixed the Windows uninstaller configuration-retention checkbox being attached to the outer wizard window, which prevented reliable interaction and could make the confirmation page sluggish.
- Changed the uninstall option to the unambiguous, default-enabled “Keep config.json” behavior; configuration is deleted only when the user clears it.
- Restored History snapshot availability on Cubism Editor 5.2.03 while preserving exact-version SDK admission.
- Restored Windows MCP startup when Java exposes a usable ACL view or the existing per-user Windows path and reparse checks, without logging bearer values, endpoints, or private connection-file paths.
- Added persistent configurator and managed-GraalVM subprocess diagnostics, including concurrent labelled stdout and stderr draining, so failed BAT integration and optional runtime setup are actionable.
- Stopped plugin activation from creating empty per-plugin config, data, and cache directories; storage now creates only the directory needed by the first real operation, plugin logs use the shared runtime log, and the obsolete `palette-filter-attach.tsv` diagnostic is no longer produced.
- Removed the unsupported reflective outside-canvas repaint attempt; theme changes now use the reliable restart-required behavior confirmed on Cubism Editor 5.2.03 and 5.3.02.
- Fixed the fx Settings window requiring an established connection before the fx shell could be opened, which made provider and model setup unreachable on a fresh installation.
- Fixed the custom-endpoint adapter rejecting the reasoning field fx sends on every request, ignoring the `ai-language-model-id` request header, and requiring an API key for unauthenticated self-hosted endpoints.
- Fixed custom profiles inheriting an unrelated Codex or Grok selection from the user's normal fx home by launching adapted connections with a plugin-owned Gateway-only fx home and an optional direct `--model` argument.
- Fixed prompt text being cleared before Turboism could report that no usable provider or model was selected.
- Replaced the restricted Windows fx payload that rejected ACP MCP servers with an independently reproducible build limited to Turboism's exact authenticated numeric-loopback HTTP MCP server.
- Fixed managed Windows launches reparsing path-bearing Turboism JVM options as standalone `cmd.exe` commands; options are now inserted as quoted arguments in an ephemeral Cubism BAT while inherited Java option variables and stale Turboism integration blocks are excluded from the child process.

## [0.43.2] - 2026-08-29

### Fixed

- Fixed Turboism runtime startup aborting before Core plugin UI registration when Cubism's bundled JVM does not expose the optional `java.net.http` module; managed GraalVM controls now fail closed without disabling menus, toolbar entries, or panels.
- Extended the managed GraalVM whole-download deadline from 20 minutes to 4 hours so slow but continuously progressing Windows downloads are not terminated prematurely.

## [0.43.1] - 2026-08-29

### Changed

- Clarified the Windows configurator's independent-shortcut and shortcut-takeover modes, kept official Cubism BAT files unmodified in both modes, generated managed `.lnk` filenames without spaces, and added an install-finish option to open the Turboism directory.
- Added persistent managed-GraalVM installation progress and diagnostics at `logs/installer/managed-graal-install.log`.

### Fixed

- Fixed Windows PowerShell launch and managed-GraalVM helpers failing when their case-insensitive `$home` variables collided with PowerShell's read-only `$HOME` automatic variable.
- Fixed managed GraalVM installation rejecting ordinary Windows files and directories because OpenJDK reports a null `BasicFileAttributes.fileKey()` on Windows; Windows now revalidates file type, size, and reparse-point state without requiring the unavailable key.

## [0.43.0] - 2026-08-28

### Added

- Exact-version runtime, SDK availability, authoring, history, lifecycle, and texture-atlas support for Cubism Editor 5.3.03.
- Turboism with fx, including ACP integration, durable-session recovery, and reviewed managed fx runtime payloads for supported Linux and macOS Java-installer packages.

### Changed

- Expanded the GitHub README with the supported Windows host platform, exact Cubism Editor versions, installation choices, current capabilities, and verification guidance.
- Added a regression check that every GitHub Release takes its notes from the matching version section in `CHANGELOG.md`.
- Grouped reusable test support and cross-module integration tests under `testing/`, and moved reviewed SDK API contracts beneath the `sdk/` domain.
- Separated public Cubism compatibility contracts from ignored local Cubism references and host evidence, and moved generated reference reports under `build/reports/`.
- Strengthened repository hygiene so forced additions of local reference, research, AI review evidence, generated-report, and validation-output paths are rejected.
- Split Windows-safe and Java-installer payload staging so managed native fx runtimes are limited to reviewed Linux and macOS Java packages.
- Added deterministic release plans, exact candidate and payload verification, an immutable eight-asset framework contract, and coordinated Plugin Directory and Updates publication sequencing.

### Fixed

- Preserved legacy SDK history implementations while rejecting stale document bindings and retaining native bindings without leaking host object graphs.
- Made parameter-group and binding-batch access scale linearly, preserved every native texture-atlas entry, and bound atlas resolvers and views to one host generation.
- Hardened managed-runtime lifecycle, ACP durable-session replay, installer platform policy, and local JSON parsing, including ASCII-only JSON digits and Unicode escapes.
- Made MCP bearer publication fail closed where owner-only permissions or protected Windows DACL proof cannot be established.
- Verified exact plugin rosters, Windows/Java payload separation, and every release-verifier call site before publication.

### Known limitations

- Windows packages do not contain the managed native fx runtime. MCP-backed Turboism with fx remains unavailable on Windows until a native protected-DACL and reparse-safe bearer-file publication mechanism is available.
- Managed child-process cleanup remains best effort until native Unix process-group and Windows Job Object containment is implemented.
- Published binaries are not code-signed or notarized; verify the accompanying SHA-256 sidecars after downloading.

## [0.42.0] - 2026-08-25

### Added

- Java 17 agent runtime and public plugin SDK for Live2D Cubism Editor.
- Exact-version runtime adapters for Cubism Editor 5.2.03 and 5.3.02.
- Windows NSIS installer, Lite and Full ZIP distributions, and a cross-platform IzPack installer.
- Official first-party plugin bundle with plugin lifecycle, permission, configuration, localization, task, event, action, menu, toolbar, workspace, and Cubism integration services.
- Plugin package inspection and Plugin Directory integration with deterministic release metadata.
- SHA-256 sidecars for every published installer and archive.

### Changed

- Consolidated public SDK governance into one released API tier with exact Cubism Editor availability annotations.
- Unified runtime event delivery and hardened plugin activation, replacement, teardown, and failure isolation.
- Made release packaging use one shared staged payload and a single reviewed plugin allowlist.

### Fixed

- Stabilized exact-host hooks, public event verification, plugin shutdown, backup continuation fencing, and recent-preview hover thumbnails.
- Hardened package inspection, configuration merging, path handling, report redaction, and supply-chain verification.

### Known limitations

- Windows is the primary Cubism host platform.
- The Java installer is available for macOS and Linux, but macOS Cubism host readiness is not claimed and Linux Cubism hosting is unsupported.
- Published binaries are not code-signed or notarized in this release; verify the accompanying SHA-256 sidecars before installation.

[Unreleased]: https://github.com/Turboism/Turboism/compare/v0.44.0...HEAD
[0.44.0]: https://github.com/Turboism/Turboism/releases/tag/v0.44.0
[0.43.11]: https://github.com/Turboism/Turboism/releases/tag/v0.43.11
[0.43.10]: https://github.com/Turboism/Turboism/releases/tag/v0.43.10
[0.43.9]: https://github.com/Turboism/Turboism/releases/tag/v0.43.9
[0.43.8]: https://github.com/Turboism/Turboism/releases/tag/v0.43.8
[0.43.7]: https://github.com/Turboism/Turboism/releases/tag/v0.43.7
[0.43.6]: https://github.com/Turboism/Turboism/releases/tag/v0.43.6
[0.43.5]: https://github.com/Turboism/Turboism/releases/tag/v0.43.5
[0.43.4]: https://github.com/Turboism/Turboism/releases/tag/v0.43.4
[0.43.3]: https://github.com/Turboism/Turboism/releases/tag/v0.43.3
[0.43.2]: https://github.com/Turboism/Turboism/releases/tag/v0.43.2
[0.43.1]: https://github.com/Turboism/Turboism/releases/tag/v0.43.1
[0.43.0]: https://github.com/Turboism/Turboism/releases/tag/v0.43.0
[0.42.0]: https://github.com/Turboism/Turboism/releases/tag/v0.42.0
