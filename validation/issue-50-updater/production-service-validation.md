# 自更新 API 生产部署验收（2026-11-01）

用户批准生产部署后，已将新版发布 API 部署到 `api.turboism.dev`。本记录对应首次保持发布关闭的部署；其后用户批准的正式密钥及公钥配置见 [签名配置验收](signing-provisioning-validation.md)。客户端自更新仍关闭，产品代码继续留在 `test/issue-50-windows-validation-fixes`。

## 部署身份

| 项目 | 已执行值 |
| --- | --- |
| 部署仓库提交 | [`0579cfd2cc5a78a23e1a4509adb461fa73becb48`](https://github.com/turboism/turboism-learn/commit/0579cfd2cc5a78a23e1a4509adb461fa73becb48) |
| 部署的产品源码 | `743a6b3882c74f0e7273b09006b932e766153d6a` |
| 实际部署工作流 | [41268862380](https://github.com/turboism/turboism-learn/actions/runs/41268862380)，`workflow_dispatch`，成功 |
| Cloudflare Worker 版本 | `e8d9a081-2c89-46f9-acfb-d98eb384dd30` |
| 部署完成时间 | 2026-11-01 03:22 UTC（北京时间 11:22） |
| 完整部署验收完成时间 | 2026-11-01 03:25 UTC（北京时间 11:25） |

实际应用的是 [deploy-release-api-disabled.patch](deploy-release-api-disabled.patch)：一个工作流文件，+9/-3。部署仓库的另一工作流会在任何 main 推送时部署教程站，因此该提交使用 `[skip ci]` 关闭本次自动 push 触发，再显式运行既有 API 部署工作流。API 的源码测试、配置断言、域名检查及完整镜像验证均实际执行；该提交仅出现这一次 API 手动运行。

## 已通过的检查

- 真实 GitHub runner 对固定源码执行服务端测试：78 项通过、0 失败。
- `api.turboism.dev` 仍绑定到独立的 `turboism-release-api` Worker。
- Stable、Beta、Nightly 的六个 EXE/ZIP 全量下载、SHA-256、校验文件、Range 206、每文件下载统计通过。验收下载使用现有统计排除标记。
- 部署前后三个通道的版本、Build、源码和资产哈希一致；均未出现 `release.incremental`。
- 新文档/载荷路径的未知哈希：GET 返回 `404 FRAMEWORK_RESOURCE_UNKNOWN`，HEAD 返回无响应体的 404，CORS 正常。
- 无凭据的空请求访问 `/v1/framework/admit` 和 `/v1/framework/withdraw` 均返回 `503 FRAMEWORK_DISABLED`；发布权限保持关闭。
- 现有 [真实 OIDC 同步工作流 41269118606](https://github.com/turboism/Turboism/actions/runs/41269118606) 成功，使用实际 GitHub 身份；未伪造 CI 编号或身份。

## 发布数据新鲜度

部署前 `/health` 已为 `degraded`，快照时间为 `2026-11-01T02:30:33.874Z`；三个通道仍为 `ready`。部署和 OIDC 同步后曾观察到相同状态，说明同步认证通过并不等于上游刷新成功。

服务随后按原有机制恢复刷新，快照更新为 `2026-11-01T03:37:40.254Z`。诊断窗口内两次健康读取及本机最终复验均为 `ready`，三通道版本和资产哈希仍与部署前一致。本轮没有修改刷新代码或增加上游凭据；没有捕获到异常消退前的具体错误，不将原因归结为已证实的限流或网络故障。

最终 [只读诊断 41270435338](https://github.com/turboism/turboism-learn/actions/runs/41270435338) 成功：100 秒窗口捕获 4 条完整记录，没有匹配的刷新错误，两次健康响应均为 `ready`。诊断仅存在于部署仓库的 `ops/issue-50-release-api-refresh-diagnostic` 分支（`93dee8146e1df14d6ec60856ea934228df75878f`），不含部署动作，诊断时部署仓库 main 为 `0579cfd`。首次诊断因工具未正确处理预期 timeout 124 提前结束，随后修正其 shell 退出码处理并重跑；该失败不是生产部署失败。

## 后续发布准备

首次部署后的只读核对发现：当时产品仓库尚无框架更新变量，`framework-release-signing` 环境尚不存在。随后用户批准的签名环境、密钥及公钥配置已另行记录；`production-release` 发布环境配置仍待后续完成。

正式签名密钥及公钥配置已获批准；发布权限及客户端激活仍保持关闭。候选构建和签名流程限定产品 `main`，产品“不合入 main”的要求继续生效；首次真实 CI 完整安装及 A→B→C 升级需先明确源码发布安排。

本机详细证据保存在忽略目录 `build/agent-tasks/`：`deployment-before.json`、`deployment-run-41268862380.log`、`deployment-run-41268862380/api-production.json`、`refresh-diagnostic-41270435338.log` 和 `refresh-diagnostic-41270435338/summary.json`。`deployment-after.json` 由复验脚本更新为最近一次结果；本次公钥部署的固定复验副本见 [签名配置验收](signing-provisioning-validation.md)。
