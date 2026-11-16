# Heavy Outline Layout Measurement and Preview (2026-10-21)

This test is a **synthetic stress sample** of the standalone Dalsoo kernel, not a user model and not a Cubism host run. The images draw the coordinates actually computed, with no second layout pass; an item that did not fit is counted in overflow.

## Actual Results

- [Same 100-item set: QUARTER versus FREE](heavy-layout-comparison.png)
- [250-item complex mix: FREE / Abey](heavy-layout250-free.png)

The shapes include a 32-vertex deep concave star, an L shape, and thin rectangles. The page is 4096² at a fixed scale; the raw input outlines cover 75% of the page, and a buffered outline grows the centre inside the star kernel by 5%. The buffer is not a fixed pixel spacing and applies none of the production planner's outline simplification, automatic scale, multi-variant parallelism, or host write-back. Every input comes from a deterministic seed (9931 + count).

## Results

OpenJDK 17.0.20, serial run, initial heap 32 MiB, maximum heap 512 MiB. Each cell ran once at N=1; the preview export re-ran an already completed case and is **not a statistical median**. The times below cover only packing and the sampling thread shutdown; independent geometry verification and image generation are untimed.

| Input | Total vertices | Rotation / kernel | First-run time | Result | Raw outline coverage of the page |
|---|---:|---|---:|---|---:|
| Mixed 100 | 1418 | QUARTER / Dalalah | 17.23 s | Search finished, 60/100 | 55.66% |
| Mixed 250 | 3518 | FREE / Abey | 39.58 s | Search finished, 185/250 | 56.87% |
| Mixed 500 | 7010 | QUARTER / Dalalah | 60.00 s | Budget cancelled; 198 items placed at that point | Not verified |
| L shape 1000 | 6000 | QUARTER / Dalalah | 60.01 s | Budget cancelled; 532 items placed at that point | Not verified |
| L shape 2500 | 15000 | NONE / Dalalah | 60.00 s | Budget cancelled; 762 items placed at that point | Not verified |

Preview-export re-runs: mixed 100 QUARTER took 28.00 s and mixed 250 FREE took 51.48 s, with placement signatures identical to the first run. A separate mixed 100 FREE run took 3.70 s with 72/100 placed and 53.67% coverage. FREE places more items but covers less total outline area, so the count alone is not a density metric.

For every completed case the run independently verified identity, finite coordinates, in-page bounds, and pairwise Java `Area` intersection (intersection area tolerance 1e-5 page coordinate square units). A JSON `status=PASS` means only that the search ended normally and the geometry verification passed; it **does not mean every item was placed or that the density is optimal**. A budget cancellation is not a successful layout, and `placed` is only a progress count; its JSON `coverage=0` is a placeholder for an unmeasured value and must not be read as real coverage. The production code adds no default 60-second timeout; only this stress test passes the kernel a cancellation callback. An external 45-second exit boundary also applies.

## Speed and Memory Conclusions

The same mixed 100 QUARTER set took 35.08 s with 2518.74 MiB of cumulative allocation at the original commit `c3d454042283e10634793bb6b31246a3d89a4e95`; the current first run took 17.23 s with 55.88 MiB and the same result signature. The current preview re-run took 28.00 s with 129.25 MiB, so short-term JIT, GC, and system load matter a lot, and one measurement must not be used to claim a stable multiplier.

All three large samples failed to finish within 60 seconds but responded to cancellation normally. Their cumulative allocation was 1152 / 5189 / 1377 MiB, while the pack heap peak sampled every 25 ms was about 21–22 MiB and a Full GC after release left about 1.33 MiB. That shows high allocation turnover and search cost, and it reproduced no sustained retained-object growth. These numbers are **not host RSS or a peak memory ceiling**, and they cannot exclude a leak in the host layer.

**Current shortfall: a large-scale search over complex outlines is still slow, and a fully completed case still shows visible holes and overflow; the density and speed of the reference screenshot is not a promise.** Further optimization must target the candidate set and the placement strategy, because reducing allocation alone cannot fix the computational scale.

## Reproduction and Raw Evidence

In this directory `*-after.json` and `*-before.json` hold the first-run metrics, `.log` holds the progress log, and `.command.json` holds the matching command (the mixed 500 command follows). `*-preview-run.json` holds the preview re-run metrics, and `*-layout.json` holds the real outline coordinates and overflow IDs.

```sh
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --mode heavy --count 500 --rotation QUARTER
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --mode heavy --shape lshape --count 1000 --rotation QUARTER
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --mode heavy --count 250 --rotation FREE --abey --layout-json /tmp/mixed250-layout.json
MPLCONFIGDIR=/tmp/atlas-heavy-matplotlib python3 validation/texture-atlas-current-page/render-heavy-layout.py
```

The render script reads the three layouts exported in this directory and generates or modifies no placement.
