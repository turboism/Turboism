---
pluginId: dev.turboism.plugin.warp-row-column-deform
version: 0.1.0
---

# 弯曲行列整体变形

| 项目 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.warp-row-column-deform` |

为 Live2D Cubism Editor 添加自定义临时变形工具，支持通过外侧边缘手柄或直接点击网格，整行或整列移动弯曲变形器的控制点。

## 主要功能

- 同时集成到 **建模 -> 临时变形工具** 菜单和 **工具细节 -> 变形方法** 下拉框。
- 默认在外侧显示行手柄（青色）与列手柄（橙色），并在工具细节中提供「移动模式（自动 / 仅移动整行 / 仅移动整列）」与「控制网格（自动跟随编辑级别 / 贝塞尔 / 转换分割点）」切换。
- 支持 Shift 正交约束与原生撤销/重做（Undo/Redo）。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.05，共 21 个精确版本）.
完整功能（审核版本）：5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.05。
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->
