# Native texture-upload preparation

Status: default-off, opt-in allocation optimization with exact-host equivalence evidence. The expanded-range matrix reduced observed temporary allocation by **13.9% (2.06 GB)**. The first occupancy cohort found **32% higher loading RSS peaks**; a later cohort with added loading/CPU/GPU instrumentation did not repeat that direction and found overlapping ranges. Neither establishes a stable memory/CPU/GPU benefit or the unique cause of the peak. **Not recommended as a memory-saving option.** Read the [unified experiment ledger, including retry conditions and contradictory results](NATIVE-PERFORMANCE-EXPERIMENTS.md) and the [original occupancy cohort](README-native-memory-occupancy.md).

## Supported boundary

The initial measurements used Cubism 5.3.02 with JVM 17 and the reviewed `app/lib/jogl/jogl-all.jar` (SHA-256 `7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9`). The current pin table declares all 20 reviewed stable versions and derives factory owners (`shader.w`, `shader.y`, `shader.A`) rather than guessing from one version. Only the reviewed texture factory call site is transformed; its complete method shape must match. Offline exact-byte verification now covers all 20 stable JARs and all three families. The promoted product build was also shadow-tested on 5.1.03, 5.2.03 and 5.3.04: each host compared **11/11 preparations / 939,524,096 bytes with zero mismatches**, including two real 8192² images. The clean off/on block showed **3.1–5.4% shorter wall medians** and **13.7–32.5% lower observed allocation**, which is not retained memory; two samples per arm make this a screening signal, not a guarantee. Older hosts opened a fixture saved by Cubism 5.3.2 only after a test-only newer-file warning was accepted on a disposable copy. See [multi-version results](../../testing/host-validation/image-archive/benchmark/MULTIVERSION.md), [artifact results](../../testing/host-validation/image-archive/benchmark/ARTIFACTS.md) and [promotion status](../../testing/host-validation/image-archive/benchmark/PROMOTION.md).

Ordinary unpremultiplied sRGB `TYPE_INT_ARGB` / `TYPE_4BYTE_ABGR` images, each dimension at most 8192 and total area 4,096–67,108,864 pixels (one fresh RGBA target bounded to 256 MiB), can be prepared as the same premultiplied RGBA8 representation that JOGL's custom converter creates. Other images/profile/version/JVM cases fall through unchanged. A complete 64 KiB channel/alpha table comes from the JDK ComponentColorModel itself. Each eligible call allocates one fresh RGBA byte image (at most 256 MiB) and a row-sized integer scratch buffer; no source images, pixel payloads, texture IDs or GL contexts are cached. Original pixels are never changed.

The original AWTTextureIO call, profile, dimensions, mipmap policy, handle ownership and subsequent GL operations remain native. This does not shrink upload regions or add a dirty-rectangle mechanism. It targets CPU conversion and temporary allocation, not GPU execution time.

## Direct-raster conversion

The current converter adds direct ARGB/ABGR array-reading paths for standard JDK
layouts, retaining the prior `getRGB` path for unsupported layouts (including
nonzero-offset ABGR buffers). Bounds, translated subimages and padded strides are
checked; premultiplication, destination allocation, GL calls and default-off policy
are unchanged. This avoids the per-pixel generic color conversion and row scratch
buffer on eligible inputs.

Local JDK 17 conversion-only measurements show approximately 1.7× ARGB and 6× ABGR
speedups, **not end-to-end loading speedups**. Direct array exposure can also affect
Java2D source acceleration tracking. The historical exact-host equivalence and
loading observations below apply to their pinned agents, not automatically to this
new converter. Subsequent native-Windows 5.3.04 validation passed 9/9 real
preparations (402,653,184 bytes) with zero mismatches and verified restoration.
A balanced three-arm, nine-run comparison reduced preparation callback work
from 684.7 to 389.2 ms, but old/new document-ready medians were effectively equal
(43.112/43.122 s); memory/commit observations were adverse and variable. This is
**not a stable overall loading or memory benefit**. The opt-in/default-off policy
is unchanged, and subsequent interactive rendering remains unmeasured.

### Production promotion status (2026-11-07)

The direct ARGB/ABGR conversion and the expanded 67,108,864-pixel / 256 MiB
admission range are **integrated into the production source `CanonicalRgbaImage`**
(`supportedSize()` now admits up to 8192×8192 / 67,108,864 pixels; `prepare()`
uses the direct-array readers first and falls back to `getRGB`). The 9/9
native-Windows preparation validation and the three-arm nine-run comparison above
predate the large-image promotion and apply only to their frozen direct-only build.
Separate private large-image validation covered 11/11 preparations; the newly
integrated product build still needs its own multi-version revalidation. The historical
33,554,432-pixel / 128 MiB figures in earlier sections describe pre-promotion
builds and are retained only as history.

Reproducible harness, raw numeric observations and limits:
[offline benchmark](../../testing/host-validation/image-archive/benchmark/README.md)
and [native-Windows report](../../testing/host-validation/image-archive/benchmark/NATIVE-WINDOWS.md).

## Control

`-Dturboism.optimization.textureUploadPreparation=true` requests installation at startup (default off). Safe mode, disabled hook `cubism.texture-upload-preparation`, invalid config, other Editor artifacts/JVM lines or a different JOGL artifact do not admit it. Setting the same JVM property to false makes future preparations use the original path. Shutdown clears the callback slots and verifies restoration of the original class bytes. Existing textures are already pixel-equivalent and need no replacement. Enabling after a launch without installation requires a restart.

## Build / exact-host validation

```sh
./gradlew --no-daemon --max-workers=1 devCheck checkTextureUploadValidationBundle
```

The wrapper reuses the existing generic runner. `off` installs no preparation hook; `on` enables it; `shadow` additionally compares the original JOGL texture-data bytes and GL-facing metadata with the prepared representation at actual native texture calls. Shadow timings are not performance results. Effective unpack row stride, not a superficial equality of alignment defaults, is checked. No validation helper issues GL calls or modifies GL state.

```sh
TURBOISM_ENV_FILE=/path/to/operator.env \
  bash scripts/preview/run-texture-upload-host-validation.sh shadow trial1 \
  --transport local --fixture-remote /path/to/source.cmo3 --fixture-sha256 <sha256> --dry-run
# Remove --dry-run to execute the official isolated Editor. Alternate off/on with fresh labels.
```

The test agent measures agent entry to runtime-ready plus stable task-document publication, process CPU in that interval, and observed per-thread allocation maxima. The allocation sum is a lower bound, not exact cumulative/retained/peak memory. Both off/on use the same observer policy. Prepared-byte counters are not uploaded-byte or GPU-memory counters. Contract/restoration checks occur after the timed window. The image-archive and float-array candidates are explicitly off in this profile.

Evidence is under the task's `build/host-validation/texture-upload-preparation/5302/` directory; `state/texture-upload/result.properties` is the auxiliary result and `load.properties` records the timed snapshot. All results must be paired with exact artifact/fixture hashes and normal exit evidence before normal-use readiness is claimed.


## Use it interactively on the validated local host

The same runner now has an explicit non-validation mode. It uses the ordinary product agent, **omits the auxiliary test agent**, leaves the optimization installed, and waits for you to close Cubism. It retains the task prefix/home/model copy; saves go to that copy, not the original.

```sh
TURBOISM_ENV_FILE=/path/to/operator.env \
  bash scripts/preview/run-texture-upload-host-validation.sh on my-work \
  --interactive --transport local \
  --fixture-remote /path/to/heavy.cmo3 --fixture-sha256 <source-sha256>
# Replace on with off for the original texture preparation path.
```

There is no automatic exit timeout in interactive mode. Interrupting its coordinator does not terminate an active Editor or discard unsaved work. Close Cubism normally; the retained copy location is printed. Do not add the smoke-test client to a work session. Interactive output is SESSION_ENDED/SESSION_DETACHED, **not validation PASS**.

Use this existing official-BAT runner for the validated Linux/Proton workflow. The legacy preview PowerShell script that directly launches Java is not this supported entry. Also, the managed Windows launcher clears inherited JAVA_TOOL_OPTIONS; setting that variable alone is not an activation method for that launcher. Those historical Proton runs do not establish native-Windows or non-JVM-17 readiness. The later 5.3.04/JVM-17 native-Windows validation is documented separately in the direct-raster report above.

## Initial narrower-range results and limits (historical, pre-promotion)

The measurements in this section and the next used the pre-promotion 33,554,432-pixel
/ 128 MiB bound and the getRGB-only converter. They are retained as historical
evidence for those pinned builds, not as current-product tests.

Fixture: user-provided `heavy.cmo3`, 447,432,485 bytes, SHA-256 `029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`; 849 model images and 6 atlases. Environment: bundled Java 17.0.3.1+2-LTS, DW-Proton 11.0-9, Mesa 26.1.5 / Intel UHD 630. Fresh Editor/prefix per run; source copy is staged before launch, so this is not disk-cold IO.

| Round | Mode | Agent to ready (s) | Process CPU (s) | Observed allocation (GB, decimal) |
| --- | --- | ---: | ---: | ---: |
| 1 | off | 106.127 | 191.400 | 14.8466 |
| 1 | on | 104.790 | 193.700 | 13.5500 |
| 2 | on | 104.185 | 190.640 | 13.5597 |
| 2 | off | 106.787 | 180.120 | 14.8416 |
| 3 | off | 148.503 | 235.630 | 14.8409 |
| 3 | on | 107.002 | 198.630 | 13.5682 |

All six runs passed the runner, original/copied fixture and official artifact hash checks, and normal exit. The slow third off run is retained, not discarded. Median observed allocation fell from 14.8416 to 13.5597 GB (about 1.28 GB, 8.6%). Timing/CPU variation does not support a speedup claim. These numbers are cumulative observed allocation, not process RSS, retained heap or GPU memory.

The separate native shadow run compared **163 real preparations / 170,950,656 bytes**, with zero mismatches in native JOGL buffer contents and GL-facing metadata/effective row stride. Those were all preparations in that load window (observer attached before the first call). Disable/re-enable, original input preservation, callback removal and bytecode restoration passed. No GPU timer queries or framebuffer readback were used, and GPU timing is not claimed.

Evidence run suffixes (prepend `texture-upload-preparation-5302-`):
- `heavy-shadow-r1-20260905T134143Z-2369670`
- `heavy-off-r1-20260905T134738Z-2379887`, `heavy-on-r1-20260905T134945Z-2384558`
- `heavy-on-r2-20260905T135152Z-2389314`, `heavy-off-r2-20260905T135359Z-2393643`
- `heavy-off-r3-20260905T135609Z-2398541`, `heavy-on-r3-20260905T135859Z-2405046`

Interactive smoke `interactive-smoke-r3-20260906T031754Z-3186734` also ended normally with no auxiliary validation agent, retained prefix, and unchanged source/copy/JAR/BAT hashes. Its explicit smoke client used prefix/PID/focus-verified Alt+F4. Earlier failed smoke attempts remain recorded; cleanup now normalizes Wine prefixes and uses PID-bound signals instead of unsafe substring ownership guesses.

Remaining roadmap work: dirty-rectangle upload/atlas composition, model-update coalescing and broader CPU evaluation optimizations. The PNG archive and float-array candidates are independent experimental flags and are not recommended based on their current natural-workflow results.

Continuation shadow `resume-texture-shadow-r1-20260906T160721Z-450525` again compared all 163 preparations / 170,950,656 bytes with zero mismatches, zero calls before observer attachment, successful restoration and normal official-launcher exit. This validates the rebuilt texture implementation, not a new timing measurement. Subsequent float-only lifecycle changes leave the texture conversion/bridge/transformer unchanged.

Runner shutdown hardening now waits for actual launcher completion instead of signalling its process tree immediately on a shutdown log; validation requires exit code 0, and a timeout requiring forced cleanup is FAIL. Interactive sessions remain user-controlled. Keep run labels short so the generated Windows fixture path stays below the installed host's path limit.

## Expanded range: final measured results (2026-09-07, historical pre-promotion build)

This section's "expanded" arm (8,192 per dimension / 33,554,432 pixels) was the
pre-promotion maximum. Production now admits up to 67,108,864 pixels; the
large-image screening signals that motivated that promotion are in the
[experiment ledger](../../testing/host-validation/image-archive/experiments/RESULTS.md)
and [PROMOTION.md](../../testing/host-validation/image-archive/benchmark/PROMOTION.md).

The initial bounds excluded many smaller inputs and one real **3840×5056** input. Lowering the minimum area to 4,096 added 268 real preparations. Raising the bounded maximum to 8,192 per dimension / 33,554,432 pixels included that final large image. Neither change touches the shader call site, GL operations, image format or resource ownership. Shadow-only size counters verified the remaining input rather than assuming it was small.

Final shadow `large-s1-20260907T021859Z-904183` compared **all 432 preparations / 269,205,504 bytes**, including the large image: zero mismatches, zero missed initial calls, zero remaining fallbacks, successful disable/restoration and normal launcher exit. The validator now fails if any preparation escapes comparison. This is texture-input equivalence, not framebuffer-readback or GPU timing evidence.

Three interleaved groups compared off, smaller-only (4,096 minimum / original maximum), and expanded (current bounds). All nine runs passed, preserved original/copied model and official hashes, and exited normally without forced cleanup. Values below use the same observed-thread-allocation lower bound as the original measurements.

| Round | Mode | Agent to ready (s) | Process CPU (s) | Observed allocation (GB) |
| --- | --- | ---: | ---: | ---: |
| 1 | off | 98.530 | 182.410 | 14.841372 |
| 1 | smaller-only | 96.647 | 181.240 | 13.382309 |
| 1 | expanded | 94.987 | 167.450 | 12.780825 |
| 2 | expanded | 96.475 | 173.650 | 12.763908 |
| 2 | smaller-only | 97.531 | 176.380 | 13.382164 |
| 2 | off | 98.902 | 182.640 | 14.839921 |
| 3 | smaller-only | 95.678 | 179.000 | 13.389467 |
| 3 | off | 97.364 | 176.280 | 14.840170 |
| 3 | expanded | 95.836 | 174.620 | 12.775706 |

Median off→expanded allocation: **14.840170→12.775706 GB**, a **2.064464 GB / 13.91%** reduction. Including the large image alone reduces the smaller-only median by **606.603 MB / 4.53%**. Earlier cutoff-only pairs measured another **163.687 MB / 1.21%** reduction from the initial bounds. These are separately measured comparisons, not additive percentage estimates. Wall/CPU values are recorded without promising a stable speedup from three noisy samples. No RSS, retained heap, memory peak, VRAM or FPS reduction follows from these allocation measurements.

Final matrix suffixes (same `texture-upload-preparation-5302-` prefix):
- `ln1-20260907T022136Z-908098`, `ls1-20260907T022336Z-911677`, `lw1-20260907T022533Z-915107`
- `lw2-20260907T022730Z-918547`, `ls2-20260907T022927Z-921995`, `ln2-20260907T023124Z-925420`
- `ls3-20260907T023324Z-928942`, `ln3-20260907T023521Z-932365`, `lw3-20260907T023722Z-935836`

Intermediate evidence is retained too: cutoff-only comparisons `cb1-20260907T014406Z-851544`, `cc1-20260907T014603Z-854973`, `cc2-20260907T014757Z-858342`, `cb2-20260907T014954Z-861781`, `cb3-20260907T015151Z-865209`, `cc3-20260907T015348Z-868706`; smaller-only off/on comparisons `no1-20260907T015801Z-873721`, `all1-20260907T015958Z-877134`, `all2-20260907T020158Z-880667`, `no2-20260907T020358Z-884159`, `no3-20260907T020602Z-887736`, `all3-20260907T020805Z-891333`. Smaller-input shadow runs: `small-s1-20260907T014102Z-847410`, `small-s2-20260907T021302Z-897115`.

Pinned Agent SHA-256: initial bounds `9941bd68b8f52ff6f15a716d26430852c8d69818cfa3587415f3d2922a99ca31`; smaller-only `e9cc756f5d4335fbab3865fe7cc8fca33ef13638515312e329a960af2d2463a7`; expanded `469f322c1a8e9a56dfe9b43415b94bc99820b8c70ca4110f7e62247c008b5a13`. ZIP entry comparison shows **only CanonicalRgbaImage.class differs** between each of these product agents. Off/on comparisons use the identical expanded Agent, with the optimization flag off/on. Every comparison uses the same helper version within its batch.

Final interactive smoke `use-r1-20260907T024041Z-940202` used the expanded ordinary Agent without auxiliary validation agent, retained its isolated copy/home/prefix, and ended normally through the explicitly selected task-window close client. It is SESSION_ENDED, not validation PASS. Do not supply that smoke client when doing real work.

The measured load now prepares every input at this factory up to the promoted
67,108,864-pixel bound; the two 8192² inputs that previously fell through are now
covered, and further size-range expansion has no demonstrated consumer here. Dirty
rectangles, partial atlas composition, update coalescing and warp-form allocation
work are separate, unimplemented directions. In particular, point-reference caching
must not retain mutable form arrays or suppress native `setUpdated` writes, and XML
character validation must not be disabled to reduce parser samples.
