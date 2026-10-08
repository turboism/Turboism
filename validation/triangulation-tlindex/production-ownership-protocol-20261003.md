# T076 production CPU/allocation ownership diagnostics

Before any submission, freeze exactly two distinct FIFO jobs: T057 baseline then
T075 candidate. Use unchanged exact production Agents, same observer-free plugin,
settings probe, fixture, source/order/output binding and three native commands.
Preserve normal exit/identity/fixture/kernel CPU unit and original-bound safe cleanup
checks. No retry, replacement, attachment, forced GC or window manipulation.

Keep the previous diagnostic GC log flag identical on both arms. Add one JFR standard
profile recording per process, dumped on normal exit, maxsize256m. No custom runtime
recorder, validation transformation or extra premain. The recording path is bound
under task HOME. JFR is the intentionally changed observation variable; these jobs
are explanatory, never production performance acceptance or replacement ABBA legs.
If native integrity or recording/command coverage fails, preserve the refusal and stop.

First validate the streaming analyzer using the retained historical diagnostic
baseline1 recording and an explicit six-row phase projection. That parser smoke is
not new production evidence. Record CPU leaf and inclusive sites separately; counts
are sampling observations, not exact durations. Allocation weights/classes and
six-frame allocation stacks are estimates, not exact or live bytes. Keep empty and
truncated stacks visible. Native epoch intervals use before.readEndEpochMillis and
after.readStartEpochMillis; concurrent threads remain in scope. Millisecond boundary
precision and weight attribution across boundaries are limitations, not exact ownership.

CPU hypotheses: costs lie mainly in native triangulation, query snapshot allocation,
index maintenance, or concurrent rendering/GC. Each produces different recorded
stacks/classes; one observed pair cannot establish causal improvement. Sparse GC heap
summaries likewise cannot prove a leak or connect a previous logless process.

Only after both host jobs finish, export/analyze JFR under a shared queue guard.
The guard examines prepared argv for queued/running resource/JFR/benchmark/quiet jobs
and stops only its own process group. No exports/builds during shared measurements.
If blocked, resume analysis of original completed recordings; never recreate host jobs.

No changed performance caps or favorable result search. Failed original production
ABBA and FIFO2588 destruction-only refusal remain unchanged. T057 delivery baseline
remains. All-version/long-run/LaneC remain open. No merge/push/release.
