# Offline Texture Region and Directional Wiring Prototype

This is an algorithm prototype awaiting human review. It changes no host plugin behavior. The question under test: when nested regions are built from the texture first and points are then placed uniformly and merged by averaging, does a sparse constraint mesh with an explicit direction result?

## Run

From the repository root, prepare a Python environment and the Java products of this project:

```sh
python3 -m venv --system-site-packages /tmp/turbo-mesh-region-venv
/tmp/turbo-mesh-region-venv/bin/pip install -r plugins/turbo-mesh/prototypes/texture-regions/requirements.txt
./gradlew :sdk:jar :plugins:turbo-mesh:jar
```

Sparse preset, one command:

```sh
/tmp/turbo-mesh-region-venv/bin/python plugins/turbo-mesh/prototypes/texture-regions/prototype.py plugins/turbo-mesh/prototypes/texture-regions/sample.png --output /tmp/turbo-mesh-region-result
```

The finer preset adds `--level 2 --spacing 42 --tolerance 2 --min-spacing 6`.

## Flow and thresholds

1. The raw PNG alpha at 128 or above defines the silhouette. Before segmentation, the nearest opaque pixel fills the edge RGB, so a black transparent area cannot disturb the color-block segmentation.
2. A Felzenszwalb image graph segmentation yields the candidate connected regions, and adjacent regions merge by Lab mean color difference. A region below **0.6%** of the solid area merges into the closest-color neighbor first. The parent-child tree is built from fine to coarse and then read from coarse to fine. This is not a rectangular slicing.
3. `--contrast 6` controls the region merge, and the three merge levels use color-difference thresholds of 3.9 / 6 / 9. The default uses the coarsest `--level 1` instead of forcing every subdivision level into the mesh.
4. The outer silhouette and a shared boundary are extracted once. A light low-pass smooths the pixel staircase while keeping a multi-pixel corner, and `--tolerance` then simplifies by the stated pixel error. The default maximum sample spacing is 8.7% of the longer bounding-box side, the simplification tolerance is 0.375%, and the near-point candidate distance is 0.9%; `--spacing`, `--tolerance`, and `--min-spacing` override them. The evidence below passes explicit values to fix the output.
5. A PCA of the solid pixels estimates the aspect ratio. When the ratio exceeds 2.2 and the section width at both 10% ends stays below 72% of the maximum width, the long-axis section interpolation generates **3 arcs that connect to both ends**; a long hard surface uses parallel straight guides; a circle or ellipse uses **3 concentric rings** scaled around the geometric centre. A direction line is clipped to the solid interior first, so it never crosses an alpha hole. Every intersection with a texture line is noded first, and points then fill the region interior.
6. **After every point is placed**, vertices closer than the pixel distance of `--min-spacing` attempt a merge, and the position becomes the weighted arithmetic mean of the original vertices. A repeated merge weighs by the original point count of the cluster. A merge is refused when it would remove a region, break a ring, or add a crossing edge. That distance is a candidate distance, so it does not guarantee that every final point pair exceeds it.
7. Triangulation uses the existing `ConstraintDelaunay` of the repository. The Java bridge scales the pixel coordinates to the UV magnitude, so the fixed tolerance of that algorithm is not misapplied.

`regions.npz` and `hierarchy.json` hold the region hierarchy, `mesh.json` holds the local pixel-coordinate mesh and the original-image offset, and `constraint-roles.json` separates a texture line, the outer silhouette, and a direction line. `sites-before-merge.json` holds the pre-merge data.

## Current results and limits

See [NOTES.md](NOTES.md) and the real offline products in `evidence/`.

This is not a texture reconstruction or a deformation quality check. The region labels come from automatic segmentation and carry no hand-drawn ground truth, and a color-difference threshold may still merge a valid weak shadow or keep a gradient false boundary. The silhouette simplification and the mean move allow a small pixel deviation, so the metrics also report the IoU against the original alpha; a self-consistent triangle mesh alone cannot prove the texture accuracy.

The shape direction model currently verifies a single circle, an ellipse, a spindle silhouette, and a USB hard surface with two alpha holes. A complex fork, several islands, and a strongly concave silhouette still need further work. The PCA classification and the three direction lines are presets of this visual verification only. The CDT still decides the radial connection of a ring and the lateral connection between arcs, so a tidy structured topology is not guaranteed, and no host deformation test exists.

The prototype stays in the prototype directory until the visual result is accepted; after that it is either absorbed into the Java production algorithm or deleted. Do not wire it into the host automatically.

## USB hard-surface sample

```sh
# Sparse: area threshold 1.5%, color-difference base 8
/tmp/turbo-mesh-region-venv/bin/python plugins/turbo-mesh/prototypes/texture-regions/prototype.py plugins/turbo-mesh/prototypes/texture-regions/sample-usb.png --output /tmp/turbo-mesh-region-usb-sparse --min-region-ratio .015 --contrast 8 --spacing 32 --tolerance 1.2 --min-spacing 4
# Finer: area threshold 1%, other distances scale from the shape size
/tmp/turbo-mesh-region-venv/bin/python plugins/turbo-mesh/prototypes/texture-regions/prototype.py plugins/turbo-mesh/prototypes/texture-regions/sample-usb.png --output /tmp/turbo-mesh-region-usb --min-region-ratio .01
```

A constraint edge may consist of a chain of collinear edges, and the check measures its covered length with a tolerance of 1e-4 original-image pixels. The topology area check uses a tolerance of 0.001 square pixels, because an SDK vertex is float32. The real missing area stays in the metrics unchanged and is never rounded away.
