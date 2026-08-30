---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.historypanel
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: history, navigation, floating-window
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: false
interface: embedded
---

# 历史记录面板插件

> **Turboism 官方插件** · **状态：预览版**

把当前文档的原生撤销历史投影到浮动历史记录面板，并通过右侧垂直工具栏按钮切换显示；支持绑定快照的撤销/重做导航。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.historypanel` |
| 类别 | `workflow` |
| 标签 | history, navigation, floating-window |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 否 |
| 界面 | `embedded` |
| 许可证 | 项目许可证 |

## 功能概述

- 在 Cubism 主窗口右侧垂直工具栏添加本地化的历史记录按钮。
- 显示当前文档的原生撤销记录，并区分已应用和已撤销项目。
- 通常每秒检查历史记录 generation/revision；点击记录时使用类型化撤销/重做 API 移动游标。

## 要求与兼容性

- **Turboism API：**`[0.1.0,0.2.0)`。
- **Cubism：**插件初始化本身不要求 Cubism；显示和操作记录需要受支持精确版本提供原生历史服务和宿主界面集成。
- **界面模式：**`embedded`。
- **插件依赖项：**未声明。

## 安装与启用

本插件包含在 Turboism Full 发布包中。安装 Full 包后，可在**插件管理**中启用或禁用。原生历史操作仍取决于当前精确 Cubism 版本和活动文档是否提供面板显示的能力。

## 使用方法

1. 安装 Turboism Full 包，并在**插件管理**中启用本插件。
2. 点击右侧垂直工具栏的历史记录按钮打开浮动面板。
3. 查看当前原生撤销历史；取消勾选已应用记录可撤销到该位置，重新勾选已撤销记录可向前重做。
4. 再次点击历史记录按钮关闭面板。

## 功能能力

| 已声明能力 | 用户效果 |
|---|---|
| `cubism.editor-history.read` | 读取当前文档的原生撤销历史快照。 |
| `cubism.editor-history.move` | 请求在原生撤销历史记录之间移动。 |
| `ui.embedded-panel.contribute` | 提供历史记录面板。 |
| `ui.status.notify` | 提供历史记录状态通知。 |

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 读取当前文档的原生撤销历史快照。 |
| `turboism.cubism.model.write` | `application` | 从历史记录操作移动原生撤销游标。 |
| `turboism.ui.panel.contribute` | `application` | 提供历史记录嵌入式面板。 |
| `turboism.ui.toolbar.main.contribute` | `application` | 添加垂直历史记录工具栏按钮。 |
| `turboism.ui.status.notify` | `application` | 提供历史快照与移动尝试通知。 |
| `turboism.action.register` | `application` | 注册面板切换和逐项历史操作。 |

## 隐私与数据

### 网络

本插件不进行网络连接。

### 本地数据

不持久化插件数据。插件只读取原生历史快照，并在内存中保存面板状态、操作注册和可选轮询任务。

### 遥测

本插件不会发送遥测数据。

插件生命周期、刷新、轮询和安全失败记录可能显示在 Turboism 会话日志及 Cubism 主机日志中，并附有插件 ID。

## 状态与限制

- **状态：**预览发布插件。
- 原生历史不可用时，面板显示本地化的不可用状态。
- 任务调度不可用时不进行轮询，但首次刷新的面板仍可使用。
- 面板刷新失败会记录日志并在下一次轮询重试；关闭失败会安全地从切换状态中移除。
- 历史读取和移动取决于经过审查的宿主集成及声明权限。

## 故障排除

| 症状 | 检查项 |
|---|---|
| 缺少历史记录按钮 | 确认本插件已启用，且垂直主工具栏贡献可用。 |
| 面板显示历史记录不可用 | 打开活动文档，并确认原生历史服务可用。 |
| 面板不刷新 | 检查任务调度器；无调度器时仅显示初始快照。 |
| 点击记录没有效果 | 确认快照可用、具备模型写入权限，并且原生撤销/重做集成可用。 |
| 面板无法正常关闭 | 检查插件日志中的宿主浮动窗口销毁诊断。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.historypanel`
