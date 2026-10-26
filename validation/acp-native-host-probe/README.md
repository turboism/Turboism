# 真实原生 ACP 观察探针

标准任务 `mcp:5303@native-observe` 仅在 exact 5.3.03 的任务副本运行。
先执行 `scripts/preview/package-mcp-native-observe-validation.sh`，再通过
`host_validation.py prepare/submit/status` 使用既有 worker。不直接启动宿主。

辅助包包含本工作区生产 ACP/SDK 类及 test-only NativeObserve 入口。
队列固定辅助包、运行时清单、Java/Node 可执行文件和完整 Pi 1.2.0 release tree（默认路线）；
运行时改变则拒绝执行。5.3.03 限制是此新集成案例的测试范围，不是生产 API 版本准入变更。

真实 Pi 通过生产 AcpClient→NativeAgentTransport→NativePiProvider 通路启动。
禁用 Pi 内置工具、自动扩展、技能与提示词模板，仅显式加载生产 MCP 扩展。
沿用用户现有 CLI 的模型/认证，由 CLI 自行读取；不复制、输出或归档认证配置。
权限回调默认拒绝。标准输入 MCP 转发器只公开 render.state/capture、两个资源和资源目录，
拒绝参数/几何/文件/历史/pose 写操作；最多32请求、2张512×512图像，不重放失败请求。
令牌仅由任务转发器从任务私有 token 文件读取，不进入 Agent session 参数。

透明观察原生get_available_models的provider/id/input，保留支持image的当前模型，
否则通过生产session配置选择运行时声明可用的视觉模型；没有候选则在prompt前拒绝。
辅助包构建脚本第二参数可给逗号分隔的供应商排除清单，固定到runtime.json；
只能用于新任务，不在失败turn内自动切换或重放。Pi1.2.0的RPC set_model调用
session.setModel(model)，不设置persist，故不保存为用户全局默认模型。
运行时声明可用不证明账号未过期、配额充足或模型请求实际成功。

宿主原生 PNG、实际模型回答和会话保留在私有任务目录。
分别核对生产 PNG hash、ACP typed image 回调 hash、文档与历史未变；
模型文字描述还须与实际图片人工核对。`CAPTURE_DELIVERED_REVIEW_REQUIRED`
不能当作视觉质量或完整自动建模通过，`modelVisionAccepted` 默认 false。
这里的 Linux 生产客户端回调不等于 Cubism Swing 窗口显示已验收。

探针仅启动自己创建的 sidecar 进程组，超时只终止该组；
宿主的生命周期与清理仍归标准队列所有。关闭 harness 不声称取消宿主操作。
观察路线不验收写入或多步 rig 编排；受控写入使用下面独立的 native-edit 路线。

## Claude native 观察路线

`bash scripts/preview/package-mcp-native-observe-validation.sh claude` 构建测试专用
Claude 路线，之后仍使用同一个标准 prepare/submit/status 流程。runtime manifest v2
固定 Java、Claude 2.1.284 可执行文件和完整安装树；v1 保留 Pi 默认路线。
不复制认证配置；真实模型请求会由现有 CLI 配置的外部服务处理，使用前须明确授权
测试图像、活动文档元数据和工具结果的发送范围。

Claude 使用 restricted、strict MCP config、空 setting-sources、禁用 hooks/plugins/
slash commands/browser/session persistence，仅允许两个资源 builtin 与受控 MCP。
权限回调默认拒绝，单次 CLI 预算上限 2 USD。运行时 system/init 声明的工具集合
还须通过闭合检查；未知或额外执行工具拒绝，不为通过测试自动扩大名单。

此路线没有 reviewed runtime-declared vision metadata，故先发送随机图片探针：
512×384、六位随机数字与四色位置，要求模型不调用工具并返回精确 JSON。
只有回答全匹配、工具集合安全后，才签发私有 canary gate，开放宿主资源和渲染；
模型或工具状态改变则拒绝。gate 拒绝符号链接、超限及额外字段。
这只证明本次输入消费，不是艺术质量、宿主图像理解或所有模型视觉能力验收。
探针 PNG、答案和 expected 仅存任务私有目录，公共报告只留必要布尔值与 hash。

首轮任务在启动 Claude 前因空 argv 失败，已修正为 `--setting-sources=`；
完整受限参数的 initialize/new-session 握手通过，未发模型 prompt。
修复后的第二轮已 prepare，但提交被自动审批拒绝，尚未进入队列；
等待明确授权向 Claude 外部服务发送测试内容。详见审计 verification 第63节。

### Codex 6.1 Sol 受控观察

用户明确选择 Codex 后，可运行
`bash scripts/preview/package-mcp-native-observe-validation.sh codex`，然后使用同一
`mcp:5303@native-observe` 标准 prepare/submit/status 流程。schemaVersion 3
固定本机 Codex 0.159.2、`gpt-6.1-sol`、Node/Java 和整个 CLI 安装树哈希。
不复制用户配置或凭据，也不回退其他模型。

启动前使用相同 argv/cwd 运行无 prompt 的 `config/read` 预检：只读 sandbox、
on-request 审批、关闭 shell/apply_patch/view_image/web/模型 code-mode/插件/子代理/
记忆/宿主技能发现等功能，清空继承的 MCP；生产 ACP 会话再注入指定只读代理。
配置字段不匹配则拒绝。此检查证明配置生效，不是完整模型工具目录的独立证明。
权限监听器保持默认拒绝，宿主 MCP 代理不提供任何写操作。

模型必须在运行时 model/list 中明确声明 image 输入，并先正确回答随机验证码和
色块 canary。通过后签发与 harness 和精确 model 绑定的私有 gate，才可读宿主
资源/调用 renderer。模型实际理解宿主渲染仍需人工核对，不能仅凭 canary 或
ACP 回调断言通过。此 sidecar 没有 Swing 窗口，不构成真实模型到 Swing 的整体验收。

协议与配置依据：[官方 app-server 文档](https://developers.openai.com/codex/app-server)、
[官方配置参考](https://developers.openai.com/codex/config-reference)；具体运行以
0.159.2 生成的 schema、实际 model/list/config/read 和实机结果为准。

Codex 内部 `code_mode_host` 保留启用：它承载 MCP 工具执行基础设施；关闭它会导致
目录发现正常但调用返回“code-mode host is disabled”。模型 code_mode、shell 等
工具仍关闭。不能把关闭内部执行组件当成工具隔离成功，实际 MCP 调用必须单独通过。


### Codex 受控关键形写入

用户明确授权合成模型坐标外发及受控写入后，执行
`bash scripts/preview/package-mcp-native-observe-validation.sh codex keyform-edit`，
再使用 `mcp:5303@native-edit` 的标准 prepare/submit/status 流程。
schemaVersion 4 固定 `workflow: keyform-edit`；队列拒绝将观察与编辑清单混用。
任务的 `readOnlyInspection=true` 仅关闭 Java 基础探针的额外变更，
`nativeEditing=true` 明确启用 Python 受控编辑验证；此任务不是只读测试。

本地验证器选定合成副本的一个独立非默认 ArtMesh form，只将身份和完整绑定轴
元组作为范围，不向模型提供预制坐标修改。模型须读取同一快照的完整分页，自行
构造完整 positions，成功 preview 后以完全相同的请求 write 一次，再重新读取。
代理只接受一个顶点 x 正向移动，幅度不超过 min(4, x跨度/32)，全部 y 和其他
顶点保持；拒绝默认/共享/域外 form、未知业务参数、额外对象及文件/历史/参数写入。
MCP 协议无关扩展字段仍丢弃，不传到宿主。

独立验证器检查目标及样本 Warp/Rotation 的完整存储 rows，扫描目标 pose 的
原生渲染前后差异，执行 guarded Undo/Redo，并最终 Undo、核对参数和身份恢复。
模型最多消费两张512×512当前 pose 图；目标 form pose 图由本地验证器另行检查，
不能混称模型已观察该目标 pose。此次不保存文件或修改原件。

写入超时不重放；同 operationId 仅查询结果。有确切收据且历史身份吻合才允许
恢复；已经尝试写入却没有收据则记录 OUTCOME_UNKNOWN，不盲目 Undo。
关闭 sidecar 不能证明宿主取消。报告即使恢复检查失败也保留失败证据。
真实任务结果见[审计验证记录](../acp-auto-modeling-capability-audit/verification.md#68-codex-受控单顶点写入)。
