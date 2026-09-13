# 原生 / 新单页 / legacy 实机验收清单（部分执行）

此文档是完整执行协议，不代表全部通过。真实入口冒烟见 [HOST-SMOKE.md](HOST-SMOKE.md)，160次原生/new重复同边界对照见 [HOST-AB.md](HOST-AB.md)，500图/1024²实际缩放、ForkJoin并行及固定倍率overflow的功能验证见 [HOST-DENSE.md](HOST-DENSE.md)。测试均使用独立 Proton prefix、Xvfb :97 和模型副本。legacy同入口A/B、其他几何/多页场景、overflow后的完整Undo恢复及保存重开仍待验收。

## 安全准备

- 仅操作用户指定模型的独立副本和独立测试安装/配置。不要覆盖源模型、共享工作目录、当前未保存会话或正式插件安装。
- 记录源模型与副本 SHA-256；每组测试从相同副本恢复，不把上一组的排版结果作为下一组输入。
- 记录 Cubism 精确版本/JAR SHA-256、Java 版本、系统/CPU、插件与框架构建身份、SDK v8 门禁日志。
- 运行包含当前修改的测试构建；不得误测正式安装中的旧 JAR。启动、许可和界面前置条件由测试环境提供，不绕过许可。

## 输入合同

每组记录：当前页宽高、这次原生 invocation 的输入 ID 顺序及原始宽高摘要/哈希、mesh 或完整图像模式、固定或自动比例、旋转、margin、parallel。

只核对这一页：没有其他页搜索、页面创建或副本外对象修改。若实现之间 margin/整数取整语义不同，标明差异，不能宣称逐像素等条件。

至少包含：

1. 稀疏固定1，全放入；固定.5和>1。
2. 密集固定1，有 overflow；全部 overflow。
3. 自动缩放，分别开/关旋转及 parallel。
4. 非零/负数/分数 mesh 原点，旧图集变换非单位；完整图像模式与 mesh 模式。
5. 多纹理页模型，只操作同一当前页并核对其余页不变。

## 执行分支与计时

- 同组轮换原生/new/legacy 顺序，至少3次冷调用、15次热调用；保留每次结果，不只报告最快值。
- 原生模式必须实际放行原生函数，不能把 new 出错后偶然 fallback 的调用当成 new 跑分。
- new 的规划日志必须是 `scope=current-page`；记录输入数、placements、overflow、scale、snapshotMs、planMs。
- 同次必须有写回 outcome 与入口最终处理结果；`status=APPLIED` 后仍应核实画布及最终模型状态。计划日志本身不证明生效。
- 不混淆阶段时间：snapshotMs 不包含进入服务前的原生投影，applyMs 不包含最终 UI 刷新。端到端时间须另外测量、使用一致的开始/结束边界。
- 从日志当前偏移/时间戳截取本轮事件，不清空用户原始日志；不得混入上次运行的成功记录。

## 正确性先于速度

每次保存：放入/overflow原生实例数量、最终绝对比例、旋转标记、矩形边界/间隙/无重叠检查、item与LayerRef变换读回、DATA_SCALE一致性。

固定比例不能偷偷缩小；legacy 已知固定失败后缩小及可变候选缺陷必须单独标注，不能将错误或质量不同的结果纳入等质量加速比。

另做：撤销→重做、取消/失败恢复、保存副本→关闭→重新打开，并核对其他页保持不变。setter 持续拒绝恢复的场景不能宣称事务完全恢复，应保留全部错误证据。

## 结果表

每次一行，建议字段：

```text
case,implementation,run,warmup,build,input_hash,page_width,page_height,model_image,requested_scale,rotation,margin,parallel,placed,overflow,final_scale,snapshot_ms,plan_ms,apply_ms,end_to_end_ms,geometry_ok,writeback_ok,entry_branch_verified,undo_redo_ok,save_reopen_ok
```

只有相同输入/设置、正确性通过且质量可比的行才计算 `native_time/new_time` 或 `legacy_time/new_time` 的冷/热 P50、P95。缺失测量填空，不填0；不同质量结果并列报告，不混算加速比。

## 接受条件

- 证明当前测试构建的生产入口实际生效，不依赖预注册假 planner 或只写 item 的假成功。
- 只处理当前页；所有几何、固定比例、可视写回、生命周期与副本持久化检查通过。
- 覆盖约定代表性模型，性能至少不劣于正确且等质量的 legacy 结果；并行慢例说明策略和默认值。
- 无实机数据则维持“未验收”，不把离线内核比值写成相对原生加速比。
