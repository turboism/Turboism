---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.parameter-lock
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: parameter, lock, palette, safety
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: embedded
---

# 参数锁定插件

> **Turboism 官方插件** · **状态：预览**

保护 Cubism 参数值避免意外修改，支持跨会话持久化与原生逐行/参数组锁定图标集成。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.parameter-lock` |
| 类别 | `workflow` |
| 标签 | parameter, lock, palette, safety |
| 需要 Cubism | 是 |
| 接口 | `embedded` |
| 许可证 | 项目许可证 |

## 功能概述

- 在宿主执行参数修改前拦截写入请求，对已锁定参数直接返回当前值；原生滑杆拖拽与文本输入的覆盖情况单独记录于实机验收文档。
- 在参数面板中直接为每个参数行与参数组行提供原生前置锁定图标（对标 Parts/Deformer 面板行级锁行为）。
- 点击参数行锁图标可精确切换该参数的独立锁定状态，不改变编辑器全局选择。
- 点击参数组文件夹行锁图标可递归锁定或解锁该组下的所有深层子参数（具备防环机制）：混合状态（部分锁定）点击全锁、全锁状态点击全解锁、全未锁状态点击全锁；空组保持未锁状态且不发生误锁。
- 单参数锁定状态变化立即自动刷新其所有父级/祖先参数组的图标状态（未锁、混合或全锁）。
- 主工具栏切换按钮已移除，改由行级原生锁全面替代。
- 写入拦截返回参数当前值，插件不直接修改历史栈；原生撤销/重做（Undo/Redo）行为仍需实机验收。
- 使用模型原生序列化 GUID（`CModelSource.guid`）跨会话持久化锁定配置，重启后自动恢复。
- 插件禁用或卸载时立即解除内存中的锁定拦截行为与行级图标注册，同时安全保留磁盘上的锁定配置文件，便于重新启用时无缝恢复。

## 要求与兼容性

- **SDK API 范围：** `[0.67.0,1.0.0)`。
- **Cubism：** 需要 Cubism。Turboism 当前仅接受经过审查的精确 Editor 构件 `5.0.00` 至 `5.3.04`（含 `5.3.03`）；本插件仅在其声明的服务和能力可用时公开每项面向宿主的功能。
- **接口模式：** `embedded`。
- **插件依赖项：** 未声明。

- 未锁定时常显部件／变形器 Tab 同款原生圆环占位；锁定时显示原生锁头，参数组混合态显示灰色锁头。

- 锁图标位于连接图标旁的独立区域，保留名称原生对齐，避免与长名称重叠。锁定名称使用原生主题的禁用文字色，解锁后恢复原色。
- 锁定参数后，原生滑条和数值输入区变灰并禁止操作，锁图标仍可解锁；双轴合并行中任一轴锁定会禁用共享拖动区，未锁轴仍可输入数值。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

模型观察与参数拦截已纳入上述版本计算。逐版本原始 JAR 的参数钩子和行 UI 变换检查可通过 `:runtime:parameterLockArtifactTest` 重跑，详见[版本兼容性验证](../../validation/parameter-lock/VERSION-COMPATIBILITY.md)。

**实机验收：** 原生逐参数行点击、SDK 及原生控制器写入拦截已在 Cubism 5.2.03、5.3.02 通过。这两个版本的真实单层组已验证“混合 → 全锁 → 全解锁”，全部子参数均逐一验证；隔离模型临时移入第二个成员，测试后已移回，全程未保存模型。5.3.02 跨进程逐行锁持久化恢复已通过。深层嵌套已有离线回归；实机深层嵌套、物理滑条拖动、文本按键输入、Undo/Redo、多模型切换仍未完整验收。

详见[实机验收记录](../../validation/parameter-lock/ROW-UI-ACCEPTANCE.md)。

## 安装与启用

此预览插件通过 **Plugin Market**（插件市场）发布，不随 Turboism 安装包随包提供。可通过插件市场直接获取，或执行 `./gradlew :plugins:parameter-lock:jar` 构建插件 JAR 并在**插件管理**中启用；也可在同一窗口停用。

## 使用方法

1. 在**参数**面板中直接点击任意参数行前置的锁定图标，即可切换该参数的锁定/解锁状态。
2. 直接点击参数文件夹行前置的锁定图标，即可递归切换或全锁该文件夹内的所有子参数。
3. 尝试通过滑杆或文本框修改已锁定的参数：参数值将保持不变。
5. 保存并重新打开模型：已锁定的参数将在新会话中自动恢复受保护状态与图标显示。

## 功能能力

| 声明的能力 | 对用户的作用 |
|---|---|
| `ui.appearance.modify` | 覆盖参数与参数组面板行前置图标动作以提供原生锁定交互。 |
| `cubism.model.read` | 读取活动模型参数与原生模型 GUID。 |
| `cubism.model.observe` | 接收模型生命周期事件以加载和管理持久化锁定状态。 |
| `cubism.model.intercept` | 拦截参数写入请求，阻止已锁定参数发生变更。 |
| `file.read` | 从沙箱插件存储中读取持久化的锁定配置。 |
| `file.write` | 将参数锁定状态写入沙箱插件存储。 |

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.ui.appearance.modify` | `application` | 覆盖参数与参数组面板行前置动作以显示原生锁定图标。 |
| `turboism.cubism.model.read` | `application` | 读取活动模型参数 ID 与原生模型 GUID。 |
| `turboism.cubism.model.observe` | `application` | 接收模型打开/创建/关闭生命周期回调以管理锁定持久化。 |
| `turboism.cubism.model.intercept` | `application` | 在参数修改到达宿主前进行拦截，阻止已锁定参数被修改。 |
| `turboism.file.read` | `application` | 读取沙箱存储中保存的参数锁定状态。 |
| `turboism.file.write` | `application` | 将参数锁定状态写入沙箱存储。 |

## 隐私与数据

### 网络

不发起任何网络连接。

### 本地数据

在插件沙箱存储（`StorageRoot.STATE`）中原子化保存 `locks/<sha256(guid)>.json` 文件。仅包含模型原生 GUID 和参数 ID 字符串。

### 遥测

本插件不发送任何遥测数据。

## 状态与限制

- **状态：** 预览。
- 离线单元测试与集成测试已验证 Hook 写入拦截、行级图标动作注册、深层参数组递归及防环、持久化存储及跨模型隔离；真实 Cubism Editor 的逐行与组动作通过项及限制见上述验收记录。
- 逐行交互已使用独立探针与产物验收；旧工具栏证据单独保留。
- 写入抑制机制在宿主执行修改前返回参数当前已有值；原生撤销历史的交互依赖所安装 Cubism 版本的具体写入管线。
- 需要活动建模文档且能访问原生模型 GUID。
- 首次读取该模型的锁定记录期间，参数写入会短暂保持当前值，加载完成后按锁定列表放行或拦截。
- 锁定配置以 `.cmo3` 文件内序列化的原生 `CModelSource.guid` 作为唯一标识键。在 Live2D Cubism Editor 中，通过“另存为”（Save As）或直接复制生成的模型副本，其宿主内部仍然保留相同的原生 GUID。因此，按宿主自身设计，副本文件会共享该模型的参数锁定配置。

## 故障排除

| 症状 | 排查建议 |
|---|---|
| 参数值仍能被修改 | 确认插件已在插件管理中启用，且该参数显示已锁定状态。 |
| 参数行图标未显示 | 确认模型已加载完毕，且已授予外观修改权限。 |
| 重新打开模型后锁定未恢复 | 确保模型已保存，且 `.cmo3` 文件的原生 GUID 未被手动修改。 |

## 更新日志

### 0.1.0

- 初始版本发布。

## 支持与许可证

- **项目官网：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** 项目许可证
- **插件 ID：** `dev.turboism.plugin.parameter-lock`
