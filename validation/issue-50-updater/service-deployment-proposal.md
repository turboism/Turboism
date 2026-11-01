# 自更新服务部署提案

本轮只准备补丁，未部署生产服务、配置正式密钥或启用增量更新。

## 已核实的状态

2026-11-01，只读检查结果：

- 现网 `/health` 为 `ready`；Stable 为 `0.67.4`、Build 56，没有 `release.incremental`。
- `/v1/framework/documents/<64 个 0>` 返回 `404 NOT_FOUND`；新实现对未知资源使用 `FRAMEWORK_RESOURCE_UNKNOWN`。
- `turboism/turboism-learn` 的 `.github/workflows/deploy-release-api.yml` 固定产品源码 `03491cca190522c3c9f85f446f1ffd36e146ffec`，部署前只允许旧的 `EDGE_DOWNLOADS_ENABLED` 配置。
- 产品测试分支的 `743a6b3882c74f0e7273b09006b932e766153d6a` 已包含准入、撤回、资源下载及版本发现接口。本轮本机执行 `node --test distribution/test/*.test.mjs`，78 项通过。

## 可审核变更

[deploy-release-api-disabled.patch](deploy-release-api-disabled.patch) 只修改外部部署仓库的一个工作流文件：

1. 将源码固定版本更新为 `743a6b3882c74f0e7273b09006b932e766153d6a`。
2. 更新配置断言，接受当前源码中三个默认关闭的框架发布配置。
3. 核对 `REGISTRY/ReleaseRegistry`、`FRAMEWORK/FrameworkAuthority` 两个绑定，以及 `v1/v2` SQLite Durable Object 迁移。

原有域名检查、Cloudflare 凭据引用、服务测试和全量镜像验证保留。补丁不包含密钥。

| 配置 | 本次拟部署值 |
| --- | --- |
| `EDGE_DOWNLOADS_ENABLED` | `true`（现有值） |
| `FRAMEWORK_PUBLISHER_WORKFLOW` | 空字符串 |
| `FRAMEWORK_PUBLISHER_ENVIRONMENT` | 空字符串 |
| `FRAMEWORK_TRUSTED_KEYS` | `{}` |

这些值使新增发布权限保持关闭。补丁完成本地应用、反向应用和内容核对后，仍需用户批准才会修改部署仓库或触发部署。该工作流对自身文件的 main 推送会自动部署，不能把推送当作单纯保存提案。

## 后续接通顺序

1. 批准后应用本补丁，部署接口实现并检查现有 API 和新资源路径。
2. 独立确认正式签名、公钥及发布工作流配置，保持客户端内置公钥与服务端校验公钥一致。
3. 使用真实编号 CI 构建和受保护发布流程生成带初始签名收据的完整包。
4. 在 Windows Cubism 验证完整安装后连续两次升级、后台下载及重启应用，补齐真实版本升级证据。

第 1 步可以直接部署补丁中固定的产品测试提交，不需要合入产品 `main`。第 3 步的现有候选构建、签名和发布准入只接受产品 `main`；当前“不要合入 main”的要求仍生效，因此正式 CI 发布的源码安排需要后续单独确认。此次没有为测试分支放宽准入。

产品源码继续留在 `test/issue-50-windows-validation-fixes`；本提案不执行产品分支合并。具体发布接线与配置见 [BOOTSTRAP_RECEIPT.md](../../packaging/windows-installer/BOOTSTRAP_RECEIPT.md)，服务契约见 [INCREMENTAL.md](../../distribution/INCREMENTAL.md#production-service-apiturboismdev)。
