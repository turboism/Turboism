---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.uitheme
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: appearance
tags: theme, colors, user-interface
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: false
interface: none
---

# UI 主题插件

> **Turboism 官方插件** · **状态：预览**

管理内置和用户主题包，并将经过审核的语义外观变更应用于 Cubism。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.uitheme` |
| 类别 | `appearance` |
| 标签 | theme, colors, user-interface |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 否 |
| 界面 | `none` |
| 许可证 | 项目许可证 |

## 功能概述

- 添加主题状态、管理器、应用内置主题、导入、导出、编辑、删除和恢复原生主题工作流。
- 验证受限的 ZIP 主题包，并将用户包以原子方式存储在插件数据中。
- 仅在成功应用语义外观后持久化所选主题，并在禁用时恢复受其控制的外观。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：**本插件可在没有 Cubism 的情况下初始化。将主题应用到 Cubism 仍需要精确审核过的 Editor 构件（`5.2.03` 或 `5.3.02`）以及语义外观服务。
- **界面模式：**`none`。
- **插件依赖：**未声明。

## 安装与启用

此官方插件是一个**商店候选项**，尚未作为商店列表发布。在市场发布前，请通过 Turboism 的官方发行包安装它，然后在**插件管理**中启用它。若不再需要此工作流，请在同一窗口中禁用或卸载它。

## 使用方法

1. 打开 **Turboism → Theme Manager** 或所提供的工作区上下文菜单命令。
2. 选择内置主题、创建或编辑用户主题，或导入经过验证的主题 ZIP。
3. 应用选择；使用原生主题选项恢复 Cubism 的原始外观。需要时，可从管理器导出或删除用户包。

## 功能能力

插件清单中未声明任何功能能力。

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.action.register` | `application` | 注册主题管理器、包导入/导出、删除和状态操作。 |
| `turboism.ui.menu.contribute` | `application` | 在 Turboism 顶级菜单下添加主题管理命令。 |
| `turboism.config.plugin.read` | `application` | 从插件自有的类型化配置中读取所选主题包。 |
| `turboism.config.plugin.write` | `application` | 在成功应用主机外观后持久化所选主题包。 |
| `turboism.ui.context-menu.contribute` | `application` | 添加主题管理上下文菜单项。 |
| `turboism.cubism.project.read` | `application` | 通过以项目为范围的 Cubism 读取功能能力读取 SDK 主题状态快照。 |
| `turboism.ui.dialog.contribute` | `application` | 显示统一主题选择窗口和受限包工作流对话框。 |
| `turboism.ui.file-chooser.request` | `application` | 请求用于导入和导出的不透明 ZIP 主题包句柄。 |
| `turboism.file.read` | `application` | 从插件存储和已授予的导入句柄读取受限的主题归档。 |
| `turboism.file.write` | `application` | 以原子方式存储、删除和导出受限的主题归档。 |
| `turboism.ui.status.notify` | `application` | 显示主题包状态、导入进度和外观应用结果。 |
| `turboism.ui.appearance.modify` | `application` | 通过语义外观服务应用或恢复经过审核的内置主题。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

将所选主题存储在位于 `ui-theme/selection.cfg` 的插件配置中。用户包是存储在插件数据中的受限 ZIP 归档，路径为 `themes/<theme-id>.zip`；导入/导出使用用户批准的不透明文件句柄。捆绑主题从插件的 `themes/` 资源中读取。

### 遥测

本插件不会发送遥测数据。

带有插件 ID 的插件生命周期和故障记录可能会出现在 Turboism 的会话日志及 Cubism 的主机日志中。

## 状态与限制

- **状态：**预览。
- 主题归档、ID、条目数、条目大小和总大小均受到限制并经过验证；无效包不可用。
- 应用 Cubism 外观需要经过审核的语义外观服务。管理器可在没有 Cubism 的情况下存在，但在不受支持时，主机外观变更会以闭合失败方式处理。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| 无法应用主题 | 检查当前主机是否公开了经过审核的外观服务，并查看状态通知。 |
| 导入的包被拒绝 | 验证它是受限且有效的 Turboism 主题 ZIP，具有有效主题 ID 和受支持的条目。 |
| 重启后主题缺失 | 检查 `ui-theme/selection.cfg`，并确认所选用户包仍存在于插件数据中。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.uitheme`
