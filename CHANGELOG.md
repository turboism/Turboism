# Changelog

All notable changes to Turboism are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow Semantic Versioning.

## [Unreleased]

## [0.43.3] - 2026-08-31

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

### Changed

- Made the Core About window display the framework version generated from the authoritative Gradle release version.
- Corrected the release-plugin allowlist to publish the History Panel and PSD Clip Mask Import business plugins while keeping development shells, demos, and legacy placeholders out of public installers and archives.
- Enlarged the Windows installer and made the manual configurator resizable and maximizable; installation now discovers exact supported Cubism Editor installations (5.2.03, 5.3.02, and 5.3.03), selects every compatible installation found, and applies the chosen Turboism-shortcut and hash-guarded official-BAT controls headlessly without opening the configurator.
- Made optional managed GraalVM installation failures or cancellation visible and logged without aborting the remaining Turboism installation.
- Defined ordinary bottom-status notifications as a latest-message slot and recorded every status invocation through the calling plugin's scoped Turboism logger; compact resident metrics retain independent keyed slots.
- Clarified in every official UI Theme locale that Cubism Editor should be restarted after applying a theme to ensure it is rendered correctly.
- Documented that the Windows fx candidate intentionally does not claim durable-session, ACP MCP-server, native-tool, networking, process, or persistence parity with official Linux/macOS fx assets.
- Made Clip Mask Viewer show a localized loading state immediately and move detached relationship indexing, counts, analysis, and graph projection off the Cubism host thread, with cancellation and stale-result guards.
- Documented that fx v0.0.5 has no Claude subscription login and that a Claude Pro/Max or Claude Code subscription is not an Anthropic API credential, so no such provider profile is offered; the custom adapter implements OpenAI Chat Completions only.
- Documented that fx's Gateway reasoning level is deliberately not forwarded to OpenAI-compatible endpoints instead of being translated into a guessed `reasoning_effort`.

### Fixed

- Fixed the Windows uninstaller configuration-retention checkbox being attached to the outer wizard window, which prevented reliable interaction and could make the confirmation page sluggish.
- Changed the uninstall option to the unambiguous, default-enabled “Keep config.json” behavior; configuration is deleted only when the user clears it.
- Restored History snapshot availability on Cubism Editor 5.2.03 while preserving exact-version SDK admission.
- Restored Windows MCP startup when Java exposes a usable ACL view or the existing per-user Windows path and reparse checks, without logging bearer values, endpoints, or private connection-file paths.
- Added persistent configurator and managed-GraalVM subprocess diagnostics, including concurrent labelled stdout and stderr draining, so failed BAT integration and optional runtime setup are actionable.
- Stopped plugin activation from creating empty per-plugin config, data, and cache directories; storage now creates only the directory needed by the first real operation, plugin logs use the shared runtime log, and the obsolete `palette-filter-attach.tsv` diagnostic is no longer produced.
- Removed the unsupported reflective outside-canvas repaint attempt; theme changes now use the reliable restart-required behavior confirmed on Cubism Editor 5.2.03 and 5.3.02.
- Fixed the fx Settings window requiring an established connection before the fx shell could be opened, which made provider and model setup unreachable on a fresh installation.
- Fixed the custom-endpoint adapter rejecting the reasoning field fx sends on every request, ignoring the `ai-language-model-id` request header, and requiring an API key for unauthenticated self-hosted endpoints.

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

[Unreleased]: https://github.com/Turboism/Turboism/compare/v0.43.3...HEAD
[0.43.3]: https://github.com/Turboism/Turboism/releases/tag/v0.43.3
[0.43.2]: https://github.com/Turboism/Turboism/releases/tag/v0.43.2
[0.43.1]: https://github.com/Turboism/Turboism/releases/tag/v0.43.1
[0.43.0]: https://github.com/Turboism/Turboism/releases/tag/v0.43.0
[0.42.0]: https://github.com/Turboism/Turboism/releases/tag/v0.42.0
