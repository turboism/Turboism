# T099 独立NMT与Java堆页面实机诊断协议

固定baseline(T057)/candidate(T093)各一次5303 FIFO/三次原生命令/每轮711来源。同73e7ab52独立无JFR插件、heavy/config/settings/唯一premain/禁attach/原GC日志/一份NativeMemoryTracking=summary启动选项、kernelobserver与5秒smaps observer；除标签外normalized argv相同。生产Agent不变。前置真实进程内NMT+只读GC.heap_info、启用/拒绝、实际打包helper与两生产loader、Linux自有JVM地址/smaps对照PASS；Wine地址对应待本次实机核验。

每腿终态先保存所有证据，标准身份/模型/config/正常退出/kernel清理、2133完整受控T057来源/几何/点版本/uint32边变化/缓存有效状态零差异、六非空POINT_ADD无按钮MOUSE_MOVED输入及六独立CPU单位、20 JVM上下文/20 NMT查询与原计数区间关系必须核验。NMT分类KB单位/reserved≥committed、G1 heap地址跨度等于NMT heap reservation、稳定地址、smaps原始gzip/SHA逐条重算、对应已保存的NMT快照、PID/start及task cgroup/inode与独立ready身份绑定，每个30秒baseline/retained窗口至少4个完整page观测。跨heap边界的驻留映射明确未归属，不按比例分摊；跨界或采样缺口限制必须保留。

NMT reserved/committed不是RSS，非JVM/Wine/第三方native可能未被NMT跟踪；page扫描非原子且稀疏，组中位数不必加总为总RSS中位数。启动NMT/查询/page observer开销是独立诊断，不能当正式性能收益或覆盖T097失败。禁止attach、强制GC/禁用GC、栈采样、开启线程监测，保留原native命令计数源码和字节码。线程退出/不可用不计0，GC/compiler elapsed不当CPU归因。

标准或上下文/page绑定无效则保存后停止，不补跑/替换；不有利重试/改历史门槛/干预他人窗口。首轮r1准备参数顺序规范化断言失败保留，无共享prepared/无宿主提交；r2在transport前添加NMT选项，两腿按顺序准备通过。T057交付与T088/T097/旧参考FAIL、多版本实机/长期稳定性/LaneC保持。
