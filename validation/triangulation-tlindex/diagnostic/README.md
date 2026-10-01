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
