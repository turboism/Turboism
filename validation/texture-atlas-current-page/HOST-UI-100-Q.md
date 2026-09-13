# Authorized 100-item UI validation after q guard

User approved Circle100 and Geometry100, **new algorithm only**, serial/parallel each once. No native rerun; no other sizes. Four fixed jobs have been submitted through the unified local manager with 1800-second hard task budgets (startup included, queue waiting excluded). Do not retry, resubmit, extend timeouts or bypass the manager. Builds completed before submission. No Runner/worker/hook changes.

Latest production includes q fail-closed guard and small-parallel serial preflight. **100 items do not exercise the <32 preflight.** Normal q=1 entry is checked in host; q!=1 rejection remains an offline regression, not an injected host scenario.

The fixed auxiliary driver now explicitly sets the existing parallel checkbox through Swing and validates the observed planner flag. Only new UI100 has an admitted parallel variant; no additional dependencies or hooks. Probe: 50 offline contract assertions PASS; scheduler focused gates PASS. Both textures are 4096×4096 px; Circle has equal-sized circles, Geometry heterogeneous shapes/sizes. Actual dimensions and input hashes must still be verified from each result.

## Fixed artifacts

- Production Agent SHA-256: `9ce70a7024838022d9e9a77fbda42d55166299eb7d247efbf95a8869de495ccd`
- Atlas plugin: `43bc240ba8778357b9fc7aaea157bf6c1614d8380f23e5d0a8168d51f16cd841`
- Auxiliary probe: `e18f5c85ec45740d6723755608b905f72949350f891b9bd744dadd1047f3de2a`
- Circle source fixture: `2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e`
- Geometry source fixture: `369c906ad47610a958e770930649f0821f616e8eb66564c39a478c41b49024ff`

## Immutable job ledger

Prepared IDs, request keys and submit responses are in `build/atlas-ui-100-q/jobs.json`; snapshots were hash-checked before submission. Request key prefix is `atlas100-q-v1-`, followed by the case below.

| Case | Job ID |
| --- | --- |
| ui-circle-100-new | `69972dcd-89f1-49f7-b7d6-a7e741f93e57` |
| ui-circle-100-new-parallel | `24371df3-735e-4ac8-a0a3-17f20a753224` |
| ui-geometry-100-new | `78d2e1e4-cc5b-4a8b-96e8-980b0b52d4cf` |
| ui-geometry-100-new-parallel | `40d44ba9-e2f1-4528-adec-468052d256ce` |

## Acceptance criteria (not met)

Submission is not PASS. For each terminal succeeded job verify validationStatus PASS, identityVerified/fixtureUnchanged/normalExit true and safe original-bound-cgroup cleanup with kernel proof. Read its exact taskDir/turboism-home/state/atlas-validation/validation.json: one handled invocation, expected plannerParallel, geometry, count, scale, actual input hash and UI lifecycle. Raw UI time includes input-probe cost; output verification follows progress close. Preserve full-precision samples and sourceEvidence, N=1. Failures remain failures, never inferred durations or ratios. Historical native and prior UI tables remain unchanged.

## First terminal failure (notification sent)

Circle100 serial job `69972dcd-89f1-49f7-b7d6-a7e741f93e57` failed before layout: `driver-error.txt` reports `java.lang.IllegalStateException: java.lang.IllegalStateException: No production ingress`. `layout-started.txt` and timing JSON are absent. No completed timing or performance ratio; this is not a sorting timeout. Raw manager evidence: `build/atlas-ui-100-q/ui-circle-100-new.terminal.json`.

Supervisor cleanup is safe: original bound cgroup inode 48560, `cgroup.kill+cgroup-destroyed`, kernel errors empty. Post-containment source/copy fixture, official JAR/BAT and staged artifacts retain expected hashes. Full validation/normal-exit acceptance did not pass. No retry or rebuild; remaining three fixed jobs continue under the existing authorization. Failure notification sent on this heartbeat; do not repeat it unless new evidence changes the diagnosis.

## Second terminal failure (notification sent)

Circle100 parallel job `24371df3-735e-4ac8-a0a3-17f20a753224` also failed before layout with `No production ingress`; layout-started.txt and timing JSON are absent. This does not establish a parallel planner failure: the planner was not measured. Raw evidence: `build/atlas-ui-100-q/ui-circle-100-new-parallel.terminal.json`. Safe original-bound-cgroup cleanup: inode 48612, cgroup.kill+cgroup-destroyed, errors empty. Post-containment fixture, official artifacts and staged hashes match. Full acceptance remains FAIL/normalExit=false. Geometry serial is running and Geometry parallel queued at this observation. No retries or builds; second failure notification sent.

## Third terminal failure (notification sent)

Geometry100 serial job `78d2e1e4-cc5b-4a8b-96e8-980b0b52d4cf` failed before layout with the same `No production ingress` error. Layout marker and timing files are absent; no measured duration or ratio. Raw evidence: `build/atlas-ui-100-q/ui-geometry-100-new.terminal.json`. Original-bound-cgroup inode 48664 safely cleaned by cgroup.kill+cgroup-destroyed, kernel errors empty. Post-containment source/copy fixture, official JAR/BAT and staged artifact hashes match. Validation FAIL and normalExit=false remain explicit. Only Geometry100 parallel is still running at this observation. No retry/build; third failure notification sent.

## Final outcome: four failures before layout

All four jobs are terminal failed; host is idle. Geometry100 parallel also reports `No production ingress`, with no layout-started.txt, timing JSON or validation.json. Its original bound cgroup inode 48716 was safely destroyed, kernel errors empty. All four source/copy fixtures, official JAR/BAT and staged artifact hashes match post-containment. Safe cleanup is not successful algorithm acceptance: validationStatus FAIL and normalExit=false remain explicit.

| Dataset | Requested mode | Fixture texture (px) | Result | Entry/UI time |
| --- | --- | --- | --- | --- |
| Circle100 | serial | 4096×4096 | FAIL before layout: No production ingress | unavailable |
| Circle100 | parallel | 4096×4096 | FAIL before layout: No production ingress | unavailable |
| Geometry100 | serial | 4096×4096 | FAIL before layout: No production ingress | unavailable |
| Geometry100 | parallel | 4096×4096 | FAIL before layout: No production ingress | unavailable |

Texture dimensions above describe the known fixtures, not a new runtime snapshot. Requested mode is not an observed planner execution. Each case had one host attempt (N=1), **zero measured layout invocations**. No new speedup, scale, placed count or actual-input hash is available. [CSV](host-ui-100-q-samples.csv) retains job/prepared/request IDs, fixture/artifact hashes, exact evidence paths and cleanup identities; missing measurements are blank, never zero. Historical host measurements remain intact and apply to their earlier artifacts. No native rerun, no retries, no evidence that the <32 preflight or q!=1 path ran. Root cause of missing ingress is unresolved; task26 stays in progress pending diagnosis and separately authorized revalidation. Completion notification sent; monitoring heartbeat is to be deleted.
