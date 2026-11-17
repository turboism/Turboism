# T029-TLINDEX: Edge-to-Triangle Index for `TriangleList.a(j)` (Offline Differential Slice)

Goal: remove the full-set scan of `TriangleList.a(j)`. Host JFR (5.3.03, heavy-nolayout, the two legs `5303-t041-digest-base1` and `base2` agree): about 2400–2500 samples on the `TriangleList.a` plus `iterator` chain (≈25 s, about 40% of the triangulation chain), of which the `a()` frames carrying an iterator are 1231 (pure `a(j)` scans; only 2 from the set-add path), and `HashMap$HashIterator.<init>` is the largest leaf (on JDK 17 the LinkedHashIterator extends HashMap$HashIterator, and after JIT inlining the whole iteration belongs to its constructor frame). `TriangleList.b` (remove) has only 218 samples.

## Facts Verified in Bytecode (javap, 5.3.03 jar sha bd0a23b9…)

- `TriangleList.b` is a private final `LinkedHashSet<l>`; the write entries are only `a(l)Z`=add, `b(l)Z`=remove, and `c()V`=clear; `c(l)Z`=contains is read-only; `iterator()` exposes the set iterator (no caller of iterator.remove() appears in h).
- `a(j)List`: new ArrayList → full iteration → `l.b(j)` filter → return in insertion order.
- `l.b(j)`: compares the three edges (a,b), (b,c), and (c,a) by the undirected endpoint pair of `TriPoint.getIndex()`; the endpoint indices of edge j may legitimately hold the sentinels -1, -2, and -3.
- `l.equals` is the 6 cyclic permutations of a three-vertex TriPoint equality; `TriPoint.equals` compares only (x,y) and **ignores index**, so two `l` values may be equal while their endpoint indices differ.
- `l.hashCode()` returns **the constant 0**, so the LinkedHashSet degrades into a single bucket chain and add, remove, and contains all cost an O(T) equals scan (the official cost already behaves this way).
- The three vertex fields of `l` are assigned only in `<init>` (the only putfield site), so an element key never changes after it enters the bucket.
- `a(j)` returns a brand-new ArrayList snapshot.
- `a(l)` and `b(l)` carry a `checkNotNullParameter` plus a `c$a.b()` gated debug-log prologue; `c()` and `a(j)` carry only a null check.
- Entry-point re-review (every getfield `b` site enumerated): `a()`=size, `b()`=iterator read-only, `iterator()`=iterator, `c(l)`=contains read-only, `d(l)` touches only a local ArrayList, and `toString`, `a(String)`, and `a(int)` are read-only, so the write entries stay exactly the three woven points `a(l)`, `b(l)`, and `c()`. If a caller calls `remove()` on the iterator exposed by `iterator()` or `b()`, the size pre-check of the Bridge catches it (a size mismatch marks dead and falls back).

## Candidate Design (Bridge.java, Pure JDK Types So the Host Can Weave It Unchanged)

- Key: `key(i,j) = (min<<32)|(max&0xffffffff)`, the undirected endpoint index pair.
- State: `IdentityHashMap<LinkedHashSet,St>` (**never** a hash or weak map: `LinkedHashSet.hashCode()` is an O(T) element sum and `equals()` is O(T²), and the first WeakHashMap version re-scanned O(T) on every get and made the bench 3× slower).
- `add(s,tri,ia,ib,ic)`: call the real `s.add(tri)` first; on success record `keys[tri]=(k1,k2,k3)` unconditionally; enter the bucket exactly only when `!dirty && sz==size-1` (otherwise mark dirty). The size pre-check catches a side path such as iterator.remove.
- `remove(s,tri)`: **never look up the index by the argument key**. Iterate the set for the first element with `tri.equals(e)` (exactly the choice HashMap.removeNode makes on a single bucket chain), remove it with `iterator.remove()`, and then detach **the element's own recorded key** from the bucket **by object identity**, so an equals-versus-keys divergence is safe by construction. O(T), the same order as the official remove.
- `clear(s)`: set.clear plus index clear.
- `tryQuery(s,ja,jb)`: dead → null; dirty or a size mismatch → rebuild; a rebuild that meets an element without a key (an element whose add was not woven) → dead, always fall back. Return a **new ArrayList** (the official snapshot semantics; a negative control proves that returning the internal bucket lets a caller pollute it).
- Every bookkeeping path wraps `try/catch Throwable` and only degrades; it never propagates into the host.

## Weave Shape (Awaiting Host A/B, Not Implemented)

`TliWeave` (ASM, following the shape-gate and INVALID convention of dweave and kweave):
- `a(l)`: insert `aload_1;l.a();getIndex; ×3` before the `invokevirtual LinkedHashSet.add` point, then replace that invoke with `invokestatic Bridge.add(LinkedHashSet,Object,III)Z` (the prologue and the log stay, and COMPUTE_FRAMES applies to this method only).
- `b(l)`: `invokevirtual LinkedHashSet.remove` → `invokestatic Bridge.remove(LinkedHashSet,Object)Z` (the same stack shape, a pure replacement).
- `c()`: `invokevirtual LinkedHashSet.clear` → `invokestatic Bridge.clear(LinkedHashSet)V` (the same stack shape).
- `a(j)`: dispatch tryQuery in front of the method body: `r!=null` → areturn, null → fall into the **original scan body** (an automatic fallback that is structurally correct).
- Shape gate: each invoke point above must occur exactly once and the method signature and access flags must not change; otherwise the whole leg is INVALID (following the AbTransformer convention).

## Integration Verification (the triangulation-weave-ab one-off probe, agent level; that probe source was deleted)

`TliWeave` and `Bridge` are compiled into `tri-weave-agent.jar`; `WeaveAbConfig` gained the `turboism.validation.tlWeave.*` namespace (mode `tl-dump-only` or `tl-dump+weave`, profile `tl-official` or `tl-shadow-selfcheck`), and `AbTransformer` dispatches `TliWeave.weaveChecked` on `t.tliWeave`; on the official 5.3.03 bytes `OfficialShapeProbe` reports `TLINDEX_PROBE PASS` (all four method gates hit and capture-after-weave compatible). `run.sh` passes 48 scenarios (including tlHappyDump, tlHappyWeave, tlMissingHelperWeave, tlShapeRejectWeave, tlWrongSha, and tlShapePins) with `hostExecuted=false officialClassLoaded=false`, so offline agent evidence is no host readiness.

## Differential Verification (This Directory, Fully Offline, Own Fixture)

`run.sh`: javac (ASM and kotlin-stdlib are compile and runtime dependencies only, see the resolution chain in the script) → `-Xverify:all` SelfCheck ×3 plus WeaveSelfCheck ×3 plus Negatives plus Bench.

- `SelfCheck`: ref (brute force) against idx (Bridge) share the same ShadowL instance stream; every `a(j)` compares size and the object identity and order of every slot, and `a(l)`, `b(l)`, `c(l)`, size, and iterator are all compared; the stream is a mesh plus Lawson edge-flip interleaving (4 seeds × 4 sizes) plus 8 fixed-point semantic groups (reversed edge, duplicate add, equals-versus-key divergent remove, degenerate duplicate vertex, iterator.remove side path with a continued woven add, a clear in the middle, negative sentinels, and a remove miss). Result: **72953 checks PASS**.
- `Negatives`: 7 single-defect variants are all rejected by the discriminating stream (directed-keys, no-dedup, no-dirty-remove, no-sidepath-check, unordered-bucket, coord-keys, and shared-bucket-list).
- `Bench` (side=30, about 1682 triangles, 15642 queries per leg): stride=1 (adversarial per-edge flip) idx≈590–778 ms against ref≈762–1196 ms; stride=12 (about the host query-to-remove ratio) idx≈236–283 ms against ref≈454–866 ms; stride=1000 (nearly pure query) idx≈12–25 ms against ref≈309–464 ms. Magnitude conclusion: the higher the query share, the larger the gain; the host window of 2500 scan samples against 218 remove samples (≈12:1) falls in the stride≈12 range, and even the worst interleaving is no worse than the baseline.

## Host Equivalence Gate (During A/B)

- The four sequence SHAs of the dump leg are byte-identical to the baseline (the equivalence gate, as with DWEAVE).
- Every standard gate passes; the `TriangleList.a` and iterator window samples in JFR must collapse to a fraction.
- Any INVALID, reject, or verdict exception stops the batch.

## 2026-10-01 Production Remove Correction (New Artifact Awaiting Host)

The old claim that "an equals scan in insertion order selects exactly what HashMap removal selects" is no general guarantee: a bucket with a constant hash becomes a tree, and if the equals relation of an existing object changes, the native tree lookup may select an object other than the first match in insertion order. Even when a common host input never reaches that degradation, the old helper first ran a linear equals scan and then entered the native removeNode through iterator.remove, which added cost. Two host pairs on 5203 proved no performance gain; the on and off CPU of the second pair were 308.4 s and 287.8 s.

The production correction runs `s.remove(tri)` first and exactly once, and the native set decides the return value and the object actually removed. The bookkeeping no longer calls element equals: only when the state is clean, the recorded key count equals the current set size plus one, and the record contains tri does it scan every live identity. If every live identity is recorded and tri is not among them, the cardinality proves tri is the single missing record, so its own key can be detached precisely; otherwise the state is marked dirty, the next query rebuilds from the real set and clears vanished records, and an unknown live object still falls back permanently. The removed object is never guessed from the argument equals or from the index. This proof depends on neither coordinates nor equality stability, and it also covers an equal re-add after an iterator removal.

The tests first reproduced the extra equals count and the differing victim under mutable equality, and then verified that the correction matches the native set in per-object identity and order; the existing equal-but-different-index, weak identity lifecycle, concurrency, and weave regressions stay. An offline pass does not mean the new candidate has host performance acceptance.

The second-round candidate 640e3b1e… passed the 5203 seq2063 and seq2069 standard gates, edge order, and production run, but CPU and duration still regressed, and JFR showed a new `IdentityHashMap.containsKey` leaf with 806 samples. Querying the identity table again for every live object ignored the already verified change protocol: every add and clear is woven, and the only unwoven write is iterator.remove, whose monotonic shrink the size pre-check (or the next add pre-check) detects. So "clean and the cardinality matches before the removal" already proves that every member identity of the original set is recorded, and the native remove deletes exactly one of them. It suffices to confirm with `==` whether the argument identity is still live: if it is live, the real victim is another equal object and the state becomes dirty for a rebuild; if it is not live, the argument's own key can be detached. The later candidate drops the per-live-object containsKey without changing the native removal or the fallback path. This relies on the reviewed change entries of the exact host and promises no support for an arbitrary same-size replacement hidden behind an unwoven add.

## 2026-10-01 Later Candidate: A Contains Fast Path That Hits Only on Object Identity (Implemented, Awaiting Host)

Three pairs on 29f13cf8 proved no stable gain on the host. In the 64-frame stack export of r3, the on leg had 669 `l.equals` leaf samples inside the `HashSet.contains` chain against 15 on the off leg. That is only sample attribution, not a call count or a causal effect. The 5203 javap again confirms that `TriangleList.c(l)` calls only `b.contains(l)` after the original null check; a triangle hashCode is always zero and equals is a permutation comparison of vertex coordinates.

The testable proposal returns true only for "a known still-live identical object": the state is neither dead nor dirty, the recorded size equals the real set size, and the identity table holds the argument. It relies on the existing all-add and all-clear weave plus the change protocol where iterator.remove only shrinks the set. Endpoint indices must never replace the geometric equals, and an identity miss must never return false directly; an unknown, stale, or equal-but-different-identity argument still runs the native contains. The original null check stays. This proposal bypasses no native add or remove semantics.

Before implementation, confirm the exact `c(l)` shape of the three versions, the fixed element type, and the constant-zero hash binding; add differential regressions for an identity hit, an equal but different identity, changed coordinates, a stale state after iterator.remove, and a dead state. The weave shape and the artifact hash must be updated and verified on the host separately. The current resource A/B keeps using the frozen old candidate without this proposal, and no performance promise is made for it.

Additional static verification: the official JARs of 5203 (bcc6e34f…), 5302 (988ef6a8…), and 5303 (bd0a23b9…) all confirm that `c(l)` calls LinkedHashSet.contains and that `l.hashCode` holds only iconst_0 and ireturn. The per-version JAR SHA and the raw javap excerpts are in `build/t029-real-host-acceptance/contains-shape-review.json`. This rules out a version shape difference at those two entries only and does not replace a candidate differential test or a host effect verification.

Implementation progress: the production helper gained a positive identity contains; the patcher adds a fifth admission check on the single native contains site of `c(l)` and rejects the whole class when it is missing. 19 helper, 12 transformer, and 5 installer tests pass; the official class shape test and devCheck pass. The packaged agent is `77ce425567f8a4fb1ef1c5055fe5015a33c1cf1dcefbc90dcfff9c0bbeebd17c`. Running the patcher from that JAR on the official classes of the three versions produced:

- 5203 patched SHA `33bea8bc8bf437df71915b17a704a2283946424458b54848cdb8cdea35010cb4`
- 5302 and 5303 patched SHA `f3427cec0e5c0c7d93c8a2cf72351a82a39b635b15682afc291eb3a62dc888f5`

The original class SHAs are unchanged. The native add and remove stay unchanged, and an unknown, dirty, or dead contains still uses the native geometric equality lookup. The new candidate has no host effect evidence yet; the complete wrapper binding regression passed.

## 2026-10-02 T047: Bounded Removal-Identity Batching (Offline Experiment Only)

The limited 5302 pairing of the final 021e still proved no net gain. In the saved first-operation JFR, remove had 428 inclusive samples, of which 420 leaf frames landed on the live-identity scan back edge of the frozen bridge class at BCI 123. A sample is neither a call count nor a causal duration; native `h.a(l,l,j)` statically confirms two consecutive removals followed by an in-order add of a replacement triangle.

The experiment keeps the native remove running first with its return value and real victim, and only stages at most 8 recorded, distinct removal arguments. Before reading the index or a positive membership, it scans the real set once and confirms by identity that every argument has vanished; combined with the recorded cardinality that proves they are the real victims. The common 1 or 2 arguments get a dedicated loop. It never assumes the argument is the victim, and the bucket may not publish before the pending verification.

During pending, a normal add may record a new object and keep the original insertion order; re-inserting a pending identity, an unknown or duplicate removal argument, a size drift, a full budget, or a still-live argument marks dirty. A query rebuilds from the real live set, an unknown live key falls back permanently, and membership takes the native fallback. A scan exception degrades permanently so partial bookkeeping is never trusted again. clear, rebuild, and invalid release the pending references, the registration stays weak-identity, and no set holds a back reference. The eight references are only an extra metadata bound, not a real memory acceptance.

The new behaviour regression drops the old 509 live accesses to 256 and keeps 26 differential, lifecycle, and real mutable-equals double-victim checks; the related focused 53 cases, the two static weave families, and devCheck pass. In the 5 pairs of the own 512-triangle flip experiment, the median thread CPU fell 21.43% and the thread-measured allocation fell 38.18%, covering only the post-warm-up change cycle and excluding the initial index and pending array construction. This is no host speedup, RSS improvement, or stability acceptance.

The frozen 021e agent does not include this, and no new host artifact was packaged or committed this round. See the [host and offline evidence](host-evidence-20261001.md) and `build/t047-offline/offline-review.json`. A production candidate still needs a frozen new agent plus the T044 and T046 host, resource, and repeated-target gates.
