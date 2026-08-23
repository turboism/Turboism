# Turboism Clip Mask Viewer Host Validation

This bundle runs a test-only Turboism probe plugin inside an exact Live2D
Cubism Editor 5.2 or 5.3.02 installation. The probe calls only the public
Turboism SDK and re-executes the same read-only path the clipmask-viewer
plugin uses — `context.cubismClipMasks().collectClipMaskRecords()` and
`context.cubismRead().meshes()/selection()` — plus the same runtime-side
registration lifecycle (action / menu / collapsible-section / status
notification). All host object reads happen on the Swing EDT
(`SwingUtilities.invokeAndWait`, mirror `onHostThread` pattern).

## Bundle contents

- `turboism-agent.jar` — preview agent (from `./gradlew previewBundle`).
- `plugins/clipmask-viewer.jar` — the production clipmask-viewer plugin that
  owns the viewer window (from `./gradlew :plugins:clipmask-viewer:jar`); it
  is only loaded and enabled here to prove the production registration path.
- `plugins/clipmask-viewer-validation-exerciser.jar` — the task-local probe
  (from `bash validation/clipmask-viewer-host-probe/build.sh`). It is
  validation tooling only and is never part of the production preview bundle.
- `README.md`, `SHA256SUMS.txt`.

## Matrix executed by the probe

1. Waits for the trigger flag `state/dev.turboism.validation.clipmask-viewer/exerciser.flag`
   (240 s budget; timeout logs `CLIPMASK_VIEWER_PROBE_FLAG_TIMEOUT` and halts).
2. Waits for an active model with ArtMeshes (240 s budget, 60 s / 150 s
   warnings), records `modelId` / `meshCount`.
3. Data matrix (all on the EDT):
   - `collectClipMaskRecords()` twice — the two snapshots must be identical
     (idempotent / stable);
   - per-record checks on a bounded sample of up to 20 records: guid,
     displayName, and ordered mask guids non-blank; join consistency —
     `displayName` equals the mesh `name` when `meshes()` contains a mesh with
     `id == guid` (non-blank name), otherwise the first 8 characters of the
     guid (same rule as the runtime `MeshIndex`);
   - dedup: record count equals the distinct-guid count;
     `maskRelationships` = total `orderedMaskGuids` size;
   - mask-guid membership: every mask guid exists in the `meshes()` id set
     (same `ALL_ART_MESHES` source, semantic guarantee);
   - fixture-content adaptation: `recordCount == 0` with `meshCount > 0`
     marks `fixture.clipMasks` as `BLOCKED` (the fixture has no clip-mask
     relationships — mirror BLOCKED precedent), never FAIL; every other
     assertion still runs.
4. Selection bridge: `cubismRead().selection()` without exception;
   `selectedObjectIds` / `activeArtMeshId` non-null (empty allowed).
5. Registration lifecycle (runtime side, permission routed): register a
   probe action, contribute menu `Turboism/clipmask-viewer-validation`, and
   contribute collapsible section
   `clipmask-viewer.validation.probe` (target `turboism.panel.main`, order 200,
   collapsed, button-backed content); close all three; register and close
   again — no exception.
6. Best-effort `notifyStatus` (PASS), result file, summary log line
   `CLIPMASK_VIEWER_MATRIX_RESULT status=...`.

## Result file

`state/clipmask-viewer-validation-result.properties` (relative to the isolated
Turboism home) contains `schemaVersion`, `runId`, `hostVersion`, `fixtureName`,
model identity (`modelId`, `meshCount`), `recordCount`, `maskRelationships`,
one `assertion.<name>.expected / .actual / .status` triple per assertion, and
a terminal `status=PASS|FAIL|BLOCKED`.

- `PASS` — every executed assertion passed.
- `FAIL` — at least one assertion failed (runner reports it).
- `BLOCKED` — the fixture loaded no clip-mask relationships; nothing failed
  (runner reports the `status=BLOCKED` marker; review the evidence to decide
  whether to rerun with a mask-bearing fixture).

The probe sleeps ~3 s after the terminal state, then exits 0; the generic
runner judges the run from the result file and log markers.

## Running

The wrapper delegates to the generic runner, which performs exact JAR/BAT
identity checks, copies the fixture into a task-scoped CoW Proton prefix,
launches through the official `CubismEditor5.bat`, polls readiness, creates
the trigger, and cleans up its own process tree only. The wrapper passes
`-Dturboism.validation.hostVersion`, `-Dturboism.validation.fixtureName`, and
`-Dturboism.validation.runId` (the runner substitutes `{TASK_ID}`).

```bash
# package the bundle first
./gradlew previewBundle :plugins:clipmask-viewer:jar
bash validation/clipmask-viewer-host-probe/build.sh
bash scripts/preview/package-windows-clipmask-viewer-validation.sh

# automated exact-host run (parent/CI host access required)
bash scripts/preview/run-clipmask-viewer-host-validation.sh 5302 r1
bash scripts/preview/run-clipmask-viewer-host-validation.sh 5203 r1

# parse-only check; no SSH, no clone, no launch
bash scripts/preview/run-clipmask-viewer-host-validation.sh 5302 dry1 --dry-run

# or through Gradle
./gradlew validateClipMaskViewerHost5302
./gradlew validateClipMaskViewerHost5203
```

Never pass the original fixture; the runner copies it and verifies the source
hash before and after (`--require-fixture-unchanged`). If the default fixture
turns out to have no clip-mask relationships, override it through runner
options, e.g.
`bash scripts/preview/run-clipmask-viewer-host-validation.sh 5302 r2 \
  --fixture-remote "$TURBOISM_HOST_VALIDATION_FIXTURE_5302" \
  --fixture-sha256 029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`.
