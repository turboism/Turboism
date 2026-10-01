# Owned MOC 生产 SDK 实机验证（部分能力）

这是全能力表中的一项证据，不是最终全版本支持表。所有未列版本仍待验证。

| Editor | 实测 Core | 生产 SDK owned-MOC | supervisor | parameterRepeat / latest MOC |
|---|---|---|---|---|
| 5.0.02 | 5.0.0 | PASS（Canvas／参数／部件；扩展三族明确拒绝） | PASS | 不提供 / V5_0 |
| 5.0.03 | 5.0.0 | PASS（Canvas／参数／部件；扩展三族明确拒绝） | PASS | 不提供 / V5_0 |
| 5.0.04 | 5.0.0 | PASS（Canvas／参数／部件；扩展三族明确拒绝） | PASS | 不提供 / V5_0 |
| 5.0.05 | 5.0.0 | PASS（Canvas／参数／部件；扩展三族明确拒绝） | PASS | 不提供 / V5_0 |
| 5.0.06 | 5.0.0 | PASS（Canvas／参数／部件；扩展三族明确拒绝） | PASS | 不提供 / V5_0 |
| 5.1.00 | 5.0.0 | PASS（Canvas／参数／部件；扩展三族明确拒绝） | PASS | 不提供 / V5_0 |
| 5.1.01 | 5.0.0 | PASS（Canvas／参数／部件；扩展三族明确拒绝） | PASS | 不提供 / V5_0 |
| 5.1.02 | 5.0.0 | PASS（Canvas／参数／部件；扩展三族明确拒绝） | PASS | 不提供 / V5_0 |
| 5.1.03 | 5.0.0 | PASS（Canvas／参数／部件；扩展三族明确拒绝） | PASS | 不提供 / V5_0 |
| 5.2.00 | 5.0.256 | PASS | PASS | 不提供 / V5_0 |
| 5.2.01 | 5.0.256 | PASS | PASS | 不提供 / V5_0 |
| 5.2.02 | 5.0.256 | PASS | PASS | 不提供 / V5_0 |
| 5.2.03 | 5.0.256 | PASS | PASS | 不提供 / V5_0 |
| 5.3.00 | 6.0.257 | PASS | PASS | 提供 / V5_3 |
| 5.3.01 | 6.0.257 | PASS | PASS | 提供 / V5_3 |
| 5.3.02 | 6.0.257 | PASS | PASS | 提供 / V5_3 |
| 5.3.03 | 6.0.257 | PASS | PASS | 提供 / V5_3 |
| 5.3.04 | 6.0.513 | PASS | PASS | 提供 / V5_3 |

9版完整结构getter与9版基础family通过，每版均核对contained-supervisor身份、夹具不变、正常退出及cleanup safe。完整profile读取42参数、19部件、84 drawable、97 deformer、0 Glue；基础profile读取42参数、19部件，完整Drawable/Glue/Deformer明确拒绝。空Glue仅覆盖getter，未证明非空语义、Editor bridge、完整MCP保存或Mesa优化。

| Editor | 原生结果 | supervisor 摘录 | job |
|---|---|---|---|
| 5.0.02 | [properties](mcp-evidence/5.0.02-allversions-owned-r2/core-owned-moc-result.properties) | [终态](mcp-evidence/5.0.02-allversions-owned-r2/lifecycle-result.json) | `42aab530-9b5b-4460-91f6-156652d52506` |
| 5.0.03 | [properties](mcp-evidence/5.0.03-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.0.03-allversions-owned-r1/lifecycle-result.json) | `c4b44dc5-90bb-45d0-87e7-dc408490153b` |
| 5.0.04 | [properties](mcp-evidence/5.0.04-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.0.04-allversions-owned-r1/lifecycle-result.json) | `f254ebd3-54fa-4f39-852e-8c7c8d7bc7ad` |
| 5.0.05 | [properties](mcp-evidence/5.0.05-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.0.05-allversions-owned-r1/lifecycle-result.json) | `d4e456c7-0150-4de6-b3fd-1e4adba83910` |
| 5.0.06 | [properties](mcp-evidence/5.0.06-allversions-owned-r2/core-owned-moc-result.properties) | [终态](mcp-evidence/5.0.06-allversions-owned-r2/lifecycle-result.json) | `3b47b96e-2c23-4906-b694-0c3ea7a08a1a` |
| 5.1.00 | [properties](mcp-evidence/5.1.00-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.1.00-allversions-owned-r1/lifecycle-result.json) | `d0a21c17-a245-433d-a6f1-e50c1a74a243` |
| 5.1.01 | [properties](mcp-evidence/5.1.01-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.1.01-allversions-owned-r1/lifecycle-result.json) | `975a4716-e33b-4fed-bf31-ec3cceb19948` |
| 5.1.02 | [properties](mcp-evidence/5.1.02-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.1.02-allversions-owned-r1/lifecycle-result.json) | `08ea7c21-6425-4e0a-966d-2a71a11fa7e5` |
| 5.1.03 | [properties](mcp-evidence/5.1.03-allversions-owned-r6/core-owned-moc-result.properties) | [终态](mcp-evidence/5.1.03-allversions-owned-r6/lifecycle-result.json) | `0165fbd2-3473-4407-b613-1ca42b69b72b` |
| 5.2.00 | [properties](mcp-evidence/5.2.00-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.2.00-allversions-owned-r1/lifecycle-result.json) | `f5578093-0dad-4cb1-95f0-a3136949fff9` |
| 5.2.01 | [properties](mcp-evidence/5.2.01-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.2.01-allversions-owned-r1/lifecycle-result.json) | `1a47c5b9-f6b1-4bdc-9581-ea43c9728eba` |
| 5.2.02 | [properties](mcp-evidence/5.2.02-allversions-owned-r3/core-owned-moc-result.properties) | [终态](mcp-evidence/5.2.02-allversions-owned-r3/lifecycle-result.json) | `85d9c5fd-9b69-4ce4-9c6b-affe1ff5db7a` |
| 5.2.03 | [properties](mcp-evidence/5.2.03-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.2.03-allversions-owned-r1/lifecycle-result.json) | `a7fd2516-6040-4140-a4ad-f4f97c089b68` |
| 5.3.00 | [properties](mcp-evidence/5.3.00-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.3.00-allversions-owned-r1/lifecycle-result.json) | `6ae62c47-e7fb-428f-9276-d4c2e8a2c69e` |
| 5.3.01 | [properties](mcp-evidence/5.3.01-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.3.01-allversions-owned-r1/lifecycle-result.json) | `979febd0-c89f-4aa9-bd5c-2641418caa67` |
| 5.3.02 | [properties](mcp-evidence/5.3.02-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.3.02-allversions-owned-r1/lifecycle-result.json) | `50562e8e-535e-4e12-8fdc-7ddd7a47f2dc` |
| 5.3.03 | [properties](mcp-evidence/5.3.03-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.3.03-allversions-owned-r1/lifecycle-result.json) | `8471a00f-254d-492e-b7f2-7ac537fbfe2a` |
| 5.3.04 | [properties](mcp-evidence/5.3.04-allversions-owned-r5/core-owned-moc-result.properties) | [终态](mcp-evidence/5.3.04-allversions-owned-r5/lifecycle-result.json) | `6b80a640-8264-44bf-91f0-d10590071d50` |

prepared digest 和逐项结果见 [任务清单](campaign-jobs.json)。原始 properties 按原样留存，其中旧探针固定文本“Empty Glue/Deformer lists”不准确：此次 Deformer 为97，只有 Glue 为空；应以计数为准。

[全20版 selector 差异审计](core-selector-delta-audit.json)仅是静态比对：5.0/5.1 缺18个新版结构 selectors，62个存在。不可据此把旧版整体标为不支持，或将缺失结构伪造为空。5.3 与5.2 的一个 API 差异由现有独立5.3 profile处理，比对缺失也不代表5.3能力不可用。

5.1.03已增加精确legacy准入与基础family适配，131项离线回归/devCheck/Core门禁通过；[回归证据](legacy-core-adaptation-regression.json)。131项为默认任务的合成回归，不含licensed真实产物启动测试。owned-r4已终态FAIL，尚未执行native MOC断言；新增专用启动回归真实复现拒绝，修复后将执行独立复测。

[启动回归证据](legacy-startup-regression.json)：record残留条件与生产HostSession join两条路径已分别复现并修复，235项宿主/Core合成回归和6项真实产物回归通过。r5 supervisor FAIL/cleanup safe，native未开始，详见[终态](mcp-evidence/5.1.03-allversions-owned-r5/lifecycle-result.json)与[启动stack](mcp-evidence/5.1.03-allversions-owned-r5/startup-failure.txt)；后续r6的实机验收见下段。

5.1.03 owned-r6已收取supervisor PASS：身份/夹具/正常退出true、cleanup safe，Core实测5.0.0，基础Canvas/42参数/19部件、update/独立实例/不可变副本/显式和父级联释放/关闭后拒绝均通过；complete-drawables/glues/deformers明确UOE，allStructuralReadMethods=PARTIAL_EXPECTED。目前共18版owned-MOC验收，9版完整结构getter与9版基础family；不证明完整geometry、Editor bridge或全能力。逐SDK方法覆盖与缺口见[覆盖映射](owned-moc-coverage.json)。

5.1.00–5.1.02各自实机supervisor PASS，三个最终门禁true、cleanup safe，基础读取与缺失族负向断言均通过。5.0.02首轮夹具加载中断，nativeAssertions=NOT_STARTED，其他5.0版仍在队列中；不能据此判定Core不支持。任务标识及摘要见campaign-jobs.json。

5.0.02 owned-r2复测supervisor PASS，三个最终门禁true、cleanup safe，Core5.0.0基础family和缺失族拒绝断言均通过。首轮加载中断证据仍保留。5.0.06 owned-r2复测也已supervisor PASS，基础family与负向断言通过；5.0.00/.01需要合适夹具，不能据加载失败记为不支持。
