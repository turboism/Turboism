---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.recent-project-board
version: 0.68.1
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: recent-files, preview, viewport, navigation
turboismApi: "[0.70.0,1.0.0)"
requiresCubism: true
interface: swing
---

# 最近工程看板

> **Turboism 官方插件** · **状态：预览**

在未打开任何 Cubism 工程时，用最近工程的缩略图填充空白建模视口，双击卡片即可打开对应工程。

| 详情 | 值 |
|---|---|
| 版本 | `0.68.1` |
| 插件 ID | `dev.turboism.plugin.recent-project-board` |
| 类别 | `workflow` |
| 标签 | recent-files, preview, viewport, navigation |
| 需要 Cubism | 是 |
| 界面模式 | `swing` |
| 许可证 | 项目许可证 |

## 功能介绍

- 在没有打开任何文档时，在原生 OpenGL 视口上展示最多 8 个最近工程。标题栏右侧的 Lucide 纯图标按钮可在 6 列网格、3 列网格和左对齐详细信息列表之间切换，并记住选择；悬停可查看当前布局。3 列与详细信息模式文字适当放大。
- 使用官方 `recent-preview` 插件的公开 `thumbnail.captured` 事件获取缩略图，并接收其启动后的有界磁盘缓存重放。
- 在打开任何文档时立即隐藏看板，最后一个文档关闭后自动恢复。
- 标题“最近的文件 / 双击工程即可打开”垂直居中并靠左，右侧为布局切换图标。
- 详细信息列表在左侧显示小缩略图，右侧垂直居中左对齐显示工程名称与时间，类似资源管理器的“详细信息”。
- 按显示名称对重复的最近菜单项去重，有缩略图预览的工程排在前面。
- 卡片名称和修改时间居中；暂无预览时在预览区内水平和垂直居中显示“暂无预览”。
- 无最近文件时，居中显示“最近没有工程”，并提供蓝色的**创建新工程**与**创建新动画工程**链接。前者调用宿主类型化 `NEW_MODEL` 命令，后者打开 Cubism 原生的“动画的目标版本选择”对话框。
- 通过宿主自身的 Recent Files 动作打开工程，不向插件暴露任何文件路径或文件授权。

## 要求与兼容性

- **SDK API 范围：** `[0.70.0,1.0.0)`。
- **Cubism：** 需要 Cubism。Turboism 目前只接纳已审核的精确版本 `5.0.00`–`5.3.04`；只有在声明的服务和能力可用时才会启用对应功能。
- **界面模式：** `swing`。
- **插件依赖：** `dev.turboism.plugin.recent-preview`（必需，排在提供方之后加载）。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

本官方插件随 Turboism 一起分发，并可通过**插件市场**安装。可在**插件管理**中启用、禁用或卸载。

## 使用方法

1. 同时启用 **Recent Preview** 与 **Recent Project Board**。
2. 打开一次工程，以便 Recent Preview 生成缩略图。
3. 关闭工程，看板即可在空白视口中显示。
4. 使用标题栏右侧的布局图标在 6 列网格、3 列网格和详细信息列表之间切换，选择会持久保存。
5. 双击卡片即可重新打开工程。
6. 没有最近工程时，使用居中的**创建新工程**链接创建新模型，或使用**创建新动画工程**打开 Cubism 原生动画目标选择弹窗。

## 权限

| 权限 | 作用域 | 申请原因 |
|---|---|---|
| `turboism.cubism.recent-file.read` | `application` | 列出最近工程并派发打开选中的工程。 |
| `turboism.ui.panel.contribute` | `application` | 将看板安装到空白视口位置。 |
| `turboism.cubism.model.observe` | `application` | 在打开、创建或关闭工程后刷新最近工程列表。 |
| `turboism.cubism.model.write` | `application` | 在空状态下点击链接时通过 `NEW_MODEL` 创建模型或调起原生新建动画。 |
| `turboism.event.subscribe` | `application` | 接收提供方实时派发与启动延迟重放的 `thumbnail.captured` 事件。 |
| `turboism.action.register` | `application` | 注册卡片双击打开动作。 |
| `turboism.file.read` | `application` | 读取插件受限存储中的布局偏好。 |
| `turboism.file.write` | `application` | 保存用户选择的布局偏好。 |

## 图标归属

布局按钮使用 Lucide v0.468.0 的 `layout-grid` 和 `list` 图标，遵循 ISC 许可证（含上游 Feather 声明）。见 `META-INF/licenses/Lucide-LICENSE.txt`。
