# T088 正式候选四腿比较

FROZEN_BEFORE_SUBMISSION。固定 order baseline1, candidate1, candidate2, baseline2；配对 baseline1/candidate1 与 baseline2/candidate2；每腿三轮、711 来源，不重试、不替换。

原 T057 Agent 17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295 对比原 T085 候选 eb6875699d3fdb92e3340a0a0af106fcf30f2985d490389f81fe3a0823c209f8。不包含 T087 helper 覆盖，不开 JFR，不增加计数。一个生产 premain、DisableAttachMechanism、相同非 JFR observer-free 插件及固定模型/配置/参数；四个 normalized argv 完全一致。仅路径/标签变化。

T086 原候选有一次表查询同线程绑定证据但冻结路径判定 FAIL，保持不变。独立 T087 覆盖诊断 PASS，三轮各 711 个作用域和 303291/303894/303738 次表命中，输出一致；该证据不等于每个正式未插桩运行的观测，不用于性能值。

每腿要求正常退出、身份、模型与冻结配置绑定、内核安全清理、2133 行七字段输出参考一致、独立内核纳秒 CPU 单位检查 PASS。若证据无效停止并保留全部记录；单纯性能门槛失败继续完成其余合法腿。

原门槛保持：每对 CPU 增幅 <=0%，每对 wall 严格减少，peak RSS 相对基线 <=20%，相对自身基线增量 <=80 MiB，retained3-1 <=64 MiB。汇总不能覆盖失败配对；RSS 为采样值，CPU 单位不保证供应者时钟分辨率，两对不是统计收益结论。

比较结果不会授予完整生产验收。三版本真实宿主、长期和 Lane C 仍待完成；T075/T077 失败与已接受 T057 保持不变。
