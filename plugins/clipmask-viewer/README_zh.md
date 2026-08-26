---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.clipmask-viewer
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: analysis
tags: clip-mask, viewer, graph
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# 裁剪蒙版查看器

> **Turboism 官方插件** · **状态：预览版**

检查裁剪蒙版关系、重复项、顺序冲突以及相关 ArtMesh，而不修改模型。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.clipmask-viewer` |
| 类别 | `analysis` |
| 标签 | clip-mask, viewer, graph |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面 | `swing` |
| 许可证 | 项目许可证 |

## 功能概述

- 在 Turboism 面板中添加一个区段，并添加用于打开查看器的 Turboism 菜单操作。
- 提供图形、以蒙版为主的表格和以用户为主的表格视图，支持筛选、缩放和刷新。
- 高亮显示编辑器选择内容，并可将所选 GUID 复制到系统剪贴板。

## 要求与兼容性

- **Turboism API：**`[0.1.0,0.2.0)`。
- **Cubism：**需要 Cubism。Turboism 当前仅接受经审查的精确编辑器构件 `5.2.03` 和 `5.3.02`；本插件仅在其声明的服务和能力可用时公开相应的面向主机功能。
- **界面模式：**`swing`。
- **插件依赖项：**未声明。

## 安装与启用

此官方插件是一个**商店候选项**，尚未作为已发布的商店条目提供。在市场发布前，请通过 Turboism 的官方发布包安装它，然后在**插件管理**中启用它。当不需要该工作流程时，可在同一窗口中将其禁用或卸载。

## 使用方法

1. 从 Turboism 面板区段或 Turboism 菜单打开裁剪蒙版查看器。
2. 选择图形或表格视图，然后使用筛选和无关节点切换开关缩小结果范围。
3. 选择某一行或节点以检查其关系；需要 GUID 时使用复制操作。

## 功能能力

| 声明的能力 | 对用户的影响 |
|---|---|
| `cubism.clipmask.read` | 读取裁剪蒙版和 ArtMesh 关系快照。 |
| `ui.embedded-panel.contribute` | 将启动器区段添加到 Turboism 面板。 |
| `ui.menu.contribute` | 将查看器命令添加到 Turboism 菜单。 |
| `ui.status.notify` | 通过主机状态通知报告剪贴板和查看器结果。 |

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 读取裁剪蒙版和 ArtMesh 快照，以及用于重复项检查器和查看器的编辑器选择内容。 |
| `turboism.ui.panel.contribute` | `application` | 将裁剪蒙版查看器的可折叠区段注入 Turboism 面板。 |
| `turboism.action.register` | `application` | 注册 Turboism 选项卡按钮和菜单项背后的 clipmask-viewer.open.viewer 操作。 |
| `turboism.ui.menu.contribute` | `application` | 通过 Turboism 菜单公开裁剪蒙版重复项检查器。 |
| `turboism.ui.status.notify` | `application` | 通知 GUID 复制结果。 |
| `turboism.event.subscribe` | `application` | 注册生成的选择观察订阅者。 |
| `turboism.cubism.selection.observe` | `application` | 将已打开的查看器与拉取检测到的选择变化同步。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

不持久保存模型数据或插件设置。用户请求的复制操作会将所选 GUID 文本写入系统剪贴板。

### 遥测

本插件不会发送遥测数据。

插件生命周期和故障记录可显示在 Turboism 的会话日志和 Cubism 的主机日志中，并附有插件 ID。

## 状态与限制

- **状态：**预览版。
- 只读：不会更改裁剪蒙版分配或模型对象。
- 需要经审查的裁剪蒙版和编辑器选择读取服务；在无头 JVM 中，Swing 查看器不可用。

## 故障排除

| 症状 | 检查项 |
|---|---|
| 缺少查看器操作 | 确认插件已启用，且面板/菜单贡献服务可用。 |
| 查看器为空 | 打开包含 ArtMesh 和裁剪蒙版关系的模型，然后刷新。 |
| 窗口未打开 | 检查日志中是否存在无头环境或不可用的裁剪蒙版读取能力。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.clipmask-viewer`
