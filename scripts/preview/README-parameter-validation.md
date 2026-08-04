# Turboism Automated Parameter Host Validation

This bundle runs a test-only Turboism plugin inside an exact Live2D Cubism Editor 5.2 or 5.3.02 installation. The plugin calls only the public Turboism SDK, performs the selected matrix, writes machine-readable results, restores changed values, and requests a normal Editor close.

The launcher always delegates to the installation's official `CubismEditor5.bat`. It does not replace Cubism's classpath, native path, startup flow, or licensing behavior.

## Preflight

```powershell
.\launch-cubism-parameter-validation.ps1 -ProbeOnly
.\launch-cubism-parameter-validation.ps1 -ProbeAgent
```

`-ProbeAgent` is an isolated JVM probe and does not claim real-host readiness.

## Automated run

Use a disposable copy of a model. Never pass the original model.

```powershell
.\run-parameter-validation.bat `
  -ProjectPath 'C:\TurboismValidation\fixture-copy.cmo3' `
  -ValidationMode matrix
```

Use an explicit installation when needed:

```powershell
.\run-parameter-validation.bat `
  -CubismRoot 'C:\Program Files\Live2D Cubism 5.3' `
  -ProjectPath 'C:\TurboismValidation\fixture-copy.cmo3' `
  -ValidationMode binding-matrix
```

## Detailed validation coverage

The automated modes below emit machine-readable evidence. The interactive window may still be used for targeted diagnosis or checks that are not yet represented by a semantic SDK assertion.


### Startup and binding

- [ ] Cubism starts normally.
- [ ] `logs\runtime\<UTC-date>\turboism-*.log` contains `Host adapter ... connected`, not `FAILED`.
- [ ] No `Turboism parameter hook disabled safely` appears in the console log.
- [ ] The validation window lists active-model parameters.
- [ ] Selecting a parameter displays ID, name, type, Blend Shape, Combined, Repeat, current value, range and default.
- [ ] The details follow the parameter selected in the test-window result list.
- [ ] **Follow Cubism selection** is visibly disabled and marked unavailable; the current production adapter does not yet expose parameter-palette selection.

### Query and filtering

- [ ] **CONTAINS** finds text in either parameter ID or display name, ignoring case.
- [ ] **EXACT_ID** returns only the exact case-sensitive ID.
- [ ] **EXACT_NAME** preserves parameters with duplicate display names.
- [ ] Type filters distinguish `NORMAL`, `BLEND_SHAPE` and `UNKNOWN`.
- [ ] Repeat and Combined filters distinguish `YES`, `NO` and `UNKNOWN`.
- [ ] Clearing filters restores stable model order.

### Turboism-originated write

1. Select a disposable parameter and note its current value.
2. Enter a finite float in **New finite float** and click **Set value**.

- [ ] Cubism parameter palette changes to the requested value (or the Editor-clamped value).
- [ ] Canvas/evaluated model reacts normally.
- [ ] Validation counters increase exactly once: `before +1`, `changed(on) +1`, `after +1`.
- [ ] One Undo restores the old value.
- [ ] One Redo restores the new value.

- [ ] **Set minimum**, **Set default**, and **Set maximum** use the displayed metadata values.
- [ ] `NaN`, infinity and malformed input are rejected without changing the parameter.
### Editor authoring-definition write

Use a disposable parameter. Change one field at a time first, then test a combined commit with **Apply definition**:

- ID
- Name
- Minimum / Default / Maximum
- Type (`NORMAL` / `BLEND_SHAPE`)
- Repeat

`Combined` remains visibly read-only because Cubism represents it as a paired parameter structure rather than a safe independent flag.

- [ ] Applying a valid definition updates Cubism's parameter palette and the validation list/details.
- [ ] Exactly one Cubism Undo restores the entire prior definition.
- [ ] Exactly one Redo restores the new definition.
- [ ] The current runtime parameter value is preserved across the definition commit.
- [ ] A duplicate or malformed ID is rejected without any partial metadata change.
- [ ] Blank name, malformed/non-finite numbers, `minimum >= maximum`, or default outside the range are rejected without mutation.
- [ ] Unsupported Repeat or Blend Shape changes fail safely according to Cubism limits/structural eligibility.
- [ ] After an ID change, the validation window follows the new ID and does not write through the stale old wrapper.
- [ ] Save the disposable model, close it, reopen it, and confirm the edited definition persists.

### Parameter folder label background (model UI facade)

Use a disposable folder in the parameter palette. Select its ID in **Group**, enter finite RGBA values (normally `0.0`–`1.0`), and click **Set label background**.

The write goes through the selected `ParameterGroup.ui()` as `NativeLabelColor.Custom(UiColor)`; the same facade reads back the semantic label color and the effective label color. `ParameterGroups` is used to list/select the folder ID.

- [ ] Cubism immediately shows the new custom folder background.
- [ ] The validation window reports the authoritative semantic background and effective background.
- [ ] The document becomes dirty.
- [ ] One Undo restores the prior label background; one Redo restores the custom background.
- [ ] Re-applying the identical RGBA value does not add another Undo step.
- [ ] Save, close, and reopen; the custom background persists.

### Automatic native-control-background modes

Set the probe mode with `-Dturboism.editorObjectValidation.mode=<mode>`. Each mode runs only its own validation (no editor-object/part matrix in parallel).

`native-control-background` — the label-background matrix. After a real active modeling model is available, the mode selects the first non-root parameter group, one non-`__RootPart__` Part, and one Deformer, then runs each matrix on the Cubism EDT through each object's `.ui()` facade:

- ParameterGroup: a fixed `Custom` RGBA different from the semantic before-state;
- Part: a `Preset` different from the before-state;
- Deformer: `Default` (if already Default, a different Preset is established first as `matrixBefore`, the Default matrix runs, then the original Default is restored; the report distinguishes `original`/`matrixBefore`/`finalRestored`).

Every matrix records before, requested, afterWrite, the same-value second write, one Undo (must return directly to before — the observable proof that the same-value second write added no Undo group; `check.singleUndoGroup` is the auditable field), Redo, and the second Undo/restored value, plus target/family/id, modelId, hostThread, semantic background, effective background (`unavailable` for UNDEFINED), per-item PASS/FAIL and the total status. Captured originals are restored in `finally` and re-read for confirmation; any restore failure fails the artifact. After restoration the mode closes the owning plugin scope and verifies the held model objects and their `.ui()` facades fail closed, then uses the existing peer-probe handshake (`state/editor-object-peer-request.txt` → `logs/editor-object-peer-scope-close.txt`) to prove the shared host stays usable (`phase=plugin-scope-close`).

`native-control-background-document-close` — holds a model object and its `.ui()` facade, closes the active document (Ctrl+W), and verifies the held model and facade read/write operations fail closed with no active modeling document. Only close-stale is verified; reopening is a separate persistence stage. Artifact: `logs/native-control-background-document-close.txt`.

`native-control-background-persist-write` / `...-persist-reopen` / `...-persist-final` — the three persistence stages: write requested label colors (ParameterGroup Custom, Part Preset, Deformer Preset) and save; after the operator reopens the document, verify the requesteds persisted, restore every original, and save again; after a final reopen, verify all restored originals persisted. Originals and requesteds live in `state/native-control-background-persist.properties`; each stage writes `logs/native-control-background-persist-{write,reopen,final}.txt` with machine-readable PASS/FAIL and the active `modelId`/`hostThread`.

The persistence stages require the task-scoped copied model path (Windows-JVM-readable, no spaces): `-Dturboism.validation.fixture=<path>`. The write and reopen stages record the fixture file mtime/size before Ctrl+S, then poll with short intervals up to a bounded deadline for an mtime/size change that stays stable across consecutive samples; only then do they report `saveConfirmed=true` with the before/after mtime and size. A timeout or a missing fixture is a FAIL — a PASS is never written without save confirmation.

Artifacts are `logs\native-control-background-*.txt` under the Turboism home. These modes intentionally do not claim dirty/Undo counts, visual palette/canvas refresh, save-dialog behavior, or real-host readiness; the exact-host trace (`logs/editor-object-runtime-trace.txt` with `-Dturboism.editorObjectValidation.trace=true`) and all host runs are verified by the parent.

### Default keyform lock

Use **Lock default** and **Unlock default** in the validation window.

- [ ] The displayed `Default keyform locked` state changes immediately.
- [ ] The document becomes dirty and the parameter palette/canvas remain responsive.
- [ ] One Undo restores the prior lock state; one Redo reapplies it.
- [ ] Clicking the button for the already-active state does not add another Undo step.
- [ ] Save, close, and reopen; the selected lock state persists.

### Unchanged write

1. Click **Set same**.

- [ ] `before +1` and `after +1`.
- [ ] `changed(on)` does **not** increase.
- [ ] No duplicate lifecycle callbacks appear.

### Cubism UI-originated write

1. Drag `ParamAngleX` in Cubism's parameter palette to a different value.

- [ ] Validation counters increase exactly once for the completed native operation.
- [ ] `on` reports the actual old/new values.
- [ ] No duplicate `on`/`after` pair occurs.
- [ ] Slider interaction remains responsive without visible stutter.

### Persistence and stale references

1. Save the disposable model, close it, reopen it.

- [ ] Saved parameter value persists.
- [ ] Validation window reconnects to the reopened active model.
- [ ] Switching documents does not write the previous document.

### Plugin cleanup

1. Close Cubism normally.

- [ ] `logs\runtime\<UTC-date>\turboism-*.log` reports plugin unload/cleanup without failure.
- [ ] No stale validation window remains.

## Supported automated modes


```text
matrix
model-edit-level
wave1
statistics-read
binding-read
binding-matrix
parameter-menu-smoke
persist-write
persist-read
plugin-scope-close
document-close
native-control-background
native-control-background-document-close
native-control-background-persist-write
native-control-background-persist-reopen
native-control-background-persist-final
```

`model-edit-level` runs the model edit-level read/write/read/restore check.

`wave1` batches `model-edit-level` and `parameter-menu-smoke` in that order in one host session, reusing each check's existing artifact and the shared terminal summary.

The launcher waits for `state\host-validation-result.properties`, verifies its terminal status, waits for the official launcher process to exit, and fails if cleanup requires a forced process-tree stop.

## Evidence

Primary evidence:

```text
state\host-validation-result.properties
logs\*-validation.txt
logs\*-smoke.txt
logs\cubism-console.log
logs\runtime\<UTC-date>\turboism-*.log
state\plugin-load-report.json
state\preview-runtime-report.json
```

The terminal result records the run ID, mode, duration, artifact statuses, launcher exit code, cleanup status, Cubism JAR hash, official BAT hash, agent hash, and fixture-after hash.

A run is GREEN only when every selected assertion reports `PASS`, the original values are restored, the official launcher exits normally, and cleanup is not forced.

Screenshots are not routine evidence. Capture one only when validating a genuinely visual-only fact or diagnosing a failure that structured logs cannot explain.
