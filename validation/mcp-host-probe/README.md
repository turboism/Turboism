# mcp-host-probe

MCP 接口宿主侧探针。

`./build.sh` 仅离线编译。实机必须使用 `scripts/preview/host_validation.py prepare/submit/status` 队列，由 Runner 创建独立素材副本和任务目录。

验证包包含测试专用 `McpGeometryRollbackProbe`，生产 MCP 不包含它。客户端通过任务内的有界 JSON 请求提供实际发现的三类对象/source/form 身份；探针在 EDT 上调用 typed ordinary setter，分别在确认完成第 1、2、3 个写入后主动抛异常。每次必须收到 SDK `ROLLED_BACK`，完整 authoring record 精确恢复，两套历史观察器各自完整状态恢复，再发布脱敏哈希与收据。

事务宿主使用原生模型绑定 generation，公开历史服务使用 Editor UI lifecycle generation；revision 和 binding ID 也分别属于各自观察器。不得跨域直接比较这些计数和 opaque ID。文档/模型身份独立校验，两个观察器各自前后完整快照相等，跨观察器核对位置与条目数。

回滚探针不保存文件，不提供任意路径、代码或故障控制的生产 API。超时或恢复未证实时，客户端停止后续 MCP 写入与不确定清理；不重放请求。它证明 SDK 事务和三类真实 setter 的异常恢复，不等于 MCP 断线、宿主崩溃或取消已经通过。

几何持久化验证复用 `McpTexturePersistenceProbe` 已有的 exact-hash-gated 原生文件流程，另用 `geometry-persistence` 目录和独立 request/result。在真实 MCP 三类写入和 Undo/Redo 验证后，保存当前修改状态到新的任务内 CMO3，关闭并重新反序列化该副本，精确核对三类完整 SDK authoring records 与全部模型绑定，再重开原任务副本，由外部 MCP 核对原始 rows。不会复用重开前的历史收据，不新增生产路径或文件句柄入口。这不证明生产 `documents.save_as` 或尚未开放的 `documents.open` 已通过实机验收。
