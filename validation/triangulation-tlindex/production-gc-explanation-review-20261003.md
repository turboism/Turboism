# T076 GC/heap explanation: memory growth also occurs in baseline

Two distinct predeclared diagnostic FIFO jobs2593/2594 succeeded once, no retries.
Exact production artifacts and existing observer-free driver are unchanged; only
GC/heap logging was added. Output4266 rows and12 independently kernel-bracketed CPU
reads verified. Normal exits/identity/fixture/cleanup pass. No diagnostic timing is
used to replace the original failed four-leg performance comparison.

| Diagnostic | GC heap summaries in resource windows | Observed committed heap MiB | Largest sampled RSS rise MiB | Retained median RSS3-1 MiB |
|---|---:|---|---:|---:|
| T057 baseline | 14 | 2000 -> 2152 | 137.05 | 159.84 |
| T075 candidate | 18 | 1920 -> 2040 | 83.25 | 95.60 |

For both diagnostics, the largest sampled RSS rise lies between a lower-capacity
GC summary and the first higher-capacity summary. Capacity increased152MiB for
baseline and120MiB for candidate. A summary timestamp is an observation, not the
exact resize event: baseline bracket spans8.505s and candidate spans34.150s. This
supports heap commitment/residency variation as an explanation to investigate;
it does not prove causality or ownership. Memory growth also occurs in baseline,
so a candidate-specific monotonically retained snapshot leak is not established.

The original candidate1 RSS rise remains unexplained at owner level: its process
had no GC logs. These new processes cannot retrospectively fill that gap. No heap
dump/full-GC live size/native map categories/allocation stacks were collected.
Diagnostic logging changes runtime conditions. Candidate2's earlier falling/stable
RSS likewise cannot prove absence of a leak. Original CPU/80MiB/64MiB failure remains.

Actual HotSpot parser smoke passed38 summaries; original smoke log is preserved
under build and the temporary Java prototype/classes were removed. This check is
not vendor admission. Real vendor G1 logs independently parsed114/124 summaries
including startup. Correlation recomputes identity-bound resource analysis and checks
all frozen/saved evidence hashes; projections are reused only when byte-identical.

Decision: do not make a production memory fix or promote T075 from these data.
Next optimization work should target measured production query/triangulation cost
with allocation/CPU ownership evidence, while treating cold-start GC/residency
variation explicitly in any separately predeclared protocol. No post-hoc gate
relaxation, forced GC, favorable rerun or historical result change. T057 remains
baseline. All-version publication/cold-start, long-run and LaneC requirements remain
open; broader goal active. No merge/push/release.
