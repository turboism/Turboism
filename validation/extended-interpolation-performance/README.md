# Cubism 拓展插值性能调查与优化计划

当前 P3 续推结果见 [Normal 局部刷新生产落地](p3-local-refresh/production.md) 和 [曲线数值复用](p3-curve-cache.md)；以下为最初原生问题确认及当时的覆盖审计，后续实现以 [交付记录](delivery.md) 为准。
> 本文是优化实施前的调查快照；下文“本次只新增调查”、覆盖审计及旧探针预期均对应基线。获授权后的代码改动、最终回归和剩余门槛见 [实施报告](implementation.md)。旧 coverage 探针用于复现历史拒绝边界，当前覆盖验证请用 `run-runtime-proof.py`。

目标：**5.3.04**；调查日期：2026-10-26。生产代码基线 `2673cee19b1421e105d07fd109c47c1889758076`。

确认问题存在。现有优化**部分覆盖，默认未启用，不能视作已经解决**。先复现 5.3.04，再运行仓库收录的 20 个稳定版；这 20 个版本均存在该机制和随点数增长的实际开销。这里的全版本范围是 **5.0.00–5.3.04**，不包含 4.x、beta 或后续版本。

本次只新增调查、探针和证据，没有启用或修改生产优化，没有改动用户模型。

## 5.3.04 实测

使用本地官方 `Live2D_Cubism.jar`，SHA-256：
`c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d`。

直接调用真实 `CExtendedInterpolationExtension.updateInterpolatedForms_common`，无 mock、无替代数学内核。固定一个对象、3 个普通参数键、256 个几何顶点、参数范围 -60°–60°、scale=1；改变**每个相邻参数键区间插入的点数**。这里的插值点不是网格几何顶点。

JDK 17.0.20，headless，堆 256 MB–1 GB；每组预热 1000 次，测量 5 轮，每轮 100 次，以下是轮均值的中位数。线程分配量来自 ThreadMXBean，**不是峰值堆占用**。

| 对象/插值 | 每区间插入点 | 该线总形态数 | 单次耗时 ms | 单次分配 MB（十进制） |
| --- | ---: | ---: | ---: | ---: |
| ArtMesh 椭圆 | 1 | 5 | 0.356 | 0.263 |
| ArtMesh 椭圆 | 4 | 11 | 1.255 | 0.424 |
| ArtMesh 椭圆 | 16 | 35 | 3.608 | 1.066 |
| ArtMesh 椭圆 | 64 | 131 | 11.903 | 3.633 |
| ArtMesh SNS | 1 | 5 | 0.585 | 0.730 |
| ArtMesh SNS | 64 | 131 | 6.616 | 5.906 |
| Warp 椭圆 | 1 | 5 | 0.382 | 0.246 |
| Warp 椭圆 | 64 | 131 | 8.841 | 3.359 |
| Warp SNS | 1 | 5 | 0.407 | 0.720 |
| Warp SNS | 64 | 131 | 3.979 | 5.638 |

证据：[ArtMesh 原始日志](evidence/target/5.3.04.log)、[Warp 原始日志](evidence/coverage/WarpCoverageProbe.log)。Warp 使用 15×15 格，即 16×16 个控制顶点。

这些是隔离 JVM 中的刷新内核测量。不同进程的时间有波动；不拿它们推算 Editor FPS、拖动延迟或固定加速承诺。首次短预热测量用于确认问题，随后因发现 JIT 噪声增加预热并冻结上述结果。

## 原因与证据强度

以下调用关系来自目标官方类的反编译，并用 `javap -c -p` 核对；原厂反编译源码没有加入仓库。

1. **插入点会变成真实派生 keyform，而非仅增加一条曲线的采样标签。** `CExtendedInterpolationExtension$c.f()` 生成扩展键，`setupFormStructure_Normal` 调用 `KeyformGridSource.addKey_exe$cubism` 建立扩展网格。对有至少 3 个普通键的非线性轴，扩展键数为 `e = k + (k−1)r`。多个轴按键组合展开，完整网格槽数为 `∏eᵢ`，不是各轴点数相加。例如三轴各有 3 个普通键、每区间插入 4 点，是 `11³=1331` 个槽，原始只有 `3³=27` 个。具体原生重建还会经过逐轴中间状态，不能把槽数公式当作精确分配次数。这部分是代码机制确认，未进行多轴整模型计时。
2. **每次刷新有大量与形态数×顶点数相关的临时对象。** common 方法给源和目标 forms 调用 `getAllPointRef()`，再建立位置向量列表；随后按每个几何顶点调用 `ExtendedInterpolationType.b()` 创建计算器，初始化源点列表，再逐插入键求值和写点。椭圆参数系数也会在各顶点上重复计算。分配随点数上升已由上表直接确认；本次没有用采样 profiler 给各类对象单独分摊 CPU/分配百分比。
3. **原生有“未变跳过”，但变更粒度粗。** `updateInterpolatedForms_exe(false)` 比较普通 forms 的 GUID/EditVersion；完全未变时可返回。任何普通 form 变更或 force=true 时，会调用 `updateInterpolatedForms_Normal()` 遍历各轴和组合，再更新 Morph Target 拓展形态。不能描述成“所有帧都无条件重算”，也没有证据说普通调参一定会触发全缓存重建；源形状编辑、结构变化与普通播放要分别测量。这是目标方法控制流确认，本次内核探针不覆盖 UI 触发频率。
4. **另有可消除的额外遍历。** 原生对每个插入键用 `com.live2d.util.l.a(list,index,predicate)` 向后查找右端普通键，一段含 r 个插入点时查找总量可达 O(r²)。现有 ArtMesh bridge 已用一次逆序扫描预计算 right[]；但其目标数组别名校验仍是 O(T²)。`getAllExtendedKeyforms()` 用 Kotlin `plus` 拼接完整普通/变形目标列表，即使调用者只取 firstOrNull，也会分配并复制引用；这个前置成本不能由只改 common 方法消除。这些是代码确认的次级候选，尚未单独计时，不能据此宣称它们是主热点。

总体主线是：**派生网格膨胀 → 单次刷新要处理更多形态 → 每形态每顶点的分配与数学运算放大；源形状编辑时再被较粗的失效范围放大。**

## 现有优化覆盖审计

| 优化 | 当前覆盖 | 缺口/启用条件 |
| --- | --- | --- |
| `MeshInterpolationTransformer` + `NativeMeshInterpolationBridge` | ArtMesh + ELLIPSE；绕过临时点引用，支持椭圆系数预计算 | 默认关闭，需 `turboism.meshInterpolation.enabled=true`；精确工件/类哈希、首次类加载和输入门禁；Warp、SNS、不支持结构回官方 |
| `EllipseWeights` | 将参数相关三角系数移出逐顶点循环；保留原生两段累加/float 舍入顺序 | 已有实现，无需另写一套；每次调用仍准备系数，不减少派生形态或全量失效范围 |
| `WarpPositionProjectionTransformer/Bridge` | common 方法中**第一处源 form 的位置投影**，只接受 Warp form | `turboism.optimization.warpPositionProjection=true` 才请求；仍创建位置向量，目标 point refs、曲线运算、写入及完整网格保留；未单独测它的收益 |
| model-update skip / incremental update | 满足条件时减少无变化帧或对象的插值/变形工作 | 不是拓展缓存构建内核优化；编辑/不支持场景保留原生；相关偏好默认 false，不能据此覆盖源形状变更时 common 的成本 |
| `NativeInterpolationSaveCache` / 派生缓存序列化投影 | 特定保存入口的大缓存处理/显式序列化优化 | 主要针对保存体积、字符串和序列化分配；不减少运行时 dense forms，不覆盖刷新内核；原生菜单保存覆盖不可由 SDK 保存入口推定 |
| `EditorParameterInterpolationAccess` 批量轴设置 | 同一写入事务合并轴设置，减少重复 rebuild | 只覆盖走该 API 的操作，不代表原生 UI 编辑获得同样优化 |

默认开关见 `bootstrap/.../MeshInterpolationHookContributor.java`、`TurboismAgent.meshInterpolationRequested`、`runtime/.../config/PerformanceSettingsConfig.java`；既有保存配置可能显式开启，所以“默认关闭”不等于用户当前实例一定关闭。本次没有检查/修改用户运行中的 Editor 配置。

额外用**当前工作树源码重新编译实际 bridge**，在 5.3.04 直接调用它；固定 256 顶点、每区间插入 64 点，结果如下（同一个 JVM，5 轮中位数）：

| 路径 | 单次耗时 ms | 单次分配 bytes |
| --- | ---: | ---: |
| 官方 | 9.314 | 3,637,480 |
| bridge，关闭系数预计算 | 7.800 | 66,104 |
| bridge，开启系数预计算 | 1.079 | 13,432 |

20 次改变普通源点后的差分通过：所有位置 raw float bits、各 form 编辑版本增量一致。ELLIPSE 命中 1220 次，SNS 拒绝 1 次且编辑版本未改变。Warp 探针另外验证两种插值×两种点数均拒绝，编辑版本不变。

证据：[bridge 原始日志](evidence/coverage/BridgeCoverageProbe.log)、[源码及工件指纹](evidence/coverage/manifest.json)。这是直接 bridge 覆盖验证，**未经过 transformer/bootstrap 安装流程，也不是全部输入的数学等价证明或真实拖动验收**。没有把原生热路径的提速等同于用户问题已经修复。

## 确认目标问题后进行的版本检查

逐个启动独立 JVM，使用各版本完整配套 JAR；固定 64 个几何顶点，点数 1/4/16/64，ELLIPSE 与 SNS，预热 1000 次，5×100 次测量。20/20 运行成功，最高点数相对最低点数的耗时和分配均增加。矩阵用于确认问题跨版本存在，**不用于比较哪一代 Cubism 更快**。

检查的完整版本列表：
`5.0.00–5.0.06`、`5.1.00–5.1.03`、`5.2.00–5.2.03`、`5.3.00–5.3.04`。

同时对五个相关方法生成去掉常量池编号、统一空白后的字节码指纹：

- `setupFormStructure_Normal`、`calcCombination$recCalculateCombination`：20 版一致。
- `updateInterpolatedForms_common`、`updateInterpolatedForms_exe`：分成 5.0/5.1 与 5.2/5.3 两组。common 的差别是工具类混淆名 k/l，未消除上述算法。
- `updateInterpolatedForms_Normal`：5.3.04 新增空目标列表检查/日志，其余 19 版一致；这一保护不优化非空重负载。

字节码指纹只是结构对照，不代替依赖闭包审计或优化安装门禁。跨版本实测是 ArtMesh 两种曲线；Warp 动态测量仅 5.3.04，其他版本 Warp 控制流由共用方法及结构对照支持，**没有宣称逐版本 Warp/UI 实测通过**。

数据：[全部测量 JSON](evidence/matrix/results.json)、[汇总 CSV](evidence/kernel-summary.csv)、[证据校验清单](evidence/manifest.json)。

## 优化计划（以 5.3.04 首发验收）

| 顺序 | 具体工作 | 完成门槛 |
| --- | --- | --- |
| P0 建立真实交互基线 | 独立 host/profile，分别测 ArtMesh/Warp × 椭圆/SNS；普通调参、源点拖动、结构/点数修改、Undo/Redo、打开/保存分开记录。采集 common 调用次数、命中/拒绝原因、缓存 form 数、p50/p95/p99、线程分配、GC 与堆峰值 | 同模型同操作官方/候选 A/B；确认用户卡顿对应哪种触发，不以本内核计时替代端到端延迟；保留当前默认开关 |
| P1 完善已有椭圆路径 | 在 `runtime/.../interpolation` 复用现有 `EllipseWeights`；为 Warp 单独审计 point setter/dirty/grid 副作用并增加受控适配，不直接假定 ArtMesh 数组写入等价；用身份集合代替 ArtMesh O(T²) 别名检测，保留原拒绝语义；参数系数可按结构代数缓存，设置有界生命周期 | 5.3.04 两种 form 的坐标位值/属性/版本及失效通知差分；别名、非有限数、退化角度、±0、未知类型/库、关闭与失败回退测试；未知情况在写入前回官方 |
| P2 覆盖 SNS 的分配热点 | 先保留官方 SNS calculator，减少源/目标点包装和临时列表、复用有界工作区；分别适配 ArtMesh/Warp。只有证明 SNS 对输入的数学性质和舍入一致性后，才考虑预计算，不能套用椭圆系数 | 非均匀键、端点/退化输入、源形状变化的原生差分；证明分配下降和实机收益，不能用数值近似换静默行为变化 |
| P3 减少不必要的缓存遍历/重建 | 给 firstOrNull 使用场景移除整列表拼接；右端键查找预计算；审计 setup 的 GUID 查找/索引和重复 rebuild。随后才设计“普通源 GUID/EditVersion → 受影响轴线/组合”的依赖传播，保留逐轴原生顺序及 morph 依赖 | force、绑定/点数/scale/拓扑变更、Undo/Redo、Morph Target、重开时完整失效；未知依赖全量回退；不能仅按当前参数附近形态推断源编辑影响范围 |
| P4 控制 dense grid 成本 | 先统计并暴露内部缓存预算、检查溢出、清理旧缓存。若大多轴工程仍受内存限制，再单独设计惰性/分块派生缓存及原生访问兼容方案；不默认减少作者设置的插入点 | 文件格式/原生访问/导出/历史恢复完整兼容；此阶段风险高，单独决策与验收，不混进椭圆小优化 |
| P5 逐版本放行 | 先完成 5.3.04 内核+真实交互验收，再对上列 20 个实际工件运行等价/拒绝/安装生命周期测试；按方法及依赖族核验，继续绑定工件与 class pins | 未知工件、晚加载、混合类加载器保持原生；默认启用必须有对应平台冷开和连续父子变形验收，不能仅依据版本号或 common 相似度放行 |

建议先推进 **P0→P1→P2**；这能复用已有成果并补齐明确的 Warp/SNS 缺口。P3/P4 解决的是尚未覆盖的失效范围和组合规模问题，收益需要新基线证明。

## 复现

需要本地官方参考目录，每版为 `<ref>/<version>/*.jar`，JDK 17、Python 3.11+。输出目录必须不存在；原生初始化生成的临时配置留在输出目录内。

```sh
python3 validation/extended-interpolation-performance/run-probe.py \
  --ref /opt/dev/projects/turboism/cubism-ref \
  --out /tmp/extended-target-new --versions 5.3.04

python3 validation/extended-interpolation-performance/run-probe.py \
  --ref /opt/dev/projects/turboism/cubism-ref \
  --out /tmp/extended-matrix-new --vertices 64 --repeats 100 \
  --versions 5.0.00 5.0.01 5.0.02 5.0.03 5.0.04 5.0.05 5.0.06 \
  5.1.00 5.1.01 5.1.02 5.1.03 5.2.00 5.2.01 5.2.02 5.2.03 \
  5.3.00 5.3.01 5.3.02 5.3.03 5.3.04

python3 validation/extended-interpolation-performance/run-coverage.py \
  --ref /opt/dev/projects/turboism/cubism-ref \
  --out /tmp/extended-coverage-new
```

本次检查：目标/20 版本内核探针全部退出 0；bridge/Warp 覆盖探针退出 0；新增 Python runner 的 Ruff 检查通过。没有为仅新增诊断材料而运行整库构建，也没有做新的实际 Editor 鼠标、视觉或保存重开验收。
