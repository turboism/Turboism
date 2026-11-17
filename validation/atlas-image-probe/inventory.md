# 020 T036 Dependency Inventory: atlas-image-observe:5303

## Authorization and Boundary

- This directory is `validation/atlas-image-probe/` and serves only the T036 offline implementation and self-check.
- The host behaviour allowed this round has not run: no prepare, no submit, no Cubism start, no external collector, no signal, and no forced process cleanup.
- `scripts/preview/host-validation-020.json` stays `runnable=false`; the scenario, the dependencies, and the real host evidence need main-agent and manager review before a separate release.
- This round only observes class definitions and the fixed menu path and measures no CPU, memory, JFR, FPS, image processing time, or parallel performance.

## Fixed Inputs and Official Dependencies

| Item | Fixed value or source | Summary |
| --- | --- | --- |
| Scenario | `atlas-image-observe:5303` | Circle100 only, one UI observation |
| Circle100 source file | the ignored `TURBOISM_HOST_VALIDATION_FIXTURE_5303` (`.env` or environment) | the wrapper and build require an absolute path whose normalized basename must be `atlas_mapping_100.cmo3` |
| Circle100 SHA-256 | `2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e` | the environment hash is not trusted; the build, the wrapper, and the manifest all verify it |
| Official JAR | the single basename `Live2D_Cubism.jar` among the real read-only `java.class.path` entries | no disk scan, no process guess, no `Class.forName` |
| 5303 official JAR SHA-256 | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` | the observer verifies it before registration; the target class bytes are read separately |
| Official source and classes | never enter the ordinary test classpath | the JAR is read only in an explicit real-host smoke, which this round did not use |
| Production agent | an explicit publish argument or `$root/build/preview/<worktree-id>/turboism-agent.jar` | after `realpath` it must still sit under the current worktree preview root; the manifest pins the path and hash and never uses `latest` |

## Code, JARs, and Configuration

### Production Validation Code

- `src/.../AtlasImageObserveContract.java` holds the constants shared by the observer and the driver and requires the `{FIXTURE}` basename to equal the `{TASK_ID}-atlas_mapping_100.cmo3` the Runner really expands.
- `src/.../AtlasImageLoadProbeAgent.java` supports `premain(properties-path)` and, without arguments, reads the named JVM properties; it registers a read-only transformer synchronously and always returns `null`.
- `src/.../ObservationSession.java` and `src/.../ReportCompletion.java` implement the existing finish and shutdown report protocol and write the result into a separate run directory.
- `src/.../SceneDriverState.java` is the fixed scene state machine; it allows only `Cancel -> finish.request -> observer persisted -> native exit action complete -> result`.
- `src/.../AtlasImageSceneDriverAgent.java` is a separate no-argument auxiliary agent. Through JDK Swing and the EDT it matches only the unique complete fixture window, `Modeling -> Texture -> Edit Texture Atlas...`, the exact editor window class `com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b`, the exact `Cancel`, and the exact native quit. The editor action waits within a bound through a latch and `finally` after Cancel and propagates exceptions; the native exit action also completes within its bound before a single result publication is allowed. It performs no automatic layout, sorting, or save, starts no command, calls no `System.exit`, and never treats a shutdown hook as success.
  Every `onEdt` query and action now uses `invokeLater` with a 5-second bounded wait. The 5 seconds is the dispatch and wait budget, and the fixed `QUEUED/STARTED/COMPLETED/TIMED_OUT` gate invalidates a callback still queued at a timeout or interrupt and skips it permanently when it arrives late; a started action cannot be undone, is never claimed rolled back, is not retried, and does not advance. The one exception is the pure read `MAIN_LOOKUP` with `TIMED_OUT + startedAtTimeout=false`, which only keeps querying as not-ready inside the explicit 120-second startup safety budget and the original overall deadline and never retries a started, failed, menu, Cancel, or Exit action; the budget is failure-evidence headroom, not a performance threshold.

### Offline Self-Check Code

- The existing `AtlasImageLoadProbeSelfCheck.java`, `AtlasImageLifecycleSelfCheck.java`, and `SyntheticLoadTarget` stay.
- The new `AtlasImageProbeConfigurationSelfCheck.java` covers the properties-path and no-argument entries, the Runner task-prefixed fixture basename, a missing or duplicated JAR, a wrong path or hash, a duplicate run, the path boundary, a wrong editor class, the refusal of a Cancel action completion barrier that throws, and the result order after exit.
  The same self-check reproduces with a real JDK EDT block the queue timeout of the real editor menu dispatch, zero menu selection or click after the queue releases, interrupt invalidation, a late return of a started action, normal completion, and exception propagation; it also reproduces the single read-only re-query after a `MAIN_LOOKUP` queue timeout, the invalidation of the first callback, the budget expiry failure, and the absence of a UI side effect, and verifies that a late return does not overwrite a recorded FAIL.
- `selfcheck/premain_smoke_selfcheck.py` and `premain-smoke.py` stay; the ordinary tests use only project-owned synthetic bytes.

### Deliverables

- `atlas-image-load-probe.jar`: observer plus shared contract only.
- `atlas-image-scene-driver.jar`: fixed driver plus state plus shared contract only.
- `build/atlas-image-probe/compile.<unique>/`: independent evidence, logs, both JARs, `artifact.sha256`, and `verified.properties` per build; no shared JAR and no `latest` pointer.
- `--publish` writes `build/preview/<id>/atlas-image-observe/bundle.<observer-hash>.<driver-hash>/` under its own worktree ID and publishes `bundle.manifest` atomically through a non-overwriting hard link; an existing manifest fails, so concurrent artifacts are never overwritten. The wrapper re-checks the preview root after the agent `realpath`.
- Publishing must supply or find an explicit production agent and record the production agent, the observer, the driver, the fixture, and the official 5303 hash in the manifest.

## Configuration and Startup Protocol

The Runner is not modified. The wrapper passes the following properties through `--jvm-option` and passes `{HOME}/{TASK_ID}/{FIXTURE}/{FIXTURE_NAME}` explicitly:

```text
-Dturboism.validation.atlasImageObserve.home={HOME}
-Dturboism.validation.atlasImageObserve.taskId={TASK_ID}
-Dturboism.validation.atlasImageObserve.fixture={FIXTURE}
-Dturboism.validation.atlasImageObserve.fixtureName={FIXTURE_NAME}
-Dturboism.validation.atlasImageObserve.version=5303
-Dturboism.validation.atlasImageObserve.outputRelative=state/atlas-image-observe
-Dturboism.validation.atlasImageObserve.timeoutSeconds=900
```

It also reuses the Runner's own `turboism.home`, `turboism.validation.runId`, and `hostVersion`. Both auxiliary agents take no arguments: the observer relies on `java.class.path` and the driver on the named properties. The old properties-path entry still accepts `version/editorJar/outputRoot/runId`, and the new real host entry does not depend on home-file template expansion. Machine paths come only from the ignored `TURBOISM_HOST_VALIDATION_FIXTURE_5303`.

## Build Commands, Network, and Secrets

Offline build and all synthetic checks:

```bash
bash validation/atlas-image-probe/build-and-selfcheck.sh
```

Only after the main agent finishes the dependency review, and only when an explicit production agent exists, may the manager decide separately whether to run (not run this round):

```bash
bash validation/atlas-image-probe/build-and-selfcheck.sh \
  --publish --production-agent /absolute/reviewed/turboism-agent.jar
```

This implementation needs no network, download, cloud service, external collector, SSH, secret, token, or credential. The build needs only JDK 17, `javac`, `jar`, Python 3, and local shell tools.

## Result, Timeout, and Cleanup Responsibility

1. The observer atomically writes `state/atlas-image-observe/<runId>/identity.properties` and `result.properties`; the run directory is claimed uniquely with `createDirectory`, and a duplicate run is refused.
   The driver also atomically replaces one bounded `driver-stage.properties` in the run directory that records fixed stages, times and durations, startupBudgetSeconds, mainLookupRetryCount, counters, booleans, and the onEdt state, covering Cancel dispatch and EDT return, close polling, action-completion waiting, and finish.request; it records no window title and no model content.
2. The driver waits for the observer run, strictly matches the task-prefixed fixture main window the Runner copied, triggers the fixed menu asynchronously, and after confirming the exact `f$b` editor window clicks only `Cancel`.
3. After Cancel succeeds and the editor and secondary dialog close safely, the driver writes `finish.request` with `CREATE_NEW` and accepts only an observer result of the same run with `completionReason=EXPLICIT_FINISH`.
4. When the observer is missing, times out, reports a non-`PASS` value, conflicts, misses the target, or omits a field, the driver may only write `status=FAIL` atomically and never fabricates a PASS.
5. After the Cancel action completes and a conforming observer result is read, the driver issues the single exact native quit; it also attempts that quit on a non-PASS observer result and can still write only FAIL. The `doClick` of that action must return within its bound without an exception before the canonical `result.txt` is written once. The run relies on no PASS overwrite, failure marker, or shutdown hook; a synchronous exit that terminates before `doClick` returns writes no PASS. A returned action does not mean the host exited, which still needs the final normal-exit and containment evidence; the driver never calls `dispose`, `kill`, or `System.exit`.
6. The wrapper passes no readiness or failure marker; the existing Runner reads `$HOME/logs/runtime/*.log` only when the marker list is non-empty, so stdout and stderr serve diagnosis only. The result timeout is 900 seconds, the graceful exit 120 seconds, and the driver's own default fixed-scene wait 900 seconds. The main agent and the manager own queue admission, timeout evidence, normal host exit, bounded cleanup, and the handling of an incomplete state.

## Corrections and Offline Evidence This Round

- The Runner really expands an explicit `--fixture-name atlas_mapping_100.cmo3` into `$TASK_ID-atlas_mapping_100.cmo3`; the dry-run regression checks `fixturePath`, `fixtureName`, `validationFixtureNameJvmOption`, and all four `{HOME}/{TASK_ID}/{FIXTURE}/{FIXTURE_NAME}` expansions.
- Both the observer and the driver strictly verify the copied fixture basename; the driver accepts only `com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b` and has negative controls for `atlasEditor.f` and `atlasEditor.a.f`. The editor `doClick` releases the driver inside one EDT dispatch, and after Cancel a completion latch and `finally` wait within a bound and check for an exception; the native exit action is likewise dispatched asynchronously and publishes the result only after it completes.
- The wrapper manifest now rejects an unknown key; it re-checks the current worktree preview root after the production agent `realpath`; it passes no readiness or failure marker, and the Runner reads no readiness log when the default marker list is empty.
- `PreparedStore.capture` followed by deleting the temporary synthetic source and input and then `load` and `command` regressed equal; no prepare, submit, worker, host, or external hook ran. The tests use temporary synthetic bytes only and move neither the real project nor the Circle100 fixture.
- The configuration self-check injects a Cancel action exception and confirms that the completion barrier refuses and never reaches result publication; the state, the self-check, and the static contract fix the publication order to one write after the native exit action completes, and no PASS-overwrite scheme remains.
- Offline evidence: `WRAPPER_ARGUMENT_CONTRACT`, `DRIVER_MODAL_DISPATCH_CONTRACT`, `ATLAS_IMAGE_PROBE_SELFCHECK checks=29`, `ATLAS_IMAGE_LIFECYCLE_SELFCHECK checks=39`, `ATLAS_IMAGE_CONFIGURATION_SELFCHECK checks=133`, `ATLAS_IMAGE_EDITOR_DISPATCH_SELFCHECK`, `ATLAS_IMAGE_MAIN_LOOKUP_SELFCHECK`, `ATLAS_IMAGE_STAGE_EVIDENCE_SELFCHECK`, `PREPARED_STORE_OFFLINE`, and `ATLAS_IMAGE_HOST_VALIDATION_OFFLINE_TEST` all passed; `host-validation-020.json` stays `runnable=false`. The stage properties file is a fixed-size atomic diagnostic file, and the synchronous file I/O is not claimed to be strictly bounded.
- New offline evidence this round: `ATLAS_IMAGE_EDT_GATE_SELFCHECK PASS` and `ATLAS_IMAGE_MAIN_LOOKUP_SELFCHECK PASS queued-retry-only=true startup-budget=120`; the stage evidence file is a single atomic replacement that cannot grow without bound with polling; no host and no third real job started. The second-round console and stage evidence prove only a queued MAIN_LOOKUP timeout at about 7.765 seconds with no document load or main scale completion, so they assert neither normal startup nor whether the window would eventually be found, claim no root cause, and do not claim to fix the first-round Cancel stall.
- Last offline build evidence (worktree-relative, `PYTHONOPTIMIZE=1` shell regression): `build/atlas-image-probe/compile.ap3Tan/`; observer JAR SHA-256 `f5313f97cf682fb7a346ed05d5ac09fdd2739276784ee9dff8d113d65d6631bb`, driver JAR SHA-256 `c69e727402464e53e26a97ede1172956471acdf0a110aca4020ebf4998991ad5`.

### Source SHA-256 of This Delivery

| File | SHA-256 |
| --- | --- |
| `src/.../AtlasImageObserveContract.java` | `3b46f7fd1932fff80580cf434d6fdf2c1c779ad040f3a2577122d64f52f1b572` |
| `src/.../AtlasImageLoadProbeAgent.java` | `6312c1ad87876cb09fbc6bf450bac5312f5fc0c7b24cf4b21d5e5ae2a193749a` |
| `src/.../AtlasImageSceneDriverAgent.java` | `6b3e594294f93d708c67f0b19e2ec309fb501bdcdc63bbb20b531c270452c054` |
| `src/.../ObservationSession.java` | `5ca39a5ef103677bb0b23de17812ad43c9afd5edec54c8d8ce6ce14c11291a3f` |
| `src/.../ReportCompletion.java` | `80d43067fc64cd9ed2221580fcbe5299bcdbcb26b8923853d4124a471728218b` |
| `src/.../SceneDriverState.java` | `47578bb26e2fd6933afb59b81e4e6dbb837489a688b6e1c7f99f7dac6c5cec2c` |
| `selfcheck/.../AtlasImageLifecycleSelfCheck.java` | `13dc90d89a8b282247f6b27ab3c32bde9d2d1ca5592639e82dfa4497d0eece42` |
| `selfcheck/.../AtlasImageLoadProbeSelfCheck.java` | `dbb24d257c2e42cdc29594d40e218d8bad128e83673eff808ec2dad742dd1bc8` |
| `selfcheck/.../AtlasImageProbeConfigurationSelfCheck.java` | `274bc3644ba35233a11367614781ef67eb01701bafe7de5b3fb50824b58a4768` |
| `build-and-selfcheck.sh` | `a7e01b4400349216b385116902bb270fd5e6fb7f529aa44c32a0e4b5adb01a3c` |
| `scripts/preview/run-atlas-image-host-validation.sh` | `9247f65e14843ad07a3855b652c495457853bd9ffbbf165a5f8734c2c8670941` |
| `scripts/preview/host-validation-020.json` | `27095486b128fbb00ec5f76d1cd537f17e1657d3f5ac91ee063d2811a960b422` |
| `scripts/test/test_atlas_image_host_validation.sh` | `c05e02fa7eb51364464c7be3150a97e7804e5375474e3650f6ef074590d7c0e7` |
| `README.md` | `a64f64e66e02acb9fcf9c9bc9e0d35019005f2ba8c8e075962644453a644c90a` |

## Remaining Gates

- The main agent reviews the source, the bundle manifest, the real production agent and hash, and the host classpath facts.
- The manager reviews the localized menu path, the unique window title, the exact editor window class `com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b` (with the `atlasEditor.f` and `atlasEditor.a.f` negative controls), the English `Cancel`, the native quit, and the fail-closed behaviour of a save dialog.
- Only after those reviews pass may the manager move the manifest from blocked to queueable and prepare, submit, and start the real host itself; this round lifted no such gate.
- Performance and CPU sampling is a separate task and is not carried by the minimal T036 correctness observation.
