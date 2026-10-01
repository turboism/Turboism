# Mesh Edit Host Validation

Build the bundle with `bash scripts/preview/package-mesh-edit-validation.sh`. It contains the Preview agent, the official Selection Brush and mirror-axis plugins, a test-only probe, isolated home configuration, and artifact hashes. The probe is never shipped as a production plugin.

Machine-specific Proton, golden-prefix and fixture paths belong in the ignored `.env`, using the variables in `.env.example`. All host runs use the current-UID managed queue documented in [host validation scheduling](README-host-validation-scheduling.md).

## Routes

The existing `matrix` and `persistence` modes retain the 5.2.03 route. Selection Brush requires an explicit version:

```bash
python3 scripts/preview/host_validation.py plan selection-brush:5203 selection-brush:5302 selection-brush:5303
python3 scripts/preview/host_validation.py prepare selection-brush:5203 --run-label brush-acceptance
```

Submit the returned prepared ID, then await the complete job verdict before advancing to 5302 and 5303. `plan` and wrapper `--dry-run` inspect routing without launching a host. The direct wrapper accepts `selection-brush <5203|5302|5303> [run-label] [runner-options...]` and uses the same queue.

## Probe evidence

The probe selects an authored object through the Parts palette and observes native ArtMesh selection before invoking the mesh-entry toggle once. Project-palette image layers with the same display name are excluded. The brush reads native editable-mesh GL positions and projects them through the current modeling camera. It validates one tool and one slider, default 32 and bounds 8–128; REPLACE, Shift ADD, Ctrl REMOVE and Ctrl+Shift precedence; a four-step drag with a vertex between event positions and an excluded large-radius witness; exact press-time mode/radius locking and no selection writes before release; and window press/drag/release delivery and exact hits through `java.awt.Robot`. Lack of Robot delivery fails acceptance. The task-owned niri helper moves only the uniquely matched task editor to a floating window and waits for stable focused bounds, calibrates Robot coordinates from an actual mouse-move receipt and verifies a second component position before sending a stroke (unscaled output), and captures the active trail as `selection-brush-preview.png`. Its source, preparing Python interpreter, and installed niri binary are snapshotted or hash-pinned; substituted dependencies and ambiguous task windows are rejected. The preview receipt binds the run, window, dimensions, and PNG digest, and a uniform image fails acceptance; the report includes vertex count and per-event duration.

Before and after the strokes it compares authored geometry, the full native history snapshot, Undo/Redo availability and document dirty state. It also exercises empty strokes, Escape during a stroke, native-tool switching during a stroke, zoom and pan with subsequent exact hits, session/toolbar rebuilding, replacement controls and final overlay cleanup. Runtime tests separately cover projection failure after partial success, stale handles and replacement plugin generations. The current plugin manager applies changes on restart; normal host shutdown supplies real-host disposal evidence rather than a synthetic live-reload claim.

The real Robot stroke starts from a selection distinct from its expected hits. It samples the same path with short pointer moves and requires exact selection for both the requested path and the events actually delivered; a window-system pointer warp cannot silently change the expected result. Press, drag, and coordinate calibration must preserve the initial selection. The separate sparse synthetic stroke verifies capsule hits between event positions and press-time radius/mode locking. Overlay bounds must survive later native layout passes, not just initial activation.

The radius display must retain the last user setting through activation, Escape, native-tool switching and session rebuilding. The probe leaves it at 8 rather than resetting it to the default before those transitions. Runtime tests also rebuild synchronously from a slider callback, replace the provider, and verify that a new plugin generation starts from its own default. Reconciliation reads contribution-owned state without calling plugin code.

The Tool Details palette can share a dock with Turboism. The probe opens the native tool palette if its controls are hidden and scrolls each control into view before operating it. Button and slider checks require showing components with a nonempty visible rectangle before activation, after activation and after session rebuilding; a hidden Swing component cannot satisfy this evidence. The native peer used for tool switching must also be visible.

The result is `state/dev.turboism.validation.mesh-edit/mesh-edit-host-validation.properties`, with individual assertions and `status=PASS|FAIL`. After publishing the result, the probe uses the shared version-routed normal-close helper. It identifies the task fixture's window and handles only that window's explicit save confirmation by choosing its unambiguous discard action; it never saves a fixture or bypasses an unknown dialog. The close event is queued so a synchronous modal save loop cannot block its confirmation observer. There is no fixed delayed-exit timer. Runner re-reads terminal evidence after observing process death to cover publication races, then still requires official/native exit, exact identity, unchanged fixture and task-owned cleanup evidence.

A probe PASS alone does not complete acceptance. Record the final queue verdict and retained evidence for every version, and review the trail image. Interpret event timings with the reported fixture vertex count; a small fixture does not establish dense-mesh performance. Neither local tests nor historical queue metadata replace a fresh host run.

## Accepted bundle — 2026-10-01

The final visible-control runs below all finished `succeeded`: 68 probe assertions per version passed, with `normalExit=true`, `identityVerified=true`, `fixtureUnchanged=true` and `cleanup=safe`. The three run-bound niri trail images were reviewed. The final `checkCompletedCommit` gate also passed.

| Cubism | Queue sequence | Job ID | Vertices | Maximum synthetic input event |
| --- | --- | --- | --- | --- |
| 5.2.03 | 2152 | `e6bd9adb-71a6-43b0-8424-f28c16b4880b` | 4 | 5.8421 ms |
| 5.3.02 | 2154 | `579addfc-fb00-49cf-aa03-060915b24fb2` | 4 | 4.8749 ms |
| 5.3.03 | 2155 | `9cdc5bff-02df-4a06-a743-5792599aa07c` | 4 | 3.9453 ms |

All three prepared snapshots used these SHA-256 hashes:

| Artifact | SHA-256 |
| --- | --- |
| Preview agent | `00197a15c50d2d21953a6b66468e935f78163ff394965427e967e9888a9095aa` |
| Selection Brush | `2cba93ab7f34c90b1a36695332972acb41d83d8d7a369812bc6519b4081de00c` |
| Validation probe | `1509e0eed792732b2869bade9c86d97c04d0069f1350b25406ac17ddbf13b520` |

These four-vertex runs establish functional behavior and complete lifecycle evidence. Dense-mesh performance remains unmeasured.
