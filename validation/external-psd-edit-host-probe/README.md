# External PSD Edit Host Probe (025, test-only)

Drives the external PSD edit pipeline on the exact Cubism 5.3.02 host through the public
SDK only:

1. Resolve an ArtMesh → model image → current raw image chain on the task fixture.
2. `exportRawImagePsd` → `EXPORTED` + runtime-issued `PsdEditFile` + baseline revision.
3. Discover the runtime temporary allocation under `java.io.tmpdir` (`turboism-psd-*/external-edit.psd`).
4. `observeSaves`; assert the baseline revision is not replayed.
5. `openInDefaultApplication` — recorded, never gated (headless Wine may lack an association).
6. N save cycles (default 3; `-Dturboism.validation.externalpsd.cycles=`): structural
   layer-name mutation → stable revision → `replaceRawImagePsd` → `APPLIED` with consumed
   revision and post-native `after` observation. Cycle 2 saves by atomic rename, cycle 3
   overlaps two writes inside the debounce window.
7. Corrupted save → non-`APPLIED`, revision not consumed.
8. Native `undo(1)`/`redo(1)`; assert the applied raw identity is restored on redo.
9. Idle wait → no revision replay without a new save.
10. `stop()` → `STOPPED`; post-stop writes publish nothing.
11. Re-export the same raw image → fresh handle → clean stop (same-binding recovery).

Not covered and recorded as `NOT_TESTED`: GUI context-menu click/selection timing, a real
external editor, document save/close/reopen persistence, multi-document isolation, p95
performance budgets.

## Build / offline test

```bash
./gradlew :sdk:jar
bash validation/external-psd-edit-host-probe/build.sh
bash validation/external-psd-edit-host-probe/test.sh
```

## Exact-host run (queued, serialized)

```bash
bash scripts/preview/run-external-psd-edit-host-validation.sh --dry-run
bash scripts/preview/run-external-psd-edit-host-validation.sh
```

Result file: `state/dev.turboism.validation.externalpsd/external-psd-edit-result.properties`
under the isolated Turboism home. Terminal status is `status=PASS|FAIL`; the probe never
rewrites a FAIL into PASS.
