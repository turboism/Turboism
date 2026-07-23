# Turboism Windows Parameter Validation

This directory is an isolated manual-test drop for **Live2D Cubism Editor 5.3.02**. It does not modify Cubism's installation or global launchers. The launcher copies the current Live2D roaming profile into `state\AppData` once, then redirects `APPDATA`/`LOCALAPPDATA` there so the test does not write back to the real profile.

## 1. Preflight

Open PowerShell in this directory:

```powershell
.\launch-cubism-parameter-validation.ps1 -ProbeOnly
.\launch-cubism-parameter-validation.ps1 -ProbeAgent
```

Both commands must exit successfully.

## 2. Start

Close every existing Cubism process, then double-click:

```text
run-parameter-validation.bat
```

If Cubism is installed elsewhere:

```powershell
.\launch-cubism-parameter-validation.ps1 -CubismRoot 'X:\path\Live2D Cubism 5.3.02'
```

Open a **disposable `.cmo3` model containing several parameter types**. A window named **Turboism Parameter Validation** should appear.

## 3. Validate

Record pass/fail for each item.

### Startup and binding

- [ ] Cubism starts normally.
- [ ] `logs\turboism.log` contains `Host adapter ... connected`, not `FAILED`.
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

- [ ] `logs\turboism.log` reports plugin unload/cleanup without failure.
- [ ] No stale validation window remains.

## 4. Return evidence

Send back:

```text
logs\cubism-console.log
logs\turboism.log
state\*.json
```

Also provide the checklist result and Cubism exact version shown at startup. Do not send the `.cmo3` model unless it is explicitly disposable and safe to share.
