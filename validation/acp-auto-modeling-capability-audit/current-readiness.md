# 当前建模闭环与下一验收入口

更新：2026-10-26。此页汇总当前工作区的 Milestone A 证据，不追溯改写原审计59项状态，也不声明完整自动建模已交付。当前目录预期80工具/15资源/2模板/8提示词；目录数字不证明每项操作准入。机器可读门槛与本轮复核见[检查点](readiness-20261026.json)。

## 已有证据与剩余门槛

| 环节 | 当前证据 | 精确范围与剩余工作 |
| --- | --- | --- |
| 发现、身份、authoring | [Hiyori头部检查](live-host-20261026/hiyori-head-inspection.json)、[只读目录](live-host-20261026/hiyori-head-inventory.json) | 真实任务副本与三类完整存储快照已有证据。默认/共享/Morph等按现有writer准入；evaluated观察仍声明freshness=UNPROVEN，不能把存储形或渲染冒充其新鲜度证明。 |
| ArtMesh/Warp/Rotation写入 | [三类头部编辑](live-host-20261026/hiyori-head-edit.json) | exact5.3.03脚本客户端三类stored form写入已有证据；[Codex单网格写入](live-host-20261026/native-edit-codex.json)新增真实模型自行读取/生成修改/preview/write的合成ArtMesh案例。真实Agent的Warp/Rotation及头部编排仍未验收。 |
| 历史、故障与未知结果 | [响应丢失实机](live-host-20261026/head-response-loss.json) | 跨三类对象单步Undo/Redo、native故障回滚及最终恢复通过；一次HTTP接收后响应丢失由同operationId两次只读查询核对，无重放。未覆盖宿主崩溃、真实宿主取消、跨会话/重启恢复。 |
| 遮挡与外观 | [工具实现与验证历史](verification.md) | 已有drawOrder/蒙版/源属性/普通ArtMesh opacity接口；全部专项美术/版本/对象域不能从头部小幅位移案例推定通过。 |
| 原生渲染、pose恢复 | [28pose任务](live-host-20261026/hiyori-head-qa-run.json) | 修改前后56张原生图像及参数恢复有证据。检查是有限采样；未包含全部中间连续性、参数组合或专业美术验收。 |
| ACP结果到窗口 | [rawOutput窗口验收](acp-raw-output-20261026.json) | 脚本ACP-v1代理→生产客户端→真实Swing，全ARGB像素/来源标签/无base64通过；canonical content与rawOutput-only分别有证据。此脚本代理不含模型。 |
| 真实Agent模型消费 | [Pi三次失败](live-host-20261026/native-observe-runs.json)、[Claude预检](claude-observer-preflight-20261026.json) | Pi已遇429/401；用户已改选Codex 6.1 Sol并明确授权合成测试图/元数据外发。[Codex实机观察](live-host-20261026/native-observe-codex.json)已通过随机canary、资源读取、原生渲染、ACP图片哈希及实际回答核对；[受控写入](live-host-20261026/native-edit-codex.json)已完成一个合成ArtMesh案例；无同链路真实Swing验收。此前Claude提交拒绝保留为历史，不能冒认Codex结果。 |
| 保存与重开 | [Hiyori生产文件任务](live-host-20261026/hiyori-head-save-run.json) | production grant→SAVE_AS→OPEN、同JVM存储form/绑定/PNG、原件保护有证据；测试chooser/文档关闭辅助与Agent自主文件流程分别记录，跨JVM尚未验收。 |
| 任务恢复与可靠完成 | [实现边界](implementation-plan.md)、[harness矩阵](harness-reference.md) | ACP对话恢复、native turn完成、MCP操作提交和宿主模型恢复分开。当前尚无完整持久任务状态机，不能声明恢复旧聊天即可续写旧计划。真实Agent观察和一个受控ArtMesh写入案例已通过；多步几何编排及跨会话恢复仍需专门设计与验证。 |

## 继续执行的具体入口

标准 `mcp:5303@native-observe` 已由Codex 6.1 Sol完成合成色块fixture的只读观察。协议扩展字段丢弃后正常调用，内部code-mode host保留，模型shell等工具关闭。新增 `mcp:5303@native-edit` 已完成一个ArtMesh form的读取、自行生成单顶点修改、预览、一次写入、完整回读、目标pose像素变化、Undo/Redo及最终恢复。模型当前pose与目标form pose不同，模型正确说明其两张当前pose图没有变化，本地验证器另查目标pose的53个变化像素。尚需真实Agent的Warp/Rotation、多步建模、保存重开和Swing同一链路验收。此前脚本Hiyori/窗口结果仍不可拼接成完整验收。

用户已明确授权 Codex 6.1 Sol 消费固定合成模型副本中一个网格的完整关键形坐标、绑定轴和必要身份，并进行一次小幅单顶点受控写入、回读、渲染、Undo/Redo及最终恢复。不含Hiyori、用户素材或凭据，不保存文件、不改原件。使用独立 `mcp:5303@native-edit` 任务；此前Claude提交拒绝只保留为历史，不再作为当前等待条件。

仍停止重复请求已返回429/401的Pi服务。用户已指定实际可用的Codex 6.1 Sol，随机验证码/颜色图测试通过；合成fixture的宿主渲染及真实回答已独立核对。已有资源/图片和单网格写入证据仍不代表完整ACP自动建模验收。

## 本轮复核

通过标准status重新确认最近头部响应丢失及ACP rawOutput窗口任务均succeeded、正常退出且cleanup=safe；没有再次启动宿主。重算Hiyori保存/重开与pose QA索引中的17份证据hash，全部一致；最近两轮共9份当前源码hash一致。源码核对确认keyforms结果以MCP session ID和operationId组合索引，snapshot同样按会话读取；NOT_FOUND明确表示旧会话结果不可证明，不能据此断言宿主未改动。完整expected state、文档/generation与当前实际形状仍需重新发现。

本轮受控写入任务 succeeded/PASS、normalExit=true、cleanup=safe，fixture/golden/identity保护均通过。189项客户端/队列离线测试与14项审计采集测试通过；详见[第68节](verification.md#68-codex-受控单顶点写入)。

Milestone A保持未通过；B/C尚按原实施依赖和专项范围待交付。本页不将尚缺的任务状态机、视觉美术算法或P1/P2工作删出目标。
