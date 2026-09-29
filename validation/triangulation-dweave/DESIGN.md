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

## 6. 宿主 A/B 最终装配清单（待主审逐项放行，本轮不提交）

本章由草案收敛为可执行装配：所有目标常量、属性值与判废口径均已落入
`validation/triangulation-weave-ab`（单一 agent jar，TRIAB/DWEAVE 双命名空间）
与 `scripts/preview/run-atlas-image-shadow-host-validation.sh`。

### 6.1 模式

- `dm-dump-only`（wrapper `--tri-dweave dm-dump-only`，腿标签
  `t029-dweave-base<N>-heavy-nolayout[-jfr]`）：h.c() 候选变换**不尝试**
  （premain 不预热 MatchList、transformer 只放行字节），MatchList 不需要
  存在；TriangleList Capture 照常。
- `dm-dump+weave`（`--tri-dweave dm-dump+weave`，腿标签
  `t029-dweave-woven<N>-heavy-nolayout[-jfr]`）：h.c()V 按双钉形状门织入
  MatchList + 同一 Capture；premain 先完成 MatchList 预热/可解析检查。

两命名空间互斥：`triWeave.enabled` 与 `dmWeave.enabled` 同时为 true →
premain `admission=reject reason=namespaces-conflict`，不安装 transformer。

### 6.2 属性合同（dmWeave 命名空间，11 个固定值）

wrapper 对 dweave 腿固定注入（与离线断言逐字节一致）：

| key | value |
|---|---|
| `turboism.validation.dmWeave.enabled` | `true` |
| `...dmWeave.mode` | `dm-dump-only` / `dm-dump+weave`（与腿标签双向绑定） |
| `...dmWeave.profile` | `dm-official`（显式钉死，防漂入 fixture profile） |
| `...dmWeave.phase` | `t029-dweave` |
| `...dmWeave.runId` | `dweave-<base|woven><N>`（腿 token） |
| `...dmWeave.outputDir` | `{HOME}/dm-weave` |
| `...dmWeave.expectClassSha256` | `5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d`（**h.class 独立摘要**，非 TriangleList） |
| `...dmWeave.expectCaptureClassSha256` | `87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29`（TriangleList.class） |
| `...dmWeave.expectLoader` | `jdk.internal.loader.ClassLoaders$AppClassLoader` |
| `...dmWeave.expectCodeSource` | `file:/C:/Program%%20Files/Live2D%%20Cubism%%205.3.03/app/lib/Live2D_Cubism.jar`（`%%20` 契约同 TRIAB/IDENTITY） |
| `...dmWeave.captureN` | `4` |

无 fixture hash、无测试旋钮、无通用透传。wrapper 侧契约：5303-only；
`--tri-dweave` 与 `t029-dweave-*` 标签双向 fail-closed；label↔mode 一致
（base↔dm-dump-only、woven↔dm-dump+weave）；`--tri-weave-agent` 或
`TURBOISM_TRI_WEAVE_AGENT` 二选一（并存即拒），绝对路径、非符号链接、
basename `tri-weave-agent.jar`、sha256 记录供准入审查。

### 6.3 aux 顺序与类定义事件

aux 顺序固定：production agent → T039 shadow agent →
`tri-weave-agent.jar` → scene driver（离线断言
`auxOrder=t039-triweave-driver`；`--aux-agent-before-main` 仍是
t039-shadow-agent.jar）。

transformer 目标表（dm 命名空间，定义期逐类处理）：

| 类 | 身份门 | woven 动作 | dump-only 动作 |
|---|---|---|---|
| `…/triangulation/h` | sha=`5aa7031e…` + loader + codeSource | `dweave.Weave.weaveChecked`（c,()V,ArrayList,()V,**slot7**,total=2,`dev/turboism/validation/dweave/MatchList`）；accept→2 个操作数差分 | 直接放行（无 capture） |
| `…/triangulation/TriangleList` | sha=`87835641…` + loader + codeSource | `CaptureWeave` 仅织 `b()Lk;` | 同左 |

MatchList 与 Capture 助手均为 agent-jar 类；woven 模式 premain 完成
MatchList 预热（load/link），运行期再按目标 loader 做可解析检查。

### 6.4 INVALID 判定（硬失败，不回退基线）

woven 腿遇下列任一即写 `tri-weave-status-<runId>.txt`
`legStatus=INVALID reason=<r>` 并记 stderr `leg-invalid`：

- `helper-unavailable`：premain 预热或定义期可解析检查发现
  MatchList 不可解析；
- `weave-reject:<reason>`：形状门拒织（`method-not-found` /
  `no pinned init site slot=7` / `ambiguous pinned init site` /
  `total-sites=N expected=2`）；
- `capture-reject:<reason>`：TriangleList 的 b() 不恰一处 ARETURN
  或 capture 织入失败（同 TRIAB 口径）。

INVALID 不回放数据、不算基线、腿作废。身份门（sha/loader/codeSource）
不命中**不算 INVALID**：该类按原字节放行并在 definition 日志记
`gate=reject reason=...`——与 TRIAB 既有口径一致（身份漂移是证据缺失，
非判废）。admission 级失败（属性非法/冲突）在 premain 拒绝安装，
stderr `admission=reject reason=...`，premain 永不外抛。

### 6.5 Capture 点与 digest schema

观测点不变：`TriangleList.b()Lcom/live2d/graphics3d/editableMesh/
triangulation/k;` 返回后，由 Capture 将 k 的边端点对序（j.a/j.b）序列化为
`a,b;a,b;…` 并 sha256。dump 行 schema 与 TRIAB 逐字段相同：
`runId seq mode classSha256 loader codeSource helperLinked sha256 helperQueries newBoxCalls`
——dm 腿 `mode=dm-dump-only|dm-dump+weave`，`helperQueries/newBoxCalls`
为 TRIAB helper 计数器，dm 路径恒 0。

### 6.6 等价判定（四腿基线 SHA 复用声明）

等价见证与 TRIAB 完全同构：dm 两模式各腿 seq1-4 的 sha256 应与 TRIAB 已
入库基线逐序号一致——`fefb401f…`/`89ed77b3…`/`17ded14e…`/`786fe2ef…`
（edge 数 1203/1122/132/100）。复用理由：候选只改 h.c() 内部 matchList
的 contains 复杂度，b() 的输出语义与观测点字节零接触；基线已是该场景
下 b() 输出的权威记录。任一序号不配对该批停审，不四舍五入。

### 6.7 JFR h.c() 叶子桶计时口径

-JFR 腿（`t029-dweave-*-heavy-nolayout-jfr`）按既有 reviewed 设置录制
（`settings=profile,stackdepth=256,maxsize=512m,dumponexit=true`）。
主指标：`jdk.ExecutionSample` 顶层帧（leaf）归 `h.c()` 的**样本计数**
跨 base/woven 对比——contains 线性扫描消失应表现为 h.c() 叶子桶显著
下降；对照桶=tri 包内其它帧与未归 buckets。**JFR 样本计数不得换算为
百分比、wall-time 或吞吐结论**——样本数是采样证据不是计时器，任何
"占比下降 X%" 或"加速 Yms" 的陈述均越界。

### 6.8 边界（不纳入本批）

冻结范围外（明示不做）：h.a 递归内 `k.a` 谓词优化、Phase-4/500 上限
语义任何改动、`k` 活表跨调用索引、生产代码与 Runner 核心改动。
MatchList 只对 pinned slot7 实例生效——Phase-4 第二张表（slot8）保持
`java.util.ArrayList`（探针断言 `dm.wovenArrayListSites=[…:slot8]`）。

## 7. 明示限制

- fixture 是字节码核实的行为切片，不声称宿主类型等价；r.a/h.a 为
  DMATCH 已验移植，窗口外阶段仅复刻触点形态不建模算法。
- `InsnDiff` 证明指令流差分恰两处；常量池重排属 ASM 重写固有，不计为
  语义差异。
- 实机准入材料为草案；宿主执行、计时解读、生产晋升均需另行主审放行。
- 范围外（冻结）：h.a 递归的 k.a 查询、Phase-4/500 上限语义、k 活表
  跨调用索引。
