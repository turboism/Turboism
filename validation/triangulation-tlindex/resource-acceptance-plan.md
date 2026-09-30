# Remaining SC-04a acceptance protocol

Status: pending implementation/execution. This is not an acceptance result.
Production correctness and historical resource observations are recorded in
[host evidence](host-evidence-20261001.md).

## Measurement boundaries

Use one immutable production agent, one immutable driver, the reviewed heavy
fixture, and the UI-saved off/on JSON pair. Prepare all settings and capture agents
before measuring. Run exclusively through the unified host queue. Record the
queue job/prepared IDs, source/fixture/agent/driver/class hashes and host identity.

Add explicit driver timestamps for model-ready baseline start/end, each editor
open/OK operation start/end, and each post-operation observation start/end. Use
30 seconds of idle observation before the first operation and after each operation,
with the EDT free and no forced GC. The 30-second interval is a measurement window,
not an approved performance tolerance. Repeat the same user operation three times
within the same JVM. Keep these runs separate from the existing single-operation
four-digest protocol; do not silently reinterpret its expected capture count.

Collect user and system CPU ticks, RSS and available smaps_rollup PSS at one-second
intervals, with PID/start-tick validation for every sample. Identify the task cgroup
by path and inode; report Java and task-local auxiliaries separately. Report any
missed process births/exits, permissions failures or sampling gaps. Normalize CPU
percentages so 100% means one logical core. Preserve JFR with production call
samples and actual GC heap observations; state explicitly that GCHeapSummary
maxima are sampled heap observations, not continuously measured heap peaks.

For each operation report baseline memory, in-operation RSS/PSS peak and CPU time,
30-second post-operation residency, and the change from the first to later
post-operation windows. Include raw observations and intervals, not only ratios.
A quieter second UI operation cannot demonstrate the cost of repeated triangulation
unless JFR/capture evidence confirms the target was actually executed again.

## Decisions

- Keep correctness gates, exact ordered-edge equivalence and production execution
  mandatory for every accepted artifact/version.
- Do not convert the historical 4c326728 results into acceptance of 640e3b1e or later
  candidates. Re-run the three supported host versions on the accepted candidate.
- Separate whole-process startup/shutdown cost from explicitly timed operations.
  FIFO gaps and host thermal/background variation must be reported.
- Repeat pairs in reverse order before attributing a small resource difference.
- Present measured CPU/memory tradeoffs and a proposed regression tolerance to the
  user as required by spec SC-04a. No numeric tolerance has been approved yet.
- No main merge or production-ready claim until the remaining gates are resolved.
