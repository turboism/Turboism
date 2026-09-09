# Geometry 2500 — final one-shot ledger

## Authorization and scope

After all six Geometry100/500/1000 jobs passed and the final 1000 result was recorded, the user explicitly requested: “记录下来，继续推进2500的测试”. This final matrix tests the original current-page project, native once and new once. No warmup, automatic retry, Undo loop or second invocation. The open overflow/Undo and full legacy hot-benchmark work is not claimed as covered.

## Immutable inputs

- User source: `/tmp/atlas_mapping_geometry_2500.cmo3`.
- Asset copy: `test-assets/texture-atlas-layout/cmo3/geometry/atlas_mapping_geometry_2500.cmo3`.
- CAFF header; 24581494 bytes; SHA-256 `4bb38d8073cf339b32047bf186514dc7d3709cbfb90dbe665b0a5b0a76daf24b`. Source and copy verified identical; no model conversion/resave.
- Production Agent: `957aa623ac9ce6b62588a4c9538db68cc6ae613bf0de546c5ab0a5ce2afdcaa3`.
- Atlas plugin: `90af8240b0db5837c978e952d59ebefb9e0bf37d00a7227e8b48cf88d4a914ac`.
- Fixed probe: `b09981756f7bbc6662ecd31c8fc567cd59381bc8c5fa8281f0b2f60744a83e15`.
- Production Agent/plugin are byte-identical to the successful preliminary matrix. Only validation admission/timeout and exclusive invocation marker were extended.

## Durable queue identities — do not replace these to retry

| Implementation | Prepared ID | Fixed request key | State / Job ID |
| --- | --- | --- | --- |
| New | `9953d1cece9e6992b77d8e301488ce509db9baaaf000280f615fe66ef705d9f3` | `atlas-final-geometry-2500-new-4bb38d8073cf339b` | PASS: `22500435-7d41-4e70-96ee-6e939e958874` |
| Native | `6ecbc80f3aae264f9bb8e62cc9cd815ba389349b999212035095197442db6f86` | `atlas-final-geometry-2500-native-4bb38d8073cf339b` | CANCELLED by user after >2 hours: `65687483-9d9e-47a3-9a75-fdd1e45ccf8b` |

Both snapshots were fixed before any 2500 execution. Execute new once first, require full lifecycle PASS, then native once through the same persistent worker. Reusing the same prepared ID/request key/22500-second queue timeout is idempotent; never switch the request key to rerun an uncertain/failed attempt. Check manager status/events and `layout-started.txt`/`timing-001.json` instead. A present invocation marker with missing completion is uncertain, not permission to retry.

The marker is created exclusively before the sole UI trigger; duplicate creation fails. This is an attempt-local guard, not a cross-job rerun lock. Cross-job at-most-once handling relies on the fixed durable request identities above and this explicit no-retry policy.

## Timeouts and acceptance

Native1000 took 1634730.3306 ms (27 min 14.73 s). Final driver layout ceiling: 21600 seconds; Runner result timeout: 21900 seconds; queue timeout: 22500 seconds. These are safety ceilings, not predictions. No build/preparation while measuring.

Require actual issued count=2500, expected native/handled branch, single timing sequence, automatic scale, rotation on, mesh mode, no overflow/overlap/out-of-bounds, matching finite transforms; compare the two input hashes and output scales before a speed ratio. Also require manager exact identity, unchanged source/staged artifacts, normal exit and bound-cgroup cleanup. No host-level PASS from a timer record alone.

Pre-submit offline checks: `devCheck`, scheduler suite, 43 queue tests, 38 probe contract assertions including duplicate-marker rejection, fixed2500 wrapper filename/hash/timeout/native-new arguments, source diagnostics and 25-plugin README contracts all PASS. Initial 2500-admission test failed against the old probe before implementation, as expected. Logs: `build/atlas-main-integration/atlas-2500-*.log`.

## Observed results

| Implementation | Native-entry ms | Scale | Page | Placed | Status |
| --- | ---: | ---: | --- | ---: | --- |
| New, serial | 11544.0627 | 0.97265625 | 8192 × 8192 | 2500 | Full manager PASS |
| Native | Not completed after >2 hours (not an exact completed method time) | Unavailable | No final snapshot | Unavailable | User cancelled; cleanup safe, not PASS |

New input hash: `80e3ad8b7552daad519b55b964a9ef3bc52b5105301839c3cff4179da212e528`. Actual branch `handled`, `plannerParallel=false`, exactly `timing-001.json` (sequence=1), exclusive marker `new`. No overflow/overlap/out-of-bounds, finite matching item/LayerRef transforms. Identity, unchanged fixture, normal exit and bound-scope cleanup are all PASS.

New run ID: `queue-7868a4d8a00f45b4911f0fc0bfc000ec`. Raw transcript: `build/atlas-main-integration/geometry-2500-new-final-wait.json`; authoritative job evidence remains in the manager store.

Native was stopped at the user's explicit request: “关闭吧，记录为超出2小时”. The manager's `cancel` command produced terminal state `cancelled`, `cleanup=safe`, `normalExit=false`, `validationStatus=FAIL`. The bound original cgroup was cleaned using `cgroup.kill+cgroup-destroyed` with no cleanup errors; no unrelated process was targeted. `timing-001.json` and terminal probe result are absent, so no native completed duration, final scale, placement validation or input-hash comparison is available. The >2-hour label records the incomplete observed run, not a completed timing sample or the configured six-hour timeout.

Evidence: `build/atlas-main-integration/geometry-2500-native-cancelled.json` and authoritative manager job evidence. Source/copy fixture hashes in post-containment checks remain `4bb38d8073cf339b32047bf186514dc7d3709cbfb90dbe665b0a5b0a76daf24b`; the aggregate lifecycle acceptance flags remain false because validation/normal exit did not complete. The failed/cancelled task evidence and retained prefix were not manually removed. No rerun is authorized or performed. Heartbeat `b3b2a04d` was deleted at user cancellation.

New uses automatic scaling below 1; native final scale is unavailable. Do not claim equal final quality or calculate a speedup ratio, including a ratio based on the wall-clock cancellation threshold.
