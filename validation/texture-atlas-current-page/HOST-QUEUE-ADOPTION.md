# Host queue adoption and final validation blockers

## Integration

- Adopted main commit: `951b6b97f855830684a39f8365064da05e6e5ffd`.
- Integration commit: `7f8b005e9` on `investigate/texture-sort-native-legacy-performance`.
- All 35 pre-existing modified/untracked files were backed up and their hashes remained unchanged after the merge. No worker checkout or official host files were changed.
- Read and adopted `scripts/preview/README-host-validation-scheduling.md`.

## Post-merge checks

| Check | Result |
| --- | --- |
| `devCheck` | PASS |
| `bash scripts/test/check_host_validation_scheduler.sh` | PASS |
| `python3 scripts/test/test_host_validation_queue.py` | PASS, 43 tests |
| `checkTextureAtlasSdkV7Linkage` | PASS |
| `:plugins:atlas-maxrects-bssf:test` | PASS |
| `:runtime:test --tests '*TextureAtlas*'` | PASS |
| SDK integration | Initial live-v8 exact FAIL; after explicit user approval, frozen v8 historical and new v9 live exact gates PASS |
| `git diff --check` | PASS |

Logs: `build/atlas-main-integration/{scheduler,queue,gradle,focused,v9-gates,v9-negative,v9-devcheck}.log`.
The initial SDK failure concerned merged HistoryEntry/RuntimeSettings metadata and history additions. User-approved v9 contract coordination is complete: no old baseline changed; 260 SDK tests, v8/v7 client linkage and verifier mutation/negative checks pass. See `sdk/api-contracts/sdk-api-v9-review.md`. Full release and host acceptance are still outstanding.

## Real-host admission: initial blocker resolved

Read-only queue status showed `workerOnline=true`, host `idle`. The existing persistent worker was not restarted.

Initially, `host_validation.py list` contained no Atlas capability and `plan atlas:5303` failed. After explicit user approval, the catalogue now exposes six fixed 5303 Geometry100/500/1000 native/new variants through the existing generic Runner and auxiliary-Agent snapshot mechanism.

The previous Atlas validation uses `HostUiProbe.java`, `HostTimingProbe.java` and a custom driver. Their complete dependencies have not been admitted to the new manager. `host_validation_queue.py` rejects custom clients and hooks except the reviewed FPS driver. A working worker or user run authorization does not remove this capability restriction.

The user explicitly approved the fixed capability and complete dependency review. That review is documented in `scripts/preview/README-atlas-queue-validation.md`: fixed UI/timing/driver classes, captured Agent/Atlas plugin/fixture, no custom clients/hooks or process launch in the probe. Unrelated MCP/FX/dynamic hooks remain blocked. No persistent-worker checkout modification or restart was needed.

Requirements applied to every submission:

1. Reviewed Atlas task/version support and complete fixed-driver/probe dependency inventory, without aliasing Atlas as FPS/status-bar or weakening queue admission.
2. Build the actual task artifacts in this worktree and capture them with the integrated manager's `prepare`.
3. Submit the returned prepared ID; accept results only with identity, hashes, normal exit and bound-cgroup cleanup evidence.

Two Geometry100 jobs now have complete manager PASS, including normal exit and bound-cgroup cleanup: new `a485a443-d34d-4f17-9cd0-52739bc18438`, native `fcbb1326-6a56-4b81-9064-a40aac959d32`. Two failed driver bring-up jobs retain evidence; no results were promoted from their exit-incomplete runs. See `HOST-GEOMETRY-QUEUE.md` for job IDs and timing scope. `run-host-ab.py` remains historical only and must not bypass the manager.

## Remaining order

SDK integration checks and Atlas queue admission are complete. Finish overflow/Undo investigation and Geometry 100/500/1000 acceptance and documentation first. Geometry contains differently sized/shaped geometries; Circle uses equal-sized circles within each model.

Geometry 2500 remains unstarted and must be last: native once and new algorithm once, with no warmup/repetition or automatic retry. The user-reported near-hour native run is not a measured benchmark result from this validation.
