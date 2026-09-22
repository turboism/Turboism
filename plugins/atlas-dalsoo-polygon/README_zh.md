---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-dalsoo
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: modeling
tags: texture-atlas, packing, polygon, auto-layout
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# Dalsoo 多边形排版算法

> **Turboism 官方插件** · **状态：开发中**

为 Cubism 纹理集工作流提供不规则多边形自动排版算法，风格对应 Cubism 5.4
的轮廓打包。内核独立移植自 MIT 许可的 `whitegreen/Dalsoo-Bin-Packing`
（提交 `bde2a3e`），见 `LICENSE` 与 `NOTICE`。本插件**不**内嵌、调用或
宣称兼容 Cubism 5.4——它运行于当前支持的宿主（`5.2.03`、`5.3.02`、
`5.3.03`）。

| 项目 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.texture-atlas-dalsoo` |
| 分类 | `modeling` |
| 标签 | texture-atlas, packing, polygon, auto-layout |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面模式 | `none` |
| 许可证 | MIT（内核移植） |

## 功能

- 在现有 MaxRects-BSSF 矩形后端之外注册 `dalsoo` 纹理集排版算法；矩形后端
  保持不变。
- 按真实凹轮廓打包（material-local `drawDataShapes`，经 verified selector
  读取），不使用包围盒，也绝不使用凸包替代。
- 支持 `NONE`、`QUARTER`（90° 步进）、`FREE`（18 个候选角度）旋转模式，
  固定或自动统一倍率、页边距，以及逐项策略：参与、保持角度、保持倍率、
  保持位置（固定项在可移动项之前作为障碍物放置）。
- 以校验过的多边形计划服务原生自动排版回调；其他算法继续走既有矩形路径。
- 计划先对照新鲜宿主状态校验（身份、重叠、边距、锁定），再经与矩形服务
  相同的分阶段 affine/undo 边界写回；失败完整回滚。

## 要求与兼容性

- **Turboism API：** `[0.1.0,0.2.0)`。
- **Cubism：** 仅精确审阅过的 Editor 构件 `5.2.03`、`5.3.02`、`5.3.03`。
  Cubism 5.4 未被准入；SDK 契约中保留 `HOST_NATIVE` 后端槽位供未来正式
  5.4 宿主 provider 使用。
- **界面模式：** `none`。
- **插件依赖：** 无。

## 诚实的限制

- **轮廓回退。** 宿主项无可用轮廓时回退到包围矩形，并在诊断中标记
  `outlineSource=BOUNDS_FALLBACK`，不会伪装成轮廓打包。
- **洞。** 轮廓中的洞在打包前被保守填充并报告（`holesFilled` 诊断）。
  复杂自交拓扑可能退化为包围矩形。
- **矩形路径降级。** 旧矩形排版契约无法表达任意角度；计划走该路径时
  `FREE` 降级为 `QUARTER`——45° 放置绝不被舍入成虚假的 90° 旋转。
- **性能。** 凹形打包比矩形慢数个数量级：100 个凹形项视旋转模式与质量
  预设需数秒到数分钟；500 项需数分钟。`AUTO` 后端会把全部近矩形输入路
  由到矩形路径。实测数据见
  [离线证据](../../validation/texture-atlas-current-page/OFFLINE-POLYGON.md)
  与原始 CSV；这些是合成离线数据，不是宿主计时。
- **不宣称原生 5.4。** Cubism 5.4 alpha2 JAR 仅用于只读研究；其构件未被
  准入，也未宣称任何原生对比数据。

## 安装与启用

本官方插件为**商店候选**，尚未上架。通过 Turboism 官方发布包安装后，在
**插件管理**中启用。

## 使用方式

1. 打开 Cubism 纹理集编辑器，选择自动排版工作流。
2. 选择 `dalsoo` 算法；可选开启并行变体搜索。
3. 运行自动排版；插件先校验完整计划再应用，并报告诊断信息（后端、轮廓、
   回退、洞、倍率、溢出）。

## 能力

| 声明的能力 | 用户效果 |
|---|---|
| `cubism.texture-atlas.layout` | 在审阅过的编辑器服务可用时注册并应用多边形排版算法。 |

## 权限

| 权限 | 范围 | 申请原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 读取图集项轮廓、已签发变换与页面状态。 |
| `turboism.cubism.model.write` | `application` | 经宿主 affine/undo 边界应用校验过的多边形排版计划。 |
| `turboism.config.plugin.read` | `application` | 恢复多边形排版设置与逐项策略。 |
| `turboism.config.plugin.write` | `application` | 持久化多边形排版设置与逐项策略。 |

## 隐私与数据

### 网络

不发起任何网络连接。

### 本地数据

排版设置与逐项策略存于插件配置。仅在工作流运行时读写活动纹理集状态。

### 遥测

本插件不发送遥测。

## 状态与限制

- **状态：** 开发中。
- 需要活动的纹理集编辑器会话与审阅过的模型读写服务。
- 排版或应用失败会报告且不应用部分布局；MaxRects-BSSF 与原生算法仍可
  作为回退。
- 三个支持版本的真实宿主验收尚未执行；目前仅有离线基准与单元/集成
  测试。

## 故障排查

| 症状 | 检查项 |
|---|---|
| 没有 `dalsoo` 选项 | 确认插件已启用且宿主暴露纹理集排版能力。 |
| 项按矩形打包 | 宿主轮廓源可能不可用；检查诊断中的 `BOUNDS_FALLBACK`。 |
| 排版缓慢 | 凹形打包代价高；降低质量预设、改用 `QUARTER`/`NONE`，或对近矩形输入选 `AUTO`/矩形后端。 |
| 排版未应用 | 查看 Turboism 日志中的校验失败；计划是原子拒绝的。 |

## 支持与许可

- **项目网站：** [https://turboism.dev](https://turboism.dev)
- **发布者：** Turboism Contributors
- **许可证：** MIT；内核移植自 `whitegreen/Dalsoo-Bin-Packing`（见
  `LICENSE`/`NOTICE`）
- **插件 ID：** `dev.turboism.plugin.texture-atlas-dalsoo`
