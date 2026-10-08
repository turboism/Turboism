# T076 same-recording native ownership: feasibility passed

FIFO2599 succeeded once with unchanged T057 production Agent and the explicitly
enabled task-only JFR clock plugin. Source/protocol commit before submission:
`cf6af03f5`. All 2133 output rows match the reference; six independent kernel CPU
readings, host identity, unchanged fixture, normal exit and safe cleanup pass.
The original bound cgroup was destroyed. No retry or replacement measurement.

Three ordered native invocation events bind the exact task and EDT thread29.
All30 primitive CPU/clock fields match the independent text evidence. Ownership
uses the same recording's event envelopes, not a Java-to-JFR epoch correction.
The guarded analysis completed successfully in24.085seconds with no shared
performance jobs. The native reviewer and ownership analyzer both pass.

| Command | EDT execution samples | settle leaf samples | Objects.checkIndex leaf samples |
|---|---:|---:|---:|
| 1 | 631 | 140 | 142 |
| 2 | 531 | 102 | 114 |
| 3 | 635 | 150 | 145 |

All three scopes have no empty or truncated execution stacks. Excluded whole-recording
events include12197 native-method samples and6746 other-thread execution/allocation
events. Global GC summaries remain distinct from command EDT ownership.

Repeated `settle` leaf samples now justify investigating its survivor scan with
properly bound native scopes. The scan proves physical absence because native
geometric equality can remove another identity. Mutable native corners and custom
triangle/point equality prohibit simply skipping this proof. The next design must
preserve actual native victims, insertion order and dirty-state fallbacks.

`Objects.checkIndex` leaf samples alone do not identify its caller. This recording
uses T057, which predates the T075 QuerySnapshot optimization; attributing those
samples to QuerySnapshot would be incorrect. Allocation estimates also show query
ArrayList copies, index key arrays and buckets, but cannot establish retained memory
ownership or causal savings. No new runtime optimization is accepted by this result.

Counts are sampled stacks, not CPU durations or percentages. Allocation weights are
estimates, not exact allocation/live bytes. Recording overhead changes conditions.
Historical clock-unproven reports, FIFO2588 refusal and failed production ABBA gates
remain unchanged. T057 remains the delivery baseline; T075 is not promoted.
All-version, long-run and Lane C review remain open. No merge/push/release.

The adjacent JSON report pins raw native review, outcome, ownership and guard files.
