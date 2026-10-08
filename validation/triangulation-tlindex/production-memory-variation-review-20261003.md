# T076 frozen production memory replay: cause remains unproven

The same frozen sample replay is byte-identical across two executions. Every balanced
analysis input and saved native evidence pin is verified before analysis. This is a
read-only feedback loop for the observed failed memory caps, not new native evidence
or a retrospective gate change. The original four-leg result remains FAIL.

Candidate1's largest sampled RSS rise is128.875MiB across1003ms, with PSS rising128.764MiB.
The interval crosses command-dispatch-start and native command2's first CPU read. It
cannot be localized exclusively to either preparation or synchronous native execution.
RSS retained median3-1 rises136.074MiB, but retained-end heap-used3-1 falls94.073MiB.
Candidate2's retained-end heap-used3-1 rises325.878MiB while retained median RSS grows
only0.609MiB. Heap-used and resident process memory are distinct observations.

These observations do not support a simple monotonically retained-query-snapshot
heap-growth explanation; they do not disprove a leak. No committed heap, GC event,
allocation owner or native memory map category was captured. GC/residency and concurrent
host allocation hypotheses remain open. The peak's ~1s interval overlaps command2,
so command-external work alone is also not established as the cause.

Production candidate1/candidate2 total command CPU is46.88/46.71s. Baselines46.85/50.72s
vary more. Diagnostic candidate1/candidate2 is50.68/49.96s versus diagnostic baselines
60.13/59.23s. These are historical different-process observations, not controlled
proof of recorder overhead or its causal interaction with the optimization. The
smaller production effect and baseline variance require retaining the failed result.

Next diagnostic should collect timestamped GC/heap events with the exact production
artifacts and existing output binding, as distinct explanatory evidence with no
performance acceptance. No repeating the failed ABBA protocol for a favorable result.
No implementation fix is justified by present evidence. T057 delivery baseline remains;
all-version/long-run/LaneC requirements stay open. No merge/push/release.
