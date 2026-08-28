---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.cubism-tab-filter
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: tab-filter, workspace
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# Cubism 选项卡筛选器

> **Turboism 官方插件** · **状态：预览版**

向 Cubism 的参数、变形器、场景和日志调色板选项卡添加关键字筛选框。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.cubism-tab-filter` |
| 类别 | `workflow` |
| 标签 | tab-filter, workspace |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面 | `none` |
| 许可证 | 项目许可证 |

## 功能概述

- 为常用的 Cubism 调色板声明四个本地化筛选框贡献。
- 让运行时负责原生小部件附加和行筛选，而不是直接操作主机小部件。
- 插件被禁用时移除每一项贡献。

## 要求与兼容性

- **Turboism API：**`[0.1.0,0.2.0)`。
- **Cubism：**需要 Cubism。Turboism 当前仅接受经审查的精确编辑器构件 `5.2.03`、`5.3.02` 和 `5.3.03`；本插件仅在其声明的服务和能力可用时公开相应的面向主机功能。
- **界面模式：**`none`。
- **插件依赖项：**未声明。

## 安装与启用

此官方插件是一个**商店候选项**，尚未作为已发布的商店条目提供。在市场发布前，请通过 Turboism 的官方发布包安装它，然后在**插件管理**中启用它。当不需要该工作流程时，可在同一窗口中将其禁用或卸载。

## 使用方法

1. 打开参数、变形器、场景或日志调色板选项卡。
2. 在添加到该选项卡的筛选框中输入关键字。
3. 清空字段以恢复未筛选的行集合。

## 功能能力

插件清单中未声明任何能力。

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.ui.toolbar.palette.contribute` | `application` | 向调色板选项卡工具栏添加关键字筛选框。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

不持久保存筛选文本或其他插件数据。

### 遥测

本插件不会发送遥测数据。

插件生命周期和故障记录可显示在 Turboism 的会话日志和 Cubism 的主机日志中，并附有插件 ID。

## 状态与限制

- **状态：**预览版。
- 需要运行时调色板筛选器注册表和受支持的调色板表面。
- 如果注册表不可用，插件会记录警告且不安装任何筛选框。

## 故障排除

| 症状 | 检查项 |
|---|---|
| 缺少筛选框 | 确认插件已启用，且当前调色板是参数、变形器、场景或日志。 |
| 行未发生变化 | 清空后重新输入关键字，然后检查日志中是否有调色板筛选器附加警告。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.cubism-tab-filter`
