# Issue #50 — implementation / evidence status

This is an in-progress branch, **not a production-ready self-updater**.

## Latest implementation

- `FrameworkUpdateApplier.applyPendingHealth` retains backups with `AWAITING_HEALTH`.
- The managed launcher runs a retained, independent `helper-runner.jar` before starting Cubism.
- `--prepare-launch <launcher PID>` records the first startup attempt (`STARTING_HEALTH`). A second invocation
  refuses recovery while the preceding launcher is still alive (PID plus start instant, not PID alone).
  The Windows launcher also rejects preparation if a live JVM still references this installed agent.
- `PreviewRuntime` confirms health only after host, shell, plugin initialization and startup reporting complete.
  Confirmation binds installation path, loaded agent path, version, size and SHA256. Backups are then discarded.
- If the preceding startup exited without confirmation, the next managed launch restores the prior installation
  before starting Cubism. This is next-launch recovery, not forced shutdown or immediate recovery inside a JVM.
- A PREPARED journal must never restore backup files: copying may have been interrupted and the old installation
  has not yet been modified. Such preparation is discarded. APPLYING or unconfirmed-health states restore backups.
- The coordinator copies the helper into independent storage before launching it. An installed agent cannot serve
  as its own updater classpath on Windows because the executing JAR remains locked.

## Evidence boundaries

- Health transaction unit tests cover confirmation, wrong version, changed bytes, unconfirmed startup, concurrent
  launch refusal, PID reuse, and interrupted partial backups.
- Real second-JVM tests exercise a launcher exiting without confirmation followed by recovery in the next process.
- Real second-JVM tests verify that a held transaction lock excludes recovery in another process without changing
  installation bytes or discarding the journal. Helper and runtime health confirmation share that lock.
- The PowerShell launcher parses successfully under Windows PowerShell 5.1.
- Prior manual Windows Cubism 5.3.04 testing used an official 0.67.4 ZIP from the release API, verified against its
  advertised size and SHA256, installed at `D:\turboism-test\home` in independent-launch mode.
- Local test updates 0.67.5/0.67.6 are synthetic identities, NOT official releases. The 0.67.6 agent uses 0.67.4 code
  with modified version metadata. The first synthetic update had an API-version mismatch; the later one started
  with 29 plugins loaded, zero plugin failures and no startup ERROR.
- Subsequent rebuilt-agent Windows Cubism tests exercised health confirmation using actual new updater code,
  including recovery from an unconfirmed launch and successful startup confirmation after fixing null CodeSource
  for bootstrap-loaded classes. See cubism-health-validation.txt. These are local test builds, not official
  numbered release acceptance; discovery/download/UI interaction and complete consecutive upgrades remain unproven.
- No Linux/Proton execution evidence has been collected in this Windows session.

## Remaining release blockers

1. Complete product lifecycle binding of trusted receipt/downloader/coordinator configuration. A modeless native
   Updates window and asynchronous core adapter now exist, with EN, zh-Hans, zh-Hant, JA and KO strings. A legacy
   discovery service remains explicitly UNAVAILABLE for installation; the UI does not bypass missing authority.
2. Provision and review the dedicated framework signing keyring. The helper now reads only framework-specific
   bundled authority, supports revocation, and rejects installation when no active key exists. It no longer borrows
   plugin-catalog keys. No production key has been provisioned; no private production key belongs in the client.
3. Publish signed manifests/payload objects through a deliberately migrated asset contract and release gate.
4. Write/migrate trusted installed receipts and update them transactionally after each application. Without this,
   consecutive fully authenticated delta downloads are not a complete chain.
5. Review cache/installation safety changes on Windows: pure NIO does not provide hard-link counts, and directory
   fsync is unavailable through FileChannel. These limitations are NOT equivalent to native handle guarantees.
   Do not assert that content hashes alone provide anti-hardlink-write safety or power-loss durability.
6. Harden recovery against hostile journal/backup/path mutation; allowlist validation and shared cross-process
   helper/health locking are present but are not a complete handle-based TOCTOU defense.
7. Qualify process-exit identity and detached helper lifecycle, including sessions longer than the current one-hour
   helper wait, launcher death while Cubism remains alive, and normal close/restart with unsaved documents.
8. Finish native-window host acceptance, opt-in, retry/recovery diagnostics, and safe plugin hot updates through
   the existing coordinator. The UI exposes background download, cancellation and install-on-user-exit, never forced
   restart. Never replace loaded framework/Graal/SDK bytes in process.
9. Complete supported-Windows acceptance of the native UI and authenticated download-to-next-upgrade chain,
   beyond the now-observed rebuilt-agent startup health/recovery. Retain reports; label any Linux/Proton evidence.
   A broader shell test currently fails DeclarativeSettingsRendererTest's long-control layout assertion; no baseline
   causality has been established, so the full shell suite is not claimed green.

Earlier offline protocol documentation (`distribution/INCREMENTAL.md`, `native/update-files/README.md`) describes
pre-existing gates. Passing local tests or building this branch does not activate production endpoints or keys.
