# Cubism history manager exact-host probes

Manual-test-only probes for exact Cubism 5.2.03 and 5.3.02:

- `history-validation-probe.jar` is read-only and records each native Undo-manager snapshot beside the Runtime SDK history projection from the same EDT poll, including structured target/change/context/coordinate data, exact native entry/child/target classes, allowlisted scalar/list availability, direction/count data, and bounded degradation codes.
- `history-seed-validation-probe.jar` uses SDK services to write Parameter values and Artmesh multiply colors, asserts FULL operation-time captured metadata and stable IDs, commits a two-change transaction, and checks earlier Artmesh details remain unchanged after a third write and Undo/Redo. These are SDK-capture checks, not evidence of native UI capture coverage.

The bundle must be launched only by a host-side task wrapper that enforces exact identity, cloned-prefix isolation, official-BAT delegation, and copied-fixture rules.

## Evidence

Plugin-owned validation evidence (created lazily on first probe write):

```text
data/dev.turboism.validation.history-manager/history-probe.jsonl
data/dev.turboism.validation.history-seed/history-seed.jsonl
```

The history probe records active document/EditMode and manager identities, positions, labels, exact entry classes, bounded group child classes, approved target classes, scalar/list availability, add/remove direction, truncation, ClassLoader, EDT identity, and the corresponding Runtime SDK projection. This permits direct comparison of native facts with the final domain-semantic result instead of treating a callable getter as semantic proof. It never enumerates fields or stringifies unknown host objects, refuses existing evidence, limits semantic projection to depth 4/nodes 64/strings 256, and caps output at 2 MiB.

The seed probe writes JSON Lines with explicit expected/actual/status assertions. PASS requires Parameter FULL/OBJECT details, grouped metadata, restoration and navigation, plus captured `ART_MESH` `multiplyColor` before/after values, complete form context and `TURBOISM` provenance. Earlier color entry details must remain exactly equal after a third write and Undo/Redo. It does not stringify SDK DTOs or call CUndoManager directly. Native UI entry capture requires separate validation and cannot be inferred from this PASS.

## Required sequence

1. Record Editor/JAR/BAT/agent/both-probe/source-fixture/isolated-fixture identities.
2. Launch a fresh copied fixture and wait for both evidence files.
3. Require final JSON line `{"type":"summary","status":"PASS"}`; any captured-detail, temporal-stability, restoration, navigation or grouped check forces FAIL.
4. Correlate stable IDs and FULL target/change/origin/context detail with the direct Parameter write sequence; verify the grouped transaction has one committed entry with two child details.
5. Correlate captured SDK entries with native GroupUndo/SimpleUndo/CArtMeshForm evidence from the same EDT poll. Native class structure is supporting evidence only; this probe no longer requires a ListUndo or claims missing redo data is recoverable. Unknown/uncaptured entries remain explicitly degraded.
6. Switch Main -> Mesh -> Texture -> Main separately; do not infer mode identity from the seed run.
7. Close Cubism normally, re-hash the protected source and copied fixture, and finalize evidence.

Exact-host native-semantic readiness is in scope only for Cubism 5.2.03 and 5.3.02. Cubism 5.3.03 remains pending even if static class shapes appear compatible.

## Local-host execution

The history wrapper defaults to local transport; no SSH key or SSH service is required. The generic Runner retains explicit `--execution-mode local|ssh` selection for compatibility. All identity, CoW prefix, official BAT, fixture and process-ownership gates remain unchanged. `--remote-*` option names refer to this machine's task paths in local mode.

```bash
./gradlew previewBundle :plugins:history-panel:jar :testing:integration-tests:testClasses
bash scripts/preview/package-windows-history-panel-validation.sh
bash scripts/preview/run-history-baseline-validation.sh 5203 capture-local-r1 --dry-run
# Only after host-idle and identity checks; use a new evidence directory for every run:
bash scripts/preview/run-history-baseline-validation.sh 5203 capture-local-r1 \
  --local-evidence-dir build/host-validation/semantic-history/5203/capture-local-r1
```

Machine-specific paths are loaded from the ignored environment file (`TURBOISM_ENV_FILE` when needed). Do not reuse an evidence directory or start while another Cubism task is active. 5.3.02 follows only after 5.2.03 completes successfully and is cleaned up.
