---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.model-import-3d
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: 3d, glb, gltf, obj, import
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# 3D Model Import

## What it does

Imports supported GLB, glTF, OBJ and model ZIP packages as native Cubism meshes. Input parsing, texture normalization, geometry simplification and parameter authoring run locally without an LLM. Source-material mode binds X/Y rotations to dense joint Warp forms and Z rotation to native scalar forms, each spanning −180° to 180°. An unbound outer “整体定位” deformer moves and scales the entire model. Source textures stay attached to their faces. One native Undo operation removes the import. The Warp path uses dense parameter sampling. The direct source-vertex fallback uses elliptical interpolation; it preserves the 9×9 ordinary pose bank and chooses insertion density per patch from its projected rotation radius. The preparation stage minimizes derived lattice size within the existing global interpolation bound, keeping simplification plus interpolation inside the 5px geometry budget. This is geometry evidence; intermediate occlusion and Editor drag latency require native acceptance.

## Requirements and compatibility

The validated host is Cubism Editor 5.3.02 with Turboism API `[0.1.0,0.2.0)`. Open a model with a 1024×1024 canvas before importing. The menu chooses one source-derived camera and pivot to fit the model inside a 400px radius. The camera stays fixed throughout rotation. The face-color inspection mode uses ±30°; source-material mode uses XYZ full-circle ranges. Explicit probe requests retain their declared camera.

GLB files are limited to 16 MiB. The bounded importer supports opaque closed convex surfaces or a single convex quadrilateral plane. A restricted branch for two disjoint, double-sided parallel X-plane rectangles is implemented but has not completed native validation. A separate regular quadrilateral surface branch preserves the original triangle geometry and UVs in chart meshes under one native Warp deformer. The XYZ apple has real menu/save/reopen and source-geometry evidence; strict occlusion and official export of this full-circle apple remain unverified. Sparse chart depth ordering can produce occlusion errors. Arbitrary concave topology and intersecting sheets are not generally supported. OBJ is now a direct menu input for self-contained geometry. Select referenced buffers, MTL and PNG/JPEG resources when prompted, or package them together with exactly one GLB, glTF or OBJ in a ZIP. FBX, DAE and RAR are not direct menu inputs. Earlier apple evidence used an OBJ converted to GLB; it does not verify the newer OBJ menu path.

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions.
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->

## Install and enable

Bundled with the official Turboism distribution; select **3D Model Import** in the installer or enable it in **Plugin Management**. For local builds, run `./gradlew :plugins:model-import-3d:jar --offline`. The JAR contains the SDK-only importer and excludes HostProbe, Turboism runtime and official Editor classes. Host validation uses isolated queued sessions. Unit tests that exercise large local 3D model fixtures (`artifacts/` and `build/model-import-3d-inputs/`) skip via JUnit `Assumptions` on clean checkouts; stage or symlink the directories (or set `TURBOISM_IMPORT_3D_ARTIFACTS` / `TURBOISM_IMPORT_3D_INPUTS`) to run them, and pass `-Pturboism.requireImport3dFixtures=true` (or `TURBOISM_REQUIRE_IMPORT_3D_FIXTURES=true`) to fail if any fixture is missing.

## How to use

菜单：**插件 → 3D 模型 → 导入 3D 模型…**。选择“保留原材质”，再选择 GLB、glTF、OBJ 或模型 ZIP；单独模型缺少外部资源时，按引用提示选择材质、buffer 或 PNG/JPEG 贴图，再确认网格和参数绑定点数量后导入。拖动新增的 **X / Y / Z 旋转**参数即可旋转，三轴可以同时调整。整体移动或缩放请选择 **整体定位**变形器；单独移动子网格会改变其在投影 Warp 内的采样位置。旧工程不会自动增加定位层。切换目标文档或停用插件后，旧请求不会继续写入。

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

## Support and license

Report the input profile, host version and local diagnostic message to the Turboism project. License: Project License. This development plugin is not a claim of compatibility with adjacent Cubism versions.

### 2026-10-02 输入与形态预算调整

授权 GLB 的总索引上限为 600,000；顶点 100,000 和容器 16 MiB。原生投影样本上限 4,000,000，形态上限 131,072，准备阶段检查最低坐标存储量。扩大读取预算后，凹面/复杂遮挡仍需独立支持证明；苹果已通过真实菜单导入、保存及新进程重开，并交付原生工程；原几何和 UV 完整保留。

### 用户入口的固定取景

菜单导入根据已读取源几何，拟合一个固定 400px 包围球半径的镜头和枢轴。模型尺寸或原点不同也能适配 1024×1024 画布；旋转中保持此镜头。直接探针调用保留其显式相机。苹果使用 98 个源曲面块，几何控制为 17×17 联合关键形。为避免官方序列化容量溢出，大模型的重复层序形态按存储预算选择 9×9 采样；几何控制密度保持不变。

### 苹果工程与运行时导出

[可旋转苹果工程](../../artifacts/apple-native/rotatable-r1-20261002/README.md)可直接在 Editor 中打开；[运行时模型](../../artifacts/apple-native/runtime-r1-20261002/README.md)可在官方 Viewer 中打开，运行时不需要 Turboism。菜单、单步撤销/重做、失败回滚、原生保存、新进程重开、官方图集/MOC 导出及独立官方 Core 的 11 个完整姿态已验证。官方 Viewer 实际显示并响应了两个旋转参数的鼠标操作；精确 Viewer 角度/逐像素对照仍未验收。

苹果源文件原本没有材质；红皮/棕梗来自为源 3D 模型制作的贴图，不是恢复原材质或在 Cubism 改色。原面数 150,784，无减面。CMO3 约 96.9 MiB，MOC 约 127.5 MiB；SDK 官方 MOC 预算为 128 MiB。较大工程导入和保存会较慢。

353 个姿态的源深度诊断包括层序关键点、所有单元中心和边中点，以及 64 个固定随机点。当前有 3 个姿态出现 3、3、7 个像素的层序残差，未用边界遮罩隐藏；这仍是源几何诊断，不能替代真实原生求值/渲染验收。同一 353 姿态的独立官方 Core 已核验全部原生顶点（共 35,391,074 样本），最大投影误差 0.436px，UV/拓扑保持。关键点整数层序严格，中间态在 floor/ceil 量化范围内；源深度仍是源几何诊断，不能称为全域原生像素通过。主用户会话未部署本次开发构建。

苹果图集重排工程已交付于 `../../artifacts/apple-native/packed-r1-20261002/`：保留全部源面、坐标与UV身份，实际图集位置重排、单history Undo/Redo、typed SAVE_AS与新进程重开通过，11组联合姿态PNG逐字节一致；完整receipt见目录provenance。该结论仅支持已声明±30°苹果域内的持久化一致性，不升级连续像素/遮挡或其他输入验收。

穿插和循环反例已通过真实菜单拒绝验证：`menu-reject-native-r1-20261002/strict-rejection-acceptance`覆盖两个冻结GLB与两种颜色模式，4次均拒绝；作者态、图片/图集/参数和完整history revision/entries/redo保持，作业正常退出。当前是结构子集拒绝，未实现穿插/循环表面的通用切分支持。

### Cat 编辑性能（2026-10-04）

Cat 自动减面和自适应插值输出保留 512 个网格、35,397 个顶点槽和 48,864 个三角形，普通及派生形态共 481,632 个。几何与插值合计上界保持 5px；这不包含遮挡近似。已定位旧结构的局部编辑开销：Editor 会强制刷新同一 Part 下的兄弟网格。直接顶点导入现为每个曲面生成独立子 Part，以限制刷新范围。

独立 Part 的 Cat 文件已通过官方保存、新进程重建、原贴图内容保持，以及实机第一网格三步拖动和完整 Undo。纯官方事件约 47–57ms，系数候选首步约 41ms、后两步约 15ms；未使用行缓存。此证据不覆盖连续、多选或所有网格的编辑性能，加载开销仍需优化。新产品真实菜单导入、保存和官方新进程读取已通过；新输出位于 `artifacts/cat-native/parent-scoped-import-r1-20261004`。加载、连续编辑和最终导出仍待完成；未部署主 Editor。


当前开发候选会机械比较三个几何/插值预算组合，在总偏差5px、UV偏差0.5texel内选择坐标与形态基础存储估算最低的方案。Cat计划为51,790面、417,800个普通/派生形态；相较上一版，保留更多面但减少形态存储。146项插件回归和独立几何抽样通过，新计划尚未完成原生性能与视觉验收，也未部署到主Editor。证据：`build/model-import-3d-offline/cat-cost-plan-candidate-r1-20261004`。

### Cat 默认插值接入候选（2026-10-04）

最大网格20步连续拖动/Undo已通过：中位72.2ms→10.4ms，事件分配约减少89%；未使用行缓存。实际cost-plan产物已通过保存、官方新进程重建和原图像素一致性。完整setup插值对照形态/结构哈希完全一致，单次setup分配减少33.7%，耗时降低46.2%，但回收后堆约520MB基本不变。新版运行时候选已默认接入经过host/class哈希校验的插值及系数路径，保留安全模式、显式关闭和官方回退；12项定向回归及license检查通过。真实默认启动的菜单导入/保存仍在验证，未部署主Editor。加载临时分配仍高，多选/网格内编辑、遮挡/缝隙、导出和多格式原生验收尚未完成。


2026-10-04：正式默认插值接入真实菜单导入/保存验收通过，job `dc146af6-32a2-49ad-aa9d-334d73e83845` 官方 succeeded、BAT/wrapper0、正常退出无强杀。未传实验插值开关，启动日志确认精确hash-admitted 5.3.02插值patch。产物28535149 bytes、SHA `30dcdc6e75bcb2af9efef461a89297478fa2813c1719d575600c36a6ff4a41fa`。无代理官方新进程完整重建417800形态；全部坐标/透明度/绘制顺序bank `d67c333b5c1874e24e393c2755181f41dc52573fc56ac5fe8f40b02e4cb34734` 与此前官方基线完全一致，512独立父Part、37202槽、51790三角形及精确采样绑定通过。521归档条目/520 PNG均可读，原贴图1048576像素一致。新输出 `artifacts/cat-native/default-interpolation-import-r1-20261004`，完整证据 `build/model-import-3d-offline/cat-default-import-native-r1-20261004`。未部署主Editor；原生UI加载性能、常驻形态存储降低、网格内编辑、多选、渲染遮挡/接缝、导出、多格式实机验收未完成，目标保持active。

### 外部资源输入候选（2026-10-04）

引用资源通过独立SDK文件选择授权读取，无需人工转GLB或先制作ZIP。原始Cat分开选择OBJ、MTL、JPEG的规范化输出与原ZIP逐字节一致；12项关联/生命周期/预算测试及158项完整回归通过。输入和外部文件合计16MiB，最多64个资源，ZIP仍需完整；只处理基础颜色引用，不额外读取bump/光照贴图。新候选尚在真实菜单验收，未部署主Editor。


2026-10-04：真实OBJ+独立MTL+JPEG输入原生验收通过，job `753518ad-0f27-4a61-b909-ac3898d2b0b5` succeeded，BAT/wrapper0、正常退出无强杀；真实选择器选中原OBJ，随后按引用选择 `12221_Cat_v1_l3.mtl` 与 `Cat_diffuse.jpg`，原文件hash逐一匹配。原生保存28535839 bytes、SHA `1c261adeebefc55930d9f3a3421173a68974fc3cd37bec87a8a4b71877e82883`。无代理官方新进程重建全部417800形态；bank `d67c333b5c1874e24e393c2755181f41dc52573fc56ac5fe8f40b02e4cb34734` 与ZIP导入基线完全一致；精确采样绑定、512独立父Part、37202槽、51790三角形通过。521条目/520 PNG均可读，原贴图1048576像素完全一致。输出 `artifacts/cat-native/separate-files-import-r1-20261004`；证据 `build/model-import-3d-offline/cat-external-import-native-r1-20261004`。GLB/glTF外部资源只有当前测试层证据，未把OBJ实机结果扩称为其他格式实机通过。未部署主Editor；目标active，网格内编辑、遮挡/缝隙、多选、导出及其他格式实际原生路径仍未完成。
