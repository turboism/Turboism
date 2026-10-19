---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.command-palette
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: launcher, commands, shortcut
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# 命令面板

> **Turboism 官方插件** · **状态：预览版**

通过 **Ctrl+K** 打开命令启动面板，支持按标识符或本地化名称搜索并执行受支持的 Cubism Editor 菜单命令及其他插件注册的操作。

| 项目 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.command-palette` |
| 分类 | `workflow` |
| 标签 | launcher, commands, shortcut |
| 需要 Cubism | 是 |
| 界面模式 | `swing` |
| 许可证 | Project License |

## 功能概述

- 通过 **Ctrl+K** 打开命令面板（也可从 **Turboism → 命令面板** 菜单打开）。
- 按显示名称顺序列出宿主当前允许的全部 Editor 命令，以及各已启用插件注册的操作。
- 输入时按子序列实时过滤：可同时匹配本地化显示名称与稳定标识符（例如 `new.model` 或 `perf-stats.window.show`）。
- 在结果行中以绿色高亮匹配字符，其余文本保持常规前景色。
- 为首条匹配项显示灰色内联补全提示，按 **Tab** 可接受补全。
- 按 **Enter** 执行选中命令或操作并关闭面板；即使下拉列表为空，输入完整 ID 后按 **Enter** 也可直接执行。
- 使用 **Up/Down** 切换选中项，按 **Esc** 或点击面板外部关闭而不执行任何操作。

## 要求与兼容性

- **SDK API 范围：** `[0.67.0,1.0.0)`。
- **Cubism：** 需要 Cubism。支持经精确审核的 Editor 版本（如 `5.2.03`、`5.3.02` 与 `5.3.03`）；仅当声明的服务与能力可用时暴露对应宿主功能。
- **界面模式：** `swing`。
- **插件依赖：** 无。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 5.1.00–5.3.04（更早版本 Cubism 本身无此功能）.
- 顶部菜单集成 不适用于 5.0.00–5.0.06（更早版本 Cubism 本身无此功能）
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

随 Turboism 正式发行包捆绑提供。可在安装器中勾选或在 **插件管理** 中启用与禁用。

## 使用方法

1. 按 **Ctrl+K**（或选择 **Turboism → 命令面板**）。
2. 输入命令名称或 ID 的一部分，例如 `undo`、`new model` 或 `new.model`。
3. 按 **Tab** 接受灰色补全提示，或按 **Up/Down** 选择条目。
4. 按 **Enter** 运行命令，按 **Esc** 关闭面板。

## 功能能力

| 声明能力 | 用户效果 |
|---|---|
| `cubism.editor-commands.execute` | 通过宿主菜单系统执行在面板中选择的 Editor 命令。 |
| `action.catalog.invoke` | 列出其他插件注册的操作并调用用户所选条目。 |

## 权限

| 声明权限 | 作用域 | 用户效果 |
|---|---|---|
| `turboism.action.register` | `application` | 注册打开命令面板的操作及其默认 Ctrl+K 快捷键。 |
| `turboism.action.invoke` | `application` | 将其他插件注册的操作枚举为面板条目并调用用户选择的项。 |
| `turboism.ui.menu.contribute` | `application` | 在 Turboism 顶级菜单中添加命令面板入口。 |
| `turboism.cubism.model.read` | `application` | 执行在面板中选择的只读级 Editor 命令。 |
| `turboism.cubism.model.write` | `application` | 执行在面板中选择的写入级 Editor 命令。 |
| `turboism.file.write` | `application` | 允许从面板启动保存（SAVE）等 Editor 命令。 |
| `turboism.network.fetch` | `application` | 允许从面板启动打开 Live2D 网页的 Editor 命令。 |
| `turboism.process.run` | `application` | 允许从面板启动打开本地文件夹的 Editor 命令。 |

## 隐私与数据

### 网络

插件自身不发起网络连接。打开 Live2D 网页的命令会将地址交给宿主 Editor 执行。

### 本地数据

不持久化任何数据。查询词与选中状态仅在面板窗口打开期间保留在内存中。

### 遥测

本插件不发送任何遥测数据。

插件生命周期与错误记录可能会附带插件 ID 写入 Turboism 会话日志和 Cubism 宿主日志。

## 状态与限制

- **状态：** 预览版。
- 面板列出 `EditorCommandService` 暴露的无参 Editor 命令以及 `ActionCatalogService` 暴露的插件操作；不含带文件路径或参数化的命令。
- 列表中仅显示宿主报告当前可用且本插件具备对应权限的命令；插件操作在其所属插件启用期间显示。面板自身操作与内部 `turboism.core` 操作默认排除。
- 用户自定义的 Ctrl+K 快捷键绑定优先于插件默认快捷键。

## 故障排除

| 现象 | 检查方法 |
|---|---|
| 按 Ctrl+K 无反应 | 确认插件已启用，且没有其他用户快捷键覆盖 Ctrl+K。 |
| 某条命令未出现在列表中 | 宿主在当前状态下报告该命令不可用，或本插件缺少该命令所需的权限。 |
| 某条插件操作未出现在列表中 | 所属插件已禁用或未注册操作；内部 `turboism.core` 操作默认隐藏。 |
| 按 Enter 后命令或操作未执行 | 查看宿主日志；当执行被拒绝或失败时会记录结果状态。 |

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** Project License
- **插件 ID：** `dev.turboism.plugin.command-palette`
