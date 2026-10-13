---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.boundingbox-warp-mirror
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: deformer, warp, editing, symmetry, boundingbox
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# 包围框弯曲镜像

> **Turboism 官方插件** · **状态：预览版**

迁移经典包围框叠加层镜像工作流：通过叠加层按钮打开镜像方向对话框，对每个选中的弯曲变形器（Warp Deformer）执行单次整体镜像。与交互式 Alt 拖拽镜像插件 `warp-deformer-alt-symmetry` 互补，互不干扰。

| 项目 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.boundingbox-warp-mirror` |
| 分类 | `modeling` |
| 标签 | deformer, warp, editing, symmetry, boundingbox |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面模式 | `none` |
| 许可证 | Project License |

## 功能概述

- 在原生包围框叠加层提供镜像入口按钮（`boundingbox-warp-mirror.mirror`）。
- 点击按钮打开模态方向选择框，提供四个方向（左 → 右、右 → 左、上 → 下、下 → 上）以及默认勾选的「不影响子物体」复选框。
- 确认后对每个选中的弯曲变形器完整控制点网格执行镜像；取消不会写入任何内容。
- 勾选「不影响子物体」时，所有子代对象（ArtMesh、嵌套弯曲变形器与旋转变形器）保持当前画布几何形状不变；若运行时无法证明满足该保证，则拒绝执行而非静默跳过子物体。
- 父变形器写入与全部子代补偿在同一个宿主事务中提交（合并为单条撤销记录），失败时回滚并给出明确原因提示。

## 要求与兼容性

- 需要由 Turboism 运行时驱动的 Cubism Editor 会话。
- 需要经精确验证的 Editor 模型适配切片；在不受支持的宿主版本上服务返回 `UNAVAILABLE`。
- 方向标签描述实际镜像方向，已修正旧版对话框左右方向反转的问题。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本.
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

随 Turboism 发行包捆绑交付。可在安装器或插件管理界面中启用或禁用；启用期间包围框叠加层显示镜像按钮。

## 使用方法

1. 选中一个或多个弯曲变形器（父子同时选中时按先父后子顺序应用）。
2. 点击包围框叠加层上的镜像按钮。
3. 选择镜像方向，保留或取消勾选「不影响子物体」，然后点击确定。
4. 被阻断或失败的目标会在提示框中列出原因，且不会写入任何更改。

## 功能能力

- `cubism.deformer.read`
- `cubism.deformer.warp-mirror`

## 权限

- `turboism.cubism.model.read`
- `turboism.cubism.model.write`
- `turboism.ui.overlay.contribute`

## 隐私与数据

插件仅读取当前选区与模型几何数据，不持久化任何插件数据，也不进行任何网络通信。

## 状态与限制

- 状态为预览版。仅支持弯曲变形器整体镜像，不支持控制点子集；ArtMesh 与旋转变形器不是镜像目标。
- 遇到锁定目标或子代、包含胶水（Glue）的模型、非已存储关键形编辑上下文、退化网格或无法求解的子代补偿时安全拒绝执行，不会降低子物体保持保证。

## 故障排除

- 点击按钮提示无目标：请确认已选中至少一个弯曲变形器。
- 目标提示被阻断：请查看提示框中的具体原因（锁定状态、存在胶水、插值编辑上下文或无法求解补偿）。

## 支持与许可证

Project License。请通过标准 Turboism 支持渠道反馈问题。
