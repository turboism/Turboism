# atlas-image-tile-patch — bbox-scratch kernel patch agent (validation-only)

Spec 020 candidate implementation: replace the body of Cubism 5.3.03
`com.live2d.util.f.g.a(BufferedImage, Graphics2D, BufferedImage, int, int)`
(the private page-size-scratch workaround) with a call to
`TiledDrawDelegate` — the identical pipeline but with scratch9/10, the merge
loop, and the SrcOver composite bounded to the transformed-tile bbox.

## Design

- `TilePatchTransformer.patch` — two-pass ASM rewrite of `com/live2d/util/f/g`:
  1. **Shape gate** — the private method must carry the verified 5303 anchors:
     `UtCache.getBufferedImage`×4, `UtCache.release`×8, `h.a(BI,int)`×1,
     `f.a(BI,BI,I)`×1, `f.a(BI,I)`×1, `f.c`×2, `f.f`×3, `i.a/i.b/i.c`×1 each,
     `Graphics2D.drawImage`×3, `Arrays.fill`×2, exactly one method match.
     Any deviation → class left unmodified (fail closed).
  2. **Body replacement** — emits only `TiledDrawDelegate.draw(a1..a5); return`.
- `TilePatchTransformer.instrumentPageHash` — stack-neutral RETURN-hook on
  `CTextureAtlas.setupCacheImage$cubism(ZZ..)V` that SHA-256s the produced
  page image (`getCachedAtlasImage().getImage().getJBufferedImage()` raster).
  Applied in BOTH run modes → per-page digests are directly comparable.
- `TiledDrawDelegate.draw` — bbox pipeline calling the REAL host helpers
  (`UtCache`, `jp.noids.graphics.f.*`, `h.a`, `i.a/b/c` — all public static).
- `TilePatchAgent` — opt-in token `TILE_PATCH_EXPLICIT_OPT_IN`; mode property
  `tilePatch.mode=tiled|hashOnly`; writes `tilepatch-summary.properties` +
  `tilepatch-pages.txt` into the task home.

## Offline evidence (build.sh)

- SelfCheck: fixture `g` patches with all anchors; anchor-less class and wrong
  owner fail closed; atlas hash hook fires and records.
- Official probe on real 5303 JAR (`bd0a23b9…`):
  `g.class` sha `ff1d1ce9…` → patched (`e5e6f6cd…`); `CTextureAtlas` sha
  `cec7892e…` → hook applied.
- Live-JVM harness (fixture replicas with host class names):
  - `manual` mode: transformer-patched `g` in a child-first loader vs unpatched
    `gorig` reference — 400 randomized cases, **0 pixel diffs**,
    delegate actually ran (147 draws / 253 clipped-skips).
  - `agent` mode under `-javaagent`: 200 cases, **0 diffs**, delegate ran.
- Opt-in gate: without the token the agent is inert.

Agent jar sha256 `a7d76e873c6556005cffbfdb4b798e0feadefddceeaf9310fdccb294897ea5f5`
(ASM relocated to `dev/turboism/validation/tilepatch/shaded/asm97`). Supersedes
`416cfe46…` which had the frame-stripping bug documented below.

## Harness bugs caught (worth remembering)

- First harness run showed 0 real diffs but `probeDraws=0` — the child loader
  was parent-first and silently loaded the UNPATCHED fixture. Fixed to
  child-first for `g` only + an explicit "delegate must have run" assertion.

## Real-host runs

### tp01ref (job 339f3890, succeeded) — hashOnly reference

- `pageHashes=6`: pages are 4096², 4096², 2048², **8192²** (the dominant one —
  322 images), 2048², 2048². A 8192² page = 67 MP → the private workaround's
  3 full-page passes per image ≈ 200 MP of scratch traffic per image; that is
  the 92.5 s page.
- Timing baseline (this run): drawModelImage 585× = 100.1 s, updateTexture
  6× = 104.8 s, editorInit 51.3 s.
- `tilepatch-pages.txt` digests are the reference for the tiled run.

### tp01tiled (job 2d85cdee, FAILED — transformer bug, fixed)

- The shape gate PASSED on the real host: all 12 anchor families matched,
  body replaced, page-hash hook installed.
- Then `java.lang.VerifyError: Expecting a stackmap frame at branch target 628`
  on `g.a(BI,Graphics,BI,IIDZ)V` — the rewrite pass used
  `ClassReader.SKIP_FRAMES` with a `COMPUTE_MAXS`-only writer, stripping every
  method's frames. Class never defined → run aborted.
- **Why offline didn't catch it**: the fixture replica's public method had no
  branches → no frames required → stripped frames were harmless there; the
  official probe checked bytes only, never defined+verified the patched class.
- Fixes: (1) pass-2 reads with `SKIP_DEBUG` only — frames flow verbatim
  through unmodified methods; (2) fixture `g`/`gorig` public methods now carry
  a real branch + loop (frames mandatory); (3) official probe gained a
  frame-retention assertion AND a real verification leg — patched official
  class is defined in a child-first loader backed by the real JAR under
  `-Xverify:all` (`officialVerify=PASS`, `publicMethodFrames=16`).
- New agent sha `a7d76e87…`; rerun queued as tp02tiled.

### tp02tiled (job 72ce9aef, succeeded) — first working real-host patch

- Patched class verified and loaded; delegate ran **574 draws** (+1 skipped,
  empty bbox). The public kernel was invoked 575× in both runs — the remaining
  10 ref-run draws belong to a page that never generated (see below).
- **Pixel equivalence on the real host: all 5 generated pages have IDENTICAL
  SHA-256 digests to tp01ref** (`tilepatch-pages.txt` 5/5 match).
- **Speed**: drawModelImage total 100.1 s → **19.3 s (−81%)**;
  updateTexture 104.8 s → **23.6 s**; dominant 8192² page call 86.1 s →
  **15.3 s (5.6×)**; editorBatch v() 105.2 s → 24.6 s.
- Scratch traffic: `bboxPx=144.5M` vs `pagePx=23.9G` — the tiled path touched
  **0.6%** of the original per-image scratch area.
- Coverage gap: page 6 (2048², 10 images) never generated in the tiled run —
  its updateTexture is lazily triggered and in ref landed inside v()'s 105 s
  window with only ~12 ms to spare; the 24.6 s tiled window ended before the
  trigger fired and the driver closed the editor. Not a pixel diff — a page
  that never materialized.
- Side observation: timing summary `blocked=evidence write failed:
  NoSuchFileException` — the final summary write raced process teardown;
  `timing-calls.txt` was still complete (1891 records). Cosmetic, noted.

### tp03tiled (job 3481eca3, succeeded) — settle=30s, still 5 pages

- Same result: 5/5 generated pages identical digests, 574 draws + 1 skip,
  drawModelImage 19.6 s. The 6th page still never generated — 30 s of dwell
  after CONFIRMED was not enough.
- Timeline analysis: in ref, the 6th `updateTexture` began ~104.7 s after
  v() started and ran back-to-back after page 5 (same worker, zero gap) —
  submitted into the worker queue while v() was still draining. Initial guess
  was a wall-clock-delayed invalidator; tp04/tp05 later disproved that (see
  below) — it is demand/event-triggered instead.

### tp04tiled (job 3a403562, succeeded) — settle=150 s, still 5 pages

### tp05tiled (job 61a6f6ff, succeeded) — settle=240 s, still 5 pages

- Both succeeded; identical outcomes to tp02/tp03: 5 generated pages, all
  digests match tp01ref, 574 draws + 1 skip, kernel ~19–21 s.
- Conclusion on page 6: the last `updateTexture` is **not** a fixed-delay
  invalidator within the observed window (240 s of post-CONFIRMED dwell did
  not trigger it). It is demand/event-triggered by something the fixed scene
  does not perform (e.g. a second texture set's deferred load, or an atlas
  UI interaction). In ref it happened to land inside v()'s 105 s window.
- Coverage statement: **every page that generated in any tiled run is
  pixel-identical to the same page in the reference run (5/5)**; the
  ungenerated page is a trigger-coverage artifact, not a pixel difference —
  its 10 images would run through the same patched method.

## Headline result (heavy fixture, real 5303 host)

| metric | reference | tiled | Δ |
|---|---:|---:|---:|
| drawModelImage total (575 draws) | 100.1 s | 19.3–20.6 s | **−80%** |
| per-draw median | 227 ms | **7 ms** | ~32× |
| per-draw p90 | 273 ms | 99 ms | ~2.8× |
| dominant 8192² page (322 images) | 86.1 s | 15.3–19.1 s | **~5×** |
| updateTexture total | 104.8 s | 23.6–25.3 s | −77% |
| editorBatch v() wall | 105.2 s | 24.6–26.3 s | **−76%** |
| editorInit z() (triangulation, untouched) | 51.3 s | 51.5 s | 0 — control |
| scratch pixels touched | 23.9 G | 144.5 M | **0.6%** |

All three tiled runs are mutually consistent (digests, draw counts, timing).

Fixture: heavy.cmo3 `029e9a4e…` (same as the timing baseline run, job 245).

## Export path discovery (in progress)

The user's second reported scenario — export — shares the kernel statically:
`exporter.w → CTextureAtlas.getCachedImage → updateTexture` when the cache is
dirty/missing. Real-host menu labels are not readable offline (obfuscated
constant pools), so the scene driver gained two opt-in diagnostics:

- `menuDump` (`TURBOISM_ATLAS_IMAGE_SHADOW_MENU_DUMP=true`): bounded read-only
  JMenuBar enumeration → `run/menu-tree.txt`. Confirmed the real path:
  `文件 → 导出运行时文件 → 导出为moc3文件...` (indices `0 → 0.0.9 → 0.0.9.0.0`).
- `exportProbe` (`TURBOISM_ATLAS_IMAGE_SHADOW_EXPORT_PROBE=true`): clicks that
  item via the host's own menu selection path, captures the dialog it raises,
  dumps the component tree to `run/export-dialog.txt`, then dismisses it with
  a plain `WINDOW_CLOSING` event — no export is ever confirmed.

These runs carry the same timing/tile-patch aux agents, so an export scene can
reuse the per-call `updateTexture` timing and page-hash comparison unchanged.

### Export probe evidence (job 275, interrupted by host reboot)

The export reference run (`exportref`, hashOnly + timing + `exportProbe`) was
killed by a host OS reboot mid-scene — no durable lifecycle verdict exists and
the job is quarantined (evidence retained, not terminal). Its incrementally
written timing log nevertheless captured the complete export kernel phase:

- Clicking `导出为moc3文件...` starts the atlas rebuild **immediately** — the
  captured dialog is a *progress* dialog (`进度`, JProgressBar + Cancel), not
  a save dialog. The kernel cost is therefore measured without any save-path
  automation.
- **6× `setupCacheImage` + 6× `updateTexture` + 585× `drawModelImage` all on
  `AWT-EventQueue-0`** — export rebuilds every atlas page directly on the EDT
  (editor-open uses a worker thread), freezing the UI for the duration.
- Export `updateTexture` total: **134.6 s** (editor-open ref: 104.8 s on
  Thread-95 — same 585 draws, slower on the EDT).
- Export covers **all six pages including page 6** — the page that never
  triggered in editor-open tiled runs. The coverage gap is a
  scene-coverage artifact of editor-open only, not a pipeline difference.

Page digests were lost with the JVM (they were buffered for a shutdown hook;
now written incrementally per page — crash-safe).

### Export tiled run (job 276, COMPLETE)

The paired tiled run completed the full scene with a durable verdict. The
export probe triggered a complete atlas rebuild **through the patched kernel**:

- 18 `updateTexture` total = three full passes: 6 on `AWT-EventQueue-0`
  (export probe), then 6 on `Thread-91` + 6 on `Thread-102` (editor-open
  pipeline, two texture-set loads).
- **All six page digests — including page 6 (`fe58a6a6…`) — are identical to
  the editor-open reference digests.** The previously uncovered page is now
  proven pixel-identical through the patched kernel on the export path.
- Export pass on EDT: 6 `updateTexture` ≈ **20.6 s** vs the crashed
  reference run's **134.6 s** (−85%). Per-pass draws: 585, drawModelImage
  ≈16 s vs ref 129.2 s.
- Digests repeat identically across all three passes — deterministic.

### Export reference run (job 277, COMPLETE) — same-path A/B closed

`exportref2` (hashOnly, incremental digests) completed cleanly:

- 12 page digests = 2 passes (export + editor-open), all matching the
  established set.
- Export pass on EDT: `updateTexture` **126.7 s**, draws 585 / 122.1 s.
- Editor-open pass: 127.8 s (slower than job 245's 104.8 s — system
  variance; the same-path export comparison is unaffected).

**Export-path A/B verdict: digests IDENTICAL (6/6 unique pages, incl. page 6)
— kernel 126.7 s → 20.6 s on the EDT (−84%).**

Combined with the editor-open evidence, the tile-bbox patch is now proven
pixel-identical on both paths that drive `updateTexture` in this scene.

### Production-path run (job 05d1e711, prod01) — pool-oversize defect found and fixed

The first real-host run of the **production** transformer (default-on
preference, hashOnly digest observer) produced 5 page digests — all identical
to reference — and a fast kernel (drawModelImage 575× ≈ 17.9 s vs ref ~100 s).
But it also surfaced a real defect the exact-size fixture pool had hidden:

- `AtlasTileBboxDelegate.draw` threw `ArrayIndexOutOfBoundsException` at the
  merge loop on 3 of 575 draws. Cause: `UtCache.getBufferedImage(w,h,type)` is
  a **type-keyed pool** that may return an image with `w≥req && h≥req` and
  `area ≤ 9×reqArea` — i.e. **larger than requested**. The merge loop had
  indexed `s10`'s raster by `s9`'s row count, which overflows when the two
  pooled images differ in real size.
- Fix (in both delegates): compute the bbox from the **union of the real
  s7/s8 bounds** (covering pooled-oversize draws exactly as the original's
  page-sized scratch did), and bound the merge loop by the **requested**
  `(bw,bh)` with each raster indexed by its own stride. Pool-oversize tails
  outside the bbox keep cleared-alpha-0 pixels, so the composite is a no-op
  there — identical to the original's page-sized version where the same
  regions carried mask-alpha 0.
- The fixture `UtCache` replicas (runtime tests + fixture-agent) now model the
  real scoring contract so the offline harness exercises oversize reuse.
- The 3 failed draws self-healed through the host task chain (digests still
  matched), but the crash was unacceptable — fixed before the production
  re-run (prod02).
