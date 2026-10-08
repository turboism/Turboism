# T064 allocation/GC and hotspot audit after T063 rejection

T063 remains withdrawn; T057 remains production baseline. No new host leg or production modification. Shared FIFO2540/2541 were terminal before exporting the frozen JFRs.

| Three command windows | T057 baseline | T063 rejected candidate |
| --- | ---: | ---: |
| Allocation sample weight, all stacks | 2277.42 MiB | 2225.95 MiB |
| Allocation sample weight, triangulation stacks | 1796.29 MiB | 1780.17 MiB |
| GVector2 sample weight, triangulation stacks | 279.49 MiB | 228.07 MiB |
| GC events starting within commands | 3 | 2 |
| GC pause overlap with commands | 0.1411 s | 0.0874 s |
| Last observed committed heap before first command | 2400 MiB | 3712 MiB |
| Last pre-command heap observation age | 39.67 s | 45.52 s |
| settle Java leaf samples | 276 | 324 |

The larger committed heap is present before the first native command, so operation-window RSS alone cannot identify a vector-copy allocation regression or leak. Candidate cycles2/3 have no command-window GC/heap summary, and prior observations are sparse. Allocation sample weights decrease2.26% overall and18.40% for GVector2, but are estimates rather than exact allocated or retained bytes. This does not overturn T063's two failed RSS gates or establish stable speed.

Exact deployed index classes are byte-identical across arms: `21697f94ca6788ae38188764809da4f7c080da12807f91526b6e208f08e8cfd0`. javap maps dominant settleBCI163 to the **already specialized two-pending** survivor scan andBCI92 to the single-pending scan. General3–8pending unrolling is not supported as the next target. Keep actual native removal and physical identity-absence proof; no assumption of frozen mutable geometry.

Next bounded prototype: inline the first two references in private edge buckets, allocate overflow only beyond two entries, and retain insertion order, duplicate handling, identity removal, mutable detached query snapshots, rebuild/fallback and weak-owner lifetime. Current buckets allocate ArrayList(2) plus an Object[]; put-stack samples identify both. Baseline put-stack ArrayList/Object[] sample weights are52.0/39.91MiB (15/14sampleevents), too sparse for precise savings. An owned ArrayList differential harness must cover0/1/2/3+entries, identity-vs-equality, removals/refill, alias-free snapshots and nonmanifold order before any production integration or native claim. It addresses allocation, not the dominant scan cost; benefit remains unproven.

Analyzer regression4tests PASS: timezone/duration, cross-boundary GC pause clipping, allocation-boundary exclusion, sparse/empty stacks and invalid marker/weight rejection. Raw pinned exports, complete six windows, GC/heap observations, deoptimization and stack records: `build/t063-vector-copy-integration-r1/allocation-gc-audit-r1/`. GC durations/sample counts are not CPU proportions. LaneC human review remains pending; no main merge/push/release. Broader performance goal active.
