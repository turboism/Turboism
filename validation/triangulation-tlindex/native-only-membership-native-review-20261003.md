# T077 exact-production balanced result: failed fixed gates

FIFO2600/2601/2602/2603 each succeeded once. Client52811 terminated normally.
All8532 ordered output rows and24 independent kernel CPU comparisons pass, with
normal exits, exact identities, unchanged fixture and safe original-bound cleanup.
Source/protocol commit `a17e616ad` predates submission. No retry or replacement.

| Pair | Command wall change | Direct process CPU change | Sampled peak RSS change | Failed gate |
|---|---:|---:|---:|---|
| B1/C1 | -0.2550% | +2.0017% | -41.4177% | CPU regression |
| B2/C2 | -10.5849% | -19.1107% | +26.8574% | RSS exceeds20% cap |

Both candidate own-baseline80MiB and retained64MiB gates pass. Combined wall-5.7863%
and CPU-9.6619% are descriptive; they cannot override either failed pair. Large
baseline/candidate RSS variation is observed, not evidence of a candidate leak or
its absence. No threshold change, forced GC, favorable retry or statistical claim.

The T077 membership-only source/tests were withdrawn to T057 semantics after all
four legs and their original frozen analyzer finished. Restored33 index tests and
`devCheck` pass; source/test text equals pinned T057 after whitespace normalization.
Rejected source, six compiled index classes, exact candidate and native results are
archived under build/t077-native-only-membership-r1. Original protocol/results stay
unchanged; replay requires the pre-submission source commit or archived source rather
than the restored checkout. T075 remains withdrawn as well.

T057 remains delivery baseline. All-version, long-run and Lane C review remain open.
The separate existing-recording caller diagnostic identifies query-triggered settlement
and native mesh edge-existence checks as the next evidence-backed leads. This result
does not establish another optimization or authorize production promotion.
