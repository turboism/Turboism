---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.geometric-mesh-layout
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: embedded
---

# 几何网格布点

## 功能概述

在网格编辑工具栏中添加“几何网格布点”工具。点击启用后可在画布上设置中心锚点，并通过配置弹窗选择圆形或正多边形、设置半径及边数，点击确定后生成点线。

## 要求与兼容性

Turboism API: `[0.67.0,1.0.0)`. Cubism Editor: 5.0.00–5.3.04.

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

随 Turboism 官方发行包提供，可在插件管理中启用或停用。

## 使用方法

1. 选择一个图形网格并进入手动网格编辑模式。
2. 点击网格编辑工具栏中的**几何网格布点**按钮。
3. 在画布上点击或拖动以设置一个锚点作为中心。
4. 在弹窗中选择**形状预设**（圆周、三角形、四边形、六边形、八边形）或调整**边数**与**半径**，并可按需勾选**创建圆心**、**生成周边点到圆心的连线**以及**生成同心周**（通过滑块设置等距同心周数）。
5. 点击**确定**生成点线。

## 功能能力

| Capability | Effect |
|---|---|
| `cubism.mesh.custom-tools` | 在原生网格编辑工具栏中注册自定义工具。 |

## 权限

| Permission | Scope | Effect |
|---|---|---|
| `turboism.ui.toolbar.mesh.contribute` | `application` | 向网格编辑工具栏添加几何网格布点按钮。 |
| `turboism.cubism.model.read` | `application` | 读取可编辑网格顶点与相机投影以定位锚点与预览。 |
| `turboism.cubism.model.write` | `application` | 在当前可编辑网格中生成顶点与边。 |

## 隐私与数据

不进行任何网络通信或遥测，生成的点线纳入原生网格编辑撤销与重做。

## 状态与限制

仅用于网格编辑模式内生成点线。

## 故障排除

如果工具未显示，请确认已选中单个图形网格并进入网格编辑模式。

## 更新日志

### 0.1.0

- 初始版本发布。

## 支持与许可证

[Issues](https://github.com/turboism/Turboism/issues) · [Project License](../../LICENSE)
