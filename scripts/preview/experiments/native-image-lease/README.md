# Native-image lease cleanup: detached control-flow experiment

This is a standalone **fake-pool prototype**, not a Cubism transformer or production
optimization. It imports no Cubism classes and allocates no image buffers. It is
not part of the normal Gradle build or the host-validation queue.

## Frozen scope

- Goal: test N02-a invocation-local cleanup and preserve counterexamples to its scope.
- Non-goals: native equivalence, N02-b pool-internal rollback, real OOME injection,
  thread safety, hook installation, and memory/CPU/GPU performance claims.
- Boundary / lane: JDK-only detached control-flow experiment (Lane A). Future native
  integration remains Lane C and requires its own reviewed design and exact-host evidence.
- Invariants: release only objects successfully returned to this invocation;
  attempt each return once; do not clear pool-wide state or touch foreign/nested leases;
  preserve the original thrown object when body/acquisition and cleanup both fail.
- Acceptance: reproduce the old acquisition-before-finally failure topology, verify
  caller-local cleanup, and assert that unreturned registrations and failed removals
  remain visibly unresolved rather than hiding them.
- Final batch: compile and run the source below once after checking shared quiet-host
  measurements are finished; retain output and source hash in the experiment ledger.

## Reproduce (JDK 17+)

From the performance worktree, with no quiet-host measurement running:

```sh
out=$(mktemp -d)
javac -d "$out" scripts/preview/experiments/native-image-lease/NativeImageLeaseCleanupPrototype.java
java -ea -Xmx64m -cp "$out" NativeImageLeaseCleanupPrototype
```

The output directory is a caller-owned temporary compilation directory, not a host
prefix. No official JAR/BAT, model, queue state, or service is accessed.

## Recorded result and interpretation

JDK 17.0.20: **PASS, 500 assertion checks** (not 500 independent test cases).
Source SHA256: `1a18c6222cf943f0a630a68b641986b67f1b3f098f685265f90d76622655cc64`.

- Failure before get 1/2/3/4: control leaves 0/1/2/3 previously returned objects;
  candidate leaves zero. Both RuntimeException and a synthetic Error are tested.
- Body failure and one cleanup failure: later resources still receive a release
  attempt and the identical primary exception propagates.
- No body failure: propagate the first cleanup failure after attempting all returns.
- Normal, foreign-owner and nested-call cases pass without double-return attempts.
- Failure after pool registration but before return: candidate still leaves one
  object. This intentionally demonstrates **N02-b is not fixed**.
- Cleanup failure before removal: candidate still leaves that object; a release
  attempt is not proof of successful return. No unsafe retry is attempted.

The candidate uses four locals, not an allocating per-call lease collection. The
fake pool and assertions do allocate; this is **not an allocation benchmark**.
Secondary cleanup exceptions are not attached with `addSuppressed`, to avoid that
allocation in an Error path; production secondary diagnostics still need design.
Concurrency, VM-fatal failures, original classfile transformation, image equality,
Undo/Redo, save/reopen, and all three performance metrics remain **NOT_TESTED** or
**NOT_MEASURED**. This result does not authorize a host run or establish readiness.

See `../../NATIVE-PERFORMANCE-EXPERIMENTS.md`, N02-a/N02-b, for the exact-native
bytecode evidence and implementation/validation blockers. No production installer
or policy defaults have been changed by this experiment.
