# T070 frozen balanced-run hotspot audit

The remaining settle identity scans recur in all four T069 recordings. Keep native geometric removal and physical identity-absence proof. General 3–8-pending unrolling still lacks evidence: dominant settle leaves remain BCI 163 (specialized two-pending scan), then BCI 92 (single-pending scan). The baseline index class SHA exactly matches the previously mapped deployed class `21697f94ca6788ae38188764809da4f7c080da12807f91526b6e208f08e8cfd0`.

| Frozen leg | Triangulation allocation sample weight MiB | Index map metadata sample weight MiB | Settle leaves BCI 163 / 92 | Command GC count | GC pause overlap s |
| --- | ---: | ---: | ---: | ---: | ---: |
| baseline1 | 2152.53 | 83.99 | 203 / 44 | 2 | 0.2027 |
| candidate1 | 2331.89 | 75.98 | 243 / 47 | 2 | 0.1434 |
| candidate2 | 2060.91 | 86.56 | 208 / 52 | 8 | 0.2233 |
| baseline2 | 2047.42 | 121.98 | 263 / 53 | 6 | 0.1723 |

The metadata column includes only `EdgeKey`, `HashMap.Node` and node-array allocation sample weights on index `put` stacks. These sparse estimates identify an independently owned allocation source; they are not exact metadata savings, retained bytes, CPU fractions or speedup. Keep existing `ArrayList` edge buckets and mutable detached query snapshots. The next prototype replaces only the long-key metadata table, avoiding per-key wrapper/node objects without changing native geometry or removal semantics. This is distinct from the rejected inline bucket storage experiment.

An edge-visit list/iterator shortcut is not selected: Java leaf counts for `h.a(l,TriPoint)` are 0/154/0/0, with native geometry, JIT inlining and sparse allocation attribution mixed into the observation. Its official bytecode eagerly reads three native edges before iteration. Any future shortcut would need to preserve that evaluation order, edge identity and mutable coordinate reads; the current data cannot establish removable CPU there.

Pre-first-command committed heap observations are 3528/3288/1976/2376 MiB, ages 44.87/31.96/31.08/37.82 seconds. They are sparse prior observations, not start snapshots. GC durations are not CPU durations, and these recordings cannot attribute cross-session footprint or T069's failed reverse-pair gates causally to bucket storage. Java and native JFR samples are separately counted; sample counts do not estimate calls or removable time. Truncated stacks remain explicit in the complete JSON.

All four frozen JFRs were bound to the same completed native reviews by SHA before export; no live performance job was present during this read-only audit. Standard JFR JSON export used stack depth 256 and execution/native samples, allocation samples, GC, pause, heap summary and deoptimization events. Raw export pins, complete cycle allocation/GC data and leaf/BCI rankings are retained in `build/t070-post-balanced-hotspot-audit-r1/`. The accompanying JSON pins the readers, summaries, official listing and baseline source.

Next checks: differential primitive-table behavior for all long keys, clustered/wrapped deletion, refill, resize, clear and released object references; then full actual index order/snapshot/nonmanifold/fallback/lifetime/concurrency checks and genuine composed premain refusal gates. Only after those pass should a new frozen native balanced comparison measure the original direct CPU/RSS/output/lifecycle caps. T069 and T066 remain rejected; T057 production is unchanged. No new host session, main merge, push or release; broader performance work remains active.
