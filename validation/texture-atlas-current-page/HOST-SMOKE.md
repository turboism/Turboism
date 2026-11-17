# Circle: Real Host Entry Smoke Result

## Conclusion Boundary

**All four tiers went through the real Cubism automatic layout dialog and the production plugin and wrote back successfully.** This settles whether only a fake planner was measured and whether the production code really ran; it is neither a complete correctness proof nor a native or legacy speedup.

| Native input count this run | Current page | placements / overflow | Final scale | snapshot ms | plan ms | apply ms |
|---:|---|---|---:|---:|---:|---:|
| 100 | 4096×4096 | 100 / 0 | 1 | 4.9251 | 10.8462 | 12.9194 |
| 500 | 4096×4096 | 500 / 0 | 1 | 1.4428 | 53.8843 | 94.1990 |
| 1000 | 8192×8192 | 1000 / 0 | 1 | 8.5575 | 66.6138 | 90.2816 |
| 2500 | 8192×8192 | 2500 / 0 | 1 | 18.4559 | 158.7416 | 250.5531 |

Each tier ran once with no standardized warm-up and no repeated sampling. The 100 and 500 tiers shared a process, while 1000 and 2500 each started a new process; **these numbers are not a stable P50, a cold-start baseline, or a performance guarantee**. The first three timings are the service snapshot, planning, and write-back phases and exclude the full native projection, texture generation, and UI refresh.

The dialog used mesh mode, margin 3 px, automatic scale, rotation allowed, MaxRects-BSSF, and parallel off. All four tiers fit at scale=1, so the run covered no real shrinking, no overflow, no cross-page invariance, and no divide-and-conquer parallelism on the host. The 4096² PSD canvas is not the texture page size of the CMO3: the last two tiers really used an 8192² texture page.

## Effect Evidence

- The run used `:bootstrap:jar :plugins:atlas-maxrects-bssf:jar :plugins:mcp:jar` rebuilt in this worktree, and the build succeeded.
- Framework log: `TURBOISM_TEXTURE_ATLAS_AUTO_LAYOUT_HOOK installation=COMPLETE`.
- The plugin lifecycle enable succeeded and explicitly recorded that native automatic layout uses current-page, while PART_BUCKET serves only explicit full-atlas SDK requests.
- All four calls recorded `scope=current-page` and the matching input and placement counts, each immediately followed by `writeback status=APPLIED failureCode=none`.
- The 100 tier ran the real editor undo and redo: the texture viewport clip `(10,237)-(558,784)` differed after undo and matched the post-layout state pixel for pixel after redo. This verifies only the viewport, not a full model or all-pages state hash.
- The 100 tier closed the texture editing window and committed, the production MCP `save` returned EXECUTED, and the task copy hash changed. **The close-and-reopen persistence check has not run yet.**

## Environment and Isolation

- Local Cubism 5.3.03 with the bundled Windows Java 17.0.3.1 and GE-Proton10-34.
- `${GOLDEN_PREFIX}` has no Wine host runtime and was cloned with `cp -a --reflink=always` into a separate prefix; only the lock file of the clone was deleted (a historical action, not the current entry point).
- The run started from the `CubismEditor5.bat` of the cloned installation with a separate Turboism home and only the atlas and mcp external plugins. `JAVA_TOOL_OPTIONS` capped the heap at 4 GiB; the extra UI probe served verification only and is no product component.
- It used its own Xvfb `:97` with Mesa llvmpipe software rendering. The user desktop `:0` was neither controlled nor captured.
- The source models came from `test-assets/texture-atlas-layout/cmo3/circle/`, and only the copies inside the task directory were opened. The 100 copy served the save experiment; the other three copies matched the source hash when the run ended.
- The recorded hashes of the original installation JAR, the BAT, and the 100 source model matched before and after startup; neither the original installation nor the source model was a write target.
- At the end the run stopped the wineserver of the task prefix and its own Xvfb; no test session was left waiting for user input.
- The GE runner offered an upgrade for the copied old DW-Proton prefix, which affects the clone only. The existing installation console carries a third-party licensed component marker; the run neither modified nor bypassed the licence and does not certify the whole installation as a clean official distribution. A purely official end-to-end baseline needs an independent check of every dependency of the distribution, not only the core JAR.

## Raw Evidence Location (Local, Binaries Not Committed)

Task path: `build/texture-host-szx2a9uu/`.

- `home/logs/runtime/`: every runtime log of this round, including the four real calls.
- `evidence/1000-runtime.txt`, `2500-runtime.txt`: process log copies for the large samples.
- `evidence/*auto-dialog*.png`, `*after-layout.png`: dialog and result screenshots.
- `evidence/100-undo.png`, `100-redo.png`, `100-save.json`, `integrity-check.json`.
- `evidence/source-hashes.json`: identity before startup, re-checked at the end.
- `evidence/console*.txt`, `wrapper*.txt`: raw startup and failure logs.

`HostUiProbe.java` is a verification-only agent that enumerates Swing controls on the EDT, presses buttons only by captured ID and exact label, and reads its request from the task evidence directory. It must never be published or installed into a production environment. It compiles with its package name `dev.turboism.validation.texture.HostUiProbe` as Premain-Class. The first version had no package and collided with the host default-package signature, which broke startup; the separate package was added and verified in the three successful startups, and the failure log is kept and never counted as a pass.

## Still Required Next Round

1. Add verification-only method timing with identical boundaries to confirm that native mode really calls the native function; never divide the production `planMs` by the total native UI time.
2. Restore the same model and current-page input per group, rotate native/new/legacy, and record the input digest, the quality, and cold and warm repeated samples.
3. Shrink the current page so automatic scaling must engage, cover fixed-scale overflow with the rotation and parallel switches, and verify that the other pages stay unchanged.
4. Save a copy, close, and reopen, and verify the complete placement, scale, image, and Undo/Redo state instead of a screenshot alone.

Until these finish, no "speedup against native" and no "never slower than legacy in any scenario" can be promised.
