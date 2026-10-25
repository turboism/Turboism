---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.artmesh-mirror
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing, symmetry
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: none
---

# 图形网格镜像

## 功能概述

在普通建模模式下镜像编辑图形网格顶点。切换选中的图形网格集合时，轴自动回到当前选择的包围框中心。

## 要求与兼容性

Turboism API: `[0.67.0,1.0.0)`. Cubism Editor: 5.0.00–5.3.04.

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

随 Turboism 正式发行包提供，在插件管理中启用或禁用。

## 使用方法

通过独立画布按钮开关镜像。拖动空心菱形或轴线移动镜像轴，拖动空心方形旋转轴，双击轴控件重置。角度文字放大显示，带描边而无底色。手柄位置跟随画布缩放，手柄本身保持易点击的屏幕大小。曲面变形器镜像有独立按钮，不注册快捷键。

## 功能能力

| Capability | Effect |
|---|---|
| `cubism.artmesh.mirror` | 控制镜像顶点编辑及画布轴控件。 |

## 权限

| Permission | Scope | Effect |
|---|---|---|
| `turboism.cubism.model.write` | `application` | 控制镜像顶点编辑及画布轴控件。 |
| `turboism.ui.toolbar.contribute` | `application` | 控制镜像顶点编辑及画布轴控件。 |
| `turboism.ui.canvas.hint` | `application` | 控制镜像顶点编辑及画布轴控件。 |

## 隐私与数据

不连接网络、不发送遥测、不持久化插件数据。镜像顶点修改加入原生手势的撤销与重做。

## 状态与限制

预览版。任意角度下按约 20 个屏幕像素容差寻找反射位置附近的顶点，保留双向且无歧义的配对。不会创建缺失的顶点。网格拓扑编辑模式使用其自身的镜像工具。

## 故障排除

轴未显示时，确认插件和画布按钮已启用，在普通建模模式选择图形网格，并检查运行时钩子可用性。对应点不跟随时，检查镜像位置附近是否存在可匹配顶点。

## 更新日志

### 0.1.0

- 初始版本发布。

## 支持与许可证

[Issues](https://github.com/turboism/Turboism/issues) · [Project License](../../LICENSE)
