# Deterministic 3D-to-Cubism import contract

User requirement, 2026-10-16: the complete production import must operate locally
using deterministic algorithms, without an LLM, character-specific preprocessing,
asset-name rules, or manually generated fixtures as a prerequisite. “Formulaic”
means a reproducible algorithmic pipeline, not a promise that all source programs
can be replaced by a single closed-form equation.

## Current acceptance priority

The user subsequently deprioritized direct `.blend` import. Standard GLB/glTF
and the public textured primitive corpus in `PrimitiveAcceptance.md` are the
current scope. The optional Blender backend below is deferred, not an acceptance
prerequisite. Complete characters are not the primary correctness standard.

## Pipeline and source of truth

1. Decode the source once into a source graph: object identities, hierarchy,
   geometry/UVs/materials, transforms, skin weights, morph targets and their saved
   weights, visibility, and explicit control dependencies. Preserve provenance.
   Standard GLB must not require custom `turboismControls` extras. Such metadata
   remains an optional extension, not the baseline for morph import.
2. For `.blend`, use a versioned local Blender evaluation backend. Evaluate its
   saved scene/view layer, drivers, constraints, modifiers and materials through
   Blender's dependency graph. A common extractor must traverse datablocks and
   RNA; never encode this character's names, mesh indices or vertex groups.
   Capture evaluated geometry and material baking from the same source state.
   Standard GLB cannot reconstruct modifiers or controls omitted by its exporter.
3. Infer structural groups from source object boundaries, hierarchy and actual
   skin-weight influence. Use deterministic tie rules and stable source IDs.
   Without sufficient structure, retain object groups. Do not label an inferred
   component “left leg”, “eye” or “clothing” solely from a name or geometry guess.
4. Enumerate supported source controls, saved defaults and valid endpoints.
   Preserve morph target names for display; use stable indices when absent.
   Rig properties become controls only when they have an evaluable supported
   influence. Explicit user mappings may supply optional semantic labels; no LLM
   is required. Do not invent an eye/foot/outfit controller when none exists.
5. Evaluate neutral and control samples once, caching by source identity, complete
   source state, evaluator version and numerical options. An absolute source morph
   uses `v(w) = v_basis + sum(w_i * delta_i)`; relative samples use
   `v(w) - v(w_saved)`. Apply the source's actual transform/skinning and evaluation
   order. Standard skinning follows weighted joint/inverse-bind matrices with the
   source coordinate-space conventions. Blender drivers and nonlinear modifiers
   must be evaluated, not replaced with an assumed linear skinning formula.
6. Compile native hierarchy and parameters. Group meshes under structural Warp
   deformers; use opacity parents for actual source visibility controls. Fit source
   displacements to supported native blend shapes using deterministic numerical
   fitting and measured residuals. Filename-scoped IDs and one import parameter
   group prevent collisions. Missing optional face/head/outfit mappings must not
   prevent ordinary geometry/morph import.
7. Project using fixed-pivot rotation and camera matrices. Build visibility
   constraints only for triangles with screen-space overlap, respecting source
   culling, textures and alpha. Compile representable constraints to native integer
   drawOrder, with native interpolation/quantization and equal-order semantics
   verified separately. Center-depth inversions are not overlap evidence.
8. Preflight all authoring, control, cache, image and memory budgets. Refine only
   partitions with measured fit/visibility conflicts, reusing unchanged fits.
   An unsatisfied constraint, unresolved cyclic visibility, unsupported source
   dependency or exceeded budget yields an explicit diagnostic. Do not silently
   drop triangles, controls, back faces or default-covered body surfaces.
9. Write through protected native transactions, batching independent work and
   placing required evaluation barriers at dependency boundaries. Construct the
   interpolation cache after geometry/control authoring. Verify topology, UVs,
   defaults, hierarchy, native values, Undo/Redo and saved/reopened results.

## Coupled appearance and nonlinear controls

Clothing opacity alone does not imply a body mask or a foot pose. Propagate a
relationship only when it exists in the source dependency graph or explicit input
metadata/mapping. Source control evaluation must provide the changed body and
clothing geometry together. If the source stores no coupling, preserve separate
controls rather than manufacture a relationship.

Independent blend displacements need not reproduce a nonlinear source evaluated
at combined controls or at rotated poses. Measure combinations selected from the
actual overlap of influenced vertices and dependencies. Add supported corrective
samples within budgets, or report the unsupported combination/range. Never infer
full 3D equivalence from the presence of parameters alone.

A 2D painter renderer cannot generally reproduce arbitrary intersecting 3D
surfaces with a finite mesh-wide total order. This is a target representation
limit. Culling detects back faces, not occlusion; opacity hides an entire group,
not just its covered pixels. A no-LLM pipeline can diagnose this limit reliably;
it must not promise exact arbitrary 3D visibility in native Cubism.

## Acceptance and current gaps

- Repeated preparation of the same source/state/options yields identical geometry,
  partitions, control samples and ordering. Fresh native IDs may differ by design;
  compare normalized source mappings rather than UUIDs.
- Public fixtures cover unnamed morphs, saved nonzero weights, multiple objects,
  rigs without semantic names, no rig, hidden helpers, source-coupled appearance,
  and nonlinear control interactions. No private character is a test prerequisite.
- Translate/refit invariants, source topology/material/default preservation,
  native integer ordering, combination residuals and all budgets are explicit
  gates. Sampled visibility acceptance is limited to the tested pose/control domain.
- Performance records decode/evaluation, planning/fitting, authoring, controls,
  cache and final verification separately. Count source evaluations and native
  rebuilds to prove eliminated duplication; do not reduce fidelity to claim speed.
- Current GLB production code already loads standard morph targets/defaults and
  source skin structure without an LLM. The earlier repaired/control-rich character
  GLBs are validation inputs, not proof of direct generic `.blend` support.
- A generic Blender evaluation/material extraction backend is not yet delivered.
  Native ordering/tie simulation, full character visual acceptance and acceptable
  full-character latency also remain open. This document adds requirements; it
  does not claim these missing capabilities have been implemented.
