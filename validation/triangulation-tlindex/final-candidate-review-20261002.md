# Final Triangulation Candidate Acceptance Review (2026-10-02)

The code fix, the three-version native performance pairing, the default-settings UI, and the six independent restart verifications are complete. On 2026-10-02 the user said "approved, commit". The current candidate state is **APPROVED_BOUNDED_CANDIDATE_WITH_DOCUMENTED_LIMITATIONS**: the three-round resource bounds below are approved, and the listed Lane C host evidence received manual review. The approval record is [candidate-approval-20261002.json](candidate-approval-20261002.json). The parts of T044/T046/T050 that fall outside this triangulation slice are not ticked in full by this approval.

This round of data verifies the real benefit of index triangulation; it does not replace Atlas multi-page image SC-02, arbitrary projects, native Windows, or long-term memory stability acceptance. No repeated performance A/B is scheduled.

## Performance and Complete Results

Same final production base, exact versions, hardware, reviewed heavy fixture, and real UI save configuration. Each leg ran three real native commands with 711 sources per cycle, 2133 rows in total; all 11 semantic fields, the complete geometry SHA, and the first four-order edge digest matched between the off and on legs. No target was replaced by a UI reopen alone. All six measurement tasks passed the standard identity, fixture, normal exit, and safe cleanup gates.

| Version | Three-command duration change | Total Java CPU change | Operation RSS peak MiB (off to on) | On-leg third minus first resident MiB |
| --- | ---: | ---: | ---: | ---: |
| 5203 | -32.63% | -30.54% | 2570.91 to 3029.64 | +1.688 |
| 5302 | -29.89% | -27.15% | 2567.97 to 2789.23 | +2.207 |
| 5303 | -27.33% | -16.40% | 2668.80 to 2609.53 | +42.855 |

RSS, PSS, heap, and CPU are all real samples; 100% CPU means one logical core. The limits of the unsampled boundary intervals, the missed short-lived helper processes, the JFR clock calibration assumption, and the statistical allocation weighting stay in the per-version reports. The first cycle of 5303 had slightly higher CPU, and the total CPU column does not hide that fact.

The operation peak of the 5203 on-leg was 458.723 MiB higher than off (17.84%), and its pre-operation baseline was already 474.781 MiB higher. The baseline difference cannot be attributed directly to the index and does not remove the absolute memory cost. The 5203 off-leg third resident window contained a native automatic backup while the on-leg window had no matching sample, so the off-leg +2515.109 MiB is no evidence of quiet residency or of memory saved by the index. The 5303 on-leg third-minus-first **+42.855 MiB** still needs review and cannot be claimed to prove the absence of a leak.

## Default UI and Six Independent Restarts

The 5303 default configuration seq2466 PASS: the real menu, the active model, 13 native frames, the default selection of a missing key, Apply, Cancel, and OK, and close and reopen of the settings were all correct. The six restarts below used only the production base and the validation plugin, with no companion, sidecars, or helper agent; they still went through the official BAT, the unified FIFO, isolated prefix, home, and fixture, with attach disabled.

| Version | Startup configuration | Sequence | Job | Real UI and all standard gates |
| --- | --- | ---: | --- | --- |
| 5203 | off | 2476 | `a32fafc8-4dd2-41c0-af58-4f2cc0fa701e` | PASS |
| 5203 | on | 2478 | `6bdca4cb-b347-42cb-8925-605f6b96302e` | PASS |
| 5302 | off | 2480 | `53286da7-8e10-432c-9f1e-5342a63bf673` | PASS |
| 5302 | on | 2481 | `853762f2-2b58-4da3-aafe-3baf69d5b29b` | PASS |
| 5303 | off | 2482 | `1bc93cb3-34f7-429b-8c75-6e7679ababf2` | PASS |
| 5303 | on | 2483 | `5c62c551-8bc9-473f-b685-84823668da36` | PASS |

The real initial selection matched the on-disk configuration, Apply saved the opposite value, Cancel kept the applied value, reopen was correct, OK restored the entry value, and a further reopen was correct. The pre-write permission preparation recorded identical before and after SHA values; the two configurations saved by the real UI are byte-identical to every performance pairing input. A normal INFO log carries no DEBUG installation record and no registration was inferred from a missing record; algorithm enablement is supported by independent native execution evidence, and a UI PASS alone is never called proof of algorithm performance.

The read-only configuration FAILs of the original seq2469–2473 are kept in full; seq2474 was cancelled while queued. The fix lives only in an explicit opt-in of the validation plugin, binds exactly to the task directory, a non-symlink ordinary configuration, and its SHA, and gains a task config owner write permission before it goes through the real UI save. It never wrote the switch value directly and modified no shared Runner, production gateway, original or prepared configuration, official file, or golden. The 13 file-boundary self-checks, the independent probe packaging, and one devCheck passed; the fix commit is `4a2cfcf83`. The official safe cleanup engine reclaimed the five old failure prefixes by exact task, and the original diagnostic evidence stays reviewable.

## Resource Bounds Submitted for Review

The bounds below were approved by the user on 2026-10-02 and apply only to the listed final artifact, versions, heavy fixture, and three-round matched protocol:

| Metric | Measured worst value | Proposed bound |
| --- | ---: | ---: |
| Three-command total Java CPU regression | No regression, lowest reduction 16.40% | No regression (≤0%) |
| Operation absolute RSS peak growth over off | +17.84% | ≤20% |
| On-leg operation peak above its own baseline | 68.133 MiB | ≤80 MiB |
| On-leg third minus first resident over three rounds | +42.855 MiB | ≤64 MiB |

The memory bounds are rounded review ranges slightly above the observed maximum; three cycles cannot estimate long-term drift or prove a stable plateau. These bounds apply only to this candidate, the three exact versions, the specified heavy fixture, and the three real command cycles. They widen no version or model admission and change no original Atlas acceptance criterion. The user accepted these reported memory costs and the limited scope; approval does not mean long-term stability is proven.

Manual review provenance: SC-04a of the authoritative spec states that the concrete CPU and memory regression ceilings wait for a measured baseline and are then proposed for user review, and that no approved threshold may be invented; section 4 of the Turboism Spec Kit states that Lane C exact-host evidence receives a manual review. A host-run authorization alone is neither a resource threshold approval nor a merge permission.

## Frozen Artifacts and Evidence

- Production base SHA256: `ddb1ba0c66d953be81d4fe99047075d3c4905e4e225887f7a9c380ffe016d6f2`
- New validation probe SHA256: `c3ae3bb61e92b802c747753df76c7520a43cb5ce813339c273fd2ee3eead010d`
- The old frozen probe `30f5203e…` is unchanged.
- UI off SHA256: `8945ee224b724641a5b468cf087be0287cb3908bca908c7abdc0c582bec9b846`
- UI on SHA256: `190ea2fcf912c290a4db40ccd512becf4d3f032098f9cbeca339c2f0903cce02`
- Current summary: `build/t050-lazy-edge-bytecode/final-owned-startup-ui-r2/final-candidate-review-r2.json`
- Current summary SHA256: `09e735763771ff648efd84d94e96d9c5f879a92bb8169d608af05e2baf7adbe9`
- Post-write re-check: 1013 independent inputs were re-hashed with zero differences.
- The original summary `final-candidate-review.json` and its SHA `259914f7…` stay unchanged.

Summary r2 corrects the original summary's use of a historical uncalibrated JFR allocation estimate. 5203 and 5303 now reference their own completed clock calibration review: edge allocation weight reduced by 75.7186% and 79.4476%. The old window estimate of 5302 is explicitly marked uncalibrated. The allocation weight is a statistical estimate only and takes no part in the CPU and memory acceptance bounds above; the duration, CPU, RSS, PSS, heap, UI, authoritative terminal state, and resource bound proposal all keep their original values. This report correction added no host run and changed no production code.

See the [host evidence](host-evidence-20261002.md) for the complete per-version history, calibration, and attribution limits. The summary binds the standard outcome, the prepared inventories, the real UI configuration and result, the production artifacts, the old failures, the completed per-version review reports, and the original input SHAs. A derived report never rewrites the authoritative terminal state.

## Ten-Round Memory Diagnostic Follow-Up

See the [2026-10-02 ten-round validation closeout](memory-stability-closeout-20261002.md). seq2492 enabled and seq2497 disabled ran 10 times each with 7110 results, all 11 fields matched one by one with zero differences, and the standard host gates passed; the seq2494 queue timeout FAIL is kept. Native automatic backup produced multi-gigabyte allocation with the feature on and off; a long-run RSS plateau and the absence of a leak were not established, and the whole affine clock attribution of the long disabled recording failed. Production stays HOLD / NOT_PASSED, the original three-round performance values are unchanged, and T044/T046/T050 stay partial.

## User Approval Record (Supersedes the Historical Pending-Review State)

On 2026-10-02 the user said "approved, commit", which approved the three-round resource bounds of this candidate and the listed host evidence and accepted the reported limits of the ten-round diagnostic. That approval lifts the resource and Lane C manual review wait of this limited candidate; it supplies no long-term resource budget, stable plateau, leak-free, or theoretical-optimality evidence. The original frozen JSON, hashes, and every failure stay unchanged, and an independent record links the approval.
