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
