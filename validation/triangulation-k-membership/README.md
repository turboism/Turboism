# T029-KBUILD — 局部边成员查询差分实验（离线）

冻结依据：`specs/020-atlas-image-parallelism/plan.md` 的 T029-KBUILD 节（ignored 同步副本）。

## 目标与边界

候选只限 `TriangleList.b()` 构建窗口：本地新建 `k`、按原三角顺序取三条原边
`l.d()/e()/f()`、无向 index 成员查询后条件 append、返回原 `k`。
**不**生产织入、**不**给暴露活表的 `k` 加跨调用索引、**不**执行官方类。

- Reference：`k.a(j,false)` 的线性无向扫描（逐字节谓词：`(a==a&&b==b)||(a==b&&b==a)`
  的 `TriPoint.getIndex` int 比较）。
- Candidate：窗口本地 `HashSet<Long>`，键 `((long)min << 32) | (max & 0xffffffffL)`
  （低 32 mask，负/极值不碰撞）；查询走集合、append 仍走真 `k.a(j)`；
  索引为局部变量，返回后不可达。

## 已实核的宿主事实（不再沿用旧报告猜测）

- `Intrinsics.checkNotNullParameter`（宿主 kotlin-stdlib **1.7.21**）抛
  **NullPointerException**（"Parameter specified as non-null is null: method …,
  parameter …"）——此前报告的 IAE 作废；fixture 用同一 stdlib jar 产出真实前缀。
- `b()` 求值顺序：三个 getter **先于**三个 contains/add 对（bci36/41/47 → 53-103)。
- getter 对 null `l`：`l.d()` 处 JVM NPE（非自动 IAE)。

## 域与明示限制

| 域 | 状态 |
|---|---|
| 重复/反向/退化边、重复 index 异坐标、负/极值 int、空集、null 查询边（NPE 前缀）、null 三角（getter NPE)、getter 故障（d/e/f 三位各自） | **已建模且差分** |
| 存储元素的 `j.a()/b()` 返回 null TriPoint | **域外，不建模**——Kotlin 非空契约下不可构造；线性实现按 `e.a()→j.a()` 顺序解引用而索引先读 `j`，空表时线性侧甚至不触碰查询边端点。不默默归一化 |
| 宿主类型等价（TriL/EdgeJ 的 Kotlin data-class equals/hashCode 等） | **域外**——fixture 是字节码核实的行为切片，不声称宿主类型等价 |
| 资源限制（分配失败/内存压力） | 域外，未建模 |
| 异步/并发 | 不涉及（窗口单线程语义） |

比较器负控：有向-only 候选（拒）、等值异引用伪造表（拒）、方向翻转元素（拒）、
getter/query 重排（拒）、步骤轨迹截断（拒）、用户自造故障消息精确比较
（"getter:A"/"getter:B" 不合并）。异常规范化仅两条窄映射：Kotlin intrinsic NPE 的
调用方法名段、隐式 NPE 的 `<localN>` 槽号；其余消息 byte-exact。

## 产出边界

- `Bench` 驱动同一实现（NOOP observer),0/1/2 小输入 + 128/1024/4096 三角、
  每轮原始 ns、A-B/B-A 交替、计时区含 k 构建与索引分配、计时外 checksum/全列表
  引用等价校验；**无阈值、不外推** 132 叶样本为耗时/收益。
- 验收止于离线差分；是否生产化须另冻结与实机证据。
- 活表 `k.a()` 只否定"无失效协议的全局缓存";`h.a` 局部缝是否可能属本期范围
  裁决停止，不做全称否定。

## KWEAVE 织入切片(T029-KWEAVE)

- `WeaveTarget`：无 Observer 的纯 `b()` 目标类（织入输入；woven 与 Observer-based
  `RefTriangleList`/`CandTriangleList` 是不同形态——woven 走真实字节改写，
  observer 版本仅做差分建模，二者分工已明示）。
- `Weave`：核心 ASM(无 asm-tree）两遍处理。pass1 形状钉：恰好 3 处
  `[ALOAD k, ALOAD jn, ICONST_0, INVOKEVIRTUAL k.a(LEdgeJ;Z)Z]`、3 处 append、
  单 ARETURN、iterator 初始化存在、k-init 锚点、跳转目标不落入替换区；
  任一不符→**返回原字节不改**。pass2 在锚点后插入 entry init
  (`aconst_null;astore box` + 窄 try `Helper.newBox()` catch LinkageError→null),
  每调用点整段替换为 `box/j null 前置门 → 窄try invokestatic query → handler
  置 box=null → 原查询` CFG;COMPUTE_FRAMES/MAXS 全量重算。
- `Helper`：独立编译目录（classes-helper)——真实隔离 loader 负控；`newBox`/
  `query` 计数器+注入故障旋钮（仅 fixture)；宿主纯读白名单仅
  `j.a()/j.b()/getIndex()`。
- 验收断言（59 checks 含原 28)：正常路径 newBox=1/helperQuery>0/originalQuery=0、
  反向重复边命中且原引用序保持、init LinkageError 每位点恰 1 次原查询、
  第 N 次 query LinkageError 后永久本地 null(helperQuery 冻结）、null 边走
  真实 NPE、RuntimeException/ThreadDeath/VMErr 透传不重试、两次 b() 独立 box、
  空输入 newBox=1/query=0 且分配计数如实（box=1,set=0 懒建）、shape
  拒织（true/错desc/位点数）字节不改。
- `IsolatedRun`：无 Helper classpath 单独 JVM(-Xverify:all)——织后类可加载、
  全量委托原路径（originalQueries=6×2 调用）。
- 依赖：kotlin-stdlib 1.7.21(sha `d46a9d77…`)+ ASM core 9.7.1
  (sha `8cadd43a…`)，均 sha pin。

## 复现

```
TURBOISM_KOTLIN_STDLIB=<kotlin-stdlib-1.7.21.jar> \
TURBOISM_ASM_JAR=<asm-9.7.1.jar> \
bash validation/triangulation-k-membership/run.sh
```
