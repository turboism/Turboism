# Atlas image kernel probe — T030 / T033 / T035 / T038 / T039 / T040 offline slices

This is an **offline-only** Java 17 reference-vs-candidate slice. It is not a
runtime hook, plugin, benchmark, host probe, or production optimization. The
only source change is this directory; it does not modify `runtime/`,
`bootstrap/`, `core/`, sorting, Runner, the main worktree, or the specifications.

## Contract and algorithm boundary

The modeled native helper is the 5303 method:

```text
com.live2d.util.f.g.a(II[III[IIIII)V
```

The pure-array input names follow the observed call and bytecode:

```text
arg1 kx, arg2 ky, arg3 source[int[]], arg4 sw, arg5 sh,
arg6 destination[int[]], arg7 stride, arg8 fullWidth/original_dw,
arg9 fullHeight/original_dh, arg10 padding (used for both x and y).
```

The reference is intentionally a separate BCI-shaped implementation. It
models, in Java `int` arithmetic, the original order:

- BCI 0 and 10 Kotlin non-null checks for source and destination, in source-before-destination order. This is a pure-array contract; it does not claim Kotlin Intrinsics messages, stack behavior, or class-initialization behavior are completely equivalent;
- BCI 13–16 `kx * ky` as an `int` denominator;
- BCI 18–60, only when the supplied `AssertionMode` is enabled, the original
  assertion `destination.length >= fullWidth * fullHeight`, using the original
  `fullWidth/fullHeight` inputs, never trimmed bounds;
- BCI 61–68 clearing the entire destination backing array before any loop;
- BCI 72–447 four inclusive-end loops (y/x/sy/sx): each end is formed by the
  original Java `int` subtraction, entry skips when `counter > end`, and the
  body breaks on equality before `counter++`; the reference is not normalized
  to an ordinary `for` loop;
- within those loops, `x * kx`, then `+ kx`, then `- 1` (and the corresponding
  y expression), source index `sy * sw + sx`, per-sample `channel * alpha / 255`,
  integer sums, `sumChannel * 255 / sumAlpha`, alpha denominator `kx * ky`, and
  destination index `(y + padding) * stride + x + padding`.

The audit's `b(n,k)`/`ap.c` evidence is align-up, not compact ceil division:
`(n + k - 1) & ~(k - 1)` for the positive power-of-two factors produced by
the original factor helper. The candidate changes **only** the iteration bounds:

```text
loopW = min(original_dw, ceilDiv(sw, kx))
loopH = min(original_dh, ceilDiv(sh, ky))
```

It retains the original full bounds for all allocation/stride/padding inputs,
clears the whole destination backing, preserves the original integer pixel
formula and source-read order, and does not model or remove the later expand,
returned-value `pop`, allocation, release, or any other side effect.

## T033 owned offline bytecode fixture

T033 adds an owned, in-memory ASM fixture and does not load, define, or execute
an official Cubism class. The generated class is deliberately small but keeps the
bytecode facts under test:

- class `dev/turboism/validation/atlasimage/t033/GeneratedAtlasFixture`, public
  final `render` with descriptor `(II[III[IIIII)V`, four inclusive-end loops, the
  original `fullWidth/fullHeight` assertion area, one full `Arrays.fill`, and the
  original int pixel formula;
- generated fixture identities are class SHA-256
  `855d93cf7e2f7f5d8566e30b84c466da43337840997058bd73c9a006dc8487c8` and
  canonical render-shape SHA-256
  `db1427e432c7dd43681468df595031c590b787cf1c8fbfb7794693b5aa5f5efa`;
- the exact owner/name/descriptor/access/class-hash/method-shape gate rejects
  wrong descriptor, flags, shape, and repeated patch attempts by returning the
  original bytes;
- the patch inserts the side-effect-free bounds calculation immediately before the
  `Arrays.fill` invocation, when the fill array/range/value operands are already on
  the JVM operand stack. This is not an insertion at the original BCI 61 empty-stack
  point. It then changes only the two y/x end-bound reads to new locals. The existing
  `- 1` inclusive-end arithmetic remains; arguments 8/9, the clear, stride/padding,
  and pixel body are not rewritten;
- the fixture reserves its own local layout, but TOP/dead-slot fidelity to a
  production class is explicitly **not covered**. `-Xverify:all` verifies this owned
  fixture's frame shape only; it is not a production loader or transformer acceptance
  test.

The only bytecode library is the locally cached `org.ow2.asm:asm:9.7.1`, source SHA-256
`8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281`; its cached
POM is snapshotted and recorded too. No framework is downloaded. A pure-JDK build tool
rewrites the 38 ASM classes into the private namespace
`dev.turboism.validation.atlasimage.shaded.asm97`, preserving the original source
SHA/license metadata and recording the shaded-JAR SHA in the inventory. Java sources
compile against that private JAR with `--release 17 -proc:none -Xlint:all -Werror`,
and owned fixture execution uses `-Xverify:all`.

T033 reports only offline evidence: optimization on/off, pure Guard rejection,
finite rejected-input execution through both original and patched fixture bytes,
factor-one, 5000 fixed-seed valid fixture comparisons, four MIN input pairs (eight
direct original/patched executions), and clear/source-read/target-write fault
propagation. It checks full backings, source-read order, exception type, event traces,
one clear, one propagated fault, and no catch/rerun. Dangerous overflow/non-terminating
inputs remain guard-only; they are never run through the full loops. A deliberately
bad zero-bounds rejection path must be detected by the comparison. The status is
`T033_OFFLINE_PASS` followed by `OFFLINE_PASS`; it is not a production integration
or performance result.

## T035 finite non-executing bytecode adapter

T035 adds a separate official-data profile and a separate hand-generated owned-fixture
execution profile. They are deliberately not the same acceptance path:

- `T035OfficialProfile` reads only the three fixed official JAR paths from the T034
  audit, checks the recorded JAR/class SHA-256 values, and parses the target class
  bytes in memory. The fixed entries are 5203 `com/live2d/util/e/g.class`, 5302
  and 5303 `com/live2d/util/f/g.class`; all target B methods are checked as
  `a(II[III[IIIII)V`, access `0x11`, class major `61`, class access `0x31`,
  14 methods, `maxStack=5/maxLocals=35`, 244 instructions, no handlers, and
  return BCI 447. The complete canonical instruction/CFG/frame shape is bound to
  the recorded 260-line SHA-256 (`cc4a0542...` for 5203 and `a75d64a1...` for
  5302/5303), with explicit fill BCI 68, boundary BCIs 74/121, four direct body
  backedges, and the TOP/dead-slot frame witness.
- `T035OfficialAdapter` uses a reader-seeded `ClassWriter(reader,
  COMPUTE_FRAMES|COMPUTE_MAXS)` and `T035StrictNoResolutionWriter`. Its
  `getCommonSuperClass` increments a counter and throws; it never delegates,
  resolves, calls `Class.forName`, or falls back to `Object`. Only target B is
  wrapped; all other methods are returned directly to the writer. The insertion
  is immediately before the fill call while its four operands are already on the
  operand stack, not at the original BCI 61 empty-stack point. It emits the
  side-effect-free bounds calculation and replaces only the two boundary reads
  with locals 35/36 (packed temporary 37/38); args 8/9, fill, loops, and pixel
  body are not rewritten. There is no handler.
- For each official profile the adapter remains an in-memory candidate only: it
  prints hashes and text, never writes a class/JAR, never defines or verifies the
  official candidate, and never puts an official JAR on the application
  classpath. The finite checks prove 244 original instructions inverse-map from
  265 emitted instructions, 13/13 untouched raw `method_info` records remain
  identical, target non-Code method metadata is unchanged, class/field metadata
  is unchanged, and common-super queries are zero. This is not a production
  loader or transformer acceptance test.
- `T035OwnedFixtureGenerator` hand-generates a distinct owned class rather than
  extracting or renaming official B. It includes four direct body backedges,
  original full-size assertion area, assertion message/local-13 reuse, explicit
  fill operand stack, and normalized frames witnessing TOP `this`/param-9 dead
  slots. `T035OwnedTransformer` is identity/shape gated before any ASM parse;
  descriptor, flags, boundary predecessor, loop backedge, repeat, frame-mismatch,
  and reference-merge variants all return original bytes with parse count zero.
  The owned original and patched classes alone are executed under `-Xverify:all`
  for the retained factor-one normal case, an actual accepted partial case
  (`kx=ky=4`, `sw=5`, `sh=3`, `full=8x4`, `stride=12`, `padding=2`, bounds `2x1`),
  finite rejected cases, MIN-axis × assertion-mode, and one-shot clear/read/write
  faults. The partial case compares the whole backing, unchanged source, identical
  non-empty source-read order, one clear, and permits deleted empty-block target
  writes; its expected pixel includes `0xbfffffff` and `0x2fffffff`. The alias
  rejection uses one array object for both source and destination and asserts the
  pure Guard rejects it. This does not imply the official candidate verifies or
  executes.
- The implementation is limited to this directory. The cached ASM 9.7.1 source JAR
  (`8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281`) and its
  BSD-3-Clause POM/license metadata are input evidence, not a host dependency. The
  production-shaped validation agent embeds only the private relocated namespace,
  has no manifest `Class-Path`, and performs a finite runtime linkage/format precheck
  before shape parsing or either patch. Missing or corrupt private ASM variants are
  exercised by real `-javaagent` JVMs and fail closed with
  `bytecode-dependency-gate`. This is not a production loader, batch, StackWalker,
  runtime, UI, or integration hook.

## T038 finite helper/classloader integration

T038 extracts the host-free boundary contract into `t038/T038ArrayHelper`. It contains the full
side-effect-free Guard and bounds calculation only: primitive arguments, array identity/lengths,
long checked arithmetic, original full bounds on rejection, and no pixel reads or writes.
`T033FixtureAdmission` remains the T030/T033 compatibility facade and delegates to this helper;
the existing rejected-bounds negative control is retained only in that facade.

`T035OwnedTransformer.applyForHelper` reuses the exact T035 byte/shape/CFG gate while binding the
inserted call to the requested helper owner/descriptor. `T038HelperBinding` validates, before ASM
patching or fixture definition, the visible helper's class identity, loader identity, public-final
class shape, and public-static `bounds` descriptor. `T038OwnedClassLoader` is intentionally finite:
it child-defines only the owned fixture/helper names and delegates every other name to its parent.
The harness covers default parent delegation and two coexisting child loaders with distinct helper
class identities; no general-purpose loader is provided.

T038 executes only the hand-generated owned CFG/TOP fixture under `-Xverify:all`. The accepted
partial case compares original/patched whole backing, unchanged source, source-read order, one full
clear, and actual trim. A finite Guard-rejected input compares original bounds, clear prefix, and
`ArrayIndexOutOfBoundsException` without rerun. Missing helper, wrong identity/loader/owner/
descriptor, wrong fixture shape, and repeated patch all return the original byte array identity,
keep fixture-definition count at zero, and keep the reused transformer's parse count at zero.
Official 5203/5302/5303 bytes remain read-only T035 profile data; no official class is defined,
verified, executed, written, or put on the application classpath.

## T039 validation-only premain shadow probe

`T039ShadowHelper` is a pure validation-only shadow helper. Its public bounds descriptor is `(II[III[IIIIIZ)J`; it closes over the exact `T038ArrayHelper` Guard/candidate arithmetic but always returns the original full bounds. It records only bounded scalar samples and counters. One immutable atomic state contains each sample with its counters, so concurrent snapshots cannot combine two parameter groups. It does not read pixels, copy or write arrays, retain array references, write files in the hot path, start threads, or expose an unbounded event stream.

`T039ShadowAgent.premain(String, Instrumentation)` accepts no agent argument and reads only named `turboism.validation.t039.*` properties. The owned and official `5303` profiles are separate. The default official mode is `data-only`: a manually tested official `Session.transform` call may analyze and discard the candidate. Only an explicit `shadowMode=shadow-ready` plus `shadowOptIn=T039_SHADOW_EXPLICIT_OPT_IN` can return the official candidate byte array to the caller; this validation build never defines or executes it. Candidate counts are not method-execution counts: `methodExecuted=false` throughout this slice.

Both T039 and T038 helper classes are prewarmed and gated by exact identity, loader, public-final shape, method descriptors, resource hashes, and bounded scalar prewarm. Resource hashes bind loaded class resources only; they are not full runtime-byte/JIT attestation, and unknown transformers remain unproved. The cold transformer is synchronized through an `ARMING` state, performs a second loaded-target check without retransformation, and treats late callbacks as inert. JDK `Instrumentation.removeTransformer` returns a boolean: the real Session arm/transform/unregister regression drives true/false/throws, records `REMOVED`/`NOT_REMOVED`/`FAILED`, returns a candidate only for true, and leaves false/throws `BLOCKED`, registered, and with `candidateReturnedCount=0`. `remove=true` proves only the removal call result, not disappearance of an already-dispatched callback; the missing-result classifier is synthetic and is not an API success.

`T039FixtureArtifact` creates a test-only owned target JAR from the existing T035 hand-generated fixture; `T039OwnedJvmHarness` runs that fixture with ordinary JDK `-Xverify:all`, checks patched-versus-original whole backing, source read order, clear, finite exception behavior, full-bounds shadow results, atomic concurrent statistics, actual instrumentation removal outcomes, ARMING/race behavior, late-callback inertness, and bounded statistics. Negative JVM runs cover wrong loader, class hash, shape, CodeSource, missing helper, wrong helper/T038 closure hash, forbidden agent arguments, failed-premain cleanup, and already-loaded gating. This proves only the owned validation fixture path and read-only official/manual-transform gates. It does not install a host hook or claim official JVM verification/execution.

The packaged-agent regression is a real independent JDK process: it starts with only
`-javaagent:<agent-jar>` and `--class-path <same-agent-jar>`, then observes premain,
first owned target definition, actual two-boundary patch/helper execution, and the
T040 freeze bridge. `T040SelfContainedJvmHarness` invokes the patched render before
freezing that same Session, so the `T040_SELF_CONTAINED_OFFLINE_PASS` marker is not a
reflection-only or later replacement-session check. It does not add ASM to the
application classpath. The agent JAR contains the private ASM closure and its
POM/notice metadata, but no original ASM namespace or `Class-Path` manifest entry.
The dependency-negative processes remove or corrupt `ClassReader.class` and still
drive first target definition; both reject before patch with candidate count zero. This
linkage/format gate is not a full byte attestation; the source/resource hashes and
shape gates remain separate.

## T040 immutable freeze bridge

`T039ShadowAgent.freezeCapture(String expectedRunId)` is the bounded, public bridge for a future manager. It is validation-only: no file I/O, class definition, official-class execution, array retention, callback retransformation, thread creation, or production installation occurs. The run ID must match exactly; an unarmed, unpatched, blocked, repeated, or wrong-run call rejects. A successful freeze is one per Session and returns `Collections.unmodifiableMap` containing only strings.

The fixed schema is:

```text
schemaVersion, runId, profile, shadowMode, state, removalStatus,
transformerRegistered, candidateReturnedCount, frozen, fullBoundsPreserved,
sampleOutcome, reason, helperPrewarmed, helperHash, t038HelperPrewarmed,
t038HelperHash,
stats.recorded, stats.dropped, stats.admitted, stats.rejected,
stats.aliased, stats.potentialTrim, stats.eventLimit,
stats.lastKx, stats.lastKy, stats.lastSourceWidth, stats.lastSourceHeight,
stats.lastStride, stats.lastFullWidth, stats.lastFullHeight, stats.lastPadding,
stats.lastSourceLength, stats.lastDestinationLength, stats.lastAliased,
stats.lastAdmitted, stats.lastPotentialTrim, stats.lastOptimizationRequested,
stats.targetEvents, stats.lateCallbacks, stats.candidateCount,
stats.candidateReturnedCount, stats.rejectionCount, stats.methodExecuted
```

All `stats.*` values are scalar strings from one atomic helper state plus one Session snapshot. `sampleOutcome` is exactly `NO_CALLS`, `NO_ELIGIBLE_CALLS`, `POTENTIAL_TRIM`, or `TRUNCATED`; a dropped sample makes the result `TRUNCATED`. Freeze waits for the helper's short diagnostic critical section, then the helper returns full bounds and does not update counters or samples on late calls. `prewarm` and `resetCounters` cannot reopen a frozen helper. The helper still uses only a short diagnostic lock around sample publication/freeze; it does not lock the B/g kernel or h, and it performs no hot-path I/O.

The official 5303 offline test manually drives the real Session arm/transform/unregister path with fixed official bytes, a temporary task-local clone ProtectionDomain, and a fake Instrumentation removal result. It requires `SHADOW_READY`, `REMOVED`, `transformerRegistered=false`, `candidateReturnedCount=1`, no preflight failures, and `sampleOutcome=NO_CALLS`; it never defines or executes the official class. The owned JVM test uses a separate owned fixture Session, concurrent helper calls, in-flight/late full-bound calls, repeat/wrong-run/unarmed/unpatched/blocked rejection, immutable-map, and post-freeze reset/prewarm checks. `owned` is test-only and never enables an official default. `T039` candidate counts remain transform observations, not method execution.

The T040 output is offline evidence only. It does not prove a manager's payload serialization, real host callback disappearance after `remove=true`, official JVM verification/execution, runtime integration, or SC-04a CPU/RSS/PSS/heap/elapsed measurements.

## T040 runtime ProtectionDomain source binding

The official profile now separates build-time read-only reference data from runtime source identity. The fixed 5303 path is used only by the offline profile to read and hash official bytes and to create a temporary task-local clone for tests; it is never emitted as an official runtime `codeSource` property. Official runtime properties use:

```text
turboism.validation.t039.sourceBinding=target-pd
turboism.validation.t039.trustedSourcePaths=<one task-local target candidate>
turboism.validation.t039.jarSha256=<fixed official JAR SHA-256>
```

On the first target-definition callback, `T040RuntimeSourceBinding` obtains the observed source only from that callback's `ProtectionDomain`. It requires a `file:` CodeSource, a regular canonical file, the configured loader identity, exactly one canonical trusted target candidate, exact PD/trusted-path equality, and the fixed JAR SHA-256. It rechecks the bound path and bytes before the official candidate can be returned. Thus a same-hash clone at a shifted task-local path is accepted, while a different hash, multiple candidates, a non-file source, PD/trusted-path mismatch, or wrong loader is rejected. The finite property is a target-candidate list, not a general classpath resolver; ambiguity is fail-closed. Existing class/CFG/descriptor/flags and T039/T038 helper gates remain in force.

`T040RuntimeSourceBindingHarness` reads official class bytes only, checks the fixed class/shape identity, and exercises the success and all listed rejection cases with pure JDK `ProtectionDomain` objects. It never defines, verifies, executes, or puts the official class/JAR on an application classpath. `T040_RUNTIME_SOURCE_OFFLINE_PASS` is only this source-binding/data evidence; it does not attest unknown transformers, full runtime bytes/JIT, real host loader behavior, or a production manager. The existing owned JVM path and all T030/T033/T035/T038/T039/T040 regressions remain separate.
## Side-effect-free admission

`T033FixtureAdmission.isAdmitted` runs before either implementation writes. It reads only scalar
arguments, array identity, and array lengths; it does not scan source pixels.
It admits only a conservative, known contract:

- positive power-of-two factors and positive source/full dimensions;
- `fullWidth/fullHeight` exactly match the original align-up result;
- `fullWidth * fullHeight`, `kx * ky`, source capacity, and every full-loop
  source coordinate are representable/safe;
- the original assertion capacity is satisfied even when the simulated JVM
  assertion mode is disabled (conservative admission, not a repair of the
  original assertion semantics);
- the full-loop `x * kx`, `+ kx`, `- 1` and y intermediates are safe, not just
  their final values;
- `sumAlpha` and worst-case `sumChannel * 255` cannot overflow `int` without
  reading pixels;
- padding/stride target coordinates and full destination capacity are safe;
- source and destination are not the same array.

A rejection returns the untouched original bounds. The candidate equivalence claim is
limited to the Guard-admitted domain. Dangerous overflow, invalid-capacity, and
non-terminating-bound inputs are guard-only. Finite rejected inputs (including null,
capacity, alias, and dimension/layout mismatches) execute both independent fixture
byte arrays and compare backing, source, exception type, and event trace. The two
MIN-axis cases likewise execute both original and patched bytes: helper rejection
keeps the original inclusive CFG, so the empty-source first read fails after clear.
A negative control deliberately returns zero bounds on rejection and requires that
comparison to fail, proving the patched precheck is exercised.

## Evidence and execution

`build.sh` creates one private run directory per invocation with
`mktemp -d "${TMPDIR:-/tmp}/atlas-image-kernel-probe.XXXXXX"`. It never removes
or reuses an earlier run directory. Each run retains:

```text
$RUN_DIR/input/       snapshotted build script, regression script, README, Java,
                       relocator source, and cached ASM source/POM
$RUN_DIR/input/deps/asm-9.7.1.jar       measured original ASM 9.7.1 snapshot
$RUN_DIR/input/deps/asm-9.7.1.pom       matching license/dependency metadata
$RUN_DIR/input/deps/asm-9.7.1-shaded.jar private relocated 38-class closure
$RUN_DIR/classes/     classes compiled against the private shaded closure
$RUN_DIR/t039-shadow-agent.jar  self-contained validation agent (no Class-Path)
$RUN_DIR/offline.log  this run's complete synchronous log
$RUN_DIR/sha256.txt   hashes of every snapshotted input
```

The hash file is created from the snapshot before compilation and checked again
after the self-test; no final hash reads the active worktree source. JVM/Javac
injection variables and `CLASSPATH` are unset. Compilation uses
`javac --release 17 -proc:none -Xlint:all -Werror`. The ordinary offline harness
uses the per-run classes plus the private shaded JAR; the independent premain
regression uses only the self-contained agent JAR. No official Cubism JAR is copied
or placed on any application classpath.

Run one isolated build:

```bash
./validation/atlas-image-kernel-probe/build.sh
```

The command prints `RUN_DIR`, `SNAPSHOT_DIR`, `CLASS_DIR`, `LOG_FILE`, and
`HASH_FILE`; use those actual paths as the evidence identity. Failures retain
the same run directory and log (and a snapshot hash whenever the snapshot was
complete).

A T039 run also prints `T039_AGENT_JAR`, `T039_FIXTURE_JAR`, `T039_FIXTURE_META`, `T039_AGENT_MANIFEST`, and `T039_INVENTORY`. The agent/fixture JARs and metadata are generated only inside that private run directory; the inventory records their SHA-256 values and the snapshot manifest hash. No generated artifact is copied into this source directory.

Run the parallel evidence regression:

```bash
./validation/atlas-image-kernel-probe/parallel-build-regression.sh
```

It starts two builds concurrently, then proves that both have distinct run,
log, hash, snapshot, and classes paths; each log contains its own hash path and
`OFFLINE_PASS`; each hash verifies its own snapshot; and the two hash manifests
match. The regression directory and both worker run directories are retained.

The self-test uses explicit `check(...)` failures and simulated assertion
modes; it does not depend on `-ea`. It reports whole-backing-array diff counts,
source-read-order checks, and full-vs-trim block iteration counts. Those are
offline arithmetic/work-count observations only, not host acceleration or
synthetic wall-time claims. A successful run ends with exactly:

```text
OFFLINE_PASS
```

## Coverage and limits

The self-test covers 5000 fixed-seed random whole-backing comparisons with
oversized strides, backing tails, non-zero reused destinations, source
immutability, and identical non-empty source-read order; partial blocks with
`bfffffff`/`2fffffff`; alpha 0/1/2/3/4 truncation cases; tiny, factor-one,
and legal large-factor inputs; and guard rejection for nulls, invalid factors,
negative/zero dimensions, aliasing, capacity, original assertion failure,
coordinate/area/accumulator overflow, and invalid target layout. It separately
reports fallback route self-consistency and exact original-CFG exceptions for
both MIN_VALUE axes × both assertion modes × reference/fallback, with empty
source, immediate `ArrayIndexOutOfBoundsException`, and a cleared destination.

The T035 owned execution additionally reports `targetWrites=32/2` for that partial
case (reference/candidate), demonstrating actual patched loop-bound execution rather
than only an admission-route assertion. Its three partial fault cases are each run
once; no catch-and-rerun is used.

This does **not** prove host class loading, Cubism integration, runtime or
bootstrap behavior, full HQ filtering/Java2D/native pixel pipelines, source
expansion side effects, allocation/pool behavior, resource release, later
page expansion, sorting, Runner safety, lifecycle, or real performance. The
only status this tool can publish is `OFFLINE_PASS`; it is not host readiness
or an optimization acceptance claim.

The bytecode basis is the read-only audit material at
`/tmp/atlas-safety-audit-t027-eidJUz/5303/com.live2d.util.f.g.javap` and
`shrink-loop-checks.py`. Those files and the official JAR are not copied into
this directory.
The T031 boundary finding is recorded in the read-only
`/tmp/atlas-safety-audit-t031-SxOprg/report.md` and
`reference-boundary-witness.txt`; those files and the official JAR are not copied
into this directory.
T033/T035/T038/T039 authority is `specs/020-atlas-image-parallelism/tasks.md` T033/T035/T038/T039 and its plan Summary, including SC-04a. The fixture scope stays inside this directory.
its plan Summary, including SC-04a. The fixture scope stays inside this directory.
SC-04a requires real CPU time, RSS/PSS/JVM-heap, elapsed, paired host measurements;
this offline slice records no such measurement and cannot substitute for it.
