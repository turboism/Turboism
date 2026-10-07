# T110 formal controlled ABBA — callback-context candidate (2026-10-04)

Frozen before any submission; no replacement or retry.

- Order: baseline1 → candidate1 → candidate2 → baseline2 (two reversed-order pairs, judged separately).
- Baseline agent `17b2a714…` (accepted T057); candidate agent `6a7aa03b…` (point-triangle corner reuse + owned callback-context tracking, T108 final candidate).
- Identical controlled-input non-JFR task plugin `e03edb6d…`, settings probe `30f5203e…`, single production premain, `-XX:+DisableAttachMechanism`.
- Per leg: 3 cycles x 711 sources complete controlled parity vs T092 baseline, six ordered input snapshots, six kernel CPU unit comparisons, exact run label and artifact pins.
- Gates (unchanged): per-pair CPU regression ≤ 0%, strict wall improvement, operation peak RSS growth ≤ 20%, peak above own baseline ≤ 80 MiB, 3rd-minus-1st retained median ≤ 64 MiB. Aggregate values never override a failed pair.
- Prior evidence: T109 preflight PASS (controlled parity, historical 378-row difference preserved); T106 formal failure for the pre-callback candidate PRESERVED.

Performance acceptance NOT granted by this protocol alone; all-version, long-run and human review remain pending. No merge, push or release.
