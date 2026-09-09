# Authorized 500-item new serial/parallel matrix after pin fix

User requested500 after the accepted four100 cases. Same scope: Circle500 and Geometry500, new algorithm serial/parallel once each; **no native rerun**, no other sizes. Manager hard timeout1800 per task including startup, excluding queue time. All four jobs already submitted once; no reprepare/resubmit/retry/key changes or unqueued launches. Production artifacts identical to accepted100; only the fixed auxiliary probe admits500 parallel now. No temporary admission observer installation, worker/Runner/hooks changes or new dependencies. Probe50 assertions and scheduler focused gates PASS, syntax/diff checks PASS. All snapshots prepared and verified before first submission; no builds during measurement.

Circle uses same-size circles, Geometry heterogeneous shapes/sizes. Expected current texture dimensions: Circle5004096×4096, Geometry5008192×8192 px; verify actual dimensions from results, not PSD canvas.

## Artifact hashes

- Agent: `a81838b36bb369a6e633779ead8fddf4904ee56fc0cc0931d2b74bda0b8efbf2`
- Plugin: `e5ffd6060d4284493fb4d6bca3babb00e3430a8f8368c40e90320e7494ca3999`
- Probe: `e87ba8d2efa4989c49a5549b4dbe049e58145b23a1f4000c553a50a73da86ffb`
- Circle fixture: `54ce27647bd8d63d16fc643eb209ef2016fda0d975779b5b07943da303478b50`
- Geometry fixture: `99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b`

## Immutable jobs

Full prepared IDs, request keys, source hashes and submit responses: `build/atlas-ui-500-pin-fixed/jobs.json`. Request keys are `atlas500-q-pin-fixed-v1-CASE`.

| Case | Job |
| --- | --- |
| ui-circle-500-new | `a8f1f2d7-c712-43da-a0fe-df1414581a1f` |
| ui-circle-500-new-parallel | `6ea9e8b7-4940-4239-bb25-f85410e13af1` |
| ui-geometry-500-new | `1a2ff0a8-4296-417f-9c6a-cb752e23dfbc` |
| ui-geometry-500-new-parallel | `e7a77f5f-f563-4218-a853-3af5c7d893fa` |

## Acceptance criteria (all met)

Use read-only manager status. For each succeeded job verify authoritative validationStatus PASS, identityVerified/fixtureUnchanged/normalExit true, safe original-bound-cgroup cleanup and kernel proof without errors (populated0/destroyed), plus official/fixture/staged hashes. Read exact details.taskDir/turboism-home/state/atlas-validation/validation.json: handled, one invocation,500 full inputs/outputs, expected plannerParallel, geometry, scale, overflow, actual page dimensions and paired input hashes. Record methodMs, raw uiActionToProgressClosedMs and included uiInputProbeMs; output verification must follow progress close. Preserve sourceEvidence, job/prepared/request IDs and all hashes in CSV. N=1; do not generalize timing or calculate a newly measured native speedup without a new native sample.

On all terminal outcomes update three-language README and this report, preserve original500 UI and earlier entry-only tables plus accepted100 results. Failures are not PASS and have no fabricated duration/ratio; no automatic retry. User notification is automatic; delete monitoring heartbeat after reporting. Task28 stays in_progress until acceptance.500 does not exercise <32 preflight; q!=1 remains offline-tested.

## Final result: four PASS

All four terminal jobs independently verified: validationStatus PASS, identityVerified/fixtureUnchanged/normalExit true, original-bound-cgroup cleanup safe with kernel errors empty and populated0/destroyed. Official JAR/BAT, fixture source/copy and staged artifacts match their recorded hashes. Raw terminal responses: `build/atlas-ui-500-pin-fixed/CASE.terminal.json`. [Full-precision CSV](host-ui-500-pin-fixed-samples.csv) preserves all clocks, packing threads/calls, geometry summary, hashes, job/prepared/request IDs, exact sourceEvidence and cleanup identity.

| Dataset | Texture px | Mode | Entry ms | Raw UI ms | Included input probe ms |
| --- | --- | --- | ---: | ---: | ---: |
| Circle500 | 4096×4096 | serial | 354.2930 | 705.4973 | 47.0378 |
| Circle500 | 4096×4096 | parallel | 418.3253 | 911.0772 | 57.4994 |
| Geometry500 | 8192×8192 | serial | 477.5247 | 808.9469 | 19.8730 |
| Geometry500 | 8192×8192 | parallel | 461.0903 | 869.6254 | 20.0660 |

Each single handled invocation placed500/500 at scale1, overflow empty, finite/inside/layerMatch true and overlaps0. Observed plannerParallel matches request; parallel runs contain actual ForkJoin worker threads. Paired input hashes match: Circle `fbc415fd0ef9271fb763ea274baff110776375d1d1f28d40855a684d0e98a93a`, Geometry `715429961888bf58875e07671b8a4e5d5aebfedd907bdbee56aaf1a35369424d`.

Geometry parallel entry time is about3.4% lower, while its raw UI time is7.5% higher. Circle parallel is slower for both entry and UI. N=1 fresh-host measurements do not establish statistically stable gains; keep serial default. Input observation costs remain included, never subtracted; output verification follows exact progress close. No native rerun or newly measured native ratio. Accepted100 and all older native/entry/UI tables remain unchanged. This completes only the authorized500 matrix, not historical1000/2500 gaps or q!=1/<32 host coverage. Three-language README updated; final notification and heartbeat removal follow.
