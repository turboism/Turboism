# T061 post-borrowed hotspot and deletion-proof audit

Decision: retain authoritative native removal and current physical-absence settle proof. Next investigate owned vector-copy elision inside the existing leased h.d method. Production remains committed T057. No Editor launch.

Frozen T060 recordings were exported only after other FIFO job2538 reached succeeded. Explicit command-window analysis keeps Java/native samples separate: baseline954Java/12native target-stack samples; rejected candidate968Java/10native. The baseline has settle294samples (227atBCI163,67atBCI92), native-removal caller159atBCI544, and duplicate-membership caller65atBCI256. Candidate no longer has that duplicate lookup leaf, but has leave66 and deindex110 samples plus large caller shifts. These are observation counts, not calls, CPU percentages or a causal explanation.

Both recordings have zero CompilerInlining events. Different attribution cannot prove an inlining regression. No repeat host leg was launched merely to chase favorable timing. T060 wall+6.06%/CPU+7.91% remains the negative acceptance result.

## Deletion proof remains necessary

Native triangle endpoints are not final fields; point coordinate setters remain available. Exact h bytecode has no explicit setX/setY calls, but it accesses context-provided lists and its public operation invokes external callback d(). That is insufficient to prove geometry immutable throughout the operation. Existing mutation/tree-removal tests demonstrate that equal geometry can yield a victim different from the removal argument. A definition lease protects class definitions; it does not freeze coordinate values. No index-only or argument-identity deletion shortcut was admitted.

## Next candidate: redundant vector copies

Native h.d constructs two values as new GVector2(point.minus(other)). Each final minus method already constructs and returns a fresh exact GVector2; the outer copy constructor only performs Kotlin null checking and copies x/y fields. The scalar constructor only stores those two float fields. All three reviewed raw GVector2 classes have identicalSHA `91d06613e29fe8d0b1b03a30594cbc28d9e2a49adcdcd544be60e2576e0aac31` and matching audited bodies. This is source evidence for a bounded candidate, not runtime admission or measured allocation savings.

Prefer this candidate over adding a new per-operation lease: reuse the existing h.d lease and keep original copies on null eligibility. Preserve minus/getter order, first native angle, scalar float order, native projection/topology/order and original fallbacks. Before integration, execute complete native d controls across three profiles, assertion modes, special float/null/list/subclass boundaries and exact failure identity; verify aliases and class initialization. Independent frozen native output/CPU/RSS comparison is still mandatory. Escape analysis may already remove copies; actual savings remain unproven.

Reproduction: diagnostic/audit-vector-copy-shape.py with exact three reviewed JAR paths and a fresh output directory. Raw JFR exports and full SHA-bound read-only listings remain under build/t061-hotspot-audit-r1 and build/t060-borrowed-integration-r1/hotspots. Adjacent JSON contains complete counts, method bodies and pins. No public/module/ownership/cap changes, main merge, push or release.
