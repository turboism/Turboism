# T068 Windows process CPU counter feasibility

The opt-in command-boundary process CPU counter works on the tested Windows/Wine Cubism 5.3.03 JVM. FIFO 2548 completed normally, and six command-boundary cumulative readings agreed with independently clocked Linux counters for the same Java process. This is a single T057 baseline diagnostic, with no candidate or performance acceptance. T066 remains withdrawn with its original CPU gate FAIL.

| Cycle | Native command wall seconds | Direct process CPU seconds |
| --- | ---: | ---: |
| 1 | 13.7968880 | 24.81 |
| 2 | 12.0096744 | 13.57 |
| 3 | 12.6554659 | 13.55 |
| Total | 38.4620283 | 51.93 |

Process CPU sums all Java threads and can exceed wall time. These values must not be compared with previous legs collected using a different metric. The largest provider read span among all markers was 109,200 ns (0.1092 ms); this is instrumentation read duration, not counter accuracy or a confidence bound. Nanosecond return units do not establish provider resolution.

`--command-cpu-boundaries` adds a separate eight-column sidecar. Default driver behavior remains unchanged. Its sampler refuses unavailable, negative or decreasing CPU counters, invalid read clocks, and throwing reads permanently. Every read records monotonic and epoch start/end clocks. The kernel reader similarly brackets its single `/proc/<pid>/stat` read before PSS collection. The analyzer requires three ordered command start/return pairs, positive CPU deltas, consistent clocks and Windows OS identity; Windows feasibility additionally requires kernel unit verification. All six cumulative readings fell between surrounding same-process kernel reads, using the declared 2/HZ allowance for the two quantized user/system fields (0.02 seconds at HZ=100). This allowance applies only to unit verification and changes no performance gate.

Offline evidence: 14 Java counter checks, 5 analyzer regressions and 3 process-reader regressions PASS. Java 17 lint/Werror and full verification PASS. Normalized bytecode comparison covered 427 existing methods; only `FixedDriver.resourceMarker` changed. Native command and producer capture methods are unchanged. Existing five-column resource windows remain intact. Composition and helper/build pins are recorded in the accompanying JSON.

Native evidence: sequence 2548, job `ba375b23-7478-4324-a2ab-578e9335aadf`, prepared `9f40e0d5f71f3822ff7ffe31e0fe0515b238fd1c9dfe3cbbea9e8d911c8d2f7b`. Identity, unchanged fixture, normal exit, cleanup and independent full producer-output review PASS (711 sources × 3 cycles, 2,133 producer rows and four ordered edge captures). The CPU sidecar was copied and hashed by that same run-bound output reviewer. Native cancellation occurred after all measurement windows; no external window operation was used. Kernel scope destruction and normal validation cleanup were verified.

Production remains T057 SHA `17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295`; capture companion SHA `82c0ecbaacf4c10c5fec8214a0fcee6fb1d680be970e1ad7bc189e9c5585d869`. Scene diagnostic SHA `83054c5add0c82eddb0a846cd2973dd6eb980fe67c9d1cd4acd8c4a4e68f663e`. Frozen raw evidence lives in `build/t068-command-cpu-boundaries-r1/` with input and evidence hashes in the accompanying JSON. No runtime production edits, main merge, push or release.

Next: freeze a separate balanced comparison protocol before any new baseline/candidate submissions. Use the direct total-process CPU counter as the declared primary CPU metric, retain original CPU ≤0%, RSS, output, configuration and lifecycle requirements, and report sampled CPU separately. Predeclare arm order, run count, aggregation and invalid-run handling; do not retroactively approve T066 or repeat runs until a favorable result appears. Broader performance work remains active.
