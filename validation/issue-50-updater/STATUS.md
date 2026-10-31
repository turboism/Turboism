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
- The coordinator persists an install-next-launch descriptor and copies the helper into independent storage.
  Production does not start a one-hour background waiter. The controlled launcher consumes the task before Cubism
  starts; it reauthenticates the manifest, old receipt and staged bytes. The descriptor supplies no signing authority.
  An installed agent cannot serve as its own updater classpath on Windows because the executing JAR remains locked.
- The production helper reauthenticates signed old installation ownership, advances the exact new receipt index in
  the same rollback transaction, and hash-guards retirement of old owned versioned libraries. Mutable installed-build
  metadata cannot supply old identity. Runtime health confirmation revalidates the entire new signed layout.
- Full-install receipt initialization verifies explicit signed inventory and installed bytes, is idempotent for the
  same receipt, and refuses to overwrite a different index. Its full-package installer invocation remains unwired.
- PreviewPluginRuntimeResources now binds the configured installer adapter. Activation and public keys are read
  only from the defining runtime JAR; absent activation, missing keys or legacy discovery keep installation disabled.

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
- Signed-fixture tests perform two consecutive authenticated receipt upgrades, preserve config/third-party additions,
  retire only old owned libraries, and restore both old index and payload after injected receipt-write failures/crash.
  These are unit/integration fixtures, not two actual published Cubism release upgrades.
- Windows JDK ProcessHandle command-line inspection proved insufficient in a real child-process test. The corrected
  helper uses bounded PowerShell/CIM inspection, rejects a live peer using the same agent, and fails closed on query
  errors. This is not an atomic launch/apply lease.
- No Linux/Proton execution evidence has been collected in this Windows session.

## Remaining release blockers

1. Provision/review release activation for the now-wired product lifecycle. The native Updates window, asynchronous
   adapter and configured receipt/downloader/coordinator construction are connected; actual published activation and
   eligible discovery are still absent. EN, zh-Hans, zh-Hant, JA and KO UI remains fail-closed for legacy discovery.
2. Provision and review the dedicated framework signing keyring. The helper now reads only framework-specific
   bundled authority, supports revocation, and rejects installation when no active key exists. It no longer borrows
   plugin-catalog keys. No production key has been provisioned; no private production key belongs in the client.
3. Deploy and qualify the reviewed framework API and protected publisher with owner approval. The implementation
   preserves the eight public release assets and serves independent signed documents/payloads from R2. Production
   endpoints, actual Cloudflare streaming behavior and incoming-object lifecycle remain unverified.
4. Wire signed bootstrap documents and receipt initialization into full installer/ZIP delivery, and qualify migration
   of existing private state/ownership. Transactional receipt advancement and two-upgrade fixture tests are now present;
   this does not yet establish bootstrap ownership for current publicly installed versions.
5. Review cache/installation safety changes on Windows: pure NIO does not provide hard-link counts, and directory
   fsync is unavailable through FileChannel. These limitations are NOT equivalent to native handle guarantees.
   Do not assert that content hashes alone provide anti-hardlink-write safety or power-loss durability.
6. Harden recovery against hostile journal/backup/path mutation; allowlist validation and shared cross-process
   helper/health locking are present but are not a complete handle-based TOCTOU defense.
7. Qualify the durable install-next-launch workflow on the actual supported Windows host, including interrupted
   scheduling, atomic launch/apply interlocking and broken-launcher recovery. The reviewed production path no longer
   depends on a one-hour helper lifetime. Peer-process checks do not establish an atomic launch/apply lease.
8. Finish native-window host acceptance, opt-in and retry/recovery diagnostics. The current framework window has
   no selected-plugin batch controls: the existing signed-catalog plugin updater is a separate workflow. Connect only
   user-selected compatible plugin updates to the restart plan without claiming partial success as complete success.
   Never replace loaded framework/Graal/SDK bytes in process or force Cubism to close.
9. Complete supported-Windows acceptance of the native UI and authenticated download-to-next-upgrade chain,
   beyond the now-observed rebuilt-agent startup health/recovery. Retain reports; label any Linux/Proton evidence.
   A broader shell test currently fails DeclarativeSettingsRendererTest's long-control layout assertion; no baseline
   causality has been established, so the full shell suite is not claimed green.

Earlier offline protocol documentation (`distribution/INCREMENTAL.md`, `native/update-files/README.md`) describes
pre-existing gates. Passing local tests or building this branch does not activate production endpoints or keys.

## Delegated review checkpoint

- **API approved/integrated:** merge `a1b55d996` includes website base `c6728c562` and reviewed publisher fixes
  through `4f2f5d0e`. Independent Node execution passes **78 tests**, both in the submitted worktree and after merge.
  Failed public preflight withdraws the candidate; ordinary retry of a withdrawn candidate returns 409
  `REACTIVATION_REQUIRED`. Only explicit protected reactivation may advertise it again. Admission precedes preflight;
  compensation is not atomic preflight-before-advertising. Nothing was deployed or activated.
- **Next-launch approved/integrated:** merge `b49c670f1` incorporates the reviewed revisions through `a360896e0`.
  Earlier no-op-recovery task loss, malformed-task startup loops, unbounded reading and partial writing were corrected.
  Complete rollback (exit 20) permits old-version startup; incomplete recovery and live-use failures still block it.
  Independent update-package execution on `a72de30b3`: **353 tests, 0 failures/errors, 6 skips**. After integrating the
  final fixes, independent receipt/downloader/next-launch execution: **150 tests, 0 failures/errors, 3 skips**.
  The real second-JVM signed-receipt test runs; this is not physical power-loss evidence. The open-file rollback test
  is explicitly Windows-only, because POSIX permits replacement of an open file.
- **Bootstrap revision `af27624e` not approved:** independent Python execution passes **10 tests**, including real
  Ed25519 receipt initialization and payload-tamper rejection on this Windows host. Review nevertheless found use
  of nonexistent installation-state fields, incomplete explicit-ACL cleanup/junction guards, and developer-worktree
  and cache paths hardcoded in its integration test. Returned for correction and real different-receipt collision
  coverage. ZIP embedding now has a real ZIP fixture; no actual NSIS compilation has been performed.
- **Selected-plugin linkage remains absent from this branch:** the read-only audit is complete; implementation is
  delegated using the existing planner, verified catalog and pending operations. It must separate download from user
  install consent, preserve dual-API compatibility and avoid falsely claiming cross-journal atomicity. No plugin-batch
  implementation or acceptance evidence has been approved yet.
