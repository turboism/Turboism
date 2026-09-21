# atlas-image-tile-scratch — bbox-scratch equivalence probe

Offline validation slice for spec 020, direction: **shrink the per-draw page-size
scratch buffers in `com.live2d.util.f.g`'s private workaround
`a(BufferedImage dst, Graphics2D pageG, BufferedImage src, int x, int y)` to the
transformed-tile bounding box**.

## Why this slice exists

Job 245 real-host timing (see `../atlas-image-timing/README.md`) showed the
dominant cost is not serial page processing — it is that **every model image
performs 3-4 full-page passes** inside the workaround:

```
scratch9  = pool(dst.w x dst.h)   // PAGE-size color  -> fill + drawImage(src@x,y under pageT)
scratch10 = pool(dst.w x dst.h)   // PAGE-size mask   -> fill + drawImage(mask@x,y under pageT)
merge loop: for every page px: s9[px] = mask<<24 | rgb&0xFFFFFF
composite:  pageG.drawImage(s9, 0, 0)   // SrcOver, identity transform
```

For a 4096x4096 page that is ~3 full-page passes per tile; 322 tiles on the big
page -> 92.5s. The tile content occupies only a small bbox.

## Verified host semantics (5303, class sha ff1d1ce9)

- `f.a(srcBI, dstBI, 0)` = **raw raster arraycopy** (per-row or full
  `System.arraycopy` over the DataBuffer) — NOT drawImage, no premultiply
  round-trip.
- `h.a(bi, 3)` = **directional scanline edge fill**: per row left→right,
  track last pixel with alpha>3; pixels with alpha<=3 inherit that RGB
  (keeping their own alpha). Second pass same per column top→bottom.
  **The int arg is the alpha threshold (3), not a radius.**
  `h` holds 4 static int[] scratch buffers -> not reentrant (matters only if
  a future impl parallelizes; the bbox variant stays serial).
- `i.a` hints = QUALITY set, interpolation **BICUBIC** (color draw).
- `i.c` hints = QUALITY set, interpolation **BILINEAR** (mask draw).
- `i.b` hints = SPEED set, interpolation **NEAREST_NEIGHBOR** (final composite).
- All helpers (`UtCache.getBufferedImage`, `f.a/c/f`, `h.a`, `i.a/b/c`) are
  public static -> a production delegate can call the real ones.

## What the slice tests

- `KernelOriginal` — faithful Java port of the bytecode (same call sequence,
  same loop bounds, same raster ops).
- `KernelTiled` — same kernel but scratch9/10 sized to
  `pageT.transform(srcRect at x,y).getBounds2D()` (+1px margin) clipped to the
  page; draws use `Translate(-bx,-by) * pageT`; merge loop over bbox; composite
  `drawImage(s9, bx, by)`.
- `DiffHarness` — randomized differential: page 64-1024, src 4-512, draw pos
  incl. negative/off-page, transforms {identity, translate, scale 0.2-3.0,
  fractional translate+aniso scale, arbitrary rotation, shear}, src types
  {INT_ARGB, INT_ARGB_PRE, INT_RGB}, page types {INT_ARGB, INT_ARGB_PRE},
  src alpha modes {opaque, clear, border-only, noise, binary}.

## Result

```
cases=5000 diffs=0  RESULT: PIXEL-IDENTICAL
merge-loop work: tiled/orig = 0.044   (~23x reduction)
```

## Interpretation

- The bbox change is **serial-safe and pixel-exact** — no concurrency, no
  locking, no ordering hazards. It cuts the dominant per-draw cost from
  O(page) to O(tile bbox).
- On the heavy fixture (big page 4096-class, tiles ~512-class) expected gain
  on the 92.5s page is an order of magnitude; real number needs host timing.
- Residual gap to production: this proves *semantic* equivalence of the
  algorithm change. A real patch must either (a) surgically edit the private
  method's bytecode (~5 edit points: pool sizes, transforms, loop bounds,
  composite offset), or (b) replace the private method body with a delegate
  call to an injected class that reimplements the pipeline while calling the
  REAL host helpers (`UtCache`, `f.*`, `h.a`, `i.*`) — preferred: all
  heavy semantics stay in verifiable Java and reuse host behavior bit-for-bit.

## Harness bug worth remembering

The first run showed 100% diffs — caused by the *harness* copying the page via
`drawImage` (premultiply round-trip drifts semi-transparent pixels). Raster
`System.arraycopy` copy fixed it. The kernels themselves were never wrong.
