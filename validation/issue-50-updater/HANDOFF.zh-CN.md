# Issue #50 自更新接手说明

## 分支与边界

- 仓库：`git@github.com:turboism/Turboism.git`。
- **只继续 `test/issue-50-windows-validation-fixes`，不要合入 `main`。**
- 当前本地分支：`local/w2-validation-fixes`。
- 最新实现提交：`f173f64e2031053767cbd8f9d584d51e32a910f4`。
- 上一检查点：`a5b0c2525`；备份安全修复：`e72137dec`。
- 用户允许本机 Cubism 验收，但这不等于批准生产部署、正式密钥配置或更新激活。
- **生产服务、正式签名、公钥及激活均未改动。不要伪造 CI 身份以绕过准入。**
- 不要强制关闭用户当前编辑器、不热替换已加载的 framework/SDK/Graal 文件、不修改官方 Cubism BAT。
- 先看 `STATUS.md`、`ACTIVATION.md`。目前不是 production-ready。

## 最新实现做了什么

`f173f64e2` 修复实机发现的失败恢复缺口：

1. 插件批次完整回滚后允许原插件继续启动，输出明确诊断，但**不调用框架健康确认**。
2. 失败的原始 `pending.json` 在完整回滚后停用保存为
   `state/runtime/plugin-management/failed-pending.json`，保留原始字节和包，不再每次启动自动执行坏包。
   旧后端只认识 `pending.json`，不会把这个停用文件当成活动任务；尚未做独立旧 JVM 验证。
3. 存在停用待办时返回 `DEFERRED`，允许已安装插件运行，继续拒绝框架健康确认；暂停自动更新及新的提交/下载，等待明确处理。
4. 插件管理原生窗口新增 **“待办变更…”**：后台读快照，EDT 展示完整列表并明确确认，后台取消。
   失败批次优先展示；若同时有独立活动批次，取消失败批次不会删掉活动批次或其引用的包。
5. 取消只针对确认过的、根目录绑定的原始任务字节快照。每次写任务添加 revision，防止相同操作重新提交后的 ABA 误取消。
6. 框架 pending/health journal 存在时禁止取消插件待办：先通过受控启动器完成框架恢复。
7. 新取消 API 拒绝 EDT IO；关闭/隐藏窗口不弹出过时确认，已明确同意的提交不因关闭窗口中断。
8. 插件 journal 有界读取、完整写循环、`force(true)`，超限写入保留旧任务。所有六份主界面语言资源已补齐。

`e72137dec` 已先修复另一个实质数据损坏风险：备份尚未完成时失败，不得拿半份备份覆盖正常插件。
原故障注入测试能把 803/804 字节的正常 JAR 损坏成 1 字节；修复后返回 recovery-required，原文件和待办保留。
真实 Windows ReadData 拒绝测试也通过。**不完整备份/回滚失败仍阻断启动，没有放宽此检查。**

主要文件：

- `runtime/src/main/java/dev/turboism/pluginmanagement/PendingPluginOperations.java`
- `RuntimePluginManagementService.java`、`PluginUpdateGate.java`、`CoordinatedPluginUpdateService.java`
- `runtime/src/main/java/dev/turboism/preview/PreviewRuntime.java`
- `runtime/src/main/java/dev/turboism/shell/{PendingPluginChangesAction,CoreWindows,CoreDialogs}.java`
- `core-contract/src/main/java/dev/turboism/internal/core/CorePluginManagement.java`（新增 default 方法，兼容旧实现）

## 已执行的测试，不要混淆证据层级

### 最新源码回归

```bash
./gradlew :runtime:test \
  --tests 'dev.turboism.pluginmanagement.*' \
  --tests 'dev.turboism.shell.FrameworkUpdatesWindowTest' \
  --tests 'dev.turboism.shell.PendingPluginChangesActionTest' \
  --tests 'dev.turboism.shell.CoreWindowsTest' \
  --tests 'dev.turboism.preview.PluginPendingStartupPolicyTest' \
  --tests 'dev.turboism.update.*' \
  :core-contract:test :bootstrap:jar --console=plain
```

- **runtime：524 tests，0 failures/errors，6 skips；core-contract：33 tests，0 failures/errors/skips。**
- Bootstrap JAR 构建、许可证检查通过。
- 新覆盖：停用失败任务、完整回滚与 DEFERRED 健康屏障、native consent 的拒绝/关闭/隐藏/重开、异步 IO、ABA、跨根快照、其他活动批次、框架恢复阻断、未知文件保护、超限写保留旧任务。
- UI 单测注入服务，不能代替实际按钮验收。
- Python 71 tests（1 skip）、Node 78 pass 是上一 `e72137dec` 检查点结果；**本次 `f173f64e2` 尚未重跑这两组**。

### 扩展测试有已确认基线失败

`PreviewPluginLifecycleIsolationIntegrationTest` 全类：5 tests，2 failures。
在独立干净 `a5b0c2525` worktree `../r50` 同样复现：

- `blockingInitIsFencedLateEnableNeverActivatesAndOtherPluginsLoad`：5 秒等不到 `blocking.entered`。
- `failedScopeCloseKeepsLoaderRetainedAndNeverReinvokesCloser`：Windows retained `scope-fail.jar` 导致 TempDir 清理失败。

没有改断言来刷绿。完整类结果不是通过，524 专项结果不包含该类。
之前的 shell 长控件断言也在 origin/main `9767e2b61` 复现；见 `shell-baseline-validation.txt`。

本机日志：

- `/tmp/issue50-recovery-primary.log`：最新专项绿。
- `/tmp/issue50-recovery-consent-red.log`：新 ABA 回归修复前失败，之后已绿。
- `/tmp/issue50-recovery-integrated.log`、`/tmp/issue50-recovery-lifecycle-isolated.log`：扩展类失败。
- `/tmp/issue50-recovery-lifecycle-baseline.log`：修改前基线同样失败。

## 两个实机目录：不要弄混

### A. 用户当前实例：请保留

- Home：`D:\turboism-test\issue50-host-fe110e4`
- Cubism：`D:\Live2D Cubism 5.3.04`，自带 Java 17。
- **PID 40752 在交接时仍运行，已进入真实编辑器界面；没有被这轮验收关闭。**
- Agent 是 `e72137dec` 本地构建，**不是最新 f173f64**。
- 29 插件、0 failures。4 个测试选更插件已升到本地 0.68.1，其余多数仍为 0.67.4。
- 选区笔仍是 **0.67.4**，无 `*-dark.png`；用户看到的旧图标因此未适配，不是最新代码倒退。
  最新 0.68.1 包已有完整暗色资源，修复提交 `966d32636`。不要重写这份已有适配。
- 曾承诺不再关闭此实例；不要为了后续测试直接杀掉它。

### B. 本轮独立验收目录：已停止，状态保留

- Home：`D:\turboism-test\issue50-recovery-f173f64`
- 最新 agent **f173f64**，真实 provenance：version 0.68.1 / stable / **local** / buildNumber 空 / dirty=false。
  `-PturboismRelease=true` 只是包格式，不代表 CI release。没有测试公钥或 client activation 资源植入。
- 从新 staging 单独复制 32 插件，自动更新偏好关闭，没有打开模型。
- 本轮仅这个新实例 **PID 34860** 已由操作者关闭；当前没有此目录的 Cubism JVM。
- 人工同版本重装夹具提交了 Clip Mask Viewer + Command Palette，最后一个暂存包被故意同大小损坏。
- 另有签名 README-only 组件事务，agent 原样复用；**不是完整框架发布/字节码升级/正式 CI 资格验收**。

已观察到：

- **全部 32 个插件 JAR 原哈希恢复**。
- 活动 `plugin-management/pending.json` 已移走；`failed-pending.json` 存在，坏包没有再次作为活动待办。
- 实际 Runtime 不再因插件完整回滚而停掉，输出 `PLUGIN_PENDING_ROLLED_BACK`。
- 框架 health 仍是 **STARTING_HEALTH**，日志明确 `UPDATE_STARTUP_NOT_CONFIRMED: PLUGIN_PENDING_ROLLED_BACK`，没有误确认。
- 但本次实际插件结果为 **30 loaded / 2 failures**：MCP 默认 `43123` 与 A 实例冲突，ACP 随依赖失败。
  **不是 32 插件零失败的整体验收，不要把它写成绿。**

当前 B 磁盘状态（交接再次确认）：

- `state/runtime/update/helper/journal.json` = **STARTING_HEALTH**。
- README 仍带组件探针；backup 保留。下一次正常受控启动应先恢复原 README。
- `state/runtime/plugin-management/failed-pending.json` 保留，`pending.json` 不存在。
- 32 JAR 原字节均恢复。没有正式 installed receipt。

本机新日志：
`D:\turboism-test\issue50-recovery-f173f64\logs\runtime\2026-10-31\turboism-16-39-42.603-p34860-14369363374658184139.log`

## 接手优先做什么

1. 解决 B 实例的**夹具端口隔离**，不要关 A 来腾端口。
   - 简便方式：只在 B 的 config.json 把 `dev.turboism.plugin.mcp` 和 `dev.turboism.plugin.acp` 加入 `disabledPlugins`；如采用此法要记录为明确禁用，并重新建立“操作后不变”的配置基线。
   - 或用合法测试 JVM 选项让 B 的 `turboism.mcp.port=0`。现有受控启动器会清空环境 Java options，不能靠放宽它的安全处理作弊，也不要改官方 BAT。
2. 正常启动 B：确认 retained helper 恢复 README、删除框架 journal；失败插件 intent 保持停用，返回 DEFERRED，已安装插件可继续运行。
3. 验证原生插件管理“待办变更…”：只展示失败批次，默认保留；取消需明确确认，已安装 JAR/用户配置不变，停用任务及其独占包可清理，然后重新下载/提交。
   - **实际按钮还没验收。** 通过 standalone service 的调用也不能冒称 native UI 点击。
   - 首次 health journal 存在时，清除必须被阻止。辅助 `HostRecoveryFixture blocked` 可检验 API，不等于 UI 验收。
4. 进一步验证框架启动健康/连续升级、下载取消重试、多实例/原子 apply-launch lease。两个 journal **不是全局原子事务**。
5. 再跑 Python/Node 回归，复核新实现，更新 `STATUS.md`，继续只推测试分支。
6. 正式完整原生下载链路需要真实、可验证 CI release 及 owner 批准的受保护签名/服务配置；没有这些不要换成本地伪 CI。

## 实机夹具与可复现性

本仓库只提交了两个 Java 夹具源：`fixtures/HostRecoveryFixture.java` 和 `fixtures/HostRollbackFixture.java`。
它们是 **实验室程序，不会打包进产品**，有隔离目录保护，不是发布签名工具。

```bash
./gradlew stageInstallerPayload -PinstallerVersion=0.68.1 -PturboismRelease=true
```

编译/运行夹具的 Windows classpath：

`<repo>/build/windows-installer/staging/turboism-agent.jar;<repo>/build/windows-installer/staging/graal/lib/*;<fixture classes>`

- `HostRecoveryFixture seed` 只适用于未提交过待办的实验室目录；不要在当前已失败状态重复 seed。
- `HostRecoveryFixture blocked` 检查框架恢复阻止取消；其他检查模式预期 code 为 PLUGIN_PENDING_FAILED。
- `HostRollbackFixture <B home> <new empty stage dir> <result.json>` 仅用于独立组件探针，不可拿它绕过完整发布资格。

更多一次性脚本/报告在原 worktree 的 `build/native/issue50-host/`，**被 git 忽略，不随 clone 或新 worktree 自动出现**：

- `prepare_recovery_host.py`、`start-recovery.ps1`、`close-recovery.ps1`
- `recovery-host-baseline.json`、`recovery-component-result.json`
- 之前实机的 `HostPluginBatchFixture.java`、`test-backup-read-denial.ps1` 等

原 worktree：`C:/Users/Lenovo/.paseo/worktrees/2emxm1z3/hungry-wolf`。
本机 JDK：`C:/Users/Lenovo/Apps/jdk-17.0.20.1+1`。
不要猜测其他 worktree 的 Gradle 输出目录；classpath 导出机制见 bootstrap 文档。

## 其余仍未完成

- 生产 API streaming / Cloudflare 真运行时、protected publisher、release activation 与密钥配置。
- 真 NSIS EXE 编译：编译器缺失，官方 SourceForge 下载及镜像重定向均超时。
- 原生 Update 窗口完整 download→插件选择→明确 consent→正式签名框架应用→health→再次升级。
- 多进程 apply/launch 原子互斥、物理断电、Linux/Proton 证据。
- 不要把 in-process root gate / CAS / 组件夹具写成跨进程原子事务或生产发布通过。

之前 Gemini Fast 子代理已额度耗尽，没有换模型继续委派；最近实现、回归及实机验证由主助手完成。
