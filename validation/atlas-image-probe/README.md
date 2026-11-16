# Atlas Image Observe (020 T036, Offline Implementation)

This is the minimal "queueable observation" scenario of feature 020. It is not a production plugin and claims no completed parallelization, image correctness, or performance acceptance.

Current scenario: `atlas-image-observe:5303` with the fixed Circle100 fixture. The implementation allows only these host actions:

1. After the main window with the unique complete fixture name appears, match `Modeling -> Texture -> Edit Texture Atlas...` on the EDT.
2. Confirm only the exact editor window class `com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b` and require a single visible, enabled English `Cancel`; the controller `atlasEditor.f` and the settings dialog `atlasEditor.a.f` are both rejected.
3. Click `Cancel` and perform no automatic layout, change no sort order, save no model, and accept no save dialog.
4. Write the task-home-relative `result.txt` once, and only after Cancel completes, the observer has persisted, and the `doClick` of the exact native quit menu has returned within its bound without an exception; a shutdown hook is never treated as action success.

The driver and the observer are two separate auxiliary agents; the driver starts no process, contacts no external client, and uses no `Class.forName`, `System.exit`, or kill.

## Offline Build and Self-Check

A JDK supporting `--release 17`, Python 3, and local shell tools are required:

```bash
bash validation/atlas-image-probe/build-and-selfcheck.sh
bash scripts/test/test_atlas_image_host_validation.sh
```

The self-check starts no Cubism and adds no official source or class to the ordinary test classpath. It keeps the existing 29-check observer, the 39-check lifecycle, and the Python smoke validator, and additionally covers:

- the old `premain(properties-path)` entry and the no-argument named JVM property entry;
- a missing or duplicated `Live2D_Cubism.jar`, a wrong path, a wrong hash, an illegal path, and a duplicate run ID;
- the `Cancel -> finish -> observer result -> native exit action complete -> result.txt` state order of a fake driver, where a Cancel action exception must not publish PASS;
- a missing observer result, a timeout, and a non-`PASS` value never producing a false PASS;
- the exact wrapper dry-run arguments, the real Runner task-prefixed fixture basename, the two auxiliary agents, the seven named JVM properties, and the absence of a hook or client; a real prepare still follows the manager gate;
- `host-validation-020.json` staying `runnable=false`;
- every EDT query and action of the fixed driver using `invokeLater` with a 5-second bounded wait, and an atomic state that distinguishes `QUEUED/STARTED/COMPLETED/TIMED_OUT`. The 5 seconds is the dispatch and wait budget: a queued callback that has not started becomes atomically invalid on timeout or interrupt and a late arrival is recorded as skipped only; an action that has `STARTED` cannot be undone, cannot be claimed rolled back, is not retried, does not advance, and a late return cannot rewrite a recorded failure. The one exception is the pure read `MAIN_LOOKUP` with `TIMED_OUT` and `startedAtTimeout=false`, which continues as a read-only query under the explicit 120-second startup safety budget and the original overall deadline; a started, failed, menu, Cancel, or Exit action is never retried. That 120 seconds is a safety budget for retaining failure evidence, not a performance threshold; the JDK self-check covers a real block, invalidation of a late callback, the next read-only identification, budget expiry failure, and the absence of a UI side effect.

Every build uses its own `build/atlas-image-probe/compile.<unique>/` and accepts only a result with `verified.properties` and `artifact.sha256`; there is no shared JAR and no `latest` pointer.
Every claimed run also atomically replaces one bounded `driver-stage.properties` that records only fixed stages, times and durations, counters, booleans, and the EDT state, and never a window title, model content, or an unbounded event log; it covers Cancel dispatch and return, close polling, action-completion waiting, and finish.request. The self-check loads the real generated file with `Properties.load` and checks every field, the fixed values, and the real newline; the file write is still a diagnostic record and does not promise that the file I/O itself is strictly bounded.

## Entry Contract

The historical entry still works:

```properties
version=5303
editorJar=/absolute/reviewed/Live2D_Cubism.jar
outputRoot=/absolute/task-owned-output
runId=properties-run-001
```

The new real host entry is a no-argument auxiliary agent passed by the Runner with explicit named properties:

```text
-Dturboism.validation.atlasImageObserve.home={HOME}
-Dturboism.validation.atlasImageObserve.taskId={TASK_ID}
-Dturboism.validation.atlasImageObserve.fixture={FIXTURE}
-Dturboism.validation.atlasImageObserve.fixtureName={FIXTURE_NAME}
-Dturboism.validation.atlasImageObserve.version=5303
-Dturboism.validation.atlasImageObserve.outputRelative=state/atlas-image-observe
-Dturboism.validation.atlasImageObserve.timeoutSeconds=900
```

The observer also requires the Runner `turboism.home`, `turboism.validation.runId`, and `hostVersion` identities to agree. The no-argument entry looks only at the read-only `java.class.path` of the real JVM for the single exact basename `Live2D_Cubism.jar`, verifies the pinned 5303 hash after normalization, and then reads the target class entries; it searches no disk or process and loads no official class.

Task output derives only from the explicit `turboism.home` and a bounded run ID:

```text
$HOME/state/atlas-image-observe/<runId>/identity.properties
$HOME/state/atlas-image-observe/<runId>/finish.request
$HOME/state/atlas-image-observe/<runId>/result.properties
$HOME/state/atlas-image-observe/result.txt
$HOME/state/atlas-image-observe/<runId>/driver-stage.properties
```

The run directory is claimed uniquely with `Files.createDirectory`, and a duplicate run is refused. The observer transformer registers synchronously, always returns `null`, and changes no official byte.

## Fixed Inputs

- fixture: supplied by the ignored `TURBOISM_HOST_VALIDATION_FIXTURE_5303` (`.env` or environment); the runtime basename must be `atlas_mapping_100.cmo3`.
- fixture SHA-256: `2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e`
- 5303 `Live2D_Cubism.jar` SHA-256: `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166`

The wrapper is a thin delegation layer:

```bash
scripts/preview/run-atlas-image-host-validation.sh 5303 r1 \
  --bundle-manifest /absolute/published/bundle.manifest \
  --dry-run
```

It only verifies the explicit manifest, the artifact and hash, the fixed fixture and hash, and its own worktree delivery root, and then delegates the `--prepare-dir`, `--dry-run`, and real lifecycle of the existing `run-cubism-host-validation.sh`. The current offline regression uses `--dry-run` only, launches nothing itself, and passes no home-file, plugin, client, or remote hook argument.

Publishing a bundle (implementation only, no host authorization implied):

```bash
bash validation/atlas-image-probe/build-and-selfcheck.sh \
  --publish --production-agent /absolute/reviewed/turboism-agent.jar
```

Publishing names the bundle by worktree ID under the fixed delivery root and its hash, and fails when the manifest already exists or a target artifact conflicts, so concurrent artifacts are never overwritten. The production agent, the observer, the driver, the fixture, and the official hash must all appear in the explicit manifest.

## Result Protocol and Gates

The observer `result.properties` must belong to the same run and carry `completionReason=EXPLICIT_FINISH`; the driver writes `status=PASS` once only when `status=PASS`, the target was really observed, there is no conflict or overflow, `guardInstalled=false`, and `optimizationReadiness=NOT_EVALUATED`, and only after the native exit action returned within its bound without an exception. Every other case atomically writes `status=FAIL`; the run no longer relies on overwriting PASS or on a failure marker. When the native menu terminates synchronously before `doClick` returns, the driver writes no PASS and the final gate rejects the missing canonical PASS; when the host still has not exited normally after the action returns, the final normal-exit and containment evidence rejects.

The Runner passes no readiness or failure marker: the existing Runner reads `$HOME/logs/runtime/*.log` only when the marker list is non-empty, so this scenario never treats stdout or stderr as readiness evidence; the result timeout is 900 seconds, the graceful exit 120 seconds, and the default fixed-scene timeout of the driver 900 seconds. A timeout, a missing observer result, a non-PASS value, a duplicate result, and an unsafe or ambiguous UI all fail closed. The main agent and the manager own prepare, submit, host startup, normal-exit evidence, timeouts, and bounded cleanup.
An EDT timeout or interrupt first writes the stage failure evidence, invalidates the callbacks that have not started, and stops further actions; only a timeout of the not-yet-started pure read `MAIN_LOOKUP` may continue within the startup budget and does not change the fail-closed condition of a secondary window. A normal result still publishes once only after the Cancel action completes, the observer result persists, and the native exit action raises no exception, and a late callback never clicks a control or resumes the flow.

The second real evidence round showed only the host startup log up to about 7.765 seconds with a `MAIN_LOOKUP` queue timeout; no document load or main scale completion appeared, so this slice proves neither the normal startup duration nor whether the window would eventually be found, and it claims no root cause and no fix of the first-round Cancel stall. This round completed only the offline implementation and verification, and the manifest stays blocked. Dynamic matching of the real menu, the exact `f$b`, `Cancel`, and native quit path, the production agent dependency, the class-path uniqueness, the normal host exit, and the manager admission remain the gates after main-agent review; performance measurement is a separate task.
