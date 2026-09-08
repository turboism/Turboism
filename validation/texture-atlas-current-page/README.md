# 当前纹理页排版调查与验证记录

## 最新收尾与证据路径

q 防御及三版本 record pins 已修复；修复后的 Circle/Geometry 100、500 串行/并行各一次均通过实机验收。最新结果见 [500 矩阵](HOST-UI-500-PIN-FIXED.md)、[100 矩阵及根因](INGRESS-DIAGNOSIS.md) 和插件三语 README。下文保留早期调查的发布限制及历史数据，不表示已完成所有交互/保存重开场景。

入库证据仅脱敏本机路径：`${VALIDATION_USER_HOME}` 为验证账号主目录，`${ATLAS_WORKTREE}` / `${PROJECT_ROOT}` 为相应源码根目录，`${GOLDEN_PREFIX}` 为配置的原始 prefix，`${LEGACY_WORKTREE}` / `${LEGACY_RESEARCH_ROOT}` 为对应历史源码/反编译材料目录。CSV 的 job、run 路径后缀、原始数值及所有 hash 未改；源 evidence 本身未改。未脱敏副本本地保留在忽略目录 `build/atlas-merge-private-originals/`。占位符不是可直接运行的 shell 配置。

旧 `run-host-ab.py` 仅供解释历史数据，入口已禁止执行。任何新增实机验证只能经统一 `scripts/preview/host_validation.py` 队列，仍须用户授权。
## 结论与交付边界

用户确认的任务是：**只处理原生本次传入当前页的图像集合；只生成当前一页，未放入项交回原生 overflow**。不枚举整个模型、不规划下一页，不追求整个纹理集最优解。

本工作树已实现单页 BSSF、固定/自动缩放、旋转、单页区域分治并行，以及 item / 可视 LayerRef / DATA_SCALE 同步写回和读回检查。生产算法注册测试不再预塞假 planner，验证了真实注册的实现和无需重启的 parallel 设置变化。明确的完整图集 SDK 请求仍走原有完整规划器，与原生单页入口分开。

**尚未达到可发布/实机验收状态：**

- SDK 阻塞已解决：用户明确批准演进新合同，新增 [v8 审阅说明](../../sdk/api-contracts/sdk-api-v8-review.md) 和独立基线，v7 保留为历史审计。v8 当前精确门禁、v7 历史门禁、旧客户端链接、验证器 mutation selftests 和负例均通过；不宣称与 v7 的 record 形状完全兼容。
- 已完成 Circle 四档真实原生/new 同边界 A/B，共160次，输入哈希复原一致、分支/几何检查通过。每侧4次预热后16条平衡热样本中位数比为 1.41× / 8.18× / 21.32× / 78.96×；不是 UI 端到端、密集缩放或 legacy 保证。切换与连续调用分别统计，详见 [HOST-AB.md](HOST-AB.md)。
- 已在隔离 Cubism 5.3.03 上实际打开 Circle 100/500/1000/2500 模型副本并触发新实现，四档均写回 APPLIED；100 档撤销改变画布、重做恢复相同视口像素，保存命令已执行且副本哈希改变。未完成保存重开及全部状态等价性验收。详见 [HOST-SMOKE.md](HOST-SMOKE.md)。原安装及记录的源文件哈希未变。
- parallel 并非总是更快；以下数据明确保留其慢例。尚不能承诺所有输入下不慢于 legacy。
- 后续500图/1024²密集实机验证已覆盖实际自动缩小、真实ForkJoin区域任务、固定1禁转及overflow原变换不变；新实现自动倍率0.3826（原生0.3450），固定1放入81（原生51）。并行该样本无质量收益且连续调用更慢；一次原生Undo未恢复overflow输入仍待调查。详见 [HOST-DENSE.md](HOST-DENSE.md)，不将此小样本混入160次性能基准。

## 原生与 legacy 合同

详细独立报告见 [native-contract-review.md](native-contract-review.md)，含三个版本官方 JAR 身份和方法字节码偏移。报告基线是 `84beadfd404509036e17a076a7c022e150f2c6ad`，不是对全部未提交实现的审阅。

- 正常输入 wrapper 的 item.scale=1；已有图集比例不参与新比例相乘。
- 固定比例 >0 时绝不偷偷缩小；<=0 表示自动，结果封顶1；拒绝非有限参数。
- mesh 模式使用原始局部 AABB；完整图像模式使用原图宽高，不能把 CRect 传给 GRectF getter。
- 旋转补偿使用原始分数高度，而不是取整、缩放或带 padding 的高度。
- 只写已放入项；全部 overflow 也是正常结果。原生调用方负责后续移除命令，adapter 不提前改容器。
- LayerRef 原生会求逆保存、再求逆读取；必须走 setter 并允许数值误差，不能只验证 item 数据。
- legacy 固定比例失败后乘 .8、共享可变候选、吞部分写回失败仍成功等缺陷不照搬。

本实现保守整数装箱和每侧固定像素 margin 不承诺与原生缓冲启发式逐像素一致。自动缩放是有界启发式搜索，不承诺数学全局最优；并行分区失败时仅回到**同一页**串行比较。

## 性能数据：纯内核，不是原生/UI A/B

环境：OpenJDK 17.0.20+8；i7-9750H，12逻辑 CPU；`-Xms128m -Xmx512m`。每个配置独立 JVM，10次调用，前3次预热、后7次中位数；单轮采集、未固定 CPU 核心，不能视为跨机器或最坏情况保证。原始10次数据见本地 `evidence/*-final.txt`（按仓库规则不入 Git）；下列复现步骤可重新生成测量。

两侧相同 `Random(51)`，宽高各为16–96像素；2048²页、固定1、不旋转、margin0、串行、全部放入。legacy 来自 `3c2f0fbbbaafa689afa32334941950790e0624dd` 的离线抽取内核，不加载宿主；只去掉宿主依赖并增加计时/几何断言。新实现调用生产 `CurrentPageTextureAtlasPlanner`（包括最终不可变 plan 创建/校验），不含映射、写回和界面刷新。

| 输入数 | legacy 串行 ms | 新单页串行 ms | 本数据集内核比值 legacy/new |
|---:|---:|---:|---:|
| 100 | 35.350 | 6.429 | 5.50× |
| 300 | 400.996 | 10.680 | 37.55× |
| 500 | 2287.714 | 24.724 | 92.53× |

这是**相对抽取 legacy 串行内核**，不是相对原生，更不是 UI 端到端加速比。两边最终均全放入、固定比例均为1，不通过缩小或漏项获取速度。

新实现500项、512²页、允许旋转、每侧 margin1：

| 模式 | 串行 ms / 放入 / scale | 区域并行 ms / 放入 / scale |
|---|---|---|
| 固定1 | 13.440 / 52 / 1 | 24.893 / 62 / 1 |
| 自动 | 193.314 / 500 / .347943977 | 489.790 / 500 / .351092791 |

这里并行更慢，但放入数或最终比例不同，不能假装是完全等质量速度对比。自动并行的多策略与串行补偿成本仍需优化；默认保持串行。legacy 固定比例 overflow 有偷偷缩小缺陷，本表不与其混算。

### 本轮定位的热点

1. 已全放入后仍继续跑其余5种排序；同一比例下 count/area 得分已无法提高，现提前结束。
2. 每插入一项都对所有旧空闲矩形重新执行二次复杂度包含检查；旧集合已经去包含，现只比较涉及新分裂矩形的配对。
3. 已找到全量可行比例后，较差的部分结果不可能胜出：保持相同排序和比例试探，改为在保留区面积不可能容纳或首个放置失败时终止该次可行性试探；并行结果不再重复做同一轮最终优化。

继续推进这一项时，500项自动并行同批前后热中位数 **682.410ms → 420.986ms**，全部500项、比例 `.351092791` 均保持；300组完整计划也逐字节相同。原始样本在本地 `evidence/auto-parallel-{pretrial,posttrial}.txt`。这是优化前后对照，不与上表不同批次的489.790ms混算；未固定 CPU 核心，仍不能宣称普遍加速。

新增日志区分 `scope=current-page/complete-atlas`、`scale`、`overflow`、`snapshotMs`、`planMs` 和写回 `status/failureCode/applyMs`；计划日志不等于已完成写回，写回状态也不代替原生入口最终结果及 UI/Undo 实机验收。

最后补齐超 int 的 padding 保留区回归后，内部分裂坐标改用 long，外部内容坐标仍受 int 页界校验；300组计划输出仍完全一致。最新500项复测：固定比例/2048²串行 **22.350ms**；自动/512²串行 **172.517ms**、并行 **388.696ms**，放入数和比例未变，见本地 `evidence/final-current-bench.txt`。并行仍有明显调度/多策略成本，因此保持用户可选且默认关闭。

第二项优化前后，对300组随机输入（1–100项、不同页大小/margin/旋转/固定或自动/串行或并行）比对完整计划输出，逐字节相同。输出 SHA-256 均为 `80d7567ac282859dbeed12b0aaf91918a9f372873290c280cba3c51ace5e6ca4`；本地原始输出 `/tmp/texture-page-bench/plans-{before,after}.txt`。这证明该样本中未改变结果，不是普遍等价的形式证明。

## 已执行验证

- 插件全量：38 tests，0失败；包含完整图集 SDK 请求保留、实际注册、live parallel、自动比例质量回归、超 int 的 padding 保留区和分阶段诊断。
- Runtime texture-atlas 相关：83 tests，0失败；新增回滚过程中多个 setter 报错仍继续恢复其他输出且可重试的回归。
- SDK 全量：252 tests，0失败。
- Bootstrap 全量：82 tests，0失败。
- 已改 Java 的 ReadSeek 语法检查无错误；`git diff --check` 无错误。
- 新增回归覆盖 raw fractional pivot、完整图像模式不读 mesh、部分/全部 overflow、可视写入被忽略、一次性 setter 异常、旧状态恢复，以及大坐标下半像素误写不得被容差吞掉。
- runtime **全量**尝试在180秒外部超时被中断，不能列为通过；之后相关82项重新运行通过。
- SDK v7 历史/v8 当前精确 API 门禁通过，v7 编译客户端可在新 SDK 链接运行；用 v7 JAR 对照 v8 基线的负例被拒绝，门禁没有放宽。

## 复现

从仓库根目录执行（B 换成当前工作树实际构建输出根）：

```sh
./gradlew :plugins:atlas-maxrects-bssf:classes --console=plain
B=build/worktree/investigate-texture-sort-native-legacy-performance
CP="$B/sdk/classes/java/main:$B/atlas-maxrects-bssf/classes/java/main"
OUT=$(mktemp -d)
javac -cp "$CP" -d "$OUT" validation/texture-atlas-current-page/CurrentPageBench.java
java -Xms128m -Xmx512m -cp "$OUT:$CP" CurrentPageBench 500 2048 false 1 false 0
java -Xms128m -Xmx512m -cp "$OUT:$CP" CurrentPageBench 500 512 true 0 true 1
# 参数：输入数 页边长 parallel requestedScale rotate margin

# 显式指定已核对 legacy 源码；抽取器先验证完整文件 SHA-256
python3 validation/texture-atlas-current-page/extract-legacy.py "$LEGACY_SOURCE" "$OUT/LegacyTextureBench.java"
javac -d "$OUT" "$OUT/LegacyTextureBench.java"
java -Xms128m -Xmx512m -cp "$OUT" LegacyTextureBench 500 2048

./gradlew :plugins:atlas-maxrects-bssf:test :runtime:test --tests '*TextureAtlas*' --console=plain
./gradlew :sdk:test :bootstrap:test --console=plain
./gradlew checkSdkV7ExactApiCompatibility checkSdkV8ExactApiCompatibility checkTextureAtlasSdkV7Linkage checkSdkApiBaselineTool --console=plain
```

## 后续验收计划

1. SDK v8 合同演进和验证已完成；锚点为 `959ca8c359f24b80c86bb9699c8111d067e75694`，仅本地提交，没有发布。
2. 本地设置绑定、质量与边界验证已完成：包括超 int 保留区、多恢复 setter 异常和分阶段日志。更广泛真实模型的最坏情况成本、实际宿主执行分支和端到端速度继续按下述实机验收执行；日志不含原生入口投影和最终 UI 刷新。
3. 用完全相同的当前页输入、比例、旋转、margin，在模型副本上轮换原生/new/legacy；记录冷/热 P50/P95、放入数、比例、几何、输入投影/规划/写回/刷新耗时，并核实实际执行分支。
4. 校验 Undo/Redo、取消、保存重开与其他页不变。以上通过后才对“确实生效、相对原生加速、至少不慢于 legacy”给最终验收结论。
