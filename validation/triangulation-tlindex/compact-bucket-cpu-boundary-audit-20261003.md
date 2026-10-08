# T067 T066 CPU boundary audit

**Existing trace cannot resolve the0.31sampled-CPU-second difference. T066 remains withdrawn and its original gateFAIL unchanged.** No new host leg or production change. FIFO2544/2545 were terminal before this audit; later2546 was also terminalfailed when queue status was read.

The original collector reads each process's /proc/stat CPU counters, then comm/PSS and memory pressure, then stamps the whole batch. The CPU read is not at that batch timestamp. Under serialized batches and monotonic recorded epoch time, the read falls between the previous batch stamp and current stamp. Therefore the first batch stamp after a command boundary is insufficient to prove its CPU read occurs after it; the next batch is needed. This audit exact-pins the collector, refuses unknown serialization, verifies constantPID/start/cgroup/frequency and nondecreasinguser/systemticks/timestamps, and does not interpolate usage uniformly across a second.

| Leg | Cycle | Start omitted ms | End omitted ms | Original inside-sample CPU s | Conditional batch bracket CPU s |
| --- | ---: | ---: | ---: | ---: | ---: |
| baseline | 1 | 315 | 1000 | 23.12 | 19.22–30.42 |
| baseline | 2 | 305 | 831 | 13.41 | 12.14–18.71 |
| baseline | 3 | 514 | 965 | 11.68 | 10.63–16.07 |
| candidate | 1 | 148 | 605 | 24.93 | 21.37–31.97 |
| candidate | 2 | 804 | 289 | 11.86 | 10.53–18.24 |
| candidate | 3 | 411 | 450 | 11.73 | 10.41–15.88 |

Totals: baseline original48.21s, conditional41.99–65.20s; candidate original48.52s, conditional42.31–66.09s. Omitted edge wall intervals total3930ms vs2707ms. Candidate-minus-baseline conditional range−22.89to+24.10CPU seconds includes both signs. These are deliberately conservative **counter/order brackets, not confidence intervals or a statistical noise estimate**. They do not prove which candidate uses less CPU, invalidate the published point estimates, establish causation, or convert the failed gate toPASS. Kernel ticks are accounting units; no per-read clocks were recorded. Clock monotonicity/serialized ordering is an explicit condition, not an unqualified true-time bound.

Baseline has one no-host batch during terminal process exit, outside all command windows. It is excluded explicitly; internal gaps, missing outer observations, ambiguousPID or counter drift refuse. Native lifecycle proof and raw trace remain unchanged. Regression6testsPASS: delayed/exacttimestamp boundary handling, identity/frequency/cgroup/counter/epoch failures, terminal-vs-internal gaps, missing outer brackets, insufficient/overlapping windows. Original48.21/48.52s estimates reproduced exactly from the same frozen traces.

Next bounded diagnostic prototype: record processCPU nanoseconds directly at command start/return using the runtime OSManagementBean, bracket every read with monotonic timestamps, and record each Linux /proc CPU read's own start/end clocks before expensive PSS. Keep processCPU as primary total-Java metric; any EDT/threadCPU is secondary diagnostic only. Unknown/unavailable/decreasing counters must refuse measurement, never become zero orPASS. Verify availability/units/monotonicity on the actual Windows/Wine JVM before relying on it. Keep all native command/output/probe/config/CPU≤0%/RSS caps/lifecycle constraints; new measurement protocol needs separately frozen/predeclared comparison, no retroactive approval or repeated runs until a favorable number appears.

Evidence: `build/t067-cpu-boundary-audit-r1/review.json`; complete boundary rows/input hashes in accompanying JSON. Production remainsT057, T066historicalexperimental only. No main merge/push/release; broader performance goal active.
