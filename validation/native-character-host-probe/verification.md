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
