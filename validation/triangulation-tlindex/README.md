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
