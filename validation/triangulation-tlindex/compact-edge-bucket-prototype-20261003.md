# T065 owned compact edge-bucket prototype

**Prototype passes; production and host benefit remain unproven.** Inline firsttwo object references in an owned bucket; allocate an overflow array only for3+entries. Grow logical capacity by the existing ArrayList1.5×rule, discounting inline references. Delete by identity with order-preserving shifts and null released array slots. Drop overflow when size≤2. Return detached mutable exact ArrayList snapshots. No set/triangle-owner backlink or cache.

Final identity/order/reference-release/alias differential: **26647072 checks PASS**, deterministic16seeds×10000operations plus all0–32entry removal/drain/refill fixtures. Includes null, hostile equals/hashCode, equal-but-distinct objects, duplicate identities, every removal position and held snapshots. Java17release/alllint/Werror/-XverifyallPASS.

Generated copies of the actual SHA-pinned production index run its **unchanged33JUnit tests per arm**, all66PASS. Only bucket representation, snapshot and identity-bucket removal are substituted; native set operations, pending physicalabsence scan, rebuild/fallback/cardinality, synchronization and weak identity registration remain the original source. Full suite covers original equality-victim/mutation/rebuild/lifetime/concurrency cases. Helper and runner use all-lint/Werror; known serial/unchecked warnings in unchanged existing tests alone are excluded. This is an owned helper replay, not real host admission or sole-premain evidence.

Local ThreadMXBean construction allocation,20000escaping buckets per size after warmup (sink arrays/value allocated outside measurement):

| Entries | Baseline bytes/bucket | Compact bytes/bucket | Change |
| --- | ---: | ---: | ---: |
| 0 | 48 | 32 | -33.33% |
| 1 | 48 | 32 | -33.33% |
| 2 | 48 | 32 | -33.33% |
| 3 | 80 | 56 | -30.00% |
| 4 | 112 | 80 | -28.57% |
| 8 | 208 | 160 | -23.08% |
| 32 | 688 | 608 | -11.63% |

These are total bytes allocated while constructing/filling the local buckets, including intermediate growth arrays; they are **not retained footprints, object counts, Cubism arity weights or timings**. Exact values depend on local VM/reference layout. An earlier overflow-ArrayList version increased32entry construction allocation; replacing that object with an array removes the regression in tested sizes. Historical build runs remain preserved. No forceGC, host configuration/resource-cap changes or Editorlaunch.

Next: integrate a private nested bucket into the real helper, preserve woven public signatures and loader/resource closure, verify actual compiled/helper/premain packaging plus targeted regression/devCheck, freeze a scoped T057 comparison artifact, then FIFO full2133×11outputs/ordered-edge captures/originalCPU/RSS/normal-exit gates. Preserve native removal and physicalabsence proof; reject if no net host gain. No main merge/push/release.

Evidence: `build/t065-compact-edge-bucket-r1/array-overflow-selfcheck-r3/`, `final-full-index-r4/`; source/classes/commands/logs/dependency artifacts pinned by their reviews. Broader performance goal remains active.
