# Authorized UI matrix: Circle / Geometry 100, 1000, 2500

User explicitly requested these additional UI measurements after the four500 cases completed, and capped native jobs at30minutes. All12 cases are submitted exactly once through the unified manager. Do not submit again, change request keys, extend timeout, retry failures or use a direct launcher. Prior Geometry2500 entry-only native result remains **incomplete after >2h, user-cancelled**; this newly authorized UI job is a distinct experiment, not a replacement for that history.

## Measurement and timeout

Same exact OK ActionEvent → layout progress window `SHOWING_CHANGED=false` measurement accepted in [500 UI report](HOST-UI-500.md). Raw instrumented UI time includes separately reported input snapshot/observer cost; output validation/hashes/JSON happen after close. Not model-open time or every-GPU-frame latency. Automatic scale, rotation enabled, mesh mode, current page only, serial new planner. Compare actual input hashes, final scale and placed counts before any speedup claim.

Every job's persisted manager `timeout_seconds` is **1800**, including native. This hard task budget includes startup after admission, but not queue waiting. UI driver/result budgets also1800. The manager stops only the task-bound cgroup on timeout; proving cleanup may take extra time. A timed-out job is not a completed native duration or PASS and receives no numeric ratio. Say "30-minute task limit reached; layout incomplete" rather than pretending layout itself ran30minutes.

All snapshots were prepared and inspected before any submission. Production Agent `957aa623ac9ce6b62588a4c9538db68cc6ae613bf0de546c5ab0a5ce2afdcaa3`, Atlas plugin `90af8240b0db5837c978e952d59ebefb9e0bf37d00a7227e8b48cf88d4a914ac` unchanged. Expanded fixed probe SHA-256 `462f0d82904837cc803b7a0400e6341a7b6a6fc2e7817d930ef924974072d416`; only admitted fixture counts and UI deadline changed from500 probe.47 offline probe assertions, all16 UI wrapper fixture/mode/hash/budget checks and scheduler/bundle gates passed. No builds during host measurement. No worker/Runner/hook changes.

Circle consists of equal-sized circles within each model; Geometry uses heterogeneous sizes/shapes. Exact fixture mappings/hashes are fixed in `scripts/preview/run-atlas-host-validation.sh`, original models remain immutable.500 is already complete and is not rerun.

## Immutable job ledger

Request key for every row: `atlas-ui-expanded-CASE-v1` (CASE is first column). Full submit responses and machine-readable ledger: `build/atlas-ui-expanded/jobs.json` and `build/atlas-ui-expanded/CASE.submit.json`.

| Case | Prepared ID | Job ID | Hard seconds |
| --- | --- | --- | ---: |
| ui-circle-100-new | `a00c1f3708a462c0473fb24b20a6d9409362990a8cd0d930ed580d8122508a25` | `c44666b4-6f84-4cd8-9bd5-04bac69415f3` | 1800 |
| ui-circle-100-native | `59dc25ae0f4e1d0cf46e341ab5a5cfac3b9c8afc38dfd58049414148e918f926` | `0a0f1eba-b296-411f-ba7e-3faeb60a85ed` | 1800 |
| ui-circle-1000-new | `0aecd0d09df2712a6766e188c2c6ceacf8a229bb4b0f0f89c9fbb23ebdd5e348` | `8aeba45a-b256-4cb9-b8a8-64160ff79807` | 1800 |
| ui-circle-1000-native | `bc9b36c198f8e6ef834b4f2d0965dab59bdd0a04ef5424c7380481177b41526f` | `c6d40556-908f-4c44-9518-58b685868274` | 1800 |
| ui-circle-2500-new | `f235be42de9c54a0953803e822957370044e4f5e73d3bd703852a502e54eaafb` | `6b1c16c5-8ad1-4661-a5ab-03005b4721a6` | 1800 |
| ui-circle-2500-native | `1896a8e323f35efae03eaa09a40fa5ef31683459fc6da6cf3bc6996936f72b68` | `8bdb4d17-0556-46fc-b8b8-1ed5dd42d421` | 1800 |
| ui-geometry-100-new | `888c92e2098e03b81838bd3d6438910551dc1b4a7f52e7f4904131395009a982` | `0231c973-4dff-4686-a91e-79dfac00244c` | 1800 |
| ui-geometry-100-native | `24bd95b6949814ee422185c88fdb4e393a48c3d93b4f2f74026a8271bc5f6278` | `ef36454f-2c6a-48a1-8678-75b12410536b` | 1800 |
| ui-geometry-1000-new | `df90cf5bf27ba440604edbe7f7a3b03f3a0627a9d99aabd605941558f39afd54` | `1fa9a5f8-d4fa-44eb-8ece-2a36ccaac05b` | 1800 |
| ui-geometry-2500-new | `bb1b1bfa4e6ad72270a0a86f451abcdad70c62d332e69a400bc10f4c05041f27` | `a0dc279e-ab5f-46be-98b6-a0e4457a8269` | 1800 |
| ui-geometry-1000-native | `15190da211e1076306b1fec68e8d7b89a4129df8d825dbed20f07f9e2f4f9d80` | `0f1b0cab-41ec-4724-bd6b-da5dcf22845f` | 1800 |
| ui-geometry-2500-native | `a4294c2ff60ef0896dfd040a0667a48b1d7f03e1248fcc9fd27f97adaf0d3871` | `4c1fbab1-f9f4-44d9-82fb-4c5de3f7886d` | 1800 |

## Completion checks

Read-only `python3 scripts/preview/host_validation.py status JOB --json` returns jobs array. For succeeded jobs verify evidence_json validationStatus=PASS, identityVerified/fixtureUnchanged/normalExit=true, cleanup=safe and original bound-cgroup kernelProof without errors (populated0 or destroyed). Then read `details.taskDir/turboism-home/state/atlas-validation/validation.json`; verify one invocation, actual branch, all current-page images, geometry, UI lifecycle fields, same paired input hashes, scale/placed count. Store full-precision rows and source job/hash paths, update three plugin READMEs alongside500 results, explicitly N=1. Any failure/timeout/unknown/quarantine is reported honestly without automatic retry or force release.

## Final outcome

All12 jobs are terminal: **10 succeeded, Geometry1000 native timed_out, Geometry2500 native failed before layout**. Host is idle. Full raw rows, input/output hashes, sourceEvidence and cleanup identities are in [host-ui-expanded-samples.csv](host-ui-expanded-samples.csv); terminal manager JSON retained under `build/atlas-ui-expanded/CASE.terminal.json`.500 remains a separate accepted four-row dataset.

| Dataset/count | Native entry ms | New entry ms | Native UI ms | New UI ms | UI ratio |
| --- | ---: | ---: | ---: | ---: | ---: |
| Circle100 | 264.4586 | 88.3663 | 511.3520 | 331.7776 | 1.54× |
| Circle1000 | 3979.5575 | 226.9234 | 4378.3728 | 579.8752 | 7.55× |
| Circle2500 | 18239.0310 | 375.8108 | 19311.8896 | 903.6158 | 21.37× |
| Geometry100 | 2266.4700 | 90.2465 | 2526.1596 | 341.4371 | 7.40× |
| Geometry1000 | incomplete | 711.1450 | task hard limit1800s | 1155.4422 | — |
| Geometry2500 | not triggered | 9670.4371 | pre-layout UI tree timeout | 10371.0697 | — |

All succeeded jobs meet complete manager acceptance and geometry checks. Completed pairs have identical input hashes and scale1/all placed; new Geometry2500 has scale0.97265625/all2500 placed. Input probe overhead is explicitly preserved in the CSV, not subtracted from raw UI times.

### Two non-PASS outcomes (no retries)

- Geometry1000 native job `0f1b0cab-41ec-4724-bd6b-da5dcf22845f`: `timed_out`,1800s persisted task budget; layout marker present, no final validation.json. Bound inode47585, `cgroup.kill+cgroup-destroyed`, original bound=true, kernel errors empty. No completed entry/UI time, no final scale/input snapshot or ratio. This budget includes startup, not1800s proven layout duration.
- Geometry2500 native job `4c1fbab1-f9f4-44d9-82fb-4c5de3f7886d`: `failed`; driver-error.txt is `java.lang.IllegalStateException: UI tree timeout`; result.txt reports FAIL; layout-started.txt and timing files absent. **Layout was not triggered.** Bound inode47754, `cgroup.kill+populated=0`, original bound=true, errors empty. Not a30-minute sorting timeout. Historical >2h cancelled native run is not changed or replaced.
- Both have validationStatus=FAIL, normalExit=false and aggregate identityVerified/fixtureUnchanged=false: not full acceptance. Post-containment source/copy fixture, official JAR/BAT and staged artifact hashes match their fixed inputs; aggregate missing validation must not be interpreted as proof the source changed. Task-owned safe cleanup is independently verified. Retained prefixes/evidence were not deleted.

User has received both the UI table and same-run entry-only table, including these limitations. Heartbeat `cab7e33e` was deleted after terminal verification/notification to prevent duplicates. Documentation/statistical recording is closed, but native1000/2500 UI acceptance remains incomplete; no new host execution is authorized by this record.
