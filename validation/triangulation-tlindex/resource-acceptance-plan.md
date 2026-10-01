# Remaining SC-04a acceptance protocol

Status: driver (216 checks), full wrapper regression, and nine analysis tests pass.
Resource-window host A/B completed after one failed off attempt. See dated evidence:
first-operation gains coexist with higher retained RSS, and later UI operations
do not demonstrate repeated triangulation. Acceptance remains open.
This is not an acceptance result.
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

## Resource driver protocol under implementation

Explicit `TURBOISM_ATLAS_IMAGE_SHADOW_RESOURCE_OBSERVATION=true` prepares the
named `resourceObservation=true` JVM property only for reviewed heavy TLPROD
preserve-layout runs without export. The driver independently validates the
production token, task-expanded filename, SHA, layout and export settings.
Default runs emit no resource markers and retain their original operation count.

The opt-in driver writes `resource-windows.tsv` in its fresh run directory, with
phase, operation index, epoch milliseconds, JVM monotonic nanoseconds and actual
MemoryMXBean heap-used bytes. Baseline and each retained interval are fixed at
30 seconds; three editor open/OK cycles execute in one JVM. No forced GC is used.
Boundary heap values and JFR GCHeapSummary values are samples, not a continuous
heap peak. Use epoch time to align Linux process samples; JVM monotonic time
checks duration but must not be directly equated to the collector's clock origin.
The one-second process sampling leaves boundary uncertainty that analysis must
report rather than treating sampled CPU deltas as exact operation totals.

Offline synthetic UI regression uses one-second windows to check phase ordering,
all three native OK actions, complete output, duration and default opt-out. This
synthetic path does not prove that later host cycles execute triangulation.

## Analysis tooling

After a run completes, analyze its explicit boundaries and identity-bound process
observations with:

```sh
python3 validation/triangulation-tlindex/analyze-resource-windows.py \
  /path/to/resource-windows.tsv /path/to/resources.jsonl /path/to/new-report.json
```

The output path must be new. The report pins both input hashes, checks the complete
three-operation protocol and thirty-second idle windows, and separates Java from
task auxiliaries. It reports user/system CPU, sampled RSS/PSS peaks and medians,
post-window growth, boundary sampling gaps and wall/monotonic clock disagreement.
Missing PSS is unknown rather than zero. Process identity changes or missing Java
samples fail analysis. CPU across unobserved process births/exits remains a lower
bound; identity transition counts expose observed changes.

Nine deterministic accounting/negative tests pass via
`python3 -B validation/triangulation-tlindex/test-resource-windows.py`.
This verifies the accounting code only. The completed resource-window pair in
`resource-windows5203-r2` demonstrates first-operation triangulation, but neither
leg has target samples in operations two and three. Each repeated operation still
needs independent execution evidence before interpreting retention as
repeated-target evidence. The newer contains candidate requires its own paired
results; the older pair does not establish its performance.

Pass `--jfr-json /path/to/execution.json` with that same run's exported
`jdk.ExecutionSample,jdk.NativeMethodSample` JSON (export with `jfr print --json
--stack-depth 64 --events jdk.ExecutionSample,jdk.NativeMethodSample recording.jfr`)
to classify target samples within
each explicit operation window. The JSON hash is recorded. `NOT_OBSERVED` means
sampling did not demonstrate execution, not that execution was impossible; later
UI cycles without observed triangulation cannot establish repeated-target retention.
The analysis separately counts production index frames and official triangulator
frames, and never treats first-operation samples as proof for later operations.

Large JFR JSON is parsed one event at a time and hashed as a stream, avoiding
whole-file allocation. Tests cover chunk boundaries, truncation and trailing data.
