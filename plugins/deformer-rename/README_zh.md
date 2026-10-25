---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.deformer-rename
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: modeling
tags: deformer, rename, batch-edit
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# 一键变形器重命名

## 功能概述

根据子物体名称与绑定参数，一键预览并重命名弯曲、旋转变形器。

## 要求与兼容性

需要 Turboism 0.67.0 及以上版本和已打开的 Cubism 模型。下方兼容性由 SDK 推导，不代表已完成真机验证。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
- 顶部菜单集成 不适用于 5.0.00–5.0.06（更早版本 Cubism 本身无此功能）
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

运行 `./gradlew :plugins:deformer-rename:jar` 构建，在 Turboism 插件管理中安装 JAR 并启用。

## 使用方法

打开 **Turboism → 插件 → 一键变形器重命名**。四列依次显示类型图标、原名称、目标名称、生效复选框。第四列表头可全选或全不选；确认后才修改，取消或 Escape 不修改。

递归收集末端子物体名称，取最长共同前缀；没有共同前缀时取最短名称，同长度按模型顺序取第一个。空子变形器以自身名称参与父级推导；没有子物体的变形器保留原名。追加自身绑定参数显示名称，无显示名称则用 ID；同组 X/Y/Z 后缀按绑定顺序合并。例如 `头发1`、`头发1 阴影` 加上 `头部X`、`头部Y` 得到 `头发1 头部XY`。其他参数以空格分隔。

## 功能能力

- `cubism.model.read`
- `cubism.model.write`
- `ui.dialog.contribute`
- `ui.menu.contribute`
- `ui.status.notify`

## 权限

- `turboism.cubism.model.read`
- `turboism.cubism.model.write`
- `turboism.action.register`
- `turboism.ui.dialog.contribute`
- `turboism.ui.menu.contribute`
- `turboism.ui.status.notify`

## 隐私与数据

全部在本地处理，不联网、不导出文件、不保存设置。

## 状态与限制

开发版本，已在 Cubism 5.3.03 实机验证预览及两种原生变形器图标（12 行原生图标，无文字回退）。各支持版本的完整重命名与撤销流程仍待实机验证。允许重复显示名，不修改 ID。所有选中修改通过一个原生事务提交，可一次撤销。名称不变的行不参与修改。

## 故障排除

请先打开模型。预览过期后重新打开；失败时查看插件日志中的事务结果。

## 支持与许可证

[问题反馈](https://github.com/turboism/Turboism/issues)。遵循仓库许可证。
