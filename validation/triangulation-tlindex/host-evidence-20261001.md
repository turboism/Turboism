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


## First frozen fusion resource pair completed (stability unproven)

Agent `2f6dd5ba7fe734072e89b7c09d400bcc35840cecf811bdecdb64b3425052fda4`:
off seq2243 (`ec79608f-ad17-44a7-a15f-a88e62225444`), on seq2244
(`598cf7fa-c353-4b51-ae82-9122639cacff`). Both exited normally with safe cleanup,
unchanged fixture and verified identity. Canonical/standard gates, four ordered
edge summaries, pinned TriangleList classes and production execution pass. On has
exactly the reviewed h transformation receipt; off has none.

`build/t029-real-host-acceptance/fusion-resource5203/` retains full reports.
First operation off/on: 137.962782 / 133.018025 seconds (about -3.58%);
sampled Java CPU 173.14 / 167.33 seconds (about -3.36%). JFR triangulation sample
span 94.415943 / 85.838165 seconds is a sampled bound, not an exact timer.
Baseline RSS medians 2535.16 / 2632.36 MiB; first retained medians 4838.21 /
4179.16 MiB; third retained medians 5294.33 / 4509.61 MiB. Whole-observed-process
Java CPU 382.19 / 383.27 seconds does not show an overall CPU reduction.
Operations two and three have zero triangulation samples on both legs; these
remain UI-repeat observations, not demonstrated repeated-target retention.

The original analysis pipeline completed semantics/resource summaries, then failed
because it expected an execution JSON export that analyze.py had not persisted.
Recovery exported the original retained JFR recordings with stack depth 64 and
ran the streaming window analyzer; no host leg was rerun. completion.json records
this recovery. The original analyzer still materializes full JFR JSON in memory;
correct that for subsequent pairs. Single-pair performance acceptance remains
NOT_DECIDED. Next: reverse-order frozen-candidate replication, followed by the
remaining version/UI/repeated-target gates if the benefit persists.


### Reverse-order replication submitted

`fusion-resource5203-reverse/` reuses exactly the same two prepared inventories
and frozen agent, with on submitted before off. On seq2278,
job `1ea6e3ce-58f3-43db-98bb-dd40713ad409`, is queued; the client submits off only
after on succeeds. Both inventories and UI configuration hashes passed again.
The first local preflight was refused before any submission because text copying
normalized UI JSON newlines; byte-for-byte copying restored the original hashes.
The refusal log remains `preflight-failed-newline-normalization.log`.

The reverse analyzer now persists depth-64 JFR JSON directly to disk and consumes
the existing streaming event parser. Its downstream window analyzer reads that
same export. Nine existing resource/parser regression tests pass. No production
artifact changes were made between pairs. The first pair's original script and
outputs remain intact. Reverse performance results are still pending.


### Fusion first-operation caller attribution

`build/t029-real-host-acceptance/attribute-fusion.py` streams the retained depth-64
exports, filters strictly to the first explicit operation, and pins export/window
report hashes in `fusion-resource5203/hotspot-attribution.json`. Target samples
are off4655/on4354. Native HashSet.contains stacks decrease from 883 to 278;
the remaining on stacks pass through TriangleList.c and the contains helper.
Native add/remove remain visible. On leaf samples include l.equals1172,
HashMap.TreeNode.find758, and TriangulationEdgeIndex.remove570. The latter is a
method-level sample attribution, not proof that all570 samples belong to its
survivor scan. The source still verifies the actual removed identity by scanning
survivors; geometric equality may select a different equal identity, so that scan
cannot simply be deleted. JIT inlining may redistribute leaf attribution between
TreeNode.find and l.equals; comparing their leaf counts individually is not a
causal performance claim. No invocation counts, stable speedup or theoretical
limit are inferred from these sample totals. Reverse pair remains pending.


### Repeated-target trigger correction

Static review followed native close into CModelingDocument on all3 official
versions. Each calls System.gc() unconditionally at offset241. Therefore the
proposed close/reload trigger cannot establish the existing no-explicit-GC
retention gate; it is not implemented as that acceptance path. Raw pinned
bytecode is retained under repeated-target-review, with native-close-gc.json.
Investigate native undo of the specific atlas operation next; no claim that undo
actually retriggers triangulation yet. This finding does not invalidate the
completed single-operation pairs, which did not use document close/reload.

### Native undo trigger diagnostic prepared and queued

Separate driver `d8c74ec635ac3771ce57223a9d4c2da23b3310fdb78644a8234c4d37c6347bfd`
built by diagnostic/build-undo-driver.py, from pinned driver source plus native
undo guard/adapter. Fourteen guard checks and seven synthetic EDT adapter checks
pass; generated driver compiles with Java17 -Xlint:all -Werror. Original production
agent remains 2f6dd5ba..., and the reverse performance pair is unchanged.

Prepared `2d438cb097b0732e464b0012b43480d1c28a655652b2b18a8f6adbd76ca0d1a9`,
seq2290 job `3863d3c1-2494-48f7-8810-f3140df4d929` is queued. Inputs were fully
hashed again before submit. Initial preparation rejected the bundle location;
copying the separate bundle to the required worktree delivery directory resolved
it, with prepare-rejected-location.log retained. Artifacts/client under
`build/t029-real-host-acceptance/undo-trigger5203/`.

This diagnostic undoes after each operation and before its observation window.
Its extra diagnostic-undo markers deliberately invalidate the standard resource
acceptance protocol. Native snapshots use the current edit mode's manager, require
one new history object, verify identity immediately before native command_undo,
and check cursor/history after return. Fixture bytes are checked before/after.
Snapshots are released before retained observation. Native undo may still leave
cached triangulation or produce no record; these remain falsifiable failures.
No repeated-target execution, per-cycle output equivalence, or retention result
is claimed until actual host evidence exists.

### Native undo live observation (2026-10-01 10:16 UTC)

Seq2290 remains running; the identity-bound host collector is live for Java
PID2800920/startTicks7010624. The first two native undo operations returned and
passed the guard, both restoring cursor0 (`undo-diagnostic.tsv`: `1\t0`, `2\t0`).
Their marker durations are 759.075s and 782.026s. Operation2 took26.043s, and
operation3 has started. These are diagnostic observations, not a successful
three-cycle run or evidence of repeated triangulation. Final JFR attribution and
the terminal outcome are still required. Do not restart or extend the2400s queue
job based on observation timeouts.

A snapshot is retained at
`build/t029-real-host-acceptance/undo-trigger5203/live-observation-1790849720966.json`.
The sandbox has a separate PID namespace: absence of the host PID from its /proc
is not process-exit evidence. The live host collector takes precedence. The
hs_err_pid328.log found inside the cloned prefix is dated2026-08-03 and is not
an error from this run.

Reverse performance on seq2278 succeeded; off seq2293 remains queued. No reverse
pair comparison or stable speedup is established yet.

### Native undo diagnostic terminal result: trigger unproven

Seq2290 reached its2400s limit: terminalState=timed_out, validationStatus=FAIL,
cleanup=safe, normalExit=false. Identity and fixture terminal gates are false;
this is missing successful final verification, not evidence that fixture bytes
changed. The client exited1 and retained the failed outcome. No rerun submitted.

The normal atlas-profiling.jfr is empty. Three repository chunks survived; each
passes `jfr summary`, was copied and SHA-pinned in retained-jfr-inventory.json,
and was assembled into retained-partial.jfr. Streaming attribution is recorded in
`build/t029-real-host-acceptance/undo-trigger5203/partial-attribution.json`.
Sample coverage spans epoch1790847902626.068–1790850282141.715, including all
three completed editor-operation intervals. Native triangulation samples are
3679/0/0 out of10716/2303/6727 total samples for operations1/2/3. Thus native undo
has not established repeated target execution; do not adopt it as the repeated
retention protocol or interpret the shorter later operations as optimization.

Undo1/2 completed in759.075s/782.026s; undo3 was incomplete. None shows native
triangulation samples. Undo leaf samples include substantial MessageDigest
engineUpdate activity (13938/16000/10889), alongside the Windows toolkit event
loop; these are stack samples, not causal durations or invocation counts. Further
caller attribution would be needed before naming the source of hashing cost.
No successful trigger verdict, output-equivalence verdict, performance acceptance,
or theoretical-limit claim follows from this partial recording.

Next repeated-target investigation must identify a native operation that changes
triangulation input, instead of relying on open/OK plus undo. Keep the frozen
reverse A/B unchanged; seq2293 is still queued.

### Fusion reverse pair completed

On seq2278 and off seq2293 both passed normal exit, safe cleanup, identity and
fixture gates. Four ordered edge summaries match; exact TriangleList pins and
membership-transform receipt pass; production bridge samples occur on the on leg.
Recovered analysis and input/report pins are in
`build/t029-real-host-acceptance/fusion-resource5203-reverse/completion.json`.

First operation off/on wall134.7785765/118.2282216s (on−12.28%), sampled Java
CPU169.64/148.06s (−12.72%). Whole-process CPU396.48/340.96s. First pair had only
−3.58% wall/−3.36% operation CPU and no whole-process CPU reduction. Both pairs
have favorable first-operation signs, but this is not stable all-version proof.
Reverse operation starts are4818.726s apart due to FIFO. Baseline RSS medians
off2624.65/on3570.40MiB, first retained4554.35/5102.15MiB, third retained
5865.70/5768.41MiB. Baseline variation and mixed absolute residency preclude a
simple memory-win claim. Operations2/3 again have zero target samples both legs.

Original analyzer failed on two unresolved method frames in one off-leg sample.
The corrected window analyzer reports unknown frames explicitly; ten accounting
and parser tests pass. Original exports and error logs are preserved. A recovery
script variable-shadowing error also remains in failed recovery artifacts;
corrected semantic/resource/window analyses then passed without another host run.
Unknown frames are outside the three operation windows; no unresolved frame is
converted to positive target execution. Top-leaf attribution in the recovery
report means first resolved frame when an unresolved frame exists.

Separate MeshResultSnapshot recorder preparation passed15 synthetic checks and
Java17 -Xlint:all -Werror. It reads cached arrays only, checks freshness, preserves
index order and raw float bits, and retains no native objects. Not yet integrated
or host validated; native auto-connect diagnostic and three-version acceptance
remain outstanding. No production candidate change or main merge made.

### Fusion 53x acceptance in progress

Same frozen production agent2f6dd5ba and resource driver3bee41a3 were prepared
for5302/5303 with the same UI-saved configurations. 5303 preparation initially
rejected its T039 agent outside the new bundle; the pinned T039 bytes were copied
into a separate5303 bundle, preserving the refusal log. No production bytes changed.

5302 off seq2298 (`e2dbee86-ebc7-4c37-b7cf-452801674aaa`) passed host gates,
normal exit, identity, unchanged fixture and safe cleanup. First-operation marker
duration102.3275225s; no relative performance verdict until paired analysis.
5302 on seq2299 (`a3618472-8bb4-480c-a712-8fb5abec98d0`) is running.
The live serial client will analyze5302 before starting5303, avoiding analysis
load during its own performance legs. Inputs and logs: fusion-resource5302 and
fusion-resource5303 under build/t029-real-host-acceptance.

### Fusion 5302 pair completed

Seq2298 off / seq2299 on both pass host gates and four ordered-edge equality,
exact class pins, membership transform receipt and observed production execution.
All phase/resource analyses completed; completion.json pins their reports under
fusion-resource5302. No unresolved JFR frames in either leg.

First operation off/on102.3275225/87.1885996s (−14.79%), sampled Java CPU
153.24/114.78s (−25.10%). Whole-process CPU338.34/325.72s, but sampled process wall
299.959/309.617s: the whole session did not get faster in this pair. In particular,
third UI operation grew from15.20s to28.32s with no target samples in either leg.
Baseline RSS medians3212.14/2998.96MiB, first retained4962.43/4359.54MiB, third
retained5501.57/4768.24MiB. These are single-pair observations, not approved resource
tolerances or proof of repeated-target retention. Operations2/3 have zero target
samples both legs. 5303 follows in the live serial client; no all-version verdict.

Auto-connect diagnostic driver18dd504d5cc9a37a45abaf1a6f4df1b37a786322871ae97db39f48d31fb4b90d
built separately after the5302 performance legs ended. Java17 strict compilation,
15 snapshot checks,12 command-guard checks and6 protocol-analyzer tests pass.
No host execution or repeated-target verdict for this driver yet.

### Fusion 5303 pair completed

Off seq2300 job14c9becc-ba9d-4dfd-aec3-02a69cc755a4 and on seq2301
jobf6bf0b0c-794d-4e5f-8607-6dbc87ba4e8c pass all host gates, ordered-edge equality,
exact class/transform receipt checks and observed production execution. Full
resource/window reports are pinned by fusion-resource5303/completion.json.

First operation off/on105.780429/88.9901439s (about−15.87%); sampled Java CPU
167.00/128.28s (−23.19%). Whole-process CPU376.30/310.43s; sampled process wall
314.917/286.489s. Baseline RSS medians2912.04/2627.45MiB, first retained
4290.43/3802.48MiB, second retained4907.75/4960.64MiB, third retained
5284.91/5083.87MiB. This single pair has a small opposite-direction second-retained
RSS difference and different baselines; no blanket memory-win claim.

Operations2/3 again have zero target samples. Off has one target sample attributed
to the baseline window and3674 to operation1; on has2342 in operation1. These are
sample-time attributions, not counts of invocations. Unknown method frames=0.
All three versions now have positive first-operation pairs for the same frozen
candidate. Stable replication, actual repeated-target retention and final UI/resource
acceptance remain open. No theoretical-limit claim or main merge.

### Follow-up scheduling

Native auto-connect diagnostic seq2303/jobebb55755-fdab-4874-b80c-07fd8471efe4
is queued with prepared401b9c04fabca712a9aafaf5695ebeecd24e2f83ee0277638893a859dcddba81.
Reverse-order5302 on seq2304/job6e291489-dbe3-44c2-af76-10b1f6bc2e04 follows;
its client will submit off after on and analyze the pair before starting reverse5303.
Reverse clients reuse the original prepared IDs and byte-identical UI JSON, with
new request IDs. Both inventories were rehashed before submission. FIFO applies;
no concurrent host run or worker intervention. Diagnostic JFR analysis must wait
if a subsequent owned performance leg has already started.

### Auto-connect diagnostic first host failure

Seq2303 failed with safe cleanup, without normal exit/final identity/fixture gates.
The host entered native mesh mode and recorded711 selected source IDs. The first
command failed before dispatch with NoSuchMethodException:
`com.live2d.ui.container.CScrollPane.getCheckboxRebuildMesh()`.
The no-argument reflection lookup selected a getToolPanel return descriptor that
produces a scroll container, whereas the reviewed native command uses the
ToolPanel_MeshEdit descriptor. No auto-connect-returned/end or result rows exist;
this run proves neither repeated target execution nor optimization behavior.
Original prepared401b9c04 and driver18dd504d plus all failure records are retained.

The adapter now selects getToolPanel by its reviewed exact return type and rejects
ambiguous/missing/bridge descriptors. Regression source includes covariant bridge
and wrong-descriptor rejection checks; compilation/tests/new diagnostic artifact
are deferred while reverse5302 on seq2304 is measuring. This is not yet a verified
fix or a rerun result. The frozen production candidate remains unchanged.

### Final UI preflight refusal and corrected preparation

Final-agent UI seq2305 failed before host launch: expected fixture hash57c4854b…
from inherited configuration differed from copied, previously reviewed UI fixture
2866a509…. Safe cleanup; no UI behavior was exercised. The initial inventory check
verified the file bytes but omitted equality with the Runner's declared fixture
hash, so it was insufficient. Original input/review/outcome/log remain under
fusion-settings-ui and are not counted as UI acceptance.

fusion-settings-ui-r2 now pins both fixture path and SHA explicitly and checks the
single normalized --fixture-sha256 against the fixture inventory, as well as all
input file hashes, agent2f6dd5ba and probe5d79212f. Prepared
f37dbad15d666bf34f0c953a682566e1bcd2a1e82b7d20e4e4b3806d12ddfb75
passes those checks. The new request is separate; no validation gate was relaxed.

The official5203 ToolMode_MeshEdit_Manual javap declares getToolPanel returning
both ToolPanel_MeshEdit and CWidget, confirming why name-only reflection was
insufficient in seq2303. The exact-return-descriptor fix still awaits its new
compiled/host artifact; the old failed diagnostic remains immutable.

### Reverse 5302 completed; final candidate UI semantics verified

Reverse pair on seq2304 / off seq2307 completed with standard host gates PASS.
First-operation wall off→on: 93.1862576→88.0417819 s (-5.52%);
CPU 122.51→121.06 s (-1.18%). Whole-process CSV observation
309.539→287.101 s, CPU 325.47→310.45 s. This replicates the positive
first-operation direction, but the CPU effect is much smaller than the first
pair. Completion/report hashes are in fusion-resource5302-reverse/completion.json.
The comparison now includes five pairs; no stable acceptance is declared.

Final UI retry seq2308 / job e918e38d-f290-43f0-96ad-b1ac49b468ba succeeded
with normal exit, identity/fixture verification and safe cleanup. Actual
settings-result.txt reports zero failures: absent config defaults selected,
Apply saves false, Cancel preserves it, reopening shows false, OK saves true,
and reopening shows true. Agent2f6dd5ba / probe5d79212f remain pinned.
The actual UI off/on snapshots hash to 8945ee22… / 190ea2fc… and are byte-identical
to the A/B source configurations. The result SHA is
d5329da999862c1222b2264263d7f14051d79ce5dd76bdd4cd010d1d904608dc.
Full semantic/hash review: fusion-settings-ui-r2/ui-semantic-review.json.
This UI process alone is not a restart-transformer check; independent A/B launch
receipts remain necessary to establish startup behavior.

The diagnostic exact-return fix compiled with Java17 -Xlint:all -Werror;
NativeAutoConnectSelfCheck passed all15 checks. Official5203 metadata inspection
found both CWidget and ToolPanel_MeshEdit returns (both nonbridge), and exactly
one reviewed ToolPanel_MeshEdit descriptor. This verifies descriptor selection,
not successful native command execution. New driver construction is deferred
until reverse5303 performance legs end; seq2310 on is currently running.

Endpoint-source correction: reverse5302 309.064→287.053 s and
324.64→309.97 CPU s are identity-resource JSON totals, not process CSV.
The CSV totals above match final-ab-analysis.json and the comparison table.
Both observations are retained; endpoint windows must not be mixed.

### Final UI configuration linked to six independent startups

`verify-final-ui-startup-link.py` passed for5203/5302/5303 off/on. Each run's
prepared --home-config bytes and actual task turboism-home/config.json exactly
match the final UI snapshots; prepared Agent SHA is2f6dd5ba. Runtime-log hashes
and actual TriangleList definition hashes were re-read and matched the completed
reports. Membership transformation receipts occur only on enabled launches;
pristine/patched class hashes differ in each version. This links final UI saved
values to actual independently launched startup behavior, not merely UI wording.
Machine-readable evidence: fusion-settings-ui-r2/startup-link-review.json (6 runs).
It closes this final UI/startup linkage gap, not resource/performance acceptance.

### Reverse 5303 and r2 diagnostic submission

Reverse5303 on2310/off2314 completed all standard/ordered-output/transform/execution
checks. First-operation off→on94.04→90.50 s (-3.77%), CPU131.82→122.75 s (-6.88%).
Whole-process CSV298.37→333.84 s and CPU321.01→334.28 s both increase. Six pairs
now consistently improve first operation, but whole-session benefit remains mixed.
The on baseline RSS4382.3 MiB also differs substantially from off2473.5 MiB;
no simple retention or leak conclusion follows. Reports/completion hashes are in
fusion-resource5303-reverse; updated comparison and RSS tables preserve adverse data.

After these performance legs and analyses finished, diagnostic r2 built with SHA
54ec1ae4362f0531bf63c47a5c3db733ada960351d946ca9335fdda9c9dd3f7e.
Prepared0a8fb6a19b9e4ae1cc0be141840892347bb1a7ee8e88817bb5beee643aa132e4
passed input inventory checks and was submitted as seq2316,
job4ef37795-6606-4ac5-8d5b-5b3bfeb85481. The frozen production agent remains2f6dd5ba.
Queue submission is not successful native repeated execution.

Fresh-edge own-type semantic selfcheck passed809874 comparisons with5683 original
contains searches. Counterexamples demonstrate why reused identities and value
-equality edge types cannot use this elimination. Static-only prototype compiles
Java17 -Xlint:all -Werror and accepts pinned h/j pairs for all3 official jars;
output h SHA5203=d0914150cd000dadbacf17aaef9492d15d06b7d6e1ee46776acb99a66a69c974,
53x=5a692d2338c995769a25344ceacbb391bc980e2278d55d2683430dacaff5d552.
No official class was executed. Generated-bytecode verification/negative tests,
production integration and real-host A/B remain uncompleted for this new candidate.

Fresh-edge generated-bytecode follow-up:41 checks now PASS under -Xverify:all,
executing only own generated fixtures. Original and transformed fixtures agree
across all8 branch masks and constructor-failure positions0–3. Paired pins and
shape-negative cases are tested. Production dependency admission remains open:
resource bytes or h's hash alone must not be used to infer actual loaded j equality.

### Guarded fresh-edge production candidate (not host validated)

The runtime implementation now guards the actual loaded edge Class using ClassValue:
exact binary name, final direct Object subclass, and Object-declared equals.
Unknown/nonidentity classes and custom ArrayList subclasses retain native contains,
including equality side effects/exceptions. No host class is loaded from transform.
This replaces the proposed resource-only j admission with an actual-type semantic
guard; freshness still depends on the exact pinned h caller and its reviewed locals.

The transformer applies the fresh-edge and membership stages independently and
reports each against final returned bytes. Focused runtime tests26/26 and official
shape tests2/2 pass. Three-version static method normalization confirms only a(l,l,j)
and c()V changed; remaining15 methods in5203 and26 in53x compare equal. Combined h
SHA5203=d0fac0cd2c2092db163db7b78bffd011713f2bef17279b08ab088af4e7d27d92;
53x=40d0754026a7a2fb7c491e95144b9d8a1a605d7aee44579cb20bb2363962f8e6.
Each c() has exactly3 guarded calls and no original ArrayList.contains call.
No official class was executed by that check.

devCheck and :bootstrap:jar PASS (98 tasks). Frozen new candidate agent SHA
0de3cf6cf61b86fbcb15b0e672e87020e96945e379c5649a26661fce5e9ab080
is under fresh-edge-candidate with artifact-pin.json confirming packaged helper,
ClassValue and patcher classes. previewBundle was not run; prior frozen deliveries
were not overwritten. Packaged-transform verification and real-host A/B remain.

### Auto-connect r2 failed in result binding, not accepted

seq2316/job4ef37795-6606-4ac5-8d5b-5b3bfeb85481 failed and was safely cleaned up.
The first native command returned after66.386s; result capture then timed out
MESH_CAPTURE state=STARTED. Main JFR is empty, but one6.5MB retained JFR chunk
was copied and hashed; jfr summary and export succeeded. Partial attribution after
command return spans34.427s. All47 samples with capture stacks have leaf
WinNTFileSystem.canonicalize0; the retained representative stack is
File.getCanonicalFile -> NativeAutoConnect.boundDocument -> NativeAutoConnect.capture.
This supports fixing repeated filesystem canonicalization on the EDT, not increasing
the deadline or blaming array hashing without evidence. No native outputs completed;
no repeated-target, equivalence or retention acceptance is claimed. Partial artifacts
are under auto-connect5203-r2; the failed outcome is preserved.

### Packaged candidate checked and fresh-edge5302 queued

The actual frozen0de3cf6c JAR was used as the classpath for the production transformer
check, with its CodeSource recorded. All3 outputs match d0fac0cd/40d07540 and both
independent receipts bind the final bytes; other normalized methods remain equal.
Evidence: fresh-edge-packaged-pins/review.log. No official class was instantiated.

New resource5302 prepared off2a3229f0e846e0c764f5256e92b4ad199b41ee879cc11c290db2074f7f31bf13,
on8ffd2e5c69d6ed93dd210c3eca41a6bc48b50ed1d2a79669ed4c93220ea4758d.
Full input inventories, frozen Agent0de3cf6c, driver3bee41a3 and UI config hashes
passed checks. Off seq2322/job3299eb57-74eb-40dc-a76a-bfee15d06df0 queued; the serial
client submits on after off succeeds, then checks edges, both transformation receipts,
execution and resource windows. No speedup is claimed from preparation or submission.

Auto-connect r3 replaces repeated canonicalization with per-driver document binding,
one off-EDT canonical fixture check and subsequent EDT identity/path checks.25 guard
checks and generated-driver compilation passed; driverfc7a5d2c is built separately.
The r3 queue workflow waits for fresh-edge5302 terminal gates/report hashes before
preparing/submitting with unchanged original Agent2f6dd5ba. Failed r2 is retained.

### Fresh-edge5302 completed, with missing default-log receipt

Off2322 and on2327 both passed host identity, normal exit, unchanged fixture and
safe cleanup. Payload binding, all four ordered edge digests, TriangleList hashes,
membership final-output SHA40d07540 and production-index execution agree. The
strict analyzer failed because bootstrap routes only membership success to INFO;
fresh-edge success is DEBUG and the default INFO sink omits it. Original failed
analyzer/log/partial report remain unchanged. The separate observational review
explicitly labels this receipt gate MISSING_DEBUG_FILTERED, not acceptance PASS.

| Observation | Off | On |
| --- | ---: | ---: |
| First explicit operation wall seconds | 93.8119 | 87.4409 |
| First operation Java CPU seconds | 125.58 | 121.73 |
| Whole CSV observed wall seconds | 305.939 | 286.540 |
| Whole CSV Java CPU seconds | 316.06 | 315.21 |
| CSV peak RSS MiB | 5147.16 | 6228.10 |
| Third retained median RSS MiB | 4978.79 | 5524.55 |

First wall decreases6.79%, first CPU3.07%, whole CPU0.27%; RSS peak increases21.00%.
This is one observational pair, not stable resource/performance acceptance. Both
legs' operation2/3 target samples remain zero. Per-window observed heap maxima
combine direct boundary readings with GCHeapSummary events; they are sampled,
not continuous peaks. Exact h.c()V first-operation BCI438 leaf samples remain
888 off/909 on. No separate fresh helper frames were observed. BCI attribution
does not establish the cost of a source operation or actual guard admission;
further attribution is required before claiming fresh-edge elimination worked.

The minimal bootstrap fix additionally routes fresh-edge success to INFO. Five
existing contributor tests, bootstrap JAR and license checks pass. Frozen Agent
021e49085daf68d02d2ab76d638f61403b6d1ece27d95050ed835aa1b0df3181 differs from0de3cf6c
only in HookContributor.class and generated sourceRevision metadata; all algorithm
entries compare byte-for-byte equal. It has not been host-validated. Final three
versions, default-log dual receipts, UI/startup linkage and stable resource bounds
remain open; no main merge or theoretical-bottleneck claim.

Resource-only completion deliberately records
performanceAcceptance=BLOCKED_MISSING_FRESH_EDGE_RECEIPT. This releases the
independent old-Agent2f6dd5ba diagnostic after performance legs/analysis end,
without granting acceptance. R3 prepared631a151f, seq2330,
job572d4b60-9ec2-478c-bf89-4eaf9836a08a is running with driverfc7a5d2c.
The terminal-bound analyzer uses a64-frame streaming export and preserves failure.
The new0de settings UI snapshot92195223 was prepared but never submitted; a final
receipt-fixed candidate will need its own frozen UI inputs.

Evidence: fresh-edge-resource5302/{analysis-failure-review.json,
observational-review,completion.json,fresh-edge-attribution.json},
fresh-edge-receipt-candidate/artifact-pin.json and auto-connect5203-r3.

### R3 diagnostic terminal failure and post-command native work

Seq2330 failed at MESH_CAPTURE/EDT_TIMEOUT and was safely contained; no result
rows completed. Its first command returned after62.796s. Main JFR is empty; the
single retained6.66MB chunk was copied, hashed and successfully summarized/exported.
Post-return partial coverage22.455s includes237 triangulation samples. A retained
stack binds h.c -> updateIndices/updateMesh/getGlIndices -> initByEditableMesh ->
actionManager lazy initialization -> Point_DragSelect -> mouseMoved -> EDT.
No capture/MeshResultSnapshot or canonicalization frames were observed in this
post-return partial recording. This differs from r2's47 canonicalization samples;
absence in samples alone does not prove that a method never ran.

The evidence motivates checking native post-command activity and EDT query
admission/completion separately. It does not establish stale-cache cause, prove
three repeated commands, or authorize simply extending deadlines. The initial
partial attribution omitted the diagnostic package prefix; its artifact is retained
and capture-timeout-attribution-r2.json corrects the matching explicitly. Supervisor
post-containment fixture source/copy, golden JAR/BAT and staged artifact hashes
match; the top-level validation remains FAIL with normalExit=false. No fabricated
normal exit or repeated-target acceptance is claimed. All monitoring workflows
are now terminal; the next action is evidence-led protocol diagnosis, not restart.

### R4 diagnostic frozen, shared queue blocked

R4 changes only the diagnostic capture protocol: retry MESH_CAPTURE after atomic
QUEUED-to-TIMED_OUT cancellation, never a started invocation, before the unchanged
30-second ready deadline and total run budget. Own lifecycle17, snapshot17 and
native guard25 checks PASS; Java17 lint/Werror compilation succeeds. The sidecar
records cancelled waits and all four stale cache versions; stale arrays and late
results remain rejected. Three cycles,711 sources and standard host gates remain.

Driver25358e39f4ff2819ca11b3dcd77163f1561c13895767ddf1257fb421d1c75e69
is frozen in auto-connect5203-build-r4b. Prepared input
12256aef630c39e0283fe24240e16b82a420f5ff83a3d29ae29bdd9408a00f0e retains
old production Agent2f6dd5ba to isolate this diagnostic change. It has not run.
The shared host was observed quarantined by another task, seq2338/job
2486b790-d594-4d5a-801d-c36e93b5360d, reason "no durable final supervisor verdict".
No cancellation, recovery, evidence change or process signal is applied to that
other task. Our submission must remain FIFO behind existing jobs. T044/T046 and
final Agent021e4908 verification remain incomplete.

### R4 terminal failure: capture admitted, index cache stale

The other session restored the shared queue; our r4 submitted as seq2340/job
36cabddb-3ce3-47bd-8c5f-d7504d62a482 and ran with the frozen inputs. The first native
command returned after64.3473187s with711 selected sources. Capture callbacks now
ran, but reported edgeVersion370/indexCacheVersion-1/positionVersion1/vertexCacheVersion1.
No version change was recorded before the original30s ready deadline expired.
There were no cancelled-before-start retry rows and no completed result rows.
This narrows the observed failure to result readiness; it does not prove that all
meshes stay stale forever or that cache-refresh/forced-computation is appropriate.

The job remains FAIL, normalExit=false, cleanup=safe. Supervisor post-containment
source/copy fixture, cloned/golden JAR/BAT and staged artifact hashes all match;
these supplementary checks do not rewrite the top-level failed gates. Original
outcome, wait/marker/selection/protocol files and the sole7,988,269-byte partial JFR
are retained under auto-connect5203-r4. JFR SHA
995c231e1d592a4f9e97afec1a7729c26db3db5909836166f333806d1819a185;
summary succeeds. failure-review.json binds the evidence and original outcome.
The terminal monitor correctly refused acceptance analysis and did not rerun.
Review native cache publication before any further diagnostic change. Separately,
final Agent021e4908 on/off5302 and real settings-UI inputs are being prepared.

### Final receipt-fixed candidate inputs submitted

Complete inventories for frozen Agent021e49085daf68d02d2ab76d638f61403b6d1ece27d95050ed835aa1b0df3181
and unchanged resource driver3bee41a3 PASS. Final real settings UI prepared
794fd8f543bac4e7672ced956b71c02f33c15be9815a56c2bda2f83460f6d0a0 was submitted
as seq2344/job4b694947-2e28-4ea9-a855-239b8df637b5. Probe5d79212f and its pinned
fixture are unchanged. The input must still pass actual UI and independent-startup
linkage; submission is not UI acceptance.

5302 reverse order is on then off: prepared on
a9b158156588990bb20794c8945b0b9454889a658a23d2701fcb8ceee92fa29f and off
0368aa32f559095f5a2139cc173a7e80262ee5db012c9c9f794cfe1497138d80. On was submitted
as seq2345/job4fad5b66-e9d5-405c-8ee4-2585590fe89c; off is not yet submitted. The
serial client submits off only after on succeeds, then checks both INFO receipts,
ordered outputs, actual execution and resource windows. The client waits through
another task's quarantine without recovery; its own quarantine/failure stops it.
At the observation both owned jobs are queued behind seq2343. No new host PASS or
speedup is claimed. Files: fresh-edge-receipt-{settings-ui-r1,resource5302}.

### Final candidate UI PASS and on5302 default INFO receipts observed

Seq2344 succeeded with all standard gates. The actual final Agent021e4908 staged
hash and real UI result were independently checked: visible precise Chinese label,
default checked, Apply=false, Cancel preserves Apply, reopen=false, OK=true and
reopen=true. Off/on snapshots8945ee22/190ea2fc match the actual5302 A/B inputs byte
for byte and differ only in meshTriangulationEdgeIndex. Final evidence is in
fresh-edge-receipt-settings-ui-r1/ui-semantic-review.json. The first review-script
attempt assumed a nested runtime JSON object; its KeyError and unverified state
remain in semantic-review-attempt-1.json. The corrected verifier checks the actual
top-level schema. No failed host outcome was changed.

On5302 seq2345 succeeded with all standard gates. Its default runtime log contains
exactly the observed fresh-edge and membership INFO success receipts, input
5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d and shared final
output40d0754026a7a2fb7c491e95144b9d8a1a605d7aee44579cb20bb2363962f8e6.
This closes the default-log observability defect for this leg; full semantic,
execution and resource comparison still waits for off. The serial client submitted
off as seq2350/job9e183118-3ffa-45e8-967f-efbde8e762d9, now FIFO queued behind other
tasks. No paired performance acceptance or actual fresh-guard hit-rate claim is
made. Final three-version startup linkage, repeated-target retention, resource
bounds and stable improvement remain open; T044/T046 are incomplete.

### Final off5302 failure and manual-intervention exclusion

Off seq2350/job9e183118-3ffa-45e8-967f-efbde8e762d9 ended FAIL after the third
operation, during its retained observation. The terminal result and retained-end
marker are missing; the Runner reports "host exited before terminal result".
Cleanup is safe, but normalExit=false and the original failed gates remain.
Supervisor post-containment fixture/golden/staged hashes match independently;
these supplementary checks do not change the terminal FAIL. Wrapper exit0 alone
is insufficient. The empty main JFR and sole10,930,109-byte retained chunk are
recorded; chunk SHA6ee1aa9a338a8da6be82ea607b7adf83638f7c59ed9375e59c5e2e3a1f23c7b0.
Copies and hashes are bound by fresh-edge-receipt-resource5302/failure-review.json.

The user confirmed operating the validation window around23:11 Beijing time.
They did not confirm closing it; the exit cause is not established. This complete
pair is excluded from performance acceptance. Its successful on leg cannot be
paired with another attempt to fabricate a completed comparison.

### Static native publication boundary and bounded final5302 follow-up

Pinned, read-only native bytecode review passes27 boundary checks across the three
versions. The raw index getter reads its field; commandAutoConnect increments the
edge version after autoConnect, whereas updateIndices publishes the cache version
through getGlIndices/updateMesh. A separate5203 producer review passes7 checks:
native triangulation writes cached_indices contents and may reuse the same array
when its length matches; it does not publish the GL cache version. Array identity
alone therefore cannot prove fresh computation. This does not prove permanent
staleness, a performance bottleneck or host output equivalence; no cache field or
official class is modified/executed by this audit.

Reports under repeated-target-review: native-cache-publication-review.json SHA
056e50377a758b55cbd9102c7ad266354d3acdbfc96760f2e34485cad0f1c43f and
5203-producer-publication-review.json SHA
15766d9ffe936a26c0d4e50f97037de0c8ac6624a529984099a107dec5456499.

The user asks for a bounded conclusion after the long run. One new complete5302
on/off pair is prepared under fresh-edge-receipt-resource5302-r2, retaining final
Agent021e4908, driver3bee41a3, the same prepared inventories and exact UI JSON
bytes. New request suffix -receipt021e-ui2344-resource5302-r2 prevents reuse of
old jobs. A local directory-copy substitution initially changed a JSON path;
the inventory check rejected this before any submission. The failed local log is
retained, original JSON bytes restored, and both inventories now verify. No host
gate or frozen input is relaxed. This pair stops on failure and is not expanded
into automatic reruns. Give an adopt/hold recommendation when it ends; single-pair
PASS alone cannot establish stable three-version acceptance. T044/T046 remain open.


### Bounded final5302 pair complete: hold production acceptance

The new complete on/off pair ended successfully: on seq2355/job
db482a9c-7006-475b-bd3d-a70034395faf and off seq2357/job
45416af3-c876-49d1-9af5-fa0f896b8cd1. Both final Agent021e4908 legs pass normal exit,
identity, unchanged fixture and safe cleanup. Strict review passes payload binding,
default fresh-edge/membership receipts, the first four ordered edge outputs,
pinned TriangleList SHA and sampled production execution. On has434 production
bridge samples, off has none. These checks do not establish actual fresh-guard
admission or complete per-cycle repeated-target outputs.

| Sampled observation | Off | On | On change |
| --- | ---: | ---: | ---: |
| First explicit operation wall seconds | 83.2949 | 90.5236 | +8.68% |
| First operation Java CPU seconds | 112.27 | 120.66 | +7.47% |
| Whole resource observer Java CPU seconds | 308.68 | 306.71 | -0.64% |
| Resource observer peak RSS MiB | 5201.17 | 5611.97 | +7.90% |
| Available-readings peak PSS MiB | 5145.68 | 5555.82 | +7.97% |

Whole-process off PSS has one unavailable record; on has none. PSS peaks refer to
available readings, not complete continuous coverage. CPU deltas omit unsampled
boundaries and unobserved lifetimes; RSS/PSS are sampled peaks. Retained median
RSS on is4170.74/5030.61/4932.72MiB, off4560.76/4772.61/4829.99MiB. Both legs still
have zero triangulation/production-index samples in operation2/3. This does not
prove no execution, and these windows cannot establish repeated-target retention.

The analysis pipeline finished normally; completion.json binds all four report
hashes. Independent bounded-review.json SHA5845ef44591294c4febe2f1ff1b1ea060d55d367d616ff48723e3b38fd1bf660
records HOLD_PRODUCTION_ACCEPTANCE / NOT_PASSED without changing either host PASS.
A premature reviewer invocation before completion.json existed was rejected and
retained in bounded-review-attempt-1.json; the completed review checks every
completion report hash before writing its conclusion.

No additional host job is submitted by this review. This final pair shows slower
first operation, negligible observed whole Java CPU reduction and higher sampled
memory; it does not support stable net benefit. Hold adoption/merge, preserve the
implementation and evidence. Final5203/5303 comparisons, final three-version
UI/startup linkage, guard/scan evidence, real repeated-target outputs and agreed
resource bounds remain open. T044/T046 and the broader stable-optimization goal
are not complete. No theoretical bottleneck is claimed.

### T047 retained-recording attribution and offline removal-batch experiment

No new host was launched. The retained final021e5302 pair was read with streaming
execution and allocation analysis. First-operation on has428 inclusive remove
samples,420 leaf samples; all420 leaf frames have BCI123, the backedge of the
frozen bridge's identity-survivor scan. Registration st has2 frames. The pinned
native53x h.a(l,l,j) performs adjacent removals at126/133, then adds replacements.
This motivates combining the survivor traversals; sample/BCI counts are not
causal operation costs. Runtime patch/install frames are also present in the
broad prefix report, so its inclusive allocation totals are not exclusively
steady-state bridge work. Weak-key allocation weight62,897,536bytes from17 events
is a sampling estimate, not retained bytes or an exact allocation count.

hotspot-review.json SHAe01fb937b2ec2e8bec9c0e95342043d40b19a576dd413439ece0127ff651ca8e
binds the retained JFR/window hashes and separately counts Java/native samples.
It agrees with the strict report's whole sample totals and first-window target
counts. Original performance/host outcomes remain unchanged.

The experiment retains each native remove first and its exact return/equality
semantics, but defers at most8 known, distinct identities. One survivor scan
proves actual removal before either indexed query or positive membership can
answer. Unknown probes, surviving arguments, reinsertion, size drift, a full
budget or partial failure cause dirty/native fallback or live-set rebuild.
Pending references are released on clear/rebuild/invalidation; registration
remains weak with no set back-reference. No host selector, caller weave,
public bridge descriptor, settings or production dependency changed.

The new owned-object regression fails against old code with509 survivor visits
for a256-triangle two-remove/two-add sequence, then passes with256 visits. The
26 differential/lifecycle cases include a pair of mutated-equality tree-bin
victims, pending membership, reinsertion, capacity overflow, side removals,
failed scan, clear release and the existing independent8-thread/weak-key gates.
Other affected bridge/patcher cases make53 focused tests; both reviewed native
families pass2 static weave tests, and devCheck passes. These tests do not execute
official classes or prove host resource safety.

A separate OpenJDK17.0.20 benchmark loads old021e and candidate bridge classes in
isolated loaders and flips self-owned512-triangle disjoint quadrilaterals. Eight
warmup pairs precede five alternating-order measured pairs of2000 cycles. Every
cycle checks the two ordered result identities. No measured cycle group has GC.
Median thread CPU73,763,371→57,954,888ns (-21.43%), wall73,926,723→58,073,322ns
(-21.44%), ThreadMXBean measured allocation5,561,024→3,437,728bytes (-38.18%).
This is one controlled owned-object workload, not Cubism speedup or RSS/retention
acceptance. Measurement excludes initial index construction, including the new
per-state array of8 pending references. The measured candidate classes are copied
and match the tested classes; host peak/retained memory remains unmeasured.

Evidence: build/t047-offline/{offline-review.json,measurements.jsonl,
RemovalBatchBenchmark.java,measured-candidate-classes,TEST-*.xml,t047-*.log}.
Old sandbox cache-lock failure and the expected red test remain retained.
The candidate is an offline source experiment; frozen production Agent021e is
unchanged. No new Agent is packaged or submitted. T047's offline slice is
complete; T044/T046 and stable final-host optimization remain unproven. The next
step is freezing a new artifact and collecting a bounded host comparison;
these owned-object numbers cannot grant production acceptance.


### 2026-10-02 T047 packaged candidate and bounded 5302 host pair in progress

The tested removal-batch implementation is committed as b15783d1c. Bootstrap jar
build and license verification pass. A new Agent is frozen separately at
build/t029-real-host-acceptance/removal-batch-candidate/turboism-agent.jar, SHA
ee244d0f0be9acc3ca9c812420ebea8bf034742fd75010c6f817987f8ddc1ad0.
The old021e artifact and previewBundle are preserved. ZIP entry sets agree;
only the bridge, its St/SetKey classes and framework-version metadata differ.
Settings, patcher and other algorithm classes are unchanged. A first byte-equality
check failed before any host submission because packaging reordered the constant
pool. That failure is preserved. Complete javap instructions, descriptors,
branches and exception tables match the tested classes after numeric pool-index
and trailing-comment-layout normalization; class bytes are not equal.

The actual packaged JAR also passes five alternating owned-object comparisons,
checking ordered identities every cycle. Median thread CPU decreases16.57% and
measured allocation38.18%, without GC in measured groups. These are owned-object
selection measurements, not Cubism or retained-memory acceptance. Artifact pin
SHA560bef844f38070e10673c3a625b0c0ab8813651331670585db8562ed88092d2
binds instruction and execution reviews. Original driver remains SHA
3bee41a353df55f20da9845052b7a90f7380ced8bca7c46f8251fbe48785209e.

Both complete prepared inventories and original real-UI seq2344 off/on JSON
bytes verify before submission. Prepared on=dd06cf64b234b9824f9ba2b8d43da0efbc246d77d62a4e5651b84f599f533652,
off=2554299c705d2da5d7504d6bec46fc383a441bf3652a066ddab72c6c4e27ee6c.
New request suffix -t047-removal-batch-ui2344-resource5302-r1 binds this pair.
On seq2363/job76797f58-f484-4e59-b8ba-f21eb082aa27 started through FIFO at
2026-10-02 00:20:16 Asia/Shanghai, runqueue-eae8a916127c414f934b050baef42ee6.
Read-only observations confirm its recorded Java identity and first operation
completion. Off is submitted only after on succeeds. This is the existing
bounded client, not a duplicate submission; failure stops the pipeline without
automatic rerun. No heavy build or JFR analysis runs during these measurements.

Evidence directory: build/t029-real-host-acceptance/removal-batch-resource5302-r1.
setup.json SHA00a8c4902fc4133ffd86c3ed6d6ea104d0d90532a8195b64d2878b76f27e5aad.
Host/performance acceptance remains NOT_DECIDED. Completed semantic/resource
reports are still pending. T044/T046 and the stable-optimization goal remain open;
final5203/5303, new-candidate UI/startup linkage, actual repeated-target/guard
proof and resource bounds are not replaced by the old candidate evidence.
This pair ends with an adopt/hold recommendation and no automatic additional job.


### 2026-10-02 bounded removal-batch 5302 pair complete: hold production acceptance

The same client completed normally with exit0 and RESOURCE_ANALYSIS_COMPLETE.
On seq2363/job76797f58-f484-4e59-b8ba-f21eb082aa27 and off seq2365/job
a87d20ef-88b6-4e57-9f0e-5f7d99fee08c pass normal exit, exact host identity,
unchanged fixture and safe task-owned cleanup. Off run is
queue-c6ffe0f35ce64964bf17c27103030a71; it waited behind another session's
FIFO task and was not inserted ahead of it. No duplicate or extra job was
submitted. Independent review verifies actual staged Agent ee244d0f, driver
3bee41a3, unchanged official5302 JAR/fixture, both actual home config hashes,
prepared/job/run binding and all completion report hashes.

Payload/canonical binding, the default fresh-edge/membership INFO receipts,
first four ordered edge outputs and precise TriangleList SHA pass. On has108
production bridge samples, off has none. This confirms sampled execution,
not actual guard admission, all-cycle output equivalence or eliminated scans.

| Sampled observation | Off | On | On change |
| --- | ---: | ---: | ---: |
| First explicit operation wall seconds | 91.0739 | 87.1515 | -4.31% |
| First operation Java CPU seconds | 120.87 | 125.05 | +3.46% |
| Three operation wall seconds combined | 121.4026 | 122.1894 | +0.65% |
| Whole resource observer Java CPU seconds | 319.41 | 317.96 | -0.45% |
| Resource observer peak RSS MiB | 5792.77 | 4988.03 | -13.89% |
| Available-readings peak PSS MiB | 5738.08 | 4933.66 | -14.02% |

On whole-process PSS has1 unavailable record; off has none. PSS peaks are from
available readings, not continuous full coverage. CPU deltas omit unsampled
boundaries and unobserved lifetimes; RSS/PSS are sampled peaks. Retained median
RSS on is3753.15/4689.71/4375.87MiB, off4626.84/4906.59/5167.23MiB. Both legs
have101 recorded GC events. Maximum JFR heap summary on4737.82MiB vs off4793.72MiB
and final recorded summary3531.89 vs3638.61MiB are event observations, not an
object-retention or leak proof. Operation2/3 still have zero sampled target
execution on both legs; their wall changes cannot establish repeated
triangulation benefit. Missing samples do not prove absence of execution.

Bounded-review.json SHAfacb40f5335ad3d299af50c5c5ee32ef5a5c22cb63938175d187039b475c06a2
under removal-batch-resource5302-r1 records HOLD_PRODUCTION_ACCEPTANCE /
NOT_PASSED without rewriting either host PASS. This is a mixed single pair:
first wall and sampled memory improve, first CPU rises, all-operation time is
slightly worse and observed whole CPU is nearly unchanged. It supports local
improvement, not stable net gain or a promised repeatable memory reduction.

The bounded measurement and evidence review are closed. Hold production
adoption/merge; no further host task is automatically submitted. T044/T046 and
the stable-optimization goal remain unfulfilled: final5203/5303, final-candidate
UI/startup linkage, actual repeated-target outputs, guard/scan proof and resource
bounds are still missing. No theoretical bottleneck has been established.


### T048 retained-pair attribution and cold-guard diagnostic preparation

Previous goal turn made progress: the new bounded5302 pair and independent
resource review ended and were committed as35a8e19ea. The goal remains active;
no stable net benefit or theoretical bottleneck has been established. A fresh
read-only queue inspection confirmed idle before retained-recording analysis.
No new host task was submitted.

Streaming analysis binds both raw JFRs, execution exports and explicit operation
windows, and agrees with the strict analysis sample totals. Exact runtime-method
prefixes exclude installer/transformer/patcher attribution. On's first window has
2054 triangulation samples and108 production bridge samples. Settle has96
inclusive/91 leaf samples: BCI92 has19 frames and BCI163 has72; these correspond
to the one/two-pending survivor loops in the frozen bridge dump. Registration st
has2 frames. The actual SetKey class's allocation weight estimate is76,928,456
bytes; this is sampled allocation, not retained memory. None of these counts
proves causal time or makes registration the dominant CPU cause.

Native h.c remains the largest triangulation leaf at1150 samples. Its innermost
BCI438 has921 samples on vs879 off. The preserved transformed h.class SHA
40d0754026a7a2fb7c491e95144b9d8a1a605d7aee44579cb20bb2363962f8e6 matches the
returned-byte SHA in both on receipts; javap maps438 to containsFresh rather
than off's ArrayList.contains. This does not prove actual runtime guard
admission/fallback. JIT/inlining can move BCI attribution. First-window j-object
allocation weights are about13.12GB on/13.03GB off; weights are estimates, not
exact byte totals. First-window recorded GC events30 on/37 off report pause-sum
fields totaling0.739/0.595 seconds; these fields are not GC CPU measurement or
proof that GC explains the first-operation CPU increase. Rendering leaves also
differ, so no exclusive cause is established.

Attribution report under removal-batch-resource5302-r1/hotspot-review.json SHA
13599af17d62f98104eb52daf052d81106ff8f06c9ac2d26f592c1f44ae3bc87. Keep the
bounded performance verdict unchanged. Next evidence should distinguish actual
fresh-guard type admission before choosing registry or geometry changes.

The new source-only guard diagnostic uses the exact fixed validation token
FRESH_EDGE_GUARD_METADATA_V1 at turboism.validation.triangulationEdgeGuard.
Each cold ClassValue computation can report actual type/loader/equality-owner
metadata and reason; ordinary calls stay silent, and cached calls retain the
same bytecode instructions. Concurrent cold computations may repeat; records
are neither query counts nor cache-publication receipts. Loader hash labels
are not unique identity proof. No strong class/loader registry is added. The
original equality decision, fallback result/exception and JDK-only dependency
contract remain; nonfatal logging failures are contained, fatal JVM failures
retain propagation policy. No mathematical, sorting, weaving or public bridge
signature changes are made.

Before implementation, nine owned-type tests ran with two expected missing-
receipt failures; the red log is retained. Final focused59/59 and devCheck pass,
including six added cases: sequential cold/cache behavior, same-name distinct
loaders and native equality, wrong/default token silence, exact receiver/null
boundaries, nonfatal sink containment and fatal retry behavior. Only generated
self-owned types execute. Frozen vs tested containsFresh instructions agree
after numeric-pool-index/comment-spacing normalization, preserving offsets,
opcodes, descriptors, symbols and branches; whole class bytes differ. CodeGraph
is synchronized after the final edit.

Offline-review.json under build/t048-offline SHA
56c0e803c48071db956f5a4dfbebbf9e7c28fbced2e24172b5827157c832c994 binds source,
XML, final devCheck/red logs and instruction checks. Frozen production Agent
still hashes ee244d0f0be9acc3ca9c812420ebea8bf034742fd75010c6f817987f8ddc1ad0;
no previewBundle, new packaged candidate or new host run occurred. This prepares
a diagnostic only. Actual host guard proof, performance improvement, final
three-version comparisons and T044/T046/stable-optimization completion remain
unproven. No main merge/push or theoretical-limit claim is made.

### T048 single actual-host guard diagnostic closed (2026-10-02)

This bounded diagnostic is complete. One 5302 on leg was submitted, with no JFR
or resource-observation protocol and no new performance pair. Production
acceptance remains **HOLD_PRODUCTION_ACCEPTANCE**. Stable optimization and a
theoretical bottleneck have not been established.

Wrapper commit fcf8134 admits only the fixed Boolean
`--tri-fresh-edge-guard-metadata` on production on/tl-dump-only legs. Default
prepares omit the option; off, non-production, duplicate and arbitrary-token
cases reject. Full offline wrapper regression passed after supplying the
existing reviewed identity probe explicitly. The original sandbox X11-access
failure and the subsequent missing-probe failure remain preserved; neither was
counted as PASS. Successful log under build/t048-guard-host has SHA
7caca9a3b28472ccc2b5fb99762f44ace331372be1fb13da270a49df6711fa44.

Only :bootstrap:jar was built, not previewBundle. Independently frozen Agent
0dc308898ce9c3c52d7e10015d2be3eab31ba933c59ab285fd3d1af9b14c73e6 differs from
the ee244 performance Agent only in FreshTriangulationEdgeSearch, its $1 and
framework-version metadata. Settings, patcher and other algorithm bytes agree.
Both packaged diagnostic classes' full instruction dumps agree with the tested
classes after numeric constant-pool-index/comment-padding normalization. The
initial padding-sensitive rejection and dumps remain preserved; whole class
bytes were not declared equal. Artifact-pin.json under candidate-reviewed has
SHA679159f1e514df5f4d4bdb92b69fe662a9127398a577de9db5ffbc154d7d2879.

Prepared ID277bc6cce19c5c703c52cb58450b48ac1ba02b85354e80fb42b9ad60aa378d3c
has146 checked inventory entries. Original heavy029e9a4e…, driver3bee41a3…,
official5302 JAR988ef6a8… and real UI seq2344 on config190ea2fc… stay bound.
The UI config was copied as original bytes, not edited by path substitution.
Queue seq2373/job20e880ac-359d-4fe3-984f-a0c8705ef817 ran as
queue-24c42e77d8234cd3bd47a9c44cc84b95, after FIFO wait behind another session.
The authoritative outcome has validationStatus=PASS, cleanup=safe,
normalExit=true, identityVerified=true and fixtureUnchanged=true. Cubism exited;
the Runner stopped the remaining task-owned launcher tree before supervisor
cleanup. This is not a claim that every launcher descendant exited gracefully.

Independent post-terminal review rechecked all frozen inputs and staged Agent,
official/staged JARs, result/payload binding and both production patch receipts.
Captured TriangleList SHA is f3427cec0e5c0c7d93c8a2cf72351a82a39b635b15682afc291eb3a62dc888f5;
the first four ordered outputs equal the retained off baseline (edge counts
1203,1122,132,100 and their ordered hashes).

The exact console receipt reports actual type
com.live2d.graphics3d.editableMesh.triangulation.j, admitted=true,
reason=IDENTITY_EQUALITY, equalsOwner=java.lang.Object, finalType=true,
objectSuperclass=true and coldComputation=true. Its AppClassLoader label is
diagnostic metadata, not unique loader identity proof. Cubism wraps System.err
as ERROR text; this receipt's severity label is not an admission failure.
Console SHA6df7b4c492b0bd45661cc948476f0606d9c868fdbc5e420f0e15dfd7359d9042.
Single-diagnostic/host-review.json SHA
566e51abb21d378e7cfa9d44963b90b8d6ddded143b6b9a560198c9cd7fa9122 binds this
run and the checked outputs.

The guard admitted the real object type, so the earlier weak aggregate benefit
cannot be explained by this guard always rejecting. Cold records do not measure
query count, hit rate or eliminated scans. This single diagnostic does not
change the retained pair's performance verdict: first wall -4.31%, first CPU
+3.46%, three-operation wall +0.65%, observed RSS peak -13.89%. T044/T046,
final three-version stable benefit and resource bounds remain open. This round
adds no retry or performance pair; no main merge/push occurred.

### T049 endpoint-forwarding audit and owned lazy-edge experiment

The previous goal turn made progress by closing seq2373 with actual guard
admission. The stable-optimization goal remains active. Fresh checks found a
clean2869665 worktree and an idle FIFO before the owned benchmarks; no new host
task was submitted. Saved first-operation allocation weights (~13GB for j in
each leg) motivated a candidate, not a claim of precise allocation or causal
time. Its allocation-site share within the full workload remains unmeasured.

Three pinned official JARs were inspected as data through javap; no official
class executed. j/r/TriPoint/l are final, j's point getters and l's three
getters are pure field returns, and TriPoint.getIndex is a pure field return.
The edge-overload of r.a performs two null checks, four endpoint getters and a
call to the existing four-point overload. The latter's floating-point algorithm
would remain untouched. j's constructor checks nulls and index distinctness
behind Kotlin's assertion gate; these checks must remain before intersections.

The complete c site inventory has four constructions and four intersections
in every version. Only its initial three are the proposed stencil. In5203 they
occur at constructor BCIs316/335/354 and intersection366/386/406; the separate
1053/1162 sites stay untouched. In5302/5303 the primary sites are315/334/353 and
365/385/405; separate657/766 sites stay untouched. Initial count assumptions
rejected the additional native site; the initial and reviewed partial dumps
are retained. Final static-review.json SHA
addd5d02b7ddbc8beb799f7bf3b41a298fa001dbe788b5d75488764697e15f40 binds all
official JAR/class hashes and final dumps. This is feasibility, not a runtime
dependency-definition or transformation proof.

The owned Java stencil calls the same owned endpoint primitive in both legs.
Its lazy leg validates all three pairs first, computes all three intersections
in original order, then constructs a distinct edge before each subsequent edge
use. No pool/cache, reorder, new numerical shortcut or persistent state is used.
Native private predicates are represented by an owned stand-in, so differential
checks do not prove full native correctness. Java17 release/lint/Werror compile
and1177 owned checks pass: endpoint identities/order, duplicates, call ordering,
null/assertion failures before intersections, later coordinate mutation, empty
inputs, negative indices, NaN/infinities/subnormals/signed zero and500 seeded
inputs. Native exception stack/handler and transformed-class verification remain
open; this experiment is not production integration.

The initial four-warmup-pair measurement showed within-run compilation drift,
and all raw samples are retained. Final measurements use separate fresh Java17
JVMs per density, no extra JVM flags or inherited option variables,24 alternating
warmup pairs, then seven alternating samples. Each sample covers12 owned
operations with64 constraints/512 triangles. Sparse/dense controls append7 and
14002 fresh edges per operation. ThreadMXBean measures actual main-thread CPU
and allocated bytes. Input/setup/warmup, GC-thread CPU, RSS and retained memory
are excluded; output lists/checksums and result-vector allocation are included.

Sparse median CPU18,114,982→12,719,268ns (-29.79%), wall18,487,933→12,773,764ns
(-30.91%), allocated28,314,912→5,376bytes (-99.98%). Dense CPU19,903,977→
15,518,102ns (-22.04%), wall20,112,952→15,631,121ns (-22.28%), allocated
24,934,560→10,092,768bytes (-59.52%). Measured eager GC counts are2/1 and lazy0/0;
these are observed counts, not GC CPU or a promise to eliminate GC. Own stencils
are not native-host benchmarks or proof of broad stable gains.

Offline-review.json under build/t049-lazy-edge-offline SHA
1935766b09202966bc6f894c04bbad41d32e9dad484658b525af7d85d554c81e binds final
source/log/audit/raw measurement hashes and limitations. Old performance Agent
ee244d0f… and diagnostic Agent0dc30889… retain their exact hashes. No runtime
production source, previewBundle or new host run changed. The evidence supports
an offline bytecode prototype as the next step, with original fourth-site,
private-predicate, runtime-definition, control-flow/exception and full-output
gates. T044/T046, final three-version performance/resource acceptance and the
continuous goal remain open; hold production adoption and main merge/push.

The commit path check rejected a hardcoded machine-home path in the initial
audit script. The final script instead requires explicit --jar-5203/5302/5303
inputs and retains the fixed per-version SHA gate. Re-audit yields the identical
static verdict/digests. Final offline-review-cli.json SHA
423566d10862aadace44efb090c8d8c6559e7eaf57c7a6789c1934b8dd215f23 binds the
final CLI source and unchanged measurements; the initial review remains saved.

### T050 retained allocation-site attribution (prototype still pending)

The preceding goal turn only rechecked terminal state and answered the user's
status question; it did not advance optimization. This turn revalidated the clean
eec4a58 source and idle shared FIFO, then completed the first T050 evidence gate.
No host task, production-source change, previewBundle, merge or push occurred.

`analyze-edge-allocation-sites.py` streams only ObjectAllocationSample events
from the retained recordings, uses the exact first-operation windows and exact
edge binary name, and separates the allocating caller from inclusive h.c frames.
It preserves compact raw edge samples and checks recording/window digests before
and after reading. All event counts, operation counts, all-class weights and edge
weights agree exactly with the prior hotspot report. Five parser tests cover
direct versus delegated allocation, overloads, inclusive time boundaries, missing
stacks/internal names, and invalid weights/event types. Final test log and sources
are pinned in build/t050-lazy-edge-bytecode/offline-review.json.

On/off have3717/3630 edge allocation samples. Edge weights remain
13,118,570,616/13,030,867,680 estimated bytes. Three direct h.c NEW sites at
BCIs301/320/339 account for13,033,429,264/12,964,200,968 estimated bytes:
99.35099%/99.48839% of edge weights and62.20865%/63.02810% of all first-operation
allocation weights. These are sampling estimates, not exact allocated/live bytes
or causal CPU cost. Other callers remain separate, including h.a overloads and
the native lazy n.a path; their inclusive h.c frames are not counted as primary
construction sites.

The frozen ee244 Agent's own patchers were invoked on official5302 class bytes
as data only. Reconstructed h SHA40d0754026a7a2fb7c491e95144b9d8a1a605d7aee44579cb20bb2363962f8e6
matches the saved production-transform receipts, whose original runtime logs
were rehashed. Its c()V has the same three NEW positions as pinned pristine h,
followed by the separate fourth NEW at649. The candidate's constructor calls
remain315/334/353; additional constructor657 stays outside scope. No official
class was defined or executed. A final-defined h digest was not captured, so this
is receipt/bytecode/BCI correspondence rather than independent downstream-
transformation or dependency-definition proof.

Final allocation-site-review.json SHA
508017396dffba54be7ca6c7c1ca2143d22bcc6a5d6de79e223d9d072e0db275 and
constructor-site-review.json SHA
65ad9116e9f9a72159e5dccae82036857d26a960c6f404a84d599dba3509e425 bind windows,
recordings, raw samples, logs and inspected bytes. Offline-review.json SHA
e23c053afaf5fe4710d341f33bc65bd1adaae1332a85a03bed9c9219272e2b83 binds sources,
five-test log, reconstruction/mapping scripts and unchanged frozen ee244/0dc308
Agents. Initial attribution/report/logs remain preserved separately.

The actual allocation-site share now supports implementing the offline lazy-edge
bytecode prototype. T050 remains partial: generated-own-class execution,
unknown-byte rejection, original fourth path/private predicate/order/fresh
identity, null/assertion/exception stacks, class initialization, dependency
definitions and frame/control-flow verification remain required. Neither stable
host benefit nor a theoretical bottleneck follows. Production acceptance remains
HOLD_PRODUCTION_ACCEPTANCE, and T044/T046/the overall goal remain open.

### T050 offline lazy-edge bytecode prototype (production gate pending)

The preceding turn was progress:329fd2f bound the retained allocation samples to
the three construction sites. This turn implemented the offline prototype on the
clean329fd2f source. No production runtime source, frozen Agent, previewBundle,
host task, main merge or push changed. The ongoing goal remains unachieved.

Whole reviewed5203/5302/5303 JAR hashes admit official class bytes only as data.
The prototype adds eight method-local slots, retaining the original first three
constructions and pair computations in each c()V call. Its local ready flag only
changes after all three calculations complete. Later iterations capture six
separate triangle getter observations, preserve original pair validation before
any calculation, and call the existing four-point native overload with identical
endpoint order. A successful-intersection branch constructs its distinct edge
before the original private/inlined predicate. No pool, state registry, ordering
change or geometry shortcut is introduced. The separate fourth path remains.

Null/index/assertion screening calls the untouched constructor when it must fail,
retaining its native failure site and original c caller line. The cold path keeps
original class initialization work before warm screening; actual official class-
initialization failures were not executed here. Warm eligibility still requires
actual admitted pure getters, immutable indices and exact dependency definitions.
No such production gate is implemented in this research source.

Java17 release/lint/Werror compilation and -Xverify:all pass3505 generated-own-
bytecode checks. Both53-style private and5203-style inlined stencils are generated
independently, remapped away official names before execution. Checks cover all64
hit/block masks, stored endpoint identity/order and fresh duplicates, cold/later
constructor and pair counts, nulls/duplicate indices before calculations, assert
on/off, exact algorithm failure-stack prefixes through c and source lines, cold
constructor/compute probes, predicate/fourth-path failures, empty/single/repeated
inputs, later endpoint mutation and100 seeded inputs. A three-face control has
10 original constructions versus4+2*bitCount(hitMask) lazy constructions; original
10 pair calls become4, retaining three cold and one fourth call. These counts
describe owned fixtures, not native allocation or CPU gains. The shared owned
primitive and predicate are stand-ins; no native geometry body executes.

All original executable ASM nodes are retained in order. The independent byte-
data verifier confirms unchanged original constant-pool entries, fields, class
metadata and raw non-c method attributes/code:16 methods in5203,27 in each53
version. c remains public/final without handlers, maxStack6; its maxLocals are
22→30 and18→26, code lengths1308→1673 and944→1309. Official c frames are computed
using header bytes, not official class loading; JVM definition of those official
outputs is still untested. Four owned verifier cases accept valid bytes and
reject wrong JAR binding, changed private-predicate bytes and truncation.

Static output h SHA5203
cf6aa58c4a70c7259555938af7c91fe8cad483a7e0de96e9a0d2fd4957fd2a30;
5302/5303
41bfba9a898a92951e1cefb59e8151003b389c4846dc78d40b88542171b488da.
Final proof under build/t050-lazy-edge-bytecode/prototype/offline-review-final.json
SHAbe49ea56ab75dc9cec7347f58ea824017d35353e223dfe29fccce8f8a89ee7b4
binds56 source/library/class/log/raw-fixture/preservation inputs, the prior
allocation report and unchanged ee244/0dc308 frozen Agents. Earlier logs and
reviews are retained. The initial2731 checks grew to2743 with fourth-failure
controls and3505 with the inlined local-slot variant; these are separate retained
logs, not multiple measurements of host stability.

The allocation and owned-bytecode slices are complete; T050 remains partial.
Next required work is composition with the existing fresh/membership transforms
using admitted production ASM modules, actual dependency admission with native
fallback, and repeated frame/exception/output checks on the composed packaged
implementation. Complete native output, class initialization, UI/startup linkage,
three-version stable benefit/resource acceptance and theoretical-limit evidence
remain unproved. HOLD_PRODUCTION_ACCEPTANCE and T044/T046/goal remain open.

### T050 owned definition collector: bounded research conclusion

The user asked how much remains and whether the work can close. This turn completed
the pending owned-JVM collector check and records a production hold, without
adding host jobs or expanding the performance runs. Read-only FIFO inspection
found unrelated manual-modeling-brush seq2385 running and source-warp seq2386
queued; no optimization host job was running. Those jobs were not changed.

Four diagnostic-only Java sources capture actual Class identities at a temporary
Instrumentation retransformation collector and compare ASM-core semantic
fingerprints. A persistent observer revokes gates on later definition callbacks;
weak class keys and weak gate links avoid retaining application loaders. The
isolated owned agent executes no official class or native geometry. The initial
compile failed Werror on explicit close inside try-with-resources; that log is
retained. Final Java17 release/lint/Werror and -Xverify:all pass28 checks, including
resource-byte deception, same-name different loaders, instruction tampering,
pool/debug/member normalization, missing/duplicate dependencies, invalid maps,
revocation/restoration, two concurrent independent captures, loader release,
idempotent close, unavailable instrumentation, UTF-16 surrogate preservation and
refusal to silently ignore module definitions. No performance benchmark ran.

One explicit limitation control uses real JVM transformations and execution with
an interleaved registration: a capable transformer is installed just after the
collector. Reviewed value3 bytes pass the collector comparison, yet the JVM
executes value9. The selfcheck reproduces this gap rather than treating the gate
as proof of the final definition. ProductionAdmissionReady=false: transformer
ownership, ordering and lifecycle closure remain required. Later-event revocation
does not fix a downstream transformation within the same initial capture.

Final build/t050-lazy-edge-bytecode/definition-admission/offline-review.json SHA
3f4bb3be9a3baf8d9cb0c51cef7e6cd8e3bddb3c577e15116f290f3edd9ff9df binds71
source/library/class/log/command/owned-fixture inputs. Frozen Agents ee244d0f…
and0dc30889… were rehashed unchanged. Runtime production source, previewBundle,
host inputs, main and remote branches were untouched. No extra test rounds or
host pairs are added to this slice.

Research evidence can close with HOLD_PRODUCTION_ACCEPTANCE. Full optimization
acceptance cannot: latest5302 first wall -4.31%, first CPU +3.46%, three-operation
wall +0.65% and sampled RSS peak -13.89% still do not establish stable net benefit.
Remaining work is production integration/definition fallback, complete native
correctness and startup/output verification, then final three-version stable
performance/resources. The new owned lazy-edge gains do not replace those gates.
T050 stays partial, T044/T046 and the continuous goal stay unachieved; no
theoretical bottleneck or reliable remaining-duration estimate is claimed.

### T050 ASM-core port and frozen-transform composition

The previous goal turn made progress: b5f1b96 bound the owned definition collector
and reproduced its downstream-transformer ordering gap. On its clean source this
turn addressed the other production-integration prerequisites, without pretending
that ordering gap was solved. Read-only FIFO inspection still found unrelated
seq2385 running and2386 queued; no optimization job or queue mutation occurred.
No performance measurement requiring a quiet host was running in that snapshot.

The new diagnostic core port records/replays only c method events. It retains all
original executable events in order, including the private/inlined predicate and
fourth path, and inserts the same warm branch, six getter observations, validation,
native endpoint calls and fresh delayed objects as the tree prototype. ClassWriter
retains the input pool and raw other methods; c frames use header data without
loading official classes. Java17 release/lint/Werror compilation uses core ASM
9.7.1 alone; jdeps confirms no tree/commons class reference in the core module.
Tree/commons remain confined to owned-fixture generation and the old test oracle.

Fresh and membership patchers come from the unchanged SHA-pinned ee244 performance
Agent in an isolated loader, with parent shadowing rejected. The public CLI still
requires a fixed official whole-JAR digest; optional --compose requires the fixed
Agent digest before applying fresh, membership, then core lazy materialization.
No arbitrary caller bytes enter through the public CLI. Shape entries are owned
testing/data research, not actual-dependency or final-definition admission.

Expanded tree, core and composed modes each pass3533 owned checks under
-Xverify:all. The original3505 checks remain, with28 membership controls for debug
on/off, three repeated invocations, return values, ordered same-object contents,
debug add effects and6→0 contains calls only in the non-debug fused mode. Native
edge names are remapped before execution, so the frozen fresh guard uses its
native fallback; this does not prove native fast-guard execution. Geometry and
private predicates remain owned stand-ins. No official body is defined or run.

The six standalone/composed official class-data cases pass preservation and
whole-definition fingerprint comparison with the tree oracle. They are also
byte-identical, not merely normalized matches. Standalone hashes remain the prior
prototype hashes. Composed5203 starts at d0fac0cd2c2092db163db7b78bffd011713f2bef17279b08ab088af4e7d27d92
and outputs fb521f43ea595fa33553df81dd66d29ea4958ce1e75c2fe0355b2ead0d5db133.
Composed5302/5303 start at the measured40d07540… and output
5d6a54280e10ee492cec0542701fe3cd7d2830b295976dba14192dca8a83c8b2.
All16/27 non-c methods are raw-identical to their post-membership baselines.
maxStack stays6; locals30/26; code1673/1310 bytes, no handlers. The53 composed c
is one byte longer than the standalone output; the core/tree bytes agree exactly.

The data verifier now accepts an explicit SHA-bound pre-lazy baseline and reports
baseline pool preservation separately from pristine official pool preservation.
It does not mislabel the existing membership transform as unchanged official
bytes. Wrong-baseline and unreviewed-Agent negative controls reject before output
publication. Prior source snapshots from b5f1b96 are retained and match the earlier
prototype review's exact digests; historical reports are not overwritten or
reinterpreted as checks of the expanded current source.

Final build/t050-lazy-edge-bytecode/core-composition/offline-review.json SHA
95e3e6d247d0c9b7802a7ec188bd2fcde9fc31f40bd1ce7c0c5fde30a462b2d3 binds184
source/library/class/fixture/log/command/report inputs, six candidate cases,
historical sources and unchanged ee244/0dc308 frozen Agents. Runtime production
source, previewBundle and host inputs remain unchanged. No main merge or push.

This completes the ASM-core port and offline composition evidence slice. T050 is
still partial: supported transformer-lifecycle ownership/final dependency admission,
production native fallback integration, actual official class/geometry/output and
startup checks remain required. T044/T046 and final three-version stable host
performance/resources remain open. HOLD_PRODUCTION_ACCEPTANCE and the continuous
goal stay active; neither stable improvement nor a theoretical bottleneck is claimed.

### T050 actual official definition and runtime projection

The preceding goal turn was progress:446ca916f completed the core port and
frozen-transform composition. This turn verified its clean state and read-only
FIFO, with unrelated2385 running and2386/2387 queued. No queue task was changed,
no host launch or performance measurement was added, and runtime production source
and both frozen Agents remain unchanged. The goal remains unachieved.

NativeClassDefinitionCheck defines candidate h in an isolated URL loader with
platform parent, reviewed official JAR, fixed ee244 Agent and inventoried sibling
libraries. The whole official/Agent and candidate output hashes are fixed;
Kotlin stdlib1.7.21 has the identical fixed SHA
d46a9d773ffb9dee4ff1a748ac845dc8e50005c589302951760a2b5187bddd19 in all three
versions. Target h receives the original entry signer/domain and package metadata
to model transformed signed-class loading; this does not assert an official
signature over the derivative bytes. Expected application/Kotlin origins and
actual Class identity/loader are checked.

Class.forName(false), declared method/constructor/field resolution and actual
retransformation verify eight types without invoking official initializers,
constructors or geometry. Owned controls have a failing initializer: metadata and
capture must leave it untouched, after which explicit owned initialization throws
as expected. Another owned class with a void return in an int method is rejected
with VerifyError, demonstrating the verification path is active. The only premain
is the owned instrumentation provider, which registers no transformer; the owned
observer/collector are the controlled capture pipeline. No official code is invoked.

Initial5302-composed and raw-capture runs failed full fingerprint comparison;
their logs and observed bytes are retained. All eight types differed. Javap on
Assertions and TriPoint identifies omitted RuntimeInvisibleAnnotations/parameter
annotations, omitted Deprecated attributes and reordered methods; pool/debug/frame
differences were already normalized. Runtime projection therefore excludes only
invisible CLASS-retention annotations and the ASM Deprecated pseudo flag, while
retaining all executable/control-flow/handler/field/member/runtime-visible data.
The existing full static definition-v1 fingerprint remains separate and all six
prior candidate fingerprints still match their pinned oracle reports. New runtime
namespace is runtime-definition-v1; code and visible annotation tampering remain
refused. This is an executable/runtime projection, not a claim to reconstruct
omitted class-file metadata.

The diagnostic recorder receives a defensive byte clone and cannot alter the
JVM buffer. Mutation and recorder-exception controls show definitions unchanged;
recorder errors/null refuse admission. Final Java17 release/lint/Werror compile,
-Xverify:all and36 owned admission checks PASS, including the still-reproduced
later-transformer false-positive boundary. No production admission is enabled.

Linux OpenJDK17.0.20 passes all six final standalone/composed cases across the
three official versions, each with eight actual runtime fingerprint matches.
Composed h hashes remain5203 fb521f43… and53x5d6a5428…; metadata resolution defines
24/25 loader types, but only the eight explicitly reviewed dependencies are claimed
as captured/matched. Raw reviewed and observed bytes, eight dependency records,
method/field metadata, classpath inventories and receipts are saved per case.
Initial and intermediate results are retained independently; previous source
snapshots from446ca916f preserve historical evidence.

Final build/t050-lazy-edge-bytecode/native-definition/offline-review.json SHA
e0443a1e612a759be0b310f4927bf6c323c912835d8fc937ad3a831497e19f77 binds568
source/library/class/log/raw-capture/receipt inputs and134 distinct classpath JAR
paths, with both frozen Agents rehashed unchanged. Official JARs/BAT/models/golden,
previewBundle, main and remote branches were untouched.

Actual controlled definition/frame/link checks and the runtime projection are now
complete slices. They do not close production transformer ordering: a collector
still cannot prove final bytes under an arbitrary later transformer. The next gate
requires enforceable supported lifecycle ownership or final-method definition
evidence, followed by JDK-only guarded integration/native fallback. Native
initialization/error/geometry/full output and final three-version stable host
benefit/resource/UI/startup acceptance remain open. T050 stays partial, T044/T046
and the continuous goal remain active, HOLD_PRODUCTION_ACCEPTANCE persists, and no
theoretical bottleneck is asserted.

### T050 owned lifecycle gateway and operation leases

The preceding goal turn only checked and reported status (no progress). This
turn implements the JDK-only TriangulationDefinitionLifecycle in runtime and
passes its handle to both premain contributors and the bootstrap thread before
JvmShims installs. No host job, performance pair, previewBundle, default startup
flag, official BAT/model or frozen Agent was changed.

Supported ownership requires one trusted premain Agent with the reviewed entry
manifest, the actual HotSpot DisableAttachMechanism value enabled, no native/debug
Agent or external boot/module replacement options, and retransformation support.
Other starts retain their raw Instrumentation and cannot admit a lazy gate.
Bootstrap withholds the raw handle from downstream installers. This is a trusted
in-process protocol, not protection against malicious JNI/reflection or a raw
handle escaping that protocol. Existing multi-Agent host validation does not
satisfy this contract and has not been relaunched with ineffective gates.

Capture and definition/registration mutations take exclusive ownership before
JVM entry. A full optimized operation must hold a thread-confined read lease,
including exceptional exit. Mutations wait outside the JVM, then permanently
revoke prior gates. Recapture revokes old gates before retransformation even if
the new fingerprints fail. Callback mutations/leases and read-to-write upgrades
refuse without waiting; search-path appends preserve existing behavior because
they cannot replace loaded dependencies. Collectors clear their Class/fingerprint
references after capture and retained gates do not root application loaders.

Final review found an inherited module-aware callback bypass: its method was
declared by a base not implementing ClassFileTransformer. The real-JVM red control
fails at that precise assertion. Source and owned-inherited-before-fix.log are
retained. Final detection also recognizes the trusted java.instrument transform
dispatch frame. Java17 release/lint/Werror and -Xverify:all pass115 owned checks
over six JVM cases:45 system-loader checks,46 Boot-Class-Path/bootstrap-loader
checks, and four6-check rejected-start cases (attach enabled, extra Agent, debug
option, and a later option overriding attach-disabled). These execute generated
owned classes only, with real captured/changed value3/value9 behavior. They cover
concurrent late registration, mutation waiting for a live lease, callback/upgrade
refusal, failed recapture revocation, clone integrity, restore/re-admission,
installer handoff, wrong-thread close and loader release while retaining a gate.

Thirteen existing bootstrap regressions pass for the ordinary startup failure and
settings paths. DevCheck passes after documenting four public API items; the
initial failure is retained. Jdeps reports only java.base, java.instrument,
java.management and jdk.management. JDK17.0.20 executable/compiler/libjvm hashes,
compiled classes, commands, logs and XML receipts are bound in
build/t050-lazy-edge-bytecode/owned-lifecycle/offline-review.json SHA
185b5fbbfb80c0b6faa47a425d1427625cccf0bc5126d51696b497195f31cc29 (66 inputs).
Both ee244 performance and0dc308 diagnostic frozen Agents are unchanged.

This completes the owned lifecycle mechanism slice, not lazy production or host
acceptance. Eligible packaged Turboism bootstrap execution, official dependency
fingerprint integration, full operation lease/finally/native fallback and a
supported single-Agent probe composition remain pending. Official native
initialization/geometry/full outputs and final three-version stable repeated
performance/resources/UI/startup evidence are still required. T050 stays partial,
T044/T046 and the full goal remain unfulfilled, HOLD_PRODUCTION_ACCEPTANCE remains,
and no stable improvement or theoretical bottleneck is claimed.
