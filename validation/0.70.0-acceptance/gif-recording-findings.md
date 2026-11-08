# 0.70.0 acceptance findings: GIF recording

Host: Live2D Cubism Editor 5.3.04, bundled Java 17.
Payload: this worktree, with 34 loaded plugins and zero load failures.
Isolated home: `build/acceptance/070/home`.
Latest committed source at diagnosis start: `77c15e9d0`.

## Fixed: unoptimized export required Gifsicle

`GifFrameEncoder.optimize` invoked Gifsicle even when the operator disabled optimization.
That option sets the size ceiling to `Long.MAX_VALUE`.

Commit `8eeb54594` uses in-process `rescale()` for that path.
It preserves requested dimensions, frame delays, and the NETSCAPE loop extension.

Regression coverage: `GifFrameEncoderTest.optimizationOffPublishesTheRecordingWithoutGifsicle`.
The test supplies a nonexistent executable and verifies successful output.

## Fixed: preview capability lacked its required permission

`cubism.preview.capture` requires `turboism.ui.viewport.read`.
The physics-editor manifest declared the capability but omitted the permission.
The runtime rejected `PreviewFrameService.focused()` on every poll.

The earlier real-host session logged 25,196 capability audit warnings.
Commit `8eeb54594` adds the permission.
The subsequent session logged zero capability audit denials.

`validatePluginMeta` now checks capability and permission consistency through `CapabilityAdmission.requiredPermissions()`.
Removing the permission makes validation fail with `capability-permission-mismatch`.

## Gifsicle verification

This machine has no Gifsicle executable on PATH.
Verification uses the downloaded Gifsicle 1.95 executable through `TURBOISM_TEST_GIFSICLE`.
The physics-editor suite passed 14 tests with zero skips.
The plugin supports an executable path and `-Dturboism.gifsicle.path`.

## Fixed: host EDT delays counted as plugin timeout failures

The real model is available on this machine.
The original desktop file contains 763,166,356 bytes.
Its SHA-256 is `83326ac519dd5e4cad9b39e4c7234410cc62bf0d1f5241364561d6906292b6da`.
The isolated `D:/TurboismValidation/0.70.0-cornflower/cornflower-070.cmo3` copy has the same hash.

An earlier session logged 9,261 `CIRCUIT_OPEN` rejections and 80 timeout events.
The relevant entries included `physics-editor action.handle` and `physics-editor ui.schedule`.

Commit `73aaa5610` routes UI scheduling through `RuntimeScheduler.dispatchUiBound()`.
Commit `92944d11d` applies that exemption to lightweight action dispatch.
Both paths retain admission, bounded worker pools, and shutdown checks.
Heavy, sidecar, and rejected work retain their existing policy.

The scheduler and action suites passed 73 tests.
The subsequent real-host session logged zero `CIRCUIT_OPEN` events.
It logged one unrelated `project-work-time plugin.refresh.low` timeout.

These results establish the timeout repair.
They do not establish visual GIF acceptance or prove the cause of every earlier timeout.
The earlier claim that model loading permanently disables the plugin exceeded the available evidence.

## Open: repeated F7 input saturates the action queue

The subsequent session logged 5,574 `action.handle REJECTED REJECTED` events.
Opening Physics Settings correlated with the flood; closing it stopped the observed flood.
That correlation alone does not prove a plugin-generated action loop.

A bounded live probe identified this action and caller chain:

```text
physics-editor.gif.record-pause
RuntimeActionRegistry.execute
RuntimeEditorUiActionRouter.invoke
RuntimeKeybindingService.invoke
RuntimeKeybindingService.dispatchKeyEvent
DefaultKeyboardFocusManager.preDispatchKeyEvent
```

Evidence: `build/acceptance/070/probe/bootstrap/trace.txt`.
The probe stores one latest trace, removes its transformer, and restores the original class after ten seconds.

Repeated Windows `GetAsyncKeyState(VK_F7)` checks returned `0x8001`.
The high bit indicates a pressed key.
Virtual-key and scan-code key-up injections did not clear that state.
A scan-code `SendInput` attempt returned one accepted event and error code zero.
The source of the persistent input remains unknown.
A physical F7 press and release must precede a clean replay.

A JFR sample also captured `GifRecordingController.command()` calling `refreshStrip()` through `updateControls()`.
Evidence: `build/acceptance/070/physics.jfr` and `build/acceptance/070/jfr-stacks.txt`.
This proves that commands reach a controller with a preview handle.
It does not prove that its controls remain visible.

Do not weaken queue limits or add further timeout exemptions to conceal repeated input.
Do not classify the input source as an internal plugin loop without additional evidence.

## Confirmed: the constructor hook registers preview surfaces

A live inspection of `NativePhysicsEditorBridge.INSTALLED` found an installed coordinator and two registry entries.
Both entries reference `ViewerUI_Panel$DirectModelDrawingPanel` objects and `BorderLayout` components.
Evidence: `build/acceptance/070/probe/registry/registry.txt`.

This disproves the earlier claim that `onPreviewConstructed()` never runs.
The earlier claim of a transformer timing race had no direct supporting evidence.

## Diagnostic limitations and remaining acceptance

Early attach attempts failed because unsigned default-package probe classes conflicted with signed host classes.
A later probe could not resolve its helper from the runtime's bootstrap classloader.
The diagnostic restored the action registry bytecode through retransformation.
The working bounded probe uses only bootstrap-visible Java calls in the instrumented method.

Those failed probes contaminate this session's later error counts and responsiveness measurements.
A fresh, task-owned session must supply final acceptance evidence.
Do not modify or terminate unrelated Cubism sessions.

The current visual acceptance remains incomplete.
The next replay must distinguish native host controls from the plugin's recording strip.
Verify the strip labels and the F7, F8, and F9 actions individually.
Verify optimized and unoptimized exports, frame timing, dimensions, and recovery after model loading.
## User-directed deferral

The user explicitly requested skipping the repeated-input investigation and all GIF acceptance for this release-preparation pass.
Keep these items deferred and unverified, not passed or fixed.
Continue the remaining acceptance checklist without requiring physical F7 inspection.
This deferral removes the GIF checks from the current acceptance scope; it does not establish GIF readiness.
Preserve this limitation in the release-preparation PR.
All other release gates remain in scope.
