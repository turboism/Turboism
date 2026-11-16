# Offline Reference for the Authorized Apple Model

These scripts only prepare and analyse test material. They start no Cubism and do not mean that Apple import is supported. The original `~/Downloads/3D-apple.rar` stays unchanged.

- `convert-apple-obj.py` accepts only a hash-verified high-density OBJ, keeps both source groups, the geometry, and the UVs, always triangulates quads along the 0–2 diagonal, and produces a material-free GLB plus a provenance manifest. It explicitly omits a zero third texture coordinate and reports float32 error separately. It performs no simplification, recentring, unit guessing, or screenshot texturing.
- `apple-occlusion-reference.py` runs a double-sided orthographic depth rasterization over the original 150,784 triangles and records the nearest depth of each component separately. The auto-fitted reference camera parameters go into the report; the label grey is not a material. Native rendering and interpolation still need separate verification.
- `verify-apple-occlusion-rays.py` re-checks the depth samples with an independent object-space Möller–Trumbore ray intersection, so the occlusion relation is not proven by the same rasterizer alone.

It needs Python, NumPy, and Pillow. Every output uses a new directory so old evidence is never overwritten.

At the same pose of yaw=-30 and pitch=-30, component 0 and component 1 each hold overlapping pixels in front of the other. The independent ray check agrees, which shows that visibility between the two complete components cannot be expressed by one fixed order. Later work needs chunking plus self-occlusion inside a chunk, and raising the GLB index limit alone is not enough.

`apple-fbx.py` reads the fixed-hash FBX 7400; `verify-apple-subdivision.py` verifies the Catmull–Clark subdivision positions and the quad connectivity of a closed mesh. Per-component coordinate translation is recorded explicitly, and the position match threshold is 0.001 source units; it verifies no UV, winding, material, or native import. Run:

```sh
python3 -B validation/model-import-3d-assets/verify-apple-subdivision.py --source build/model-import-3d-inputs/apple-20261001/apple --out build/model-import-3d-offline/apple-subdivision-new
```

The stem at level 3 and the body at level 5 reproduce the source OBJ positions and connectivity; a lower level still needs its own error measurement and cannot be used for acceptance directly.

`measure-apple-levels.py --cages <subdivision-output> --reference <original-depth-output> --out <new-directory>` measures the outline, component ownership, and depth difference of four subdivision levels. It uses a fixed reference camera and three poses and masks no boundary; the depth difference is not a vertex projection error, and the result is no native acceptance. A complete rebuild may still choose different quad diagonals, so matching vertices and quad connectivity alone cannot assert an identical triangle surface.

`prepare-apple-bsp-input.py` extracts every source triangle and UV of the fixed-hash high-precision GLB exactly into offline BSP probe input (BSP1, little endian, two meshes with their own triangle counts, XYZUV float32 per corner). The matching `selfcheck/AppleBspProbe.java` calls only the chunking algorithm and no native entry point. It outputs the input hash and the face count manifest; a budget refusal must never be read as an import pass.

`measure-apple-self-occlusion.py` records the front nearest and farthest depth of the original model at three fixed reference poses and distinguishes multi-layer coverage beyond 1e-5 source units. `verify-apple-front-layers.py` verifies the witnessed two-layer depths and the front hit with an independent object-space ray; it is not native verification.

After the user authorized a moderate limit increase on 2026-10-02, the complete Apple BSP computation finished but produced about 300,000 fragments. `AppleBspProbe` accepts an explicit computation budget and component scope; a component test must record its scope and never pass as the whole Apple. The complete result and its resource cost are in `build/model-import-3d-offline/apple-expanded-budget-20261002/`.

`measure-apple-fixed-order.py` measures one fixed triangle order (by default descending source centroid Z; `--order` accepts a little-endian int32 source triangle permutation written by Java), and its software painter writes only by coverage and order while verifying source depth and component ownership. That candidate order is no global proof. `apple-occlusion-reference.py --grid25` produces 25 fixed joint-pose references, and the default stays the original three poses; the old reference arrays regress equal value by value.

`prepare-apple-control-charts.py` assigns every original triangle corner to one of 98 subdivision root quads and keeps the chart coordinates separate from the original texture UVs; it verifies the position, the UV, and the 0–2 diagonal of the original GLB exactly and simplifies nothing. The initial 3-cell control mesh was only a capacity estimate, not a usable surface fit.

`OfficialWarpKernelProbe.java` calls only the Warp math singleton inside the fixed-SHA256 official 5.3 JAR and opens no editor; identity, affine, boundary, and control-point impulse checks show that `quadTransform=true` is bilinear and `false` is triangle-linear. Never assume cubic interpolation at runtime because the editing mesh contains Bezier classes, and never record that offline call as host, Core, or export acceptance.

`measure-apple-warp-fit.py` fits the original positions onto a bilinear control mesh in the chart; it pins the boundary of an aligned source sample to the same source vertex, checks full edge continuity, and verifies a seam by original vertex identity instead of welding equal positions or UVs. All source triangles and UVs stay as reference. It uses the AABB midpoint and the farthest real vertex radius (400 px) of the production `SurfaceAutoCamera`, measures the object-space fit error, and computes the joint parameter linear interpolation error separately. The official math kernel replay covers 25 fixed poses and 25 poses with unbound points. The parameter interpolation is an offline ideal bilinear computation and is not yet the native parameter evaluator.

```sh
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 python3 -B validation/model-import-3d-assets/measure-apple-warp-fit.py \
  --charts build/model-import-3d-offline/apple-control-charts-20261002 \
  --out build/model-import-3d-offline/apple-warp-fit-new \
  --kernel-classes build/model-import-3d-offline/apple-warp-kernel-20261002/classes \
  --lib '<proton-prefix>/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib' \
  --replay-cells 16 --replay-key-count 17
```

Measurement with the correct menu camera on 2026-10-02: a 16-cell chart, a 238×119 Warp, and a 17×17 parameter grid need 8,288,520 control samples, at least 66,308,160 bytes of pure XY float. The global object-space error bound is 0.2143 px and the ideal parameter error bound is 0.4284 px, 0.6426 px combined (float and native evaluation excluded); the official math kernel replay over 50 poses shows a maximum source projection deviation of 0.4478 px, and the kernel differs from the ideal object-space basis function by less than 0.000151 px. The older r2–r5 runs used a larger AABB corner-sphere framing and stay as exploration records; their smaller errors must not be mixed with the menu result.

The evidence is `apple-warp-fit-r7-dense-20261002/report.json`; the current hard cap on total samples was raised moderately to 10,000,000 with at most 80 MB of pure coordinate storage, and other objects, history, and textures are counted separately. Apple still does not enter production native import: explicit Warp form target writing, native index order and occlusion, save and reopen, and official export verification are all missing. `selfcheck/AppleKeyDensityProbe.java` compares the memory need of binding the original mesh directly without allocating a large form or writing to the host; a 17×17 grid still needs about 227 MB of pure coordinates and exceeds the single-batch write limit.

`prepare-apple-warp-fixture.py` refuses an over-limit request before capacity allocation and packs the original fixed-order triangles into meshes of at most 65535 vertices by `(source component, GLB slot, chart)` identity, verifying the original XYZ, UV, and source position and UV indices per triangle and recording the derived Warp local input coordinates separately; it produces 289 control forms. It only prepares offline native authoring input, modified no source file, created no Cubism project, and is no production admission.
