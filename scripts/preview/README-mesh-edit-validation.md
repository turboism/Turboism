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

The `selection-brush` route first runs ordinary-modeling assertions, then the existing mesh acceptance in the same version-specific session. Its test-only companion reads native current point references and point selectors independently of production adapters. It checks the new top-toolbar position, ArtMesh/Warp/mixed ownership, release-only Robot strokes and modifier precedence, zero hits and cancellation, native selection-history/dirty baseline, Undo/Redo readback, zoom, pan, native-tool switching and mesh entry. Release and Undo/Redo additionally check the native bounding-box target points and projected corner extents. The oracle uses the native lazy bounding-rectangle reader without marking the cache dirty or rebuilding it explicitly; zero or one selected point falls back to object bounds, while two or more selected points define the box. A real native lasso single-point baseline independently verifies this fallback. Object-selection setup is explicitly labeled test-only native setup; it is not counted as a real point-selection gesture. Any missing Robot receipt or differing requested/delivered footprint fails the probe. Ordinary-mode results use the `modeling.*` prefix and are additional to mesh assertions.

The ordinary dirty-preservation assertion runs after brush strokes, Undo/Redo, navigation and native-tool switching, before entering mesh editing. A separate mesh enter/exit control without a custom activation records native dirty/history behavior: native exit can mark the document modified and replace ordinary selection history with a mesh-edit entry while preserving geometry. Custom activation must preserve the control's history. Its mesh transition must then append exactly one entry with matching native label, significance, action and decoded detail, preserve the previous entries and native Undo/Redo state, match geometry/dirty, disable the ordinary button in mesh mode and remove its overlay. The probe never resets the dirty flag or assumes mesh exit restores clean state. The second transition starts after the native control, so its dirty comparison is coexistence evidence; brush preservation comes from the earlier strict assertion.

Ordinary-tool exclusivity reads the visible modeling `toolGroup_arrow` directly as well as the native tool map: animation registration can overwrite the modeling Arrow map entry. Activation, release, Undo/Redo and Space pan must leave that button unselected. Robot clicks must restore Arrow/Lasso/Brush Selection and remove the custom selected state and overlay. The native lasso baseline uses a fixed 64-pixel margin and independently compares the requested polygon, the delivered polygon and exact selected point identities. Native input calibration checks two camera-input positions without changing selection/history; it never replaces real gestures. Family strokes normalize the fixture camera to one document unit per pixel, then test explicit zooms separately. Final pointer positioning precedes modifier key-down, and every press must carry the requested Shift/Ctrl bits. A missing modifier or changed hit footprint fails acceptance.

The probe selects an authored object through the Parts palette and observes native ArtMesh selection before invoking the mesh-entry toggle once. Project-palette image layers with the same display name are excluded. The brush follows the native lasso: source-to-calculated-form conversion in the displayed-form view, identity in source/image views, then camera projection. Expected vertices come independently from the displayed native form or the lasso's converter factory, rather than the brush projector. The report records view mode and maximum source-to-canvas displacement so an identity fixture cannot be mistaken for evidence of form conversion. It validates one tool and one slider, default 32 and bounds 8–128; REPLACE, Shift ADD, Ctrl REMOVE and Ctrl+Shift precedence; a four-step drag with a vertex between event positions and an excluded large-radius witness; exact press-time mode/radius locking and no selection writes before release; and window press/drag/release delivery and exact hits through `java.awt.Robot`. Lack of Robot delivery fails acceptance. The task-owned niri helper moves only the uniquely matched task editor to a floating window and waits for stable focused bounds, calibrates Robot coordinates from an actual mouse-move receipt and verifies a second component position before sending a stroke (unscaled output), and captures the active trail as `selection-brush-preview.png`. Its source, preparing Python interpreter, and installed niri binary are snapshotted or hash-pinned; substituted dependencies and ambiguous task windows are rejected. The preview receipt binds the run, window, dimensions, and PNG digest, and a uniform image fails acceptance; the report includes vertex count and per-event duration.

Before and after the strokes it compares authored geometry, the full native history snapshot, Undo/Redo availability and document dirty state. It also exercises empty strokes, Escape during a stroke, native-tool switching during a stroke, zoom and pan with subsequent exact hits, session/toolbar rebuilding, replacement controls and final overlay cleanup. Runtime tests separately cover projection failure after partial success, stale handles and replacement plugin generations. The current plugin manager applies changes on restart; normal host shutdown supplies real-host disposal evidence rather than a synthetic live-reload claim.

The real Robot stroke starts from a selection distinct from its expected hits. It samples the same path with short pointer moves and requires exact selection for both the requested path and the events actually delivered; a window-system pointer warp cannot silently change the expected result. Press, drag, and coordinate calibration must preserve the initial selection. The separate sparse synthetic stroke verifies capsule hits between event positions and press-time radius/mode locking. Overlay bounds must survive later native layout passes, not just initial activation.

The radius display must retain the last user setting through activation, Escape, native-tool switching and session rebuilding. The probe leaves it at 8 rather than resetting it to the default before those transitions. Runtime tests also rebuild synchronously from a slider callback, replace the provider, and verify that a new plugin generation starts from its own default. Reconciliation reads contribution-owned state without calling plugin code.

With the brush active, the probe also clicks the native mesh-confirmation icon through `java.awt.Robot`. An independent native-scene observer identifies the exact commit callback and current component bounds without invoking the action or using the production hit-region helper. Acceptance requires press/release receipts on the native canvas, no corresponding brush receipts, an ended mesh session and removal of the overlay. The native confirmation is a GL icon entity, so a Swing toolbar-button test or the mesh-exit command cannot substitute for this check.

Native confirmation switches the document's edit mode without calling the mesh editor's `endMode()`. The session hook observes successful document mode switches as well as mesh-editor start/end. Only a switch away from the current session's mode in that session's document revokes its lease and disposes its active tool; a same-mode update, another document or a failed setter leaves it active.

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

These four-vertex runs establish the recorded functional behavior and lifecycle evidence. Their original oracle projected raw staging coordinates, so they do not establish agreement between displayed vertices and brush hits under a changed form. Manual acceptance subsequently exposed that gap; the corrected oracle above must be used for new runs. Dense-mesh performance remains unmeasured.

## Ordinary-mode basic matrix and tool exclusivity — 2026-10-02

The final runs below used one production build and one probe. Every job finished `succeeded/PASS`, with `normalExit=true`, `identityVerified=true`, `fixtureUnchanged=true` and `cleanup=safe`.

| Cubism | Queue sequence | Job ID |
| --- | --- | --- |
| 5.2.03 | 2384 | `14aad661-6669-44e8-973c-2525083cdf50` |
| 5.3.02 | 2382 | `4ea46d84-6ef3-4528-89da-8d785dfe0607` |
| 5.3.03 | 2383 | `7acd79e7-1753-4d58-8956-d72c950fc036` |

Each run passed 460 distinct assertion names, with 1,551 assertion records including repeated readback and 72 checks of the visible modeling Arrow. They cover ordinary ArtMesh/Warp/mixed selection, real modifiers, cancellation, explicit zoom/pan, selection-only history, Undo/Redo, dirty/geometry preservation, toolbar border/focus and mutual exclusivity, native-tool switching, native mesh entry/exit comparison and the existing mesh confirmation/rebuild regression. All three run-bound mesh trail images were reviewed.

| Artifact | SHA-256 |
| --- | --- |
| Preview agent | `44d844f2aff582edda5ebd63bad00a6b8063eacd07c91ffe6867f5ac37511ce2` |
| Selection Brush | `07bdaacea8532d6cb3bce09bb5c14c22c0988f3cddbd58609ee8e073caa87cde` |
| Validation probe | `378180ff01ab253db7ee4885b18a5ee0708b907627072239eed283d1d660c028` |

This is the basic fixture matrix and the Select/Move exclusivity fix. It does not establish the full non-default parameter/nested-parent matrix, native menu/shortcut switching, complex document/owner/toolbar lifecycle scenarios or dense-model performance. The task's remaining acceptance work stays open. A separate pure-production manual window was provided without the probe; its readiness does not count as user acceptance.

## Ordinary-mode bounding-box refresh — 2026-10-02

The unchanged final probe first reproduced the stale-box bug with the archived pre-fix Agent: job `0604377d-faaf-43ec-b7af-b0d5e7af68c8` selected ArtMesh points 0 and 1, but the native box still held 0–3. The real native lasso single-point baseline passed and confirmed the native object-bounds fallback for zero/one selected point.

The fixed build passed the complete ordinary/mesh matrix on all exact versions:

| Cubism | Queue sequence | Job ID |
| --- | --- | --- |
| 5.2.03 | 2444 | `53b14f25-c5a3-455d-8894-3bfd7cb9f904` |
| 5.3.02 | 2445 | `2ab08841-f532-49dd-8712-1fc438b52b27` |
| 5.3.03 | 2446 | `3e749e23-fed9-40ce-8902-3292e05e3138` |

Every final verdict is `succeeded/PASS`, with normal exit, verified identity, unchanged fixture and safe cleanup. Each run passed 366 bounding-box assertions plus the existing ordinary/mesh regression, totaling 828 distinct assertion names and 1,919 records including repeated readback. The lazy native reader checks selected targets and corner extents without invalidating the cache in the oracle. All three bound mesh trail images were reviewed.

The shared Agent SHA is `1fd8f9d9d62725c6ec976cbc5cd86375409c7ba59491bc4dc0f51644bb0b79de`, Selection Brush SHA is `0191b57f6e3264a9e1f217ff5e3172e321fada86fdef255b208e937d123edac6`, and probe SHA is `3674d1b2de5c36ce780c71b314681703af0eb4541cdd53073d4e19c1e6fdfc7b`. Structured evidence is in `build/host-validation/modeling-selection-brush-bbox-summary.json`. Earlier input failures remain archived; these successful runs establish this build's behavior, not a diagnosed environment fix or completion of the remaining complex fixture/lifecycle matrix.

Manual acceptance passed on 2026-10-02 in the pure-production 5.2.03 window, job `3527751b-80a6-490d-bc34-09da4ff9bc11` (sequence 2448). It contained no validation probe and used the same Agent and Selection Brush hashes as the three successful runs above. The tester confirmed normal operation after the bounding-box fix and approved merging the feature. This records human acceptance separately from automated results and does not expand the automated coverage described above.
