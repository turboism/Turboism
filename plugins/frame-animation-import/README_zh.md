---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.frame-animation-import
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: animation, frame-animation, sprite-sheet, parameter
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# 帧动画导入 (Frame Animation Import)

> **官方 Turboism 插件** · **状态: 预览版 (Preview)**

从精灵表（Sprite Sheet）切片或多张序列帧图片导入帧动画，自动生成图形网格（ArtMesh），创建并绑定专用参数，使每个网格在不同关键点上精确显示与隐藏。

| 属性 | 内容 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.frame-animation-import` |
| 分类 | `modeling` (建模) |
| 标签 | animation, frame-animation, sprite-sheet, parameter |
| Turboism API | `[0.1.0,0.2.0)` |
| 宿主需求 | Live2D Cubism Editor |
| 界面技术 | `swing` |
| 授权 | Project License |

## 功能概述

- **精灵表切片导入：** 支持单张大图精灵表，按行数、列数、有效帧数截取，配备可视化网格辅助线与序号实时预览。
- **多图序列帧导入：** 支持批量选入多张独立 PNG 帧图片，具备自然数字排序（如 `frame_1.png` 到 `frame_10.png`）与顺序调整能力。
- **自动图层组织与网格生成：** 自动创建专用归纳部件（Part），一键生成四边形网格几何体，自适应适应 SDK 内存预算，画质无损。
- **参数生成与关键形态绑定：** 自动创建或复用参数（支持设置循环 `repeat` 属性），提供逐帧硬切（Step Flipbook）与平滑淡入淡出（Crossfade）两种策略。
- **原子事务与撤销：** 全流程封装于原子事务内，按下 `Ctrl+Z` 即可完整回退，不污染模型。

## 要求与兼容性

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** 支持全部经过验证的 Editor 版本 `5.0.00` 到 `5.3.04`。
- **界面模式:** `swing`。
- **插件依赖:** 无。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本.
- 部件不透明度写入 仅 5.3.00–5.3.04（[Cubism 5.3.00 新增部件离屏绘制（可设部件不透明度与剪贴蒙版）](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/)）
- 顶部菜单集成 不适用于 5.0.00–5.0.06（更早版本 Cubism 本身无此功能）
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

本官方插件是**应用市场候选插件 (Store Candidate)**。在正式上架应用商店前，您可通过 Turboism 的官方发布包进行安装，并在**插件管理**窗口中启用。在无需使用时亦可随时在该窗口禁用或卸载。

## 使用方法

1. 打开命令面板（`Ctrl+P`）或在部件（Part）/ 变形器（Deformer）面板空白处右键，选择 **导入帧动画...**。
2. 选择 **精灵表切片**（配置行、列、有效帧数）或 **多图序列帧**（添加图片）。
3. 配置参数名称、参数 ID、是否循环以及切换方式（逐帧硬切 / 平滑过渡）。
4. 点击 **开始导入** 即可一键生成。

## 功能能力

| 声明能力 | 用户效果 |
|---|---|
| `cubism.model.read` | 读取模型结构、部件和现有参数。 |
| `cubism.model.write` | 创建部件容器、网格、纹理和参数绑定。 |
| `cubism.parameter.write` | 创建动画参数并写入关键帧不透明度。 |
| `ui.context-menu.contribute` | 向部件和变形器面板添加上下文菜单入口。 |
| `ui.menu.contribute` | 向 Turboism 顶层菜单添加帧动画导入启动入口。 |
| `ui.status.notify` | 报告导入结果与状态提示。 |

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 读取模型部件、网格和参数定义。 |
| `turboism.cubism.model.write` | `application` | 创建部件、网格、参数并绑定关键点不透明度。 |
| `turboism.file.read` | `application` | 读取选中的帧图片与精灵表文件。 |
| `turboism.action.register` | `application` | 注册帧动画导入主动作。 |
| `turboism.ui.context-menu.contribute` | `application` | 在部件和变形器右键菜单中展示导入入口。 |
| `turboism.ui.menu.contribute` | `application` | 向 Turboism 顶层菜单添加帧动画导入启动入口。 |
| `turboism.ui.status.notify` | `application` | 通知导入结果与校验错误。 |

## 隐私与数据

### 网络

不发起任何网络连接。

### 本地数据

在导入会话中从磁盘读取用户选择的精灵表或帧图片，不持久化任何私人数据。

### 遥测

不发送任何遥测数据。运行日志仅保留在本地。

## 状态与限制

- **状态:** 预览版 (Preview)。
- 导入操作需要打开活动的 Cubism 模型文档。
- 单帧分辨率超过 4MB RGBA 预算时将自动进行平滑下采样。
- 在无头 JVM 环境中不显示 Swing 交互向导窗口。

## 故障排除

| 现象 | 排查方法 |
|---|---|
| 右键菜单未显示 | 确保当前已在 Cubism Editor 中打开了活动模型文档。 |
| 无法加载图像 | 检查所选文件是否为受支持的 PNG/JPEG 图像。 |
| 导入操作失败 | 查看状态通知与运行时日志排查具体校验信息。 |

## 支持与许可证

- **项目官网:** [https://turboism.dev](https://turboism.dev)
- **开发者:** Turboism Contributors
- **许可证:** Project License
- **插件 ID:** `dev.turboism.plugin.frame-animation-import`
