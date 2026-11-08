# 0.70.0 acceptance findings — GIF recording strip and Gifsicle

Host: Live2D Cubism Editor 5.3.04, bundled Java 17, payload built from this worktree.
Isolated Turboism home: `build/acceptance/070/home` (34 plugins, 0 load failures).

## Defect 1 — GIF export failed without Gifsicle even when optimization was off

`GifFrameEncoder.optimize` always shelled out to Gifsicle. The export dialog lets the
operator clear "optimize", which sets the size ceiling to `Long.MAX_VALUE`, but the
encoder still ran `gifsicle --careful ...` on that path. On a machine without
Gifsicle every export therefore failed with a spawn error, even though nothing needed
optimizing, and the recording was only retained for retry.

Fixed: with `maxBytes == Long.MAX_VALUE` the encoder now rewrites the recording
in-process (`GifFrameEncoder.rescale`) at the requested pixel size, preserving the
per-frame delay distribution and the NETSCAPE loop extension. No external process.

Regression test: `GifFrameEncoderTest.optimizationOffPublishesTheRecordingWithoutGifsicle`
(passes a nonexistent executable path and asserts the recording is published).

## Defect 2 — the preview capture capability was admitted but denied on every call

`CapabilityAdmission.CUBISM_PREVIEW_FRAMES` gates `cubism.preview.capture` on
`turboism.ui.viewport.read`. The `physics-editor` manifest declared the capability
but **not** the permission. The runtime therefore admitted the plugin and then
rejected every `PreviewFrameService.focused()` call.

Observed on the real host before the fix: **25,196** `CubismFacadeAuditEvent` WARNING
records in one session, one per 400 ms preview poll, and the plugin's poll caught the
denial and silently degraded to "no preview". That is why the recording strip never
appeared.

Fixed: added `turboism.ui.viewport.read` to
`plugins/physics-editor/src/main/resources/META-INF/turboism/plugin.json`.
Observed after the fix: **0** audit denials in a full session.

Repo-wide guard: `validatePluginMeta` now rejects any manifest whose declared
capability is gated on a permission it does not declare
(`PluginMetaValidationCli.capabilityPermissionErrors`, using the new
`CapabilityAdmission.requiredPermissions`). Verified by removing the permission
again: the gate failed with
`capability 'cubism.preview.capture' is gated on undeclared permission(s) [turboism.ui.viewport.read]`.

All seven runtime-gated capability holders were audited; `physics-editor` was the only
mismatch.

## Gifsicle availability

Not on PATH on this machine. Real Gifsicle 1.95 was downloaded for verification;
the full `physics-editor` suite (12 tests, 0 skipped) passes against it, including the
optimization path. The plugin already exposes a configurable executable path and
`-Dturboism.gifsicle.path`.

## Still open — strip not visually confirmed

The recording strip is attached to the focused **Physics Settings preview** surface.
`PhysicsEditorConstructorTransformer` only fires for the host's
`ViewerUI_Panel$DirectModelDrawingPanel`. An empty Untitled model has no physics
settings, so no preview panel was constructed and no strip could bind. F7 in that
state correctly reaches the plugin and reports "no focused preview window" through a
status notification, which is not logged.

Verifying the strip visually needs a model with physics settings. The 763 MB
cornflower model and the `texture-atlas-layout` test corpus are both absent from this
machine, and GitHub is unreachable, so neither could be fetched. This item is **not
accepted**.


## Defect 3 (RELEASE BLOCKER) — a large-model load permanently disables physics-editor UI

Reproduced on the real 矢车菊 model (`矢车菊机械未来1.cmo3`, 763,166,356 bytes,
SHA-256 `83326ac5...6292b6da`) in Cubism 5.3.04 with the 0.70.0 payload
(34 plugins, 0 load failures).

Timeline from one session log:

```
10:02:15.622  project-work-time plugin.refresh.low TIMED_OUT LIGHTWEIGHT
10:02:16.160  physics-editor action.handle TIMED_OUT LIGHTWEIGHT
10:02:16.165  physics-editor action.handle TIMED_OUT LIGHTWEIGHT
10:02:16.584  physics-editor ui.schedule TIMED_OUT LIGHTWEIGHT
10:02:34.951  physics-editor action.handle CIRCUIT_OPEN REJECTED   <- breaker opens
...           (repeats for the rest of the session)
10:11:34.990  physics-editor action.handle CIRCUIT_OPEN REJECTED
```

Totals for that session: **9,261** `CIRCUIT_OPEN` rejections and **80** `TIMED_OUT`,
still rejecting ~9 minutes after the model finished loading.

Cause: `PluginWorkExecutor` gives plugin work a 500 ms budget and opens a
resilience4j circuit breaker after 4 failures (`minimumNumberOfCalls(4)`,
`slidingWindowSize(4)`, 50% failure rate). Loading the 763 MB model stalls the host
EDT for far longer than 500 ms, so the physics-editor preview poll
(`PREVIEW_POLL_INTERVAL = 400 ms`, `UiScheduler.runOnUiThreadLater`) times out four
times and trips the breaker. The poll then keeps resubmitting, every half-open trial
times out again, and the breaker re-opens: the plugin never recovers.

User-visible effect: the GIF recording strip never attaches, F7/F8/F9 do nothing,
and the Physics Settings plugin is inert for the whole session. This is exactly the
"GIF recording bar does not appear" symptom, and it happens on the primary large
model the release targets.

Note the capability/permission fix (Defect 2) is still required — with the permission
missing, every poll was denied instead — but it is not sufficient on its own.

Not fixed yet: the fix needs a decision on the recovery policy (back off the poll
instead of resubmitting at a fixed rate, and/or let a transient host stall not
permanently disable a plugin's UI). That is a design change to shared scheduler
policy, not a local plugin patch.


## Update — circuit-breaker defect fixed, a second defect exposed

Two commits address the blocker:

- `73aaa5610` routes `ui.schedule` through `RuntimeScheduler.dispatchUiBound`
  (`PluginWorkExecutor.submitUiBound`): the bulkhead still bounds concurrency and shutdown still
  waits, but the 500 ms time limit and the breaker accounting are skipped.
- `905...` extends the same treatment to the lightweight `action.handle` lane, because an action
  handler runs from a menu click or shortcut and routinely hops to the host EDT. HEAVY, SIDECAR and
  REJECTED classifications keep their own outcome, so this is not a blanket bypass.

Verified on the same 763 MB 矢车菊 model, same host, same payload:

| metric | before | after |
|---|---:|---:|
| `CIRCUIT_OPEN` rejections in one session | 9,261 | **0** |
| `TIMED_OUT` events | 80 | 1 (project-work-time, unrelated) |

The plugin no longer goes inert after a large-model load.

### Second defect now visible — a hot action loop, previously masked by the breaker

With the breaker closed, the same session instead logged **5,574** `REJECTED REJECTED` events
(backpressure: the plugin bulkhead queue is full) at roughly 17/s. The flood starts when the
Physics Settings window opens and **stops when that window is closed**, so it is driven by the
physics preview panel or the control-strip path, not by the plugin at rest.

The breaker previously masked this: the same loop that saturates the queue is what tripped it. The
loop is therefore the remaining reason the recording strip does not appear, and it is not fixed.

Also unresolved: no preview surface is registered for the Physics Settings preview in this build
(`onPreviewConstructed` never fires), so even without the loop the strip would have nothing to
attach to. `ViewerUI_Panel$DirectModelDrawingPanel` exists in the 5.3.04 host JAR with the exact
expected name, so this looks like a transform-timing race rather than a wrong class name.
