# T040 atlas-image-shadow Dependency and Evidence Inventory

## Scope, Authorization, and State

- Scenario: `atlas-image-shadow:5303`, fixed Circle100, independent of T036; T036 stays BLOCKED.
- This round added only `validation/atlas-image-shadow-scene/`, `scripts/preview/run-atlas-image-shadow-host-validation.sh`, `scripts/preview/host-validation-020-shadow.json`, and `scripts/test/test_atlas_image_shadow_host_validation.sh`.
- It modified no Runner, kernel probe, SDK, core, settings, or T036; it prepared nothing, submitted nothing, started no host, sent no signal, and ran no external collector.
- `runnable=false` is the fixed gate; this inventory is no host acceptance.

## Fixed Inputs and Read-Only Dependencies

| Item | Entry or digest | SHA-256 |
|---|---|---|
| Circle100 source fixture | `${TURBOISM_HOST_VALIDATION_FIXTURE_5303}`, basename must be `atlas_mapping_100.cmo3` | `2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e` |
| Official Cubism JAR (build-time reference) | `${TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE}`, an explicit `Live2D_Cubism.jar`; used only for the offline build and publish check and never written into the runtime `trustedSourcePaths` | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` |
| T039 agent JAR | `${TURBOISM_ATLAS_IMAGE_SHADOW_T039_AGENT}`, delivered as `t039-shadow-agent.jar`; the real pure-JDK bridge input of this round | `88a30f4aa156e10af009532d45bb066c01840aaaae9aa986f8985038d7d85a0a` |
| T039 source | the read-only `T039ShadowAgent.java` reference named by the task; this implementation never reads it at runtime and commits no personal path | `de2b24bfb2de1eb7ec54f80f917244baf840e794e24942e894c58048837da9d1` |
| T039 REPORT | the read-only basis for the schema, eligibility, and runtime binding; this implementation never reads it at runtime and commits no personal path | `17048d7dc371539fa196f1d8c1e90cc67b912706164350d002beac1f1928cc36` |
| T039 helper class | delivered explicitly by the T039 artifact and inventory | `9aa1db0ff67c27b026d4c29fd56485bfb0641c92d1e5b31963764462c4a8caf0` |
| T038 helper class | the T039 dependency digest | `47500ced125b0d69bd42d1af3f3d55137a2e3fb785ffaca87f69678bf3635fd6` |
| T039 official class resource | the fixed 5303 profile | `ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6` |
| T039 5303 shape | the fixed target shape digest | `a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f` |
| T039 runtime binding | `sourceBinding=target-pd`; the single `trustedSourcePaths` candidate is `C:\Program Files\Live2D Cubism 5.3.03\app\lib\Live2D_Cubism.jar` inside the isolated task prefix, verified by the real JVM `ProtectionDomain` and `toRealPath()` plus the loader and hash | `T040RuntimeSourceBinding.java` `2b0e3c1b92496c4547f7ad2ae1a80771398c63f302769a041d861a07d849b12c` |
| T040 runtime binding harness | read-only verification of a same-hash shifted path, a wrong hash, multiple candidates, a non-file CodeSource, a ProtectionDomain and path mismatch, and a wrong loader; it defines and executes no official class | `T040RuntimeSourceBindingHarness.java` `72844c8329ba79f5dbcc38e949a491bc7bbedb7e6b57166b4650e4ad818367f0` |
| Runner 5303 path evidence | the Runner `cubism_win` and `cubism_rel` are pinned to 5303 and the prefix is cloned per task; the official BAT runs `cd /d "%~dp0"` and then uses the relative `app\lib\Live2D_Cubism.jar` classpath | Runner `e17ec59c1cc1400f4b0f033f5a1ef72042e0099f1514973e0d606cd8fd6cdc35`; BAT `85552a49c6633a997d4e8df41dcad6b92b4e6bcf06dc1a0c333b5e13e5ea10bf` |

The ordinary T040 compile and self-check classpath holds no official JAR, T039 source, or official class. The shell regression puts only this round's explicit T039 agent JAR (SHA `88a30f4aa156e10af009532d45bb066c01840aaaae9aa986f8985038d7d85a0a`) on a temporary pure-JDK probe classpath, confirms `public static Map<String,String> freezeCapture(String)`, and confirms that an unarmed call is refused; it defines and executes no official Cubism class.

## T039 Freeze Schema and Admission

The bridge follows the read-only T039 `createFrozenCapture` and `requireFreezeEligible` and strictly requires the complete 43-key flat string map; a missing, unknown, non-string, newline or NUL value, a wrong run or profile, or a wrong fixed state is refused:

```text
schemaVersion, runId, profile, shadowMode, state, removalStatus,
transformerRegistered, candidateReturnedCount, frozen, fullBoundsPreserved,
sampleOutcome, reason, helperPrewarmed, helperHash, t038HelperPrewarmed,
t038HelperHash,
stats.recorded, stats.dropped, stats.admitted, stats.rejected,
stats.aliased, stats.potentialTrim, stats.eventLimit,
stats.lastKx, stats.lastKy, stats.lastSourceWidth, stats.lastSourceHeight,
stats.lastStride, stats.lastFullWidth, stats.lastFullHeight, stats.lastPadding,
stats.lastSourceLength, stats.lastDestinationLength, stats.lastAliased,
stats.lastAdmitted, stats.lastPotentialTrim, stats.lastOptimizationRequested,
stats.targetEvents, stats.lateCallbacks, stats.candidateCount,
stats.candidateReturnedCount, stats.rejectionCount, stats.methodExecuted
```

The fixed 5303 scenario additionally requires `state=SHADOW_READY`, `removalStatus=REMOVED`, `transformerRegistered=false`, `candidateReturnedCount=1`, `frozen=true`, and `fullBoundsPreserved=true`. `sampleOutcome` accepts only `NO_CALLS|NO_ELIGIBLE_CALLS|POTENTIAL_TRIM|TRUNCATED`; these are capture classifications, not coverage, optimization, or correctness evidence. No payload data is written after the freeze; a late call is handled by the T039 full-bounds semantics itself and must not change the frozen snapshot.

## Implementation Summary and Path Boundary

- `T040ShadowSceneDriverAgent` is the fixed-scenario driver: strict task and fixture identity, the unique main window, the exact `Modeling -> Texture -> Edit Texture Atlas...`, the exact editor class `com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b`, and the unique OK; inside the editor it clicks the unique `Automatic layout...`, then in the host layout dialog (`com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f`, `APPLICATION_MODAL`) clicks `user specified`, writes the fixed `40`% into the text field of the scale control (`com.live2d.ui.control.a.a.j`), and applies with that dialog's unique `OK`. The host integer downsampling kernel is called only when the effective scale is at most 0.45, and this one fixed scale is the only action that makes the real OK rebuild reach the kernel; no other parameter changes, no CMO3 is saved, and nothing is exported.
- An unknown secondary window, a save prompt, a duplicate OK, an exception, or an incomplete action fails closed; no unknown control other than Save, Cancel, or OK is clicked by default. Right after the readiness gate the run samples one host window baseline (an identity set holding only the non-main windows already showing): a real 5303 host floating window is an ordinary `javax.swing.JDialog` (`com.live2d.ui.window.m`) while the driver's own menu popup is a heavy-weight popup. Only a window that appears after the baseline is a candidate, and only a modal window (`Dialog.isModal()`; the real 5303 `jp.noids.framework.e.a.f` progress window uses `setModal(true)`) blocks; a new non-modal window (popup, host palette) is recorded without blocking. After dispatching the menu action, `MenuSelectionManager.clearSelectedPath()` clears the driver's own popup. The close poll only observes and never acts, sampling until the 240-second budget: it continues only when the editor has closed and no modal window remains, and it fails closed only when the budget runs out; it writes `editor.closePollVisible`, `editor.closePollUnexpected`, `editor.unexpectedWindowCount` and `editor.unexpectedWindowClasses` (modal), `editor.nonModalWindowCount` and `editor.nonModalWindowClasses` (non-modal), `editor.closePollMillis`, `editor.closePollTimedOut`, and `baseline.windowCount` and `baseline.windowClasses`, with at most 3 allowlisted and truncated class names per category, so the blocking cause is diagnosable without leaking a title or model content.
- The order is: after OK confirm the editor closed and the action completed → T039 freeze → atomic payload creation, close, re-read, and hash verification → one canonical `COMPLETE`. Before publishing canonical the loop handles a short write, then fully verifies byte and Properties consistency after force; a truncation, write failure, zero progress, or duplicate target publishes no COMPLETE. A preceding failure attempts `FAILED` once only; a failed write leaves no COMPLETE. Whether the native exit returns is not a capture success condition: after COMPLETE the exit click is posted fire-and-forget (the host exit action may terminate the JVM directly), and a completed capture is never rewritten because the exit did not return. When the host closes a document with unsaved changes it asks modally through `UUOption` plus `JOptionPane` (the button texts come from the Look-and-Feel `OptionPane.yesButtonText`, `noButtonText`, and `cancelButtonText`), and the scene may click only the single no-button inside the single option pane (the expected label is likewise read from the Look-and-Feel, never hardcoded and never logged as localized text); when it cannot decide it clicks nothing and records the reason. Within the bounded 20-second window it records `nativeExit.clickMillis`, `exit.windowSamples`, `exit.windowCount`, `exit.windowClasses`, `exit.promptSeen`, `exit.promptAnswer`, `exit.promptButtonCount`, `exit.promptClasses`, `exit.promptMillis`, `exit.nonDaemonThreadCount`, and `exit.nonDaemonThreadNames` as diagnostic evidence; the lifecycle and containment belong to the queue gate.
- `FixedEdt` serves only this scenario; a pure read and an action use `invokeLater`, with a 5-second bounded wait for the queued startup budget and another for the query budget of a started action. The state is `QUEUED -> STARTED -> COMPLETED`, or `TIMED_OUT` after a lost queued race; a queued callback that times out or is interrupted never executes when it arrives late, and a started action is never claimed revocable, but its long duration is no longer misjudged as a failure. The started barrier of the modal editor menu `doClick`, the synchronous modal OK apply, and the action completion latch are separate.
- The driver starts in premain and passes a readiness gate before driving the UI: three consecutive EDT round trips must each complete within 1000 ms, and a slow round resets the count and increments `readiness.slowRounds`. `OK` only waits for the press, after which the run polls for editor close within the overall run deadline (a queued timeout is only recorded and continues) and waits for the modal action to end; the synchronous texture atlas rebuild of the real 5303 no longer triggers a false `EDT_TIMEOUT`.
- The startup main window uses at most a 240-second startup budget; a queued pure read lookup that times out may continue waiting within the overall deadline, and a started lookup is never retried. The default Runner result timeout of 900 seconds is unchanged.
- `driver-stage.properties` is a bounded replaceable snapshot that records the stage, epoch and elapsed time, the EDT counters and state, action completion, freeze, payload, canonical, and native-exit booleans; it writes real newlines and never a title, model content, or unbounded event history.
- The task home and output are located only from the explicit `turboism.home` and the runId and taskId; the driver safely creates the fixed `state/atlas-image-shadow` parent in a clean home and claims `state/atlas-image-shadow/<taskId>` atomically with `CREATE_NEW`, refusing a duplicate, a lost concurrent claim, a symlink, and an out-of-bounds path. A duplicate payload or result is refused, and the bundle is located uniquely by worktree id and artifact hash with no `latest`. The T039 runtime candidate uses no personal or build-machine path but the fixed Windows path inside each isolated Runner 5303 task prefix; the wrapper passes only `sourceBinding=target-pd` and one candidate, and the real JVM performs the canonical, ProtectionDomain, loader, and hash verification.

## Dependencies, Build, and Verification Commands

The only dependencies are JDK 17 AWT, Swing, and the standard library; zero network, zero secret, and zero external service. Every path comes from the ignored `.env` or an explicit environment variable, and no personal worktree path is written into a production script, wrapper, manifest, or inventory.

```bash
TURBOISM_HOST_VALIDATION_FIXTURE_5303="${TURBOISM_HOST_VALIDATION_FIXTURE_5303}" \
  bash validation/atlas-image-shadow-scene/build-and-selfcheck.sh

TURBOISM_HOST_VALIDATION_FIXTURE_5303="${TURBOISM_HOST_VALIDATION_FIXTURE_5303}" \
TURBOISM_ATLAS_IMAGE_SHADOW_T039_AGENT="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_AGENT}" \
TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE}" \
  bash scripts/test/test_atlas_image_shadow_host_validation.sh
```

The builder uses `javac --release 17 -proc:none -implicit:none -Xlint:all -Werror` and never runs `--publish` or creates a production bundle. Only after manager review may `build-and-selfcheck.sh --publish` run with an explicit production agent, T039 agent, build-time official code source, loader class, and helper hashes; the published manifest records only the runtime binding and path fields and never forwards a build-machine code-source path to the task JVM, and it stays blocked.

## Current Offline Evidence

- `bash -n`: the builder, the wrapper, and the test pass; `python3 -m json.tool scripts/preview/host-validation-020-shadow.json` passes.
- Latest offline build: `T040_SHADOW_SCENE_SELFCHECK PASS checks=78 hostExecuted=false`; driver artifact SHA `cd0df055e92003798f9fca2c4aeebd50840e9f21eb6d43752cac8dada915dc47` at the relative output path `build/atlas-image-shadow-scene/compile.xPoRtn/atlas-image-shadow-scene-driver.jar`.
- The shell regression passes (`PYTHONOPTIMIZE=1`): static contract, the pure-JDK public-static bridge of the real T039 agent JAR with SHA `88a30f4aa156e10af009532d45bb066c01840aaaae9aa986f8985038d7d85a0a` (unarmed rejected), the Runner dry-run task-local prefix and fixture expansion with no build-host golden path, unknown-key and PreparedStore invalid-input refusal, and the complete capture, deletion of only the synthetic source, bundle, agent, and fixture, and snapshot replay with hash; it prints `T040_PREPARED_STORE_REPLAY ... hostLaunched=false` and `ATLAS_IMAGE_SHADOW_HOST_VALIDATION_OFFLINE_TEST PASS hostLaunched=false`.
- The two expected `ATLAS_IMAGE_SHADOW_DRIVER_BLOCKED` lines of the self-check come from the save-prompt and duplicate-OK negative controls; the clean-home, concurrency, duplicate, symlink, and out-of-bounds claim negatives plus the canonical short-write, truncation, write-failure, and zero-progress negatives all pass inside the same `checks=78` offline self-check, which is no host execution. The manifest stays `runnable=false`, and this round prepared nothing, submitted nothing, and started no host.

## Delivered File SHA-256 of This Round

```text
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/FixedEdt.java                         eaa61f1b9d73298cf9ef1587d8fd4a67f01c9d4bde16db9da97cfa68fca8a7ec
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/ShadowPayloadStore.java               df5ba84928bb9303d18e1ed7785f6f31f7ce6b1d27db27f534c68e2e1e6efb1b
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/ShadowSceneContract.java              c5ca6cb8c6d4caaae3c272001f9ec356369d0a9b21b361349098378b32469cf6
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/StageEvidence.java                    5b0b29482d884bae02ba3ba4089ca309839c19e717dd363d7011bd6db2b60507
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/T039FreezeBridge.java                  f701b8e56ce3fc3569d8e36225243f0bc095904dbb39f9593e519a397330bf42
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/T040ShadowSceneDriverAgent.java        1f294e1e68c8874d61732a250634e196647ff4f9e50a79452df6cc06001e3939
validation/atlas-image-shadow-scene/selfcheck/com/live2d/cubism/doc/modeling/ui/atlasEditor/f$b.java                    3d9f051958d3d0ed8cc793464492367bc657fdc76ab492b7027508f20212ad6b
validation/atlas-image-shadow-scene/selfcheck/dev/turboism/validation/atlasimage/shadow/T040SelfCheck.java              e6b8b74266fb913faf5b73a3b2c92a20b7a80016221625a5cf19412873603e94
validation/atlas-image-shadow-scene/build-and-selfcheck.sh                                                             8a11898dec00d3ab9d205983b65597e0cbb706bddc8e3dcd0ece938fe65ee578
validation/atlas-image-shadow-scene/README.md                                                                          e799d96be2a98f7706a7bae8f7a21eec2c7f21c8ee92c66240e5a6676beacc0f
scripts/preview/run-atlas-image-shadow-host-validation.sh                                                              efaafd63b5edbc5bd30711cfa468bd026e15ffddee5351103a1fa7645a023853
scripts/preview/host-validation-020-shadow.json                                                                        37406dcbb9ee31edae731c4b3064c822ee40c1504638dd57b9fd6785204da986
scripts/test/test_atlas_image_shadow_host_validation.sh                                                                 1e13d0b7df83a42da3adea3419a519799b649d403176b46c2a3703d63745c3c5
```

## Responsibility and Remaining Gates

- The driver owns the fixed UI actions, the T039 freeze eligibility, the payload content and hash, the secure output and run claim, its own single canonical publication, and the fail-closed behaviour on failure.
- The Runner and queue manager own prepare and submit, the host lifecycle, the native normal exit, containment, the result timeout, cleanup, and the final task verdict; the current wrapper offers no hook, client, or collector channel; readiness is limited to the fixed `--ready-marker 'TURBOISM_DEFERRED_GL_ERROR_CHECK deferred=ACTIVE'` (when the Runner deferred-GL gate marker is empty it samples the runtime log once immediately, and the wait predicate uses the same literal as the gate predicate, the T029-READY correction). A missing or INACTIVE marker still fails safely after a bounded timeout, which is no absolute guarantee under every mode or log rotation. Only the queue seeing the host's own `-- successfully exited pid:N --` marker (`com.live2d.cubism.appCtrlImpl.bn.c()`) decides a normal exit; the exit evidence on the driver side never replaces it.
- The main agent must still review the real production agent, the per-item hash of the T039 parameters and dependencies, the Runner-expanded `{TASK_ID}-atlas_mapping_100.cmo3`, the real UI OK text and ownership, the payload post-check, and the source and copy invariance of the fixture, and the manager must lift `runnable=false` before host admission. This round performed no host acceptance.

## Primary Integration Artifact (T040 First-Round Candidate, Replacing the Historical Artifact Summary Above)

- production agent SHA-256: `224d6ee29597dcb520aa294ea5e569dcc8cb4b95831e179e00dd33b7522e3261`.
- T039 self-contained agent SHA-256: `29323bf536208a76b2a1c7ff532e5b34ac98742b4cd69c8d51cd03687c953a3a`; its private ASM 9.7.1 holds 38 classes with no external Class-Path.
- scene driver SHA-256: `367b0076c912017bd7552862080c20917e87d5629a6f34020571b9cf332f6996`.
- The T039 and T038 helper class SHAs match the table above; the fixture, official JAR, class, and shape identities are unchanged.
- Kernel standalone build evidence: `/tmp/atlas-image-kernel-probe.GSaHv6`; scene build: `build/atlas-image-shadow-scene/compile.JrOqlU`.
- Delivery manifest: `build/preview/optimize-atlas-processing-cache-parallel/atlas-image-shadow/bundle.manifest`.
- The expected loader is `jdk.internal.loader.ClassLoaders$AppClassLoader`; this is the admission condition that rejects a mismatch this round, not a loader class name already proven by an earlier observation.
- This record pins only this round's inputs and means neither a prepared state nor a host acceptance pass.

## T040 First-Round Host Failure and Launch-Property Contract Fix (Replacing the Driver and Contract Summary Above)

- The first-round host job `f37232b8-3aa5-4cf2-8c5c-c40d71fd7b83` (seq 69, prepared `2e267cc65d79c8997f405736cffe0e844fe776a66cff0e447bcdf23966629cd1`) **failed**: the first line of `cubism-console.txt` had no `t039.codeSource` in the plan, the driver premain threw `IllegalArgumentException`, and the evidence directory showed `ATLAS_IMAGE_SHADOW_DRIVER_BLOCKED IllegalArgumentException`; the driver never started, `state/atlas-image-shadow/result.txt` did not exist, and the exit code was 1. The host JAR identity check passed, and the fixture and the golden and clone hashes were unchanged.
- Root cause: after the T039 source binding change the wrapper passes only `sourceBinding=target-pd` plus a single `trustedSourcePaths` (the static contract forbids `codeSource` from appearing), but `T040ShadowSceneDriverAgent` still required `t039.codeSource` unconditionally. The offline self-check used only `DriverConfig.forSelfCheck(...)` and never ran `fromSystemProperties()`, so `checks=78` stayed green while missing it.
- Fix: the driver startup gate now verifies `sourceBinding=target-pd` plus a single absolute `trustedSourcePaths` candidate ending in `Live2D_Cubism.jar`; the self-check gained positive and negative shape cases (`checks=88`); the shell regression gained an end-to-end startup gate that parses every `jvmOption.*` of the Runner `--dry-run`, asserts that the real `fromSystemProperties()` accepts it, and asserts that removing `t039.sourceBinding` or `t039.trustedSourcePaths` is refused.
- Regression evidence: under the same plan the pre-fix driver `367b0076…` reported `missing turboism.validation.t039.codeSource`, and the fixed driver (build `compile.vDgVdR`, SHA `96b4f835bd80d7074e08505d04e3333ff7ded0ea00dab3b5f85530e5f4b88470`) passed; a driver JAR is not guaranteed to be byte-identical across builds (each build produces a different SHA), and the bundle manifest records the hash of the real file at publish time.
- Post-fix file SHA-256 (replacing the matching rows above): `ShadowSceneContract.java f72aa1419263b4ecf020a162e32ed3ca96cfbf1185f269afb8fb9dea04637566`; `T040ShadowSceneDriverAgent.java 4ea62718c2ce2cbccdcf88b9516c300c868dbce234906201966e67d0e7df4a2c`; `T040SelfCheck.java 6ae7c2919495e2a541738e62c3d10f42dcb2e31cca7b80bb31da1beaeba7a291`; `scripts/test/test_atlas_image_shadow_host_validation.sh 9aa1ec6efb8f782f5b8bf404df52d1872a70fe7b2b83dab811f1f2a5f737f10c`; `README.md` updated with this note.
- The published bundle manifest (driver `367b0076…`) is stale and must be republished before prepare or submit; this round only rebuilt and re-ran the offline regression, with no publish, no prepare, no submit, and no host start.

## T040-S4 Scale Allowlist, T039 Window Widening, and Reverse Threshold Control (This Round)

- Change: the layout scale used to be hardcoded to `"40"` (`ShadowSceneContract.LAYOUT_SCALE_PERCENT`). The scale is now the only per-job parameter of this scenario: the wrapper selects it strictly from the `{runLabel}` suffix (no suffix or `-scale40` = 40, `-scale60` = 60, any other `-scale<number>` fails closed) and passes it through `-Dturboism.validation.atlasImageShadow.layoutScalePercent`; `DriverConfig.fromSystemProperties()` re-checks it against the two-value allowlist (`40` and `60`) at the startup gate and refuses anything else, and `LayoutApplyDispatch` accepts only the scale from the config and reads it back after writing the text field. Because the same driver, T039, and production agent bytes can run both sides, the reverse control introduced no code fork.
- Change: the wrapper `-Dturboism.validation.t039.maxEvents` widened from `16` to `64` (the driver limit is 64 and the T039 helper limit is 128), which grows the prefix evidence of a 40% run from 16 to 64.
- Fix: `scripts/preview/host-validation-020-shadow.json` had been re-serialized with compact separators by the previous round (`"runnable":false` without a space), which made the offline test `grep -q '"runnable": false'` fail silently under `set -e` (only rc=1 with no error output, which looked like a build failure). It was restored to the format of the sibling `host-validation-020.json` (2-space indent, `": "`) and its description was updated to say the scale is optional; the JSON semantics are unchanged, and prepare still flips it to true temporarily and restores false immediately afterwards.
- Offline evidence: `T040_SHADOW_SCENE_SELFCHECK PASS checks=134 hostExecuted=false` (+10: the allowlist positive, negative, and property-path cases plus the complete 60% driver flow); `ATLAS_IMAGE_SHADOW_HOST_VALIDATION_OFFLINE_TEST PASS hostLaunched=false` (three new wrapper dry-run assertions for the default 40, `-scale60`, and the `-scale55` fail-closed case, plus the rejection of `layoutScalePercent=55` in the startup property contract).
- Host run A (40%, window 64): job seq 114, `queue-6b482897236f4d939bc392fa84e51c76`, prepared `37d297b28078b8ebff52e49ae1857416d9c0de4263b79dddbeaa56c9fe8fae8f`, `state=succeeded`, `validationStatus=PASS`, `normalExit=true`, `identityVerified=true`, `fixtureUnchanged=true`; `freeze.sampleOutcome=TRUNCATED`, `eventLimit=64`, `recorded=64`, `admitted=64`, `potentialTrim=64`, `dropped=36` (100 calls in all); `layout.scaleText=40`, `layout.dialogSeen=true`, `layout.closed=true`, `runElapsedMillis=109059`.
- Host run B (60%, reverse control): job seq 116, `queue-53c2672283984acf91e34dd1c5c90269`, prepared `5d424a54a0a62e5257ce36d411810c70cb53ec068ffa5164c6973a0d0acd78be`, every queue item PASS; `freeze.sampleOutcome=NO_CALLS`, `recorded=0`, `admitted=0`, and the kernel was called **0 times**; `layout.scaleText=60`, `layout.dialogSeen=true`, `layout.closed=true`, `runElapsedMillis=93940`.
- Conclusion and limits: 40% produced 100 real calls (all 64 prefix entries `admitted+potentialTrim` with 2×2 parameters, stride=104, destLen=10816), and 60% produced 0 calls, which together with the 1:1 job 8 confirms the threshold from both sides. `TRUNCATED` is still a prefix classification rather than the full hit rate; SC-01, SC-02, and SC-04a are still incomplete, and the `runElapsedMillis` difference is no gain.
- Bundle published this round: `build/preview/optimize-atlas-processing-cache-parallel/atlas-image-shadow/bundle.manifest` (driver `2b5c4f161fd7cff6b6100151027ef1322ebdfab726c8f6237dd24f629058175a`, reusing the T039 agent `29323bf5…` and the production agent `224d6ee2…`; `runnable=false`).
- File SHA-256 changed this round (replacing the table and every earlier patch row; the README also carries this note):

```text
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/ShadowSceneContract.java      03e8d2f6ad48d92718e689686c676ff0f6a765c09a92d9efd6037cf8768049fa
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/T040ShadowSceneDriverAgent.java 6d5a66cbe38b5e11a7aa43b458c1fa8b376b66c5cebea139dfa8d798b43e9f90
validation/atlas-image-shadow-scene/selfcheck/dev/turboism/validation/atlasimage/shadow/T040SelfCheck.java          e27c763dfb9d68623ca5629dd073e3c8a430d63db1c249458ef739414d50f8bc
validation/atlas-image-shadow-scene/README.md                                                                     79cfc60cd023ce21a4f1ab6b37b6ed0d93b714b867a4298fd69779e7c6831d67
scripts/preview/run-atlas-image-shadow-host-validation.sh                                                         8841eca0b4f7104d9c30db9437f7757f345683da9813838b631a7eba8bcd8be3
scripts/preview/host-validation-020-shadow.json                                                                    c844decceeb706ef483c120c886e2d23f51a8dd7cf8fdf18cd0e4754b09064bd
scripts/test/test_atlas_image_shadow_host_validation.sh                                                             b8a9929ef93512a2dfa01207d4e7817810875bf5304db0ae4c089cbe6e1957c4
```

- Unchanged: the fixture, the official JAR, class, and shape, the T039 and T038 helper hashes, and the loader class; the run still starts no collector, changes no Runner or production runtime, and `fixtureUnchanged` was true in all four runs.
