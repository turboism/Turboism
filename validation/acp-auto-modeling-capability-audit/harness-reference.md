# Harness 适配参考与实现进度

日期：2026-10-24。按用户建议，参考本机安装的 `@getpaseo/server@0.11.2` 的发布 JavaScript 实现。仅参考协议处理方式，不引入 Paseo 运行时依赖，也未派发代理或修改 Paseo。

## 可复核的参考来源

包内路径相对于 `@getpaseo/server`。SHA-256 固定本次读取的文件，不能据此假定其他版本具有相同行为。

| 包内路径 | SHA-256 |
| --- | --- |
| `dist/server/server/agent/providers/codex/tool-call-mapper.js` | `d7c1da5388c8da2710bb8232634cadaa5c356ee1d99eb4c5817997070abc3895` |
| `dist/server/server/agent/providers/codex-app-server-agent.js` | `44947b2052a84092cd5627f9ed3ebdcc3253d5cf5ab1d5e9736dbc4f5576bc7b` |
| `dist/server/server/agent/providers/pi/agent.js` | `8e285b630e263a6084af767ff6d3c7ef3036d4ffe301fad1cdb28871f2ce2d2a` |
| `dist/server/server/agent/providers/pi/tool-call-mapper.js` | `2f5972799b8538c969d9cf1e16c20806895a03d91e21cff86363a9b653f902c1` |
| `dist/server/server/agent/providers/pi/extensions/pi-mcp-adapter/index.js` | `982062ef5bc441958f095bc67cda17ac17698f34f8e83a5c4dacd21dfb4e0e7a` |
| `dist/server/server/agent/providers/acp-agent.js`（2026-10-26追加） | `990bc04929092ea0b831d9cdd41783debc4236b377f9296c2fcdd0e0c57b9b7a` |

## 已采用的行为边界

- Codex：`splitCodexMcpToolResultImages` 将 MCP image 从文本结果中分出；Turboism 保留 ACP typed content，由图片回调交给现有 UI，避免把 base64 作为聊天文本。
- Pi：区分 prompt ACK、`agent_end` 和 `agent_settled`；带 `willRetry` 的结束事件不直接完成 turn。取消 ACK 不冒充宿主建模任务取消。
- Pi：按模型 `input` 判断视觉能力。Turboism 在没有已验证 image input 的模型上拒绝图片 prompt，且不向该模型发出 prompt。
- 工具结果保持原生结构，普通文本仍可读；Claude base64 source 转为 ACP image，嵌入资源保持 resource 类型。
- Pi 的 Paseo MCP adapter 主要映射工具名称，并未证明 Turboism 的资源发现链路已完成。Turboism 另补只读 list/templates/read 模型工具，按 capability 注册，每次读取服务器实时目录，保留分页。

## 当前实现与证据范围

本次代码已加入 `turboism.render.state` / `turboism.render.capture` 并接入生产 MCP 装配。新增 native 工具图片转换、Pi 资源读取、权限字符串摘要和非视觉模型拒绝。测试记录见 [verification.md](verification.md)。

这些改动不代表 Milestone A 完成。三类 authoring 读取、关键形写入/CAS/事务及 pose grid 已有后续离线测试记录；几何诊断随后接入生产并通过离线回归。5.3.03色块副本的生产授权保存重开已见verification第51节；官方模型和真实视觉模型/各harness完整通路仍待验收。统一现状见 [实施清单第 8 节](implementation-plan.md#8-工作区实现状态与下一步)。原审计中的 B/P/A 状态保留为历史基线，不因局部实现自动升级。

## Harness 通信契约与验收清单

下表是后续实现/复核要求。Paseo 的行为用作协议参考，不将其测试或兼容范围移作 Turboism 的验收证据。native provider 与 ACP-v1 adapter 分别验收。

| 环节 | Paseo 参考位置 | Turboism 契约与必测情形 |
| --- | --- | --- |
| 会话与 turn 身份 | `codex-app-server-agent.js` 的 `interrupt()` | 分开保存客户端请求、thread、原生 turn 和建模 operation 身份。start ACK 早于 turn/started 时，取消仍绑定原 turn；迟到事件不能结束新 turn |
| 终态 | `pi/agent.js` 的 `handleTurnBoundaryEvent()` | prompt ACK 只确认接收。支持 settled 的 Pi 等待 agent_settled；旧版无 willRetry 的 agent_end 兼容须单独准入；测试重试、重复终态、事件交错和进程退出 |
| 取消 | `codex-app-server-agent.js` 的 `turn/interrupt` 请求；`pi/agent.js` 的 `interrupt()` | 取消请求返回与 turn 终态分开；Agent 取消与 MCP/宿主提交状态再分开。写入结果未知时先核对 operation，不自动重放；测试启动竞态、取消 ACK 后迟到结果和断线 |
| 权限往返 | `codex-app-server-agent.js` 的 commandExecution/fileChange requestApproval handlers | 审批绑定原 request 与目标，拒绝/过期/断线必须闭合；字符串、数组和对象摘要均可读并脱敏；Paseo shell/file 审批不能替代 Cubism 文件 grant |
| 工具图像 | `codex/tool-call-mapper.js` 的 `splitCodexMcpToolResultImages()`；`pi/tool-call-mapper.js` | text/image/resource/resource_link 保持类型；分别验证模型上下文与 Swing 展示；图片预算和 MIME 校验明确，错误结果不能变为成功；禁止将 base64 填入聊天文字 |
| 模型视觉能力 | `pi/agent.js` 的 `piModelSupportsImageInput()` | 按当前运行模型的 image input 判断，模型切换后重新判断；能力未知时明确提示；UI 能展示图片不能证明模型已消费图片 |
| MCP 发现与恢复 | `pi/extensions/pi-mcp-adapter/index.js`；Turboism Pi 扩展 | 工具、资源、模板分别发现；保存 cursor、检测重复页；恢复后刷新目录。Paseo 工具映射不证明资源桥接完整；prompts 为独立可选范围 |

以上函数定位以表中 SHA-256 对应的发布文件为准；行号会随版本变化。此次补查确认 Codex 取消等待原生 turn 身份，以及 Pi 对旧版 agent_end 的兼容分支；这些分支尚不能声称在 Turboism 全部复现。

建议 A8 的证据按以下矩阵填写，每格记录版本、命令或案例、结果与证据路径；未执行写“未验收”。

| 通路 | 进程/握手与恢复 | 文本与资源 | 模型消费图片 | UI 展示图片 | 审批往返 | 取消/终态竞态 |
| --- | --- | --- | --- | --- | --- | --- |
| Pi native | Linux Pi1.2.0隔离及标准5303任务sidecar握手通过；恢复未验收 | MCP桥接目录发现通过，真实模型调用前429/401；模型资源read未验收 | 未验收，三轮模型请求均失败 | 有回调/解码测试，实机未验收 | 有摘要测试，真实往返未验收 | 有离线测试，实机未验收 |
| Codex native | Linux0.159.2隔离initialize/session-new通过；恢复未验收 | 未验收 | 未验收 | 有回调/解码测试，实机未验收 | 未验收 | 未验收 |
| Claude native | Linux2.1.284隔离initialize/session-new通过；恢复未验收 | 未验收 | 未验收 | 有回调/解码测试，实机未验收 | 未验收 | 未验收 |
| 脚本 ACP-v1（测试包） | 5.3.03/Proton握手及owned session通过；恢复未验收 | 实际stdio MCP分页发现/rename/Undo通过 | 不含模型，未验收 | 原生256×256 PNG与真实窗口全ARGB/来源标签通过 | 不代替真实模型审批 | 未验收 |
| 各 ACP-v1 adapter | 逐 adapter 验收 | 逐 adapter 验收 | 逐 adapter 验收 | 逐 adapter 验收 | 逐 adapter 验收 | 逐 adapter 验收 |

集成 A4/A7 时，至少保留 harness turn 状态与宿主 operation 状态两个字段。turn 已结束或进程已退出，均不足以推断宿主未写入；重新连接后先校验文档与 generation，再核对实际提交结果。

## A8 可独立评审的实施任务

以下是待实施或补验证的任务，不改变上表的未验收状态。先以协议事件回放验证边界，再使用对应真实 harness 验证；每种 native 与 ACP-v1 路线单独填写结果。

| 任务 | 主要核对位置 | 最小验收场景 | 完成证据 |
| --- | --- | --- | --- |
| H1 身份与事件归属 | NativeAgentProvider、NativePiProvider、AcpClient 及各 adapter | start ACK 后尚未收到原生 turn 身份即取消；旧 turn 迟到事件到达新 turn；重复终态 | 去敏事件序列关联 request/thread/turn；仅目标 turn 完成一次 |
| H2 终态与重试 | Pi 边界事件处理、各 provider 完成回调 | agent_end(willRetry=true) 后继续输出；agent_settled 后完成；进程在完成前退出 | 事件回放断言与真实版本记录；不将 ACK 或重试边界作为成功 |
| H3 取消与宿主结果 | provider 取消入口、MCP operation 结果查询 | interrupt ACK 先到、工具结果后到；断线时宿主已经提交；恢复后重复 operationId | 分别记录 turn 终态和宿主提交状态；未知结果先查询，不重复写入 |
| H4 审批往返 | NativePiProvider 权限转换及 ACP 权限 UI | 允许、拒绝、超时、断线、重复回复；字符串/数组/对象参数摘要 | 审批 requestId 与目标一致，终结一次；摘要可读且没有凭据；文件授权仍走 SDK grant |
| H5 typed 内容与视觉准入 | NativeToolContent、NativeAgentProvider、Pi bridge、Swing 图片组件 | 混合 text/image/resource/link；错误工具结果；非视觉/未知能力模型；模型切换 | 模型收到结构化图片与 UI 成功展示分别留证；文字无 base64；尺寸、MIME、字节限制生效 |
| H6 MCP 目录与恢复 | pi-mcp-extension.mjs 及其他 harness MCP 配置 | 多页资源、重复 cursor、恢复后目录变化、资源读取失败 | 工具/资源/模板分别发现；失败保持错误语义；不使用旧目录冒充当前准入 |

H1/H2 是 H3 的事件归属前提；H5 依赖 A6 提供带 document/model/pose/revision 的真实图像。H6 可独立完成只读验证。H3 的宿主提交与 H5 的模型视觉消费均不能仅靠模拟 transport 测试验收。

每个案例至少记录：harness 名称与精确版本、native/ACP-v1 路线、模型及 vision 能力、输入事件/动作、预期结果、实际结果、去敏日志或图像路径、人工步骤和限制。只记录所需协议字段，不保存 token、原始会话头或描述符。

本轮文档复核重新读取本机包版本和以上五个文件的 SHA-256，均与表中记录一致。仅核对参考来源与符号定位，没有启动 harness 或 Cubism，未执行上述集成案例。


2026-10-25 追加：工具图片来源标签已沿 AcpClient→AcpAttachment→现有 UI 附件/回放路径传递；native 三种 provider 和 ACP-v1 假事件均验证 capture/pose 索引映射与 PNG/Swing 预览。Pi bridge 同时修正旧固定协议头，按照 initialize 的受支持版本发送后续 HTTP。此处为 Turboism 自身实现，不是 Paseo 新增参考或依赖升级；真实外部 CLI/模型视觉/窗口与宿主验收仍缺。详情见 verification.md 的本轮记录。


2026-10-26追加：标准acp:5303任务验证脚本ACP-v1的原生PNG到真实Swing窗口，全ARGB像素/工具稳定键/capture/文档/模型/revision/尺寸一致，原件不变、正常退出和安全清理通过；[窗口](live-host-20261026/acp-agent-window.png)与[宿主报告](live-host-20261026/acp-native-render-verified.properties)已归档。另运行三种本机真实CLI的隔离新会话握手，3项通过无跳过，见[第53节证据](live-host-20261026/native-real-cli-smoke.json)。两种证据不能拼成真实模型看图通过；native视觉消费、恢复/权限/取消与实际ACP-v1 adapter专项仍未验收。Paseo参考版本与hash不变。


2026-10-26追加：Codex native按本次turn/start响应绑定权威turn.id，加入旧/无身份事件过滤、提前取消/重复取消、迟到审批回复拒绝和ACK前缓存预算。三项原实现回归失败后修复，详情见[第60节](verification.md#60-codex-原生-turn-身份取消与审批隔离)与[离线证据](codex-turn-isolation-20261026.json)。Paseo参考provider SHA256仍为44947b2052a84092cd5627f9ed3ebdcc3253d5cf5ab1d5e9736dbc4f5576bc7b。未移植旧形态无ID ACK兼容；Codex真实模型、恢复、权限/取消及窗口仍未验收，不改变上表实机状态。


2026-10-26追加：Pi/Claude的本地prompt future现绑定审批和异步回调；旧handled ACK/取消失败不结束下一轮，空闲/重复cancel不发送命令，当前不确定请求要求重连核对。三项原实现失败后修复；ACP全量239项与真实三种CLI隔离新会话握手见[第61节](verification.md#61-piclaude-本轮回调隔离与三种真实-cli-握手)、[摘要](native-callback-isolation-20261026.json)。无ID旧事件首次在新prompt期间到达仍不能归属；审批UI/槽位主动回收、真实模型视觉/审批/取消/恢复均未验收。Paseo参考不变，不将本地future身份称为原生turn身份。


2026-10-26追加：本地审批生命周期现随cancel/terminal/resume/close退役，native槽位按原prompt future回收，生产Swing观察signal关闭精确dialog。Xvfb实际窗口和队列/槽位/迟到ALLOW一次性结果见[第62节](verification.md#62-审批生命周期native槽位与真实swing窗口回收)、[摘要](permission-lifetime-20261026.json)。补齐前两节本地回收缺口，不改写其历史证据；真实模型审批、无ID旧事件、宿主取消/回滚和恢复仍未验收。Paseo参考未变，未引入Paseo运行依赖。


2026-10-26追加：测试专用 Claude2.1.284 标准观察入口已构建，固定运行时、限制内置工具，并在宿主资源/渲染前要求随机图片 canary 与工具集合验证。受限真实 CLI 握手通过但未发 prompt；首轮启动参数失败已保留并修复。修复后任务已 prepare，提交因向 Claude 外部服务发送私有测试图像/元数据的授权范围被自动审批拒绝，尚未入队，不重放或绕过。见[第63节](verification.md#63-claude-标准观察入口与提交授权边界)与[摘要](claude-observer-preflight-20261026.json)。真实视觉消费和 ACP 建模仍未完成；Paseo参考及生产API范围未因此改变。


2026-10-26追加：AcpClient原先忽略rawOutput-only工具结果，三例失败已复现并修复。空/缺失content时复用typed转换，非空content优先，不混入raw图片或改变Agent工具状态；4新回归、Xvfb ACP252项（249通过/3 opt-in跳过）、代理坏图拒绝测试通过。标准acp:5303任务验证RAW_OUTPUT_ONLY原生PNG到真实Swing全ARGB/来源标签/无base64，队列正常退出、原件/身份/golden/清理通过。见[第65节](verification.md#65-acp-rawoutput-结果回退与真实窗口验证)、[摘要](acp-raw-output-20261026.json)。仅补结果接收/显示，不冒称外部模型视觉、持久任务恢复或ACP建模编排完成。Paseo0.11.2新增acp-agent.js参考hash已登记，未引入运行时依赖。
