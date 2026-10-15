---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.warp-deformer-alt-symmetry
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: deformer, warp, editing, symmetry
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# 曲面变形器 Alt 对称

> **Turboism 官方插件** · **状态：预览版**

将 Cubism 原生包围框的 Alt 对称拖拽语义扩展到曲面变形器（Warp Deformer）控制点，并在画布顶部控制条提供镜像轴切换按钮与绘图区状态提示。

| 项目 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.warp-deformer-alt-symmetry` |
| 分类 | `modeling` |
| 标签 | deformer, warp, editing, symmetry |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面模式 | `none` |
| 许可证 | Project License |

## 功能概述

- 按住 `Alt`（垂直网格轴）或 `Alt+Shift`（水平网格轴）拖拽曲面变形器控制点时，自动将反向位移镜像到对称控制点，并接入原生撤销/重做（Undo/Redo）。
- 在激活镜像轴期间，将选区权重写入（如笔刷选择工具涂抹及其他权重选区操作）实时同步镜像到同一曲面变形器对称轴另一侧的对应控制点。
- 在画布顶部控制条添加三态镜像轴切换按钮（关闭 / 垂直 / 水平）。
- 激活镜像轴时在宿主原生绘图区右下角提示区显示当前镜像模式。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：** 需要 Cubism。Turboism 当前支持全部已审核的 Editor 版本 `5.0.00` 至 `5.3.04`；仅当声明的服务与能力可用时暴露对应宿主功能。
- **界面模式：** `none`。
- **插件依赖：** 无。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本.
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

随 Turboism 正式发行包捆绑提供。可在安装器中勾选或在 **插件管理** 中启用与禁用。

## 使用方法

1. 点击画布顶部控制条按钮切换镜像轴状态：关闭 → 垂直 → 水平。
2. 按住 `Alt`（垂直）或 `Alt+Shift`（水平）拖拽控制点，对称侧控制点会按反向位移同步移动。
3. 激活镜像轴期间使用笔刷选择工具涂抹选区权重时，相同权重会同步应用到对称控制点。
4. 再次点击按钮切回关闭状态即可解除镜像，画布提示会自动清除。

## 功能能力

| 声明能力 | 用户效果 |
|---|---|
| `cubism.deformer.read` | 读取曲面变形器网格以记录快照并对比被拖拽的控制点。 |
| `cubism.deformer.alt-axis-mirror` | 将被拖拽的控制点沿当前激活的网格轴镜像写入。 |

## 权限

| 权限 | 作用域 | 用途说明 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 按下 Alt 时记录曲面变形器网格快照，并在释放时对比检测被拖拽的控制点。 |
| `turboism.cubism.model.write` | `application` | 通过 replaceGrid 提交镜像后的控制点坐标，使对称移动接入原生 Undo/Redo。 |
| `turboism.ui.toolbar.contribute` | `application` | 在画布顶部控制条添加镜像轴切换按钮。 |
| `turboism.ui.canvas.hint` | `application` | 在绘图区右下角原生提示区显示激活的镜像轴提示，并在关闭时清除。 |

## 隐私与数据

### 网络

不进行任何网络连接。

### 本地数据

不持久化插件数据。仅读取模型网格快照并写入当前手势对应的镜像控制点位置。

### 遥测

本插件不发送任何遥测数据。

插件生命周期与错误记录可能会附带插件 ID 写入 Turboism 会话日志和 Cubism 宿主日志。

## 状态与限制

- **状态：** 预览版。
- 当前版本完整回退一次镜像拖拽需要两步撤销：一步撤销镜像提交，一步撤销原生拖拽。
- 正好位于激活镜像轴上的控制点仅跟随原生拖拽移动。
- 当宿主集成功能不可用时，控制条按钮与画布提示会自动省略。

## 故障排除

| 现象 | 检查方法 |
|---|---|
| 顶部控制条按钮缺失 | 确认插件已启用且当前处于建模视图；可在宿主日志中检查 `BUTTON_MOUNTED`。 |
| 拖拽未触发镜像 | 确认镜像轴按钮未处于关闭状态、拖拽对象为曲面变形器，且对称轴另一侧存在对应控制点。 |
| 未显示画布提示 | 检查是否已授予画布提示权限，以及宿主是否提供原生提示表面。 |

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- 按项目许可证分发。
