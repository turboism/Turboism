# edge-index — T029-EDGE 离线候选验证（test-only）

最小自包含原型：把 `b.a` apply 边应用循环中 `addEdgeIfNotExists` 的逐边线性首命中
扫描，替换为按批构建的一次性首命中边索引（`min<<32|max` → 首个下标）。**仿真层
证据，非 official-equivalence**：官方类只被 `javap` 只读字节码，从未加载或执行。

## 字节码契约来源（官方 5303 JAR `bd0a23b9…`，`javap -c -p`）

`GEditableMesh2.addEdgeIfNotExists(i1,i2,type,z)` → `addEdge(i1,i2,type,false,z)`，
apply 路径恒为 `checkSimilar=false`。确认语义：

| 行为 | 证据 |
|---|---|
| null type → `NullPointerException`("Parameter specified as non-null is null: …") | `Intrinsics.checkNotNullParameter` → `throwParameterIsNullNPE`(kotlin-stdlib 1.7.21) |
| `i1==i2` → 日志 `illegal indexed edge : <i1> - <i2>`(`util/log/a.b`),返回 -1,**不** bump 版本 | addEdge 偏移 8→33 areturn,早于 setEdgeUpdated(71) |
| 端点规范化为 (min,max),丢弃输入方向 | addEdge 75-97 |
| 非退化调用无条件 `_edge_edit_version++` | setEdgeUpdated 仅 `int++`,先于查重 |
| `checkExitingTypedEdge`→`chechExistingEdge_exe(lo,hi,false)`:**0..size-1 线性扫描,端点相等即首命中(不看类型)** | G.javap 3089/1868/1825 |
| 命中后:仅当 `existing.priority < new.priority` 时在**同下标** `set(i, new MEdge)` 原位重定型 | 优先级 LOCKED=40>NORMAL=30>USER_TRIANGULATION=20>AUTO_TRIANGULATION=10>NOT_INITIALIZED=-10 |
| 未命中 append 尾部,返回 append 前 size | addEdge 212-246 |
| `clearAutoTriangulation()` = `_edges.removeIf(type==AUTO_TRIANGULATION)`,保序 | predicate `editableMesh/c` 恒等过滤 |
| apply 循环:每三角形 addEdgeIfNotExists(v0v1),(v1v2),(v2v0),返回值丢弃;`j/a.d()` 六次调用全部在循环外 | b.a 偏移 243-302(progress 40/132/156/173/190/312) |

未建模(不在 apply 可达路径):`checkSimilar=true` 分支(`checkCross_exe`/
`checkSimilarEdge_exe` 需要几何数据)、`addEdge` 其它 flag 组合、`b.a` 的
triangulation 计算阶段本身。

## 等价定义（验收口径）

逐**操作**比较，非终态：apply 批内逐边锁步——每次 addEdgeIfNotExists 调用后比对
返回值或异常类+真实消息、完整有序边列表（端点+类型逐位）、版本计数、事件序列
（退化日志/重定型/进度标记）。覆盖：初始重复边、反向端点、退化边、类型替换与抑制、
极值下标、null type、畸形 triangle 行（null 数组/null 行/长度 0/1/2/超长行，按字节码
实读顺序——length-2 行先完成 (v0,v1) 再于 (v1,v2) 读处 AIOOBE）、确定性取消注入
（ArmCancel 武装 `progress()` 在指定 d() 次数后抛 ModeledCancel——模拟
`jp.noids.framework.e.a` 的传播边界；取消点位 190/312 已由字节码证实，生产异常类型
本身不在仿真层声称等价）、固定种子随机流（seed 1/7/42/199/2026，含 clear/apply/
armCancel/progress 交错）。首处分歧即打印最小反例并 FAIL——反例保留、候选停止，
不降低标准。取消后脚本继续执行，验证中止后的后续使用行为两边一致。

索引生命周期：`beginBatch` 在 clear 后构建；`endBatch` 在 finally 中执行（索引是
候选内部 scratch，释放不依赖正常终止且不产生宿主可见事件）；批外直调时索引惰性
构建（非线性回退，语义恒为索引）。

## 已捕获反例（保留记录+最小回归用例）

首轮 random-seed-1 step 1490 曾真实分歧：`Clear`（`removeIf` 压缩列表）后批内索引陈旧，
indexed 命中越界下标而 native 返回正常——证明"仅比对终态"不足以发现此类缺陷。修正为
`clearAutoTriangulation` 使批索引失效（真实 apply 顺序恒为 clear→build→adds，该修正
不改变 apply 内行为，只覆盖批外直调的防御路径）。最小确定回归用例已固化为
`clear-invalidates-index-regression`（Add→Clear→Add 命中移位后的存活边）。

## 运行

```bash
bash validation/mesh-triangulation-hash/edge-index/run.sh
```

编译到 `build/edge-index/compile.XXXXXX`,跑 `EdgeIndexSelfCheck` 与
`EdgeIndexBenchmark`(含建索引与分配成本;报告 `referenceEndpointComparisons` 与
`indexBuildEntries`/`indexLookups` 三个不同量纲的计数,不相除、不设速度阈值,
全样本输出含 A/B 交错)。对比数字仅为合成成本;不外推宿主收益/上界,不能分解
原 50s apply 观测(真实 `a.a(progress)` 三角化计算仍可能主导)。
不改已有 meshhash patch/agent/构建,不动生产、SDK、Runner、timing 工具,不启动宿主。
