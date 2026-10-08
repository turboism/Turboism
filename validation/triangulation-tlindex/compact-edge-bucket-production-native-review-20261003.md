# T066 compact edge-bucket integration: rejected original CPU gate

**Decision: withdraw T066 production integration and retain T057.** This single diagnostic pair observes lower command wall time and passes all candidate memory caps, but sampled JavaCPU increases0.31s(+0.64%) and fails the original ≤0% gate. No priorT051exception or favorable rerun is applied. The small sampled difference does not establish a stable regression or prove the storage idea has no effect.

| Three native auto-connect commands | T057 baseline | T066 candidate | Change / gate |
| --- | ---: | ---: | ---: |
| Command wall time | 39.912 s | 37.724 s | −5.48% |
| Producer time | 36.189 s | 34.060 s | −5.88% |
| Sampled Java CPU | 48.21 s | 48.52 s | +0.64%, FAIL≤0% |
| Peak Java RSS | 3104.50 MiB | 3152.82 MiB | +1.56%, PASS≤20% |
| Peak RSS above own baseline | 39.68 MiB | 26.28 MiB | PASS≤80MiB |
| Retained cycle3 minus cycle1 | 0.71 MiB | 0.80 MiB | PASS≤64MiB |

All2133×11semantic fields and four ordered edge captures match exactly. Each of3cycles covers711sources. FIFO2544/2545 passed normal exit, identity, unchanged fixture and kernel cgroup destruction; isolated prefixes independently confirmed absent. Native cancel occurs after all windows; no external window action. Exact frozen staging and companion-vs-production entries independently verified.

CPU excludes unsampled boundaries (maximum gaps baseline1028ms/candidate1034ms); RSS/PSS sampled rather than continuous maxima. Do not interpret the CPU difference as causal/statistically established or the wall improvement as stable/uninstrumented/all-version production gain. The original gate still failed. Local allocation reductions and successful offline tests remain valid within their owned scope.

Only this session's private Bucket/index storage and added nonmanifold test were withdrawn; production source/test restored byte-for-byte to committed baseline. Sources, exact candidate classfamily, frozen JARs, all offline controls and native negative acceptance evidence retained in `build/t066-compact-edge-bucket-integration-r1/`, including `rejected-integration-snapshot-r1/`. Experimental productionSHA `4b6145a7ada0eb77c4df72e813897f423bb4da418da788befcc4a6aab33e6b88` and companionSHA `a111ba81e518882146b008682346ab54188447e40d368df247f783d0cafd2a07` are not approved delivery. Complete native h/TL weave was unchangedT057 throughout.

Next read-only audit: quantify actual CPU observation boundaries from the frozen resource trace, separate interval uncertainty from total point estimates, and inspect allocation/GC/Java leaves before another candidate. No threshold relaxation, manufactured favorable retry or production acceptance. LaneC human review pending/no main merge/push/release. Broader performance goal active.
