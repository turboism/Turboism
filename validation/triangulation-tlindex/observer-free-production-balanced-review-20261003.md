# T076 exact-production four-leg result: fixed gates failed

All four predeclared FIFO jobs2589/2590/2591/2592 succeeded once. The exact unchanged
T057/T075 production Agents each cold-started via a single production premain with
the same observer-free task plugin/settings probe/kernel observer. Lifecycle, identity,
fixture, cleanup and 2133 ordered output rows per leg all pass. All24 process CPU reads
have independent PID/start/cgroup/HZ kernel brackets and nanosecond unit proof.

| Pair | Baseline wall s | Candidate wall s | Wall change | Baseline CPU s | Candidate CPU s | CPU change | Fixed gates |
|---|---:|---:|---:|---:|---:|---:|---|
| baseline1/candidate1 | 36.312 | 35.593 | -1.98% | 46.85 | 46.88 | +0.06% | FAIL |
| baseline2/candidate2 | 37.223 | 35.831 | -3.74% | 50.72 | 46.71 | -7.91% | PASS |

Combined descriptive totals: wall -2.87%, CPU -4.08%. This does not override the
first failing pair or establish statistical benefit. Production effect is materially
smaller than the earlier instrumented T075 result; that historical result remains
unchanged and is not substituted for this production comparison.

| Leg | Peak operation RSS MiB | Peak above own baseline MiB | Retained3 minus retained1 MiB |
|---|---:|---:|---:|
| baseline1 | 3083.27 | 51.70 | 1.20 |
| candidate1 | 2768.76 | 188.39 | 136.07 |
| candidate2 | 2650.85 | 10.12 | 0.61 |
| baseline2 | 3063.10 | 28.87 | 1.04 |

First candidate pair fails CPU no-regression (+0.03s), own-baseline RSS80MiB
and retained growth64MiB gates. Both paired absolute RSS20% caps pass. Pair2 passes
all five gates. Sampled operation windows include common preparation/output snapshots;
RSS variation and retained growth are observations, not proof of a native/index leak.
No forced GC, favorable retry, replacement, outlier removal or retrospective gate edit.

Decision: FAIL_FIXED_BALANCED_GATES; do not promote T075 on these results. T057 delivery
baseline remains. Original FIFO2588 destruction-only refusal remains separately preserved.
All-version publication/cold startup, long-run and Lane C human review remain incomplete.
No merge/push/release. Broader performance objective remains active. Next work should
investigate the production memory variation and observer-related effect-size difference
before spending more host runs on this candidate.

Raw reproducible review and all evidence pins:
build/t076-production-acceptance-preflight-r1/native5303-production-abba-r1/balanced-review.json.
