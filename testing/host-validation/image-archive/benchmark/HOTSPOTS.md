# Remaining loading hotspots: native-Windows JFR attribution

Follow-up: all five candidate areas were independently screened on the same host;
see [experiment results](../experiments/RESULTS.md). Large-image coverage repeated
an approximately 4% lower ready-time point estimate and approximately 20% less
observed allocation; the other candidates did not establish overall benefit.
The JFR findings below remain diagnostic evidence, not those timing results.

## Scope

Two additional **diagnostic** runs, `profile-direct` and `profile-native`, use the
same Cubism 5.3.04 / bundled Java 17 / RTX 4060 / 763 MB fixture as
[NATIVE-WINDOWS.md](NATIVE-WINDOWS.md). Both passed normal exit 0, safe Job Object
cleanup, restoration where applicable, and unchanged model/artifact hash checks.
No new production optimization or area-limit expansion was made for this investigation.

Recordings start at JVM startup with `settings=profile`, stack depth 96, dump on exit.
Analysis is restricted to the auxiliary loading-trace start through document-ready
marker; the following 30-second idle window is excluded. `--profile` is recorded in
the run envelope, and the performance summarizer **rejects profiled runs**. Do not
mix these runs into the existing nine-run loading medians.

The direct run contains **880 Java execution samples** and **1,930 native-method
samples** in that window. Of all 2,810 samples, 359 have truncated stacks. Native
samples include event loops, network waits and graphics/driver calls: they are not
a denominator for CPU optimization percentages. Percentages below are of the **880
Java execution samples**, not elapsed loading time, exact CPU seconds, GPU time,
or an achievable percentage speedup.

## Ranked findings

| Candidate | Direct Java samples | Share of Java samples | Native/off Java samples | Direct JFR allocation weight |
| --- | ---: | ---: | ---: | ---: |
| JOGL custom texture conversion | 194 | 22.0% | 345 | ~4.73 GB |
| Extended interpolation setup/evaluation | 152 | 17.3% | 147 | ~3.63 GB |
| Model serialization/deserialization, including XML ancestry | 130 | 14.8% | 126 | ~4.58 GB |
| Floating-point parsing | 63 | 7.2% | 60 | ~1.38 GB |
| PNG decoding | 24 | 2.7% | 11 | ~1.99 GB |
| Native image materialization/conversion | 29 | 3.3% | 31 | ~1.01 GB |

Allocation weights come from `jdk.ObjectAllocationSample` (6,126 in the direct load
window), are noisy statistical estimates, and are **not retained RAM, peak memory,
exact cumulative allocation, or bytes an optimization can necessarily eliminate**.
The category assignment is exclusive by nearest relevant subsystem, while inclusive
stack counts can overlap. The native run has 961 Java samples, so raw sample counts
across runs are corroborating attribution, not an A/B timing measurement.

### 1. Uncovered large-texture conversion — strongest next experiment

Observed path on the EDT:

`AWTTextureIO.newTexture → AWTTextureData.getBuffer/createFromCustom → Graphics2D.drawImage → OpaqueCopyArgbToAny.Blit → ComponentColorModel.getDataElements`

- There are still two real 8192×8192 inputs rejected by the existing area bound;
  the other nine preparations use the direct path. The residual custom-conversion
  stack is consistent with that uncovered work, not evidence that the direct path
  has failed. No per-call image-size JFR event was added, so do not claim exact
  per-image duration/weighted-allocation attribution from stack sampling alone.
- `ComponentColorModel.getDataElements` is the leaf in 107 Java samples and carries
  approximately **4.20 GB of sampled int-array allocation weight** across all contexts.
  The JOGL custom-conversion category is approximately 4.73 GB overall.
- In the direct recording, 193 of the 194 Java samples in this category fall in the
  **35–40 s** bucket of the trace; nine already-eligible preparations account for
  only 29 Java samples across their own conversion path.
- Local bytecode inspection confirms that reviewed JOGL recognizes the canonical
  RGBA color model directly, whereas unmatched layouts use lazy custom conversion
  and a Java2D `Src` draw. The existing public converter still excludes images
  above 33,554,432 pixels.

**Next:** separately test a bounded 8192² candidate against the current converter,
with full real-host byte/metadata shadow validation and repeated non-shadow load,
CPU and memory comparisons. It raises the converter's maximum target array from
128 to **256 MiB per image**; this is a buffer limit, not proof of an additional
256 MiB peak relative to the native path, which also needs conversion storage.
Keep fresh ownership, fallback, original GL calls and default-off policy. Do not
silently relax the product bound based only on this recording.

### 2. Extended interpolation — real hotspot, previous shortcut did not win

Observed document-loader path:

`CModelSource.setup → CExtendedInterpolationExtension.setupFormStructure → updateInterpolatedForms_common → CWarpDeformerForm.getAllPointRef / WarpPointRef.getPos`

- 152 Java samples, concentrated at trace **25–30 s**, and approximately 3.63 GB
  sampled allocation weight across the whole interpolation category.
- `getAllPointRef` alone has approximately **1.27 GB** of `WarpPointRef` allocation
  weight. Local bytecode confirms a new list and point-reference wrapper per point
  on every call; these references bind back to mutable source/form state.
- **Do not simply enable/repeat the old warp-position projection experiment.**
  Ledger P01 already measured no stable net benefit and worse memory on its earlier
  host/model. That patch only eliminated one read-only projection subloop, not the
  entire interpolation setup or later write-bearing point-reference path.

**Next:** count initial setup passes and distinguish required first construction,
read-only projection and later mutation before proposing an algorithmic change.
No cross-call caching of mutable form arrays/point references; retain dirty writes,
interpolation bits, Undo semantics and fresh-output ownership. This is higher-risk
than the texture coverage experiment, and no new safe shortcut is established yet.

### 3. Model deserialization / buffered copies — allocation target, broader contract

The document-loader thread contains:

`ArchiveReader.readFileByFilePath → serialize.b.invoke → kotlin.io.ByteStreamsKt.readBytes → ByteArrayOutputStream.ensureCapacity / Arrays.copyOf`

and `UUSerialize.deserializeDoc_exe → SAXBuilder/XMLReader → PropertySet.readProperty`.
Float parsing adds 63 Java samples via `serialize.impl.G.a → Float.parseFloat →
FloatingDecimal/FDBigInteger`.

These stages dominate the **20–30 s** loader buckets, independent of texture prep.
Relevant allocation leaves include whole-stream `readNBytes`, byte-array output
streams, buffer expansion/copies and strings. Their generic leaf weights overlap
multiple subsystems; do not attribute every byte to one archive read.

**Next:** measure entry sizes and duplicated-buffer lifetimes at the specific
archive-reader closure, then consider size-aware single allocation or streaming
where the serializer contract permits it. Do not weaken archive validation, EOF,
size/error handling, float bits or XML character validation. The existing float
parse cache also has prior negative natural-workload evidence; a parse hotspot
alone is not evidence that re-enabling that cache will help.

### 4. PNG temporary-file cache and format conversion — narrower follow-up

The EDT contains **50 additional native-method samples** under PNG decoding,
separate from the 24 Java samples in the table. Stacks include:

`PNGImageReader.decodePass → FileCacheImageInputStream.read/readUntil → RandomAccessFile.read/write/seek`

Local bytecode confirms the byte-array image overload wraps already-resident PNG
bytes in `ByteArrayInputStream`, calls `ImageIO.read(InputStream)`, then
`UtImage.createSameTypeImage` converts the decoded image to the requested type.
So in-memory encoded data is nevertheless taking an ImageIO temporary-file cache
path, and image format conversion can add another full surface.

**Next:** an exact-call-site, per-read memory/byte-array-backed image input stream
experiment, preserving the original reader and final image type. **Do not globally
call `ImageIO.setUseCache(false)`**: that would change unrelated plugins and image
loads, and extra compressed-data buffering needs a bound. Keep cleanup, seek/EOF,
pixel/alpha/color-space behavior and failure fallback. No measured speedup is
claimed for this unimplemented experiment.

## What not to optimize from these samples

- The native event/network-wait category accounts for 1,509 samples, including
  AWT-Windows and HTTP selector waits. These are **not 1,509 CPU-hot samples**.
- Licensing and driver/OpenCL setup appear too, but no licensing checks, validation,
  security checks or GL synchronization contracts are removed to manufacture a result.
- Recorded GC pause events total about **0.692 s** in the direct window. The sum of
  all `GarbageCollection.duration` events is not a GC-pause metric because it may
  include concurrent phases. Large allocation weights are not proof that GC alone
  determines the 43-second loading time.
- Recorded file-read events total approximately 0.553 s, subject to JFR thresholds
  and possible overlap across threads. This is not all I/O; absent file-write events
  do not invalidate the sampled ImageIO cache-write stacks.
- Bootstrap samples also show verified host digest/JAR reads. Those deserve a
  separate bounded-identity investigation, not removal of exact-artifact attestation
  or an unsafe cache keyed only by path.

## Evidence and reproduction

Sanitized categories, leaves, event counts, allocation weights, artifact hashes and
recording hashes: [jfr-hotspots.json](observations/hotspots/jfr-hotspots.json).
Compact Java-sample table: [java-samples.csv](observations/hotspots/java-samples.csv).

`TextureLoadJfrReport.java` uses the JDK RecordingFile API to stream full recorded
stacks (not `jfr print`'s default five-frame truncation) and emits only method/thread
attribution, not event payloads or environment variables. Compile with JDK 17 and
pass `loading.jfr START_EPOCH_MS END_EPOCH_MS`; boundaries come from the first row
of `jvm-loading.csv` and `memory/ready.properties`. The Java source hash is recorded
with the sanitized observations. `run_native.py --profile` runs the diagnostic
capture with the same isolation/normal-exit assertions as the earlier harness.

Raw profiles, full reports, logs, process samples, hash envelopes and local bytecode
inspection remain in ignored **`build/texture-host/profile-{direct,native}/`** and
**`build/texture-host/hotspot-static/`**. Raw JFR can contain environment/system
properties: keep it private; do not commit or upload it. The earlier performance
archive and nine-run receipts are unchanged; this investigation is a separate
profile-only evidence set, not a new performance gain claim.
