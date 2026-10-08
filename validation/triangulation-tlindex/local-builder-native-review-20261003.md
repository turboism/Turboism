# T053 local edge builder: bounded native5303 comparison (2026-10-03)

Implementation and one queued diagnostic pair are complete. Correctness and the
original resource gates pass. Measured incremental wall improvement is **2.17%**;
one sequential pair cannot establish a stable speedup or direct uninstrumented
production performance. Separate LaneC human review is still required. No merge,
push or release. Source implementation commit: `3c76b75e9`.

The local unordered-endpoint table preserves native getter/append semantics and
first physical edge order. It shares the existing full-operation definition
lease, contains no host objects and falls back natively when not admitted.

## Actual measurements

Both legs use enabled indexing, the same711-source heavy fixture, three native
commands, settings/config, JVM options and unchanged observation probes. FIFO
baseline seq2519 and candidate seq2521 passed normal exit, official/fixture
identity and final kernel scope destruction. Both task-owned prefixes were
removed by the supervisor. Another queued task seq2520 ran between these legs;
managed queue tasks did not overlap either measurement.

| Measurement | T051 baseline | T053 companion | Observation |
| --- | ---: | ---: | --- |
| Three native command wall | 67.406s | 65.945s | −2.17% |
| Recorded producer time | 64.228s | 61.959s | −3.53% |
| Sampled Java CPU total | 74.76s | 74.26s | −0.67% |
| Sampled Java peak RSS | 3043.18MiB | 3041.85MiB | Essentially unchanged |
| Sampled Java peak PSS | 2981.35MiB | 2979.45MiB | Essentially unchanged |
| RSS peak above own baseline | 19.32MiB | 21.09MiB | Below80MiB cap |
| Retained RSS cycle3−cycle1 | 0.72MiB | 0.89MiB | Below64MiB cap |
| Observed operation heap peak | 1931.10MiB | 1908.39MiB | Boundary + GC samples only |
| Auxiliary sampled CPU | 5.03s | 4.86s | Kept separate from Java |

CPU average (100%=one logical core) was116.81%→117.87%, interval peaks314.48%→
410.92%. Java user/system totals73.71/1.05s→73.32/0.94s. Sampled CPU omits
operation boundaries; maximum operation sampling gap was1033ms in both legs.
Heap values combine same-JVM operation boundary heapUsedBytes and JFR
GCHeapSummary epoch-window samples; they are not continuous maxima. Five vs three
GC events occurred in the three command windows. Resident metrics and heap
depend on initial heap/native state; tiny differences do not prove memory savings.

The unchanged gates (Java CPU total regression≤0%, absoluteRSS growth≤20%,
own-baseline RSS increase≤80MiB, retained3−1≤64MiB) all pass. The prior T051
0.89MiB exception is not used. No forced GC or native backup disabling.

## Correctness and runtime execution

All2133 rows×11 semantic fields match exactly, including source/order, vertex
counts, edge versions and full position/index hashes. All four observed ordered
edge count/hash captures also match. Native cross-cycle differences are the same
in corresponding legs. Actual TL pre-observation bytes match the frozen expected
candidate hash, and runtime capture reports OWNED_FINAL_DEFINITION_MATCH.

Whole-recording Java ExecutionSamples show350 baseline k.a leaf samples directly
called by TriangleList.b(), and0 on the candidate. Four candidate leaf samples
execute TriangulationBuilderEdges.slot (also record/seen frames). This is positive
evidence that the table executed, not merely installed. No sampled builder scan
does not prove no fallback ever occurred. Samples are not calls or CPU duration;
NativeMethodSample counts remain separate. The profile change cannot be converted
into a speedup percentage.

The largest remaining conditional Java leaf is TriangulationEdgeIndex.settle
(249/689 candidate target-stack Java samples); native geometric routines also
remain prominent. This identifies a next audit candidate, not a promised removable
cost or a reason to relax consistency checks.

## Artifact and review boundary

Production SHA256:
`2f4f1876c6a209f1fa983d87b4cc68458be4c5e9c022dbf38e1cf6525ed704ab`.

Diagnostic companion SHA256:
`5c28ed980eac8e8b1678b9a79f7e1639bed1cb4d423613c5f601b2d0514c6367`.

The existing return observer modifies b(). Production continues to reject that
modified contract. Only diagnostic preparation expects the two exact reviewed
return-captured fingerprints; geometry, helper, weave and lifecycle are shared.
These numbers are an instrumented baseline-on/candidate-on comparison. They do
not approve the new productionSHA, all-version Editor behavior, long-run memory
plateau or direct uninstrumented production performance. No fixture, official
files or golden prefix were modified. The companion-only difference and failed
offline harness attempts remain recorded in the offline review.

Adjacent JSON binds73 raw/script/report evidence files. The prior offline report
records56 JUnit tests/devCheck,652 native class execution controls,30 real-premain
positive/negative controls and three unsupported-startup fallbacks. No further
production code changes followed that final verification.
