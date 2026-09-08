# Circle / Geometry 500: OK action to progress-window close

User requested actual UI duration from starting layout with the button to the end of the layout progress window. Four fresh-host, one-invocation cases are authorized. No warmup or repeated-hot samples. Geometry2500 remains closed after user cancellation (>2 hours native); never rerun it for this matrix.

## Boundary and observer cost

Start: first ActionListener dispatched by the exact auto-layout settings dialog's OK button. This excludes file-command polling and `doClick()`'s simulated press delay. End: `SHOWING_CHANGED=false` on the same exact `jp.noids.framework.e.a.f` progress window observed showing after that action. No timer based on a polled UI tree or queue completion.

Read-only official Cubism 5.3.03 bytecode establishes `impl.v.a(settings)` → `util.ak.a(Function1)` → `impl.y` → `impl.v$a.run()` (input preparation, layout entry, overflow/list update, repaint, completion flag) → `util.an` → progress `h()`/dispose. Official JAR SHA-256: `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166`. Inspection transcripts: `/tmp/atlas-v-ui.txt`, `/tmp/atlas-y-ui.txt`, `/tmp/com.live2d.util.ak.txt`, `/tmp/atlas-progress-f.txt`.

The primary UI time is **raw instrumented wall time**. It includes separately measured input-snapshot/observer setup overhead (`uiInputProbeMs`). Output reflection, O(N²) geometry validation, hashes and JSON writes are deferred until after the exact progress window closes. Subtracting input-probe overhead is an estimate only, not an uninstrumented measurement. Method time retains the previous native entry/exit boundary. The UI boundary does not imply all GPU rendering is finished.

## Fixed inputs and artifacts

- Circle500: equal-sized circles; `test-assets/texture-atlas-layout/cmo3/circle/atlas_mapping_500.cmo3`, SHA-256 `54ce27647bd8d63d16fc643eb209ef2016fda0d975779b5b07943da303478b50`.
- Geometry500: varied sizes and geometric shapes; `test-assets/texture-atlas-layout/cmo3/geometry/atlas_mapping_geometry_500.cmo3`, SHA-256 `99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b`.
- Production Agent unchanged: `957aa623ac9ce6b62588a4c9538db68cc6ae613bf0de546c5ab0a5ce2afdcaa3`.
- Atlas plugin unchanged: `90af8240b0db5837c978e952d59ebefb9e0bf37d00a7227e8b48cf88d4a914ac`.
- Fixed auxiliary probe: `da71550e3209e344ba06cbd79c59ec71c45318f5543ece0d03140f2b93fa9bc2`.
- Same configured local DW-Proton runner / DISPLAY :0 / Cubism5303 as the Geometry queue matrix. Historical Circle hot timings used a different environment and must not be mixed with these samples.
- No production algorithm edits for this measurement. 47 offline probe assertions, scheduler/bundle gates and 43 queue tests passed before submission. All four inputs prepared and inspected before the first host measurement; no builds during measurement.

## Queue ledger

| Case | Prepared ID | Job ID | State |
| --- | --- | --- | --- |
| Circle500 new | `db3f3afab06dd1d42bf94adf886bd50ff08019f15e87848f5c5a35281b163c09` | `f0378ad4-7f25-4faa-ace8-badb43391e1f` | succeeded / full PASS |
| Circle500 native | `5271846da88d76e9b04e5bd529061978acb84c06772832642906f6d755f83428` | `69038cd4-d41a-4776-ae4f-2ac5687ca8dd` | succeeded / full PASS |
| Geometry500 new | `1cc76a7b52a21454d4f60c34b9c646e6bb6d5d3e4de1c0c62a9f0ffca077908d` | `16dff3bd-aeeb-46a2-a4d4-4e40e84a46b4` | succeeded / full PASS |
| Geometry500 native | `8a7a2669bc50c6f510828070496d8c257efa8d8c3d7d9010228443aff501f209` | `3963ad0f-06c5-4545-a59e-0d5e252d77fc` | succeeded / full PASS |

Request keys: first `atlas-ui500-circle-new-v1`; others `atlas-ui-circle-500-native-v1`, `atlas-ui-geometry-500-new-v1`, `atlas-ui-geometry-500-native-v1`. Do not resubmit completed or unknown attempts automatically.

The existing main persistent worker owns FIFO execution and cleanup. All jobs use local `host_validation.py prepare/submit/status/wait`; no SSH, direct host launch, custom hook or worker changes. Queue completion is not itself enough: verify validationStatus, exact identity, unchanged fixtures/artifacts, normal exit and bound original cgroup proof before publishing a row as PASS.

## Results (milliseconds; one invocation each)

| Case | Entry | Raw UI | Input probe included in UI | Entry return → progress close |
| --- | ---: | ---: | ---: | ---: |
| Circle500 new | 175.4170 | 596.8814 | 42.6029 | 217.3363 |
| Circle500 native | 1157.4114 | 1527.4977 | 25.7645 | 176.2360 |
| Geometry500 new | 386.2683 | 746.3221 | 17.0149 | 183.8690 |
| Geometry500 native | 267829.4619 | 268164.2065 | 16.0731 | 145.0645 |

All four jobs passed validationStatus, identityVerified, fixtureUnchanged, normalExit and safe original-bound-cgroup cleanup. Each pair placed all500 at scale1, with identical actual input hashes and no overlap/out-of-bounds/LayerRef mismatch. Circle page4096², input `fbc415fd0ef9271fb763ea274baff110776375d1d1f28d40855a684d0e98a93a`; Geometry page8192², input `715429961888bf58875e07671b8a4e5d5aebfedd907bdbee56aaf1a35369424d`.

Circle UI speedup **2.56×** versus same-run entry **6.60×**. Geometry UI speedup **359.31×** versus entry **693.38×**; native UI 4min28.1642s. New UI time outside entry is421.4644/360.0538ms respectively, including measured probe cost. No uninstrumented latency claim.

[Four raw rows](host-ui-500-samples.csv) retain full precision, prepared/job IDs, input/output hashes, exact sourceEvidence and cgroup inode/cleanup method. Circle new/native inodes46415/46467; Geometry new/native46519/46571. Native normal Exit was verified in every case; any residual processes were cleaned only by the manager in the original bound scope. Raw JSON is at each manager `evidence_json.details.taskDir/turboism-home/state/atlas-validation/validation.json`; lifecycle and bound-cgroup evidence are retained under `~/.local/state/turboism/host-validation/jobs/JOB/`.

Subsequent user instruction authorizes a separate UI matrix for Circle/Geometry100/1000/2500, with native task hard timeout30minutes including startup. This does not alter the historical Geometry2500 >2h cancellation or any500 measurement here.
