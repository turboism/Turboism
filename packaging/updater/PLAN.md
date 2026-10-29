# Turboism in-Cubism incremental updater — implementation plan

Status: design + offline prototype only (NOT a production updater)  
Target: Windows x64 supported Cubism hosts; Linux/Proton is verification-only until separately qualified.  
Related UI prototype: packaging/updater/prototype.html  
Planner prototype: runtime/src/main/java/dev/turboism/update/FrameworkDeltaPlanner.java

## User outcome

A user sees **Turboism → Updates** inside Cubism, reviews the version and changes, clicks **Download update**, and continues editing while verified files download to a private cache. After download:

- For a standalone plugin update, offer **Apply now** only if the current hot-reload coordinator explicitly marks that plugin safe. Otherwise, defer to restart.
- For a framework/agent/SDK/bootstrap/GraalJS update, offer **Restart and update** or **Install on next launch**; never replace active agent or loaded dependency bytes in process.
- No browser, manually downloaded EXE/ZIP, or NSIS wizard in the normal update path. Keep a manual full-installer recovery path.

A downloaded update is not an installed update. The UI must distinguish **downloaded**, **verified**, **waiting for restart**, **applying**, **applied**, and **failed/rolled back**.

## Starting point (main, reviewed 2026-10-29)

- RuntimeUpdateService checks the release-channel JSON, compares installed version/build identity and currently only provides discovery and a URL for the website/installer. It does not download or apply framework payloads.
- RuntimePluginAutoUpdater already downloads/verifies signed-catalog plugin artifacts, stages them, and attempts hot reload only when allowed.
- packaging/windows-installer/assemble-release.sh generates SHA-256 file manifests and a conditional payload extractor; install-jar-payload.ps1 skips files with matching hashes. This reduces extraction work **after** the full EXE has been downloaded; it is NOT network delta delivery.
- distribution/README.md documents channel feeds, R2 immutable-file access, ETag/range and health behavior. Public product contract currently admits complete installer/ZIP only.
- Release protocol explicitly binds version, channel, build number, source SHA and immutable assets. An updater must not bypass these identity and publication gates.

## Scope and non-goals

**MVP:** file-level delta download for framework-owned immutable payload, authenticated manifest discovery, progress/cancel/resume, cache verification, external restart-time apply with rollback, Cubism UI, robust fallback. First-party independent plugin updates keep using the current signed plugin catalog.

**Not MVP:** bsdiff on compressed JARs; arbitrary native code or Cubism Editor binary patching; overwriting user plugin files; updating a running Java Agent; unattended restart of a document with unsaved changes; automatic channel switching; changing the existing installer/uninstaller ownership model without migration evidence.

The demo HTML uses invented sizes, versions and timers. It makes **no network requests**, installs **nothing**, and is not a Cubism integration.

## Proposed architecture

1. **Publisher / CI.** After existing release verification, generate a deterministic, canonical UTF-8 manifest for the exact source-bound candidate. Enumerate allowed framework-owned files from the staged payload (agent, approved scripts, pinned graal/lib, bundled first-party plugins, icons/documentation as appropriate). Each entry records normalized relative path, bytes, SHA-256 and immutable artifact URL or content identity. Record build number, version, channel, source SHA, manifest schema, compatibility and minimum updater version. Do not include config.json, user settings, projects, per-user plugin JARs, logs, install state or downloaded managed GraalVM.
2. **Authenticity.** Independently sign the manifest (Ed25519), with trust anchors shipped in the current runtime/updater and a planned key rotation process. Hashes from the same HTTP origin alone are NOT a signature. Verify signing key, signature, full release identity and digest/length of every downloaded file before making a staging plan.
3. **Distribution.** Extend the existing distribution API and R2 publication pipeline deliberately: immutable authenticated manifest and content-addressed payload objects; publish/verify all objects before advertising the version in channel feeds. Existing 8-asset GitHub Release rule and API whitelist must be explicitly migrated and tested rather than silently violated. Maintain GitHub full-package recovery and a bounded, allowlisted download-origin/failover policy. R2 availability is not mainland-China performance evidence.
4. **Runtime.** Retain RuntimeUpdateService as discovery source, add a separate FrameworkUpdateCoordinator with a persisted state machine: IDLE → CHECKING → AVAILABLE → DOWNLOADING → VERIFYING → STAGED → APPLY_PENDING → APPLIED, plus CANCELLED/UNAVAILABLE/FAILED/ROLLED_BACK. Download off the Cubism UI and render threads. Apply strict size/count limits, concurrency limits, timeout/retry/backoff, resumable Range/ETag handling, disk-space checks, free-space reservation and monotonic progress. Persist only verified staging records.
5. **Planner.** Compare manifest entries to local files by size and SHA-256; preserve unchanged files, fetch only changed/new files, never follow links out of the install root, and never rewrite mutable state. Prototype FrameworkDeltaPlanner demonstrates selection only; it does not parse remote manifests, download, modify or delete files.
6. **External updater helper.** A fixed, bundled, independently executable helper waits for the exact Cubism process to exit; acquires an update lock, re-verifies manifest and staged bytes, backs up the previous owned files and metadata, performs an atomic-or-journaled transaction, updates installed-build.json last, and restores the previous consistent version after failure. Idempotent recovery handles crashes, locked files, partial writes, antivirus, power loss and version drift. Do not infer that the process exited just because a UI window closed.
7. **UI.** Add a new Updates panel or entry alongside existing About/update settings with Check, Download, Cancel, Restart and update, Install next launch, and View release notes. Show target version/channel, human-readable size estimate, progress and failure diagnostics. Localize to EN/zh-Hans/JA/KO. No unsolicited shutdown; unsaved Cubism work must stay under user control.
8. **Plugin path.** Leave first-party marketplace catalog updates owned by RuntimePluginAutoUpdater. Reuse its hot-reload coordinator strictly when isHotReloadSafe says yes. No forced classloader mutation for agent/SDK/loaded Graal dependencies. Avoid clobbering separately managed plugin versions or user-installed third-party plugins.
9. **Bootstrap.** Existing builds cannot understand signed delta manifests. The first release containing this updater may require one final conventional full install, then later builds can use the embedded path. Offer full installer if manifest, helper, signature, package base or disk conditions fail safely.

## State transitions and ownership

- CHECKING / AVAILABLE: reads version/changelog only; no disk changes outside cache/preferences.
- DOWNLOADING / VERIFYING: private temporary storage, cancellable; never mutate active installation.
- STAGED: signature, release identity, compatibility, file hashes and update plan verified.
- APPLY_PENDING: plugin-only eligible hot reload OR external restart helper; no ambiguous 'updated' banner while files are still staged.
- APPLIED: helper finished and new installed identity verified; only then discard rollback backups.
- FAILED: current installation remains unchanged or is restored; surface retry / recovery options and diagnosis without losing the original configuration.

## Security and correctness gates

- Fail closed on unavailable/404/HTML feed, stale cache, absent signature, malformed manifest, unknown signing key, mismatched version/channel/build/source SHA, unexpected URL/redirect, hash failure, unsupported path, symlink/junction, duplicate case-folded path or inadequate disk space.
- Do not silently downgrade based on build numbers. Use SemVer / channel compatibility policy and immutable build identity. Beta/Nightly selection is an explicit setting.
- Protect installer-managed Cubism BAT integration with its existing hash-guarded ownership/backups. Never overwrite unknown user modifications.
- Validate the exact install root, reject relative traversal and Windows path aliases, and protect against reparse-point TOCTOU at apply time.
- Avoid replacing files that are loaded by Cubism. Never hot-reload arbitrary framework classes. Prefer restart to a risky 'no-restart' feature.
- Test key rotation, tamper detection, repeated update requests, interrupted downloads, resumed Range, failed helper start, permissions, locks, rollback and safe-mode behavior.

## Proposed milestones (separately reviewable PRs)

**M0 — prototype (this PR).** Document architecture, UX and trust boundaries. Add an offline, read-only Java file-delta planner with targeted tests. Add a self-contained interactive UI mock, clearly simulated.

**M1 — signed publishing contract.** Deterministic manifest builder, signature verification, staged artifacts, publication test vectors, distribution endpoints and immutable-object retention. Confirm CI source identity and channel pointer ordering. No production UI button until this is verified.

**M2 — download and staging.** FrameworkUpdateCoordinator, bounded authenticated downloader, progress and cancel, durable state/cache, retry/fallback, test doubles and corruption/interrupt tests.

**M3 — safe application.** Independent updater helper, process-exit guard, transactional apply/rollback, restart behavior, backwards-compatible config preservation and Windows VM fault-injection tests.

**M4 — UI and release.** Wire the Cubism Updates panel, notes, localization and opt-in controls; instrument nonblocking behavior and record real Windows x64 host results (and Proton as separate evidence). Small-channel rollout before stable.

**M5 — optional binary delta.** Measure compressed-JAR churn first. Only add binary patch assets for files whose delta saves meaningful bytes; otherwise use the existing file replacement algorithm.

## Acceptance checklist

- [ ] One click in Cubism downloads changed *framework-owned* files without visiting a browser.
- [ ] Demonstrated unchanged GraalJS JARs are never transferred and a changed plugin JAR is not mistaken for an update of unrelated framework files.
- [ ] Download progress is truthful, cancellable and survives retries; no GUI/render-thread stalls.
- [ ] Safe plugin updates can hot reload; unsafe plugins defer; framework/agent always waits for restart.
- [ ] Unsaved Cubism documents are not force-closed.
- [ ] A failed or interrupted update restores the exact old installation without touching user configuration, third-party plugins, projects or modified official BAT files.
- [ ] Signed manifest/hash/identity/path checks fail closed, with full-package fallback.
- [ ] Windows real-host and offline automated tests cover fresh install, successive versions, no-op, lock, interruption, rollback, downgrade/channel switch, corrupted feed and startup recovery.
- [ ] Current eight-asset publish gate and public download API are migrated explicitly before shipping new asset types.

## Prototype instructions

- Open packaging/updater/prototype.html in a local browser to try simulated framework and plugin flows. Nothing is actually downloaded.
- Run ./gradlew :runtime:test --tests dev.turboism.update.FrameworkDeltaPlannerTest on a Java 17 development machine.
- The read-only planner accepts a list of already authenticated payload entries and a local install root. Production integration requires a verified manifest, trusted URL policy, real downloader, helper and host evidence.
