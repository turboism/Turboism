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

## Repeated-target protocol investigation after fusion pair

The first fusion pair again has no triangulation samples in operations 2/3.
Do not extend the current reopen-only loop and call it repeated-target evidence.
A concrete next diagnostic is native document close/reload in the same JVM,
followed by the existing editor open/OK action on the unchanged task fixture.

Repository precedent: `ProtectedExportHostProbeAgent.openAndAwaitBoundDocument`
uses `CEAppCtrl.command_open(File, boolean)` and verifies the bound file;
`closeDocument` uses `command_closeFileContent`, with project-child detachment
as its completion check. This precedent establishes an API investigation path,
not support on all three reviewed host versions or proof of retriangulation.
Its disposable-copy helper also changes saved timestamps and may force-release
file-cache entries: those behaviors must NOT be carried into a retention test,
because they change lifecycle observations.

Before host submission, review the native command signatures for each version.
Use a separate opt-in driver/diagnostic artifact, leaving the current frozen pair
unchanged. On the EDT, verify that the active document is the task-owned fixture;
record its byte hash, document identity and project membership. Close through the
native command, explicitly observe any discard dialog, and require removal from
the project. Reopen the same task path through the native open command, require
a different live document object and the original bytes, then run open/OK.
Do not force GC, clear host caches, mark documents artificially saved, or retain
old documents in a probe registry. Identity comparisons must use object identity,
not identityHashCode equality; any probe reference needed to verify replacement
must be released before the retained observation window.

Keep reload time separate from editor-operation time, and preserve thirty-second
retained windows with identity-bound process/cgroup measurements. Each of all
three operation windows must independently show native triangulation execution;
absence of samples leaves the repeated-target gate unproven. The four-capture
agent's hard cap cannot establish output equality for every later cycle: add a
separate per-cycle evidence strategy before claiming repeated-cycle correctness.
This experiment is pending and does not establish either retention safety or a
theoretical optimization limit.

### Three-version native API review

Read-only javap review of the three official jars confirms identical signatures
for command_open(File, boolean), command_closeFileContent(IFileContent),
getCurrentDoc() and getCurrentProject(). Raw signatures, close dispatch bytecode
and IFileContent defaults are retained in
`build/t029-real-host-acceptance/repeated-target-review/`.

- 5203: CEAppCtrl SHA `6762f7d5bb593648f54cc8cf834e71d788fa8b0c7c5621ee1827a30ac18980f7`; close implementation O SHA `6c80b27bdbcb2da5fce4616e710671552da4d9c3f4759b6b43225e4fddaee59b`.
- 5302: CEAppCtrl SHA `ed2ed37d5d3f34375aa8918c12305c61be6e6e78bfc7e28c382c72b2e04215fc`; close implementation O SHA `18691b3a694cc0329cb1e5afa765b3e89f55c56c6d72e3dec3fab0434c8089bf`.
- 5303: CEAppCtrl SHA `a4613396fdf86de9b9ba6ca9950b2bf7748dd7ae5f8d85ea142968331bca2b2e`; close implementation O SHA `7f932833eed8b5442c40f5ccf2053b98748c67f2112441d3bc22da2b7fedd638`.

The modeling-document branch delegates through IFileContent default arguments
to closeFile(true, false), preserving the normal close/save path. A returned
command is not proof of document detachment (the wrapper discards the boolean).
The next implementation must handle the task-owned native discard prompt and
verify project detachment separately. Static API compatibility is proven here;
actual reload, modal handling and repeated target execution are not yet proven.


### Correction: native close/reload is not the no-explicit-GC acceptance loop

Following the native close call into CModelingDocument.closeFile(boolean, boolean)
reveals an unconditional System.gc() at bytecode offset241 after project removal
in **all three versions**. Pinned class/dump hashes and exact instructions are in
`repeated-target-review/native-close-gc.json`. This supersedes the proposed
close/reload route above for the existing no-forced-GC retention gate. Native
close/reload could measure ordinary close/reopen behavior if explicitly labeled,
but must not be substituted for no-explicit-GC repeated triangulation acceptance.
Do not patch away host GC or introduce DisableExplicitGC merely to pass the gate.

The next candidate trigger to investigate is native command_undo(IDocument)
after the atlas operation, followed by editor open/OK again. The controller has
this public command; existence alone does not prove it restores pre-triangulation
state. First verify a specific undo record was created, the command restores
that record (not an unrelated edit), and later operations independently execute
the target. Capture canonical output per cycle and preserve unchanged fixture
bytes. If undo merely restores already triangulated cached state, reject this
trigger too rather than treating UI success as repeated target execution.

### Native auto-connect candidate after undo rejection

Undo diagnostic seq2290 timed out safely; recovered partial JFR shows target
samples3679/0/0 across the three completed editor operations. It does not establish
repeated execution. Do not rerun the same undo trigger as a retention protocol.

The first target stack enters TAE__EditLayer_ModelImage.setupEditLayer through
GEditableMesh2.getGlIndices/updateMesh/updateIndices. 5203 bytecode shows that
updateIndices skips when _edge_edit_version equals cache_version_gl_indices.
Native command_meshEditConnectAuto dispatches only in mesh-editor edit mode;
commandAutoConnect calls autoConnect on each editDataList entry, marks its edges
updated, ends the edit and repaints. This is a concrete native candidate to review,
not authorization to directly bump cached versions or bypass the editor.

Pinned static artifacts and remaining prerequisites are under
build/t029-real-host-acceptance/repeated-target-review/native-auto-connect-candidate.json.
Before submission, review native mesh selection/entry, checkbox options, all three
versions, and per-cycle output capture. Mesh editing changes the workload: keep it
as a separate diagnostic and do not substitute it for the frozen atlas A/B.

Static follow-up across5203/5302/5303 is recorded in
`repeated-target-review/auto-connect-shape-review.json` with pinned input dumps.
All three commandAutoConnect methods read the native rebuild/border checkboxes,
call autoConnect, and mark edges updated. autoConnect directly calls the native
triangulator after rebuilding edges when requested; it is not gated solely by
the cached GL-index version. The progress parameter differs (`util.i.a` in5203,
`util.j.a` in53x), so invoke the public controller command, not a hardcoded
low-level descriptor.

5203 entry review requires main modeling mode, a nonempty selected ArtMesh list,
unlocked hierarchy, and present/unlocked editable-mesh extensions. Native entry
may return false or display a dialog; require the actual resulting edit mode and
its editDataList, not just the command return value. Selection can use native
selector.addSelected(source,int) with sources from the bound document; never
unlock source data to satisfy the diagnostic. Record selected IDs and counts.

For per-cycle observations, all three versions expose editDataList and raw
getCached_indices$core/getCached_positions$core getters. These are candidates
for hashing existing results without getGlIndices-triggered extra work. Validate
cache freshness and nonnull arrays before interpreting them; still require JFR
execution evidence and paired off/on output equivalence. Do not treat these
static checks as a runnable or accepted host diagnostic.
