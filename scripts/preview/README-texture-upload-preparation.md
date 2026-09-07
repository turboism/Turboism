# Native texture-upload preparation

Status: usable opt-in allocation optimization in the exact scope below, verified in official Cubism 5.3.02 on Proton. Three off/on runs reduced observed temporary allocation by a median 8.6%; CPU/load-time/FPS or peak-memory improvements are **not** claimed.

## Supported boundary

The first scope is Cubism 5.3.02 with JVM 17 and its reviewed `app/lib/jogl/jogl-all.jar` (SHA-256 `7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9`). Only the profiled `shader.A.a(graphics3d.a,CWritableImage,int,String):Pair` texture factory call site is transformed; its complete method shape must match the official reference.

Ordinary unpremultiplied sRGB `TYPE_INT_ARGB` / `TYPE_4BYTE_ABGR` images, each dimension at most 4096 and total area 65,536–16,777,216 pixels, can be prepared as the same premultiplied RGBA8 representation that JOGL's custom converter creates. Other images/profile/version/JVM cases fall through unchanged. A complete 64 KiB channel/alpha table comes from the JDK ComponentColorModel itself. Each eligible call allocates one fresh RGBA byte image and a row-sized integer scratch buffer; no source images, pixel payloads, texture IDs or GL contexts are cached. Original pixels are never changed.

The original AWTTextureIO call, profile, dimensions, mipmap policy, handle ownership and subsequent GL operations remain native. This does not shrink upload regions or add a dirty-rectangle mechanism. It targets CPU conversion and temporary allocation, not GPU execution time.

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

Use this existing official-BAT runner for the validated Linux/Proton workflow. The legacy preview PowerShell script that directly launches Java is not this supported entry. Also, the managed Windows launcher clears inherited JAVA_TOOL_OPTIONS; setting that variable alone is not an activation method for that launcher. Native Windows and non-JVM-17 readiness have not been established by these runs.

## Measured results and limits

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
