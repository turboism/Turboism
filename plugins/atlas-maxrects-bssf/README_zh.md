---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: texture-atlas, packing, auto-layout
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# MaxRects-BSSF 布局算法

> **Turboism 官方插件** · **状态：预览版**

为 Cubism 的纹理图集工作流程添加 MaxRects-BSSF 自动布局算法。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.texture-atlas` |
| 类别 | `modeling` |
| 标签 | texture-atlas, packing, auto-layout |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面 | `none` |
| 许可证 | 项目许可证 |

## 功能概述

- 向纹理图集编辑器注册 MaxRects-BSSF 和原生布局选项。
- 在可选的并行搜索下规划有边界的图集布局，然后通过编辑器创作 API 应用经验证的完整方案。
- 持久保存所选布局模式、算法和并行搜索偏好设置。

## 要求与兼容性

- **Turboism API：**`[0.1.0,0.2.0)`。
- **Cubism：**需要 Cubism。Turboism 当前仅接受经审查的精确编辑器构件 `5.2.03`、`5.3.02` 和 `5.3.03`；本插件仅在其声明的服务和能力可用时公开相应的面向主机功能。
- **界面模式：**`none`。
- **插件依赖项：**未声明。

## 安装与启用

此官方插件是一个**商店候选项**，尚未作为已发布的商店条目提供。在市场发布前，请通过 Turboism 的官方发布包安装它，然后在**插件管理**中启用它。当不需要该工作流程时，可在同一窗口中将其禁用或卸载。

## 使用方法

1. 打开 Cubism 的纹理图集编辑器并选择自动布局工作流程。
2. 选择 MaxRects-BSSF 或原生算法，并选择是否启用并行搜索。
3. 运行自动布局；插件会在应用前验证完整方案。

## 功能能力

| 声明的能力 | 对用户的影响 |
|---|---|
| `cubism.texture-atlas.layout` | 在经审查的编辑器服务可用时，注册并应用自动纹理图集布局算法。 |

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 读取完整的活动纹理图集创作快照。 |
| `turboism.cubism.model.write` | `application` | 通过编辑器创作状态应用经验证的完整纹理图集布局方案。 |
| `turboism.config.plugin.read` | `application` | 恢复所选的自动纹理图集布局模式。 |
| `turboism.config.plugin.write` | `application` | 持久保存所选的自动纹理图集布局模式。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

将布局设置存储在 `texture-atlas/layout.cfg` 的插件配置中。仅在工作流程运行时读取和写入活动纹理图集创作状态。

### 遥测

本插件不会发送遥测数据。

插件生命周期和故障记录可显示在 Turboism 的会话日志和 Cubism 的主机日志中，并附有插件 ID。

## 状态与限制

- **状态：**预览版。
- 需要活动的纹理图集编辑器会话以及经审查的模型读/写服务。
- 规划或应用失败时会进行报告，且不会应用部分布局；原生算法仍可作为回退选项。

## 故障排除

| 症状 | 检查项 |
|---|---|
| 缺少自动布局选项 | 确认插件已启用，且当前主机公开了纹理图集布局能力。 |
| 布局未应用 | 检查 Turboism 日志中是否有打包或验证失败，并确认纹理图集会话处于活动状态。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.texture-atlas`
