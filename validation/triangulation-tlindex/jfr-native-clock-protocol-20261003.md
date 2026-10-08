# T076 same-recording native invocation feasibility

Freeze one distinct FIFO T057 exact-production baseline job before submission.
No retry/replacement/performance acceptance. Original failed ABBA and clock-unproven
ownership recordings remain unchanged. T057 production Agent and settings probe
bytes unchanged; replace only the task diagnostic plugin with7a60b17e… clock build.
Use same source selection, three commands/2133 outputs and kernel CPU unit checks.

The explicit T076_JFR_NATIVE_INVOCATION_V1 opt-in is required. A custom JFR duration
event starts before the existing invokeMeasured and ends after it: only the already
resolved native method and its two CPU reads are enclosed. Preparation, bound-source
checks, output capture/hash and event payload persistence/commit are outside.
No producer recorder, official-class transformation or extra premain is introduced.
Failed invocations emit nativeReturned=false and cannot authorize a complete result.

Require three ordered unique events, exact task/run identity, same native EDT thread,
normal native return and30 matching primitive CPU/clock fields against independent
text rows. Use event start/duration on the recording's own JFR clock for attribution;
no constant-offset correction or Java/JFR epoch equivalence assumed. Conservative
integer-ms interior projection discards at most1ms at each edge. Retain only native
EDT ExecutionSample/allocation events for method attribution; native-method eventloop
samples and other threads are explicitly counted as excluded, not active CPU cost.
Global GC observations remain separate. Counts/weights are estimates, not durations,
exact allocation bytes, live heap or stable benefit.

Offline actual recording verifies scope with a supplied Java epoch offset+5s, before/
inside/after markers, opt-in/EDT/cycle/disabled-recording refusals and native failure
propagation. Complete three-event/independent text binding passes; wrong task, wrong
counter and failed/incomplete event recording refuse. Production loader/inspector
harness55assertions and packaged helpers24/14/17/13 plus18clock assertions pass;
these are fixtures/assertions, not host scenarios. No full generated-template lint claim.

Real native lifecycle/identity/unchanged fixture and original-bound destroyed OR same
integer-populated-zero cleanup must pass. Preserve failed evidence; never recreate a
job to get desired samples. After normal exit, guarded offline export checks JFR/task/
counter/thread binding and three commands each with execution/allocation samples.
All-version/long-run/LaneC remain pending. No overall promotion/merge/push/release.
