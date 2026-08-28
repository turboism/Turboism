---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.parameter-batch-transfer
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: parameter, batch-edit, transfer
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# 参数批量传输

> **Turboism 官方插件** · **状态：预览**

将参数绑定从一个选定的 ArtMesh 或变形器传输到多个目标参数。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.parameter-batch-transfer` |
| 类别 | `modeling` |
| 标签 | parameter, batch-edit, transfer |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 接口 | `swing` |
| 许可证 | 项目许可证 |

## 功能概述

- 向变形器、部件和工作区对象上下文菜单添加批量传输条目。
- 从一个选定的 ArtMesh、Warp Deformer 或 Rotation Deformer 构建模态目标选择会话。
- 应用经确认的传输，可选择反转，并报告每项结果。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：** 需要 Cubism。Turboism 当前仅接受经过审查的精确 Editor 构件 `5.2.03`、`5.3.02` 和 `5.3.03`；本插件仅在其声明的服务和能力可用时公开每项面向宿主的功能。
- **接口模式：** `swing`。
- **插件依赖项：** 未声明。

## 安装与启用

此官方插件是一个**商店候选插件**，尚未发布为商店条目。在市场发布之前，请通过 Turboism 的官方发布包安装它，然后在**插件管理**中启用它。当不再需要该工作流时，可在同一窗口中禁用或卸载它。

## 使用方法

1. 恰好选择一个已具有参数绑定的受支持 ArtMesh 或变形器。
2. 打开其上下文菜单并选择批量传输命令。
3. 选择目标参数，在需要时选择反转，然后确认对话框以应用传输。

## 功能能力

| 声明的能力 | 对用户的作用 |
|---|---|
| `cubism.parameter.read` | 读取源绑定和可用目标参数。 |
| `cubism.parameter.write` | 写入经确认的绑定变更。 |
| `cubism.parameter.bindings.transfer` | 执行可选反转的类型化绑定传输。 |
| `ui.context-menu.contribute` | 将批量传输启动器添加到受支持的上下文。 |
| `ui.status.notify` | 报告前置条件和传输结果。 |

## 权限

| 权限 | 作用域 | 请求原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 为传输会话读取选定对象的参数绑定和模型参数列表。 |
| `turboism.cubism.model.write` | `application` | 通过类型化 Editor 创作 API 应用经确认的参数绑定传输。 |
| `turboism.action.register` | `application` | 注册位于上下文菜单条目之后的批量传输打开操作。 |
| `turboism.ui.context-menu.contribute` | `application` | 在变形器标签、部件标签和工作区对象上下文菜单中公开批量传输条目。 |
| `turboism.ui.status.notify` | `application` | 通知传输结果和前置条件跳过。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

不持久化插件数据。它读取选定对象的绑定状态，并写入经确认的目标绑定。

### 遥测

本插件不会发送遥测数据。

插件生命周期和失败记录可能会出现在 Turboism 的会话日志和 Cubism 的宿主日志中，并附带插件 ID。

## 状态与限制

- **状态：** 预览。
- 需要恰好一个受支持的源对象和至少一个现有源绑定。
- 经确认的目标行会逐项传输；本插件不声称提供一个合并的撤销组。
- 模态 Swing 对话框在无头 JVM 中不可用。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| 缺少上下文命令 | 在变形器、部件或工作区对象上下文中选择一个受支持对象。 |
| 对话框没有源绑定 | 选择一个具有现有参数绑定的对象。 |
| 某项传输被跳过 | 检查目标有效性、选择状态，以及状态通知中被拒绝行的内容。 |

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** 项目许可证
- **插件 ID：** `dev.turboism.plugin.parameter-batch-transfer`
