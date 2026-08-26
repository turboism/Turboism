---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.mesh-edit-mirror-axis-enhance
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# 网格编辑镜像轴增强

> **Turboism 官方插件** · **状态：预览**

检查网格和变形器状态，并为受支持的网格编辑 UI 添加具有边界的镜像轴角度控件。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.mesh` |
| 类别 | `modeling` |
| 标签 | mesh, artmesh, editing |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 接口 | `embedded` |
| 许可证 | 项目许可证 |

## 功能概述

- 注册“检查网格”操作，用于报告网格、变形器和上下文数量。
- 提供从 -180° 到 180°、步长为 0.1° 且支持重置的镜像轴角度控件。
- 对检查和角度变更均使用经过验证的类型化服务。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：** 需要 Cubism。Turboism 当前仅接受经过审查的精确 Editor 构件 `5.2.03` 和 `5.3.02`；本插件仅在其声明的服务和能力可用时公开每项面向宿主的功能。
- **接口模式：** `embedded`。
- **插件依赖项：** 未声明。

## 安装与启用

此官方插件是一个**商店候选插件**，尚未发布为商店条目。在市场发布之前，请通过 Turboism 的官方发布包安装它，然后在**插件管理**中启用它。当不再需要该工作流时，可在同一窗口中禁用或卸载它。

## 使用方法

1. 从其贡献的操作入口运行**检查网格**，以报告当前网格/变形器上下文。
2. 进入 Cubism 受支持的网格编辑模式，并找到镜像轴角度控件。
3. 调整角度或使用重置；该控件更改的是当前网格编辑工具角度，而不是已存储的插件设置。

## 功能能力

| 声明的能力 | 对用户的作用 |
|---|---|
| `cubism.mesh.read` | 读取网格快照以供检查。 |
| `cubism.deformer.read` | 读取变形器快照以供检查。 |
| `cubism.mesh.mirror-axis-angle` | 读取和写入当前镜像轴工具角度。 |
| `ui.mesh-edit.mirror-axis-angle` | 将具有边界的控件贡献给受支持的网格编辑 UI。 |
| `ui.context-source.read` | 读取检查所使用的类型化操作上下文。 |
| `ui.status.notify` | 报告检查和回退结果。 |

## 权限

| 权限 | 作用域 | 请求原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 为检查器读取网格和变形器快照。 |
| `turboism.ui.context-source.read` | `application` | 读取检查上下文所需的类型化上下文源。 |
| `turboism.ui.status.notify` | `application` | 通知检查结果和空回退。 |
| `turboism.action.register` | `application` | 注册网格检查操作。 |
| `turboism.cubism.model.write` | `application` | 更改当前网格镜像轴工具角度。 |
| `turboism.ui.panel.contribute` | `application` | 将镜像轴角度控件贡献给 Cubism 的网格编辑器。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

不持久化插件数据。检查会读取模型/上下文快照；角度控件仅写入活动网格编辑工具角度。

### 遥测

本插件不会发送遥测数据。

插件生命周期和失败记录可能会出现在 Turboism 的会话日志和 Cubism 的宿主日志中，并附带插件 ID。

## 状态与限制

- **状态：** 预览。
- 需要经过审查的网格、变形器、上下文源、镜像轴和网格编辑 UI 服务。
- 检查为只读操作。其精确的宿主集成不可用时，会省略镜像轴控件。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| 缺少镜像控件 | 确认插件已启用、网格编辑模式处于活动状态，并且宿主公开了经过审查的镜像轴 UI 服务。 |
| 检查报告没有对象 | 打开包含网格或变形器的模型，并确保相关编辑器上下文处于活动状态。 |
| 角度更改被拒绝 | 检查受支持的 -180° 至 180° 范围，并查看插件日志中的宿主服务可用性。 |

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** 项目许可证
- **插件 ID：** `dev.turboism.plugin.mesh`
