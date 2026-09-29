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
