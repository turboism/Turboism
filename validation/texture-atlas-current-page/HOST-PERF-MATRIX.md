# HOST-PERF-MATRIX：三宿主 × 双数据集 × 三实现实机性能矩阵

## 范围声明

在本机隔离队列（GE-Proton10-34，任务专属 cgroup，单槽串行）中完成 **54 次真实自动排版调用**：Cubism 5.2.03 / 5.3.02 / 5.3.03 × circle / geometry × 100/500/1000 图 × native / MaxRects-BSSF / Dalsoo Polygon，**每格 N=1**。

这是此测试环境、此计时边界下的单次测量，不是统计中位数，也不是所有模型的性能保证。circle（同尺寸）与 geometry（异尺寸）是两个独立分布，结论不得互相外推。

## 计时与内存口径

- `uiMs`：对话框 OK 的 ActionEvent 派发 → 进度窗 `jp.noids.framework.e.a.f` `SHOWING_CHANGED=false`。仪器化墙钟，含约 13ms 输入探针开销（`uiInputProbeMs` 单列）。
- `methodMs`：排版方法本体入口→返回的精确耗时。
- `heapMB`：探针内 `MemoryMXBean` 50ms 采样的 heap used 峰值。
- `cgMB`：任务专属 cgroup 的 `memory.peak`，覆盖整个 Proton/JVM 进程树（含 Cubism 本体与 Wine 基础设施），**是进程级口径，不是算法占用**。

原始数据：`host-perf-matrix-samples.csv`（含 methodMs、heap、cgroup、窗口期 memory.current 峰值、GC 增量、inputHash、run id），`host-perf-matrix-summary.json`。

## 正确性核验

- 每格 `placed=count`、`scale=1.0`、无 overflow、输出有限且在页内。
- 分支归属：native 格 `branch=native`，插件格 `branch=handled`（dalsoo/maxrects 实际接管，无静默回落）。
- 同一 `dataset:count` 的全部 9 个 run `inputHash` 完全一致，即三宿主三实现吃的是同一 fixture。

## Circle（同尺寸圆，8192² 页）

UI 墙钟 ms（OK→进度窗关闭）：

| 宿主 | 数量 | native | MaxRects | Polygon |
|---|---:|---:|---:|---:|
| 5203 | 100 | 252 | 216 | 1,306 |
| 5203 | 500 | 1,004 | 306 | 167,612 |
| 5203 | 1000 | 2,511 | 512 | 1,347,980 |
| 5302 | 100 | 264 | 243 | 1,291 |
| 5302 | 500 | 1,041 | 355 | 164,659 |
| 5302 | 1000 | 2,710 | 477 | 1,392,547 |
| 5303 | 100 | 278 | 228 | 1,290 |
| 5303 | 500 | 941 | 324 | 166,334 |
| 5303 | 1000 | 2,568 | 380 | 1,436,883 |

## Geometry（异尺寸，8192² 页）

| 宿主 | 数量 | native | MaxRects | Polygon |
|---|---:|---:|---:|---:|
| 5203 | 100 | 2,155 | 273 | 1,337 |
| 5203 | 500 | 193,046 | 446 | 49,671 |
| 5203 | 1000 | 1,228,413 | 652 | 633,211 |
| 5302 | 100 | 1,743 | 288 | 1,402 |
| 5302 | 500 | 192,693 | 472 | 86,713 |
| 5302 | 1000 | 1,243,873 | 556 | 741,601 |
| 5303 | 100 | 1,712 | 245 | 1,138 |
| 5303 | 500 | 189,211 | 568 | 83,171 |
| 5303 | 1000 | 1,258,442 | 734 | 630,819 |

## JVM heap used 峰值（MB）

| 数据集 | 数量 | native 范围 | MaxRects 范围 | Polygon 范围 |
|---|---:|---:|---:|---:|
| circle | 100 | 329–696 | 253–458 | 487–530 |
| circle | 500 | 562–1,117 | 341–517 | 832–1,318 |
| circle | 1000 | 1,390–1,561 | 631–707 | 1,533–1,673 |
| geometry | 100 | 682–1,135 | 287–424 | 406–516 |
| geometry | 500 | 1,472–1,512 | 659–962 | 1,240–1,541 |
| geometry | 1000 | 1,324–1,697 | 747–1,047 | 1,343–1,475 |

cgroup 进程树峰值全部落在 2.3–4.7GB，三实现间无稳定差异（Cubism 本体+Wine 基底占主导）。逐格数值见 csv。

## 基础设施抖动与重试

5 格首次尝试失败并重试一次成功，证据保留于 `~/TurboismValidation/atlas/`：

- `5302 circle-100-polygon`、`5302 geometry-500-new`、`5303 circle-500-native`：JVM 启动期挂死（日志停在 core enable，CPU≈0），1800s 超时被杀。
- `5303 geometry-1000-polygon`：首次 30 分钟超时（打包后段近零 CPU），重试以 2700s 完成（uiMs=630,819）。
- `5303 geometry-1000-native`：进程 rc=1 自行退出，重试完成（uiMs=1,258,442）。

超时/失败不计入比值；上表每格均为一次成功 run 的实测值。

## 结论

1. **MaxRects 全面最快**：全部 18 格 methodMs ≤ 0.5s（geometry-1000 也仅 0.28–0.49s），heap 峰值普遍最低。
2. **native 在 geometry 上急剧恶化**：500 档 ~3.2 分钟，1000 档 ~20 分钟；circle-1000 仅 ~2.3s。同算法跨数据集差异 ~550×，确认分布相关。
3. **Dalsoo Polygon 存在严重的规模瓶颈**：100 档 ~1.1s 可用；circle-500 ~2.8min / geometry-500 ~1.4min；**circle-1000 ~23min、geometry-1000 ~10.5min**。circle-1000 上 polygon 比 native 慢约 570×（1,347s vs 2.35s）。三宿主一致复现，是算法复杂度问题而非环境噪声。这与"宿主签发 FREE=18 档候选 × 每顶点×每已放顶点×每角度接触搜索"的组合复杂度一致，根因分析另案处理。
4. **宿主版本间无显著差异**，量级一致；native 慢例的堆峰值 ~1.3–1.7GB，未接近上限。

## 后续：内核空间索引优化（矩阵完成后实机复测）

矩阵暴露 dalsoo 规模瓶颈后，对 `Bin` 做了两项**严格保持输出**的优化：

- 已放多边形按 `outBb` 入均匀网格（页 ÷32 的 cell），`isFeasible` 只查与候选 bbox 相交 cell 内的多边形——跳过者必然被 `overlapStrict` 自带 bbox 测试拒绝，语义不变。
- 顶点接触候选先做平移后 bbox 的出界检查，通过才分配平移环并做可行性测试（Dalalah / Abey / corner-lattice 三条路径同改）。

离线签名验证（rect/lshape/mixed × 300–500 × FREE/QUARTER，含未放满触发 lattice 回退的用例）：4 例 placement 签名逐字节不变，耗时 0.5–2.5× 缩短。

实机复测（5.3.03，同一 fixture，同队列口径，N=1）：

| 格 | 优化前 uiMs | 优化后 uiMs | 加速 |
|---|---:|---:|---:|
| circle-500-polygon | 166,334 | 31,683 | 5.3× |
| circle-1000-polygon | 1,436,883 | 162,946 | **8.8×** |
| geometry-500-polygon | 83,171 | 13,599 | 6.1× |

三格均 placed=count、scale=1.0、branch=handled。注意：优化后 circle-1000 的 JVM heap 峰值升至 ~5.4GB（优化前 1.5GB）——吞吐放大使单位时间分配量上升，GC 压力随之内升。

**剩余差距是结构性的**：候选枚举仍是 O(已放数 × 候选顶点 × 已放顶点 × 旋转档)，circle-1000 优化后 163s 对 native 2.3s 仍慢 ~70×。进一步提速需要削减候选集（会改变落位决策，输出不再逐位一致）或换内核策略——属于设计取舍，不在本次"保输出优化"范围内。
