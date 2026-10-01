# T029-TLINDEX — TriangleList.a(j) 边→三角形索引（离线差分切片）

目标：消除 `TriangleList.a(j)` 的全集扫描。宿主 JFR（5.3.03，heavy-nolayout，
`5303-t041-digest-base1`/`base2` 两腿一致）：`TriangleList.a`+`iterator` 链上
约 2400–2500 样本（≈25s，占三角化链 ~40%），其中带 iterator 的 a() 帧
=1231（纯 `a(j)` 扫描；set-add 路径仅 2），`HashMap$HashIterator.<init>`
为最大叶子（JDK17 上 LinkedHashIterator 继承 HashMap$HashIterator，
JIT 内联后整段迭代归属其构造帧）。`TriangleList.b`（remove）仅 218 样本。

## bytecode 已核实事实（javap，5.3.03 jar sha bd0a23b9…）

- `TriangleList.b` = private final `LinkedHashSet<l>`；写入口仅
  `a(l)Z`=add、`b(l)Z`=remove、`c()V`=clear；`c(l)Z`=contains 只读；
  `iterator()` 暴露 set 迭代器（h 内未见 iterator.remove() 调用方）。
- `a(j)List`：new ArrayList → 全迭代 → `l.b(j)` 过滤 → 按插入序返回。
- `l.b(j)`：按 `TriPoint.getIndex()` 的无向端点对比较三边
  (a,b)/(b,c)/(c,a)；边 j 端点下标含 -1/-2/-3 哨兵合法值。
- `l.equals` = 三顶点 TriPoint-equals 的 6 种循环排列；`TriPoint.equals`
  只比 (x,y) **忽略 index** → 两个 `l` 可 equals 但端点 index 不同。
- `l.hashCode()` = **常数 0** → LinkedHashSet 退化为单桶链，
  add/remove/contains 全部 O(T) equals 扫描（官方代价本就如此）。
- `l` 三顶点字段仅在 `<init>` 中赋值（putfield 仅此一处）→ 元素入桶后
  键不变。
- `a(j)` 返回全新 ArrayList 快照。
- `a(l)`/`b(l)` 有 `checkNotNullParameter` + `c$a.b()` 门控 debug-log
  序言；`c()`/`a(j)` 只有 null 检查。
- 变更入口复审（全部 getfield `b` 站点已枚举）：`a()`=size、`b()`=iterator
  只读、`iterator()`=iterator、`c(l)`=contains 只读、`d(l)` 只操作本地
  ArrayList、`toString`/`a(String)`/`a(int)` 只读 —— 写入口仍仅
  `a(l)/b(l)/c()` 三个 woven 点。`iterator()`/`b()` 暴露的迭代器若被
  调用方 `remove()`，由 Bridge 的 sz 前检兜住（尺寸不符→dead→回退）。

## 候选设计（Bridge.java，纯 JDK 类型 = 宿主可原样织入）

- 键：`key(i,j) = (min<<32)|(max&0xffffffff)` 无向端点下标对。
- 状态：`IdentityHashMap<LinkedHashSet,St>`（**禁止** hash/weak map：
  LinkedHashSet.hashCode() 是 O(T) 元素求和、equals() O(T²) —— 初版
  WeakHashMap 实测每次 get 都重新 O(T) 扫描，bench 反而慢 3 倍）。
- `add(s,tri,ia,ib,ic)`：先真实 `s.add(tri)`；成功后无条件记录
  `keys[tri]=(k1,k2,k3)`；仅当 `!dirty && sz==size-1` 时精确入桶
  （否则 dirty）。sz 前检兜住 iterator.remove 等侧路。
- `remove(s,tri)`：**不按参数键去索引**。迭代集合找第一个
  `tri.equals(e)` 的元素 e（与 HashMap.removeNode 在单桶链下的选择
  完全一致），`iterator.remove()` 删除，再按 **e 自己记录的键** 从桶里
  按**对象身份**摘除——equals-vs-keys 分歧天然安全。O(T)，与官方
  remove 同阶。
- `clear(s)`：set.clear + 索引清空。
- `tryQuery(s,ja,jb)`：dead→null；dirty 或 sz 不符→rebuild；rebuild 遇
  无键元素（未织入 add 的元素）→ dead，永远回退。返回**新 ArrayList**
  （官方快照语义；负控证明返回内部桶会被调用方污染）。
- 所有簿记 try/catch Throwable → 只降级，不向宿主传播。

## 织入形态（待宿主 A/B，未实施）

`TliWeave`（ASM，沿用 dweave/kweave 的 shape-gate + INVALID 约定）：
- `a(l)`：在 `invokevirtual LinkedHashSet.add` 点前插入
  `aload_1;l.a();getIndex; ×3`，再把该 invoke 换成
  `invokestatic Bridge.add(LinkedHashSet,Object,III)Z`（序言/日志不动，
  COMPUTE_FRAMES 仅此方法）。
- `b(l)`：`invokevirtual LinkedHashSet.remove` →
  `invokestatic Bridge.remove(LinkedHashSet,Object)Z`（等栈形，纯替换）。
- `c()`：`invokevirtual LinkedHashSet.clear` →
  `invokestatic Bridge.clear(LinkedHashSet)V`（等栈形）。
- `a(j)`：在方法体**前置** tryQuery 派发：`r!=null→areturn`，null→落入
  **原扫描体**（自动回退，结构性正确）。
- shape gate：上述 invoke 点各恰好一处、方法签名/访问标志不变；
  否则整腿 INVALID（沿用 AbTransformer 约定）。

## 集成验证（validation/triangulation-weave-ab，agent 级）

`TliWeave`+`Bridge` 编入 `tri-weave-agent.jar`；`WeaveAbConfig` 新增
`turboism.validation.tlWeave.*` 命名空间（mode `tl-dump-only` /
`tl-dump+weave`，profile `tl-official`/`tl-shadow-selfcheck`），
`AbTransformer` 按 `t.tliWeave` 派发 `TliWeave.weaveChecked`；官方
5.3.03 字节上 `OfficialShapeProbe` 报 `TLINDEX_PROBE PASS`（四方法 gate
全中、capture-after-weave 兼容）。`run.sh` 48 场景 PASS（含
tlHappyDump/tlHappyWeave/tlMissingHelperWeave/tlShapeRejectWeave/
tlWrongSha/tlShapePins），`hostExecuted=false officialClassLoaded=false`
—— 离线 agent 证据不构成实机 readiness。

## 差分验证（本目录，纯离线，own-fixture）

`run.sh`：javac（ASM+kotlin-stdlib 仅编译/运行期依赖，见脚本解析链）→
`-Xverify:all` SelfCheck ×3 + WeaveSelfCheck ×3 + Negatives + Bench。

- `SelfCheck`：ref(暴力) vs idx(Bridge) 共享同一 ShadowL 实例流，
  每次 a(j) 比 size+逐槽对象身份与顺序，a(l)/b(l)/c(l)/size/iterator
  全比；流 = 网格 mesh + Lawson 翻边交错（4 种子 × 4 规模）+ 8 组定点
  语义（反向边、dup-add、equals-键分歧 remove、退化重复顶点、
  iterator.remove 侧路+续接织入 add、clear 中段、哨兵负数、remove-miss）。
  结果：**72953 checks PASS**。
- `Negatives`：7 个单缺陷变体全部被判别流拒绝（directed-keys /
  no-dedup / no-dirty-remove / no-sidepath-check / unordered-bucket /
  coord-keys / shared-bucket-list）。
- `Bench`（side=30，~1682 三角形，15642 查询/腿）：
  stride=1（对抗性逐边翻）idx≈590-778ms vs ref≈762-1196ms；
  stride=12（≈宿主 query:remove 比）idx≈236-283ms vs ref≈454-866ms；
  stride=1000（近纯查询）idx≈12-25ms vs ref≈309-464ms。
  量级结论：查询占比越高收益越大；宿主 2500 样本扫描窗对 218 remove
  样本（≈12:1）落在 stride≈12 区间，最坏交错也不劣于基线。

## 宿主等价门（A/B 时）

- dump 腿四序号 SHA 与基线逐字节一致（等价门，同 DWEAVE）。
- 标准门全过；JFR 中 TriangleList.a/iterator 窗样本应塌缩至零头。
- 任一 INVALID/reject/verdict 异常 → 停批。


## 2026-10-01 生产 remove 修正（新工件待实机）

旧文“按插入序 equals 扫描与 HashMap 删除选择完全一致”不是一般性保证：
常数 hash 的桶会 treeify；如果已有对象的 equals 关系改变，原生树查找可能选中
不同于插入序首个匹配的对象。即使宿主常见输入没有此退化，旧 helper 先做
一次线性 equals 扫描，再经 iterator.remove 进入原生 removeNode，增加了成本。
5203 两对实机观测未证明性能收益；第二对 on/off CPU 为308.4/287.8秒。

生产修正先执行且只执行一次 s.remove(tri)，返回值和实际被删除对象由原生集合
决定。簿记不再调用元素 equals：仅在状态干净、记录键数=当前集合大小+1、记录
含 tri 时扫描所有存活身份。如果每个存活身份都已记录且 tri 不在其中，基数
证明 tri 是唯一缺失记录，可按其自录键精确摘桶；否则标脏，下次查询按真实集合
重建并清除消失记录，未知存活对象仍永久回退。不能根据参数 equals 或索引猜测
被删对象。此证明不依赖坐标/equality 不变，也覆盖 iterator 移除后等值重加。

测试先复现额外 equals 次数及可变 equality 下实际 victim 不同的失败，再验证
修正与原生集合的逐对象身份顺序一致；既有等值异索引、弱身份生命周期、并发与
织入回归保留。离线通过不代表新候选已取得实机性能验收。


第二轮候选640e3b1e…的5203 seq2063/2069标准门、边序及生产实跑PASS，但CPU/耗时
仍退化，JFR新增IdentityHashMap.containsKey叶806样本。逐存活对象再次查身份表
没有利用已验证变更协议：所有add/clear均织入，唯一未织入写是iterator.remove，
其单调缩小由sz前检（或下一次add前检）检测。因此“干净且删除前基数吻合”已证明
原集合成员身份完整登记；原生remove只从其中删除一个。只须用==确认参数身份
是否仍存活：存活则实际victim是另一等值对象，置dirty重建；不存活则可摘参数
的自录键。后续候选去掉逐存活对象的containsKey，不改变原生删除和回退路径。
这依赖精确宿主的已审阅变更入口，不承诺支持未织入add隐藏的任意同尺寸替换。

## 2026-10-01 后续候选：仅对象身份命中的 contains 快路（已实现，待实机）

三对 29f13cf8 实机未证明稳定收益。r3 的 64 层栈导出中，on 的
`l.equals` 叶样本有 669 个位于 `HashSet.contains` 链，off 为 15 个。
这只是采样归因，不是调用次数或因果效应。5203 javap 再次确认
`TriangleList.c(l)` 在原 null 检查后只调用 `b.contains(l)`；三角形
hashCode 恒零，equals 为顶点坐标的排列比较。

可检验的方案是仅对“已知仍存活的相同对象”返回 true：状态非 dead、非 dirty，
记录尺寸与原集合尺寸相同，身份表存在参数。依赖既有全 add/clear 织入与
iterator.remove 仅缩小集合的变更协议。不得用端点 index 代替几何 equals，
不得把身份未命中直接返回 false；未知、过期或相等但不同身份的参数仍执行
原生 contains。原 null 检查保留。此方案不绕过 add/remove 的原生语义。

实施前还需确认三版本 c(l) 精确形状、固定元素类型与恒零哈希绑定；增加
身份命中、相等异身份、坐标变化、iterator.remove 后过期状态、dead 状态的
差分回归。必须更新织入形状与工件哈希，并单独实机验证。当前资源 A/B
继续使用冻结的旧候选，不含此方案；尚未对该方案作性能承诺。

补充静态核验：5203（bcc6e34f…）、5302（988ef6a8…）、5303（bd0a23b9…）
三个官方 JAR 均确认 c(l) 调用 LinkedHashSet.contains，且 l.hashCode 只有
iconst_0 / ireturn。逐版本 JAR SHA 与原始 javap 片段保存在
`build/t029-real-host-acceptance/contains-shape-review.json`。这只排除了这两个
入口的版本形状差异，不替代候选差分测试或实机效果验证。


实现进展：生产 helper 新增正向身份 contains；patcher 对 c(l) 的唯一原生
contains 站点增加第五项准入检查，缺失时整类拒绝。19 项 helper、12 项
transformer、5 项 installer 测试通过；官方类形状测试和 devCheck 通过。
打包后的 agent 为 `77ce425567f8a4fb1ef1c5055fe5015a33c1cf1dcefbc90dcfff9c0bbeebd17c`。
使用该 JAR 中的 patcher 处理三版本官方类得到：

- 5203 patched SHA `33bea8bc8bf437df71915b17a704a2283946424458b54848cdb8cdea35010cb4`
- 5302/5303 patched SHA `f3427cec0e5c0c7d93c8a2cf72351a82a39b635b15682afc291eb3a62dc888f5`

原始类 SHA 未变。原生 add/remove 保持不变，unknown/dirty/dead contains
仍使用原生几何相等查找。新候选尚无实机效果证据；完整 wrapper 绑定回归已通过。
