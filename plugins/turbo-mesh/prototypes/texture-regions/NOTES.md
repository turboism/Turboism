# Offline Verification Record (2026-10-25)

Two user PNGs serve as independent samples, and circle and ellipse direction-line checks are added. No host was started and no production algorithm was changed.

| Sample | Texture regions | Before → after mean merge | Triangles | Alpha IoU |
|---|---:|---:|---:|---:|
| hair-sparse | 5 | 160 → 139 | 241 | 98.86% |
| hair-detailed | 10 | 266 → 204 | 357 | 99.08% |
| usb-sparse | 10 | 185 → 134 | 213 | 97.81% |
| usb-detailed | 14 | 258 → 221 | 374 | 98.80% |
| circle | 1 | 130 → 129 | 224 | 99.53% |
| ellipse | 1 | 112 → 109 | 184 | 99.38% |

## Confirmed

- A subdivision label belongs strictly to a coarse label. Adjacent regions share a graph edge, and the unified graph holds no boundary that was duplicated and then separated.
- The mean merge runs after every constraint and interior sample exists, weighs by the original vertex mass, and refuses a merge that breaks the region face count or adds a crossing edge.
- A hair strand uses 3 longitudinal connection arcs. The USB ends do not meet the spindle narrowing condition, so it uses 3 parallel guide lines cut into 5 segments and keeps the 2 alpha holes. The circle and the ellipse each keep 3 concentric rings.
- For the listed results, every constraint edge or collinear edge-chain coverage check passes, no triangle crosses a vector region, and no area overlaps. The float area residual of the USB stays below 0.001 square pixels and is kept in the JSON.

## Not confirmed

- Automatic segmentation still ignores a weak shadow or keeps a gradient false boundary. Only two real images exist, so this is no general conclusion.
- The sparse USB sample reaches only 97.81% alpha IoU against the original, and the finer sample reaches 98.80%; the silhouette and the near-point merge still need visual feedback. Fewer points do not mean better deformation quality.
- The CDT still decides the connection between an arc and a ring. A tidy quad band, a controlled radial connection, a deformation test, and host wiring are not implemented.
- This is a reproducible prototype awaiting the user's review of the graphic result, which then decides whether it joins the Java production code or is deleted.

`metrics.json` records the input hash and every parameter of each run. `constraints.txt` and `triangles.txt` are the input and output of the Java bridge, and a mesh coordinate uses crop_origin as the original-image offset. The circle and the ellipse are synthetic samples; the hair strand and the USB are original user PNGs, not host screenshots.
