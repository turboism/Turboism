# T054 identity absence scan feasibility — 2026-10-03

Decision: do not integrate the reusable array-copy scan. This is an owned-only Lane A prototype; production T053 remains unchanged. No Editor was launched for T054.

Native removal must remain authoritative: mutable point coordinates affect triangle equality, and collision-tree removal can select another equal stored object. The physical identity absence proof cannot be skipped or replaced with argument-victim guessing.

The prototype compares the existing identity iterator proof with exact LinkedHashSet.toArray into a reused 4096-reference buffer, contiguous identity comparisons, and finally clearing. Subclasses, oversize and busy scratch fall back to the iterator. No element equals/hashCode calls are added by either proof.

Java 17 lint/Werror compilation and -Xverify:all passed. Both executions finished 529940 assertions, including 480524 initial control assertions (many check cleared buffer slots), not independent test cases. Controls cover pending 1/2/8, survivors, null, capacities, subclass fallback, busy scratch, mutated equality/treeified native removal and independent sets. Exception/drift cleanup is coded but not fault-injected.

Full repeat r2 timing is archived in the adjacent log. Six warmup pairs run iterator then array; five measured pairs alternate order. Each row is the ratio of independent medians; negative means less elapsed time. Initial execution raw output was tool-only; its medians are retained in JSON and are not presented as a full archived raw record.

| Elements | Pending identities | Repeat array time change |
| ---: | ---: | ---: |
| 128 | 1 | -24.320% |
| 128 | 2 | -30.810% |
| 128 | 8 | -1.116% |
| 512 | 1 | +9.556% |
| 512 | 2 | +15.525% |
| 512 | 8 | +18.474% |
| 2048 | 1 | +7.639% |
| 2048 | 2 | +10.451% |
| 2048 | 8 | +20.960% |
| 4096 | 1 | +8.556% |
| 4096 | 2 | +5.913% |
| 4096 | 8 | +26.825% |

All nine repeat combinations with 512–4096 elements regressed by 7.639%–26.825%; the initial run regressed in eight of twelve combinations. The small-set benefit does not justify production integration without a real workload distribution and a stronger experiment. Iterator allocation was 32 bytes/proof; the candidate allocated zero during measured proofs, excluding its preallocated scratch (4096 references plus holder). Allocation reduction alone does not establish elapsed-time benefit.

This experiment excludes full settle/deindex, production ThreadLocal acquisition, Editor operation and retention. It is not a host speedup or readiness result. T053 native5303 single-pair wall reduction remains 2.17%, with its existing evidence limitations.

Reproduce:

```sh
mkdir -p /tmp/t054-identity-scan-classes
javac --release 17 -Xlint:all -Werror -d /tmp/t054-identity-scan-classes validation/triangulation-tlindex/diagnostic/IdentityAbsenceScanSelfCheck.java
java -Xverify:all -cp /tmp/t054-identity-scan-classes IdentityAbsenceScanSelfCheck
```

The adjacent JSON pins the source and full repeat log. Further work should use the existing native JFR to rank remaining callers; array-copy integration is closed as not justified.
