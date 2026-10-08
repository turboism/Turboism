# Streaming allocation analysis: frozen-recording equivalence

PASS for one complete existing baseline1 allocation export:1,256,831,081bytes, SHA`cf4d677f032718151c5d4ba14677266901a16069d1361a115a39aeee43f46404`. All10window summaries exactly match the inherited analyzer result, including allocation class/site weights, event counts and index-put weights. This extends the four synthetic regression tests from commit`633d69920` with an actual frozen-recording check.

Observed child VmHWM was25,133,056bytes (23.97MiB). This is a sampled observation of the kernel high-water field, not a continuous memory benchmark or measured comparison with the old analyzer RSS. The new tool does not retain individual allocation stacks; aggregate memory still scales with distinct sites and non-allocation events.

Input hash was verified before processing. The wrapper watches the submitted T075 baseline and pauses only its own analysis child if that leg starts. Baseline1 equivalence completed while T075 was queued. The next candidate1 check refused at its prelaunch guard because T075 had started; no candidate1 analysis child launched. Candidate2/baseline2 checks remain unexecuted. No JFR re-export, host retry, measurement change or performance-acceptance claim.

Raw script/output/report pins: `build/t073-memory-window-audit-r1/streaming-equivalence-r1/`. T075 host protocol and candidate artifacts remain frozen; production stays T057. Further large offline checks await a suitable interval between live measurements.
