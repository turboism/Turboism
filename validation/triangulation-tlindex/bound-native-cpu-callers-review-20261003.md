# T078 native CPU caller attribution from the existing recording

The distinct caller analyzer reuses FIFO2599's exact T057 recording. It revalidates
all original input pins, rebinds three native EDT events to30 independent CPU/clock
fields and uses the same conservative recording-clock windows. ExecutionSample
events alone are exported. Per-cycle scopes and every previously reported top40
leaf method/count match; native event-loop samples are excluded by export definition.
No new Editor, native command or performance leg was started.

| Command | EDT execution samples | settle from query | settle from contains | checkIndex from mesh existing-edge check |
|---|---:|---:|---:|---:|
| 1 | 631 | 92 | 48 | 142 |
| 2 | 531 | 55 | 47 | 114 |
| 3 | 635 | 93 | 57 | 145 |

All checkIndex leaf chains run through ArrayList.get, CArrayList.get,
GEditableMesh2.chechExistingEdge_exe, checkExitingTypedEdge and native edge insertion.
They are native mesh edge-existence costs, distinct from the TriangleList snapshot.
Most settle leaf samples are query-triggered. T077 only changes selected membership
calls; these T057 stacks explain its limited coverage, not its measured causal effect.

Next inspect the exact native edge-search semantics and its mutation/operation scope
before an operation-local index prototype. Preserve first matching native edge,
orientation/type distinctions, return values, edge versions and fallback behavior.
The survivor proof remains necessary unless an exact victim-tracking design replaces
it. No removal shortcut is justified by mutable corner/point equality.

Five scope tests and real-recording equivalence pass under the shared queue guard.
The initial analysis refused because equal-count leaf sites had different list order;
the corrected comparison uses method/count maps. No count, window, thread, binding
or old report was modified. Initial refusal and final success are retained separately.

Eight caller frames are retained; inlining/truncation limits attribution. Sample counts
are not CPU durations or savings. This T057 recording predates T075/T077. Historical
failed performance gates remain binding; no production acceptance is granted.
