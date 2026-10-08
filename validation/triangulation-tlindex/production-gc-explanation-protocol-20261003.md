# T076 separate GC/heap explanation protocol

Exactly one baseline and one candidate FIFO diagnostic job, in that order. No retry,
replacement or performance acceptance. This is new explanatory evidence for memory
variation, not replacement legs for the failed production ABBA. Original result,
limits and all measurements remain unchanged; T057 remains the delivery baseline.

Use identical existing observer-free plugin, settings probe and kernel observer,
unchanged exact T057/T075 production Agents, same fixture and three commands covering
711 sources each. The only new JVM variable is timestamped GC/heap logging:
-Xlog:gc=info,gc+heap=debug:stdout:time,uptimemillis,level,tags. No new premain, JFR,
attach, forced GC or window manipulation. Preserve all output/lifecycle/CPU identity
checks and the predeclared original-bound destroyed-or-same-integer-populated-zero
cleanup contract. Abort on invalid evidence; never rerun to obtain a desired spike.

Predictions: if heap resizing explains RSS jumps, timestamped committed-heap changes
should coincide with those sample intervals. If GC residency changes within an
already committed heap explain them, commitment can remain flat while GC/used size
and RSS differ; no allocation-owner claim can follow from that alone. If retained
queries accumulate, repeated heap observations should support growth; these are
not full-GC live sizes and cannot establish or exclude a leak. If neither run
reproduces the previous spike, retain that absence and report the cause unresolved.

A actual HotSpot G1 smoke run validates the parser/log flag offline, not vendor/native
admission. Parser requires G1 admission, bound before/after GC heap summaries and
valid used/committed sizes, preserving timestamps and any log-order inversions.
Logged timings do not amend or supplement the fixed performance decisions. The
purpose is to determine whether additional allocation/native-map evidence is needed.
All-version/long-run/LaneC remain open. No merge/push/release.
