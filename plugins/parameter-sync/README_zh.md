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

# 参数同步 (Parameter Sync)

> **官方 Turboism 插件** · **状态：预览**

在 Live2D Cubism Editor 中提供基于归一化百分比进度的参数数值实时同步联动插件。统一单窗口配置，顶部全局开关，各参数独立勾选。

| 属性 | 内容 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.parameter-sync` |
| 分类 | `modeling` |
| 标签 | parameter, sync, link, workflow |
| 依赖 Cubism | 是 |
| 界面模式 | `swing` |
| 许可证 | 项目许可证 |

## 功能特性

- 滑块拖拽、文本输入或外部驱动时，实时将当前变动参数的归一化进度百分比同步给所有其他已勾选的参数。
- 极简合一窗口设计：顶部总开关与已启用数量统计，下方各参数独立勾选，底部测试滑块即时预览。
- 基于 Turboism 事件系统驱动与程序写入抑制机制，彻底杜绝回弹震荡与卡顿。
- 基于模型原生 GUID 持久化保存同步配置，支持跨编辑器会话记忆。

## 使用方法

1. 在参数面板中右键任意参数。
2. 选择 **参数同步...** 打开统一配置窗口。
3. 勾选想要一起联动的各个参数，可在下方拖动测试滑块即时预览效果，点击 **保存并关闭**。
4. 随后在参数列表或画板中拖动任意勾选的参数，其他勾选参数将实时平滑同步。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->
