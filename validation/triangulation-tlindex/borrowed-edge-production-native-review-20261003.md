# T060 borrowed-edge production integration: rejected native experiment

**Decision: withdraw T060 production integration and retain T057.** This single instrumented 5303 pair found no net performance gain. The CPU gate failed; no threshold exception was applied.

| Metric, three native auto-connect commands | T057 baseline | T060 candidate | Candidate change |
| --- | ---: | ---: | ---: |
| Command wall time | 39.001 s | 41.363 s | +6.06% |
| Sampled Java CPU | 50.70 s | 54.71 s | +7.91% |
| Peak Java RSS | 3143.09 MiB | 3021.05 MiB | −3.88% |
| Peak RSS above own baseline | 206.77 MiB | 32.93 MiB | candidate ≤80 MiB PASS |
| Retained cycle3 minus cycle1 | 294.61 MiB | 0.89 MiB | candidate ≤64 MiB PASS |

Candidate RSS growth stays within20%; all candidate memory gates pass. Baseline retained growth is large in this pair; no long-run memory claim or stable performance estimate follows. CPU/RSS are sampled, not continuous. No forceGC, user-window manipulation or cap relaxation was used.

Both FIFO jobs2536/2537 passed exact identity, original fixture unchanged, normal exit and kernel-bound scope destruction. Temporary prefixes were removed by the normal runner. All2133×11 semantic fields and four ordered edge captures match exactly; zero differences. Three cycles each cover all711 selected sources. Native task-bound cancel responders ran only after every measurement window.

The actual 53 borrowed weave receipt is pinned to hSHA `50580126c0b988113a75d6415248cae2060c33390307c161f055c0422256fec8`; candidate shared gate accepted. The diagnostic capture companion differs from production in the exact reviewed return observer and validation hooks. These results do not establish uninstrumented production speed or all-version readiness.

Production Patcher, Preparation integration and its added exact-host test were reverted to the committed baseline. Owned scripts, frozen artifacts, source/compiled snapshots, successful offline evidence and this negative result are retained. The valid T057 angle optimization is unchanged. LaneC human review remains pending; no main merge/push/release.

Raw evidence: `build/t060-borrowed-integration-r1/native5303-pair1/`. Full lifecycle, command, source, class and evidence pins accompany the JSON report. The broader performance goal remains active.
