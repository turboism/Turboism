---
turboismReadmeSchema: 1
pluginId: turboism.core
version: 0.1.0
kind: core
status: built-in
delivery: bundled
category: system
tags: plugin-management, settings, diagnostics
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: false
interface: none
---

# Turboism 核心

> **Turboism 官方插件** · **状态：内置**

提供 Turboism 的内置菜单、主页工具栏入口、设置、日志、关于窗口、面板和插件管理器。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `turboism.core` |
| 类别 | `system` |
| 标签 | plugin-management, settings, diagnostics |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 否 |
| 界面 | `none` |
| 许可证 | 项目许可证 |

## 功能概述

- 拥有不可移除的 Turboism 主页入口，以及顶级的设置、插件管理、日志和关于命令。
- 发布主 Turboism 嵌入式面板及其浮动/停靠上下文操作。
- 管理非核心插件的安装、启用/禁用状态和卸载请求。

## 要求与兼容性

- **Turboism API：**`[0.1.0,0.2.0)`。
- **Cubism：**插件可在没有 Cubism 项目的情况下初始化，但仅当运行时具有可用的 UI 主机时才显示主机 UI 贡献。
- **界面模式：**`none`。
- **插件依赖项：**未声明。

## 安装与启用

Turboism 核心随运行时捆绑。它会自动初始化，且不能单独安装、禁用或移除。

## 使用方法

1. 使用 Turboism 主页工具栏入口或 **Turboism** 菜单打开内置窗口。
2. 打开**插件管理**以检查、启用、禁用、安装或计划移除非核心插件。
3. 打开**设置**或**日志**以配置运行时并检查诊断信息。

## 功能能力

插件清单中未声明任何能力。

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.action.register` | `application` | 注册主工具栏主页入口操作。 |
| `turboism.ui.toolbar.main.contribute` | `application` | 将主页入口按钮添加到主工具栏。 |
| `turboism.ui.panel.contribute` | `application` | 发布并激活 Turboism 嵌入式面板。 |
| `turboism.ui.settings.contribute` | `application` | 向共享的性能设置选项卡贡献由核心拥有的 Cubism JVM 选择器。 |
| `turboism.ui.context-menu.contribute` | `application` | 贡献内置面板选项卡的浮动和停靠菜单操作。 |
| `turboism.ui.menu.contribute` | `application` | 将设置和插件管理条目添加到 Turboism 顶级菜单。 |
| `turboism.ui.dialog.contribute` | `application` | 确认插件卸载请求。 |

## 隐私与数据

### 网络

核心插件本身不建立网络连接。

### 本地数据

在 Turboism 所有的存储中读取和写入运行时设置、插件管理状态、日志和文件选择器历史记录。已安装的插件包会通过运行时经验证的包管理边界处理。

### 遥测

本插件不会发送遥测数据。

插件生命周期和故障记录可显示在 Turboism 的会话日志和 Cubism 的主机日志中，并附有插件 ID。

## 状态与限制

- **状态：**内置。
- 内置、捆绑且不可移除。运行时策略会拒绝禁用或卸载核心的请求。
- 某些插件包更改会在下一次发现或重新加载周期生效；安全模式可能有意限制可选行为。

## 故障排除

| 症状 | 检查项 |
|---|---|
| 缺少 Turboism 入口 | 检查启动诊断信息，并确认内置核心已完成初始化。 |
| 某项插件更改处于待处理状态 | 当插件管理报告待处理的安装或卸载操作时，重启或重新加载 Turboism。 |
| 无法打开窗口 | 检查安全模式、主机 UI 可用性和 Turboism 会话日志。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`turboism.core`
