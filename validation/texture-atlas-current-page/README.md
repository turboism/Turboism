# Current Texture Page Layout Investigation and Verification Record

## Latest Closeout and Evidence Paths

The q guard and the three-version record pins are fixed; after the fix, one serial and one parallel host run each of Circle and Geometry at 100 and 500 passed host acceptance. The latest results are in the [500 matrix](HOST-UI-500-PIN-FIXED.md), the [100 matrix and root cause](INGRESS-DIAGNOSIS.md), and the three-language plugin README. The text below keeps the release limits and historical data of the early investigation and does not mean every interaction and save-reopen scenario is complete.

Committed evidence sanitizes only local machine paths: `${VALIDATION_USER_HOME}` is the home directory of the validation account, `${ATLAS_WORKTREE}` and `${PROJECT_ROOT}` are the matching source roots, `${GOLDEN_PREFIX}` is the configured original prefix, and `${LEGACY_WORKTREE}` and `${LEGACY_RESEARCH_ROOT}` are the matching historical source and decompiled material directories. The job and run path suffixes, the raw values, and every hash in the CSV are unchanged; the source evidence itself is unchanged. The unsanitized copies stay locally in the ignored `build/atlas-merge-private-originals/` directory. A placeholder is not a runnable shell configuration.

The old `run-host-ab.py` serves only the interpretation of historical data, and its entry point refuses to execute. Any new host verification may run only through the unified `scripts/preview/host_validation.py` queue and still needs user authorization.

## Conclusion and Delivery Boundary

The task the user confirmed: **handle only the image set the native caller passes for the current page; generate only that one page and hand an unplaced item back to native overflow**. It enumerates no whole model, plans no next page, and does not pursue an optimal solution for the whole texture atlas.

This worktree implements single-page BSSF, fixed and automatic scale, rotation, single-page region divide-and-conquer parallelism, and the synchronized write-back and read-back check of item, visible LayerRef, and DATA_SCALE. The production algorithm registration test no longer preloads a fake planner and verifies the really registered implementation plus a parallel setting change without a restart. An explicit complete-atlas SDK request still goes to the original complete planner, separate from the native single-page entry.

**It has not reached a releasable or host-accepted state:**

- The SDK blocker is resolved: the user explicitly approved evolving a new contract, and a new [v8 review note](../../sdk/api-contracts/sdk-api-v8-review.md) plus a separate baseline were added, with v7 kept as historical audit. The current v8 exact gate, the v7 historical gate, the old client linkage, the verifier mutation self-tests, and the negative cases all pass; full record-shape compatibility with v7 is not claimed.
- The real native/new same-boundary A/B of the four Circle tiers is complete with 160 runs, an identical restored input hash, and passing branch and geometry checks. The balanced hot-sample median after 4 warm-ups per side is 1.41× / 8.18× / 21.32× / 78.96×; this is neither a UI end-to-end, a dense-scale, nor a legacy guarantee. Switched and consecutive calls are counted separately; see [HOST-AB.md](HOST-AB.md).
- On isolated Cubism 5.3.03 the run really opened the Circle 100/500/1000/2500 model copies and triggered the new implementation, and all four tiers wrote back APPLIED; the 100 tier undo changed the canvas, redo restored identical viewport pixels, the save command executed, and the copy hash changed. Save-reopen and full state equivalence acceptance are incomplete. See [HOST-SMOKE.md](HOST-SMOKE.md). The hashes of the original installation and the recorded source files are unchanged.
- Parallelism is not always faster; the data below keeps its slow cases explicitly. No promise is possible that every input is no slower than legacy.
- The later dense host verification of 500 images at 1024² covered real automatic shrinking, real ForkJoin region tasks, fixed 1 with rotation forbidden, and unchanged transforms for overflow; the new implementation reached an automatic scale of 0.3826 (native 0.3450) and placed 81 at fixed 1 (native 51). Parallelism gave no quality gain on that sample and was slower on consecutive calls; one native undo that did not restore the overflow input still needs investigation. See [HOST-DENSE.md](HOST-DENSE.md); this small sample is not mixed into the 160-run performance baseline.

## Native and Legacy Contract

The detailed standalone report is [native-contract-review.md](native-contract-review.md), with the official JAR identity and method bytecode offsets of three versions. The report baseline is `84beadfd404509036e17a076a7c022e150f2c6ad` and is not a review of every uncommitted implementation.

- A normal input wrapper has item.scale=1; an existing atlas scale never multiplies into the new scale.
- A fixed scale above 0 never shrinks silently; a value at or below 0 means automatic with the result capped at 1; a non-finite parameter is refused.
- Mesh mode uses the original local AABB; full-image mode uses the original image width and height and never passes a CRect to a GRectF getter.
- Rotation compensation uses the original fractional height, not a rounded, scaled, or padded height.
- Only placed items are written; an all-overflow result is also a normal result. The native caller owns the later removal command, and the adapter never changes the container early.
- LayerRef is inverted on save and inverted again on read natively; the write must go through the setter and tolerate numeric error, and verifying item data alone is not enough.
- The legacy defects are not copied: multiplying by .8 after a fixed-scale failure, sharing a mutable candidate, and swallowing a partial write-back failure while still reporting success.

This implementation's conservative integer packing and fixed per-side pixel margin do not promise pixel identity with the native buffer heuristic. Automatic scale is a bounded heuristic search and does not promise a mathematical global optimum; when a parallel partition fails it falls back only to a serial comparison of **the same page**.

## Performance Data: Pure Kernel, Not a Native or UI A/B

Environment: OpenJDK 17.0.20+8; i7-9750H with 12 logical CPUs; `-Xms128m -Xmx512m`. Each configuration uses its own JVM, 10 calls with the first 3 as warm-up and the median of the last 7; one collection round without CPU pinning, so it is no cross-machine or worst-case guarantee. The raw 10-call data is local in `evidence/*-final.txt` (not committed, per the repository rule); the reproduction steps below regenerate the measurement.

Both sides use the same `Random(51)` with widths and heights of 16–96 pixels; a 2048² page, fixed 1, no rotation, margin 0, serial, everything placed. Legacy comes from the offline extracted kernel of `3c2f0fbbbaafa689afa32334941950790e0624dd` and loads no host; only the host dependencies were removed and timing plus geometry assertions added. The new implementation calls the production `CurrentPageTextureAtlasPlanner` (including the final immutable plan creation and validation) and excludes mapping, write-back, and UI refresh.

| Inputs | legacy serial ms | new single-page serial ms | Kernel ratio on this dataset legacy/new |
|---:|---:|---:|---:|
| 100 | 35.350 | 6.429 | 5.50× |
| 300 | 400.996 | 10.680 | 37.55× |
| 500 | 2287.714 | 24.724 | 92.53× |

This is **against the extracted legacy serial kernel**, not against native, and even less a UI end-to-end speedup. Both sides finally place everything at a fixed scale of 1 and never buy speed with shrinking or missing items.

New implementation with 500 items, a 512² page, rotation allowed, and margin 1 per side:

| Mode | Serial ms / placed / scale | Region parallel ms / placed / scale |
|---|---|---|
| Fixed 1 | 13.440 / 52 / 1 | 24.893 / 62 / 1 |
| Automatic | 193.314 / 500 / .347943977 | 489.790 / 500 / .351092791 |

Parallelism is slower here, but the placed count or the final scale differs, so this is not presented as a fully equal-quality speed comparison. The multi-strategy and serial compensation cost of automatic parallelism still needs optimization, and the default stays serial. Legacy fixed-scale overflow has the silent-shrink defect and is not mixed into this table.

### Hot Spots Located This Round

1. After everything is placed the run still continued through the remaining 5 sort orders; the count and area score cannot improve at the same scale, so it now ends early.
2. Every insertion re-ran a quadratic containment check against all old free rectangles; the old set is already containment-free, so only the pairs involving the new split rectangles are compared now.
3. Once a fully feasible scale is found, a worse partial result cannot win: with the same order and scale probing, the feasibility probe now ends when the reserved area cannot hold the item or the first placement fails, and a parallel result no longer repeats the same final optimization round.

Continuing that item, the same-batch hot median of 500-item automatic parallelism moved **682.410 ms → 420.986 ms** with all 500 items and the scale `.351092791` preserved; the 300 complete plans were also byte-identical. The raw samples are local in `evidence/auto-parallel-{pretrial,posttrial}.txt`. This is a before-and-after comparison of one optimization and is not mixed with the 489.790 ms of another batch in the table above; without CPU pinning it still cannot claim a general speedup.

New logs separate `scope=current-page/complete-atlas`, `scale`, `overflow`, `snapshotMs`, `planMs`, and the write-back `status/failureCode/applyMs`; a plan log does not mean the write-back finished, and a write-back status never replaces the native entry result or the UI and undo host acceptance.

After the final regression for a padding reserve beyond int, the internal split coordinates use long while external content coordinates still pass the int page-bound check; the 300 plans are still byte-identical. Latest 500-item re-measurement: fixed scale / 2048² serial **22.350 ms**; automatic / 512² serial **172.517 ms** and parallel **388.696 ms**, with the placed count and scale unchanged; see local `evidence/final-current-bench.txt`. Parallelism still carries clear scheduling and multi-strategy cost, so it stays user-selectable and off by default.

Before and after the second optimization, 300 random inputs (1–100 items, different page sizes, margins, rotations, fixed or automatic, serial or parallel) produced byte-identical complete plans. The output SHA-256 was `80d7567ac282859dbeed12b0aaf91918a9f372873290c280cba3c51ace5e6ca4` both times; the local raw output is `/tmp/texture-page-bench/plans-{before,after}.txt`. This proves unchanged results on that sample, not a general formal equivalence proof.

## Executed Verification

- Plugin suite: 38 tests, 0 failures, including preservation of the complete-atlas SDK request, real registration, live parallel, the automatic-scale quality regression, the padding reserve beyond int, and the staged diagnostics.
- Runtime texture-atlas related: 83 tests, 0 failures, including the new regression where several setters fail during rollback and the remaining outputs still recover and can retry.
- SDK suite: 252 tests, 0 failures.
- Bootstrap suite: 82 tests, 0 failures.
- The ReadSeek syntax check of the changed Java reported no error, and `git diff --check` reported no error.
- New regressions cover a raw fractional pivot, full-image mode not reading the mesh, partial and complete overflow, an ignored visual write, a one-shot setter exception, old state recovery, and a half-pixel wrong write at large coordinates that the tolerance must not swallow.
- The **full** runtime attempt was interrupted by an external 180-second timeout and cannot be listed as a pass; the related 82 cases were re-run afterwards and passed.
- The SDK v7 historical and v8 current exact API gates pass, a client compiled against v7 links and runs against the new SDK, and the negative case comparing a v7 JAR against the v8 baseline is rejected, so no gate was relaxed.

## Reproduction

Run from the repository root (replace B with the real build output root of the current worktree):

```sh
./gradlew :plugins:atlas-maxrects-bssf:classes --console=plain
B=build/worktree/investigate-texture-sort-native-legacy-performance
CP="$B/sdk/classes/java/main:$B/atlas-maxrects-bssf/classes/java/main"
OUT=$(mktemp -d)
javac -cp "$CP" -d "$OUT" validation/texture-atlas-current-page/CurrentPageBench.java
java -Xms128m -Xmx512m -cp "$OUT:$CP" CurrentPageBench 500 2048 false 1 false 0
java -Xms128m -Xmx512m -cp "$OUT:$CP" CurrentPageBench 500 512 true 0 true 1
# Arguments: input count, page side, parallel, requestedScale, rotate, margin

# Name the verified legacy source explicitly; the extractor verifies the whole file SHA-256 first
python3 validation/texture-atlas-current-page/extract-legacy.py "$LEGACY_SOURCE" "$OUT/LegacyTextureBench.java"
javac -d "$OUT" "$OUT/LegacyTextureBench.java"
java -Xms128m -Xmx512m -cp "$OUT" LegacyTextureBench 500 2048

./gradlew :plugins:atlas-maxrects-bssf:test :runtime:test --tests '*TextureAtlas*' --console=plain
./gradlew :sdk:test :bootstrap:test --console=plain
./gradlew checkSdkV7ExactApiCompatibility checkSdkV8ExactApiCompatibility checkTextureAtlasSdkV7Linkage checkSdkApiBaselineTool --console=plain
```

## Follow-Up Acceptance Plan

1. The SDK v8 contract evolution and verification are complete; the anchor is `959ca8c359f24b80c86bb9699c8111d067e75694`, a local commit only, not published.
2. Local settings binding, quality, and boundary verification are complete, including the reserve beyond int, the multi-recovery setter exception, and the staged logs. Worst-case cost on a wider set of real models, the real host execution branch, and the end-to-end speed continue with the host acceptance below; the logs exclude the native entry projection and the final UI refresh.
3. With exactly the same current-page input, scale, rotation, and margin, rotate native/new/legacy on a model copy; record cold and warm P50 and P95, the placed count, the scale, the geometry, the input projection, planning, write-back, and refresh durations, and verify the real execution branch.
4. Verify undo and redo, cancel, save and reopen, and that the other pages stay unchanged. Only after these pass can a final acceptance conclude "it really takes effect, it is faster than native, and it is at least no slower than legacy".
