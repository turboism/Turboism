# mesh-triangulation-hash — 离线验证切片

T030 之外的独立切片，验证 021 的候选修复：宿主三角化的常数哈希退化。

**边界**：本目录只做离线验证。不定义、不执行、不写回任何官方类；不启动宿主；不发布生产产物。
证据来自自有 fixture（形状镜像宿主已审核的类），官方类只作**只读字节解析**。

## 被验证的缺陷

宿主 `com.live2d.graphics3d.editableMesh.triangulation.l#hashCode()` 的字节码是
`iconst_0; ireturn`（恒为 0），而 `triangulation.h.a` 是 `Set<kotlin.Pair<l, l>>`。`l.equals`
对 `(a,b,c)` 三个 `TriPoint` 接受**全部 6 种置换**，因此哈希必须对置换不变——这正是原作者
直接 `return 0` 的原因。代价是集合中每个元素落进同一个桶：HashMap 冲突超阈值后桶被树化，
`find` 逐层 `equals`，退化为接近 O(n)。

## 修复的不变式与设计

- 哈希必须**对 `{a,b,c}` 任意置换不变**，且与 `equals` 使用的字段一致。
- `TriPoint.equals` 只看 **x/y**；而 `TriPoint.hashCode()` 把 **index** 也算进去（`equals` 忽略它）。
  因此补丁**绝不委托** `TriPoint.hashCode()`，而是直接取 `getX()/getY()`：

  ```
  h = Σ_{p∈{a,b,c}} ( Float.hashCode(p.getX()) * 31 + Float.hashCode(p.getY()) )
  ```

  求和天然对置换不变；只用 x/y 才与 `equals` 一致。
- `l.a/b/c` 只在构造函数赋值（构造后不变），`TriPoint` 经 `GVector2` **可变**。
  因此实机等价性（SC-01）是必须的门禁：若坐标在对象存活期间被改，哈希会失真。

## 补丁器（`CornerTripleHashPatcher`）

fail-closed 且天然幂等：只接受 `hashCode()` 体恰为 `iconst_0; ireturn`、且拥有三个同类型
角点字段 `a`/`b`/`c` 的类。其它一切形状（不同方法体、缺字段/错类型、非本类身份、已打过补丁、
字节不可解析）一律抛 `Rejected`，调用方保留原字节。生成的方法体是直线代码，无需 StackMap。

## 离线证据

```
MESH_HASH_COMPARE original[distinct=1    maxBucket=4000 treeBuckets=1 lookupMillis=426]
                 patched [distinct=4000 maxBucket=5    treeBuckets=0 lookupMillis=1]
MESH_HASH_SELFCHECK PASS elements=4000 hostExecuted=false officialClassLoaded=false
```

| 指标 | 原始（常数哈希） | 修复后 |
|---|---|---|
| 不同哈希值 | 1 | 4000 |
| 最长桶 | **4000**（全部元素） | 5 |
| 树化桶 | 1 | 0 |
| 4000 次 `contains` | **426 ms** | **1 ms** |

契约与拒绝用例（全部通过）：

- 6 种置换的 `hashCode` 相同、互相 `equals`；
- `index` 不同但 x/y 相同 → `equals` 为真，且**补丁后的哈希相同**（证明没有委托 index 敏感的实现）；
- 同时记录 fixture 点自身的 `hashCode` 确实 index 敏感，即宿主契约缺陷本身也被复现；
- 拒绝：已打过补丁、错类身份、错字段类型、非 class 字节、截断字节。

官方只读验形（`OfficialClassProbe`，官方字节仅解析）：

```
OFFICIAL_ENTRY com/live2d/graphics3d/editableMesh/triangulation/l.class
               size=7912 sha256=6f06427c59d3907fe0d4ec80c72a318410d2e5169e18a8263ddfaa526813bd90
OFFICIAL_PATCH CANDIDATE size=8002 defined=false executed=false
MESH_TRIANGULATION_HASH_OFFICIAL_PROBE PASS
```

## 构建与运行

```bash
bash validation/mesh-triangulation-hash/build.sh
# 可选显式指定官方 JAR（默认读本机 Proton prefix 内的 5303 安装）
TURBOISM_MESH_HASH_OFFICIAL_JAR=/path/to/Live2D_Cubism.jar \
  bash validation/mesh-triangulation-hash/build.sh
```

使用 JDK 17、`-Xlint:all -Werror`、ASM 9.7.1（SHA-256
`8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281`）。桶布局证据需要读
`HashMap` 内部，故 self-check 以 `--add-opens java.base/java.util=ALL-UNNAMED` 运行。

## 实机 A/B（5303 + heavy.cmo3，用户真实路径 = 不排版）

`-meshprobe` / `-meshfix` 两个运行标签把本切片接入 T040 场景：两者都安装同一个验证 agent
（`mesh-hash-agent.jar`，自包含、遮蔽 ASM），仪器完全相同，唯一差别是 `probe` 保留原生常数哈希、
`fix` 应用修正后的哈希。agent 只在类字节与固定 SHA-256 一致时打补丁，否则记 `HASH_MISMATCH`。
每次 `GEditableMesh2.updateMesh` 返回前追加一次摘要调用，因此两次运行产出**直接可比**的网格输出摘要。

五组配对、共十次运行，全部 `succeeded` / `PASS` / `normalExit` / `fixtureUnchanged`：

| 配对 | probe（原生哈希）整轮 / OK | fix（修复）整轮 / OK | 配对变化 |
|---|---|---|---|
| 1 | 364.4 s / 144.6 s | 297.6 s / 118.0 s | **−18.3%** |
| 2 | 340.8 s / 123.1 s | 288.8 s / 116.3 s | **−15.2%** |
| 3 | 448.9 s / 145.9 s | 318.1 s / 122.1 s | **−29.1%** |
| 4 | 391.4 s / 150.8 s | 342.2 s / 140.5 s | **−12.6%** |
| 5 | 342.0 s / 120.9 s | 303.3 s / 120.0 s | **−11.3%** |
| **中位** | **364.4 s** / 144.6 s | **303.3 s** / 120.0 s | **−16.8%**（OK 阶段 −17.0%） |

- **5/5 组全部改善**，范围 −11.3% .. −29.1%，均值 −17.3%。
- `tripleState` 在 fix 侧始终为 `PATCHED`，probe 侧始终为 `SEEN_UNPATCHED`；
  观测到的官方类哈希始终为 `6f06427c…`（与固定常量一致）。
- **网格捕获次数十次全部为 719，输出摘要十次全部为 `c23b2a8a…`（逐字节相同）**，
  即 SC-01 等价性在全部配对中成立。
- probe 侧整轮中位 364.4 s，两组历史单次（391 s / 341 s）落在同一噪声带内；收益以配对差值为准。

机制确认（同机同 fixture 的 JFR 对比）：`HashMap$TreeNode.find` 采样 **2014 → 1**；
三角化自身工作量未减少（2161 → 2268 采样），消失的是病态查找开销，与墙钟下降一致。

## 生产路径 A/B（5303 + heavy.cmo3，生产 transformer + 持久化偏好）

与上节不同，这一组让**生产** `VerifiedMeshTriangulationHashInstaller` 在 premain 决定是否安装
transformer；观察 agent（`mesh-hash-agent.jar`，probe 模式）注册在生产 transformer 之后，
因此 `tripleDetail` 记录的是它收到的类字节 SHA-256，即生产 transformer 的输出。

| 运行 | 暂存偏好 | 观察到的类字节 | JFR `TreeNode.find` | 输出摘要 |
|---|---|---|---|---|
| `t011-off-…-meshoff-jfr`（runId `queue-d7b2eb71`） | `meshTriangulationHashFix:false`（config.json 已暂存） | `6f06427c…` 原生 | 17,568 采样 | `c23b2a8a…` |
| `t011-on-…-meshprobe-jfr`（runId `queue-c0f368d3`） | 缺省（默认 true） | `adb7d709…` 生产补丁输出 | 0 采样 | `c23b2a8a…` |
| `t011-off-v2-…-meshoff-jfr`（runId `queue-59766cde`，manifest 修正后） | `meshTriangulationHashFix:false` | `6f06427c…` 原生 | 11,316 采样 | `c23b2a8a…` |
| `t011-on-v2-…-meshprobe-jfr`（runId `queue-8b3058cc`，manifest 修正后） | 缺省（默认 true） | `adb7d709…` 生产补丁输出 | 0 采样 | `c23b2a8a…` |

两跑 `captures=719`、`points=4`、`edges=5`、`lastIndicesLength=6` 完全一致。

### 性能指标（校正后 OFF v2 vs ON v2，同场景同 fixture）

| 维度 | OFF（原生） | ON（补丁） | 说明 |
|---|---|---|---|
| 整轮墙钟 | 349.9 s | 337.4 s（−3.6%） | 场景固定等待占大头，单对差值在宿主噪声带内；配对级收益仍以 P1 五组中位 **−16.8%** 为准 |
| EDT 忙时总样本 | 6,401 | 5,048（−21%） | EDT 饱和显著缓解（解释 OFF 中 `onEdt.timedOut` 更多、交互更卡） |
| **EDT 陷入 HashMap 树遍历** | 2,436 样本 ≈ **48.7 s**（占 EDT 忙时 38%） | 26 样本 ≈ **0.5 s**（0.5%） | **核心收益：UI 线程上约 48 s 的树化桶遍历被消除** |
| `TreeNode.find` 活动窗口 | ~100 s 连续区间 | 无 | OFF 的 churn 集中在网格编辑/重算阶段 |
| GC 次数 / 暂停总量 | 92 次 / **12.1 s** | 68 次 / **7.0 s**（−42%） | churn 制造垃圾更快，补丁后 GC 压力下降 |
| `HashMap$TreeNode` 分配 | 28 个采样 | **0** | ON 不再产生树化节点（普通 Node 分布均匀） |
| 保留堆峰值 | ~4.1 GB | ~4.7 GB | **无实质变化**——堆由模型/图集数据主导，差异为 GC 时点噪声 |
| 采样分配总量 | ~33.4 GB | ~34.3 GB | 不变——创建的对象相同（`triangulation.j` 4,130 vs 4,091 个） |
| 进程总 exec 样本 | 12,767 | 12,041 | 整进程 CPU 差被固定等待稀释；收益集中在 EDT 临界路径 |

内存结论：本修复是 **CPU/延迟** 优化而非内存优化——保留堆与分配体积不变，
但消除了树化节点对象、降低了 GC 暂停与 EDT 上的病态查找占用。

- **SC-03 关闭直通**：OFF 跑证实持久化 `false` 时生产 transformer 不改写目标类（原生字节直通），
  且退化 hash 病灶（17,568 次 TreeNode.find）仍在——即"关闭"确实回到原生慢路径而非部分生效。
- **SC-01 等价性（生产路径）**：补丁前后的输出摘要逐字节相同。
- **SC-02 机制（生产路径）**：补丁后 TreeNode.find 采样归零。
- **曾发生的 harness 事故（已归因，与本特性无关）**：首两跑的场景驱动在 freeze 阶段报
  DRIVER_BLOCKED，原因是该批 bundle manifest 误写 `t039LoaderClass=T039OwnedTargetLoader`
  （官方 profile 应为 `AppClassLoader`），T039 的 loader-gate 拒绝 → `state=BLOCKED`。
  旧生产 agent + 同一坏 manifest 的对照跑（`t011-diag-oldagent-…`）以完全相同签名失败，
  证实与 mesh 改动及新 agent 无关。manifest 修正后重跑两跑（`t011-on-v2`/`t011-off-v2`）
  均 **succeeded/PASS**，`freeze.completed=true`、`canonical.complete=true`、
  `payload.reopenHashVerified`，mesh 证据与上表一致。

- **SC-01 等价性（本组通过）**：捕获次数相同且摘要逐字节相同，说明修正哈希后三角化输出未变。
- **SC-02 性能（本组 −18%）**：`TreeNode.find` 的病态查找消失是机制证据，与墙钟下降一致；
  三角化本身的工作量没有减少（2268 vs 2161 采样），减少的是查找开销。
- 场景与 agent 均未改动生产 runtime；官方类仍只作字节级变换，不写入、不落盘、不改 JAR。

## 尚未证明（不得外推）

- **样本量**：5 组配对已满足规格的 ≥5 组要求；但配对变化跨越 −11% .. −29%，说明宿主侧噪声仍大，
  单次运行的绝对值不可直接比较，只有配对差值可用。
- **摘要的覆盖范围**：摘要只覆盖 `updateMesh` 返回时的索引缓冲与点/边计数，不代表渲染像素、
  Undo/Redo、保存等全部语义。
- **坐标可变性风险**：`TriPoint` 可变，若在集合存活期间被改写哈希会失真；本组摘要相同说明这次
  运行没有发生，但不构成对所有模型/编辑序列的证明。
- **只对 5303 验形**：5203/5302 未验，未验证版本不得启用。
- 实机摘要是在验证 harness（隔离 prefix、三个 agent）内采集的，绝对值含 harness 开销。
- **同类缺陷面已穷举**：对官方 5.3.03 JAR 全量扫描（16,992 类、305 个 `hashCode()I`），
  常量返回的退化实现**仅两处**——本修复覆盖的 `editableMesh.triangulation.l`，以及
  `jp.noids.design.morph.simple.i$b`（morph simple 工具的三点三元组，`equals` 同为六点
  排列不变，被放入 `HashSet`，同样会树化桶退化）。后者位于 morph 编辑路径而非本次测量的
  atlas/mesh 热路径，未纳入本切片；如需处理应开独立 spec 走同样的验形/门控流程。
