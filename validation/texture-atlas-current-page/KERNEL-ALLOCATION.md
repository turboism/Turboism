# Dalsoo candidate allocation and cancellation (2026-10-20)

针对用户报告的“进度不动、内存持续上涨”，本轮在独立 Java 内核中复现并修复
候选分配膨胀与取消检查不足。**尚未复现用户实际模型的宿主现场，也未确认持续
保留对象的内存泄漏。** 此报告的时间、内存均不代表 Cubism 全流程或真实复杂
模型的普遍性能保证。

## 根因与修改

- `Bin.cornerLattice` 原先在每个旋转角度物化 `(2N+1)^2` 个坐标数组。
  1000 个已放项对应 4,004,001 个候选；即使下一个图形明显放不下，仍先分配
  整个列表。现在只保存两条坐标轴，按原顺序遍历其笛卡尔积；候选存储由
  O(N²) 降为 O(N)，枚举时间仍为 O(N²)。
- Dalalah 平移与 Abey 旋转/平移的缓冲轮廓改用搜索局部的可复用数组。
  评分凸包仍持有独立的原始轮廓坐标，避免缓冲区复用改写已保存的最优结果。
- 页内均匀网格使用固定槽位列表，避免高频查询中的整数装箱及散列表查找。
- 凸包评分的点包含判断仅保存交点 X 坐标，不再创建平移后的整环及交点小数组。
  保留原运算顺序、边界及浮点下溢行为。
- 搜索内部检查取消及线程中断。原先主要在每个图形开始时检查，单个复杂图形
  的搜索无法及时停止，`shutdownNow()` 发出的工作线程中断也被忽略。
  两个新增取消测试在修复前均失败、修复后通过。

未修改候选顺序、评分、倍率或间距契约。未添加默认超时或减少搜索质量。
取消修复针对内核传入的取消标志及线程中断；**不宣称已接通宿主 UI 的取消按钮**。

## 同输入对照

基线：`c3d454042283e10634793bb6b31246a3d89a4e95`。
OpenJDK 17.0.20，本机 Linux，单线程，初始堆 32 MiB、最大堆 512 MiB；
每格新输入，预热 3 次、测量 7 次取中位数。计时只覆盖 `PolygonPack.pack`；
输入构造、签名和独立 `Area` 重叠/边界验证位于计时之外。
`allocatedMB` 实际单位是 MiB，通过 `ThreadMXBean` 读取当前线程分配字节，
**不是峰值堆、存活对象大小或宿主 RSS**。每版本各一轮，短时计时受 JIT/调度影响。

页面 300²，L 形 60 项、星形 25 项；缓冲轮廓为以中心放大 5% 的合成轮廓，
不是对任意形状都满足固定像素间距的通用膨胀算法。固定倍率，允许 overflow。
NONE/QUARTER 使用 Dalalah，FREE 使用 Abey。

| 形状 | 旋转 | 前 / 后耗时 ms | 前 / 后分配 MiB |
|---|---|---:|---:|
| L 形 | NONE | 31.481 / 27.287 | 19.968 / 2.064 |
| L 形 | QUARTER | 83.398 / 66.473 | 80.591 / 9.879 |
| L 形 | FREE | 37.499 / 34.285 | 46.929 / 13.751 |
| 星形 | NONE | 27.342 / 23.312 | 13.201 / 0.755 |
| 星形 | QUARTER | 124.113 / 117.056 | 51.283 / 2.113 |
| 星形 | FREE | 69.637 / 62.445 | 42.752 / 7.038 |

六格输出签名与基线全部一致，放入数量、旋转和平移未变化；临时分配下降
70.7%–95.9%，耗时下降 5.7%–20.3%。这些是合成内核样例，不能外推为
截图模型的密度、耗时或多线程内存上限。

原始数据：[before](kernel-allocation-before.csv)、[after](kernel-allocation-after.csv)。

## 受限堆与连续排版

`--fallback`：1000 个固定小矩形 + 一个放不下的大矩形，页面 4096²，QUARTER，
最大堆 64 MiB。基线在 `cornerLattice` 抛出 `OutOfMemoryError`；修复后完成，
保留全部固定项并正确报告一项 overflow。原始耗时、分配量与退出码见
[before](kernel-fallback-before.txt)、[after](kernel-fallback-after.txt)。
这是存储复杂度的复现，不是用户模型，也不意味正常场景都能在 64 MiB 下运行。

`--retention`：60 个 L 形，连续排版 40 次，每 5 次释放结果并请求 Full GC，
最大堆 64 MiB。基线与修复后均在预热后稳定于约 1.36 MB 存活堆，未见随次数
持续增长。见 [before](kernel-retention-before.csv)、[after](kernel-retention-after.csv)。
因此，此探针支持“高分配及瞬时大候选列表”，不支持“已证实或已排除整个宿主泄漏”。
若真实模型仍卡住，需要采集宿主线程栈与堆占用/GC 数据继续定位。

## 复现

以下命令不加载宿主，无第三方运行依赖；从仓库根目录运行，构建文件自动清理：

```sh
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --ref c3d454042283e10634793bb6b31246a3d89a4e95
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --mode fallback --heap-mb 64
python3 validation/texture-atlas-current-page/run-kernel-allocation-bench.py --mode retention --heap-mb 64
```

最后两条加上同一 `--ref` 可运行基线；基线 fallback 预期退出码为 1（OOM）。

验证：插件全部 39 项测试通过、格式检查通过。包括几何差分、六组基线结果
签名回归、轮廓/间距/锁定等既有规划测试及取消回归。

```sh
./gradlew --offline :plugins:atlas-dalsoo-polygon:test :plugins:atlas-dalsoo-polygon:spotlessJavaCheck
```
