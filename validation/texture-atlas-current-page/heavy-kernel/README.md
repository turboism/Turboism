# Heavy 轮廓排版实测与预览（2026-10-21）

本次测试是独立 Dalsoo 内核的**合成压力样例**，不是用户模型或 Cubism 实机。
图片直接绘制实际计算出的坐标，未做二次排版；未放入项明确计入 overflow。

## 实际效果

- [同一组 100 项：QUARTER 与 FREE 对比](heavy-layout-comparison.png)
- [250 项复杂混排：FREE / Abey](heavy-layout250-free.png)

图形包含 32 顶点深凹星形、L 形与细长矩形。页面 4096²，固定倍率；
输入原始轮廓总面积占页面 75%，缓冲轮廓按星形核内的中心放大 5%。
缓冲不代表固定像素间距，也未套用生产规划器的轮廓简化、自动缩放、
多变体并行或宿主写回。所有输入是确定性种子（9931 + count）。

## 结果

OpenJDK 17.0.20，串行运行，初始堆 32 MiB、最大堆 512 MiB。
每格首次 N=1；预览导出另运行一次已完成案例，**不是统计中位数**。
下表时间仅为 pack 及采样线程收尾，独立几何验证、图片生成不计时。

| 输入 | 总顶点 | 旋转 / 内核 | 首次时间 | 结果 | 已放原始轮廓占页 |
|---|---:|---|---:|---|---:|
| 混合 100 | 1418 | QUARTER / Dalalah | 17.23 s | 搜索完成，60/100 | 55.66% |
| 混合 250 | 3518 | FREE / Abey | 39.58 s | 搜索完成，185/250 | 56.87% |
| 混合 500 | 7010 | QUARTER / Dalalah | 60.00 s | 预算取消；当时已放 198 项 | 未校验 |
| L 形 1000 | 6000 | QUARTER / Dalalah | 60.01 s | 预算取消；当时已放 532 项 | 未校验 |
| L 形 2500 | 15000 | NONE / Dalalah | 60.00 s | 预算取消；当时已放 762 项 | 未校验 |

预览导出的复测：混合 100 QUARTER 为 28.00 s，混合 250 FREE 为 51.48 s；
对应落位签名与首次完全相同。另测同一组混合 100 FREE：3.70 s、72/100、
占页 53.67%。FREE 放入项更多，但总轮廓面积更少；数量不能直接作为密度指标。

已完成的案例逐项独立校验身份、有限坐标、页内边界及 Java `Area` 两两交集
（交集面积容差 1e-5 页坐标平方单位）。JSON 的 `status=PASS` 只代表搜索正常
结束且几何验证通过，**不表示全部项目放入或密度最优**。
预算取消不作为排版成功，`placed` 仅为进度计数；其 JSON 中 `coverage=0`
是未测值的占位，不能解读为实际覆盖率。生产代码未添加默认 60 秒超时；
预算仅由本压力测试传给内核的取消回调实现。外部还有额外 45 秒退出边界。

## 速度与内存结论

同一组混合 100 QUARTER 在原提交
`c3d454042283e10634793bb6b31246a3d89a4e95` 耗时 35.08 s、累计分配
2518.74 MiB；当前首次 17.23 s、55.88 MiB，结果签名一致。
当前预览复测则为 28.00 s、129.25 MiB，短时 JIT/GC/系统负载影响显著，
不得用一次测量宣称稳定倍数。

三个大样例虽然都未在 60 秒内完成，但正常响应取消。其累计分配分别为
1152 / 5189 / 1377 MiB，而 25 ms 采样的 pack 堆峰值约 21–22 MiB，释放后
Full GC 约 1.33 MiB；显示高分配周转与搜索计算代价，未复现持续保留对象增长。
这些数值**不是宿主 RSS 或峰值内存上限**，也不能排除宿主层泄漏。

**当前不足：复杂轮廓的大规模搜索仍慢，完整结束案例仍有明显空洞和 overflow；
不能据此承诺参考截图那种密度与速度。** 继续优化需要针对候选集和落位策略，
仅减少分配不足以解决计算规模。

## 复现与原始证据

本目录 `*-after.json` / `*-before.json` 为首次运行指标，`.log` 为进度日志，
`.command.json` 为相应命令（混合 500 命令如下）。`*-preview-run.json` 是
导图复测指标，`*-layout.json` 包含真实轮廓坐标和 overflow ID。

```sh
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --mode heavy --count 500 --rotation QUARTER
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --mode heavy --shape lshape --count 1000 --rotation QUARTER
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --mode heavy --count 250 --rotation FREE --abey --layout-json /tmp/mixed250-layout.json
MPLCONFIGDIR=/tmp/atlas-heavy-matplotlib python3 validation/texture-atlas-current-page/render-heavy-layout.py
```

绘图脚本读取本目录导出的三份 layout，不生成或修改任何落位。
