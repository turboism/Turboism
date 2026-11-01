# Issue #50 — implementation / evidence status

## Current result: official Beta framework updates accepted (2026-11-01)

**Latest release: 0.68.1-beta.10 / Build 84.** PR #64 combines pending framework and plugin updates in one list,
hides components without an update, and shows the selected component's localized notes on the right. Plugin-only
updates, combined MB totals and elapsed time are included. All PR/main checks, official signing, protected publication,
and public API/signature/36-object HEAD/Range checks passed. No service deployment was needed.

The official Beta.10 window passed a real two-plugin download, queue and managed-restart installation in Cubism
5.3.02: both 0.68.0 → 0.68.1 hashes matched; the other 30 plugin JARs, configuration, preferences, signed framework
and receipt stayed unchanged. Startup reported 30 enabled plugins and zero failures; updated rows disappeared.
See [unified update window acceptance](unified-update-window-acceptance.md) for exact release and native evidence.

The official Beta.9 / Build 83 → Beta.10 / Build 84 coordinated framework-plus-two-plugin upgrade also passed.
All 36 installed framework files matched the admitted signed manifest, runtime startup health was confirmed,
30 enabled plugins started with zero failures, other plugin files/settings stayed unchanged, and both transactions
cleared. The older lab hit GitHub response timeouts before a normal restart; the restarted attempt succeeded.
Both official acceptance labs were closed normally.

Production deployment, signing, client activation and publication are complete. Two consecutive native Windows
Cubism 5.3.02 upgrades passed: **0.68.1-beta.2 / Build 76 → beta.5 / Build 79 → beta.7 / Build 81**.
Both upgrades used official signed releases, the public API, native download/consent and the managed restart.
Each verified all 36 framework files, preserved 32 plugin JARs and user settings, confirmed healthy startup
(30 enabled plugins, zero failures; MCP/ACP explicitly disabled in the lab), and cleared pending transactions.

See [production Beta acceptance](production-beta-acceptance.md) and its
[machine-readable evidence](production-beta-evidence.json) for exact identities, release runs and limitations.
Server configuration is complete. Product PRs #59–#64 have merged; the local branch remains
`local/w2-validation-fixes`, tracking `test/issue-50-windows-validation-fixes`.

Beta.7's selected-plugin update is blocked by its outdated permission-reason limit; Beta.9 aligns the client with
the signed directory's 2,000-code-point contract. Uncheck that option for the first Beta.7 → Beta.9 upgrade;
the corrected validator and new window take effect after restart. Older installations without the updater and signed receipt
need a new full package once. Stable migration, full signed-framework failure recovery, the NSIS installation
process, physical power loss and Linux/Proton have not been qualified by these successful Beta upgrades.

## Historical local checkpoint (before production activation)

The sections below retain earlier implementation and test evidence. Their absent-key, unpublished-release and
release-blocker statements describe that checkpoint, not the current production status above.

**Current evidence:** [Windows launch-lease/native recovery acceptance](windows-launch-lease-host-validation.md)
(2026-11-01, current worktree). This checkpoint extends `f173f64e2` with lease/ACL/launcher changes tested on the
working tree based on `bae2f3486`. The [older handoff](HANDOFF.zh-CN.md) retains prior-host history.
Primary user Cubism p40752 is left running on the older e72137 local agent (a historical prior-host record,
not the current worktree host). The separate f173 recovery instance was stopped; its STARTING_HEALTH component
transaction and suspended intent are retained.

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
  same receipt, and refuses to overwrite a different index. Full-package assembly embeds optional signed bootstrap
  documents in the ZIP and NSIS payload without adding public release assets. Installer finalization and the first
  managed ZIP launch invoke initialization; probes, existing receipts and recovery journals skip re-bootstrap.
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
- With explicit host-testing permission, the fe110e4 local runtime/full payload started Cubism 5.3.04 with
  **32 plugins, 0 failures**. In a separate 29-plugin lab baseline, two selective plugin batches downloaded through
  a temporary signed catalog/loopback transport and applied on actual managed startups: BoundingBox Warp Mirror,
  then Clip Mask Viewer, both 0.67.4 -> locally built 0.68.1. Each batch preserved the other 28 JAR hashes and five
  protected configuration/sentinel hashes. These were real plugin executions, not the earlier inert-JAR fixtures.
- Real launch checks also covered live-instance refusal without terminating that instance, damaged pending-task
  removal, and unconfirmed component README recovery through the retained helper before normal Cubism startup.
  The component probe reused the agent unchanged; it was not a full signed framework or bytecode upgrade/rollback.
- Host testing found and fixed official ProxyConfig/dp0 handling under disabled current-directory executable search,
  plus unnecessary WRITE_OWNER in bootstrap ACL preparation. See `host-batch-validation.txt` for detailed evidence,
  lab paths and strict boundaries. A subsequent full-layout bootstrap fixture signed all 36 actual files using
  a temporary key bundled only in the lab agent: real managed receipt initialization and 29-plugin startup passed.
  Original agent was restored and fixture receipt/key material removed afterward; another 29-plugin startup passed.
  Framework client activation stayed disabled for this unnumbered local build.
- No Linux/Proton execution evidence has been collected in this Windows session.

## Pre-activation release blockers (historical)

1. Provision/review release activation for the now-wired product lifecycle. The native Updates window, asynchronous
   adapter and configured receipt/downloader/coordinator construction are connected; actual published activation and
   eligible discovery are still absent. EN, zh-Hans, zh-Hant, JA and KO UI remains fail-closed for legacy discovery.
2. Provision and review the dedicated framework signing keyring. The helper now reads only framework-specific
   bundled authority, supports revocation, and rejects installation when no active key exists. It no longer borrows
   plugin-catalog keys. No production key has been provisioned; no private production key belongs in the client.
3. Deploy and qualify the reviewed framework API and protected publisher with owner approval. The implementation
   preserves the eight public release assets and serves independent signed documents/payloads from R2. Production
   endpoints, actual Cloudflare streaming behavior and incoming-object lifecycle remain unverified.
4. Qualify the wired full-package bootstrap on the actual installer/ZIP lifecycle and protected signing pipeline.
   The NSIS changes have not been compiled into a real EXE here. Private-state ownership migration and adoption of
   existing publicly installed versions still require qualification; signed fixtures are not that evidence.
5. Review cache/installation safety changes on Windows: pure NIO does not provide hard-link counts, and directory
   fsync is unavailable through FileChannel. These limitations are NOT equivalent to native handle guarantees.
   Do not assert that content hashes alone provide anti-hardlink-write safety or power-loss durability.
6. Harden recovery against hostile journal/backup/path mutation; allowlist validation and shared cross-process
   helper/health locking are present but are not a complete handle-based TOCTOU defense.
7. Qualify the durable install-next-launch workflow with actual signed releases, interrupted scheduling and
   broken-launcher recovery. The managed LAUNCHING-to-USING lease now has child-JVM/premain and local Cubism evidence;
   peer-process checks remain an additional guard. Legacy/direct entry before premain and older retained-helper
   migration remain unqualified. The production path no longer depends on a one-hour helper lifetime.
8. Finish native-window host acceptance, opt-in and retry/recovery diagnostics for the now-wired selected-plugin
   workflow. Downloads and explicit installation consent are separate; plugin and framework journals are not a global
   atomic transaction. Qualify plugin-apply failures together with framework startup health/rollback on the host.
   Actual two-plugin failure restored all 29 old JARs and prevented health confirmation; the next controlled launch
   recovered the README component. The earlier corrupt-intent startup trap (`failure-host-acceptance.txt`) is now
   addressed in `f173f64e2`: complete rollback suspends original intent, permits installed plugins, and defers health.
   Native review/cancellation and README component recovery now have current-host evidence; full signed framework
   recovery and the native download-to-upgrade chain still require qualification; see the current evidence record.
   Never replace loaded framework/Graal/SDK bytes in process or force Cubism to close.
9. Complete supported-Windows acceptance of the native UI and authenticated download-to-next-upgrade chain,
   beyond the now-observed rebuilt-agent startup health/recovery. Retain reports; label any Linux/Proton evidence.
   Broader shell execution: 123 tests, 1 failure, 1 skip. The long-control assertion also fails on the clean detached
   origin/main baseline 9767e2b61, independent of Issue #50; see `shell-baseline-validation.txt`. The complete shell
   suite is not claimed green.

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
- **Bootstrap approved/integrated:** merge `875f3c5fb` incorporates revisions through `20cf6f19`, with reviewer
  fixes to consume only the current Gradle classpath export and to wire the missing first-ZIP-launch call. Independent
  execution passes **14 Python tests**, including real Ed25519 initialization, tamper rejection, different-receipt
  collision preserving index/documents, explicit-ACL cleanup, junction sentinel/SDDL preservation and isolated
  launcher wiring. Real ZIP embedding is tested. PowerShell bootstrap checks pass **33 assertions**; configuration
  merge and installer-packaging checks pass. No actual NSIS compilation or real ZIP-to-Cubism launch is claimed.
- **Combined client regression after both integrations:** update package **356 tests, 0 failures/errors, 6 skips**;
  core-contract **31 tests, 0 failures/skips**; release/native Python **56 tests, 0 failures, 1 skip**; Node **78 pass**;
  bootstrap JAR builds. These do not replace native-window host acceptance or the complete shell suite.
- **Selected-plugin linkage integrated:** `22a8ba3c1` squashes the delegated work and lead takeover fixes. The Fast
  agent stopped on a provider usage limit; its unfinished work was checkpointed locally, not silently approved or
  rerouted to another model. The lead finished independent cancellation, stable planning failures, owned worker-side
  leases, frozen/invalidated selection, explicit framework-only fallback, final dependency/pending checks and truthful
  partial-status reporting. See `plugin-batch-validation.txt` for implementation and evidence boundaries.
- **Latest combined integration regression:** runtime update/plugin-management/window selection **483 tests,
  0 failures/errors, 6 skips**; core-contract retains its **31-test** green result; Python bootstrap/release/native
  **70 tests, 0 failures, 1 skip**; Node **78 pass**; bootstrap JAR and configuration/installer-packaging checks pass.
  UI tests use injected services; signed catalog/JAR containers are not executed as real host plugins. The full shell
  suite and full admitted-framework/native-window/repeated-framework-update host chain remain unqualified.
- **Authorized real-host checkpoint:** selective plugin download/next-start application succeeded twice, with real
  plugin loading and unchanged protected files; see `host-batch-validation.txt`. Latest selected Java regression:
  **483 tests, 0 failures/errors, 6 skips**. Python bootstrap/release/native: **71 tests, 0 failures, 1 skip**;
  standalone proxy regression and PowerShell bootstrap **33 assertions** pass, as do packaging/configuration checks.
  The synthetic managed-launch PowerShell suite now passes **188 assertions**, repeated twice after fixture
  corrections without changing production admission. Its child processes use an explicit mock probe/argument-echo
  executable, not genuine Java/Graal execution; the separate option parser uses actual Java 17. One concurrent run
  saw an atomic-shortcut sharing violation; cause/stability remain unqualified. No complete official framework
  update or native Update-button acceptance is claimed.
- **Follow-up failure acceptance:** `e72137dec` fixes incomplete plugin-backup preparation restoring over live JARs.
  Both new fault tests reproduced byte loss before the fix; selected regression now **485 tests, zero failures/errors,
  6 skips**. Real Windows ReadData denial against the new production backend preserved both seeded JARs/journal.
  New LOCAL (not CI) agent host startup and subsequent same-byte manual plugin reinstall (historical prior-host
  record): p40752, **29 plugins, zero failures**, unchanged JAR hashes. Post-fix Python: **71 tests, 1 skip**. Actual capture revealed
  the vendor trial notice still blocks an interactive editor session; startup banners precede it. Manual click on
  Continue Using is needed before further GUI acceptance. See `failure-host-acceptance.txt`; production stays disabled.
- **Suspended-intent/native recovery implementation:** `f173f64e2` adds inactive failed intent, graceful old-plugin
  startup without health confirmation, root/generation-bound consented cancellation, owned worker/view lifecycle,
  native Plugin Management entry and all six locales. Latest selected tests: **524, zero failures/errors, 6 skips**;
  core-contract **33 pass**; bootstrap build/license checks pass. A broader lifecycle class has two failures also
  reproduced on clean pre-change a5b0c2525; it is not claimed green.
- **Newest isolated host observation (partial):** all **32 plugin hashes restored**, bad active intent suspended,
  STARTING_HEALTH retained and confirmation deferred. Runtime reached ACTIVE but **30 loaded / 2 failures** due to
  MCP port collision with the deliberately preserved primary instance and ACP dependency failure. Native cancellation
  was not clicked/qualified. The secondary test process was stopped; next managed launch should recover README.
  Do not call this 32-plugin green acceptance. Exact continuation and lab fixture sources are in the handoff.
- **NSIS tooling attempt:** the official SourceForge download timed out on this machine. No real EXE compilation
  or installation is claimed. Production keys, activation and deployment remain unchanged.

## Current worktree-host checkpoint

- **Windows receipt ACL fix (verified):** `FrameworkUpdateApplier` writes, backs up and restores the installed
  receipt index through a private temp file given an explicit owner/SYSTEM/Administrators ACL before an atomic
  rename, so a host whose temp directory propagates untrusted inherited SIDs cannot make the index fail the
  `Receipt state is not private` check. Two new deterministic ACL regressions plus the six previously failing
  receipt tests pass on this Windows host: **8 tests, 0 failures/errors/skips**. The five symlink cases remain
  unexercised on this host for lack of symlink privilege and are not claimed green.
- **Coverage wiring:** `test_ci_coverage.py` compares the host-tooling runner path with `.as_posix()`, and the
  previously ungated `test_bootstrap_receipt.py` is now wired into `checkCompletedCommit` through a
  `checkBootstrapReceipt` Exec gate that receives the exported runtime test classpath via a structured env var
  (no nested Gradle build inside the gate). The coverage guard passes.
- **W2 native (partial evidence, not full acceptance):** a real Windows W2 run reached **22 PASS, 3 NOT_RUN,
  0 FAIL**; the NOT_RUN cases are the file/directory-symlink cases needing privilege and a foreign-owner case
  that cannot be assigned. Full manifest digest
  `59a2e7596b07640c587129db5199907b80f07b2fb1fd6e7c8d61c66cff7f9af6`. Exit remains INCOMPLETE_OR_FAILED, so
  this is not a complete W2 acceptance pass.
- **Current local host recovery passed with bounded scope:** Cubism 5.3.02, a local dirty 0.68.1 agent, 32 installed
  JARs and 30 enabled plugins (MCP/ACP explicitly disabled). Actual startup restored all 32 hashes after a corrupt
  two-plugin batch and suspended the intent without confirming framework health. After a normal lab-only exit, the
  next managed startup recovered the README component and cleared its journal. Real native Plugin Management
  review/default-keep/explicit-cancel passed via targeted Windows keyboard events, preserving plugin/config bytes;
  subsequent valid same-version restaging applied on another real startup. Each startup loaded 30 plugins with zero
  failures. These are component/manual-package acceptance results, not an admitted full-release update/download.
- **Lease and launcher evidence:** latest lease unit class 31/31; child-process class 8/8; built-agent premain and
  dependency-isolation probes passed. Windows PowerShell 5.1 lease gate 101 assertions and managed-launch gate
  196 assertions passed, including exact Chinese/space argv through the two-stage BAT chain. Bootstrap receipt
  Python 15/15 passed. Two real Cubism JVMs also coexisted with distinct USING leases; mutation was refused while
  they lived, and stale leases were reaped after both exited. Simultaneous exit produced one conservative release
  lock-contention warning (74), documented in the evidence. All four lab JVMs are stopped. Official BAT/JRE hashes
  were unchanged.
- **Integration remains partial:** runtime 564 tests / 5 failures / 10 skips (all five failures require unavailable
  symlink privilege); bootstrap 251 tests / zero failures / 30 skips. Core-contract 33/33 is an UP-TO-DATE prior result.
  Runtime spotless still reports 39 historical files; no whole-suite green claim. Production activation, signing
  keys and deployment are unchanged. See the linked evidence for paths, commands and remaining release blockers.
