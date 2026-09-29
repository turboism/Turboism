# T029-DMATCH — h.c() Phase-3 匹配窗口索引化（离线）

冻结依据：任务内联描述（本 workspace `specs/020-atlas-image-parallelism`
tasks.md/plan.md 中尚无 T029-DMATCH 条目；grep `DMATCH` 无命中）。
设计、javap 证据、语义结论、差分矩阵与负控清单：`DESIGN.md`。

## 目标与边界

候选只限 `h.c()` Phase-3（bci 223-522）匹配窗口：外层 `k.c()` 边迭代
×内层 `TriangleList.iterator()` 三角迭代，逐三角构造
`j(l.a(),l.b())/j(l.b(),l.c())/j(l.c(),l.a())` 三候选，
`r.a 相交 flag`→`!h.a 端点共享`→`!matchList.contains`→`add`。
把 contains 由 O(n) 恒等线性扫描降为 O(1)——**j 无 equals/hashCode
（恒等语义，javap 已核实）→ 恒等键集合是唯一无条件等价形态**。
不改生产、不织入、不执行官方类、不新建宿主作业。

- Reference：`Shadow.matchRef`——逐指令影子（真实 ArrayList.contains）。
- Candidate：`Shadow.matchCand`——`IdentityHashMap` 背书局部集合，
  追加仍写真 ArrayList；索引返回后不可达。
- 差分：同输入引用、逐事件比较（identity 归一化）、结果结构摘要、
  异常类+窄规范化消息；负控 9 项与 2 个 j 变体域全部实装。
- 验收止于离线差分；是否生产化须另冻结与实机证据。

## 复现

```
TURBOISM_KOTLIN_STDLIB=<kotlin-stdlib-1.7.21.jar> \
bash validation/triangulation-dmatch/run.sh
```

run.sh：sha pin（stdlib 1.7.21=`d46a9d77…`；ASM 不需要未引入）、
构建前统一队列空闲检查（busy 等待）、`-Xverify:all`、SelfCheck 三连
+ Bench 一次。计时仅合成成本、量纲分开、无速度阈值、不外推宿主收益。
