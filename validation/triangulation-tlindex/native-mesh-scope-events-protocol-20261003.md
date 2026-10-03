# T087 独立确定性路径诊断

FROZEN_BEFORE_SUBMISSION。一项 5303 FIFO 实机诊断，禁止替换、重试和性能验收。

T086 的冻结失败保持不变。本协议使用独立计数覆盖工件 81840bf2fca8e991edfb59fba53b91220b373a237c7fccd283849ce471e760b8，正式候选 eb6875699d3fdb92e3340a0a0af106fcf30f2985d490389f81fe3a0823c209f8 不变。覆盖工件只改变 helper 和 Scope 两个类条目、增加一个 JFR Event；其余 5313 条目相同。它不等于正式候选。

源码生成检查唯一替换点；保持原有 entry、gate、查询返回、追加与拒绝条件。每个成功建立的 scope 汇总真实 table.find 返回值与追加数。结束时先 end，清空 ThreadLocal/表并释放 gate，再 commit。仅成功关闭的 scope 会报告 released。独立事件不会调用主机对象或用户回调。

真实三版本 SDK、两种断言模式共 288 条几何记录对照 PASS，并验证 event 查询合计与释放。实机同 heavy/711 来源/三轮命令、一个 premain、禁止 attach、固定插件配置。要求正常退出、身份绑定、模型不变、内核安全清理、2133 输出七字段参考一致。

使用同一录制 NativeCommandInvocation 的 task/EDT/30 个 CPU 字段绑定区间；每轮至少一个完整包含的 NativeMeshIndexScope，且实际 tableCalls 至少一。所有区间内 scope released=true，unknown=0，tableCalls=hits+absent+unknown；拒绝 JFR DataLoss。区间外事件单独统计不用于通过。不能证明每个来源均索引，不能将计数覆盖的耗时用于性能收益。

通过后，正式性能对比必须使用不含此覆盖的原候选与 T057，单独冻结无 JFR 协议。T075/T077 失败不变，三版本/长期/Lane C 仍未验收。
