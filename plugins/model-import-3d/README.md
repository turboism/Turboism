---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.model-import-3d
version: 0.68.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: 3d, glb, gltf, obj, import
turboismApi: "[0.70.0,)"
requiresCubism: true
interface: embedded
---

# 3D Model Import

> **Official Turboism plugin** · **Status: Preview**

Imports supported GLB, glTF, OBJ and model ZIP packages as native Cubism meshes with texture normalization and rotation deformers.

| Detail | Value |
|---|---|
| Version | `0.68.0` |
| Plugin ID | `dev.turboism.plugin.model-import-3d` |
| Category | `workflow` |
| Tags | 3d, glb, gltf, obj, import |
| Requires Cubism | Yes |
| Interface | `embedded` |
| License | Project License |

## What it does

Imports supported GLB, glTF, OBJ and model ZIP packages as native Cubism meshes. Input parsing, texture normalization, geometry simplification and parameter authoring run locally without an LLM. Source-material mode binds X/Y rotations to dense joint Warp forms and Z rotation to native scalar forms, each spanning −180° to 180°. An unbound outer deformer, the placement control the import creates, moves and scales the entire model. Source textures stay attached to their faces. One native Undo operation removes the import. The Warp path uses dense parameter sampling. The direct source-vertex fallback uses elliptical interpolation; it preserves the 9×9 ordinary pose bank and chooses insertion density per patch from its projected rotation radius. The preparation stage minimizes derived lattice size within the existing global interpolation bound, keeping simplification plus interpolation inside the 5px geometry budget. This is geometry evidence; intermediate occlusion and Editor drag latency require native acceptance.

The import confirmation includes a small 3D preview: drag to rotate, scroll to zoom, or reset the view. Preview controls do not change the imported model. After import, the parameter tree and project palette refresh automatically.

## Import a preset

Open a model project, then choose **Plugins → 3D Models → Import preset…**. Choose a cube, regular tetrahedron (triangular pyramid), triangle, cylinder, sphere, square, filled circle, flat annulus, short double-sided ring wall or solid donut and confirm. Each preset uses the native source-image import path, supports XYZ ±180° rotation and a single Undo, and needs no external file.

The ten self-contained GLBs total about 48 KiB. Each has one shared 256×256 UV grid image that can be replaced after import. Keep its dimensions and UV layout, and update the texture atlas as needed. Cube faces occupy a 3×2 grid (−X, +X, −Y, +Y, −Z, +Z from low to high V). The cylinder side uses V=0…0.5; the lower/upper caps occupy the left/right halves of V=0.5…1. The sphere uses longitude/latitude UVs, the square and flat ring use planar UVs, and the donut uses major/tube circumferences. The tetrahedron has four equilateral faces, each in its own tile of a 2×2 UV grid. The triangle is equilateral with planar UVs. The square, triangle, filled circle and flat annulus are double-sided. The short ring wall has radius 1 and height 0.3, with both inner and outer faces visible and sharing the same texture; U follows its circumference and V its height. The flat ring has inner/outer radii 0.5/1; the donut has major/tube radii 1/0.3.

Rebuild these original assets with `python3 scripts/generate_model_import_3d_presets.py` (standard library only).

## Source controls and parts

The source-aware native path runs locally without an LLM or a character-specific preprocessing script. It reads standard glTF position Morph Targets and preserves the saved node or mesh weights as the zero state. Each effective shape becomes an independent Cubism Blend Shape parameter. Source target names are preserved when available. The generic endpoint interval is 0..1 extended to include the saved value; glTF does not carry Blender slider limits.

For skinned geometry, each triangle is grouped by its strongest summed source joint influence. The group label uses the actual source bone name and node index. This creates shared Warp parents across materials without guessing anatomical meaning. Explicit part metadata takes precedence. Unskinned models use source-object groups. This grouping does not import arbitrary skeletal posing or inverse kinematics.

Eye, head and clothing controls are optional mappings. They are not required for automatic shape or part recognition. Source shader networks and Blender modifiers that were lost during export cannot be reconstructed from an ordinary GLB. A generated character-specific repair fixture is validation input, not a production importer dependency.

Local Warp fitting currently measures a 2px residual at the neutral view. This does not establish the same accuracy at side views or for combined controls. Unsupported fits and storage limits fail explicitly. Source geometry remains intact. Cubism 5.3.04 passed the two-joint, two-shape fixture through the production menu, native pixel changes, Undo/Redo, save and fresh-process reopen. Both authoring-bank and morph digests match after reopening. This evidence does not establish arbitrary asset or all-version visual fidelity.

The native source-control planner allows up to 256 MiB of combined geometry-keyform and Warp-control coordinate payload (two floats, eight bytes per point). This is not a bound on JVM heap or saved CMO3 size. Geometry alone remains bounded to six million keyform slots; interpolation cache remains bounded to 64 million slots. Decoded source controls retain their separate 16-million-slot limit. Budget errors include the measured geometry and control counts. Failed patches are refined together per pass to avoid rebuilding the scene order once per failed patch. The 2px neutral fitting threshold is unchanged.

A fixed 2D Warp is currently fitted at the neutral view. It does not rotate source depth displacements with whole-body yaw or pitch. A diagnostic triangle with a 0.2-unit depth displacement has zero neutral residual and a 28.284px projection difference at yaw or pitch ±30° under the fixed 400px-radius camera. This is a measured counterexample, not acceptance of side-view shape accuracy. Angle-conditioned corrections remain required.

## Full-circle source geometry

The source-aware native path retains XYZ ranges −180°…180°. It splits connected
surfaces into bounded supporting-plane patches, preserves source single/double-sided
semantics, and compiles sampled screen-overlap constraints into integer draw-order
keys. The compiler samples the entire angular domain every 15°, with denser 3.75°
front-view calibration inside ±30°. This is a deterministic local process and does
not require an LLM, Blender or a named character. Every source triangle is retained;
unrepresentable visibility or exceeded budgets fail explicitly.

The public numbered-texture corpus includes a cube, octahedron, cross-star, torus,
cylinder and ring/column assembly. With and without source morphs, its 996 source-projection depth screens pass.
Native Editor validation and pixel/filtering limits are recorded separately in the
[primitive acceptance report](../../validation/native-character-host-probe/performance/PrimitiveAcceptance.md).
Finite keyforms approximate continuous rotation; this does not promise exact 3D
occlusion for arbitrary intersecting surfaces or all shape-control combinations.

## Requirements and compatibility

The public primitive corpus is exercised on Cubism Editor [0.70.0,)4 with SDK API range `[0.70.0,)`. Open a model with a 1024×1024 canvas before importing. The menu chooses one source-derived camera and pivot to fit the model inside a 400px radius. The camera stays fixed throughout rotation. Both source-material and distinct-face-color modes use XYZ ranges of −180° to 180°. Explicit probe requests retain their declared camera.

The bounded surface path limits GLB files to 16 MiB. The source-aware native path accepts GLB files up to 128 MiB. The bounded importer supports opaque closed convex surfaces or a single convex quadrilateral plane. A restricted branch for two disjoint, double-sided parallel X-plane rectangles is implemented but has not completed native validation. A separate regular quadrilateral surface branch preserves the original triangle geometry and UVs in chart meshes under one native Warp deformer. The XYZ apple has real menu/save/reopen and source-geometry evidence; strict occlusion and official export of this full-circle apple remain unverified. Sparse chart depth ordering can produce occlusion errors. Arbitrary concave topology and intersecting sheets are not generally supported. OBJ is now a direct menu input for self-contained geometry. Select referenced buffers, MTL and PNG/JPEG resources when prompted, or package them together with exactly one GLB, glTF or OBJ in a ZIP. FBX, DAE and RAR are not direct menu inputs. Earlier apple evidence used an OBJ converted to GLB; it does not verify the newer OBJ menu path.

The generated exact-version declaration below covers reviewed SDK availability on
20 Editor versions, 5.0.00–5.3.04. This change adds no host reflection or new SDK
entrypoint. The current primitive real-host results are for 5.3.04; the same visual
corpus has not been rerun on every admitted version.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.3.00–5.3.04.
- Part opacity writes only on 5.3.00–5.3.04（[Cubism 5.3.00 added Part offscreen drawing (part opacity and clipping masks)](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/)）
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

Bundled with the official Turboism distribution; select **3D Model Import** in the installer or enable it in **Plugin Management**. For local builds, run `./gradlew :plugins:model-import-3d:jar --offline`. The JAR contains the SDK-only importer and excludes HostProbe, Turboism runtime and official Editor classes. Host validation uses isolated queued sessions. Unit tests that exercise large local 3D model fixtures (`artifacts/` and `build/model-import-3d-inputs/`) skip via JUnit `Assumptions` on clean checkouts; stage or symlink the directories (or set `TURBOISM_IMPORT_3D_ARTIFACTS` / `TURBOISM_IMPORT_3D_INPUTS`) to run them, and pass `-Pturboism.requireImport3dFixtures=true` (or `TURBOISM_REQUIRE_IMPORT_3D_FIXTURES=true`) to fail if any fixture is missing.

## How to use

Menu: **Turboism → Plugins → 3D Models → Import 3D Model…**. Choose "Keep original materials", then choose a GLB, glTF, OBJ, or model ZIP. When a standalone model lacks an external resource, select the material, buffer, or PNG/JPEG texture the reference prompt names, then confirm the mesh and parameter-binding point counts before importing. Drag the new **X / Y / Z rotation** parameters to rotate, and the three axes adjust together. Choose the **Overall placement** deformer to move or scale the whole model; moving one child mesh alone changes its sample position inside the projection Warp. An older project gains no placement layer automatically. After you switch the target document or disable the plugin, an old request writes nothing further.

The optional face-color mode is separate from retaining source materials. For the textured cube, use source materials so colors, axis labels and arrows come from the GLB itself. The default zero-angle pose looks square because it faces the red front surface.

The workspace sample and ready-to-open native CMO3 are documented in [the textured cube artifacts](../../artifacts/cube-textured/README.md).

## Capabilities

No capabilities are declared in the plugin manifest.

The importer uses SDK model authoring, native parameter binding, file selection and a single authoring transaction. Parsing and preparation run in the background; model writes happen after confirmation.

## Permissions

- `turboism.action.register`: register the import action.
- `turboism.ui.menu.contribute`: add the import menu entry.
- `turboism.ui.file-chooser.request`: ask the user to select a supported model or ZIP package.
- `turboism.file.read`: read that selected file.
- `turboism.cubism.model.read`: inspect the target model and parameters.
- `turboism.cubism.model.write`: create meshes and parameter forms in one undoable transaction.

## Privacy and data

Input is read locally through SDK file authorization. The plugin does not upload models. Diagnostics report import failures to the local Turboism logger. Importing does not overwrite the selected model or package. Native project saving remains an Editor operation.

## Status and limitations

Source base color can be solid or an embedded opaque, power-of-two, 8-bit RGB/RGBA PNG. Decoded source textures together must fit the existing 4 MiB RGBA budget. The source Warp path preserves original image dimensions and texels; it adds edge padding only when that fits the same image budget, otherwise it uses the complete original clamp-sampled image. The legacy simple-patch path still requires 256×128 textures. Source UVs are retained and the base-color factor is multiplied in linear space. Sampling must explicitly use CLAMP_TO_EDGE and LINEAR. Repeat/mirror wrapping, mipmaps, external images, transparency and color profiles are rejected before native object creation. Images are not silently resized or flipped. The current input normalizer accepts opaque PNG/JPEG package textures and converts them to plain PNG without resizing. The downstream geometry/material restrictions still apply. Cat native import, compact saving, exact source-bank reconstruction and isolated whole-mesh translation/Undo have passed; intermediate visual occlusion and smooth drag performance remain unaccepted.

Planes must contain one primitive and two consistently wound, strictly coplanar triangles forming four convex boundary corners. Four to six source vertices allow UV seams without merging them. Concave, overlapping or noncoplanar quadrilaterals are rejected.

The frozen textured-cube build has real menu/file-selection, Undo/Redo, save/fresh-process reopen, atlas repack and official export evidence. Standalone official Core and Viewer also evaluated/displayed the exported cube without Turboism. Small source-filter sampling deviations are retained under the user's acceptance; exact save/reopen comparisons remain strict. These findings apply to recorded inputs/builds, not arbitrary GLBs. Packed-atlas save and fresh-process reopen also preserve all 12,150 native forms and nine identical pose rasters. Current full repository checks are still being completed. Plane and two-sheet native validation remain incomplete.

Plugin metadata names and descriptions have five language catalogs; the import dialogs currently use Chinese. Later packaging changes do not retroactively change frozen host evidence. See [acceptance status](../../specs/040-model-import-3d/acceptance-status.md) for exact scopes and remaining work.

## Troubleshooting

If the menu is missing, confirm that this development JAR is installed and enabled. If import is rejected, check the canvas size, file size and supported geometry/material limits above. Open the intended target model and start again after switching documents. At 0 / 0, adjust both angle parameters to see the side faces. File-picker cancellation makes no model changes.

## Changelog

### 0.68.0

- Align README metadata with the plugin descriptor.

### 0.1.0

- Initial release.

## Support and license

Report the input profile, host version and local diagnostic message to the Turboism project. License: Project License. This development plugin is not a claim of compatibility with adjacent Cubism versions.

### 2026-10-02 Input and shape budget adjustment

The authorized GLB total index limit is 600,000, with 100,000 vertices and a 16 MiB container. The native projection sample limit is 4,000,000, the shape limit is 131,072, and the preparation stage checks the minimum coordinate storage. A larger read budget does not prove concave or complex occlusion support, which still needs its own evidence. The apple passed the real menu import, the save, and the fresh-process reopen, and it ships as a native project with the original geometry and UVs fully preserved.

### Fixed framing of the user entry

The menu import fits one camera and pivot around a fixed 400px bounding-sphere radius from the source geometry it read, so a different model size or origin still fits the 1024×1024 canvas, and the camera stays fixed during rotation. A direct probe call keeps its explicit camera. The apple uses 98 source surface patches with 17×17 joint keyform geometry control. To avoid an official serialization capacity overflow, a large model repeats the layer-order shapes at 9×9 sampling by storage budget, and the geometry control density stays unchanged.

### Apple project and runtime export

The [rotatable apple project](../../artifacts/apple-native/rotatable-r1-20261002/README.md) opens directly in the Editor, and the [runtime model](../../artifacts/apple-native/runtime-r1-20261002/README.md) opens in the official Viewer without Turboism at run time. The menu, single-step undo and redo, failure rollback, native save, fresh-process reopen, official atlas and MOC export, and 11 complete poses of the standalone official Core are verified. The official Viewer really displayed the model and responded to mouse input on the two rotation parameters; an exact Viewer angle and per-pixel comparison is not yet accepted.

The apple source file carried no material. The red skin and brown stem come from a texture made for the source 3D model; they are neither a restored original material nor a color change inside Cubism. The original face count is 150,784 with no reduction. The CMO3 is about 96.9 MiB and the MOC about 127.5 MiB, while the official SDK MOC budget is 128 MiB. Importing and saving a larger project is slower.

The source-depth diagnosis of 353 poses covers the layer-order key points, every cell centre and edge midpoint, and 64 fixed random points. Three poses currently show a layer-order residual of 3, 3, and 7 pixels that no boundary mask hides; this is still a source-geometry diagnosis and cannot replace a real native evaluation or render acceptance. The standalone official Core verified all 35,391,074 native vertex samples of the same 353 poses with a maximum projection error of 0.436px and preserved UVs and topology. The key-point integer layer order is exact and the intermediate states stay inside the floor and ceil quantization; the source depth is still a source-geometry diagnosis and cannot be called an all-domain native pixel pass. The main user session did not deploy this development build.

The apple atlas repack project ships in `../../artifacts/apple-native/packed-r1-20261002/`. It keeps every source face, coordinate, and UV identity, and the real atlas position repack, the single-history undo and redo, the typed SAVE_AS, and the fresh-process reopen pass with 11 joint pose PNGs byte-identical; the complete receipt is in the directory provenance. That conclusion supports persistence agreement only inside the declared ±30° apple domain, and it does not upgrade the continuous pixel, occlusion, or other input acceptance.

The real menu rejected the intersection and loop counterexamples: `menu-reject-native-r1-20261002/strict-rejection-acceptance` covers two frozen GLBs and both color modes and refuses all 4 cases. The authoring state, the image, atlas, and parameter state, and the complete history revision, entries, and redo stay unchanged, and the job exits normally. This is a structural-subset refusal; general splitting support for intersecting or looped surfaces is not implemented.

### Cat edit performance (2026-10-04)

The Cat automatic simplification and adaptive interpolation output keeps 512 meshes, 35,397 vertex slots, and 48,864 triangles, with 481,632 ordinary and derived shapes in total. The combined geometry and interpolation bound stays 5px and excludes the occlusion approximation. The local edit cost of the old structure was located: the Editor forces a refresh of the sibling meshes under the same Part. A direct vertex import now creates one child Part per surface to limit the refresh scope.

The Cat file with independent Parts passed the official save, the fresh-process rebuild, the preserved original texture content, and a real three-step drag of the first mesh with a complete undo. A pure official event takes about 47–57ms, and the coefficient candidate takes about 41ms on the first step and about 15ms on the next two; no row cache was used. This evidence does not cover continuous, multi-select, or all-mesh edit performance, and the load cost still needs work. The real menu import, the save, and the official fresh-process read of the new product pass; the new output is in `artifacts/cat-native/parent-scoped-import-r1-20261004`. Loading, continuous editing, and the final export remain unfinished, and the main Editor did not deploy it.

The current development candidate mechanically compares three geometry and interpolation budget combinations and chooses the one with the lowest estimated coordinate and shape base storage inside a 5px total deviation and a 0.5texel UV deviation. The Cat plan holds 51,790 faces and 417,800 ordinary and derived shapes; it keeps more faces than the previous version and stores fewer shapes. The 146 plugin regressions and the independent geometry sampling pass, and the new plan has not completed native performance and visual acceptance and is not deployed to the main Editor. Evidence: `build/model-import-3d-offline/cat-cost-plan-candidate-r1-20261004`.

### Cat default interpolation candidate (2026-10-04)

A 20-step continuous drag and undo of the largest mesh passed: the median fell from 72.2ms to 10.4ms, the event allocation dropped about 89%, and no row cache was used. The actual cost-plan product passed the save, the official fresh-process rebuild, and the original image pixel agreement. The complete setup interpolation comparison is exactly equal in shape and structure hashes, the single setup allocation dropped 33.7%, and the elapsed time dropped 46.2%, while the heap after collection stays near 520MB. The new runtime candidate now admits by default the interpolation and coefficient path that passed the host and class hash check, and it keeps the safe mode, the explicit opt-out, and the official fallback; the 12 focused regressions and the license check pass. The real default-startup menu import and save are still under verification, and the main Editor did not deploy it. The load transient allocation stays high, and multi-select, in-mesh editing, occlusion and seams, export, and multi-format native acceptance remain unfinished.

2026-10-04: the formal default interpolation admission passed the real menu import and save acceptance in job `dc146af6-32a2-49ad-aa9d-334d73e83845`, which the official side reported succeeded with BAT/wrapper 0 and a normal exit without a forced kill. No experimental interpolation switch was passed, and the startup log confirms the exact hash-admitted 5.3.02 interpolation patch. The product is 28535149 bytes with SHA `30dcdc6e75bcb2af9efef461a89297478fa2813c1719d575600c36a6ff4a41fa`. A proxy-free official fresh process fully rebuilt 417800 shapes; the complete coordinate, opacity, and draw-order bank `d67c333b5c1874e24e393c2755181f41dc52573fc56ac5fe8f40b02e4cb34734` is exactly identical to the earlier official baseline, and the 512 independent parent Parts, 37202 slots, 51790 triangles, and exact sampling binding pass. All 521 archive entries and 520 PNGs are readable, and the original texture agrees in all 1048576 pixels. The new output is `artifacts/cat-native/default-interpolation-import-r1-20261004` and the complete evidence is `build/model-import-3d-offline/cat-default-import-native-r1-20261004`. The main Editor did not deploy it; native UI load performance, the resident shape storage reduction, in-mesh editing, multi-select, render occlusion and seams, export, and multi-format host acceptance are unfinished, and the goal stays active.

### External resource input candidate (2026-10-04)

A referenced resource is read through an independent SDK file-selection grant, so no manual GLB conversion or earlier ZIP build is needed. The normalized output of separately selecting the original Cat OBJ, MTL, and JPEG is byte-identical to the original ZIP; the 12 association, lifetime, and budget tests and the 158 complete regressions pass. The input and external files total 16MiB with at most 64 resources, and a ZIP must still be complete; only base-color references are handled, and no bump or light map is read. The new candidate is still under real menu acceptance and the main Editor did not deploy it.

2026-10-04: the real OBJ plus separate MTL and JPEG input passed native acceptance in job `753518ad-0f27-4a61-b909-ac3898d2b0b5`, reported succeeded with BAT/wrapper 0 and a normal exit without a forced kill. The real chooser selected the original OBJ and then `12221_Cat_v1_l3.mtl` and `Cat_diffuse.jpg` by reference, and every original file hash matches. The native save is 28535839 bytes with SHA `1c261adeebefc55930d9f3a3421173a68974fc3cd37bec87a8a4b71877e82883`. A proxy-free official fresh process rebuilt all 417800 shapes; the bank `d67c333b5c1874e24e393c2755181f41dc52573fc56ac5fe8f40b02e4cb34734` is exactly identical to the ZIP import baseline, and the exact sampling binding, the 512 independent parent Parts, the 37202 slots, and the 51790 triangles pass. All 521 entries and 520 PNGs are readable, and the original texture agrees in all 1048576 pixels. The output is `artifacts/cat-native/separate-files-import-r1-20261004` and the evidence is `build/model-import-3d-offline/cat-external-import-native-r1-20261004`. GLB and glTF external resources have only the current test-layer evidence, and the OBJ host result is not widened into a host pass of another format. The main Editor did not deploy it; the goal is active, and in-mesh editing, occlusion and seams, multi-select, export, and the real native path of the other formats remain unfinished.

### Native texture atlas auto-planning and append (2026-10-09)

The 3D model import now plans and creates a native Live2D texture atlas from the actual texture size and count of the loaded model, instead of only registering the original model images:

- **Adaptive deterministic power-of-two size and page count**: the layout is deterministic from the real model resolution at 1:1, so a small image is not scaled up without cause and a large image is not scaled down, and it uses 256×256, 512×512, 1024×1024, 2048×2048, or 4096×4096. The choice weighs memory use against page count, so it never creates a huge blank page to reach a single page. When one page cannot hold the content, the split into several pages is deterministic.
- **Texture deduplication and margin protection**: a source texture shared by several materials or meshes is laid out once, so a shared original image counts once and saves atlas memory. The edge margin defaults to 2px and the item padding defaults to 2px, which prevents bilinear edge bleed.
- **Preserved existing model atlas**: when the import target already holds an older atlas, the existing atlas and pages stay completely untouched and heterogeneous, and the import appends only the new pages it creates for the 3D model. It never overwrites or repacks an old page, so the old mesh UVs and atlas stay unchanged.
- **Single undo transaction atomicity**: the atlas append, the mesh UV binding, and the parameter and mesh construction all join the existing single authoring transaction. A user cancel or a failure at any step rolls the whole operation back atomically and leaves no half-built parameter, mesh, atlas, or dangling page.
- **Atlas specification in the confirmation dialog**: the import confirmation window states the real atlas count and each size, and the six language catalogs are synchronised.
- **Source texture and environment limits**: the texture format supports opaque PNG and JPEG base color, and transparency and bump are not supported yet. One texture is limited to 1,048,576 decoded pixels or the 4096 host atlas limit, and the model canvas must be 1024×1024. When the environment cannot append an atlas, a localized message stops the import before any model change.

Canvas fitting: the import no longer requires a 1024×1024 project canvas. The projection and occlusion compilation keeps its internal 1024 coordinate workspace and fits the model to the shorter canvas side through a new outermost placement deformer, centred and scaled proportionally, and it never moves a previously imported model. The display-space interpolation error changes with that outer scale. The project canvas size and the texture atlas resolution are independent. A canvas change still uses the native host canvas command and undo, and the import returns success only after the size reads back consistently.

The **Overall placement** rotation deformer of a new import uses the model projection centre as its pivot, which coincides with the canvas centre of the Z rotation. After a parent change, the default form and every non-default keyform are converted and read back separately, and the existing canvas pose is preserved. The placement name, the X/Y/Z parameters and rotation deformers, the surface deformation, and the local action names are generated in the import language (Simplified Chinese, Traditional Chinese, English, Japanese, and Korean); the parameter IDs, the file-name parameter group, the source bone names, and the shape key names stay unchanged. An older object in a saved project is not migrated automatically.
