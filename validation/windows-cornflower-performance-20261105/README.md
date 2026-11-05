# Windows 5.3.04 矢车菊大模型性能优化实机筛选（2026-11-05）

## 目的与结论

在原生 Windows x64 的 Cubism 5.3.04 上，用桌面 `矢车菊机械未来1.cmo3`（763,166,356 字节，
SHA-256 `83326ac519dd5e4cad9b39e4c7234410cc62bf0d1f5241364561d6906292b6da`）实测当前
Performance 页的优化开关，目标是找出**不改变模型视口像素**的精选组合，而不是“全部打开”。

结论：

- **精选组合 Tier 1（推荐默认，导航/渲染）**：模型更新跳过 + Uniform 位置缓存 + 帧末延迟 GL 检查
  （Mesa GL 线程开关）+ 上传省略 + 网格 Setup 省略 + 剪贴蒙版同帧去重 + 蒙版包围盒裁剪清屏 +
  扩展插值增量重构。完整 200 事件×4 腿的 wheel 测试中，模型更新跳过的 ON 腿均值
  **231.2/229.0 ms → 73.0/71.6 ms（约 3.2×）**；相邻校准对照约为 **2.3×**。模型视口像素与
  基线逐像素一致（`x>=500` 区域 0 差异；Tier 1 校准轮的整窗截图哈希也与基线完全相同）。
- **编辑组合 Tier 2**：Tier 1 + 增量更新（含胶水影响器）。适合参数/变形器/层级编辑；纯相机缩放时
  增量更新会增加开销（本轮 wheel 实测约 103.5 ms，慢于 Tier 1 的 73–86 ms），因此不放进默认配置。
- **不要全开**：全部开关同时开启的 wheel 组合出现模型视口 **43,582 个像素差异**（包围盒
  x=618..933、y=53..698），且速度不优于精选组合；`warpPositionProjection` 在本宿主直接安装失败。
  `meshInterpolation`、`shaderUniform`、`matrixScratch` 单独叠加时模型视口一致，但在 wheel 上更慢；
  旧的分配/纹理缓存开关没有主指标收益。
- **流水线画布回读（asyncReadback）**不是默认推荐：像素自检一致，但画布比 UI 晚一帧；本轮没有
  复现出稳定的额外加速（与关闭相比在噪声内），严格视觉一致场景应保持关闭。

## 环境与工件

- 宿主：Windows 11 x64，Cubism Editor 5.3.04 官方安装，
  bundled Java 17.0.3.1，Intel UHD Graphics 770 / OpenGL 4.6，i7-13700，16 GB。
- 模型：桌面源文件复制到本机临时 fixture 目录后测试；源文件与副本
  SHA-256 相同，测试过程中源文件未修改。`run-envelope.properties` 记录 `sourceUnchanged=true`。
- 性能代码 agent（大多数轮次）：`6016a33b92f887b940776d40b2f9a97a9667ec23b9f196d6425918dd736976c7`。
  最终 Tier 1 完整轮使用 `9f5aa2838f5b4c3f46d88332e7586863a8e0218b6ef5d73a0e59106ea07608e3`；
  该差异仅为此后更新的六语言“推荐组合”提示文本，性能代码未变。

## 方法

- 使用 `model-update-skip` exerciser 的 wheel 工作负载：真实 AWT 滚轮事件 → 原生重绘 → EDT barrier；
  每条腿 200 个事件（校准轮 16 个），OFF/ON/ON/OFF 四腿，每轮重启宿主。
- 画布固定为 1513×1252，滚轮前后恢复精确 camera raw 状态；每腿用 Robot 抓取画布父区域 PNG，
  同时记录整窗哈希。跨轮视觉比较使用画布内模型区域 `x>=500`，以排除偶发出现在左侧空白边距的
  无关黑色浮窗；Tier 1 校准轮的整窗哈希与基线完全相同。
- 资源采样：250 ms 间隔的进程 CPU、heap used、working set、GC；不使用 JFR、不强制 GC、不等待 GPU。
- 最终组合通过 `config.json` 的 `launcher` 字段实际生效，而不是只靠 `-D` 覆盖。
- 隔离 home 会在启动时弹出 Cubism 试用欢迎对话框；测试使用本地的 `LocalDialogAgent` 自动点击
  “继续使用”，避免把对话框误判为模型加载超时。该 agent 只点击明确的继续/试用欢迎按钮，不点击购买、
  激活或取消。

## 结果

### 完整 200 事件/腿（最终对照）

| 配置 | 模式 | 腿均值 ms | p50 ms | p95 ms | 模型视口像素 |
|---|---|---:|---:|---:|---|
| 基线（仅允许 modelUpdateSkip 腿切换） | OFF | 324.8 / 269.3 | 308.1 / 267.8 | 442.7 / 301.6 | 基线 |
| 基线 | ON | 231.2 / 229.0 | 230.2 / 230.4 | 258.1 / 256.4 | 基线 |
| Tier 1（8 项精选，config.json） | OFF | 100.1 / 106.0 | 99.1 / 99.1 | 111.7 / 151.7 | 与基线 0 差异 |
| Tier 1（8 项精选，config.json） | ON | **73.0 / 71.6** | 72.8 / 71.5 | 78.9 / 75.4 | 与基线 0 差异 |

- ON 腿相对基线 ON 腿：**约 3.17× / 3.20×**；p95 约 258→79 ms、256→75 ms。
- OFF 腿：基线 324.8/269.3 → Tier 1 100.1/106.0 ms（说明精选组合中除 modelUpdateSkip 之外的
  渲染/上传/蒙版项本身就有大幅收益）。
- 资源（同轮采样，非精确归属）：基线 heap used 均值约 4.0 GB、working set 约 8.86 GB；
  Tier 1 heap used 均值约 3.2–3.5 GB、working set 约 7.96 GB。CPU（单核归一化）从约
  150–155% 降到约 122–149%，没有出现“更快但更吃 CPU”的反向证据。数据受宿主负载影响，
  不作为普遍内存承诺。

### 校准筛选轮（更快、用于归因）

| 配置（在 modelUpdateSkip 之外叠加） | ON 腿均值 ms | 与基线的模型视口差异 | 说明 |
|---|---:|---:|---|
| 基线（仅 modelUpdateSkip） | 193.7 / 190.1 | 0 | 基线 |
| Uniform 位置缓存 + 帧末延迟 GL | 106.2 / 98.9 | 0 | 约 1.9× |
| 上面 + 上传省略 + 网格 Setup 省略 | 83.3 / 76.1 | 0 | 约 2.3× |
| 上面 + 剪贴蒙版去重 + 包围盒裁剪 | 99.5 / 92.8 | 0 | 蒙版清屏像素节省 96.7% |
| Tier 1（8 项精选） | 86.1 / 80.2 | 0；整窗哈希也等于基线 | 推荐组合的校准证据 |
| Tier 1 + 分配/纹理缓存三项 | 77.3 / 73.3 | 0 | 三项无主指标收益，保留为可选 |
| Tier 1 + meshInterpolation | 102.6 / 105.2 | 0 | 视觉一致但 wheel 更慢，不推荐 |
| Tier 1 + shaderUniform + matrixScratch | 102.5 / 107.1 | 0 | 视觉一致但 wheel 更慢，不推荐 |
| Tier 1 + incrementalUpdate + affecters | 103.5 / 103.9 | 0 | 纯相机缩放更慢；编辑场景另评 |
| 全开（含 async、meshInterp、shader、matrix、旧缓存等） | 124.3 / 95.2 | **43,582 像素差异** | 否决；`warpPositionProjection` 安装失败 |
| 全开但关闭 async | 92.8 / 93.6 | **43,582 像素差异** | 说明差异不是 async 造成的；整组组合仍否决 |

补充：

- 只开 `uniformLocationCache`、不开 `mesaGlThread` 时，帧末延迟检查不可用
  （`uniform-seam-unavailable`），单独 Uniform 缓存没有稳定收益，反而可能更慢。
  推荐始终把 Uniform 位置缓存与帧末延迟 GL 检查一起开启。
- 只开 `mesaGlThread`、不开 Uniform 缓存时，延迟检查为 INACTIVE；Windows 上还会失去
  启用它的前提。
- `clipMaskScissorClear` 在本模型的 wheel 轮次中每腿记录约 16.26 亿基线清屏像素，
  实际清屏约 0.53 亿，节省 96.7%；这是精选组合里很明确的填充/带宽收益。
- `meshSetupElision` 在 modelUpdateSkip ON 腿省略了 13,374 / 40,212 次 buffer refresh
  （约 33%），OFF 腿省略 0，符合依赖关系。
- `asyncReadback` 的日志为 `pipelined=ACTIVE oneFrameLatency=true`；自带像素自检无 mismatch，
  但本轮没有稳定的额外吞吐收益，且有“画面晚一帧”的视觉/交互代价。
- 增量更新在 wheel 上模型视口一致，但比 Tier 1 慢；它的价值在参数/变形器/层级编辑，
  不在纯相机导航。artmesh 拖拽探针本轮被 1 像素画布尺寸身份检查
  （expected 1513×1252 / actual 1513×1253）挡住，未产出新的拖拽/Undo 结论；增量更新的
  既有 5.3.04 Undo/Redo 像素验收另见 `validation/incremental-update-host-probe/`。

## 推荐配置

### Tier 1（默认推荐）

`validation/windows-cornflower-performance-20261105/tier1-config.json`，核心字段：

```json
"launcher": {
  "modelUpdateSkip": true,
  "uniformLocationCache": true,
  "uploadElision": true,
  "meshSetupElision": true,
  "mesaGlThread": true,
  "clipMaskDeduplication": true,
  "clipMaskScissorClear": true,
  "extendedInterpolationIncrementalSetup": true,
  "incrementalUpdate": false,
  "asyncReadback": false,
  "meshInterpolation": false,
  "shaderUniform": false,
  "matrixScratch": false,
  "imageArchiveReuse": false,
  "floatArrayParseCache": false,
  "textureUploadPreparation": false,
  "warpPositionProjection": false,
  "inputPathElision": false,
  "zgc": true,
  "memoryProfile": "system"
}
```

依赖/组合说明：`mesaGlThread` 在 Windows 上只安装帧末延迟 GL 检查（Mesa 多线程仅 Linux/Proton），
且需要 `uniformLocationCache`；`meshSetupElision` 需要 `modelUpdateSkip`；`clipMaskScissorClear`
依赖 `clipMaskDeduplication`。`uploadElision` 与 `uniformLocationCache` 可以同时开启：网格内容
比对会停用，但线段叠加层拆分仍有效。

### Tier 2（重度编辑）

`validation/windows-cornflower-performance-20261105/editing-config.json`：Tier 1 +
`incrementalUpdate=true` + `incrementalUpdateAffecters=true`。用于参数/变形器/层级编辑密集的会话；
纯导航会话建议留在 Tier 1。

### 可选/不建议

- `asyncReadback`：仅在接受“画布晚一帧”时开启；本轮不作为默认推荐。
- `inputPathElision`：仅 Wine/Proton 输入路径有意义，原生 Windows 无收益。
- `meshInterpolation`、`shaderUniform`、`matrixScratch`、`imageArchiveReuse`、
  `floatArrayParseCache`、`textureUploadPreparation`、`warpPositionProjection`：不进入默认组合。
  前三个在本轮 wheel 上更慢；后四个没有主指标收益，且 `warpPositionProjection` 在本宿主安装失败。
- 不要全开：全开组合出现模型视口像素差异，不能作为“无视觉问题”的方案。

## 限制

- 单一模型、单一宿主版本（5.3.04）、单次完整对照；不宣称跨版本或所有模型通用。
- wheel 指标是“事件→原生重绘 barrier”，不是物理显示器 FPS；不覆盖拖拽、参数编辑、Undo/Redo
  的完整交互矩阵。
- 完整轮之间的宿主负载有波动：完整轮观察约 3.2×，相邻校准轮约 2.3×；两者都明显优于基线，
  但不要把 3.2× 当成稳定保证。
- 画布父区域截图偶尔包含左侧无关浮窗；模型区域 `x>=500` 的比较结果、以及 Tier 1 校准轮整窗哈希
  与基线完全一致，是视觉结论的依据。
- 性能证据位于本地 `build/perf-evidence/<run>/plugin-state/`（`wheel-benchmark.txt`、
  `window-leg-*.png`、`run-envelope.properties`）；`build/` 不进入 Git。本报告和两个 config
  位于 `validation/windows-cornflower-performance-20261105/`。

## 本次附带修改

- 六语言 Performance 页“推荐组合”提示更新为：精选大模型组合、编辑组合、异步回读的可选代价、
  以及“不要全开”的实测结论；Uniform 位置缓存提示不再声称“不要与上传省略同时使用”。
- `scripts/preview/launch-cubism-host-probe-benchmark.ps1` 允许 `-JvmOption` 传入 `-javaagent:`
  （本地对话框辅助/测试 agent）。
- `CanvasWheelWorkload` 增加可选的 `-Dturboism.validation.saveWindowShots=true`，把每腿窗口截图
  保存为 `window-leg-N.png`，供跨轮视觉核对。
- 未修改任何性能优化实现；产品性能代码与上述实测 agent 一致，仅推荐文本和验证工具变化。
