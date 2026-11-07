# Multi-version native-host validation of the promoted texture preparation

Status: **completed for 5.1.03, 5.2.03 and 5.3.04 on one native-Windows machine**.
These are screening measurements on one fixture, one GPU and one CPU; they are
not a statistical guarantee or a release-wide performance promise.

## What was run

- Production agent: the rebuilt `turboism-agent.jar` from this branch
  (SHA-256 `55b72ac6eb79a85ac9bddd387d8c1300b54b886e6eff65f80bc478ad2d27267e`,
  source revision `de65419b6`). The production source did not change during
  this validation.
- Official hosts: Cubism 5.1.03, 5.2.03 and 5.3.04. All three bundled
  JVM `17.0.3.1+2-LTS` and the reviewed JOGL
  (`7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9`).
- Fixture: 763,166,356-byte `bench.cmo3`, SHA-256
  `83326ac519dd5e4cad9b39e4c7234410cc62bf0d1f5241364561d6906292b6da`.
  The optimized load made 11 preparations covering 234,881,024 pixels /
  939,524,096 output bytes, including the two 8192² images. All 11 were
  `TYPE_INT_ARGB`; this fixture exercised **zero ABGR inputs**.
- Isolated home, AppData/profile copies and a fresh model copy per run. The
  runner checked source/copy/artifact hashes, normal exit and Job Object cleanup.

Test-only host accommodations used for these runs:

- `--dismiss-trial` clicked the official trial welcome dialog through the
  in-JVM auxiliary agent.
- The 763 MB `bench.cmo3` was saved by Cubism 5.3.2, so hosts older than that
  raise the “file saved by a newer editor” warning. During the first pass this
  harness answered that warning itself; that duplicate responder was **removed**
  because the repository requires every license/version prompt to be answered
  only by the audited `dev.turboism.validation.NativeFreeStartup`. Older hosts
  are now exercised with the official `hiyori_free_t08` fixture instead, which
  needs no such warning.

The trial-dialog dismissal is a validation-harness option, not product behavior.

## Shadow correctness

All three hosts transformed the pinned factory owner for their generation and
compared every preparation with the unchanged native JOGL representation.

| Host | Factory owner | Runs | Preparations | Compared bytes | Mismatches | Rejected | Cleanup / exit |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 5.1.03 | `shader.w` | 1 | 11 | 939,524,096 | 0 | 0 | safe / 0 |
| 5.2.03 | `shader.y` | 1 | 11 | 939,524,096 | 0 | 0 | safe / 0 |
| 5.3.04 | `shader.A` | 2 | 11 each | 939,524,096 each | 0 | 0 | safe / 0 |

The second 5.3.04 shadow run (`5304-shadow-final`) repeated the result with the
final shipped harness build: 11/11, 939,524,096 bytes, 0 mismatches, 0 rejected.

Shadow runs also verified observer attachment before the first call,
source-pixel immutability, callback/registry removal and class-byte restoration.
Shadow wall/CPU numbers are **not** performance samples.

## Off/on loading comparison

Sequence per host: `off, on, on, off`, plus one final `off, on` pair on 5.3.04
with the shipped harness build. Big-fixture runs only; the 398 KB sanity fixture
is excluded. Times are agent-entry to stable document publication. Allocation is
the sum of observed per-thread allocation maxima, a lower bound; it is not
retained RAM, RSS or VRAM. The primary cohort is `clean` (after removing a verbose
diagnostic window dump) plus `final`; the diagnostic cohort is retained in
`loads.csv` but is not used for the medians below.

**Cohort caveat:** the 5.1.03 and 5.2.03 rows below were measured while the
harness still answered the newer-file warning itself. That responder has been
removed for policy compliance, so those two rows are retained as historical
screening data but are **not reproducible with the current harness**. The 5.3.04
rows need no such warning.

| Host | Arm | Runs | Wall median (s) | Wall range (s) | CPU median (s) | Observed alloc median (GB) | Preparation median (ms) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 5.1.03 | off | 2 | 88.853 | 86.128–91.578 | 220.891 | 57.454 | — |
| 5.1.03 | on | 2 | 85.757 | 84.135–87.379 | 209.109 | 49.606 | 1,781.6 |
| 5.2.03 | off | 2 | 74.722 | 74.384–75.061 | 182.703 | 22.043 | — |
| 5.2.03 | on | 2 | 72.385 | 71.455–73.315 | 170.195 | 14.877 | 1,664.8 |
| 5.3.04 | off | 3 | 79.177 | 78.288–80.939 | 186.594 | 22.009 | — |
| 5.3.04 | on | 3 | 73.069 | 72.627–77.483 | 165.969 | 14.872 | 1,631.6 |

Medians versus off:

| Host | Wall change | CPU change | Observed allocation change |
| --- | ---: | ---: | ---: |
| 5.1.03 | **−3.5%** | **−5.3%** | **−13.7%** |
| 5.2.03 | **−3.1%** | **−6.8%** | **−32.5%** |
| 5.3.04 | **−7.7%** | **−11.1%** | **−32.4%** |

Interpretation:

- In this cleaner block, all three hosts show a **3.1–7.7% shorter wall median** and
  **5.3–11.1% lower CPU median**, alongside lower observed allocation. This is a
  consistent screening signal, not a statistical guarantee: two to three samples
  per arm, one model, one machine, no randomized order and no significance test.
- The 5.2.03 and 5.3.04 wall ranges are separated in this block; the 5.1.03 ranges
  overlap (off minimum 86.128 s, on maximum 87.379 s). Treat 5.1.03 as the weakest
  timing evidence. The third 5.3.04 pair (`*-f1`, shipped harness) reproduced the
  direction: 79.177 → 72.627 s.
- The observed-allocation reduction is about one third on 5.2.03/5.3.04 and about
  one seventh on 5.1.03, whose baseline is much larger. This remains a sampled
  lower bound, not a retained-memory or peak-memory claim.
- Preparation callback cost is now roughly 1.7–1.8 s; the wall reduction is of the
  same order. The block cannot by itself apportion the gain between the direct
  converter, the large-image admission and ordinary run-to-run variation.
- The earlier diagnostic cohort (with a verbose per-poll window dump) produced
  contradictory wall medians and is retained only as harness history. This is why
  the report distinguishes the cohorts instead of pooling them.

## Failures and limits

All attempts are retained in `attempts.csv`; none are silently discarded.

- In the earlier diagnostic cohort, `5103-on-r2` failed while opening the
  newer-saved fixture with “file was closed/interrupted”; the replacement
  `5103-on-r2b` passed. The clean `5103-on-c1/c2` reruns did not repeat it. The
  failed run is retained but is not a timing sample.
- The 763 MB fixture was **saved by Cubism 5.3.2**. Hosts older than that cannot
  open it, so their timing rows above come from an earlier harness pass that has
  since been superseded; the cross-version runtime evidence now rests on the
  official hiyori fixture below, which every reviewed host opens natively.
- 5.3.04 and earlier trial hosts required dismissing the official trial welcome
  dialog. Without the test-only dismissal the document never opens, and the
  attempt correctly fails as “document did not become ready”.
- Only one model, one GPU (Intel UHD 770), one machine, two samples per arm, no
  statistical test. No interactive FPS/deformation measurement was made.
- The two 8192² inputs fit the promoted 67,108,864-pixel / 256 MiB target bound;
  the shadow check confirms the promoted product path was actually used.
- The test-only auxiliary agent changed across harness iterations (trial and
  warning automation, then removal of a verbose diagnostic dump). The production
  agent hash was constant. All four clean timing runs per host used auxiliary
  SHA-256 `029baf2894f083163448cc9baa1bd542f76a254dad1f439b2629fc91042ca7f2`.
  The shadow runs used slightly earlier auxiliary builds; the pixel/metadata and
  restoration logic was unchanged. `attempts.csv` records the exact per-run hash.

## Reproduce

Run one case at a time with no build or other Cubism/Java session. For an older
host with the 5.3.2-saved fixture:

```sh
python testing/host-validation/image-archive/benchmark/run_native.py \
  --host '<official host>' --version 5203 \
  --fixture '<bench.cmo3>' \
  --agent 'build/preview/<worktree>/turboism-agent.jar' \
  --auxiliary 'build/texture-upload-host-validation-exerciser.jar' \
  --output 'build/texture-host-multiversion/5203-shadow-s1' \
  --arm direct --mode shadow --dismiss-trial --accept-newer-file-warning \
  --timeout 900
```

Use `--arm native --mode off` for the control and `--arm direct --mode on` for
the timing arm. Never reuse an output directory. Raw logs, copied models, JFRs
and AppData stay under the ignored local build tree; the tracked
`observations/multiversion/` files contain only sanitized numbers and hashes.

## Runtime coverage for the remaining reviewed versions

The 13 versions that previously had only offline artifact evidence were each
launched on the same native-Windows machine with the promoted product agent and
the auxiliary shadow observer. Two fixtures were used:

- `hiyori_free_t08.cmo3` (10,127,083 bytes, SHA-256
  `99cf572c8dcb144f657ee0669c5f05e0aed239066f3673b15889eafae67256e9`), an
  official sample old enough for every reviewed host. Every version loaded it and
  compared **1 preparation / 16,777,216 bytes with zero mismatches**.
- The 763 MB `bench.cmo3` was also attempted. Cubism 5.0.02, 5.1.01, 5.2.00 and
  5.3.01 abort that load with “file was closed/interrupted” because the project
  was saved by Cubism 5.3.2; those four attempts are recorded as fixture
  incompatibility, not hook failures, and the same hosts passed with the hiyori
  fixture.

| Version | Factory owner | hiyori shadow | bench shadow |
| --- | --- | --- | --- |
| 5.0.00 | `shader.w` | PASS 1/16,777,216 B, 0 mismatch | PASS |
| 5.0.02 | `shader.w` | PASS 1/16,777,216 B, 0 mismatch | fixture incompatible |
| 5.0.03 | `shader.w` | PASS 1/16,777,216 B, 0 mismatch | PASS |
| 5.0.04 | `shader.w` | PASS 1/16,777,216 B, 0 mismatch | PASS |
| 5.0.06 | `shader.w` | PASS 1/16,777,216 B, 0 mismatch | PASS |
| 5.1.01 | `shader.w` | PASS 1/16,777,216 B, 0 mismatch | fixture incompatible |
| 5.1.02 | `shader.w` | PASS 1/16,777,216 B, 0 mismatch | PASS |
| 5.2.00 | `shader.y` | PASS 1/16,777,216 B, 0 mismatch | fixture incompatible |
| 5.2.01 | `shader.y` | PASS 1/16,777,216 B, 0 mismatch | PASS |
| 5.2.02 | `shader.y` | PASS 1/16,777,216 B, 0 mismatch | PASS |
| 5.3.01 | `shader.A` | PASS 1/16,777,216 B, 0 mismatch | fixture incompatible |
| 5.3.03 | `shader.A` | PASS 1/16,777,216 B, 0 mismatch | PASS (3 preparations) |

Every passing run reported `cleanup=safe`, `normalExitCode=0`, an observer
attached before the first call, and verified class-byte restoration. Together
with the earlier 5.1.03 / 5.2.03 / 5.3.04 runs, **all 20 reviewed stable versions
now have runtime evidence**, and the three shader families are each exercised on
more than one version. Numbers: `observations/multiversion/runtime-matrix.csv`.

Limits: this is still one machine, one GPU and a small/medium fixture per version.
It proves the hook admits, transforms, produces byte-identical upload data and
restores on each version; it does **not** measure loading performance for these
13 versions, and it is not a substitute for a per-version heavy-model benchmark.
