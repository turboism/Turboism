# T057 angle guard: bounded native5303 comparison (2026-10-03)

One exact-host diagnostic pair passes complete output comparison and every original
CPU/RSS gate. Measured command wall improves **46.04%**, sampled Java CPU improves
**43.81%**. This supports the bounded5303 workload, not a statistically established
all-version or direct uninstrumented production performance claim. LaneC human
review remains required. No merge, push or release. Implementation commit: `531fb4473`.

| Metric | Frozen T053 | T057 angle guard | Change |
| --- | ---: | ---: | ---: |
| Three native commands | 66.691s | 35.989s | −46.04% |
| Producer time | 62.989s | 32.547s | −48.33% |
| Sampled Java CPU total | 75.12s | 42.21s | −43.81% |
| Sampled peak Java RSS | 3545.96MiB | 3021.62MiB | −14.79% |
| RSS peak above own baseline | 19.36MiB | 28.15MiB | Both below80MiB |
| Retained RSS cycle3−cycle1 | 0.645MiB | 0.672MiB | Both below64MiB |

All2133 ordered producer records match across11 semantic fields, and all four
ordered edge captures match. Both enabled-index legs use the same711-source
heavy fixture, three native commands, configuration, probes and JVM flags.
FIFO seq2530 and2532 both pass official/fixture identity, normal exit and bound
kernel scope destruction. Task2531 ran between legs; no measurements overlapped.
Supervisors removed both task-owned prefixes. No forceGC or backup disabling.

All original gates pass: Java CPU total regression≤0%, absolute RSS growth≤20%,
RSS above own baseline≤80MiB, retained3−1≤64MiB. The previous T051 exception is
not used. RSS/PSS depend on initial JVM/native state, so the peak RSS difference
alone does not establish an algorithmic memory saving. Maximum command-window
sample gaps were1029/1023ms; CPU omits unsampled boundaries.

The candidate's INFO whole-h installed-byte SHA exactly matches the compiled
reviewed weave, and the shared gate reports OWNED_FINAL_DEFINITION_MATCH. The
separate DEBUG angle marker is suppressed in this host logging configuration.
Four whole-recording Java stacks include TriangulationAngleGuard.reject; baseline
has none. Command-window native atan2 samples with h.d fall245/236/227→2/0/2.
Sample counts are neither calls, bypass rates nor CPU duration. Together these
provide execution/hotspot evidence; measured times remain independent metrics.

Scoped production SHA256:
`17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295`.
Diagnostic companion SHA256:
`82c0ecbaacf4c10c5fec8214a0fcee6fb1d680be970e1ad7bc189e9c5585d869`.
The companion alone admits the exact existing return-observed b() fingerprints.
Production rejects observer-modified builders. Only T057 preparation/transformer
and new helper/patcher families replace frozen T053; other entries are preserved.

Offline verification is recorded in the adjacent offline report:58JUnit tests,
devCheck,45,970 complete native assertions,1,387,528 numerical assertions,
30angle premain controls,15builder controls and three capture premain checks.
Raw failed artifact/archival attempts remain preserved. The review script first
assumed the former production directory name and then demanded a suppressed
DEBUG marker; final review uses the explicitly pinned production path and actual
INFO whole-class SHA plus admission, without changing production or rerunning host.
Adjacent JSON binds raw sources, commands, profiles, samples, outcomes and reports.
