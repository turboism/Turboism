# T072 primitive edge table: native closeout

Decision: **WITHDRAW_PRIMITIVE_EDGE_TABLE_RETAIN_T057**. Both fixed reversed-order pairs must pass independently. The reverse pair failed wall, direct process CPU, and retained-growth gates. No fifth leg or favorable retry was performed.

| Pair | Command wall change | Direct CPU change | Peak RSS change | Result |
|---|---:|---:|---:|---|
| B1 / C1 | −9.0953% | −2.5256% | −47.4840% | PASS |
| B2 / C2 | +1.5814% | +3.4680% | −1.9173% | FAIL |

| Leg / FIFO | Wall s | Direct CPU s | Peak RSS MiB | Peak above own baseline MiB | Retained 3−1 MiB |
|---|---:|---:|---:|---:|---:|
| B1 / 2560 | 40.4678133 | 52.66 | 5042.1445 | 341.6602 | 558.8633 |
| C1 / 2562 | 36.7871592 | 51.33 | 2647.9336 | 37.6836 | 6.7812 |
| C2 / 2564 | 37.2413329 | 50.72 | 2685.7852 | 79.3906 | **118.0781** |
| B2 / 2567 | 36.6615812 | 49.02 | 2738.2852 | 20.6641 | 0.3672 |

C2 exceeds the fixed retained-growth cap of 64 MiB. Its peak above its own baseline remains within 80 MiB. Descriptive aggregate wall −4.0204% and CPU +0.3639% cannot override pair failures. Large B1 footprint variation prevents attributing the first pair RSS reduction to the new table; these observations alone prove neither a leak nor a stable regression.

All four authoritative jobs succeeded with normal exit, pinned identity, unchanged fixtures, cgroup destruction and independently verified validation cleanup. Each leg has 2,133 producer rows × 11 matching semantic fields and four matching ordered edge captures. All 24 command-boundary CPU unit checks passed. Direct CPU covers all Java threads/common dispatch; sampled RSS and two pairs do not establish statistical or uninstrumented production benefit.

Protocol commit `85f92ad0a` preceded submission. Frozen protocol SHA: `5f01eb953fd76046604985e49a02947b6a5cbaca7d29d391b8f4549c7e462185`. See the companion JSON for raw-input hashes, per-cycle results, job IDs and rejected snapshot pins. Historical offline/protocol evidence is unchanged.

Only the owned candidate source and tests were restored byte-for-byte to HEAD. Rejected source, tests and five actual compiled family classes remain in `build/t072-primitive-edge-table-integration-r1/rejected-integration-snapshot-r1/`. The canonical Gradle jar is still an experimental withdrawn candidate and **must not be delivered**. Use frozen T057 production SHA `17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295`.

Next: read-only audit of existing command and retained-window heap/GC/resource data, especially B1 footprint and C2 late RSS growth, before selecting a distinct candidate. No retry of this table to obtain favorable results. Lane C human review remains pending; no merge, push or release; broader performance goal remains active.
