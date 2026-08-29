# Changelog

All notable changes to Turboism are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow Semantic Versioning.

## [Unreleased]

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

[Unreleased]: https://github.com/Turboism/Turboism/compare/v0.43.1...HEAD
[0.43.1]: https://github.com/Turboism/Turboism/releases/tag/v0.43.1
[0.43.0]: https://github.com/Turboism/Turboism/releases/tag/v0.43.0
[0.42.0]: https://github.com/Turboism/Turboism/releases/tag/v0.42.0
