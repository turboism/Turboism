# T029-DWEAVE — h.c() Phase-3 MatchList 单点织入（离线切片）

冻结依据：`specs/020-atlas-image-parallelism/plan.md` T029-DWEAVE 冻结节 +
tasks.md 同名条目（主仓版；本 worktree 的 spec 尚未同步该条目）。
设计、javap 证据、语义论证、门矩阵与宿主 A/B 准入草案：`DESIGN.md`。

## 目标与边界

把 `h.c()V` Phase-3 起点唯一的 `new ArrayList`（bci 223-230，ASTORE 槽 7）
单点替换为 `MatchList extends ArrayList`（仅覆盖 `contains`/`add`，
IdentityHashMap 镜像 O(1) 同一性判定）。只改 NEW 的类型操作数与
INVOKESPECIAL 的 owner 两个操作数，其余指令逐字不变。离线切片只验收：
自有窗口复刻类织入全差分、官方 h.class 只读探针（分配点形态+织入模拟）、
形状拒织可观测、`-Xverify:all`。不改生产/Runner、不执行官方类、不新建
宿主作业。

- 织入器：`Weave`（core-ASM，两遍；形状门=pinned 槽位恰好一处 +
  总位点数钉住；`Result` 携带拒绝原因与位点清单）。
- Fixture：`OwnWindow.c()V`——同一方法内含两处 `new ArrayList`
  （Phase-3 matchList + Phase-4 工作表）、嵌套迭代 +
  contains/add 门、post-window remove(0)/re-add/isEmpty/addAll 消费。
- 差分：`SelfCheck`——woven 字节经独立 loader define 后与 unwoven
  在同一输入引用上逐事件/结果/异常比对；MatchList 单测逐点契约；
  8 项形状负控全部断言拒绝原因。
- 探针：`OfficialProbe`——只读官方 jar h.class，核 sha、位点唯一性
  （pinned slot 7）、织入模拟接受、两处操作数差分证明；绝不
  define/execute。

## 复现

```
TURBOISM_KOTLIN_STDLIB=<kotlin-stdlib-1.7.21.jar> \
bash validation/triangulation-dweave/run.sh
```

run.sh：构建前统一队列空闲检查（busy 等待）、依赖 sha pin
（stdlib 1.7.21=`d46a9d77…`；ASM 9.7.1=`8cadd43a…`，默认取
`../triangulation-weave-ab/deps/` 受审件，可用 `TURBOISM_ASM_JAR` 覆盖）、
`-Xverify:all`、SelfCheck 三连 + 官方探针一次（`TURBOISM_CUBISM_JAR`
可覆盖默认 5.3.03 安装路径；缺失则 SKIP 并明示）。
