# P3 曲线数值复用

目标先验 5.3.04，再验证已收集的 20 个稳定版本。本轮实现的是 common 内核的纯数值结果复用；没有安装 `p3-dependencies/` 中通知不等价的整切片跳过方案。

## 实现边界

- 保留官方 `force=true`、直接父 Part 广播、全部普通/Morph 刷新遍历及完整派生形态网格。
- 每次仍执行原生 `zero / blend / postBlend`，属性从当前源形态重新混合。仅当曲线输入完整相同时复用几何求值。
- 键包含每个源坐标 raw bits、缩放后锚点、插入标记/区间/比例、ELLIPSE/SNS、系数模式、弧度常数，以及宿主 Access 的数值域。完整数组比较，不以哈希相等替代输入相等。
- 命中时按原顶点/形态顺序执行每个实际写回及对应 `setUpdated`，保留原 `old * 0 + value * 1` 运算。独立 present 位图保留求值返回 null 时的跳过行为。
- Rotation 只有一个几何点，继续使用已有原点优化，不纳入本轮数值缓存。
- 非有限源/既有目标在任何写入前退回官方路径；该边界避免实测出现的 NaN 符号位差异。所有差分仍以原生输出为 oracle，不将拒绝等同于跳过工作。

## 预算与准入

候选签名和结果共用 LRU：最多 32 条、估算 8 MiB，单条最多 1 MiB。预算是 Java 数组/条目估算，不是精确 retained heap、RSS 或原生形态内存上限。超过单条预算继续用已有无缓存优化。

首次输入只保存数值签名，第二次相同输入才捕获结果，第三次开始可直接命中。此策略由实际连续编辑基准触发：早期实现首次 miss 就分配完整结果，大网格每次额外约 318 KB。候选同样受预算与退出清理约束。

缓存只持有独立 int[]/float[]/boolean[] 和基本类型，不持有 form、owner、源数组、类或类加载器。关闭 bridge 即清空；未完成计算在关闭后发布会被拒绝。

诊断属性 `turboism.meshInterpolation.curveCache=false` 可保持 P1/P2 内核与索引优化而禁用数值缓存，用于 P3 增量 A/B。产品性能复选框仍默认 false。

## 验收口径

`CurveCacheRuntimeProof` 为每个对象/曲线/系数模式/随机种子组合先独立运行全部 18 步原生序列，再运行优化序列；比较坐标 raw bits、属性和绝对 EditVersion。包含无变化、仅属性/版本变化、未增版本的坐标变化、比例、正负零、负 scale、NaN 目标、系数/曲线切换及零 scale。每版 24 用例、432 快照，ArtMesh/Warp 必须产生真实命中。保持数值结果已缓存时验证 owner/extension/全部 form 的弱引用回收，随后关闭并检查零存储。

性能基准分 repeat（输入不变）、dirty（每次修改源坐标）、mixed（每 4 次调用修改一次）三类。比较 official / uncached（已有优化）/ optimized（新增 P3）；这些是隔离 common 内核数据，不能外推 Editor FPS。最终矩阵、相关回归已归档；宿主结果见下文。

## 最终源码与隔离验收

- [常规安装差分](evidence/p3-regular-matrix/manifest.json)：20 版本全通过，20 个源码/资源指纹与最终生产源码一致。
- [新增数值缓存差分](evidence/p3-curve-cache-matrix/manifest.json)：20 版本 × 24 用例 × 432 快照；每版命中断言和 13 个宿主引用回收通过，21 个指纹匹配。
- [相关回归](evidence/p3-checks/summary.json)：runtime 106 / bootstrap 236（27 skipped）/ 配置往返 1，均无失败。previewBundle 构建成功。
- bundle SHA：`044b0148e395b94ad7d7b76ebe4b3a997796b9659fb318197f0918c15f26d5b4`。
- [只读复核](p3-curve-cache-review.md) 已完成；父代理修正其“首次 miss 零分配/百字节签名”表述。首次 miss 仍分配签名，大小随输入增长。

## P3 增量性能

5.3.04、3 个源锚点、每区间 64 插入点、256 顶点，300 次预热，5 轮 × 100 次调用。表内为各轮每次耗时的中位数，比较已有无缓存优化与新增 P3。官方完整基线、每轮耗时及线程分配见 [原始日志和摘要](evidence/p3-benchmarks/summary.json)。repeat 最终复验无本任务 Gradle/矩阵并发；dirty/mixed 在该轮回归结束后运行。不是随机交错的大样本实验，不将几百分点差异解释为稳定收益。

| 输入模式 | 对象/曲线 | 已有优化 → P3，μs/次 | 耗时变化 |
| --- | --- | --- | --- |
| 不变 | ArtMesh ELLIPSE | 1060.7 → 660.6 | −37.7% |
| 不变 | Warp ELLIPSE | 1289.6 → 969.8 | −24.8% |
| 不变 | ArtMesh SNS | 2478.6 → 920.5 | −62.9% |
| 不变 | Warp SNS | 2371.8 → 989.9 | −58.3% |
| 每次变化 | ArtMesh ELLIPSE | 1089.3 → 1184.1 | +8.7% |
| 每次变化 | Warp ELLIPSE | 1174.4 → 1196.0 | +1.8% |
| 每次变化 | ArtMesh SNS | 2323.5 → 2303.4 | −0.9% |
| 每次变化 | Warp SNS | 2402.5 → 2281.6 | −5.0% |
| 每四次变化 | ArtMesh ELLIPSE | 1202.3 → 1016.0 | −15.5% |
| 每四次变化 | Warp ELLIPSE | 1211.8 → 1122.4 | −7.4% |
| 每四次变化 | ArtMesh SNS | 2334.3 → 1843.3 | −21.0% |
| 每四次变化 | Warp SNS | 2380.0 → 1639.9 | −31.1% |

重复 SNS 分配由 2,257,592 B/次降至 22,016 B/次（−99.0%）。重复 ELLIPSE 分配反而增加约 4.6 KB/次；连续变化输入两曲线均增加约 15.7 KB/次，混合 ELLIPSE 增加约 85.6 KB/次。准入减少 miss 的完整结果捕获，不消除签名开销。Rotation 不使用本缓存，其不同测量腿之间的差异仅作噪声对照，不计为 P3 收益。

结论限于重复或部分重复的输入。不能承诺所有源点拖动更快；每次都变化的 ELLIPSE 场景存在实测开销。整个产品性能开关继续默认关闭。

## 真宿主验收

[当前包宿主证据](../extended-interpolation-host/evidence/p3-curve-pass/origin.json)，job `52a49f7b-0304-4b65-93ed-f2fb878ece10`。官方 BAT、受控副本、六组合源点对照、两曲线真实 mesh/Warp 各 12 对采样、全 bank Undo/Redo/Undo、父子级联 raw bits、GL 像素与姿态变化正对照、CAFF 保存/同 JVM 新文档验证通过；数值缓存命中增量 **5,302**。队列确认原 fixture 不变、官方身份正确、正常退出及 containment 安全清理。

最终缓存共 8 条、估算 11,544 B；过程 2,818 misses、681 evictions，记录为累计观测，不是总体命中率预测。关闭官方 oracle 腿会清空数值条目，不能将每轮 A/B 视为同一常驻缓存连续会话。

以下真实拖动只比较 **官方路径与 P1/P2/P3 合并优化**，不能单独归因于 P3，也不能同旧任务跨会话相减当作增量。耗时包含准备与官方帧等待，仅单模型、每曲线 12 对：

| 真实拖动 | 官方 → 合并优化 p50，ms | 官方 → 合并优化 p95 / p99，ms |
| --- | --- | --- |
| ArtMesh ELLIPSE | 275.6 → 232.7 | 333.8 / 387.4 → 259.6 / 285.6 |
| ArtMesh SNS | 266.7 → 269.4 | 321.0 / 327.4 → 294.8 / 313.4 |
| Warp ELLIPSE | 230.4 → 235.8 | 260.4 / 301.8 → 265.4 / 362.4 |
| Warp SNS | 245.3 → 209.6 | 268.4 / 343.2 → 238.0 / 257.1 |

ArtMesh SNS p50 与 Warp ELLIPSE p50/p95/p99 本轮更慢；保留实测值，不能宣传所有交互改善。独立进程重开当前保存副本也已通过，job `775e8517-d9ad-4f83-ac9d-753664beec91`，三类对象全部 bank 坐标 raw bits、属性、父子层级匹配，且 JVM 启动身份不同；队列正常退出/fixture 不变/安全清理全部通过，见 [重开证据](../extended-interpolation-host/evidence/p3-curve-cold-pass/origin.json)。

后续 [局部刷新可行性实验](p3-local-refresh/README.md) 已通过 Normal 的 20 工件差分；该实验没有进入本包，不能把其计算跳过比例计入上述性能数据。
