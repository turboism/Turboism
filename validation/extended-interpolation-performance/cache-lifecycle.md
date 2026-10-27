# 缓存预算与生命周期验收

本轮在 `NativeFormLookupBridge.cacheStatistics()` 暴露不持有宿主引用的统计快照：辅助索引预算、构建/命中/超限次数、最大观察 bank、活跃作用域与索引条目、峰值条目。预算仍是单次结构重建最多 65,536 个输入形态；总数以 long 相加，超过预算回原生查找，每作用域只计一次拒绝。该预算不限制原生 dense grid 或作者插入点。

退出时在正常/异常路径平衡作用域，并清除 token 内的 source、extension、index 强引用；即使外部保留已退出 token，也不应借此保留模型。嵌套作用域测试核对活跃计数的恢复。统计值分别原子读取，并非并发事务快照，不应把各项瞬时差值解读为泄漏。

## 5.3.04 动态证据

载体为 `CacheLifecycleProof.java`，由 `run-runtime-proof.py --installed --cache-lifecycle` 驱动真实官方类和安装后的转换器，先验证目标版，再逐版扩展。三类对象 × ELLIPSE/SNS × 原生/优化，共 12 个场景；三轴每轴 8 个插入点产生 6,832 个 Normal 派生形态。

- 构建线程累计分配约 514–534 MB；显式 GC 后整个 JVM 的活堆增量约 3.23–4.19 MB。后者是同进程基线差值，**不是对象图 retained size、进程 RSS 或同时峰值**。
- 保持 owner 存活，将每轴插入点缩减为 4，得到 1,304 个形态；原 6,832 个派生形态的弱引用全部清空。再释放模型，owner、extension 和当前形态共 1,306 个弱引用全部清空。
- 额外强持有已退出 token，以 reachabilityFence 保证存活，同时检查其原 owner/extension 均可回收。
- 最终 activeScopes=0、activeIndexEntries=0，peakIndexEntries=6,859；所有 12 个场景均通过。
- 每个 heap pool 单独记录 peak usage；这些峰值可能出现在不同时间，不能相加后声称是 JVM 同时峰值。

目标版成功原始日志来自 `/tmp/extended-cache-lifecycle-r2/5.3.04/proof.log`。首轮 r1 虽完成断言，但官方非 daemon helper 使 JVM 未退出，随后仅终止已核实的任务进程；不计为退出成功。载体已在断言结束后显式退出，r2 正常退出 0。

## 结论与后续

当前证据支持继续优化结构构建的瞬时分配，不支持“旧派生缓存持续泄漏”判断。原生缩减网格时已能释放旧形态；辅助索引保持即用即清。暂不引入派生形态驱逐或惰性结构：须先在真实大工程证明驻留内存成为约束，再审计求值、Morph、历史恢复、保存与导出的原生访问契约。完整网格仍按原生创建，作者设置不变。

最新版 20 版本常规安装差分已通过，归档于 `evidence/budget-installed-matrix/`；独立缓存生命周期矩阵也已通过同样 20 版本、共 240 场景，归档于 `evidence/cache-lifecycle-matrix/`。每版均检查全部旧形态、释放后的模型引用及已退出 token 引用回收，退出码均为 0；源码指纹与当前实现一致。不能用常规差分替代回收证据。
