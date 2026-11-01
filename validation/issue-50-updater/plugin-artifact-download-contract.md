# 插件下载加速客户端契约

本次只接客户端，两个服务端路径由另一边实现和部署。路径约定不代表服务已经上线。

1. 签名目录继续来自 `https://plugin.turboism.dev/api/v2/catalog.json` 及其独立签名。目录中的 GitHub Release URL 不变。
2. 客户端验证目录后，先请求 `GET https://api.turboism.dev/v1/plugins/artifacts/{sha256}/{filename}`。
3. 该入口预期返回 302，Location 必须是同一 SHA-256、同一文件名的 `https://dl.turboism.dev/plugins/{sha256}/{filename}`。不接受其他主机、路径、查询参数或二次 CDN 跳转。
4. API/CDN 尚未部署、镜像缺失、限流、服务故障或网络超时/中断时，自动回退到签名目录原有的 GitHub Release URL，继续使用原 GitHub CDN 跳转白名单。加速尝试最多占用 15 秒，并给原始下载保留总预算；整个下载仍受总时限约束。
5. 两条链路都必须通过目录规定的大小、SHA-256、归档和插件信息校验。取消、校验失败、磁盘写入失败和不符合约定的跳转不会触发回退。部分下载因网络故障回退时，从零开始，不能拼接不同来源的文件。

服务端约定仅发布签名目录内已发布、未撤回且字节校验通过的 JAR。CDN 文件接口支持 GET、HEAD、Range、ETag；完整下载为 200，分段下载为 206。本次客户端仍使用完整 GET，未新增断点续传功能。

插件市场安装、后台插件自动更新、框架与插件统一更新窗口共用 `PluginCatalogClient.downloadArtifact`，因此使用相同的加速、回退和校验逻辑。无需修改或重新签署目录来启用加速。
