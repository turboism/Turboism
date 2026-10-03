# T100 direct NMT startup review

Status: **PARTIAL_FINAL_PROXY_GATE_UNPROVEN**. FIFO 2659 completed normally, with standard identity, fixture and cleanup gates passing. The original post-terminal audit failed because successful cleanup removed the task prefix before its final ProxyConfig read. That failure is preserved; the frozen final-config gate is unproven. No candidate was submitted and no performance acceptance is granted. T057 remains the accepted delivery.

Independent data audit bound 20 NMT snapshots and 56 raw page samples to the actual host JVM. Each idle window contains six complete captures. Controlled output differential: 2,133 rows, zero mismatches. Historical reference mismatch count remains 378.

| Window | Heap RSS MiB | Outside heap RSS MiB | Crossing RSS MiB |
| --- | ---: | ---: | ---: |
| Baseline | 3719.17 | 619.51 | 0 |
| Retained 1 | 3761.99 | 643.00 | 0 |
| Retained 2 | 3761.99 | 654.43 | 0 |
| Retained 3 | 3683.76 | 688.51 | 0 |

Retained 3 minus 1: heap −78.23 MiB; outside heap +45.51 MiB. Earlier large heap growth did not reproduce in this baseline diagnostic. These values neither attribute T093's memory behavior nor replace T097 gates. NMT accounting is not residency, and outside heap includes Wine and untracked allocations.

The JSON records rehashed protocol, raw preservation, saved evidence and analyzer inputs, plus durable audit scripts and logs. Original `native-review.log` and the empty failed-audit destination remain intact. A future distinct protocol must capture final configuration before cleanup; this protocol is not retried or retrospectively waived.
