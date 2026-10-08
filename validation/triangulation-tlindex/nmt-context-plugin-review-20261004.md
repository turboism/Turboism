# T099 NMT/堆页面诊断离线通过

独立无JFR插件73e7ab52570a5399876ed9d4281d234703d83dd931e9f501bea2cf715c49101f，生产T057/T093不变。真实进程内DiagnosticCommand vmNativeMemory(summary,scale=KB)和只读gcHeapInfo通过，禁attach；NMT未启用时明确拒绝。保留原MXBean上下文，在同20组采集内查询原始NMT分类与G1堆地址范围；不触发GC、启用监测或请求栈采样。原native command wrapper class与T098逐字相同，原计数方法源码不变，十个输入/CPU/writer/snapshot class逐字相同。六项打包输入/命令、JVM上下文、NMT启用/拒绝、T057/T093生产loader检查PASS，85离线工件/日志/审查重新核验SHA。

Linux自有实际JVM对照已验证JVM报告heap range与/proc/smaps映射，堆RSS非0、无驻留跨界映射，原始smaps gzip及SHA保存；NMT heap reserved与地址跨度完全一致。30自有控制工件重核SHA。helper-r1的summary-only前身PASS保留，helper-r2增补只读heap range后PASS，未发生宿主重试。真实Cubism/Wine的地址对应尚未验证。

独立观察器保持Java PID/startTicks与task cgroup/inode，5秒一次保存原始smaps压缩文本/SHA、读取时段、对应原始NMT快照和heap/outside/crossing分类汇总。跨边界映射不按比例分摊；不可归属驻留独立记录。分析器要求20 NMT/上下文快照绑定、NMT查询位于原命令CPU计数区间外、G1地址/NMT reservation相同、稳定进程和地址、所有原始smaps重解压重算、每30秒baseline/retained至少4个完整观测。精确/跨界/缺映射/缺指标、NMT KB单位/重复分类/committed超过reserved控制PASS。

NMT startup bookkeeping、查询和smaps读取是独立诊断开销，不能用于正式性能收益或T097豁免。NMT reserved/committed不是RSS，Wine/第三方native可能不被NMT跟踪；组中位数也不必加总为总RSS中位数。没有已证明的生产根因或新的生产修复。下一步两腿独立5303 FIFO，标准/完整受控输出/输入/CPU单位/退出/kernel清理及页面绑定均要审核；不补跑、不改历史门槛，T057交付及全部开放项保持。

首次准备r1的NMT JVM选项放在transport后，wrapper规范化导致输入顺序等值断言失败，未生成共享prepared/未提交；r1保留。r2将该选项置于transport前，按顺序准备两腿，属准备修正而非宿主重试。
