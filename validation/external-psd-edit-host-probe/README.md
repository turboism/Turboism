# External PSD Edit Host Probe (025, test-only)

Drives the external PSD edit pipeline on the exact Cubism 5.3.02 host through the public
SDK only. Three phases, selected by `-Dturboism.validation.externalpsd.phase=`:

## pipeline (default)

1. Resolve an ArtMesh → model image → current raw image chain on the task fixture, and
   record how many ArtMeshes share the same current raw (`relation.sharedRawArtMeshes`).
2. `exportRawImagePsd` → `EXPORTED` + runtime-issued `PsdEditFile` + baseline revision.
3. Discover the runtime temporary allocation under `java.io.tmpdir` (`turboism-psd-*/external-edit.psd`).
4. `observeSaves`; assert the baseline revision is not replayed.
5. `openInDefaultApplication` — with the task-scoped `.psd → notepad.exe` association the
   pre-launch hook installs into the cloned prefix's `system.reg`, `OPENED` is expected;
   the golden prefix and real OS associations are never touched.
6. N save cycles (default 3; `-Dturboism.validation.externalpsd.cycles=`): structural
   layer-name mutation → stable revision → `replaceRawImagePsd` → `APPLIED` with consumed
   revision and post-native `after` observation. Cycle 2 saves by atomic rename, cycle 3
   overlaps two writes inside the debounce window.
7. Corrupted save → non-`APPLIED`, revision not consumed.
8. Native `undo(1)`/`redo(1)`; assert the applied raw identity is restored on redo.
9. Idle wait → no revision replay without a new save.
10. `stop()` → `STOPPED`; post-stop writes publish nothing.
11. Re-export the same raw image → fresh handle → clean stop (same-binding recovery).
12. Environment metrics (`env.*`: heap, processors, EDT dispatch latency).
13. Persist tail when `-Dturboism.validation.externalpsd.persist=1`: a task-pinned
    `UserFileGrantSource.fixedSelection` target (wired by
    `-Dturboism.preview.userFileFixedGrant={HOME}/persisted-document.cmo3`) grants a WRITE
    handle → `EditorFileCommand.SAVE_AS` executes the verified native `saveDocument` → a
    `ProjectFileLifecycleEvent.After` SAVE event must confirm it. The saved copy lives in
    the task home, outside the fixture copy — the runner's fixture-unchanged guarantee
    still holds. A post-edit re-export then records `persist.postEditSha256` /
    `persist.postEditImageSha256` for the reopen stage.

## reopen

Runs against a fixture copy produced by a persist run (`--fixture-local <saved>`), re-exports
the current raw image, and asserts the exported image-data section hash equals the recorded
`postEditImageSha256` — layer names are normalized by the host's import, so content bytes are
the durable marker. Hashes arrive via
`-Dturboism.validation.externalpsd.postEdit{,Image}Sha256=`.

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
table, the probe relocates a unique matching row by key and fresh bounds, up to three attempts.
Ambiguous, unkeyed, or unrelocatable rows are rejected without a right-click. Popup association
is limited to a new or successfully dismissed popup from that attempt. Renderer preparation is
diagnostic-only and runs after the right-click, or is skipped for a detached table.

## Build / offline test

```bash
./gradlew :sdk:jar
bash validation/external-psd-edit-host-probe/build.sh
bash validation/external-psd-edit-host-probe/test.sh
```

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
