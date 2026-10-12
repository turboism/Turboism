# 3D Model Import Test Assets (`model-import-3d`)

此目录存放用户授权入库的 `:plugins:model-import-3d:test` 自建最小 GLB 几何夹具，使依赖这些最小模型的契约测试在干净检出环境（无本地 `artifacts/` 或 `build/model-import-3d-inputs/`）下也能真实执行。

第三方模型（如 `cat-textured`、`apple-textured`）以及任何 `.cmo3`、`.can3`、`.psd` 或私有工程资产**不入库**，相关测试仍通过 `ModelImportTestFixtures` 按本地路径解析并由 JUnit `Assumptions` 门控。

## 目录结构

```text
test-assets/model-import-3d/
├── README.md
├── cube-985496727e50/
│   └── cube.glb
├── cube-textured/
│   └── cube-textured.glb
└── plane-textured/
    ├── plane.glb
    └── plane-seam.glb
```

## 文件清单与 SHA-256

| 仓库内路径 | 原解析逻辑路径 | 字节数 | SHA-256 |
|---|---|---:|---|
| `test-assets/model-import-3d/cube-985496727e50/cube.glb` | `build/model-import-3d-inputs/cube-985496727e50/cube.glb` | 1936 | `985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630` |
| `test-assets/model-import-3d/cube-textured/cube-textured.glb` | `artifacts/cube-textured/cube-textured.glb` | 5672 | `dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859` |
| `test-assets/model-import-3d/plane-textured/plane.glb` | `artifacts/plane-textured/plane.glb` | 2868 | `8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997` |
| `test-assets/model-import-3d/plane-textured/plane-seam.glb` | `artifacts/plane-textured/plane-seam.glb` | 3068 | `89d1617c9555250d504136c081abec11b2f3e00fde45237d633bdb2096585f34` |

## 来源与几何/UV 元数据（Manifest 摘要）

### 1. `cube-985496727e50/cube.glb`

- **来源**：由用户显式提供并授权用于验收测试的自建最小立方体（源自本地 `~/Downloads/cube.glb`，Khronos glTF Blender I/O v3.6.28 导出）。
- **几何与属性**：单网格 `Cube`，坐标范围 `[-1, 1]^3`，包含 24 个 `POSITION`（`VEC3`）、24 个 `NORMAL`（`VEC3`）、24 个 `TEXCOORD_0`（`VEC2`）及 36 个 `SCALAR` 索引（12 个三角形 / 6 个正交面），双面基础材质（无内嵌贴图）。

### 2. `cube-textured/cube-textured.glb`

- **来源**：基于 `cube-985496727e50/cube.glb`（`sourceSha256 = 985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630`）程序化内嵌一张 256×128 六面标签 PNG 贴图（`textureSha256 = a724382bcfdf3e706d64d5cf57646ac28eb00977bd95b20ce5b1323c2572d10f`）。
- **不变性**：`geometryNormalsUvsIndicesUnchanged = true`，`embeddedTexture = true`，原顶点坐标、法线、UV 与索引完全未改动。
- **六面 UV 与颜色定义**：

| 面 | 法线 | 源顶点槽位 (`sourceVertices`) | UV 范围 `[u0, v0, u1, v1]` | RGB 颜色 |
|---|---|---|---|---|
| `-Z` | `(0, 0, -1)` | `[0, 4, 13, 17]` | `[0.375, 0.5, 0.625, 0.75]` | `[222, 55, 55]` |
| `+Y` | `(0, 1, 0)` | `[1, 7, 14, 20]` | `[0.625, 0.25, 0.875, 0.5]` | `[50, 180, 80]` |
| `+X` | `(1, 0, 0)` | `[2, 5, 8, 11]` | `[0.375, 0.25, 0.625, 0.5]` | `[50, 90, 220]` |
| `-Y` | `(0, -1, 0)` | `[3, 9, 16, 22]` | `[0.125, 0.25, 0.375, 0.5]` | `[228, 179, 37]` |
| `+Z` | `(0, 0, 1)` | `[6, 10, 19, 23]` | `[0.375, 0.0, 0.625, 0.25]` | `[35, 184, 194]` |
| `-X` | `(-1, 0, 0)` | `[12, 15, 18, 21]` | `[0.375, 0.75, 0.625, 1.0]` | `[181, 57, 202]` |

### 3. `plane-textured/plane.glb`

- **来源**：自建确定性非正方形单面贴图平面，内嵌 256×128 非对称四色标识 PNG（`pngSha256 = 3b29a9b2c1b0f779aca95022bc91a3589d280e4da593ce35f973783835e681bf`）。
- **几何与 UV**：
  - `positions`：`[[-1, -0.5, 0], [1, -0.5, 0], [1, 0.5, 0], [-1, 0.5, 0]]`
  - `normals`：4 个 `(0, 0, -1)`
  - `uvs`：`[[0, 1], [1, 1], [1, 0], [0, 0]]`
  - `indices`：`[0, 2, 1, 0, 3, 2]`

### 4. `plane-textured/plane-seam.glb`

- **来源**：由 `plane-textured/plane.glb`（`sourceSha256 = 8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997`）沿对角线将索引展开为 6 个独立源顶点槽位生成。
- **几何与槽位语义**：`sourceSlots = [0, 2, 1, 0, 3, 2]`，索引变为 `[0, 1, 2, 3, 4, 5]`，`preservesEqualPositionUvAsDistinctSlots = true`（位置与 UV 相等的对角顶点保持为独立源槽位而不被焊接）。

## 如何构建与复现

本目录下的四份最小几何夹具基于 Blender 3.6+ 与确定性程序化工具生成，构建来源与生成方式如下：

1. **`cube-985496727e50/cube.glb`（基础无贴图立方体）**：
   - 使用 Blender 默认立方体网格（坐标范围 `[-1, 1]^3`，24 顶点，36 标量索引，12 三角面）。
   - 导出器：Khronos glTF Blender I/O v3.6.28，导出格式为二进制 GLB（`.glb`），包含 POSITION、NORMAL、TEXCOORD_0 属性及双面基础材质，无贴图。

2. **`cube-textured/cube-textured.glb`（六面贴图立方体）**：
   - 以 `cube-985496727e50/cube.glb` 为基底，顶点位置、法线、UV 及三角形索引完全保持不变（`geometryNormalsUvsIndicesUnchanged = true`）。
   - 程序化生成一张 256×128 的六面色块 PNG 标识贴图（贴图 SHA-256 为 `a724382bcfdf3e706d64d5cf57646ac28eb00977bd95b20ce5b1323c2572d10f`，各面 UV 与 RGB 色值详见前表），以 GLB 二进制 buffer view 形式内嵌并绑定至材质主色贴图。

3. **`plane-textured/plane.glb`（贴图平面）**：
   - 程序化生成 4 顶点单面非对称矩形平面（坐标范围 `X: [-1, 1]`, `Y: [-0.5, 0.5]`, `Z: 0`，法线全为 `(0, 0, -1)`）。
   - 内嵌 256×128 非对称四色标识 PNG 贴图（SHA-256 为 `3b29a9b2c1b0f779aca95022bc91a3589d280e4da593ce35f973783835e681bf`），索引为 `[0, 2, 1, 0, 3, 2]`。

4. **`plane-textured/plane-seam.glb`（对角线展开缝平面）**：
   - 由 `plane-textured/plane.glb` 程序化展开生成：沿对角线将 4 顶点索引展开为 6 个独立源顶点槽位（`sourceSlots = [0, 2, 1, 0, 3, 2]`，索引变为 `[0, 1, 2, 3, 4, 5]`）。
   - 保持几何与贴图完全一致，用于验证导入时位置与 UV 均相等的对角顶点保持为独立槽位而不被自动焊接。

## 对应测试（`:plugins:model-import-3d:test`）

- `EmbeddedTextureTest`
  - `sourcePixelsAndUvsSurviveWithoutFlippingOrResizing`（`cube-985496727e50/cube.glb`）
  - `paddedNativeBorderSamplingMatchesSourceClampAtEdgesAndInterior`（`cube-985496727e50/cube.glb`）
  - `materialFactorMultipliesInLinearSpaceAndFaceColorsAreExplicit`（`cube-985496727e50/cube.glb`）
  - `unsupportedSamplerExternalImagesAlphaAndCorruptionAreRejectedBeforeBinding`（`cube-985496727e50/cube.glb`）
- `ModelImportPluginTest`
  - `documentSwitchDuringPreparationRefusesWrite`（`cube-985496727e50/cube.glb`）
  - `staleRetainedModelRefusesWriteEvenWithSameId`（`cube-985496727e50/cube.glb`）
  - `confirmationCannotWriteToChangedDocument`（`cube-985496727e50/cube.glb`）
  - `faceColorsAndDensePlanReachConfirmationBeforeWrite`（`cube-985496727e50/cube.glb`）
- `PlanarImportTest`
  - `equalPositionAndUvSeamSlotsAreNotWelded`（`plane-textured/plane-seam.glb`）
  - `malformedActualPlaneGlbsAreRejectedDuringPrepare`（`plane-textured/plane.glb`）
  - `actualTexturedPlanePreparesWithoutLosingSourceUvOrAspect`（`plane-textured/plane.glb`、`cube-985496727e50/cube.glb`）
- `SurfaceAutoCameraTest`
  - `scaledTranslatedSourcesHaveEquivalentFramedJointProjections`（`cube-textured/cube-textured.glb`）
  - `userPreparationUsesOneFixedCameraAndPreservesTheGlbDigest`（`cube-textured/cube-textured.glb`）
  - `degenerateAndNonfiniteFramingFailBeforeAnyHostAccess`（`cube-textured/cube-textured.glb`）
- `SurfaceExpandedAllocationTest`
  - `actualCubePreparationRejectsAllocationBeforeHostWrites`（`cube-textured/cube-textured.glb`）
