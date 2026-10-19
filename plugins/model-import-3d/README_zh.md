---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.model-import-3d
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: 3d, glb, gltf, obj, import
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: embedded
---

# 3D 模型导入

> **Turboism 官方插件** · **状态：预览版**

将受支持的静态不透明 GLB、glTF、OBJ/MTL 及模型 ZIP 压缩包导入为原生 Cubism 网格，支持 PNG/JPEG 基础色贴图、XYZ 整圈旋转及有界几何优化。

## 功能概述

将受支持的 GLB、glTF、OBJ 和模型 ZIP 压缩包导入为原生 Cubism 网格。输入解析、纹理归一化、几何简化与参数绑定均在本地完成，不依赖外部服务。在保留原材质模式下，X/Y 轴旋转绑定至密集联合弯曲变形（Warp）关键形，Z 轴旋转绑定至原生标量关键形，旋转范围均为 −180° 至 180°。外层未绑定的「整体定位」变形器可用于整体移动和缩放模型，源贴图保持绑定在其对应网格面上，且整个导入可通过单次原生撤销（Undo）完整移除。直接顶点路径采用椭圆插值与自适应采样，在将简化与插值合误差控制在 5px 预算内的同时降低派生形态存储开销。

## 要求与兼容性

- **SDK API 范围：** `[0.67.0,1.0.0)`。
- **画布要求：** 导入前请先打开一个画布尺寸为 1024×1024 的目标模型。菜单导入会根据源几何自动拟合固定 400px 包围球半径的相机与枢轴。
- **输入格式：** 支持单个 GLB、glTF、OBJ 或包含完整资源的模型 ZIP（上限 16 MiB，最多 64 个关联资源）；若单独选择的模型引用了外部 MTL、buffer 或 PNG/JPEG 贴图，会按引用提示依次选择。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本.
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

随 Turboism 正式发行包捆绑提供。可在安装器中勾选 **3D 模型导入**，或在 **插件管理** 中启用与禁用。

## 使用方法

1. 打开一个 1024×1024 画布的模型文档。
2. 选择菜单：**插件 → 3D 模型 → 导入 3D 模型…**。
3. 选择「保留原材质」（±180° XYZ 旋转）或面颜色检查模式（±30°），然后选择 GLB、glTF、OBJ 或模型 ZIP；若缺少外部引用资源，按提示选择对应材质、buffer 或 PNG/JPEG 贴图。
4. 在确认对话框中核对网格与参数绑定点数量后确认导入。
5. 拖动新增的 **X / Y / Z 旋转**参数即可旋转模型；整体移动或缩放请选中外层 **整体定位** 变形器。

## 功能能力

插件清单中未声明任何功能能力。

导入器通过公开 SDK 模型构建、原生参数绑定、文件选择授权和单个可撤销编辑事务工作；解析与几何准备在后台执行，确认后才执行模型写入。

## 权限

- `turboism.action.register`：注册 3D 模型导入操作。
- `turboism.ui.menu.contribute`：添加 3D 模型导入菜单项。
- `turboism.ui.file-chooser.request`：请求用户选择受支持的模型文件或 ZIP 压缩包。
- `turboism.file.read`：读取用户选择的模型文件及关联贴图资源。
- `turboism.cubism.model.read`：检查目标模型与参数状态。
- `turboism.cubism.model.write`：在单个可撤销事务中创建网格、变形器与参数形态。

## 隐私与数据

输入文件通过 SDK 文件授权在本地读取，不会上传模型或发送遥测数据。导入过程不会覆盖原模型文件；诊断信息仅记录在本地 Turboism 日志中。

## 状态与限制

- **状态：** 预览版。
- 源基础色支持纯色或不透明 8 位 RGB/RGBA PNG/JPEG 贴图（解码后 RGBA 合计不超过 4 MiB），采样模式为 CLAMP_TO_EDGE 与 LINEAR；包含透明度、重复/镜像环绕、mipmap 或色彩配置文件的贴图会在创建原生对象前安全拒绝。
- 支持不透明闭合凸面、受支持的凸四边形平面以及经过有界几何简化的曲面；任意凹面拓扑与自相交/循环穿插薄片会被拒绝。
- 切换目标文档或停用插件后，进行中的导入请求会安全中止，不会向新文档写入。

## 故障排除

- **未显示菜单项：** 确认插件已在插件管理中启用。
- **导入被拒绝：** 检查当前模型画布是否为 1024×1024、文件大小是否在 16 MiB 以内，以及几何/贴图是否符合上述支持范围。
- **切换文档后中止：** 打开目标模型后重新从菜单发起导入。

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** Project License
