# Windows 启动租约与原生恢复验收：2026-11-01

本记录补充 `HANDOFF.zh-CN.md` 中旧机器的历史状态。此次验收使用当前 Windows 工作区，
只继续 `local/w2-validation-fixes` / `test/issue-50-windows-validation-fixes`，没有合入 main。
**本地恢复链路的通过不代表生产自更新已经就绪。**

## 受检构建与隔离环境

- 受检源码基于 `bae2f34863d92d0f52804876f8f1ccdb0b7e1bdb`，验收时包含本轮未提交修改。
- 构建：`gradlew.bat stageInstallerPayload -PinstallerVersion=0.68.1 -PturboismRelease=true --console=plain`。
  构建和 agent 许可证检查通过；该参数选择包格式，不赋予 CI 身份。
- 包内实际身份：`version=0.68.1`、`channel=stable`、`buildKind=local`、`dirty=true`、`buildNumber=`。
- agent SHA256：`3be137ab0cd5df1ff6b973938328e8b70e77eaca8e01caf9de973d9b941e8143`。
- agent 内没有 `framework-update-client.json` 或 `framework-update-trusted-keys.json` 激活/信任资源。
- Cubism：`F:\Live2D\Live2D Cubism 5.3.02`，使用内置 Java `17.0.3.1`，没有打开模型。
- 实验 home（相对仓库）：`build/native/issue50-host/managed-recovery-flash-20261101-092216/home`。
  由现有 `LabHomeGuard` 标记并限制在仓库的实验目录内。
- 32 个实际插件 JAR；MCP 和 ACP 在实验配置中明确禁用，避免端口/依赖冲突。因此
  **30 loaded / 0 failures 指 30 个启用插件，不是 32 个全部启用的验收**。
- 框架自动检查、插件自动更新关闭；配置基线在这些实验设置完成后建立。
- 日志、JSON、截图在 `build/native/issue50-validation/managed-recovery-flash-20261101-092216/`。
  此目录被 git 忽略，复制仓库不会自动带走证据。

## 真实 Cubism 恢复与原生确认

1. 现有 `HostRecoveryFixture seed` 暂存 Clip Mask Viewer、Command Palette 的实际 0.68.1 JAR，
   故意破坏最后一份暂存包；`HostRollbackFixture` 建立 README-only 组件事务，并保留独立 helper 副本。
   组件夹具仅在自身 JVM 内使用临时签名密钥，agent 原样复用，不注入客户端信任资源。
2. 第一次受控启动：launcher **40700**，Cubism JVM **37020**。真实启动恢复全部 32 个插件 JAR 原哈希，
   `pending.json` 停用为 `failed-pending.json`；30 个启用插件加载成功，0 失败。
   框架日志保留 `STARTING_HEALTH`，明确输出 `UPDATE_STARTUP_NOT_CONFIRMED: PLUGIN_PENDING_ROLLED_BACK`。
   premain 将该启动的 `LAUNCHING` 转成绑定实际 JVM PID/启动时间的 `USING`。
3. 首个 JVM 存活时调用独立 helper 的 `--recover`，实际返回 **75/BUSY**，日志字节不变，JVM 仍存活。
   `HostRecoveryFixture blocked` 同时确认框架恢复日志存在时，API 拒绝取消插件待办。
   这一项是 API 证据，不冒称原生按钮阻断已验证。
4. 仅向本轮无模型的实验实例发送正常关闭请求，确认 JVM 和 launcher 均退出，没有强杀。
5. 第二次受控启动：launcher **35264**，JVM **25632**。保留的 helper 在 Cubism 前输出
   `RECOVERED: rolled back interrupted update from state STARTING_HEALTH`。
   README 恢复原哈希，框架日志清除，旧 JVM 的租约回收，32 个插件、agent、实验配置哈希仍一致。
   失败插件任务保持停用；真实运行时输出 `PLUGIN_PENDING_REVIEW_REQUIRED`，再次 30 loaded / 0 failures。
6. 在真实 Cubism 中打开 Turboism → 管理插件 → “待办变更…”。以绑定实验 PID/窗口的 Windows
   定向键盘事件操作实际 Swing 控件，并逐步截图核对；没有注入服务、调用取消夹具或修改进程内字节码。
   前台鼠标操作因 Windows 焦点限制未执行，不能将这些尝试当成鼠标验收。
   对话框标题和 owner 均核对，只列出上述两项失败任务，恰有“取消列出的变更”和“保留待办”两个选项。
7. 默认选中“保留待办”。激活默认按钮后，失败任务原始 SHA256 保持
   `85b701b905bfdc7a9189ce286b09872deff200ab4a7ab87cce20be48c810bc34`。
   原生窗口显示“已保留插件待办。”。
8. 关闭/重新打开插件管理，再次打开同一确认窗口，明确将焦点移到“取消列出的变更”并激活。
   失败任务和两份独占暂存包被清理；活动插件任务仍不存在；全部 32 个插件、配置、agent、README
   保持预期原字节。此步骤是实际原生控件确认，不是 standalone API 调用替代。
9. 正常关闭第二个空白实验实例后，使用现有 `restage` 重新提交两份未损坏的同版本插件。
   第三次受控启动：launcher **38120**，JVM **35328**；真实插件应用成功，30 loaded / 0 failures。
   安装器将两个模块文件名规范化为对应插件 ID 文件名，32 个插件内容哈希保持一致，没有留下重复旧 JAR。
   框架/插件活动任务及失败任务均不存在。这是同版本本地重装重试，不是下载或正式版本升级证据。
10. 保持第三个 JVM 运行，在无更新待办的同一 home 再启动第四个实例：launcher **25000**，JVM **41040**。
    两个真实 JVM 同时存在，各有不同 token、PID、启动时间的 `USING`，各自均为 30 loaded / 0 failures。
    独立 helper 的 `--recover` 仍返回 **75/BUSY**。普通多实例启动未被错误阻断。
11. 核对两个窗口仍无模型后，分别发送正常关闭请求，确认两个 JVM 及两个 launcher 全部退出。
    几乎同时退出时，一次 release 因事务锁竞争返回 **74** 并发出保守清理警告；没有强杀或放宽锁。
    随后正常 `--recover` 返回 **10/NOTHING**，回收两个已死进程的 `USING`，租约目录为空。
    最终所有本轮实验 Cubism 均已停止，恢复/活动/失败任务均为空。

关键证据文件：

- `05-first-launch-verification.json`、`06-live-helper-refusal.log`、`13-framework-cancel-blocked.log`
- `16-second-launch-verification.json`、`25-native-review-44239744.png`
- `27-native-keep-result.json`、`43-cancel-focused-44305280.png`、`46-native-cancel-verification.json`
- `48-valid-restage.log`、`49-third-launch.stdout.log`、`50-valid-retry-verification.json`
- `51-concurrent-launch.stdout.log`、`52-two-live-leases.json`、`52-two-live-helper-refusal.log`
- `53-after-exit-reap.log`、`final-verification.json`、`source-sha256.json`

## 中文路径与脚本回归

此前中文路径红灯是真实缺陷。最小对照证明 CMD 按控制台**输出代码页**解析 BAT；
`Console.OutputEncoding` 的缓存会在 `chcp` 后给出过期值。现在读取实时 `GetConsoleOutputCP()`，
按该编码写临时 BAT，并明确区分原官方 ANSI BAT 与本次生成的中间 BAT 的读取编码。
Java override 路径含空格时，临时副本中的 `%JAVA_EXE%` 调用也加了引号。官方 BAT 没有被编辑。

`cn-diag-v2-*` 保留 CP936、CP65001、输入936/输出65001 三组独立隐藏控制台对照，包含
Java override → managed options 的两段串接。原生 argv 以显式 UTF-8 文件中的 UTF-16 hex 记录，
逐参数比较；旧 ANSI 变体可重现失败，当前变体通过。不能用控制台显示乱码替代 argv 正确性判断。

| 检查 | 实际结果 |
| --- | --- |
| PowerShell 5.1 `test-launch-lease.ps1` | 101 assertions PASS；含中文/空格两段串接及官方 BAT 哈希不变 |
| `test-managed-launch.ps1` | 196 assertions PASS；输出代码页 65001 下也通过 |
| `test-bat-integration.ps1` / proxy | 89 assertions PASS / PASS；均是隔离夹具 |
| Bootstrap receipt Python | 15 tests PASS，最终日志无 `UnicodeDecodeError` |
| CI coverage | `python -X utf8 scripts/test/test_ci_coverage.py` PASS；默认 GBK 的直接调用会解码失败 |
| 本地 installer staging / agent licenses | PASS |
| `git diff --check` | PASS |

最终脚本日志为 `lease-final-gate.log`、`managed-launch-final-gate.log`、
`bootstrap-receipt-final-gate.log`、`bat-integration-final-gate.log`、`proxy-final-gate.log`。
脚本中的 mock Java/host 不算真实 JVM 证据；真实宿主证据见前一节。

## Java 与环境限制

- 整合运行：runtime **564 tests / 5 failures / 0 errors / 10 skips**；5 项均在
  `Files.createSymbolicLink` 因本机权限不足失败（receipt 3 项、downloader 2 项）。整组没有通过。
- 最后补充的晚期 BUSY 回归独立重跑后，`FrameworkUpdateLaunchLeaseTest` **31/31**；真实子进程
  `FrameworkUpdateLaunchLeaseProcessTest` 在整合运行 **8/8**。
- Bootstrap **251 tests / 0 failures / 30 skips**；真实打包 agent 的 premain probe 和依赖隔离 probe 均通过。
- Core-contract 保留 **33/33** 的既有 XML；整合日志显示 `UP-TO-DATE`，不称本次重跑。
- receipt ACL 新回归及原失败用例合计 **8/8**；W2 native 仍为 **22 PASS / 3 NOT_RUN / 0 FAIL**，
  不算完整 W2 通过。缺符号链接权限和无法赋予 foreign owner 的覆盖没有被伪造成通过。
- Bootstrap 格式检查通过；runtime 格式检查仍有 **39 个既有文件**失败，本次没有批量格式化它们。

Java 原始日志/XML：`flash-integrated-java.log`、`flash-integrated-evidence/`、
`flash-final-lease-format.log`；真实 premain 证据在 `build/worktree/macho-hyena/bootstrap/launch-lease-premain/`。

## 尚未完成的边界

没有正式 CI 发布的完整框架下载/签名应用/连续两次版本升级，没有生产服务部署、正式签名、公钥配置或激活。
README-only 事务不证明 framework/SDK/Graal 字节码升级。插件和框架日志不是一个全局原子事务。
旧 retained helper 不支持新启动 token 协议时仍拒绝启动并提示完整包恢复，未实现自动迁移。
legacy/direct launch 在 premain 注册之前仍有窗口；本次托管启动结果不能推广为所有入口的原子保证。
物理断电、完整 Windows handle/TOCTOU 防护、NSIS EXE 安装及 Linux/Proton 仍未获本轮证据。

本次未恢复任何 DeepSeek V4 Pro 代理；既有 Flash 代理完成最后源码修复后已空闲。
