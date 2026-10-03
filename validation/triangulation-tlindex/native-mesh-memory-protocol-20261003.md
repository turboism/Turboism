# T089 独立表分配与 GC 诊断

FROZEN_BEFORE_SUBMISSION。仅一次 5303 FIFO，禁止替换、重试或授予性能验收。T088 首对 RSS 失败保持不变，早期0.89MiB例外不适用。正式 eb687 候选保持不变。

独立 overlay d0de71b53150801f05347bc71140a6b885eb2b6dfc19cc661eb4ba6cfc45ff3d，仅改 helper 与 Scope、增加 NativeMeshMemoryScopeEvent，其余5313条目相同。不改变原 native 字节码、gate、查询返回、追加和拒绝条件。Scope前后的线程分配计数，真实表的 initialEntries/entryLimit/capacity/bytes，以及真实查询合计，释放后提交事件。观察器自身首次初始化和 gate capture/generation 不在此线程分配差中；计数包含之后的诊断开销。

真实 SDK 三版本/两种断言模式 288 完整几何记录逐字节相同，capacity/declared bytes/实际线程分配下界/查询合计/释放均 PASS。内存分析负向控制拒绝错容量、错字节数、低分配、错初始项数、未释放、丢事件、DataLoss。

同 heavy 固定SHA/711 来源/三轮命令、固定JFR-clock插件与startup-readonly设置探针、一个premain与DisableAttachMechanism。JFR profile 加详细 GC/heap stdout 日志；没有强制 GC、堆大小修改、附加agent或备份调整。

判据：正常退出/身份/模型/配置/内核安全清理/CPU纳秒单位/2133行七字段参考一致。无JFR DataLoss；同录制task/EDT/30CPU字段绑定；每轮恰711成功scope且有真实tableCalls，全部released=true、unknown=0、查询合计一致。初始项数<=entryLimit<=16384，capacity由limit固定倍增且declaredBytes=12*capacity+1024；线程分配差>=12*capacity。要求GC和heap summary事件。作用域外事件排除；GC按同JFR时钟分类，不将Javaepoch堆marker套用到JFR时间。

分析表数组确定容量、helper/suffix线程分配与GC/heap摘要；JFR allocation weights只作估计。按initialEntries估算更小初始容量仅为未实现设计；不把其推测当生产改善。该新运行不能证明T088历史失败的原因，不允许据此清除原失败或自动重跑性能对比。

后续实施应由分配/GC证据支持，保留完整正确性/资源目标。三版本真实宿主、长期及LaneC仍开放，T057交付和历史失败保持不变。
