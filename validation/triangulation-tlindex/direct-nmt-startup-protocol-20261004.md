# T100 旧Windows JVM直接启动NMT可行性预检

本协议仅一腿baseline T057/5303，单次FIFO；不是重跑T099。T099的NMT初始化失败、标准FAIL/正常退出false等原判定及46原始文件保留，候选不补提交。Linux17.0.20与宿主17.0.3.1的JAVA_TOOL_OPTIONS行为不同，旧宿主未正确启用NMT；新方案通过官方BAT既有ProxyConfig/MAXMEMORY调用位置直接传参。

同73e7ab52诊断插件、heavy/config/settings、唯一premain/禁attach/无JFR/原GC日志及kernel+5秒smaps观察器，生产Agent不变；从JAVA_TOOL_OPTIONS移除NMT。自包含pre-launch hook仅修改新任务克隆ProxyConfig，原全部bytes保留后追加原MaxRAMPercentage=100及NMT summary。官方BAT及原ProxyConfig固定SHA、路径/任务/版本/参数位置均核验，golden不改；队列仅在canonical scripts/preview且atlas-image-shadow:5303、同步标准context、fixture/plugin/禁attach/资源观察及诊断token齐全时准入，其他阶段/路径/版本/任务/附加hook参数拒绝。28 PreparedStore快照/冻结/准入拒绝测试和配置保留/拒绝自检PASS。r1准备因缺依赖清单拒绝且未提交，保留；r2显式登记依赖清单并准备通过，不是宿主重试。

终态先保留全证据，标准身份/模型/config/正常退出/kernel安全清理、2133完整受控T057来源/几何/点版本/uint32边变化/缓存状态零差异、六非空POINT_ADD无按钮MOUSE_MOVED输入及六独立CPU单位、20上下文/20NMT采集与计数区间关系、NMT分类/地址绑定、真实Wine heap address对应smaps/raw SHA重算、各idle至少4完整观测必须核验。pre-launch前后原配置bytes和official BAT不变、task ProxyConfig staged hash及无env NMT/无wrong-launcher警告也要核验。失败终止，不替换/补跑。

NMT accounting不是RSS；跨heap边界驻留不分摊，稀疏/非原子观测和未跟踪Wine/native限制保留。查询/启动/observer是独立诊断开销，不授予正式性能验收，不覆盖T088/T097/旧参考FAIL。不得attach/强制GC/开启监测/栈采样；T057交付与多版本真实宿主/长期稳定性/LaneC开放项保持。通过后再依据可行性证据规划独立候选诊断，当前不包含候选提交。
