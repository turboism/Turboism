---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-stats
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: analysis
tags: texture-atlas, metrics, inspection
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# 纹理图集统计

> **Turboism 官方插件** · **状态：预览**

在 Cubism 的原生纹理图集编辑器中显示模型图像总数和当前纹理图像数量。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.texture-atlas-stats` |
| 类别 | `analysis` |
| 标签 | texture-atlas, metrics, inspection |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面 | `swing` |
| 许可证 | 项目许可证 |

## 功能概述

- 将一行本地化统计信息附加到活动纹理图集编辑器 UI。
- 每秒刷新一次模型图像总数和选定纹理的图像数量。
- 显示不可用状态，而非将读取失败传播至主机 UI。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：**需要 Cubism。Turboism 当前允许精确审核过的 Editor 构件 `5.2.03`、`5.3.02` 和 `5.3.03`；本插件仅在其声明的服务和功能能力可用时公开各项面向主机的功能。
- **界面模式：**`swing`。
- **插件依赖：**未声明。

## 安装与启用

此官方插件是一个**商店候选项**，尚未作为商店列表发布。在市场发布前，请通过 Turboism 的官方发行包安装它，然后在**插件管理**中启用它。若不再需要此工作流，请在同一窗口中禁用或卸载它。

## 使用方法

1. 打开模型并启动 Cubism 的纹理图集编辑器。
2. 找到添加到原生编辑器窗口的统计信息行。
3. 切换所选纹理以更新当前纹理的数量；总数每秒刷新一次。

## 功能能力

| 声明的功能能力 | 对用户的影响 |
|---|---|
| `cubism.texture-atlas.statistics` | 从活动纹理图集编辑器会话读取模型图像数量。 |

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 从活动纹理图集编辑器会话读取模型图像数量。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

不持久化数据。它仅读取活动纹理图集会话摘要，并更新内存中的 UI 标签。

### 遥测

本插件不会发送遥测数据。

带有插件 ID 的插件生命周期和故障记录可能会出现在 Turboism 的会话日志及 Cubism 的主机日志中。

## 状态与限制

- **状态：**预览。
- 需要经过审核的纹理图集会话和编辑器 UI 服务。
- 本插件为只读。当编辑器或服务不可用时，该行会缺失或显示本地化的不可用状态。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| 缺少统计信息行 | 打开原生纹理图集编辑器，并确认插件已在审核过的主机上启用。 |
| 计数始终为零 | 确认模型和纹理处于活动状态，然后等待下一次每秒刷新。 |
| 显示不可用文本 | 检查 Turboism 日志中是否存在纹理图集会话读取失败。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.texture-atlas-stats`
