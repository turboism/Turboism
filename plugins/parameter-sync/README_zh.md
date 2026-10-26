---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.parameter-sync
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: parameter, sync, link, workflow
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# 参数同步插件

> **Turboism 官方插件** · **状态：预览**

在 Live2D Cubism Editor 中提供基于归一化百分比进度的参数数值实时同步联动插件。统一单窗口配置，顶部全局开关，各参数独立勾选。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.parameter-sync` |
| 类别 | `modeling` |
| 标签 | parameter, sync, link, workflow |
| 需要 Cubism | 是 |
| 接口 | `swing` |
| 许可证 | 项目许可证 |

## 功能概述

- 滑块拖拽、文本输入或外部驱动时，实时将当前变动参数的归一化进度百分比同步给所有其他已勾选的参数。
- 极简合一窗口设计：顶部总开关与已启用数量统计，下方各参数独立勾选。
- 基于 Turboism 事件系统驱动与程序写入抑制机制，彻底杜绝回弹震荡与卡顿。
- 基于模型原生 GUID 持久化保存同步配置，支持跨编辑器会话记忆。

## 要求与兼容性

- **SDK API 范围：** `[0.67.0,1.0.0)`。
- **Cubism：** 需要 Cubism。Turboism 当前仅接受经过审查的精确 Editor 构件 `5.0.00` 至 `5.3.04`；本插件仅在其声明的服务和能力可用时公开每项面向宿主的功能。
- **接口模式：** `swing`。
- **插件依赖项：** 未声明。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

此官方插件已随 Turboism 提供，也可通过 **Plugin Market** 获取。可在**插件管理**中启用、禁用或卸载该插件。

## 使用方法

1. 在参数面板中右键任意参数。
2. 选择 **参数同步...** 打开统一配置窗口。
3. 勾选想要一起联动的各个参数，点击 **保存并关闭**。
4. 随后在参数列表或画板中拖动任意勾选的参数，其他勾选参数将实时平滑同步。

## 功能能力

| 声明的能力 | 对用户的作用 |
|---|---|
| `cubism.parameter.read` | 从当前活动模型中读取参数值、取值范围与名称。 |
| `cubism.parameter.write` | 向目标参数写入同步计算后的数值。 |
| `cubism.model.observe` | 监听模型打开与关闭生命周期事件以恢复同步规则。 |
| `event.subscribe` | 订阅实时参数值变更事件。 |
| `ui.context-menu.contribute` | 向参数面板上下文菜单贡献参数同步操作。 |
| `ui.status.notify` | 通知同步状态变更与开关提示。 |
| `file.read` | 从插件存储读取已保存的参数同步规则。 |
| `file.write` | 将参数同步规则持久化保存到插件存储。 |

## 权限

| 权限 | 作用域 | 请求原因 |
|---|---|---|
| `turboism.action.register` | `application` | 注册参数同步管理器与开关操作。 |
| `turboism.ui.context-menu.contribute` | `application` | 向参数面板上下文菜单贡献参数同步操作。 |
| `turboism.cubism.model.read` | `application` | 读取参数值、边界、名称以及活动模型 GUID 以执行同步。 |
| `turboism.cubism.model.write` | `application` | 将同步值写入目标参数。 |
| `turboism.cubism.model.observe` | `application` | 监听参数值变化与模型生命周期事件。 |
| `turboism.event.subscribe` | `application` | 订阅参数值变更事件。 |
| `turboism.file.read` | `application` | 从插件存储读取持久化的参数同步规则。 |
| `turboism.file.write` | `application` | 将参数同步规则持久化保存到插件存储。 |
| `turboism.ui.status.notify` | `application` | 通知同步规则变更、成环告警与开关状态。 |

## 隐私与数据

### 网络

不建立网络连接。

### 本地数据

在插件沙箱存储中按模型 GUID 持久化保存参数同步配置，仅包含模型 GUID 和参数 ID 字符串。

### 遥测

本插件不会发送遥测数据。

插件生命周期和失败记录可能会出现在 Turboism 的会话日志和 Cubism 的宿主日志中，并附带插件 ID。

## 状态与限制

- **状态：** 预览。
- 使用归一化百分比映射同步活动模型中已勾选的参数。
- 需要处于活动状态的模型文档。
- 勾选少于 2 个参数时不同步。

## 故障排除

| 症状 | 检查事项 |
|---|---|
| 参数未同步 | 确认参数同步已开启且已勾选至少 2 个参数。 |
| 右键菜单缺少条目 | 确认当前已在 Editor 中打开模型。 |
| 同步已暂停 | 通过右键菜单的“切换参数同步”或弹窗顶部的复选框恢复启用。 |

## 更新日志

### 0.1.0

- 初始版本发布。

## 支持与许可证

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** 项目许可证
- **插件 ID：** `dev.turboism.plugin.parameter-sync`
