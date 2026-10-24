---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-nesting
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: texture-atlas, packing, polygon, auto-layout
turboismApi: "[0.67.4,1.0.0)"
requiresCubism: true
interface: none
---

# 位掩码密集排版

## 功能概述

独立的当前纹理页密排插件。使用材质局部凹轮廓、保守位掩码及跳步落点搜索，让小部件填入开放凹槽和独立轮廓区域之间。全部为精确矩形时自动使用轻量 BSSF 路径，异形仍按真实轮廓检测。无需其他排版插件。

## 要求与兼容性

需要 Java 17+、Turboism API [0.67.4,1.0.0) 及自动生成清单支持的 Cubism 5.0–5.3。5.2–5.3 支持轮廓嵌合；5.0–5.1 在轮廓接口不可用时使用矩形排版。复用现有 AUTO 后端值，以 bitmask-nesting 诊断标识内核，无需扩展 SDK 枚举。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 全部审核版本（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04，共 20 个精确版本）.
完整功能（审核版本）：5.2.00–5.2.03, 5.3.00–5.3.04。
- 多边形图集布局 仅 5.2.00–5.3.04（更早版本 Cubism 本身无此功能）
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

执行 `./gradlew :plugins:atlas-bitmask-nesting:jar`，通过 Turboism 插件管理安装生成的 JAR 并启用。本开发模块尚未加入正式发布包。

## 使用方法

打开当前纹理页，在原生自动排版算法列表选择 **Bitmask Nesting**，设置倍率、边距和旋转后执行。关闭旋转时保留原角度；开启时增加四分之一转和最小包围盒方向。边距采用原生每侧语义：3px 边距对应部件之间至少 6px。固定倍率不会暗中缩小，自动倍率不超过 100%。

## 功能能力

`cubism.texture-atlas.layout` 注册原生算法入口；框架负责快照、验证、仿射写回、撤销及过期目标检查。

## 权限

`turboism.cubism.model.read` 读取轮廓和已发放变换。`turboism.cubism.model.write` 通过框架应用验证后的方案。

## 隐私与数据

几何数据在内存中处理，不导出源图、不写模型文件、不联网、不发送遥测。诊断日志记录部件数量、倍率、网格大小和内核耗时。

## 状态与限制

开发版本。原生入口使用真实轮廓排当前页。完整纹理集 SDK 请求（包括按部件文件夹一键排版）使用可用的网格轮廓（缺失时回退完整图片矩形），保持原始尺寸且不旋转；放不下时在页数预算内分页，否则报告明确的排版失败。轮廓缺失时采用 SDK 声明的矩形回退。SDK 保守填充封闭孔洞；开放凹槽仍可利用。有限网格及角度以部分密度换取速度。默认每边最多 512 格（4096 页对应 8px 格）；DENSE SDK 请求使用 1024 格。显式边距独立于网格精度。轮廓来自网格而非图像透明度；圆形图片若使用矩形网格，仍按矩形处理。串行、确定性启发式搜索，不保证全局最优或最大可行倍率；并行选项禁用。

## 故障排除

算法入口缺失时检查插件启用状态、宿主兼容性和日志。固定倍率下放不下的项遵守框架当前页溢出契约。执行 `./gradlew :plugins:atlas-bitmask-nesting:test` 运行碰撞及几何回归；`:plugins:atlas-bitmask-nesting:nestingBenchmark` 测量合成样例的内核耗时，不等于 Cubism 界面总耗时。

## 支持与许可证

通过 https://turboism.dev 向项目提交可复现问题，附宿主版本及诊断日志。采用 MIT 许可证，见仓库 LICENSE。
