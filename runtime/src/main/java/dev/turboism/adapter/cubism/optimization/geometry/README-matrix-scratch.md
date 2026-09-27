# Invocation-local matrix scratch candidate

This is a default-OFF candidate, separate from the default-on uniform-location
cache and the opt-in incremental-model-update experiment. It has not yet passed
whole-Editor performance acceptance. Do not reuse the uniform cache's speedup.

## Request and admission

```text
-Dturboism.optimization.matrixScratch=true
```

The bootstrap installs it only after full runtime admission, a reviewed exact
Editor digest, JVM 17+ and hook-policy approval. The known profiles are Cubism
5.2.03, 5.3.02 and 5.3.03; their mathematical dependencies are verified against
each version's own original archive. Unknown binaries and safe mode remain native.
The policy identifier is `cubism.render.matrix-scratch`.

`VerifiedMatrixScratchInstaller` checks the actual loaded parent traversal,
entity/parent accessors, matrix methods and allocating product helper. It rechecks
them immediately before installation. An installer-owned AtomicBoolean in
`turboism.matrix-scratch.admission` is false during installation and is armed only
after the target was rewritten successfully. An absent, wrong-typed or false gate
keeps the original path even with the request enabled. Do not set the admission
slot manually.

A non-mutating dependency observer retires the gate on an observed incompatible
retransformation. Later matching observations do not revive it. Arbitrary
unmanaged agents rewriting code after our observer are not a supported admission
mechanism. Closing disarms first, removes only owned slots/transformers and
verifies restoration of the target's original full class digest. Failed removal
or restoration is reported as a failure, not successful cleanup.

## Computation and ownership

Only `GTransform.getLocalToWorldMatrix()`'s allocating parent-product call is
conditional. Each invocation still reads each real parent and local transform in
the native order. No transform, topology or world-matrix result is cached across
calls or frames. At most two lazily-created product destinations alternate inside
one invocation. The root-only return alias is unchanged; product results from
separate invocations are not shared. Input matrices are never the destination.
The original four-term native general multiplication remains in the same order.

The implementation is not a skip-render optimization. Selection, geometry writes,
Undo/Redo, draw calls, GL uploads, error queries and uniform writes are not removed.
Do not enable by default until three interaction workloads, pixel comparisons and
resource-accounted OFF/ON results support doing so.

## Verification

With `TURBOISM_UNIFORM_HOST_JAR` set explicitly to a reviewed local application JAR:

```bash
./gradlew --no-watch-fs \
  :runtime:test --tests 'dev.turboism.adapter.cubism.optimization.geometry.MatrixScratch*' \
  :bootstrap:test --tests 'dev.turboism.bootstrap.VerifiedMatrixScratchInstallerTest' \
  --rerun-tasks
./gradlew --no-watch-fs devCheck
```

The artifact tests instantiate only pure native math classes, compare 2,048 sets
of inputs bit-for-bit and load transformed reference bytecode. Installer tests use
a JVM-instrumentation protocol double, not a running Editor. They cover safe mode,
unknown identities, explicit opt-in, occupied slots, dependency changes, install
failure, later retirement, removal failure, restoration and startup/shutdown order.
An absent explicit archive is a visible skip, not a host pass.

On the examined three local reference archives, each forced offline run completed
15 tests with zero failures/errors/skips. The first 5.2 path supplied was absent;
that failed attempt was corrected using the existing Runner's version directories,
not by changing reviewed digests or ignoring test failures.

## Real-host comparison still required

Use only the existing serialized exact-host queue and immutable task fixture
copies. Keep uniform-location caching ON and Slice B/value suppression OFF in all
legs; compare matrix OFF/ON/ON/OFF. Validate the actual installed gate and transformed
execution, moved/restored nonblank pixels, all untouched meshes, native Undo/Redo
and source hashes. Report queue/handler/repaint/press/release timing separately,
completed-display throughput (not presentation FPS), CPU time, sampled working set,
heap and exact thread allocation. Keep diagnostic profiling outside acceptance
windows. Offline checks alone do not establish a matrix host speedup.

## First exact-host evidence (2026-09-17)

The 5.3.03 heavy-model wheel calibration and the full 800-event OFF/ON/ON/OFF
comparison completed through the shared queue. The full wheel job is
`64d36233-e5c5-4e3e-bbcb-e6922d150cef`, run
`queue-7b1174295a694c12acb1feb75baf0c01`. Its native canvas pixel comparisons at
two different zoom states, exact host/source identity, normal exit and supervised
cleanup passed. The source model stayed unchanged. Uniform caching remained ON
in all controls; Slice B and value suppression stayed OFF.

| Full wheel metric | Matrix OFF | Matrix ON |
|---|---:|---:|
| Events | 400 | 400 |
| Mean event-to-repaint-barrier ms | 71.9859 | 67.2150 |
| p95 / p99 ms | 87.4944 / 141.2937 | 75.7518 / 90.0982 |
| Completed displays per second | 13.8402 | 14.8248 |
| EDT allocated MiB per frame | 10.6740 | 8.1985 |
| Process CPU ms per frame | 88.425 | 80.800 |
| Sampled working-set mean GiB | 3.9698 | 4.1251 |

Allocation decreased 23.19% in this run. The observed pooled latency ratio is
1.071x, but OFF leg means were 76.9192 and 67.0527 ms, while ON leg means were
67.9765 and 66.4536 ms. The late OFF leg overlaps ON performance: do not promote
this to a demonstrated stable 7% additional speedup. Working set did not decrease.
Keep the candidate default OFF pending replication and further acceptance.

The full 800-event native-pan job
`9693002d-4ba7-4a1a-98ae-8494f8f0a4e3` / `queue-425f818451bd4e739d14e8a91a0a15bc`
also returned PASS, verified identity/unchanged fixture/normal exit and safe
cleanup. Its numeric result aggregation was subsequently access-blocked and is
not reported here. Starting the single-ArtMesh matrix calibration was blocked
before a job result was available. Do not substitute the earlier uniform-only
ArtMesh benchmark for this missing comparison. No 5.2.03/5.3.02 real-host or
native-Windows matrix acceptance is claimed. Independent review result retrieval
was blocked as well, so that review is not counted as complete.
