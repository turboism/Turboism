---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.external-psd-edit
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: psd, texture, external-edit
turboismApi: "[0.67.2,1.0.0)"
requiresCubism: true
interface: embedded
---

# 外部 PSD 编辑插件

> **Turboism 官方插件** · **状态：预览版**

右键点击 ArtMesh 即可在指定的外部编辑器（留空时使用系统默认程序）中打开并编辑其完整原图 PSD；每次稳定保存都会应用 Cubism 原生显式目标原图替换。

| 项目 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.external-psd-edit` |
| 分类 | `modeling` |
| 标签 | psd, texture, external-edit |
| 需要 Cubism | 是 |
| 界面模式 | `embedded` |
| 许可证 | Project License |

## 功能概述

- 在支持 ArtMesh 的右键菜单（部件、变形器与工作区对象视图）中添加「外部编辑 (PSD)」入口。
- 通过类型化纹理关系图将每个选中的 ArtMesh 解析为其当前原图（Raw Image），并对每个不同原图开启一个独立编辑会话；重复选择与已打开的原图会自动去重。
- 当 ArtMesh 当前从纹理图集渲染时，仅当其保留的模型图像输入全部解析为同一个当前原图时才继续追踪；缺失、未知或存在歧义的源会被拒绝，且绝不切换渲染输入。
- 通过 Cubism 原生分层图像导出将完整原图 PSD 导出到运行时管理的临时文件，然后启动设置中指定的编辑器；未填写时使用系统默认的 PSD 应用程序。
- 监听该临时文件，并在每次稳定保存时应用 Cubism 原生显式目标原图替换。
- 会话存活期间执行有界只读关系刷新，以感知 Undo/Redo 引起的原图变化，而不会额外排队替换或重放保存。
- 以非阻塞通知报告会话、导入与暂停状态；当原生结果不确定时暂停会话而非猜测结果。

## 指定外部编辑器

在 Turboism 的插件设置中打开「外部编辑 PSD」，输入编辑器程序的完整路径，或点击「浏览…」选择 exe。应用设置后，新打开或重新打开的文档会使用该程序；留空则使用系统默认打开方式。路径可以包含空格，无需加引号或命令行参数。无效路径会报告打开失败，并保留临时 PSD。

## 要求与兼容性

- **SDK API 范围：** `[0.67.2,1.0.0)`。
- **Cubism：** 必需。导出与替换使用 Cubism 原生分层图像导出与原图替换能力（自 Cubism 5.2.00 起提供）。
- **界面模式：** `embedded`。
- **插件依赖：** 无。
- 导出的临时 PSD 是重建的分层文档，而非源资产的字节副本。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.2.00–5.2.03, 5.3.00–5.3.04。
- 导出 PSD 原图 仅 5.2.00–5.3.04（更早版本 Cubism 本身无此功能）
- 替换 PSD 原图 仅 5.2.00–5.3.04（更早版本 Cubism 本身无此功能）
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

随 Turboism 正式发行包捆绑提供。可在安装器中勾选或在 **插件管理** 中启用与禁用。

## 使用方法

1. 打开模型文档并选中一个或多个 ArtMesh。
2. 右键点击并选择外部 PSD 编辑菜单项。
3. 若选区解析出多个新原图，确认一次批量打开提示。
4. 在外部应用程序中编辑并保存该文件；每次稳定保存都会自动导入到对应原图。
5. 禁用插件或关闭文档即可结束会话；临时文件与外部程序保持原样，不会被强制关闭或删除。

## 功能能力

清单声明了 `cubism.psd.external-edit`（用于 ArtMesh 到 PSD 的外部编辑管线）、`ui.context-menu.contribute`（用于右键菜单入口）、`ui.status.notify`（用于会话状态通知）以及 `ui.dialog.contribute`（用于多文件确认对话框）。

## 权限

- `turboism.cubism.model.read` — 读取纹理关系以将 ArtMesh 解析为原图并校验会话绑定。
- `turboism.cubism.model.write` — 在每次稳定保存时应用原生显式目标原图替换。
- `turboism.cubism.model.observe` — 接收模型关闭与编辑器退出生命周期回调以停止外部编辑会话。
- `turboism.file.read` — 读取运行时签发的临时 PSD 修订版本以执行替换。
- `turboism.file.write` — 将完整原图 PSD 导出到运行时管理的临时文件。
- `turboism.process.run` — 使用指定编辑器或系统默认程序打开临时 PSD。
- `turboism.config.plugin.read` / `turboism.config.plugin.write` — 读取与保存编辑器路径。
- `turboism.ui.settings.contribute` — 显示路径输入框和浏览按钮。
- `turboism.ui.file-chooser.request` — 选择编辑器可执行文件。
- `turboism.ui.status.notify` — 显示会话、导入以及暂停/失败通知。
- `turboism.ui.dialog.contribute` — 在打开多个临时 PSD 前进行确认。
- `turboism.action.register` — 注册由右键菜单调用的操作。
- `turboism.ui.context-menu.contribute` — 向支持 ArtMesh 的右键菜单提供菜单项。
- `turboism.ui.context-source.read` — 读取触发编辑时捕获的右键菜单选区。

## 隐私与数据

### 网络

不进行任何网络连接。

### 本地数据

在系统临时目录下写入运行时管理的临时 PSD 文件并读取以供替换。会话停止后保留临时文件，插件从不删除它们，也不关闭外部应用程序。会话之间不持久化插件数据。

### 遥测

本插件不发送任何遥测数据。

插件生命周期与错误记录可能会附带插件 ID 写入 Turboism 会话日志。

## 状态与限制

- **状态：** 预览版。
- 导出文件由 Cubism 原生分层图像重建生成；Cubism 未建模的纯 PSD 专有结构不保证往返保留。
- 仅导入对所签发临时文件的原地保存；“另存为”到其他路径、不保存关闭或编辑副本不会触发替换。
- 多个模型图像或 ArtMesh 共享同一原图时只编辑一次，替换会同步作用于所有依赖对象。
- 若原生替换结果不确定，会话将安全暂停；再次点击菜单只会重新打开现有临时文件，需禁用再启用插件后重新发起才能创建新会话。

## 故障排除

| 现象 | 检查方法 |
|---|---|
| 点击菜单项无反应 | 选区必须包含 ArtMesh，且捕获的文档绑定仍须与当前活动模型匹配。 |
| 出现警告后不再自动替换 | 会话因不确定的原生结果而暂停；请先禁用再启用插件，然后重新发起编辑会话。 |
| 修改后未导入 | 确认外部程序保存到了同一个临时文件路径，“另存为”新文件名不会被监听。 |
| 再次右键提示“已打开” | 该原图已有活动会话，请继续使用已打开的外部编辑器窗口。 |
| 确认后未打开文件 | 当权限被撤销或原生导出报告原图不可用时，导出会安全中止。 |

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** Project License
- **插件 ID：** `dev.turboism.plugin.external-psd-edit`
