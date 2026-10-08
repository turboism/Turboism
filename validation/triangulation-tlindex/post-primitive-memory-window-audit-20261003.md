# T073 memory-window audit: initial verified findings

Status: **RESOURCE_TRANSITIONS_VERIFIED_JFR_CORRELATION_PENDING**. Read-only audit of the four frozen T072 raw resource logs and marker tables. Input hashes match the committed T072 review; host PID/start identity and monotonic sample ordering pass, and every window peak reproduces its prior review. No new host leg, production edit or historical gate change.

B1 enters baseline observation with boundary used heap 3976.40 MiB versus B2 1327.37 MiB. Baseline median RSS is 4700.48 versus 2717.62 MiB. Thus substantial session variation exists before the first measured command; the first pair cannot establish table memory savings.

| Leg | Third return→retained marker gap ms | Sampled RSS increase MiB | Boundary used-heap increase MiB |
|---|---:|---:|---:|
| B1 | 1920 | 97.52 | 48 |
| C1 | 1751 | 0.21 | 40 |
| C2 | 1759 | 90.96 | 48 |
| B2 | 1835 | 0.11 | 48 |

RSS brackets use the final sample at/before command return and the first at/after retained-start, not instantaneous marker readings. C2 sampling overhang is 245 ms before and 994 ms after. The interval includes producer evidence persistence, installation checks, fixture verification and marker reads; background host activity can overlap. Generated driver source shows fixture SHA reads through an 8192-byte streaming buffer. This rules out whole-file buffering in that method, but neither proves nor excludes other memory causes.

C2 used heap decreases during command3 (1458.18→1298.76 MiB), then increases to1346.76MiB at retained-start and remains unchanged at its end. These values include unreachable objects and do not measure live retention, committed heap or native memory. B1 also shows a large post-command RSS jump. No causal primitive-table leak, GC or native-memory attribution follows from this audit.

Next: correlate existing JFR heap summaries, GC/pause events and allocation samples across baseline, command, post-return and retained windows. Export only needed event types, and preserve observation ages. Heavy analysis is deferred while the independently verified live shared FIFO2568 runs. T072 remains withdrawn and frozen T057 remains production; Lane C review and broader optimization work remain open.

Companion JSON contains all window data,12 transition brackets and input/script/source hashes. Raw reproducible script: `build/t073-memory-window-audit-r1/audit-resource-transitions.py`.
