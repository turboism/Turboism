# T029-TRIAB 设计说明 — dump+weave A/B 定论腿（离线切片）

冻结依据：`specs/020-atlas-image-parallelism/plan.md` T029-TRIAB + `tasks.md` 同名条目。
目标：两腿交错（`base`/`woven` ×2）实机裁决"构建窗口局部成员去重"候选；本轮只交付
离线实现切片与准入材料，不提交实机运行。

## 1. 官方类型与描述符（javap 只读核定）

证据对象：`Live2D_Cubism.jar` (5.3.03)，jar SHA-256
`bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166`；
`TriangleList.class` SHA-256
`87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29`（与
T029-IDENTITY 钉住的是同一摘要）。

织入目标（`WeaveAbConfig.OFFICIAL_WEAVE` 的编译期常量）：

| 项 | 精确字符串 |
|---|---|
| target internal | `com/live2d/graphics3d/editableMesh/triangulation/TriangleList` |
| method | `b()Lcom/live2d/graphics3d/editableMesh/triangulation/k;` |
| kType | `com/live2d/graphics3d/editableMesh/triangulation/k` |
| jType | `com/live2d/graphics3d/editableMesh/triangulation/j` |
| ctorDesc | `()V` |
| query | `k.a(Lcom/…/triangulation/j;Z)Z`（`INVOKEVIRTUAL`，Z=`iconst_0`）|
| append | `k.a(Lcom/…/triangulation/j;)Z`（条件守卫后）|
| iterator 钉 | `java/util/LinkedHashSet.iterator()Ljava/util/Iterator;` |

Helper 触碰链（白名单，仅纯读）：

```
j.a()Lcom/live2d/graphics3d/editableMesh/triangulation/TriPoint;
j.b()Lcom/live2d/graphics3d/editableMesh/triangulation/TriPoint;
TriPoint.getIndex()I
```

javap 证据摘录（`javap -c -p -cp Live2D_Cubism.jar …TriangleList`，`b()`）：

```
 0: new           #14    // class .../triangulation/k
 3: dup
 4: invokespecial #46    // Method .../k."<init>":()V
 7: astore_1
 8: aload_0
 9: getfield      #35    // Field b:Ljava/util/LinkedHashSet;
12: invokevirtual #70    // Method java/util/LinkedHashSet.iterator:()Ljava/util/Iterator;
15: astore_2
16: aload_2
17: invokeinterface #81  // Iterator.hasNext:()Z
22: ifeq          107
25: aload_2
26: invokeinterface #82  // Iterator.next:()Ljava/lang/Object;
31: checkcast     #15    // class .../triangulation/l
34: astore_3
35: aload_3
36: invokevirtual #53    // l.d:()L.../j;
39: astore        4
41: aload_3
42: invokevirtual #54    // l.e:()L.../j;
45: astore        5
47: aload_3
48: invokevirtual #55    // l.f:()L.../j;
51: astore        6
53: aload_1
54: aload         4
56: iconst_0
57: invokevirtual #48    // k.a:(L.../j;Z)Z
60: ifne          70
63: aload_1
64: aload         4
66: invokevirtual #47    // k.a:(L.../j;)Z
69: pop
70..94: （j5、j6 两处同形）
104: goto          16
107: aload_1
108: areturn
```

支撑类型签名（`javap -p`）：

```
k:  public final java.util.ArrayList<j> a();
    public final boolean a(j);
    public final boolean a(j, boolean);
j:  public final TriPoint a();
    public final TriPoint b();
TriPoint: public final int getIndex();
l:  public final j d(); public final j e(); public final j f();
```

结论：官方 `b()` 与 KWEAVE 已验收 fixture 的结构形状一致——`NEW k/DUP/<init>/ASTORE`
锚点、LinkedHashSet 迭代、三 getter 先行、三个
`[ALOAD k, ALOAD jn, ICONST_0, INVOKEVIRTUAL k.a(j,Z)]` 查询序列、条件+append+POP、
单 `ARETURN`。同一 Config 机制驱动 shadow fixture（不同 owner/方法名
`produce/has/add`，证明参数化真实生效）。

## 2. 单一变换实现

`Weave.java`（`validation/triangulation-k-membership/…`）泛化为
`Weave.Config`：methodName/kType/jType/queryName/appendName/helperInternal 注入，
结构性描述符（`methodDesc/queryDesc/appendDesc/helperQueryDesc`）由类型名推导而非
独立接受——描述符无自由度可被配错。无第二实现：`tri-weave-ab` 的 agent jar 直接把
KWEAVE 的 `Weave.java` 编入（`-implicit:none` 源编译），`WeaveAbConfig` 只传 Config。

**回归等价性证据**：`validation/triangulation-k-membership/run.sh` 在重构后重跑：
`KBUILD_SELFCHECK PASS checks=98`（与 e0f5667d3 验收同数）、
`KBUILD_ISOLATED PASS`、bench `listEqual=true` 全档、`KWEAVE_RUN PASS`。
FIXTURE 配置为编译期常量，原 `weave(byte[])`/`weaveChecked(byte[])` API 语义不变
（拒织→原 bytes + 可观测 reason）。

## 3. 织入门行为矩阵

两段式 transform（`AbTransformer`）：身份门 → 可解析门 → 候选织入（仅 woven）→
采集织入（两腿同）。

| 条件 | dump-only | dump+weave | 证据 |
|---|---|---|---|
| className≠target | 透传 | 透传 | — |
| loader 不符 | `gate=reject reason=loader`，原 bytes | 同 | def log |
| class sha 不符 | `gate=reject reason=classSha` | 同 | def log |
| codeSource 不符 | `gate=reject reason=codeSource` | 同 | def log |
| Helper 不可解析（目标 loader）| —（不检查、不需要）| `legStatus=INVALID helper-unavailable` | status 文件（同步写）|
| Capture 不可解析 | `INVALID capture-unavailable` | 同 | status 文件 |
| 候选形状拒织 | —（候选不参与基线）| `INVALID weave-reject:<reason>` | status 文件 |
| 采集形状拒织（≠1 ARETURN / 方法缺失）| `INVALID capture-reject:<reason>` | 同（在织后字节上跑）| status 文件 |
| 全部通过 | capture 织入，`gate=accept` | 候选织入 + capture 织入 | def log |

要点：身份门失败**不是** INVALID——它只是跳过非目标副本；INVALID 仅标记
"钉住正确的目标字节后变换级失败"。woven 腿形状拒织一律硬失败，绝不静默降级为
基线；dump-only 不需要 Helper，缺 Helper 照常工作。

## 4. Sink 上界

| 界 | 值 | 机制 |
|---|---|---|
| 采集调用数 | `captureN`≤4（默认4）| `Capture.SEQ`；超限直接 return |
| 单字段 | 256 chars（edges 字段 4096）| `Sink.field` 追加期逐字符逃逸+截断 `~truncated` |
| 单行 | 4800 chars | `Sink.offer` 截断 |
| 单文件 | 64 KiB | writer 计数后写 `bytesCapReached=true` 一次 |
| 队列 | 32 条 | `ArrayBlockingQueue.offer` 非阻塞，丢则 `queueDropped++` |
| INVALID 标记 | 同步 `Files.write` 追加 | 不经队列，daemon 未排干也落地 |
| 定义事件 | 4 + overflow 标记 | `AbTransformer.eventSeq` |
| 守护线程 | 1 个 daemon `tri-weave-writer` | premain 预启 |

逃逸保序：`\n`→`\\n`、真实换行→`\\n`、tab→`\\t`、空格→`\\s`、控制符→`\\xNN`，
`maliciousFields` 场景断言可区分与有界。

## 5. 两模式差异唯一性论证

- 两腿共享同一份 `Capture`/`ShadowCapture` 采集实现与同一份 `CaptureWeave`
  （`ARETURN` 处存值→`onReturn`→catch(Throwable)→恢复原返回路径）；dump-only 与
  dump+weave 的采集代码在字节上同源（同一编译单元、同一字段 schema），`mode=`
  字段只是记录、从不参与分支。
- 两腿间唯一差异：`AbTransformer` 中 `config.woven()` 为真时执行
  `Weave.weaveChecked`（外加 woven 专属的 helper 可解析门与 premain Helper 预热）。
  dump-only 分支在候选织入前直接跳过该调用。
- `Capture.onReturn` 的 seq 计数、SHA-256 域（`i0,i1;` canonical）、有界 raw 序列、
  逃逸与 sink 路径两腿一致 → 每序数 `sha256` 可直接配对比较。
- 离线证明：`happyDump` vs `happyWeave` 同一 shadow 输入，Python 断言 per-seq
  sha256 完全一致（`TRI_WEAVE_SHA_PARITY PASS`）；`fallbackInit`/`failAtN`/
  `missingHelperWeave`/`shapeReject*`/`badReturn*`/`getterFault*`/`nullEdge`/
  `emptyWeave` 覆盖回退、硬失败、异常直通、天然 NPE 与空输入。

## 6. Helper 语义（与 KWEAVE 一致）

- `newBox()`：每次目标方法进入返回独立 `Box`（`HashSet<Long>` 懒建）。
- `query(k,j,directed,box)`：`undirected` 归一化端点 index 对 → 64-bit key；
  `directed` 保留序。命中 = `!seen.add(key)`。仅 `LinkageError` 可恢复——织入块的
  catch 将局部 box 置 null（本次调用永久回退原路径，下一次调用重新初始化）；
  `RuntimeException`/`ThreadDeath`/`VirtualMachineError` 直传不重试。
- `Helper.Box` 不出现于任何织入方法描述符/帧——过界类型即 `Object`。
- `Counters`（newBoxCalls/queries/hits）独立成类：dump-only 永不链接 Helper，
  但每条 dump 仍记录 `helperLinked`/`helperQueries`/`newBoxCalls`，woven 腿若静默
  回退会在自身 dump 中暴露（`helperQueries=0`）。
- shadow 侧 `ShadowHelper`/`ShadowCapture`/`ShadowCounters` 同构，注入旋钮
  `failNewBox`/`failQueryAt`/`injectError` 仅经系统属性，不进 agent jar。

## 7. Shadow fixture 与自验

`produce()` 复刻官方 `b()` 字节形状（自建包名/类名/方法名，配置而非硬编码匹配）。
`-Xverify:all` 独立 JVM 每场景一遍：happyDump/happyWeave（newBox=1、query>0、
original=0、SHA=期望常量的 sha256、edges 原文）、failAtN（5 号注入 LinkageError→
本次永久回退+下次恢复）、missingHelperDump（缺 Helper 基线照常）、
missingHelperWeave（INVALID）、shapeRejectWeave/Dump（badshape `queries=2`）、
badReturn（双 ARETURN：woven 侧 `weave-reject:areturns=2`、dump 侧
`capture-reject:capture-areturns=2`）、wrongSha/wrongSource/wrongLoader（身份门）、
observerBudget、off/refusedConfig/writeFailure/maliciousFields/getterFaultDump+Weave
（异常直通且抛异常调用不占 seq）、emptyWeave（空集 sha256-of-empty、newBox=1）、
nullEdgeWeave（天然 NPE 保留、helper 未被触碰）、shapePins（8 变体全拒绝且
reason 可观测）、codeSourceUnit、officialProbe（真实官方字节只读：shaMatch +
候选织入 + 采集织入 + 织后采集全部 accepted，官方类未加载未执行）。

## 8. 四腿准入包草案（不执行）

顺序交错：`base1, woven1, base2, woven2` — 用 `--tri-weave-ab dump-only|dump+weave`
+ 标签 `t029-triab-{base|woven}{N}-heavy-nolayout[-jfr]`；wrapper 强制模式↔标签一致、
`%%20` codeSource 转义、9 项固定属性、`runId=triab-<leg>`。

每腿标准门（与既有实机腿一致）：正常退出、模型不变性、主机身份/来源正确、
安全清理；另有 TRIAB 专属门——woven 腿 `legStatus=INVALID` 文件存在即废腿；
dump 文件按 seq 配对 sha256，任何不等即候选枪毙；woven 腿 dump 中
`helperQueries=0` 视为需人工复核的异常（不自动判非法，因为合法回退也存在）。

JFR 沿用 `9b55f078` 已评审设置（`settings=profile` + `stackdepth=256` 等，
wrapper `-jfr` 标签后缀触发）。主指标仅 JFR 采样桶计数：查询窗口桶、
三角化内部桶、未知栈桶——不得把采样占比换算成耗时或性能百分比。期望方向：
查询窗口桶收缩、三角化内部桶不变；若窗口桶不收缩或出现等价性/稳定性反例，
停止后续腿并如实报告。四腿全过仅结论为"安全的小效应候选已成立"；生产推广需
另行审批。离线证据不构成主机性能证据。
