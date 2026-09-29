# T029-DMATCH — h.c() Phase-3 匹配窗口索引化（离线差分原型）

证据对象：`Live2D_Cubism.jar` (5.3.03)，jar sha256
`bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166`
（与 T029-TRIAB/inventory 同一受审件）。全程仅 `javap -c -p` 只读；
官方类未加载、未执行。冻结节在本 workspace 的 tasks.md/plan.md 中
未检索到（grep `DMATCH` 无命中），按任务内联描述执行。

## 1. javap 证据摘录

### 1.1 h.c() Phase-3 窗口（bci 223–522，`javap -c -p` 实读）

```
223: new java/util/ArrayList; 227: <init>; 230: astore 7     ; matchList
232: aload_3(k); 233: invokevirtual k.c()Iterator; 236: astore 8
238/240: it8.hasNext; 245: ifeq 523                            ; 外层边迭代
248/250: it8.next; 255-258: Intrinsics.checkNotNullExpressionValue(#4="")
261: checkcast j; 264: astore 9                              ; ej
266: aload 4(TL); 268: invokevirtual TriangleList.iterator(); 271: astore 10
273/275: it10.hasNext; 280: ifeq 238                         ; 内层三角迭代
283/285: it10.next; 290-293: checkNotNullExpressionValue("")
296: checkcast l; 299: astore 11
301-315: new j(l.a(), l.b()); 318: astore 12                 ; eAB
320-334: new j(l.b(), l.c()); 337: astore 13                 ; eBC
339-353: new j(l.c(), l.a()); 356: astore 14                 ; eCA
358-376: r.a(ej,eAB)!=null -> loc15                          ; flag AB
378-396: r.a(ej,eBC)!=null -> loc16                          ; flag BC
398-416: r.a(ej,eCA)!=null -> loc17                          ; flag CA
418: iload15 ifeq 452
423-428: invokespecial a(j9, j12)Z; 431: ifne 452            ; h.a share-skip
434-438: ArrayList.contains(j12); 441: ifne 452              ; contains-skip
444-448: ArrayList.add(j12); 451: pop                        ; append
452-485: gate eBC 同构（a(j9,j13) / contains / add）
486-519: gate eCA 同构（a(j9,j14) / contains / add）; 520: goto 273
523: Phase-4（新 ArrayList + ≤500 循环，h.b(matchList)=remove(0) 消费）
```

**事件序（每 (ej, tl) 对）**：构造 eAB→eBC→eCA；计算 flag AB→BC→CA；
然后按 AB、BC、CA 顺序各做 `flag && !h.a(ej,cand) && !contains -> add`。
三个候选对象与三个 flag 全部先于任何门检查完成——求值顺序已核实。

**窗口内零突变**：整个 c() 方法体内仅有的两次 `k.c()` 调用在 bci 84
（Phase-1 标记数组）与 bci 233（本窗口外层迭代器）；无 `k.a(j)`、
`k.b(j)`、无 `Iterator.remove`、无 LinkedHashSet/TL 写操作；
无异常表。窗口内唯一集合写是 matchList.add（448/482/516）。

### 1.2 迭代顺序来源

- `k.c()` → `getfield a; invokevirtual ArrayList.iterator()`（k.txt bci 0-7，
  附 `checkNotNullExpressionValue`）→ **ArrayList 插入序**，可变类型但窗口未 remove。
- `TriangleList.iterator()` → `b.iterator()`，字段 `b` 声明为
  **LinkedHashSet**（TL.txt bci 0-10）→ **插入序**；每条边外循环内新取一次
  （bci 266-271 在外层循环体内，不是循环外提）。

### 1.3 j 类（决定性分叉证据）

`javap -c -p j` 全部方法：`<init>(TriPoint,TriPoint)`、`a()`、`b()`、
`c()F`、`a(I)Z`、`toString()`、`a(j,Z)`。**无 equals、无 hashCode**
→ `java.lang.Object` **恒等语义**。

- `ArrayList.contains(freshCand)` 走 Object.equals → 恒等比较；
  每个候选均为本次迭代内 `new j(...)` 的新对象，contains 在官方窗口
  **恒为 false（事实死码）**，但语义=“恒等成员资格”而非“无操作”。
- 结论落点（规格第 2 点分叉）：equals=恒等 ⇒ 任何 HashSet/LinkedHashSet
  镜像是否等价取决于“j 的 equals/hashCode 契约”本身——本例恰好成立
  （恒等 equals+恒等 hash 自洽），但**不依赖该契约的显式键结构才是
  无条件等价解**：候选使用 `Collections.newSetFromMap(IdentityHashMap)`，
  键=对象引用本身，与 equals 语义逐点一致，无键碰撞歧义（恒等无歧义）。
- 反证已实装：端点 index 值键（ShadowJValEq，equals 值化+一致 hash）
  与 equals 值化但 hash 未覆盖（ShadowJBrokenHash，equals/hashCode
  不一致）两个变体域下，HashSet 镜像/恒等索引与参考 contains 产生
  **可观测分歧** → 证明选择恒等键的必要性边界。

附注（窗口不消费，仅记录）：`j.<init>` 两参 `checkNotNullParameter`
（NPE，参数字符串为 `ldc #4` = **空串**）；随后计算
`a.index != b.index` 并由 `kotlin._Assertions.ENABLED` 门控
AssertionError（默认关）。`j.a(j,Z)` 用 `Intrinsics.areEqual` 比较
TriPoint 对象（非 index），窗口未调用。`TriPoint.equals` 仅比 x,y
（index 不参与），而 `hashCode` 含 index——官方便存在
equals/hashCode 契约违例；窗口内不触发。`l.hashCode`=`iconst_0`；
`l.equals`=TriPoint 轮换全等（6 种）；窗口内亦不触发。

### 1.4 私有 h.a(j,j)Z（javap 行 1710-1775）

```
j1.a().index == j2.a().index (bci24) ||
j1.a().index == j2.b().index (bci51) ||
j1.b().index == j2.a().index (bci78) ||
j1.b().index == j2.b().index (bci105) -> true else false
```

**无向端点 index 共享谓词**（四组合任一）。调用形态 `a(ej, cand)`
（bci 423-428/457-462/491-496 均 aload9 在前、候选在后）；谓词本身
对称，方向无可观测效应。**无任何 null 检查**——null 实参在首个
invokevirtual 处 JVM NPE（窗口内不可达：ej 经 expressionValue 检查、
cand 为 new，非 null）。

### 1.5 r.a(j,j)GVector2（flag 门，javap r.txt bci 163-185 → 10-273）

两参 `checkNotNullParameter`（参数字符串 `ldc #5`=`\u0001\u0001`，
混淆产物）；取 ej.a()/b()、cand.a()/b()（checkcast GVector2，TriPoint
IS-A GVector2 恒过）后做参数化线段相交：

```
f5=(p4.y-p3.y)(p4.x-p1.x)-(p4.x-p3.x)(p4.y-p1.y)
f6=(p2.x-p1.x)(p4.y-p1.y)-(p2.y-p1.y)(p4.x-p1.x)
f7=(p2.x-p1.x)(p4.y-p3.y)-(p2.y-p1.y)(p4.x-p3.x)
t=f5/f7; u=f6/f7
(t∈[0,1] && u∈[0,1]) -> new GVector2(p1+t(p2-p1)) else null
```

区间闭（含端点接触）；NaN 经 fcmpg 全部落入 null 分支（影子实现用
Java `>=`/`<=` 比较，对 NaN 同为 false，语义一致）。

### 1.6 异常消息形态（stdlib 1.7.21 实读，sha d46a9d77…）

- `checkNotNullExpressionValue(v,"")` → `NPE("" + " must not be null")`
  = `" must not be null"`（前导空格；ldc #4 空串已核实）。
- `checkNotNullParameter(v,"")` → NPE
  `"Parameter specified as non-null is null: method <fqmn>, parameter "`
- 差分比较仅规范化 intrinsic 消息中的调用方法名段（ref/cand 调用点
  类名合法不同，沿用 KBUILD 先例），其余逐字节。

## 2. 语义结论

1. **窗口=纯构造**：外层 k 边序（ArrayList 插入序）× 内层三角序
   （LinkedHashSet 插入序，每边新迭代器）的候选收集器；无输入突变。
2. **contains 语义=恒等成员资格**：官方恒 false 于新对象，但等价判据
   必须按“恒等成员”建模——值键去重会吞掉官方会追加的重复端点候选
   （相邻三角共边产生同端点不同对象的候选，两端均入列）。
3. **候选索引形态**：`IdentityHashMap` 背书的 `Set<JEdge>`（键=引用），
   contains→O(1)，append 仍写真 ArrayList 并同步索引；索引为方法局部，
   返回后不可达。对任意可设想列表内容（含同一引用已入列）与
   `ArrayList.contains` 逐点等价——不只依赖“候选必新”不变量。
4. **门链**：`flag(r.a 相交非空)` → `!h.a 端点 index 共享` → `!contains`
   → `add`，三候选顺序 AB、BC、CA；候选构造与 flag 计算先于门检查。
5. **下游消费**（窗口外，仅记录为何序/重复对象语义重要）：Phase-4
   `h.b(matchList)=remove(0)` FIFO 逐条弹出驱动合法化；列表顺序与
   元素身份决定消费顺序。

## 3. 差分矩阵

参考=官方指令逐事件影子（真实 `ArrayList.contains` 恒等扫描）；
候选=同一窗口 + `IdentityHashMap` 成员索引。同一输入引用驱动，
Observer 逐事件记录（identity 归一化序数）：

| 观测维度 | 比较方式 |
|---|---|
| 追加序列（which×事件位） | 事件流逐项相等 |
| 最终列表内容+顺序 | 每槽端点 index、x/y float 位、对象序数模式逐项相等 |
| 引用同一性 | identity 序数归一化后相等（同构对象图），追加对象即构造对象 |
| 异常类 | getClass 相等 |
| 异常消息 | 窄规范化 intrinsic 调用方法名段后逐字节 |

## 4. 域覆盖（已实现）

| 域 | 内容 |
|---|---|
| 空集 | 空 k、空 TL、双空 |
| 基本命中 | 1 边×1 三角相交追加 |
| **contains 死码荷载域** | 两三角产同端点（不同对象）候选 → 参考两次均追加（append0×2），对象互异 |
| 端点共享 | 同向/交叉端点 index 共享 → share=true → 不追加 |
| int 极值 | MIN_VALUE/负值/0 端点 index |
| 同 index 异坐标 | index 谓词命中（坐标无关） |
| 同坐标异 index | 不共享（index 语义），几何端点接触仍 flag+append |
| 退化边/退化候选 | 同 index 双端（断言关）可构造 |
| null 域 | k 元素 null→NPE" must not be null"；TL 元素 null→同；TL=null+非空 k→NPE；**TL=null+空 k→不抛、空结果**（迭代器在边循环体内，顺序敏感已证）；k=null→提前返回 null |
| 异常前缀 | j.<init> 参数 NPE（null TriPoint getter，宿主 Kotlin 非空下不可达，fixture 压力）；getter 故障 RuntimeException 原文透传 |
| 浮点域 | NaN/±Inf/-0.0 坐标、共线重叠（f7=0→NaN→flag=false）、端点接触（t/u∈{0,1}→flag=true） |
| 混合 | 5 边×5 三角复合 |
| 变体域 | ShadowJValEq（值 equals+一致 hash）、ShadowJBrokenHash（值 equals+恒等 hash）下参考值化去重 vs 恒等索引不去重 → **预期分歧**（候选适用界证据） |

## 5. 负控清单（全部必须被拒）

| 负控 | 缺陷 | 判别域 |
|---|---|---|
| matchValueDedup | 端点 index 值键 HashSet 去重 | dupEdgeObj（吞掉合法重复追加） |
| matchNoShare | 去 share 检查 | shareEndpoint |
| matchDirectedShare | 仅同向 a-a∧b-b 共享判定 | crossShare（漏交叉共享） |
| matchSwapOrder | query 先于 share | shareEndpoint（事件序分歧） |
| matchAppendNew | append 新复制对象而非构造对象 | cross1x1（恒等分歧） |
| matchReversed | 候选 j(b,a) 反向构造 | cross1x1（结构分歧） |
| matchGateOrder | 门序 CA,AB,BC | 多门命中域（事件序/追加序分歧） |
| matchHashSetMirror | 依赖 j 自身 equals/hashCode 的 HashSet | brokenHash 变体（equals/hashCode 不一致即失效） |
| 比较器自检 | 截断事件流 | 直接断言被拒 |

## 6. 明示限制

- fixture 是字节码核实的行为切片，不声称宿主类型等价；TriPoint/l 的
  equals/hashCode、j.a(j,Z)、l.equals、KWEAVE 织入形态均窗口外。
- 浮点 r.a 端口按指令逐算移植（确定性函数），窗口外几何未建模。
- 资源限制/并发：窗口单线程、有界输入；未建模分配失败。
- 计时仅合成成本（含索引构建与分配），无阈值、不外推宿主收益；
  本批尺寸下参考线性扫描多腿更快（追加次数远小于查询次数），如实记录。
