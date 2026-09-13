# Offline scale-search and partition policy evaluation

This is synthetic planner-only evidence, **not a new Cubism/UI or native benchmark**. Previous Circle/Geometry host measurements remain unchanged; they predate this optimization and the q guard.

## Method and retained evidence

`OfflinePolicyBench.java` generates square/mixed/wide inputs with 16/32/64/128/500 items, densities 0.45/1.15, automatic/fixed-1 scale, and serial/parallel requests: 120 scenarios per variant. Rotation is allowed, margin is 3; page dimensions are generated from content area (`ceil(sqrt(area/density))+6`), not the real fixture texture sizes. Deterministic seed is 51+count.

Five isolated source variants run in two independent JVM forks, forward then reverse order. Each scenario has three warmups and five measured calls; each CSV stores their median. Analysis averages the two fork medians per scenario before taking candidate/baseline ratios. Heap 256–512 MiB, common ForkJoin parallelism 4. Compilation precedes timing. Input reversal, repeated-result equality, finite/fixed scale, unique IDs, page bounds, rotated dimensions and padded separation are checked outside timing. All variants passed these checks and had stable output hashes across forks.

Retained `offline-policy-evidence/` contains all ten CSVs and the source/SDK/harness/JVM inventory. No host was started. These small wall times are sensitive to JIT and scheduling; N=2 forks does not establish a universal speedup.

## Decision

| Candidate | Observed result | Decision |
| --- | --- | --- |
| Six sort orders in every serial binary-search trial | First exploratory run: 10/30 auto scenarios improve scale, no sampled quality loss; median time ratio 1.43× baseline | Do not enable by default; improved scale has a measurable cost |
| Hard partition threshold 32 | First exploratory run: no sampled count/scale loss for affected 16-item cases | Prefer preserving regional candidates rather than assuming this generalizes |
| Hard partition threshold 64 | Mixed 32 fixed-scale placement count 21→19; wide 32 count 20→17 | Reject: faster is not worth dropping placeable images |
| Serial preflight for parallel requests below 32 | Second run: identical count/scale in all 120 scenarios; affected 12-case median time ratio 0.754× | Adopt with fallback, keeping partition admission at 16 |

The preflight returns early only when serial packs every input. At a fixed scale, full-input candidates tie on the current count/content-area score. If serial is partial, the original regional candidate still competes with the cached serial result. Placement coordinates can differ; byte-identical output to the old policy is not promised. The serial default and requests with at least 32 items are unchanged.

### Adopted preflight: affected 16-item cases

Times below are milliseconds, averages of two fork medians. Ratio below 1 is faster.

| Shape | Density | Scale mode | Baseline | Preflight | Time ratio |
| --- | ---: | --- | ---: | ---: | ---: |
| square | 0.45 | auto | 1.1347 | 0.4061 | 0.358 |
| square | 0.45 | fixed | 0.8233 | 0.2851 | 0.346 |
| square | 1.15 | auto | 4.5840 | 2.5850 | 0.564 |
| square | 1.15 | fixed | 0.5711 | 0.5720 | 1.002 |
| mixed | 0.45 | auto | 0.3766 | 0.4512 | 1.198 |
| mixed | 0.45 | fixed | 0.2589 | 0.2866 | 1.107 |
| mixed | 1.15 | auto | 7.1518 | 5.4545 | 0.763 |
| mixed | 1.15 | fixed | 1.2266 | 0.9150 | 0.746 |
| wide | 0.45 | auto | 0.2086 | 0.0490 | 0.235 |
| wide | 0.45 | fixed | 0.0942 | 0.0470 | 0.499 |
| wide | 1.15 | auto | 1.2440 | 1.0140 | 0.815 |
| wide | 1.15 | fixed | 0.0983 | 0.1448 | 1.473 |

Some cases are slower (up to 1.47× here); the median is not an across-the-board guarantee. No change claims native-equivalent scale or mathematically maximum feasible scale. The eight-trial automatic search remains a bounded packing heuristic.

## Regression and reproduction

The new full-fit 16-item serial/parallel equality test failed before the patch and passed after it. The 32-item dense regression preserves the useful regional result (21 placed vs serial 19). The entire plugin suite passes: 40 tests, zero failures.

The runner now requires an explicit frozen baseline and a fresh output directory, and verifies the baseline planner SHA-256. This prevents accidentally benchmarking the patched production source as the old baseline or overwriting evidence:

```sh
python3 validation/texture-atlas-current-page/run-offline-policy-bench.py \
  --baseline-dir build/atlas-offline-policy/baseline \
  --output-dir build/atlas-offline-policy-repeat
```

The original frozen source copies remain under `build/atlas-offline-policy/baseline`; this local build directory is required for that command. Baseline planner hash: `936621a3372505bc8e954d79b02eb272905bde781f29cf995c2cd9d865120a51`. Other input hashes are in the inventory. Do not substitute current production sources. No new real-host validation is authorized by this document.
