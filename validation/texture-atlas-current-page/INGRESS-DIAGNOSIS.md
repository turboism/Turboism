# Post-q host admission diagnosis — in progress

User renewed real-host authorization after all four UI100 attempts failed. Scope remains Circle100/Geometry100 new-only serial/parallel. Stage execution: diagnose first with one Circle100 serial task; do not submit the remaining matrix until admission/layout/safe cleanup PASS. Use unified manager, hard timeout1800; no native run, other sizes, direct launch, worker changes or unreviewed hook.

## Evidence and ranked hypotheses

1. New production runtime fails to register ingress due to admission/init failure.
2. Probe expects an obsolete ingress key/type.
3. Registration timing or plugin initialization failure.

Archived Circle100 serial `evidence/turboism.log` shows `Host adapter entered FAILED: CONNECTION_FAILED` and `Cubism host admission failed before plugin loading` at PreviewRuntime.java:410. Thus `No production ingress` is secondary, before any planner invocation. Underlying HostSession exception is swallowed into the generic diagnostic. No basis yet to blame q or layout logic.

Offline static verification of the exact official 5.3.03 JAR against current editor-model record returned `allVerified=true`, including the newly added q getter. Temporary Java verifier `/tmp/AtlasVerifyRecord.java` compiles under `dev.turboism.mapping.verification`, artifacts under `build/atlas-ingress-diagnosis`. Official source was read only.

## Targeted observation (test-only, not yet host-run)

Added `HostAdmissionDiagnostic.java` to the existing fixed auxiliary probe. It observes caught Throwable/Exception/RuntimeException handlers in production HostSession through ASM, duplicates the exception for stack recording and preserves the original stack/handler decision. No production source modification; no host launch inside probe; only task-scoped `host-admission-exceptions.txt`. Added source to package dependency inventory; called before probe wait. This is diagnostic instrumentation, not a new managed lifecycle hook.

`HostAdmissionDiagnosticTest.java`: offline sentinel catch preserves exception identity and normal return and checks recorded cause. Actual HostSession bytecode verification initially hit a test-loader sealed-interface error, not a production verification result; adjusted test loader to load the host package together. **Adjusted test still needs running.** AtlasQueueProbe 50 assertions must rerun after this package change. Diagnostic probe was packaged once; production Agent/plugin unchanged from failed matrix. Do not claim fixed/validated.

## Current pause / next actions

Another user's managed job `4acfbf9d-7dce-41f0-95c8-b8f897598115` (`native-memory-scope-baseline-20260908-b1`) was observed running. Do not interfere or build/test during its performance measurement. Status was observed in the same shell that unfortunately continued into the small probe compilation; no further builds/tests after identifying this. Resume only when host idle (and avoid other queued performance work).

1. Read-only manager status, wait for idle. Never cancel the unrelated job.
2. Run adjusted offline diagnostic test and AtlasQueueProbeTest, diagnostics/diff check. Fix instrumentation issues offline first.
3. Only after gates PASS, `prepare atlas:5303@ui-circle-100-new --run-label atlas100-admission-diagnostic-v1` with `TURBOISM_ENV_FILE=/opt/dev/projects/turboism/.env`; inspect exact prepared hashes/options; submit once with a fixed request key `atlas100-admission-diagnostic-v1-ui-circle-100-new` and timeout1800. **No such prepare/submit has occurred yet.** Record returned IDs.
4. Wait terminal; read diagnostic exception evidence and normal acceptance. If failure diagnose before any further submission, no blind repeat. If fixed later, remove temporary observation for final performance acceptance.
5. Keep task26 in_progress and blocker27 pending until the cause is actually resolved. Old failed results and earlier timing tables remain immutable history.

## User-authorized unchanged-artifact retry supersedes diagnostic launch

User explicitly requested retry again and reported another session had been modifying things. This is user-provided context, not yet proven root cause. Submitted **one** Circle100 serial retry using the original immutable prepared snapshot `d64f10e3d99c402030d9604ba0ab30da93d9acad0f398b79e86515a8e042c438`, not the unvalidated diagnostic probe. All production/fixture/probe hashes therefore match the first failed Circle100 serial attempt. No build or prepare occurred during the other performance job.

- Job: `4365ee21-d185-41d3-bbe0-6b73be616b4c`
- Request key: `atlas100-retry-after-external-v1-ui-circle-100-new`
- Hard timeout: 1800 seconds.
- Submit evidence: `build/atlas-ui-100-q/ui-circle-100-new.retry-after-external.submit.json`.
- Submitted queued behind other job `4acfbf9d-7dce-41f0-95c8-b8f897598115`; no interference.

**Do not launch the previously planned diagnostic-v1 task while this retry is pending.** Track this job via manager only; if full admission/layout/cleanup PASS, continue the remaining three UI100 new-only cases under the renewed authorization, with new fixed request keys and existing immutable snapshots. If failed, report evidence and return to diagnosis rather than blind retry. No raw timing result until full validation, no replacing original failed history. Diagnostic source remains offline/unvalidated and is not in this retry snapshot.

## Unchanged-artifact retry failed (notified)

Job `4365ee21-d185-41d3-bbe0-6b73be616b4c` terminal failed again after queued predecessor ended. Production log again reports CONNECTION_FAILED before plugin loading; driver No production ingress, no layout marker or validation.json. No duration/ratio. Safe original-bound-cgroup inode49132, cgroup.kill+populated=0, kernel errors empty; fixture/official/staged hashes match. Raw evidence `build/atlas-ui-100-q/ui-circle-100-new.retry-after-external.terminal.json`. Host observed idle. This does not prove the other session caused the earlier failures. Remaining three retries are NOT submitted. Return to offline diagnostic work, not another unchanged-artifact run. Failure notification sent.

## Diagnostic single case submitted — do not submit again

Host observed idle with no pending jobs. Adjusted HostAdmissionDiagnosticTest PASS (exception identity, return, captured cause, actual HostSession bytecode verification using same-loader sealed family); AtlasQueueProbeTest 50 assertions PASS. Packaged source/dependency hashes match, diagnostic syntax and diff checks pass. No new external dependencies, hooks or production behavior changes. Prepared snapshot options checked for Circle100/new/serial/UI, no hooks; artifact bytes hashed before submission.

- Prepared: `998d9ff62e3770bf85b609b53b2b9833e340d129bbba775cbf58aa027a3e57fa`
- Job: `857014b1-090e-457d-9907-9632c1c39a7e`
- Request: `atlas100-admission-diagnostic-v1-ui-circle-100-new`, hard timeout1800.
- Probe SHA-256: `b13c37aff87a15bf5af7621b0d3dfab4f3735e719426d2f260553e62019ae5b1`.
- Production Agent/plugin/source fixture unchanged from original failed case.
- Fixed inputs and submit result: `build/atlas-ingress-diagnosis/diagnostic-inputs.json`, `circle100.submit.json`.

Now only read status/evidence for this job. No builds, reprepare, resubmit, new unchanged runs or remaining three-case matrix. Read exact taskDir/turboism-home/state/atlas-validation/host-admission-exceptions.txt for the swallowed root exception, plus authoritative terminal cleanup/hashes. Diagnostic attempt is not final performance acceptance. On failure diagnose cause offline before further execution; on unexpected success still inspect evidence and remove temporary observation before final performance measurement. Existing heartbeat must read this latest section to avoid duplicate submission.

## Diagnostic v1 terminal FAIL — observation incomplete

Job `857014b1-090e-457d-9907-9632c1c39a7e` failed again with No production ingress. `host-admission-exceptions.txt` is absent: the new observer did NOT capture the cause, despite offline tests. Do not interpret absence as no connection exception, or claim root cause identified. Raw terminal JSON: `build/atlas-ingress-diagnosis/circle100.terminal.json`; taskDir ends `queue-7513bfd393e546788758e66628bcdd5a`. Original-bound-cgroup inode49184, cgroup.kill+cgroup-destroyed, errors empty, cleanup safe; post-containment source/copy fixture, official JAR/BAT and staged artifacts match fixed hashes. Full validation FAIL/normalExit=false remains.

Manager now reports external-busy (unrelated Cubism PID1559501). No builds/tests/new host launch while busy; never kill or forcibly release the external session. No further diagnostic submit is authorized by a heartbeat alone. Next offline work must establish observer installation/transformation coverage and classloader/output visibility, with a regression matching actual runtime loading, before considering another instrumented run. Current tests prove transformed-class validity and simple catch behavior, NOT real-host capture. Keep task26/27 unresolved; notify this finding once and avoid polling this completed job again.

## Renewed user authorization: diagnostic v2 submitted

User requested continuing and another host attempt. Host checked idle/no active or queued jobs before offline work. v2 replaces direct helper-class calls with a bootstrap Consumer bridge in System properties, and records installation/target transformation to `host-admission-observer.txt`. Hypothesis: loader visibility or installation gap hid v1 evidence; NOT established root cause. Offline test deliberately makes the probe class invisible to transformed code and still verifies captured exception identity/normal return; actual HostSession bytecode verification and 50 probe assertions PASS. Syntax/diff checks pass. Production Agent/plugin/fixture unchanged. Only fixed auxiliary observer changed; no new dependency/hook or official file modification.

- Job: `88faeb6e-3e9e-4e14-92a2-fcc8cd0a2604`
- Prepared: `5cd56294b73d7cc672ea96a239fe2bfe9b32e06a5469df4d5bd949388c79aac5`
- Request: `atlas100-admission-diagnostic-v2-ui-circle-100-new`, timeout1800.
- Probe SHA-256: `2d798d9ae2d3a8bfd7b79243c99636d42fa4864e61081972f1d175705b1ea6bf`.
- Fixed snapshot checks and responses: `build/atlas-ingress-diagnosis/diagnostic-v2-inputs.json`, `circle100-v2.prepare.json`, `circle100-v2.submit.json`.

Already submitted once. Track only this job, never reprepare/resubmit/change key or launch additional scenarios while pending. Terminal: inspect installation/transform markers AND exception file in exact taskDir, plus complete manager validation and bound cleanup. Missing markers must be distinguished from missing catch evidence. This is a diagnostic, not performance acceptance; no blind retries if it fails. Prior failures remain unchanged. Task26 remains in_progress.

## v2 captured root cause; offline fix verified

v2 job terminal FAIL, safe original-bound-cgroup inode49704 destroyed, kernel errors empty. Marker proves `installed` and `transform loader=null`. Exception captured: `IllegalArgumentException: verification record is not the reviewed trust-root record` at PinnedVerifiedResolverWorkflow.create:38, then VerifiedEditorModelResolverFactory.create:169 → VerifiedHostAdapterConnector.connect:425 → HostSession.refresh:260. Raw evidence: `build/atlas-ingress-diagnosis/circle100-v2.terminal.json`. Source/copy fixture, official JAR/BAT and staged artifacts match fixed hashes. No layout performance PASS.

Root cause: q selector additions changed all three editor-model JSON records, but their byte-hash pins in EditorModelVerificationManifest were not updated. This was our migration omission, not proven external-session interference. Static verification alone did not test the pinned admission workflow. All three official JARs now independently verify all selectors; updated only the three record pins to reviewed current JSON hashes, without changing official artifact trust or bypassing comparisons.

New EditorModelRecordPinTest reproduces the drift (RED) and checks the pinned bytes and q selector for all three versions (GREEN). Focused runtime pin/workflow/TextureAtlas tests PASS; devCheck and previewBundle PASS. Temporary HostAdmissionDiagnostic.install removed from final probe entry; its helper is retained only as diagnostic source, not installed in final timing. Build log `/tmp/atlas-pin-build.log`, regression `/tmp/atlas-pin-red.log` and `/tmp/atlas-pin-green.log`.

Next authorized action is one fresh Circle100 serial acceptance with fixed new artifacts; do not reuse old snapshots or treat old failures as PASS. Remaining three100 cases stay blocked until full single-case acceptance. Root identified and offline fixed, but task26/27 are not yet host-accepted.

## Fixed single-case acceptance submitted

Already submitted job `7a3e745f-694c-4ac1-8a3d-98f89c2a2bae`, prepared `3943344910ef39269fbfddf1067f07dffce7dd00ac634e5aa2d78f164541d81c`, request `atlas100-q-pin-fixed-v1-ui-circle-100-new`, timeout1800. Snapshot hashes checked; production Agent `a81838b36bb369a6e633779ead8fddf4904ee56fc0cc0931d2b74bda0b8efbf2`, plugin `e5ffd6060d4284493fb4d6bca3babb00e3430a8f8368c40e90320e7494ca3999`, probe `d03cc1b1e50e33d3fc5831839621298a7f5b7dcaba78ee2ad2a85115ebc4f74f`, original Circle100 fixture hash unchanged. Evidence inventory and submit JSON under `build/atlas-ingress-diagnosis/{pin-fixed-inputs.json,circle100-pin-fixed.submit.json}`. No observer installation in this run. Only track this new job; do not resubmit. Full manager+geometry+UI PASS is required before preparing remaining three with these fixed artifacts; old failed snapshots must not be reused.

## Fixed Circle100 serial accepted

Job7a3e745f-694c-4ac1-8a3d-98f89c2a2bae succeeded with validationStatus PASS, identityVerified/fixtureUnchanged/normalExit true, safe bound-cgroup inode49821 populated0 (no kernel errors), post-containment fixture/official/artifact hashes matching. One handled invocation, plannerParallel=false, 100/100, scale1, 4096×4096, finite/inside/layerMatch=true, overlaps0, overflow empty. Entry89.2337ms; raw UI391.1679ms (input probe19.6989ms included, output validation deferred). Input hash688b606413aa8fa473e0ac4c450fbf4cb256b446491ac3f32c4bb7a0c4dfc23e. Raw terminal evidence `build/atlas-ingress-diagnosis/circle100-pin-fixed.terminal.json`; task run queue-42496f9aefbb40c9b02997514e1e0ee2. N=1, no native rerun. User notified; proceed remaining three cases using identical fixed artifacts, never rerun this accepted case.

## Remaining fixed100 cases submitted once

All three prepared snapshots hash-checked against the accepted Circle100 Agent/plugin/probe before submission. Ledger `build/atlas-ui-100-pin-fixed/jobs.json` retains prepared IDs/request keys/submit responses. Each hard timeout1800. No build during measurements. Already submitted; never repeat prepare/submit or change keys.

- Circle100 parallel: `fb5d7739-6aee-42b3-96a5-2fa41ea79d2f`
- Geometry100 serial: `ca39c531-e4f2-4dc8-85e4-0592d14bb196`
- Geometry100 parallel: `f1a7481e-e705-4506-8900-2266c29bfb26`

Only read status and terminal evidence for these jobs now. Apply same complete acceptance checks, retain all raw times/input/fixture/artifact hashes and sourceEvidence, compare actual modes and geometry. When all terminal, update tri-language README and a separate fixed100 CSV, preserving seven earlier failed attempts and all historical timing tables. No native rerun; 100 does not cover <32 preflight, q!=1 remains offline-tested only.

## Final fixed100 acceptance: four PASS

All four fixed100 jobs succeeded. Each authoritative manager result has validationStatus PASS, identityVerified/fixtureUnchanged/normalExit true, safe original-bound-cgroup cleanup with no kernel errors and populated0/destroyed, and matching post-containment fixture/official/staged artifact hashes. Raw terminal JSONs are in `build/atlas-ui-100-pin-fixed/CASE.terminal.json`.

[Full-precision samples and evidence CSV](host-ui-100-pin-fixed-samples.csv) preserves texture dimensions, entry/UI/probe times, scale/placed/overflow, actual plannerParallel, packing threads/calls, input/output/fixture/artifact hashes, job/prepared/request IDs, exact sourceEvidence and cleanup identities.

| Dataset | Texture px | Mode | Entry ms | Raw UI ms | Included input probe ms |
| --- | --- | --- | ---: | ---: | ---: |
| Circle100 | 4096×4096 | serial | 89.2337 | 391.1679 | 19.6989 |
| Circle100 | 4096×4096 | parallel | 235.0498 | 529.3501 | 15.1949 |
| Geometry100 | 4096×4096 | serial | 173.9537 | 455.9776 | 14.2502 |
| Geometry100 | 4096×4096 | parallel | 261.3549 | 559.0466 | 13.7781 |

All outputs are100/100, scale1, inside/finite/layerMatch true, overlaps0, empty overflow. Circle pair input hash `688b606413aa8fa473e0ac4c450fbf4cb256b446491ac3f32c4bb7a0c4dfc23e`; Geometry pair `54103bb8af49409d3ae153fdacc82285e19abca0cecb72f9128e621f98d1e21b`. Each actual branch is handled; observed planner mode matches request. Parallel runs show real ForkJoin worker threads, not just a checkbox. Serial runs show one packing thread. Each fresh host N=1; timings do not establish statistically stable speedups. Parallel was slower in both100-item cases, so retain serial default. No new native measurements/ratios. UI includes input observation cost; output verification occurs after exact progress close.

The q-record pin omission is fixed and real-host admission restored. Seven previous failed attempts and all historical timings remain in their original ledgers; these results do not retroactively validate those attempts.100 items do not exercise the <32 preflight, and q!=1 rejection remains an offline regression. This closes the authorized100-item matrix, not all historical1000/2500 native/UI acceptance gaps. Three-language README updated; final notification and heartbeat removal follow.
