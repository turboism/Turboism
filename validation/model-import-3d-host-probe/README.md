# Model import 3D validation probes

Spec Kit 040 has two separate entry points. `src/`, `build.sh` and `selfcheck.sh`
exercise the accepted **offline** locate/identity/digest/material protocol.
They never load or connect to Cubism and their native sections remain NOT_RUN.
`native-src/` and `build-host.py` implement the separately gated N2 investigation below.

## Build / run (offline only)

From the repository root (Bash, Python 3 stdlib, JDK 17 required):

```sh
PROBE=validation/model-import-3d-host-probe
bash "$PROBE/build.sh" --dry-run
bash "$PROBE/selfcheck.sh" --dry-run
bash "$PROBE/selfcheck.sh"  # fresh UUID run; prints paths
python3 "$PROBE/selfcheck/test_output_safety.py" --real-java
```

Every build/selfcheck exclusively creates
`build/model-import-3d-offline/runs/<new-id>/`. Existing runs are refused, even
if empty. There is no cleanup/reuse option and no historical directory-name
exception. Traversal and symlink components are rejected before normalization.
Failed attempts remain as evidence; retry with a **new** ID.

For a standalone probe, choose a previously unused ID (do not reuse the
literal example on a second invocation):

```sh
RUN="$PWD/build/model-import-3d-offline/runs/$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
bash "$PROBE/build.sh" --out "$RUN/classes"
java -Djava.awt.headless=true -cp "$RUN/classes" \
  dev.turboism.validation.modelimport3d.Main --out "$RUN/first"
```

`Main` accepts only a fresh direct child of its own compiled run; arbitrary
`--root` overrides are not supported. Selfcheck success/failure seals the run
with `.terminal`; failed builds seal too. A successful standalone build stays
open for explicitly requested fresh probe outputs. Abrupt termination may
leave an incomplete run, never a reusable wrapper output. This is local
offline evidence protection, not an independent host-supervisor verdict or a
sandbox against hostile writers with the same OS identity.

The safety regression runs against disposable source copies under a new
`main-t022-audit-*` directory. Compiler/runtime stubs test admission and
failure paths; `--real-java` additionally runs the actual offline fixture and
checks sealed-run/foreign-run rejection. It retains all copies and logs.

Exit codes: `0` = all offline checks passed · `2` = at least one check failed ·
`3` = usage / I/O / path-safety error. A **rejection** self-check that passes
means the invalid input was *correctly refused* — it is not a successful
operation (`operationStatus` in the report records the operation outcome
separately from the check `status`).

## What the run produces

`report.json` + `material.png` under each fresh probe child (`CREATE_NEW`).
Selfcheck also retains classes, negative-test artifacts, `selfcheck-log.txt`
and an append-never `.terminal` process exit marker in its run directory.

- `offlineSelfcheck` — PASS/FAIL checks plus rejection checks, each with
  `expected`/`actual`/`status`/`evidenceKind=OFFLINE_FIXTURE`.
- `native` — the **required** real-host assertions (evaluated reads, Undo/Redo,
  dirty state, save/reopen, atlas repack, stale host refs, U vs U-B, morph
  basic identity, morph isolation, texture binding, history admission) plus
  the required SDK read seams. They are always `NOT_RUN` / `BLOCKED` here,
  `evidenceKind: null`, `requiredEvidenceKind: EXACT_HOST`,
  `evidenceProvided: false`. The native summary can never be `PASS` offline
  (`verdict` is `NOT_EVALUATED`). Missing entries are a verifier
  violation, not an absence.
- `materialSamples` — frozen expected ARGB constants, sample coordinates and
  `tolerance=0`, plus `sha256` of the generated PNG. This is material sanity
  evidence only; it says nothing about native texture binding or atlas
  tolerance.
- `domainDigests` — SHA-256 over canonical LE encodings (explicit draw-order
  presence byte; `null` draw order cannot collide with `Integer.MIN_VALUE`).
- `payloadSha256` — deterministic content hash (checks/rejections/native/
  material/digests/scope), using signed big-endian UTF-8 byte-length fields
  (`-1` for null). Delimiters in strings cannot alias field boundaries.
- Schema `t006-offline-r3`: `runId` hashes executionId + payload + source/class
  manifest hashes; two executions with identical payloads have distinct IDs.
- `build` — `inputs/` is a readonly source/entrypoint snapshot compiled by
  javac 17. `sources.sha256` and `classes.sha256` bind paths and file contents;
  `build.properties` retains compiler version, HEAD/status capture and run ID.
  `Main` and the independent verifier recheck inventories and bytes. The dirty
  fingerprint combines captured status and source-content hashes, not just
  filenames. Class provenance covers on-disk bytecode, not loaded-class bytes.
  Disposable non-Git copies report `head=unobserved`, never an ancestor's HEAD.
  These hashes detect drift; they are not signatures against a malicious writer.
- `hostIdentity` — `observed:false`, `cubismVersion:null`, `jarSha256:null`.
  No synthetic scope identity is presented as a host identity.

`verify_report.py` (Python stdlib, independent of the generator) accepts only
**clean r3 offline evidence**: fixed tuple/default/morph/alias/snapshot values,
per-case rejection reasons, complete exact ID sets, typed summaries, entirely
unobserved host identity, captured build inventories, payload/run hashes and
PNG CRC/size/filters/deflate/alpha/frozen samples. Fault reports remain useful
negative evidence but are not accepted as clean successes. To review elsewhere,
copy the whole run bundle, retaining its run/child names (not just report.json).

Selfcheck runs `test_report_integrity.py`: every counterexample has its own
unmodified passing control before mutation; even checksum-repaired semantic
fakes must fail. Artifacts are retained under the fresh run's `report-integrity/`.
The original removed-native, bad-PNG, zero-digest, payload and invalid-JSON
coverage is retained here, rather than accidentally testing path mismatches.

## Fixture protocol (what the offline data asserts)

- `Binding` — paramGuid/paramId, finite min ≤ max and default inside range,
  unique keys (`Float.floatToIntBits`: `+0.0`/`-0.0` distinct, NaN/Inf rejected).
  Missing declared-default keys are retained as diagnostic fixture input and
  rejected by default lookup; duplicate param IDs/GUIDs are rejected.
- `Grid` — every `CellEntry` tuple is complete (arity = binding count), no
  duplicate/extra/unbound dimensions, every key declared, no duplicate cells,
  non-blank formGuids. Cell order is list order; **no native mixed-radix
  indexing is implemented**.
- `SourceGeometry` / `Form` — positions/UVs/indices lengths consistent, all
  finite, indices in `[0,n)`, index count % 3 == 0; optional draw order is
  presence-encoded.
- Default tuple = per-axis declared `defaultValue` matched by exact key lookup
  (`[-1,0,1]` default `0` → middle index, **not** index 0). Missing/ambiguous
  default keys fail closed.
  `Fixture` construction enforces default form identity when the default cell
  and its form exist; intentional missing-cell/form negatives remain locatable
  failures rather than being silently repaired.
- Morph targets located by `(paramGuid, keyValue)` exact match; `keyValue=null`
  is an observation record (never an exact hit, never writable). An explicit
  `0.0` key is still `basicIdentity: UNKNOWN` — no zero-is-basic rule.
- A formGuid referenced by more than one cell/target is reported
  `alias=true, writable=BLOCKED_ALIAS`, never as an independent writable form.
- Stale generation / wrong owner / missing form / stale formGuid / arity,
  NaN, duplicate-dimension, unbound-dimension, undeclared-key inputs all fail
  closed with typed reasons.
- `DomainSnapshot` has a single private canonical constructor (no digest
  input); getters return copies. Digest results are independently
  recomputed by `verify_report.py`, not compared to a cached value.

## N2 SDK-only native investigation (not stage A acceptance)

N1 exposes immutable `Drawable.authoringSnapshot()` for exact 5.3.02 only.
N2 creates a **new blank task document**, then a four-vertex ArtMesh and two
ordinary three-key axes. It observes stored forms/complete tuples (including
aliases), verifies native creation Undo/Redo, deletion rejection, read/history
no-change, and restores the initial blank model. The startup fixture is only a
read-only seed. No seed objects are modified. The temporary new document is
not saved in this investigation; persistence remains a later stage A gate.

Document snapshots carry a session-local `modelId`; the live `CubismModel.id()`
carries a native model GUID. N2 **does not compare these namespaces directly**.
On the EDT it brackets live reads with document observations and compares each
identity only within its own namespace. Both seed and new-document records
include `documentId`, snapshot `modelId` and `editorModelId`; each must change
for NEW_MODEL. Every later task access rechecks all three plus native history
binding before restoration. The bounded 90-second wait keeps only the last
observation, attempt count and phase (`SEED`/`NEW`), including honest missing,
changed or errored states. A timeout never fabricates a seed or a new model.

`native-src/` is compiled **only against the public SDK and JDK**, not runtime or
Cubism jars. No reflective host access, simulated evaluation or new launcher.
Its manifest declares model read/write; it is never part of release packaging.
`native-test/` is a synthetic oracle self-test, not a host run.
`packaging-test/` is a separate offline checker compiled against the **actual
bundled runtime**, calling `PluginDescriptorParser` and `PluginJarContract`.
It never instantiates the plugin or loads Cubism, and is not put in the probe
JAR. Each build checks the real JAR and three rejected content mutations
(missing base catalog, missing entrypoint, undeclared localized catalog).
The implicit base i18n catalog is required even when `locales` is empty.

Build explicit artifacts first (`./gradlew previewBundle :sdk:jar`). Then use the
actual SDK and agent paths from that build, not a guessed newest artifact:

```sh
python3 validation/model-import-3d-host-probe/build-host.py \
  --sdk /absolute/worktree/build/path/sdk.jar \
  --agent /absolute/worktree/build/preview/path/turboism-agent.jar \
  --run unique-unused-label --dry-run
# Repeat without --dry-run to compile the snapshotted sources and run synthetic assertions.
export TURBOISM_MI3D_BUILD_RUN=/absolute/worktree/build/model-import-3d-host/unique-unused-label
bash scripts/preview/run-model-import-3d-host-validation.sh 5302 unique-label --dry-run
```

Every native build exclusively owns a new `build/model-import-3d-host/<label>`;
existing/empty/symlink/traversal runs are rejected, including after failure.
`provenance/` pins 26 source/manifest/wrapper/test files plus compile SDK;
`build.json` pins SDK/agent/probe/checker class hashes and revision. Eight
recorded commands include raw java/javac version output, SDK-only compilation,
synthetic tests, JAR packaging and the separate actual-runtime contract check.
`verify-host.py --build-run ... --build-only` independently compares SDK classes
with those in the actual agent, probe/checker inventories, declared catalogs,
resource bytes against snapshots, command logs and source bytes. It does not
compile or start a JVM during wrapper dry-run. The
wrapper hands agent/probe and provenance to the existing queue snapshot via
`--agent`, `--plugin`, `--home-dir`. It never rewrites `.env` or official files.

After the separately recorded G3 decision, the existing queue catalogue entry
is `model-import-3d:5302`. Use `host_validation.py prepare`, review the prepared
input, then `submit` with a fresh request ID; the existing single worker owns
execution. Never use a direct Java/Proton launch or restart somebody else's
worker. See `scripts/preview/README-host-validation-scheduling.md`, the generic
Runner `--help`, and the local operator runbook `docs/agents/host-validation.md`.

N2 uses `mi3d-native-n2-v1`, not the offline report schema. It records expected,
actual and status for each native observation and retains the source/grid/form
snapshots. `report.json` is CREATE_NEW, then `result.properties` is CREATE_NEW
last. In the current build, probe `status=PASS` requires **N2, N3a, N3b, N3c, N4a and N4b assertions**; `stageAStatus` stays
NOT_EVALUATED. Host identity is not fabricated from JVM options. Missing
assertions, exceptions, non-restoration, wrong identity or missing final
scope cleanup cannot be promoted to host success.

The supervisor archives the plugin report at
`final-task/turboism-home-logs-state.tar`; no extraction of arbitrary archive
paths is needed. After a completed job:

```sh
python3 validation/model-import-3d-host-probe/verify-host.py \
  --build-run "$TURBOISM_MI3D_BUILD_RUN" \
  --evidence /absolute/job/evidence --run-id ACTUAL_RUN_ID
```

The independent verifier requires fixed geometry/nine tuples/identity/restore
observations plus `lifecycle-result.json` from the **contained supervisor**,
matching run/attempt/prepared identities, official JAR/BAT and seed hashes,
staged artifact hashes, normal exit and safe task-owned cleanup. Building,
preparing or passing a fake fixture never constitutes exact-host acceptance.

## N3a stored target writes (evaluation still NOT_RUN)

The current build adds `ordinaryWrite` (`mi3d-native-n3a-v1`) without changing
any of the 15 N2 checks. Its 16 additional checks exercise existing targets
(+1,0) and (-1,0), while both sliders remain at (0,0). Fixed stored positions:
A = (48,40), (304,40), (304,168), (48,168);
B = (-4,64), (252,64), (252,192), (-4,192).
Default, seven other forms, all draw orders, source positions/UVs/indices and
layout must remain unchanged. Two writes use one SDK authoring transaction;
full snapshots and receipts evidence one Undo/Redo, no-change, and deliberate
callback failure after swapping both targets. Default write, incomplete tuple
and wrong vertex count must be rejected without mutation. It restores its
starting history position before N2's final restoration. Task identity checks,
outer finally, immutable build inputs and queue/G3 requirements are unchanged.

`verify_native` now requires all three sections; an old N2/N3a-only report is not success
for this new build. `verify_observations` remains the historical N2-only helper
for tests; archived builds retain their own matching verifier. The independent
N3a oracle derives identities from N2's grid, uses frozen numeric constants,
compares entire snapshots, all captured parameter values, native history and
transaction receipts, and rejects contradictory statuses even with PASS checks.

T032 samples `historyGridRedo` independently in HostProbe after N2's allRedo,
with `historyGridEntryIds` / `historyGridRedoEntryIds` on both sides of that
round trip. N3a's initial history must equal this post-Redo anchor **in full**,
including revision and decoded details. Across the N2 Undo/Redo itself, entry
identities/count/position/binding stay equal, but decoded details may change
when native Undo objects lazily acquire redo payloads. Missing anchors reject
old reports; never insert synthetic observations into archived host evidence.

Transaction receipts and facade states use distinct history-provider clocks.
Cross-provider comparison preserves full entries, entryIds, position,
availability and both binding IDs; generation/revision remain recorded,
nonnegative integers and are checked within their provider instead. Receipt
generation must stay stable, revisions cannot go backwards, and NO_CHANGE /
ROLLED_BACK receipts require exact before/after equality. All same-provider
no-op, rollback and rejection whole-state checks remain unchanged. This is
not permission to ignore history details or to equate unrelated model leases.

**Not evaluated geometry:** the existing `Drawable.geometry()/vertexPositions()`
read current-keyform, whose ordinary grid lookup requires exact key values.
They are not an interpolated-form oracle at 0.5. N3a records
`evaluationStatus=NOT_RUN`, `coordinateSpace=EDITOR_STORED_FORM`; independent
native interpolation/rendering, pixel tolerance, target draw order, morph,
images and the complete stage A lifecycle/persistence gates remain open.

## Explicitly not established by N2

- Texture decode/register/model-image binding, morph creation/basic identity,
  U versus U-B, target writes, full-import atomicity, dirty state, save/reopen,
  atlas repack and plugin-disable matrix remain **NOT_RUN**.
- Stored form alias observations do not provide independently writable targets.
- Offline float-bit equality is not native EQUAL/NEAREST behavior; offline PNG
  tolerance says nothing about host atlas tolerance.
- Eager native undoables still need the reviewed five-part transaction protocol
  before batch participation; N2 only uses existing separate native edits.
- No stage A completion, version expansion, merge or release is implied.

## N3b calculated ordinary positions (new exact-host evidence required)

`evaluation` (`mi3d-native-n3b-v1`) starts NOT_RUN. After the unchanged 31 N2/N3a
checks restore the task model, a separate root fixture is created on a 1024×1024
canvas. SDK `evaluationSnapshot()` reads calculated, not stored current form.
All SDK operations and observations retain HostProbe's three-domain identity
checks on EDT. The worker yields between samples; no forced evaluator or GL call.

Nine samples: baseline at x=+1 before target writes; written at +1; native Undo
at +1; Redo at +1; +0.5, 0, -1, -0.5, 0. Y remains zero. Two target writes use
one transaction. This makes Undo/Redo change evaluated geometry without slider
writes destroying the Redo branch. N3a still independently proves target choice
with both sliders at default. Source/default/other forms/UV/index/order stay intact.

The 15-second wait observes a change of the **consumed set's own** native counter
and consumption of all requested live GUID/ID/values. It never compares the two
sets' counters, nor waits for expected positions. The first consumed observation
is fixed, even if its calculated form is absent/wrong (then FAIL). Timeout keeps
last observation, attempts and reason. EDT queue stalls are additionally bounded
by the existing external supervisor; they cannot yield a late PASS. Read history
and stored snapshots must not change. The independent Python oracle freezes all
nine expected arrays and allows at most 0.1 pixel Euclidean error per vertex.
The retained official console is also checked for swallowed native evaluation
exception stacks. A fresh pass-entry stamp alone is not mesh success.

Rendering configuration is observed, not changed: actual JVM arguments and the
two reviewed properties must select the official Offscreen path. Backend evidence
is honestly CONFIGURATION_DERIVED, not a reflected backend instance. The official
BAT uses relative classpaths under its working directory; a unique same-install
`app/lib/jogl/jogl-all.jar` is read only (bounded 32 MiB) and pinned to the
investigated SHA-256 `7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9`.
G3 independently checks the official assets; no asset is modified or executed by
this check. Native root form labels DeformerLocal/Canvas are preserved, not
renamed to Core units. Their pixel interpretation is limited to the undeformed
root/Identity-transform fixture documented in Spec Kit research §36.

Native Undo in the inner finally restores initial counts, canvas metrics and
history position; the outer task finally remains in force. Creating the new
fixture intentionally replaces the old N2 redo branch; no claim is made that
those abandoned entries are recreated. Current `verify-host.py` requires all
four matrices, immutable build identity, official final console and contained
supervisor termination. Old reports are never supplemented or retroactively
accepted. Images, morphs, target order, persistence and Stage A remain unverified.


## N3c ordinary draw order (new exact-host evidence required)

`OrderProbe` appends a separate SDK-only root fixture after the existing 44 checks.
It never rewrites those matrices. It creates a 1024-square canvas, one mesh and
nine independent ordinary forms with baseline order 500; source positions,
UVs and indices remain frozen. Source/form GUIDs must differ from N2 and N3b.

First, with sliders at (0,0), one transaction writes non-current (+1,0) order
1000 and (-1,0) order 0. Its five stored/history states prove isolated writes,
Undo, Redo and restoration. While written, controls cover no-change, four
order-first/position-first × same/different-target callback rollbacks, and a
three-contribution same-target rollback. Each records the actual intermediate
snapshot, completed step count and throw marker. Native refresh faults are
not injected here; those are separate offline T033 defensive regressions.
Seven SDK rejection controls cover four out-of-range integers, default target
(including a same-value request) and an incomplete tuple, without state changes.

Parameter writes themselves enter history. After restoring the non-current
prefix, set X=1 on a new branch **before** the evaluated order transaction.
This keeps X=1 throughout order Undo/Redo rather than accidentally undoing the
slider. The nine first-consumed samples require exact calculated orders:

| Sample | X | Order |
| --- | ---: | ---: |
| baseline | 1 | 500 |
| written | 1 | 1000 |
| undone | 1 | 500 |
| redone | 1 | 1000 |
| positiveMid | 0.5 | 750 |
| defaultAfterPositive | 0 | 500 |
| negativeEnd | -1 | 0 |
| negativeMid | -0.5 | 250 |
| defaultFinal | 0 | 500 |

All positions remain `[16,24],[272,24],[272,152],[16,152]` within 0.1px
Euclidean error. Order has no tolerance or clamping. Worker waiting reuses
`EvaluationProbe.awaitConsumed`; EDT sampling retains three-domain identity
protection, stored/history nonmutation and the first consumed observation.
Wrong order on that observation fails immediately, never polls until correct.
Actual JVM configuration is independently re-sampled and matched to N3b.

`verify-order.py` owns its fixed geometry/order/rollback expectations. Receipt
comparisons use T032's shared semantic fields across providers while validating
local counter ordering independently; same-provider no-change/recovery states
remain fully equal. The new `order` section defaults to NOT_RUN; missing old
reports cannot be upgraded. Inner finally restores canvas/count/history position.
The 28 new checks require a new immutable 22-source build, packaging contract,
prepare, G3 and FIFO host run. Until its final supervisor/verifier evidence is
accepted, target order remains unverified on host. Morphs, images, persistence,
full atomic import and Stage A are not covered by this matrix.


## N4a morph keyform members (new exact-host evidence required)

`MorphProbe` appends a fourth SDK-only root fixture after the existing 72 checks.
It never rewrites those matrices. On a fresh 1024-square canvas it creates one
mesh, the same two ordinary three-key axes, then — in a separate committed
operation — two normalized non-repeating BLEND_SHAPE parameters M and N on
[0,1] with default 0. Native parameter GUIDs are resolved from live evaluation
parameters by unique parameter ID; a `ParameterId` is never used as a GUID.
`baseline.morphKeys` must be empty: an auto-materialized unknown target fails
its check rather than being deleted.

Frozen stored content: base B, first target U, midpoint H, second target U2 and
controls V/W (Java and Python each own the literals; expected values are never
derived from actual output). A single SDK transaction creates (M,1), seeds B
and writes U; one committed entry yields one new form, one morph key and the
(M,1,new-form) triple. Fixed Undo/Redo samples prove B/U; the old handle is
observed rejecting in BOTH the member-deleted Undo state and the post-Redo
state, while a fresh lookup returns the same detached form. `baseSnapshot()`
must equal the exact ordinary default-cell form, never a fabricated zero
target. Samples at M=0.5 and 0 require the independently frozen H and B on the
first consumed pass.

Only then is the second key (M,0.5) created, seeding H. Rewriting (M,1) to U2 is
isolated: before/written/undone/redone whole-snapshot states prove the 0.5
member and unrelated records untouched. Three more first-consumed samples
require U2, H and B; the second key can never alter the first key's linear
expectation.

Member transaction controls run at M=N=0. An unrelated (N,1) create seeds B. One
execute performs create(.25)→write V→create(.75)→write W with mandatory seeds
[(24,28),(280,28),(280,156),(24,156)] and [(56,44),(312,44),(312,172),(56,172)]
— interpolated seeds toward existing keys, never bare midpoints. Two Undo/Redo
rounds plus a final Undo leave the member set at its pre-transaction state. The
same four steps with a unique sentinel produce ROLLED_BACK with the full before
state restored. The tested unrelated (N,1) handle is acquired only after the
final in-flight membership mutation and must still be VALID before the sentinel
— an earlier-captured handle is already stale and proves nothing — then the same
handle must reject after compensation while its detached form stays readable
and a fresh lookup by GUID returns equal values. A no-change position write
keeps handles valid and history untouched; a committed position write leaves
the tested handle VALID before its Undo stales it again. Fourteen rejection
controls cover zero/negative/>1/NaN/Infinity/epsilon-near/duplicate creates,
zero/missing/ordinary lookups, wrong vertex count and stale reads/writes with
typed exceptions and byte-equal before/after state; the ordinary-parameter
lookup is a domain rejection (UnsupportedOperationException), distinct from
missing-parameter or missing-key NoSuchElementException.

`verify-morph.py` owns the fixed geometry, morph identity, absolute-vs-delta,
key isolation, transaction, receipt, stale/rejection and restoration
expectations independently of the Java encoder. The rollback transient is
rebuilt whole from the verified pre-transaction state plus exactly the two
members the transaction's own triples identify — source geometry, UVs, indices,
axes, cells, every old form/key and both seed identities included. The `morph`
section (`mi3d-native-n4a-v1`) defaults to NOT_RUN and is mandatory for new
reports; old reports missing the section fail rather than being upgraded. The
historical
`deferred` list — including morphCreation, absoluteVsDelta and targetWrite — is
intentionally retained unchanged; this matrix is limited single-instance
evidence, not the full stage A morph surface. Image import, texture binding,
persistence, atomic import, host lifecycle and Stage A remain NOT_EVALUATED
until separately gated evidence is accepted.


## N4b morph draw order (new exact-host evidence required)

`MorphOrderProbe` appends a fifth SDK-only root fixture after the existing N4a
matrix. It never rewrites those matrices. On a fresh 1024-square canvas it
creates one mesh, two ordinary three-key axes X/Y and — in a separate committed
operation — two normalized BLEND_SHAPE parameters M/N on [0,1] with default 0,
with IDs distinct from every previous matrix and GUIDs resolved from live
parameters by unique parameter ID. The baseline is the same nine-form grid at
order 500 with an empty `morphKeys` inventory; a materialized unknown target
fails rather than being deleted.

The section exercises `ArtMeshMorphKeyform.setDrawOrder(int)` end to end, in
stored and evaluated space. One committed transaction creates (M,1) and (N,1)
with order-500 base seeds; a second writes M=1000/N=0 at M=N=0 with the
in-flight state recorded after the first write, exactly two mutations and one
new history entry, a VALID post-commit handle, a same-value NO_CHANGE that
leaves state/history untouched, and two Undo/Redo rounds plus a final Undo —
after which the pre-write handle is STALE, a fresh lookup returns the base
seed, and detached/relooked snapshots agree.

Calculated order is observed only on the first CONSUMED pass after a parameter
change (never from an expected value): with M=1,N=0 evaluated order is 1000,
restores to 500 after Undo and 1000 after Redo — without the order Undo ever
reverting the parameter writes — then M=0.5 yields 750, M=N=0 yields 500,
N=1 yields 0 and N=0.5 yields 250. A separate phase writes M=701 alone and
samples M=0.5→600 (the asymmetric expectation that distinguishes truncation
from rounding) and M=1→701 while only one key exists. Only then is (M,0.5)
created — its seed must be 600 — and set to 299, after which the two-key
samples require 500 at M=0, 399 at 0.25, 299 at 0.5, 500 at 0.75 and 701 at 1.
Every calculated sample still requires the frozen base positions B.

Controls run at M=N=0 from the two-key state, entered through an explicitly
recorded parameter reset. Four orderFirst×sameTarget callback rollbacks each
perform exactly two effective writes before one unique sentinel; a three-write
700→300→900 rollback on one target; and a mixed create(M,.25)→order
700→positions V→order 900 transaction that commits, cycles Undo/Redo twice,
ends undone, then repeats under the sentinel to ROLLED_BACK — the second
creation binding its own fresh member GUID and seed — with the unrelated N
handle VALID in-flight and STALE after compensation.
Range rejections −1/1001/MIN/MAX and stale-handle reads/order/position writes
must throw typed exceptions with byte-equal before/after state.

Transport deduplicates repeated complete history payloads inside this section
only: an explicit `{historyRef, historyDomain}` replaces a value at a fixed
schema path→domain mapping enumerated in `verify-morph-order.py`
(receipt `historyBefore`/`historyAfter` positions → TRANSACTION, the mapped
facade history positions → FACADE). References at unmapped positions are
rejected — a familiar leaf key or a receipt-shaped object earns no authority —
and malformed tags, null/missing leaves and `historyEntryId` are never
references. Every bank value is validated as one complete eight-field history
before expansion. The bank holds at most 256 unique domain+complete-eight-
field values and 512 retained references counted on the final report only;
temporary comparisons and overwritten observations never consume the budget.
`verify-morph-order.py` expands references before the semantic oracle,
enforcing the 32 MiB expansion budget and a 4 MiB (4,194,304-byte) compact
section budget in compact UTF-8 (`ensure_ascii=False`) byte accounting, plus
orphan/cycle/nested-reference/same-domain duplicate-value rejection and the
unchanged same-provider clock and cross-provider shared-field rules.

Each sample row carries its own action-local anchors: history and all four
parameter values immediately before and after the action inside one EDT
callback, plus read-before/read-after history around evaluation consumption.
A parameter change is expected to commit its own standalone history entry
(`Set Parameter Value`) with the applied prefix kept and local revision grown;
an unchanged value keeps all eight history fields equal. Phase transitions —
the explicit `setM(0);setN(0)` resets before truncation, second-key and
controls stages, and the rolled-back second `create(M,.25)` carrying its own
fresh member GUID, seed and full transient — are recorded and independently
verified. The `morphOrder` section (`mi3d-native-n4b-v2`) defaults to NOT_RUN
and is mandatory for new reports; old reports missing it fail rather than
being upgraded. Every Undo/Redo leg inside the recorded cycles
(`nonCurrent.undoCycle` and `controls.mixedCommit`) is also checked in
execution order: binding, generation and entry IDs stay identical, position
returns to the expected anchor and the same-provider revision strictly
increases leg over leg — a backward or equal clock is rejected even when
position and entry IDs look right. Each sample's recorded `before.live`
parameters are cross-checked against the frozen `parametersBefore` values by
real parameter GUID and ID; the captured `consumed`/`calculated` fields of
that pre-action observation may legitimately lag and are not re-required.
The synthetic assertion suite also replays the `awaitConsumed` slow-
consumption protocol with an injected clock and sleeper (no wall-clock wait,
no host classes): 550 sampling rounds emitting 1,650 temporary history
references retain only the references surviving in the final report, the
first CONSUMED lands at 13,725 simulated milliseconds, and a never-consumed
poll preserves `lastObservation` across the exact 15,000 ms timeout.

A PASS in the N4b matrix establishes only ordinary and fused morph
stored/calculated positions and draw order on this host; the texture
binding surface is covered separately by N5a below. Image import
persistence, atlas repacking, atomic import, host lifecycle and Stage A
remain NOT_EVALUATED.


## N5a image registration/binding (new exact-host evidence required)

`ImageBindingProbe` appends a sixth SDK-only fixture after the existing N4b
matrix; it never rewrites those matrices and never reflects into runtime or
Cubism classes — it compiles against `sdk.jar` alone and observes only the
public SDK surface (`ModelTextures.rawImages()/modelImageGroups()/
textureAtlases()`, the detached `ModelImageBinding` six-field handle,
`Drawable.textureIndex()`/`authoringSnapshot()`/`setLocked`,
`Deformers.createWarp` + `Drawable.setTargetDeformer`, facade history and
position restore). Five fixture meshes are created up front; the setup
mutations (five creates, the second registration, the lock, the warp and
the reparent) are booked separately from the tested calls.

The frozen execution order is: (1) `registerImageAndBind` with a
deterministically generated 256x128 PNG (valid signature/IHDR, RGBA payload
exactly 131072 bytes) bound to mesh1 — the returned detached handle must
carry scope `runtime.editor-model`, the live model identity, a positive
generation and the raw/model-image/ArtMesh identities, and the registration
projection must add exactly one raw image and one group entry at 256x128
while atlases stay untouched; (5) a single Undo/Redo round performed while
the registration transaction is still the history stack top — Undo must
restore the blank projection, mesh snapshot and texture index, Redo must
restore the registration projection with the handle identities stable
across it; (2) the same model image bound to mesh2 (shared image), and
`bindModelImage` of image1 to mesh1 again must be a NO_CHANGE leaving the
whole projection and history untouched; (4) the five-case rejection matrix
— a different image on the bound mesh (`IllegalStateException`), a locked
target (`IllegalStateException`), a target under a deformer parent
(`IllegalStateException`), an over-budget PNG (`IllegalArgumentException`)
and a wrong-size PNG (`IllegalArgumentException`) — each compared against a
call-before SDK projection/history snapshot with zero mutation; (6) the
decode-counter observation, which is honestly `resolution: "UNAVAILABLE"`
with a reason because the private static `CImageResource` counters have no
public SDK channel — a fabricated PASS or observed value is rejected by the
oracle; (7) the final restore/counts/canvas/history-position contract.

Host finding (first host job 46c9d38e): `Drawable.textureIndex()` goes
through the Core evaluated-data join, which becomes available asynchronously
per model generation on a real host — reading it before the join fails
closed (`IllegalStateException` starting "Core evaluated data is
unavailable"). Every texture-index observation therefore polls with the
N3b bound (`WAIT_MILLIS` = 15 s, 250 ms cadence): a successful read yields
the integer, exhaustion of the bound yields the honest string
`"UNAVAILABLE"` plus the exception message as the sample `reason`, and any
other failure still propagates. The oracle accepts a sample whose legs are
all integers or all `"UNAVAILABLE"` (with a non-empty reason in the latter
case); mixed-type legs, fabricated values and cross-leg mismatches remain
rejections. The *meaning* of the index stays UNKNOWN inside this frozen G2
scope — the observation records availability, never a semantic claim.

The `textureBinding` section (`mi3d-native-n5a-v1`) defaults to NOT_RUN and
is mandatory for new reports; old reports missing it fail rather than being
upgraded. `verify-image-binding.py` owns the frozen 19-check inventory and
re-validates every handle field, projection transition, Undo/Redo leg,
rejection zero-mutation and the UNAVAILABLE counter row independently of
the probe's own assertions. `ImageBindingAssertionsTest` — dispatched by
`HostAssertionsTest.main` — drives the real probe code through every leg
with SDK-interface stubs only, including a corrupt backend that must fail
rather than pass silently.

A PASS in this section establishes only image registration/binding on the
public SDK projection surface on this host: rendering, persistence, atlas
repacking, full-import atomicity, host lifecycle and Stage A remain
NOT_EVALUATED, and exact-host readiness is not claimed.


### Archive report resource budget

The seven full matrices include repeated stored/history/receipt snapshots; a
valid complete report exceeded first the former N2-only 2,000,000-byte limit
and then the N3c 8 MiB limit (an observed complete report is 8,405,488
bytes). The verifier admits exactly one regular, nonempty member at the
fixed report path, with a **maximum of 32 MiB (33,554,432 bytes,
inclusive)**. It checks the
header size before reading, reads at most that ceiling plus one byte, checks
actual length against the header, then applies strict JSON decoding and all
196 semantic checks. Duplicate members, links/directories, oversize, truncation,
duplicate JSON keys and non-finite JSON remain errors. No sample, history
snapshot or assertion is removed to fit a smaller file.

This is a transport resource budget, not a relaxation of semantic acceptance.
The synthetic archive tests exercise the actual CLI/tar path, mocking only
build/host lifecycle prerequisites; they do not represent an executed host.
Earlier failed evidence remains immutable. This slice requires a fresh build,
review and queued host run rather than rewriting an earlier failed verdict.

### Joint projection and parameter-key density (offline groundwork)

`SurfaceProjection` is the shared calculation used by the B0 yaw probe. It
also supports compound pitch/yaw about a fixed pivot: source coordinates are
right-handed, pitch about X precedes yaw about Y, the camera looks along +Z,
screen Y is inverted, and depth is rotated source Z (smaller is nearer). Source positions must already
include static node transforms. Vertex order and coincident seam slots remain
unchanged; the helper neither edits UVs nor decides face visibility/draw order.

The depth sign matches the independently admitted native cube visibility:
at neutral pose the visible face has source Z=-1, with Z=+1 behind it.
`build/model-import-3d-offline/depth-convention-r1-20261001/native-evidence.json`
records the original archive/report/frame hashes and the neutral pixel recheck.
The earlier unused depth metadata had the opposite sign; correcting it does
not change projected XY, baked keyforms, or any frozen native bundle. This
convention check does not prove multi-surface sorting or crossing support.

`SurfaceKeyPlan.linear` plans a symmetric ordinary-parameter grid, preserving
zero and both endpoints. Three keys are only an initial layout: at scale 200,
the unit-X vertex's 15-degree projection differs by over 6 pixels from linear
interpolation between 0 and 30 degrees. For projected radius bound M and angular
steps hx/hy in radians, ideal bilinear interpolation has error at most
`M * (hx*hx + hy*hy) / 8`. The planner increases density to meet an explicit
budget and rejects grids exceeding the caller's sample limit before allocating
key lists. This conservative bound covers the specified rigid orthographic
rotation only, not visibility, perspective, morphs or native evaluator behavior.

`SurfaceProjectionTest` covers rotation order, pivot, depth, seams, B0 yaw
compatibility, invalid inputs, sample budgets and 1,000 fixed-seed joint samples.
Its 0.05px ideal-interpolation budget leaves room under the existing 0.1px native
criterion; that remaining room is not a certification of float/native errors.
The native B0 probe still binds only its original three yaw keys. Dense joint
binding and native evaluation are pending; no new host result is claimed.

Cubism's extended/ellipse interpolation is a separate future native path. The
current SDK does not expose its configuration, and `EditorArtMeshMorphState`
rejects source-level extended interpolation. Merely detecting that extension
does not establish ellipse configuration, Undo/Redo, persistence or export.

### Optional native joint-pose gate

Set `TURBOISM_MI3D_MODE=joint-pose` on the existing task wrapper to add
`JointPoseProbe` to the existing gates. It creates two source triangles with
shared positions in distinct ArtMeshes, on a fixed 1024px canvas, scale 200,
zero pivot and yaw/pitch domains [-30,30] degrees. The bounded linear plan
produces 23 keys per axis and 529 independent full-tuple keyforms per mesh.
`SurfaceKeyformBake` checks the complete grid, source/default geometry and
non-aliased form identities before writing; only non-default positions are
written inside one authoring transaction. Float-key collisions are rejected
before binding. UVs, topology, form identities and draw orders are protected.

The probe checks stored readback and one Undo/Redo, then observes 2,057 poses:
the Cartesian product of every key and adjacent midpoint (2,025 poses), plus
32 samples from the fixed Java Random seed 4012026. This includes all grid
keys, cell centers, edge midpoints and domain boundaries. Native consumption
must advance before calculated positions are checked against source-space
truth; waiting never depends on whether the geometry happens to match. The
maximum Euclidean vertex error remains 0.1px. Full stored state is checked
again after evaluation, and the original history position, object counts and
canvas are restored in finally, including on failure.

`verify-joint-pose.py` independently recomputes the source rotation, full grid,
sample schedule, protected state and fresh parameter consumption. Run the
final host verifier with `--expected-mode joint-pose` to reject a report from
another mode. `JointPoseProducerIntegrationTest` drives the actual producer
through a synthetic SDK backend with bilinear interpolation and sends its
JSON to that Python oracle; a wrong calculated-position backend must fail and
restore. This is producer/protocol evidence, never exact-host evidence.

The gate does not add textures, visibility/order compilation, ellipse settings,
full-import atomicity, save/reopen, atlas repacking or export acceptance. It
does not turn the synthetic triangle inputs into a real-model acceptance claim.

Cube SAVE_AS file helper stage (`mi3d-cube-save-file-v1`, 85 sources) adds a typed command and bounded artifact reader plus 37 synthetic checks. Runtime grant requires exact cube-persistence-save opt-in/request and pins persistence/mi3d-cube-model.cmo3. This stage does not yet expose a host cube persistence mode or establish save/reopen acceptance. Legacy 83-source whole-import builds remain independently verifiable.


Cube persistence producer stage (`mi3d-cube-persistence-v1`, 86 sources) adds SDK-only SAVE/reopen routines using the actual importer and typed SAVE_AS command. The reopen routine only observes the launcher seed; it does not recreate geometry. Captures retain every authored snapshot plus durable model metadata; the independent cube oracle checks all 12,150 forms against the frozen GLB. Runtime session identities are excluded only from cross-process state. Rendered geometry/pixels are explicitly NOT_EVALUATED and observed image bytes are UNAVAILABLE. The public queue wrapper and final job verifier intentionally do not yet admit these modes: exact artifact pair admission and rendering checks remain required before native submission/acceptance.


Persistence render producer (`mi3d-cube-persistence-render-v1`, 87 sources) captures nine source-bound poses before and after SAVE_AS and nine on fresh-process reopen. Each frame waits for a fresh consumed parameter pass, records evaluated geometry before/after native raster capture, protects authored state/history and restores the entry pose in finally. PNGs are bounded, CREATE_NEW artifacts under cube-persistence-render. The independent render oracle checks frozen GLB projection within 0.1px, consumption, exact frame identities/digests and the source-color silhouette against analytic visibility. CAPTURED is a producer observation, not final acceptance. Exact pair/archive/lifecycle admission and native execution remain pending; public wrapper modes remain closed.


Cube pair admission (`mi3d-cube-persistence-pair-v1`, 87 sources) opens explicit cube-persistence-save/reopen wrapper modes. Each leg uses the shared full lifecycle/containment/console gate, exact bounded PNG/CMO3 archive inventory, GLB geometry and raster oracles. `verify-persistence-pair.py --cube --build-run ... --job-a ... --job-b ...` admits both jobs against the same immutable build, then checks distinct run/job/attempt/process identities, saved-byte SHA equality with all reopen fixture digests, complete authored-state equality and decoded pixels for all nine poses. Single-leg success never establishes pair success. Legacy micro-fixture pair verification remains the default. Native acceptance is still pending until real jobs complete.

Packed-atlas persistence (`mi3d-atlas-persistence-v1`, 94 sources) adds
`cube-atlas-save` and `cube-atlas-reopen`. Save admits the existing pack/repack/
UndoRedo evidence plus 18 save-side PNGs and one CMO3 (54 PNGs total); reopen
admits nine PNGs and forbids a new saved artifact or another atlas build.
`verify-persistence-pair.py --atlas --build-run ... --job-a ... --job-b ...`
first admits both jobs with the shared lifecycle/archive gate. The reopen leg
requires the admitted save's raw source capture, verifies packed UVs and GUID
bindings against it, and then binds all initial/final seed hashes, distinct
job/attempt/run/process identities, authored layouts and decoded pixels.
A single save can be checked with `verify-host.py --expected-mode cube-atlas-save`;
reopen is intentionally available only through the pair CLI so an unauthenticated
reference file cannot stand in for admitted save evidence. Offline protocol tests
use explicitly stubbed raster calls; they are not native persistence acceptance.
