# Actual memory occupancy: texture preparation off/on

## Conclusion — 2026-09-07

**Do not recommend the current texture-preparation option as a way to lower peak RAM usage.** It reduces cumulative allocation, but this controlled six-run Linux/Proton measurement observed a **higher loading RSS peak in every on run**. End-of-idle occupancy varies substantially and does not establish a repeatable reduction. The product flag remains default off; no product code, heap settings or collector policy was changed for this measurement.

Fixture: the same 447,432,485-byte `heavy.cmo3`, SHA-256 `029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`. Exact Cubism 5.3.02 and bundled JVM 17 on the validated Proton host. Same product Agent in all runs: SHA-256 `469f322c1a8e9a56dfe9b43415b94bc99820b8c70ca4110f7e62247c008b5a13`. Same auxiliary observer: `5c1300e57207a8be54e7612e4dd742719c2eb1e7685871e61a987875a975f198`. Heap maximum is unchanged at 16,592,666,624 bytes in all six runs.

## Measurement

- Fresh official-BAT/task-scoped prefix/home/model copy per run; order off/on/on/off/off/on.
- Main Cubism `java.exe` process, identified by exact task prefix, main-class argument, UID and process start identity. This is **not the entire Proton process tree**, native Windows Task Manager, whole-system memory, or VRAM.
- Read-only `/proc/<pid>/smaps_rollup` RSS, PSS, private pages and swap approximately every second from startup. Actual largest interval was 1.112 seconds; slowest sample read was 109.1 ms. Peak is a **sampled peak**, not a claim to capture every instantaneous peak.
- Also record `/proc/<pid>/status` VmHWM. Kernel RSS accounting is asynchronous, so HWM is corroboration, not an exact oracle. No writes to `/proc`, no forced GC, heap dump, cache drop, collector change or reduced heap limit.
- After the existing stable-document readiness checks, remain idle for 120 seconds with the optimization still in its selected state. Record JVM heap used/committed and aggregate GC counters each second. Synthetic contract calls and hook restoration happen only after the memory sampler completes.
- The steady figure is each run's **last-30-second median**, then the median across three runs. It is not a forced post-GC live set or a long-term leak test. PSS apportions shared pages rather than counting each process's shared mapping in full.

All six runs passed both sampling protocol and normal host validation, with `wrapper_exit=0`, unchanged source/copied fixture and official JAR/BAT hashes. No failed or unfavorable trials were removed.

## Results (GiB = 2^30 bytes)

| Run | Mode | Loading sampled RSS peak | Final idle RSS | Loading sampled PSS peak | Final idle PSS | Kernel RSS HWM before idle |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| mem-n1 | off | 4.283 | 2.561 | 4.184 | 2.461 | 4.313 |
| mem-y1 | on | 5.575 | 3.061 | 5.476 | 2.961 | 5.644 |
| mem-y2 | on | 5.661 | 2.419 | 5.561 | 2.319 | 5.720 |
| mem-n2 | off | 4.103 | 3.185 | 4.004 | 3.085 | 4.115 |
| mem-n3 | off | 4.463 | 2.419 | 4.364 | 2.319 | 4.572 |
| mem-y3 | on | 6.170 | 2.356 | 6.070 | 2.256 | 6.452 |

Three-run medians:

| Metric | off | on | Interpretation |
| --- | ---: | ---: | --- |
| Sampled loading RSS peak | 4.283 GiB | 5.661 GiB | **32.16% higher**, about 1.38 GiB more |
| Sampled loading PSS peak | 4.184 GiB | 5.561 GiB | **32.92% higher** |
| Kernel-reported loading HWM | 4.313 GiB | 5.720 GiB | **32.62% higher**, corroborates sampled trend |
| Final idle RSS | 2.561 GiB | 2.419 GiB | Median 5.55% lower, but off range 2.419–3.185 and on range 2.356–3.061 overlap |
| Final idle PSS | 2.461 GiB | 2.319 GiB | Median 5.78% lower, not established as a stable saving |
| Final idle heap committed | 1.953 GiB | 1.953 GiB | Same median, large per-run variation |
| Final idle heap used | 1.278 GiB | 1.335 GiB | Median slightly higher; not a post-GC retained-set measure |

One off run (mem-n3) briefly had about **173 MiB swap**; the other five had zero measured process swap. That can depress resident-memory readings and is retained as a limitation, not grounds for dropping the run. The other two off runs also had lower loading peaks than every on run. System MemAvailable minima ranged from 3.54 to 5.50 GiB. System memory pressure was monitored, not held constant; these variations are not attributed solely to unrelated applications or to the optimization.

GC counts by document readiness were off 47/44/45 and on 36/41/41. Reported aggregate GC collection time medians were 1393/1323 ms. These MXBean times are **not a pause distribution** and may include collector-specific/concurrent accounting. No additional collection occurred in the 120-second idle windows. Changed GC timing or heap growth is a possible explanation for lower allocation coexisting with higher RSS, but **the cause of the peak increase has not been established**; no heap/native-memory attribution at the peak was captured.

## Evidence

Under `build/host-validation/texture-upload-preparation/5302/`, prepend `texture-upload-preparation-5302-` to:
- `mem-n1-20260907T030729Z-967487`
- `mem-y1-20260907T031227Z-975177`
- `mem-y2-20260907T031626Z-982058`
- `mem-n2-20260907T032028Z-989485`
- `mem-n3-20260907T032433Z-996482`
- `mem-y3-20260907T032841Z-1004030`

Each directory contains `memory-samples.jsonl`, sampler log, ordinary identity/result/exit evidence, and `turboism-home-logs-state.tar`. The archive retains `state/texture-upload/memory/{attached.properties,ready.properties,jvm.csv,end.properties,complete.properties}`. Keep raw host logs local; they may contain unrelated host/licensing details.

## Reproduce on the validated local host

First read the applicable host runbook. The observer has no launch or signal implementation; the existing generic runner still owns official launch, isolation and cleanup.

```sh
python3 scripts/test/test_task_memory_observer.py
./gradlew --no-daemon --max-workers=1 checkTextureUploadValidationBundle

TURBOISM_ENV_FILE=/path/to/operator.env \
  bash scripts/preview/run-texture-upload-host-validation.sh off mem-n1 \
  --transport local \
  --fixture-remote /path/to/heavy.cmo3 --fixture-sha256 <source-sha256> \
  --home-file "$PWD/scripts/test/measure-task-memory.py:validation/measure-task-memory.py" \
  --home-file "$PWD/scripts/preview/host-task-processes.py:validation/host-task-processes.py" \
  --remote-pre-launch "$PWD/scripts/test/start-task-memory-observer.sh" \
  --jvm-option '-Dturboism.validation.textureUpload.memoryIdleSeconds=120' \
  --dry-run
# Remove --dry-run for the real run. Use fresh short labels; alternate off/on.
```

Observer tests cover units/missing fields, PID identity changes, real self-process smaps reading, incomplete windows, immutable evidence, and the JDK helper's default-off/30-second handshake. The product Agent hash stayed unchanged after building the modified test-only helper.

Kernel metric reference: https://docs.kernel.org/filesystems/proc.html — process status, smaps/smaps_rollup and asynchronous RSS accounting.
