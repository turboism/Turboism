# Direct-raster texture preparation benchmark

Offline CPU conversion comparison only. This is **not** a Cubism loading, PNG decoding,
GPU upload, RSS, or retained-memory benchmark. The optimization remains default-off.

## Reproduce

From the repository root, using JDK 17 (`java` and `javac` from the same JDK):

```sh
mkdir -p build/texture-fastpath/classes
javac --release 17 -d build/texture-fastpath/classes \
  runtime/src/main/java/dev/turboism/adapter/cubism/optimization/image/CanonicalRgbaImage.java \
  testing/host-validation/image-archive/benchmark/CanonicalRgbaImageBenchmark.java
java -Xms512m -Xmx1g -Djava.awt.headless=true \
  -cp build/texture-fastpath/classes dev.turboism.validation.CanonicalRgbaImageBenchmark
```

Run each process without a concurrent build or other benchmark. There are ten warmup
pairs and nine measured, alternating-order pairs per case. Reported time is median
milliseconds per conversion, including destination allocation. Every input is checked
byte-for-byte against the former getRGB implementation before measurement. A volatile
sink consumes results; no forced GC is requested. Allocation columns are calling-thread
bytes for the last measured pair, not retained size or a process-wide allocation total.
This small harness is not JMH; JIT/GC, fixed case ordering, and system load can affect results.

The frozen baseline is `CanonicalRgbaImage` from commit
`626257f2e2a2187fd331fdadf63d8d61163e4c97`, copied into `legacy()` with an equivalent JDK-derived
premultiplication table. Inputs cover ARGB/ABGR, small/medium/large images, and translated
subimages with padded row strides. There is no source/payload cache and no GL context.

## Recorded observations

Windows amd64, Eclipse Adoptium OpenJDK `17.0.9+9`, `-Xms512m -Xmx1g`, headless.
Raw CSVs for all four process runs are in [observations](observations/).
Run 2 overlapped the Gradle regression build: retain it for transparency, but do not
use it as controlled timing evidence. Runs 1, 3 and 4 did not overlap our build/test work.
They are illustrative local measurements, not isolated-machine or statistical guarantees.

| Input (uncropped) | Legacy ms, median across runs 1/3/4 | Direct ms, median across runs 1/3/4 | Ratio |
| --- | ---: | ---: | ---: |
| ARGB 1024×1024 | 5.3842 | 3.1385 | 1.72× |
| ARGB 3840×5056 | 100.8849 | 59.3065 | 1.70× |
| ABGR 1024×1024 | 18.2610 | 3.0496 | 5.99× |
| ABGR 3840×5056 | 345.2432 | 59.0411 | 5.85× |

For all sizes/crop variants in these three runs, per-process median ratios range from
1.58–1.74× (ARGB) and 5.44–6.77× (ABGR). Most destination allocation remains: a
3840×5056 RGBA image still needs about 77.7 MB, irrespective of conversion speed.

Production source SHA-256 at measurement:
`33a2f06e8a606e785ef4cf6dae4e682b2df4dba7e3f4a2db77278039782bb213`.
Benchmark source SHA-256:
`ed708711d390c81c00db8b017ed691acc1a6da5db58c356450c43cb7215db974`.

## Correctness and limits

The fast path admits only standard JDK raster, sample-model, color-model and buffer
classes with the expected channel layout and checked bounds. It honors translated
subimages and row padding, and reads but never writes or retains source arrays.
Nonzero-offset ABGR buffers fall back to getRGB: the JDK byte raster's interpretation
of those offsets can differ from generic SampleModel indexing. Tests compare those
constructed inputs with the former getRGB semantics; ordinary images still compare
against the original JOGL-equivalent Java2D conversion.

Calling `DataBuffer.getData()` can make the source untrackable to Java2D acceleration.
Although pixels are unchanged, this is a potential downstream rendering cost not
represented by this conversion-only benchmark. The old exact-host shadow/load results
cannot be transferred to this new implementation. Re-run exact-host pixel/metadata
shadow validation and interleaved, non-shadow off/on loading measurements, including
CPU/RSS and subsequent rendering, before claiming a real loading improvement.

Targeted checks: all 6 runtime tests passed; 3 bootstrap installer tests passed and
1 exact-artifact admission test was skipped because the official artifact was not
configured. Spotless and `git diff --check` passed.

```sh
./gradlew :runtime:test --tests '*CanonicalRgbaImageTest' \
  --tests '*TextureUploadPreparationTransformerTest' :runtime:spotlessJavaCheck \
  --no-daemon --max-workers=2
./gradlew :bootstrap:test --tests '*VerifiedTextureUploadPreparationInstallerTest' \
  --no-daemon --max-workers=2
```

The initial observations above were offline only. Subsequent authorized real-host
testing on native Windows / Cubism 5.3.04 passed pixel/metadata/restoration checks
and completed nine balanced loading runs. Callback preparation was 43.2% faster,
but old/new overall document-ready medians were effectively unchanged and memory
observations were adverse/variable. See the complete [native-Windows report](NATIVE-WINDOWS.md),
including every run, failures, raw-data receipts and reproduction instructions.

The next investigation adds two separate, profiled diagnostic runs and ranks remaining
large-texture conversion, interpolation, deserialization and ImageIO cache hotspots:
[remaining loading hotspots](HOTSPOTS.md). Profiled runs are rejected by the loading
performance summarizer and are not added to the nine-run timing matrix.

Parent workflow:
[scripts/preview/README-texture-upload-preparation.md](../../../../scripts/preview/README-texture-upload-preparation.md).
