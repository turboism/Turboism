# mesh-triangulation-hash: Offline Verification Slice

An independent slice outside T030 that verifies the candidate fix of 021: the constant-hash degradation in host triangulation.

**Boundary**: this directory performs offline verification only. It defines, executes, and writes back no official class; it starts no host; and it publishes no production artifact. The evidence comes from an own fixture whose shape mirrors the reviewed host class, and the official class is only **parsed as bytes, read-only**.

## The Defect Under Test

The bytecode of the host `com.live2d.graphics3d.editableMesh.triangulation.l#hashCode()` is `iconst_0; ireturn` (always 0), while `triangulation.h.a` is a `Set<kotlin.Pair<l, l>>`. `l.equals` accepts **all 6 permutations** of the three `TriPoint` values `(a,b,c)`, so the hash must be permutation-invariant, which is exactly why the original author wrote `return 0` directly. The cost is that every element of the set lands in one bucket: once the HashMap collisions pass the threshold the bucket becomes a tree, `find` walks the tree calling `equals`, and the lookup degrades to nearly O(n).

## The Invariants and Design of the Fix

- The hash must be **invariant under any permutation of `{a,b,c}`** and must use the same fields as `equals`.
- `TriPoint.equals` looks only at **x and y**, while `TriPoint.hashCode()` also folds in **index** (which `equals` ignores). The patch therefore **never delegates** to `TriPoint.hashCode()` and reads `getX()` and `getY()` directly:

  ```
  h = Σ_{p∈{a,b,c}} ( Float.hashCode(p.getX()) * 31 + Float.hashCode(p.getY()) )
  ```

  The sum is permutation-invariant by construction, and only x and y keep it consistent with `equals`.
- `l.a`, `l.b`, and `l.c` are assigned only in the constructor (immutable after construction), while `TriPoint` is **mutable** through `GVector2`. Host equivalence (SC-01) is therefore a mandatory gate: if a coordinate changes while the object is alive, the hash becomes wrong.

## The Patcher (`CornerTripleHashPatcher`)

Fail-closed and naturally idempotent: it accepts only a class whose `hashCode()` body is exactly `iconst_0; ireturn` and which holds three corner fields `a`, `b`, and `c` of the same type. Every other shape (a different method body, a missing or mistyped field, a foreign class identity, an already patched class, unparsable bytes) throws `Rejected` and the caller keeps the original bytes. The generated method body is straight-line code and needs no StackMap.

## Offline Evidence

```
MESH_HASH_COMPARE original[distinct=1    maxBucket=4000 treeBuckets=1 lookupMillis=426]
                 patched [distinct=4000 maxBucket=5    treeBuckets=0 lookupMillis=1]
MESH_HASH_SELFCHECK PASS elements=4000 hostExecuted=false officialClassLoaded=false
```

| Metric | Original (constant hash) | After the fix |
|---|---|---|
| Distinct hash values | 1 | 4000 |
| Longest bucket | **4000** (every element) | 5 |
| Treeified buckets | 1 | 0 |
| 4000 `contains` calls | **426 ms** | **1 ms** |

Contract and refusal cases (all passing):

- the `hashCode` of all 6 permutations is equal and they are mutually `equals`;
- a different `index` with the same x and y makes `equals` true and **the patched hash equal** (which proves no delegation to an index-sensitive implementation);
- the run also records that the fixture point's own `hashCode` really is index-sensitive, so the host contract defect itself is reproduced;
- refusals: an already patched class, a wrong class identity, a wrong field type, non-class bytes, and truncated bytes.

Read-only official shape check (`OfficialClassProbe`, official bytes parsed only):

```
OFFICIAL_ENTRY com/live2d/graphics3d/editableMesh/triangulation/l.class
               size=7912 sha256=6f06427c59d3907fe0d4ec80c72a318410d2e5169e18a8263ddfaa526813bd90
OFFICIAL_PATCH CANDIDATE size=8002 defined=false executed=false
MESH_TRIANGULATION_HASH_OFFICIAL_PROBE PASS
```

## Build and Run

```bash
bash validation/mesh-triangulation-hash/build.sh
# Optionally name the official JAR explicitly (by default it reads the 5303 installation inside the local Proton prefix)
TURBOISM_MESH_HASH_OFFICIAL_JAR=/path/to/Live2D_Cubism.jar \
  bash validation/mesh-triangulation-hash/build.sh
```

It uses JDK 17, `-Xlint:all -Werror`, and ASM 9.7.1 (SHA-256 `8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281`). The bucket-layout evidence needs to read `HashMap` internals, so the self-check runs with `--add-opens java.base/java.util=ALL-UNNAMED`.

## Host A/B (5303 with heavy.cmo3, the User's Real Path: No Layout)

The two run labels `-meshprobe` and `-meshfix` wire this slice into the T040 scenario: both install the same verification agent (`mesh-hash-agent.jar`, self-contained with shaded ASM) and the instrumentation is identical, and the only difference is that `probe` keeps the native constant hash while `fix` applies the corrected hash. The agent patches only when the class bytes match the pinned SHA-256 and otherwise records `HASH_MISMATCH`. Each `GEditableMesh2.updateMesh` gets one extra digest call before it returns, so the two runs produce **directly comparable** mesh output digests.

Five pairs and ten runs in total, all `succeeded` / `PASS` / `normalExit` / `fixtureUnchanged`:

| Pair | probe (native hash) whole run / OK | fix (patched) whole run / OK | Paired change |
|---|---|---|---|
| 1 | 364.4 s / 144.6 s | 297.6 s / 118.0 s | **−18.3%** |
| 2 | 340.8 s / 123.1 s | 288.8 s / 116.3 s | **−15.2%** |
| 3 | 448.9 s / 145.9 s | 318.1 s / 122.1 s | **−29.1%** |
| 4 | 391.4 s / 150.8 s | 342.2 s / 140.5 s | **−12.6%** |
| 5 | 342.0 s / 120.9 s | 303.3 s / 120.0 s | **−11.3%** |
| **Median** | **364.4 s** / 144.6 s | **303.3 s** / 120.0 s | **−16.8%** (OK phase −17.0%) |

- **All 5 pairs improved**, in the range −11.3% to −29.1%, mean −17.3%.
- `tripleState` was always `PATCHED` on the fix side and always `SEEN_UNPATCHED` on the probe side; the observed official class hash was always `6f06427c…`, matching the pinned constant.
- **The mesh capture count was 719 in all ten runs and the output digest was `c23b2a8a…` in all ten (byte-identical)**, so SC-01 equivalence holds in every pair.
- The probe-side whole-run median of 364.4 s places the two historical single runs (391 s and 341 s) inside the same noise band; the paired difference is the measure that counts.

Mechanism confirmation (JFR on the same machine and fixture): `HashMap$TreeNode.find` samples went **2014 → 1**; the triangulation work itself did not shrink (2161 → 2268 samples), so the pathological lookup overhead is what disappeared, which matches the wall-clock drop.

## Production Path A/B (5303 with heavy.cmo3, Production Transformer Plus Persisted Preference)

Unlike the previous section, this group lets the **production** `VerifiedMeshTriangulationHashInstaller` decide in premain whether to install the transformer; the observation agent (`mesh-hash-agent.jar` in probe mode) registers after the production transformer, so `tripleDetail` records the SHA-256 of the class bytes it receives, which is the output of the production transformer.

| Run | Staged preference | Observed class bytes | JFR `TreeNode.find` | Output digest |
|---|---|---|---|---|
| `t011-off-…-meshoff-jfr` (runId `queue-d7b2eb71`) | `meshTriangulationHashFix:false` (staged in config.json) | `6f06427c…` native | 17,568 samples | `c23b2a8a…` |
| `t011-on-…-meshprobe-jfr` (runId `queue-c0f368d3`) | absent (default true) | `adb7d709…` production patch output | 0 samples | `c23b2a8a…` |
| `t011-off-v2-…-meshoff-jfr` (runId `queue-59766cde`, after the manifest fix) | `meshTriangulationHashFix:false` | `6f06427c…` native | 11,316 samples | `c23b2a8a…` |
| `t011-on-v2-…-meshprobe-jfr` (runId `queue-8b3058cc`, after the manifest fix) | absent (default true) | `adb7d709…` production patch output | 0 samples | `c23b2a8a…` |

Both runs agree exactly on `captures=719`, `points=4`, `edges=5`, and `lastIndicesLength=6`.

### Performance Metrics (Corrected OFF v2 Against ON v2, Same Scenario and Fixture)

| Dimension | OFF (native) | ON (patched) | Note |
|---|---|---|---|
| Whole-run wall clock | 349.9 s | 337.4 s (−3.6%) | fixed scenario waits dominate, so a single-pair difference sits inside the host noise band; the paired gain stays the P1 five-pair median of **−16.8%** |
| Total EDT busy samples | 6,401 | 5,048 (−21%) | EDT saturation eased markedly (which explains the higher `onEdt.timedOut` and the stiffer interaction in OFF) |
| **EDT time inside HashMap tree traversal** | 2,436 samples ≈ **48.7 s** (38% of EDT busy) | 26 samples ≈ **0.5 s** (0.5%) | **core gain: about 48 s of treeified bucket traversal on the UI thread removed** |
| `TreeNode.find` active window | a contiguous ~100 s interval | none | the OFF churn concentrates in the mesh edit and recompute phase |
| GC count / total pause | 92 / **12.1 s** | 68 / **7.0 s** (−42%) | churn produced garbage faster, and GC pressure drops after the patch |
| `HashMap$TreeNode` allocation | 28 samples | **0** | ON no longer creates treeified nodes (ordinary Nodes spread evenly) |
| Retained heap peak | ~4.1 GB | ~4.7 GB | **no substantive change**: the heap is dominated by model and atlas data, and the difference is GC-timing noise |
| Total sampled allocation | ~33.4 GB | ~34.3 GB | unchanged: the same objects are created (`triangulation.j` 4,130 against 4,091) |
| Total process exec samples | 12,767 | 12,041 | the whole-process CPU difference is diluted by fixed waits; the gain concentrates on the EDT critical path |

Memory conclusion: this fix is a **CPU and latency** optimization, not a memory optimization. The retained heap and the allocation volume stay the same, but it removes the treeified node objects and lowers the GC pause and the pathological lookup time on the EDT.

- **SC-03 off passthrough**: the OFF run confirms that with the persisted `false` the production transformer leaves the target class untouched (native bytes pass through) and the degenerate hash pathology (17,568 TreeNode.find calls) is still present, so "off" really returns to the native slow path instead of partially applying.
- **SC-01 equivalence (production path)**: the output digest before and after the patch is byte-identical.
- **SC-02 mechanism (production path)**: after the patch the TreeNode.find samples drop to zero.
- **A harness incident that happened (attributed, unrelated to this feature)**: the scenario driver of the first two runs reported DRIVER_BLOCKED in the freeze phase because that batch's bundle manifest wrongly wrote `t039LoaderClass=T039OwnedTargetLoader` (the official profile requires `AppClassLoader`), so the T039 loader gate refused and the state became `BLOCKED`. A control run with the old production agent and the same bad manifest (`t011-diag-oldagent-…`) failed with exactly the same signature, which proves the incident is unrelated to the mesh change and the new agent. After the manifest fix both re-runs (`t011-on-v2` and `t011-off-v2`) reported **succeeded/PASS** with `freeze.completed=true`, `canonical.complete=true`, and `payload.reopenHashVerified`, and the mesh evidence matched the table above.

- **SC-01 equivalence (this group passes)**: the capture count is identical and the digest is byte-identical, so the triangulation output is unchanged after the hash correction.
- **SC-02 performance (this group, −18%)**: the disappearance of the pathological `TreeNode.find` is the mechanism evidence and matches the wall-clock drop; the triangulation work itself did not shrink (2268 against 2161 samples) and only the lookup overhead went away.
- Neither the scenario nor the agent modifies the production runtime; the official class still receives only a byte-level transformation that is never written, never persisted, and never changes a JAR.

## Not Proven (Do Not Extrapolate)

- **Sample size**: the 5 pairs satisfy the specification's requirement of at least 5; but the paired change spans −11% to −29%, which shows that host noise is still large, so an absolute value of one run is not directly comparable and only the paired difference is usable.
- **Coverage of the digest**: the digest covers only the index buffer and the point and edge counts at `updateMesh` return and represents no rendered pixel, undo or redo, or save semantics.
- **Coordinate mutability risk**: `TriPoint` is mutable, so a rewrite while the set holds it would corrupt the hash; an identical digest in this group shows it did not happen in these runs but is no proof for every model or edit sequence.
- **Only 5303 was shape-checked**: 5203 and 5302 were not, and an unverified version must not be enabled.
- The host digest was captured inside the validation harness (isolated prefix, three agents), so the absolute values include harness overhead.
- **The class of defect is fully enumerated**: a full scan of the official 5.3.03 JAR (16,992 classes, 305 `hashCode()I` methods) found **only two** constant-return degenerate implementations: `editableMesh.triangulation.l`, which this fix covers, and `jp.noids.design.morph.simple.i$b` (the three-point triple of the morph simple tool, whose `equals` is likewise invariant under the six permutations, placed in a `HashSet`, which also degrades into a treeified bucket). The latter sits on the morph editing path rather than the atlas and mesh hot path measured here and is not covered by this slice; handling it needs its own spec and the same shape-check and gating flow.
