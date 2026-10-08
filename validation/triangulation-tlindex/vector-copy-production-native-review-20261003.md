# T063 vector-copy integration: rejected native experiment

**Decision: withdraw T063 production integration and retain T057.** The single instrumented 5303 pair has a small wall-time reduction, but fails two original RSS gates. No resource-cap exception or favorable retry was applied.

| Metric, three native auto-connect commands | T057 baseline | T063 candidate | Candidate change / gate |
| --- | ---: | ---: | ---: |
| Command wall time | 38.319 s | 37.977 s | −0.89% |
| Sampled Java CPU | 48.83 s | 46.10 s | −5.59%, PASS |
| Peak Java RSS | 3088.57 MiB | 4444.91 MiB | +43.91%, ≤20% FAIL |
| Peak RSS above own baseline | 31.08 MiB | 363.34 MiB | ≤80 MiB FAIL |
| Retained cycle3 minus cycle1 | 7.52 MiB | 63.39 MiB | ≤64 MiB PASS |

All 2133×11 semantic fields and four ordered edge captures match exactly. Each cycle covers all711sources. Both FIFO jobs2540/2541 passed identity, unchanged fixture, normal exit and kernel-bound cgroup destruction; isolated prefixes were independently confirmed removed. Task-bound native cancel ran only after all measurement windows; no external window action occurred.

Observed RSS difference does not establish a causal allocation regression or leak. Sampling is approximately1second and excludes unsampled CPU boundaries; RSS is not a continuous maximum. This sequential diagnostic companion pair does not establish statistically reliable speed, uninstrumented production performance, all-version benefit or long-run memory stability.

The actual composed53h receipt is pinned to `74e03c8218630f2677c640250d3aa1b8353d35aea5c87b909f730f1ce02e12de`. Frozen production candidate `b50baa7119355df8bc33c04bf3fe7e44b94c585829bf4befc134e365645819ff` and exact capture companion `d411f54931930ff6b048a011b477cf7a743a0e2fa411b14fc89a63292b111a08` remain experimental artifacts, not approved delivery.

Only this experiment's production patcher, Preparation changes and added exact-host test were withdrawn. T057 remains unchanged. Source and147file compiled snapshot pins, frozen artifacts, offline gates and full native evidence remain in `build/t063-vector-copy-integration-r1/`. The compiled-production verifier accepts `--runtime-source` pointing to the archived mesh sources and `--runtime-classes` pointing to archived classes for replay. The offline report records historical tests against the rejected candidate, not the restored checkout.

LaneC human review remains pending. No main merge/push/release. The broader performance goal remains active; next analysis should inspect allocation/GC and existing native removal/settle hotspots before another candidate, without relaxing geometry or lease proofs.
