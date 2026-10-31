# First-party plugin UI lifecycle cleanup

Plugin lifecycle window inventory for [issue #54](https://github.com/turboism/Turboism/issues/54).
This is a source-path audit, not evidence of a leak in a running Cubism Editor.

`UiScheduler.runUiTeardown` is available from API **0.68.1**. Its timeout bounds
waiting for cleanup to **start**, not cleanup execution. A `false` result leaves
cleanup queued. Detach and capture the old window before dispatching cleanup;
never read a mutable current-window field inside a deferred cleanup. Release
lifecycle locks before waiting on the UI thread. Ordinary opens must use the
framework scheduler and reject stale enable generations; teardown must remain
available after the runtime fences ordinary work.

## Window owners

Paths below are relative to each plugin's `src/main/java` package. “Follow-up”
means the path needs a separate change; it does not claim a reproduced host bug.

| Plugin | Lifecycle path / disposition |
| --- | --- |
| acp | **Migrated:** `TurboismAcpPlugin.disable`, `shutdown`, scope `disposeUi`; detach window/controller under lock, close registrations then controller, dispatch captured window cleanup, then close settings. Open callbacks carry the enable generation. `AcpChatController` already drops queued updates after `close` and clears its pending UI queue. Component-local permission dialog and rendering dispatch remain component-owned. |
| mcp | **Migrated:** `McpPlugin.stop` from disable/shutdown/runtime scope; detach server/window and fence opens under lock, then close registrations, server, captured window outside the lock. Cache scheduler/logger for runtime-scope cleanup after base shutdown clears context. |
| command-palette | **Migrated:** `disposePalette` from disable/shutdown/scope; capture dialog while fencing opens. `onOpen` dispatches through the SDK scheduler; `togglePalette` rechecks the captured generation before creation/show. |
| perf-stats | **Migrated:** `deactivate` from disable/shutdown/scope; detach window while fencing opens/sampling, close sampling/status outside the lock, always attempt captured window teardown. In-flight CPU publication checks its sample generation before installing a registration; canceled publication cannot clear a newer status handle. |
| clipmask-viewer | **Previously migrated:** `ClipMaskViewerPlugin.disposeWindow` uses teardown; opens/refreshes check generation. **Follow-up:** cleanup currently reads `window.getAndSet(null)` inside the deferred callback. Review capture timing for disable/re-enable before delayed cleanup; this issue changes only the four owners above. |
| project-inspector | **Previously migrated:** `ProjectInspectorPlugin.disposeWindow` uses teardown; opens/results check generation. **Follow-up:** as in clipmask-viewer, the deferred cleanup reads the current window field rather than a captured old view. |
| parameter-sync | **Follow-up:** `ParameterSyncPlugin.closeActiveDialog` directly disposes the captured `ParameterSyncDialog` from disable; `handleOpenSync` uses SDK UI dispatch and an enabled check. Needs EDT teardown and review of cross-enable opens. |
| external-psd-edit | **Follow-up:** `ExternalPsdEditSessionManager` owns `exportProgressDialog`; `onExportSettled` detaches it and uses bare `invokeLater` off EDT. Disable/shutdown stop sessions. Review modal-progress open/stop ordering before migrating. |
| boundingbox-warp-mirror | **Follow-up:** `onOverlayClick` queues `openMirrorDialog` with bare Swing dispatch; modal `MirrorDirectionDialog` has local lifetime, no plugin-owned teardown handle or generation fence. |
| frame-animation-import | **Follow-up:** action queues creation of modal `FrameAnimationDialog`; enabled is checked before dispatch, not at queued creation. Dialog is local to the action, not closed by disable. |
| parameter-batch-transfer | **Follow-up:** action prepares a session then queues modal `BatchTransferDialog`; review stale prepared-session admission and give active dialog a lifecycle owner. |
| model-import-3d | **Partial equivalent guarantee:** `ModelImportPlugin` captures `epoch` for queued starts and asynchronous completions; `active(token)` prevents stale starts/continuations. **Follow-up:** modal chooser/import/viewer surfaces have local ownership; epoch invalidation alone is not synchronous window teardown. |
| webdav-backup | **Follow-up:** `WebDavSettingsDialog.open` queues a local modal settings dialog via Swing; disable shuts down workers/settings binding but owns no dialog cleanup handle. Review stale opens and in-flight connection-test UI completions. |

## Contributions and non-window plugins

These plugins do not allocate a plugin-owned top-level Swing window in their
production source. Their relevant cleanup is registration/host-surface ownership,
not replacement of component-local Swing calls with window teardown.

| Plugin | Relevant ownership / disposition |
| --- | --- |
| artmesh-mirror | Existing scope-owned action/menu; no top-level window. `Graphics2D.dispose` is unrelated. |
| atlas-bitmask-nesting | Existing host atlas planner contribution; no top-level window. |
| atlas-dalsoo-polygon | Existing planner/settings binding and worker shutdown; no top-level window. |
| atlas-folder-layout | Existing host atlas planner contribution; no top-level window. |
| atlas-maxrects-bssf | Existing host planner/settings binding; no top-level window. |
| cubism-tab-filter | Existing scope-owned host filter contributions; no top-level window. |
| deformer-batch-rename | Existing SDK-dispatched open and SDK selection-table dialog registration; disable captures/closes the dialog handle and invalidates generation. Host owns the actual window. |
| demo | Existing SDK UI contributions and disposable registrations; no top-level window. |
| geometric-mesh-layout | Existing host menu/action workflow; no top-level window. |
| history-panel | Existing panel/contribution registrations; `dismissPanel` stops polling and closes panel/action handles. No plugin-owned top-level window. |
| mesh-edit-mirror-axis-enhance | Existing host settings/hook workflow; no top-level window. |
| palette-label-style | Existing host component styling/restore registrations; no top-level window. |
| parameter | Existing parameter-panel contribution/registrations; no top-level window. |
| parameter-lock | Existing generation/epoch checks, canceled polling and host UI registrations; no top-level window. |
| physics-editor | Existing host physics workflow/settings contributions; no top-level window. |
| protected-export | Existing action/export workflow; no top-level window. |
| psd-clip-mask-import | Existing action/import workflow; no top-level window. |
| recent-preview | Existing host popup contribution and controller cleanup; queued capture is component work, no plugin-owned top-level window. |
| scene-palette-enhancer | Existing host context-menu/scene contribution; no top-level window. |
| selection-brush | Existing host brush/settings contributions; no top-level window. |
| texture-atlas-stats | Existing attached host label; queued text updates are component updates, no top-level window. |
| ui-theme | Existing theme service/settings contributions; no top-level window. |
| warp-deformer-alt-symmetry | Existing host action/hook workflow; no top-level window. |

## Rechecking the inventory

Start with the plugin descriptors to enumerate owners, then inspect lifecycle
entrypoints, runtime scope closers, and action handlers together:

```sh
rg --files plugins -g plugin.json
rg -n 'runUiTeardown|invokeAndWait|invokeLater|runOnUiThread|dispose\(' plugins/*/src/main/java
rg -n 'JFrame|JDialog|TurboismWindowFactory\.(frame|dialog)' plugins/*/src/main/java
```

The four migrated plugins' lifecycle tests use controlled queues with real Swing
windows under an isolated Xvfb. They cover stale queued opens across disable and
re-enable, worker/EDT cleanup, repeated disable/shutdown/scope close, teardown
after an ordinary-work fence, deferred cleanup and replay, and survival of a new
window after old cleanup. The command-palette latch regression also exercises
“EDT blocked → open queued → disable → EDT released” through the registered action.
SDK/runtime teardown and generation-guard tests cover the scheduler's real
wait-start timeout, interruption and exception propagation contracts.
