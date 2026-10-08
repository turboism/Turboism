# T075 small query snapshot: fixed host gates passed

The private detached mutable snapshot stores zero to two triangle references inline and creates its private ArrayList only on caller mutation. Larger results retain the existing ArrayList snapshot. The exact tested source is retained; T057 remains the delivery baseline pending uninstrumented acceptance and Lane C human review.

The protocol was committed as `6125182f4` before the first submission. Its SHA-256 remains `e8e1fb1323e333e10267373c52acc94d49863c9505304e196645c8c604e3cac3`. Exactly four FIFO legs B1/C1/C2/B2 ran once: 2571/2573/2574/2575. There was no replacement leg, favorable retry, cap change, or alteration of the frozen scripts or arithmetic.

| Pair | Command wall baseline → candidate | Change | Direct process CPU baseline → candidate | Change | Operation peak RSS change |
|---|---|---|---|---|---|
| B1/C1 | 43.3872789 → 37.7011866 s | −13.1054% | 60.13 → 50.68 s | −15.7159% | +0.7780% |
| B2/C2 | 43.9035150 → 37.8887924 s | −13.6999% | 59.23 → 49.96 s | −15.6509% | −1.3738% |

Both pairs independently pass strict command-wall improvement, direct total process CPU no regression, operation RSS ≤120% of paired baseline, candidate peak above own baseline ≤80 MiB, and retained3−1 ≤64 MiB. Candidate own-baseline increases are 21.921875/33.25390625 MiB; retained3−1 increases are 0.54296875/3.171875 MiB. The descriptive combined wall/CPU changes are −13.4044%/−15.6836%; they do not substitute for pair gates.

All four legs independently pass native result, frozen config, official JAR identity, unchanged fixture, normal exit, kernel containment destruction, and safe cleanup review. Every leg has 2133 producer rows; all 11 semantic fields and four ordered edge captures match across legs. All 24 CPU-boundary unit checks match independent same-process kernel brackets. Nanosecond unit verification does not establish provider timer resolution. Normal native cancel occurred after measurement; there was no external window action in these legs.

The 60 runtime and 5 bootstrap tests, devCheck, bootstrap license checks, 1,709,997 actual frozen snapshot checks, three-profile genuine sole-premain mutation/refusal/revocation and paired geometry checks, and actual TriangleList query probe already passed before protocol freeze; their evidence is preserved in the offline report. No source changes occurred after freeze.

This establishes the frozen instrumented protocol result for the 5303 heavy fixture, not statistical benefit, long-run memory stability, or uninstrumented production acceptance. Capture-only startup checks on the three profiles are not geometry or performance evidence. No merge, push, release, or delivery promotion is performed. Historical candidate rejections remain unchanged; the broader optimization goal remains active.

Raw evidence is in `build/t075-small-query-snapshot-integration-r1/native5303-abba1/`; the companion JSON pins every final input and the exact source and artifacts. The canonical Gradle jar remains experimental and must not be delivered. Next: review this exact Lane C evidence and define the separate uninstrumented acceptance protocol before any further host submission.
