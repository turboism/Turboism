# T102 same-recording allocation site audit

Existing T092 T057 baseline and T095 final T093 recordings rehashed before read-only streaming analysis. No new host runs or production changes. Three command envelopes per recording bound to the same JFR clock, task, EDT and 30 independent CPU/clock fields. Five analyzer positive/negative controls pass. Original evidence and failed formal verdicts remain unchanged.

| Three command scopes | T057 baseline | T093 candidate |
| --- | ---: | ---: |
| All EDT allocation sample weight | 10617.53 MiB | 10636.29 MiB |
| GVector2 weight | 6237.34 MiB | 6055.57 MiB |
| PointInTriangleD corner-vector leaf weight | 5864.21 MiB | 5665.23 MiB |
| Corner-vector share of sampled weight | 55.23% | 53.26% |
| NativeMeshEdgeLookup inclusive weight | 0 | 55.75 MiB |
| NativeMeshEdgeTable inclusive weight | 0 | 31.98 MiB |
| Samples / truncated samples | 3188 / 184 | 3024 / 166 |

Top corner-vector leaves: PointInTriangleD$a.a bytecode positions 87, 112 and 137, reached from meshGenerator transform and actionManager.ac selection queries. This is a more substantial allocation target than the bounded native lookup table. Same official PointInTriangleD, PointInTriangleD$a and GVector2 class hashes were found in all three SDK archives; GVector2 companion differs in 5203 and must be audited separately. Nine corner-creation sites exist across inside search, nearest fallback and final-result preparation. Original math/selection order remains the reference.

Next experiment: per-invocation lazy reuse of three private corner vectors, preserving array-read order, all native arithmetic, logger and result semantics; no static/thread-local shared mutable geometry. Owned SDK differential can establish local semantic feasibility before considering a new production admission. No speed/RSS acceptance or monotonic-object-leak claim follows from this audit.

Weights are estimates and may straddle sample timestamps, not exact/live bytes or CPU fractions. Inclusive owner values overlap. Strict same-recording envelope membership excludes boundary timestamps and other threads; loss refuses; missing/truncated frames stay visible. Historical independent recordings cannot causally explain T101. Streaming avoids whole-export memory retention. All detailed class/site/BCI stacks and source pins are in the JSON and build evidence.
