---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.palette-label-style
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: appearance
tags: palette, labels, typography
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# 调色板标签样式插件

> **Turboism 官方插件** · **状态：预览**

为变形器、部件和参数调色板的上下文菜单添加文本和背景颜色控件。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.palette-label-style` |
| 类别 | `appearance` |
| 标签 | palette, labels, typography |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 接口 | `embedded` |
| 许可证 | 项目许可证 |

## 功能概述

- 为受支持的调色板条目提供预设、清除和自定义颜色操作。
- 协调临时调色板外观覆盖与原生变形器标签颜色写入。
- 当相应的模型或项目变为活动状态时，重新应用按项目保存的颜色。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：** 需要 Cubism。Turboism 当前仅接受经过审查的精确 Editor 构件 `5.2.03` 和 `5.3.02`；本插件仅在其声明的服务和能力可用时公开每项面向宿主的功能。
- **接口模式：** `embedded`。
- **插件依赖项：** 未声明。

## 安装与启用

此官方插件是一个**商店候选插件**，尚未发布为商店条目。在市场发布之前，请通过 Turboism 的官方发布包安装它，然后在**插件管理**中启用它。当不再需要该工作流时，可在同一窗口中禁用或卸载它。

## 使用方法

1. 在受支持的变形器、部件或参数调色板中选择一个条目并打开其上下文菜单。
2. 选择文字颜色或背景颜色子菜单，然后选择预设、清除操作或自定义颜色。
3. 重新打开项目，以确认会为相同的项目和对象 ID 重新应用已持久化的颜色。

## 功能能力

| 声明的能力 | 对用户的作用 |
|---|---|
| `ui.context-menu.contribute` | 向受支持的调色板上下文菜单添加颜色子菜单。 |
| `ui.appearance.modify` | 应用临时的调色板文本/背景覆盖。 |
| `ui.dialog.contribute` | 打开自定义颜色表单。 |
| `cubism.model.read` | 解析选定对象和重新应用目标。 |
| `cubism.model.write` | 写入原生变形器标签颜色。 |
| `cubism.project.read` | 将持久化范围限定为活动项目。 |
| `file.read` | 读取按项目保存的颜色。 |
| `file.write` | 写入或清除按项目保存的颜色。 |

## 权限

| 权限 | 作用域 | 请求原因 |
|---|---|---|
| `turboism.action.register` | `application` | 注册从调色板上下文菜单调用的标签文字/背景颜色操作。 |
| `turboism.ui.context-menu.contribute` | `application` | 向变形器、部件和参数调色板上下文菜单贡献文字颜色和背景颜色子菜单。 |
| `turboism.ui.appearance.modify` | `application` | 覆盖调色板条目文字和背景颜色，并设置原生变形器标签颜色。 |
| `turboism.ui.dialog.contribute` | `application` | 打开自定义颜色表单对话框。 |
| `turboism.cubism.model.write` | `application` | 为变形器标签背景菜单写入原生变形器标签颜色。 |
| `turboism.cubism.model.read` | `application` | 解析选定调色板对象，并在模型打开时重新应用已持久化的颜色。 |
| `turboism.cubism.project.read` | `application` | 解析活动项目 ID，以便按项目持久化标签颜色。 |
| `turboism.file.read` | `application` | 读取已持久化的按项目标签颜色以供重新应用。 |
| `turboism.file.write` | `application` | 每次应用或清除后持久化按项目标签文字/背景颜色。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

以原子方式将按项目颜色记录存储在插件数据中，路径为 `palette-label-style/colors-<projectId>.properties`。记录包含项目/对象标识符、调色板系列、颜色目标和 `#RRGGBB` 值。

### 遥测

本插件不会发送遥测数据。

插件生命周期和失败记录可能会出现在 Turboism 的会话日志和 Cubism 的宿主日志中，并附带插件 ID。

## 状态与限制

- **状态：** 预览。
- 需要活动项目/模型以及受支持的调色板上下文菜单和外观服务。
- 无效的已持久化记录会被忽略。原生变形器颜色和临时调色板覆盖使用不同的宿主路径，可能具有不同的可用性。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| 缺少颜色子菜单 | 使用受支持的调色板条目，并确认上下文菜单和外观能力可用。 |
| 颜色未恢复 | 确认相同的项目和对象 ID 处于活动状态，并检查存储/读取诊断信息。 |
| 自定义颜色被拒绝 | 通过提供的对话框输入有效的六位 RGB 颜色。 |

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** 项目许可证
- **插件 ID：** `dev.turboism.plugin.palette-label-style`
