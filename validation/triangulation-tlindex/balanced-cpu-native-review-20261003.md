# T069 fixed balanced comparison: failed gates

**Keep the compact-bucket candidate withdrawn and retain T057.** Four predeclared ABBA legs completed with full output, lifecycle and CPU-unit verification. The first pair improved wall/CPU; the reversed pair regressed both. The fixed rule requires both pairs to pass. Descriptive aggregate wall −0.40% and direct CPU −0.42% cannot override the failed reversed pair. These observations do not establish statistical benefit, stable regression or causation.

| Pair | Baseline wall s | Candidate wall s | Wall change | Baseline direct CPU s | Candidate direct CPU s | CPU change | Result |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Baseline then candidate | 38.9628683 | 37.4479996 | −3.89% | 52.20 | 50.86 | −2.57% | PASS |
| Candidate then baseline | 37.3286784 | 38.5407449 | +3.25% | 51.72 | 52.62 | +1.74% | FAIL wall/CPU |

| Leg | FIFO sequence | Peak RSS MiB | Peak above own baseline MiB | Retained cycle 3−1 MiB | Sampled CPU s |
| --- | ---: | ---: | ---: | ---: | ---: |
| baseline1 | 2550 | 4115.9375 | 23.7773 | 8.0938 | 47.81 |
| candidate1 | 2552 | 3942.4336 | 30.5664 | −279.0820 | 47.41 |
| candidate2 | 2554 | 2712.1562 | 65.2539 | 43.5625 | 46.67 |
| baseline2 | 2555 | 2788.1719 | 38.4961 | 5.7148 | 49.22 |

Both pairs pass all original memory caps: candidate peak RSS change −4.22%/−2.73% (≤20%), candidate own-baseline increases 30.57/65.25 MiB (≤80), retained changes −279.08/43.56 MiB (≤64). Memory is sampled, not a continuous maximum. Large cross-session footprint variation and a negative retained change do not prove compact-bucket retention savings. The resource reports separately preserve RSS/PSS, user/system CPU, average/peak occupancy, auxiliary process metrics, idle windows, marker heap readings and sample gaps (operation maximum gaps 1,052/1,053/1,037/1,027 ms).

Each primary CPU value sums three direct start/return process counter deltas for all Java threads. All 24 command-boundary cumulative readings passed same-process independent kernel unit checks, with the declared two-field 2/HZ allowance solely for unit verification. Provider return units do not prove its granularity. Sampled CPU remains secondary; it omits command edges and must not replace the fixed primary metric. Baseline totals are wall 76.2915467 s/direct CPU 103.92 s; candidate totals are wall 75.9887445 s/direct CPU 103.48 s. No rounding tolerance changed the gates.

All four isolated sessions normally exited with exact identity, unchanged fixture, frozen configuration and safe cleanup. Independent review confirmed all 2,133 producer rows per leg across 11 semantic fields and four ordered edge captures match the first baseline. Native cancellation occurred after all windows. Kernel scopes were destroyed and normal validation cleanup was recorded. Four frozen prepared IDs and all inputs are in the protocol JSON; all per-run evidence/report hashes and cycle metrics are in the accompanying native-review JSON. Raw evidence: `build/t069-balanced-cpu-r1/native5303-abba1/`.

Protocol commit `ef906d23d` preceded the first submission; protocol SHA `e718cd7ee83249be65492a3f10103ba12af3cdce6e333e6aac91216b6e0ed55b` and frozen scripts remained unchanged throughout. The inherited output reviewer contained a directory-name rewrite typo in its local offline/artifact references. Initial baseline/candidate reviews failed on missing local files. Their partial evidence directories were preserved; byte-identical pinned reports/artifacts were supplied at the referenced paths, and the same read-only reviewer was rerun against the same completed sessions. No host rerun, substituted artifact or methodology change occurred. The complete errata and hashes are retained.

No fifth leg, changed cap or favorable retry. Historical T066 gate FAIL remains unchanged. Production source and frozen baseline remain T057; no main merge, push, release or Lane C human acceptance. The broader performance goal remains active. Next work should audit the existing new-protocol traces for a distinct ownership-safe hotspot hypothesis, rather than repeat this candidate until a favorable number appears.
