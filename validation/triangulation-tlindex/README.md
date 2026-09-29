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
weave shape, integrated A/B status, and the (not yet run) host plan.
