# External PSD Edit Host Probe (025, test-only)

Drives the external PSD edit pipeline on the exact Cubism 5.3.02 host through the public
SDK only. Four phases, selected by `-Dturboism.validation.externalpsd.phase=`. The
fixture-specific decoder is used only as validation evidence: persistence/reopen and GUI
checks decode target RGB, while no production pixel API is involved.

## Content profile

The validation content profile is selected explicitly through the namespaced property
`turboism.validation.externalpsd.contentProfile`. The thin host wrapper accepts the equivalent
`EXTERNAL_PSD_CONTENT_PROFILE` environment variable and passes it through unchanged:

```text
control7 (default) | f1
```

`control7` retains the existing seven-layer control contract. `f1` is a separate validation-only
contract for the prepared F1 input: PSD v1 RGB/8-bit, 2048×2048, 24 layer records consisting of
20 paint records and 4 group records, with the target record/layer ID (`lyid`) 22 and bounds
`(0,0)-(2048,2048)`. The same selected profile is used for baseline, written, current, old,
incoming, Undo, Redo, persistence, GUI, and reopen RGB evidence. F1 also requires the resolved
current ModelImage relation to contain at least two distinct `usingArtMeshIds`; that shared
relation is checked again around each replacement. The probe binds the relation dynamically and
does not require a fixed document, model-image, raw-image, or ArtMesh GUID.

An unknown profile is rejected before readiness, export, or mutation. A reopen run must receive
the same profile as the pipeline that produced its saved copy.

## Performance observation (opt-in only)

The validation-only sampler is started only when the namespaced JVM property
`turboism.validation.externalpsd.performance=1` is present together with
`contentProfile=f1`, `phase=pipeline`, and exactly `cycles=10`. Control7, ordinary F1 runs,
GUI/reopen/prepare phases, and any other cycle count reject the request before readiness/export/
mutation; absent or `=0` leaves the sampler off. The existing thin wrapper forwards an explicit
Runner `--jvm-option`, so an observation run can be admitted without changing the wrapper:

```bash
EXTERNAL_PSD_PHASE=pipeline EXTERNAL_PSD_CONTENT_PROFILE=f1 EXTERNAL_PSD_CYCLES=10 \
EXTERNAL_PSD_PERSIST=1 \
  bash scripts/preview/run-external-psd-edit-host-validation.sh \
  --jvm-option -Dturboism.validation.externalpsd.performance=1 \
  --jvm-option -Dturboism.validation.externalpsd.performanceWarmCold=cold
```

`performanceWarmCold` records an explicit `warm`/`cold` declaration; an omitted declaration is
recorded as `UNDECLARED`, never inferred. The sampler records a real 10ms EDT heartbeat,
heartbeat pending/queue delay, maximum sampling gap, heap/nonheap samples and peaks, JVM/OS,
processor count, max heap, lifecycle finish/stop, and coverage status. RSS and an exact native
stable-refresh timestamp are `UNAVAILABLE`. `PsdFileRevision` has no stable timestamp: each
cycle's timing is explicitly labelled as (1) write-complete → public replacement completion,
and write-complete → fresh-current content observation, upper-bound observations, and (2)
revision callback → that same fresh observation, a lower-bound observation. These values are not
native-final-refresh times. Ten timing samples use nearest-rank p95, so p95 is the maximum item.

Incomplete heartbeat coverage, a pending callback, a sampler failure, zero samples, or fewer than
ten ordered timing observations cannot be reported as complete evidence. This slice records
evidence only: `performance.gate=EVIDENCE_ONLY` and `performance.sc006=NOT_CLAIMED`; it does not
declare SC-006 PASS or lower its thresholds. The standalone `test.sh` remains offline and does
not start a host or enqueue a job.

## prepare-fixture

This preparation-only phase must run before the ordinary model-readiness wait:

```text
-Dturboism.validation.externalpsd.phase=prepare-fixture
```

The probe calls `OfficialPsdFixturePreparation.prepareFixture(context, () -> stopped, result)`
before `awaitReady()`. The helper drives the official Cubism 5.3.02 PSD startup chooser through
the reviewed new-model option, waits for the resulting model and relation graph, and performs
the task-scoped `SAVE_AS` control copy. The returned `PreparationResult.window()` is adopted as
`guiBoundWindow`, so the existing result write and exact-window normal close path are reused.
This phase does not call `resolveTarget()` by the old CMO fixture name, does not run pipeline
mutation/Undo/Redo/persistence, and does not itself claim FR-011 or external-edit functionality.
The helper records `prepare.*` input, chooser, relation, SAVE_AS, and failure evidence in the
same result properties file.

## pipeline (default)

1. Resolve an ArtMesh → model image → current raw image chain on the task fixture. The
   document/model/binding/generation/model-image/ArtMesh tuple is the stable target anchor;
   the raw image is tracked separately as the current lineage resource. Record how many
   ArtMeshes share that raw (`relation.sharedRawArtMeshes`).
2. `exportRawImagePsd` → `EXPORTED` + runtime-issued `PsdEditFile` + baseline revision.
3. Bind that handle to one newly-created runtime candidate under `java.io.tmpdir` by the
   before/after candidate-set difference. Zero or multiple new candidates, missing files,
   path escapes, and symlinks are rejected; for these native pipeline exports modification
   time is only recorded as marker diagnostics, never used to choose a result. The GUI
   session path takes its own candidate snapshot immediately before the menu click, then
   accepts only the one new directory and a complete, repeatedly identical PSD read; it
   never selects by maximum mtime.
4. With `EXTERNAL_PSD_PERSIST=1`, perform a second independent native export before any
   external edit. Decode both target-layer RGB payloads and require equal bounds, dimensions,
   RGB channel IDs, and content fingerprint.
5. `observeSaves`; assert the baseline revision is not replayed.
6. `openInDefaultApplication` — with the task-scoped `.psd → notepad.exe` association the
   pre-launch hook installs into the cloned prefix's `system.reg`, `OPENED` is expected;
   the golden prefix and real OS associations are never touched. The association proves only
   task-prefix launch configuration; it does not provide a PID or window handle for one external
   editor instance.
7. N save cycles (default 3; `-Dturboism.validation.externalpsd.cycles=`): structural
   layer-name mutation → stable revision → `replaceRawImagePsd(currentRaw)` → `APPLIED` with
   the exact pending revision consumed. A cycle advances its current target only when fresh
   after-relations directly retain the stable binding/generation and stable model-image/ArtMesh
   relation, contain the explicit `after` raw exactly once, and report that raw as the current
   model-image raw; a separate fresh identity observation must agree on the same stable anchor
   and current raw. `isReplaced` and a raw-set difference never advance lineage. The next cycle,
   corrupted-save check, recovery export, persistence tail, and other current-target reads use
   that verified raw rather than the initial raw. The final persistence cycle applies one decoded
   target-layer RGB inversion; all earlier cycles remain name-only. Cycle 2 saves by atomic rename,
   and cycle 3 overlaps two writes; the overlap's final bytes retain the one final-cycle RGB
   inversion.

   Persist mode also captures structured `relations.rawImages` on the EDT immediately before and
   after each import. The full raw ID set difference is recorded as `ZERO`, `UNIQUE`, or
   `MULTIPLE`; the previous raw is retained as an independent old-resource diagnostic export and
   is never labeled with the incoming export. Only a `UNIQUE` candidate that is exactly the
   explicit verified `after` raw enters the new-raw export coordinator. Its EDT starter verifies
   the current document/model/binding/generation/model-image/ArtMesh anchor and exactly one
   candidate occurrence before invoking public `exportRawImagePsd(candidate)` in that same EDT
   task; after export it verifies the same current anchor and candidate uniqueness again.
   A switched target, missing or duplicate candidate, or unavailable relation is `UNAVAILABLE`;
   an already-created handle is stopped before any bytes/RGB are accepted. The handle's `rawId()`
   is only the requested argument, not host identity evidence. `MULTIPLE` remains an explicit
   rejected/unguessable difference; export/decode/stop failures fail closed. `ZERO` is recorded
   as `NOT_ATTEMPTED` for the new-raw diagnostic and leaves the lineage and replacement gates in
   force.
8. In persistence mode, a fresh native export must differ from the stable baseline fingerprint.
   Corrupted save → non-`APPLIED`, revision not consumed; its invalid external bytes are never
   accepted as post evidence.
9. Native `undo(1)` and `redo(1)` must both report `MOVED`. Undo is checked against the raw from
   the last replacement-before target (for A→B→C, Undo must return to B), not always the initial
   raw; Redo is checked against the final applied raw C. In persistence mode each is followed by
   a fresh native export, requiring baseline and post fingerprints respectively; raw identity
   alone is not sufficient. Ordinary pipeline mode retains the corresponding current-lineage
   identity checks.
10. Idle wait → no revision replay without a new save.
11. `stop()` → `STOPPED`; post-stop writes publish nothing.
12. Re-export the verified current raw image → fresh handle → clean stop (same-binding recovery).
13. Environment metrics (`env.*`: heap, processors, EDT dispatch latency).
14. Persist tail when `-Dturboism.validation.externalpsd.persist=1`: a task-pinned
   `UserFileGrantSource.fixedSelection` target (wired by
   `-Dturboism.preview.userFileFixedGrant={HOME}/persisted-document.cmo3`) grants a WRITE
   handle → `EditorFileCommand.SAVE_AS` executes the verified native `saveDocument` → a
   `ProjectFileLifecycleEvent.After` SAVE event must confirm it. The saved copy lives in
   the task home, outside the fixture copy — the runner's fixture-unchanged guarantee still
   holds. A fresh native export immediately before SAVE_AS must equal the post-cycle fingerprint;
   a fresh export after SAVE_AS must equal it again. Only then are all tracked export directories
   moved with no-overwrite atomic moves into a task-owned quarantine. The result must expose:

   Each tracked export directory is moved by its own no-overwrite `ATOMIC_MOVE`. This is a
   per-directory atomic protocol, not an all-or-nothing batch transaction: if a later move fails,
   already-moved directories remain in quarantine, every item records its actual `moved` state,
   and the quarantine result is `FAILED` with `taskOwned=false` and `sourceMissing=false`.

   `persist.baselineTargetRgbSha256`, `persist.baselineSecondTargetRgbSha256` (equal),
   `persist.postEditTargetRgbSha256` (different), `persist.targetContentChanged=true`,
   `persist.saveSucceeded=true`, and
   `persist.tempQuarantine.status=MOVED`, `.taskOwned=true`, `.sourceMissing=true`.

### Persist diagnostic observations

Persist mode records a validation-only observation at each native baseline export, after each
public `PsdReplaceResult` completion, and during a bounded worker-side settle poll. Each observation
keeps raw-image IDs, model-image IDs, the `layerInputsByRawImage` selector projection, live PSD
`layerId`/name/`artMeshIds`, and the fresh native decoded target-RGB fingerprint in separate fields.
The observation is bound to the original CMO project document ID, model ID, relation binding,
model-image ID, and raw-image ID. The adapter's PSD snapshot `documentId` is the corresponding
`CLayeredImage` GUID (the same value exposed as `RawImageId`), so it is matched to the raw-image
ID separately and is never compared with the CMO project document ID. The probe checks that
identity before a diagnostic export and again while reading the post-export metadata; a switched
or incomplete target is `UNAVAILABLE`, never a combination of metadata and a fingerprint from
different targets. Fresh-export and metadata start/end timestamps
are recorded for each complete observation.
The public replacement result is recorded as import completion; the SDK exposes no native return
value, so `nativeReturn.observation=UNAVAILABLE` is never relabeled as observed native completion.

Settle evidence records `attempts`, elapsed `durationMs`, a stability criterion, and the last
observation. Stability means two consecutive complete observations are equal at the observation
level only; it does not prove that native replacement has settled or provide a native completion
signal, and it never replaces the RGB/content gates. Each poll receives its remaining worker
budget; within the poll, target binding, export, and metadata stages each receive a fresh
remaining-budget calculation, and the worker checks again after returning. Public `invokeAndWait` and export calls are synchronous
public SDK calls with no safe cancellation seam, so the 15-second window is advisory for a call
already in progress (`deadline.hard=false`); the worker waits for that call to return before handle
cleanup, then rejects a late value and does not record it as settle evidence. An unavailable
projection, observation exception, or bounded timeout remains a non-stable diagnostic result.
These observations do not weaken or replace the persistence RGB, history, SAVE_AS, handle-stop,
quarantine, or reopen gates. Unsupported public fields remain explicitly unavailable rather than
being inferred from layer names or IDs.

For each persistence cycle, `cycle.N.write.*` records the exact final bytes written by the test
mutation, its full-file SHA-256, and its decoded layer-6 RGB fingerprint. `cycle.N.raw.old.*`
records the previous target raw ID and an independent fresh native RGB observation after public
completion; it is old-resource diagnosis only and never receives the incoming/current export.
`cycle.N.raw.new.*` records the one newly-added raw ID, its fresh exported PSD bytes/RGB, and
immediate stop result. The existing `cycle.N.importCompletion.publicCompletion` and
`consumed` fields remain the public replacement evidence. A new raw's decoded RGB proves only
that content is observable through that raw's public export. It cannot distinguish a native
matcher that did not switch the model-image reference from a stale relation projection, and it
does not satisfy FR-011 or promote T021 to complete. This is the validation-only diagnostic
extension recorded under the authoritative `specs/025-external-psd-edit/plan.md` T021 entry;
the authoritative plan remains read-only for this worktree.

If the first fresh post-completion export fails, the result records
`importCompletion.diagnosticFailure` and `freshNativeRgb.failure`, records settle as
`NOT_ATTEMPTED`, and rethrows the original failure; no later settle or success claim is made.

## reopen

Runs against a fixture copy produced by a persist run (`--fixture-local <saved>`), re-exports
the current raw image, and asserts the fresh native decoded target-layer RGB fingerprint equals
the recorded `postEditTargetRgbSha256`. The result records
`reopen.expectedTargetRgbSha256` and `reopen.targetRgbSha256`; both must be canonical lowercase
SHA-256 values. Legacy full-file/composite hashes may be recorded for diagnosis, but cannot
replace this RGB gate. The target hash arrives via
`-Dturboism.validation.externalpsd.postEditTargetRgbSha256=`.

## gui

Loads alongside the production `external-psd-edit` plugin jar (`EXTERNAL_PSD_WITH_PLUGIN`),
discovers only a visible host `JTable` whose actual model class is the reviewed
`com.live2d.ui.treeTable.j`, and performs the expensive artifact/shape preflight once per host
classloader off the EDT. The returned private access context is reused by EDT row resolution;
an unavailable or unverified host is `BLOCKED`, not a generic Swing fallback.

The GUI wrapper admits dispatch only after the exact 5.3.02 readiness markers below have all
appeared in the task runtime log:

```text
Context-menu transform applied to com/live2d/cubism/view/palette/deformer/b appendPoints=11
Context-menu transform applied to com/live2d/cubism/view/palette/parts/T appendPoints=22
Turboism Developer Preview started
EXTERNAL_PSD_EDIT_GUI_TRIGGER_ARMED
```

The probe emits `EXTERNAL_PSD_EDIT_GUI_TRIGGER_ARMED` only after it has verified that the
context-owned state directory is real and that this run's trigger is absent. The shared Runner
then creates the relative trigger
`state/dev.turboism.validation.externalpsd/gui-ready.flag`. The probe waits for that regular
file under `context.paths().stateDir()` on its daemon worker before sampling GUI target state,
choosing a row, or dispatching mouse events. It rejects a pre-existing trigger, symlink,
non-regular path, invalid state directory, or timeout; the trigger itself is only a readiness
handshake and is never feature-pass evidence. The arm/trigger protocol uses no fixed sleep or
mtime heuristic, and the wait has no Swing/EDT or runtime-log access.

While a GUI wait remains armed but has not received its trigger, a separate daemon may take at
most two observational JVM samples at 120s and 180s after arming. Each sample uses this JVM's
`ThreadMXBean.dumpAllThreads(true, true)` and writes one bounded
`external-psd-gui-thread-dump-1.txt` or `-2.txt` file below the authenticated task state
directory. The file records `runId`, UTC time, elapsed time, wait stage, every included thread's
name/state/lock owner and a bounded complete stack for that thread; bootstrap and
`AWT-EventQueue` threads are prioritized in the bounded output, and the sampling daemon is part
of the all-thread dump. The output is capped at 256 KiB and the two-file limit is fixed.
`diagnostic unavailable` is evidence that the observation could not be collected, never a ready
signal or PASS condition. A received trigger, timeout completion, or `disable()` stops further
sampling; the daemon never waits on the readiness worker, EDT, or host, and it does not extend
the Runner timeout or alter hook behavior.

The wrapper also registers `Turboism object context-menu hook disabled safely` as a Runner
failure marker for GUI runs. The current shared Runner scans failure markers only after it has
created `gui-ready.flag`; therefore a hook-disabled line written before that point can still
allow the probe to receive the trigger and attempt a GUI dispatch. The later failure-marker scan
rejects the complete Runner result, but this slice cannot claim that a failed hook is impossible
to dispatch against. This slice does not change the generic Runner/runtime/bootstrap, and the
durable queue currently rejects an unreviewed `--client-script`. A future validation-only
handshake would need an explicitly reviewed, task-local client protocol that verifies the
readiness/failure markers and creates a separate probe trigger; until that dependency is
admitted, no pre-trigger hook-success claim is made.

Before every pipeline, reopen, or GUI operation, `resolveTarget` binds the task fixture/document to the first AWT
active, showing, displayable window that contains a reviewed table row for the exact resolved
ArtMesh domain ID. It uses the same exact `j.a`/`j.b` host accessor chain and window-local family
selection as the GUI path. This binding is required for `pipeline`, `reopen`, and `gui`; an
unproven or unavailable binding ends the phase `BLOCKED` after a bounded wait. The bound `Window`
object is retained through the phase, including a persistence `SAVE_AS`, so shutdown does not
re-resolve by an old filename, title, or arbitrary visible window. The probe records
`exit.targetWindow.*` evidence for the fixture/document/model/domain binding. The
`prepare-fixture` exception receives its exact window from `PreparationResult.window()` instead;
it does not use the CMO-name target resolver.

The GUI path then requires an AWT active, showing, displayable target window and scans reviewed
tables in that window only; a window that cannot be proven active is rejected, and another
window is never a fallback. Once the first reviewed window containing the exact target is
matched, its identity remains bound for the GUI session. If focus moves elsewhere, the probe
waits for that same window or ends `BLOCKED`; it never rebinds to another project window. The
selected SDK ArtMesh's complete domain ID is matched against
the exact host accessor chain (`j.a` backing tree model → `j.b` JTree → node source →
`getId().getIdString()`). Parts/deformer family and exact ArtMesh source class are retained in
the target identity. Candidates are grouped by window, row family, source class, and complete
domain ID: one Parts and one Deformer entrance for the same ArtMesh are valid and tried in
Deformer-then-Parts order, while multiple candidates in one family are rejected. The name model
column is fixed at index `2` and converted to the current view column; its visible cell bounds
supply both selection and popup coordinates. Draw/Lock columns, labels, row numbers, arbitrary
JTree/JList rows, and other windows cannot be fallbacks.

Before the left selection click and again immediately before the right-click, the EDT validates
showing/displayable/parent state, fresh row bounds, family/source/domain identity, and visible/
locked state. A detached or replaced table is re-resolved by the same identity with a bounded
three-attempt retry. Any visible/locked change is rejected and recorded; the probe does not
restore it. Diagnostics include complete capture/selection/after-left widget, window, model,
row, identity, state, and coordinate fields. Popup association remains limited to a new or
successfully dismissed popup from that attempt, with exactly one platform popup trigger.

After the menu click, GUI success requires the same bound document/model/model-image/binding/
generation/ArtMesh stable anchor, a real RGB mutation written to that exact issued session file,
and a fresh native export of the observed current raw whose decoded target RGB exactly matches
the written mutation. The current raw is recorded independently from the wrapper's
`isReplaced` bit; a same-raw `false→true` flag alone is never PASS, and an incoming raw is
accepted only through the current-raw export/RGB gate. An anchor change or initially
`isReplaced=true` is stale/blocked evidence. This does not claim repeated-save persistence
coverage.

Before the GUI mutation, the probe records `gui.session.read.attempt.N.*` for every bounded
candidate/file-read state. Each readable attempt records the authenticated real path, file size,
read byte length, mtime, full-file SHA-256, and the structural section/offset/reason. It also
records `fileKeyStatus` and
`identityVerified`: a non-null platform `BasicFileAttributes.fileKey()` is compared as an
additional file-object check, while a null key is explicitly `UNAVAILABLE` and never claims
object identity verification. If both reads have null keys, the gate may use only the
authenticated task path plus size, mtime, complete bytes, and structure; a key appearing,
disappearing, or changing is rejected rather than treated as `null == null` identity.

The gate accepts only the reviewed PSD v1 RGB 8-bit raw/PackBits forms. A PSD is not ready
merely because it is a regular file or has a mutable layer name: header, declared sections,
layer records, channel data, PackBits/raw boundaries, and composite data must all be complete.
Three consecutive reads must agree on the same real task path, size, mtime, full bytes, and
structure, with equal file keys whenever keys are available. Any in-place write resets the
stability window; a replacement identity, symlink, ambiguous/new-directory change, or timeout
is rejected. Immediately before the test mutation, the complete byte snapshot is read and
confirmed again, and the path is checked again before writing; otherwise no write occurs. The
portable path/content protocol cannot distinguish a same-path replacement with identical
content and metadata; task-owned directory binding is the scope of this validation and it does
not claim a native file-object identity under a null-key filesystem. This is only an export-file
readiness gate and does not claim that the production session has subscribed to save
notifications; the later fresh current-raw RGB match remains the GUI pass condition.

## Validation-only decoder

`PsdValidationContent` has two explicit validation profiles. `control7` accepts the verified
PSD v1, RGB, 8-bit, 1000×1000, seven-layer control with four channels and PackBits/RLE1
layer/composite data; it locates layer 6 by its validated `(450,450)-(550,550)` bounds and
100×100 dimensions. `f1` accepts only the reviewed PSD v1, RGB, 8-bit, 2048×2048 structure
with 24 records (20 paint and 4 group records), and locates record/`lyid` 22 by its validated
full-canvas bounds. Both profiles validate the whole structure before mutation and change only
decoded target RGB sample bytes. Alpha, composite, other layers, metadata, and the caller's
input array are preserved. Their fingerprints hash decoded target RGB plus bounds, dimensions,
and RGB channel IDs, so equivalent RLE packetization, alpha-only changes, layer-name changes,
and composite-only changes do not masquerade as content changes. Raw/ZIP/16-bit, malformed,
truncated, or wrong-profile data fails closed. This is not a production PSD reader or a
runtime/SDK pixel API.

## Build / offline test

```bash
./gradlew :sdk:jar
bash validation/external-psd-edit-host-probe/build.sh
bash validation/external-psd-edit-host-probe/test.sh
```

`test.sh` runs the decoder's standalone main and the probe's offline focused main; neither
starts Cubism or enqueues a host job. It also executes this wrapper in a temporary sandbox with
a stub Runner and asserts the actual GUI argv contains all readiness/failure markers, keeps the
fixed trigger after a caller-supplied trigger, and adds no GUI gate to pipeline or reopen. The
default helper test is portable and prints
`SHAPE=NOT_RUN`; that output is not a real-host shape result. The test-only exact-name fixture is
compiled only into the temporary test output, and the probe JAR asserts that it contains no
`com/live2d/` entries.

To run and record the real-JAR shape check, configure both the reviewed host JAR and its linking
dependencies explicitly. `--shape` makes missing configuration a nonzero failure instead of a
skip:

```bash
TURBOISM_EXTERNAL_PSD_SHAPE_JAR=/path/Live2D_Cubism.jar \
TURBOISM_EXTERNAL_PSD_SHAPE_CLASSPATH=/path/kotlin-stdlib-1.7.21.jar:/path/jdom-1.1.jar \
  bash validation/external-psd-edit-host-probe/test.sh --shape
```

The command must print `SHAPE=PASS` and exit `0` before an actual-host claim is made. Equivalent
system properties are `turboism.validation.externalpsd.shapeJar`,
`turboism.validation.externalpsd.shapeClasspath`, and `...shapeRequired=true`.

To validate the retained 958a6 real export without modifying its evidence file, pass its exact
path through `turboism.validation.externalpsd.sessionSample`:

```bash
JAVA_TOOL_OPTIONS='-Dturboism.validation.externalpsd.sessionSample=/path/to/958a6/external-edit.psd' \
  bash validation/external-psd-edit-host-probe/test.sh
```

The focused test records the source byte count, full SHA-256, and structural parse result, then
copies the source into a task temporary directory and runs the production candidate/readiness
and write-confirmation helpers against that copy. It prints `REAL_SESSION_SAMPLE=NOT_RUN` when
the property is absent; a configured source must parse completely and the helper check must pass.

## Official PSD fixture preparation

The preparation wrapper drives only the reviewed Cubism 5.3.02 chooser and the fixed-grant
`SAVE_AS`; it does not load the production external-PSD plugin. The default `normal` profile
keeps the existing `prepared-control.cmo3` output and selects `CUB3-0418`. The explicit `legacy`
profile selects `CUB3-4408` and uses the independent `prepared-control-legacy.cmo3` output/grant.
Unknown profiles fail before the shared runner is invoked.

```bash
bash scripts/preview/run-external-psd-fixture-preparation.sh --dry-run
bash scripts/preview/run-external-psd-fixture-preparation.sh --profile legacy --dry-run
```

Both profiles retain the exact source, chooser shape, source-layer/raw relation, window/stop,
code-source SHA, and SAVE identity gates. The helper records the model blend/version-mode
observation; when no reliable public SDK getter is available it records `UNAVAILABLE` rather
than inferring the mode from the selected chooser option.

The independent `f1` profile uses the reviewed `f1-2048-20layers.psd` source
(2048×2048, 20 drawing layers; SHA-256
`2473baeae7fc8942d8d2e0df72cf4567f57f8e3a9a37a924fc4d01034ababfeb`) and saves
`prepared-f1.cmo3` under its own fixed WRITE grant. It selects the first chooser option, keeps
the source snapshot/raw/document identity gate, then uses the exact reviewed ArtMesh row and
public SDK `COPY`/`PASTE` commands. The F1 relation gate requires the old ModelImage/current
raw/non-empty bindings to remain and requires both the original and one new ArtMesh to resolve
to that old ModelImage; it deliberately does not require seven-layer leaf/binding equality or
the seven-layer target-RGB hash. This is fixture preparation evidence only, not an F1 pipeline or
performance PASS. If an exact row, SDK selection, command result, or relation cannot be proven,
the helper returns `BLOCKED` without guessing.

```bash
bash scripts/preview/run-external-psd-fixture-preparation.sh --profile f1 --dry-run
```

## Exact-host run (queued, serialized)

```bash
bash scripts/preview/run-external-psd-edit-host-validation.sh --dry-run
bash scripts/preview/run-external-psd-edit-host-validation.sh

# persistence: save-as → reopen two-stage evidence
bash scripts/preview/run-external-psd-edit-persist-validation.sh

# if A finished but its client wait failed, recover that exact job and run only B
bash scripts/preview/run-external-psd-edit-persist-validation.sh --resume-stage-a <job-id>

# F1 content profile (use the same variable for a later reopen stage)
EXTERNAL_PSD_CONTENT_PROFILE=f1 \
  bash scripts/preview/run-external-psd-edit-host-validation.sh

# GUI: stage the production plugin jar beside the probe
EXTERNAL_PSD_PHASE=gui EXTERNAL_PSD_WITH_PLUGIN=<external-psd-edit.jar> \
  bash scripts/preview/run-external-psd-edit-host-validation.sh
```

`--resume-stage-a` queries the existing job through the shared queue CLI. It requires exactly
that job's complete successful Supervisor evidence and reruns the ordinary terminal SHA,
pipeline phase, changed RGB, quarantine, and saved-file checks before submitting B. A failed,
unfinished, missing, or ambiguous job starts no host task. It never submits A or changes the
queue's bounded wait retries. Use it after confirming B was not already submitted; it submits
a new independent B, so it is not an idempotent B recovery command. The option cannot be combined
with `--dry-run`. Other arguments are passed to the host wrapper, including B; do not pass A's
fixed `--fixture-sha256`, because B opens a different saved CMO file.

Result file: `state/dev.turboism.validation.externalpsd/external-psd-edit-result.properties`
under the isolated Turboism home. Terminal status is `status=PASS|FAIL|BLOCKED`; the probe
never rewrites a FAIL or BLOCKED into PASS.

Shutdown: after writing the result the probe posts `WINDOW_CLOSING` only to the exact task window
bound during target resolution (the GUI path retains that same binding). The probe recognizes only a newly-created
`JOptionPane` owned by that same window whose verified host shape is `[Yes (Y), No (N),
Cancel (C)]`, and clicks `No` (index 1) so the task copy is not saved. Unknown, pre-existing
modal/JOptionPane, multiple, or cross-window dialogs are rejected. A pre-existing dialog is
excluded only when the complete before and after snapshots have the same identity, exact bound
owner, `showing=true`, `displayable=true`, `modal=false`, `modality=MODELESS`, and `panes=0`;
the exclusion is recorded as `excludedPreExisting` and that window is never operated on. Any
identity, owner, liveness, modality, modal flag, or pane-shape change is rejected. The No action is claimed once per close session
by a shared atomic gate; the option must be enabled, showing, and displayable both when observed
and immediately before `doClick`, and a failed action is not reported as dismissed. If exact
binding or native close cannot be proven, the phase remains `BLOCKED`/exit failure and cleanup
belongs to the shared Supervisor. The task-prefix `.psd` association has no PID or native window
handle ownership relation for the detached default application, so the probe does not close or
kill that external process; the task-scoped Supervisor owns its cleanup. The exit watchdog writes
a bounded thread diagnostic only; it never kills the JVM or substitutes for Supervisor cleanup.
Result write failures and close failures are logged separately. Each close session also emits a
bounded before/after dialog inventory with the dialog identity, exact owner identity, modality,
JOptionPane count, option classes/labels, snapshot-to-dispatch timing, and dispatch/inspection
counts. All close-diagnostic budgets below count UTF-8 encoded bytes, including the `…` truncation
marker: each dialog field is capped at 128 bytes, before and after inventories at 1536 bytes each,
dispatch evidence at 1024 bytes, and the rejection/decision reason at 2048 bytes. A trace is capped
at 7168 bytes; its session, counters, timings, and `before=`, `after=`, `dispatch=`, and `reason=`
labels remain present even when those inventories are truncated. The result reason receives 1022
bytes, and the final result-plus-trace diagnostic is capped at 8192 bytes including its separator.
At most 32 dialog entries are included; larger inventories record `truncated=true` and the total
count. The T021 close-race evidence recorded by the authoritative 025 plan for run
`queue-18a49af72e7a434eb47f1e2e05b3f32a` showed the before `JDialog@43f3428a` as an exact-owner,
modeless, zero-pane window and the after `JDialog@4b1fd017` as the same-owner
`APPLICATION_MODAL` three-option save dialog; the minimal classifier change correlates those
full snapshots instead of rejecting the harmless pre-existing tool window. The same evidence
still requires the later real-run checks and does not weaken the GUI current-raw RGB evidence or
Supervisor gates. The authoritative record is `/opt/dev/projects/turboism/specs/025-external-psd-edit/plan.md`
under T021; this README records the validation-only interpretation because that specification is
read-only for this worktree.
# Live composition evidence

For the independently prepared normal/legacy controls, the pipeline can additionally receive
`--jvm-option '-Dturboism.validation.externalpsd.compositionProfile=normal'` (or `legacy`).
The validation-only reader verifies the reviewed host artifact off EDT, then binds native
ArtMesh GUIDs to the current SDK document/model on EDT and reads the actual color/alpha
composition enums and target version. It requires the requested old/new blend family and
compares every ArtMesh ID/GUID/composition after each replacement and Undo/Redo with the
baseline. Missing coverage or changed mode fails the phase. This is opt-in live evidence;
earlier serialized XML audits are not relabelled as live observations.

## Structural comparison controls (F5)

`EXTERNAL_PSD_PHASE=structure-native|structure-sdk` collects one fixed structural variant
on a fresh copy of the prepared F1 CMO. Both phases require `EXTERNAL_PSD_CONTENT_PROFILE=f1`,
`EXTERNAL_PSD_STRUCTURE_VARIANT=add|delete|merge|canvas` and an absolute
`EXTERNAL_PSD_STRUCTURE_SOURCE` path. The shared Runner stages that input at
`{HOME}/structural-input/external-edit.psd`; the probe verifies its fixed official-writer SHA.
The wrapper grants WRITE only to `{HOME}/persisted-document.cmo3`.

The native phase uses official `command_open` and the reviewed model/raw choosers. The SDK
phase writes the runtime-issued edit file, awaits an opaque revision, and requires APPLIED,
the requested before raw and consumption of that exact token. Both observe one new native
history entry, a unique incoming raw, stable authoring structure, Undo/Redo and SAVE_AS.
Task/document/model/window/stop and deadline admission precede mutations; queued expired
EDT work cannot execute. A native chooser return alone does not complete the observation.

`structure.collection=PASS` means only that this task collected its observations.
`structure.comparison=NOT_RUN` remains until independent native and SDK tasks are compared
for the same variant and baseline. Generated raw/image/layer GUIDs are normalized; original
identities, empty bindings, layer/input/group ordering and mesh geometry remain observable.
Multiply/screen RGBA comes from official Editor current-keyform getters, matched to SDK
ArtMeshes by GUID on the same EDT observation with document/model generation checks.
The helper verifies the defining loader, official JAR SHA and exact method shapes before use;
missing colors or incomplete/ambiguous mesh coverage fail. These are authoring colors, not
evaluated public Core colors. The separate Editor-to-Core receiver mismatch remains unresolved.
`structure.inputDetails.version=1` adds a validation-only native observer for all selector
records: six affine components and null or exact `ClipByMesh` positions/indices, preserving
record order. ModelImage/raw/layer GUID coverage and detail presence must match the SDK graph.
It reads the cached local-to-canvas field directly; the lazy getter would initialize a cache.
All classes, getters and fields pass the defining-loader/code-source/official SHA checks;
unknown shapes, non-finite components and identity changes reject the observation.
Each stage also records the exact current document's `isModifiedAfterSaving()`. SAVE_AS binds
the saved CMO SHA in the terminal for subsequent read-only archive auditing.
Older collections lack these live fields and cannot be compared with version 1.
Final viewport presentation remains unavailable. Collection PASS alone cannot satisfy F5 or SC-006.

Compare two collected task directories with:

```bash
python3 validation/external-psd-edit-host-probe/structural_compare.py NATIVE_DIRECTORY SDK_DIRECTORY
```

Each directory must contain `bound-job.json`, `lifecycle-result.json` and the SHA-bound
`external-psd-edit-result.properties`. The auditor checks independent successful task identities,
safe cleanup, unchanged fixture/official JAR, staged artifact hashes, the same probe and variant
input, command/revision completion and all five observation stages. Agent hashes are reported
separately so a runtime fix can be compared with an earlier official baseline.
Only cross-task history object identities and exact scope declarations are excluded; each history
identity must remain stable within its task. Names, order, colors and geometry differences remain
visible. Exit 0 means `OBSERVED_FIELDS_MATCH`, 1 means `OBSERVED_FIELDS_MISMATCH`, and 2 means
rejected/incomplete evidence. Every result retains `F5=NOT_CLAIMED`; incomplete older collections
retain their live input/dirty gaps, and final viewport presentation is always unobserved. Synthetic auditor
regressions: `python3 scripts/test/test_external_psd_structural_compare.py`.

For an independent saved-input supplement, extract `main.xml` with the official ArchiveReader
and run `python3 validation/external-psd-edit-host-probe/structural_xml.py BASELINE.xml SAVED.xml`.
It resolves serializer references and `FilterValueId.idstr`, preserves ordered input records
and empty bindings, and rejects unknown/missing/ambiguous structure. Saved clipping currently
supports only an observed empty `null` element. Non-null clipping is rejected until its exact
serialized shape is verified; the live `ClipByMesh` reader does not establish that XML shape,
and `CRect` is not the host field type. It reads only XML and
prints JSON; it does not modify a CMO or reconstruct bindings. Serialized values supplement
the saved stage only; live Undo input details remain `NOT_OBSERVED`. Focused checks:
`python3 scripts/test/test_external_psd_structural_xml.py`.

### Failure-only file-holder observation

The admitted RSS observer takes one bounded task-scope `/proc` fd/maps snapshot after a
FAIL terminal. Only `external-edit.psd` and its `.tmp` sibling under task-local
`turboism-psd-*` directories are reported, with PID/start-ticks and cgroup identity
rechecked around reads. It reads no file contents, sends no signals, and never
changes the atomic-save gate. Limits are a 2-second checkpoint budget (individual OS reads cannot be interrupted),
128 processes/observations,
4096 descriptors per process and 4 MiB of mappings per process; partial coverage
is explicit. Linux file presence is not Windows sharing-mode or lock proof;
absence cannot prove that a Windows handle was absent at the failed move.

On an atomic cycle-2 move failure the probe additionally observes only the current
Cubism JVM's Windows disk handles, using the verified host loader and pinned JNA
5.6.0 artifact. The x64 system table is capped at 4 MiB; at most 4096 own handles
are queried with a 2-second checkpoint budget (individual native calls cannot be
interrupted). Only exact target/sibling matches are emitted. It never duplicates
or closes an observed handle, opens another process, or retries the move. Query
failure remains UNAVAILABLE/PARTIAL and the original move exception is rethrown.
Handle reuse races and absent matches cannot prove absence of a sharing lock;
this is diagnostic evidence, not performance or editor readiness acceptance.
Failure metadata also records target/sibling DOS attributes without following
links or changing attributes. File-type failures and disk-path failures have
separate last-error histograms; the JNA thread error is cleared before each call
and captured immediately afterwards. Non-file handles can fail `GetFileType`, so
the aggregate failure count alone does not identify failed disk-path queries.
Attribute/path observations race with external writers; no match, error code,
or read-only flag alone proves the cause of the earlier move failure.

Opt-in `-Dturboism.validation.externalpsd.atomicFileControl=true` adds three tiny,
independent file pairs under a new directory beside the issued PSD before cycle 1:
unopened target, target held by a READ/NOFOLLOW channel, and target whose read
channel has closed. Each pair gets one atomic replacement attempt. The control
records exceptions and validates final bytes; it never opens or alters the issued
PSD, retries its save, or pauses its watcher. Only control-owned channels close;
control files remain in the task temp directory. The 2-second budget is checked
between cases, not a hard OS-call cancellation. These diagnostic runs do not
establish performance acceptance or identify the real failed PSD's holder.

Performance environment metadata includes the active collector names and read-only
HotSpot VM options for G1 region size, compressed pointers and object alignment,
including each option's origin. Unsupported beans/options are UNAVAILABLE. The
probe never calls `setVMOption`; these fields do not alter collector configuration.

`OfficialSecondDocumentOpen` is a validation-only helper for the F2 preparation
coordinator. It admits an immutable task-owned `.cmo3` with a fixed SHA, then
dispatches the reviewed public `command_open(File,true)` once on EDT. Its initial
document UID/contentId and main window must match the caller's binding. Completion
requires two distinct native documents/model objects, unique document/content IDs,
the expected second file path and an unchanged first document observation. Equal
model GUIDs or raw basenames are allowed; they never establish document identity.
The observation covers raw names/GUIDs and model-image current/linked/selector keys,
not layer record contents, ArtMesh bindings, RGB, history or full F2 isolation.
Dialogs after dispatch are observed until they disappear or the common deadline
expires; this helper never clicks them. Stop, task loss and expiry invalidate queued
EDT callbacks without interrupting EDT. The `prepare-second-document` phase opens
the fixed original normal control alongside its ten-cycle persisted copy. Set
`EXTERNAL_PSD_FIXTURE_LOCAL` to the persisted copy and `EXTERNAL_PSD_SECOND_DOCUMENT`
to the original `prepared-control.cmo3`. The wrapper requires the reviewed SHA of
both inputs and stages the second under `second-document/prepared-control.cmo3`.
The phase checks SDK binding/generation in the same EDT operation before opening,
then compares the active second document's SDK/native identity and model-image
relations. `secondDocument.status=PREPARED` and phase `PASS` only describe preparation;
`f2.acceptance=NOT_CLAIMED` remains explicit. No replace, save or Undo is performed.
Focused tests and exact JAR shape are run by
`bash scripts/test/test_official_second_document_open.sh`; normal probe tests run
the host-independent cases, and `test.sh --shape` also runs the exact JAR check.
