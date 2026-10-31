# 网站/API：为框架自更新提供增量资源发布与下载接口（关联 #50）

关联：#50（In-Cubism incremental updater）

## 用户目标

用户在 Cubism 内点击“更新”，后台下载发生变化的框架文件，继续建模；下次启动使用新框架。如果勾选更新插件，同时下载所选插件的兼容版本。

正常流程不打开浏览器、不运行安装向导、不强制关闭 Cubism。

这需要网站后端/分发服务提供资源，但不需要重做下载网页、建立用户登录系统，或提供远程“安装/重启 Cubism”接口。

## 当前缺口

当前发布链路仍以完整 EXE/ZIP 为公开下载契约。仓库已有离线增量清单生成、签名验证、准入协议和客户端下载实现，但 `distribution/worker.mjs` 尚未接入增量发布/资源接口。

不能仅在下载页面增加一个按钮：必须先发布签名清单及对应的不可变文件载荷，再在版本查询结果里声明该版本支持内部增量更新。

## 网站/API 需要完成的操作

### 1. 扩展现有版本查询接口，不另建一套检查更新协议

- [ ] 保持现有 `GET https://api.turboism.dev/v1/releases/{stable|beta|nightly}.json`。
- [ ] 对已经完成增量资源发布和验证的版本，增加可选的 `release.incremental`。
- [ ] 旧版或不具备增量资源的版本不返回此字段，继续支持完整安装包下载。
- [ ] 保留版本、通道、buildNumber、sourceRevision、发布时间及现有更新说明/多语言说明。
- [ ] `release.incremental` 必须与外层 release 的版本、通道、buildNumber、sourceRevision 完全一致。

采用当前客户端已有的结构，不再发明新格式：

```text
release.incremental = {
  source: {
    origin: "https://api.turboism.dev",
    redirects: "reject"
  },
  descriptor: {
    format: "turboism.incremental-candidate",
    schemaVersion: 1,
    repository: "turboism/Turboism",
    identity: { version, channel, buildNumber, sourceRevision },
    manifest: { bytes, sha256 },
    envelope: { bytes, sha256 },
    objectsSha256
  }
}
```

上面是结构说明，不是可直接发布的 JSON。`descriptor` 使用发布工具生成的原始值。签名公钥、任意下载域名、安装路径不从这个接口下发。

### 2. 提供签名清单与文件载荷的公开读取接口

建议沿用同一官方域名，确定一个固定前缀。例如：

```text
GET /v1/framework/documents/{sha256}
GET /v1/framework/objects/{sha256}/payload
```

`/v1/framework/` 是待确认的建议路径，不代表已经部署。最终前缀须同步到客户端内置配置。相对路径的 `documents/...`、`objects/.../payload` 已由客户端实现约定。

- [ ] `documents/{sha256}` 返回原始 manifest 或独立签名 envelope 的精确字节。
- [ ] `objects/{sha256}/payload` 返回清单引用的单个文件内容。
- [ ] 同一 SHA256 的资源不可覆盖为不同内容；可以直接使用现有 R2/CDN 基础设施存储和分发。
- [ ] 对外从 `api.turboism.dev` 返回实际内容，不用 HTTP 重定向把客户端送到 GitHub、代理站或临时下载域名。
- [ ] 返回正确 `Content-Length` 和稳定的强 ETag；资源字节不做动态压缩、转码或 HTML 错误页替换。
- [ ] 支持 `Range`、`If-Range`；部分响应正确返回 `206` 和 `Content-Range`。版本不匹配时可返回完整 `200`，由客户端重新下载。
- [ ] 缺失资源使用明确的 `404` 等错误状态，不把成功状态与错误正文混用。
- [ ] 仅暴露已经发布登记的资源，不把对象存储内部上传区直接变成公共目录。

客户端已经实现文件级比较和缓存：网站无需根据用户本地文件计算 diff，也不需要接收用户的配置、工程或插件目录。

### 3. 接入发布流水线：文件先就绪，再公布版本

这部分由发布 CI 与网站后端共同完成：

- [ ] 从同一次可追溯的正式构建生成完整框架文件清单，记录路径、长度、SHA256、版本、通道、构建号和源码 SHA。
- [ ] 在受保护的发布 CI 中用专用 Ed25519 密钥签署清单；网站保存并分发原始清单和签名即可，不需要持有私钥。
- [ ] 配置分发端校验使用的公钥；同步客户端内置的框架更新公钥。不要默认把插件目录密钥当成框架更新权限。
- [ ] 上传所有载荷对象、清单、签名，并核对远端实际长度/哈希后，才在版本接口中公布 `release.incremental`。
- [ ] 上传失败或发布冲突时，不切换到一个缺少文件的新版本；保留此前可用版本和已发布资源。
- [ ] 复用现有 registry 实现受控发布及通道切换，不需要另建面向用户的授权服务。
- [ ] 明确修改目前“GitHub Release 恰好八个资产”和 EXE/ZIP 白名单契约：确定新增文档如何交付、载荷是否只存 R2，并同步发布器、校验器、文档和测试。不能偷偷绕过旧校验，也不能无条件放开所有资产。

### 4. 插件沿用现有签名目录和下载资源

- [ ] 确保现有插件签名目录、签名及插件 JAR 下载源可稳定访问。
- [ ] 确保目录中的版本、兼容性要求、大小和哈希与实际插件文件一致。
- [ ] 如果新版框架需要新的插件兼容版本，先发布插件资源与目录，再公布框架更新。

不需要把所有插件塞进框架增量清单，不需要另建“更新全部插件”接口。用户勾选哪些插件、后台下载、暂存和下次启动应用，由客户端复用现有插件更新逻辑完成；未选插件和第三方文件不动。

### 5. 保留现有下载页面作为手动恢复入口

- [ ] 现有 `turboism.dev/download` 完整 EXE/ZIP 下载继续可用。
- [ ] 网页不参与正常内部更新，不要求用户在浏览器中确认每次下载。
- [ ] 不支持内部更新的旧客户端仍能通过完整安装包升级到具备自更新能力的版本。

## 客户端/安装包负责的工作，不属于网站 API

- 后台下载、进度、取消、续传及所选插件更新。
- 签名/哈希验证和本地文件级计划。
- 初次完整安装时建立可信安装收据；每次更新后推进收据。
- 用户自行关闭 Cubism 后应用更新；启动失败时恢复旧版本。
- 保留配置、工程、未选插件、第三方文件和受保护的 Cubism BAT 集成。

网站不负责也不应直接操作用户电脑上的安装、退出或重启。

## 验收清单

- [ ] 旧客户端和完整安装包下载不受影响。
- [ ] 可用的新版本返回正确的 `release.incremental`；不完整发布不返回。
- [ ] 清单、签名、所有引用对象可从官方域名直接读取，实际字节与清单一致。
- [ ] 已有相同文件无需重新传输；只修改一个文件的版本仅下载该文件及少量元数据。
- [ ] 下载中断后能通过 Range/ETag 继续；异常响应不会被误判成功。
- [ ] 勾选插件更新时，客户端可通过原有插件目录获取兼容的选中插件。
- [ ] 发布中断不破坏当前可用版本；能停止向新检查请求推荐问题版本。
- [ ] 与客户端联调一次完整流程：Cubism 内点击更新 → 后台下载 → 用户关闭 → 下次启动生效；再升级一次。

## 对接参考

- `distribution/INCREMENTAL.md`：已有离线准入协议和发现结构。
- `distribution/incremental.mjs`：已有离线校验/发布准入逻辑，尚未接到生产 Worker。
- `runtime/src/main/java/dev/turboism/update/UpdateIncrementalDiscovery.java`：客户端发现字段。
- `runtime/src/main/java/dev/turboism/update/FrameworkUpdateSource.java`：固定源及相对资源路径。
- `scripts/release/turboism_release/framework_update.py`：清单和签名生成工具。
- `validation/issue-50-updater/ACTIVATION.md`：客户端公钥、启用配置和首次安装衔接说明。

建议先交付最小接口与一个可联调版本，不把二进制差分、下载统计、复杂管理后台或用户账户体系列为本 issue 的前置条件。
