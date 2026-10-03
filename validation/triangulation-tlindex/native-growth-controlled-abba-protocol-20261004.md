# T097 最终扩容工件正式受控四腿对比

FROZEN_BEFORE_SUBMISSION。固定baseline1/candidate1/candidate2/baseline2，各一次5303 FIFO、每腿三轮与711来源；固定配对baseline1/candidate1、baseline2/candidate2，不重试、不替换，不由汇总覆盖失败配对。T05717b2对照最终T093fa9e，原生helper无诊断覆盖/计数，无JFR录制或JFR时钟插件依赖。四腿normalized argv除了标签完全相同，唯一premain/禁attach；相同非JFR输入插件e03e、配置/设置只读探针/heavy模型/独立kernel观察器。

前置T095最终未插桩工件受控完整2133边界/实际缓存刷新链/独立CPU单位/清理PASS；T094每轮711表scope诊断与分配减少只作为独立路径证据，不进入性能值。T096无JFR插件六项打包helper、两个真实生产loader PASS；10个输入/CPU/输出helper class与b497逐字节一致，直接测量方法源文相同，实际字节码没有JFR包装调用。

每轮在命令与源版本绑定之前向自有实际画布中心派发无按钮MOUSE_MOVED，六条原字段快照在CPU区间外；要求真实非空画布/POINT_ADD/MOUSE_MOVED/无按钮，711来源顺序保持。每腿要求正常退出/身份/模型/config/部署/kernelcleanup、六次独立CPU单位以及2133完整来源/几何/点版本/uint32边变化/缓存有效状态匹配冻结的T092受控T057；四腿之间也比较同完整边界。绝对边版本保留但不跨进程误比。旧T076参考差异单独记FAIL，不覆写T088或其他历史失败；本协议使用预先验证的受控参考，验证内容比旧七字段更完整。

原门槛不变：每对process CPU增幅<=0%；每对命令wall严格减少；候选operation peak RSS相对配对基线<=20%；候选peak RSS相对自身baseline增量<=80MiB；retained3−1<=64MiB。CPU采用相同预解析原生命令两侧process CPU读数，独立kernel单位核验；wall采用读数区间跨度。RSS使用保守operation窗口（包含共同准备/输出快照），各idle窗口>=30秒，保留全部采样间隔。无强制GC/池化、临时增加预算或改变caps。

控制器每腿终态先保存原始证据（包括失败），标准无效停止后续腿且不补跑；纯性能门槛失败不提前结束合法腿。读取失败或其他任务开始性能观察时，离线审查只停止自身，不能因此重启宿主提交。四腿原始输入/结果及计算器固定；两对不是统计收益，也不是完整生产验收。T088正式RSS失败、全部历史失败、已接受T057、多版本真实宿主/长期/LaneC开放项保持。
