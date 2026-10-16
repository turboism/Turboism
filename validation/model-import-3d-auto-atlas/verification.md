# Automatic texture atlas validation

## Automated integration checks

- SDK: 436 tests passed, no failures/errors/skips.
- Runtime targeted atlas, permission and forwarding checks: 75 passed, no failures/errors/skips (including 13 append-atlas cases).
- 3D import plugin: 185 tests, 159 passed, 26 external-fixture tests skipped, no failures/errors.
- Framework and official-plugin language catalogs: 29 passed.
- Whole-project `spotlessCheck`, bootstrap agent and 3D-import plugin JAR builds passed with `--max-workers=2`.
- Probe JAR passed the production descriptor parser and runtime JAR-content contract before native execution.

## Native probe (2026-10-15)

- Host: Cubism Editor 5.3.04 under an isolated Proton prefix.
- Official host JAR SHA-256: `c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d`.
- Queue job: `f5ecbd7c-5849-4094-82ef-7aebc07eb173`.
- Prepared inputs: `e248b3b45e6cbfe03536e979416946343b1cc569c58cab98f6db8b7fcf423c33`.
- Probe JAR SHA-256: `db011aa4fc640ec561b611b8bf5e1bda15cd0961b283a2d064bdfb7dbe66a071`.
- Native probe report (5304-native-r7): PASS (schemaVersion: `auto-atlas-probe-v1`).

Verified zero-atlas bootstrap to 512x512, append of a 1024x1024 page while preserving the first page GUID and existing mesh UVs, exactly one history entry for the second mesh/image/atlas transaction, one-step Undo/Redo, actual exported ARGB primary-color samples on both pages, and intentional transaction failure with complete atlas/mesh/raw-image/ModelImage rollback and unchanged history position.

Earlier runs were rejected by probe packaging or exposed incorrect probe assumptions (reading an invalidated mesh reference after Undo and sampling an accent grid line). These were corrected; earlier failures remain in queue evidence and are not counted as passing native runs.

The queue lifecycle (job: `f5ecbd7c-5849-4094-82ef-7aebc07eb173`) finished `succeeded`, with verified identity, unchanged source/copied fixture and golden installation, exit code 0, and safe cleanup. The probe explicitly requests JVM exit only in its opted-in queue-owned process; this is not validation of the Editor close/save-prompt workflow. Run r6 already passed the functional assertions but failed the lifecycle gate because it lacked this exit step.

## Scope

The native probe directly exercises the new SDK/runtime operation using procedural textures and meshes. Planner and all three plugin import routes are covered by automated plugin tests; this run does not prove every third-party 3D asset or Editor version works. Full CMO3 save/reopen persistence and GPU viewport readback remain unverified. Existing opaque PNG/JPEG base-color and 1024x1024 target-canvas restrictions remain in the importer.
