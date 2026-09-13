# Native image-pipeline diagnostics (first slice)

This is an opt-in, validation-only diagnostic path, not a texture optimization or a real-host readiness claim. It does not skip image encoding, change caches, modify model data, or replace rendering. The ordinary `camera` / `edit` profiles keep their existing seven metrics and v1 report shape.

## Admission and invocation

The existing validation agent accepts `performanceProbeScenario=images` along with its existing explicit installation/capture options, agent/fixture digests, run identity, output path and rollback manifest. The image profile is pinned to the independently inspected **Cubism 5.3.02** artifact; 5.2.03, 5.3.03 and unknown artifacts reject it. Do not infer image support from the ordinary render probe's 5.3.03 support.

Build and inspect the validation-only bundle with:

```sh
./gradlew --no-daemon --max-workers=1 performanceProbeValidationBundle checkPerformanceProbeReports
```

Real-host collection must use the current common host-validation runner and its exact-artifact, task-owned fixture/home and process-identity contract. Consult `run-cubism-host-validation.sh --help` for current arguments. The older camera scenario wrapper still drives a camera scenario; passing an image profile does not turn its interactions into PSD/atlas actions. An image-specific interaction driver and real-host results remain follow-on work.

## What is measured

The image profile adds five timing targets to the seven existing render/edit targets:

| Metric | Entry point | Meaning |
| --- | --- | --- |
| imageDecode | CWritableImage companion InputStream decoder | Decode method entries, including failed attempts; not necessarily file IO |
| pngEncode | CWritableImage.writeImageAsPng | PNG encoder method entries, including failed attempts |
| imageArchive | CImageResource.archive | Archive attempts; can be no-ops or catch errors internally |
| atlasRebuild | CTextureAtlas.setupCacheImage$cubism | Full atlas composition method entries |
| textureRedraw | GTexture2D.redrawTexture | Existing full-size redraw method entries, not actual GPU completions |

The v2 `images` report explicitly marks GPU timings and uploaded bytes as **not measured**. It cannot distinguish all cache invalidation reasons yet. It records inclusive method wall time; nested encode/decode/render timings must not be added together as independent CPU work.

Each metric contains the four old counters plus `latency`: completed sample count and p50/p95/p99 **base-2 bucket upper bounds**. These are approximate bounds over sampled calls, not exact population percentiles. Storage is fixed at 64 counters per metric. A percentile can exceed `maxNanos` because it is the containing bucket's upper bound. The sample count must match completed sampled calls in a drained report. Capture restart rejects a still-draining prior window.

For `--scenario images`, `verify-cubism-performance-probe.py` requires the v2 report, exact image target/restoration evidence, declared measurement limitations, consistent latency counts and some actual image-pipeline activity. Legacy v1 evidence remains separately strict. A passing verifier establishes complete evidence format and restoration matching, not an image-quality or performance improvement.

## Automated checks

```sh
./gradlew --no-daemon --max-workers=1 :runtime:test \
  --tests '*PerformanceProbe*Test' --tests '*PerformanceLatencyHistogramTest' \
  :bootstrap:test --tests '*PerformanceProbe*Test' checkPerformanceProbeReports
```

An optional test reads the reviewed JAR and transforms all twelve selected method bodies without launching or initializing Cubism. Supply its absolute path in the test JVM as `-Dturboism.test.cubism5302Jar=...`; absent configuration skips the test. A supplied missing/wrong artifact fails it. The test validates selector coverage and frame computation, not execution inside a real GL context.

## Why archive reuse is not enabled yet

A non-null compressed buffer does not, by itself, prove that it still represents the decoded pixels. The parent design requires auditing every reachable mutation path, including escaped writable images, graphics handles and pixel arrays, before skipping an encode. This slice supplies the measurements needed to distinguish repeated encoding from decoding and no-op archive attempts; it does not claim that mutation audit is complete.

Follow-on acceptance: identify a provably tracked resource subset, test unmodified decode/archive cycles, exercise in-place edits and source replacement, compare decoded pixels, test save/reopen and Undo/Redo, then collect matched on/off real-host results. Until then, keep the original archive implementation.
