# T053 local edge-builder integration: offline review (2026-10-03)

Status: **PASS_OFFLINE; exact-host and resource acceptance pending**.

The prior native5303 JFR review found 363/379 native k.a(j,boolean) leaf samples
called directly by TriangleList.b(). That is a hotspot attribution, not a promised
32% elapsed-time reduction. The native builder owns a fresh local k until return.
T053 replaces only its three unordered endpoint membership queries with a bounded
method-local primitive table. The original d/e/f getter order, append branches,
first edge objects, result order and native fallback remain intact. No general
cache, geometry, official files, backup policy or product settings are changed.

The woven operation holds the existing definition-ownership lease until normal
or exceptional exit. One shared capture includes k and the builder/field contract;
calling the builder entry does not revoke the previous lazy-edge gate. Other
TriangleList methods are outside this explicit builder contract. Full official
and Kotlin JAR admission, endpoint/face/geometry dependencies, classloader/origin
checks and ownership rules remain mandatory. The table starts at16 entries,
grows at50% occupancy and stops at1<<20 longs (8MiB table; old/growing arrays can
coexist transiently). Ordinary refusal permanently restores native membership
for that invocation; fatal VM errors retain the existing FatalErrors policy.

## Evidence

- 56 affected JUnit tests passed (33 helper/index,12 transformer,6 installer,
  5 exact-host class shape). Final devCheck and bootstrap shadow build passed.
- Offline execution of actual5203/5302/5303 classes passed652 controls under
  Java17 lint/Werror and Xverify:all. For100 native triangles, the active owned
  test lease reduced300 native scanning queries to0 and returned the same201
  physical edge objects in the same order. Inactive lease, getter exceptions,
  empty result and lease release controls passed. This substitutes an owned test
  lease and is not evidence of real premain or Editor execution.
- Genuine single canonical premain, attach-disabled, headless JVMs admitted the
  frozen production artifact on all three reviewed versions. Both bridge entries
  shared a usable lease. No Editor or official class initialization was requested.
- Production and diagnostic companion each passed15 real-premain controls:
  accepted,b-body mutation,k-body mutation,field mutation and owned-instrumentation
  revocation on5203/5302/5303. All30 passed. Additional production runs with attach
  enabled rejected both optimization entries on all three versions.

## Diagnostic compatibility and preserved failures

The prior planning assumption that the return observer instruments a(j) was
incorrect: CaptureWeave instruments b() itself. The first capture-only production
checks correctly refused that changed dependency; all nine other dependencies
matched. These attempts and two earlier incompatible scene-driver attempts are
preserved. Two control-harness attempts exceeded the probe's32-character runId
limit; they are retained and excluded from passed counts.

Production admission was **not widened**. Its exact builder contract continues to
reject return instrumentation. The dedicated build-local-builder-companion.py
compiles only diagnostic preparation with two exact reviewed return-observation
fingerprints. The observer stores the result, calls onReturn after the production
lease closes, catches observation errors and returns the same object. Actual
captured definitions including the b body and all field shape are compared;
additional b/k/field mutations still reject. The companion differs from production
in the existing validation hooks/classes and two preparation class entries. Its
geometry, primitive helper, weaving and definition lifecycle match production.
Future host numbers therefore describe a bounded instrumented comparison, not
uninstrumented production performance or complete production readiness.

## Frozen inputs and remaining acceptance

Production SHA256:
`2f4f1876c6a209f1fa983d87b4cc68458be4c5e9c022dbf38e1cf6525ed704ab`

Diagnostic companion SHA256:
`5c28ed980eac8e8b1678b9a79f7e1639bed1cb4d423613c5f601b2d0514c6367`

Baseline is the previously reviewed T051 production/companion pair. The native5303
baseline-on and candidate-on snapshots use identical711-source/3-cycle protocol,
fixture, JVM flags, probes and config. Candidate TL bytes before capture are
`f09c870494cb1d028fe149fda4ec13d1caa271df4c6415c68a7d570dc011ca0b`;
baseline retains `f3427cec0e5c0c7d93c8a2cf72351a82a39b635b15682afc291eb3a62dc888f5`.
Prepared IDs and210 source/artifact/evidence pins are in the adjacent JSON report.

The shared worker was online but observed a queue-external Cubism session; no
window was manipulated, adopted or terminated. Host execution remains pending.
Queued acceptance must compare all2133 rows across11 semantic fields plus the
first4 ordered edge captures, verify live dependency admission, official/fixture
identity, normal exit and final kernel containment/prefix cleanup. Report wall,
sampled CPU, RSS/PSS and heap under the original resource gates. The earlier
T0510.89MiB RSS exception does not apply to this new production SHA. One pair
cannot establish statistical speedup, long-run retention or all-version Editor
acceptance. No merge,push or release was performed.
