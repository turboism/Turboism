# Native character candidate — Cubism Editor 5.3.04

The candidate imports editable ArtMeshes on the main canvas. The default-appearance fixture preserves original geometry and appends Blender-evaluated masked body and fitted footwear surfaces. It is a generated fixture, not an automatic repair of arbitrary GLB files.

## Fixed inputs

- Host JAR SHA-256: `c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d`.
- Agent SHA-256: `b2cdf3979d88acc13fa72923601613f46a565f322627d7aa3889d78a98a40bc9`.
- Production plugin SHA-256: `95394f0f713dc67f7ff5449fbec4bfeede1a1f7a5cd0ea1e721fe766d6842b7c`.
- Probe SHA-256: `42e2fe6c74f5ee0b2c81ddce4ffaa9bc7217906cfd136930501c31f050f97b52`.
- Generated GLB: `<asset-work-dir>/native-default-appearance.glb`.
- GLB SHA-256: `9c3d11b550cc2d71a89724a596ff0c706e35c81462b186947abcf0a4ab9799d7`.
- GLB provenance: `<asset-work-dir>/default_appearance_provenance.json`.
- Blender 3.6.23 runs with automatic scripts disabled. Original GLB and Blend remain unchanged.

## Native import and save

Queue job `9eff436a-49a9-42de-896e-d8b77baffabe`, run `queue-e568f6d8f03c4eed953ea4c7d69a2ee9`, completed with supervisor PASS and safe cleanup. Launch used the reviewed official BAT, isolated home and task-scoped prefix.

Evidence root:
`<host-validation-root>/native-character/5304-native-r7/queue-e568f6d8f03c4eed953ea4c7d69a2ee9/`.

The probe invokes the production import menu and real source chooser. It verifies 248 ArtMeshes, 203851 triangles, atlas bindings, three XYZ parameters, one import Undo/Redo, rotation writes, and a common anatomical Warp opacity change through native rendered pixels. Restoring opacity preserves ArtMesh banks.

The saved CMO3 is `turboism-home/state/dev.turboism.validation.native-character/persistence/native-character.cmo3` below that root. Size: 90442352 bytes. SHA-256: `3a69859a0e5a9966a6ccdf16010d9816f01628f0fd6f2e9e04b8e88339518314`.

The save profile is validation-only, explicitly enabled, pinned to this plugin's `state/persistence/native-character.cmo3`, WRITE, ONE_OPERATION, and exactly `cmo3`. It rejects existing targets and symlinked state paths. It does not change production file authorization.

## Fresh-process reopen

Queue job `3e20f346-80a8-4b99-8b73-ed747a6775fd`, run `queue-072a751bacd7420b99491621557f42a6`, completed with supervisor PASS and safe cleanup.

Evidence root:
`<host-validation-root>/native-character/5304-native-r7-reopen/queue-072a751bacd7420b99491621557f42a6/`.

The second process opens a copy of the saved CMO3. It verifies every imported ID, the complete authoring-bank digest, hidden mesh count, atlas bindings and XYZ parameter count. Both leg Warps retain nine control points, opacity 1, and at least two outfit children. `native-reopened.png` records the reopened native frame.

## Geometry and limits

- Original triangles: 121097; appended evaluated triangles: 82754; total: 203851. Hidden source geometry remains present.
- Partition slots: 178447; 25-form slots: 4461175; interpolation cache slots: 38861387.
- Seven 4096×4096 atlas pages. Material bake deduplicates eleven images.
- Theoretical interpolation chord bound: 15.3818 px. This is not a measured visibility or image-error bound.
- Screen-overlap depth samples select native drawing order at 25 poses. Depth samples never replace source geometry. The order remains an approximation for cyclic/intersecting surfaces and between keys.
- The neutral native frame no longer shows the previous skin-through-corset and skin-through-boots defects. Arbitrary-angle visual equivalence is not established.
- Head/eye controls, imported editable shape keys and clothing Blend Shape parameters are not implemented by this candidate. Existing parents expose native opacity editing.
- Real-host evidence covers 5.3.04. Offline admission does not establish all-version runtime readiness.

## Regression verification

- Focused original/default fixture, NativeCharacter and NativeModelImport tests: PASS (`/tmp/native-overlap-regression3.log`).
- ValidationFileGrants regressions: PASS (`/tmp/native-save-grants-test.log`).
- Runtime admission preflight: PASS.
- `env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 xvfb-run -a ./gradlew devCheck --max-workers=2`: PASS, 143 tasks, 2m45s (`/tmp/native-devcheck-final.log`).
- Earlier Wayland runs hit an existing screenshot capture timeout. No test assertion was relaxed.


## Generic source controls — 2026-10-15

This later candidate uses standard glTF Morph Targets and actual skin weights. It has no eye, head or clothing name requirement. The generated GLB contains two triangles, two joints, two shape targets and saved weights 0.25 and 0.5. It has no custom control or part metadata. Input SHA-256: `50d5ed07f6769e6a4ea16cbabb5c71cf3a47b463ed4cdc694a457fe8fe517165`.

Fixed bundle:

- Agent SHA-256: `dc405006e7c9da0647944a951ef86565d603e3372f9fada878c0a2be74dd8431`.
- Production plugin SHA-256: `6b68e982ac7cb821639557721e649cfd1eb62f19164848c280ed1a1cf06ce736`.
- Probe SHA-256: `7e8930a038f37979fd92f9675b00c67b73aec0c6865a453d31c1c4b93a125074`.

Import job `2df232a0-9aa9-43c4-a1d3-1c06fc00686c`, run `queue-7f382d820ebf4e10acb45e06997b5e90`, finished with supervisor PASS, verified identity, unchanged source fixture, normal exit and safe cleanup. Evidence root: `<host-validation-root>/native-character/5304-generic-controls-r6/queue-7f382d820ebf4e10acb45e06997b5e90/`.

The production menu created two ArtMeshes, three rotation parameters, two independent Blend Shape parameters and four native Warp morph keys. Atlas binding, single import Undo/Redo, endpoint pixel changes and exact pixel restoration at zero passed. Saving produced 28786 bytes, SHA-256 `0e2f71b5bd3e5a5536a5a12cad5c3e668a1567f7a7464e3b41823bc17ddc9676`.

Fresh-process reopen job `ea645b30-9a12-4a73-b2d1-c3e58e36cbd8`, run `queue-a0778517a798449395c95eacbe8750c9`, also finished with supervisor PASS and safe cleanup. Evidence root: `<host-validation-root>/native-character/5304-generic-controls-r6-reopen/queue-a0778517a798449395c95eacbe8750c9/`.

Reopen preserved the complete ordinary-bank digest `b247a72d7ce901ec42800917b95d455cda9fb0f09f1f49e333932b3cebc006e3` and morph digest `8f787cd44283294acb3a7d7c04238c1140872811892a296975c18e237c55c7f6`. Both controls still changed native pixels and restored the zero-state image.

Earlier failed runs exposed three defects. Native reparenting converts stored positions, so position equality is not a valid structural check. Ordinary batch writes must exclude the protected default form. Native Redo rebuilds evaluated instances, so captured history callbacks must guard source and document identity without retaining evaluated-instance identity. Regression tests reproduce each failure. Public stale-handle checks remain strict. Temporary diagnostic traces were removed.

Verification after fixes:

- Full plugin suite: 264 tests, 34 conditional skips, zero failures.
- Object-source replacement, deletion, document lifetime and rebuilt-instance regressions: PASS.
- Reviewed-version Warp morph transactions and ArtMesh morph regressions: PASS.
- `env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 xvfb-run -a ./gradlew devCheck --max-workers=2`: PASS, 143 tasks (`/tmp/native-generic-final-fixes-devcheck.log`).

Limits: Warp fitting is calibrated only at the neutral view. Side views, combined controls, dedicated eye/head/clothing mappings and mask seams remain unverified. The optional 111-control character bake exceeds the added-control storage cap and is rejected explicitly. This fixture does not prove that arbitrary models fit within the budgets. The main user Editor has not been updated with this candidate.


## Combined source controls and nested opacity — 2026-10-15

`build-combination-fixture.py OUTPUT.glb` generates one triangle, two explicit position controls and two explicit opacity controls. It contains no private character assets. This fixture tests the explicit mapping path; the standard Morph Target evidence above remains separate. Input: 1416 bytes, SHA-256 `ed2e15742f63d8df7e4329645bca20ac50604efd3a689d536b76759269c658b4`.

Fixed final bundle (`build/manual-test/native-combinations`):

- Agent: `fc972d5787566a61ed10d3b1966c361f48625434b1777b1c773acce0f3575eae`.
- Production plugin: `2d61034ab6466f9485ea57a68e471040b7573d781c6ad7ea367bafd952523776`.
- Probe: `e0a122745141aa39c4128652e4ac5224661091431b8793e4335ff85a596c6dc4`.

Import job `0dbc9852-01a5-488c-a26a-030e28faa4af`, run `queue-61d82958128f4195879afc14e3126ade`, finished with supervisor PASS, verified identity, unchanged source fixture, normal exit and safe cleanup. Evidence root: `<host-validation-root>/native-character/5304-combinations-r6/queue-61d82958128f4195879afc14e3126ade/`.

The production menu created one ArtMesh, three XYZ parameters and four Blend Shape parameters. Both geometry controls were enabled together. At yaw -30°, 0° and +30°, rendered alpha sums divided by the same opaque geometry gave exactly 0.5, 0.25 and 0.125 for each opacity factor and their product. Reset restored the original pixels. Ordinary banks and morph data did not change during parameter evaluation. Single import Undo/Redo passed.

The probe also creates a temporary Warp and opacity morph in a transaction, then deliberately throws. The result is ROLLED_BACK. Deformer IDs, complete history snapshot, ordinary banks and morph digests return to their before images. No temporary Warp remains.

Saving produced 23278 bytes, SHA-256 `35045fc39aba7dd91d7279751932019d2317ad18a449271035223dc2a2838cf4`. Fresh-process reopen job `99d3e9c5-7f13-4948-af4d-78f66116ab82`, run `queue-fdac0daf04084b53b8a8c7f8b683bbff`, finished with supervisor PASS, verified identity, unchanged source, normal exit and safe cleanup. Evidence root: `<host-validation-root>/native-character/5304-combinations-r6-reopen/queue-fdac0daf04084b53b8a8c7f8b683bbff/`. Both combination and intentional rollback checks passed again. The complete ordinary-bank digest `3e96a97e6d8db4e05ae42bc11d672c29018a2d84f2208ed76199811b9e5baad9` and morph digest `cdc5da40c93fc056ebc34214c663018e9153aca7ef386d6b9a6e1a8b1d0247f3` match the saved originals.

Two regressions explain the changes. Native reparenting of an identity opacity Warp changes stored float positions, so exact snapshot equality wrongly rejected the import. Layout remains strict; all coordinate differences must remain within 1e-5 in local coordinates. Warp references are reacquired by stable ID after parent conversion. During failed-root recovery, native Undo may already remove the newly created Warp. Morph compensation now accepts removal only when the coordinator proves the same root owns that source creation and its complete removal predicate passes. Unregistered missing sources still fail. Public stale references remain strict. Temporary debug instrumentation was removed.

Verification:

- Full plugin suite: 266 tests, 34 conditional skips, zero failures.
- Warp morph transactions and source/lifetime regressions: 121 tests, zero failures. The new created-source recovery regression covers all 20 reviewed Cubism versions. This is offline compatibility evidence, not all-version host readiness.
- Final `env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 xvfb-run -a ./gradlew devCheck --max-workers=2`: PASS, 143 tasks (`/tmp/native-combinations-final-devcheck.log`).
- A nonidentity joint matrix regression checks morph rotation/scale without translation leakage.
- A depth-only 0.2-unit shape diagnostic measures zero neutral residual but 28.284px projected displacement at yaw or pitch ±30° under the fixed 400px-radius camera. The neutral Warp remains identity, so it cannot reproduce that side displacement. This is an explicit failing accuracy example, not acceptance of side-view fidelity.

Limits: native alpha composition and pixel restoration do not establish three-dimensional projection accuracy, occlusion correctness, arbitrary shape combinations or dedicated clothing/mask/foot/eye/head behavior. The main Editor has not been updated.


## Expanded source-control coordinate budget — 2026-10-15

The plugin now budgets geometry-keyform and local Warp-control coordinates together at 256 MiB (33,554,432 points, two floats per point). This replaces the prior one-million-control-point and six-million-combined-point limits. The separate geometry-only six-million-slot limit, 64-million interpolation-cache-slot limit, 16-million decoded-source-control-slot limit and 2px neutral fitting threshold remain unchanged. This budget does not bound JVM heap, CMO3 size or MOC export size. No SDK or Runtime limit changed.

The optional private character bake `/tmp/task-n002-blend/native-controls.glb` has SHA-256 `ad25dbb8cd3f12901c17f104ef26ee5a16ba688a8b6422aeccaddef19896623a`. A standalone same-package diagnostic called production `NativeModelImport.prepare` with the standard 1024px canvas / 400px-radius framing and `-Xmx2g`. It ran the complete loader, material conversion, control fitting and adaptive partition pipeline, without writing the source file. Raw output is retained in `/tmp/native-budget-character-batch.log`; the diagnostic source is `/tmp/NativeBudgetDiagnostic.java`.

```text
PASS controls=111 patches=796 controlSlots=3399999 geometrySlots=4844506 coordinateBytes=65956040 cacheSlots=37960810 partitions=940 triangles=236437 residualPx=1.990992392 seconds=216.534
exit=0 elapsedSeconds=216.76 peakRssKiB=1560468
```

All 111 effective controls survived. Authored coordinate payload is approximately 62.9 MiB. The measured interpolation-cache count fits the existing limit, so it was not raised. Peak process RSS was approximately 1.49 GiB. The production geometry planner checks source triangle counts and partition provenance. This preparation evidence does not establish native import, save/reopen, side-view projection or arbitrary-asset readiness.

The first attempt with larger storage and the old single-failure refinement loop was deliberately stopped after 880.197 seconds, at peak RSS 1,788,208 KiB. It is not a passing run. Each failed fit previously caused a complete scene-order rebuild. The planner now collects all failed patches in one pass and refines them together. Fits retain their original error threshold, cache semantics and single-triangle rejection. A regression checks two independent failures are both reported for refinement.

Validation: 268 plugin tests, 34 conditional skips, zero failures (`/tmp/native-budget-batch-tests.log`). Final `devCheck` passed with 143 tasks (`/tmp/native-budget-final-devcheck.log`). Exact coordinate-budget boundaries and long-overflow rejection passed. The main Editor was not deployed or launched for this change.


## Requested live manual inspection — 2026-10-15 (pending)

The operator requested a visible imported character for manual inspection. The test-only probe now supports `turboism.character.manualInspection=true`. After production-menu import it verifies source hash, triangle count, added parameter count and atlas bindings, captures the neutral image and stops automation. It does not run Undo/Redo, change poses, save or exit. `manualInspection=PENDING` and `validationScope` explicitly distinguish import structure from visual/history/persistence acceptance. Configurable planning and authoring deadlines retain their prior defaults for automated runs.

Bundle: `build/manual-test/native-controls-manual`. Initial job `1b6f50db-d22c-4efb-a24a-996da371de6b`, run `queue-2676368fc1f443ca85417e0d42d5203e`, used the real desktop and exited during source preparation with wrapper code 1. Supervisor cleanup is safe; no terminal probe result exists. This is a failed run, not import acceptance. No current JVM crash or kernel OOM evidence was found. The `hs_err_pid328.log` inherited from the prefix dates from August and is unrelated. The cause remains undetermined.

Retry job `77ac49e1-cf10-40b4-8284-292e37b707ca` changes the task JVM heap from 8 GiB to 4 GiB and adds GC logging. All source and importer artifacts remain the same. It is queued with `interactive-desktop`, a 24-hour job deadline and 20-hour normal-exit allowance; automation must not close the manual window. As last observed, admission is `external-busy` because an unrelated frame-animation manual Cubism session is open. It was not closed or controlled. The operator was notified. A queued retry is not a launch or PASS.

Probe build and packaged descriptor preflight passed. Production plugin SHA-256: `772c0c4bd6aa38ecb7e110555281d4fc9551a3568b3b1250e313aaf5225c05f2`; Agent: `4fec5444a79e22b66281e34599268e8051e75b1236580fe0a7df3398a9e023d7`; probe: `8d0227930256eac426c03faace6cc5250ba00c92f80436522122859020d57c36`. Both launches use official BAT, task CoW prefixes, isolated homes and immutable queued inputs. Manual inspection and successful full-asset native authoring remain pending.


### Manual inspection retry progress — 2026-10-15

The operator closed the unrelated frame-animation window. Retry r2 then completed source preparation in approximately 222 seconds and entered native authoring. It remained in authoring for more than 20 minutes. GC logs show the 4 GiB heap repeatedly approaching capacity and Full GC reclaiming little space (4082M to 3849M at 1377.415 seconds). No explicit OutOfMemoryError was observed. The task was cancelled through the host queue. Job `77ac49e1-cf10-40b4-8284-292e37b707ca` is cancelled, not PASS.

Retry r3 is job `85171f82-8099-47b7-8678-9680bc5cb6ba`, run `queue-0db092f91f684706a882b028e1922d1f`. It uses the same immutable bundle and fixture, an 8 GiB heap and GC logging. Source preparation ran from 21:12:28 to 21:16:03 CST. The last observed stage is native-authoring; no terminal probe result exists. Evidence root: `<host-validation-root>/native-character/5304-controls-manual-r3/queue-0db092f91f684706a882b028e1922d1f/`. The task must retain the window if import succeeds. Native writing performance, manual inspection and persistence acceptance remain pending.


### Deferred interpolation candidate — 2026-10-15 (host acceptance pending)

Retry r3 remained in native authoring for more than 40 minutes without a probe result. Its console repeatedly reports `CModelSource.setup` near 4.8 seconds and extended interpolation structure rebuilds. Raising the heap did not resolve native authoring latency. Queue cancellation completed with supervisor cleanup `safe`; the host returned to idle. No import or manual acceptance is claimed.

The plugin previously initialized rotation interpolation caches in `NativeCharacterAuthoring`, then created the atlas and local control Warps in `NativeModelImport`. Later model updates can rebuild all existing caches. The candidate executes composed atlas/control authoring before interpolation initialization, within the same root transaction. Existing standalone assembly still initializes interpolation. Control fitting, sampling density, geometry, opacity composition, public SDK and Runtime protection domains stay unchanged. Cache initialization verifies the current rebased mesh banks and original patch Part scope. Stage logs distinguish geometry, atlas, local controls (every 32 patches) and cache initialization.

The existing combined geometry/two-opacity native-parent-conversion regression now runs through this composition point. With the old order, it fails because caches already exist when controls begin (`/tmp/native-cache-order-red.log`). After the order change, the full plugin suite passes: 268 tests, 34 conditional skips, zero failures (`/tmp/native-cache-order-build.log`). Final `devCheck` passes, 143 tasks (`/tmp/native-cache-order-devcheck.log`). These are offline results, not measured host speedup.

Retry r4 job `2edbc01e-d14b-4c7c-98fa-fedf82518a45`, run `queue-e305974cf6604ffcb4e34ca99f0aaecb`, uses the same 111-control fixture, 8 GiB heap and manual-inspection protocol. Source preparation began at 22:14:11 CST. Bundle: `build/manual-test/native-controls-cache-last`; agent SHA-256 `846ce6bc7f3dc862a27c851c4d440e48dcc82dc5d8e4e5ef6d25c6c6fe0147b3`, production plugin `813a6fec709f2f9aaef099615f36d5f1831e175d8ee90310e3ad3cc30ab4d8cb`, probe `0764e6bc1e16828c019a599b5c5be1c676274ba3981d7470b48b5e03191eed0d`. Prepared digest `87b5f3d3ebc0f7a7f426f783f3d487df54eee418106940cda58a1e4df7dfcc28`. Host result and manual inspection remain pending.


### Full-control performance limit and operator-selected manual CMO3 — 2026-10-15

The deferred-cache candidate did not achieve acceptable full-character import latency. r4 reached source controls 448/796 at 23:00:25 CST, without a terminal probe result. Geometry took 434.731 seconds (22:17:52.802 to 22:25:07.533 CST); atlas writing took 8.277 seconds. Source-control writing then remained slow. The order regression and offline gates pass, but full-control host performance does not. Job `2edbc01e-d14b-4c7c-98fa-fedf82518a45` was cancelled through the queue. No successful full-control import is claimed. Per-key native authoring remains unresolved; the candidate is not a completed performance fix.

The operator explicitly selected opening the previously verified CMO3 to inspect appearance and rotation first. Its source remains 90442352 bytes, SHA-256 `3a69859a0e5a9966a6ccdf16010d9816f01628f0fd6f2e9e04b8e88339518314`. It contains the saved default appearance, anatomical Warp hierarchy and XYZ controls, without the added 111 blend controls. This selection does not satisfy the separate full-control import requirement.

Manual reopen job `aac2b67c-9de7-461c-bb27-2164dde0c387`, run `queue-85fb58fe11fc4a98827c97ff1e9d415d`, opened that CMO3 through the reviewed official BAT in a task-owned CoW prefix. Evidence root: `<host-validation-root>/native-character/5304-native-default-manual-r8/queue-85fb58fe11fc4a98827c97ff1e9d415d/`. Probe `status=PASS`, `persistence.reopen=PASS`; the bank digest remains `977187b025e718f380e11aeaa76b4aed1ce64af71ece5b27ac98fa866129aef2`, all imported mesh atlas bindings pass, and all three XYZ parameters remain present. The captured `native-reopened.png` visibly contains the character. The live window remains open with `exitAfterProbe=false` and `--keep-prefix`. The queue is intentionally still running until the operator closes it. No current-run supervisor terminal PASS or manual visual acceptance is claimed.


### Transaction refresh and file-scoped parameters — 2026-10-16

Warp morph creation previously updated all model instances after each key. The reviewed 5.3.04 bytecode shows that `CModelSource.updateModelInstances()` first invokes `setup()`. The writer now verifies stored source forms immediately and requests one root-commit instance update, deformer-palette refresh, canvas repaint and dirty mark. Undo/Redo listeners and compensation retain their immediate refresh. No SDK or mapping selector changed.

The multi-key regression failed before the change for all 20 reviewed versions. The affected Runtime run passed 250 tests with no skips or failures. It verifies no forward refresh between source-key writes and one update at commit. Existing rollback and history tests pass. The plugin run passed 269 tests, with 34 explicit external-fixture skips and no failures. `devCheck` and candidate packaging passed 156 tasks in 2m28s under an isolated Xvfb display. The first desktop-display attempt was stopped after a thread sample showed the unrelated screenshot test blocked in `ScreencastHelper.getRGBPixelsImpl`; that attempt is not counted as PASS. A later filename-boundary test and plugin rebuild passed after limiting the ID stem to 20 characters.

Selected filenames now determine the import display name and parameter ID stem. A fresh import suffix separates repeated imports. Unicode-only names retain their display text and use a filename-derived legal ID stem. Each native import creates one parameter group. Roll, Yaw/Pitch and source blend controls are created directly in that folder through existing batch creation APIs. The source-control ID prefix also derives from the file-scoped rotation ID. Other small-model paths group only parameters created in the current transaction. Existing parameters are excluded.

Small combined-control import:

- Job: `a7da4456-3b6f-46f2-b657-8aef23f211c8`.
- Run: `queue-3ad6a6e1686949b981562bd699e0180e`.
- Evidence root: `<host-validation-root>/native-character/5304-refresh-groups-r2/queue-3ad6a6e1686949b981562bd699e0180e/`.
- Supervisor result: PASS, normal exit, fixed identities, unchanged source fixture and safe cleanup.
- The file-named group contains all seven new parameters: three rotations and four blend controls.
- Source-control rendering, ordinary banks, Undo/Redo, forced rollback and official save pass.
- The probe observed 1055 ms from native-authoring confirmation to native verification. This tiny fixture is a correctness check, not evidence of full-character latency.
- The preceding r1 failed because the probe compared `Optional<String>` directly with a string. The group and all seven members had passed. r2 unwraps the optional and retains the assertion.

Independent reopen:

- Job: `e8adef95-c3a1-4af7-bc5c-cf9328408ccb`.
- Run: `queue-e5008c369f554719bf30be59df77c6a4`.
- Supervisor result: PASS, normal exit, fixed identities, unchanged source fixture and safe cleanup.
- Group name, member IDs, ordinary banks, atlas inputs and blend-key hash persist.
- Saved CMO3: 23,397 bytes; SHA-256 `10cb7e199d5ee00b9867fc93e19694a8cde3c4c6a162ab4feb497fa7d34af4fa`.

The 111-control character benchmark did not reach import. Job `4e4d7715-fa4c-44f9-9fe4-38f1443ed027`, run `queue-0834e72052e34ab0a54be28ec6dcc502`, stopped at the official license-activation network-error dialog. The probe reports `Timed out waiting for active document`; no source planning or native-authoring timing exists. Supervisor cleanup is safe. This run is not a performance acceptance or a successful character import. Full-character load latency remains unaccepted.

### Rotation occlusion experiment — 2026-10-16

The offline depth-coverage diagnostic separated fixed internal triangle order from inter-partition ordering. It uses exact projected geometry and covered depth samples at 384 squared resolution. It does not reproduce final native RGBA pixels or interpolation geometry.

The existing normal-axis split cannot run: normal Z range is at most two, so its weighted score cannot exceed 0.8 times the largest spatial span. A minimal opposing-face regression reproduced the issue below the triangle threshold. A six-direction geometric-normal seed was tested, then reverted because the original character's frontal ordering worsened.

| Pose | Baseline wrong depth samples | Directional candidate wrong depth samples |
| --- | ---: | ---: |
| Yaw 0 | 978 | 3127 |
| Yaw -90 | 5709 | 4712 |
| Yaw 90 | 5849 | 4404 |
| Yaw 180 | 14892 | 8272 |

The default appearance fixture retained 203,851 triangles. The candidate increased partitions from 248 to 351. Forced backface culling reduced some conflicts but retained thousands of wrong depth samples and can remove intended double-sided surfaces. Neither experiment is shipped as a visual fix. Reports and the reverted patch are retained under `/tmp/native-occlusion-*` and `/tmp/native-directional-partition-experiment.patch`. The user's reported occlusion, broken surfaces and clipping remain unresolved.


### Full 111-control retry — 2026-10-16

User authorized Enter to dismiss the previously observed startup network-error dialog. In this retry that dialog did not recur; no Enter was sent. The official editor opened the task document and invoked the production import menu.

- Job: `5782bccc-c6f5-4d5e-91df-39f3e1505f42`.
- Run: `queue-707273a9640641f5a8ce60b2f0f621eb`.
- Candidate: `e25b89434`, existing `native-refresh-groups` bundle.
- Input: `native-controls.glb`, 127,651,212 bytes, SHA-256 `ad25dbb8cd3f12901c17f104ef26ee5a16ba688a8b6422aeccaddef19896623a`.
- Source planning: 219.360 seconds (17:04:38.890 to 17:08:18.250 UTC).
- Native source geometry: 551.842 seconds (17:08:18.286 to 17:17:30.128 UTC).
- Texture atlas: 10.551 seconds (to 17:17:40.679 UTC).
- Source controls: reached 448/796 patches after 632.519 seconds; last logged progress 576/796 at 17:33:40.635 UTC. These are local patches, not the 111 parameter count.
- Final queue result: `timed_out`, validation FAIL, cleanup `safe`, normal exit false. The 1,800-second queue limit ended the run at approximately 17:33:48 UTC. No complete authored model, cache initialization, official save or reopen was verified.

Compared with the previous roughly 35-minute 448-patch observation, the control-stage refresh change materially improves this partial benchmark. Geometry is still slow and later control batches slow down. This is neither full-character functional acceptance nor acceptable import performance. Further investigation must measure reparent conversion, keyform position writes, object creation and source readback costs; do not infer their individual shares from these stage timings.

Evidence root: `<host-root>/native-character/5304-controls-refresh-groups-r6/queue-707273a9640641f5a8ce60b2f0f621eb/`. Runtime log: `turboism-home/logs/runtime/2026-10-15/turboism-17-04-14.324-p344-10061965474221261865.log`. Queue terminal observation retained at `/tmp/native-r6-terminal.json`. Independent post-run SHA-256 checks match the original CMO3 fixture, original BLEND/GLB and generated control GLB hashes; this does not replace the missing complete supervisor acceptance checks.


### Measured import hotspots and safe reductions — 2026-10-16

Sampling run `queue-2c99ed1c06914e7199fa23fdd76b6762` (job `7253f275-498d-4bbc-ba79-9a117a777d6b`) used the unchanged full 111-control input and the previous candidate. Source planning took 220.672 seconds. An opt-in test-only 100 ms sampler identified the strided depth-column scan in `NativeCharacterVisibility.order` as the largest planning hotspot (1,413 leaf samples at the nearest-partition scan). Native authoring stacks also showed repeated object-creation instance updates, batch property palette refresh, and source/instance collection scans. These are wall-clock stack observations, not CPU percentages. After sufficient samples, the job was explicitly cancelled through the queue; supervisor cleanup is safe. No import acceptance is claimed for this diagnostic run.

Changes:

- Coverage votes scan each partition depth row contiguously in two passes. Nearest-depth selection, first-partition ties, finite checks, threshold and votes remain identical; no geometry or ordering rule changes.
- Bulk ordinary keyform position/order writes still update native model instances immediately. Part/deformer palettes and canvas refresh once at the root transaction boundary; Undo/Redo and compensation retain immediate refresh.
- ArtMesh collection reads build a temporary identity membership index per snapshot rather than scanning all sources for each instance. Fresh source/instance bijection checks, duplicate-ID rejection and stale-instance rejection remain in place; no persistent cache is introduced.
- Anatomical parent conversion completes for every mesh before expanding any ArtMesh to its 25 ordinary rotation forms. The final banks, source geometry and hierarchy readback checks remain enabled.

Deterministic regressions reproduced eager batch repaint and interleaved parent/25-form expansion before their respective fixes. Differential voting tests cover missing coverage, ties, near-threshold depths, nonfinite values and randomized sparse depth tables. Source/instance regressions reject duplicate instances, same-ID foreign sources and missing instances, and accept fresh instance replacement.

Planning proof, same input and harness, quiet separate JVM runs with a 4 GiB heap:

| Measurement | Archived baseline | Contiguous voting |
| --- | ---: | ---: |
| Full source planning | 211.073 s | 73.130 s |
| Triangles | 236,437 | 236,437 |
| Geometry partitions | 940 | 940 |
| Controls / control patches | 111 / 796 | 111 / 796 |
| Geometry slots | 4,844,506 | 4,844,506 |
| Control slots | 3,399,999 | 3,399,999 |
| Max fit residual | 1.990992392100 px | 1.990992392100 px |

All 25 final draw-order lists have the same SHA-256: `4aeb14b1517d0b3a36845ff5ba845917ddedda165b2e62cbc0bcf87b3c0ff90d`. The synthetic 940-partition / 42,500-pixel scan proof also verifies every vote: median 1,465.620 ms versus 139.266 ms over five measured iterations after two warm-ups (10.52x for this scan only). This is not full native import latency or an occlusion fix.

Reproduction harnesses are in `validation/native-character-host-probe/performance/`. Local raw outputs: `/tmp/native-visibility-scan-proof.log`, `/tmp/native-preparation-baseline.log`, `/tmp/native-preparation-improved.log`. Full-character native authoring, cache initialization, save and reopen remain necessary before performance acceptance.

Candidate verification:

- `devCheck`, `previewBundle` and plugin JAR build PASS: 158 tasks, 3m 3s. Log `/tmp/native-performance-finalcheck.log`.
- Related Runtime regressions: 357 tests, zero failures/errors/skips. Log `/tmp/native-performance-regressions.log`.
- Final plugin suite after hierarchy sequencing: 272 tests, zero failures/errors, 34 explicit private-fixture skips. Log `/tmp/native-plugin-final-tests.log`.
- Runtime agent SHA-256 `cbbfb915352cce266ed41693854b453d6e450e76cb732fda85b2151fd62e6ef5`; plugin SHA-256 `8c3ec4caf2476b8114b2a0b2f01c737226a4c5a98d13cf1df74c1aa3e5e1b408`; probe SHA-256 `90b1df2604fb84f72f93455345f9c3635fff0ca54e4d249d055ff46b13e191b7`.

Exact-host dual-part import:

- Job `a06e3c43-aa80-4d68-b6e2-dfd6bc227d29`, run `queue-bf9fe147f6da4a439c96d266519f16b9`, run label `performance-r8-small`.
- Two source primitives with distinct anatomical metadata, two shape controls and two shared opacity controls. All seven imported parameters belong to the file-named group.
- Native authoring confirmation to structure verification: 1.449 seconds. This tiny fixture is functional evidence, not full-character performance evidence.
- Undo/Redo, forced rollback, rotation checks, shape restoration, combined shape/opacity controls at yaw -30/0/30, official save PASS. Combined alpha ratios 0.5, 0.25 and 0.125; source banks and morph forms remain unchanged by parameter evaluation.
- Supervisor PASS, normal exit, verified identity, unchanged source fixture and safe cleanup.
- Saved CMO3: 29,700 bytes, SHA-256 `00c394747c7a83e8f2b9c48a9f8adfd5abb34e4c713f583c3a3f3641e5adf07e`.

Independent reopen:

- Job `64f2fa3b-002a-45a6-a5ba-412597133b08`, run `queue-d5f8a9695f8f41bd8cb21b92e3a6c325`, run label `performance-r8-reopen`.
- Parameter group membership/name, ordinary source banks, atlas inputs, morph hashes and combined controls PASS after a fresh process opens the official saved file.
- Supervisor PASS, normal exit, verified identity, unchanged fixture and safe cleanup.

These changes do not claim to fix the reported large-angle occlusion or to have completed a full 111-control native authoring/save run. T035 and the full-character performance gate remain open.


## Early source-control hierarchy and coalesced parent palettes (2026-10-16)

The r9 diagnostic repeated the full 111-control input with the r8 production bundle. Job `bb75eed3-4647-49c4-b626-7c908ab79a1c`, run `queue-0f872455b6894846bfb52159c1907a61`, label `5304-controls-performance-r9`. Source preparation lasted 78.487 s (01:52:39.285Z to 01:53:57.772Z), versus 219.360 s in r6. This is preparation only, not total import latency. Sampling then captured 573 runnable observations through cooperative ArtMesh reparenting, including 463 through UI refresh and 400 with the native deformer palette at the leaf. Counts are wall-clock observations, not CPU percentages. The diagnostic was explicitly cancelled after collecting this signal; supervisor cleanup is safe. It did not complete import/save and is not acceptance.

The candidate now creates source-control opacity/local Warp parents while every mesh still has one ordinary form, then writes all 25 rotation forms in the resulting local coordinates. The original post-rotation source callback remains available; the production importer uses a separate early hierarchy callback and explicit affine rotation localizer. Neutral native conversion, source topology, source-control memberships, and all 24 non-neutral rotation projections remain checked. Empty ordinary-form edits are avoided for one-form meshes. Regression `sourceGeometryAndMultipleOpacityFactorsReacquireRebuiltWarpParents` failed before the change because controls were authored after 25-form expansion, then passed with early conversion and full coordinate checks.

A second measured hotspot was cooperative ArtMesh parent writes refreshing all palettes after every conversion. Forward writes still update native instances immediately; Part/Deformer palettes and canvas refresh now coalesce at root commit. Undo listeners and compensation retain immediate complete refresh. Regression `parentConversionsKeepInstancesCurrentAndRefreshPanelsOnlyAtCommit` failed before the change (unexpected intermediate repaint), then passed with fresh instances during two parent changes and one final repaint. Related Runtime tests: 485, no failures/errors/skips. Plugin suite: 272 tests, zero failures/errors, 34 explicit external-fixture skips. `devCheck previewBundle :plugins:model-import-3d:jar` passed (154 tasks, 2m47s). Logs: `/tmp/native-parent-panels-red.log`, `/tmp/native-parent-panels-green.log`, `/tmp/native-early-parent-suite.log`, `/tmp/native-early-parent-finalcheck.log`.

Early-hierarchy-only bundle `build/manual-test/native-early-parents-r10`: first small run `6487cc42-dec7-4d5c-9316-b5c53db9ed5e` failed waiting for an active document, while Cubism displayed License Activation; no import was attempted. Cleanup is safe. Explicit retry `4fec681f-c4d3-46f5-9850-a091bfb00862`, run `queue-f72c14e2e2e34deeb40abf569ea8a776`, label `5304-early-parents-r11-small`, passed history, rollback, rotation, combined two-shape/two-opacity controls, atlas, filename parameter grouping and official save. Saved model: 29,662 bytes, SHA-256 `f98d2e215ddb8f4d71164356197fd0f5ba709f9ec54474c54a1eef0bb8afb867`. Independent reopen `3b45e4a0-86aa-48f9-8563-baba77a29c28`, run `queue-1f4a3af73a3748bea92817348e75ff33`, passed. Both successful jobs have verified identity, unchanged fixture, normal exit and safe supervisor cleanup. These small-model timings do not prove full-character performance.

### Occlusion candidates rejected during this iteration

The retained `performance/NativeOcclusionDiagnostic.java` reproduces the prior baseline exactly at all six yaw poses. It measures projected source-geometry depth, not native final blended pixels. Raw baseline: `/tmp/native-occlusion-current-report.txt`.

| Geometry/order experiment | 0° wrong samples | -30° | +30° | -90° | +90° | 180° |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Existing production | 978 | 2340 | 2026 | 5709 | 5849 | 14892 |
| Normal-axis split for thin clusters | 799 | 2168 | 2322 | 5865 | 5919 | 11967 |
| Split each spatial cluster by normal Z sign | 1238 | 3393 | 3388 | 5032 | 5011 | 6959 |
| Existing partitions with last-painted depth votes | 978 | 2099 | 1872 | 5730 | 5861 | 14924 |
| Spatial/normal split with last-painted votes | 1237 | 3190 | 3502 | 5059 | 5101 | 7070 |
| Spatial/normal split with depth-gap weighted votes | 2180 | 3335 | 3322 | 5103 | 5247 | 6803 |

None passed the no-regression comparison across front/side/back. They remain isolated under `/tmp/native-*-experiment` and were not applied to production. The source triangles are unchanged in production; no forced culling or hidden surface deletion was shipped. T035 remains open. At ±30°, fresh exact-pose partition ordering reduces the baseline to 1172/1162 samples, exposing an additional stored-rank interpolation limitation. The remaining internal-triangle errors require independently orderable geometry; increasing rank interpolation alone cannot fix them.


The r12 combined intermediate candidate passed the two-part small fixture again: job `c6dfda43-f618-44a3-a619-7bc7b16ca0e3`, run `queue-b9e7ab956dca4ff09a3fab67a7d6eb11`. Full character job `ff9cd81e-c325-47a0-a272-7b32fa5947af`, run `queue-eee49bedcf7a43cd8cba8819eab9cb94`, label `5304-early-parents-panels-r12-character`, failed the 1200-second result deadline; supervisor cleanup is safe. Preparation: 79.636 s. Initial geometry/anatomical hierarchy: 88.266 s. Source controls: 480.036 s for all 796 patches (448 patches at 187.054 s versus r6 632.519 s). It then stayed in rotation keyforms until timeout: no full import/save acceptance. Moving all source morph materialization before rotation banks transferred repeated setup cost into the rotation-binding phase; the stage speedup is not an end-to-end speedup.

The follow-up separates source-control hierarchy preparation from morph materialization: all native parents first, then ordinary rotation banks, then atlas and source morph forms, then caches. Source opacity defaults are set during hierarchy preparation; opacity morph keys are deferred with positional keys. All morph targets are reacquired by stable Warp ID after hierarchy/atlas operations. Compatibility `apply` still prepares and finishes in one call. Regression `rotationBindingsAfterSourceMorphs` was nonzero before this scheduling change (2 in the small source-control case); it must be zero while preserving final morph memberships, all control values and all rotation positions. Logs: `/tmp/native-staged-morphs-red.log`, `/tmp/native-staged-morphs-green.log`. Final full-character timing remains unaccepted until a new exact-host run completes.


### Staged-morph final candidate r13

Final production plugin SHA-256: `48ea798e5b21bb48f58794720cb2250471ac44903935981e376e312a7313b24d`, bundle `build/manual-test/native-staged-morphs-r13`. Final `devCheck previewBundle :plugins:model-import-3d:jar`: PASS, 154 tasks, 1m11s. Plugin tests remain 272 with 34 explicit fixture skips; no failures/errors. Runtime code is unchanged from the 485 passing related tests above.

Small combination fixture job `e45e0450-d9f6-4c31-a9ee-092650f78a99`, run `queue-4e739cf0dfd5411a87c3b9bacc2fd65b`: supervisor PASS, verified identity, unchanged fixture, normal exit, safe cleanup. History, rollback, ±30° combinations, opacity ratios, exact zero restore, parameter folder and official save passed. Saved small CMO3: 29,693 bytes, SHA-256 `507584aca1faddbe4a696678c8884ee6fa5cf06f326d2f8725464952f0b1889d`. Independent fresh-process reopen is tracked separately below.

Full 111-control character job `09e60cb7-2fac-4c05-84eb-4ee3fa3353d6`, run `queue-5e331667e01b4acc912cf91168f1acc3`, label `5304-staged-morphs-r13-character`: supervisor PASS, verified identity, unchanged fixture, normal exit, safe cleanup. **This job's scope is import structure only.** It ran with `manualInspection=true`, so the probe bypassed history, pose, save and reopen checks; it did not produce a full-character CMO3 or establish visual acceptance. The task-owned host closed after the probe. Actual: 940 ArtMeshes, 236,437 triangles, 114 added parameters (111 source controls + XYZ), one filename-derived group containing the imported parameters, all texture-atlas bindings present. Source preparation to structural-ready: **1096.836 s (18m16.836s)**. This is still unacceptable load latency; T036 remains open.

| Full-character stage | Measured seconds |
| --- | ---: |
| Source preparation | 76.663 |
| Initial geometry/anatomical hierarchy | 89.714 |
| Local source-control hierarchy | 320.165 |
| Ordinary rotation bindings and keyforms | 438.629 |
| Atlas | 9.756 |
| Morph materialization and readback | 110.648 |
| Interpolation/cache setup and root commit until structure verification | 46.938 |

Raw logs: `/tmp/native-r13-character-host.log`, task-local `turboism.log`, `progress.properties`, `result.properties`, `import-stacks.txt`, `/tmp/native-r13-character-terminal.json`. The sampler still identifies synchronous whole-model instance refreshes and native object lookup during parent/binding/bank writes. Further deferral requires an explicit evaluation barrier or batch contract: preserve current instances before native parent conversion, evaluated reads and atlas work, and preserve recovery/Undo semantics. It is not safe to simply delete instance updates.

Original GLB, original Blend and the full-controls generated fixture were independently SHA-256 checked again after the run and exactly match their recorded source hashes. No original asset was modified. No forced culling, triangle deletion or failed normal-partition experiment was shipped. T035 remains open. A further untested seam hypothesis is that different interpolation densities on adjacent partitions can disagree at intermediate poses; it requires an exact-host shared-vertex evaluation regression before any sampling policy change, and must retain cache preflight.


The r13 small-model independent reopen completed: job `478c6447-a5fc-4b57-bf11-1448dd12c801`, run `queue-ea240ae3edaf41efbc1f260218034b7c`, supervisor PASS with verified identity, unchanged fixture, normal exit and safe cleanup. The shared host is idle. This is small-fixture persistence evidence only.


### Explicit source-evaluation barrier and shared-lattice follow-up

`AuthoringTransactionService.batchStoredSourceWrites` is opt-in and synchronous. Only verified parameter-binding writes and ordinary ArtMesh keyform batches defer instance evaluation inside it; each write still admits native Undo and verifies stored-source readback. The rotation-bank phase uses one batch, and its barrier precedes placement, atlas operations and evaluated reads. Ordinary calls, native reparenting, compensation and Undo listeners retain immediate evaluation. Exceptions cross the barrier before propagation; a failed evaluation is retained as a suppressed failure rather than replacing the callback failure. The unavailable backend never runs the callback. The Facade and dynamic-host wrappers explicitly forward this method with the existing permission, lifecycle, plugin-identity and active-lease checks. A new facade regression failed before that forwarding fix (`/tmp/native-batch-forwarding-red.log`).

Deformer snapshot membership now uses a snapshot-local identity map, matching the earlier ArtMesh optimization, instead of rescanning every source for every instance. Fresh reads still reject duplicate instances, replaced sources and removed objects, and accept rebuilt instances. No identity index survives a native update.

The r15 two-part/four-control fixture passed: job `5743449f-856e-4580-bba7-2f633fa1d918`, run `queue-d1f64e643d894b66aab50b742081bd16`, supervisor PASS, verified identity, unchanged fixture, normal exit and safe cleanup. **This predates the wrapper-forwarding fix, so it establishes compatibility, not actual native batch activation or full-character speed.** Intermediate character job `a09db8af-3948-4e84-9c13-3cd877ffba90` was cancelled through the shared queue when the seam fix became available; supervisor cleanup is safe. It is not import acceptance or a usable timing result.

The public shared-seam fixture reproduced a real native-calculated-geometry defect: baseline job `0f2f428c-6c07-48f2-a8f9-3b8ea084f0e2`, run `queue-58e0b99e1fb6461790e3aa945c570315`, produced a maximum shared-vertex gap of **2.96954345703125 Canvas pixels** across the specified yaw/pitch poses. The test correctly failed; supervisor cleanup was safe. Earlier fixture attempts lacked required attributes or selected the small-object path and did not reach this measurement; they are not evidence for the seam.

The uniform visible interpolation policy uses the existing ELLIPSE/3 configuration on every visible partition, retaining per-mesh/global cache budgets and the actual error bound. Hidden source topology remains intact. Planning regression failed with inserts 1 versus 3 before the fix and passes afterward, including the explicit original GLB fixture. Final keyform/triangle counts are unchanged.

The r17 native seam measurement returned **maximum gap 0.0 Canvas pixels** at every tested yaw/pitch pose, with the expected single shared pair. Its overall probe failed the later import Undo check because the first probe version sampled parameters before testing the import Undo entry. Parameter changes create their own history entries; the probe now performs seam sampling after the existing import Undo/Redo and rotation checks. This r17 run is seam evidence only, not a passing full validation job. The final r18 bundle includes this probe correction and the actual batch forwarding; final results are tracked below.


The r18 native batch was actually reached and correctly rolled back on a new guard failure: the public root belongs to the plugin, whereas native writer contributions use `runtime.editor-model`. Source-evaluation participation now uses the same `Binding.sameScope` document/model/generation/thread comparison as the coordinator's existing `apply`; public batch admission still checks the owning plugin binding. Regression includes an internal writer in the same root and rejects a foreign document. r18 failed jobs (`caece8a5-c795-4689-87c7-e4ceb552a2cd`, `30ee4eee-ea98-448f-8b6c-27a83af20174`) are not acceptance. The r19 candidate includes this fix; affected Runtime tests and packaging passed (`/tmp/native-seam-batch-r19-check.log`).

Latest full plugin suite: 273 tests, 0 failures/errors, 34 explicit external-fixture skips. SDK suite: 440 tests, 0 failures/errors, 1 skip. The r18 full `devCheck previewBundle :plugins:model-import-3d:jar` passed (154 tasks, 2m55s), followed by format/package verification. Source-only-batch tests exercise rollback and failure barriers; facade/dynamic tests exercise forwarding and fail-closed permissions/lifecycle. r19 changes only the scope comparison noted above and its regression plus explanatory comments.


### r19 final native acceptance and measured remaining work

Bundle: `build/manual-test/native-seam-batch-r19`. The actual batch path (including both forwarding wrappers and internal writer scope admission) passed:

- Shared seam: job `ca3f4feb-d6d7-4316-a03c-cc4d5fcab71f`, run `queue-c8379552e6894ff3936b8f8a1de47254`: **maximum gap 0.0 Canvas pixels** over all 18 yaw/pitch samples. Import Undo/Redo, rotations, opacity, exact source-bank retention and official save also passed.
- Two parts / four controls: job `cc18944a-92e9-4baf-97e4-52fcd32565c2`, run `queue-0ca93de889634eea92431bd789d2f6a7`: combined ±30-degree shape/opacity checks, rollback, zero restore, filename folder and save passed. Saved CMO3: 29,661 bytes, SHA-256 `441ea02cb5a29f4a9139096df5ed44397e6358a06b6ed05e8e1bdf0faf04e69f`.
- Independent fresh-process reopen of that CMO3: job `dad6c5e0-7aec-4b56-aa33-c39fceb55c31`, run `queue-17c31b5be5dc4df090eef3379bfb8dd0`: PASS, including persisted banks/morphs and combinations.

Each of those jobs has supervisor PASS, verified identity, unchanged fixture, normal exit and safe cleanup. The shared host was confirmed idle after all jobs.

Full 111-control character: job `565b9a2a-775b-4f34-933b-fe07ac7cddfc`, run `queue-3505322736b64bba8bef8b563580aa32`, label `5304-seam-batch-r19-character`, supervisor PASS with verified identity, unchanged fixture, normal exit and safe cleanup. **Scope remains import structure only**, using the same `manualInspection=true` path, fixture, 8 GiB heap and stack sampler as r13; no builds or other host workloads ran alongside this timed import. This does not establish full-character appearance, history, save/reopen or rotation acceptance, and no new complete-character CMO3 was saved. Its host has closed. Counts remain **940 ArtMeshes, 236,437 triangles, 114 parameters (111 source controls plus XYZ)**, one filename-derived folder and all atlas bindings present.

Preparation-to-structure-ready: **814.013 seconds (13m34.013s)**, versus r13 **1096.836 seconds (18m16.836s)**: 282.823 seconds / 25.785% less time. This is one same-protocol comparison, not a multi-run statistical benchmark. **Load latency is still unacceptable; T036 stays open.**

| Full-character stage | r13 seconds | r19 seconds |
| --- | ---: | ---: |
| Source preparation | 76.663 | 79.284 |
| Initial geometry/anatomical hierarchy | 89.714 | 89.973 |
| Local source-control hierarchy | 320.165 | 309.413 |
| Ordinary rotation bindings/keyforms and evaluation barrier | 438.629 | 94.609 |
| Atlas | 9.756 | 9.369 |
| Morph materialization/readback | 110.648 | 89.522 |
| Interpolation/cache, root commit and structure verification | 46.938 | 141.843 |

The rotation phase is about 4.64 times faster. The uniform visible lattice fixes the proven seam but increases cache work; its extra cost is included in the measured end-to-end result. No controls, source triangles or interpolation precision were removed to obtain the speedup. All existing authored-coordinate and cache-budget preflights remain enabled.

The next structural performance target is parent creation: construct anatomical/opacity/local Warp parents before materializing the character's ArtMeshes, then perform one native conversion to each final parent, rather than evaluating the entire populated model while creating every local Warp. This is **not implemented** and requires the same source-UV, all-forms, Undo/rollback and native coordinate-conversion checks. Cache construction/readback is the other measured remaining cost.

T035 remains open: the shared-lattice fix resolves a measured interpolation seam, but fixed triangle traversal within an ArtMesh and interpolated partition ranks still cause front/back depth-order errors. The six-pose depth diagnostic has not become final native RGBA acceptance; no rejected partition experiment or forced culling was shipped. Original GLB, Blend and full-control input hashes were independently checked again and match the earlier provenance exactly. Raw final logs: `/tmp/native-shared-seam-r19-green-host.log`, `/tmp/native-seam-batch-r19-small-host.log`, `/tmp/native-seam-batch-r19-character-host.log`, `/tmp/native-seam-batch-r19-reopen-host.log`, plus each task's retained evidence.

Final r19 artifact SHA-256 values:

- `model-import-3d.jar`: `a41b53cfbfba58d1a334e09db1787a186377a8486a027c8398bcd7b4ac91ddbc`
- `native-character-host-probe.jar`: `24fc931dd7a94d13ee9c15d038e3f92b42bebf32ae248aabc21bd859724d984e`
- `turboism-agent.jar`: `e35a8d89fa1ce559f6d68458bdcfdd451363de3cff56e43ee24bfa2b52f5fad8`

Final r19 full `devCheck` passed under Xvfb: **BUILD SUCCESSFUL in 2m59s**, 143 actionable tasks (19 executed, 124 up-to-date). Log: `/tmp/native-seam-batch-r19-finalcheck.log`. `git diff --check` also passed. These checks supplement the native evidence above; T035 and T036 remain open.


### Parent hierarchy before ArtMesh materialization (r21)

The importer now constructs anatomical/outfit parents and every source-control parent before creating any imported ArtMesh. The hierarchy carries immutable source-index and stable parent-ID lists, so source-control setup no longer requires materialized mesh instances. Each mesh binds its source image while still unparented, then performs one native conversion directly into its final parent chain. Default stored coordinates are checked against the composed body-frame/local-control transform before expanding the 25 rotation forms. Source UV geometry and all existing bank/readback/cache checks remain intact. Dense morph forms still follow atlas and rotation writes.

A new regression simulates the entire affine parent chain and native instance replacement. It verifies zero Warp creations with imported ArtMeshes present, exactly one conversion per mesh, unparented texture binding, all 25 pose coordinates (including neutral), all opacity factors, source morphs and the filename-derived parameter folder. The first run exposed stale anatomical Warp references after parent conversion; these are now reacquired by stable IDs. Log: `/tmp/native-preparents-regression.log`; green: `/tmp/native-preparents-regression-green.log`.

The first real-host attempt r20, job `49b4ea71-d139-447f-bfd8-bc9da62a0e24`, run `queue-a440e6d1160a4c8394a478ebca1c2601`, correctly rolled back with `Cubism art mesh sits under a deformer`: source-image binding must precede mesh reparenting. That ordering is corrected and regression-tested. This failed attempt is not acceptance; the supervisor confirms safe cleanup and idle host afterward.

Corrected r21 two-part/four-control fixture: job `447c0f28-79df-4a36-a055-811bda8f3348`, run `queue-92d5007f57e7430a8055b0ef16dbf9d0`, supervisor PASS, identity verified, unchanged fixture, normal exit and safe cleanup. Import Undo/Redo, rotation, source opacity/morph combinations, rollback and official save pass. Small saved CMO3: **29,693 bytes**, SHA-256 `f7f1a3bbd96572bef6856fa23d95eb30f48b7ceaa78fffb5c9e4351b390720bb`. Native default-parent coordinate verification passed. Log: `/tmp/native-preparents-r21-small-host.log`.

Plugin suite and packaging passed (`/tmp/native-preparents-r21-plugin.log`). Full `devCheck` passed in 2m51s, 143 actionable tasks (`/tmp/native-preparents-r20-devcheck.log`); it overlapped small-fixture validation, never the full-character timing run. Full-character measurement uses the same explicit 111-control input, 8 GiB heap, stack sampler and structure-only manual-inspection protocol as r19; results are recorded below after terminal verification. No claim of improved occlusion follows from hierarchy scheduling.


The r21 full-character attempt `bf861493-e842-42f8-970d-f90777dc5fa2`, run `queue-07d7066903324871b4605203ab93d4e2`, rolled back when partition 96 exceeded the provisional normalized-local epsilon. That epsilon was not a physical pixel measure. The importer now restores local coordinates into body-parent coordinates, scales their difference into canvas pixels and rejects non-finite or greater-than-0.001-pixel errors. The optional measured maximum is returned in `Created` and logged before rotation expansion. A synthetic injected 0.01-canvas-pixel native drift is rejected. This retains an explicit physical gate rather than silently accepting normalized-coordinate errors.

r22 first small attempt `4a24a7da-68ed-44d9-ad90-f53a7f39c26a` never reached import: the official startup trial flow reported an Internet connection error and the probe timed out awaiting a document. It is not a performance or feature result. Retry `590b4ae4-6ad0-4b37-8eb7-ac3e64281ada`, run `queue-95c5c1b83a4243669c27b936d7d9bb62`, passed with verified identity, unchanged fixture, normal exit and safe cleanup. Maximum neutral conversion error: **0.000039315816864496725 canvas px**.

r22 full-character diagnostic `b78c6286-2b0b-47c6-9c05-60066fad9b22`, run `queue-8c17d590960d4d23b6a36bd6fbe32f61`, was cancelled through the queue after the source-mesh phase alone exceeded the r19 combined geometry/hierarchy cost. Supervisor confirms cancelled and safe cleanup. No successful total, full-character pixel-error bound or import acceptance is claimed. Prebuilding all parents alone is not a demonstrated end-to-end optimization: it makes later per-mesh instance/palette updates expensive. Stack observations identified repeated inspector `setDrawOrder` writes and palette refresh alongside required native parent conversion.

The follow-up groups initial stored ArtMesh draw orders in a separate explicit batch, after all image bindings/parent conversions and before rotation binding. Runtime admits the stored scalar through the same source-evaluation barrier as ordinary bank edits. Default-form draw order remains writable through the existing inspector method; ordinary bank default-position protection is unchanged. Native Undo, per-write stored readback, batch failure barrier and rollback remain intact; calls outside the explicit batch still evaluate immediately. The new real Runtime wiring regression fails before the fix (two updates inside the batch), then passes with one barrier and successful abort restoration. Logs: `/tmp/native-draworder-batch-red.log`, `/tmp/native-draworder-batch-green.log`. Plugin suite: **276 tests, 0 failures/errors, 34 explicit external-fixture skips**; related Runtime keyform suite: **56 tests, 0 failures/errors/skips**.

Occlusion diagnostic correction: forced candidate culling no longer changes the source reference's coverage/material semantics. `missing` pixels are included in errors. Public synthetic test: 22,472 source-visible pixels remain in the reference; a culled candidate reports 22,472 holes/errors. A mutant reproducing the old reference mistake reports zero reference pixels and correctly fails (`/tmp/native-culling-reference-red.log`). The corrected harness passes. No global culling is applied to production, and this does not resolve T035 or prove final native RGBA correctness.


### r23 final acceptance and bounded next hypothesis

Bundle `build/manual-test/native-preparents-r23`; full `devCheck` PASS in 4m1s (143 tasks, `/tmp/native-preparents-r23-devcheck.log`). Final plugin suite: 276 tests, no failures/errors, 34 external-fixture skips. Runtime keyform transaction suite: 56 tests, no failures/errors/skips.

Full character job `2b0c0a39-a261-4dd2-b7bf-d97b3a9786f6`, run `queue-7d162559ee764dc9937966fd23e3eee5`, label `5304-preparents-r23-character`: supervisor PASS, verified identity, unchanged fixture, normal exit, safe cleanup. Same explicit full-control input, 8 GiB heap and sampler; no build ran during this timed import. Counts remain 940 ArtMeshes, 236,437 triangles, 114 added parameters (111 controls plus XYZ), one filename-derived group and complete atlas bindings. Maximum neutral-parent conversion error: **0.0006068566462325795 Canvas px**. Scope is still **structure only** (`manualInspection=true`), with no full-character visual/pose/history/save/reopen acceptance and no new full-character CMO3.

Comparable source-preparation-to-manual-inspection-ready time: **805.532368 s**, against r19 **814.013030 s**: only **8.480662 s / 1.042%** less. This is not a substantial performance fix. Do not substitute the earlier verifying-native timestamp for the same comparison endpoint. r23 source preparation 78.386 s; hierarchy preparation 41.819 s; meshes and initial scalar batch 183.977 s; rotation writes/barrier 192.942 s; atlas 9.990 s; source morphs 217.662 s; interpolation/commit/structure verification 80.755 s. Stage gains were largely offset elsewhere. A host resource snapshot showed memory pressure and swap use, but cannot establish causality or isolate this scheduling change.

Small two-part/four-control job `699f667f-4fe2-4461-8b6f-50b36e7f7496`, run `queue-f28b9adbf5f446c7ab3dd1ef98019c54`: supervisor PASS, verified identity, unchanged fixture, normal exit, safe cleanup. Undo/Redo, rollback, combined poses/opacity, zero restore and official save passed. Maximum neutral-parent error 0.000039315816864496725 Canvas px. Saved CMO3 29,715 bytes, SHA-256 `45b3e6411a20c3b7f11b44e584b649452ab9abe96ab8c31fa4b36331c24ac4bc`.

Final shared seam job `3007bfc3-6354-42ca-aac7-d80c229c5d3e`, run `queue-7ce874bb5a1440aba9bc85692693764e`, supervisor PASS with verified identity, unchanged fixture, normal exit, safe cleanup. Shared-vertex gap is **0.0 Canvas px**, with one expected pair; history, opacity and official save also pass. This does not establish complete character appearance.

Corrected source-depth diagnostic on the default-appearance input: source wrong counts at 0/-30/+30/-90/+90/180 are 978/2340/2026/5709/5849/14892; forced candidate culling gives 1598/2216/2023/2501/2533/6973, with missing pixels 65/70/120/9/130/4. Front regression and holes reject global forced culling. Logs `/tmp/native-culling-source-r23.log` and `/tmp/native-culling-candidate-r23.log`; this is exact-source depth evidence, not final native RGBA acceptance. T035 remains open.

Aggregating r23 sampler blocks by the first plugin call site identifies 451 EDT observations inside per-partition Part creation and 2066 inside source morph creation. Sampling is wall-clock, not CPU or exact elapsed attribution. Source confirms Part creation calls whole-model `updateModelInstances`. The next bounded change moves all isolated patch Parts before ArtMesh/deformer creation; it retains all Part memberships and geometry. Regression requiring zero Part creations with imported meshes present fails before the change (`/tmp/native-preparts-r24-red.log`) and passes after (`/tmp/native-preparts-r24-green.log`). This scheduling candidate is not yet a measured full-character speedup. Repeated object lookups and morph protection snapshots remain separate hypotheses; no safety checks or source geometry are removed. T036 remains open.


r23 small CMO3 independent fresh-process reopen: job `b0bc6dac-d675-4c7f-8871-def84a2be4b3`, run `queue-c1608ae85b5548729ef446dd31d32c2b`, supervisor PASS, identity verified, fixture unchanged, normal exit and safe cleanup; persisted controls and combinations pass.

r24 precreated-Part candidate: complete plugin suite 276 tests, 0 failures/errors, 34 fixture skips; formatting PASS (`/tmp/native-preparts-r24-plugin.log`). Small two-part/four-control job `522eaa60-aa6e-40e0-a80a-cc1ddfe3a563`, run `queue-d8fe1e73001a453aa1cd122a0ec54b76`, supervisor PASS with verified identity, unchanged fixture, normal exit, safe cleanup. History, rollback, ±30-degree combined morph/opacity controls and save pass. Full-character timing starts only after all local tests/audits have finished.

A read-only exact-position edge audit on the explicit default-appearance scene found open/nonmanifold edges in the shoe/body visible primitives. It therefore provides no justification for treating those source double-sided surfaces as closed opaque shells. Audit is a conservative diagnostic in `/tmp/NativeClosedSurfaceAudit.java` and `/tmp/native-closed-surface-audit.log`, not a production culling rule or proof of native visual acceptance. No geometry or winding was changed.


### r24 full-character structure timing

Job `82d4d414-0dc8-4c84-9f34-b9ccbe488954`, run `queue-c919bb472465425882f63f6c490caed8`, bundle `build/manual-test/native-preparts-r24`: supervisor PASS, verified identity, unchanged fixture, normal exit and safe cleanup. Source preparation started 05:53:48.121908800Z; manual-inspection-ready 06:04:14.649225900Z. Same fixture, 8 GiB heap, sampler and structure-only protocol as r19/r23, with no concurrent local builds or diagnostic runs during this measurement. **626.527317 s (10m26.527s)** total: **179.005051 s / 22.222%** less than r23 and **187.485713 s / 23.033%** less than r19. This is one run per candidate, not a statistical claim or proof that every stage gain is caused by Part scheduling. Load latency remains unacceptable; T036 stays open.

r24 early hierarchy grew to 120.352 s (r23 41.819 s), while meshes/default orders took 163.817 s (183.977), rotation 82.582 s (192.942), and atlas/morph combined 101.947 s (227.652). Cache/commit/structure verification 79.064 s (80.755). Changes between stages must not be reported as independent speedups; use the common final endpoint. Maximum neutral coordinate error remains **0.0006068566462325795 Canvas px**. Full-character acceptance remains **structure only**, no visual rotation acceptance, full-character save/reopen or new full-character CMO3. No geometry or controls were dropped.

Follow-up preparation-only hypothesis: `NativeModelImport.prepare` currently computes all 25 visibility orders before each control-fit attempt. Failed fit candidates discard those expensive orders; fitting reads geometry/camera/budgets and does not read ordering. A typed internal geometry candidate now defers visibility until control fitting succeeds, while public `NativeCharacterPlan.prepare` still returns a complete plan. An explicit assembly preflight rejects missing orders before creating native objects. Regression compares completed candidate and public-plan equality on overlapping geometry; a sequential exact-input benchmark compares all final draw-order hashes, residuals and budgets before performance is accepted. This does not change the ordering algorithm or fix T035.


Preparation-only comparison completed sequentially, without concurrent host imports or builds. Same explicit 111-control asset and `-Xmx4g`; baseline r24 **73.260 s**, deferred-order r25 **47.475 s**, saving **25.785 s / 35.197%**. Both return 236,437 triangles, 940 partitions, 111 controls, 796 patches, 4,844,506 geometry slots, 3,399,999 control slots and max residual 1.990992392100 px. Both final draw-order SHA-256 values are **`4aeb14b1517d0b3a36845ff5ba845917ddedda165b2e62cbc0bcf87b3c0ff90d`**. Logs `/tmp/native-final-visibility-baseline-r24.log` and `/tmp/native-final-visibility-candidate-r25.log`. This establishes the local preparation reduction; it is not a new full-host end-to-end timing or an occlusion improvement. The final candidate's small native validation and full `devCheck` run only after that comparison ended.


### r25 final verification and retained limits

Final bundle `build/manual-test/native-final-visibility-r25`. Plugin suite **277 tests, 0 failures/errors, 34 explicit fixture skips**. `devCheck` PASS under Xvfb, **1m18s / 143 tasks**, log `/tmp/native-final-visibility-r25-devcheck.log`. The related Runtime transaction suite remains the 56 passing tests recorded above; r24/r25 do not add further Runtime changes.

- Final two-part/four-control native import: job `c33a110e-bcda-4b91-9098-b6357bcfa246`, run `queue-952e4f2dfd8f4332a75406dd0f6c6e8f`, supervisor PASS, identity verified, unchanged fixture, normal exit and safe cleanup. History, rollback, combined ±30-degree poses and opacity controls, zero restore, one filename parameter folder and official save pass. Saved small CMO3 **29,754 bytes**, SHA-256 **`7f4c5e25527b9f5426d6930f3151ab879a52f0248a6c5ae4409b96167d2a72f3`**.
- Independent fresh-process reopen: job `8f798d45-513d-4f83-9dca-acfda3e96313`, run `queue-0938f2da5d1540b08e74d526b5e90b94`, supervisor PASS, identity verified, unchanged fixture, normal exit and safe cleanup. Persisted shapes/opacity and combinations pass.
- Final shared seam: first job `628cab2e-189a-4e58-bd3a-a5b30d5a0800`, run `queue-c0f8d70697e14f2a9363585118f34f66`, failed before import while waiting for an active document. Official startup dialog recorded `Unable to connect to the Internet (2 ,-103)` during trial activation. Supervisor cleanup is safe; this is not an import or geometry failure. Retry job `183e7ed0-bc89-40a8-abeb-18710c5d1741`, run `queue-971f1faf8ff840c69c404badc758fe40`, supervisor PASS, identity verified, unchanged fixture, normal exit and safe cleanup. Maximum shared gap **0.0 Canvas px**, one expected pair; history, rotation, opacity and save pass. Shared host confirmed idle afterward.

Final diagnostic self-test passes with the unchanged 22,472-pixel source reference and 22,472 candidate holes. Per-partition CSV sums match all six source-depth measurements (wrong 978/2340/2026/5709/5849/14892), with zero holes in the source-policy candidate. At ±90/180, internal errors are 5264/5408/14004; this identifies internal triangle traversal as the dominant remaining side/back limitation. These are source-depth samples, not final native RGBA error percentages. No new occlusion fix is claimed; **T035 remains open**. Final r25 full-host timing is not measured: report r24 full-host time and r25 preparation-only time separately, without subtracting the latter savings from the former. **T036 remains open** because about ten minutes is still unacceptable latency.

Final artifact SHA-256:

- plugin `07641d97b5ae2219d981e2f286beca227d19acac2060a2fc895c54dc9cbc3805`
- probe `24fc931dd7a94d13ee9c15d038e3f92b42bebf32ae248aabc21bd859724d984e`
- agent `7ab9d463b7b863fdee198725e48a7366a6d2155d6b3e1fbac5297b3ce48644ac`

Original GLB `1e16cdf61b75b4bb4d336d1a37f66fce03f33c694b42d9c710b2f4200a18bd45`, original Blend `f21860cbe03528a2fab031d3d7a565e03347c73d02563399e2df838b7c96c5fc`, and explicit controls input `ad25dbb8cd3f12901c17f104ef26ee5a16ba688a8b6422aeccaddef19896623a` were independently checked again and remain unchanged. No external committee agent was created; automatic review rejected that optional delegation, and the source/stack analysis was completed locally instead.


### r26 repeated-read reduction and deferred control parameters

The r24 stack sample was re-aggregated by first Runtime/plugin frame (wall-clock observations, not CPU percentages). Native hierarchy creation/update remains a major cost (1002 observations), with source morph writes (862), source-control parent setup (790 at the grid call site) and mesh creation/conversion also prominent. No native identity, source/instance, transaction, Undo or protection checks were removed.

The source-control preparation now reads the existing parameter collection once, checks all generated IDs in a local set, and delays `createMany` until the deferred source-control finish. Parent grids, mesh creation, texture binding and ordinary rotation banks need only reserved immutable IDs, not the 111 BLEND parameter objects. The atomic native batch still checks current conflicts when parameters are actually created; all definitions retain their filename-derived folder and zero default. Each mesh now reads its ID once and shares one post-conversion snapshot between neutral-coordinate and source-geometry verification. This reuse is confined to a phase without intervening native writes and does not cache across native updates.

The regression first failed on both legacy/prebuilt control paths because BLEND parameters existed before the finish callback. It passes after deferral. Additional assertions require one parameter collection read for all source conflicts and one post-conversion snapshot for both geometry checks. The complete plugin suite and packaging passed. Native validation and timing evidence follow after supervisor finalization; no end-to-end speedup is claimed from call-count assertions alone. T035 and T036 remain open.


r26 disposition: the complete plugin suite passed (277 tests, 0 failures/errors, 34 explicit external-fixture skips). Full `devCheck` passed in a fresh X11/Xvfb environment in 2m12s, 143 tasks (`/tmp/native-deferred-parameters-r26-devcheck-x11.log`). The first devCheck attempt blocked in the existing Robot/Wayland screenshot test; only that identified test worker was stopped. This is not an importer failure.

Small native checks all have supervisor PASS, verified identity, unchanged fixture, normal exit and safe cleanup:

- two-part/four-control: job `961fc265-f8c8-48e3-b238-31eadc1feb72`, run `queue-7196115fb47e4985b59be7862b146d3a`; Undo/Redo, rollback, rotation/source-control combinations, filename parameter folder and save pass. Small CMO3 29,687 bytes, SHA-256 `5cb95b360aed7eebb75b77286abbd4b820e09f130906af68d588dad1a95a03a0`.
- independent reopen: job `3dc0ea91-3098-40ad-8d72-017fba439d71`, run `queue-08ffc42f839542748f11b1f9f4dfa23a`; persisted controls/combinations pass.
- shared seam: job `9f8bd2d4-8575-475c-a0a6-da0f6ead92a9`, run `queue-940506e47cb442e588b7a148bd726a0d`; one shared pair, maximum gap 0.0 Canvas px; history/rotation/opacity/save pass.

Full r26 job `b8149089-e61c-4362-9e2d-6d29897f44b8`, run `queue-2e4debcfa8154edaa69dc07f531dafac`, failed in probe line 94: `awaitPane` checked visibility in one EDT task and fetched the pane again in another, allowing startup assistance to dismiss the success message in between. The console recorded the normal import-success dialog, but the probe never reached structural verification. Supervisor reports FAIL, unproven identity/fixture/normal-exit flags, safe cleanup and idle host. There is **no accepted full timing or structure result**, no full-character CMO3 and no visual acceptance. The final parameter-delay candidate was withdrawn because a speed benefit was not established; the final importer retains early parameter creation and only reduces duplicate collection/ID/snapshot reads. The final plugin suite/packaging passes after that withdrawal (`/tmp/native-repeated-reads-r27-plugin.log`).

The probe now captures the pane in the same EDT task as the successful visibility check. `test/NativeDialogCaptureRegression.java` deterministically schedules dismissal after that check: old probe fails (`/tmp/native-dialog-race-r27-red.log`), corrected probe passes (`/tmp/native-dialog-race-r27-green.log`). Both compile against the SDK and run under Xvfb, with no full character import. No second ten-minute import was run for this fix.

The requested stage/overlap analysis is in `performance/ImportCostAnalysis.md`. It uses disjoint timestamps and first-Runtime-frame sampling to avoid double-counting nested model/palette/texture work. The next gate is a small fixture proving fewer native updates when creating a Warp directly in its final parent/grid, before any further full-host timing. The r24 626.527-second result remains the latest successful full-host baseline; no new total speedup is claimed. T035/T036 stay open.
