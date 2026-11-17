# edge-index — T029-EDGE Offline Candidate Validation (test-only)

A minimal self-contained prototype that replaces the per-edge linear first-hit scan of `addEdgeIfNotExists` inside the `b.a` apply-edge loop with a one-shot first-hit edge index built per batch (`min<<32|max` to the first index). **This is simulation-layer evidence, not official-equivalence**: the official classes were only read as bytecode with `javap` and were never loaded or executed.

## Bytecode Contract Source (official 5303 JAR `bd0a23b9…`, `javap -c -p`)

`GEditableMesh2.addEdgeIfNotExists(i1,i2,type,z)` calls `addEdge(i1,i2,type,false,z)`, so the apply path always has `checkSimilar=false`. Confirmed semantics:

| Behaviour | Evidence |
|---|---|
| null type raises `NullPointerException` ("Parameter specified as non-null is null: …") | `Intrinsics.checkNotNullParameter` calls `throwParameterIsNullNPE` (kotlin-stdlib 1.7.21) |
| `i1==i2` logs `illegal indexed edge : <i1> - <i2>` (`util/log/a.b`) and returns -1, **without** bumping the version | addEdge offset 8 to 33 areturn, before setEdgeUpdated(71) |
| Endpoints normalize to (min,max) and the input direction is dropped | addEdge 75-97 |
| A non-degenerate call unconditionally runs `_edge_edit_version++` | setEdgeUpdated only runs `int++`, before the duplicate lookup |
| `checkExitingTypedEdge` calls `chechExistingEdge_exe(lo,hi,false)`: a **linear scan over 0..size-1 that stops at the first hit with equal endpoints, ignoring type** | G.javap 3089/1868/1825 |
| On a hit, only when `existing.priority < new.priority` does it retype in place with `set(i, new MEdge)` at the **same index** | priority LOCKED=40>NORMAL=30>USER_TRIANGULATION=20>AUTO_TRIANGULATION=10>NOT_INITIALIZED=-10 |
| On a miss it appends at the tail and returns the size before the append | addEdge 212-246 |
| `clearAutoTriangulation()` is `_edges.removeIf(type==AUTO_TRIANGULATION)` and keeps order | predicate `editableMesh/c` is an identity filter |
| The apply loop calls addEdgeIfNotExists(v0v1), (v1v2), and (v2v0) per triangle and discards the return value; all six `j/a.d()` calls sit outside the loop | b.a offsets 243-302 (progress 40/132/156/173/190/312) |

Not modelled because they are unreachable from apply: the `checkSimilar=true` branch (`checkCross_exe` and `checkSimilarEdge_exe` need geometry data), other `addEdge` flag combinations, and the triangulation computation stage of `b.a` itself.

## Equivalence Definition (Acceptance Criterion)

Compare per **operation**, not at the terminal state: inside an apply batch, lock step on every edge. After each addEdgeIfNotExists call, compare the return value or the exception class with its real message, the complete ordered edge list with endpoints and type bit for bit, the version counter, and the event sequence (degenerate log, retype, progress markers). Coverage: initial duplicate edges, reversed endpoints, degenerate edges, type replacement and suppression, extreme indices, null type, malformed triangle rows (null array, null row, length 0, 1, 2, and overlong rows, following the bytecode read order, so a length-2 row completes (v0,v1) and then hits AIOOBE at the (v1,v2) read), deterministic cancellation injection (ArmCancel arms `progress()` to throw ModeledCancel after a set number of `d()` calls, which models the propagation boundary of `jp.noids.framework.e.a`; the cancellation points 190 and 312 are proven by bytecode, and the simulation layer makes no equivalence claim about the production exception type), and fixed-seed random streams (seeds 1/7/42/199/2026 with clear, apply, armCancel, and progress interleaved). The first divergence prints the minimal counterexample and fails; the counterexample is kept, the candidate stops, and the standard is never lowered. After a cancellation the script keeps running and verifies that later use behaves the same on both sides.

Index lifecycle: `beginBatch` builds the index after clear; `endBatch` runs in `finally`, because the index is candidate-internal scratch whose release must not depend on normal termination and must produce no host-visible event; a direct call outside a batch builds the index lazily, which is not a linear fallback because the semantics are always the index.

## Captured Counterexample (Record Kept, Minimal Regression Case)

The first random-seed-1 run really diverged at step 1490: after `Clear` (`removeIf` compacts the list) the batch index was stale, so the indexed lookup hit an out-of-range index while native returned normally, which proves that comparing only the terminal state cannot find this defect. The fix made `clearAutoTriangulation` invalidate the batch index; the real apply order is always clear, build, then adds, so the fix changes no behaviour inside apply and only covers the defensive path of a direct call outside a batch. The minimal deterministic regression case is now frozen as `clear-invalidates-index-regression` (Add, Clear, Add hits the surviving edge after the shift).

## Run

```bash
bash validation/mesh-triangulation-hash/edge-index/run.sh
```

It compiles into `build/edge-index/compile.XXXXXX` and runs `EdgeIndexSelfCheck` and `EdgeIndexBenchmark`, which include the index build and allocation cost. The report prints `referenceEndpointComparisons`, `indexBuildEntries`, and `indexLookups` as three counts of different dimensions; it never divides them and sets no speed threshold, and every sample output interleaves A and B. The comparison numbers are synthetic cost only; they extrapolate no host benefit or upper bound and cannot decompose the original 50 s apply observation, because the real `a.a(progress)` triangulation computation may still dominate. This work changes no existing meshhash patch, agent, or build, touches no production, SDK, Runner, or timing tooling, and starts no host.
