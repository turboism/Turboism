# 2026-10-02 Ten-Round Native Triangulation Validation Closeout

This diagnostic batch is complete. On 2026-10-02 the user said "approved, commit", which approved the resource bounds and host review of the limited three-round candidate and accepted the limitations of this report; see the [approval record](candidate-approval-20261002.json). Long-run memory stability and the theoretical performance bottleneck remain unproven; this approval does not extend to those technical conclusions and does not tick T044/T046/T050 in full.

## Confirmed Gains on Three Versions

The numbers below reuse the original three-round matched A/B of the frozen final artifact and do not re-claim a performance gain from this batch's longer rounds with a different queueing strategy.

| Cubism | Three-round time off to on (s) | Time reduction | Java CPU reduction |
|---|---:|---:|---:|
| 5.2.03 | 229.00 to 154.27 | 32.63% | 30.54% |
| 5.3.02 | 103.98 to 72.91 | 29.89% | 27.15% |
| 5.3.03 | 102.29 to 74.33 | 27.33% | 16.40% |

The original three-round RSS cost, the settings page defaults, and the six independent restart results remain in the [final candidate review](final-candidate-review-20261002.md). The approved three-round budget is not extended to the longer rounds.

## New Host Evidence in This Batch

- Enabled seq2492: 10 real commands, 7110 results; disabled fix re-run seq2497: the same 10 commands and 7110 results. All 11 semantic fields of both legs matched with zero differences, including every position and index digest; normal exit, exact identity, fixture before and after hashes, and kernel task cleanup all passed.
- First disabled seq2494: 9 commands completed and the 10th MESH_CONNECT timed out after 5 seconds of queueing with state=TIMED_OUT, so the command did not execute; the formal FAIL is kept. The safe scope cleanup is confirmed, the 31 log, state, and backup models were saved and verified, and only its failure prefix was reclaimed by the official cleanup engine.
- The driver generator gained compile-time cycles=3..12, and every generated source at the default 3 is byte-identical to the old protocol. Only a long round applies bounded queue retry to a MESH_CONNECT that has not started before the cancellation; STARTED/COMPLETED, other operations, an expired entry, and an interrupt are all rejected. The nominal 60-second queue deadline plus a final barrier of at most 5 seconds cannot duplicate a command that already started.
- The final extended driver passed 38 self-checks, two Runner dry runs, and devCheck; the generated source of the actual re-run driver matches the final self-check driver. Neither the production base nor the companion changed.

## Memory Conclusions and Scope

Round 3 of the original three-round 5303 run raised heap commit from 2000 to 2040 MiB, close to the RSS increase of about 42.86 MiB; the existing bounded clock audit supports that correspondence but cannot prove that every increase came from growth.

The enabled leg produced 1 native automatic backup, logged at about +3439 MB; the disabled re-run produced 2, the first at about +3939 MB. The failed disabled leg also logged +3446 MB and +2818 MB. A host backup clearly produces multi-gigabyte memory events. Heap usage fell substantially after a natural GC, which shows that many temporary objects are collectable; it does not prove that the index never leaks and does not attribute every byte to a backup.

| Round | Disabled resident RSS median MiB | Enabled resident RSS median MiB |
|---|---:|---:|
| 1 | 2716.68 | 2713.38 |
| 2 | 2717.74 | 2045.02 |
| 3 | 2718.17 | 2074.98 |
| 4 | 2718.43 | 2094.06 |
| 5 | 6277.91 | 2205.25 |
| 6 | 5715.30 | 5748.77 |
| 7 | 5719.38 | 5795.90 |
| 8 | 4422.81 | 5841.28 |
| 9 | 4440.46 | 5849.90 |
| 10 | 5701.25 | 4982.16 |

The two legs differ in baseline, backup count and timing, and queueing strategy, and neither formed a stable RSS plateau in the second half. The last-round difference cannot be read as memory saved, and the approved three-round 64 MiB ceiling cannot accept the longer rounds. No backup was disabled, no GC was forced, no failure was deleted, and no original three-round gate was relaxed.

The 11 resident anchors of the long enabled leg support a single bounded affine clock; the disabled leg cannot satisfy every anchor at once, and the first clock audit failure is kept. The disabled leg's JFR heap values are reported as an observation over the whole recording and are not forced onto individual native command phases. The CPU, RSS, and PSS arithmetic and the result digest of the native markers are unaffected by that attribution limit.

## Frozen Evidence and Remaining Acceptance

`build/t050-lazy-edge-bytecode/memory-stability-r2/closeout-review.json`, SHA `7fb10b7e09147e6c847fa6ead6b580013ff76a4b3149cf8c17dcb4b8d0b7b1d2`; re-checking the 75 new evidence pins found zero differences. The original aggregate r2 is unchanged. The Runner reclaimed the successful on/off prefixes of this batch, the failure prefix has its own receipt, and the diagnostic and backup models are kept.

The user approved the three-round resource budget and the Lane C manual review of this limited candidate. A separate long-term stability acceptance still needs its own long-run resource budget and stability criterion. Continued sampling for long-term stability must record synchronous clock events and stratify by native backup phase; repeating the same three-round or ten-round sampling cannot replace that acceptance definition. The existing evidence supports an effective performance optimization and consistent results across ten rounds; it does not support indefinite stability or theoretical optimality, and the manual approval of the limited candidate is an independent decision that changes none of these measured facts.

The HOLD state in the frozen closeout JSON is the historical result before the user approval; that artifact and its hash are not rewritten, and the independent approval record now carries the current decision.
