# T101 controlled NMT memory attribution review

Both FIFO legs passed the frozen diagnostic protocol: baseline 2661 and candidate 2662. Standard/identity/fixture/normal exit/safe cleanup, 2,133 controlled output rows with zero differences, six actual input/CPU boundaries, NMT/page identity and pre-cleanup final launcher evidence passed. The final task configuration was saved before successful prefix removal. T100's historical missing final-config gate remains unproven.

| Metric | T057 baseline | T093 candidate |
| --- | ---: | ---: |
| Command process CPU | 127.19 s | 128.44 s (+0.98%) |
| Command boundary wall total | 115.32 s | 120.57 s (+4.55%) |
| Heap RSS, retained 1 | 2273.68 MiB | 2316.66 MiB |
| Heap RSS, retained 3 | 3479.82 MiB | 2824.41 MiB |
| Heap RSS, retained 3 minus 1 | +1206.15 MiB | +507.75 MiB |
| Outside heap RSS, retained 3 minus 1 | +55.51 MiB | +25.16 MiB |
| Heap committed, retained 1 → 3 | 2360 → 3560 MiB | 2400 → 2880 MiB |
| NMT GC committed, retained 1 → 3 | 147.00 → 192.33 MiB | 147.95 → 165.80 MiB |

The observed retained RSS increase is predominantly inside the Java heap range in both legs. This identifies the next investigation target as heap allocation volume/sites and heap growth behavior. Heap used at retained 3 was lower than at retained 1 (baseline 1393.72 → 1156.98 MiB; candidate 1431.80 → 1138.10 MiB), while committed/resident grew. This is not proof of a monotonically retained-object leak. It does not identify the allocating method or prove an algorithmic cause. Candidate growth was smaller in this diagnostic, while CPU and command-boundary wall were higher. **No production performance acceptance is granted.** T057 remains the accepted delivery; T088/T089/T097 failures remain unchanged.

Each leg has 20 bound NMT snapshots, with 55/57 raw page samples and at least four complete captures per idle window. No resident crossing mappings were observed. Rehashed 43 frozen inputs, 122 raw preserved files, 62 saved review copies and 112 decompressed raw smaps hashes. The JSON pins durable scripts, analyses, final configuration bytes and original logs.

Single sequential pair with NMT overhead, sparse non-atomic observations and component medians: no formal ABBA/statistical/causal acceptance. NMT committed is not residency, outside heap includes Wine/untracked allocations, and separate medians need not sum to median total RSS. Next work should attribute allocation sites under a distinct controlled diagnostic protocol, without forced GC, JVM tuning or relaxed gates.
