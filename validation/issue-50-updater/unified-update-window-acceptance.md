# 统一更新列表与右侧日志验收（2026-11-01）

本次按用户要求将框架和插件放入同一个待更新列表：已是最新或没有可用更新的组件不列出；点击条目只切换右侧更新日志，勾选框决定参与更新的组件。框架可取消勾选，框架已最新时也可独立更新插件。进度使用框架与已选插件的合计 MB，保留下载与验证计时。

## 实现及检查

- PR [#64](https://github.com/turboism/Turboism/pull/64) 已合入 main `96ff898324f1f0f6cb6af1968e59ba903dd0fce2`。实现提交为 `da2473eca946e32282efc0cda34a61f4c8f67df4`、`b649bd7f9807467bfc27bca8d6f234c718ec4df1`。
- 插件日志从已验签目录指定的固定源码提交读取本地化 README，提取当前版本之后、目标版本以内的变更。缺少对应版本日志时显示占位；请求失败可重试。异步加载不会阻塞窗口，旧请求不会覆盖新选择。
- 实机 Cubism 自带 Java 连接 raw GitHub 超时，已补官方 GitHub Contents API 回退；仍限定同一源码提交、大小和总时限。该回归先复现失败，修复后通过。
- Windows 插件/窗口专项 169 项、0 failures、1 skip；后续窗口、日志、完整语言检查 53 项全部通过。Linux 代码质量、Javadoc、格式、四语 changelog 检查通过。
- PR 主 CI [41313330744](https://github.com/turboism/Turboism/actions/runs/41313330744)、三通道打包 [41313330785](https://github.com/turboism/Turboism/actions/runs/41313330785)、发布说明及两次历史检查全部通过。
- 本次不需要修改或重新部署服务端。

## 本地构建的真实插件升级

独立目录：`build/native/issue50-host/unified-updates-local-20261101/home`。框架身份明确为 `0.68.1-beta.local-SNAPSHOT (beta, local, dirty)`，不是正式 CI 身份。

1. Cubism 5.3.02 启动正常，30 个启用插件、0 failures；MCP/ACP 仅在实验中显式禁用。
2. 真实原生窗口只显示剪裁蒙版查看器和命令面板两个 `0.68.0 → 0.68.1` 更新，右侧日志随所选行变化。
3. 真正点击下载后显示 `0.15 / 0.15 MB`、耗时 `00:22`；验证成功，再明确点击下次启动安装，pending 恰好两项 INSTALL。
4. 正常退出、托管重启后两个插件版本和 SHA-256 均匹配已验证下载包，pending 清除，30 个启用插件、0 failures。再次检查时待更新列表为空。
5. 离线恢复一个官方旧插件作为日志夹具，重新启动后真实读取并显示命令面板中文 `0.68.1` 日志。该次使用 Slate 主题，日志正文可读；宿主的全局深色配色存在文字/背景不协调，因此不将其计为完整深色主题验收通过。
6. 日志检查结束后正常关闭实验，离线恢复升级后的命令面板及原主题配置。

本地截图与日志在 `build/native/issue50-validation/unified-updates-local-20261101/`，关键文件：`native-download-completed-58002012.png`、`queued-plugins.json`、`apply-empty-list-106501386.png`、`local-apply-launcher-out.log`、`notes-dark-loaded-120523770.png`。

此部分证明原生窗口与真实插件升级，不冒充正式签名框架升级。

## 正式发布与联合升级

正式 [Beta.10 / Build 84](https://github.com/turboism/Turboism/releases/tag/v0.68.1-beta.10) 已于 `2026-11-01T14:33:33Z` 发布并准入自更新服务。候选 [41314639033](https://github.com/turboism/Turboism/actions/runs/41314639033)、受保护发布 [41316246339](https://github.com/turboism/Turboism/actions/runs/41316246339)、同步 [41316801762](https://github.com/turboism/Turboism/actions/runs/41316801762) 全部成功；main 的主 CI、三通道打包、历史检查也全部通过。

最终 ZIP SHA-256：`a479afa6d5657383beb5138f11b187c160ae2e7d001fbaa84f2a2038bc20b516`；正式清单 SHA-256：`c496963c4e085896813caf9b603ba65e8b734b1b5d5db42dd8f47a2bbf324fcb`。本机验签并校验全部 36 个框架文件。线上发现接口返回正确源码身份，中英日韩日志非空，签名验证及 36 个对象的 HEAD 大小/ETag、单字节 Range 206 检查通过；Stable、Nightly 的身份与资产哈希未变。

### 正式 Beta.10 的新版窗口与插件独立更新

独立目录 `build/native/issue50-host/unified-updates-ui-official-20261101/home` 直接来自上述正式签名原包。首次启动前仅将两个插件替换成官方旧版本夹具，框架文件保持原样。

- 实际显示两个待升级插件，已最新的框架和其他插件不在列表中。选择剪裁蒙版查看器显示该版本未提供日志；点击命令面板后右侧标题和正文切换成其中文 `0.68.1` 日志。
- 首次目录请求遇到 `Connection reset`，点击检查更新后成功。真实下载、验证显示合计 `0.15 / 0.15 MB`，耗时 `00:02`；点击下次启动安装，恰好排队两项 INSTALL。
- 正常退出再托管启动后，两插件均为 `0.68.1` 且哈希匹配已验签目录与排队包；其余 30 个插件 JAR、配置、更新偏好、官方 BAT 未变。36 个签名框架文件和原安装收据保持不变，pending/journal 均清空，30 个启用插件、0 failures。
- 再次检查时列表为空且显示“没有可用更新”；实验已正常关闭。

证据目录 `build/native/issue50-validation/unified-updates-ui-official-20261101/`：`E-ui-command-notes-12521772.png`、`F-plugin-result-12521772.png`、`G-queued-plugins.json`、`I-plugin-acceptance.json`、`J-no-updates-28840142.png`。

### 旧正式版的框架与插件联合升级

独立正式起点：`build/native/issue50-host/unified-updates-official-20261101/context.json`，来自已验签的 Beta.9 / Build 83 包。首次启动前只把两个待验插件换成官方已验签目录中的 `0.68.0` 旧包，正式框架文件保持原样。

旧实例两次联合下载在 GitHub 响应阶段超时，未产生待安装任务。保存失败截图后，正常退出并重启该空白实验；随后联合下载与验证成功，窗口显示框架 `102.60 / 102.60 MB`、耗时 `00:11`。此处仍是 Beta.9 的旧窗口；Beta.10 的合计 MB 和新布局以独立正式新版窗口验收为准。

真实点击“下次启动时安装”，框架 pending 和恰好两项插件 INSTALL 均已写入，当前加载的插件文件仍与升级前一致。再次正常退出、通过托管启动器启动后：

- 框架为 `0.68.1-beta.10 / Build 84`，源码身份和签名收据与正式更新源一致，全部 36 个框架文件的大小与 SHA-256 匹配。
- 剪裁蒙版查看器与命令面板均由 `0.68.0` 升为 `0.68.1`，安装哈希与入队包一致；其他 30 个插件 JAR、配置、更新偏好和官方 BAT 未变。
- 运行时记录 `UPDATE_STARTUP_CONFIRMED`，启动结果为 `host=ACTIVE, plugins=30, failures=0`。
- 框架及插件 pending/journal 全部清空。实验已正常关闭。

证据目录 `build/native/issue50-validation/unified-updates-official-20261101/`：`B-before-update.json`、`F-reconnected-result-6034616.png`、`G-coordinated-queued.json`、`H-coordinated-apply-launcher-out.log`、`I-coordinated-acceptance.json`。精简机器可读结果见 [unified-update-window-evidence.json](unified-update-window-evidence.json)。

两次正式验收分别证明新窗口的插件独立更新，以及旧正式版的框架与插件联合升级。未通过伪造版本或 CI 身份制造待更新框架条目，也未热替换加载中的框架、SDK、Graal 或插件文件。
