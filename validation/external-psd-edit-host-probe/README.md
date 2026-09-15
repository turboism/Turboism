# External PSD Edit Host Probe (025, test-only)

Drives the external PSD edit pipeline on the exact Cubism 5.3.02 host through the public
SDK only. Three phases, selected by `-Dturboism.validation.externalpsd.phase=`. The
fixture-specific decoder is used only by the persistence/reopen validation; the ordinary
GUI phase has no pixel-parser dependency.

## pipeline (default)

1. Resolve an ArtMesh → model image → current raw image chain on the task fixture, and
   record how many ArtMeshes share the same current raw (`relation.sharedRawArtMeshes`).
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
   layer-name mutation → stable revision → `replaceRawImagePsd` → `APPLIED` with consumed
   revision and post-native `after` observation. In persistence mode only, the final valid
   cycle applies one decoded target-layer RGB inversion; all earlier cycles remain name-only.
   Cycle 2 saves by atomic rename, and cycle 3 overlaps two writes; the overlap's final bytes
   retain the one final-cycle RGB inversion.
8. In persistence mode, a fresh native export must differ from the stable baseline fingerprint.
   Corrupted save → non-`APPLIED`, revision not consumed; its invalid external bytes are never
   accepted as post evidence.
9. Native `undo(1)` and `redo(1)` must both report `MOVED`. In persistence mode each is followed
   by a fresh native export, requiring baseline and post fingerprints respectively; raw identity
   alone is not sufficient. Ordinary pipeline mode retains the raw-identity check.
10. Idle wait → no revision replay without a new save.
11. `stop()` → `STOPPED`; post-stop writes publish nothing.
12. Re-export the same raw image → fresh handle → clean stop (same-binding recovery).
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

Before every phase operation, `resolveTarget` binds the task fixture/document to the first AWT
active, showing, displayable window that contains a reviewed table row for the exact resolved
ArtMesh domain ID. It uses the same exact `j.a`/`j.b` host accessor chain and window-local family
selection as the GUI path. This binding is required for `pipeline`, `reopen`, and `gui`; an
unproven or unavailable binding ends the phase `BLOCKED` after a bounded wait. The bound `Window`
object is retained through the phase, including a persistence `SAVE_AS`, so shutdown does not
re-resolve by an old filename, title, or arbitrary visible window. The probe records
`exit.targetWindow.*` evidence for the fixture/document/model/domain binding.

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

After the menu click, GUI success is limited to this fixture's first replacement observation:
the target must start with `isReplaced=false`, and the same binding, generation, and raw image
must remain unchanged while `isReplaced` changes `false→true`. A binding/generation/raw change,
or initial `isReplaced=true`, is stale/blocked evidence rather than success. This does not claim
content-difference or repeated-save persistence coverage.

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
notifications; the later strict `false -> true` target observation remains the GUI pass
condition.

## Validation-only decoder

`PsdValidationContent` accepts only the verified fixture profile: PSD v1, RGB, 8-bit,
1000×1000, seven layers, four channels, and PackBits/RLE1 layer/composite data. It locates
layer 6 by its validated `(450,450)-(550,550)` bounds and 100×100 dimensions, validates the
whole structure before mutation, and changes only decoded target RGB sample bytes. Alpha,
composite, other layers, metadata, and the caller's input array are preserved. Its fingerprint
hashes decoded target RGB plus bounds/dimensions/RGB channel IDs, so equivalent RLE packetization,
alpha-only changes, layer-name changes, and composite-only changes do not masquerade as content
changes. Raw/ZIP/16-bit, malformed, truncated, or wrong-profile data fails closed. It is not a
production PSD reader or runtime/SDK pixel API.

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

## Exact-host run (queued, serialized)

```bash
bash scripts/preview/run-external-psd-edit-host-validation.sh --dry-run
bash scripts/preview/run-external-psd-edit-host-validation.sh

# persistence: save-as → reopen two-stage evidence
bash scripts/preview/run-external-psd-edit-persist-validation.sh

# GUI: stage the production plugin jar beside the probe
EXTERNAL_PSD_PHASE=gui EXTERNAL_PSD_WITH_PLUGIN=<external-psd-edit.jar> \
  bash scripts/preview/run-external-psd-edit-host-validation.sh
```

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
still requires the later real-run checks and does not weaken the GUI `false→true` or Supervisor
gates. The authoritative record is `/opt/dev/projects/turboism/specs/025-external-psd-edit/plan.md`
under T021; this README records the validation-only interpretation because that specification is
read-only for this worktree.
