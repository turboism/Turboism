# T029-DWEAVE — h.c() Phase-3 MatchList 单点织入（离线切片设计 + 宿主 A/B 准入草案）

冻结依据：`specs/020-atlas-image-parallelism/plan.md`「T029-DWEAVE — h.c()
Phase3 MatchList 单点织入（主审冻结，2026-09-29）」+ tasks.md 同名条目。
注意：该冻结节在主仓（`/opt/dev/projects/turboism`，tip 6fbe6cad9）的 spec
中；本 worktree（tip 944c101b8）的 tasks.md/plan.md 尚无此条目，本文按主仓
冻结文本与任务内联描述执行。

证据对象：`Live2D_Cubism.jar` (5.3.03)，jar sha256
`bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166`；
`h.class` sha256
`5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d`。
全程仅 `javap -c -p` 只读；官方类未加载、未执行（探针输出
`officialClassLoaded=false`）。

## 1. javap 证据摘录（h.c()，javap -c -p 实读）

### 1.1 织入位点（Phase-3 起点，bci 223-230）

```
223: new           #52   // class java/util/ArrayList
226: dup
227: invokespecial #186  // Method java/util/ArrayList."<init>":()V
230: astore        7                            ; matchList
```

real-insn 序号（ASM 视角，探针输出）：`new@108 init@110 astore@111:slot7`。

### 1.2 c()V 内第二处同形位点（Phase-4，bci 523-530）

```
523: new           #52   // class java/util/ArrayList
526: dup
527: invokespecial #186  // Method java/util/ArrayList."<init>":()V
530: astore        8                            ; Phase-4 工作表
```

**与冻结文的调和**：冻结节写「形状门=c()V 内恰好一处
NEW→DUP→INVOKESPECIAL→ASTORE 序列（记录 astore 槽位）」，而实读 c()V 有
**两处**完全同形的 4 指令序列（slot 7 与 slot 8）。逐字面读法会拒织官方类；
与冻结节并读（plan 原文「（new+dup+invokespecial ArrayList.<init> + astore 7）
在官方字节唯一且匹配」）后可知唯一性以 **astore 槽位=7 为钉**：pinned 形状含
槽位。实现据此定门：`slot==pinned` 的位点恰一处 AND c()V 内全型位点总数
==2（两处都钉住，第二处不许动）。fixture 复刻同一双位点形态。

### 1.3 matchList（slot 7）全部触点（c()V 内，javap 实读）

| bci | 操作 | 阶段 |
|---|---|---|
| 87 | astore 7（**槽位复用**：Phase-2 曾作 int 计数器，iinc 7 @217） | Phase-2 |
| 230 | astore 7 = new ArrayList | 织入点 |
| 434-438 | aload 7 → `ArrayList.contains` | Phase-3 门 |
| 444-448 | aload 7 → `ArrayList.add` | Phase-3 门 |
| 468-472 / 478-482 | contains / add（eBC 同构） | Phase-3 门 |
| 502-506 / 512-516 | contains / add（eCA 同构） | Phase-3 门 |
| 535-540 | aload 7 → checkcast Collection → `invokeinterface Collection.isEmpty` | Phase-4 循环门 |
| 567-570 | `this.b(matchList)` → h.b 内 `isEmpty`+`remove(I)` | Phase-4 弹出 |
| 806-810 | aload 7 → `ArrayList.add`（把弹出的 j10 按分支重新入列，goto 535） | Phase-4 |
| 862-879 | `a(list8)`/`a(list8,j,TL,k)` —— **参数为 slot 8，不是 matchList** | Phase-5 |

要点：窗口内 matchList 写路径仅 `add`（448/482/516）；窗口后另有
`add`（810）与 `h.b` 内的 `remove(0)`；**全程无窗口后 contains**。
`h.a(ArrayList,j,TL,k)` 体内确有 `aload_1` 上的 `addAll`（bci 189/227），
但调用点传入的是 slot 8 表而非 matchList。isEmpty 经 Collection 接口分派，
`b(Ljava/util/ArrayList;)` 等参数要求 ArrayList——MatchList IS-A 全部满足。

c()V 内 `new java/util/ArrayList` 总数=2（bci 223、523），无其它变体
（无 `(I)V`/`(Collection)V` 构造）。c()V 内无 contains 之外的读路径依赖
迭代顺序外的语义；Phase-4/5 所见必须是同一实例——单点替换天然满足
（ASTORE 7 不变，槽内即 MatchList 实例）。

## 2. MatchList 语义论证

```java
public final class MatchList<E> extends ArrayList<E> {
    private final IdentityHashMap<E, Boolean> mirror = new IdentityHashMap<>();
    @Override public boolean contains(Object o) { return mirror.containsKey(o); }
    @Override public boolean add(E e) { mirror.put(e, Boolean.TRUE); return super.add(e); }
}
```

- **逐点一致（窗口可达域）**：窗口内对列表的唯一写是 `add`（先注册镜像再
  `super.add`）；因此对窗口内每次 `contains(q)`，镜像成员集 == 列表元素
  的同一性集合，`contains` 与 `ArrayList.contains` 逐点等价——对任意
  元素类型成立（不依赖 j 无 equals 的事实；该事实只保证官方窗口里
  contains 恒 false 的死码性质）。官方 j 无 equals/hashCode
  （DMATCH 验收的 javap 复核），恒等键是唯一无条件等价形态。
- **null 安全**：IdentityHashMap 允许 null 键；`contains(null)` 为空表
  →false、`add(null)` 后 →true，与 ArrayList 对 add-only 内容的答案
  逐点一致（官方 ArrayList 允许 null 元素）。
- **窗口后突变不失镜像可观测性**：`remove(0)`（h.b）不走 add 会留陈旧
  镜像，`h.a` 的 addAll 目标是另一张表；但 javap 证实窗口后再无
  contains——陈旧不可观测。此为已知边界，非缺陷：MatchList 合同 = 
  「add-only 使用域内逐点等价」。
- **越界域（已在单测断言为可观测分歧）**：值语义元素（覆盖 equals）下
  `contains(equalNotSame)` 官方为 true、镜像为 false——故该 helper 仅
  可用于恒等语义元素（j 已核实）；addAll/remove 后再 contains 亦在
  合同外。二者在官方路径均不可达。
- **无全局状态**：mirror 为实例字段；类仅 JDK 依赖，生产 agent 可直接
  打包（`Contains`/`add` 之外无新增行为面）。

## 3. 织入机制（Weave.java）

core-ASM 两遍（无 asm-tree 依赖），real-insn 索引（不计 label/line/frame）：

- pass1 `collect`：目标方法内检全部
  `NEW listType → DUP → INVOKESPECIAL listType.<init>()V → ASTORE s`
  4 指令连续序列，记录 {newIdx, initIdx, astoreIdx, slot}；同时计
  `listTypeNews`（所有 NEW listType，不论后续）。
- `gate`：method 缺失 → `method-not-found`；pinned 槽位点数为 0 →
  `no pinned init site slot=N`；>1 → `ambiguous pinned init site`；
  `expectedTotalSites>=0` 且总位点数不符 → `total-sites=`。
- pass2 `emit`：`ClassWriter(0)` 逐字回写，仅在记录的 newIdx 把 NEW 操作数
  改为 `matchListInternal`、initIdx 把 INVOKESPECIAL owner 改为
  `matchListInternal`（名称/描述符 `<init>()V` 不变）。指令数、分支目标、
  栈形、locals、frames 全部不变——MatchList <: ArrayList 使原 frame 中
  的 `ArrayList` 声明对新实例仍是合法超类型，`-Xverify:all` 通过。

拒织返回**原数组**（不拷贝不改动），原因随 `Result.rejectReason` 可观测。

## 4. 织入门矩阵（实装断言）

| 输入形态 | 结果 | 观测 |
|---|---|---|
| c()V 含 pinned-slot 位点恰一处、总位点=期望 | 接受，2 个操作数差分 | Result.plan.target |
| 无目标方法 | 拒 `method-not-found c()V` | rejectReason |
| pinned 槽位点=0（dropPinnedDup/retargetPinnedSlot/wrongCtorDesc/otherNewType/gapBeforeDup） | 拒 `no pinned init site slot=N` | rejectReason + sites |
| pinned 槽位点>1（duplicatePinnedSite） | 拒 `ambiguous pinned init site` | rejectReason |
| 总位点数≠期望（expectedTotalSites=99） | 拒 `total-sites=` | rejectReason |
| 钉第二位点槽（fixture site2） | 接受且仅改该位点 | InsnDiff=2 |

InsnDiff 对 woven vs 原字节做全方法指令流逐位差分：fixture 与官方类均
恰 2 处差异（NEW 操作数 + INVOKESPECIAL owner），其余指令零改动——
「禁止改动其它任何指令」的机器化证明。

## 5. 自有窗口差分（OwnWindow.c()V）

fixture 复刻官方形态：同名 `public final void c()`；**同一方法两处**
`new ArrayList`（Phase-3 matchList→pinned 槽，Phase-4 popped→另一槽）；
外层 `Iterator<Edge>`（k.c() 形态）×内层每边新 `tris.iterator()`
（LinkedHashSet 插入序）；每三角三候选 `new Edge(l.a(),l.b())` 等；
`intersects`(r.a 逐指令移植) → `shareAny`(h.a index 谓词) →
`matchList.contains` → `add`；窗口后 remove(0) FIFO + 条件 re-add +
Collection.isEmpty + 对另一表的 addAll。Edge 无 equals/hashCode
（恒等，同官方 j）；checkNotNullExpressionValue/checkNotNullParameter
走真实 kotlin-stdlib intrinsic。

差分方式：woven 字节由 ByteLoader（仅 OwnWindow 本类 child-first，嵌套
类型/MatchList/Intrinsics 全走父委派共享同一 Class）define，反射构造
同一输入引用，事件流（identity 归一化序数）+ matchList/popped 序数 +
异常类与消息逐项比对；另断言 woven 侧 `matchListOut instanceof MatchList`
而 popped 仍是 `java.util.ArrayList`（织入生效与选择性同步证明）。

覆盖域（全等断言）：nullEdges 早退、emptyBoth/emptyEdges/emptyTris、
basic1x1、dupCandidates（同端点异对象候选双双入列——contains 死码域）、
shareSkip、intExtremes（MIN_VALUE/-1/0）、nullEdgeElem/nullTriElem
（intrinsic NPE）、nullPointGetter（j.<init> 型 NPE）、phase5AddAll、
mixed5x5。

MatchList 单测：add-only 脚本（含 null、重复同引用、缺查询、isEmpty/
size/get/迭代快照）与 ArrayList 逐点一致；三条边界断言（值语义元素、
remove 后 contains、addAll 后 contains 的可观测分歧）固定合同边界。

## 6. 宿主 A/B 准入材料草案（待主审逐项放行，本轮不提交）

沿用 TRIAB 已验设施（单一 aux agent 两模式、身份门、Sink、Capture、
INVALID 硬失败、交错腿），差异仅在候选变换与目标类：

### 6.1 模式

- `dm-dump-only`：生产 + Capture（TriangleList.b() 返回逐序号 SHA），
  候选变换不尝试、MatchList 不需要。
- `dm-dump+weave`：h.c()V MatchList 织入 + 同一 Capture。形状拒织或
  helper 不可解析 → 腿 INVALID 硬失败，严禁静默降级。

### 6.2 AbTransformer 泛化点（不破坏既有 TRIAB 路径）

现 `AbTransformer` 只对单一 `targetInternal` 应用 `Weave.weaveChecked`
（KWEAVE 查询点变换）+ `CaptureWeave`。DWEAVE 需要**双目标分派**：

- 命中 `com/live2d/graphics3d/editableMesh/triangulation/h` → woven 模式
  应用 `dweave.Weave.weaveChecked(OFFICIAL_CFG)`（methodName=c,desc=()V,
  listType=java/util/ArrayList,ctorDesc=()V,astoreSlot=7,
  expectedTotalSites=2,matchListInternal=<agent 内 MatchList 内部名>）；
  dump-only 直接放行（h 无 capture 需求）。
- 命中 `.../triangulation/TriangleList` → 两模式同 apply CaptureWeave
  （b()Lk; 返回捕获，与 TRIAB 完全相同的采集实现）。
- 身份门（loader/classSha/codeSource）对两类分别钉住：h.class sha=
  `5aa7031e…`、TriangleList.class sha=`87835641…`。
- `turboism.validation.dmWeave.*` 独立属性前缀 + `profile=dm-official`
  新增（`official`/`shadow-selfcheck`/`dm-official` 三档），profile 之外
  的既有键与默认值不变；helper 预热与可解析门新增 MatchList（woven 模式
  下不可解析 → INVALID）。
- 既有 TRIAB 配置路径零改动：新增 code path 只在 profile=dm-official 且
  className=h 时进入；selfcheck 需新增以官方 h 形状为准的 shadow fixture
  （双位点 + pinned slot），复用本目录离线断言形态。

### 6.3 等价与计时口径

- **等价**：TriangleList.b() 返回的逐调用序号 sha256，与 TRIAB 四腿基线
  `fefb401f…`/`89ed77b3…`/`17ded14e…`/`786fe2ef…`（seq1-4，
  1203/1122/132/100 条边）直接复用比对——候选不改变 b() 输出语义，
  跨腿逐序号一致即等价成立。
- **计时**：JFR 样本分桶计数，主指标 `h.c()` 叶子桶（窗口内
  ArrayList.contains 线性扫描消失 → 预期显著下降）；对照桶=三角化内部
  （tri 包）与未知栈；禁止样本比例→耗时/百分比外推。
- **腿**：同一 prepared 快照族交错 4 腿（dm-base、dm-woven ×2），每腿
  标准门（正常退出/模型不变/宿主身份/清理 safe）+ woven 腿
  `legStatus=INVALID` 即废、序号 sha 不配对即停批。
- agent jar 固定构建 sha 自证、aux 顺序与 prepared 快照审查照旧由主审
  逐腿放行。

## 7. 明示限制

- fixture 是字节码核实的行为切片，不声称宿主类型等价；r.a/h.a 为
  DMATCH 已验移植，窗口外阶段仅复刻触点形态不建模算法。
- `InsnDiff` 证明指令流差分恰两处；常量池重排属 ASM 重写固有，不计为
  语义差异。
- 实机准入材料为草案；宿主执行、计时解读、生产晋升均需另行主审放行。
- 范围外（冻结）：h.a 递归的 k.a 查询、Phase-4/500 上限语义、k 活表
  跨调用索引。
