# T029 final-artifact host evidence, 2026-10-01

## Verdict

The six final-artifact legs passed the standard host gates, scene payload binding,
four ordered edge digests per pair and exact original/patched class SHA checks.
The real settings UI and subsequent restart activation/deactivation passed.
Performance acceptance is still open: the first 5203 pair did not show a gain,
and SC-04a retention/repetition windows and approved resource limits remain outstanding.
No main merge or broad speedup claim is made.

## Artifact and UI binding

- Production agent: `4c32672877b8bfd6e6565e1cdf1a4efaf0592aaa5810acdfe4c69e42fc7dbc47`.
- Driver: `a202a21b315f9e5d1b9b5fe14b19551a5f3d52ce69da6cd13590af6c71763b11`.
- Capture agent: `f0b11990c6c8f39710ea2352ac7f3dc6fed274d5136c460b9e9f2f5e96a37e70`; all legs use `tl-dump-only`.
- Fixture: reviewed `heavy.cmo3`, SHA `029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`.
- UI PASS: seq2025, job `355fecc7-a031-4562-93f2-64cdc375c500`.
- UI off/on config SHA: `8945ee224b724641a5b468cf087be0287cb3908bca908c7abdc0c582bec9b846` / `190ea2fcf912c290a4db40ccd512becf4d3f032098f9cbeca339c2f0903cce02`.
- Those configs differ only in `meshTriangulationEdgeIndex`. All six prepared inventories and config bytes were checked before submission.

The prior UI failure seq2013 remains failed. The probe had called the active-model
read API without declaring its required permission and swallowed the exception as
not-ready. Adding the read-only permission resolved the exact real-host scenario;
timeouts now retain the last exception. No production code changed for this fix.

## Host jobs

| Version / leg | Sequence | Job |
| --- | --- | --- |
| tlprod-5203-off | 2035 | `281b2ee4-46a2-40f3-bf7c-0e18105bd395` |
| tlprod-5203-on | 2036 | `925e5890-0985-449c-ad44-7df66c2d2ae6` |
| tlprod-5302-off | 2037 | `8044269f-6d7e-44e8-a32a-91b63624721a` |
| tlprod-5302-on | 2041 | `324885c1-26e7-49c8-bf2d-87b09844f121` |
| tlprod-5303-off | 2044 | `18a38199-4191-4724-a8a9-7ecddbdd81dc` |
| tlprod-5303-on | 2046 | `b4747f56-9284-4758-af21-c4725ce9d8f0` |

Every row has `validationStatus=PASS`, `cleanup=safe`, `normalExit=true`,
`identityVerified=true`, and `fixtureUnchanged=true`. Each canonical result hash
and payload hash/run identity was independently checked after completion. All
three on legs contain production bridge execution samples in JFR.

## First-pair resource observations

Metrics are Linux Java-process observations from the Runner ready identity until
process exit, sampled at about 1 second with PID/start-tick checks. CPU seconds are
the delta of Linux user+system ticks. RSS is the maximum observed process RSS.
Heap is the maximum observed JFR GCHeapSummary value, not an exact continuously
observed heap peak. The triangulation span is bounded by first/last matching JFR
samples, not explicit operation timestamps. Queue waiting is excluded.

| Version | Leg | Observed process wall s | CPU s | RSS MiB | GC-observed heap MiB | Triangulation sample span s |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 5203 | off | 256.8 | 302.3 | 5080 | 4122 | 91.6 |
| 5203 | on | 278.4 | 313.2 | 4194 | 3527 | 96.1 |
| 5302 | off | 135.7 | 249.3 | 5027 | 3998 | 50.9 |
| 5302 | on | 139.9 | 263.0 | 4611 | 3507 | 46.2 |
| 5303 | off | 135.8 | 256.0 | 4851 | 3767 | 50.0 |
| 5303 | on | 128.6 | 250.6 | 4601 | 3582 | 44.6 |

The 5302/5303 query/iterator hotspot shrank with production indexing; the whole
process CPU total does not uniformly improve. On 5203 the first sampled span
increased (91.6 to 96.1 s), and JFR attributes 571 `l.equals` leaf samples to
`TriangulationEdgeIndex.remove`, with a further 1517 under HashMap TreeNode.find
without a visible bridge frame. This is attribution, not proof that all observed
difference is causal: JIT, GC and run ordering remain confounders. The reversed 5203 on leg (seq2049) passed; its off leg (seq2050) was
cancelled at the higher-version model warning before the operation. That incomplete
pair is excluded from performance comparisons.

SC-04a is not complete: this capture lacks PSS, separate auxiliary-process costs,
a fixed post-operation retention window and repeated operations within one JVM.
It cannot prove absence of long-session retention growth. No CPU/RSS regression
threshold has been invented or treated as approved.

## Evidence locations

Authoritative outcomes and original inputs remain under the shared queue job/prepared IDs.
The task-local review artifacts are `build/t029-real-host-acceptance/`:
`final-ab-runs.json`, `final-ab-analysis.json`, per-leg `*-outcome.json` /
`*-process.csv`, `5203-equals-attribution.json`, and verified UI result/configs.
JFR and original capture files are retained in each outcome’s `details.taskDir`.
Successful task prefixes were removed by the normal supervisor; evidence remains.

## 5203 higher-version load warning follow-up

Seq2050 / job `517571a3-1891-4341-b280-6180de6d2a4e` ended `cancelled`,
`cleanup=safe`. Post-containment hashes confirmed both heavy fixture source/copy
unchanged. It is not a successful host validation.

The screenshot shows the 5.2.3 / 5.3.0 compatibility warning. Static inspection of
5.2.03 `UUSerialize` confirms a modal JOptionPane with exact Chinese resource
text COR3-0327..0333, title `警告`, and `加载` / `取消` options. The driver now
answers only that exact form owned by a unique 5.2.03 main window, only for the
explicit TLPROD token and fixed heavy filename/SHA. It queues one load action,
records `startup.higherVersionLoadAcknowledged`, and does not save the model.
An unexpected owner, version text or control shape refuses.

Offline selfcheck: 182 checks PASS, including missing token, wrong fixture
name/hash, wrong host version, altered warning text, wrong owner, duplicate load
button and repeat-poll click suppression. Driver SHA:
`b30553349bed341442813f1788cb276c353dc0177e896a4897b73844683da2d6`.
Production agent remains `4c326728…`; original bundle/evidence retained.
The new paired run and read-only Java/cgroup resource observer are under
`build/t029-real-host-acceptance/load-warning5203/`.

The first new-driver leg seq2051 / `e5dd948b-d2a6-468b-b2af-7f3e53ad1807`
failed closed before any editor action: `higher-version warning has unexpected
owner/modality`. Cleanup was safe and post-containment fixture hashes unchanged.
No off leg was submitted. Driver `b3055334…` is therefore not host-accepted.
A diagnostics-only follow-up (`cbc5b21a…`) retains the same strict gate and records
owner class/title and modality on refusal. PSS collection succeeded on seq2051,
but those startup-only samples are not used for a performance verdict.

Seq2052 / `1188ffa2-2cff-4232-83c7-8730af81559c` confirmed
`modal=true owner=javax.swing.SwingUtilities$SharedOwnerFrame ownerTitle=`
with main class `com.live2d.ui.window.CFr`; it failed closed and cleaned safely.
The reviewed startup form is now allowed with either the unique 5.2.03 main in
its owner chain or exactly Swing's hidden, untitled, ownerless SharedOwnerFrame.
Application modality and all fixture/text/button gates remain mandatory.
Offline selfcheck: **184 PASS**, including the shared-owner positive case and
an unrelated-frame negative case. New driver SHA:
`cad03995d1971b9bf28e83230aa1be25e89849de1e1da9417cc2642a369d964c`.
Frozen pair inputs and evidence: `build/t029-real-host-acceptance/shared-owner5203/`.

Seq2053 / `77969f5c-2d09-4b33-b9e5-e83f2286a532` passed the owner gate but
refused the full-window control count. Seq2054 / `fb8c4fa4-504c-4ec8-9a98-81140cc65eae`
(diagnostics driver `2f6be561…`) isolated the cause: the first message matches all
271 characters exactly; the full window contains two text labels and three buttons
(`加载`, `取消`, and a blank title-bar close control). Both failed before clicking
and cleaned safely. The scanner is corrected to the JDialog content pane so the
host's root-pane decoration cannot be treated as an option control; a decorated
shared-owner synthetic case covers the actual failure shape.

## Repaired-driver 5203 pair (on then off)

Driver `cfc099771845e8a6ad4582ac4849fd56131cece6ee6ab6e7f9234b798c4308e5`;
186 offline selfchecks PASS. Both actual host legs acknowledged the reviewed warning,
completed the standard gates, exited normally, preserved fixture hashes and cleaned safely.

- on: seq2055 / `e001951e-9ad7-400e-8918-4397c59d48ad`
- off: seq2056 / `66197270-8b40-47c4-add3-2eb8084032dc`

Canonical/payload binding, expected pristine/patched class SHA, all four ordered edge
digests and production JFR execution passed. Inputs and full analysis are retained in
`build/t029-real-host-acceptance/content-pane5203/`.

| Leg | Java observed wall s | CPU s | RSS MiB | GC-observed heap MiB | Triangulation sample span s |
| --- | ---: | ---: | ---: | ---: | ---: |
| off | 173.1 | 287.8 | 4231 | 3235 | 87.1 |
| on | 181.3 | 308.4 | 4504 | 3334 | 92.0 |

Separate 1-second `/proc`/smaps_rollup observation (sample alignment differs slightly
from the original shell collector):

| Leg | Role | CPU s | Concurrent RSS peak MiB | Concurrent PSS peak MiB |
| --- | --- | ---: | ---: | ---: |
| off | host-java | 286.44 | 4235.36 | 4174.41 |
| off | task-auxiliary | 19.02 | 700.20 | 313.41 |
| on | host-java | 308.10 | 4518.71 | 4451.35 |
| on | task-auxiliary | 19.95 | 694.97 | 304.68 |

All PSS reads succeeded. Auxiliary rows include only identities from the Java process's
verified task cgroup; lifetime CPU between missed births/exits is not captured, so those
CPU totals are sampled lower bounds. Detailed user/system and average/peak CPU percentages
are in `resource-analysis.json`; 100% means one logical core.

The repaired-driver pair again does **not** support 5203 performance acceptance:
on increased observed Java CPU by about 7.2%, RSS by about 6.5%, and triangulation
sample span by about 5.7%. This repeated direction warrants further investigation;
it is not a precise causal effect estimate. The on JFR leaf profile still includes
1995 `l.equals` samples versus 21 off. Exact operation baselines, fixed post-operation
retention windows and repeated same-JVM retention remain outstanding. No approved
resource regression threshold or blanket production readiness is claimed.

## Native-remove candidate 640e3b1e (not performance accepted)

Production commit `0376a1a25` preserves native set removal before reconciling the
index. Regression first reproduced two old-code failures: 212 versus native 85
equality calls, and a different actual removed identity when existing equalities
mutate in a treeified bucket. Helper 17 tests, affected mesh/transformer/bootstrap
checks and devCheck passed; the packaged helper was inspected with javap.

Candidate SHA `640e3b1e3e65e3a2c0df6b8e7b8a25a205ecc8543d76b14e2bfa137e443b4e4a`:

- on seq2063 / `d3d78a38-7cb5-4ffc-8787-0b9c846464c7`
- off seq2069 / `000fef29-e1d3-4a57-9f60-a085b5750195`

Both standard gates, normal exit, unchanged fixtures and safe cleanup PASS.
Canonical/payload binding, four ordered edge digests, expected class SHA and
production execution samples PASS. Driver remains `cfc09977…` and acknowledged
the reviewed warning on both legs. FIFO jobs separated the pair; do not treat
small differences as a precise causal estimate.

| Leg | Observed Java wall s | CPU s | RSS MiB | GC-observed heap MiB | Triangulation sample span s |
| --- | ---: | ---: | ---: | ---: | ---: |
| off | 183.5 | 305.6 | 4907 | 3796 | 92.2 |
| on | 206.4 | 333.8 | 4350 | 3224 | 108.8 |

The separate cgroup observer measured Java PSS peaks off/on 4919.70/4296.63 MiB,
and auxiliary CPU off/on 20.42/22.38 seconds (sampled lower bounds). PSS reads all
succeeded. All values and user/system CPU breakdowns are in
`build/t029-real-host-acceptance/native-remove5203/{final-ab-analysis,resource-analysis}.json`.

This candidate is **not performance accepted**: about +9.2% observed Java CPU and
+12.5% observed wall time, despite lower memory. The on triangulation leaf profile
includes 806 `IdentityHashMap.containsKey` samples. A follow-up removes repeated
identity lookups for each survivor, relying on the statically reviewed host mutation
protocol and retaining an identity-only survival check plus dirty rebuild fallback.
That follow-up requires its own frozen artifact and host comparison.

## Follow-up identity-only survivor scan (one favorable pair; stability pending)

The per-survivor identity-map lookup has been removed. With all host add/clear
sites woven and iterator removal the only unwoven write, a clean state with matching
pre-removal cardinality already establishes recorded membership; only an identity
survival check is needed after native removal. Equal-but-distinct native victims still
force a rebuild. Arbitrary unwoven same-size add/replace sequences are outside this
reviewed mutation protocol. See DESIGN.md for the scope of the proof.

Affected mesh tests, devCheck and agent build PASS (101 tasks). New production SHA:
`29f13cf8bd0cb563c384dedef095270b5e165b4f223f9a014213dc0fd1ba3813`.
Driver remains `cfc09977…`. Prepared IDs:

- on `5df28314271b163a1941ffcbb3487d64e0e19b7ae360699573b915ea0c89a4b7`
- off `e5e30646e0cc6d3291a7eda8544b88feaee955454d3a2087f11359462962c9a4`

Completed on seq2080 / `e8ab1099-fc06-4b04-835b-2146701a16d2` and off
seq2085 / `8475b5b4-497d-41ec-b3af-c720080976a8`. Standard gates, normal exit,
fixture integrity, safe cleanup, canonical/payload binding, four ordered edge
digests, expected class SHA and production JFR execution passed.

| Leg | Observed Java wall s | CPU s | RSS peak MiB | Triangulation sample span s |
| --- | ---: | ---: | ---: | ---: |
| off | 210.633 | 350.45 | 4443.12 | 99.345 |
| on | 189.749 | 308.68 | 4630.93 | 95.102 |

Whole observed process wall decreased 9.915% and CPU decreased 11.919%, while RSS
increased 4.227%. The sampled triangulation window CPU was 104.41 s on versus
112.60 s off. These are one pair's observations with a FIFO gap, not proof of a
stable causal gain. The on leaf samples still include 1532 `l.equals` and 462
`TriangulationEdgeIndex.remove` samples; the remaining native removal and identity
scan costs have not vanished.

Authoritative data: `build/t029-real-host-acceptance/identity-scan5203/`
`completion.json`, `final-ab-analysis.json`, and `resource-analysis.json`.
`performanceAcceptance` remains `NOT_DECIDED`.

Two additional pairs reuse the identical prepared inventories and UI JSONs:
`identity-scan5203-r2` (off→on) and `identity-scan5203-r3` (on→off).
The task client sequences submission through the unified queue and only analyzes
after all four performance legs finish. At this update r2 off seq2180 and on seq2181 both passed standard gates; r3 on
seq2185 is submitted. Preliminary r2 process metrics (before canonical/edge/JFR
analysis): off/on wall 193.967/184.695 s, CPU 317.50/306.45 s, RSS peak
4056.95/4470.19 MiB. Thus wall −4.780%, CPU −3.480%, RSS +10.186%. These
smaller time savings and larger memory costs still do not establish acceptance.
Raw CSV hashes and calculations are retained in r2 `preliminary-process-analysis.json`.
Remaining SC-04a measurement work is in
[resource acceptance plan](resource-acceptance-plan.md). New-candidate 5302/5303
and real UI revalidation remain open. No main merge or production acceptance.

## Three-pair stability result for 29f13cf8 (not accepted)

Both repeat pairs completed canonical/payload, four ordered-edge, class identity and
production execution checks. r2 jobs seq2180/2181 and r3 jobs seq2185/2191 all passed
standard gates, normal exit, unchanged fixtures and safe cleanup.

| Pair/order | Off/on wall s | Off/on CPU s | Off/on RSS peak MiB | Wall delta | CPU delta | RSS delta |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 on→off | 210.633 / 189.749 | 350.45 / 308.68 | 4443.12 / 4630.93 | −9.915% | −11.919% | +4.227% |
| 2 off→on | 193.967 / 184.695 | 317.50 / 306.45 | 4056.95 / 4470.19 | −4.780% | −3.480% | +10.186% |
| 3 on→off | 182.463 / 193.375 | 311.01 / 331.08 | 4293.62 / 5125.86 | +5.980% | +6.453% | +19.383% |

The third pair reverses the apparent speed benefit, and all three sampled RSS peaks
increase. Stable effectiveness is **not established**. FIFO gaps and startup/GC
variation prevent interpreting these whole-process observations as precise target
costs. Triangulation JFR sampled spans on/off were 95.102/99.345,
95.713/94.955, and 96.989/94.689 seconds: the target-window direction also fails to
show consistent improvement. On `l.equals` leaf samples remain 1532/1656/1628 and
index remove samples 462/473/472; further attribution is needed, not a theoretical
bottleneck claim.

All data remains under the three named pair directories. The consolidated
`build/t029-real-host-acceptance/identity-scan5203-three-pair-summary.json` pins
analysis hashes. No production change was made during measurements.

The new explicit resource driver passed 216 offline selfchecks; driver SHA
`3bee41a353df55f20da9845052b7a90f7380ced8bca7c46f8251fbe48785209e`.
Its fixed baseline/operation/retention windows and three-cycle host measurements
remain pending wrapper regression and real execution; these tests do not confer
performance acceptance.

Resource wrapper regression passed after supplying the reviewed identity probe
SHA `9c4a4ccc…` (the first attempt stopped for a missing test input, retained in
`resource-wrapper-regression.log`; successful run is `resource-wrapper-regression-r2.log`).
The new 5203 resource pair is prepared and submitted via task-local
`resource-windows5203/`, keeping production `29f13cf8…` unchanged. Prepared IDs:

- off `2d7a7d20da0a0b6514b196af92d41ea20a0d1d74cab39fbd15b49ec8b7a8e513`
- on `2cdabf11b0d8a7df4adf54dbfc54941fc643dab3350b641425cc273d16d502df`

Full-stack diagnostic note: `jfr print` defaults to five frames. The historical
sample-span/leaf reports used that limit; do not treat missing deeper frames as
absence of target execution. Re-export of r3 with `--stack-depth 64` attributes
on `l.equals` leaf samples to HashSet add 696, contains 669, remove 254,
iterator remove 8, and Intrinsics equality 1; off counts are 19/15/6/1/4.
The official 5203 triangle `hashCode()` returns constant zero, consistent with
collision-tree search stacks. This identifies an attribution target, not a proven
cause or theoretical limit. New resource analysis uses 64 frames explicitly.
Whole-process CPU, wall, RSS and edge comparisons are unaffected by print depth.

## Resource protocol first execution: incomplete (seq2200)

Off job `dac65550-aa5d-42e6-ae0c-561f85554dd4` failed with
`host exited before terminal result`. Cleanup is safe, timeout is false, and the
supervisor did not write cgroup.kill. No on leg was submitted. Baseline lasted
30.007 s; native operation windows lasted approximately 137.085, 16.317 and
16.304 s. Third retained-start exists, but third retained-end and canonical result
do not. These partial observations are **not an accepted resource comparison**.
Post-containment hashes match fixture, official BAT/JAR and staged agents, but
whole-run terminal gates remain FAIL. Do not relabel this as PASS.

The cloned prefix's hs_err_pid328.log is dated 2026-08-03 and describes a startup
failure; it is historical, not evidence for this run. No cause has been proven for
the early exit. First-run outcome, markers and process samples are retained in
`resource-windows5203/`, with `failure-analysis.json`. A single retry reuses the
same immutable prepared IDs under `resource-windows5203-r2/` through the queue.

Further read-only failure inspection: the first resource run's `atlas-profiling.jfr`
is empty, so shutdown/caller events cannot be recovered from it. User systemd
journal reports this exact task scope used a 7.5G memory peak and 2.7G swap peak
before destruction at 05:10:50 UTC. Queried kernel/oomd logs provided no explicit
OOM evidence; resource pressure is a hypothesis, not an established exit cause.
Retry seq2211 (`e8cb251e-f231-435a-bf81-fe918de7adfd`) adds read-only per-sample
cgroup memory.current/swap.current/events/pressure and host available memory to
both legs' collectors. Production, driver, fixture and UI configs remain unchanged.

Retry off seq2211 completed standard gates, normal exit and safe cleanup. All
four idle windows and three native operation windows are present. Read-only pressure
collector captured 372 samples: cgroup memory peak 7165.98 MiB, swap peak
2690.57 MiB, minimum host MemAvailable 1234.57 MiB; all observed memory.events
counters (including oom/oom_kill) remained zero. This successful run shows that
similar high occupancy alone does not explain the first exit. It does not rule out
unsampled/global events in the failed run. Summary: r2 `off-pressure-summary.json`.
On submitted as seq2215 / `ccf4476c-ecdb-4412-b60f-cac4eac48b52`; full paired
resource analysis remains pending, so no acceptance conclusion is drawn here.

## Completed explicit-window pair (seq2211 off / seq2215 on)

Both legs passed standard gates, canonical/payload binding, first-four ordered edge
digests, exact class identity and production execution verification. All baseline,
operation and retained windows are present. Agent remains 29f13cf8; driver 3bee41a3.
Reports and input hashes are in `resource-windows5203-r2/*-window-analysis.json`.

| Window | Off/on wall s | Off/on Java CPU s | Off/on Java RSS median MiB | Off/on Java PSS median MiB |
| --- | ---: | ---: | ---: | ---: |
| baseline | 30.012 / 30.009 | 1.28 / 0.76 | 2954.59 / 2424.04 | 2891.53 / 2360.76 |
| operation 1 | 152.017 / 138.690 | 197.29 / 177.76 | 2945.72 / 2795.88 | 2892.49 / 2732.88 |
| retained 1 | 30.001 / 30.001 | 1.08 / 0.65 | 4429.39 / 4760.16 | 4378.99 / 4699.20 |
| operation 2 | 23.045 / 14.009 | 31.10 / 24.01 | 4452.12 / 4883.87 | 4384.61 / 4838.18 |
| retained 2 | 30.002 / 30.001 | 2.31 / 1.13 | 4379.72 / 5283.88 | 4333.70 / 5233.29 |
| operation 3 | 18.655 / 15.354 | 23.82 / 24.61 | 4532.50 / 5478.87 | 4487.28 / 5428.79 |
| retained 3 | 30.001 / 30.001 | 18.69 / 0.95 | 4678.29 / 5729.93 | 4632.98 / 5679.72 |

First operation wall decreases about 8.77%, sampled CPU about 9.90%. Operation 1
has 5589 off / 5313 on triangulation samples, including 2368 production-index
samples on. **Neither operation 2 nor 3 has observed triangulation on either leg.**
Thus this is a repeated UI-operation test, not verified repeated-triangulation
retention evidence. The tiny off baseline/last-retained counts (2/1 samples) must
not be reclassified as full target operations.

Retained Java RSS median grows from first to third window by 248.90 MiB off and
969.76 MiB on. On ends with higher RSS/PSS but lower boundary heap used
(3234.67 versus 4276.85 MiB); heap samples, GC timing, native allocations and swap
are different measurements, and none alone establishes an index leak. First-operation
RSS peaks are 4359.21 off / 4753.01 MiB on. Timing boundaries are explicit, but CPU
samples omit small boundary intervals documented in each report. No numeric
resource tolerance has been approved; stable effectiveness remains unproven in
light of the three earlier pairs' inconsistent direction. `pair-verdict.json`
records these limits; no main merge or theoretical-bottleneck claim.


## Contains candidate explicit-window pair (seq2219 on / seq2226 off)

Candidate agent `77ce425567f8a4fb1ef1c5055fe5015a33c1cf1dcefbc90dcfff9c0bbeebd17c`
(commit `e3df8865b`), resource driver `3bee41a3`. Both legs succeeded with normal
exit, safe cleanup and unchanged fixture. Full analysis verifies the first four
ordered edge digests, class identity and production-index execution. Evidence:
`build/t029-real-host-acceptance/contains-resource5203/{completion.json,final-ab-analysis.json}`
and each `*-window-analysis.json`; these pin the raw inputs.

| Window | Off/on wall s | Off/on sampled Java CPU s | Off/on Java RSS median MiB |
| --- | ---: | ---: | ---: |
| baseline | 30.013 / 30.005 | 1.65 / 0.71 | 2870.26 / 4510.27 |
| operation 1 | 131.958 / 136.850 | 158.78 / 167.45 | 3053.94 / 4352.03 |
| retained 1 | 30.001 / 30.001 | 1.05 / 1.40 | 4697.04 / 3552.11 |
| operation 2 | 12.943 / 31.517 | 19.21 / 27.58 | 4959.29 / 3583.15 |
| retained 2 | 30.002 / 30.003 | 0.62 / 2.01 | 5403.09 / 3130.79 |
| operation 3 | 17.545 / 28.674 | 21.30 / 21.63 | 5481.66 / 3849.96 |
| retained 3 | 30.001 / 30.001 | 2.44 / 21.11 | 4817.56 / 4899.22 |

First operation is about 3.71% slower with 5.46% more sampled Java CPU on.
This pair does not demonstrate a benefit. Baseline RSS differs substantially;
first-to-third retained RSS grows 120.52 MiB off / 1347.11 MiB on, while final
retained medians differ by only 81.66 MiB. Do not infer an index leak or a causal
memory regression from growth alone. Neither later UI operation has target JFR
samples on either leg. Operation 1 has 4361 off / 4717 on target samples, with
2824 production-index samples on; these are samples, not invocation counts.

Stable effectiveness remains unproven. Full caller attribution is being analyzed
before selecting the next change; the sampled `HashSet.contains` hotspot alone
does not establish that the woven `TriangleList.c` entry is its caller. This pair
is retained as measured, not replaced by a favorable historical result.


Caller attribution completed: `contains-membership-attribution.json` streams all
four first-operation recordings with 64-frame stacks and pins each exported JSON.
All 1173 sampled native contains stacks on the older on leg go through
`TriangleList.c`; all 690 on the new on leg go through both `TriangleList.c` and
`TriangulationEdgeIndex.contains`. Thus the chosen entry is on the actual hot
path, rather than an unrelated contains caller. Of 694 samples containing the
helper, 690 also contain native contains. This is time-biased sampling, **not**
a 99.4% fallback invocation rate; a cheap successful shortcut is less likely to
be sampled. New on first-operation sampled native add/contains/remove counts are
1093/690/507, versus 898/776/442 off. Counts across runs do not establish cost
reduction. Equality leaf differences may reflect JIT inlining and attribution,
not a corresponding change in equality invocation count.

Next diagnostic must distinguish clean identity hits from unknown identities,
dirty/cardinality fallback and dead states with bounded observation in a separate
diagnostic artifact. Keep the production candidate frozen; do not reinterpret
instrumented timings as its acceptance result. No further optimization is justified
solely by the 694/690 sample ratio.


## Contains branch diagnostic queued (not acceptance)

The standalone builder under `diagnostic/` pins the production source and base
agent, adds fixed-size reason/outcome counters in a generated copy, and leaves
production source unchanged. Both generated-class and packaged-jar selfchecks
pass 36 assertions and exact shutdown JSON counts. Only the outer helper class is
replaced; all original remaining jar entries are byte-identical.

Diagnostic agent SHA: `42a4282bc67cd70183e95d4dd4d988da6726de53b03974f039785aa8d37b5528`.
Prepared: `bcb382bd89875b592ca6d4e2e57438d147d72906a218ea7a8ab8e9dd1b087cb1`.
Queue seq2234, job `caba0c55-e949-4d5c-bd5e-b7c601964be5`, request
`t029-contains-branch-diagnostic5203-v1`. Specific status confirms queued with
worker online. Output root: `build/t029-real-host-acceptance/contains-diagnostic5203`.
No real diagnostic counters are available yet. Preserve normal exit, fixture and
capture checks before interpreting aggregate counts. This instrumented artifact
and its timing cannot substitute for final production performance acceptance.


Static caller follow-up while seq2234 waits: reviewed 5203 `h.a(l,l,j)` creates
new triangle instances at bytecode offsets 89 and 106 (locals 7 and 8), then
queries those instances with `TriangleList.c` at offsets 251 and 269 before each
conditional add. The first contains call on each fresh identity cannot hit the
positive identity shortcut. This supports the identity-miss hypothesis for that
caller; it does not quantify its runtime share or prove native contains is false
(an equal-but-distinct object can exist). Raw javap and pinned source/output hashes
are in `contains-diagnostic5203/{h-bytecode.txt,caller-origin.json}`. Branch counts
remain pending; do not replace geometric equality with identity-negative answers.


Membership semantics follow-up (5203 only): `l.equals` checks six vertex
permutations using `TriPoint.equals`; point equality compares x/y with JVM float
comparison and ignores point index. Signed zeros compare equal; NaN does not.
`TriPoint` inherits public coordinate mutators from `GVector2`, so coordinate
immutability cannot be assumed from class shape. A geometry-keyed membership
index would need a proven mutation protocol and compatible float normalization;
existing index-keyed edge buckets cannot prove non-membership of equal geometry
with different indices. This is a correctness constraint, not proof that points
actually mutate during triangulation or that optimization is impossible. Pinned
raw bytecode/signatures and summary: `contains-diagnostic5203/membership-semantics.json`.


Alternative under review, not implemented: the two adjacent contains/conditional
add pairs in `h.a(l,l,j)` repeat a native collision lookup for an absent triangle.
Fusion could remove that duplicate search without a coordinate cache. However,
`TriangleList.a(l)` has debug-mode logging and a static counter increment before
native add. Unconditional add is therefore not automatically equivalent when the
triangle is already present. A candidate must retain the original debug path,
prove native collection identity/order semantics for mutable/degenerate equality,
and independently pin caller bytecode. The separate helper with intervening
geometric validation is not an interchangeable site. Review details and raw add
bytecode are in `contains-diagnostic5203/fusion-review.json`; no speedup claim.


Own-type fusion experiment passed 1,117,616 assertions across eight fixed seeds,
including mutable geometric equality in collision trees, NaN/signed zero,
duplicate identities, removes, iterator removes, clear and debug-mode counters.
Exact stored identities/order matched after every operation. Measured fixture
operations made 745,328 original versus 560,262 candidate equality calls (about
24.8% fewer). Log: `contains-fusion-experiment/result.log`; source and invocation
are in `diagnostic/`. This is a semantic experiment, not an official-class or
host benchmark, and it does not justify weakening any native equality behavior.


Three-version static fusion review: all reviewed versions contain exactly two
adjacent contains/conditional-add blocks in `h.a(l,l,j)`, at offsets 251/261 and
269/279. Receiver and argument locals match in each block, the branch skips only
the add/pop, and the target is the next instruction. 5203 `h` SHA is
`ef4a5eb2f0e1b0ac0295f76146a513a326729cfe4526884104cbb27978c52543`;
5302/5303 share `5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d`.
Jar/class/output hashes and exact instruction blocks are pinned in
`contains-fusion-experiment/three-version-shape.json`. This is read-only javap
inspection, not an implemented transformer or successful official execution.


Debug-side-effect refinement: all three official `c$a.b()Z` methods consist only
of `iconst_0; ireturn`; the reviewed debug switch is not dynamically mutable.
5203 companion SHA is `339501ac3499cb1c319bacbd1606c85004eeaa218e5654a72e98d83a12b2b23e`;
5302/5303 share `0558a3ea9810ebfb796a1169db774f5a295645ee235150a3fe38f276a6e49c51`.
Raw disassembly and method checks: `contains-fusion-experiment/debug-switch-review.json`.
This resolves the dynamic-toggle uncertainty for those pinned bytes, not arbitrary
future versions or third-party transformations. A prototype may still preserve
an explicit debug-on original path and must reject unknown caller shapes. No
production transformation has been implemented or accepted at this point.


Offline guarded fusion bytecode prototype now emits separate patched `h.class`
files for all three pinned jars, without installing or loading official classes.
Its generated own-fixture selfcheck passes 47 assertions under `-Xverify:all`,
covering both debug branches, duplicate adds, exact identity/order and five
refusal conditions. `contains-fusion-experiment/bytecode-selfcheck.log` records
execution; each `prototype<version>/pins.txt` records input/output SHA. Production
source/artifact remains unchanged. Official-body runtime behavior, integration
and real-host performance are still unverified for this prototype.


## Completed contains branch diagnostic (seq2234)

Job `caba0c55-e949-4d5c-bd5e-b7c601964be5`, run
`queue-4ed3d800adb94e86b8e80ad1d2a75aa9`, completed successfully with normal exit,
safe cleanup, verified identity and unchanged fixture. Diagnostic agent remains
`42a4282bc67cd70183e95d4dd4d988da6726de53b03974f039785aa8d37b5528`.
`contains-diagnostic5203/counts-analysis.json` validates staged-agent hashes,
outcome gates and complete aggregate counts and pins raw inputs. Independent
`semantic-check.json` verifies canonical/payload binding, exact patched class and
first-four ordered edges against the prior off leg.

| Reason | Calls | Native true | Native false |
| --- | ---: | ---: | ---: |
| identity shortcut | 0 | 0 | 0 |
| clean identity miss | 777185 | 12 | 777173 |
| dirty state | 2157 | 0 | 2157 |
| dead / set-size / key-size / bookkeeping error | 0 | 0 | 0 |

Total 779342 calls, zero identity shortcut hits. The result supports unknown/new
identities as the dominant fallback reason; dirty fallback is about 0.277%.
Unlike sampled stacks, these are actual invocation/outcome counters over the
whole diagnostic JVM. They do not identify individual caller sites or timing.
The positive-only contains shortcut adds checks without avoiding any native
lookup in this run. Next implementation work should evaluate guarded query/add
fusion, retaining native geometric equality; the 12 true results explicitly rule
out assuming every unknown identity is absent. Diagnostic timings are excluded
from production acceptance, and repeated operation/three-version stability and
resource gates remain open.

Cubism wraps the shutdown stderr line with a logger source suffix. The analyzer
now accepts that exact observed wrapper while rejecting arbitrary trailing text;
five parser tests pass, including the wrapped real-log shape. The `ERROR` prefix
on that intercepted stderr line is not itself a failed host verdict.


## Guarded fusion candidate integration (not host-accepted)

After seq2234 showed zero identity hits, the guarded fusion patcher was ported to
runtime and attached to the existing edge-index transformer/setting. The two
class targets have independent hash/shape gates and independent outcomes. Fusion
uses native add even if edge indexing declines. The installer refuses already
loaded membership callers as well as TriangleList; redefinitions are ignored.
ASM tree 9.7.1 is added for the reviewed frame-preserving implementation, with
Maven Central artifact checksums pinned in Gradle verification metadata (commons
is test-only). The initial missing-checksum build failure is retained in
`/tmp/t029-fusion-focused.log`; reviewed checksums resolved it.

Focused tests pass: membership fixture 1 test (48 internal checks), existing
transformer 12, installer 6, official class-family evidence 2, total 21 tests,
zero failures/errors. Official fusion output hashes match the offline prototype.
Log `/tmp/t029-fusion-focused-r2.log`. Full `devCheck :bootstrap:jar` is running;
no new frozen production artifact or host performance verdict exists yet. The
prior contains shortcut remains unchanged to isolate the fusion candidate's
behavior; its lack of hits in seq2234 remains recorded, not hidden.


Full integration check correction: `/tmp/t029-fusion-devcheck.log` initially found
three missing public-method docs; those were fixed. The second run
`/tmp/t029-fusion-devcheck-r2.log` rejects ASM tree/commons under the repository's
explicit core-only supply-chain boundary. The added dependency declarations and
verification entries have been withdrawn rather than widening that policy. The
runtime patcher and its test are not yet buildable without their core-Visitor
port; focused results above apply to the pre-port experiment only. No candidate
has been frozen or submitted. Next action is porting the proven transformation
and tests to core ASM, then rerunning focused/exact-byte/full checks.


Core-only port completed: the runtime patcher buffers/replays the selected method
with core ASM visitors, preserving labels and expanded frames. The test's own
fixture remapping and mutations also use only core ASM. No Gradle dependency or
verification-metadata changes remain. The first port compile caught intentional
instruction-identity comparison; a narrow documented suppression preserves that
control-flow identity check. `/tmp/t029-fusion-core-focused-r2.log` passes all 21
focused/exact-family tests, including byte-for-byte output SHA equality with the
offline tree prototype. `/tmp/t029-fusion-core-devcheck.log` passes full devCheck
and bootstrap jar construction (98 tasks). No fusion host performance result yet.
Before freezing final A/B inputs, verify the packaged patcher and ensure runtime
evidence independently demonstrates the membership caller transformation; the
TriangleList capture alone does not prove the new caller was transformed.


## Frozen fusion resource pair started

Production commits `f0da33fc2` (core-only guarded fusion) and `f04670000`
(independent input/output-hash receipt). Full post-receipt checks and build pass:
`/tmp/t029-fusion-receipt-checks.log`, 102 tasks. The packaged shaded transformer
was invoked against all three official jars without loading host classes;
`fusion-packaged-pin/result.log` confirms both TriangleList and h outputs match
reviewed pins. Frozen agent SHA:
`2f6dd5ba7fe734072e89b7c09d400bcc35840cecf811bdecdb64b3425052fda4`.
Driver remains `3bee41a353df55f20da9845052b7a90f7380ced8bca7c46f8251fbe48785209e`.

`fusion-resource5203/` contains immutable bundle manifest and prepared identities:
off `ec99909779995efd0b47800109170c9d1909f2e40b8fe5460d24464e39efa901`,
on `c79e042346cebce692ec3e1988d17c719a03dca43a8aabf394c61478e96eb285`.
The queue client verifies both inventories/UI hashes first. Off seq2243, job
`ec79608f-ad17-44a7-a15f-a88e62225444`, is running. On is submitted only after
off succeeds. Post-pair analysis additionally requires exactly one 5203 membership
receipt on, none off, with h input `ef4a5eb2...` and output `d38c2fbe...` full pins.
It retains four-edge/canonical/class/JFR/resource-window checks. No performance
verdict yet; no production-ready or theoretical-bottleneck claim.
