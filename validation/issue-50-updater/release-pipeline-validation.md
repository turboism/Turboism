# 自更新发布接线验证（2026-11-01）

工作基线为 `743a6b3882c74f0e7273b09006b932e766153d6a`，本地分支 `local/w2-validation-fixes`，远端目标仍为 `test/issue-50-windows-validation-fixes`。本轮继续使用已有 Paseo DeepSeek V4.1 Flash 代理编写代码，主助手审查和验证。

## 已完成的验证

| 检查 | 结果 |
| --- | --- |
| 服务端 `node --test distribution/test/*.test.mjs` | 78 项通过 |
| 既有发布工具、orchestrator、promotion、prerelease 回归（本机 WSL Ubuntu） | 106 项通过 |
| 客户端资源生成 `scripts.test.test_framework_client` | 17 项通过，相关 Python lint 通过 |
| 发布接线及打包回归 `scripts.test.test_framework_release` | 19 项通过；最终与既有回归、客户端资源测试合计 142 项通过 |
| 临时测试公钥的实际 `:bootstrap:jar` | 构建及许可证检查通过；JAR 包含两份生成资源 |
| 资源目录使用仓库根相对路径 | `:bootstrap:processResources` 通过 |
| 关闭生成配置后重新构建 `:bootstrap:jar` | 通过；生成目录和最终 JAR 均无激活资源 |
| 真实 NSIS 3.09 编译（隔离签名夹具） | 最终脚本编译成功；生成含收据的 EXE/ZIP 和全部八个资产；ZIP 的签名、27 个框架文件及排除项校验通过 |
| `checkFrameworkUpdateTooling --dry-run` | Gradle 配置解析通过；此项只验证任务配置，不计为测试执行 |
| actionlint 1.7.7（不运行可选 shellcheck/pyflakes） | 三份变更工作流通过 |
| CI coverage、相关 Python Ruff、`git diff --check` | 通过 |
| 服务端部署提案补丁 | 应用及反向应用通过；断言对本工作区真实 Worker 配置通过 |

实际 JAR 验证使用 `local-build-probe` 临时公钥。包内 `buildKind=local`、`dirty=true`、`buildNumber` 为空，没有伪造真实 CI 身份。该 JAR 未安装到 Cubism；最后已恢复为不含激活资源的本地构建。

NSIS 实验使用独立临时目录、临时签名密钥和明确标记的合成测试身份，只验证编译和包内字节。没有安装测试 EXE，没有申请 CI 编号或调用线上准入。

最终审查修复了签名工作流对一个未生成的外部公钥文件的引用。新增回归从工作流步骤提取真实 `sign`/`verify` 命令，在没有该外部文件的隔离夹具中执行签名、打包和验签；公钥仍必须从实际随包 agent JAR 读取并通过校验。最终 142 项回归包含此项。

本机 Windows 直接运行既有 106 项发布回归时，有 3 项符号链接权限错误，以及换行差异造成的 promotion 相关失败；同一组测试在 Ubuntu 下 106 项通过。不能将 Windows 那次运行记为通过。本轮未重跑旧的 runtime 全集或宣称既有格式问题已解决。

## 现网及部署提案

发布接线阶段的现网只读检查：`/health` 为 ready；Stable 为 `0.67.4`、Build 56，无增量字段。彼时新资源路径返回旧实现的 `NOT_FOUND`。

所需变更整理为 [服务部署提案](service-deployment-proposal.md) 和最小补丁后，用户批准了生产部署，新 API 已部署且完整下载验收通过；后续实测状态见 [生产部署验收](production-service-validation.md)。正式签名、公钥及自更新激活尚未配置。

## 证据位置与范围

详细日志在被 git 忽略的 `build/agent-tasks/`：`client-enabled-build.log`、`client-enabled-verified.json`、`client-enabled-identity.properties`、`client-relative-path-build.log`、`client-disabled-build.log`、`client-disabled-verified.json`、`framework-real-nsis.log`、`framework-real-nsis.json`、`final-release-tests.log`、`framework-gate-config.log`。

真实编号 CI 发布、安装正式完整包后连续两次框架升级及现场故障恢复仍需后续验收。现有候选构建、签名和发布准入均限定产品 `main`；本轮按要求只提交测试分支，未改变该限制。后续正式发布需要另行确认产品源码发布安排，服务端部署本身不要求把产品测试分支合入 `main`。
