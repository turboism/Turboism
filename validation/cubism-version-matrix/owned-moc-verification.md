# Owned MOC 生产 SDK 实机验证（部分能力）

这是全能力表中的一项证据，不是最终全版本支持表。所有未列版本仍待验证。

| Editor | 实测 Core | 生产 SDK owned-MOC | supervisor | parameterRepeat / latest MOC |
|---|---|---|---|---|
| 5.2.00 | 5.0.256 | PASS | PASS | 不提供 / V5_0 |
| 5.2.01 | 5.0.256 | PASS | PASS | 不提供 / V5_0 |
| 5.2.02 | 5.0.256 | PASS | PASS | 不提供 / V5_0 |
| 5.3.00 | 6.0.257 | PASS | PASS | 提供 / V5_3 |
| 5.3.01 | 6.0.257 | PASS | PASS | 提供 / V5_3 |
| 5.3.04 | 6.0.513 | PASS | PASS | 提供 / V5_3 |
| 5.1.03 | 5.0.0（此前身份预检实测） | FAIL：当前生产 Core metadata 准入不可用；原生 owned-MOC 能力待适配复测 | FAIL，cleanup safe | 待验证 |

六个 PASS 均验证读取、update、独立实例、不可变副本、显式释放、父子级联关闭、关闭后拒绝操作；contained-supervisor 确认身份、夹具不变、正常退出和 cleanup safe。固定官方 Haru MOC V3_0，实际均读到42参数、19部件、84 drawable、97 deformer、0 Glue。空 Glue 只覆盖 getter，未证明非空 Glue 语义；均未证明 Editor acquisition、完整 MCP/保存或 Mesa 优化。

| Editor | 原生结果 | supervisor 摘录 | job |
|---|---|---|---|
| 5.2.00 | [properties](mcp-evidence/5.2.00-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.2.00-allversions-owned-r1/lifecycle-result.json) | `f5578093-0dad-4cb1-95f0-a3136949fff9` |
| 5.2.01 | [properties](mcp-evidence/5.2.01-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.2.01-allversions-owned-r1/lifecycle-result.json) | `1a47c5b9-f6b1-4bdc-9581-ea43c9728eba` |
| 5.2.02 | [properties](mcp-evidence/5.2.02-allversions-owned-r3/core-owned-moc-result.properties) | [终态](mcp-evidence/5.2.02-allversions-owned-r3/lifecycle-result.json) | `85d9c5fd-9b69-4ce4-9c6b-affe1ff5db7a` |
| 5.3.00 | [properties](mcp-evidence/5.3.00-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.3.00-allversions-owned-r1/lifecycle-result.json) | `6ae62c47-e7fb-428f-9276-d4c2e8a2c69e` |
| 5.3.01 | [properties](mcp-evidence/5.3.01-allversions-owned-r1/core-owned-moc-result.properties) | [终态](mcp-evidence/5.3.01-allversions-owned-r1/lifecycle-result.json) | `979febd0-c89f-4aa9-bd5c-2641418caa67` |
| 5.3.04 | [properties](mcp-evidence/5.3.04-allversions-owned-r5/core-owned-moc-result.properties) | [终态](mcp-evidence/5.3.04-allversions-owned-r5/lifecycle-result.json) | `6b80a640-8264-44bf-91f0-d10590071d50` |
| 5.1.03 | [失败结果](mcp-evidence/5.1.03-allversions-owned-r3/core-owned-moc-result.properties) | [终态](mcp-evidence/5.1.03-allversions-owned-r3/lifecycle-result.json) | `ecc64fcb-b11f-4d13-b155-42d352800caf` |

prepared digest 和逐项结果见 [任务清单](campaign-jobs.json)。原始 properties 按原样留存，其中旧探针固定文本“Empty Glue/Deformer lists”不准确：此次 Deformer 为97，只有 Glue 为空；应以计数为准。

[全20版 selector 差异审计](core-selector-delta-audit.json)仅是静态比对：5.0/5.1 缺18个新版结构 selectors，62个存在。不可据此把旧版整体标为不支持，或将缺失结构伪造为空。5.3 与5.2 的一个 API 差异由现有独立5.3 profile处理，比对缺失也不代表5.3能力不可用。

5.1.03已增加精确legacy准入与基础family适配，131项离线回归/devCheck/Core门禁通过；[回归证据](legacy-core-adaptation-regression.json)。新的owned-r4实机已提交，终态前保留待验证，不能将此前r3失败改写为通过。
