# 正式签名密钥配置验收（2026-11-01）

用户批准创建仅限 main 的签名环境、生成并保存正式私钥及配置对应公钥，同时要求保持自更新关闭。GitHub 侧配置和服务器公钥生产部署均已完成，发布授权与客户端自更新仍关闭。

## 密钥与 GitHub 配置

| 项目 | 已核对结果 |
| --- | --- |
| Key ID | `framework-official-v1` |
| 算法与格式 | Ed25519；公钥为 44 字节 X.509 SPKI；私钥为 base64 编码的 PKCS#8 PEM |
| 公钥 SPKI SHA-256 | `0d455181367e79798bd4559dd757f8e788e72ff5bffae6fbff98ae4c1862ae18` |
| 私钥保存位置 | 产品仓库 `framework-release-signing` 环境的 `FRAMEWORK_UPDATE_SIGNING_KEY_B64` Secret |
| Secret 更新时间（GitHub） | `2026-11-01T04:05:49Z` |
| 签名环境分支规则 | custom branch policy；仅 `name=main, type=branch` 一条规则 |
| 环境变量 | `FRAMEWORK_UPDATE_SIGNING_KEY_ID=framework-official-v1` |
| 仓库公钥变量 | `FRAMEWORK_UPDATE_TRUSTED_KEYS`；schemaVersion 1、单个上述公钥、revoked 为空 |
| 仓库签名/客户端开关 | `FRAMEWORK_UPDATE_SIGNING_ENABLED=false` |

私钥在本机内存中生成，只经 GitHub CLI 的标准输入保存到环境 Secret，没有写入本机私钥文件、命令参数、环境变量或日志。本机保留的三个 JSON 文件仅包含公钥、指纹及操作状态。

使用带独立配置用途前缀和随机 nonce 的挑战，完成本地签名、验签及篡改拒绝检查；未签署发布清单或构造 CI 身份。Secret 写入成功后，重新读取了公开变量、环境规则和 Secret 名称/更新时间。GitHub 不提供 Secret 明文读回，因此本记录不声称验证了远端私钥明文或已经运行正式签名 CI。

## 服务器配置

公开配置提交为 [`a0c84429baba6ee197aadfb3bdac80d9fe0e261a`](https://github.com/turboism/Turboism/commit/a0c84429baba6ee197aadfb3bdac80d9fe0e261a)，仅修改 `distribution/wrangler.json` 中的 `FRAMEWORK_TRUSTED_KEYS`，与客户端仓库变量中的有效公钥精确一致。本地服务端 78 项测试通过。

`FRAMEWORK_PUBLISHER_WORKFLOW` 和 `FRAMEWORK_PUBLISHER_ENVIRONMENT` 均继续为空。公钥登记本身不赋予发布权限。

## 生产部署与验收

| 项目 | 已执行值 |
| --- | --- |
| 部署仓库提交 | [`33ccb35c630ade51d24bad38a805a9d8f9114f2d`](https://github.com/turboism/turboism-learn/commit/33ccb35c630ade51d24bad38a805a9d8f9114f2d) |
| 固定产品源码 | `a0c84429baba6ee197aadfb3bdac80d9fe0e261a` |
| 实际部署工作流 | [41272321923](https://github.com/turboism/turboism-learn/actions/runs/41272321923)，`workflow_dispatch`，成功 |
| Cloudflare Worker 版本 | `64aa72c0-878a-4570-9f94-258a52160410` |
| 部署完成时间 | 2026-11-01 04:15 UTC（北京时间 12:15） |
| 完整部署验收完成时间 | 2026-11-01 04:18 UTC（北京时间 12:18） |

部署工作流只更新固定产品源码和公钥配置断言；发布身份配置仍为空，域名、存储绑定、迁移及原有完整验收均保留。提交使用 `[skip ci]` 避免无关的教程站自动部署，随后显式运行上述完整 API 部署工作流。

已核对的结果：

- 真实 GitHub runner 对固定源码运行 78 项服务端测试，全部通过；部署配置断言确认公钥及关闭的发布授权。
- `api.turboism.dev` 仍绑定独立的 `turboism-release-api` Worker。
- Stable、Beta、Nightly 的六个 EXE/ZIP 全量 SHA-256、校验文件、Range 206 和每文件下载统计均通过；验收下载使用统计排除标记。
- 本机于 04:18 UTC 复验：`/health` 和三个通道均为 `ready`；三通道版本、Build、源码及资产哈希与部署前一致，没有 `release.incremental`。
- 未知哈希的文档和载荷 GET 返回 `404 FRAMEWORK_RESOURCE_UNKNOWN`，HEAD 返回无响应体的 404；CORS 正常。
- 无凭据空请求访问增量准入及撤回接口，均返回 `503 FRAMEWORK_DISABLED`。
- 部署后重新读取 GitHub 公开配置：签名开关仍为 `false`，客户端与 Worker 公钥一致，签名环境仍只有 `main` 分支规则，Secret 更新时间未变。

## 剩余发布步骤

`FRAMEWORK_UPDATE_MINIMUM_UPDATER` 留待首个实际发布版本确定后设置。`production-release` 环境的发布凭据、客户端激活以及真实 A→B→C 验收尚未完成。

产品代码仍在 `test/issue-50-windows-validation-fixes`，没有合入 main。现有正式候选和签名流程限定 main，后续仍需明确源码发布安排；本次仅配置密钥与公开信任材料。

本机公开证据位于忽略目录 `build/agent-tasks/framework-signing-public/`：`client-trusted-keys.json`、`worker-trusted-keys.json`、`provisioning-record.json`。该目录不含私钥。生产部署日志为 `build/agent-tasks/framework-key-deployment.log`，完整下载验收和本机接口复验分别保存在 `build/agent-tasks/framework-key-deployment/api-production.json` 与 `public-api-checks.json`。
