# T030 / T033 / T035 / T038 / T039 / T040 — isolated offline bytecode-slice evidence

## Scope and authority

- Worktree: `$HOME/.paseo/worktrees/…/atlas-shrink-empty-blocks`
- Baseline: `83a4916822e549d04c58b6947f697afc05a65629`
- Authority: `specs/020-atlas-image-parallelism/tasks.md` T030, T033, T035, T038, T039, T040; `plan.md` Summary; and `spec.md` SC-04a.
- Read-only audit basis: `/tmp/atlas-safety-audit-t034-qn4lyk_g/report.md` (SHA-256 `623dd9b58a70d008d5a5ea24153c76e6c1f4dcb82d6e44df5badbabb186df3cc`), plus the T027/T031/T034 evidence already cited by the authority.
- Changed only `validation/atlas-image-kernel-probe/`; no runtime/bootstrap/core/settings/UI/sort/Runner/spec change, commit, merge, sub-agent, host launch, signal, or production hook.

This report is offline evidence only. It is not functional, production-loader, integration, or performance acceptance.

## T038 implementation

### Host-free helper and Guard boundary

`src/dev/turboism/validation/atlasimage/t038/T038ArrayHelper.java` is a pure Java 17 scalar/array helper. It performs no pixel reads or writes. It exposes the full T033 admission checks and bounds result:

- null, alias, factor, dimensions, stride, padding, exact aligned full dimensions, full-area/source-area/block-area, inclusive-end intermediate arithmetic, accumulator, padded target coordinate/stride, and array-capacity checks use checked `long` calculations;
- rejection returns the original `fullWidth/fullHeight` bounds, never the candidate dimensions;
- accepted dimensions are `min(fullWidth, ceilDiv(sw,kx))` and the analogous height;
- T033's existing harness remains intact: its admission facade delegates to this helper, so the prior 5000-case and MIN/reference-CFG regression coverage still exercises the extracted full Guard.

Source SHA-256: `a10715783e620547da067d51ff0e6aedbf877a174fd5353c79e89c4f7c32f051`.

### Finite helper visibility and identity gate

The T035 two-boundary transformer is reused through `T035OwnedTransformer.applyForHelper`; only the helper owner/descriptor supplied to the already gated rewrite is parameterized. The insertion remains immediately before the fill call, where its four operands are already on the operand stack; it is not the original BCI 61 empty-stack point. The original parameter 8/9 assertion inputs, clear, stride/padding, surrounding B instructions, inclusive-end CFG, and pixel body are unchanged. The only boundary reads redirected by the transformer are the two new locals.

The finite T038 gate consists of:

- `T038HelperBinding`: exact helper `Class<?>`, loader identity, owner, descriptor, public/final class shape, and public static bounds method reflection checks;
- `T038OwnedClassLoader`: child-defines only the owned fixture and helper; otherwise delegates to its parent. A no-byte child therefore tests default parent delegation, while two byte-supplied children test coexisting loader isolation;
- `T038OfflineAdapter`: performs helper visibility/identity checks and the existing fixture byte/shape/repeat gate before ASM parsing or fixture definition. Missing helper, wrong helper identity, wrong loader, owner, descriptor, descriptor/flags/CFG/frame/type-merge shape, and repeated patch all return the original byte-array identity and show zero fixture definitions/ASM parses.

Hashes:

- `T038HelperBinding.java`: `2a8322390623ddf93e728e1bc7445c553d10ce6628083d22a234647feee3a624`
- `T038OwnedClassLoader.java`: `2c887dc698e82fdec825f3726cd51d5d1f7a3942efc4f2e8c227b6d0ae24f71a`
- `T038OfflineAdapter.java`: `3acebe90decc42a4f05701fe67c349d61699104906e3df147e2b2c7f82c575b4`
- `T035OwnedTransformer.java`: `14ba2ff10821453aa0db6f21b51085f0e721c70e2ee10d3be204e907f1999310`
- `T033FixtureAdmission.java`: `349e8fbd010fbd8652a2ea2257717711d1e72998134f32510c32323ed72ac6ba`

### Owned JVM integration

`T038OfflineHarness` first runs all retained T030/T033/T035 tests and then executes only the hand-generated owned fixture under ordinary JDK `-Xverify:all`. It does not extract, rename, define, or execute any official class. The owned partial case is `kx=ky=4`, `sw=5`, `sh=3`, `full=8x4`, `stride=12`, `padding=2`; it proves accepted bounds `2x1`, whole dirty backing equivalence, unchanged source, source-read-order equivalence, one clear, and opaque `0xbfffffff/0x2fffffff` results. Guard-rejected execution proves original bounds, clear-before-failure, exception equivalence, and no rerun. Factor-one, normal/fault rejection, and the existing 8 MIN-axis × assertion-route tests remain.

The output also records:

```text
t038PureArrayHelper=PASS guard/full-bounds/no-pixel-read
t038ParentDelegation=PASS helper-visible/preflight-before-define
t038OwnedExecution=PASS full-backing/source-read-order/clear/trim
t038LoaderIsolation=PASS parent-and-coexisting-child-identities
t038GuardRejectExecution=PASS original-bounds/clear/exception/no-rerun
t038PreflightNegatives=PASS missing/identity/loader/owner/descriptor/shape/repeat
t038HelperSha256=47500ced125b0d69bd42d1af3f3d55137a2e3fb785ffaca87f69678bf3635fd6
T038_OFFLINE_PASS
```

## T039 validation-only auxiliary shadow agent

`T039ShadowHelper` is a pure validation-only shadow helper. Its public bounds descriptor is `(II[III[IIIIIZ)J`; it closes over the exact `T038ArrayHelper` Guard/candidate arithmetic but always returns the original full bounds. It records only bounded scalar samples and counters. One immutable atomic state contains each sample with its counters, so concurrent snapshots cannot combine two parameter groups. It does not read pixels, copy or write arrays, retain array references, write files in the hot path, start threads, or expose an unbounded event stream.

`T039ShadowAgent.premain(String, Instrumentation)` accepts no agent argument and reads only named `turboism.validation.t039.*` properties. The owned and official `5303` profiles are separate. Official mode defaults to `data-only`: the manual `Session.transform` test analyzes the official bytes and discards the candidate. Only explicit `shadowMode=shadow-ready` plus `shadowOptIn=T039_SHADOW_EXPLICIT_OPT_IN` returns the official candidate byte array to that test caller; this build never defines or executes an official class. Candidate counts are not method-execution counts; `methodExecuted=false` throughout.

The finite helper closure prewarms and gates both T039 and T038 by exact class identity, loader, public-final shape, method descriptors, resource hashes, and scalar prewarm. Resource hashes bind loaded class resources only; they are not full runtime-byte/JIT attestation, and unknown transformers remain unproved. The cold transformer uses an `ARMING` lifecycle, a synchronized add/second loaded-target check without retransformation, and inert late callbacks. JDK `Instrumentation.removeTransformer` returns a boolean: the actual Session arm/transform/unregister regression drives true/false/throws, records `REMOVED`/`NOT_REMOVED`/`FAILED`, returns a candidate only for true, and leaves false/throws `BLOCKED`, registered, and with `candidateReturnedCount=0`. `remove=true` proves only the removal call result, not disappearance of an already-dispatched callback; the missing-result classifier is synthetic and is not an API success.

The owned JVM harness defines only the hand-generated T035 fixture under `-Xverify:all`. It checks whole-backing/source-read/clear/exception behavior, atomic concurrent helper statistics, actual instrumentation removal outcomes, ARMING/race behavior, late-callback inertness, and both official manual-transform modes. The output is:

```text
 t039PremainArm=PASS no-args/named-properties/helper-prewarm
 t039OwnedExecution=PASS whole-backing/read-order/clear/exception/repeat
 t039ShadowConcurrency=PASS atomic-sample/counts
 t039ShadowHelper=PASS full-bounds/no-pixel-fields/bounded-stats
 t039InstrumentationRemoval=PASS arm/transform/unregister true/false/throws/late
 t039RemovalStatuses=PASS classifier model only
 t039ArmRace=PASS synchronized-arm/late-callback/loaded-after-scan
 t039OfficialModes=PASS data-only/no-return/shadow-ready/manual-candidate
 t039OfficialDataOnly=PASS version=5303 classpath=none execution=none candidateBytes=...
 t039FailureCleanup=PASS kind=t038-helper-hash state=REJECTED
 t039LoadedGate=PASS already-loaded/unknown
 T039_OFFLINE_PASS
 OFFLINE_PASS
```

Negative JVM runs cover wrong loader, class hash, CFG/shape hash, CodeSource, missing T039 helper, wrong T039/T038 resource hash, forbidden agent arguments, failed-premain cleanup, and already-loaded gating. This proves only the owned validation fixture and read-only official/manual-transform gates. It does not install a host hook or claim official JVM verification/execution.

### Self-contained ASM runtime closure

The cached `org.ow2.asm:asm:9.7.1` source JAR is read-only build input with SHA-256 `8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281`; matching POM SHA-256 is `7229b03b30a73ee91008072d9e4569a51d8547fae8c50f527841aef4c1b0baa8`, license metadata is BSD-3-Clause, and the private relocated 38-class closure SHA-256 is `a318365bd1befe7d1d73d5d16f7933a7a5081f10d28019d499ac683be7e1385f`. `AsmNamespaceRelocator` is a finite pure-JDK build tool; the agent embeds only `dev.turboism.validation.atlasimage.shaded.asm97`, has no manifest `Class-Path`, and the inventory records `agentContainsOriginalAsm=false` and `agentManifestClassPath=false`.

The build's independent JDK process passes only the agent JAR to both `-javaagent` and application `--class-path`; it exercises premain, first owned definition, actual patch/helper, and freeze. A finite runtime linkage/format precheck runs before shape parsing/patch. Missing and corrupt `ClassReader.class` variants still drive first target definition and fail closed with `bytecode-dependency-gate`, zero candidates, and successful cleanup. This gate is linkage/format evidence, not full runtime-byte attestation; source/resource hashes and CFG gates remain separate.

## T040 immutable freeze bridge

### Implemented API and exact keys

`T039ShadowAgent.public static Map<String,String> freezeCapture(String expectedRunId)` is implemented as a one-shot, validation-only bridge. It requires exact `runId`; unarmed, unpatched, blocked, repeated, or mismatched runs reject. Successful output is an immutable string map and has no file I/O, class definition, official-class execution, array retention, retransformation, thread creation, or production installation.

Exact keys, in insertion order:

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

`sampleOutcome` is constrained to `NO_CALLS`, `NO_ELIGIBLE_CALLS`, `POTENTIAL_TRIM`, or `TRUNCATED`; `dropped>0` selects `TRUNCATED`. The helper freezes one atomic scalar state under its short diagnostic lock. Bounds calls arriving after freeze return original full bounds and do not update counters/sample; `prewarm` and `resetCounters` reject. The lock does not cover B/g or h and the helper performs no hot-path I/O.

### Evidence and boundary

- `t040OfficialFreeze=PASS`: manual official 5303 bytes only, real Session arm/transform/unregister path with fake `remove=true`, `SHADOW_READY`, `REMOVED`, returned=1, registration=false, no preflight failure, `sampleOutcome=NO_CALLS`, 43-key immutable map. No official class was defined, verified, executed, or put on the application classpath.
- `t040OwnedFreeze=PASS`: owned-only real fake arm/transform/unregister setup, unarmed/unpatched/BLOCKED/wrong-run/repeat rejection, four concurrent in-flight helper callers, late full-bound call, stable all-`stats.*` snapshot, immutable map, and frozen reset/prewarm rejection. `POTENTIAL_TRIM` or `TRUNCATED` is reported from the bounded event limit; no pixel data is retained.
- T039's actual true/false/throws removal-chain regression remains separate. `remove=true` still proves only the API return value, not disappearance of already-dispatched callbacks. `candidateReturnedCount` remains a transform observation, not method execution.

This is offline evidence only. It does not prove a manager payload/canonicalization path, real host callback disappearance, official JVM verification/execution, runtime integration, or SC-04a CPU/RSS/PSS/heap/elapsed measurements.

## T040 runtime source binding repair

The official runtime path no longer compares `ProtectionDomain` to the build machine's golden path. Build-time T035/T039 data still reads the fixed read-only reference JAR and its exact SHA-256, class SHA-256, and CFG/shape SHA-256; runtime official properties instead carry `sourceBinding=target-pd`, the fixed expected JAR hash, and one bounded task-local `trustedSourcePaths` candidate.

`T040RuntimeSourceBinding.bind` derives the observed file solely from the first target-definition `ProtectionDomain`, requiring a `file:` URL, canonical regular file, exact loader identity, exactly one canonical trusted candidate, exact observed/trusted path equality, and the fixed 5303 JAR SHA. The first `verify` is immediately after `bind` and before class/shape gates and patch. After a shadow-ready `unregister`, a second `verify` runs immediately before incrementing `candidateReturnedCount` and returning the candidate. No personal golden path is placed in runtime properties; the bounded candidate list is intentionally not a general classpath resolver and rejects ambiguity.

`T040RuntimeSourceBindingHarness` passes a same-hash JAR at a shifted path, then explicitly rejects a different hash, same-hash multiple candidates, non-file CodeSource, PD/trusted-path mismatch, and wrong loader. It reads/checks official 5303 bytes and shape only; it does not define, verify, execute, or classpath-load any official class. The harness status is `T040_RUNTIME_SOURCE_OFFLINE_PASS`, not production readiness.

This repair proves only the finite task-JVM source-binding/data gate. It does not prove an actual host's classpath discovery, unknown transformer effects, full runtime-byte/JIT attestation, production manager approval, or official JVM verification/execution.
## Official profile boundary

T035's official profiles remain read-only byte data for versions 5203, 5302, and 5303. Fixed 5303 evidence is JAR `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166`, class `ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6`, shape `a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f`. The official target is `a(II[III[IIIII)V`; the profile reports fill BCI 68, boundary BCIs 74/121, 244 original instructions inverse-mapped from 265 emitted, 13/13 untouched `method_info`, inserted count 21, and zero common-super/type-resolution calls. `StrictNoResolutionWriter` rejects resolution directly and never delegates, calls `Class.forName`, or uses `Object` as a fallback. Official classpath and execution are both `none`; no candidate class/JAR is produced.

The official data-only and shadow-ready manual Session tests now bind a temporary task-local clone through its ProtectionDomain and the runtime candidate gate; they retain the fixed official JAR SHA and never publish the clone or official bytes as a loadable artifact.

## Retained T030/T033/T035 coverage

The same build retains fixed-seed 5000 whole-backing comparisons with zero backing and source-read-order diffs, full-vs-trim iteration counts only, factor-one/no-empty-block behavior, partial `bfffffff/2fffffff`, alpha 0–4 truncation, invalid capacity/overflow/factor/alias/bounds guards, 8 finite MIN boundary executions with first-read AIOOBE and clear-only backing, the bad-zero-bounds negative control, and one-shot clear/read/write fault propagation. T035 retained official profiles and owned-fixture `-Xverify:all` checks all pass. Counts are arithmetic work counts only; they are not host acceleration, CPU, memory, or wall-time evidence.

## Build, dependency, and parallel evidence

`build.sh` creates a fresh `mktemp -d` run directory, snapshots source/scripts/README and the cached dependency before compiling, uses synchronous file redirection, retains failed logs and exit status, and never shares or removes a prior `classes` directory. It unsets JVM/classpath injection. The compile command is:

```text
javac --release 17 -proc:none -Xlint:all -Werror
```

The cached source dependency is `org.ow2.asm:asm:9.7.1`, SHA-256 `8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281`; matching POM SHA-256 is `7229b03b30a73ee91008072d9e4569a51d8547fae8c50f527841aef4c1b0baa8`, and the private shaded JAR SHA-256 is `a318365bd1befe7d1d73d5d16f7933a7a5081f10d28019d499ac683be7e1385f`. No framework was downloaded. Final source/tool hashes are `build.sh` `4bdf92c33cca14b83be48fc8ca5cfb66cadc8057902ee420c4f9f46820d4bccf`, `README.md` `322005f299a9ad4c72e60076fcaa845451ab1ccc4702baa703cda500c619152a`, `T039ShadowAgent.java` `481acfefe035311b439c9b32afd3ff4b62c0ef96318f14be216184a31c8dfbb3`, `T040SelfContainedJvmHarness.java` `2ce37f0c2380ae6c2cd639ff33d8d9b1b8f2d1fe460f42c9b9748f5d878af25d`, and `AsmNamespaceRelocator.java` `5b3c497b8cbc1fec7128bc2d6fe4db80b1c15681102bf3cefbec563789f83e08`.

Commands run:

```bash
bash -n validation/atlas-image-kernel-probe/build.sh
bash -n validation/atlas-image-kernel-probe/parallel-build-regression.sh
validation/atlas-image-kernel-probe/build.sh
validation/atlas-image-kernel-probe/parallel-build-regression.sh
```

Final isolated build: `/tmp/atlas-image-kernel-probe.IxQnUi`; synchronous log SHA `f279e6faebe0564cd91da32eb5fd31232b26f1b893f989e552ad308ad75ed4c8`, snapshot manifest `/tmp/atlas-image-kernel-probe.IxQnUi/sha256.txt` SHA `55e0363cab8f5f613cccc38e6fe434686377f468d568c1bbcfadf31cf22726eb`, agent JAR SHA `511eba960c13ab0ffe50a13c84fc683ddeef96aa3add74a7eacbb81c234ef42e`, fixture JAR SHA `546a03dc4d30932a42312582667d51f3d202a653a9d83005aa02cbe00545cc01`, and inventory SHA `7b1d5219531cfe180c5fda2dd175aeecf6265eab3ab3b09fce8b6e3abe17a935`. The log contains `T040_SELF_CONTAINED_OFFLINE_PASS`, the missing/corrupt-ASM gates, `T040_RUNTIME_SOURCE_OFFLINE_PASS`, and exit status 0.

Parallel regression directory: `/tmp/atlas-image-kernel-parallel.AKitoR`; worker A `/tmp/atlas-image-kernel-probe.B3OslL` has log SHA `c2545e7f06adfce161b82deed95c52ef960cc745e236e7716587af8c1f0165e4`, snapshot SHA `55e0363cab8f5f613cccc38e6fe434686377f468d568c1bbcfadf31cf22726eb`, agent JAR SHA `d3df76a38f1e01ce87cb6e378c70454c8dc25679642997b77903f0bf49c11535`, inventory SHA `7428b5a20a87afea2a1fdac0784f8cf31a5122f442a07a3687d5bdf56b1d5572`; worker B `/tmp/atlas-image-kernel-probe.SqoMIo` has log SHA `42787ceee809c9c315264ec5adbac7a042d15f097ff729bf27dc66609e76ad46`, the same snapshot SHA, agent JAR SHA `284556f644de9d4cde210013f299dd5921c9016cfff9a937c0457cc202797a57`, inventory SHA `bf2e3c3d56c48e7c1444eb603571a50eb9835145b5e20cebd368247286ce6df4`. Both worker logs contain `T040_SELF_CONTAINED_OFFLINE_PASS`, the missing/corrupt-ASM dependency gates, `T040_RUNTIME_SOURCE_OFFLINE_PASS`, `T040_OFFLINE_PASS`, `T039_OFFLINE_PASS`, and `OFFLINE_PASS`; both exit 0 and the parallel script reported `parallelBuildEvidence=PASS`.
## Limits and acceptance boundary

This slice does not prove official candidate JVM verification/execution, production transformer selection, StackWalker/batch/config/UI behavior, Cubism/Proton/SSH behavior, runtime/bootstrap integration, complete HQ/Java2D/native pipelines, allocation/release/expansion side effects, sorting, Runner safety, lifecycle, functional acceptance, or performance. `-Xverify:all` proves only the owned fixture classes. SC-04a requires real paired CPU time, elapsed time, RSS/PSS, JVM heap, and declared limits; this offline slice deliberately performs none of those measurements. Only `OFFLINE_PASS` is claimed. Changes remain uncommitted for the main agent's independent reread and rerun.
