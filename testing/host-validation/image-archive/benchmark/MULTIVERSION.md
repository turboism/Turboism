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
- `--accept-newer-file-warning` accepted the “file saved by a newer editor”
  warning **on the disposable copy** for 5.2.03/5.1.03. The fixture was saved
  by Cubism 5.3.2, so older-host results are not a native-old-fixture corpus.

Neither behavior is product behavior; both are validation-harness options.

## Shadow correctness

All three hosts transformed the pinned factory owner for their generation and
compared every preparation with the unchanged native JOGL representation.

| Host | Factory owner | Runs | Preparations | Compared bytes | Mismatches | Rejected | Cleanup / exit |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 5.1.03 | `shader.w` | 1 | 11 | 939,524,096 | 0 | 0 | safe / 0 |
| 5.2.03 | `shader.y` | 1 | 11 | 939,524,096 | 0 | 0 | safe / 0 |
| 5.3.04 | `shader.A` | 1 | 11 | 939,524,096 | 0 | 0 | safe / 0 |

Shadow runs also verified observer attachment before the first call,
source-pixel immutability, callback/registry removal and class-byte restoration.
Shadow wall/CPU numbers are **not** performance samples.

## Off/on loading comparison

Sequence per host: `off, on, on, off`. Big-fixture runs only; the 398 KB
sanity fixture is excluded. Times are agent-entry to stable document publication.
Allocation is the sum of observed per-thread allocation maxima, a lower bound; it
is not retained RAM, RSS or VRAM. The primary (`clean`) cohort was measured after
removing the earlier diagnostic window dump; the diagnostic cohort is retained in
`loads.csv` but is not used for the medians below.

| Host | Arm | Runs | Wall median (s) | Wall range (s) | CPU median (s) | Observed alloc median (GB) | Preparation median (ms) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 5.1.03 | off | 2 | 88.853 | 86.128–91.578 | 220.891 | 57.454 | — |
| 5.1.03 | on | 2 | 85.757 | 84.135–87.379 | 209.109 | 49.606 | 1,781.6 |
| 5.2.03 | off | 2 | 74.722 | 74.384–75.061 | 182.703 | 22.043 | — |
| 5.2.03 | on | 2 | 72.385 | 71.455–73.315 | 170.195 | 14.877 | 1,664.8 |
| 5.3.04 | off | 2 | 79.613 | 78.288–80.939 | 188.930 | 22.018 | — |
| 5.3.04 | on | 2 | 75.276 | 73.069–77.483 | 171.555 | 14.878 | 1,823.4 |

Medians versus off:

| Host | Wall change | CPU change | Observed allocation change |
| --- | ---: | ---: | ---: |
| 5.1.03 | **−3.5%** | **−5.3%** | **−13.7%** |
| 5.2.03 | **−3.1%** | **−6.8%** | **−32.5%** |
| 5.3.04 | **−5.4%** | **−9.2%** | **−32.4%** |

Interpretation:

- In this cleaner block, all three hosts show a **small 3.1–5.4% shorter wall
  median** and **5.3–9.2% lower CPU median**, alongside lower observed allocation.
  This is a consistent screening signal, not a statistical guarantee: two samples
  per arm, one model, one machine, no randomized order and no significance test.
- The 5.2.03 and 5.3.04 wall ranges are separated in this block; the 5.1.03 ranges
  overlap (off minimum 86.128 s, on maximum 87.379 s). Treat 5.1.03 as the weakest
  timing evidence.
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
- 5.2.03/5.1.03 required accepting the newer-file warning on a disposable copy.
  This is a compatibility caveat: the model was **saved by Cubism 5.3.2**.
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
