# T073 frozen heap/GC/allocation closeout

Four frozen T072 recordings matched their original native-review SHA before sequential exports. Heap/GC/pause exports total under0.4MiB; allocation-only depth64 exports are large (0.716–1.257GB each). Existing exports and hashes are preserved under `build/t073-memory-window-audit-r1/`; do not export them again unnecessarily. Six window-analyzer regression tests passed in commit5b107ce1e.

B1 nearest heap observation before baseline has5280MiB committed (age22.70s), compared with B2 2032MiB (age0.31s). These are sparse prior observations, not start snapshots. B1 first command collects used heap4216.40→1282.13MiB with unchanged5280MiB commitment; later commands have no recorded GC heap summaries. Large session heap-policy/footprint variation predates the measured index comparison.

C2 third command records GC69 committed heap1976→2096MiB (+120MiB), used heap1178.76→1218.76MiB, ending10.286s after command start and approximately1.70s before command return. No GC heap observation lies inside third post-return or retained windows. Sampled RSS subsequently rises90.96MiB across the return→retained bracket; retained3−1 medians increase118.08MiB. Committed-heap expansion precedes late RSS growth, a temporal association requiring caution rather than evidence of a table leak or proof of causation. Candidate1 remains2080MiB committed across recorded commands, B2 remains2032MiB. GC event elapsed durations are not CPU; pause overlap is reported separately.

| Leg | Visible tryQuery allocation sample weight MiB | Index put stack weight MiB |
|---|---:|---:|
| B1 | 250.49 | 177.32 |
| C1 | 250.83 | 168.16 |
| C2 | 213.02 | 101.14 |
| B2 | 255.47 | 155.75 |

Weights are sparse estimates, not exact bytes, retention, CPU fractions or savings. Query visibility is limited to the first six rendered site frames; exported traces have depth64. The inherited allocation helper uses inclusive boundaries, so adjacent window totals are not asserted as exact disjoint partitions. Retained3 has no observed triangulation-stack allocation sample in any leg; this does not prove allocation absence or identify native memory.

**Next distinct target:** audit actual `tryQuery` snapshot consumers, modification operations, ordering, escape and detached lifetime guarantees before considering an owned small-list snapshot prototype. Source currently returns `new ArrayList<>(4)` for empty results and `new ArrayList<>(bucket)` otherwise. No shared live buckets, cached mutable snapshots or weakened fallback/removal proof. Allocation evidence motivates feasibility work but proves no performance gain.

T072 remains FAIL/withdrawn, T057 production retained. No candidate retry, historical gate reinterpretation, new host launch, merge/push/release. Lane C human review and the broader optimization goal remain open.

Execution correction: shared FIFO2568 was verified as a live model-import functional test with no performance observer/JFR configuration. Earlier blanket deferral was overly broad; small sequential heap exports then proceeded. Allocation exports were unexpectedly large; by the time a unique-output-file descriptor interruption check ran, all four read-only analyses had completed. No process was interrupted or analysis rerun. Future large event work should use streaming aggregation.
