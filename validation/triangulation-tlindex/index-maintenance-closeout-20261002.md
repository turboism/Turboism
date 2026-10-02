# T051 index maintenance — 2026-10-02

Implementation and evidence collection are finished on `perf/cubism-edt-async`.
Production acceptance is **HOLD_RESOURCE_ENVELOPE**, not inherited from the prior
approved SHA. One new-candidate resource gate failed; no merge, push or release.

The helper creates edge buckets with capacity 2 (larger/nonmanifold buckets still
grow) and deindexes a proven physical victim under one bound-probe monitor.
The survivor identity scan, fallback/dirty repair, registry lifetime, geometry,
public woven descriptors, exact-version admission and native backups are unchanged.
Two regression cases cover bucket recreation/nonmanifold growth/negative and
degenerate edges/identity order, and repair after an unexpectedly absent bucket.

## Verification

52 affected tests passed (31 index, 12 transformer, 6 installer, 3 exact-host
shape across supported profiles); one final `devCheck` passed. Packaged helper
allocation workload: 2048 strip triangles, 50 warmups, five measured rebuilds,
query-order checks and clear; every round measured 852416 → 721312 bytes,
**15.38% lower owned-index allocation**. This is not a host RSS measurement.

Frozen production candidate SHA256:
`3a1666c11cb46fd26b34f914ca238f7bf0d80441175706a3f76c970fc39b4a86`.
Companion SHA256:
`0f980862fa236a7d2abd1e36ccc7aa970f1043592b242749a8e79396586a7c07`.
Only six helper-family class entries changed against the corresponding approved
base/companion; no entries added or removed, public descriptors identical.
Assembly refusals and sandbox read refusal are retained locally; neither caused
an extra host submission or weaker gate.

## Native 5303 pair

Both legs had the index enabled, same pinned fixture/config/driver/JVM flags.
Shared FIFO seq2501 baseline and seq2502 candidate each returned PASS, normal
exit, exact identity, unchanged fixture and safe cgroup destruction. Runner removed
both owned prefixes; evidence/JFR retained. All 2133 rows × 11 semantic fields
match between legs, including position/index hashes. Cross-cycle differences in
native edge versions/some indices are identical between corresponding legs.

| Measurement | Approved baseline | Candidate | Change |
| --- | ---: | ---: | ---: |
| Three native commands, wall | 74.891 s | 69.393 s | −7.34% |
| Recorded producer execution | 71.142 s | 65.907 s | −7.36% |
| Sampled operation Java CPU | 87.90 s | 78.79 s | −10.36% |
| Sampled operation peak RSS | 3062.33 MiB | 2665.16 MiB | −12.97% |
| Peak above own idle baseline | 120.91 MiB | **80.89 MiB** | candidate exceeds 80 MiB cap |
| Retained cycle3 − cycle1 median RSS | −6.08 MiB | 58.51 MiB | candidate below 64 MiB cap |

CPU-regression, absolute operationRSS-growth (20%) and retained-growth gates pass.
The candidate exceeds the 80 MiB own-baseline cap by 0.89 MiB. Do not round this
into a PASS or relax the budget. Baseline also exceeds that cap in this new pair;
this does not rewrite its earlier approved evidence. New-candidate production
acceptance remains on hold; exact-host human review remains distinct.

This is one sequential pair, not a statistically established speedup. CPU omits
unsampled boundaries (largest operation gaps baseline 1073ms/candidate 1022ms).
RSS/PSS are sampled, dependent on initial heap/native state; lower absolute peak
does not establish sustained memory savings. No forced GC or backup disablement.
JFR is preserved without uncalibrated phase attribution. No long-run stability,
absence of leaks, all-version incremental host acceptance or Atlas benefit claim.

Structured report: `index-maintenance-review-20261002.json` (62 pins reverified,
zero mismatches). Raw evidence under `build/t051-index-maintenance-r1`, including
`offline-review.json`, `artifact-review.json`, allocation logs, final-checks.log,
and `native5303-pair1/{baseline,candidate}/{native-review,resource-review}.json`.
Next production decision requires resolving the resource gate and reviewing this
new SHA; existing approved candidate remains the acceptance reference.
