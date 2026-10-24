---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-folders
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: texture-atlas, packing, auto-layout
turboismApi: "[0.67.4,1.0.0)"
requiresCubism: true
interface: swing
---

# 按部件文件夹排版纹理集

## 功能概述

按顶层部件文件夹生成纹理页，空间不足时递归拆分子文件夹。排版算法从运行中的注册表发现，不依赖特定算法插件。

## 要求与兼容性

需要 Java 17+、Turboism API [0.67.4,1.0.0)、活动的可编辑 Cubism 模型。至少启用一个支持完整纹理集排版的算法。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
- 顶部菜单集成 不适用于 5.0.00–5.0.06（更早版本 Cubism 本身无此功能）
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

通过 Turboism 插件管理安装插件 JAR，启用文件夹排版插件，以及 MaxRects-BSSF、Bitmask Nesting 或 Dalsoo 等支持完整纹理集的算法插件。

Bitmask Nesting 与 Dalsoo 使用图片局部坐标下的网格轮廓；轮廓源不可用时保守回退到完整图片矩形。保持原始尺寸且不旋转。部件树顺序用于优先分组，允许跨文件夹利用剩余空间；叶子文件夹溢出时优先装满已有页，再创建新页。

## 使用方法

打开 **Turboism → 按部件文件夹一键排版**，选择最大边长（1024、2048、4096 或 8192）、算法、边距、间距与自动尺寸。排版作为一次可撤销编辑替换整个纹理集，页面顺序遵循部件树。


新导入的 PSD 尚无纹理集时，操作会直接创建输出页面，不触发原生初始放置。纹理图像生成和布局作为一次可撤销编辑完成，无需再打开纹理集窗口点击确认。

## 功能能力

- `cubism.texture-atlas.layout`
- `cubism.model.read`
- `ui.menu.contribute`
- `ui.status.notify`

## 权限

- `turboism.action.register`
- `turboism.ui.menu.contribute`
- `turboism.ui.status.notify`
- `turboism.cubism.model.read`
- `turboism.cubism.model.write`

## 隐私与数据

读取当前模型并在编辑器内写入纹理集布局，不上传模型数据，也不发起网络请求。

## 状态与限制

预览功能。仅支持当前页的算法会显示但不可用于整个纹理集排版。菜单不依赖 MaxRects，但仍需启用至少一个支持完整纹理集的算法。

## 故障排除

没有可用算法时，先启用兼容的算法插件；没有纹理集时，先为当前模型创建或打开纹理集。使用撤销恢复上一次布局。

## 支持与许可证

请在 https://github.com/turboism/Turboism/issues 提交可复现问题，并注明编辑器版本和所选算法。适用仓库的 Project License。
