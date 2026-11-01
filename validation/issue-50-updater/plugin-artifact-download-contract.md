# 插件下载加速客户端契约

本次只接客户端，两个服务端路径由另一边实现和部署。最终契约见 [#67](https://github.com/turboism/Turboism/issues/67)，服务端实现见独立 PR #66；代码完成不代表手动激活已经完成。

1. 签名目录继续来自 `https://plugin.turboism.dev/api/v2/catalog.json` 及其独立签名。目录中的 GitHub Release URL 不变。
2. 客户端验证目录后，先请求 `GET https://api.turboism.dev/v1/plugins/artifacts/{sha256}/{filename}`。SHA-256 和含 `.jar` 扩展名的文件名直接来自已通过 Ed25519 验证的 `release.artifact.sha256` / `fileName`，不从 URL 或未签名数据推导。
3. 该入口预期返回 302，Location 必须是同一 SHA-256、同一文件名的 `https://dl.turboism.dev/plugins/{sha256}/{filename}`。不接受其他主机、路径、查询参数或二次 CDN 跳转。
4. API/CDN 尚未部署、镜像缺失、限流、5xx 服务故障、TLS 失败、网络超时/中断、下载截断或大小/哈希校验失败时，丢弃已写入的文件并自动回退到签名目录原有的 GitHub Release URL，继续使用原 GitHub CDN 跳转白名单。加速尝试最多占用 15 秒，并给原始下载保留总预算；整个下载仍受总时限约束。
5. 两条链路都必须通过目录规定的实际大小、SHA-256、descriptor hash、插件 ID/版本/权限和现有安装回滚检查。回退后从零下载并完整重验，不能拼接不同来源的文件；GitHub 文件校验失败仍拒绝安装。取消、磁盘写入/清理失败和不符合约定的跳转不会触发回退。

服务端约定仅发布签名目录内已发布、未撤回且字节校验通过的 JAR。CDN 文件接口支持 GET、HEAD、Range、If-Range、If-None-Match、ETag；完整下载为 200，分段下载为 206，缓存 60 秒。HEAD、ETag 或 URL 中的哈希不能代替完整字节校验。本次客户端仍使用完整 GET，未新增断点续传功能。

插件市场安装、后台插件自动更新、框架与插件统一更新窗口共用 `PluginCatalogClient.downloadArtifact`，因此使用相同的加速、回退和校验逻辑。无需修改或重新签署目录来启用加速。
