# T086 原生网格实机路径验证

状态：FROZEN_BEFORE_SUBMISSION。仅一次 5303 FIFO 诊断，禁止替换或重试。

候选 Agent：eb6875699d3fdb92e3340a0a0af106fcf30f2985d490389f81fe3a0823c209f8；已接受 T057 保持不变。

同一 heavy 固定模型，711 个来源，三轮命令。要求正常退出、身份与模型绑定、内核安全清理、原生共享准入日志及 2133 条输出的七字段参考一致性。只使用一个生产 premain，禁止附加 Agent。

JFR ExecutionSample 预设 1 ms。使用同录制 task/EDT/CPU payload 绑定的三轮调用区间；每轮至少一条 NativeMeshEdgeTable.find 栈帧，单独 helper 栈不够。缺失为未证实，禁止有利重跑。此采样证明执行路径，不证明性能改善。

性能验收：NOT_APPLICABLE_DIAGNOSTIC。通过后才冻结无 JFR 的正式 ABBA；历史失败保持不变，三版本、长期及 Lane C 仍待验收。原始证据目录 build/t086-native-mesh-host-proof-r1/native5303-index-proof-r1。
