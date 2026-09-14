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
   time is only recorded as marker diagnostics, never used to choose a result. (The separate
   GUI session path retains its existing marker-based discovery.)
4. With `EXTERNAL_PSD_PERSIST=1`, perform a second independent native export before any
   external edit. Decode both target-layer RGB payloads and require equal bounds, dimensions,
   RGB channel IDs, and content fingerprint.
5. `observeSaves`; assert the baseline revision is not replayed.
6. `openInDefaultApplication` — with the task-scoped `.psd → notepad.exe` association the
   pre-launch hook installs into the cloned prefix's `system.reg`, `OPENED` is expected;
   the golden prefix and real OS associations are never touched.
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

   `persist.baselineTargetRgbSha256`, `persist.baselineSecondTargetRgbSha256` (equal),
   `persist.postEditTargetRgbSha256` (different), `persist.targetContentChanged=true`,
   `persist.saveSucceeded=true`, and
   `persist.tempQuarantine.status=MOVED`, `.taskOwned=true`, `.sourceMissing=true`.

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
dispatches a real popup-trigger right-click on visible `JTree`, `JTable`, or `JList` rows until
a popup exposes the contributed item (`Edit PSD Externally`), clicks it, waits for the plugin's own
`turboism-psd-*` session file, writes a mutated save, and verifies the plugin auto-imports
it for the resolved target: the target must start with `isReplaced=false`, and success requires
the same binding, generation, and raw image with `isReplaced` changing `false→true`. Any
binding, generation, or raw-target change is stale evidence and is not success; an initial
`isReplaced=true` target is `BLOCKED` because no new replacement can be observed. `BLOCKED`
(not `FAIL`) when no popup can be raised — a blocked run terminates fast through the runner
`--failure-marker`.

Each captured row records its widget identity, row key, and bounds. Immediately before dispatch,
the probe runs on the EDT and requires a showing, displayable widget with a parent and valid
bounds. The selection click is followed by a second EDT validation; if the host replaced the
table, the probe relocates a unique matching row by its full length-prefixed value key and fresh
bounds, up to three attempts. Candidates must remain in the captured window: a same-type row in
another window is rejected. Ambiguous, unavailable-key, or unrelocatable rows are rejected
without a right-click; default `ClassName@identity` text is not a row key. Popup association is
limited to a new or successfully dismissed popup from that attempt. Renderer preparation is
diagnostic-only and runs after the right-click, or is skipped for a detached table.

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
starts Cubism or enqueues a host job.

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

Shutdown: after writing the result the probe taskkills the task-scoped `notepad.exe` editor
and drives the document frame's `WINDOW_CLOSING`, so Cubism releases its engine and exits
natively (`-- successfully exited pid:`). A plain `Runtime.exit`/`halt` is not viable once
the native save path initialized JOGL/GlueGen: ExitProcess deadlocks inside DLL detach under
Wine and freezes every thread, which defeats even daemon-thread watchdogs.
