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
