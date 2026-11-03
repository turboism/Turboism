# Reinit Pair-elision

`CModel.reinitModelInstance_exe()` rebuilds three GUID-indexed reuse maps on every model
reinitialization. The host is Kotlin, and each map is built with the inline
`associateBy` expansion, which materializes one transient `kotlin.Pair` per model object
and immediately discards it after `map.put(pair.getFirst(), pair.getSecond())`. A model
with a few thousand parts, drawables and parameters therefore allocates thousands of
short-lived Pair objects per reinitialization, all of which die in the young generation.

This optimization rewrites that reviewed method in place. For each recognized site the
allocation and both getters disappear:

```text
new Pair; dup; <guid>; <object>; Pair.<init>; astore p
...; map.put(p.getFirst(), p.getSecond())
```

becomes

```text
<guid>; <object>; swap; astore first; astore second
...; map.put(first, second)
```

Two fresh invocation-local slots hold the already-evaluated key and value, so the map
contents, insertion order, evaluation order and the exception path
(`new Pair(null, null)` becomes `null, null`) are unchanged. No bridge, callback, shared
state or new runtime class is involved, and the rewritten method allocates no Pair at all.

## Admission

- The class must match a reviewed `CModel` SHA-256 pin for its declared Cubism release
  (`class-pins/reinit-pair-elision.json`).
- The reviewed method shape must equal the attested reference.
- Every `kotlin.Pair` use in that method must be a recognized creation, getter or local
  copy; an escaping Pair, an unknown constructor or any other use fails closed.
- The rewrite must consume exactly the planned creation sites and leave no
  `kotlin.Pair` reference in the method; frames are recomputed with the host loader.
- Only `CModel.reinitModelInstance_exe()` is touched. Other methods in the class keep
  their Pair users untouched.
- The installer verifies byte-identical restoration on close.

## Switch

`turboism.optimization.reinitPairElision` / `launcher.reinitPairElision`, off unless the
user explicitly enables it on the Performance settings page. The managed launcher emits
`-Dturboism.optimization.reinitPairElision=false` when the persisted choice is off.

## Validation

- `ReinitPairElisionTransformerTest` compiles a fixture with the reviewed bytecode shape
  and asserts 5 allocations before and 0 after the rewrite, identical map contents,
  repeated-call stability, untouched unrelated Pair users and fail-closed escaping.
- The rewritten real 5.2.03 and 5.3.04 `CModel` classes verify under `-Xverify:all`.
