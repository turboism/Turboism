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
