---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.physics-editor
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: physics, editing, simulation
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# 物理编辑器

> **Turboism 官方插件** · **状态：预览**

为 Cubism 的 Physics Settings 组列表新增全选行为和重新打开时的状态保留功能。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.physics-editor` |
| 类别 | `modeling` |
| 标签 | physics, editing, simulation |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面 | `swing` |
| 许可证 | 项目许可证 |

## 功能概述

- 提供经审核的 Physics Settings 策略，并启用组全选。
- 请求在重新打开 Physics Settings 窗口时保留组选择状态。
- 禁用时干净地关闭该贡献。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：**需要 Cubism。Turboism 当前允许精确审核过的 Editor 构件 `5.2.03`、`5.3.02` 和 `5.3.03`；本插件仅在其声明的服务和功能能力可用时公开各项面向主机的功能。
- **界面模式：**`swing`。
- **插件依赖：**未声明。

## 安装与启用

此官方插件是一个**商店候选项**，尚未作为商店列表发布。在市场发布前，请通过 Turboism 的官方发行包安装它，然后在**插件管理**中启用它。若不再需要此工作流，请在同一窗口中禁用或卸载它。

## 使用方法

1. 启用插件后，打开 Cubism 的 Physics Settings 编辑器。
2. 使用组列表标题的全选行为启用或禁用所有组。
3. 关闭并重新打开编辑器；所提供的保留策略会保留受支持的选择状态。

## 功能能力

插件清单中未声明任何功能能力。

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.cubism.model.write` | `application` | 通过已验证的 Editor 事务路径以原子方式启用或禁用 Physics Settings 组。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

不写入插件自有文件。状态保留由经过审核的 Physics Editor 主机服务处理。

### 遥测

本插件不会发送遥测数据。

带有插件 ID 的插件生命周期和故障记录可能会出现在 Turboism 的会话日志及 Cubism 的主机日志中。

## 状态与限制

- **状态：**预览。
- 需要活动 Cubism 主机中受支持的 Physics Editor 贡献服务。
- 本插件有意仅限于全选和重新打开时的状态保留；它不替代 Cubism 的物理编辑器。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| 缺少全选功能 | 确认在打开 Physics Settings 前已启用插件，并检查主机服务是否可用。 |
| 未保留选择状态 | 检查插件日志中是否存在贡献附加失败，并在插件处于活动状态后重新打开编辑器。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.physics-editor`
