# Layout by Part Folder

Standalone plugin: `atlas-folder-layout` (one-click texture set layout), which depends only on the SDK. The algorithm is registered by a separate plugin.

Entry point: Turboism, then one-click layout by part folder. The active model must already have a texture set.

The modal settings offer a maximum square side of 1024 / 2048 / 4096 / 8192 (default 4096), the sort algorithm, the margin, the image spacing, and automatic per-page size by content (on by default). With automatic size off, the layout uses a square of the selected maximum. Images keep their original size and are neither rotated nor scaled. Every time the window opens it discovers every algorithm from the registry and uses the name the plugin publishes. MaxRects-BSSF and Dalsoo come first, the remaining algorithms follow in registration order, and Cubism native is last. Only registered plugin algorithms are listed; the list is resolved again when the layout starts, and an uninstalled algorithm reports as unavailable. Dalsoo uses the rectangle planning entry point; native and other delegated algorithms without a planner are disabled with a stated reason.

The layout groups by top-level part folder first and splits recursively by subfolder on overflow. Images in a leaf are then bisected in descending area order; pages are numbered in tree order, so a repeated folder name never duplicates a page name. An image shared across folders goes to the nearest common ancestor of its users, and an image with no owner is kept separately. After planning succeeds, the whole result applies through the existing complete atlas transaction, which keeps the native undo path.

## Offline Verification

```sh
./gradlew :plugins:atlas-folder-layout:test :plugins:atlas-maxrects-bssf:test :plugins:atlas-dalsoo-polygon:test :sdk:test :runtime:test --tests 'dev.turboism.adapter.cubism.textureatlas.*' --offline
```

Result: BUILD SUCCESSFUL. 50 MaxRects plugin tests, 36 Dalsoo plugin tests, 457 SDK tests (1 skipped), and 186 texture runtime tests, for 728 passed and 1 skipped.
They cover nested ownership, shared images, unowned images, tree order, multi-level overflow, leaf splitting, budget, size, margin, fixed page size, partial and overflow results of the selected planner, and the no-rotation and unit-scale constraints of Dalsoo.

Swing modal interaction was verified in an isolated Xvfb: the default 4096, the four size options, disabling a Dalsoo or native algorithm that is not enabled, confirmation returning 8192 with the modified automatic-size option, and cancel returning an empty value. The [window preview](settings-preview.png) is drawn by the shown Swing window itself, not captured from a Cubism host. Those screenshots and host records belong to the first implementation; algorithm discovery later became a dynamic registry list, and new tests cover third-party algorithm names, priority, list refresh after register and remove, and disabling an entry without a planner.

The full runtime suite once timed out in an unrelated `ScreenshotCaptureHostOperationsTest` and was stopped; the targeted tests above passed.

## Host Verification

With user authorization, the probe ran through the unified queue on official Cubism 5.3.03 with an isolated Proton prefix and a copy of the Geometry100 sample. The probe operates the real production menu and modal window and never calls the planner directly. The validation code is in [src](src/dev/turboism/validation/atlasfolders/FolderAtlasHostProbe.java) and the launch entry is the [queue wrapper script](../../scripts/preview/run-atlas-folders-host-validation.sh).

Both MaxRects-BSSF and Dalsoo split 100 original-size images across a three-level part tree into 3 pages of 1024×1024, ordered HostLeft, HostRight, HostBody. The checks covered that cancel modifies nothing, the default algorithm and the four size ceilings, recursive folder splitting, image integrity, no rotation or scaling, the 3 px margin, the 6 px spacing, the fixed square setting, exact undo and redo, and the native save. A separate reopen session compared the full plan SHA-256 again.

Host runs found and fixed two existing runtime problems: reading a native scaled or rotated image used the transformed bounds, and the reinit-input used by the undo and redo callbacks was missing from the formal access list. The matching regressions pass. The first three failed rounds are kept as failures. Raw results and host screenshots stay in the local ignored `host-evidence` directory, following the repository rule that raw evidence containing local paths is not committed. All four successful tasks were checked for host identity, normal exit, unchanged sample, safe cleanup of the cgroup bound to the original, and an empty kernel error log:

| Check | Queue task ID | Result |
| --- | --- | --- |
| MaxRects layout and save | e6ddff0d-9e16-435e-be72-63de63f98a71 | PASS |
| Dalsoo layout and save | 1238b0a7-0b90-4fd7-b2e9-7fea8aab6763 | PASS |
| MaxRects independent reopen | 6ba8218a-83b3-4c89-84d6-911207fffde1 | PASS |
| Dalsoo independent reopen | 2f786233-a08a-4172-98c6-3fe5c5591fe7 | PASS |

The saved models stay in `state/atlas-folders/saved.cmo3` of each queue task and do not enter the source. This round verified 5.3.03 only; a statically admitted version is not the same as a per-version host pass.

## Standalone Plugin Split

The UI, the menu, part ownership, and the divide-and-conquer logic moved to `plugins/atlas-folder-layout`. MaxRects no longer registers that menu. The split uses only the SDK `TextureAtlasLayoutPlanner` and the shared packing failure protocol, with no compile-time or runtime dependency on an algorithm plugin. New offline checks cover menu registration and uninstall cleanup with no algorithm, the discovery order when only a third-party algorithm is available, and disabling the algorithm of the current page. The host records above belong to the version before the split; the post-split result is recorded separately and cannot be reused as host acceptance of the new binary.
