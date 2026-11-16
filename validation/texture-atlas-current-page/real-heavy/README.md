# heavy FREE Layout Validation (2026-10-21)

Real `heavy.cmo3` current page, Cubism 5.3.03, 4096×4096, spacing 3, FREE, Abey, automatic scale, BALANCED, serial.
File SHA-256: `029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`.
The binary model does not enter the repository.

By default each outline offers at most 24 candidate contact vertices; FAST uses 8 and DENSE uses the full search. The limit applies to candidate generation only, because collision and scoring still use the complete preprocessed outline.
The limit may reduce compactness. When no consecutive contact candidate yields a feasible position, the limited mode tries the Dalsoo internal discrete angles and corner candidates and never calls the native algorithm.

## Host Results

- Official queue task `eddda3fa-7612-456d-a0e7-255cdcb1cb1c` exited normally, cleaned up, and reported succeeded.
- run: `queue-96361ffbae724f4eb5b583eefbf695fa`.
- New plugin SHA-256: `e476f712a3f0bf0d32962068cc94c9278c084dc726481c7db73ee0b70c27cbda`.
- Click OK to progress window close: **29,347.8585 ms**; layout method: **28,843.9122 ms**, including validation probe overhead.
- **101/101, scale 1.0, overflow=0, branch=handled, returned=true**.
- Production log: `placements=101 overflow=0 scale=1.0 {candidateVertexLimit=24, autoScaleMaxTry=12, autoScaleTolerance=0.005, autoScaleAttempts=bisect, rotationMode=FREE, kernel=dalsoo}`.
  `autoScaleAttempts=bisect` is an existing diagnostic label; the search is really a descending scale gradient, and this run finished on the first round.
- Applying the real preprocessed outlines independently to the written-back matrix and checking the full page bounds and 3-pixel spacing with Java `Area` found 0 overlaps. The LayerRef set matched the packed result matrix.
- `heavy-result.png` is a direct niri capture of this task's "edit texture set" window with the real textures. The source model was neither saved nor modified.
- The rectangle-bounding-box overlaps and inside counts in `timing-001.json` cannot represent the correctness of rotated concave polygons; use `heavy-geometry.json`.
- The validation script count=0 is the sentinel for "read every item of the heavy current page"; the real item count is 101.

## Baseline and Memory

The original full-search host run did not return within a 120-second observation, task `b3352f17-112f-4ffc-98a5-49d1262dbfdc`.
Offline replay of the same real input: the original kernel placed only 6 items in 30 seconds, and an automatic-scale round that kept the full candidate set but added a collision index had still not finished after 240 seconds.
The shipped limited-candidate kernel replayed offline in about 23.2 seconds with 101 items at 100% scale, and the independent outline, buffered outline, and spacing checks found no overlap.
One initial host re-run accidentally read the old standalone plugin JAR and also timed out at 120 seconds; it does not count as a performance result of the new kernel.
The plugin jar task then ran explicitly, recorded the hash above, and passed host validation.

Three consecutive real heavy kernel layouts in one JVM with `-Xmx512m` all completed, with post-GC heap usage of 6,689,752 / 6,750,760 / 6,750,760 bytes and no observed sustained kernel retention growth (`kernel-retention.txt`).
The heavy model itself uses a lot on the host: this round the heap rose from about 1.89 GiB to a peak of 2.45 GiB with no GC in between. This is an allocation observation, and one run cannot establish that every leak is excluded; cumulative allocation is not leak volume.

## Regression

All 44 plugin tests passed, including random concave outline index equivalence, the real near-collinear numeric regression, unsampled concave edge geometry collision, per-item kernel progress, six full search result signatures, cancellation, and settings persistence.
The runtime auto-layout transformer and dialog tests passed; with Dalsoo explicitly selected, a missing entry point, a failed return, or a thrown exception never falls through to the native method and never reports a false success.

After merging main: the 192 runtime texture atlas tests, the 44 plugin tests, and the format checks all passed; the 76 offline probe assertions compiled and ran separately. The full offline packaging matrix did not finish because this worktree has no geometry-100 and other test models configured; this heavy host task and its binary model were prepared and verified separately.
