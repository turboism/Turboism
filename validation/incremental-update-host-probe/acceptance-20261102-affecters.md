# Glue affecter admission (2026-11-02)

## Scope

`IncrementalUpdateBridge.unsafe(model)` used to keep every model with a non-empty
`CModel.getAllAffecters()` list on the complete native path. This change admits
glue-only affecter models behind an explicit experimental opt-in, exposed as the
Performance-page sub-toggle "Include glue affecters"
(`launcher.incrementalUpdateAffecters`) and as the equivalent JVM override:

```text
-Dturboism.optimization.incrementalUpdate=true
-Dturboism.optimization.incrementalUpdateAffecters=true
```

The default remains native for every affecter model. ArtPath presence and any
affecter that is not a `CGlue` still force a full epoch, and a host without the
glue class also stays native.

## Why glue is compatible with the reused state

Host bytecode (5.0.00–5.3.04) shows one affecter pass in the geometry update, after
the ArtMesh pass:

1. every affecter form is interpolated unconditionally (`interpolate__testImpl`);
2. `CArtMesh.setAffectedForm(null)` clears the previous glue output for the visible
   meshes passed to the pass;
3. every `CGlue` recomputes both targets from
   `getCalculatedForm().getPositions()` (which now falls back to `deformedForm`)
   and writes `prepareAffectedForm$cubism()` with
   `base + (other - base) * weight * intensity`.

The incremental path only reuses `deformedForm` and per-object interpolation. The
host's interpolation skip flag is checked per object together with
`keyformGrid.c()`, and affecter interpolation never consults that flag. Glue output
is therefore a pure function of the current `deformedForm`s plus the glue form
values, which the host recomputes each update. `getCalculatedForm()` is also what
the acceptance agent compares, so the glue result is inside the differential.

The probe digest now includes `ACDrawable.getAffectedForm()` next to
`getDeformedForm()`. That covers the form glue actually writes and makes an
alternating full/narrow mismatch observable.

## Offline evidence

- `:runtime:test --tests '*IncrementalUpdate*'`: 25 tests, 0 failures
  (`glueAffecterModelsReuseOnlyBehindExplicitOptIn`,
  `unknownAffecterTypeStaysNativeEvenWithOptIn` included).
- `:runtime:legacyCubismEvidenceTest --tests '*IncrementalUpdateArtifactTest*'`
  with all 20 reviewed JARs: 20/20 pass, including the new
  `ACDrawable.getAffectedForm` dependency.
- `:bootstrap:test --tests '*IncrementalUpdate*'` and `spotlessApply` pass.

## Real-host acceptance

Fixture: `D:/Turboism-models/official/hiyori_free/hiyori_free_t08.cmo3`,
SHA-256 `99cf572c8dcb144f657ee0669c5f05e0aed239066f3673b15889eafae67256e9`,
unmodified (affecters present, no ArtPaths). The task-only undo agent drags a Warp
handle, then Undo / Redo / final Undo with `repaintCanvas` barriers only, and
compares every source and `getCalculatedForm()` raw bit plus the GLJPanel pixels
between the native and incremental legs.

| Host | Profile | OFF reuse | ON reuse | Failures | Result |
| --- | --- | ---: | ---: | ---: | --- |
| 5.0.00 | p1 | 0 | 762 | 0 | PASS |
| 5.1.03 | p4 | 0 | 1008 | 0 | PASS |
| 5.2.03 | p6 | 0 | 1008 | 0 | PASS |
| 5.3.03 | p8 | 0 | 844 | 0 | PASS |
| 5.3.04 | p9 | 0 | 1008 | 0 | PASS |

Every leg reports `differential=all source and calculated coordinate raw bits match
in all phases`, equal OFF/ON pixel SHA-256 for before/dragged/undo/redo, no document
timestamp change, and zero bridge failures. A 5.3.04 run with
`-Dturboism.incremental-update.probe=true` also passes with no probe mismatch,
exercising the new `affectedForm` digest.

The 5.3.04 row was also re-run through the persisted settings path instead of JVM
options: `config.json` carried `launcher.incrementalUpdate=true` and
`launcher.incrementalUpdateAffecters=true`, the bootstrap read them through
`NativeOptimizationConfig`, and the run again reports PASS with ON reuse 1008 and
zero failures. That is the path the Performance-page toggle writes.

The same bundle also opened the real editor settings dialog: the 性能 (Performance)
tab shows "包含胶水影响器" immediately after "仅更新变化的模型部件"
(`state/settings-shot/settings-performance.png` in the run directory). The
contribution tests cover the binding, label key, tooltip and order; this capture
confirms the row renders in the shipped page.

Run directories: `build/host-validation-affecters/runs/incremental-undo-affecters-*`
in the task worktree (raw `result.txt`, per-phase coordinate dumps and PNGs).

## Limits

- One fixture and one interaction shape. Multiple/chained glues, glue weight edits
  and other models are not separately exercised; authoring/editing guards still
  force full epochs.
- ArtPath glue remains native. Only `CGlue` affecters are admitted.
- The stock undo agent expects the editor's trial notice to be dismissed. The
  machine used for this run had a stale UAC prompt owning the desktop, so the
  acceptance run used a temporary in-process dialog-dismissing variant of the
  agent; the source change was not kept, and the stock agent's behavior is
  unchanged.
