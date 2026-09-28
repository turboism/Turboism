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

## 等价定义(验收口径)

逐**操作**比较,非终态:每次调用后比对返回值或异常类+消息前缀、完整有序边列表
(端点+类型逐位)、版本计数、事件序列(退化日志/重定型/进度标记)。覆盖:初始重复边、
反向端点、退化边、类型替换与抑制、极值下标、null、固定种子随机流
(seed 1/7/42/199/2026,含 clear/apply 交错)。首处分歧即打印最小反例并 FAIL——
反例保留、候选停止,不降低标准。

## 已捕获反例（保留记录）

首轮 random-seed-1 step 1490 曾真实分歧：`Clear`(`removeIf` 压缩列表）后批内索引陈旧，
indexed 命中越界下标而 native 返回正常——证明"仅比对终态"不足以发现此类缺陷。修正为
`clearAutoTriangulation` 使批索引失效（真实 apply 顺序恒为 clear→build→adds，该修正
不改变 apply 内行为，只覆盖批外直调的防御路径）。

## 运行

```bash
bash validation/mesh-triangulation-hash/edge-index/run.sh
```

编译到 `build/edge-index/compile.XXXXXX`,跑 `EdgeIndexSelfCheck` 与
`EdgeIndexBenchmark`(含建索引与分配成本;输出比较次数与纳秒)。对比数字仅为
合成成本;不外推宿主收益/上界,真实 `a.a(progress)` 三角化计算仍可能主导 apply 内部耗时。
不改已有 meshhash patch/agent/构建,不动生产、SDK、Runner、timing 工具,不启动宿主。
