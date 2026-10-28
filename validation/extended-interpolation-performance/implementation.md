# 本轮局部刷新补充

最新 [Normal 固定点跳过生产报告](p3-local-refresh/production.md) 优先说明当前实现与候选包身份。Normal 切片可在完整输入/目标状态命中时省去重复计算，并逐次重放实际官方通知；原生 force、广播、轴顺序、dense grid 和 Morph 全量路径保留。下面已有阶段的证据继续保留原包身份。

# 拓展插值优化实施与验证

目标 **5.3.04**；完成本轮内核验证日期 **2026-10-27**。本报告替代调查阶段 README 中关于“当前覆盖范围”的描述；历史测量与原始清单保留。

已实现 P1/P2 的主要内核优化、P3 的首元素检查与单次结构重建 GUID 索引。**已通过真实 Editor 的设置保存与独立重启验收，已通过六组合活体源点内核差分及同 JVM 独立文档保存重开，真实鼠标与全网格 bank 历史恢复、父子级联几何、真实 GL 像素对照及独立进程模型重开均已补充通过；尚未实现细粒度失效图或惰性派生网格；不能称作 P0–P5 全部完成。** 默认性能开关保持原样（关闭），未部署至官方安装或修改用户模型。

## 实现范围

- `NativeMeshInterpolationBridge`：从仅 ArtMesh 椭圆扩展为 **ArtMesh / Warp / Rotation × ELLIPSE / SNS_CURVE**。
- ArtMesh、Warp 使用真实位置数组，复用每次调用内的源向量和输出向量，避免逐形态逐顶点创建 PointRef/位置列表；仍调用各自官方 `zero/blend/postBlend` 保留不透明度、绘制顺序与颜色等属性。
- Rotation 单独适配 `originX/originY`；官方点引用长度为 1，曲线只作用于原点。angle、scale、反射标志等继续走官方 blend。原点写回保留 `old*0 + new*1` 运算顺序及一次 `setUpdated()`；不伪造网格数组。
- 椭圆沿用已有 `EllipseWeights` 的每调用参数预计算；**没有新增跨调用系数缓存**。SNS 保留官方计算器、初始化和求值顺序，没有采用椭圆系数或近似曲线；完成完整 reset 审计后，在单次 common 内复用一个新建 SNS 计算器；仅开放/无拐角，方法结束即丢弃。每个顶点仍按官方 initialize 重建控制点和样条段，未引入跨调用共享或近似数学。
- 右端普通键保持一次逆序扫描；用身份映射替代成对别名遍历。拒绝非有限源坐标、形状不匹配、所有者不匹配、未知类型，以及涉及写入目标的别名；拒绝发生在修改前。
- `MeshInterpolationTransformer`：只在已固定哈希的 `updateInterpolatedForms_exe(Z)Z` 中，将 `getAllExtendedKeyforms()` 的一次调用改成选择首个非空 bank，接下来的原生 `firstOrNull` 不变。Normal 非空且首项为 null 时也不转到 Morph。完整列表的其他调用保持原语义。
- `NativeFormLookupBridge` / `StructureLookupPatch`：仅在一次原生 `setupFormStructure_Normal` 调用内建立 GUID 索引，先普通键后 Normal 扩展键，保留重复 GUID 的首项优先规则。索引在原生完成形态创建后才建立，正常或异常退出均释放，支持嵌套调用，合计超过 65,536 条时回退原生线性查找。没有跨 rebuild 的持久缓存，也不改 force、EditVersion 或失效规则。
- 禁用时首检查构造完整有序快照，common 与 GUID 查找留在原生路径；安装关闭后停用桥。工件 SHA、class pins、首次定义和类来源门禁保留。

实现文件：
[Bridge](../../runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/NativeMeshInterpolationBridge.java)、
[Transformer](../../runtime/src/main/java/dev/turboism/adapter/cubism/interpolation/MeshInterpolationTransformer.java)。

## 5.3.04 同进程测量

真实官方配套 JAR，JDK 17.0.20，headless，堆 256 MB–1 GB。3 个普通锚点，每区间 64 插入点，共 131 forms；ArtMesh/Warp 256 个几何点，Rotation 1 个原点。每路径预热 300 次、5 轮各 100 次，取轮均值中位数。官方腿禁用桥，优化腿经过实际安装后的原生方法入口；没有把优化代码当作自己的 oracle。

| 对象 | 曲线 | 官方 ms/次 | 优化 ms/次 | 官方 bytes/次 | 优化 bytes/次 |
| --- | --- | ---: | ---: | ---: | ---: |
| ArtMesh | 椭圆 | 9.186 | 1.000 | 3,633,192 | 17,376 |
| ArtMesh | SNS | 4.205 | 2.289 | 5,906,432 | 2,271,928 |
| Warp | 椭圆 | 9.324 | 1.023 | 3,362,976 | 17,376 |
| Warp | SNS | 4.363 | 2.366 | 5,636,256 | 2,271,928 |
| Rotation | 椭圆 | 0.308 | 0.097 | 25,312 | 20,592 |
| Rotation | SNS | 0.140 | 0.082 | 34,164 | 18,904 |

这是一轮环境下的内核对比，有 JIT、系统负载波动；绝对时间不能与先前不同进程测量直接相减，更不能外推原生 Windows 帧率或真实拖动延迟。bytes 是当前线程分配量，不是保留堆或 RSS。旋转变形器绝对收益较低。

首元素检查另用 10,000 条重复合法引用隔离列表复制成本：Normal-only、Morph-only 都从 **80,112 降至 40 bytes/次**。对象未绑定参数，调用在前检查退出；这是列表复制验证，不是完整有效模型的刷新计时，也不是 Morph Target 全链路验收。

证据：[最终 common 测量 CSV](evidence/final-common-benchmark/summary.csv)、[原始测量](evidence/final-common-benchmark/5.3.04.log)、[源码指纹及命令](evidence/final-common-benchmark/manifest.json)。上一轮数据保留在 `evidence/implementation/benchmark/`，不能把两轮绝对耗时之差归因于 SNS 复用；复用仅减少计算器外层包装，主要样条分配仍在。

## 结构重建测量与多轴验证（本轮新增）

仅反射调用真实原生私有 `setupFormStructure_Normal`，不计 common 几何曲线求值。固定 Warp 四点、每轴三个源锚点，12 次预热、5 轮各 10 次，取轮均值中位数；同进程官方腿禁用桥。索引优化额外占用有界临时表，目标是减少 GUID 扫描，**不是减少 dense grid 分配**。

| 参数轴 | 每区间插入 | 派生 forms | 官方 ms/次 | 索引 ms/次 | 官方线程分配 MB/次 | 索引线程分配 MB/次 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 4 | 112 | 4.433 | 2.841 | 1.446 | 1.401 |
| 2 | 8 | 352 | 6.752 | 5.690 | 5.992 | 6.004 |
| 3 | 4 | 1,304 | 35.816 | 27.798 | 35.237 | 35.296 |
| 3 | 8 | 6,832 | 544.158 | 376.985 | 513.626 | 513.977 |

MB 按十进制计量，分配包含原生临时数据，不代表 retained heap 或峰值 RSS；测量受 JIT/GC/主机负载影响，不能外推 UI 帧率。三轴高点数仍有大量构建分配，支持保留 P4 预算/结构设计任务。

证据：[测量汇总](evidence/structure-benchmark/summary.csv)、[原始日志](evidence/structure-benchmark/5.3.04.log)、[当时源码及命令](evidence/structure-benchmark/manifest.json)。该指纹是格式化前实现，后续最终矩阵需单独留存，不能将历史指纹当作当前源码。

新增 `StructureRuntimeProof` 在每个已收录版本实际创建三类对象×两种曲线×1/2/3 轴，共 18 对原生/优化结构。连续修改源形态、检查无变化 skip，修改插入数与 scale 后全量重建，比较派生坐标 raw bits、属性、绝对 EditVersion 和轴绑定键。还验证重复 GUID 优先级、missing 返回 null、重建间无陈旧索引、预算回退，以及原生异常后作用域释放。此项是官方源模型/内核验证，仍不是 Editor UI 验收。

## 回归与版本范围

先验证目标 5.3.04，再扩展到已收录的 **20 个稳定版**：5.0.00–5.0.06、5.1.00–5.1.03、5.2.00–5.2.03、5.3.00–5.3.04。不包含 4.x、beta 或未来版本。

实际使用 `VerifiedMeshInterpolationInstaller`、Transformer、每版完整官方 JAR 和独立 JVM，**20/20 通过，每版 164 个 common/拒绝/检查用例，加 18 个多轴结构用例；每版 3,444 次成功桥调用、36 次已启用状态拒绝**。每次官方 oracle 均显式禁用桥：

- 三种对象、两种曲线，3/5 个普通锚点，1/4/16/64 插入点，非均匀键、退化相等键、正负/零 scale、signed zero、固定种子随机坐标。
- 每个差分用例连续四轮修改源坐标，检查全部坐标的 raw float bits、属性、非默认 multiply/screen 颜色、Rotation angle/scale/反射状态、每个 form 的 EditVersion 增量。
- 椭圆系数开/关；LINEAR、NaN scale、无穷源坐标、目标别名、目标/源别名和不同所有者拒绝前不修改。禁用路径也验证拒绝。
- 核验每版实际转化命中，Normal-only/Morph-only 前检查，close 后官方执行且不再增加命中次数。

这覆盖 common 内核与前检查安装生命周期，**不是全部输入的数学证明，其中新增多轴网格构建由上节单独覆盖；仍不覆盖 Morph Target 编辑链、Undo/Redo、父子变形或保存重开**。

最终关联回归：runtime **102 tests / 0 failures**；bootstrap 整套 **236 tests / 0 failures / 27 skipped**；安装器配置往返集成 **1 tests / 0 failures**，`previewBundle` 构建通过。原有 licensed 20-JAR 字节码测试仍按仓库规则排除，由上述独立安装矩阵补充真实 JAR 证据。Windows 安装器 Python 检查在 CF2b 停止；以 HEAD 原脚本及原 PowerShell 文件复核出现相同失败，属于既存检查失败，未计作通过。XML 和失败对照日志见 `evidence/final-checks/`。

证据：本轮[含 SNS 复用与多轴结构的最终矩阵 manifest](evidence/final-installed-matrix/manifest.json)、[摘要](evidence/final-installed-matrix/summary.json)。上一轮[版本矩阵 manifest](evidence/implementation/matrix/manifest.json)、[测试清单](evidence/implementation/test-summary.json)保留作历史基线。日志、XML、源码指纹均在同目录归档；不归档官方 JAR。

## Fast 代理的刷新范围结论

使用 Paseo **Fast** 配置，在同 Project、同 Workspace 内调查；任务完成后已归档该代理，没有创建或遗留额外工作树。报告及探针见 [刷新范围调查](refresh-scope-investigation.md)。

经复核，进入有效刷新后，原生会重算对象内非线性轴的组合切片，再调用 Morph 扩展更新；它没有按本次变化的源键做局部更新。前置 early return、LINEAR 和空组合仍有跳过。两个已知官方直接调用点传入 `true`，绕过 GUID/EditVersion 的“未变跳过”。UI 静态链还存在直接父 Part 范围的派发：仅直接子对象，筛选已启用扩展，不递归子 Part。普通参数播放通常走已有网格求值，这属于静态调用链判断，尚无本轮 UI 动态计数佐证。

因此“任一源 keyform 变化会刷新该对象整套拓展形态”的核心判断成立；还可能因父 Part 派发而扩大重算范围。**本轮没有将 force=true 改成 false**：类型/点数/scale、Morph 数据与历史恢复可能并不都由普通源 EditVersion 完整表达，不能据此直接认定兄弟对象重算可全部删除。

## 剩余优化顺序与放行条件

1. **P0 / P5 的受控 5.3.04 宿主验收已补齐，扩大放行仍有边界**：已建立专用 5.3.04 托管任务，在任务副本中覆盖三类对象×两种曲线的源点拖动、普通调参、配置/拓扑变化、Undo/Redo、父子变形、保存重开。已记录调用计数、线程分配、缓存数量、堆/GC，并核对几何与画面；单次冒烟不承担尾延迟结论。重复真实 mesh 拖动 A/B（两曲线各 12 组）另获通过，含准备/模型更新等待的经验 p50：ELLIPSE 250.3→218.2 ms，SNS 231.6→198.8 ms，不能外推 FPS；详见宿主 README。其他版本使用内核/安装矩阵覆盖，未宣称逐版 UI 验收。现有 `warp-position` 探针验证的是旧的源 PointRef 投影，不能充当新增 bridge 的验收；旧 Cat 拖动探针绑定 5.3.02/03 和历史哈希，也不能直接用于目标版本。已通过真实 Performance 复选框点击保存与独立重启、无 JVM 覆盖的 installed 证明，见 `../extended-interpolation-settings-host/README.md`。几何宿主已补建受控变形器结构：三类对象×两曲线的活体源点坐标 raw bits、属性和 EditVersion 增量与官方一致；官方保存并以同 JVM 新文档重开后，三类对象全部形态 bank 坐标/属性一致，桥接增加 1,924、拒绝 0。普通调参 delta=0。job `e0d52911-3a84-4167-881e-8138644620dc` 冒烟通过，身份/原始模型不变/退出/清理均通过，见 `../extended-interpolation-host/README.md`。后续 job `91503114-91fd-45aa-918b-0daefea12f5f` 已修正测试重挂接坐标，真实视图拖动及全部网格 bank 坐标/属性的 Undo/Redo 恢复通过，六组合内核和保存重开继续通过；最终 job `6593f1c5-42b6-4d1f-8504-73f699678bdb` 已通过父子级联子网格 raw bits 对照及真实 MSAA FBO 像素一致性（姿态变化正对照 3,177 像素），独立进程重开 job `e72ef3ea-83eb-4afa-8c2c-f14bb42b0b9f` 的三类对象全部 bank 摘要与层级一致。原始 fixture 不变、官方身份与 cgroup 清理通过。参数面板 Slider、专门 Morph 编辑历史与长时间 FPS 尚未声称覆盖。
2. **P3 进度**：首元素检查和单次 rebuild GUID 索引已落地；下一切口为父 Part 无效重复刷新。先观测派发原因和普通/Morph/绑定/拓扑的完整代数，证明哪些 force 调用可跳过；未知或结构变化保持全量。优先对象级跳过，再研究对象内部依赖传播，保持原生逐轴顺序。
3. **SNS 进度**：完整 reset 状态已审计，单 common 内的开放/无拐角计算器复用已实现并通过最终 20 版本安装差分。闭曲线→开放曲线存在原生状态残留，不允许混合输入共享池。主要样条拟合仍逐顶点分配，是否继续改数学内核须由真实交互热点决定。审计见 [报告](sns-reuse-audit/AUDIT_REPORT.md)。
4. **P4 条件性重构**：先取得多轴真实缓存规模/堆峰值基线，再决定预算、回收或分块/惰性缓存。当前仍构建完整 dense forms；本轮减少的是刷新开销，没有消除 Cartesian 增长，也不减少作者设定的插入点。宿主最终任务已记录三对象 1,575 个形态、坐标长度合计 52,336 bytes、堆池峰值和 GC；坐标长度不是 retained heap。辅助索引 65,536 条预算及异常/正常退出即清已通过矩阵，原生形态裁剪或惰性实现另立设计，不能用辅助索引预算冒充原生网格内存上限。
5. 完成上述宿主验收后再评估默认启用。当前构建可供审阅，尚不具备默认开启的验证证据。

## 复核

```sh
python3 validation/extended-interpolation-performance/run-runtime-proof.py \
  --ref /opt/dev/projects/turboism/cubism-ref \
  --out /tmp/extended-installed-new --installed \
  --asm /path/to/asm-9.7.1.jar \
  --versions 5.0.00 5.0.01 5.0.02 5.0.03 5.0.04 5.0.05 5.0.06 \
  5.1.00 5.1.01 5.1.02 5.1.03 5.2.00 5.2.01 5.2.02 5.2.03 \
  5.3.00 5.3.01 5.3.02 5.3.03 5.3.04
```

另用新输出目录加 `--benchmark` 测量，避免在计时期间构建。探针在自己的输出目录运行，以隔离官方类初始化产生的 `data/`；不启动 Editor Java main。

## 本轮补充：辅助预算与回收证明

新增 `NativeFormLookupBridge.CacheStatistics` 只含数值快照；超过单次 65,536 个输入形态时回原生，每作用域计一次预算拒绝，总数使用 long 避免 int 溢出。退出时同时清理 token 内强引用，保留嵌套及异常退出语义。

最新生产源码重新通过 20 版 installed 常规矩阵（`evidence/budget-installed-matrix/`），所有源码指纹匹配。另通过独立生命周期矩阵（`evidence/cache-lifecycle-matrix/`）：20 版 × 12 场景，旧 bank、释放后的 owner/extension/当前 bank 及强持有已退出 token 的宿主引用全部可回收，返回后活跃作用域/索引条目均为零。详见 [缓存预算与生命周期](cache-lifecycle.md)。

5.3.04 三轴 6,832 形态累计构建线程分配约 514–534 MB，同 JVM 显式 GC 后活堆增量约 3.23–4.19 MB；二者测量口径不同，不能将前者当作驻留缓存。当前不改变原生完整派生网格，不执行驱逐。以上新增生产改动已构建新候选 bundle（SHA `923d33d61a3b2e3a2c0beea43dc21aa83b979ffb7b628e9a444fd9bb52c5e51e`），宿主任务 `ce38cb88-834a-4f38-89f9-fc9a32cfb0d2` 通过 Warp 两曲线各 12 组配对真实拖动、全 bank 历史恢复、六组合源点差分、级联、像素与官方保存重开，返回后辅助索引活跃计数为零。ELLIPSE p50 改善但本轮 p95/p99 变慢，完整数值与限制见宿主报告；不宣称尾延迟全面改善。旧 bundle 的宿主证据继续保留其原始身份。相关 102 项 runtime、236 项 bootstrap（27 skipped）及配置往返 1 项无失败，previewBundle 成功。整套 runtime 扩大尝试卡在无关系统屏幕捕获，线程栈已归档且未计作通过。

最新包的独立 Editor 会话重开也已通过：job `944d3022-b079-4a8c-91c1-49640b7dfc57`，三对象全部 bank 摘要/属性/父子关系一致，JVM 与文档身份独立，来源副本不变、正常退出和清理通过。证据 `../extended-interpolation-host/evidence/warp-budget-cold-pass/`。

P3 依赖传播设计已交付并审阅，见 [专项报告](p3-dependencies/REPORT.md)：按原生轴顺序追踪 source→slice→下游派生依赖；三个目标版 ELLIPSE 合成用例几何/部分属性输出一致但版本增量不同。保持仅设计，不拦截 force、不改 Morph。此前“下一切口”属于候选后续实现，不代表本轮应直接启用。完整计划收口见 [验收核对](plan-acceptance.md)。

## P3 续推：保留通知的数值复用

实现与最终隔离证据见 [P3 曲线数值复用](p3-curve-cache.md)。新增 32 条 / 8 MiB 数值 LRU，首次只记签名、重复才保存结果，完整输入 raw bits 匹配后复用几何求值，所有原生属性混合、实际写回和 EditVersion 更新保持。Rotation 不缓存，force/Part 广播/完整网格不变。最终源码通过两套 20 版本差分，新增每版 432 快照比较绝对版本并验证宿主引用回收；相关 runtime 106、bootstrap 236（27 skipped）、配置往返 1 无失败，previewBundle 成功。连续变化的 ELLIPSE 存在约 2%–9% 额外内核开销，重复/混合场景改善范围与分配变化均按原始基准报告，不外推 FPS。当前包已完成 5.3.04 真宿主复验及独立 JVM 重开，缓存命中 5,302 次；旧包结论保留原身份。三类对象全部 bank raw bits、属性和层级重开匹配。Paseo Fast 只读复核代理已归档，未新建工作树。
