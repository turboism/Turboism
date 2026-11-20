# Windows Launch Lease and Native Recovery Acceptance: 2026-11-01

This acceptance used the current Windows workspace and continued only `local/w2-validation-fixes` and `test/issue-50-windows-validation-fixes`, without merging into main. **A passing local recovery chain does not mean production self-update is ready.**

## Build Under Test and Isolated Environment

- The source under test is based on `bae2f34863d92d0f52804876f8f1ccdb0b7e1bdb` and carried this round's uncommitted changes at acceptance time.
- Build: `gradlew.bat stageInstallerPayload -PinstallerVersion=0.68.1 -PturboismRelease=true --console=plain`. The build and the agent licence check passed; that argument selects the package format and grants no CI identity.
- Real packaged identity: `version=0.68.1`, `channel=stable`, `buildKind=local`, `dirty=true`, `buildNumber=`.
- agent SHA256: `3be137ab0cd5df1ff6b973938328e8b70e77eaca8e01caf9de973d9b941e8143`.
- The agent holds no `framework-update-client.json` or `framework-update-trusted-keys.json` activation or trust resource.
- Cubism: `F:\Live2D\Live2D Cubism 5.3.02` with the bundled Java `17.0.3.1`, no model open.
- Experiment home (repository-relative): `build/native/issue50-host/managed-recovery-flash-20261101-092216/home`. It is marked by the existing `LabHomeGuard` and confined to the repository experiment directory.
- 32 real plugin JARs; MCP and ACP are explicitly disabled in the experiment configuration to avoid port and dependency conflicts. So **30 loaded / 0 failures means 30 enabled plugins, not an acceptance of all 32 enabled**.
- Framework auto-check and plugin auto-update are off; the configuration baseline was taken after these experiment settings.
- Logs, JSON, and screenshots are in `build/native/issue50-validation/managed-recovery-flash-20261101-092216/`. Git ignores that directory, and copying the repository does not carry the evidence.

## Real Cubism Recovery and Native Confirmation

1. The existing `HostRecoveryFixture seed` staged the real 0.68.1 JARs of Clip Mask Viewer and Command Palette and deliberately corrupted the last staged package; `HostRollbackFixture` built a README-only component transaction and kept a separate helper copy. The component fixture uses a temporary signing key inside its own JVM only, reuses the agent unchanged, and injects no client trust resource.
2. First controlled launch: launcher **40700**, Cubism JVM **37020**. The real startup restored the original hashes of all 32 plugin JARs and deactivated `pending.json` into `failed-pending.json`; 30 enabled plugins loaded with 0 failures. The framework log kept `STARTING_HEALTH` and explicitly printed `UPDATE_STARTUP_NOT_CONFIRMED: PLUGIN_PENDING_ROLLED_BACK`. premain turned that launch's `LAUNCHING` into a `USING` bound to the real JVM PID and start time.
3. While the first JVM was alive, the separate helper `--recover` really returned **75/BUSY**, the log bytes were unchanged, and the JVM stayed alive. `HostRecoveryFixture blocked` also confirmed that the API refuses to cancel a plugin pending task while the framework recovery log exists. That is API evidence and does not claim the native button block was verified.
4. Only this round's model-free experiment instance received a normal close request, which confirmed that both the JVM and the launcher exited, with no force kill.
5. Second controlled launch: launcher **35264**, JVM **25632**. The retained helper printed `RECOVERED: rolled back interrupted update from state STARTING_HEALTH` before Cubism started. The README was restored to its original hash, the framework log was cleared, the lease of the old JVM was reclaimed, and the 32 plugins, the agent, and the experiment configuration hashes stayed identical. The failed plugin task stayed deactivated; the real runtime printed `PLUGIN_PENDING_REVIEW_REQUIRED` and again reported 30 loaded / 0 failures.
6. Inside the real Cubism the run opened Turboism, then manage plugins, then "pending changes...". It operated the real Swing controls with Windows directed keyboard events bound to the experiment PID and window and checked each step with screenshots; it injected no service, called no cancel fixture, and modified no in-process bytecode. Foreground mouse operation was not executed because of Windows focus limits, and those attempts are not counted as mouse acceptance. The dialog title and owner were verified, and it listed exactly the two failed tasks with exactly the two options "cancel the listed changes" and "keep pending".
7. "Keep pending" is selected by default. Activating the default button kept the original SHA256 of the failed task at `85b701b905bfdc7a9189ce286b09872deff200ab4a7ab87cce20be48c810bc34`. The native window showed "plugin pending changes kept".
8. After closing and reopening plugin management and opening the same confirmation window again, the run explicitly moved focus to "cancel the listed changes" and activated it. The failed task and both exclusive staged packages were cleaned up; no active plugin task remained; all 32 plugins, the configuration, the agent, and the README kept their expected original bytes. This step is real native control confirmation, not a substitute for a standalone API call.
9. After the second blank experiment instance closed normally, the existing `restage` resubmitted the two intact same-version plugins. Third controlled launch: launcher **38120**, JVM **35328**; the real plugin apply succeeded with 30 loaded / 0 failures. The installer normalized the two module file names into the file names of the matching plugin IDs, the content hashes of the 32 plugins stayed consistent, and no duplicate old JAR remained. Neither framework nor plugin active or failed tasks existed. This is a same-version local reinstall retry, not download or official version upgrade evidence.
10. With the third JVM still running, a fourth instance started in the same home with no pending update: launcher **25000**, JVM **41040**. Two real JVMs coexisted, each with its own `USING` carrying a different token, PID, and start time, and each with 30 loaded / 0 failures. The separate helper `--recover` still returned **75/BUSY**. An ordinary multi-instance launch was not wrongly blocked.
11. After confirming that neither window held a model, both received a normal close request, which confirmed that both JVMs and both launchers exited. Because they exited almost simultaneously, one release hit a transaction lock race and returned **74** with a conservative cleanup warning; nothing was force-killed and no lock was relaxed. A normal `--recover` then returned **10/NOTHING**, reclaimed the `USING` of both dead processes, and left the lease directory empty. In the end every experiment Cubism of this round had stopped, and the recovery, active, and failed tasks were all empty.

Key evidence files:

- `05-first-launch-verification.json`, `06-live-helper-refusal.log`, `13-framework-cancel-blocked.log`
- `16-second-launch-verification.json`, `25-native-review-44239744.png`
- `27-native-keep-result.json`, `43-cancel-focused-44305280.png`, `46-native-cancel-verification.json`
- `48-valid-restage.log`, `49-third-launch.stdout.log`, `50-valid-retry-verification.json`
- `51-concurrent-launch.stdout.log`, `52-two-live-leases.json`, `52-two-live-helper-refusal.log`
- `53-after-exit-reap.log`, `final-verification.json`, `source-sha256.json`

## Localized Path and Script Regression

The earlier red result on a localized path was a real defect. A minimal comparison proved that CMD parses a BAT with the **output code page of the console**, and that the `Console.OutputEncoding` cache returns a stale value after `chcp`. The launcher now reads the live `GetConsoleOutputCP()`, writes the temporary BAT in that encoding, and clearly separates the read encoding of the original official ANSI BAT from that of the intermediate BAT it generates. When the Java override path contains a space, the `%JAVA_EXE%` call in the temporary copy is quoted as well. The official BAT is never edited.

`cn-diag-v2-*` keeps three independent hidden-console comparisons for CP936, CP65001, and input 936 with output 65001, including the two-stage chaining from the Java override to the managed options. The native argv is recorded as UTF-16 hex inside an explicit UTF-8 file and compared argument by argument; the old ANSI variant reproduces the failure and the current variant passes. A garbled console display is never used as a substitute for argv correctness.

| Check | Actual result |
| --- | --- |
| PowerShell 5.1 `test-launch-lease.ps1` | 101 assertions PASS, including both the localized and the spaced two-stage chaining and an unchanged official BAT hash |
| `test-managed-launch.ps1` | 196 assertions PASS, also under output code page 65001 |
| `test-bat-integration.ps1` and proxy | 89 assertions PASS / PASS, both isolated fixtures |
| Bootstrap receipt Python | 15 tests PASS, and the final log holds no `UnicodeDecodeError` |
| CI coverage | `python -X utf8 scripts/test/test_ci_coverage.py` PASS; a direct call under the default GBK fails to decode |
| Local installer staging and agent licences | PASS |
| `git diff --check` | PASS |

The final script logs are `lease-final-gate.log`, `managed-launch-final-gate.log`, `bootstrap-receipt-final-gate.log`, `bat-integration-final-gate.log`, and `proxy-final-gate.log`. A mock Java or host inside a script is no real JVM evidence; the real host evidence is in the previous section.

## Java and Environment Limits

- Integrated run: runtime **564 tests / 5 failures / 0 errors / 10 skips**; all 5 failed in `Files.createSymbolicLink` because of insufficient local permission (3 in receipt, 2 in downloader). The whole group did not pass.
- After the late BUSY regression added last was re-run separately, `FrameworkUpdateLaunchLeaseTest` reported **31/31**; the real child-process `FrameworkUpdateLaunchLeaseProcessTest` reported **8/8** in the integrated run.
- Bootstrap **251 tests / 0 failures / 30 skips**; the premain probe of the real packaged agent and the dependency isolation probe both passed.
- Core-contract kept its existing **33/33** XML; the integrated log showed `UP-TO-DATE`, which is not claimed as a re-run here.
- The new receipt ACL regression plus the originally failing cases totalled **8/8**; W2 native stayed at **22 PASS / 3 NOT_RUN / 0 FAIL**, which is not a complete W2 pass. The missing symlink permission and the coverage that needs a foreign owner were never forged into a pass.
- The Bootstrap format check passed; the runtime format check still fails on **39 existing files**, which this round did not reformat in bulk.

Java raw logs and XML: `flash-integrated-java.log`, `flash-integrated-evidence/`, `flash-final-lease-format.log`; the real premain evidence is in `build/worktree/macho-hyena/bootstrap/launch-lease-premain/`.

## Boundaries Not Yet Covered

There is no official CI-released complete framework download, signature apply, or two consecutive version upgrades, and no production service deployment, production signing, public key configuration, or activation. A README-only transaction proves no framework, SDK, or Graal bytecode upgrade. The plugin and framework logs are not one global atomic transaction. An old retained helper that does not support the new launch token protocol still refuses to start and asks for a complete package recovery, and no automatic migration exists. A legacy or direct launch still has a window before premain registers; the managed launch result of this round cannot be generalized into an atomic guarantee for every entry point. A physical power loss, complete Windows handle and TOCTOU protection, the NSIS EXE installation, and Linux or Proton still have no evidence from this round.

This round restored no DeepSeek V4 Pro agent; the existing Flash agent went idle after it finished the last source fix.
