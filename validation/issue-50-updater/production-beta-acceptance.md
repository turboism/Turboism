# 正式 Beta 自更新实机验收：两次连续升级通过（2026-11-01）

> 后续正式 Beta.9 / Build 83 已修复下文记录的客户端插件目录限制，并添加更新日志、MB 和计时。
> 本文保留 Beta.2 → Beta.5 → Beta.7 的实机历史证据；新版发布及因用户游戏而延后的联动验收见 [后续记录](update-window-followup.md)。

用户已授权生产部署、正式签名配置、自更新、验收后合入 main 并发布；随后明确结束游戏并允许恢复 Cubism 验收。本记录使用正式 GitHub Actions 候选、正式签名和公开生产 API，在独立 Windows Cubism 5.3.02 安装目录执行。

**结论：Beta 框架自更新已可用。** 原生下载、验签、明确安装确认、正常退出、托管启动应用和健康确认连续通过两次：Beta.2 → Beta.5 → Beta.7。生产服务已部署并启用，本轮没有待执行的服务端配置。可选插件联动的目录问题及旧安装的首次迁移要求见文末。

## 已确认的生产链路

- PR [#59](https://github.com/turboism/Turboism/pull/59)、[#60](https://github.com/turboism/Turboism/pull/60) 已合入，正式 Beta.5 / Build 79 已发布。
- PR [#61](https://github.com/turboism/Turboism/pull/61) 修复匿名 GitHub 请求 403 时发现数据停滞、通知工作流假绿的问题；合入 main `9a450f4b361cbb73f6c81610efb2baaa29da1ec4`。
- PR [#62](https://github.com/turboism/Turboism/pull/62) 修正短期凭据的格式和长度假设。只读 job 凭据由真实 OIDC 通知携带，仅用于本次固定 GitHub API 读取，不持久化、不进入客户端或镜像下载。完整服务回归 86 项通过。
- PR #62 的全部检查通过后已正常合入 main `e4d9cc932ea104aa7ee51542f0c59039581e6d03`；合并后主 CI [41296740873](https://github.com/turboism/Turboism/actions/runs/41296740873)、三通道检查 [41296740883](https://github.com/turboism/Turboism/actions/runs/41296740883)、历史检查 [41296740870](https://github.com/turboism/Turboism/actions/runs/41296740870) 均通过。Beta.7 保持其真实候选源码 `9a450f4b...`，没有改写发布身份。
- 修复部署 [41295436877](https://github.com/turboism/turboism-learn/actions/runs/41295436877) 成功；固定源码 `3b977212a35bbd5714bb8aef489bc460b68292eb`。真实手动通知 [41295679392](https://github.com/turboism/Turboism/actions/runs/41295679392) 与候选完成自动通知 [41296045901](https://github.com/turboism/Turboism/actions/runs/41296045901) 均成功，返回新的 ready 快照。
- Stable、Nightly 发布身份与资产哈希未变。Beta.5 的公开签名、36 个对象 HEAD 大小/ETag 与单字节 Range 206 验证通过；发布任务另完成全部对象内容哈希读回验证。

## 第一次真实升级：Beta.2 → Beta.5

原生菜单检查更新，原生窗口下载并验证 `102586270` 字节，明确点击“下次启动时安装”，正常退出空白实验编辑器，再经原托管启动器启动。

| 检查 | 实际结果 |
| --- | --- |
| 原版本 | `0.68.1-beta.2` / Build 76 |
| 新版本 | `0.68.1-beta.5` / Build 79 |
| 新源码 | `206b8ed33f38eb9fc5f12e0bf961e29edb4d961f` |
| 已安装签名清单 | `b42c198a657420a86fd18eb6bc4735eaded95e41e8ba66449f65cbe3e8134e55` |
| 框架文件 | 36 个大小、SHA-256 与正式清单一致 |
| 插件与设置 | 32 个插件 JAR、config、更新偏好和官方 BAT 哈希均未变 |
| 实际启动 | 30 个启用插件，0 failures；MCP/ACP 只在实验目录显式禁用 |
| 健康确认 | `UPDATE_STARTUP_CONFIRMED`，`2026-11-01T09:59:41.930807600Z` |
| 事务状态 | 新收据已写入，pending/journal 清除，无失败插件待办 |

本机证据：`build/agent-tasks/beta2-to-beta5-native-acceptance.json`、`beta5-public-acceptance-final/acceptance.json`；原生截图与前后快照位于 `build/native/issue50-validation/framework-beta-user-20261101/`。没有强杀编辑器、热替换已加载框架/SDK/Graal、修改官方 BAT 或伪造 CI 身份。

## 第二次真实升级：Beta.5 → Beta.7

正式 Beta.7 / Build 81 候选 [41295116909](https://github.com/turboism/Turboism/actions/runs/41295116909) 与后继发布 [41296126926](https://github.com/turboism/Turboism/actions/runs/41296126926) 均成功，已发布 [v0.68.1-beta.7](https://github.com/turboism/Turboism/releases/tag/v0.68.1-beta.7)。自动通知 [41296682864](https://github.com/turboism/Turboism/actions/runs/41296682864)、[41296733865](https://github.com/turboism/Turboism/actions/runs/41296733865) 均成功。

在第一次升级所得的 Beta.5 实例中，原生更新窗口发现 Beta.7。通过实际鼠标点击下载，窗口显示 `102586270 / 102586270 B`、验证完成；再明确点击“下次启动时安装”，窗口显示框架及 0 个插件已排队。正常关闭空白实验 JVM 17732，经同一托管启动器重新启动 JVM 33276。

| 检查 | 实际结果 |
| --- | --- |
| 原版本 | `0.68.1-beta.5` / Build 79 |
| 新版本 | `0.68.1-beta.7` / Build 81 |
| 新源码 | `9a450f4b361cbb73f6c81610efb2baaa29da1ec4` |
| 已安装签名清单 | `15d93fc1403bb9df78159c4064975a83575881726f84d23fccc5b2a2365e4d1a` |
| 框架文件 | 36 个大小、SHA-256 与正式清单一致 |
| 插件与设置 | 32 个插件 JAR、config、更新偏好和官方 BAT 哈希均未变 |
| 实际启动 | 30 个启用插件，0 failures；MCP/ACP 仍仅在实验配置中禁用 |
| 健康确认 | `UPDATE_STARTUP_CONFIRMED`，`2026-11-01T10:22:31.106627300Z` |
| 事务状态 | 新收据已写入，框架及插件 pending/journal 清除，无失败插件待办 |

第二次使用独立的前后快照与清单重新验证，没有复用第一次通过结果。证据：`build/agent-tasks/beta5-to-beta7-native-acceptance.json`、`beta7-public-acceptance/acceptance.json`，以及实验截图目录的 `B-before-download-snapshot.json`、`B-beta7-queued-4199624.png`、`C-after-beta7-startup-snapshot.json`。验收后仅正常关闭本轮空白实验实例，保留安装与证据文件。

Beta.2/Beta.5 的四个旧版本 SDK/Graal host 自有 JAR 均已退役。启动器退出后，USING 租约按既有设计留待下次 helper 检查回收；收尾运行正式 helper `--recover` 返回 `10 / NOTHING: no journal`，回收已退出 JVM 的租约。最终租约为零，安装文件哈希与成功启动快照完全一致，没有手工删除租约或回滚成功安装。证据为 `C-final-stale-lease-reap.log`、`C-final-reaped-snapshot.json`。

最终公开 HTTP 检查时间为 `2026-11-01T10:22:43Z`：`/health` 及三个通道均为 ready，Beta 返回版本 `0.68.1-beta.7`、Build 81 和正式框架更新描述。两次本机验收与公开服务检查的精简结果已保存为 [production-beta-evidence.json](production-beta-evidence.json)；完整截图、日志和快照在上述被 Git 忽略的本机证据目录。

## 使用范围与已知限制

这轮验证框架自更新；“同时更新选中的兼容插件”已在原生窗口明确取消。当前公开插件目录中 MCP preview `0.68.0`、`0.68.2` 的权限说明有 508/1021 字符，超过客户端 500 字符上限，导致插件联动显示 `reason length is out of bounds`。该可选联动尚未通过，使用框架更新时须取消勾选；没有删除目录数据或绕过签名检查。

尚未包含正式更新器及签名收据的旧安装，需要先通过新的完整安装包升级一次。当前发布和本机验收属于 Beta 通道，不能据此宣称 Stable 用户已经自动迁移。

本记录不扩大此前的故障恢复与平台证据：真实坏插件回滚、原生取消和 README 组件恢复见 [Windows 恢复验收](windows-launch-lease-host-validation.md)。完整正式框架故障回滚、物理断电、NSIS EXE 安装过程及 Linux/Proton 不属于本次连续成功升级的验收范围。
