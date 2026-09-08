# Circle 500：密集当前页、区域并行和固定倍率验证

本批次在隔离宿主内，通过原生“更改纹理尺寸…”将模型副本的当前页从4096²改为1024²，仍只对原生传入的500张图执行当前页排版。环境、核心 JAR 身份和隔离规则见 [HOST-AB.md](HOST-AB.md)。

**功能验证通过，但不是新的正式性能基准。** 自动倍率的串行/并行批次各8次（原生4、新实现4）；固定倍率对照各自使用新进程及原始副本，单次调用。样本量、预热与切换分布不支持用本批次更新 README 的160次基准表。

## 自动倍率确实缩小并全部放入

margin=3、网格模式、允许旋转，自动倍率 requestedScale=0。

| 实现 | 放入 / overflow | 最终倍率 |
|---|---|---:|
| 原生 | 500 / 0 | 0.3450134771 |
| 新实现，parallel=false | 500 / 0 | 0.3826196053 |
| 新实现，parallel=true | 500 / 0 | 0.3826196053 |

新实现保留的线性倍率约比原生高 **10.90%**；并非靠更小比例换速度。原生与新实现的 margin 启发式不同，不能直接宣称逐像素同质量或把本批次耗时相除当作等质量加速比。

- 两个批次全部输入哈希相同（每次先原生 Undo）；原生输出哈希稳定，新实现串行/并行输出哈希也完全相同。
- 全部几何检查通过：矩阵有限、无重叠、不越页、item 与可视 LayerRef 一致。
- 初始 item transform 为 null 是正常状态；验证探针保留该事实，不伪装成单位矩阵。

## 并行不是只勾选 UI

验证探针对生产 `CurrentPageTextureAtlasPlanner.plan(..., boolean)` 的真实第三个参数进行观测，并对 `bestPacking` 记录线程和调用次数；未替换生产算法。

- 串行每次观测到 `plannerParallel=false`，`bestPacking` 调用1次，在宿主调用线程执行。
- 并行每次观测到 `plannerParallel=true`，`bestPacking` 调用43次，实际使用 `ForkJoinPool.commonPool-worker-1/2/3` 以及宿主调用线程。
- 说明区域任务真实执行，不是一个无效开关。最终输出与串行一致，意味着本样本没有获得质量收益。

保留四次新实现方法耗时（按运行顺序，ms）：

- 串行：289.3982、51.2359、235.2989、35.7271。
- 并行：356.2473、76.9528、193.4771、58.4196。

存在启动/切换干扰且为不同进程的小样本，不能作稳定速度比。连续调用的两次并行值均高于对应串行值；**不能宣传并行总是更快，默认仍应关闭**。后续可依据模型分布评估分区失败、额外试探与线程调度成本，不凭此小样本直接改启发式。

## 固定倍率：不偷偷缩小，overflow 保持原变换

页1024²、margin=3、网格、固定100%（requestedScale=1）、禁止旋转。两种实现使用各自全新进程和相同原始副本，进入函数时输入哈希一致。

| 实现 | 输入 | 放入 | overflow | 最终倍率 | 单次方法耗时 ms |
|---|---:|---:|---:|---:|---:|
| 原生 | 500 | 51 | 449 | 1.0 | 628.1432 |
| 新实现 | 500 | 81 | 419 | 1.0 | 81.4713 |

- 两侧实际保持倍率1，未采用 legacy 已知的失败后乘0.8行为。
- 几何及 item/LayerRef 检查通过。
- 对每个 overflow 项额外比较输入/输出：item transform 与可视 LayerRef transform 均不变。
- 新实现仅返回当前页81个 placement，其余419个实例交给原生 overflow；未搜索下一页。
- 此处放入数量不同、各只有一次测量，**不计算性能加速比**。

## 必须保留的未解决问题：overflow 后一次 Undo 不恢复原输入

较早尝试复用同一个会话进行固定倍率 A/B 时，第一次原生调用返回449个 overflow；点击一次原生“复原”后，下一次调用的输入只有51个，不再是500个。输入哈希门禁中断了测试，没有把这两次不等输入混算。

这发生在原生模式之后，不能直接归因为新 planner 的回归。原生调用方 `com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.v$a.run()` 字节码显示：

- BC 101 调用排版方法；
- BC 105–181 逐项消费 overflow；
- BC 160 创建 `jp.noids.design.layer.b.s`，BC 168 调 `b(true)`，BC 173 直接 `redo()` 移除 layer；
- BC 182–238 更新列表与视图。

还需进一步区分原生历史边界和异步完成时机，不能把“一次 Undo 可复原有 overflow 的整次操作”列为通过。当前以独立原始副本完成固定倍率验证，既没有绕过哈希门禁，也没有擅自修改宿主 Undo 或移除流程。

## 证据与复现

结构化摘要：[host-dense-summary.json](host-dense-summary.json)。原始结果、UI状态和源码版本对应的 agent 构建留在本机：

| 场景 | 任务目录 |
|---|---|
| 自动串行，8次 | `build/texture-ab-500-xrz1dbmf/` |
| 自动并行，8次 | `build/texture-ab-500-wg863vnn/` |
| 固定1、禁止旋转，新实现独立副本 | `build/texture-ab-500-jo1yq90c/` |
| 固定1、禁止旋转，原生独立副本 | `build/texture-ab-500-hb95zz5s/` |
| 被输入哈希门禁拒绝的试测 | `build/texture-ab-500-o6p_9si2/` |

```bash
python3 validation/texture-atlas-current-page/run-host-ab.py --layers 500 --rounds 4 --page-size 1024
python3 validation/texture-atlas-current-page/run-host-ab.py --layers 500 --rounds 4 --page-size 1024 --parallel
python3 validation/texture-atlas-current-page/run-host-ab.py --layers 500 --rounds 1 --implementation new --page-size 1024 --fixed-scale-one --no-rotation
python3 validation/texture-atlas-current-page/run-host-ab.py --layers 500 --rounds 1 --implementation native --page-size 1024 --fixed-scale-one --no-rotation
```

全部通过批次均已停止所属 prefix 与 Xvfb，记录的源 JAR/BAT/模型哈希以及未保存副本哈希未变。没有改动正式安装或源模型。新增探针只属于 validation；额外的 planner 观测会产生少量测量成本，不应与早期未带此观测的阶段计时混算。

尚需：legacy 同入口比较、完整保存重开、多页不变性、有 overflow 的历史恢复边界，以及更丰富尺寸分布下的并行质量/性能验证。
