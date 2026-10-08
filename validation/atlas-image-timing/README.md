# validation/atlas-image-timing — per-call atlas-path timing agent

Validation-only timing instrumentation for spec 020 (T029/FR-01). Weaves
stack-neutral enter/exit calls into reviewed Cubism methods (5.3.03 and 5.2.03
target lists) and records per-call durations + thread attribution inside the
task home.

## Targets (exact-signature, 5303-verified)

| metric | owner.method | measures |
| --- | --- | --- |
| updateTexture | `CTextureAtlas.updateTexture(ZZLcom/live2d/util/a/a;)V` | per-page rebuild window |
| setupCacheImage | `CTextureAtlas.setupCacheImage$cubism(ZLcom/live2d/util/a/a;)V` | page image generation |
| drawModelImage | `util.f.g.a(BufferedImage,Graphics,BufferedImage,IIDZ)V` | per-ModelImage draw kernel |
| editorBatch | `TAE_DataModel.v()V` | whole texture-set rebuild batch |
| editorInit | `TAE_DataModel.z()V` | edit-layer construction loop |
| setupEditLayer | `TAE__EditLayer_ModelImage.setupEditLayer()V` | per-ModelImage layer setup |
| updateMesh | `GEditableMesh2.updateMesh(Lcom/live2d/util/j/a;Z)V` | per-mesh triangulation |
| updateVertices | `GEditableMesh2.updateVertices()V` | vertex-position buffer rebuild inside updateMesh |
| updateIndices | `GEditableMesh2.updateIndices(Lcom/live2d/util/j/a;)V` | edge-version-gated index rebuild inside updateMesh |
| delaunayCompute | `editableMesh.b.b(Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;)Ljava/util/List;` | Delaunay candidate-triangle computation |
| delaunayApply | `editableMesh.b.a(Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;Ljava/util/List;ZLcom/live2d/util/j/a;)V` | Delaunay apply (edge/indices mutation) |
| autoTriangulate | `editableMesh.triangulation.g.a(Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;Lcom/live2d/util/j/a;)V` | fused non-Delaunay auto-triangulation |

The last five metrics (ids 11–15, T029-P3A) decompose `updateMesh` by call
structure: `updateMesh` invokes `updateVertices`, a cancellation check, then
`updateIndices`, and `updateIndices` invokes either
`delaunayCompute`+`delaunayApply` or `autoTriangulate`. Inclusive durations
overlap by design and each method carries its own gating and instrumentation
overhead — do not read the structure as an exact time equation. On 5.2.03 the
`com.live2d.util.j.a` context type is `com.live2d.util.i.a`; all other
descriptors are identical between versions.

Design: `enter(I)/exit(I)` push/pop a ThreadLocal stack — zero new locals,
exception-table-neutral, unmatched exits counted as `unpaired`. Opt-in token
`turboism.validation.atlasTiming.optIn=ATLAS_TIMING_EXPLICIT_OPT_IN`; output dir
`turboism.validation.atlasTiming.output`. Forbidden with `-meshprobe`/`-meshfix`
(their reflective digest inside updateMesh distorts timing).

Offline gates (build.sh): selfcheck (nesting/exceptions/unrelated-class
pass-through), official-JAR non-execution shape probe (per-profile owner×desc
exactly-once, owner SHA256s recorded), live-JVM harness, opt-in gate. The agent
jar is not reproducible — every build emits a different archive; build.sh
prints `agentSha256` per run (e.g. `9f9d5be9…` for the P3A build, `b638a39a…`
for the reviewer's rebuild), so tie evidence to the run's printed hash rather
than to a fixed value here.

## Known measurement limits

This agent's output is decomposition evidence only — never performance
acceptance evidence.

- **Inclusive timing only.** Records carry a millisecond `startEpochMs` and the
  thread name, so nanosecond-level nesting cannot be reconstructed
  unambiguously (same-named threads, wall-clock adjustments). Report per-method
  inclusive statistics and call counts; do not derive precise exclusive time
  from differences.
- **Exceptional exits are invisible, not timed.** `exit` scans the thread stack
  for a matching metric; a frame abandoned by recursion or an exception inside
  the same metric can pair with an older entry, and `enter` pushes dropped at
  stack depth ≥512 can let a later `exit` mis-pair with a stale frame.
  `unpaired` counts discarded entries but is an incompleteness indicator, not
  attribution.
- **Recording is not fully asynchronous.** `record()` flushes to disk on the
  host thread every 64 records in addition to the daemon flusher, and the two
  writers share a `timing-summary.properties.tmp`→rename window — observer
  overhead may only be estimated from off/on paired legs.
- Reliable nested pairing and exceptional-completion semantics (call id,
  thread id, monotonic timestamps, invalidation) are a separate follow-up task.

## Real-host baseline — job 245, queue-a9578d5ef86741c18aeadceca5734b1e

Heavy fixture (heavy.cmo3 `029e9a4e…`), 5303 (`bd0a23b9…`), production agent
`5472b991` (mesh-hash fix ON by default), scene = open texture-set editor → OK.
PASS; fixture unchanged; 1,903 records, 0 dropped, 0 unpaired.

### Timeline (t0 = editorInit start, ms)

```
0      57609   59772                          175569
|------z()-----|--v()--------------------------------|
  EDT: 585×setupEditLayer      Thread-95: 6×updateTexture
       719×updateMesh = 56.1s        585×drawModelImage = 110.2s
```

- **Phase 1 (EDT, serial):** `TAE_DataModel.z()` = 57.6s — per-image edit-layer
  build dominated by triangulation (already optimized by spec 021 hash fix;
  TreeNode samples now 0).
- **Phase 2 (Thread-95, serial):** `TAE_DataModel.v()` = 115.8s EDT-side window
  spawning one worker; on Thread-95: 6 `updateTexture` calls = 115.3s, inside
  them 585 `drawModelImage` calls = 110.2s kernel time.
- The two phases are strictly serial; EDT stays busy throughout (progress/modal
  wait during phase 2).

### Per-page breakdown (updateTexture windows)

| call | start ms | dur s | images | kernel s |
| --- | --- | --- | --- | --- |
| 0 | 60234 | 14.9 | 101 | 12.4 |
| 1 | 75099 | 2.5 | 1 | 2.2 |
| 2 | 77597 | 1.4 | 30 | 1.3 |
| 3 | 78984 | **92.5** | **322** | 90.5 |
| 4 | 171507 | 3.6 | 121 | 3.4 |
| 5 | 175060 | 0.5 | 10 | 0.3 |

drawModelImage per-call: mean 188ms, median 232ms, p90 329ms, p99 499ms,
max 3043ms.

### Kernel composition (JFR, f.g.a stacks n=4,970)

- private workaround `f.g.a(BI,Graphics2D,BI,int,int)` pixel loop: 65%
- `Arrays.fill(int[])` per-draw scratch clear: 19.6%
- `jp.noids.graphics.h.a(BI,int)` edge expansion: 8.1%
- `Arrays.fill(byte[])`: 7.2%
- dangerous two-image `h.a(BI,BI)`: **not observed**

### Concurrency findings (static, 5303)

- Call chain: `setupCacheImage → D.a(CWritableImage,ui.g,CWritableImage,IIZ) →
  f.g.a(9-arg) → f.g.a(private) → h.a(BI,int)` once per image draw.
- `f.g` and `util.D` are field-less singletons — stateless services.
- `jp.noids.graphics.h` holds **4 static int[] scratch + static int y**, written
  by `h.a(BI,int)` on every draw → kernel is NOT reentrant; intra-page
  parallelism must serialize h (~8% of kernel) or provide per-thread scratch.
- `jp.noids.util.UtCache` image pool: all methods `static synchronized` —
  thread-safe, low contention (short critical sections).

### Kernel semantics — verified by bytecode (5303)

`setupCacheImage$cubism` serial loop per entry: `getFilteredImage` →
`calcModelImageLocalToAtlasTransform` → `g.a(CAffine)` on the shared page
Graphics2D → `D.a(page,g,src,w,h,flag)` → `f.g.a` public → private workaround
`f.g.a(dst,pageG,src,x,y)`:

```
scratch7  = pool(src.w×src.h)   f.a(src→7) RAW RASTER ARRAYCOPY (not drawImage);
                                h.a(7,3) scanline edge-fill, arg = ALPHA THRESHOLD
                                (px with a<=3 inherit last a>3 pixel's RGB,
                                row L→R then col T→B; STATIC arrays)
scratch8  = pool(src-size)      BYTE_GRAY alpha mask from 7; 7 forced opaque
scratch9  = pool(dst.w×dst.h)   PAGE-SIZE: fill 0 + drawImage(7,x,y) w/ pageT, hints i.a BICUBIC
scratch10 = pool(dst.w×dst.h)   PAGE-SIZE mask: fill 0 + drawImage(8,x,y), hints i.c BILINEAR
merge loop over dst w×h:        PAGE-SIZE: 9[px] = mask<<24 | rgb&0xFFFFFF
pageG.drawImage(9,0,0)          PAGE-SIZE SrcOver composite, hints i.b NEAREST
release ×4 (UtCache, synchronized)
```

Private `a(BI,Graphics2D,BI,int,int)` has exactly **2 callsites** (BCI 427, 597),
both inside the public 9-arg method — a body-level replacement covers all paths.

**Per-image draw cost is O(page), not O(tile)** — ~3 full-page passes per image.
For the 322-image page: 322 × ~16MP ≈ the observed 92.5s. The workaround exists
to get correct SrcOver alpha compositing on platforms where direct drawImage
misbehaves; scratch9 outside the tile is transparent so the full-page SrcOver
composite only lands tile pixels.

### Implication for spec 020 direction

Page-level parallelism ceiling ≈ 115.3/92.5 ≈ **1.25×** — one page holds 322 of
585 images (92.5s). Two better directions, in decreasing ROI:

1. **Tile-size scratch**: scratch9/10 + merge loop + composite only need the
   tile bounds (+3px bleed). O(page)→O(tile) per draw — potentially >10× on the
   dominant page, serial, no concurrency hazards.
   **Offline equivalence PROVEN** in `../atlas-image-tile-scratch/`:
   5000 randomized cases (transforms incl. rotation/shear/off-page, 3 src
   types, 2 page types) — **0 pixel diffs**, merge work = 4.4% of original
   (~23×). Preferred impl: replace private method body with a delegate call to
   an injected class that calls the REAL host helpers (`UtCache`, `f.*`,
   `h.a`, `i.*`) — all are public static.
2. **Intra-page parallel pipeline**: resample/expand/mask/merge into per-worker
   private scratch (parallel), serial SrcOver composite in entry order.
   Hazards: `h` static arrays must serialize (~8% of kernel) or be confined;
   memory = page-size scratch × workers. Keeps O(page) work per draw.

Export path remains unmeasured (scene driver has no export action).

Evidence: `TurboismValidation/atlas-image-shadow/5303-t020-timing-01-heavy-nolayout-atlastiming-jfr/queue-a9578d5ef86741c18aeadceca5734b1e/turboism-home/atlas-timing/`

## T029-STACK — entry-side stack sampling (ids 0/1 only)

Optional second opt-in: `-Dturboism.validation.atlasTiming.stackOptIn=ATLAS_STACK_SAMPLE_EXPLICIT_OPT_IN`
on top of the base `atlasTiming.optIn` token. Only the `updateTexture` (0) and
`setupCacheImage` (1) entries are sampled; sampling is independent of the
enter/exit pairing (an entry that later exits by throwing still lands its
sample) and shares no queue, flush, or file with the timing records.

`StackSamples` (single class, not a framework):

- Budget reserved **before** collecting: ≤512 reservations total; `seq` is the
  unique reservation id. Over-cap attempts are counted (`droppedBudget`),
  never collected.
- JDK 17 `StackWalker` with a walk limit of 97 frames, keeping at most 96
  (`truncated=1` past that). Only `getClassName()`/`getMethodName()` strings —
  no `RETAIN_CLASS_REFERENCE`, no host getters, no retained host objects.
- The producer never waits for queue capacity: the bounded queue (≤512) is
  guarded by one short lock that also owns every counter mutation, so queue
  ownership transfer and accounting linearise together and every
  `snapshot()`/status build reads a consistent point in time. The lock itself
  is still a short wait; it is never held while walking a stack or performing
  file IO. Overflow is `droppedQueue`.
- One daemon writer drains to `timing-stacks.txt` and keeps a single-writer
  `timing-stacks.status` fresh through its own temp file + atomic rename
  (independent of the timing probe's `.tmp` window). A batch whose write threw
  is `unconfirmedWrites` — a partial append may already be on disk; reconcile
  by `seq` rather than trusting counts alone, and the run is `incomplete=1`.
- Status accounting has two counters: `statusErrors` is cumulative and never
  resets; `statusWriteFailures` counts the consecutive run and resets on a
  successful write. The first status failure already sets `incomplete=1`
  (sticky). After 8 consecutive failures, `statusDisabled=1` stops further
  status attempts only — record sampling and draining continue normally.
- Status counters: `attempted / reserved / sampleError / queued / droppedQueue
  / droppedBudget / droppedStopped / droppedInFlight / dequeued / written /
  unconfirmedWrites / truncated / namesTruncated / statusErrors /
  statusWriteFailures / statusDisabled / pending / inFlight / inWrite /
  queueDepth / incomplete / state`. Invariants at every snapshot:
  `attempted = reserved + droppedBudget + droppedStopped`,
  `reserved = sampleError + droppedQueue + droppedInFlight + queued +
  inFlight`, `queued = dequeued + pending`, `dequeued = written +
  unconfirmedWrites + inWrite`. In-flight work is never counted as lost — it
  stays visible as `inFlight`/`pending`/`inWrite`.
- If the writer dies (any failure outside the write path), the terminal reason
  is recorded in memory under the same lock that sets `incomplete=1`, and a
  best-effort `state=TERMINATED:<reason>` status is attempted. Once terminal,
  the reservation gate refuses new entries as `droppedStopped` (no stack walk,
  no seq consumed), and samples still in flight at that moment are dropped at
  the offer point as `droppedInFlight` — bounded and accounted, never retried.
- Record line: `stack seq=<n> metric=<name> tid=<id> thread="<name>" depth=<d>
  truncated=<0|1> frames="a.b;c.d;…" truncNames=<n>`. Names are length-capped
  on the raw value first, then separators/control characters are neutralised
  (`"`→`'`, `;`→`,`, `\`→`/`, CR/LF/control → space); a truncated name carries
  a trailing `..` and is counted in `truncNames`.
- Disabled = no writer thread, no walker, no files; an in-memory state only.
  A missing/wrong stack token must not poison `blocked`, and the stack token
  can never enable anything without the base token.

Offline legs (build.sh): selfcheck drives direct/worker/deep/exceptional
entries, over-budget (clamped injected caps), gated full-queue, unconfirmed
writes, a throwing collector, a gate-controlled mid-write interleave with
concurrent consistent snapshots, and writer death with terminal accounting —
all deterministic, no timing races; harness legs run both tokens,
base-only, wrong-token, and stack-token-only. Marker fixtures
(`appCtrlImpl.ap`/`al`, `exporter/w`) only place their owner.method names on
the sampled stacks.

### Stack-sampling evidence limits

- Records are **raw frames only**. The tool never classifies callers and never
  claims who initiated or scheduled a call — a marker frame only proves the
  sampled entry ran underneath it. Under a nested event pump (SecondaryLoop),
  unrelated EDT work can carry the same marker frames; read stacks as
  "executed under", never "caused by".
- This slice does not exercise any real nested pump; marker frames in fixtures
  are name-shape evidence, not pump semantics.
- There is no collection-boundary or final-confirmation marker: a native host
  exit can drop undrained samples **and** the final status, a drained-queue
  status is only a momentary state, and `TERMINATED` also covers abnormal
  exits. This slice only ever provides the positive stack samples that reached
  the file — it never proves full coverage or absence of tail loss.
  `incomplete=0` merely means no fault had been recorded at that snapshot. A
  missing `timing-stacks.txt`, a missing/stale status file, or zero samples
  never proves a path did not run (sampling may have been disabled, or the
  writer lost).
- Budget and queue caps bound *output*, not per-sample cost — each accepted
  sample still walks up to 97 frames. Host-side overhead is unmeasured; use
  on/off paired legs before reading anything into timings.
- Frame names are interpreted against the 5303 mapping; that says nothing
  about other versions, and the JDK-side sampler itself does not verify host
  version.
