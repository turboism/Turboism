# Direct-raster texture preparation: native-Windows evidence

## Verdict

**The conversion is faster; overall document loading is not measurably faster than the old converter.**
Keep this an opt-in experiment, not a default-on or memory-saving recommendation.

Three runs per arm on one model:

| Median | Native (optimization off) | Old getRGB converter | Direct-raster converter |
| --- | ---: | ---: | ---: |
| Agent entry → stable document, s | 44.925 | 43.112 | 43.122 |
| Process CPU to that boundary, s | 35.422 | 36.625 | 36.844 |
| Preparation callback work, ms | N/A | 684.739 | 389.191 |
| Observed thread allocation, GB (decimal) | 23.648 | 20.902 | 20.856 |
| Loading peak working set, GiB | 5.927 | 5.615 | 5.990 |
| Loading peak private commit, GiB | 6.711 | 7.598 | 10.063 |
| Idle final 10 s working set, GiB | 5.024 | 4.898 | 6.190 |

Old → direct callback time is **−43.2%**, approximately **296 ms saved** in the
median. Whole-document medians differ by only **+10 ms (+0.02%)**; ranges overlap.
The approximately 4% lower median versus native/off is also present with the
**old** converter, so it is not evidence of an additional loading gain from this change.
Process CPU does not improve. Memory medians are worse, especially commit and idle
working set, with substantial between-run variation. Do not infer a stable memory
regression mechanism from three runs, but do not hide these adverse observations
or recommend this as a RAM optimization.

## Environment and workload

- Native Windows 11 x64, build 22631; Ryzen 9 7940H, 8 cores / 16 logical CPUs,
  approximately 32 GB physical RAM. Balanced power plan; AC power reported after the batch.
- Official Cubism **5.3.04**, bundled OpenJDK **17.0.3.1+2-LTS**, unchanged official
  JAR and JOGL. All nine console logs identify **NVIDIA GeForce RTX 4060 Laptop GPU/PCIe/SSE2**
  as the actual OpenGL renderer. This is not a Proton result.
- Existing performance model: **763,166,356 bytes**, **1,029 model images / 12 atlases**.
  Fixture SHA-256: `83326ac519dd5e4cad9b39e4c7234410cc62bf0d1f5241364561d6906292b6da`.
  User model name/location and model contents are not published.
- Nine eligible preparations per on-run: **100,663,296 pixels / 402,653,184 bytes**.
  All observed inputs are ARGB. Two **8192×8192** inputs exceed the existing
  33,554,432-pixel limit and retain the original path. No size bounds were expanded.
  There is **no real-host ABGR timing claim** from this workload.

## Controlled comparison

Order was balanced across each position, with fresh JVM/home/model copy per run:

1. `native-r1`, `legacy-r1`, `direct-r1`
2. `direct-r2`, `native-r2`, `legacy-r2`
3. `legacy-r3`, `direct-r3`, `native-r3`

No builds or other managed Java/Cubism sessions ran during these nine measurements;
there was an eight-second gap between runs. The runner rejects an already-running
Java/Cubism process and holds a per-user performance lock. This is still a working
machine, not an isolated performance lab; background OS load, thermal state and GC
remain possible sources of noise.

`native` uses the new product agent with preparation disabled. `legacy` uses that
same agent ZIP with **only `CanonicalRgbaImage.class` replaced** by the pre-change
implementation compiled for Java 17 from revision
`626257f2e2a2187fd331fdadf63d8d61163e4c97`. ZIP entry names and all other entry contents
were checked identical (`build/texture-host/variant-proof.json`). Both on arms use
the same callback/profile observer without shadow conversion. All arms use the same
auxiliary agent and startup configuration; PNG archive reuse and float-array cache
are explicitly disabled. Other startup defaults remain identical.

The pristine official BAT backup is copied and only `%~dp0` is rebased to the
installation. No direct Java main launch or managed user-home launcher is used.
Each run has its own Turboism home, AppData seed copy, Java user home and full model
copy. A waiting Python entry is placed in the existing Windows Job Object backend
before a launch gate releases the official BAT. Only that job can be terminated.
Neither official installations nor original models are edited by the harness.

Every timed run passed auxiliary assertions, exited normally with code **0**, and
recorded kernel-proven `cleanup=safe`, zero remaining Job Object processes, unchanged
input/model-copy/artifact hashes. `shadow-s5` separately passed the same exit checks,
compared **9/9 preparations**, and found **zero pixel/metadata/effective-stride differences**,
zero missed initial calls and zero preparation failures. Live disable/re-enable,
callback removal and native bytecode restoration all passed. Shadow time is excluded
from the performance table.

## Per-run loading observations

| Run | Document ready s | Process CPU s | Preparation ms |
| --- | ---: | ---: | ---: |
| native-r1 | 44.925 | 34.922 | N/A |
| legacy-r1 | 42.374 | 36.625 | 660.365 |
| direct-r1 | 41.714 | 36.844 | 428.675 |
| direct-r2 | 43.122 | 35.594 | 389.191 |
| native-r2 | 45.135 | 38.219 | N/A |
| legacy-r2 | 43.112 | 35.484 | 684.739 |
| legacy-r3 | 45.376 | 37.578 | 711.092 |
| direct-r3 | 43.353 | 38.313 | 350.336 |
| native-r3 | 44.112 | 35.422 | N/A |

The CSV uses `preparationMs=0` for native/off as an absent-hook sentinel, not a
measurement of native conversion cost.

Full numeric observations, including memory/GC for **every run**:
[CSV](observations/native-windows/native-loads.csv).
Artifact/source/run-envelope hash receipt:
[JSON](observations/native-windows/native-receipt.json).

## Measurement limits

- The main boundary is auxiliary premain → native document observed stable by the
  existing EDT/document/title observer, not texture-only time or click-to-ready latency.
  Its 500 ms polling/stability checks limit sensitivity to a few hundred milliseconds.
  Callback wall time is cumulative CPU-side preparation work, **not GPU upload time**.
- The source and staged model are hashed/copied before launch: **not disk-cold I/O**.
  Startup, model parsing, decoding, upload and initial UI work all contribute to ready time.
- Windows working set / private commit are collected on a handle bound to the exact
  task Java PID, bundled runtime path and creation identity, at about 250 ms intervals.
  Loading peaks end at the auxiliary ready marker (approximately aligned with document
  readiness); kernel peak counters may include earlier startup. Private commit is not
  resident RAM, and neither metric is GPU memory or Linux RSS.
- There are 30 seconds of no-input idle observation after ready. The idle table uses
  the final 10 seconds. No forced GC, heap-cap tuning, GPU queries or framebuffer readback.
  Thread allocation is the original sampled lower-bound metric, not retained memory.
- Direct `DataBuffer.getData()` can disable Java2D source acceleration tracking. Initial
  rendering occurred, but no subsequent interactive FPS/deformation benchmark was run.
  Pixel equivalence and no-input idle measurements do not settle that downstream risk.
- One model, one host version/GPU, three runs per arm. No statistical significance,
  cold-start, ABGR workload, other versions, memory benefit, or stable total-load speedup claim.

## Retained failures and verification-tool repairs

All failures remain failures; their timings are excluded, not relabeled:

| Attempt | Failure / correction |
| --- | --- |
| offline packaging gate | Probe omitted `NativeFloatArrayHostAgent.Allocations`. A packaged-only class-loader test failed first; metrics were moved to independent `NativeLoadMetrics`, then the gate passed. |
| shadow-s1 | Native coordinator used CRT list quoting for `cmd /s /c`; BAT did not launch. Corrected command-string grammar and added a real harmless BAT/space-path unit test. |
| shadow-s2 | Runtime logs were searched only at the top level; the ready trigger was never written. Corrected recursive log lookup and added a nested-directory test. Timed-out job was killed; kernel recorded zero processes, but cleanup status remains `unknown` because exit was forced. |
| shadow-s3 | Old probe looked for removed agent field `TEXTURE_UPLOAD_PREPARATION`; pixel comparison itself passed but restoration check failed. Updated lookup to registry ownership. |
| shadow-s4 | Registry handle has two close wrappers, not one. Added bounded AutoCloseable-only unwrap and a nested-wrapper regression test. |
| shadow-s5 | Complete shadow, restoration, normal exit and cleanup **PASS**. |

The official BAT emits a `ProxyConfig.bat` command warning in these runs but continues;
it was not edited to remove it. Retain console logs rather than claiming warning-free startup.

## Reproduction and local archive

Native runner: [run_native.py](run_native.py); offline safety tests: [test_native.py](test_native.py).
Use the explicit `--host`, `--fixture`, `--agent`, `--auxiliary`, `--arm`, `--mode` and a
fresh `--output` directory. Never pass an existing run directory or overwrite evidence.
For a comparison, independently freeze/verify the single-entry legacy agent replacement;
do not compare different product/probe builds or run builds during measurements.

Build/gates:

```sh
./gradlew checkTextureUploadValidationBundle --no-daemon --max-workers=2
python -m unittest discover -s testing/host-validation/image-archive/benchmark -p 'test_native.py' -v
python testing/host-validation/image-archive/benchmark/summarize_native.py \
  build/texture-host testing/host-validation/image-archive/benchmark/observations/native-windows
python testing/host-validation/image-archive/benchmark/archive_native.py \
  build/texture-host testing/host-validation/image-archive/benchmark/observations/native-windows
```

All 14 native attempt directories (including failures), sampled JSONL/CSV, original
load/result properties, runtime/console logs, Job Object proofs, machine metadata,
variant proof and measurement-time source snapshots are retained locally under
**`build/texture-host/`**. The sanitized tracked receipts contain no model/license files
or machine-local paths. A local archive excludes copied models and AppData/license state;
its digest is in `observations/native-windows/archive-receipt.json`.
The measurement runner/source fingerprint in the receipt identifies the captured snapshot;
subsequent lint-only edits and CLI input checks are not retroactively treated as measured code.

**Next hypothesis, not an implemented change:** this fixture provides real evidence of two
large fallbacks. Testing 8192² conversion would require a separate 256 MiB destination
budget and memory experiment, especially given the adverse commit observations here.
Do not silently increase the limit or infer the next gain from the present measurements.
