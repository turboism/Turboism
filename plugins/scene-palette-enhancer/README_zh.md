---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.scene-palette-enhancer
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: appearance
tags: scene, palette, enhancement
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# 场景调色板增强器

> **Turboism 官方插件** · **状态：预览**

为 Cubism 的 Scene 调色板添加自然排序和持久化的手动行排序。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.scene-palette-enhancer` |
| 类别 | `appearance` |
| 标签 | scene, palette, enhancement |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面 | `none` |
| 许可证 | 项目许可证 |

## 功能概述

- 使 Scene 调色板的排序在升序、降序和手动顺序间循环切换。
- 支持手动拖动行，并显示标题排序标记。
- 按不透明的场景范围持久化手动顺序，并将其与新发现的实时行合并。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：**需要 Cubism。Turboism 当前允许精确审核过的 Editor 构件 `5.2.03` 和 `5.3.02`；本插件仅在其声明的服务和功能能力可用时公开各项面向主机的功能。
- **界面模式：**`none`。
- **插件依赖：**未声明。

## 安装与启用

此官方插件是一个**商店候选项**，尚未作为商店列表发布。在市场发布前，请通过 Turboism 的官方发行包安装它，然后在**插件管理**中启用它。若不再需要此工作流，请在同一窗口中禁用或卸载它。

## 使用方法

1. 打开 Cubism 的 Scene 调色板，并使用列标题循环切换排序模式。
2. 切换到手动顺序，并将行拖动到所需的序列。
3. 返回相同的场景范围，以恢复持久化的手动顺序。

## 功能能力

插件清单中未声明任何功能能力。

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.file.read` | `application` | 恢复按项目保存的 Scene 调色板手动顺序。 |
| `turboism.file.write` | `application` | 持久化按项目保存的 Scene 调色板手动顺序。 |
| `turboism.event.subscribe` | `application` | 注册生成的注解订阅者。 |
| `turboism.ui.scene-table.observe` | `application` | 观察由 Runtime 拥有的 Scene 调色板快照、标题点击和重新排序变化。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

以原子方式将换行分隔的项目 ID 存储在名为 `manual-order-<scopeId>.txt` 的插件状态文件中。范围 ID 必须是一个不透明的 64 字符小写十六进制值；本插件不存储项目路径。

### 遥测

本插件不会发送遥测数据。

带有插件 ID 的插件生命周期和故障记录可能会出现在 Turboism 的会话日志及 Cubism 的主机日志中。

## 状态与限制

- **状态：**预览。
- 需要受支持的 Scene 表格服务。如果存储不可用，排序仍可继续，但手动顺序无法持久化。
- 存储的 ID 会与当前行进行协调：缺失的行会被移除，新行会被追加。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| 排序控件不存在 | 确认插件已启用，且当前主机公开了 Scene 表格服务。 |
| 未恢复手动顺序 | 确认相同的场景范围处于活动状态，并检查状态存储诊断信息。 |
| 新行出现在末尾 | 当存储的手动顺序与新创建的场景项目合并时，这是预期行为。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.scene-palette-enhancer`
