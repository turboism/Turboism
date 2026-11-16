# 3D Model Import Test Assets (`model-import-3d`)

This directory holds the user-authorized, self-built minimal GLB geometry fixtures for `:plugins:model-import-3d:test`. They let contract tests that depend on these minimal models run for real in a clean checkout that has no local `artifacts/` or `build/model-import-3d-inputs/`.

Third-party models (such as `cat-textured` and `apple-textured`) and every `.cmo3`, `.can3`, `.psd`, or private project asset stay **out of the repository**. Those tests still resolve their inputs through `ModelImportTestFixtures` from local paths and are gated by JUnit `Assumptions`.

## Directory Structure

```text
testing/assets/model-import-3d/
├── README.md
├── cube-985496727e50/
│   └── cube.glb
├── cube-textured/
│   └── cube-textured.glb
└── plane-textured/
    ├── plane.glb
    └── plane-seam.glb
```

## File Manifest and SHA-256

| Repository path | Original resolution path | Bytes | SHA-256 |
|---|---|---:|---|
| `testing/assets/model-import-3d/cube-985496727e50/cube.glb` | `build/model-import-3d-inputs/cube-985496727e50/cube.glb` | 1936 | `985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630` |
| `testing/assets/model-import-3d/cube-textured/cube-textured.glb` | `artifacts/cube-textured/cube-textured.glb` | 5672 | `dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859` |
| `testing/assets/model-import-3d/plane-textured/plane.glb` | `artifacts/plane-textured/plane.glb` | 2868 | `8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997` |
| `testing/assets/model-import-3d/plane-textured/plane-seam.glb` | `artifacts/plane-textured/plane-seam.glb` | 3068 | `89d1617c9555250d504136c081abec11b2f3e00fde45237d633bdb2096585f34` |

## Provenance and Geometry/UV Metadata (Manifest Summary)

### 1. `cube-985496727e50/cube.glb`

- **Provenance**: a self-built minimal cube that the user supplied explicitly and authorized for acceptance testing (from local `~/Downloads/cube.glb`, exported with Khronos glTF Blender I/O v3.6.28).
- **Geometry and attributes**: one mesh named `Cube` spanning `[-1, 1]^3`, with 24 `POSITION` (`VEC3`), 24 `NORMAL` (`VEC3`), 24 `TEXCOORD_0` (`VEC2`) entries and 36 `SCALAR` indices (12 triangles across 6 orthogonal faces), plus a double-sided base material and no embedded texture.

### 2. `cube-textured/cube-textured.glb`

- **Provenance**: derived from `cube-985496727e50/cube.glb` (`sourceSha256 = 985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630`) by embedding a procedural 256×128 six-label PNG texture (`textureSha256 = a724382bcfdf3e706d64d5cf57646ac28eb00977bd95b20ce5b1323c2572d10f`).
- **Invariance**: `geometryNormalsUvsIndicesUnchanged = true` and `embeddedTexture = true`. Vertex positions, normals, UVs, and indices are unchanged.
- **Per-face UV and colour definition**:

| Face | Normal | Source vertex slots (`sourceVertices`) | UV range `[u0, v0, u1, v1]` | RGB colour |
|---|---|---|---|---|
| `-Z` | `(0, 0, -1)` | `[0, 4, 13, 17]` | `[0.375, 0.5, 0.625, 0.75]` | `[222, 55, 55]` |
| `+Y` | `(0, 1, 0)` | `[1, 7, 14, 20]` | `[0.625, 0.25, 0.875, 0.5]` | `[50, 180, 80]` |
| `+X` | `(1, 0, 0)` | `[2, 5, 8, 11]` | `[0.375, 0.25, 0.625, 0.5]` | `[50, 90, 220]` |
| `-Y` | `(0, -1, 0)` | `[3, 9, 16, 22]` | `[0.125, 0.25, 0.375, 0.5]` | `[228, 179, 37]` |
| `+Z` | `(0, 0, 1)` | `[6, 10, 19, 23]` | `[0.375, 0.0, 0.625, 0.25]` | `[35, 184, 194]` |
| `-X` | `(-1, 0, 0)` | `[12, 15, 18, 21]` | `[0.375, 0.75, 0.625, 1.0]` | `[181, 57, 202]` |

### 3. `plane-textured/plane.glb`

- **Provenance**: a self-built deterministic non-square single-sided textured plane with an embedded 256×128 asymmetric four-colour marker PNG (`pngSha256 = 3b29a9b2c1b0f779aca95022bc91a3589d280e4da593ce35f973783835e681bf`).
- **Geometry and UV**:
  - `positions`: `[[-1, -0.5, 0], [1, -0.5, 0], [1, 0.5, 0], [-1, 0.5, 0]]`
  - `normals`: four `(0, 0, -1)` entries
  - `uvs`: `[[0, 1], [1, 1], [1, 0], [0, 0]]`
  - `indices`: `[0, 2, 1, 0, 3, 2]`

### 4. `plane-textured/plane-seam.glb`

- **Provenance**: derived from `plane-textured/plane.glb` (`sourceSha256 = 8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997`) by expanding the indices along the diagonal into six independent source vertex slots.
- **Geometry and slot semantics**: `sourceSlots = [0, 2, 1, 0, 3, 2]` and the indices become `[0, 1, 2, 3, 4, 5]`, with `preservesEqualPositionUvAsDistinctSlots = true`. Diagonal vertices that share position and UV stay as distinct source slots instead of being welded.

## How to Build and Reproduce

The four minimal geometry fixtures in this directory come from Blender 3.6+ and deterministic procedural tooling. Their provenance and generation follow.

1. **`cube-985496727e50/cube.glb` (plain untextured cube)**:
   - Uses the Blender default cube mesh (`[-1, 1]^3`, 24 vertices, 36 scalar indices, 12 triangles).
   - Exporter: Khronos glTF Blender I/O v3.6.28. The output is binary GLB (`.glb`) with POSITION, NORMAL, and TEXCOORD_0 attributes, a double-sided base material, and no texture.

2. **`cube-textured/cube-textured.glb` (six-face textured cube)**:
   - Built on `cube-985496727e50/cube.glb` with vertex positions, normals, UVs, and triangle indices unchanged (`geometryNormalsUvsIndicesUnchanged = true`).
   - A procedural 256×128 six-colour PNG label texture (texture SHA-256 `a724382bcfdf3e706d64d5cf57646ac28eb00977bd95b20ce5b1323c2572d10f`, per-face UVs and RGB values in the table above) is embedded as a GLB binary buffer view and bound to the material base colour texture.

3. **`plane-textured/plane.glb` (textured plane)**:
   - A procedural four-vertex single-sided asymmetric rectangle (`X: [-1, 1]`, `Y: [-0.5, 0.5]`, `Z: 0`, all normals `(0, 0, -1)`).
   - Embeds a 256×128 asymmetric four-colour marker PNG (SHA-256 `3b29a9b2c1b0f779aca95022bc91a3589d280e4da593ce35f973783835e681bf`) and uses indices `[0, 2, 1, 0, 3, 2]`.

4. **`plane-textured/plane-seam.glb` (diagonal seam plane)**:
   - Generated procedurally from `plane-textured/plane.glb` by expanding the four-vertex indices along the diagonal into six independent source vertex slots (`sourceSlots = [0, 2, 1, 0, 3, 2]`, indices `[0, 1, 2, 3, 4, 5]`).
   - Geometry and texture stay identical. The fixture proves that diagonal vertices with equal position and UV remain distinct slots during import instead of being auto-welded.

## Covered Tests (`:plugins:model-import-3d:test`)

- `EmbeddedTextureTest`
  - `sourcePixelsAndUvsSurviveWithoutFlippingOrResizing` (`cube-985496727e50/cube.glb`)
  - `paddedNativeBorderSamplingMatchesSourceClampAtEdgesAndInterior` (`cube-985496727e50/cube.glb`)
  - `materialFactorMultipliesInLinearSpaceAndFaceColorsAreExplicit` (`cube-985496727e50/cube.glb`)
  - `unsupportedSamplerExternalImagesAlphaAndCorruptionAreRejectedBeforeBinding` (`cube-985496727e50/cube.glb`)
- `ModelImportPluginTest`
  - `documentSwitchDuringPreparationRefusesWrite` (`cube-985496727e50/cube.glb`)
  - `staleRetainedModelRefusesWriteEvenWithSameId` (`cube-985496727e50/cube.glb`)
  - `confirmationCannotWriteToChangedDocument` (`cube-985496727e50/cube.glb`)
  - `faceColorsAndDensePlanReachConfirmationBeforeWrite` (`cube-985496727e50/cube.glb`)
- `PlanarImportTest`
  - `equalPositionAndUvSeamSlotsAreNotWelded` (`plane-textured/plane-seam.glb`)
  - `malformedActualPlaneGlbsAreRejectedDuringPrepare` (`plane-textured/plane.glb`)
  - `actualTexturedPlanePreparesWithoutLosingSourceUvOrAspect` (`plane-textured/plane.glb`, `cube-985496727e50/cube.glb`)
- `SurfaceAutoCameraTest`
  - `scaledTranslatedSourcesHaveEquivalentFramedJointProjections` (`cube-textured/cube-textured.glb`)
  - `userPreparationUsesOneFixedCameraAndPreservesTheGlbDigest` (`cube-textured/cube-textured.glb`)
  - `degenerateAndNonfiniteFramingFailBeforeAnyHostAccess` (`cube-textured/cube-textured.glb`)
- `SurfaceExpandedAllocationTest`
  - `actualCubePreparationRejectsAllocationBeforeHostWrites` (`cube-textured/cube-textured.glb`)
