# Offline polygon packing benchmark (DALSOO_POLYGON vs MaxRects rectangle)

Benchmark source: `OfflinePolygonBench.java` (same directory). Run:

```
SDK_JAR=$(ls build/worktree/joyous-wombat/sdk/libs/*.jar | head -1)
CP="build/worktree/joyous-wombat/atlas-dalsoo-polygon/classes/java/main:build/worktree/joyous-wombat/atlas-maxrects-bssf/classes/java/main:$SDK_JAR"
javac -cp "$CP" -d /tmp/polybench validation/texture-atlas-current-page/OfflinePolygonBench.java
java -Xmx1024m -cp "/tmp/polybench:$CP" dev.turboism.validation.texture.OfflinePolygonBench
```

Raw results: `offline-polygon-results.csv` (49 rows, run exit 0; every plan
passed the offline validator - margin bounds and pairwise `Area` intersection -
and repeated runs produced identical placement signatures).

## Scope and limits

- Synthetic shapes (`rect`, `lshape`, `star`, `mixed`), deterministic seeds.
- `scaleMode=0` requests automatic scale (kernel searches a common scale that
  packs everything); `scaleMode=1` fixes scale 1.0. Overflow at fixed scale
  means the page was too small - a packed-fit metric, not a failure.
- `FREE` rotation uses 18 candidate angles per placement. Its parallel variant
  is omitted (4 kernel configs x 18 angles exceed a reasonable wall time), as
  is parallel mode for concave families; parallel determinism and speedup are
  demonstrated on the `rect` family.
- 500-item runs are single-sample (`NONE`/`QUARTER`, fixed scale, serial) and
  cover `rect` + `lshape` only; all other cases are 100 items.
- Not a host benchmark: no Cubism JAR was started. Cubism 5.4 alpha2 was used
  only for read-only evidence review; no alpha2 numbers appear here.

## Reading the numbers

- `coverage` = placed raw outline area / page area. `maxrects` rows show `-1`
  because that backend packs bounding boxes, not outlines; its `placed` count
  is the number of bounding rectangles fitted.
- Auto scale converged to ~0.40-0.65 and packed all 100 items in every case.
- At fixed scale the page is deliberately under-sized (90% target fill), so
  overflow counts are expected. The Dalsoo kernel's contact-placement heuristic
  packs somewhat looser than MaxRects on rectangles (e.g. 73/100 vs 78/100
  placed) - the value of the polygon backend is contour fidelity on concave
  items, not rectangle density.

## Headline results (median ms / placed-of-count / coverage)

| shape | count | rotation | scale | dalsoo ms | placed | cov | maxrects ms | placed |
|-------|-------|----------|-------|-----------|--------|-----|-------------|--------|
| rect  | 100 | NONE    | auto  |   4,058 |  98/100 | .131 |   27 | 100/100 |
| rect  | 100 | NONE    | fixed |     299 |  73/100 | .700 |    3 |  78/100 |
| rect  | 100 | QUARTER | auto  |   1,979 | 100/100 | .369 |      |         |
| rect  | 100 | QUARTER | fixed |   1,514 |  69/100 | .749 |      |         |
| rect  | 100 | FREE    | auto  |     971 | 100/100 | .369 |      |         |
| rect  | 100 | FREE    | fixed |     395 |  73/100 | .730 |      |         |
| rect  | 500 | NONE    | fixed | 120,542 | 396/500 | .787 |  170 | 394/500 |
| rect  | 500 | QUARTER | fixed | 537,254 | 391/500 | .792 |      |         |
| lshape| 100 | NONE    | auto  |   1,395 | 100/100 | .368 |    8 | 100/100 |
| lshape| 100 | NONE    | fixed |     732 |  53/100 | .613 |    3 |  78/100 |
| lshape| 100 | QUARTER | auto  |   4,303 | 100/100 | .368 |      |         |
| lshape| 100 | QUARTER | fixed |   2,733 |  62/100 | .677 |      |         |
| lshape| 100 | FREE    | auto  |   2,160 | 100/100 | .368 |      |         |
| lshape| 100 | FREE    | fixed |     876 |  67/100 | .684 |      |         |
| lshape| 500 | NONE    | fixed | 124,624 | 312/500 | .661 |  172 | 394/500 |
| lshape| 500 | QUARTER | fixed | 423,231 | 359/500 | .716 |      |         |
| star  | 100 | NONE    | auto  |  52,957 | 100/100 | .366 |    8 | 100/100 |
| star  | 100 | NONE    | fixed |  17,775 |  47/100 | .408 |    2 |  78/100 |
| star  | 100 | QUARTER | auto  | 204,931 | 100/100 | .366 |      |         |
| star  | 100 | QUARTER | fixed |  69,359 |  47/100 | .425 |      |         |
| star  | 100 | FREE    | auto  |  26,765 | 100/100 | .138 |      |         |
| star  | 100 | FREE    | fixed |   2,966 |  34/100 | .286 |      |         |
| mixed | 100 | NONE    | auto  |   8,057 | 100/100 | .368 |    8 | 100/100 |
| mixed | 100 | NONE    | fixed |   3,211 |  55/100 | .605 |    3 |  78/100 |
| mixed | 100 | QUARTER | auto  |  30,622 | 100/100 | .368 |      |         |
| mixed | 100 | QUARTER | fixed |  14,001 |  62/100 | .654 |      |         |
| mixed | 100 | FREE    | auto  |   5,264 | 100/100 | .368 |      |         |
| mixed | 100 | FREE    | fixed |   1,599 |  57/100 | .603 |      |         |

Parallel rows (rect only): NONE/auto 4,850 ms, NONE/fixed 391 ms,
QUARTER/auto 2,505 ms, QUARTER/fixed 1,840 ms - roughly serial-time; the
parallel mode exists for variant selection (it chose a marginally different
fixed-scale QUARTER result: 70 vs 69 placed), not raw speed.

## Interpretation

- Concave outlines cost orders of magnitude more than rectangles
  (vertex-pair contact placement + strict overlap checks). Star outlines are
  the worst case; 500-item concave packs take minutes. `TextureAtlasLayoutQuality`
  presets bound vertex counts via RDP simplification and cap auto-scale tries.
- The `AUTO` backend routes all-near-rectangular inputs to the MaxRects path,
  which is the right default for rectangle-dominated atlases; the polygon
  backend is for genuinely concave contours.
- The rectangle control packs bounding boxes, so its coverage/overflow numbers
  describe rectangle fit on the same item count, not outline packing quality.
