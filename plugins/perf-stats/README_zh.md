---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.perf-stats
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: performance
tags: metrics, diagnostics, fps
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# 性能统计

> **Turboism 官方插件** · **状态：预览**

显示实时 Cubism CPU、FPS、JVM 内存和垃圾回收统计信息。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.perf-stats` |
| 类别 | `performance` |
| 标签 | metrics, diagnostics, fps |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 接口 | `swing` |
| 许可证 | 项目许可证 |

## 功能概述

- 添加嵌入式“性能”面板和独立的“性能监视器”窗口。
- 每秒对共享运行时统计信息源采样一次，并保留有界的 120 点图表历史记录。
- 在 Cubism 状态区域显示简洁的 CPU 百分比。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：** 需要 Cubism。Turboism 当前仅接受经过审查的精确 Editor 构件 `5.2.03`、`5.3.02` 和 `5.3.03`；本插件仅在其声明的服务和能力可用时公开每项面向宿主的功能。
- **接口模式：** `swing`。
- **插件依赖项：** 未声明。

## 安装与启用

此官方插件是一个**商店候选插件**，尚未发布为商店条目。在市场发布之前，请通过 Turboism 的官方发布包安装它，然后在**插件管理**中启用它。当不再需要该工作流时，可在同一窗口中禁用或卸载它。

## 使用方法

1. 打开嵌入式“性能”面板，以获得始终可用的紧凑图表。
2. 使用 **Turboism → Performance Monitor** 打开独立窗口。
3. 观察视口 FPS、CPU、堆/非堆内存和垃圾回收暂停趋势。

## 功能能力

| 声明的能力 | 对用户的作用 |
|---|---|
| `performance.stats.read` | 读取本地 Cubism 进程和 JVM 性能样本。 |
| `ui.embedded-panel.contribute` | 将实时图表面板添加到 Cubism UI。 |

## 权限

| 权限 | 作用域 | 请求原因 |
|---|---|---|
| `turboism.performance.stats.read` | `application` | 读取 Cubism 进程 CPU、FPS 和 JVM 内存统计信息，以绘制实时图表。 |
| `turboism.ui.status.notify` | `application` | 在 Cubism 状态栏中显示常驻的紧凑 CPU 百分比标签。 |
| `turboism.ui.panel.contribute` | `application` | 将嵌入式性能图表面板添加到 Cubism 调色板区域。 |
| `turboism.action.register` | `application` | 注册性能监视器窗口操作。 |
| `turboism.ui.menu.contribute` | `application` | 将性能监视器条目添加到 Turboism 顶级菜单。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

不持久化样本。指标和图表历史记录保留在内存中，并随插件生命周期清除。

### 遥测

本插件不会发送遥测数据。

插件生命周期和失败记录可能会出现在 Turboism 的会话日志和 Cubism 的宿主日志中，并附带插件 ID。

## 状态与限制

- **状态：** 预览。
- 需要运行时性能统计服务以及受支持的面板/状态 UI 集成。
- 图表是诊断样本，不保证基准测试结果；不受支持的指标可能显示为不可用。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| 缺少面板或菜单 | 确认插件已启用，并且面板/菜单贡献服务可用。 |
| 图表没有数据显示 | 等待每秒采样器运行，并在日志中检查性能探针可用性。 |
| CPU 标签消失 | 检查状态通知可用性，以及插件是否已被禁用或重新加载。 |

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** 项目许可证
- **插件 ID：** `dev.turboism.plugin.perf-stats`
