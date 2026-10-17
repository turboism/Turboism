# Parameter Lock Host Validation Probe

Task-local exerciser for exact-host acceptance of the production parameter lock plugin (`dev.turboism.plugin.parameter-lock`).

The probe compiles solely against the public Turboism SDK (`sdk-*.jar`) and executes within the exact Cubism host environment alongside the production plugin JAR. It drives the production plugin's real UI elements via parameter row and group row leading action icon slots and verifies parameter mutation prevention via public SDK and host lifecycle hooks.

---

## 1. Directory Structure

```text
validation/parameter-lock-host-probe/
├── build.sh
├── README.md
└── src/
    ├── META-INF/
    │   └── turboism/
    │       ├── i18n/
    │       │   └── messages.properties
    │       └── plugin.json
    └── dev/turboism/validation/parameterlock/
        └── ParameterLockHostValidationPlugin.java
```

---

## 2. Compilation & Manifest Preflight Check

Compile the probe and execute the lightweight manifest preflight check using:

```bash
./validation/parameter-lock-host-probe/build.sh
```

**Artifact:** `build/parameter-lock-host-validation-probe.jar`

Prerequisites:
- Built SDK JAR under `build/worktree/<worktree_id>/sdk/libs/sdk-*.jar`
- Java 17+ `javac` and `jar`
- Python 3 (for fast probe manifest sanity check during build; the root agent separately executes complete runtime schema CLI verification)

The build script executes a lightweight preflight sanity check on the probe's `META-INF/turboism/plugin.json`:
- Schema version 3 structure and format (`turboism.plugin.meta`);
- Entrypoint class verification (`dev.turboism.validation.parameterlock.ParameterLockHostValidationPlugin.class` present in JAR);
- Permissions and capabilities declaration;
- Embedded UI and Cubism requirements.

---

## 3. Host Launch & Execution Contract

The probe is invoked through `scripts/preview/run-parameter-lock-host-validation.sh`:

```bash
./scripts/preview/run-parameter-lock-host-validation.sh <version> [mode] [run-label]
```

### Lifecycle Flow
1. **Startup & Readiness:**
   - The probe initializes and starts a daemon exerciser thread.
   - Emits log marker: `PARAMETER_LOCK_PROBE_READY stateDir=<path>`
   - The host waits for both `PARAMETER_LOCK_PROBE_READY` and `Plugin load complete`.
2. **Trigger:**
   - The queue/runner creates the trigger flag at:
     `state/dev.turboism.validation.parameter-lock/exerciser.flag`
3. **Execution Modes (`-Dturboism.parameterLockValidation.mode`):**
   - **`matrix` (Default):**
     1. **Target Row Discovery & Dynamic Resolution:** Finds first visible parameter row strictly matching active model source identity (`rowSource == rawSdkSource`). Dynamically re-resolves live row widgets and native `JLabel` before every interaction.
     2. **Leading Action MARKER & Native Icon Verification:** Verifies client property `turboism.paletteRow.leadingAction` on the native `JLabel` starts as `UNLOCKED`. The lock occupies a separate leading border slot independently of native name alignment; clicks target that slot directly. The border reserves space excluded from the name layout, while the icon wrapper retains the native folder icon. Reflectively inspects the binding wrapper's `normal` and `disabled` `Icon` fields, asserting that `normal` is exactly 16x16 logical. Inspects the `normal` icon's runtime origin class; if it matches `CubismNativeIconResolver` / `CachedIcon`, records provenance as `VERIFIED_CUBISM_NATIVE`; if unknown/foreign, explicitly records boundary and does not claim native identity (`rowLeadingActionVerified`).
     3. **Unlocked Writable:** Tests SDK `param.setValue(testVal)` on EDT when unlocked and verifies value changes. Restores original value (`unlockedWritable`).
     4. **Positive Control (Native Controller Write):** Submits native `s.a(source, val)` when unlocked, confirming host model parameter value changes. Restores original value (`nativeControllerWriteUnlockedAccepted`).
     5. **Parameter Selection & Diagnostics:** Dispatches `SingleParameterRowWidget.setSelectParameter()` on EDT, collects Bridge and selection diagnostics, and asserts `context.cubism().runtime().selection()` reflects the target parameter.
     6. **Row Lock Click:** Dispatches real `MouseEvent` hitting the leading 16px icon slot on the native `JLabel` (without directly invoking plugin callbacks), and awaits MARKER transitioning to `LOCKED` (`rowLockClicked`).
     7. **Locked Write Rejection:** Attempts SDK `param.setValue(testVal)` on EDT and native `s.a(source, val)`. Verifies that both are intercepted and value remains original (`lockedRejectsWrite`, `nativeControllerWriteLockedRejected`).
     8. **Native Control Inspection:** Locates the native parameter row component and inspects `CSlidableFloat` (`nativeControlInspect`).
     9. **Row Unlock Click:** Dispatches real `MouseEvent` hitting the leading 16px icon slot on the native `JLabel`, and awaits MARKER transitioning to `UNLOCKED` (`rowUnlockClicked`).
     10. **Unlocked Restore:** Verifies that both SDK `setValue` and native `s.a(source, val)` writes succeed again (`unlockedRestoredWritable`, `nativeControllerWriteUnlockedRestored`).
     11. **Parameter Group Recursion & MIXED State:**
         - Traverses actual parameter groups from fixture model (`model.parameterGroups().all()`), prioritizing nested candidate groups (groups with child folders) over flat groups.
         - Correlates with coordinator binding catalog (`NativeParameterAppearanceBridge -> coordinator.parameterControlBindings()`) to find visible native group `JLabel` while strictly verifying actual group identity against the model.
         - Dynamically re-resolves the group `JLabel` immediately before every click and marker read (preventing stale label caching across native row rebuilds).
         - If an eligible group with $\ge 2$ descendants and a visible parameter row is found:
           - Locks one descendant via row leading action -> verifies group marker transitions to `MIXED` (`groupMixedStateVerified`);
           - Clicks group leading action in `MIXED` state -> verifies ALL descendant parameter IDs reject writes and group becomes `LOCKED` (`groupMixedClickLocksAll`);
           - Clicks group leading action in `LOCKED` state -> verifies ALL descendant parameter IDs accept writes (restored) and group becomes `UNLOCKED` (`groupUnlockClickUnlocksAll`, `groupRecursionVerified`).
         - If the tested candidate group is nested (has child groups) and all descendant writes pass, reports `nestedGroupRecursion.status=PASS` (`nestedGroupRecursionVerified`).
         - Otherwise (if candidate is single-tier or no group was testable), explicitly reports `nestedGroupRecursion.status=NOT_TESTED` with the exact boundary explained.
     12. **Explicit Boundaries:** Reports `sliderDrag=NOT_TESTED`, `textInput=NOT_TESTED`, `multiModelIsolation=NOT_TESTED`, and `nestedGroupRecursion=NOT_TESTED` (when tested group is single-tier or nested groups are unexercised).
   - **`persist-write`:**
     - Dispatches row leading action lock click, verifies MARKER `LOCKED` and write rejection (`persistWriteLockActive`).
     - Waits for production plugin to write `<TurboismHome>/state/dev.turboism.plugin.parameter-lock/locks/<sha256(guid)>.json`.
     - Copies snapshot to `state/parameter-lock-snapshot.json` for transfer across test runs (`persistWriteSaved`).
   - **`persist-read`:**
     - **Requires** the runner to have pre-seeded `<TurboismHome>/state/dev.turboism.plugin.parameter-lock/locks/<sha256(guid)>.json` before host launch; performs no runtime copying.
     - Verifies parameter row MARKER is **already locked** upon startup without probe clicking anything (`persistReadRestoredLocked`).
     - Unlocks via row leading action click and verifies parameter becomes writable (`persistReadUnlocked`).
4. **Result Reporting:**
   - Written to: `state/parameter-lock-host-result.properties` (and mirrored to `state/dev.turboism.validation.parameter-lock/parameter-lock-host-result.properties`).
   - Format:
     ```properties
     status=PASS
     mode=matrix
     checks=rowLeadingActionVerified,unlockedWritable,nativeControllerWriteUnlockedAccepted,rowLockClicked,lockedRejectsWrite,nativeControllerWriteLockedRejected,nativeControlInspect,rowUnlockClicked,unlockedRestoredWritable,nativeControllerWriteUnlockedRestored,groupMixedStateVerified,groupMixedClickLocksAll,groupUnlockClickUnlocksAll,groupRecursionVerified
     modelId=...
     parameterId=...
     durationMillis=...
     ```
5. **Termination:**
   - Flushes logs and exits cleanly via `Runtime.getRuntime().exit(pass ? 0 : 2)`.

---

## 4. Multi-Session Runner Artifacts

When validating persistence across isolated runner invocations (where each run starts with a clean `TURBOISM_HOME`):
1. Run `persist-write`:
   Produces `state/parameter-lock-snapshot.json`.
2. Runner / Operator pre-seeds the JSON into the next session's `<TurboismHome>/state/dev.turboism.plugin.parameter-lock/locks/<sha256(guid)>.json` prior to host launch; the probe strictly requires this file to exist and performs no runtime copying.
3. Run `persist-read`:
   Validates automatic preloading, restored row MARKER `LOCKED`, and write rejection on fresh host startup.

---

## 5. Scope Boundaries and Limitations

1. **Interactive Canvas Pixel Drag:**
   Full manual mouse-dragging of the parameter slider knob on a display requires a physical display/Robot coordinate session. The probe inspects the native Swing row component and verifies that write attempts via the host lifecycle pipeline are intercepted and rejected by `beforeSetParameterValue`.
2. **Keystroke Text Input:**
   Direct textfield keystroke editing requires a focused caret Robot session; native rejection is verified via host lifecycle interception.
3. **Multi-Model Concurrency:**
   Single-model test fixtures exercise single-document operation. Multi-model dynamic switching and cross-model isolation is explicitly reported as `NOT_TESTED` because static storage path separation cannot prove runtime switching without a multi-document session.
4. **Fixture Parameter Groups & Nested Hierarchies:**
   Matrix mode requires actual group lock/unlock and MIXED-state checks to run. It prioritizes existing model groups. If the fixture only has a visible group with one child, the probe temporarily moves one existing parameter into that group through the public SDK, records the original membership, and restores it in `finally` after the checks. These changes exist only in the runner's isolated model session and are never saved. Missing testable groups or failed restoration fail the run. A flat tested group still reports `nestedGroupRecursion.status=NOT_TESTED`; the probe does not claim live nested-group coverage from a flat fixture.
5. **Dynamic Row Resolution Across Native Rebuilds:**
   Because native parameter value writes and selection updates cause the Cubism host to discard and rebuild native row widgets, the probe never holds cached `JLabel` references across writes or clicks. Every interaction and marker read dynamically re-resolves the fresh visible label by model source identity (for parameters) or coordinator binding catalog (for groups).
6. **Fixture Preservation:**
   The probe mutates parameter values only in-memory and restores them before exit; it never saves or dirties the fixture file on disk, fulfilling `--require-fixture-unchanged`.


### Disabled native editors

The current matrix additionally requires `lockedEditorsDisabledAndShielded` and `unlockedEditorsEnabledAndUnshielded`. It resolves the current native row after each state change, asserts actual Swing enabled states for `paintComp` and `getSliderFloat().getJComponent()`, checks runtime disable markers, and verifies the native pointer hit test resolves to the editing shield only while locked. `persist-read` requires `persistReadEditorsDisabled` before its first click. These checks verify native state and pointer routing; they do not claim physical Robot keystroke/drag coverage.
