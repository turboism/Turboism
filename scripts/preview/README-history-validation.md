# Cubism history manager exact-host probes

Manual-test-only probes for exact Cubism 5.2.03 and 5.3.02:

- `history-validation-probe.jar` is read-only and projects the public Undo-manager surface.
- `history-seed-validation-probe.jar` uses the Turboism SDK to write Parameter values, asserts each Turboism write is projected with FULL structured `HistoryAction` detail, asserts the production `moveTo()` capability fails closed (`UNAVAILABLE`, document untouched), and restores the original value through a direct write.

The bundle must be launched only by a host-side task wrapper that enforces exact identity, cloned-prefix isolation, official-BAT delegation, and copied-fixture rules.

## Evidence

Plugin-owned validation evidence (created lazily on first probe write):

```text
data/dev.turboism.validation.history-manager/history-probe.jsonl
data/dev.turboism.validation.history-seed/history-seed.txt
```

The history probe records active document/EditMode identities, `DOCUMENT`, `CURRENT`, `MAIN`, and `LINKED` manager identities, positions, labels, entry counts, ClassLoader, and EDT identity. It refuses existing evidence and caps output at 2 MiB.

The seed probe records one Parameter's before/write/restored matrix plus the fail-closed move result. A PASS requires exact restoration and FULL detail on every Turboism entry. It never calls `CUndoManager` directly.

## Required sequence

1. Record Editor/JAR/BAT/agent/both-probe/source-fixture/isolated-fixture identities.
2. Launch a fresh copied fixture and wait for both evidence files.
3. Require `history-seed.txt` status `PASS`.
4. Correlate the FULL `HistoryAction` metadata (kind/targetType/targetId/property/before/after) with the native write sequence; confirm `moveTo()` reports `UNAVAILABLE` without mutating the document.
5. Switch Main -> Mesh -> Texture -> Main separately; do not infer mode identity from the seed run.
6. Close Cubism normally, re-hash the protected source and copied fixture, and finalize evidence.

These probes do not test direct `undoRedoTo` (capability-gated in production); populated history observation with FULL detail is the production-read contract.
