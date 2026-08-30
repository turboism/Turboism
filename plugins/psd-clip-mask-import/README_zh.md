---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.psd-clip-mask-import
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: psd, import, clip-mask
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: embedded
---

# PSD 剪贴蒙版导入插件

> **Turboism 官方插件** · **状态：预览版**

在明确预览和覆盖确认后，把有序 PSD 剪贴关系导入 ArtMesh 剪贴蒙版分配。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.psd-clip-mask-import` |
| 类别 | `workflow` |
| 标签 | psd, import, clip-mask |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面 | `embedded` |
| 许可证 | 项目许可证 |

## 功能概述

- 在 Turboism 面板添加 **PSD 剪贴蒙版导入**区域和导入按钮。
- 从当前 Cubism 模型的 PSD 文档读取有序剪贴关系，并解析相关 ArtMesh。
- 在写入前预览目标、有序蒙版来源、PSD 引用、覆盖冲突和跳过原因。
- 将确认后的变更作为一个条件批次应用，由宿主提供全有或全无提交及单次 Undo/Redo。

## 要求与兼容性

- **Turboism API：**`[0.1.0,0.2.0)`。
- **Cubism：**需要受支持精确版本提供活动模型 PSD 关系、剪贴蒙版读取、有序替换、事务/Undo、嵌入面板、对话框和状态通知服务。
- **界面模式：**`embedded`。
- **插件依赖项：**未声明。

## 安装与启用

本插件包含在 Turboism Full 发布包中。安装 Full 包后，可在**插件管理**中启用。只有当前精确版本宿主提供全部经过审查的必要服务时，操作和写入才可用。

## 使用方法

1. 打开目标模型，并确保活动 PSD 文档中的剪贴关系可解析到模型 ArtMesh。
2. 在 Turboism 面板中打开 **PSD 剪贴蒙版导入**，点击导入按钮。
3. 检查目标、蒙版顺序、来源图层、跳过项和所有覆盖冲突。
4. 取消不会写入内容；明确确认后才替换显示的剪贴蒙版分配。
5. 使用 Cubism Undo/Redo 撤销或恢复这一次确认批次。

## 功能能力

| 已声明能力 | 用户效果 |
|---|---|
| `cubism.psd.layer-relationship.read` | 读取活动文档中有序的 PSD 剪贴关系。 |
| `cubism.clipmask.read` | 读取当前 ArtMesh 剪贴蒙版列表与反向状态。 |
| `ui.dialog.contribute` | 显示预览和覆盖确认对话框。 |
| `ui.status.notify` | 报告导入、跳过、无写入和安全失败结果。 |
| `cubism.clipmask.replace-ordered-sources` | 按计划顺序替换确认后的 ArtMesh 蒙版来源列表。 |
| `cubism.transaction.real-write-undo` | 通过 Cubism 真实写入/Undo 路径提交批次。 |
| `ui.embedded-panel.contribute` | 在 Turboism 面板中添加导入区域。 |

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 读取有序 PSD 图层关系，并解析到现有 Drawable 身份。 |
| `turboism.cubism.model.write` | `application` | 通过条件模型批次原子提交确认后的剪贴蒙版分配。 |
| `turboism.action.register` | `application` | 注册 PSD 剪贴蒙版导入操作。 |
| `turboism.ui.panel.contribute` | `application` | 提供 PSD 剪贴蒙版导入按钮。 |
| `turboism.ui.dialog.contribute` | `application` | 在写入前显示目标、蒙版、冲突和跳过项。 |
| `turboism.ui.status.notify` | `application` | 报告导入、跳过、失败和失败关闭诊断数量。 |

## 隐私与数据

### 网络

本插件不进行网络连接。

### 本地数据

不请求文件系统或插件存储访问，也不持久化插件数据。只读取活动文档 PSD 关系、ArtMesh ID、当前有序剪贴蒙版列表与反向状态；仅通过一个条件批次写入用户明确确认的 ArtMesh 剪贴蒙版分配。

### 遥测

本插件不会发送遥测数据。

生命周期和安全失败记录可能显示在 Turboism 会话日志及 Cubism 主机日志中，并附有插件 ID。用户可见错误使用本地化安全信息，而不暴露宿主异常细节。

## 状态与限制

- **状态：**预览发布插件。
- 只导入剪贴关系；不导入 PSD 图像或纹理、不创建 ArtMesh、不修复图层绑定、不扩展画布，也不提供通用 PSD 重导入。
- 每次替换都需要确认。已有非空列表或反向状态会作为覆盖冲突显示，替换结果设置为非反向。
- 无法解析、缺少蒙版、自引用、重复身份、跨文档歧义和已匹配计划的关系会被跳过或失败关闭。
- 提交前会重新获取模型并验证文档、模型身份和计划仍与预览一致；发生变化时零写入。
- 宿主必须提供声明的 Cubism/UI 服务；在 Cubism 外不可用。

## 故障排除

| 症状 | 检查项 |
|---|---|
| 缺少导入区域或按钮 | 确认插件已启用，并且当前受支持 Cubism 版本提供嵌入面板和操作服务。 |
| 预览中只有跳过项 | 确认 PSD 剪贴基础、蒙版和目标都能唯一解析到活动模型 ArtMesh。 |
| 确认后没有变化 | 检查已匹配项、取消、歧义，或文档/模型/计划变化导致的安全失败。 |
| 导入安全失败 | 检查本地化状态摘要和插件日志，并确认所需 PSD、剪贴蒙版、对话框、写入/Undo 与状态服务可用。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.psd-clip-mask-import`
