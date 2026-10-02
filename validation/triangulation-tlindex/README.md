# T029-TLINDEX offline differential

Own-shadow differential slice for `TriangleList.a(j)` edge indexing.
Official Cubism classes are never read, loaded, or executed.

Run:

    ./run.sh

Requires an idle host-validation queue (the script waits for it).
Dependencies (compile/runtime only, resolved from Gradle cache or env
overrides `TLI_ASM_JAR`/`TLI_KOTLIN_STDLIB`): ASM 9.7.1 (sha-pinned) for
`TliWeave`, kotlin-stdlib for the own fixture's Intrinsics preamble.
Official-host probing is static-only via
`triangulation-weave-ab/OfficialShapeProbe` — no official class is
executed. See DESIGN.md for the bytecode-verified facts, invariants,
weave shape, integrated A/B status, and the historical host plan.

## Production continuation (T029-TLPROD)

The production bridge is `runtime/.../mesh/TriangulationEdgeIndex.java`, not this
own-shadow fixture's Bridge. Its weak identity registry avoids Set value hashing,
expunges collected keys on subsequent accesses and unregisters normal clears;
proven-dead sets keep only weak tombstones so clearing never reactivates an unsafe
index. Fourteen production regressions cover the original differential semantics,
identity-only keys, deterministic queue cleanup and independent-set concurrency.
This is mechanism evidence, not a real-host heap/RSS/CPU measurement.

`run-atlas-image-shadow-host-validation.sh` production on/off legs use one production
agent and `tl-dump-only` auxiliary capture; validation weaving is forbidden. The
5203 heavy pair requires `TLPROD_EXPLICIT_OPT_IN` at the wrapper/driver seam. For
manifest publication use `build-and-selfcheck.sh --host-profile 5203
--fixture-profile heavy --tlprod-opt-in TLPROD_EXPLICIT_OPT_IN` with the pinned
per-version heavy fixture environment key; the ordinary 5203 profile stays closed.
No arbitrary environment fixture digest replaces the fixed name/hash pairs.

The settings probe's edge-index mode exports actual UI-saved on/off config snapshots.
`--tri-tlindex-home-config` freezes a leg-matching explicit Boolean for subsequent
production starts; acceptance must separately bind the file SHA to a PASS UI result.
Synthetic protocol tests are not UI or restart evidence.

As of 2026-09-30, production regression/config-merge tests, two-family static JAR
shape checks, the complete wrapper protocol regression and devCheck pass. Old
979910f4… production 5303 on/off results remain historical evidence only. The global
queue has an orphaned seq2004 and no worker, with `recover safe=false` reported by
its owner; the user chose offline convergence. Final-artifact three-version A/B,
SC-04a actual resources and real settings/restart acceptance remain blocked. No
forced queue recovery, new host launch, main merge or readiness claim was made.
The authoritative scope/evidence/task state remains Spec Kit `specs/020`.


2026-10-01 update: the queue recovered under its owner's workflow. Real UI, final-agent
three-version correctness A/B, and repaired-driver 5203 repeat completed. See
[dated host evidence](host-evidence-20261001.md) for exact jobs, artifact hashes,
resource observations and limitations. 5203 performance acceptance remains open;
T044/T046 are not complete and nothing was merged to main.


The follow-up production removal correction is documented in the final section of
[DESIGN.md](DESIGN.md). Candidate `640e3b1e…` preserves the native removal before
identity reconciliation; its first 5203 on leg (seq2063) passed standard gates.
Its paired off leg (seq2069) also passed correctness, but the resource verdict was
negative. Follow-up candidate `29f13cf8…` completed three 5203 pairs: the first two
showed lower observed CPU/wall time, the third regressed, and all three RSS peaks
increased. Stable performance is **not established**. See the dated report for
exact evidence and the [resource acceptance plan](resource-acceptance-plan.md)
for the fixed-window A/B now submitted with the same production agent.


### Validation-only fresh-edge guard metadata (2026-10-02)

The opt-in JVM option
`-Dturboism.validation.triangulationEdgeGuard=FRESH_EDGE_GUARD_METADATA_V1`
enables `TRIANGULATION_FRESH_EDGE_GUARD` stderr records when the guard computes a
cold type decision. Set it before the first guard use through the managed
Runner/prepared input; it does not activate a disabled production hook. Each
record includes actual type/loader metadata, the equality method owner,
admission/rejection reason, final/Object-superclass checks and
`coldComputation=true`. Loader hash labels are diagnostic labels, not unique
process/class identity proof. An absent or wrong token stays silent.

These are cold computations, not query counts or cache-publication receipts.
Concurrent first uses may compute more than once, and a type already cached
before enabling the option may emit no record. Missing records cannot prove
rejection or absence of execution. Nonfatal diagnostic failures leave the
original decision and native fallback unchanged; fatal JVM failures propagate.
No new strong class/loader registry is introduced. Cached-query instructions
remain unchanged. The independent seq2373 diagnostic admitted the actual 5302
edge type and passed the standard host gates plus first-four ordered output
checks. It supplies no eliminated-scan rate or performance acceptance. Frozen
performance Agent `ee244d0f…` is unchanged. See the dated host evidence and
`build/t048-offline/offline-review.json` for tests and hashes.

The managed shadow wrapper accepts `--tri-fresh-edge-guard-metadata` only for
production `on<N>` labels with `--tri-tlindex tl-dump-only`. It adds exactly the
fixed JVM option above; default prepares omit it. Off legs, non-production legs,
duplicate flags and arbitrary token arguments are rejected. A diagnostic leg
uses its own frozen Agent/bundle and does not establish an A/B performance gain.

### Offline lazy edge materialization experiment (2026-10-02)

The next candidate targets the three temporary edges in h.c's constrained-edge
intersection loop. Each pinned edge overload forwards to an existing four-point
native overload. A candidate can keep that native computation and its order,
while constructing a fresh edge only when an intersection needs subsequent
edge processing. Null/index/assertion checks still precede all three calls;
the fourth construction/intersection elsewhere in c is outside this candidate.

`diagnostic/audit-lazy-edge-materialization.py` checks exact 5203/5302/5303 JAR
hashes, final types, pure getters, constructor calls, native forwarding and site
inventories using javap only. Supply local paths through `--jar-5203`,
`--jar-5302` and `--jar-5303`; their hashes must match the fixed reviewed versions.
`diagnostic/LazyEdgeMaterializationSelfCheck.java` runs only owned types.
It checks ordered endpoint identity, distinct duplicates, call order, constructor
failures, changing coordinates and float boundary cases. The private predicate
is an owned stand-in; this is not execution of the native triangulator.

Java17 lint/Werror compilation and1177 owned checks passed. After24 warmup pairs,
seven alternating samples measured lower thread CPU in both sparse (-29.79%)
and dense (-22.04%) owned stencils; actual thread allocation fell99.98%/59.52%.
These are candidate-selection measurements. Input construction, warmup,
GC-thread CPU, RSS and retained memory are outside the measured scope. The
initial four-pair warmup run showed compilation drift and remains preserved.
See `build/t049-lazy-edge-offline/offline-review-cli.json` and the dated report.

No production bridge/transformer, frozen Agent or host task changed. Actual
dependency-definition gates, native bytecode/exception verification and complete
host output/performance evidence remain required. Production acceptance stays
on hold; a theoretical bottleneck has not been established.

### Definition collector research and adoption hold (2026-10-02)

`diagnostic/DefinitionAdmissionSelfCheck.java` uses a separate owned Instrumentation
agent and generated classes. Java17 lint/Werror compilation and `-Xverify:all`
pass28 checks. The ASM-core fingerprint preserves executable instructions,
metadata and control-flow positions while normalizing pool indices, debug data
and member order. Live capture rejects a changed definition even when its loader
returns reviewed resource bytes. Later retransformation revokes an old gate;
concurrent independent captures and weak loader lifetime checks also pass.

This collector is **not ready for production admission**. A deterministic control
registers a capable transformer after the collector: the collector sees reviewed
value3 bytes, its gate passes, but the real JVM executes value9. Matching an
intermediate callback cannot prove the final definition. Transformer ordering and
lifecycle ownership must be resolved before integrating the lazy-edge candidate.
No runtime production source or frozen Agent changed, and no host job was added.

Evidence and exact commands are under
`build/t050-lazy-edge-bytecode/definition-admission/`, with final
`offline-review.json` SHA
`3f4bb3be9a3baf8d9cb0c51cef7e6cd8e3bddb3c577e15116f290f3edd9ff9df`.
The research slice is recorded; T050/T044/T046 and the stable-benefit goal remain
open. Current recommendation is to hold production adoption and merging. Further
work comprises production integration with native fallback, complete correctness
verification, and final three-version performance/resource acceptance. Existing
host measurements have not established stable net benefit or a theoretical limit.

### ASM-core lazy-edge port and existing-transform composition (2026-10-02)

`diagnostic/CoreLazyEdgeBytecodePrototype.java` replays the selected c method
through core ASM visitors, adding the same eight locals and lazy construction
branches as the tree prototype. Its compilation classpath contains only ASM
core9.7.1; jdeps confirms no tree/commons dependency. It remains diagnostic code,
with no runtime installer registration or packaged production candidate.

The public CLI accepts a reviewed official JAR and a new output directory, with
optional `--compose <frozen-agent>`. The optional Agent must have the fixed
ee244d0f… digest. `FrozenEdgeTransforms` loads its own reviewed patchers in an
isolated loader and rejects parent shadowing. Composition is fresh, then
membership, then core lazy materialization. Official bytes remain data.

The expanded owned selfcheck accepts `--core` or `--compose <frozen-agent>` before
its optional new fixture directory. Each of tree, core and composed modes passes
3533 checks under `-Xverify:all`, including explicit membership debug/order and
repeat controls. Remapped owned edges use the existing fresh guard's native
fallback; these checks do not establish its fast guard execution on native types.

All three official-version composed outputs are byte-identical to the tree
oracle. Static preservation compares against the reviewed post-membership
baseline, retaining its non-c methods and original c executable order/fourth
path. `verify-lazy-edge-static-output.py --baseline` requires the baseline digest
from candidate pins; its report distinguishes baseline pool preservation from
the pristine official pool. Wrong baselines and unreviewed Agents are refused.

Evidence is under `build/t050-lazy-edge-bytecode/core-composition/`; final
offline-review.json SHA
`95e3e6d247d0c9b7802a7ec188bd2fcde9fc31f40bd1ce7c0c5fde30a462b2d3`
binds184 inputs. The definition collector ordering gap is still unresolved.
Official output class definition/native execution, production integration and
final host acceptance remain open; no new host job or performance result was added.

### Actual official class definition check (2026-10-02)

`diagnostic/NativeClassDefinitionCheck.java` accepts the reviewed official JAR,
existing candidate directory, fixed frozen Agent and a new result directory.
Run it in an isolated Java17 process with `-Xverify:all` and the owned
DefinitionAdmissionSelfCheckAgent. It defines candidate h plus official dependencies,
resolves their metadata and retransforms them for capture. It invokes no official
initializer, constructor or geometry method. Generated controls prove the metadata
and capture path does not initialize classes and rejects an invalid return opcode.

All six standalone/composed cases for5203/5302/5303 pass. Eight dependencies per
case match their runtime fingerprints: h, j, TriPoint, l, r, GVector2, Kotlin
Assertions and Intrinsics. Definition uses original signer/domain metadata for
package compatibility; altered bytes do not acquire an official JAR signature.

The initial full-fingerprint comparisons failed because HotSpot omits invisible
CLASS-retention annotations and the Deprecated pseudo access bit during
retransformation. `DefinitionFingerprint.of` retains complete static metadata;
the separate `runtimeOf` projection retains instructions, control flow, fields,
member flags/signatures and visible annotations. Full static fingerprints of all
six prior candidates remain identical. Owned tests refuse instruction or visible
annotation changes, check recorder defensive copies/failure fallback and retain
the explicit downstream-transformer limitation. All36 owned admission checks pass.

Evidence is under `build/t050-lazy-edge-bytecode/native-definition/`, including
initial failures, raw reviewed/observed bytes, classpath inventory and receipts.
offline-review.json SHA
`e0443a1e612a759be0b310f4927bf6c323c912835d8fc937ad3a831497e19f77`
binds568 inputs. This is controlled Linux OpenJDK17.0.20 definition evidence;
production transformer ownership, native initialization/geometry/outputs and
final host performance/resource acceptance remain open. No new host task was added.

### Owned definition lifecycle for integration (2026-10-02)

`runtime/.../mesh/TriangulationDefinitionLifecycle.java` provides the JDK-only
ownership gateway for the pending lazy-edge integration. Bootstrap passes its
handle to both premain installers and the bootstrap thread, before JvmShims
installation. Admission requires one trusted premain Agent with the expected
manifest, the actual HotSpot DisableAttachMechanism value enabled, no native/debug
Agent or external boot/module replacement options, and retransformation support.
Unsupported starts receive the original Instrumentation and cannot capture an
admitted gate. Default launcher arguments have not been changed.

All owned transformer registration/removal, retransform/redefine and module/native
prefix changes acquire exclusive ownership before JVM entry. Capture installs its
last capable collector under that same ownership. Optimized execution must hold
a thread-confined read lease for the entire operation, including exceptional exit.
Subsequent owned mutations wait outside the JVM and permanently revoke gates;
capture itself retires previous gates before retransformation, including on a
failed capture. Mutations and lease acquisition from transformer callbacks refuse
without waiting, and same-thread read/write upgrades refuse. Search-path appends
retain their existing behavior: they cannot replace already loaded dependencies.

`diagnostic/OwnedDefinitionLifecycleSelfCheck.java` executes generated owned
classes with real Instrumentation. It verifies concurrent downstream registration,
operation-time redefinition, callback rejection, failed recapture revocation,
defensive copies, restoration requiring a new gate, thread-confined/idempotent
leases, retained-gate loader release and installer ownership handoff. Separate
JVMs reject enabled/overridden attach, two Agents and debug options. The older
unowned collector limitation remains valid; this protocol requires bootstrap to
withhold the raw handle and trusted in-process code to use the gateway. It is not
a security boundary against hostile JNI, reflection or escaped raw Instrumentation.

Evidence is under `build/t050-lazy-edge-bytecode/owned-lifecycle/`. This mechanism
is not yet used by the lazy weave. Official dependency fingerprints, full lease
coverage/native fallback, supported single-Agent validation probe composition,
native output/initialization and final host performance/resources remain pending.
The existing multi-Agent host setup does not satisfy this new admission contract.
Frozen Agents, official BATs, standard startup flags and the host queue are unchanged.

The module-aware inherited callback control first failed: its declaring base did
not implement ClassFileTransformer. The final detector also recognizes the trusted
java.instrument transform dispatch frame. The failure log and pre-fix source are
retained. Final Java17 lint/Werror and -Xverify:all pass115 checks across six JVM
cases, including the helper on Boot-Class-Path (bootstrap loader). Thirteen existing
bootstrap tests and devCheck pass; initial public-API documentation failure is
retained. Jdeps finds only JDK modules. The eligible packaged Turboism bootstrap
itself has not been executed, and no official geometry or host performance ran.
Final offline-review.json SHA
`185b5fbbfb80c0b6faa47a425d1427625cccf0bc5126d51696b497195f31cc29`
binds66 inputs; the pre-correction checkpoint is superseded.

### Production lazy weave and packaged helper verification (2026-10-02)

The runtime transformer now attempts fresh, membership and leased lazy stages in
that order. Lazy preparation requires the existing owned lifecycle, exact complete
host identity from ReviewedHostArtifacts, the pinned Kotlin JAR, a reviewed
post-membership baseline and the canonical bridge. It builds an immutable plan
without application Class/loader references. Initial weaving and actual live
definition admission have separate receipts; all weave receipts bind the final
returned bytes. Unsupported preparation preserves the two earlier stages.

The JDK-only bridge publishes an empty ClassValue holder and performs one capture
inside the published holder. Callback entry refuses before metadata/capture.
Actual eight-dependency fingerprints, metadata/origin/loader links and exclusive
capture admit a full-method read lease; absent or permanently revoked leases use
the original eager construction path. Normal and Throwable exits release the
lease. The cold first group and fourth construction path remain eager. In owned
mode startup suppression keeps its already inert transformer until handle close,
avoiding prohibited self-removal from a transformer callback.

`diagnostic/LazyEdgeBytecodeSelfCheck --leased` accepts the owned Instrumentation
Agent with attach disabled. Optional `--production-compose` uses the current
packaged fresh-shape and membership patchers before the leased shape. Both modes
pass4882 owned checks under -Xverify:all, including native fallback construction
counts, exception stack/order/identity preservation and real exclusive capture
after every method exit. The remapped owned fresh guard still uses native fallback;
these checks do not execute official geometry.

`diagnostic/LazyEdgeProductionIntegrationSelfCheck` receives the official JAR,
prior reviewed runtime-fingerprints.tsv, a new output directory and reviewed or
tampered mode. Its only Agent provides Instrumentation and exposes the new
packaged candidate on Boot-Class-Path. The real production transformer runs in
initial definition callbacks. All three versions admit eight concurrent cold
callers with exactly one capture; modified live edge definitions refuse admission.
Later mutation permanently retires the old holder's gate. Six packaged cases
pass189 checks and the packaged lifecycle check passes46. Official classes are
defined and metadata resolved; initializers, constructors and geometry are not
invoked. This does not execute the complete Turboism premain.

Forty affected JUnit tests, devCheck and bootstrap JAR packaging pass. New isolated
candidate SHA is0e5bb77f401a25a8d28399de0fb893200abe6320d8beaa2d6a04ad302c9aff44.
Exact commands, initial failures and final artifacts are under
build/t050-lazy-edge-bytecode/production-integration/. Final offline-review.json SHA
ef491d69296b3bb3e05fe460380918bc883d80772883d4552a798ae6763b8955 binds391 inputs.
The earlier wrong expected rejection string and frozen-patcher parent-shadow
refusal are preserved; explicit current packaged composition succeeds. Existing
ee244 performance and0dc308 diagnostic Agents remain unchanged.

This closes the integration code slice. Production acceptance stays on hold.
Remaining gates are supported single-Agent probe/full-bootstrap startup, native
initialization/geometry/full repeated outputs, and final three-version stable
performance/resources/UI/startup linkage. Current multi-Agent host validation
cannot enable this new weave; default launch flags have not changed. No new host
job was submitted, stable benefit is unproved, and no theoretical limit is claimed.


Actual-premain metadata validation is described in [single-agent/README.md](single-agent/README.md).
The2026-10-02 hash-composition slice passes all three versions with the existing
hash optimization on/off, plus three negative controls (96 checks);187 affected
JUnit tests and devCheck pass. These checks invoke no official initializer or
geometry and start no full Editor runtime. Production remains
HOLD_PRODUCTION_ACCEPTANCE; full host scene/repeated outputs and final
three-version stable performance/resources/UI/startup linkage remain open.

The supported single-Agent scene subsequently passed real 5302 job2395. Frozen
inputs and staged artifacts were independently checked; the lazy patch was
admitted, startup config remained unchanged, and the first four complete ordered
endpoint digests matched bound off baseline job2365. See
[2026-10-02 host evidence](host-evidence-20261002.md). This is bounded native
correctness/activation evidence; full-cycle outputs and stable performance stay
unproved. Final-candidate 5302 pair 2396/2397 completed with first wall −5.77%,
first Java CPU +3.73%, whole observed Java CPU −3.07% and sampled RSS peak −12.54%.
JFR estimated edge-allocation weight fell 98.82%, with actual lazy-path samples;
this is an allocation estimate, not a speedup percentage. One pair and no later
target samples leave stable/repeated-target acceptance open. 5303 composition
and the remaining three-version gates stay open.

### Native producer diagnostic driver (2026-10-02)

`diagnostic/build-auto-connect-driver.py OUTPUT --producer-recorder --base-agent
PINNED_BASE [--host-profile 5203|5302]` builds an independent, validation-only driver using the reviewed
b47f6f47 production dependency. Its native command scope binds every selected
source to the actual edit-data mesh identity. It writes ordered 14-column
`auto-connect-producer-results.tsv` and per-cycle
`auto-connect-producer-status.properties`, including partial failed evidence
before stopping. Legacy delayed-cache capture remains a separate default mode.
The default profile is5203 and preserves every frozen r5 source and JAR entry's
content. Explicit5302 requires the producer recorder and freezes its version
guard plus the native CUB3-4362 cancellation resource;5303 and unknown profiles
are refused. The reviewed Yes/Cancel responder shape and all measured commands,
recorder and resource windows remain unchanged. The5302 artifact is separate.

Registration and removal run in an ordinary owner class; a separate transformer
class implements the callback so owned callback rejection remains effective.
The weave preserves mesh and ticket in new locals because official Kotlin frames
discard dead original arguments before return. Its catch frame assumes only those
new locals remain live. Own dead-argument bytecode executes under -Xverify:all;
official 5203 metadata passes the real companion premain with an admitted lazy
lease. Metadata checks start no Editor and execute no official geometry.

148 focused checks and devCheck pass. The initial frozen driver SHA is
6fff29ad096257f53e77aae1737b22604c622f5e9b3af926e88dfd20f29788c6.
Evidence is under `build/t050-lazy-edge-bytecode/producer-driver-r3/` and the
[host evidence record](host-evidence-20261002.md). One FIFO diagnostic is allowed;
it is distinct from Atlas A/B performance acceptance. Frozen production artifacts,
the shared Runner and the original stale-cache refusal remain unchanged.

The one 5203 run (seq2406) failed at the first MESH_CONNECT source-binding gate:
711 sources selected, zero producer events and zero complete cycles. The recorded
message is `native edit data order/source differs from selected IDs`. Normal exit
and production gates did not pass; task-owned cleanup is safe. This is a
diagnostic failure before measured production work, not evidence of no speedup.
The offline correction obtains the complete native edit-data order immediately
after native entry's exact selected-object identity check. It rejects omissions,
additions, duplicate identities/IDs and later order changes. The native-binding
selfcheck passes 17 cases. No second host run was submitted. The corrected driver
SHA is 5e34ad58afaa5064616e819e8ed61dbcf9549186f4d6d728d36019269c4ee3ae;
its offline report is under `build/t050-lazy-edge-bytecode/producer-driver-r4/`.

The corrected r4 later passed one bounded 5203 native diagnostic (seq2408): all
711 sources in each of three cycles, 2133 producer records and all standard host
gates. Native command durations were 58.395/56.900/57.972 seconds. Positions match
across cycles, while eight/six sources' index hashes differ from cycle 1; no
matched off diagnostic exists yet. Retained RSS medians are
2584.72/2582.87/2602.88 MiB. Complete output/resource evidence is independently
reviewed in producer-driver-r4; this is not Atlas A/B or final production acceptance.

Its native cancellation prompt caused 482 seconds of additional wait after all
measurement windows. The distinct r5 revision responds only to a new modal prompt
directly owned by the bound task window, with the native CUB3-0009 locale message
and the exact reviewed Yes/Cancel option shape. It confirms cancellation once,
stops/releases its Timer on every outcome and checks restoration of the bound
document's main mode. Unknown prompts are untouched; response/leave deadlines
are 10/30 seconds. 56 real own-modal private-Xvfb checks, native binding17,
writer11, CaptureWait17 and actual premain metadata admission pass. The driver
contains no selfcheck classes, and measured source/recorder helpers are unchanged.
The r5 driver is frozen separately as b6684c49. Its later matched5203 off/on
pair (seq2413/2414) passed all standard gates and matched all2133 complete
producer result projections. Total native command wall time fell21.49%, sampled
Java CPU19.00%; the cancel responder completed in0.256/0.377 seconds. Independent
recomputation and639 post-writer pin checks passed. This closes that bounded
pair. On baseline/operation RSS is higher and both third retained windows contain
native serialization/archive work; retention safety remains undecided.
See the [current host evidence](host-evidence-20261002.md) for hashes and remaining
acceptance. Production remains NOT_PASSED.

The explicit 5302 producer profile later completed its own matched off/on pair
(seq2418/2421). Both legs pass all standard host gates, and all 2133 complete
producer projections match. Three command windows total 105.498/off versus
72.803/on seconds (30.99% lower); sampled Java CPU falls 28.87%. Independent
recomputation and 744 post-writer pin checks pass. This closes that bounded
native auto-connect report. On has a lower initial RSS baseline but higher
operation peaks and retained medians: operation peak maxima are 2957.58/off
versus 3825.05/on MiB. Memory acceptance remains open, with investigation of
existing evidence taking priority over another performance run. Final 5303,
memory stability/limits and formal native startup/real-settings/restart linkage
remain open; production remains NOT_PASSED. See the current host evidence record
for complete comparisons, hashes and limitations.
