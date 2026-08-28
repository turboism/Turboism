---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.recent-preview
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: recent-files, preview, navigation
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# 最近预览插件

> **Turboism 官方插件** · **状态：预览**

捕获受限缩略图，并在 Cubism 的 Recent Files 悬停弹窗中显示它们。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.recent-preview` |
| 类别 | `workflow` |
| 标签 | recent-files, preview, navigation |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面 | `swing` |
| 许可证 | 项目许可证 |

## 功能概述

- 在受支持的项目打开或保存活动后，捕获最大为 150×150 的建模画布预览。
- 对进行中的捕获和未变更的捕获进行去重，并将缓存内容贡献给 Recent Files 悬停 UI。
- 使用不透明的最近文件 ID 和不含路径的缓存索引。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：**需要 Cubism。Turboism 当前允许精确审核过的 Editor 构件 `5.2.03`、`5.3.02` 和 `5.3.03`；本插件仅在其声明的服务和功能能力可用时公开各项面向主机的功能。
- **界面模式：**`swing`。
- **插件依赖：**未声明。

## 安装与启用

此官方插件是一个**商店候选项**，尚未作为商店列表发布。在市场发布前，请通过 Turboism 的官方发行包安装它，然后在**插件管理**中启用它。若不再需要此工作流，请在同一窗口中禁用或卸载它。

## 使用方法

1. 在插件启用期间打开或保存 Cubism 项目。
2. 打开 Cubism 的 Recent Files 界面，并将鼠标悬停在最近项目上。
3. 弹窗会显示缓存的缩略图和可用的显示元数据；过期或不可用的捕获会被安全地跳过。

## 功能能力

插件清单中未声明任何功能能力。

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.cubism.recent-file.read` | `application` | 列出主机的 Recent Files 投影，以不透明文件 ID 作为预览缓存的键。 |
| `turboism.ui.viewport.read` | `application` | 为最近文件捕获受限的建模画布预览缩略图。 |
| `turboism.ui.recent-preview.contribute` | `application` | 向主机的 Recent Files 悬停桥接贡献缩略图弹窗内容。 |
| `turboism.file.read` | `application` | 从插件隔离的缓存存储中读取缓存的预览 PNG。 |
| `turboism.file.write` | `application` | 将预览 PNG 和不含路径的索引以原子方式写入插件隔离的缓存存储。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

在插件缓存路径 `recent-preview/images/` 和 `recent-preview/index/` 下存储受限的 PNG 缩略图和不含路径的索引条目。键是从不透明的最近文件 ID 派生的 SHA-256 值；索引条目有意不包含文件路径。磁盘缓存会在禁用后保留，直到从外部清除缓存数据为止。

### 遥测

本插件不会发送遥测数据。

带有插件 ID 的插件生命周期和故障记录可能会出现在 Turboism 的会话日志及 Cubism 的主机日志中。

## 状态与限制

- **状态：**预览。
- 需要最近文件、视口截图和最近预览贡献服务。
- 捕获是异步且受限的；项目变更、过期 ID、无效 PNG 或不可用的视口都会导致结果被跳过。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| 悬停弹窗中没有缩略图 | 打开或保存项目，等待捕获完成，并确认视口/最近文件服务可用。 |
| 仍显示旧缩略图 | 重新打开或保存项目以触发协调；无效或过期的缓存条目会被忽略。 |
| 缓存增大 | 通过 Turboism 的存储管理清除此插件的缓存，或在 Turboism 停止时删除插件缓存。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.recent-preview`
