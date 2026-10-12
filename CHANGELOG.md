[EN / English](CHANGELOG.md) · [ZH / 简体中文](CHANGELOG_zh.md) · [JP / 日本語](CHANGELOG_ja.md) · [KR / 한국어](CHANGELOG_ko.md)

# Changelog

All notable changes to Turboism are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow Semantic Versioning.

## [Unreleased]

## [0.67.0] - 2026-10-13

### Added

- Comprehensive multi-version capability adaptation across 20 reviewed Live2D Cubism Editor versions
  (5.0.00 – 5.3.04). Across all 143 framework capabilities, 129 universal capabilities (90.2%) are admitted
  across the entire reviewed version set. Coverage by patch line:
  - Cubism 5.0.x (5.0.00–5.0.06, 7 versions): 129 / 143 capabilities (90.2%).
  - Cubism 5.1.x (5.1.00–5.1.03, 4 versions): 135 / 143 (94.4%) in 5.1.00–5.1.01 and 136 / 143 (95.1%) in 5.1.02–5.1.03.
  - Cubism 5.2.x (5.2.00–5.2.03, 4 versions): 140 / 143 capabilities (97.9%) across all versions.
  - Cubism 5.3.x (5.3.00–5.3.04, 5 versions): 143 / 143 capabilities (100.0%) across all versions.
  Capability sets satisfy strict monotonicity within each minor line. Evidence-based version boundaries in
  `compatibility/cubism/version-boundaries.json` document architectural differences: Cubism 5.0.x lacks host
  auto-backup, protected-export, auto-yure read, top-menu integration, and external-app edit-toggle; the
  edit-protocol bridge is available since 5.1.02; animation timeline SDK/runtime, selection brush, and mesh
  geometry read are available since 5.2.00; and part inspector writes, part opacity writes, MOC3 export reads,
  and alpha composition are available since 5.3.00. Multidocument compatibility matrices are available in
  English, Simplified Chinese, Japanese, and Korean under `compatibility/cubism/` covering capabilities and plugins.
- Every first-party plugin JAR ships a derived `META-INF/turboism/cubism-versions.json` descriptor declaring
  its exact supported Editor versions generated from reviewed capability requirements. The SDK `@CubismEditor`
  annotation contract is established: a bare annotation without arguments declares availability across all 20
  reviewed versions, while explicit attributes define constrained subsets.
- First-party plugin version support expanded across all 20 reviewed versions:
  - `parameter`, `recent-preview`, and `warp-deformer-alt-symmetry` are fully functional across all 20 reviewed versions without feature degradation.
  - `mesh-edit-mirror-axis-enhance` and `selection-brush` support mesh editing across all 20 versions; normal modeling view selection brush (from 5.2.00) and Core geometry reads (from 5.2.00) are established as evidence boundaries.
  - `mcp` supports texture writes and transaction execution across all 20 versions, with editor command execution available from 5.1.00 onward.
  - `command-palette` and `protected-export` are available from 5.1.00 onward by evidence boundary (as earlier Cubism versions lack top-menu and export orchestration), while `webdav-backup` is supported across all 20 versions with auto-backup capabilities active from 5.1.00 onward.
- Host validation probe version gates now comprehensively cover all 20 reviewed Editor versions (5.0.00–5.3.04), bucketed by exact bytecode shape, ensuring end-to-end host verification across the complete supported Cubism spectrum.
- Automated host startup dialog assistance (`NativeFreeStartup`): handles dialog interactions across all
  supported Cubism versions during testing and validation runs. It automates PRO trial selection
  ("Start PRO Version Free Trial") along with confirmation and activation handling (falling back to FREE when
  the trial path is unavailable), dismisses update notifications and home news windows, automatically confirms
  warnings when opening newer project files in older Editor versions, and suppresses the 5.0.x JavaFX
  announcement dialog (`AnnouncementDialog` background stage hidden via the JavaFX thread) to prevent host
  main-frame and menu-bar lockouts. The exact-host validation task queue derives task version sets directly
  from capability dependencies in the matrix, orchestrating 56 distinct validation tasks.
- 3D Model Import plugin (`dev.turboism.plugin.model-import-3d`): native 3D model import supporting static
  opaque GLB, glTF, OBJ/MTL, and model ZIP archives with PNG/JPEG textures, full 360-degree XYZ rotation, and
  bounded geometry optimization committing through a single undoable authoring transaction.
- Turboism ACP plugin (`dev.turboism.plugin.acp`): connects user-installed ACP-compatible AI agents (Claude
  Agent ACP, Codex ACP, Pi, Gemini CLI, OpenCode, Antigravity, Devin CLI, or custom executables) to the
  authenticated Turboism MCP server, featuring executable detection on PATH, durable session persistence, and
  a credential-free stdio-to-HTTP MCP attachment bridge.
- Animation workspace support in the SDK and runtime: plugins can enumerate animation documents, project
  timelines, tracks, attributes, and keyframes; activate and rename scenes; seek playback; apply batched
  keyframe edits and curve types; and record evaluated values. A pure-SDK `Motion3Validator` reports structural
  issues in motion3 data. The animation workspace is available on Cubism 5.2.00–5.3.04.
- History Panel plugin (`dev.turboism.plugin.historypanel`): Photoshop-style docked history panel projecting
  the active document's native Undo history with snapshot-bound click-to-jump Undo/Redo navigation.
- Recent Preview plugin (`dev.turboism.plugin.recent-preview`): captures bounded viewport preview thumbnails
  for recently opened project files and contributes them to the host Recent Files hover bridge with
  memory-bounded caching.
- Texture Atlas packing plugins: MaxRects-BSSF auto-layout plugin (`dev.turboism.plugin.texture-atlas`), Dalsoo
  polygon packing plugin (`dev.turboism.plugin.texture-atlas-dalsoo`), and texture-atlas statistics; runtime
  tile-bbox processing and output cache reuse cutting editor open and export times.
- Selection Brush plugin (`dev.turboism.plugin.selection-brush`): modeling surface selection brush tool
  supporting weighted point selection.
- Warp Deformer Alt Symmetry plugin (`dev.turboism.plugin.warp-deformer-alt-symmetry`): Alt vertical and
  Alt+Shift horizontal symmetry dragging for warp deformer control points across all reviewed versions,
  supported by verified native drag-tick hooks on Cubism 5.2.03, 5.3.02, and 5.3.03 with live mirrored selection
  weight painting, falling back to AWT dispatch elsewhere.
- Keybindings window and runtime dispatcher: customizable shortcut mapping in the Turboism menu,
  intercepting host key events to trigger plugin actions and rebind native shortcuts with conflict detection
  and persistent storage in `state/runtime/keybindings.properties`.
- Public SDK distribution: each release ships `turboism-sdk-<version>.jar` and SHA-256 sidecar as a
  standalone developer asset, with local Maven coordinates via `publishToMavenLocal` and a standalone project
  template in `templates/plugin-template`.
- Typed plugin service lookup via `PluginContext.services()` and `PluginServiceDirectory`.
- Process-wide window icon customization via `TurboismWindowFactory.installWindowIcon`.
- Performance settings tab controls for Cubism ZGC launch and automatic backup suspension during editing.
- Developer governance gates in `devCheck`: `checkVersionMonotonicity` enforces capability superset invariants
  across patch releases; `checkVersionSetRegistry` requires explicit registration of literal Cubism version
  sets in `scripts/version-set-registry.json`; `checkSdkCubismEditorAnnotations` blocks unannotated public
  Cubism SDK APIs against `sdk/api-contracts/cubism-annotation-baseline.json`; and
  `check_host_validation_versions.py` verifies all 56 host validation tasks derive version coverage from
  matrix requirements. Version adaptation rules and standards are documented in `CONTRIBUTING.md`.
- Self-contained 3D test assets in `test-assets/model-import-3d/` (`cube.glb`, `cube-textured.glb`,
  `plane.glb`, `plane-seam.glb`), enabling offline regression testing of 3D imports without proprietary fixtures.

### Changed

- SDK contract convergence and exact baseline v14:
  - Removed deprecated per-service `PluginContext` getters; service resolution is unified through
    `ctx.services().find(ServiceType.class)` and `require(ServiceType.class)`.
  - The parameter query read plane (`ParameterQueryService`, `ParameterSummary`, `ParameterBounds`) is removed;
    `ParameterAppearance` exposes palette visibility and editability (`visible()`, `editable()`), and
    parameters are queried through `cubism().model().active().parameters().all()`.
  - The legacy command queue and `TransactionManager` are replaced by authoring transactions and direct
    `Parameter.setValue`.
  - The internal `StrictJson` codec is replaced by `dev.turboism.sdk.json.Json`.
  - Auto-backup file paths are replaced by `BackupArtifactHandle` metadata.
  - The reviewed SDK exact baseline is now v14, with v2–v13 retained as historical audits. Plugin API
    migration is required for affected surfaces.
- The built-in core plugin is folded into the runtime as the framework shell (`dev.turboism.shell`),
  removing the separate plugin JAR while retaining config, task, and log attribution.
- Managed launches run Cubism on ZGC (`-XX:+UseZGC`) by default, eliminating second-scale G1 GC pauses and
  reducing steady-state RSS.
- Parameter CSV batch imports execute in a single authoring transaction instead of committing per write,
  reducing structure rebuilds by 89% and median write latency by 75%.
- Startup suppression (skip update check, splash screen, and info dialogs) is now enabled by default.
- Host hooks are declared in `META-INF/turboism/hooks` and scanned via `HookContributor`, replacing manual
  agent wiring while preserving fail-closed admission.
- Continuous integration runs `devCheck` and the full `checkCompletedCommit` suite on every pull request and
  push using Xvfb for display-dependent tests.
- Javadoc presence checks use the JDK 17 compiler tree API, covering implicit public interface methods and
  publicly reachable nested types.
- Semantic event types relocated to `dev.turboism.sdk.cubism.event`.
- Release gates enforce complete localization across all supported Cubism languages for marketplace listings
  and candidate release notes.

### Removed

- Completely removed the managed fx runtime: no bundled agent binaries, credential stores, or OpenAI gateway
  adapters; the development-only `turboism-with-fx` plugin id is retired.
- Removed `ParameterQueryService`, `ParameterSummary`, `ParameterBounds`, and `PluginContext.parameterQuery()`
  from the SDK.
- Removed deprecated per-service `PluginContext` convenience accessors, `availableServices()`, and the legacy
  `cubism.transaction` queue types.

### Fixed

- ACP-attached agents can use Turboism MCP write tools over a precompiled stdio bridge without requiring a
  compiler on the host JRE, and sessions auto-reconnect on MCP endpoint changes.
- `ClassPinTable.load` falls back to the system class loader when bootstrap agent classes have no defining loader.
- Removed machine-specific paths from preview launch scripts, test harnesses, and monitors.
- Enforced strict plugin scope lifecycle and permission checks across animation, clip mask, PSD, texture
  atlas, and physics services, invalidating access upon plugin scope disposal.
- Animation time scaling applies consistent affine transforms to Bézier control handles.
- Bounded memory growth: FIFO eviction on overlay button tables, weak caching for editor binding identities,
  and live-entry pruning for recent previews and palette walkers.
- Texture atlas cache reuse falls back to the rebuild path when input digests cannot be computed.
- `Motion3Validator` validates numeric ranges before narrowing segment identifiers.
- Managed Graal installer reconciles orphan activation markers left by interrupted installations.
- Document switching publishes newly bound models immediately, preventing evaluated reads from pinning
  stale models.
- Agent hook manifest resolves reliably on the boot class path regardless of process working directory.
- Canvas context menu no longer receives extraneous workspace-level plugin injections.

### Security

- The MCP loopback server requires `Authorization: Bearer` token authentication for mutating `tools/call`
  requests and session lifecycle, backed by an owner-only persisted token and stdio bridge.
- Scope-isolated disposal guarantees and atomic hook unregistration prevent cross-session resource leaks and
  post-disposal operations.

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

[Unreleased]: https://github.com/Turboism/Turboism/compare/v0.67.0...HEAD
[0.67.0]: https://github.com/Turboism/Turboism/releases/tag/v0.67.0
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
