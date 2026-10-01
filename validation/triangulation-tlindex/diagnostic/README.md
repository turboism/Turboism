# Contains branch diagnostic (not performance acceptance)

The first contains candidate resource pair (seq2219 on / seq2226 off) did not
demonstrate a gain. Full-stack JFR proves the native contains hotspot reaches the
woven helper, but time-biased stack samples cannot estimate shortcut hit rates.

Ranked hypotheses and falsifiable observations:

1. Unknown identities dominate: `identityMiss` attempts will dominate native
   fallbacks. Its true/false split distinguishes equal-but-distinct objects from
   absent objects without adding another native lookup.
2. Stale state dominates: `dirty`, `setSize`, `keySize` or `dead` attempts will
   dominate. This would direct investigation to the mutation/rebuild protocol.
3. The shortcut already handles most calls: `identityHit` will dominate, while
   residual native add/remove or index-maintenance samples remain expensive.
   Counts alone cannot quantify those other costs.

Build from the SHA-pinned production source and frozen agent:

```sh
python3 validation/triangulation-tlindex/diagnostic/build-contains-probe.py \
  /absolute/path/to/frozen/turboism-agent.jar /absolute/path/to/new-output
```

This generates a helper copy in the new output directory, compiles it, and runs
36 checks against the real generated helper. It then replaces only the outer
`TriangulationEdgeIndex.class` entry, adds `ContainsDiagnostic.class`, verifies
every other existing jar entry is unchanged, and repeats the checks against the
packaged jar with the original inner classes. Production source and the input jar
are never edited. Source or input-agent SHA mismatch refuses the build. Native
return values, equal-but-distinct queries and native exception identity are tested.

At normal JVM shutdown the counter emits one stderr line with prefix
`[TL-CONTAINS-DIAGNOSTIC-v1]` and a JSON object. Each reason has three columns:
`[attempts, returnedTrue, returnedFalse]`. Reasons are mutually exclusive and
follow the original short-circuit checks in order. `identityHit` returns true
without native lookup. For fallback reasons, attempts minus completed returns
means native exceptions or unfinished calls; it is not a false result. Fatal
bookkeeping failures retain the original fatal policy and may prevent output.
Missing shutdown output never means zero calls. The build checks the exact JSON
counts in both selfchecks, including intentionally throwing native calls.

The fixed-size atomic counters retain no host sets or triangles and do no per-call
I/O. They still perturb timing and compilation. Submit the separately labeled
diagnostic artifact only through the unified host queue, with cloned fixtures and
the normal capture gates. Its timings are **not** the frozen production candidate's
performance result. Counts aggregate the complete JVM run, not a specific window.
No performance acceptance, new threshold, or theoretical bottleneck follows from
this probe alone.


After normal completion, use the console log inside that task directory, the queue
outcome, and the diagnostic build manifest:

```sh
python3 validation/triangulation-tlindex/diagnostic/analyze-contains-probe.py \
  /task/logs/console.log /job/outcome.json /diagnostic-build/manifest.json \
  /new/report.json
python3 -B validation/triangulation-tlindex/diagnostic/test-contains-analysis.py
```

The analyzer requires successful host gates, matching staged diagnostic agent
hashes, a log belonging to the outcome task, and exactly one complete aggregate.
It refuses zero execution, duplicate/missing output, bookkeeping failures and
incomplete native returns. It hashes inputs and never overwrites an existing
report. Its five parser tests cover counts, missing/duplicate output, invalid or
incomplete rows, schema errors and impossible shortcut outcomes. Capture/edge
correctness still needs the existing independent checks before accepting a
production candidate; this report only establishes aggregate branch behavior.


## Query/add fusion semantic experiment

`ContainsAddFusionSelfCheck.java` compares original conditional contains/add with
unconditional add only when debug is off. Its own triangle types model constant
hash, six point permutations, mutable float coordinates and index-independent
geometric equality. Eight seeds exercise tree-sized collision buckets, duplicate
identities/geometries, signed zero, NaN, removal, iterator removal, clear and
coordinate mutation. Every step compares exact stored identity/order and debug
add counters. This does not load official classes, weave a caller, or measure
host performance.

```sh
mkdir -p /tmp/tl-fusion-classes
javac --release 17 -d /tmp/tl-fusion-classes \
  validation/triangulation-tlindex/diagnostic/ContainsAddFusionSelfCheck.java
java -Xverify:all -cp /tmp/tl-fusion-classes \
  dev.turboism.validation.tlindex.diagnostic.ContainsAddFusionSelfCheck
```

The recorded run passed 1,117,616 assertions with 745,328 original versus 560,262
candidate equality calls in the measured fixture operations. These are invocation
counts in the own fixture, not JFR samples or a predicted host speedup. Production
caller shape gates, actual branch counts, official side-effect equivalence and
three-version A/B remain outstanding before selecting this as a final candidate.


## Offline bytecode prototype

`FusionBytecodePrototype.java` reads a reviewed official jar and emits a standalone
patched `h.class` into a new directory. It is not an agent and does not install
anything. Both known caller SHA values are required, plus exact method access,
two receiver/argument-matched contains/add blocks, branch destinations, no handlers,
and existing empty-stack join frames. It inserts a debug-switch guard: debug-on
runs the original block; debug-off calls the existing add and jumps to the original
join. Existing method frames are preserved with matching new frames, so no host
class hierarchy is loaded to calculate frames.

Compile the prototype and `FusionBytecodeSelfCheck.java` with local ASM 9.7 core,
tree and commons jars. Run the selfcheck with `java -Xverify:all`. The 47 checks
execute generated own fixtures after remapping away all official class names;
they verify baseline/patched stored identity/order, debug counts, no duplicate
adds in debug mode, removal of redundant contains calls when debug is off, and
rejection of unknown SHA, missing sites, wrong argument, wrong branch and wrong
access. These tests do not execute the official class body.

The prototype emitted output for all three reviewed jars. Artifacts are under
`build/t029-real-host-acceptance/contains-fusion-experiment/prototype{5203,5302,5303}`;
each has original/patched pins. No production integration or real-host fusion
result exists. Production integration requires the normal admission, lifecycle,
configuration and semantic gates plus new artifact A/B; queued branch diagnosis
remains independent and must be preserved.

### Repeated-target undo guard (not yet host-integrated)

`AtlasUndoGuard.java` checks snapshots of the **current edit mode's** native undo
manager, as used by CEAppCtrl.command_undo. Require one new entry, preserve the
applied history prefix by object identity, recheck immediately before dispatch,
and verify the native cursor moved back while retaining the redo entry. It
rejects no-op/redo, extra edits, document/manager changes and history replacement.
These checks do not identify the semantic content of an edit: the integrating
probe must bind snapshots to its specific atlas operation and exclude unrelated
UI actions. They do not prove that undo restores untriangulated model state.

Run the offline negative cases with:

```sh
mkdir -p /tmp/t029-undo-guard
javac --release 17 -d /tmp/t029-undo-guard validation/triangulation-tlindex/diagnostic/AtlasUndoGuard*.java
java -cp /tmp/t029-undo-guard AtlasUndoGuardSelfCheck
```

All host snapshots/dispatch must occur on the EDT. Snapshot objects intentionally
hold history identities for verification; release them before resource retention
windows. No global registry, saved-timestamp changes, forced GC or cache clearing
is introduced. This helper is not packaged into the frozen performance candidate.

`AtlasNativeUndo.java` is the corresponding reflective native adapter. It requires
EDT access, exact canonical task-fixture binding, current-edit-mode manager
identity, an unchanged history immediately before dispatch and native canUndo.
It invokes exactly one matching public command_undo(document), then checks the
restored cursor/history. Native exceptions propagate; no direct undo-manager
mutation is used. The caller still owns fixture SHA validation, operation timing,
EDT timeout/cancellation, exclusive task UI ownership and releasing snapshots.

Compile both AtlasUndoGuard*.java and AtlasNativeUndo*.java into the temporary
classes directory; run AtlasNativeUndoSelfCheck with -Djava.awt.headless=true.
Seven synthetic adapter checks and fourteen guard checks pass. This adapter is
not wired into the driver yet and has no host execution evidence.

### Standalone undo-trigger driver build

`python3 validation/triangulation-tlindex/diagnostic/build-undo-driver.py /path/to/new-output`
creates a separate jar from the SHA-pinned current driver source plus the two undo
helpers. It compiles with Java17, all lint warnings treated as errors, and records
all input/generated source hashes plus jar SHA in build.json. It does not publish,
prepare, submit or launch anything. Existing frozen bundles remain unchanged.

The generated driver admits only 5203 production resource-observation scenes,
without export or shadow profiling. It snapshots before each editor action,
performs guarded native undo after that action, checks fixture SHA before/after,
releases snapshots, then enters the retained window. New diagnostic-undo markers
intentionally make the standard performance-window parser reject this protocol.
The result is trigger-feasibility evidence only; not performance acceptance.
Operation output and repeated execution still require independent host/JFR checks.
`undo-diagnostic.tsv` records completed undo operations and restored positions.
No host outcome exists yet. The native command may fail to produce a single undo
record or may restore cached triangulation; either outcome must remain a failed
trigger hypothesis, not be bypassed by weakening the guard.

After a successful undo diagnostic, export its JFR at stack depth64 and run:

```sh
python3 validation/triangulation-tlindex/diagnostic/analyze-undo-trigger.py \
  resource-windows.tsv undo-diagnostic.tsv execution.json outcome.json new-report.json
```

The analyzer requires all native host gates, exact diagnostic phase order, three
undo receipts and a stable restored cursor. It streams JFR and counts native
triangulation separately in each operation (excluding undo/retained phases).
REPEATED_TARGET_OBSERVED still does not establish per-cycle output equivalence,
retention safety or performance acceptance. Five deterministic negative/boundary
checks run via `python3 -B validation/triangulation-tlindex/diagnostic/test-undo-trigger-analysis.py`.

### Cached mesh result recorder (auto-connect diagnostic preparation)

`MeshResultSnapshot` reads existing native cache arrays on the EDT, requires
matching position/index cache versions, validates finite 2D positions and triangle
indices, and emits ordered SHA-256 digests without retaining host references.
It never calls getGlIndices/updateMesh. Raw float bits preserve signed zero.
The result is an observation only: native command execution, selected source
identity and paired output equality still need separate evidence.

`MeshResultSnapshotSelfCheck` covers stale-cache refusal before reading arrays,
EDT enforcement, mutation during observation, native error propagation, ordering,
shape/index bounds and nonfinite coordinates. Run with Java17:

```sh
javac --release 17 -Xlint:all -Werror -d /tmp/mesh-snapshot-check \
  validation/triangulation-tlindex/diagnostic/MeshResultSnapshot*.java
java -Djava.awt.headless=true -cp /tmp/mesh-snapshot-check MeshResultSnapshotSelfCheck
```

15 checks passed locally. The recorder is not yet wired into a host driver;
its shape assumptions still require native validation before host acceptance.

`NativeAutoConnect` is the task-bound command adapter under preparation. It uses
native selection and mesh-editor commands, rejects locked/missing mesh extensions,
checks selected source identities against edit data, sets the two real checkbox
options, and captures existing results on a later EDT observation. It does not
unlock model data or write cache-version fields. Sources with fewer than three
points are excluded and selected source IDs are returned for the driver to record.
The driver must additionally verify fixture SHA and record options/phase markers.

Java17 -Xlint:all -Werror compilation and12 `NativeAutoConnectSelfCheck` guard
checks pass. Native class names, edit-data ordering and actual command/repaint
completion are not established by these synthetic tests. The separate driver now builds; task-owned host validation is still pending.

`build-auto-connect-driver.py OUTPUT` generates a separate5203-only diagnostic
from the same pinned T040 source. It replaces the atlas editor loop with native
mesh entry, three rebuild+preserve-border auto-connect cycles, cached-result
observations, and native cancel before ordinary exit. Each cycle records command
start/return/result-ready boundaries separately. Stale caches are observed for at
most30s without forcing recomputation; other capture errors propagate immediately.
The selected IDs are Base64-encoded and each result row contains cycle, source ID,
point count, edge version, position/index lengths and ordered hashes. Fixture SHA
is checked around the operations. Distinct mesh/auto-connect markers intentionally
fail the ordinary atlas resource protocol.

Generator syntax and generated Java17 -Xlint:all -Werror compilation pass.
Protocol analysis has six synthetic checks below; real-host execution is pending. Native undo history is
part of this different workload; even successful repeated execution would not by
itself establish index retention safety or replace atlas A/B acceptance.

`analyze-auto-connect.py MARKERS SELECTED RESULTS PROTOCOL JFR_JSON OUTCOME OUTPUT`
requires successful host gates, complete entry/three-cycle/cancel markers, exact
native options and a complete ordered result set for every selected ID each cycle.
It counts command and settling samples separately; all three command intervals
must show target samples before reporting REPEATED_COMMAND_TARGET_OBSERVED.
Unresolved frames are counted, not treated as positive target execution. Reports
always leave cross-run equality unassessed and performance acceptance inapplicable.
Six synthetic tests pass with `python3 -B validation/triangulation-tlindex/diagnostic/test-auto-connect-analysis.py`,
including missing-cycle, delayed-only execution, partial-host and incomplete-result
rejection cases. This validates the analyzer, not the pending native diagnostic.

The current auto-connect diagnostic build is recorded under
`build/t029-real-host-acceptance/auto-connect5203-build/build.json`, with driver SHA
`18dd504d5cc9a37a45abaf1a6f4df1b37a786322871ae97db39f48d31fb4b90d`.
It was compiled after both5302 measurement legs ended and before5303 began.
No native auto-connect execution is claimed by this build receipt.

#### First host failure and exact-descriptor correction

The initial driver18dd504d run (seq2303) entered native mesh mode with711 selected
sources, then failed before command dispatch: name-only reflection selected the
CWidget-returning getToolPanel method and received a CScrollPane. This failed run
is retained and does not prove repeated target execution. Official5203 metadata
contains two zero-argument getToolPanel methods with distinct return types, both
nonbridge. The adapter now requires the exact reviewed ToolPanel_MeshEdit return
descriptor and rejects missing/ambiguous/bridge matches.

The corrected adapter passes15 guard checks. `NativePanelDescriptorSelfCheck`
loads official metadata without class initialization and verifies a unique exact
nonbridge descriptor. It passed against Cubism-5.2.03/jars. Neither check proves
native command completion. The separate r2 build/run waits for the active53x
performance pipeline to finish; the original build and failed outcome are immutable.

### Fresh-edge failed-search elimination (offline candidate)

`FreshEdgeContainsSelfCheck` compares ordered identities and branch traces for
fresh Object-equality edge instances;809874 checks pass. Reused identities and
value-equality objects are explicit counterexamples to widening the rewrite.
`FreshEdgeBytecodePrototype` accepts paired pinned h/j bytes from all3 official
jars, checks the c()V/three-query/local-initialization shape, then replaces only
contains invocations with POP2/ICONST_0. Existing frames and branches are retained;
no official class is loaded. Shape checks alone are not a general escape proof:
paired reviewed hashes are mandatory at its public patch entry.

`FreshEdgeBytecodeSelfCheck` executes generated own fixtures with -Xverify:all;
41 checks pass, including all branch masks, constructor exceptions, duplicate
patch, changed locals/access/owner, nonfinal or overridden-equality edge metadata,
and refusal of unreviewed input hashes. Java17 -Xlint:all -Werror compiles both.
Artifacts/logs: build/t029-real-host-acceptance/fresh-edge-prototype and
fresh-edge-selfcheck. No production integration or real-host benefit is claimed.

Production admission still needs a design decision: h may be defined before j,
so an h-only transformer must not assume it has observed the actual j definition.
Do not initialize host classes from the transformation callback. Either bind the
reviewed dependency safely, or retain a runtime fallback that checks the actual
loaded edge equality semantics before eliding the search. Reading a resource
alone is weaker than proving the actual loaded type's equals behavior.

Auto-connect r3 fixes a second, independently observed diagnostic failure: r2's
MESH_CAPTURE timeout had47 partial-JFR capture samples in WinNTFileSystem.canonicalize0
via boundDocument. A per-driver Binding now captures document identity and File on
the EDT, validates canonical fixture equality once off the EDT, then rechecks exact
document identity and captured path without filesystem I/O on later EDT operations.
Unverified bindings, changed document/path and canonicalization on the EDT are
rejected. Per-cycle fixture SHA verification remains in the driver. Bindings are
not stored globally and do not extend beyond the diagnostic run.

25 adapter guard checks pass, including a File double that throws if canonicalized
on the EDT and proves repeated binding checks perform no additional canonicalization.
The r3 driver compiled with Java17 -Xlint:all -Werror; SHA
fc7a5d2cc9a204cc5abf9cbbcc4e6b5002d96e99351a0ee9cf433da3b520551b.
It is waiting for the fresh-edge5302 performance pair before preparation/submission,
using the original frozen Agent2f6dd5ba to isolate diagnostic changes. Native results
and repeated target execution remain unproven.
